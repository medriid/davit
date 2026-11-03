//! Talks to VTOP over plain HTTP. [check] refuses VTOP's money, password and registration pages
//! outright; the few requests that change anything need a [Consent], which only exists once
//! you've pressed a confirm button.

use super::captcha::Solver;
use super::forms::{self, FormPage, LeaveForm, PaperSeeingPage, Reply, UploadForm, UploadOutcome};
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

/// Never, whoever asks: money, passwords, registration and secrets.
const NEVER: &[&str] = &[
    "otpverfication", "otpverification", "paymentgateway", "dopayment", "makepayment", "onlinepayment", "payfee", "wallet", "password", "passwd",
    "courseregistration", "dosturegister", "dostudelete", "generateinvoice", "wishlistreg", "coursewithdraw", "arrearregistration/regulararrearstudentreg",
    "makeupexamstudentreg", "compre/registrationform", "saveregistration", "patregistrationform", "phyfacilityprocessregistration", "enrollclubchapters",
    "deleteenrollment", "dofinalsubmission", "fpp/studentreqbyprojectid", "finance/payments", "paymentsdelete", "getonlinetransfer", "refundrequest",
    "viewstudentcredentials", "backupcode", "bankinfostudent", "changepreferreduser", "save/academic/action", "submit/feedback", "late/hour/student/request/10",
    "processviewecainvoice", "cancleinvoicegenerateeca",
];

/// What changes something on VTOP: only with a [Consent].
const WRITES: &[&str] = &[
    "dodassignmentuploadmethod", "dodassignmentotpupload", "processdigitalassignmentupload", "hostels/student/leave/3", "late/hour/student/request/3",
    "doeventregistraiton", "studentbonafidesave", "saveacademicscoursefeedback", "paperseeingsubmit", "messcomplaint/save",
];

/// Proof that a person asked for a write. Made only by the command behind a confirm button;
/// a sync never has one, so it can't upload or apply.
pub struct Consent {
    pub what: String,
}

impl Consent {
    pub fn given(what: &str) -> Consent {
        Consent { what: what.to_string() }
    }
}

pub fn check(path: &str) -> Res<()> {
    let low = path.to_lowercase();
    if NEVER.iter().chain(WRITES).any(|n| low.contains(n)) {
        Err(VtopError::Blocked)
    } else {
        Ok(())
    }
}

