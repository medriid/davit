//! HTML to data for each VTOP page: the Android parsers, line for line. `tests/parity.rs`
//! holds them to what the Android parsers read from the same fixtures.

use super::html::*;
use super::models::*;
use chrono::{Datelike, NaiveDate, NaiveTime};
use scraper::{ElementRef, Html};
use std::collections::{BTreeMap, HashSet};

fn parse(html: &str) -> Html {
    Html::parse_document(html)
}

fn txt_contains(el: ElementRef, s: &str) -> bool {
    text(el).contains(s)
}

fn ignore_case_eq(a: &str, b: &str) -> bool {
    a.to_lowercase() == b.to_lowercase()
}

fn non_empty(s: &str) -> Option<String> {
    if s.trim().is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

fn after_first<'a>(s: &'a str, d: &str, missing: &'a str) -> &'a str {
    s.split_once(d).map(|x| x.1).unwrap_or(missing)
}

fn before_first<'a>(s: &'a str, d: &str) -> &'a str {
    s.split_once(d).map(|x| x.0).unwrap_or(s)
}

fn plain_number(s: &str) -> Option<f64> {
    clean(s).parse().ok().filter(|n: &f64| n.is_finite())
}

// ---- login ------------------------------------------------------------------------------------

pub fn open_page_csrf(html: &str) -> Option<String> {
    let d = parse(html);
    dfirst(&d, "#stdForm input[name=_csrf]").and_then(|e| non_empty(attr(e, "value")))
}

pub struct LoginPage {
    pub csrf: String,
    pub captcha: String,
}

pub enum PageError {
    Recaptcha,
    Unexpected,
}

pub fn login_page(html: &str) -> Result<LoginPage, PageError> {
    let d = parse(html);
    let img = dfirst(&d, "#captchaBlock img[src^='data:image']")
        .or_else(|| dfirst(&d, "img[src^='data:image']"))
        .map(|e| attr(e, "src").to_string())
        .unwrap_or_default();
    if img.is_empty() && dfirst(&d, "#recaptcha.g-recaptcha, div.g-recaptcha").is_some() {
        return Err(PageError::Recaptcha);
    }
    let csrf = dfirst(&d, "#vtopLoginForm input[name=_csrf]").map(|e| attr(e, "value").to_string()).unwrap_or_default();
    if dfirst(&d, "#vtopLoginForm").is_none() || csrf.trim().is_empty() || (img.is_empty() && dfirst(&d, "#captchaBlock").is_none()) {
        return Err(PageError::Unexpected);
    }
    Ok(LoginPage { csrf, captcha: img })
}

pub fn captcha_block(html: &str) -> Option<String> {
    let d = parse(html);
    dfirst(&d, "img[src^='data:image']").map(|e| attr(e, "src").to_string())
}

#[derive(PartialEq, Debug)]
pub enum LoginOutcome {
    Success,
    WrongCaptcha,
    BadCredentials,
    Unknown,
}

pub fn login_outcome(html: &str, final_url: &str) -> LoginOutcome {
    if final_url.contains("/vtop/content") || html.to_lowercase().contains("authorizedidx") {
        return LoginOutcome::Success;
    }
    let t = doc_text(&parse(html)).to_lowercase();
    if t.contains("invalid captcha") || (t.contains("captcha") && t.contains("invalid")) {
        return LoginOutcome::WrongCaptcha;
    }
    if (t.contains("invalid") && (t.contains("login") || t.contains("credential") || t.contains("user") || t.contains("password"))) || t.contains("bad credentials") {
        return LoginOutcome::BadCredentials;
    }
    LoginOutcome::Unknown
}

pub fn csrf_of(html: &str) -> Option<String> {
    let d = parse(html);
    dfirst(&d, "input[name=_csrf]")
        .and_then(|e| non_empty(attr(e, "value")))
        .or_else(|| dfirst(&d, "meta[name=_csrf]").and_then(|e| non_empty(attr(e, "content"))))
}

pub fn session(html: &str) -> Option<Session> {
    let d = parse(html);
    let id = dfirst(&d, "#authorizedIDX").or_else(|| dfirst(&d, "#authorizedID")).map(|e| attr(e, "value").to_string())?;
    let csrf = csrf_of(html)?;
    if id.trim().is_empty() {
        return None;
    }
    Some(Session { csrf, authorized_id: id })
}

pub fn is_logged_out_page(html: &str, final_url: &str) -> bool {
    let path = after_first(after_first(final_url, "://", final_url), "/", "");
    if path.ends_with("vtop/login") || path.contains("open/page") || path.contains("prelogin") {
        return true;
    }
    if html.len() > 200_000 {
        return false;
    }
    html.contains("id=\"vtopLoginForm\"") || html.contains("id=\"stdForm\"")
}

// ---- semesters and attendance -------------------------------------------------------------------

pub fn semesters(html: &str) -> Vec<Semester> {
    let d = parse(html);
    let Some(s) = dfirst(&d, "#semesterSubId, select[name=semesterSubId]") else { return vec![] };
    all(s, "option")
        .into_iter()
        .filter_map(|o| {
            let id = attr(o, "value").trim().to_string();
            if id.is_empty() {
                None
            } else {
                Some(Semester { id, name: text(o) })
            }
        })
        .collect()
}

pub fn attendance(html: &str) -> Vec<AttendanceRow> {
    let d = parse(html);
    let Some(t) = dfirst(&d, "#AttendanceDetailDataTable") else { return vec![] };
    let call = re(r"callStudentAttendanceDetailDisplay\('([^']*)','([^']*)','([^']*)','([^']*)'\)");
    let digits = re(r"\d+");
    all(t, "tbody tr")
        .into_iter()
        .filter_map(|tr| {
            if first(tr, "td.dataTables_empty").is_some() {
                return None;
            }
            let c = all(tr, "td");
            if c.len() < 8 {
                return None;
            }
            let whole = text(c[2]);
            let parts: Vec<&str> = whole.split(" - ").collect();
            let pct = first(c[7], "span[style]").map(text).unwrap_or_else(|| text(c[7]));
            let link = c.last().and_then(|l| first(*l, "a[onclick]")).map(|a| attr(a, "onclick").to_string()).unwrap_or_default();
            let m = call.captures(&link);
            let g = |i: usize| m.as_ref().map(|m| m[i].trim().to_string()).unwrap_or_default();
            Some(AttendanceRow {
                course_code: parts.first().unwrap_or(&"").trim().to_string(),
                course_name: if parts.len() >= 3 { parts[1..parts.len() - 1].join(" - ") } else { parts.get(1).map(|s| s.to_string()).unwrap_or_else(|| whole.clone()) }.trim().to_string(),
                class_type: if parts.len() >= 3 { parts[parts.len() - 1].trim().to_string() } else { String::new() },
                attended: text(c[5]).parse().unwrap_or(0),
                total: text(c[6]).parse().unwrap_or(0),
                vtop_percent: digits.find(&pct).and_then(|x| x.as_str().parse().ok()),
                class_id: normalize_class_id(m.as_ref().map(|m| &m[3]).unwrap_or("")),
                detail: DetailKey { semester_id: g(1), reg_no: g(2), course_id: g(3), course_type: g(4).to_uppercase() },
            })
        })
        .collect()
}

pub fn timetable(html: &str) -> Vec<TimetableCourse> {
    let d = parse(html);
    let Some(t) = dall(&d, "table").into_iter().find(|t| {
        let x = text(*t);
        x.contains("Class Group") && x.contains("Course Option")
    }) else {
        return vec![];
    };
    let rows = all(t, "tr");
    if rows.len() < 3 {
        return vec![];
    }
    rows[1..rows.len() - 1]
        .iter()
        .filter_map(|row| {
            let c = all(*row, "td");
            if c.len() < 12 {
                return None;
            }
            let status = text(c[11]).to_lowercase();
            let approved = status.contains("approved");
            if !status.starts_with("registered") || (!approved && status.contains(" and ")) {
                return None;
            }
            let slot_venue = text(c[7]);
            let nil = slot_venue.to_uppercase().contains("NIL");
            let ps = all(c[2], "p");
            let head = ps.first().map(|p| text(*p)).unwrap_or_else(|| text(c[2]));
            let head_parts: Vec<&str> = head.split(" - ").collect();
            let class_id = class_id_of(*row, &c);
            if class_id.is_empty() {
                return None;
            }
            let name = head_parts[1..].join(" - ");
            Some(TimetableCourse {
                class_id,
                course_code: head_parts[0].trim().to_string(),
                course_name: if name.trim().is_empty() { head.clone() } else { name.trim().to_string() },
                course_type: ps.get(1).map(|p| text(*p).trim_matches(['(', ')', ' ']).to_string()).unwrap_or_default(),
                credits: text(c[3]).split(' ').next_back().and_then(|x| x.parse().ok()).unwrap_or(0.0),
                slot: if nil { String::new() } else { before_first(&slot_venue, " - ").trim().to_string() },
                venue: if nil { String::new() } else { after_first(&slot_venue, " - ", "").trim().to_string() },
                faculty: before_first(&text(c[8]), " - ").trim().to_string(),
                approved,
            })
        })
        .collect()
}

