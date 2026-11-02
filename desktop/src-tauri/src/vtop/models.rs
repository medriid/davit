//! What the parsers produce. Field names and order match the Android models, so the parity
//! test can compare them, and the UI reads the same shapes.

use serde::{Deserialize, Serialize};

macro_rules! model {
    ($($(#[$m:meta])* pub struct $name:ident { $(pub $f:ident : $t:ty),* $(,)? })*) => {
        $(
            $(#[$m])*
            #[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
            #[serde(rename_all = "camelCase", default)]
            pub struct $name { $(pub $f: $t),* }
        )*
    };
}

model! {
    pub struct Semester { pub id: String, pub name: String }
    pub struct DetailKey { pub semester_id: String, pub reg_no: String, pub course_id: String, pub course_type: String }
    pub struct AttendanceRow {
        pub course_code: String, pub course_name: String, pub class_type: String, pub attended: i32, pub total: i32,
        pub vtop_percent: Option<i32>, pub class_id: String, pub detail: DetailKey,
    }
    pub struct AttendanceDay { pub date: String, pub slot: String, pub status: String, pub day_order: Option<i32> }
    pub struct TimetableCourse {
        pub class_id: String, pub course_code: String, pub course_name: String, pub course_type: String, pub credits: f64,
        pub slot: String, pub venue: String, pub faculty: String, pub approved: bool,
    }
    pub struct MarkItem {
        pub title: String, pub max: Option<f64>, pub weightage: Option<f64>, pub status: String, pub scored: Option<f64>,
        pub weighted: Option<f64>, pub remark: String,
    }
    pub struct CourseMarks { pub class_id: String, pub course_code: String, pub course_name: String, pub course_type: String, pub items: Vec<MarkItem> }
    pub struct Exam {
        pub class_id: String, pub course_code: String, pub course_name: String, pub r#type: String, pub date: String,
        pub session: String, pub time: String, pub venue: String, pub seat: String,
    }
    pub struct GradeEntry { pub course_code: String, pub course_title: String, pub credits: f64, pub grade: Option<String>, pub exam_month: String }
    pub struct CreditDomain { pub name: String, pub required: f64, pub earned: f64 }
    pub struct GradeHistory { pub cgpa: Option<f64>, pub credits_earned: Option<f64>, pub entries: Vec<GradeEntry>, pub curriculum: Vec<CreditDomain> }
    pub struct AssignmentCourse { pub class_id: String, pub course_code: String, pub course_name: String, pub course_type: String }
    pub struct Assignment {
        pub title: String, pub max: Option<f64>, pub weightage: Option<f64>, pub due: String, pub updated: Option<String>,
        pub code: String, pub has_question: bool, pub has_submission: bool, pub can_upload: bool,
    }
    pub struct CourseAssignments { pub class_id: String, pub course_code: String, pub course_name: String, pub course_type: String, pub items: Vec<Assignment> }
    pub struct HostelInfo { pub block: String, pub room: String, pub bed: String, pub mess: String }
    pub struct Profile { pub name: String, pub gender: String, pub photo: Option<String>, pub hostel: Option<HostelInfo> }
    pub struct NightSlip { pub venue: String, pub purpose: String, pub applied_to: String, pub from: String, pub to: String, pub time: String, pub status: String }
    pub struct Leave { pub place: String, pub reason: String, pub r#type: String, pub from: String, pub to: String, pub status: String }
    pub struct HostelRequests { pub active_slips: Vec<NightSlip>, pub past_slips: Vec<NightSlip>, pub active_leave: Vec<Leave>, pub past_leave: Vec<Leave> }
    pub struct FacultyInfo { pub emp_id: String, pub name: String, pub designation: String, pub department: String, pub school: String, pub email: String, pub cabin: String }
    pub struct FacultyHit { pub emp_id: String, pub name: String, pub designation: String, pub school: String }
    pub struct ClassGroup { pub id: String, pub name: String }
    pub struct CalendarCell { pub date: String, pub label: String, pub note: String }
    pub struct CourseOption {
        pub course_id: String, pub semester_id: String, pub r#type: String, pub semester_name: String, pub code: String,
        pub name: String, pub type_name: String,
    }
    pub struct Material {
        pub file_id: String, pub title: String, pub module: String, pub kind: String, pub uploader_id: String,
        pub uploader: String, pub school: String, pub date: String, pub semester: String,
    }
    pub struct CourseMaterials { pub modules: std::collections::BTreeMap<String, String>, pub materials: Vec<Material> }
    pub struct CourseRemark { pub code: String, pub name: String, pub r#type: String, pub attendance: Option<f64>, pub remark: String }
    pub struct FeedbackItem { pub title: String, pub category: String, pub done: bool }
    pub struct ClubEvent { pub date: Option<String>, pub title: String, pub category: String, pub club: String }
    pub struct SpotlightItem { pub section: String, pub title: String, pub link: Option<String>, pub download: Option<String> }
    pub struct Person { pub role: String, pub fields: Vec<(String, String)> }
    pub struct VtopMessage { pub from: String, pub course: String, pub text: String, pub date: String }
    pub struct SimpleTable { pub headers: Vec<String>, pub rows: Vec<Vec<String>> }
    pub struct Receipt { pub invoice: String, pub number: String, pub date: String, pub amount: Option<f64>, pub campus: String }
    pub struct FeeLetter { pub year: String, pub term: String, pub description: String, pub batch_id: String }
    pub struct RegistrationWindow { pub title: String, pub semester: String, pub start: Option<String>, pub end: Option<String> }
    pub struct CurriculumCategory { pub id: String, pub name: String, pub credits: Option<f64>, pub max: Option<f64> }
    pub struct CurriculumCourse { pub group: String, pub code: String, pub title: String, pub r#type: String, pub credits: Option<f64>, pub ltpj: String }
    pub struct SemesterGrades { pub semester_id: String, pub gpa: Option<f64>, pub courses: Vec<GradeEntry> }
    pub struct MessStatus { pub title: String, pub lines: Vec<String>, pub open: bool }
    pub struct ClubListing {
        pub id: String, pub club: String, pub title: String, pub description: String, pub dates: String, pub time: String,
        pub venue: String, pub registration: String, pub can_register: bool, pub state: String,
    }
    pub struct RegisteredEvent {
        pub id: String, pub club: String, pub title: String, pub dates: String, pub venue: String, pub status: String,
        pub participation: bool, pub winner: bool,
    }
    pub struct BoardCertificate { pub year: String, pub club: String, pub position: String, pub association_id: String, pub generated_year: String, pub role_id: String }
    pub struct ClubEvents { pub upcoming: Vec<ClubListing>, pub registered: Vec<RegisteredEvent>, pub board: Vec<BoardCertificate> }
    pub struct GeneralCertificate { pub name: String, pub event_id: String, pub certificate_id: String, pub participant_id: String }

    pub struct Circular { pub id: String, pub title: String, pub date: String, pub office: String, pub group: String }
    pub struct FacultyProject { pub id: String, pub title: String, pub area: String, pub posted: String, pub faculty: String, pub school: String }
    pub struct VtopWindow { pub id: String, pub title: String, pub open: bool, pub note: String }
    pub struct SignIn { pub activity: String, pub time: String, pub ip: String, pub app: String }
    pub struct Facility { pub name: String, pub fee: String, pub seats: String }
    pub struct FacilityStatus { pub name: String, pub status: String }
    pub struct Sports { pub facilities: Vec<Facility>, pub mine: Vec<FacilityStatus> }
    pub struct ClubRow { pub name: String, pub r#type: String, pub year: String, pub status: String }
    pub struct ClubDirectory { pub mine: Vec<ClubRow>, pub all: Vec<ClubRow> }
    pub struct FaqItem { pub domain: String, pub process: String, pub question: String, pub answer: String }
    pub struct MalpracticeRule { pub exam: String, pub nature: String, pub action: String }
    pub struct ExamTable { pub title: String, pub table: SimpleTable }
    pub struct Certificate { pub source: String, pub title: String, pub detail: String, pub path: String, pub params: std::collections::BTreeMap<String, String>, pub menu: String }
}

pub struct Session {
    pub csrf: String,
    pub authorized_id: String,
}