/// [check] for a request made with [Consent]: the writes open up, NEVER doesn't.
pub fn check_write(path: &str, _consent: &Consent) -> Res<()> {
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
    pages: Option<std::path::PathBuf>,
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
        Vtop { http, jar, session: None, solver, creds: None, base: base.trim_end_matches('/').to_string(), pages: None }
    }

    pub fn set_page_folder(&mut self, folder: Option<std::path::PathBuf>) {
        self.pages = folder;
    }

    /// The opt-in debug copy: the last raw page of each kind, with the username and tokens scrubbed.
    fn keep_page(&self, path: &str, html: &str) {
        let Some(dir) = &self.pages else { return };
        let mut text = html.to_string();
        if let Some(s) = &self.session {
            text = text.replace(&s.authorized_id, "USERNAME").replace(&s.csrf, "TOKEN");
        }
        let name: String = path.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
        if std::fs::create_dir_all(dir).is_ok() {
            let _ = std::fs::write(dir.join(format!("{name}.html")), text);
        }
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
        self.keep_page(path, &html);
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

    /// One write, as a form. It goes once: if VTOP has dropped the session it wasn't taken, and
    /// you're told, rather than sent twice.
    async fn write_form(&mut self, consent: &Consent, path: &str, extra: &[(String, String)]) -> Res<String> {
        check_write(path, consent)?;
        let mut form = self.base_form().ok_or(VtopError::SessionExpired)?;
        form.extend(extra.iter().cloned());
        let resp = self.http.post(self.url(path)).form(&form).header(REFERER, self.url("/content")).header("X-Requested-With", "XMLHttpRequest").send().await?;
        let url = resp.url().to_string();
        let html = resp.text().await?;
        if p::is_logged_out_page(&html, &url) {
            return Err(VtopError::SessionExpired);
        }
        if let (Some(t), Some(s)) = (p::csrf_of(&html), self.session.as_mut()) {
            s.csrf = t;
        }
        Ok(html)
    }

    /// A multipart POST, as a page's fetch(FormData) sends it: Origin and Accept, no
    /// X-Requested-With. The page and VTOP's `error` and `message` headers.
    async fn multipart(&mut self, path: &str, form: reqwest::multipart::Form, consent: Option<&Consent>) -> Res<(String, Option<String>, Option<String>)> {
        match consent {
            Some(c) => check_write(path, c)?,
            None => check(path)?,
        }
        let origin = self.base.split("/vtop").next().unwrap_or(&self.base).to_string();
        let resp = self.http.post(self.url(path)).multipart(form).header(REFERER, self.url("/content")).header("Origin", origin).header("Accept", "*/*").send().await?;
        let url = resp.url().to_string();
        let header = |name: &str| resp.headers().get(name).and_then(|v| v.to_str().ok()).map(str::to_string);
        let (error, message) = (header("error"), header("message"));
        let html = resp.text().await?;
        if p::is_logged_out_page(&html, &url) {
            return Err(VtopError::SessionExpired);
        }
        if let (Some(t), Some(s)) = (p::csrf_of(&html), self.session.as_mut()) {
            s.csrf = t;
        }
        Ok((html, error, message))
    }

    /// [fields] as a browser's FormData, with the session's tokens first.
    fn form_data(&self, fields: &[(String, String)]) -> Res<reqwest::multipart::Form> {
        let s = self.session.as_ref().ok_or(VtopError::SessionExpired)?;
        let mut form = reqwest::multipart::Form::new().text("_csrf", s.csrf.clone()).text("authorizedID", s.authorized_id.clone());
        for (k, v) in fields {
            form = form.text(k.clone(), v.clone());
        }
        Ok(form)
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

    pub async fn activity_semesters(&mut self) -> Res<Vec<Semester>> {
        Ok(semesters_of(&self.menu("/academics/common/ExtraCurricular").await?))
    }

    pub async fn activities(&mut self, sem: &str) -> Res<SimpleTable> {
        Ok(p::simple_table(&self.authed("/processViewEca", &[("semesterSubId", sem), ("regNo", "undefined")]).await?))
    }

    pub async fn qcm(&mut self, sem: &str) -> Res<SimpleTable> {
        self.menu("/academics/common/QCMStudentLogin").await?;
        Ok(p::simple_table(&self.authed("/getStudentLoginForQcm", &[("paramReturnId", "getStudentLoginForQcm"), ("semSubId", sem)]).await?))
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
            "boardCertificate" => {
                self.menu("/event/swf/loadEventRegistration").await?;
                self.download("/events/swf/boardMembersCertificate/doDownloadCertificate", &[("associationId", g("associationId")), ("generatedYear", g("generatedYear")), ("roleId", g("roleId"))], false, target).await
            }
            "generalCertificate" => {
                self.menu("/events/swf/certificateDownload").await?;
                self.download("/events/downloadCertificate", &[("eventId", g("eventId")), ("certificateId", g("certificateId")), ("participantId", g("participantId"))], false, target).await
            }
            _ => Err(VtopError::Blocked),
        }
    }
}

fn semesters_of(html: &str) -> Vec<Semester> {
    let mut o = p::options(html, "semesterSubId");
    if o.is_empty() {
        o = p::options(html, "semSubId");
    }
    o.into_iter().map(|(id, name)| Semester { id, name: name.trim_end_matches(" - VLR").trim().to_string() }).collect()
}