pub fn attendance_detail(html: &str) -> Vec<AttendanceDay> {
    let d = parse(html);
    let Some(t) = dfirst(&d, "#StudentAttendanceDetailDataTable") else { return vec![] };
    const DAYS: [&str; 7] = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];
    all(t, "tbody tr")
        .into_iter()
        .filter_map(|tr| {
            if first(tr, "td.dataTables_empty").is_some() {
                return None;
            }
            let c = all(tr, "td");
            if c.len() < 5 {
                return None;
            }
            let raw = text(c[4]).to_lowercase();
            let status = if raw.starts_with("present") {
                "Present"
            } else if raw.starts_with("absent") {
                "Absent"
            } else if raw == "od" || raw.contains("duty") {
                "OnDuty"
            } else {
                "Other"
            };
            let order: String = text(c[3]).chars().take(3).collect::<String>().to_uppercase();
            Some(AttendanceDay {
                date: text(c[1]),
                slot: text(c[2]),
                status: status.to_string(),
                day_order: DAYS.iter().position(|x| *x == order).map(|i| i as i32 + 1),
            })
        })
        .collect()
}

// ---- academic calendar ------------------------------------------------------------------------

pub fn class_groups(html: &str) -> Vec<ClassGroup> {
    let d = parse(html);
    dall(&d, "select#classGroupId option")
        .into_iter()
        .filter_map(|o| {
            let id = attr(o, "value").trim().to_string();
            if id.is_empty() {
                None
            } else {
                Some(ClassGroup { id, name: text(o) })
            }
        })
        .collect()
}

pub fn calendar_months(html: &str) -> Vec<String> {
    let d = parse(html);
    let calls: Vec<String> = dall(&d, "[onclick]").into_iter().map(|e| attr(e, "onclick").to_string()).collect();
    let mut seen = HashSet::new();
    re(r"processViewCalendar\('([^']+)'\)")
        .captures_iter(&calls.join(" "))
        .map(|c| c[1].to_string())
        .filter(|m| seen.insert(m.clone()))
        .collect()
}

pub fn calendar_month(html: &str, cal_date: &str) -> Vec<CalendarCell> {
    let d = parse(html);
    let Some(t) = dall(&d, "table.calendar-table").into_iter().next_back() else { return vec![] };
    let m = after_first(cal_date.trim(), "-", cal_date.trim()).to_string();
    let Ok(start) = NaiveDate::parse_from_str(&format!("01-{m}"), "%d-%b-%Y") else { return vec![] };
    let next = if start.month() == 12 { NaiveDate::from_ymd_opt(start.year() + 1, 1, 1) } else { NaiveDate::from_ymd_opt(start.year(), start.month() + 1, 1) };
    let last = next.and_then(|n| n.pred_opt()).map(|l| l.day()).unwrap_or(31);
    all(t, "td")
        .into_iter()
        .filter_map(|td| {
            let spans = all(td, "span");
            let day: u32 = text(*spans.first()?).parse().ok()?;
            if !(1..=last).contains(&day) {
                return None;
            }
            let label = spans.iter().skip(1).map(|s| text(*s)).find(|s| !s.is_empty()).unwrap_or_default();
            let note = spans
                .iter()
                .skip(2)
                .map(|s| {
                    let t = text(*s);
                    t.strip_prefix('(').unwrap_or(&t).strip_suffix(')').unwrap_or(t.strip_prefix('(').unwrap_or(&t)).trim().to_string()
                })
                .filter(|s| !s.is_empty())
                .collect::<Vec<_>>()
                .join(" | ");
            Some(CalendarCell { date: start.with_day(day)?.to_string(), label, note })
        })
        .collect()
}

// ---- academics ----------------------------------------------------------------------------------

pub fn is_sheet_leftover(remark: &str) -> bool {
    re(r"^=.*$|^[A-Za-z]+\([\w:,;+\-*/().$]*[A-Za-z]{1,3}\$?\d+[\w:,;+\-*/().$]*\)$|^[A-Za-z]{1,3}\d+:[A-Za-z]{1,3}\d+$").is_match(&remark.replace(", ", ","))
        || re(r"(?i)^#(REF!|VALUE!|N/A|DIV/0!|NAME\?|NUM!|NULL!)$").is_match(remark)
}

pub fn marks(html: &str) -> Vec<CourseMarks> {
    let d = parse(html);
    let Some(t) = dfirst(&d, "#fixedTableContainer table.customTable") else { return vec![] };
    let rows = all(t, "tr.tableContent");
    let mut out = vec![];
    let mut i = 0;
    while i + 1 < rows.len() {
        let course = all(rows[i], "td");
        let detail = rows[i + 1];
        i += 2;
        if course.len() < 9 {
            continue;
        }
        let items = first(detail, "table.customTable-level1")
            .map(|t| all(t, "tr.tableContent-level1"))
            .unwrap_or_default()
            .into_iter()
            .filter_map(|tr| {
                let c = all(tr, "td");
                if c.len() < 8 {
                    return None;
                }
                let remark = text(c[7]);
                Some(MarkItem {
                    title: text(c[1]),
                    max: plain_number(&text(c[2])),
                    weightage: plain_number(&text(c[3])),
                    status: text(c[4]),
                    scored: plain_number(&text(c[5])),
                    weighted: plain_number(&text(c[6])),
                    remark: if remark == "-" || is_sheet_leftover(&remark) { String::new() } else { remark },
                })
            })
            .collect();
        out.push(CourseMarks {
            class_id: normalize_class_id(&text(course[1])),
            course_code: text(course[2]),
            course_name: text(course[3]),
            course_type: text(course[4]),
            items,
        });
    }
    out
}

pub fn exams(html: &str) -> Vec<Exam> {
    let d = parse(html);
    let Some(b) = dfirst(&d, "#fixedTableContainer") else { return vec![] };
    let mut kind = String::new();
    all(b, "tr")
        .into_iter()
        .filter_map(|tr| {
            if tr.value().has_class("tableHeader", scraper::CaseSensitivity::CaseSensitive) {
                return None;
            }
            if let Some(h) = first(tr, "td.panelHead-secondary[colspan]") {
                kind = text(h).to_uppercase();
                return None;
            }
            let c = all(tr, "td");
            if c.len() < 13 {
                return None;
            }
            let code = text(c[1]);
            if ignore_case_eq(&code, "Course Code") {
                return None;
            }
            let class_id = normalize_class_id(&text(c[4]));
            if class_id.is_empty() {
                return None;
            }
            let dash = |s: String| if s == "-" { String::new() } else { s };
            let location = dash(text(c[11]));
            let seat_no = dash(text(c[12]));
            Some(Exam {
                class_id,
                course_code: code,
                course_name: text(c[2]),
                r#type: kind.clone(),
                date: text(c[6]),
                session: text(c[7]),
                time: text(c[9]),
                venue: text(c[10]),
                seat: if !seat_no.is_empty() && !location.is_empty() { format!("{seat_no} ({location})") } else if seat_no.is_empty() { location } else { seat_no },
            })
        })
        .collect()
}

