//! Everything that sends something to VTOP, against a small fake VTOP on this computer serving
//! the Android fixtures. No real account is involved: these requests never leave 127.0.0.1.

use davit_lib::vtop::client::{check, check_write, Consent, Vtop};
use davit_lib::vtop::forms::UploadOutcome;
use davit_lib::vtop::parsers as p;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

type Log = Arc<Mutex<Vec<(String, String)>>>;

fn fixture(name: &str) -> String {
    std::fs::read_to_string(format!("{}/../../android/core/src/test/resources/html/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

fn answer(path: &str) -> String {
    let path = path.trim_start_matches("/vtop");
    let said = |words: &str| format!(r#"<input id="info" value="{words}">"#);
    match path {
        "/hostels/student/leave/1" | "/hostels/late/hour/student/request/1" => "<p>menu</p>".into(),
        "/hostels/student/leave/2" => fixture("leave_form.html"),
        "/hostels/student/leave/3" => "<p>Leave applied successfully</p>".into(),
        "/hostels/late/hour/student/request/2" => fixture("night_slip_form.html"),
        "/hostels/late/hour/student/request/3" => said("Request submitted"),
        "/event/swf/loadEventRegistration" => fixture("club_events.html"),
        "/event/swf/registered/doEventRegistraiton" => said("Registered"),
        "/academics/common/processViewFeedbackList" => fixture("course_feedback_list.html"),
        "/academics/common/StudentClassGrievance" => fixture("course_feedback_page.html"),
        "/academics/common/saveAcademicsCourseFeedback" => said("Saved"),
        "/examinations/paperSeeing/PaperSeeing" => fixture("paper_seeing_page.html"),
        "/examinations/paperSeeing/PaperSeeingOnChange" => fixture("paper_seeing_courses.html"),
        "/examinations/paperSeeing/PaperSeeingSubmit" => said("Submitted"),
        "/others/bonafide/StudentBonafidePageControl" => fixture("bonafide_page.html"),
        "/others/bonafide/StudentBonafideOnChange" => fixture("bonafide_form_3.html"),
        "/others/bonafide/StudentBonafideSave" => said("Applied"),
        "/hostels/messComplaintPage" => fixture("mess_complaint_page.html"),
        "/hostels/messComplaint/save" => said("Saved"),
        "/examinations/doDigitalAssignment" | "/examinations/processDigitalAssignment" => fixture("assignment_detail.html"),
        "/examinations/processDigitalAssignmentUpload" => fixture("upload_form.html"),
        "/examinations/doDAssignmentUploadMethod" => fixture("upload_otp.html"),
        "/examinations/doDAssignmentOtpUpload" => fixture("upload_done.html"),
        _ => "<p>nothing here</p>".into(),
    }
}

async fn serve(log: Log) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (mut sock, _) = listener.accept().await.unwrap();
            let log = log.clone();
            tokio::spawn(async move {
                let mut buf: Vec<u8> = vec![];
                let mut chunk = [0u8; 16384];
                loop {
                    let n = sock.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        return;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    let Some(end) = text.find("\r\n\r\n") else { continue };
                    let length: usize = text[..end].lines().find_map(|l| l.to_lowercase().strip_prefix("content-length:").and_then(|v| v.trim().parse().ok())).unwrap_or(0);
                    if buf.len() < end + 4 + length {
                        continue;
                    }
                    let path = text.split_whitespace().nth(1).unwrap_or("").to_string();
                    log.lock().unwrap().push((path.clone(), text.clone()));
                    let reply = answer(&path);
                    let out = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: {}\r\n\r\n{}", reply.len(), reply);
                    sock.write_all(out.as_bytes()).await.ok();
                    buf.clear();
                }
            });
        }
    });
    format!("http://127.0.0.1:{port}/vtop")
}