fn refused(message: impl Into<String>) -> VtopError {
    VtopError::Unexpected(message.into())
}

/// What goes to VTOP from each confirm button, step for step as VTOP's own page does it, each
/// after a fresh read so a closed form or a changed one is refused rather than guessed at.
impl Vtop {
    pub async fn course_assignments(&mut self, class_id: &str) -> Res<Vec<Assignment>> {
        Ok(p::assignments(&self.authed("/examinations/processDigitalAssignment", &[("classId", class_id)]).await?))
    }

    pub async fn open_upload(&mut self, consent: &Consent, semester: Option<&str>, class_id: &str, code: &str) -> Res<UploadForm> {
        if let Some(sem) = semester {
            self.authed("/examinations/doDigitalAssignment", &[("semesterSubId", sem)]).await?;
        }
        self.authed("/examinations/processDigitalAssignment", &[("classId", class_id)]).await?;
        let extra = [("classId".to_string(), class_id.to_string()), ("mode".to_string(), code.to_string())];
        let html = self.write_form(consent, "/examinations/processDigitalAssignmentUpload", &extra).await?;
        forms::upload_form(&html, class_id, code).map_err(refused)
    }

    pub async fn upload(&mut self, consent: &Consent, form: &UploadForm, name: &str, mime: &str, bytes: Vec<u8>) -> Res<UploadOutcome> {
        let csrf = self.session.as_ref().ok_or(VtopError::SessionExpired)?.csrf.clone();
        let mut body = reqwest::multipart::Form::new();
        let mut file = Some(reqwest::multipart::Part::bytes(bytes).file_name(name.to_string()).mime_str(if mime.is_empty() { "application/octet-stream" } else { mime }).map_err(|_| refused("That file's type isn't one VTOP takes."))?);
        for (i, (k, v)) in form.fields.iter().enumerate() {
            if i == form.file_at {
                body = body.part(form.file_field.clone(), file.take().expect("once"));
            }
            body = body.text(k.clone(), if k == "_csrf" { csrf.clone() } else { v.clone() });
        }
        if let Some(f) = file {
            body = body.part(form.file_field.clone(), f);
        }
        let (html, error, message) = self.multipart("/examinations/doDAssignmentUploadMethod", body, Some(consent)).await?;
        Ok(forms::upload_outcome(&html, &form.code, error.as_deref(), message.as_deref()))
    }

    pub async fn submit_upload_otp(&mut self, consent: &Consent, fields: &[(String, String)], otp_field: &str, otp: &str, code: &str) -> Res<UploadOutcome> {
        let s = self.session.as_ref().ok_or(VtopError::SessionExpired)?;
        let (csrf, id) = (s.csrf.clone(), s.authorized_id.clone());
        let mut body = reqwest::multipart::Form::new();
        for (k, v) in fields {
            body = body.text(k.clone(), if k == "_csrf" { csrf.clone() } else { v.clone() });
        }
        if !fields.iter().any(|f| f.0 == "authorizedID") {
            body = body.text("authorizedID", id);
        }
        body = body.text(otp_field.to_string(), otp.trim().to_string());
        let (html, error, message) = self.multipart("/examinations/doDAssignmentOtpUpload", body, Some(consent)).await?;
        Ok(forms::upload_outcome(&html, code, error.as_deref(), message.as_deref()))
    }

    /// Leaves an upload or an OTP page the way VTOP's Cancel does: by reopening the course.
    pub async fn cancel_upload(&mut self, class_id: &str) {
        let _ = self.authed("/examinations/processDigitalAssignment", &[("classId", class_id)]).await;
    }

    pub async fn leave_form(&mut self) -> Res<Option<LeaveForm>> {
        self.menu("/hostels/student/leave/1").await?;
        Ok(forms::leave_form(&self.authed("/hostels/student/leave/2", &[("apply", ""), ("form", "undefined"), ("control", "apply")]).await?))
    }

