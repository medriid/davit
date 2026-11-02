//! Talks to VTOP over plain HTTP. Reads only: the desktop app sends nothing to VTOP that
//! changes anything, and [check] refuses VTOP's money, password and registration pages outright.

use super::captcha::Solver;
use super::html::clean;
use super::models::*;
use super::parsers as p;
use base64::Engine;
use reqwest::header::{HeaderMap, HeaderValue, CONTENT_DISPOSITION, REFERER, USER_AGENT};
use reqwest_cookie_store::{CookieStore, CookieStoreMutex};
use std::collections::BTreeMap;
use std::path::Path;
use std::sync::Arc;
use tokio::io::AsyncWriteExt;

pub const BASE: &str = "https://vtop.vit.ac.in/vtop";
const AGENT: &str = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

#[derive(Debug, thiserror::Error)]
pub enum VtopError {
    #[error("VTOP rejected the username or password.")]
    BadCredentials,
    #[error("VTOP is asking for a Google reCAPTCHA right now. Try again in a little while.")]
    Recaptcha,
    #[error("Couldn't solve VTOP's captcha in {0} tries.")]
    CaptchaFailed(u32),
    #[error("The VTOP session has expired.")]
    SessionExpired,
    #[error("{0}")]
    Unexpected(String),
    #[error("This build has no captcha model, so it can't sign in. See docs/05-captcha-solver.md.")]
    ModelMissing,
    #[error("Couldn't reach VTOP. Check your connection.")]
    Offline,
    #[error("daVIT never opens this VTOP page.")]
    Blocked,
}

pub type Res<T> = Result<T, VtopError>;

impl From<reqwest::Error> for VtopError {
    fn from(_: reqwest::Error) -> Self {
        VtopError::Offline
    }
}

/// Never, whoever asks: money, passwords, registration, secrets, and anything that writes.
const NEVER: &[&str] = &[
    "otpverfication", "otpverification", "paymentgateway", "dopayment", "makepayment", "onlinepayment", "payfee", "wallet", "password", "passwd",
    "courseregistration", "dosturegister", "dostudelete", "generateinvoice", "wishlistreg", "coursewithdraw", "arrearregistration/regulararrearstudentreg",
    "makeupexamstudentreg", "compre/registrationform", "saveregistration", "patregistrationform", "phyfacilityprocessregistration", "enrollclubchapters",
    "deleteenrollment", "dofinalsubmission", "fpp/studentreqbyprojectid", "finance/payments", "paymentsdelete", "getonlinetransfer", "refundrequest",
    "viewstudentcredentials", "backupcode", "bankinfostudent", "changepreferreduser", "save/academic/action", "submit/feedback", "late/hour/student/request/10",
    "processviewecainvoice", "cancleinvoicegenerateeca", "dodassignmentuploadmethod", "dodassignmentotpupload", "processdigitalassignmentupload",
    "hostels/student/leave/3", "late/hour/student/request/3", "doeventregistraiton", "studentbonafidesave", "saveacademicscoursefeedback", "paperseeingsubmit",
    "messcomplaint/save",
];

pub fn check(path: &str) -> Res<()> {
    let low = path.to_lowercase();
    if NEVER.iter().any(|n| low.contains(n)) {
        Err(VtopError::Blocked)
    } else {
        Ok(())
    }
}

fn utc_now() -> String {
    chrono::Utc::now().format("%a, %d %b %Y %H:%M:%S GMT").to_string()
}

pub struct Vtop {
    http: reqwest::Client,
    jar: Arc<CookieStoreMutex>,
    session: Option<Session>,
    solver: Option<Solver>,
    creds: Option<(String, String)>,
    base: String,
}

impl Vtop {
    pub fn new(solver: Option<Solver>, base: &str) -> Vtop {
        let jar = Arc::new(CookieStoreMutex::new(CookieStore::default()));
        let mut headers = HeaderMap::new();
        headers.insert(USER_AGENT, HeaderValue::from_static(AGENT));
        let http = reqwest::Client::builder()
            .cookie_provider(jar.clone())
            .default_headers(headers)
            .connect_timeout(std::time::Duration::from_secs(15))
            .read_timeout(std::time::Duration::from_secs(25))
            .build()
            .expect("the HTTP client");
        Vtop { http, jar, session: None, solver, creds: None, base: base.trim_end_matches('/').to_string() }
    }

