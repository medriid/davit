//! Sign-in and the session against a small fake VTOP on this computer: the real requests, the
//! real cookies, and the real captcha step. No real account is involved.

use base64::Engine;
use davit_lib::vtop::captcha::Solver;
use davit_lib::vtop::client::{Vtop, VtopError};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

#[derive(Default)]
struct World {
    seen: Vec<String>,
    logins: u32,
    /// Next authenticated page bounces to the login, as an expired session does.
    expire_once: bool,
    reject_password: bool,
}

fn fixture(name: &str) -> String {
    std::fs::read_to_string(format!("{}/../../android/core/src/test/resources/html/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

fn captcha_uri() -> String {
    let png = std::fs::read(format!("{}/../../android/core/src/test/resources/captcha/gen_00.png", env!("CARGO_MANIFEST_DIR"))).unwrap();
    format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(png))
}

fn login_form() -> String {
    format!(r#"<form id="vtopLoginForm"><input name="_csrf" value="LOGIN-CSRF"></form><div id="captchaBlock"><img src="{}"></div>"#, captcha_uri())
}

fn form(body: &str) -> BTreeMap<String, String> {
    body.split('&').filter_map(|kv| kv.split_once('=')).map(|(k, v)| (k.to_string(), v.replace('+', " "))).collect()
}

async fn serve(world: Arc<Mutex<World>>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (mut sock, _) = listener.accept().await.unwrap();
            let world = world.clone();
            tokio::spawn(async move {
                let mut buf = vec![];
                let mut chunk = [0u8; 8192];
                loop {
                    let n = sock.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        return;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    let Some(end) = text.find("\r\n\r\n") else { continue };
                    let length: usize = text.lines().find_map(|l| l.to_lowercase().strip_prefix("content-length:").and_then(|v| v.trim().parse().ok())).unwrap_or(0);
                    if buf.len() < end + 4 + length {
                        continue;
                    }
                    let path = text.split_whitespace().nth(1).unwrap_or("").to_string();
                    let body = &text[end + 4..end + 4 + length];
                    let (status, reply) = answer(&world, &path, body, &text);
                    let out = format!("HTTP/1.1 {status} OK\r\nContent-Type: text/html\r\nContent-Length: {}\r\nSet-Cookie: JSESSIONID=abc; Path=/\r\nConnection: keep-alive\r\n\r\n{reply}", reply.len());
                    sock.write_all(out.as_bytes()).await.ok();
                    buf.clear();
                }
            });
        }
    });
    format!("http://127.0.0.1:{port}/vtop")
}

fn answer(world: &Arc<Mutex<World>>, path: &str, body: &str, raw: &str) -> (u16, String) {
    let mut w = world.lock().unwrap();
    w.seen.push(path.to_string());
    let path = path.trim_start_matches("/vtop");
    let f = form(body);
    match path {
        "/open/page" => (200, r#"<form id="stdForm"><input name="_csrf" value="OPEN-CSRF"></form>"#.to_string()),
        "/prelogin/setup" => {
            assert_eq!(f.get("_csrf").map(String::as_str), Some("OPEN-CSRF"));
            (200, login_form())
        }
        "/login" => {
            assert_eq!(f.get("_csrf").map(String::as_str), Some("LOGIN-CSRF"));
            if w.reject_password {
                return (200, format!("<p>Invalid User Id / Password</p>{}", login_form()));
            }
            w.logins += 1;
            (200, format!(r#"<input id="authorizedIDX" value="DEMO"><input name="_csrf" value="SESSION-{}"><p>home</p>"#, w.logins))
        }
        _ => {
            assert!(raw.to_lowercase().contains("cookie: jsessionid=abc"), "no session cookie on {path}");
            assert_eq!(f.get("authorizedID").map(String::as_str), Some("DEMO"), "{path}");
            // The session's token, or a newer one a page handed back: never a login page's.
            assert!(f.get("_csrf").is_some_and(|c| !c.is_empty() && c != "OPEN-CSRF" && c != "LOGIN-CSRF"), "{path}");
            if w.expire_once {
                w.expire_once = false;
                return (200, r#"<form id="stdForm"><input name="_csrf" value="OPEN-CSRF"></form>"#.to_string());
            }
            match path {
                "/academics/common/StudentAttendance" => (200, fixture("attendance.html")),
                "/processViewStudentAttendance" => (200, fixture("attendance.html")),
                "/examinations/doStudentMarkView" => (200, fixture("marks.html")),
                _ => (404, String::new()),
            }
        }
    }
}

fn solver() -> Option<Solver> {
    let model = std::fs::read(format!("{}/../../android/core/src/main/resources/dev/medriid/davit/vtop/captcha_model.bin", env!("CARGO_MANIFEST_DIR"))).ok()?;
    Solver::parse(&model)
}

#[tokio::test]
async fn signs_in_reads_and_signs_in_again_when_the_session_expires() {
    let Some(solver) = solver() else { return };
    let world = Arc::new(Mutex::new(World::default()));
    let base = serve(world.clone()).await;
    let mut v = Vtop::new(Some(solver), &base);
    v.set_credentials("demo".into(), "pw".into());

    v.login_saved().await.unwrap();
    assert!(v.has_session());
    assert_eq!(v.semesters().await.unwrap().len(), 2);
    assert_eq!(world.lock().unwrap().logins, 1);

    world.lock().unwrap().expire_once = true;
    assert_eq!(v.attendance("VL20262701").await.unwrap().len(), 3);
    assert_eq!(world.lock().unwrap().logins, 2, "signed in again once");

    // The session survives being saved and restored, as across restarts.
    let saved = v.export_session().unwrap();
    let mut again = Vtop::new(None, &base);
    again.restore_session(&saved);
    assert!(again.has_session());
    assert!(!again.marks("VL20262701").await.unwrap().is_empty());
}

#[tokio::test]
async fn a_rejected_password_stops_at_once() {
    let Some(solver) = solver() else { return };
    let world = Arc::new(Mutex::new(World { reject_password: true, ..World::default() }));
    let base = serve(world.clone()).await;
    let mut v = Vtop::new(Some(solver), &base);
    assert!(matches!(v.login("demo", "wrong").await, Err(VtopError::BadCredentials)));
    assert_eq!(world.lock().unwrap().seen.iter().filter(|p| p.ends_with("/login")).count(), 1, "never retried, so no lockout");
}

#[tokio::test]
async fn blocked_pages_never_leave_the_computer() {
    let world = Arc::new(Mutex::new(World::default()));
    let base = serve(world.clone()).await;
    let mut v = Vtop::new(None, &base);
    let target = std::env::temp_dir().join("davit-blocked");
    let args = BTreeMap::from([("path".to_string(), "/finance/Payments".to_string()), ("menu".to_string(), "/finance/Payments".to_string())]);
    assert!(v.fetch_file("certificate", &args, &target).await.is_err());
    assert!(v.fetch_file("nope", &BTreeMap::new(), &target).await.is_err());
    assert!(world.lock().unwrap().seen.is_empty());
}