    /// Dates as yyyy-MM-dd and times as HH:mm, as the page's date inputs send them.
    pub async fn apply_leave(&mut self, consent: &Consent, values: &[(String, String)]) -> Res<Reply> {
        let form = self.leave_form().await?.ok_or_else(|| refused("VTOP's leave form closed."))?;
        let mut fields: Vec<(String, String)> = form.fields.into_iter().filter(|f| f.0 != "_csrf" && f.0 != "authorizedID" && !values.iter().any(|v| v.0 == f.0)).collect();
        fields.extend(values.iter().cloned());
        fields.push(("form".into(), "LeaveRequestForm".into()));
        fields.push(("control".into(), "submitControl2".into()));
        Ok(forms::leave_outcome(&self.write_form(consent, "/hostels/student/leave/3", &fields).await?))
    }

    pub async fn night_slip_form(&mut self) -> Res<FormPage> {
        self.menu("/hostels/late/hour/student/request/1").await?;
        Ok(forms::read(&self.authed("/hostels/late/hour/student/request/2", &[("apply", ""), ("form", "undefined"), ("control", "apply")]).await?, None))
    }

    pub async fn apply_night_slip(&mut self, consent: &Consent, values: &BTreeMap<String, String>) -> Res<Reply> {
        const PATH: &str = "/hostels/late/hour/student/request/3";
        let page = self.night_slip_form().await?;
        let Some(form) = page.form else {
            return Err(refused(if page.message.is_empty() { "VTOP isn't taking night slips right now.".to_string() } else { page.message }));
        };
        if form.control.is_empty() || !format!("/{}", form.submit_to.trim_start_matches('/')).ends_with(PATH) {
            return Err(refused("VTOP's night slip form has changed, so daVIT won't send it. Apply on VTOP this time."));
        }
        let mut fields: Vec<(String, String)> = form.hidden.into_iter().filter(|h| h.0 != "_csrf" && h.0 != "authorizedID").collect();
        fields.extend(form.fields.iter().map(|f| (f.name.clone(), values.get(&f.name).cloned().unwrap_or_else(|| f.value.clone()))));
        fields.push(("form".into(), form.id.clone()));
        fields.push(("control".into(), form.control.clone()));
        Ok(forms::reply(&self.write_form(consent, PATH, &fields).await?))
    }

    pub async fn register_event(&mut self, consent: &Consent, event_id: &str) -> Res<Reply> {
        let page = p::club_page(&self.menu("/event/swf/loadEventRegistration").await?);
        let listing = page.upcoming.iter().find(|e| e.id == event_id).ok_or_else(|| refused("VTOP no longer lists this event."))?;
        if !listing.can_register {
            return Err(refused(if listing.state.is_empty() { "VTOP isn't taking registrations for this event.".to_string() } else { listing.state.clone() }));
        }
        Ok(forms::reply(&self.write_form(consent, "/event/swf/registered/doEventRegistraiton", &[("eventId".to_string(), event_id.to_string())]).await?))
    }

    pub async fn feedback_semesters(&mut self) -> Res<Vec<Semester>> {
        let page = forms::read(&self.menu("/academics/common/StudentClassGrievance").await?, Some("courseFeedbackStudentView"));
        Ok(page.form.and_then(|f| f.fields.into_iter().find(|x| x.name == "semesterSubId")).map(|f| f.options.into_iter().map(|(id, name)| Semester { id, name }).collect()).unwrap_or_default())
    }

    pub async fn class_feedback(&mut self, sem: &str) -> Res<Vec<forms::ClassFeedback>> {
        Ok(forms::class_feedback(&self.authed("/academics/common/processViewFeedbackList", &[("semesterSubId", sem)]).await?))
    }