    pub fn set_credentials(&mut self, username: String, password: String) {
        self.creds = Some((username, password));
    }

    pub fn has_session(&self) -> bool {
        self.session.is_some()
    }

    fn url(&self, path: &str) -> String {
        format!("{}/{}", self.base, path.trim_start_matches('/'))
    }

    pub fn export_session(&self) -> Option<String> {
        let s = self.session.as_ref()?;
        let mut cookies = vec![];
        self.jar.lock().ok()?.save_incl_expired_and_nonpersistent_json(&mut cookies).ok()?;
        Some(serde_json::json!({ "csrf": s.csrf, "id": s.authorized_id, "cookies": String::from_utf8_lossy(&cookies) }).to_string())
    }

    pub fn restore_session(&mut self, saved: &str) {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(saved) else { return };
        let (Some(csrf), Some(id), Some(cookies)) = (v["csrf"].as_str(), v["id"].as_str(), v["cookies"].as_str()) else { return };
        if let (Ok(store), Ok(mut jar)) = (CookieStore::load_all(cookies.as_bytes(), |c| serde_json::from_str(c)), self.jar.lock()) {
            *jar = store;
            self.session = Some(Session { csrf: csrf.to_string(), authorized_id: id.to_string() });
        }
    }

    pub fn clear(&mut self) {
        self.session = None;
        if let Ok(mut jar) = self.jar.lock() {
            jar.clear();
        }
    }

    // ---- login --------------------------------------------------------------------------------

    fn solve_uri(&self, uri: &str) -> Res<String> {
        if uri.is_empty() {
            return Ok(String::new());
        }
        let solver = self.solver.as_ref().ok_or(VtopError::ModelMissing)?;
        let data = uri.split_once(',').map(|x| x.1).unwrap_or("");
        let bytes = base64::engine::general_purpose::STANDARD.decode(data).unwrap_or_default();
        Ok(solver.solve_image(&bytes).unwrap_or_default())
    }

    async fn walk_to_login(&self) -> Res<Result<p::LoginPage, p::PageError>> {
        let open = self.http.get(self.url("/open/page")).send().await?.text().await?;
        let csrf = p::open_page_csrf(&open).ok_or_else(|| VtopError::Unexpected("Couldn't read VTOP's start page.".into()))?;
        let (html, _) = self.post_raw("/prelogin/setup", &[("_csrf".into(), csrf), ("flag".into(), "VTOP".into())], "/open/page").await?;
        Ok(p::login_page(&html))
    }

    /// VTOP picks the captcha per visit, so when it serves only Google's, walk in again.
    async fn fresh_login_page(&self) -> Res<p::LoginPage> {
        for dodge in 0..=10u64 {
            match self.walk_to_login().await? {
                Ok(page) => return Ok(page),
                Err(p::PageError::Unexpected) => return Err(VtopError::Unexpected("The VTOP login page didn't look as expected.".into())),
                Err(p::PageError::Recaptcha) if dodge < 10 => tokio::time::sleep(std::time::Duration::from_millis(1000 + dodge * 500)).await,
                Err(p::PageError::Recaptcha) => return Err(VtopError::Recaptcha),
            }
        }
        Err(VtopError::Recaptcha)
    }

    /// Retries only on a wrong or unreadable captcha; a rejected password stops at once, so the
    /// account is never at risk of a lockout.
    pub async fn login(&mut self, username: &str, password: &str) -> Res<()> {
        const ATTEMPTS: u32 = 8;
        self.clear();
        if self.solver.is_none() {
            return Err(VtopError::ModelMissing);
        }
        let mut page = self.fresh_login_page().await?;
        for _ in 0..ATTEMPTS {
            let mut guess = self.solve_uri(&page.captcha)?;
            let mut swaps = 0;
            while !Solver::is_confident(&guess) && swaps < 3 {
                let block = self.http.get(self.url("/get/new/captcha")).send().await?.text().await?;
                let Some(uri) = p::captcha_block(&block) else { break };
                page.captcha = uri;
                guess = self.solve_uri(&page.captcha)?;
                swaps += 1;
            }
            if !Solver::is_confident(&guess) {
                page = self.fresh_login_page().await?;
                continue;
            }
            let form = [("_csrf".to_string(), page.csrf.clone()), ("username".into(), username.trim().to_string()), ("password".into(), password.to_string()), ("captchaStr".into(), guess)];
            let (html, url) = self.post_raw("/login", &form, "/login").await?;
            match p::login_outcome(&html, &url) {
                p::LoginOutcome::Success => {
                    self.session = Some(p::session(&html).ok_or_else(|| VtopError::Unexpected("Signed in, but couldn't read the session.".into()))?);
                    return Ok(());
                }
                p::LoginOutcome::BadCredentials => return Err(VtopError::BadCredentials),
                _ => {
                    page = match p::login_page(&html) {
                        Ok(again) => again,
                        Err(_) => self.fresh_login_page().await?,
                    }
                }
            }
        }
        Err(VtopError::CaptchaFailed(ATTEMPTS))
    }