pub fn assignment_courses(html: &str) -> Vec<AssignmentCourse> {
    let d = parse(html);
    let Some(t) = dall(&d, "table").into_iter().find(|t| {
        let x = text(*t);
        x.contains("Class Nbr") && x.contains("Course Code") && x.contains("Dashboard")
    }) else {
        return vec![];
    };
    let call = re(r"myFunction\('([^']+)'\)");
    all(t, "tr")
        .into_iter()
        .filter_map(|tr| {
            let c = all(tr, "td");
            if c.len() < 7 {
                return None;
            }
            let code = text(c[2]);
            if ignore_case_eq(&code, "Course Code") {
                return None;
            }
            let onclick = first(c[6], "button[onclick]").map(|b| attr(b, "onclick").to_string()).unwrap_or_default();
            let mut class_id = normalize_class_id(&text(c[1]));
            if class_id.is_empty() {
                class_id = normalize_class_id(call.captures(&onclick).map(|m| m.get(1).unwrap().as_str()).unwrap_or(""));
            }
            if class_id.is_empty() {
                return None;
            }
            Some(AssignmentCourse { class_id, course_code: code, course_name: text(c[3]), course_type: text(c[4]) })
        })
        .collect()
}

pub fn assignments(html: &str) -> Vec<Assignment> {
    let d = parse(html);
    let Some(t) = dall(&d, "table").into_iter().find(|t| {
        let x = text(*t);
        x.contains("Weightage") && x.contains("Due Date") && x.contains("Last Updated On")
    }) else {
        return vec![];
    };
    all(t, "tr")
        .into_iter()
        .filter_map(|tr| {
            let c = all(tr, "td");
            if c.len() < 8 {
                return None;
            }
            let title = text(c[1]);
            if title.is_empty() || ignore_case_eq(&title, "Title") {
                return None;
            }
            let question = first(tr, "button[name=downloadQuestion]");
            let mine = first(tr, "button[name=downloadStudentDA]");
            let edit = first(tr, "button[name=editAssignment]");
            let code = [edit.map(|e| attr(e, "data-editcode")), mine.map(|e| attr(e, "data-code")), question.map(|e| attr(e, "data-code")), first(tr, "input[name=code]").map(|e| attr(e, "value"))]
                .into_iter()
                .flatten()
                .map(|s| s.trim().to_string())
                .find(|s| !s.is_empty())
                .unwrap_or_default();
            let updated = text(c[6]);
            Some(Assignment {
                title,
                max: plain_number(&text(c[2])),
                weightage: plain_number(&text(c[3])),
                due: text(c[4]),
                updated: if updated.is_empty() || updated == "-" { None } else { Some(updated) },
                code,
                has_question: question.is_some(),
                has_submission: mine.is_some(),
                can_upload: edit.is_some(),
            })
        })
        .collect()
}

// ---- profile and hostel -------------------------------------------------------------------------

fn after_label(cells: &[ElementRef], label: &str) -> String {
    cells.iter().position(|c| ignore_case_eq(&text(*c), label)).and_then(|i| cells.get(i + 1)).map(|c| text(*c)).unwrap_or_default()
}

pub fn profile(html: &str) -> Profile {
    let d = parse(html);
    let tds = dall(&d, "td");
    let name = after_label(&tds, "STUDENT NAME")
        .split(' ')
        .filter(|w| !w.is_empty())
        .map(|w| {
            let low = w.to_lowercase();
            let mut c = low.chars();
            c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
        })
        .collect::<Vec<_>>()
        .join(" ");
    let photo = dfirst(&d, "img.img.border.border-primary[src^='data:image/']")
        .or_else(|| dfirst(&d, "img[src^='data:image/']"))
        .map(|e| attr(e, "src").to_string())
        .filter(|s| s.len() > 100);
    let hostel = dall(&d, ".accordion-button")
        .into_iter()
        .find(|b| text(*b).to_uppercase().contains("HOSTEL INFORMATION"))
        .and_then(|b| {
            let mut at = Some(b);
            while let Some(e) = at {
                if e.value().has_class("accordion-item", scraper::CaseSensitivity::CaseSensitive) {
                    return first(e, "table");
                }
                at = e.parent().and_then(ElementRef::wrap);
            }
            None
        })
        .and_then(|t| {
            let cells = all(t, "td");
            let info = HostelInfo {
                block: after_label(&cells, "Block Name"),
                room: after_label(&cells, "Room No."),
                bed: after_label(&cells, "Bed Type"),
                mess: after_label(&cells, "Mess Information"),
            };
            if !info.block.is_empty() || !after_label(&cells, "Application Number").is_empty() {
                Some(info)
            } else {
                None
            }
        });
    Profile { name, gender: after_label(&tds, "GENDER"), photo, hostel }
}

fn request_values(tr: ElementRef) -> Vec<String> {
    all(tr, "td").into_iter().filter(|td| first(*td, "input[type=hidden], input[type=button]").is_none()).map(text).collect()
}

pub fn night_slips(html: &str) -> Vec<NightSlip> {
    let d = parse(html);
    let Some(t) = dfirst(&d, "#LateHourStatusTable") else { return vec![] };
    all(t, "tbody tr")
        .into_iter()
        .filter_map(|tr| {
            let v = request_values(tr);
            if v.len() < 8 {
                return None;
            }
            let l = &v[v.len() - 8..];
            let purpose = if !l[1].is_empty() && !l[2].is_empty() { format!("{} - {}", l[1], l[2]) } else if l[2].is_empty() { l[1].clone() } else { l[2].clone() };
            Some(NightSlip { venue: l[0].clone(), purpose, applied_to: l[3].clone(), from: l[4].clone(), to: l[5].clone(), time: l[6].clone(), status: l[7].clone() })
        })
        .collect()
}