    pub async fn submit_class_feedback(&mut self, consent: &Consent, sem: &str, class_id: &str, words: &str) -> Res<Reply> {
        let words = words.trim();
        if words.is_empty() {
            return Err(refused("Write something first."));
        }
        let classes = self.class_feedback(sem).await?;
        let c = classes.iter().find(|c| c.class_id == class_id).ok_or_else(|| refused("VTOP no longer lists this class."))?;
        if !c.open {
            return Err(refused("VTOP already has your feedback for this class."));
        }
        if words.chars().count() as i32 > c.max_length {
            return Err(refused(format!("VTOP takes up to {} characters.", c.max_length)));
        }
        let extra = [("semesterSubId".to_string(), sem.to_string()), ("classId".to_string(), class_id.to_string()), ("courseFeedbackVal".to_string(), words.to_string())];
        Ok(forms::reply(&self.write_form(consent, "/academics/common/saveAcademicsCourseFeedback", &extra).await?))
    }

    pub async fn paper_seeing_form(&mut self) -> Res<FormPage> {
        Ok(forms::read(&self.menu("/examinations/paperSeeing/PaperSeeing").await?, Some("paperSeeingView")))
    }

    pub async fn paper_seeing_courses(&mut self, sem: &str, service: &str, exam: &str) -> Res<PaperSeeingPage> {
        let base: Vec<(String, String)> = self.paper_seeing_form().await?.form.map(|f| f.hidden).unwrap_or_default().into_iter().filter(|h| h.0 != "_csrf" && h.0 != "authorizedID").collect();
        let mut extra: Vec<(&str, &str)> = base.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
        extra.extend([("semesterSubId", sem), ("serviceType", service), ("examType", exam)]);
        Ok(forms::paper_seeing(&self.authed("/examinations/paperSeeing/PaperSeeingOnChange", &extra).await?))
    }

    /// VTOP raises a fee invoice for it; paying is left to VTOP.
    pub async fn submit_paper_seeing(&mut self, consent: &Consent, sem: &str, service: &str, exam: &str, chosen: &[String]) -> Res<Reply> {
        if chosen.is_empty() {
            return Err(refused("Pick at least one course."));
        }
        let page = self.paper_seeing_courses(sem, service, exam).await?;
        if !chosen.iter().all(|c| page.courses.iter().any(|o| &o.value == c)) {
            return Err(refused(if page.message.is_empty() { "VTOP no longer offers one of those courses.".to_string() } else { page.message }));
        }
        let s = self.session.as_ref().ok_or(VtopError::SessionExpired)?;
        let mut body = reqwest::multipart::Form::new().text("_csrf", s.csrf.clone()).text("authorizedID", s.authorized_id.clone()).text("x", utc_now());
        for (k, v) in page.fields.iter().filter(|f| !["_csrf", "authorizedID", "semesterSubId", "serviceType", "examType"].contains(&f.0.as_str())) {
            body = body.text(k.clone(), v.clone());
        }
        body = body.text("semesterSubId", sem.to_string()).text("serviceType", service.to_string()).text("examType", exam.to_string());
        for c in chosen {
            body = body.text("frontValue", c.clone());
        }
        let (html, _, _) = self.multipart("/examinations/paperSeeing/PaperSeeingSubmit", body, Some(consent)).await?;
        Ok(forms::reply(&html))
    }

    pub async fn bonafide_services(&mut self) -> Res<Vec<(String, String)>> {
        let page = forms::read(&self.menu("/others/bonafide/StudentBonafidePageControl").await?, Some("studentBonafide"));
        let options = page.form.and_then(|f| f.fields.into_iter().find(|x| x.name == "certificateCode")).map(|f| f.options);
        options.ok_or_else(|| refused(if page.message.is_empty() { "VTOP didn't show the bonafide form.".to_string() } else { page.message }))
    }