    /// Signs in with the saved credentials.
    pub async fn login_saved(&mut self) -> Res<()> {
        let (u, pw) = self.creds.clone().ok_or(VtopError::SessionExpired)?;
        self.login(&u, &pw).await
    }

    pub async fn logout(&mut self) {
        if let Some(form) = self.base_form() {
            let _ = self.post_raw("/logout", &form, "/content").await;
        }
        self.clear();
    }

    // ---- requests -------------------------------------------------------------------------------

    fn base_form(&self) -> Option<Vec<(String, String)>> {
        let s = self.session.as_ref()?;
        Some(vec![("_csrf".into(), s.csrf.clone()), ("authorizedID".into(), s.authorized_id.clone()), ("x".into(), utc_now())])
    }

    async fn post_raw(&self, path: &str, form: &[(String, String)], referer: &str) -> Res<(String, String)> {
        check(path)?;
        let resp = self.http.post(self.url(path)).form(form).header(REFERER, self.url(referer)).header("X-Requested-With", "XMLHttpRequest").send().await?;
        let url = resp.url().to_string();
        Ok((resp.text().await?, url))
    }

    async fn once(&mut self, path: &str, extra: &[(&str, &str)]) -> Res<String> {
        let mut form = self.base_form().ok_or(VtopError::SessionExpired)?;
        form.extend(extra.iter().map(|(k, v)| (k.to_string(), v.to_string())));
        let (html, url) = self.post_raw(path, &form, "/content").await?;
        if p::is_logged_out_page(&html, &url) {
            return Err(VtopError::SessionExpired);
        }
        if let (Some(t), Some(s)) = (p::csrf_of(&html), self.session.as_mut()) {
            s.csrf = t;
        }
        Ok(html)
    }

    /// An authenticated POST. A session VTOP has dropped gets one fresh sign-in, then one retry.
    pub async fn authed(&mut self, path: &str, extra: &[(&str, &str)]) -> Res<String> {
        if self.session.is_none() {
            self.login_saved().await?;
        }
        match self.once(path, extra).await {
            Err(VtopError::SessionExpired) => {
                self.login_saved().await?;
                self.once(path, extra).await
            }
            other => other,
        }
    }

    async fn menu(&mut self, path: &str) -> Res<String> {
        self.authed(path, &[("verifyMenu", "true")]).await
    }

    /// Streams a file VTOP serves to [target]; the name VTOP gives it, and its type.
    pub async fn download(&mut self, path: &str, form: &[(&str, &str)], get: bool, target: &Path) -> Res<(Option<String>, String)> {
        check(path)?;
        if self.session.is_none() {
            self.login_saved().await?;
        }
        for attempt in 0..2 {
            let mut params = self.base_form().ok_or(VtopError::SessionExpired)?;
            params.extend(form.iter().map(|(k, v)| (k.to_string(), v.to_string())));
            let req = if get { self.http.get(self.url(path)).query(&params) } else { self.http.post(self.url(path)).form(&params) };
            let mut resp = req.header(REFERER, self.url("/content")).send().await?;
            let kind = resp.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("").split(';').next().unwrap_or("").trim().to_string();
            let disposition = resp.headers().get(CONTENT_DISPOSITION).and_then(|v| v.to_str().ok()).map(|s| s.to_string());
            let is_page = kind == "text/html" && !disposition.as_deref().unwrap_or("").to_lowercase().contains("attachment");
            if !resp.status().is_success() || is_page {
                let url = resp.url().to_string();
                let html = resp.text().await.unwrap_or_default();
                if attempt == 0 && p::is_logged_out_page(&html, &url) {
                    self.login_saved().await?;
                    continue;
                }
                return Err(VtopError::Unexpected("VTOP sent a page instead of the file.".into()));
            }
            let mut file = tokio::fs::File::create(target).await.map_err(|_| VtopError::Unexpected("Couldn't write the file.".into()))?;
            while let Some(chunk) = resp.chunk().await? {
                file.write_all(&chunk).await.map_err(|_| VtopError::Unexpected("Couldn't write the file.".into()))?;
            }
            file.flush().await.ok();
            return Ok((p::file_name_of(disposition.as_deref()), kind));
        }
        Err(VtopError::SessionExpired)
    }