pub fn leaves(html: &str, table_id: &str) -> Vec<Leave> {
    let d = parse(html);
    let Some(t) = dfirst(&d, &format!("#{table_id}")) else { return vec![] };
    all(t, "tbody tr")
        .into_iter()
        .filter_map(|tr| {
            let v = request_values(tr);
            if v.len() < 6 {
                return None;
            }
            let l = &v[v.len() - 6..];
            Some(Leave { place: l[0].clone(), reason: l[1].clone(), r#type: l[2].clone(), from: l[3].clone(), to: l[4].clone(), status: l[5].clone() })
        })
        .collect()
}

pub fn faculty(html: &str, emp_id: &str) -> Option<FacultyInfo> {
    let d = parse(html);
    let mut f = BTreeMap::new();
    for tr in dall(&d, "table tr") {
        let c = all(tr, "td");
        if c.len() >= 2 {
            f.insert(text(c[0]).to_uppercase(), text(c[1]));
        }
    }
    let get = |k: &str| f.get(k).cloned().unwrap_or_default();
    let name = get("NAME OF THE FACULTY");
    if name.is_empty() {
        return None;
    }
    let dep = get("NAME OF DEPARTMENT");
    Some(FacultyInfo {
        emp_id: emp_id.to_string(),
        name,
        designation: get("DESIGNATION"),
        department: if ignore_case_eq(&dep, "None") { String::new() } else { dep },
        school: get("SCHOOL / CENTRE NAME"),
        email: get("E-MAIL ID"),
        cabin: get("CABIN NUMBER"),
    })
}

pub fn faculty_search(html: &str) -> Vec<FacultyHit> {
    let d = parse(html);
    let Some(el) = dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Name of the Faculty")) else { return vec![] };
    let t = table(el);
    let (name, designation, school) = (t.find(&["Name"]), t.find(&["Designation"]), t.find(&["School", "Centre"]));
    let call = re(r#"getEmployeeIdNo\(\s*["']?([^"')]+)"#);
    t.elements
        .iter()
        .enumerate()
        .filter_map(|(i, tr)| {
            let b = first(*tr, "[onclick*=getEmployeeIdNo]")?;
            let id = call.captures(attr(b, "onclick")).map(|m| m[1].trim().to_string()).or_else(|| non_empty(attr(b, "id")))?;
            Some(FacultyHit { emp_id: id, name: t.value(i, name), designation: t.value(i, designation), school: t.value(i, school) })
        })
        .collect()
}

fn grade_points(g: &str) -> Option<f64> {
    match g {
        "S" => Some(10.0),
        "A" => Some(9.0),
        "B" => Some(8.0),
        "C" => Some(7.0),
        "D" => Some(6.0),
        "E" => Some(5.0),
        "F" | "N" => Some(0.0),
        _ => None,
    }
}

pub fn grade_history(html: &str) -> GradeHistory {
    let d = parse(html);
    let grades = dall(&d, "table.customTable").into_iter().find(|t| txt_contains(*t, "Effective Grades"));
    let entries: Vec<GradeEntry> = grades
        .map(|g| all(g, "tr.tableContent"))
        .unwrap_or_default()
        .into_iter()
        .filter(|tr| !attr(*tr, "style").replace(' ', "").contains("display:none") && !attr(*tr, "id").starts_with("detailsView_"))
        .filter_map(|tr| {
            let c = all(tr, "td");
            if c.len() < 6 {
                return None;
            }
            let g = text(c[5]);
            Some(GradeEntry {
                course_code: text(c[1]),
                course_title: text(c[2]),
                credits: plain_number(&text(c[4])).unwrap_or(0.0),
                grade: if g.is_empty() || g == "-" { None } else { Some(g) },
                exam_month: c.get(6).map(|x| text(*x)).unwrap_or_default(),
            })
        })
        .collect();

    let mut cgpa = None;
    let mut earned = None;
    'tables: for t in dall(&d, "table") {
        let rows = all(t, "tr");
        for (r, row) in rows.iter().enumerate() {
            let heads: Vec<String> = all(*row, "th, td").into_iter().map(|c| text(c).to_uppercase()).collect();
            let Some(at) = heads.iter().position(|h| h == "CGPA") else { continue };
            if r + 1 >= rows.len() {
                continue;
            }
            let values = all(rows[r + 1], "td");
            cgpa = values.get(at).and_then(|v| plain_number(&text(*v)));
            earned = heads.iter().position(|h| h == "CREDITS EARNED").and_then(|i| values.get(i)).and_then(|v| plain_number(&text(*v)));
            if cgpa.is_some() {
                break 'tables;
            }
        }
    }
    if cgpa.is_none() {
        let graded: Vec<_> = entries.iter().filter(|e| e.credits > 0.0 && e.grade.as_ref().is_some_and(|g| grade_points(&g.to_uppercase()).is_some())).collect();
        let credits: f64 = graded.iter().map(|e| e.credits).sum();
        if credits > 0.0 {
            cgpa = Some(graded.iter().map(|e| e.credits * grade_points(&e.grade.as_ref().unwrap().to_uppercase()).unwrap()).sum::<f64>() / credits);
        }
    }
    let brackets = re(r"\(.*?\)");
    let curriculum = dall(&d, "table.customTable")
        .into_iter()
        .find(|t| txt_contains(*t, "Curriculum Details"))
        .map(|t| all(t, "tr.tableContent, tr.fixedContent"))
        .unwrap_or_default()
        .into_iter()
        .filter_map(|tr| {
            let c = all(tr, "td");
            if c.len() < 3 {
                return None;
            }
            Some(CreditDomain {
                name: brackets.replace_all(&text(c[0]), "").trim().to_string(),
                required: plain_number(&text(c[1]))?,
                earned: plain_number(&text(c[2])).unwrap_or(0.0),
            })
        })
        .collect();
    GradeHistory { cgpa, credits_earned: earned, entries, curriculum }
}

fn class_id_of(row: ElementRef, c: &[ElementRef]) -> String {
    let id = normalize_class_id(&text(c[6]));
    if !id.is_empty() {
        return id;
    }
    for cell in c {
        let id = normalize_class_id(&text(*cell));
        if !id.is_empty() {
            return id;
        }
        let id = normalize_class_id(attr(*cell, "data-classid"));
        if !id.is_empty() {
            return id;
        }
    }
    normalize_class_id(&text(row))
}

/// VTOP class ids look like VL2026270100123: the longest VL+digits run, 8 characters or more.
pub fn normalize_class_id(value: &str) -> String {
    let compact: String = value.to_uppercase().chars().filter(|c| c.is_ascii_uppercase() || c.is_ascii_digit()).collect();
    let mut best = "";
    for m in re("VL[0-9]+").find_iter(&compact) {
        if m.as_str().len() > best.len() {
            best = m.as_str();
        }
    }
    if best.len() >= 8 {
        best.to_string()
    } else {
        String::new()
    }
}

// ---- home page feeds ----------------------------------------------------------------------------

pub fn remarks(html: &str) -> Vec<CourseRemark> {
    let d = parse(html);
    let Some(t) = dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Remark")) else { return vec![] };
    all(t, "tbody tr")
        .into_iter()
        .filter_map(|tr| {
            let tds = direct_tds(tr);
            if tds.len() < 4 {
                return None;
            }
            let spans: Vec<String> = all(tds[0], "span").into_iter().map(text).filter(|s| !s.is_empty()).collect();
            let (code, name) = if spans.len() >= 2 {
                (spans[0].trim_end_matches(['-', ' ']).to_string(), spans[1].clone())
            } else {
                let whole = text(tds[0]);
                (before_first(&whole, "-").trim().to_string(), after_first(&whole, "-", "").trim().to_string())
            };
            if code.is_empty() {
                return None;
            }
            Some(CourseRemark { code, name, r#type: text(tds[1]), attendance: number(&text(tds[2])), remark: text(tds[3]) })
        })
        .collect()
}

pub fn feedbacks(html: &str) -> Vec<FeedbackItem> {
    let d = parse(html);
    let Some(el) = dfirst(&d, "table") else { return vec![] };
    let t = table(el);
    let title = t.find(&["Feedback"]).or(Some(1));
    let category = t.find(&["Category"]).or(Some(2));
    let status = t.find(&["Status"]).or(Some(3));
    (0..t.rows.len())
        .filter_map(|i| {
            let name = t.value(i, title);
            if name.is_empty() {
                return None;
            }
            Some(FeedbackItem { title: name, category: t.value(i, category), done: ignore_case_eq(&t.value(i, status), "Completed") })
        })
        .collect()
}

pub fn events(html: &str) -> Vec<ClubEvent> {
    let d = parse(html);
    let iso = re(r"\d{4}-\d{2}-\d{2}");
    let mut seen = HashSet::new();
    let mut out = vec![];
    for card in dall(&d, ".card") {
        let day: Option<u32> = first(card, ".h5").and_then(|e| text(e).parse().ok());
        let month = first(card, ".subtitle").and_then(|e| NaiveDate::parse_from_str(&format!("01-{}", text(e)), "%d-%b-%Y").ok());
        let card_date = match (day, month) {
            (Some(x), Some(m)) => m.with_day(x).map(|v| v.to_string()),
            _ => None,
        };
        for b in all(card, ".card-body.small") {
            let title = first(b, ".card-title").map(text).unwrap_or_default();
            if title.is_empty() {
                continue;
            }
            let date = iso.find(&text(b)).and_then(|m| NaiveDate::parse_from_str(m.as_str(), "%Y-%m-%d").ok()).map(|v| v.to_string()).or_else(|| card_date.clone());
            let club = first(b, "small").map(text).unwrap_or_default();
            if seen.insert((date.clone(), title.clone(), club.clone())) {
                out.push(ClubEvent { date, title, category: first(b, ".fst-italic").map(text).unwrap_or_default(), club });
            }
        }
    }
    out
}

pub fn spotlight(html: &str) -> Vec<SpotlightItem> {
    let d = parse(html);
    let mut sections = dall(&d, ".spotlightMsgDivs");
    if sections.is_empty() {
        sections = dall(&d, "div[id^=spotlight]").into_iter().filter(|e| e.children().filter_map(ElementRef::wrap).any(|c| c.value().name() == "ul")).collect();
    }
    let call = re(r"vtopDownload\('([^']+)'");
    let mut out = vec![];
    for s in sections {
        let section = first(s, "h5").map(text).unwrap_or_default();
        for a in all(s, "li a") {
            let title = text(a);
            if title.is_empty() {
                continue;
            }
            let download = call.captures(attr(a, "onclick")).map(|m| m[1].to_string());
            let href = Some(attr(a, "href").to_string()).filter(|h| h.starts_with("http://") || h.starts_with("https://"));
            if download.is_none() && href.is_none() {
                continue;
            }
            out.push(SpotlightItem { section: section.clone(), title, link: href, download });
        }
    }
    out
}

// ---- people -------------------------------------------------------------------------------------

fn field(p: &Person, words: &[&str]) -> String {
    p.fields
        .iter()
        .find(|(k, _)| {
            let k = k.to_lowercase();
            words.iter().any(|w| k.contains(w))
        })
        .map(|f| f.1.clone())
        .unwrap_or_default()
}

pub fn proctor(html: &str) -> Option<Person> {
    let d = parse(html);
    let t = dfirst(&d, "table")?;
    let p = Person { role: "Proctor".to_string(), fields: label_values(t) };
    if field(&p, &["name"]).is_empty() {
        None
    } else {
        Some(p)
    }
}

fn own_text(e: ElementRef) -> String {
    clean(&e.children().filter_map(|c| c.value().as_text().map(|t| t.to_string())).collect::<Vec<_>>().join(""))
}

pub fn hod_dean(html: &str) -> Vec<Person> {
    let d = parse(html);
    let everything = dall(&d, "*");
    dall(&d, "table")
        .into_iter()
        .filter_map(|t| {
            let at = everything.iter().position(|e| e.id() == t.id())?;
            let heading = everything[..at]
                .iter()
                .rev()
                .filter(|e| closest(**e, "table").is_none())
                .map(|e| own_text(*e))
                .find(|o| !o.is_empty() && o.chars().count() < 60)
                .unwrap_or_default();
            let mut p = Person { role: if heading.is_empty() { "Contact".to_string() } else { heading }, fields: label_values(t) };
            if field(&p, &["name"]).is_empty() {
                return None;
            }
            let role = p.role.to_lowercase();
            let mail = field(&p, &["mail"]).to_lowercase();
            p.role = if role.contains("dean") {
                "Dean".into()
            } else if role.contains("hod") || role.contains("head") {
                "HOD".into()
            } else if mail.starts_with("dean") {
                "Dean".into()
            } else if mail.starts_with("hod") {
                "HOD".into()
            } else {
                p.role
            };
            Some(p)
        })
        .collect()
}

pub fn messages(html: &str) -> Vec<VtopMessage> {
    let d = parse(html);
    let tables: Vec<_> = dall(&d, "table").into_iter().map(table).filter(|t| !t.rows.is_empty()).collect();
    if !tables.is_empty() {
        let mut out = vec![];
        for t in &tables {
            let from = t.find(&["Faculty", "Sender", "From", "Name"]);
            let course = t.find(&["Course", "Class"]);
            let msg = t.find(&["Message", "Content", "Subject", "Description"]);
            let date = t.find(&["Date", "Expir", "Sent", "Time"]);
            for i in 0..t.rows.len() {
                let mut body = t.value(i, msg);
                if body.is_empty() {
                    body = t.rows[i].iter().rev().max_by_key(|s| s.chars().count()).cloned().unwrap_or_default();
                }
                if !body.is_empty() {
                    out.push(VtopMessage { from: t.value(i, from), course: t.value(i, course), text: body, date: t.value(i, date) });
                }
            }
        }
        return out;
    }
    dall(&d, ".expand")
        .into_iter()
        .filter_map(|e| {
            let whole = text(e);
            let head = whole.strip_prefix('+').unwrap_or(&whole);
            let head = head.strip_prefix('-').unwrap_or(head).trim().to_string();
            let body = e.next_siblings().find_map(ElementRef::wrap).map(text).unwrap_or_default();
            if head.is_empty() && body.is_empty() {
                return None;
            }
            Some(VtopMessage {
                from: before_first(&head, " - ").trim().to_string(),
                course: after_first(&head, " - ", "").trim().to_string(),
                text: if body.is_empty() { head.clone() } else { body },
                date: String::new(),
            })
        })
        .collect()
}

// ---- records ------------------------------------------------------------------------------------

pub fn simple_table(html: &str) -> SimpleTable {
    let d = parse(html);
    dall(&d, "table")
        .into_iter()
        .map(table)
        .find(|t| !t.rows.is_empty())
        .map(|t| SimpleTable { headers: t.headers, rows: t.rows })
        .unwrap_or_default()
}

pub fn receipts(html: &str) -> Vec<Receipt> {
    let d = parse(html);
    let Some(el) = dfirst(&d, "#dataTable").or_else(|| dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Receipt"))) else { return vec![] };
    let t = table(el);
    let (inv, no, date, amount, campus) = (t.find(&["Invoice"]), t.find(&["Receipt"]), t.find(&["Date"]), t.find(&["Amount"]), t.find(&["Campus"]));
    (0..t.rows.len())
        .filter_map(|i| {
            let n = t.value(i, no);
            if n.is_empty() && t.value(i, inv).is_empty() {
                return None;
            }
            Some(Receipt { invoice: t.value(i, inv), number: n, date: t.value(i, date), amount: number(&t.value(i, amount)), campus: t.value(i, campus) })
        })
        .collect()
}

pub fn fee_letters(html: &str) -> Vec<FeeLetter> {
    let d = parse(html);
    let Some(el) = dfirst(&d, "#feesIntimationLetterDataTable").or_else(|| dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Fee"))) else { return vec![] };
    let t = table(el);
    let (year, term, what) = (t.find(&["Year"]), t.find(&["Term"]), t.find(&["Description"]));
    t.elements
        .iter()
        .enumerate()
        .filter_map(|(i, tr)| {
            let batch = first(*tr, "[data-batchid]").map(|e| attr(e, "data-batchid").to_string()).unwrap_or_default();
            if batch.is_empty() {
                return None;
            }
            Some(FeeLetter { year: t.value(i, year), term: t.value(i, term), description: t.value(i, what), batch_id: batch })
        })
        .collect()
}

pub fn library_dues(html: &str) -> Vec<(String, Option<f64>)> {
    let d = parse(html);
    let Some(t) = dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Due")) else { return vec![] };
    label_values(t).into_iter().map(|(k, v)| (k, number(&v))).collect()
}

pub fn registration(html: &str) -> Vec<RegistrationWindow> {
    let d = parse(html);
    dall(&d, "table")
        .into_iter()
        .filter_map(|t| {
            let rows: Vec<Vec<String>> = all(t, "tr").into_iter().map(cells).collect();
            let row = |label: &str| rows.iter().find(|r| r.first().is_some_and(|f| ignore_case_eq(f, label)));
            let at = |label: &str| -> Option<String> {
                let r = row(label)?;
                let day = NaiveDate::parse_from_str(r.get(1)?, "%d-%b-%Y").ok()?;
                let time = r.get(2).and_then(|x| NaiveTime::parse_from_str(x, "%H:%M:%S").ok()).unwrap_or_default();
                let s = day.and_time(time).format("%Y-%m-%dT%H:%M").to_string();
                Some(if time.format("%S").to_string() != "00" { day.and_time(time).format("%Y-%m-%dT%H:%M:%S").to_string() } else { s })
            };
            let (start, end) = (at("Start"), at("End"));
            if start.is_none() && end.is_none() {
                return None;
            }
            let title = rows.iter().find(|r| r.len() == 1 && !r[0].is_empty()).map(|r| r[0].clone()).unwrap_or_default();
            let low = title.to_lowercase();
            let mut c = low.chars();
            let cap = c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default();
            Some(RegistrationWindow {
                title: if cap.is_empty() { "Registration".to_string() } else { cap },
                semester: row("Semester").and_then(|r| r.get(1)).cloned().unwrap_or_default(),
                start,
                end,
            })
        })
        .collect()
}

pub fn curriculum_categories(html: &str) -> Vec<CurriculumCategory> {
    let d = parse(html);
    let call = re(r"categoryOnClick\('([^']+)'\)");
    dall(&d, "[onclick*=categoryOnClick]")
        .into_iter()
        .filter_map(|e| {
            let id = call.captures(attr(e, "onclick"))?[1].to_string();
            let numbers: Vec<Option<f64>> = first(e, ".symbol-label").map(|b| all(b, "span")).unwrap_or_default().into_iter().map(|s| number(&text(s))).collect();
            let name = first(e, ".col-6 span, span.d-block").map(text).unwrap_or_else(|| {
                let w = text(e);
                w.rsplit(' ').next().unwrap_or("").to_string()
            });
            Some(CurriculumCategory { id, name, credits: numbers.first().copied().flatten(), max: numbers.get(1).copied().flatten() })
        })
        .collect()
}

pub fn curriculum_courses(html: &str) -> Vec<CurriculumCourse> {
    let d = parse(html);
    let names: BTreeMap<String, String> = dall(&d, "[data-bs-target]").into_iter().map(|e| (attr(e, "data-bs-target").trim_start_matches('#').to_string(), text(e))).collect();
    let mut panes = dall(&d, "[id^=cat-pane-]");
    if panes.is_empty() {
        panes = dall(&d, "body");
    }
    let mut out = vec![];
    for pane in panes {
        let group = names.get(attr(pane, "id")).cloned().unwrap_or_default();
        for el in all(pane, "table") {
            let t = table(el);
            let (code, title, kind, credit) = (t.find(&["Code"]), t.find(&["Title"]), t.find(&["Type"]), t.find(&["Credit"]));
            let l = t.headers.iter().position(|h| h == "L");
            for (i, tr) in t.elements.iter().enumerate() {
                let c = first(*tr, "[data-coursecode]")
                    .and_then(|e| non_empty(attr(e, "data-coursecode")))
                    .unwrap_or_else(|| before_first(&t.value(i, code), " ").to_string());
                if c.is_empty() {
                    continue;
                }
                let ltpj = l.map(|l| t.rows[i].iter().skip(l).take(4).cloned().collect::<Vec<_>>().join("-")).unwrap_or_default();
                out.push(CurriculumCourse { group: group.clone(), code: c, title: t.value(i, title), r#type: t.value(i, kind), credits: number(&t.value(i, credit)), ltpj });
            }
        }
    }
    out
}

pub fn semester_grades(html: &str, semester_id: &str) -> SemesterGrades {
    // `select` is gone from the text the GPA is read from, as on Android.
    let d = parse(html);
    let tables: Vec<_> = dall(&d, "table").into_iter().map(table).collect();
    let courses = tables
        .iter()
        .find(|t| t.find(&["Grade"]).is_some() && t.find(&["Course", "Title"]).is_some())
        .map(|t| {
            let code = t.find(&["Course Code", "Code"]);
            let title = t.find(&["Course Title", "Title"]);
            let credits = t.find(&["Credit"]);
            let grade = t.headers.iter().rposition(|h| {
                let h = h.to_lowercase();
                h.contains("grade") && !h.contains("type")
            });
            (0..t.rows.len())
                .filter_map(|i| {
                    let c = t.value(i, code);
                    if c.is_empty() || ignore_case_eq(&c, "Total") {
                        return None;
                    }
                    let g = t.value(i, grade);
                    Some(GradeEntry { course_code: c, course_title: t.value(i, title), credits: number(&t.value(i, credits)).unwrap_or(0.0), grade: if g.is_empty() || g == "-" { None } else { Some(g) }, exam_month: String::new() })
                })
                .collect()
        })
        .unwrap_or_default();
    let body = text_without(d.root_element(), &["select"]);
    let gpa = re(r"(?i)GPA\s*[:\-]?\s*(\d+(?:\.\d+)?)").captures(&body).and_then(|m| m[1].parse().ok());
    SemesterGrades { semester_id: semester_id.to_string(), gpa, courses }
}

pub fn mess_change(html: &str) -> Option<MessStatus> {
    let d = parse(html);
    let title = dfirst(&d, ".card-header").map(text).unwrap_or_default();
    let br = re(r"(?i)<br\s*/?>");
    let mut lines: Vec<String> = vec![];
    for h in dall(&d, ".card-body h4, .card-body h3, .card-body h5, .card-body .alert") {
        for piece in br.split(&h.inner_html()) {
            let t = doc_text(&Html::parse_fragment(piece));
            if !t.is_empty() && !lines.contains(&t) {
                lines.push(t);
            }
        }
    }
    if lines.is_empty() {
        if let Some(b) = dfirst(&d, ".card-body").map(text).filter(|s| !s.is_empty()) {
            lines.push(b);
        }
    }
    if title.is_empty() && lines.is_empty() {
        return None;
    }
    let all_text = format!("{} {}", lines.join(" "), title).to_lowercase();
    let open = !all_text.contains("closed") && !dall(&d, ".card-body select, .card-body input[type=radio], .card-body button").is_empty();
    Some(MessStatus { title, lines, open })
}

// ---- course pages -------------------------------------------------------------------------------

pub fn courses(html: &str) -> Vec<CourseOption> {
    let d = parse(html);
    let mut seen = HashSet::new();
    dall(&d, "select#courseId option, select[name=courseId] option")
        .into_iter()
        .filter_map(|o| {
            let id = if attr(o, "data-courseid").is_empty() { attr(o, "value") } else { attr(o, "data-courseid") }.trim().to_string();
            if id.is_empty() {
                return None;
            }
            let whole = text(o);
            let parts: Vec<&str> = whole.split(" - ").map(|p| p.trim()).collect();
            let tail: &[&str] = if parts.last().is_some_and(|l| l.parse::<f64>().is_ok()) { &parts[..parts.len() - 1] } else { &parts };
            let kind = if attr(o, "data-crstype").is_empty() {
                if tail.len() >= 2 { tail[tail.len() - 2].to_string() } else { String::new() }
            } else {
                attr(o, "data-crstype").to_string()
            };
            let name = if tail.len() >= 5 { tail[2..tail.len() - 2].join(" - ") } else { tail.get(2).map(|s| s.to_string()).unwrap_or_default() };
            let semester_id = attr(o, "data-semestr").trim().to_string();
            let kind = kind.trim().to_string();
            if !seen.insert(format!("{semester_id}|{id}|{kind}")) {
                return None;
            }
            Some(CourseOption {
                semester_id,
                r#type: kind,
                semester_name: parts.first().unwrap_or(&"").to_string(),
                code: parts.get(1).map(|s| s.to_string()).unwrap_or_else(|| id.split('_').nth(1).unwrap_or(&id).to_string()),
                name,
                type_name: tail.last().unwrap_or(&"").to_string(),
                course_id: id,
            })
        })
        .collect()
}

fn spans(td: ElementRef) -> Vec<String> {
    let s: Vec<String> = all(td, "span").into_iter().map(text).filter(|s| !s.is_empty()).collect();
    if s.is_empty() {
        vec![text(td)]
    } else {
        s
    }
}

pub fn materials(html: &str) -> CourseMaterials {
    let d = parse(html);
    let co = re(r"(?i)\s*-\s*CO\s*:.*$");
    let date = re(r"^\d{2}-\d{2}-\d{4}$");
    let modules: BTreeMap<String, String> = dall(&d, "table")
        .into_iter()
        .find(|t| first(*t, "tr").is_some_and(|r| text(r).to_lowercase().contains("module")) && txt_contains(*t, "Topic"))
        .map(|t| all(t, "td[rowspan]"))
        .unwrap_or_default()
        .into_iter()
        .filter_map(|td| {
            let t = text(td);
            let no = before_first(&t, " - ").trim().to_string();
            if no.is_empty() || !no.chars().all(|c| c.is_ascii_digit()) {
                return None;
            }
            Some((no, co.replace(after_first(&t, " - ", &t), "").trim().to_string()))
        })
        .collect();
    let Some(t) = dfirst(&d, "#materialTable").or_else(|| dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Material Detail"))) else {
        return CourseMaterials { modules, materials: vec![] };
    };
    let materials = all(t, "tr")
        .into_iter()
        .filter_map(|tr| {
            let file_id = first(tr, "[data-fileid]").map(|e| attr(e, "data-fileid").trim().to_string()).unwrap_or_default();
            if file_id.is_empty() {
                return None;
            }
            let tds = direct_tds(tr);
            if tds.len() < 4 {
                return None;
            }
            let course = spans(tds[1]);
            let detail = spans(tds[2]);
            let by = spans(tds[3]);
            let who = by.first().cloned().unwrap_or_default();
            let up: Vec<&str> = who.split(" - ").map(|x| x.trim()).collect();
            let has_id = up.len() >= 2 && up[0].chars().any(|c| c.is_ascii_digit());
            let names: &[&str] = if has_id { &up[1..] } else { &up };
            let names = if names.len() >= 2 { &names[..names.len() - 1] } else { names };
            Some(Material {
                file_id,
                title: detail.first().cloned().unwrap_or_else(|| text(tds[2])),
                module: detail.iter().skip(1).find(|x| !x.is_empty() && x.chars().all(|c| c.is_ascii_digit())).cloned().unwrap_or_default(),
                kind: detail.iter().rev().find(|x| x.starts_with('(')).map(|x| x.trim_matches(['(', ')', ' ']).to_string()).unwrap_or_default(),
                uploader_id: if has_id { up[0].to_string() } else { String::new() },
                uploader: names.join(" - "),
                school: if up.len() >= 3 { up[up.len() - 1].to_string() } else { String::new() },
                date: by.iter().find(|x| date.is_match(x)).cloned().unwrap_or_default(),
                semester: course.first().cloned().unwrap_or_default(),
            })
        })
        .collect();
    CourseMaterials { modules, materials }
}

// ---- clubs --------------------------------------------------------------------------------------

fn id_in(onclick: &str) -> Option<String> {
    re(r#"\(\s*['"]([^'"]+)['"]"#).captures(onclick).map(|m| m[1].trim().to_string())
}

fn outside_modal(td: ElementRef) -> String {
    text_without(td, &["modal", "button"])
}

pub fn club_page(html: &str) -> ClubEvents {
    let d = parse(html);
    let upcoming = dfirst(&d, "#dataTable1")
        .map(|el| {
            let t = table(el);
            let (club, title, date, time, reg, venue, action) = (t.find(&["Association"]), t.find(&["Title"]), t.find(&["Event Date"]), t.find(&["Time"]), t.find(&["Registration"]), t.find(&["Venue"]), t.find(&["Register"]));
            t.elements
                .iter()
                .enumerate()
                .filter_map(|(i, tr)| {
                    let c = direct_tds(*tr);
                    let button = first(*tr, "[onclick*=doEventRegistraiton]");
                    let id = button.and_then(|b| id_in(attr(b, "onclick"))).or_else(|| first(*tr, "[onclick*=doShowEventdescription]").and_then(|b| id_in(attr(b, "onclick"))))?;
                    Some(ClubListing {
                        id,
                        club: t.value(i, club),
                        title: title.and_then(|x| c.get(x)).map(|x| outside_modal(*x)).unwrap_or_default(),
                        description: first(*tr, ".modal-body").map(text).unwrap_or_default(),
                        dates: t.value(i, date),
                        time: t.value(i, time),
                        venue: t.value(i, venue),
                        registration: t.value(i, reg),
                        can_register: button.is_some(),
                        state: if button.is_some() { String::new() } else { action.and_then(|x| c.get(x)).map(|x| text(*x)).unwrap_or_default() },
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let registered = dfirst(&d, "#dataTable2")
        .map(|el| {
            let t = table(el);
            let (club, title, date, venue, status) = (t.find(&["Association"]), t.find(&["Title"]), t.find(&["Event Date"]), t.find(&["Venue"]), t.find(&["Status"]));
            t.elements
                .iter()
                .enumerate()
                .filter_map(|(i, tr)| {
                    let c = direct_tds(*tr);
                    let part = first(*tr, "[name=downloadBtn1]");
                    let win = first(*tr, "[name=downloadBtn2]");
                    let id = part
                        .and_then(|b| non_empty(attr(b, "data-eventid")))
                        .or_else(|| win.and_then(|b| non_empty(attr(b, "data-eventid"))))
                        .or_else(|| first(*tr, "[onclick*=doShowEventdescription]").and_then(|b| id_in(attr(b, "onclick"))))
                        .or_else(|| first(*tr, "[data-eventid]").map(|b| attr(b, "data-eventid").to_string()))?;
                    Some(RegisteredEvent {
                        id,
                        club: t.value(i, club),
                        title: title.and_then(|x| c.get(x)).map(|x| outside_modal(*x)).unwrap_or_default(),
                        dates: t.value(i, date),
                        venue: t.value(i, venue),
                        status: t.value(i, status),
                        participation: part.is_some(),
                        winner: win.is_some(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let board = dfirst(&d, "#dataTable3")
        .map(|el| {
            let t = table(el);
            let (year, club, position) = (t.find(&["Year"]), t.find(&["Club"]), t.find(&["Position"]));
            t.elements
                .iter()
                .enumerate()
                .filter_map(|(i, tr)| {
                    let b = first(*tr, "[name=downloadBtn][data-associationid]")?;
                    Some(BoardCertificate {
                        year: t.value(i, year),
                        club: t.value(i, club),
                        position: t.value(i, position),
                        association_id: attr(b, "data-associationid").to_string(),
                        generated_year: attr(b, "data-generatedyear").to_string(),
                        role_id: attr(b, "data-roleid").to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    ClubEvents { upcoming, registered, board }
}

pub fn general_certificates(html: &str) -> Vec<GeneralCertificate> {
    let d = parse(html);
    let call = re(r"downloadCertificate\(([^)]*)\)");
    dall(&d, "[onclick*='downloadCertificate(']")
        .into_iter()
        .filter_map(|b| {
            let args: Vec<String> = call.captures(attr(b, "onclick"))?[1].split(',').map(|a| a.trim().trim_matches(['\'', '"', ' ']).to_string()).collect();
            if args.len() < 3 {
                return None;
            }
            let name = closest(b, "tr").and_then(|r| first(r, "td")).map(text).unwrap_or_default();
            Some(GeneralCertificate { name, event_id: args[0].clone(), certificate_id: args[1].clone(), participant_id: args[2].clone() })
        })
        .collect()
}

// ---- the rest of the menu -----------------------------------------------------------------------

pub const WINDOWS: &[(&str, &str, &str)] = &[
    ("transport", "Transport registration", "/transport/transportRegistration"),
    ("internship", "Industrial internship", "/internship/InternshipRegistration"),
    ("mooc", "MOOC registration", "/academics/mooc/studentRegistration"),
    ("exc", "Extra-curricular courses", "/academics/exc/studentRegistration"),
    ("migration", "Programme migration", "/admissions/programmeMigration"),
    ("hostel", "Hostel room booking", "/hostels/online/room/allotment/open"),
    ("convocation", "Convocation", "/convocation/entryPage"),
    ("pat", "PAT registration", "/pat/PatRegistrationProcess"),
];

pub fn circulars(html: &str) -> Vec<Circular> {
    let d = parse(html);
    let call = re(r"viewCertificate\('([^']+)'\)");
    let when = re(r"\[\s*(\d{2}-[A-Za-z]{3}-\d{4})\s*]");
    let mut seen = HashSet::new();
    dall(&d, "a[onclick*=viewCertificate]")
        .into_iter()
        .filter_map(|a| {
            let id = call.captures(attr(a, "onclick"))?[1].to_string();
            if !seen.insert(id.clone()) {
                return None;
            }
            let t = text(a);
            let date = when.captures(&t).and_then(|m| NaiveDate::parse_from_str(&m[1], "%d-%b-%Y").ok()).map(|x| x.to_string()).unwrap_or_default();
            let path: Vec<String> = a
                .ancestors()
                .filter_map(ElementRef::wrap)
                .filter(|e| e.value().name() == "li")
                .filter_map(|li| li.children().filter_map(ElementRef::wrap).find(|c| c.value().name() == "span").map(text))
                .collect();
            Some(Circular { id, title: circular_title(&t), date, office: path.last().cloned().unwrap_or_default(), group: path.first().cloned().unwrap_or_default() })
        })
        .collect()
}

pub fn circular_title(t: &str) -> String {
    let s = before_first(t, " [").trim();
    let s = re(r"(?i)\.pdf$").replace(s, "");
    let s = re(r"[_\s]+\d{2}\.\d{2}\.\d{4}$").replace(&s, "");
    let s = re(r"^\d+[_\s]+").replace(&s, "");
    s.replace('_', " ").trim().to_string()
}

pub fn projects(html: &str) -> Vec<FacultyProject> {
    let d = parse(html);
    let Some(el) = dall(&d, "table").into_iter().find(|t| txt_contains(*t, "RESEARCH AREA")) else { return vec![] };
    let t = table(el);
    let (title, area, posted, faculty) = (t.find(&["Title"]), t.find(&["Area"]), t.find(&["Posted"]), t.find(&["Faculty"]));
    (0..t.rows.len())
        .filter_map(|i| {
            let name = t.value(i, title);
            if name.is_empty() {
                return None;
            }
            let id = first(t.elements[i], "[data-id]").map(|e| attr(e, "data-id").to_string()).unwrap_or_default();
            let who = t.value(i, faculty);
            let (person, school) = who.rsplit_once(" - ").map(|(a, b)| (a.trim().to_string(), b.trim().to_string())).unwrap_or((who.trim().to_string(), String::new()));
            Some(FacultyProject {
                id: if id.is_empty() { name.clone() } else { id },
                title: name,
                area: t.value(i, area),
                posted: NaiveDate::parse_from_str(&t.value(i, posted), "%d-%m-%Y").map(|x| x.to_string()).unwrap_or_default(),
                faculty: person,
                school,
            })
        })
        .collect()
}

const CLOSED: &[(&str, &str)] = &[
    ("date is over", "Registration is over"),
    ("date has been closed", "Registration is closed"),
    ("last date for applying", "The last date has passed"),
    ("no active registration", "No registration open"),
    ("not eligible", "You're not eligible"),
    ("not permitted", "Not open to you"),
    ("not registered", "Needs registered courses"),
    ("not enabled", "Not open yet"),
    ("closed", "Closed"),
];

pub fn window(id: &str, title: &str, html: &str) -> VtopWindow {
    let low = doc_text(&parse(html)).to_lowercase();
    let said = CLOSED.iter().filter_map(|(w, n)| low.find(w).map(|at| (at, *n))).min_by_key(|x| x.0);
    VtopWindow { id: id.to_string(), title: title.to_string(), open: said.is_none(), note: said.map(|s| s.1.to_string()).unwrap_or_default() }
}

pub fn sign_ins(html: &str) -> Vec<SignIn> {
    let d = parse(html);
    let Some(el) = dall(&d, "table").into_iter().find(|t| txt_contains(*t, "Ip Address")) else { return vec![] };
    let t = table(el);
    (0..t.rows.len())
        .map(|i| SignIn { activity: t.value(i, t.find(&["Activity"])), time: t.value(i, t.find(&["Date"])), ip: t.value(i, t.find(&["Ip"])), app: t.value(i, t.find(&["App"])) })
        .collect()
}

pub fn sports(html: &str) -> Sports {
    let d = parse(html);
    let tables = dall(&d, "table");
    let list = tables.iter().find(|t| txt_contains(**t, "Facility Name")).copied();
    let facilities = list
        .map(|el| {
            let t = table(el);
            (0..t.rows.len())
                .filter_map(|i| {
                    let name = t.value(i, t.find(&["Facility"]));
                    if name.is_empty() {
                        return None;
                    }
                    Some(Facility { name, fee: t.value(i, t.find(&["Fees"])), seats: t.value(i, t.find(&["seats"])) })
                })
                .collect()
        })
        .unwrap_or_default();
    let mine = tables
        .iter()
        .filter(|t| list.map(|l| l.id()) != Some(t.id()))
        .flat_map(|t| all(*t, "tr"))
        .filter_map(|tr| {
            let c: Vec<String> = direct_tds(tr).into_iter().map(text).collect();
            if c.len() == 2 && c.iter().all(|x| !x.is_empty()) {
                Some(FacilityStatus { name: c[0].clone(), status: c[1].clone() })
            } else {
                None
            }
        })
        .collect();
    Sports { facilities, mine }
}

fn club_rows(el: ElementRef) -> Vec<ClubRow> {
    let t = table(el);
    let (name, year, status) = (t.find(&["Association"]), t.find(&["Year"]), t.find(&["Status"]));
    let kind = re(r"\(([^()]+)\)\s*$");
    (0..t.rows.len())
        .filter_map(|i| {
            let full = t.value(i, name);
            if full.is_empty() {
                return None;
            }
            let k = kind.captures(&full).map(|m| m[1].to_string()).unwrap_or_default();
            let mut state = t.value(i, status);
            if state.is_empty() {
                state = t.rows[i].last().filter(|l| **l != full).cloned().unwrap_or_default();
            }
            Some(ClubRow { name: full.strip_suffix(&format!("({k})")).unwrap_or(&full).trim().to_string(), r#type: k, year: t.value(i, year), status: state })
        })
        .collect()
}

pub fn club_directory(html: &str) -> ClubDirectory {
    let d = parse(html);
    let tables: Vec<_> = dall(&d, "table").into_iter().filter(|t| txt_contains(*t, "Association Name")).collect();
    let mine = tables.iter().find(|t| txt_contains(**t, "Team Name")).copied();
    let other = tables.iter().find(|t| mine.map(|m| m.id()) != Some(t.id())).copied();
    ClubDirectory { mine: mine.map(club_rows).unwrap_or_default(), all: other.map(club_rows).unwrap_or_default() }
}

pub fn faqs(html: &str, domain: &str, process: &str) -> Vec<FaqItem> {
    let d = parse(html);
    dall(&d, ".panel")
        .into_iter()
        .filter_map(|p| {
            let question = first(p, ".panel-title").map(text).unwrap_or_default();
            if question.is_empty() {
                return None;
            }
            Some(FaqItem { domain: domain.to_string(), process: process.to_string(), question, answer: first(p, ".panel-collapse").map(text).unwrap_or_default() })
        })
        .collect()
}

pub fn options(html: &str, select: &str) -> Vec<(String, String)> {
    let d = parse(html);
    let Some(el) = dfirst(&d, &format!("select[name={select}]")).or_else(|| dfirst(&d, &format!("#{select}"))) else { return vec![] };
    all(el, "option")
        .into_iter()
        .filter_map(|o| {
            let v = attr(o, "value").trim().to_string();
            if v.is_empty() {
                None
            } else {
                Some((v, text(o)))
            }
        })
        .collect()
}

pub fn malpractice(html: &str) -> Vec<MalpracticeRule> {
    let d = parse(html);
    let mut out = vec![];
    for (n, el) in dall(&d, "table").into_iter().filter(|t| txt_contains(*t, "Nature of Malpractice")).enumerate() {
        let t = table(el);
        for i in 0..t.rows.len() {
            let nature = t.value(i, t.find(&["Nature"]));
            if !nature.is_empty() {
                out.push(MalpracticeRule { exam: if n == 0 { "CAT and MTT" } else { "FAT" }.to_string(), nature, action: t.value(i, t.find(&["Action"])) });
            }
        }
    }
    out
}

pub fn regulations_pdf(html: &str) -> Option<Vec<u8>> {
    use base64::Engine;
    let marker = "data:application/pdf;base64,";
    let from = html.find(marker)? + marker.len();
    let end = html[from..].find(|c: char| !(c.is_ascii_alphanumeric() || c == '+' || c == '/' || c == '=')).map(|e| from + e).unwrap_or(html.len());
    base64::engine::general_purpose::STANDARD.decode(&html[from..end]).ok().filter(|b| !b.is_empty())
}

pub fn certificates(html: &str, source: &str, function: &str, names: &[&str], send: &[&str], menu: &str, title: fn(&[String]) -> (String, String)) -> Vec<Certificate> {
    let d = parse(html);
    let path = dfirst(&d, "#downloadForm").map(|f| attr(f, "action").trim_matches('/').to_string()).unwrap_or_default();
    if path.is_empty() {
        return vec![];
    }
    let args = re(r"'([^']*)'");
    dall(&d, "tr")
        .into_iter()
        .filter_map(|tr| {
            let call = first(tr, &format!("[onclick*={function}]"))?;
            let a: Vec<String> = args.captures_iter(attr(call, "onclick")).map(|m| m[1].to_string()).collect();
            if a.len() != names.len() {
                return None;
            }
            let c: Vec<String> = direct_tds(tr).into_iter().map(text).collect();
            let (name, detail) = title(&c);
            let params = names.iter().zip(a).filter(|(n, _)| send.contains(n)).map(|(n, v)| (n.to_string(), v)).collect();
            Some(Certificate { source: source.to_string(), title: name, detail, path: format!("/{path}"), params, menu: menu.to_string() })
        })
        .collect()
}

pub fn file_name_of(disposition: Option<&str>) -> Option<String> {
    let d = disposition?;
    let name = re(r"(?i)filename\*\s*=\s*[^']*''([^;]+)")
        .captures(d)
        .and_then(|m| percent_encoding::percent_decode_str(m[1].trim()).decode_utf8().ok().map(|s| s.to_string()))
        .or_else(|| re(r#"(?i)filename\s*=\s*("([^"]*)"|[^;]+)"#).captures(d).map(|m| m.get(2).map(|x| x.as_str().to_string()).unwrap_or_else(|| m[1].trim().trim_matches('"').to_string())))?;
    let base = name.rsplit('/').next()?.rsplit('\\').next()?.trim().to_string();
    if base.is_empty() || base == "." || base == ".." {
        None
    } else {
        Some(base)
    }
}
