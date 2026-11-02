//! The sync, in the Android app's order and with its rules: read what rarely changes every few
//! hours, the day-by-day history only for courses whose count moved, and keep the last good
//! copy of anything that fails.

use crate::vtop::client::{Res, Vtop, VtopError};
use crate::vtop::models::*;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

const HOUR: i64 = 3600;
const COOL: i64 = 6 * HOUR;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Calendar {
    pub semester_id: String,
    pub group: String,
    pub cells: Vec<CalendarCell>,
    pub at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Feeds {
    pub remarks: Vec<CourseRemark>,
    pub feedback: Vec<FeedbackItem>,
    pub events: Vec<ClubEvent>,
    pub proctor_note: Option<String>,
    pub registration: Vec<RegistrationWindow>,
    pub daily_at: i64,
    pub circulars: Vec<Circular>,
    pub projects: Vec<FacultyProject>,
    pub windows: Vec<VtopWindow>,
    pub watch_at: i64,
    pub assignment_sig: String,
    pub assignments_at: i64,
    pub timetable_at: i64,
    pub grades_at: i64,
    pub profile_at: i64,
    pub current_id: String,
    pub current_at: i64,
    pub faculty_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Snapshot {
    pub semester: Semester,
    pub semesters: Vec<Semester>,
    pub attendance: Vec<AttendanceRow>,
    pub timetable: Vec<TimetableCourse>,
    pub marks: Vec<CourseMarks>,
    pub exams: Vec<Exam>,
    pub grades: Option<GradeHistory>,
    pub assignments: Vec<CourseAssignments>,
    pub history: BTreeMap<String, Vec<AttendanceDay>>,
    pub profile: Option<Profile>,
    pub calendar: Option<Calendar>,
    pub faculty: Vec<FacultyInfo>,
    pub feeds: Feeds,
    pub synced_at: i64,
}

fn key(r: &AttendanceRow) -> String {
    format!("{}|{}", r.course_code, r.class_type)
}

/// Your current semester: the newest in which a class has been held, not merely the newest
/// listed. Registering for next semester makes VTOP list it, with "-" for every count.
async fn current_semester(v: &mut Vtop, semesters: &[Semester], read: &mut BTreeMap<String, Vec<AttendanceRow>>) -> Res<Semester> {
    let mut registered = None;
    for s in semesters.iter().take(3) {
        let rows = v.attendance(&s.id).await?;
        let held = rows.iter().any(|r| r.total > 0);
        if !rows.is_empty() {
            registered = Some(s.clone());
        }
        read.insert(s.id.clone(), rows);
        if held {
            return Ok(s.clone());
        }
    }
    registered.or_else(|| semesters.first().cloned()).ok_or_else(|| VtopError::Unexpected("VTOP didn't list any semesters.".into()))
}

fn faculty_key(name: &str) -> String {
    let up = name.to_uppercase();
    let no_title = crate::vtop::html::re(r"^(DR|PROF|MR|MRS|MS)\.?\s*").replace(&up, "").to_string();
    let letters: String = no_title.chars().map(|c| if c.is_ascii_uppercase() || c == ' ' { c } else { ' ' }).collect();
    letters.split_whitespace().collect::<Vec<_>>().join(" ")
}

async fn calendar(v: &mut Vtop, sem: &str, history: &BTreeMap<String, Vec<AttendanceDay>>) -> Res<Option<Calendar>> {
    let groups = v.class_groups(sem).await?;
    if groups.is_empty() {
        return Ok(None);
    }
    // Dates that ran another weekday's timetable say which class group's calendar is yours.
    let mut evidence = BTreeMap::new();
    for d in history.values().flatten() {
        if let (Ok(date), Some(order)) = (chrono::NaiveDate::parse_from_str(&d.date, "%d-%m-%Y"), d.day_order) {
            use chrono::Datelike;
            if date.weekday().number_from_monday() as i32 != order {
                evidence.insert(date.to_string(), order);
            }
        }
    }
    let has = |id: &str| groups.iter().any(|g| g.id == id);
    let mut chosen = None;
    let mut cached = BTreeMap::new();
    if !evidence.is_empty() {
        for id in ["ALL", "ALL03", "ALL04", "ALL10", "ALL02", "ALL09", "ALL06"].into_iter().filter(|i| has(i)) {
            let cells = v.calendar(sem, id).await?;
            let agrees = evidence.iter().all(|(date, order)| cells.iter().find(|c| &c.date == date).is_some_and(|c| day_order(c) == Some(*order)));
            cached.insert(id.to_string(), cells);
            if agrees {
                chosen = Some(id.to_string());
                break;
            }
        }
    }
    let id = chosen.unwrap_or_else(|| if has("ALL") { "ALL".to_string() } else { groups[0].id.clone() });
    let cells = match cached.remove(&id) {
        Some(c) => c,
        None => v.calendar(sem, &id).await?,
    };
    if cells.is_empty() {
        return Ok(None);
    }
    let group = groups.iter().find(|g| g.id == id).map(|g| g.name.clone()).unwrap_or_default();
    Ok(Some(Calendar { semester_id: sem.to_string(), group, cells, at: chrono::Utc::now().timestamp() }))
}

fn day_order(c: &CalendarCell) -> Option<i32> {
    use chrono::Datelike;
    if !c.label.to_lowercase().starts_with("instructional") {
        return None;
    }
    let names = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];
    let named = crate::vtop::html::re(r"(?i)(\w+)\s+day\s+order").captures(&c.note).and_then(|m| names.iter().position(|n| *n == m[1].to_lowercase())).map(|i| i as i32 + 1);
    named.or_else(|| chrono::NaiveDate::parse_from_str(&c.date, "%Y-%m-%d").ok().map(|d| d.weekday().number_from_monday() as i32))
}

pub async fn sync(v: &mut Vtop, old: Option<&Snapshot>, everything: bool, idle: bool, progress: &(dyn Fn(&str) + Sync)) -> Res<Snapshot> {
    let now = chrono::Utc::now().timestamp();
    // VTOP has long since dropped a session this idle, so don't spend a request finding out.
    if idle || !v.has_session() {
        progress("Signing in");
        v.login_saved().await?;
    }
    progress("Reading semesters");
    let mut semesters = v.semesters().await?;
    if semesters.is_empty() {
        v.login_saved().await?;
        semesters = v.semesters().await?;
    }
    if semesters.is_empty() {
        return Err(VtopError::Unexpected("VTOP didn't list any semesters.".into()));
    }

    progress("Fetching attendance");
    let mut read = BTreeMap::new();
    let last = old.map(|o| &o.feeds);
    let known = last.filter(|f| now - f.current_at < 24 * HOUR).and_then(|f| semesters.iter().find(|s| s.id == f.current_id)).cloned();
    let (semester, current_at) = match known {
        Some(s) => (s, last.map(|f| f.current_at).unwrap_or(now)),
        None => (current_semester(v, &semesters, &mut read).await?, now),
    };
    let same = old.filter(|o| o.semester.id == semester.id);
    let attendance = match read.remove(&semester.id) {
        Some(r) => r,
        None => v.attendance(&semester.id).await?,
    };

    let mut s = Snapshot {
        semester: semester.clone(),
        semesters,
        attendance,
        timetable: same.map(|o| o.timetable.clone()).unwrap_or_default(),
        marks: same.map(|o| o.marks.clone()).unwrap_or_default(),
        exams: same.map(|o| o.exams.clone()).unwrap_or_default(),
        grades: old.and_then(|o| o.grades.clone()),
        assignments: same.map(|o| o.assignments.clone()).unwrap_or_default(),
        history: same.map(|o| o.history.clone()).unwrap_or_default(),
        profile: old.and_then(|o| o.profile.clone()),
        calendar: same.and_then(|o| o.calendar.clone()),
        faculty: same.map(|o| o.faculty.clone()).unwrap_or_default(),
        feeds: old.map(|o| o.feeds.clone()).unwrap_or_default(),
        synced_at: now,
    };
    s.feeds.current_id = semester.id.clone();
    s.feeds.current_at = current_at;

    if everything || same.is_none() || s.timetable.is_empty() || now - s.feeds.timetable_at >= COOL {
        progress("Fetching timetable");
        s.timetable = v.timetable(&semester.id).await?;
        s.feeds.timetable_at = now;
    }

    // Day-by-day history, only for courses whose count moved: a request or two, not one per course.
    let before: BTreeMap<String, &AttendanceRow> = same.map(|o| o.attendance.iter().map(|r| (key(r), r)).collect()).unwrap_or_default();
    s.history.retain(|k, _| s.attendance.iter().any(|r| &key(r) == k));
    let stale: Vec<AttendanceRow> = s
        .attendance
        .iter()
        .filter(|r| !r.detail.course_id.is_empty() && (!s.history.contains_key(&key(r)) || before.get(&key(r)).is_none_or(|p| p.attended != r.attended || p.total != r.total)))
        .cloned()
        .collect();
    for (i, r) in stale.iter().enumerate() {
        progress(&format!("Attendance history {}/{}", i + 1, stale.len()));
        if let Ok(days) = v.attendance_detail(r, &semester.id).await {
            s.history.insert(key(r), days);
        }
    }

    // One odd page keeps its old data instead of failing the sync.
    progress("Fetching marks");
    if let Ok(m) = v.marks(&semester.id).await {
        s.marks = m;
    }
    progress("Fetching exams");
    if let Ok(e) = v.exams(&semester.id).await {
        s.exams = e;
    }
    progress("Checking assignments");
    let signature = v.assignments_signature().await.ok();
    let settled = same.is_some() && !s.assignments.is_empty() && signature.as_deref() == Some(s.feeds.assignment_sig.as_str()) && now - s.feeds.assignments_at < COOL;
    if !settled {
        progress("Fetching assignments");
        if let Ok(a) = v.assignments(&semester.id).await {
            s.assignments = a;
            if let Some(sig) = signature {
                s.feeds.assignment_sig = sig;
                s.feeds.assignments_at = now;
            }
        }
    }
    if everything || s.grades.is_none() || now - s.feeds.grades_at >= COOL {
        progress("Fetching grades");
        if let Ok(g) = v.grade_history().await {
            s.grades = Some(g);
            s.feeds.grades_at = now;
        }
    }
    if s.profile.is_none() || now - s.feeds.profile_at > 24 * HOUR {
        progress("Fetching profile");
        if let Ok(p) = v.profile().await {
            s.profile = Some(p);
            s.feeds.profile_at = now;
        }
    }

    progress("Reading attendance remarks");
    if let Ok(r) = v.remarks().await {
        s.feeds.remarks = r;
    }
    if everything || now - s.feeds.daily_at > 20 * HOUR {
        progress("Reading feedback, events and registration");
        let f = v.feedback().await;
        let e = v.events().await;
        let n = v.proctor_note().await;
        let r = v.registration().await;
        let complete = f.is_ok() && e.is_ok() && n.is_ok() && r.is_ok();
        if let Ok(x) = f {
            s.feeds.feedback = x;
        }
        if let Ok(x) = e {
            s.feeds.events = x;
        }
        if let Ok(x) = n {
            s.feeds.proctor_note = x;
        }
        if let Ok(x) = r {
            s.feeds.registration = x;
        }
        if complete {
            s.feeds.daily_at = now;
        }
    }
    if now - s.feeds.watch_at > 20 * HOUR {
        progress("Reading circulars and what's open");
        let c = v.circulars().await;
        let p = v.projects().await;
        let w = v.windows().await;
        let complete = c.is_ok() && p.is_ok() && w.is_ok();
        // An empty list is a page that changed shape: the last good list stays, so a later
        // reading doesn't count everything as new.
        if let Some(x) = c.ok().filter(|x| !x.is_empty()) {
            s.feeds.circulars = x;
        }
        if let Some(x) = p.ok().filter(|x| !x.is_empty()) {
            s.feeds.projects = x;
        }
        if let Ok(x) = w {
            for one in x {
                match s.feeds.windows.iter_mut().find(|o| o.id == one.id) {
                    Some(o) => *o = one,
                    None => s.feeds.windows.push(one),
                }
            }
        }
        if complete {
            s.feeds.watch_at = now;
        }
    }
    let stale_calendar = s.calendar.as_ref().is_none_or(|c| c.semester_id != semester.id || now - c.at > 3 * 24 * HOUR);
    if stale_calendar {
        progress("Fetching calendar");
        if let Ok(Some(c)) = calendar(v, &semester.id, &s.history).await {
            s.calendar = Some(c);
        }
    }
    if !s.timetable.is_empty() && (s.faculty.is_empty() || now - s.feeds.faculty_at > 7 * 24 * HOUR) {
        let mut names: Vec<String> = s.timetable.iter().map(|t| t.faculty.clone()).filter(|n| faculty_key(n).len() >= 3).collect();
        names.sort_by_key(|n| faculty_key(n));
        names.dedup_by_key(|n| faculty_key(n));
        let mut cards = vec![];
        for (i, name) in names.iter().enumerate() {
            progress(&format!("Faculty {}/{}", i + 1, names.len()));
            let Ok(hits) = v.search_faculty(name).await else { continue };
            let wanted = faculty_key(name);
            let hit = hits.iter().find(|h| faculty_key(&h.name) == wanted).or(if hits.len() == 1 { hits.first() } else { None });
            if let Some(h) = hit {
                if let Ok(Some(card)) = v.faculty(&h.emp_id).await {
                    cards.push(card);
                }
            }
        }
        if !cards.is_empty() {
            s.faculty = cards;
            s.feeds.faculty_at = now;
        }
    }
    Ok(s)
}

fn few<T>(out: &mut Vec<(String, String)>, items: &[T], one: impl Fn(&T) -> (String, String), many: impl Fn(usize) -> (String, String)) {
    if items.len() <= 3 {
        out.extend(items.iter().map(one));
    } else {
        out.push(many(items.len()));
    }
}

/// What a sync should tell you: nothing on a first sync, nor across a change of semester.
pub fn alerts(old: Option<&Snapshot>, new: &Snapshot) -> Vec<(String, String)> {
    let mut out = vec![];
    let Some(old) = old else { return out };
    if old.semester.id == new.semester.id && !old.marks.is_empty() {
        let seen: Vec<String> = old.marks.iter().flat_map(|c| c.items.iter().filter(|i| i.scored.is_some()).map(move |i| format!("{}|{}", c.class_id, i.title))).collect();
        let fresh: Vec<(String, String)> = new
            .marks
            .iter()
            .flat_map(|c| c.items.iter().filter(|i| i.scored.is_some()).map(move |i| (c, i)))
            .filter(|(c, i)| !seen.contains(&format!("{}|{}", c.class_id, i.title)))
            .map(|(c, i)| (format!("New mark · {}", c.course_name), format!("{}: {}/{}", i.title, i.scored.unwrap_or(0.0), i.max.unwrap_or(0.0))))
            .collect();
        few(&mut out, &fresh, |x| x.clone(), |n| (format!("{n} new marks"), "Open Academics to see them.".to_string()));
    }
    if old.semester.id == new.semester.id {
        let before: BTreeMap<String, &AttendanceRow> = old.attendance.iter().map(|r| (key(r), r)).collect();
        for r in &new.attendance {
            if let Some(p) = before.get(&key(r)) {
                let (risk, was) = (4 * r.attended < 3 * r.total, 4 * p.attended < 3 * p.total);
                if risk && !was {
                    out.push((format!("{} is below 75%", r.course_name), format!("{}/{}. Attend the next {} to get back.", r.attended, r.total, 3 * r.total - 4 * r.attended)));
                }
            }
        }
    }
    if old.feeds.watch_at > 0 {
        if !old.feeds.circulars.is_empty() {
            let fresh: Vec<&Circular> = new.feeds.circulars.iter().filter(|c| !old.feeds.circulars.iter().any(|o| o.id == c.id)).collect();
            few(&mut out, &fresh, |c| ("New circular".to_string(), c.title.clone()), |n| (format!("{n} new circulars"), "Open Circulars to read them.".to_string()));
        }
        for w in &new.feeds.windows {
            if w.open && old.feeds.windows.iter().any(|o| o.id == w.id && !o.open) {
                out.push((format!("{} is open", w.title), "VTOP has opened it. Check the dates and apply there.".to_string()));
            }
        }
    }
    out
}