    // ---- data -----------------------------------------------------------------------------------

    pub async fn semesters(&mut self) -> Res<Vec<Semester>> {
        Ok(p::semesters(&self.menu("/academics/common/StudentAttendance").await?))
    }

    pub async fn attendance(&mut self, sem: &str) -> Res<Vec<AttendanceRow>> {
        let rows = p::attendance(&self.authed("/processViewStudentAttendance", &[("semesterSubId", sem)]).await?);
        if !rows.is_empty() {
            return Ok(rows);
        }
        Ok(p::attendance(&self.authed("/academics/common/StudentAttendance", &[("semesterSubId", sem), ("verifyMenu", "true")]).await?))
    }

    pub async fn attendance_detail(&mut self, row: &AttendanceRow, sem: &str) -> Res<Vec<AttendanceDay>> {
        let k = &row.detail;
        let s = if k.semester_id.is_empty() { sem } else { &k.semester_id };
        Ok(p::attendance_detail(&self.authed("/processViewAttendanceDetail", &[("semesterSubId", s), ("registerNumber", &k.reg_no), ("courseId", &k.course_id), ("courseType", &k.course_type)]).await?))
    }

    pub async fn timetable(&mut self, sem: &str) -> Res<Vec<TimetableCourse>> {
        Ok(p::timetable(&self.authed("/processViewTimeTable", &[("semesterSubId", sem)]).await?))
    }

    pub async fn marks(&mut self, sem: &str) -> Res<Vec<CourseMarks>> {
        Ok(p::marks(&self.authed("/examinations/doStudentMarkView", &[("semesterSubId", sem)]).await?))
    }

    pub async fn exams(&mut self, sem: &str) -> Res<Vec<Exam>> {
        Ok(p::exams(&self.authed("/examinations/doSearchExamScheduleForStudent", &[("semesterSubId", sem)]).await?))
    }

    pub async fn assignments_signature(&mut self) -> Res<String> {
        let html = self.authed("/get/upcoming/digital/assignments", &[]).await?;
        Ok(clean(&super::html::doc_text(&scraper::Html::parse_document(&html))))
    }

    pub async fn assignments(&mut self, sem: &str) -> Res<Vec<CourseAssignments>> {
        let courses = p::assignment_courses(&self.authed("/examinations/doDigitalAssignment", &[("semesterSubId", sem)]).await?);
        let mut out = vec![];
        for c in courses {
            let items = match self.authed("/examinations/processDigitalAssignment", &[("classId", &c.class_id)]).await {
                Ok(html) => p::assignments(&html),
                Err(VtopError::SessionExpired) => return Err(VtopError::SessionExpired),
                Err(_) => vec![],
            };
            out.push(CourseAssignments { class_id: c.class_id, course_code: c.course_code, course_name: c.course_name, course_type: c.course_type, items });
        }
        Ok(out)
    }

    pub async fn profile(&mut self) -> Res<Profile> {
        Ok(p::profile(&self.menu("/studentsRecord/StudentProfileAllView").await?))
    }