async fn signed_in() -> (Vtop, Log) {
    let log: Log = Arc::new(Mutex::new(vec![]));
    let base = serve(log.clone()).await;
    let mut v = Vtop::new(None, &base);
    v.restore_session(r#"{"csrf":"TOKEN","id":"DEMO","cookies":""}"#);
    assert!(v.has_session());
    (v, log)
}

fn last(log: &Log, path: &str) -> String {
    log.lock().unwrap().iter().rev().find(|(p, _)| p.ends_with(path)).unwrap_or_else(|| panic!("{path} was never sent")).1.clone()
}

fn sent(log: &Log, path: &str) -> bool {
    log.lock().unwrap().iter().any(|(p, _)| p.ends_with(path))
}

#[test]
fn writes_need_consent_and_money_never_goes() {
    let consent = Consent::given("test");
    for path in ["/hostels/student/leave/3", "/examinations/doDAssignmentUploadMethod", "/examinations/doDAssignmentOtpUpload", "/others/bonafide/StudentBonafideSave", "/hostels/messComplaint/save", "/examinations/paperSeeing/PaperSeeingSubmit", "/academics/common/saveAcademicsCourseFeedback", "/event/swf/registered/doEventRegistraiton", "/hostels/late/hour/student/request/3"] {
        assert!(check(path).is_err(), "{path} went without consent");
        assert!(check_write(path, &consent).is_ok(), "{path} refused with consent");
    }
    for path in ["/finance/Payments", "/controlpanel/ChangePassword", "/academics/courseRegistration", "/fpp/studentReqByProjectId", "/event/swf/student/enrollClubChapters"] {
        assert!(check_write(path, &consent).is_err(), "{path} went through");
    }
}

#[tokio::test]
async fn leave_goes_with_the_forms_own_fields() {
    let (mut v, log) = signed_in().await;
    let values: Vec<(String, String)> = [("leaveCode", "HT"), ("visitingPlace", "Chennai"), ("leaveFromDate", "2026-11-10"), ("fromTime", "09:00"), ("leaveToDate", "2026-11-12"), ("toTime", "18:00"), ("reason", "Family")].iter().map(|(a, b)| (a.to_string(), b.to_string())).collect();
    let r = v.apply_leave(&Consent::given("leave"), &values).await.unwrap();
    assert!(r.ok, "{}", r.message);
    let body = last(&log, "/hostels/student/leave/3");
    for want in ["leaveCode=HT", "visitingPlace=Chennai", "leaveFromDate=2026-11-10", "fromTime=09%3A00", "toTime=18%3A00", "reason=Family", "form=LeaveRequestForm", "control=submitControl2", "_csrf=", "authorizedID=DEMO"] {
        assert!(body.contains(want), "no {want} in {body}");
    }
}

#[tokio::test]
async fn a_night_slip_refuses_a_changed_form() {
    let (mut v, log) = signed_in().await;
    let values = BTreeMap::from([("venue".to_string(), "TT".to_string()), ("purpose".to_string(), "Lab".to_string())]);
    let r = v.apply_night_slip(&Consent::given("slip"), &values).await.unwrap();
    assert!(r.ok, "{}", r.message);
    let body = last(&log, "/hostels/late/hour/student/request/3");
    assert!(body.contains("venue=TT") && body.contains("purpose=Lab") && body.contains("control=submitRequest") && body.contains("form=LateHourRequestForm"), "{body}");
}

#[tokio::test]
async fn an_event_registers_only_if_still_offered() {
    let (mut v, log) = signed_in().await;
    let page = p::club_page(&fixture("club_events.html"));
    let open = page.upcoming.iter().find(|e| e.can_register).expect("the fixture offers one");
    let r = v.register_event(&Consent::given("event"), &open.id).await.unwrap();
    assert!(r.ok, "{}", r.message);
    assert!(last(&log, "doEventRegistraiton").contains(&format!("eventId={}", open.id)));

    let before = log.lock().unwrap().len();
    if let Some(shut) = page.upcoming.iter().find(|e| !e.can_register) {
        assert!(v.register_event(&Consent::given("event"), &shut.id).await.is_err());
    }
    assert!(v.register_event(&Consent::given("event"), "NOPE").await.is_err());
    assert!(!log.lock().unwrap()[before..].iter().any(|(p, _)| p.ends_with("doEventRegistraiton")), "a closed event went through");
}

#[tokio::test]
async fn feedback_checks_the_class_still_takes_it() {
    let (mut v, log) = signed_in().await;
    let classes = davit_lib::vtop::forms::class_feedback(&fixture("course_feedback_list.html"));
    let open = classes.iter().find(|c| c.open).expect("the fixture has an open class");
    assert!(v.submit_class_feedback(&Consent::given("f"), "VL1", &open.class_id, "   ").await.is_err());
    assert!(v.submit_class_feedback(&Consent::given("f"), "VL1", &open.class_id, &"x".repeat(101)).await.is_err());
    assert!(!sent(&log, "saveAcademicsCourseFeedback"), "bad text went through");
    let r = v.submit_class_feedback(&Consent::given("f"), "VL1", &open.class_id, "Clear examples").await.unwrap();
    assert!(r.ok, "{}", r.message);
    let body = last(&log, "saveAcademicsCourseFeedback");
    assert!(body.contains("courseFeedbackVal=Clear+examples") && body.contains(&format!("classId={}", open.class_id)), "{body}");
}

#[tokio::test]
async fn paper_seeing_sends_every_choice_as_a_browser_does() {
    let (mut v, log) = signed_in().await;
    let page = davit_lib::vtop::forms::paper_seeing(&fixture("paper_seeing_courses.html"));
    let chosen = vec![page.courses[0].value.clone()];
    assert!(v.submit_paper_seeing(&Consent::given("p"), "VL1", "2", "RGR", &["NOT-OFFERED".to_string()]).await.is_err());
    assert!(!sent(&log, "PaperSeeingSubmit"));
    let r = v.submit_paper_seeing(&Consent::given("p"), "VL1", "2", "RGR", &chosen).await.unwrap();
    assert!(r.ok, "{}", r.message);
    let body = last(&log, "PaperSeeingSubmit");
    assert!(body.to_lowercase().contains("content-type: multipart/form-data"));
    for name in ["_csrf", "authorizedID", "semesterSubId", "serviceType", "examType", "frontValue"] {
        assert!(body.contains(&format!("name=\"{name}\"")), "no {name}");
    }
    assert!(body.contains(&chosen[0]));
}

#[tokio::test]
async fn bonafide_keeps_the_dates_vtop_locked() {
    let (mut v, log) = signed_in().await;
    let page = v.bonafide_form("3").await.unwrap();
    let form = page.form.unwrap();
    let locked: Vec<_> = form.fields.iter().filter(|f| f.read_only).collect();
    assert!(!locked.is_empty(), "the vacation visa fixes its dates");
    let values = BTreeMap::from([("description".to_string(), "For my visa".to_string()), (locked[0].name.clone(), "01-Jan-2000".to_string())]);
    let r = v.apply_bonafide(&Consent::given("b"), "3", &values).await.unwrap();
    assert!(r.ok, "{}", r.message);
    let body = last(&log, "StudentBonafideSave");
    assert!(body.contains("For my visa"));
    assert!(body.contains(&locked[0].value) && !body.contains("01-Jan-2000"), "a locked date was changed");
}

#[tokio::test]
async fn a_mess_complaint_checks_its_file_first() {
    let (mut v, log) = signed_in().await;
    let c = Consent::given("m");
    assert!(v.send_mess_complaint(&c, "", "a.png", "image/png", vec![1]).await.is_err());
    assert!(v.send_mess_complaint(&c, &"x".repeat(201), "a.png", "image/png", vec![1]).await.is_err());
    assert!(v.send_mess_complaint(&c, "Cold", "a.exe", "", vec![1]).await.is_err());
    assert!(v.send_mess_complaint(&c, "Cold", "a.png", "image/png", vec![0; 5 * 1024 * 1024 + 1]).await.is_err());
    assert!(!sent(&log, "/hostels/messComplaint/save"));
    let r = v.send_mess_complaint(&c, "Cold food", "a.png", "image/png", b"PNGDATA".to_vec()).await.unwrap();
    assert!(r.ok, "{}", r.message);
    let body = last(&log, "/hostels/messComplaint/save");
    assert!(body.contains("name=\"complaintData\"") && body.contains("Cold food") && body.contains("filename=\"a.png\"") && body.contains("PNGDATA"), "{body}");
}

#[tokio::test]
async fn an_upload_then_its_emailed_code() {
    let (mut v, log) = signed_in().await;
    let c = Consent::given("u");
    let form = v.open_upload(&c, Some("VL1"), "C1", "D1").await.unwrap();
    let outcome = v.upload(&c, &form, "answer.pdf", "application/pdf", b"%PDF-1.4 hello".to_vec()).await.unwrap();
    let body = last(&log, "doDAssignmentUploadMethod");
    assert!(body.contains(&format!("name=\"{}\"; filename=\"answer.pdf\"", form.file_field)) && body.contains("%PDF-1.4 hello") && body.contains("name=\"_csrf\""), "{body}");
    // The file goes where the page has it, among the other fields.
    let at = body.find("filename=\"answer.pdf\"").unwrap();
    let before = form.fields.iter().take(form.file_at).all(|(k, _)| body[..at].contains(&format!("name=\"{k}\"")));
    assert!(before, "fields out of the page's order");
    let UploadOutcome::NeedsOtp { fields, otp_field, .. } = outcome else { panic!("expected the OTP step, got {outcome:?}") };
    let done = v.submit_upload_otp(&c, &fields, &otp_field, " 123456 ", "D1").await.unwrap();
    assert!(matches!(done, UploadOutcome::Uploaded { .. }), "{done:?}");
    let body = last(&log, "doDAssignmentOtpUpload");
    assert!(body.contains(&format!("name=\"{otp_field}\"")) && body.contains("123456") && !body.contains(" 123456 "), "{body}");
}

#[tokio::test]
async fn opening_a_form_sends_nothing() {
    let (mut v, log) = signed_in().await;
    assert!(v.leave_form().await.unwrap().is_some());
    assert!(v.night_slip_form().await.unwrap().form.is_some());
    assert!(!v.bonafide_services().await.unwrap().is_empty());
    assert!(!v.mess_complaint_notes().await.unwrap().is_empty());
    assert!(v.paper_seeing_form().await.unwrap().form.is_some());
    assert!(!v.feedback_semesters().await.unwrap().is_empty());
    for (path, _) in log.lock().unwrap().iter() {
        assert!(check(path.trim_start_matches("/vtop")).is_ok(), "{path} is a write");
    }
}
