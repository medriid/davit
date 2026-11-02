//! The Rust parsers against what the Android parsers read from the same fixtures.
//! `parity.json` comes from Android's `ParityDump` test; run that after changing a parser.

use davit_lib::vtop::captcha::Solver;
use davit_lib::vtop::client::check;
use davit_lib::vtop::parsers as p;
use serde_json::{json, Value};

fn fixture(name: &str) -> String {
    let path = format!("{}/../../android/core/src/test/resources/html/{name}", env!("CARGO_MANIFEST_DIR"));
    std::fs::read_to_string(path).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn run(parser: &str, html: &str) -> Value {
    fn j<T: serde::Serialize>(v: T) -> Value {
        serde_json::to_value(v).unwrap()
    }
    match parser {
        "semesters" => j(p::semesters(html)),
        "attendance" => j(p::attendance(html)),
        "attendance_detail" => j(p::attendance_detail(html)),
        "timetable" => j(p::timetable(html)),
        "marks" => j(p::marks(html)),
        "exams" => j(p::exams(html)),
        "assignment_courses" => j(p::assignment_courses(html)),
        "assignments" => j(p::assignments(html)),
        "profile" => j(p::profile(html)),
        "grade_history" => j(p::grade_history(html)),
        "faculty_search" => j(p::faculty_search(html)),
        "calendar_months" => j(p::calendar_months(html)),
        "calendar_month" => j(p::calendar_month(html, "01-OCT-2026")),
        "night_slips" => j(p::night_slips(html)),
        "leaves_applied" => j(p::leaves(html, "LeaveAppliedTable")),
        "leaves_history" => j(p::leaves(html, "LeaveHistoryTable")),
        "remarks" => j(p::remarks(html)),
        "feedbacks" => j(p::feedbacks(html)),
        "events" => j(p::events(html)),
        "spotlight" => j(p::spotlight(html)),
        "proctor" => j(p::proctor(html)),
        "hod_dean" => j(p::hod_dean(html)),
        "messages" => j(p::messages(html)),
        "table" => j(p::simple_table(html)),
        "receipts" => j(p::receipts(html)),
        "fee_letters" => j(p::fee_letters(html)),
        "library" => j(p::library_dues(html)),
        "registration" => j(p::registration(html)),
        "curriculum_categories" => j(p::curriculum_categories(html)),
        "curriculum_courses" => j(p::curriculum_courses(html)),
        "mess" => j(p::mess_change(html)),
        "courses" => j(p::courses(html)),
        "materials" => j(p::materials(html)),
        "clubs" => j(p::club_page(html)),
        "general_certificates" => j(p::general_certificates(html)),
        "semester_grades" => j(p::semester_grades(html, "VL1")),
        "faculty" => j(p::faculty(html, "E1")),
        other => panic!("no parser {other}"),
    }
}

#[test]
fn every_fixture_reads_as_on_android() {
    let text = std::fs::read_to_string(format!("{}/tests/parity.json", env!("CARGO_MANIFEST_DIR"))).unwrap();
    let cases: Vec<Value> = serde_json::from_str(&text).unwrap();
    let mut failed = vec![];
    for case in &cases {
        let parser = case["parser"].as_str().unwrap();
        let name = case["fixture"].as_str().unwrap();
        let got = run(parser, &fixture(name));
        if got != case["out"] {
            failed.push(format!("{parser} on {name}\n  android: {}\n  rust:    {got}", case["out"]));
        }
    }
    assert!(failed.is_empty(), "{} of {} differ:\n{}", failed.len(), cases.len(), failed.join("\n"));
}

#[test]
fn the_sections_only_desktop_uses() {
    let c = p::circulars(r#"<ul><li><span>Office of the Dean (Academics)</span><ul><li><span>General</span><ul><li><a onclick="javascript:viewCertificate('CIR1');">011_ACAD. CALEN._07.09.2026.pdf [ 07-SEP-2026 ]</a></li></ul></li></ul></li></ul>"#);
    assert_eq!(json!(c), json!([{"id":"CIR1","title":"ACAD. CALEN.","date":"2026-09-07","office":"Office of the Dean (Academics)","group":"General"}]));
    let w = p::window("x", "X", "<p>Registration: 10:00 PM Activity Not Permitted</p><noscript>Enable JavaScript</noscript>");
    assert!(!w.open);
    assert_eq!(w.note, "Not open to you");
    assert!(p::window("x", "X", "<p>Pick a course</p><noscript>closed without JavaScript</noscript><script>var m='closed'</script>").open);
    assert_eq!(p::is_sheet_leftover("SUM(G48:K48)"), true);
    assert_eq!(p::is_sheet_leftover("Reviewed(see Q3)"), false);
    assert_eq!(p::file_name_of(Some(r#"attachment; filename="../a b.pdf""#)).as_deref(), Some("a b.pdf"));
}

#[test]
fn the_guard_refuses_money_passwords_and_writes() {
    for path in ["/finance/Payments", "/controlpanel/ChangePassword", "/examinations/doDAssignmentUploadMethod", "/hostels/student/leave/3", "/backupCode/getBackupcodes", "/academics/exc/doStuRegister", "/save/academic/action/plan/read/logs"] {
        assert!(check(path).is_err(), "{path} went through");
    }
    for path in ["/academics/common/StudentAttendance", "/finance/libraryPayments", "/finance/getStudentReceipts", "/hostels/student/leave/1"] {
        assert!(check(path).is_ok(), "{path} was blocked");
    }
}

#[test]
fn captchas_solve_as_on_android() {
    let root = format!("{}/../../android/core/src", env!("CARGO_MANIFEST_DIR"));
    let Ok(model) = std::fs::read(format!("{root}/main/resources/dev/medriid/davit/vtop/captcha_model.bin")) else { return };
    let solver = Solver::parse(&model).unwrap();
    let expected = std::fs::read_to_string(format!("{root}/test/resources/captcha/expected.txt")).unwrap();
    let mut checked = 0;
    for line in expected.lines().filter(|l| !l.trim().is_empty()) {
        let parts: Vec<&str> = line.split_whitespace().collect();
        let bytes = std::fs::read(format!("{root}/test/resources/captcha/{}", parts[0])).unwrap();
        assert_eq!(solver.solve_image(&bytes).as_deref(), Some(parts[1]), "{}", parts[0]);
        checked += 1;
    }
    assert!(checked > 0);
}