    pub async fn hostel_requests(&mut self) -> Res<HostelRequests> {
        self.menu("/hostels/late/hour/student/request/1").await?;
        let active_slips = p::night_slips(&self.authed("/hostels/late/hour/student/request/9", &[("form", ""), ("control", "status")]).await?);
        let past_slips = p::night_slips(&self.authed("/hostels/late/hour/student/request/11", &[("form", ""), ("control", "history")]).await?);
        self.menu("/hostels/student/leave/1").await?;
        let active_leave = p::leaves(&self.authed("/hostels/student/leave/4", &[("form", ""), ("control", "status")]).await?, "LeaveAppliedTable");
        let past_leave = p::leaves(&self.authed("/hostels/student/leave/6", &[("form", ""), ("control", "history")]).await?, "LeaveHistoryTable");
        Ok(HostelRequests { active_slips, past_slips, active_leave, past_leave })
    }

    pub async fn grade_history(&mut self) -> Res<GradeHistory> {
        Ok(p::grade_history(&self.menu("/examinations/examGradeView/StudentGradeHistory").await?))
    }

    pub async fn semester_grades(&mut self, sem: &str) -> Res<SemesterGrades> {
        self.menu("/examinations/examGradeView/StudentGradeView").await?;
        Ok(p::semester_grades(&self.authed("/examinations/examGradeView/doStudentGradeView", &[("semesterSubId", sem)]).await?, sem))
    }

    pub async fn class_groups(&mut self, sem: &str) -> Res<Vec<ClassGroup>> {
        self.menu("/academics/common/CalendarPreview").await?;
        Ok(p::class_groups(&self.authed("/getDateForSemesterPreview", &[("paramReturnId", "getDateForSemesterPreview"), ("semSubId", sem)]).await?))
    }

    /// The calendar for [group], month by month.
    pub async fn calendar(&mut self, sem: &str, group: &str) -> Res<Vec<CalendarCell>> {
        let months = p::calendar_months(&self.authed("/getListForSemester", &[("paramReturnId", "getListForSemester"), ("semSubId", sem), ("classGroupId", group)]).await?);
        let mut out = vec![];
        for m in months {
            out.extend(p::calendar_month(&self.authed("/processViewCalendar", &[("calDate", &m), ("semSubId", sem), ("classGroupId", group)]).await?, &m));
        }
        Ok(out)
    }

    pub async fn search_faculty(&mut self, name: &str) -> Res<Vec<FacultyHit>> {
        let q = name.trim().to_lowercase();
        if q.chars().count() < 3 {
            return Ok(vec![]);
        }
        self.menu("/hrms/employeeSearchForStudent").await?;
        Ok(p::faculty_search(&self.authed("/hrms/EmployeeSearchForStudent", &[("empId", &q)]).await?))
    }

    pub async fn faculty(&mut self, emp: &str) -> Res<Option<FacultyInfo>> {
        Ok(p::faculty(&self.authed("/hrms/EmployeeSearch1ForStudent", &[("empId", emp)]).await?, emp))
    }

    pub async fn remarks(&mut self) -> Res<Vec<CourseRemark>> {
        Ok(p::remarks(&self.authed("/get/dashboard/current/semester/course/details", &[]).await?))
    }

    pub async fn feedback(&mut self) -> Res<Vec<FeedbackItem>> {
        Ok(p::feedbacks(&self.authed("/get/last/five/feedbacks", &[]).await?))
    }

    pub async fn events(&mut self) -> Res<Vec<ClubEvent>> {
        Ok(p::events(&self.authed("/get/scheduled/events", &[]).await?))
    }

    pub async fn proctor_note(&mut self) -> Res<Option<String>> {
        let html = self.authed("/get/dashboard/proctor/message", &[]).await?;
        let t = super::html::doc_text(&scraper::Html::parse_document(&html));
        Ok(if t.is_empty() || t.to_lowercase().contains("no message") { None } else { Some(t) })
    }

    pub async fn spotlight(&mut self) -> Res<Vec<SpotlightItem>> {
        Ok(p::spotlight(&self.authed("/content", &[]).await?))
    }

    pub async fn registration(&mut self) -> Res<Vec<RegistrationWindow>> {
        Ok(p::registration(&self.menu("/academics/common/studentAllocationView").await?))
    }

    pub async fn proctor(&mut self) -> Res<Option<Person>> {
        Ok(p::proctor(&self.menu("/proctor/viewProctorDetails").await?))
    }

    pub async fn hod_dean(&mut self) -> Res<Vec<Person>> {
        Ok(p::hod_dean(&self.menu("/hrms/viewHodDeanDetails").await?))
    }