    /// The fields one kind needs. Dates VTOP fixes for a kind come filled in and locked, as its page does.
    pub async fn bonafide_form(&mut self, code: &str) -> Res<FormPage> {
        let body = self.form_data(&[("certificateCode".to_string(), code.to_string())])?;
        let (html, _, _) = self.multipart("/others/bonafide/StudentBonafideOnChange", body, None).await?;
        let mut page = forms::read(&html, Some("studentBonafide"));
        let fixed_for = super::html::re(r"parseInt\(code\)\s*==\s*(\d+)").captures_iter(&html).any(|m| &m[1] == code);
        let fixed: Vec<(String, String)> = if fixed_for {
            super::html::re(r#"getElementById\("(\w+)"\)\.value\s*=\s*"([^"]*)""#).captures_iter(&html).map(|m| (m[1].to_string(), m[2].to_string())).collect()
        } else {
            vec![]
        };
        if let Some(form) = page.form.as_mut() {
            for f in form.fields.iter_mut() {
                if let Some((_, v)) = fixed.iter().rev().find(|(k, _)| k == &f.name) {
                    f.value = v.clone();
                    f.read_only = true;
                } else if f.name == "description" && f.max_length.is_none() {
                    f.max_length = Some(100);
                }
            }
        }
        Ok(page)
    }

    pub async fn apply_bonafide(&mut self, consent: &Consent, code: &str, values: &BTreeMap<String, String>) -> Res<Reply> {
        let form = self.bonafide_form(code).await?.form.ok_or_else(|| refused("VTOP didn't open the bonafide form."))?;
        if !form.fields.iter().any(|f| f.name == "description") {
            return Err(refused("VTOP's bonafide form has changed, so daVIT won't send it. Apply on VTOP this time."));
        }
        let mut fields: Vec<(String, String)> = form.hidden.into_iter().filter(|h| h.0 != "_csrf" && h.0 != "authorizedID").collect();
        for f in &form.fields {
            let v = if f.name == "certificateCode" { code.to_string() } else if f.read_only { f.value.clone() } else { values.get(&f.name).cloned().unwrap_or_else(|| f.value.clone()) };
            fields.push((f.name.clone(), v));
        }
        let body = self.form_data(&fields)?;
        let (html, _, _) = self.multipart("/others/bonafide/StudentBonafideSave", body, Some(consent)).await?;
        Ok(forms::reply(&html))
    }

    pub async fn mess_complaint_notes(&mut self) -> Res<Vec<String>> {
        forms::mess_complaint_notes(&self.menu("/hostels/messComplaintPage").await?).map_err(refused)
    }

    pub async fn send_mess_complaint(&mut self, consent: &Consent, words: &str, name: &str, mime: &str, bytes: Vec<u8>) -> Res<Reply> {
        let words = words.trim();
        if words.is_empty() {
            return Err(refused("Say what the complaint is."));
        }
        if words.chars().count() > 200 {
            return Err(refused("VTOP takes up to 200 characters."));
        }
        if bytes.is_empty() || bytes.len() > 5 * 1024 * 1024 {
            return Err(refused("The photo or PDF has to be under 5 MB."));
        }
        let ext = name.rsplit('.').next().unwrap_or("").to_lowercase();
        if !["jpg", "jpeg", "png", "pdf"].contains(&ext.as_str()) {
            return Err(refused("VTOP takes JPG, PNG or PDF only."));
        }
        self.mess_complaint_notes().await?;
        let s = self.session.as_ref().ok_or(VtopError::SessionExpired)?;
        let part = reqwest::multipart::Part::bytes(bytes).file_name(name.to_string()).mime_str(if mime.is_empty() { "application/octet-stream" } else { mime }).map_err(|_| refused("VTOP takes JPG, PNG or PDF only."))?;
        let body = reqwest::multipart::Form::new().text("authorizedID", s.authorized_id.clone()).text("complaintData", words.to_string()).part("complaintFile", part).text("_csrf", s.csrf.clone());
        let (html, _, _) = self.multipart("/hostels/messComplaint/save", body, Some(consent)).await?;
        Ok(forms::reply(&html))
    }
}