    pub async fn class_messages(&mut self) -> Res<Vec<VtopMessage>> {
        Ok(p::messages(&self.menu("/academics/common/StudentClassMessage").await?))
    }

    pub async fn proctor_messages(&mut self) -> Res<Vec<VtopMessage>> {
        Ok(p::messages(&self.menu("/proctor/viewMessagesSendByProctor").await?))
    }

    pub async fn gate(&mut self, days: i64) -> Res<SimpleTable> {
        self.menu("/academics/common/BiometricInfo").await?;
        let from = (chrono::Local::now() - chrono::Duration::days(days)).format("%d-%b-%Y").to_string();
        Ok(p::simple_table(&self.authed("/getStudViewBioList", &[("fromDate", &from)]).await?))
    }

    pub async fn receipts(&mut self) -> Res<Vec<Receipt>> {
        Ok(p::receipts(&self.menu("/finance/getStudentReceipts").await?))
    }

    pub async fn fee_letters(&mut self) -> Res<Vec<FeeLetter>> {
        Ok(p::fee_letters(&self.menu("/finance/getStudentFeesIntimation").await?))
    }

    pub async fn library(&mut self) -> Res<Vec<(String, Option<f64>)>> {
        Ok(p::library_dues(&self.menu("/finance/libraryPayments").await?))
    }

    pub async fn curriculum(&mut self) -> Res<(Vec<CurriculumCategory>, Vec<CurriculumCourse>)> {
        let cats = p::curriculum_categories(&self.menu("/academics/common/Curriculum").await?);
        let mut courses = vec![];
        for c in &cats {
            for mut x in p::curriculum_courses(&self.authed("/academics/common/curriculumCategoryView", &[("categoryId", &c.id)]).await?) {
                if x.group.is_empty() {
                    x.group = c.name.clone();
                }
                courses.push(x);
            }
        }
        Ok((cats, courses))
    }

    pub async fn mess(&mut self) -> Res<Option<MessStatus>> {
        Ok(p::mess_change(&self.menu("/hostels/onlineMessChange").await?))
    }

    pub async fn clubs(&mut self) -> Res<(ClubEvents, Vec<GeneralCertificate>)> {
        let page = p::club_page(&self.menu("/event/swf/loadEventRegistration").await?);
        let general = self.menu("/events/swf/certificateDownload").await.map(|h| p::general_certificates(&h)).unwrap_or_default();
        Ok((page, general))
    }

    pub async fn courses(&mut self) -> Res<Vec<CourseOption>> {
        Ok(p::courses(&self.menu("/academics/common/CoursePageConsolidated").await?))
    }

    pub async fn materials(&mut self, c: &CourseOption) -> Res<CourseMaterials> {
        self.menu("/academics/common/CoursePageConsolidated").await?;
        Ok(p::materials(&self.authed("/academics/CoursePageConsolidated/getCourseDetail", &[("semester", &c.semester_id), ("CourseId", &c.course_id), ("CoursType", &c.r#type)]).await?))
    }

    pub async fn registered_next(&mut self, current: &str) -> Res<Option<(Semester, Vec<TimetableCourse>)>> {
        let mut listed: Vec<Semester> = p::options(&self.menu("/academics/common/StudentTimeTable").await?, "semesterSubId")
            .into_iter()
            .map(|(id, name)| Semester { id, name: name.trim_end_matches(" - VLR").trim().to_string() })
            .collect();
        for c in self.courses().await? {
            if !c.semester_id.is_empty() && !listed.iter().any(|s| s.id == c.semester_id) {
                listed.push(Semester { id: c.semester_id, name: c.semester_name });
            }
        }
        let Some(next) = listed.into_iter().filter(|s| s.id.len() == current.len() && s.id.as_str() > current).max_by(|a, b| a.id.cmp(&b.id)) else { return Ok(None) };
        let t = self.timetable(&next.id).await?;
        Ok(Some((next, t)))
    }

    /// Course allocation: the semester it's for and the curriculum categories.
    pub async fn allocation(&mut self) -> Res<(String, Vec<(String, String)>)> {
        let html = self.menu("/academics/common/StudentRegistrationScheduleAllocation").await?;
        let t = super::html::doc_text(&scraper::Html::parse_document(&html));
        let semester = super::html::re(r"Semester\s*((?:Fall|Winter|Summer|Tri|Inter)[A-Za-z &]*?\d{4}-\d{2})").captures(&t).map(|m| m[1].trim().to_string()).unwrap_or_default();
        Ok((semester, p::options(&html, "curriculumCategory")))
    }

    pub async fn allocation_courses(&mut self, category: &str) -> Res<Vec<(String, String)>> {
        Ok(p::options(&self.authed("/academics/common/getCoursesListForCurriculmCategory", &[("cccategory", category)]).await?, "courseId"))
    }

    pub async fn allocation_classes(&mut self, code: &str) -> Res<SimpleTable> {
        Ok(p::simple_table(&self.authed("/academics/common/getCoursesDetailForRegistration", &[("courseCode", code)]).await?))
    }

    pub async fn circulars(&mut self) -> Res<Vec<Circular>> {
        Ok(p::circulars(&self.menu("/admissions/costCentreCircularsViewPageController").await?))
    }

    pub async fn projects(&mut self) -> Res<Vec<FacultyProject>> {
        Ok(p::projects(&self.menu("/fpp/studentProposal").await?))
    }

    pub async fn windows(&mut self) -> Res<Vec<VtopWindow>> {
        let mut out = vec![];
        for (id, title, path) in p::WINDOWS {
            match self.menu(path).await {
                Ok(html) => out.push(p::window(id, title, &html)),
                Err(VtopError::SessionExpired) => return Err(VtopError::SessionExpired),
                Err(_) => {}
            }
        }
        Ok(out)
    }

    pub async fn sign_ins(&mut self) -> Res<Vec<SignIn>> {
        Ok(p::sign_ins(&self.authed("/show/login/history", &[]).await?))
    }

    pub async fn sports(&mut self) -> Res<Sports> {
        Ok(p::sports(&self.menu("/phyedu/facilityAvailable").await?))
    }

    pub async fn club_directory(&mut self) -> Res<ClubDirectory> {
        Ok(p::club_directory(&self.menu("/event/swf/student/loadClubChapterEnrollmentPage").await?))
    }

    pub async fn malpractice(&mut self) -> Res<Vec<MalpracticeRule>> {
        Ok(p::malpractice(&self.menu("/examinations/malpracticePunishmentDetails").await?))
    }

    /// The first failure ends it with what was read, as a slow page would hold up everything else.
    pub async fn faqs(&mut self) -> Res<Vec<FaqItem>> {
        let mut out = vec![];
        let run: Res<()> = async {
            let domains = p::options(&self.menu("/academics/common/FaqPreview").await?, "domainName");
            for (domain, _) in domains {
                let processes = p::options(&self.authed("/getFaqProcessSec", &[("paramReturnId", "getFaqProcessSec"), ("domainName", &domain), ("process", "")]).await?, "process");
                for (process, _) in processes {
                    out.extend(p::faqs(&self.authed("/processPreviewFaqList", &[("domainName", &domain), ("process", &process)]).await?, &domain, &process));
                }
            }
            Ok(())
        }
        .await;
        if let Err(VtopError::SessionExpired) = run {
            return Err(VtopError::SessionExpired);
        }
        Ok(out)
    }

    pub async fn other_exams(&mut self, sem: &str) -> Res<Vec<ExamTable>> {
        let mut out = vec![];
        self.menu("/examinations/StudExamScheduleForMakeupExam").await.ok();
        let pages = [
            ("Make-up exams", self.authed("/examinations/doSearchExamScheduleForStudentMakeupExam", &[("semesterSubId", sem)]).await),
            ("Arrear exams", self.menu("/examinations/arrearRegistration/viewRARExamSchedule").await),
            ("English proficiency test", self.menu("/compre/eptScheduleShow").await),
            ("Comprehensive exam", self.menu("/compre/studentExamInfo").await),
        ];
        for (title, page) in pages {
            if let Ok(html) = page {
                let table = p::simple_table(&html);
                if !table.rows.is_empty() {
                    out.push(ExamTable { title: title.to_string(), table });
                }
            }
        }
        Ok(out)
    }

    pub async fn certificates(&mut self) -> Res<Vec<Certificate>> {
        const GRAVITAS: &str = "/events/gravitas/student/viewCertificateDetails";
        const UDAY: &str = "/event/uday/certificates";
        const SIX: &str = "/events/sixsigma/loadStudentViewPage";
        let mut out = vec![];
        if let Ok(h) = self.menu(GRAVITAS).await {
            out.extend(p::certificates(&h, "graVITas", "downloadCertificate", &["year", "eventId", "participantId"], &["eventId", "participantId"], GRAVITAS, |c| (c.get(2).cloned().unwrap_or_else(|| "Participant certificate".into()), c.get(1).cloned().unwrap_or_default())));
        }
        if let Ok(h) = self.menu(UDAY).await {
            out.extend(p::certificates(&h, "University Day", "doDownloadIndividualCertificate", &["awardId"], &["awardId"], UDAY, |c| (c.get(2).cloned().unwrap_or_else(|| "Certificate".into()), c.get(1).cloned().unwrap_or_default())));
        }
        if let Ok(h) = self.menu(SIX).await {
            out.extend(p::certificates(&h, "Six Sigma", "downloadCertificate", &["monthYear", "registerNumber"], &["monthYear", "registerNumber"], SIX, |_| ("Six Sigma".into(), String::new())));
        }
        Ok(out)
    }

    /// A file by its kind, as the UI names it. Fixed routes only: the UI can't name a path.
    pub async fn fetch_file(&mut self, kind: &str, a: &BTreeMap<String, String>, target: &Path) -> Res<(Option<String>, String)> {
        let g = |k: &str| a.get(k).map(|s| s.as_str()).unwrap_or("");
        match kind {
            "material" => self.download("/downloadCourseMaterialFacultyPdf", &[("fileId", g("fileId"))], false, target).await,
            "question" => self.download("/examinations/doDownloadQuestion/", &[("code", g("code")), ("classIdNumber", g("classId"))], false, target).await,
            "submission" => self.download("/examinations/downloadSTudentDA/", &[("code1", g("code")), ("classId1", g("classId"))], false, target).await,
            "spotlight" => {
                let path = g("path");
                if !path.starts_with("downloadSpotLightViewFile/") {
                    return Err(VtopError::Blocked);
                }
                self.download(&format!("/{path}"), &[], true, target).await
            }
            "syllabus" => self.download("/courseSyllabusDownload1", &[("courseCode", g("courseCode"))], false, target).await,
            "feeLetter" => self.download("/finance/downloadStudentFeesIntimationLetter", &[("batchId", g("batchId")), ("studentNumber", "")], false, target).await,
            "circular" => {
                self.menu("/admissions/costCentreCircularsViewPageController").await?;
                self.download("/admissions/viewStatusWiseCostCentreCircularContent", &[("val", g("id"))], false, target).await
            }
            "regulations" => {
                let html = self.menu("/academics/council/CouncilRegulationView/new").await?;
                let pdf = p::regulations_pdf(&html).ok_or_else(|| VtopError::Unexpected("VTOP didn't include the regulations.".into()))?;
                tokio::fs::write(target, pdf).await.map_err(|_| VtopError::Unexpected("Couldn't write the file.".into()))?;
                Ok((Some("Academic regulations.pdf".to_string()), "application/pdf".to_string()))
            }
            "certificate" => {
                let (path, menu) = (g("path").to_string(), g("menu").to_string());
                if ![("/events/Gravitas/", "/events/gravitas/"), ("/download/report", "/event/uday/"), ("/events/sixsigma/", "/events/sixsigma/")].iter().any(|(pre, m)| path.starts_with(pre) || menu.starts_with(m)) {
                    return Err(VtopError::Blocked);
                }
                if !menu.is_empty() {
                    self.menu(&menu).await?;
                }
                let params: Vec<(&str, &str)> = a.iter().filter(|(k, _)| k.as_str() != "path" && k.as_str() != "menu").map(|(k, v)| (k.as_str(), v.as_str())).collect();
                self.download(&path, &params, false, target).await
            }
            "eventCertificate" => {
                self.menu("/event/swf/loadEventRegistration").await?;
                let path = if g("winner") == "true" { "/events/swf/doDownloadWinnerCertificate" } else { "/events/swf/doDownloadCertificate" };
                self.download(path, &[("eventId", g("eventId"))], false, target).await
            }
            _ => Err(VtopError::Blocked),
        }
    }
}
