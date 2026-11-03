pub mod store;
pub mod sync;
pub mod vtop;

use base64::Engine;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use store::Store;
use sync::{Choices, Snapshot};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager, State};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_notification::NotificationExt;
use tokio::sync::Mutex;
use vtop::captcha::Solver;
use vtop::client::{Consent, VtopError, Vtop, BASE};

#[cfg(has_private_data)]
const MODEL: Option<&[u8]> = Some(include_bytes!(concat!(env!("OUT_DIR"), "/captcha_model.bin")));
#[cfg(not(has_private_data))]
const MODEL: Option<&[u8]> = None;
#[cfg(has_private_data)]
const SLOTS: &str = include_str!(concat!(env!("OUT_DIR"), "/slot_times.txt"));
#[cfg(not(has_private_data))]
const SLOTS: &str = "";

const SNAPSHOT: &str = "snapshot.enc";
const SESSION: &str = "session.enc";
const COLD: &str = "cold.enc";
const YOURS: &str = "yours.enc";

struct App {
    vtop: Mutex<Vtop>,
    store: Store,
    snapshot: Mutex<Option<Snapshot>>,
    /// Closing the window leaves daVIT in the tray.
    background: AtomicBool,
    tray: AtomicBool,
}

type Out<T> = Result<T, String>;

fn err(e: VtopError) -> String {
    e.to_string()
}

async fn keep_session(app: &App, v: &Vtop) {
    if let Some(s) = v.export_session() {
        let _ = app.store.write(SESSION, &s);
        let _ = app.store.write("session-at", &chrono::Utc::now().timestamp().to_string());
    }
}

/// What the window needs when it opens: whether you're signed in, and the last sync.
#[tauri::command]
async fn status(app: State<'_, App>) -> Out<Value> {
    let snapshot = app.snapshot.lock().await.clone();
    Ok(json!({
        "signedIn": app.store.credentials().is_some(),
        "canSignIn": MODEL.is_some(),
        "snapshot": snapshot,
        "slots": SLOTS,
        "version": env!("CARGO_PKG_VERSION"),
    }))
}

#[tauri::command]
async fn sign_in(app: State<'_, App>, username: String, password: String) -> Out<()> {
    let mut v = app.vtop.lock().await;
    v.login(&username, &password).await.map_err(err)?;
    v.set_credentials(username.trim().to_string(), password.clone());
    app.store.save_credentials(username.trim(), &password)?;
    keep_session(&app, &v).await;
    Ok(())
}

#[tauri::command]
async fn sign_out(app: State<'_, App>) -> Out<()> {
    let mut v = app.vtop.lock().await;
    v.logout().await;
    app.store.wipe();
    *app.snapshot.lock().await = None;
    Ok(())
}

/// A full read when you ask for it; otherwise what rarely changes is only read every few hours.
#[tauri::command]
async fn sync_now(window: tauri::Window, app: State<'_, App>, everything: bool, choices: Option<Choices>) -> Out<Snapshot> {
    let mut v = app.vtop.lock().await;
    let old = app.snapshot.lock().await.clone();
    let idle = app.store.read("session-at").and_then(|t| t.parse::<i64>().ok()).map(|t| chrono::Utc::now().timestamp() - t).unwrap_or(i64::MAX);
    let progress = |what: &str| {
        let _ = window.emit("sync-progress", what);
    };
    let new = sync::sync(&mut v, old.as_ref(), everything, idle > 3600, &choices.unwrap_or_default(), &progress).await.map_err(err)?;
    keep_session(&app, &v).await;
    let _ = app.store.write(SNAPSHOT, &serde_json::to_string(&new).unwrap_or_default());
    *app.snapshot.lock().await = Some(new.clone());
    Ok(new)
}

fn j<T: serde::Serialize>(v: T) -> Value {
    serde_json::to_value(v).unwrap_or(Value::Null)
}

/// A section read when its screen opens, and kept for offline.
#[tauri::command]
async fn section(app: State<'_, App>, name: String, arg: Option<String>, force: Option<bool>) -> Out<Value> {
    let key = match &arg {
        Some(a) => format!("{name}|{a}"),
        None => name.clone(),
    };
    let mut cold: BTreeMap<String, Value> = app.store.read(COLD).and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
    let fresh_for = match name.as_str() {
        "rules" | "curriculum" => 14 * 86400,
        "people" | "courses" | "materials" => 86400,
        "circulars" | "projects" | "windows" => 6 * 3600,
        _ => 20 * 60,
    };
    let now = chrono::Utc::now().timestamp();
    if force != Some(true) {
        if let Some(hit) = cold.get(&key) {
            if now - hit["at"].as_i64().unwrap_or(0) < fresh_for {
                return Ok(hit.clone());
            }
        }
    }
    let sem = app.snapshot.lock().await.as_ref().map(|s| s.semester.id.clone()).unwrap_or_default();
    let a = arg.clone().unwrap_or_default();
    let mut v = app.vtop.lock().await;
    let read: Result<Value, VtopError> = async {
        Ok(match name.as_str() {
            "people" => json!({ "proctor": v.proctor().await?, "hodDean": v.hod_dean().await.unwrap_or_default() }),
            "messages" => json!({ "class": v.class_messages().await?, "proctor": v.proctor_messages().await? }),
            "spotlight" => j(v.spotlight().await?),
            "gate" => j(v.gate(a.parse().unwrap_or(7)).await?),
            "money" => json!({ "receipts": v.receipts().await?, "letters": v.fee_letters().await.unwrap_or_default(), "library": tokio::time::timeout(std::time::Duration::from_secs(8), v.library()).await.ok().and_then(|r| r.ok()).unwrap_or_default() }),
            "curriculum" => {
                let (categories, courses) = v.curriculum().await?;
                json!({ "categories": categories, "courses": courses })
            }
            "semesterGrades" => j(v.semester_grades(&a).await?),
            "courses" => j(v.courses().await?),
            "materials" => {
                let c: vtop::models::CourseOption = serde_json::from_str(&a).map_err(|_| VtopError::Blocked)?;
                j(v.materials(&c).await?)
            }
            "clubs" => {
                let (page, general) = v.clubs().await?;
                json!({ "page": page, "general": general })
            }
            "registered" => j(v.registered_next(&sem).await?.map(|(s, t)| json!({ "semester": s, "courses": t }))),
            "signIns" => j(v.sign_ins().await?),
            "allocation" => {
                let (semester, categories) = v.allocation().await?;
                json!({ "semester": semester, "categories": categories })
            }
            "allocationCourses" => j(v.allocation_courses(&a).await?),
            "allocationClasses" => j(v.allocation_classes(&a).await?),
            "sports" => j(v.sports().await?),
            "clubDirectory" => j(v.club_directory().await?),
            "rules" => json!({ "malpractice": v.malpractice().await?, "faqs": v.faqs().await? }),
            "otherExams" => j(v.other_exams(&sem).await?),
            "certificates" => j(v.certificates().await?),
            "mess" => j(v.mess().await?),
            "hostel" => j(v.hostel_requests().await?),
            "activitySemesters" => j(v.activity_semesters().await?),
            "activities" => json!({ "record": v.activities(&a).await?, "qcm": v.qcm(&a).await.unwrap_or_default() }),
            "facultySearch" => j(v.search_faculty(&a).await?),
            "faculty" => j(v.faculty(&a).await?),
            _ => return Err(VtopError::Blocked),
        })
    }
    .await;
    match read {
        Ok(data) => {
            keep_session(&app, &v).await;
            let entry = json!({ "at": now, "data": data });
            cold.insert(key, entry.clone());
            let _ = app.store.write(COLD, &serde_json::to_string(&cold).unwrap_or_default());
            Ok(entry)
        }
        // Offline or refused: the last copy, if there is one, marked as old.
        Err(e) => match cold.get(&key) {
            Some(hit) if force != Some(true) => Ok(hit.clone()),
            _ => Err(err(e)),
        },
    }
}

/// A form, always fresh from VTOP: nothing here sends anything.
#[tauri::command]
async fn form_read(app: State<'_, App>, name: String, args: Option<BTreeMap<String, String>>) -> Out<Value> {
    let args = args.unwrap_or_default();
    let g = |k: &str| args.get(k).map(|s| s.as_str()).unwrap_or("");
    let mut v = app.vtop.lock().await;
    let read: Result<Value, VtopError> = async {
        Ok(match name.as_str() {
            "leave" => j(v.leave_form().await?),
            "nightSlip" => j(v.night_slip_form().await?),
            "bonafideKinds" => j(v.bonafide_services().await?),
            "bonafide" => j(v.bonafide_form(g("code")).await?),
            "feedback" => {
                let semesters = v.feedback_semesters().await?;
                let Some(sem) = semesters.first().cloned() else { return Err(VtopError::Unexpected("VTOP isn't taking course feedback right now.".into())) };
                json!({ "semester": sem.clone(), "classes": v.class_feedback(&sem.id).await? })
            }
            "paperSeeing" => j(v.paper_seeing_form().await?),
            "paperCourses" => j(v.paper_seeing_courses(g("semester"), g("service"), g("exam")).await?),
            "messComplaint" => j(v.mess_complaint_notes().await?),
            "assignments" => j(v.course_assignments(g("classId")).await?),
            _ => return Err(VtopError::Blocked),
        })
    }
    .await;
    if read.is_ok() {
        keep_session(&app, &v).await;
    }
    read.map_err(err)
}

#[derive(serde::Deserialize)]
struct PickedFile {
    name: String,
    mime: String,
    data: String,
}

fn decoded(file: &PickedFile) -> Out<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(&file.data).map_err(|_| "Couldn't read that file.".to_string())
}

/// The only way anything changes on VTOP. The window calls it from a confirm button, once you've
/// seen everything that goes; a sync can't reach it. Money, passwords and registration aren't here.
#[tauri::command]
async fn vtop_send(app: State<'_, App>, action: String, args: Option<Value>, file: Option<PickedFile>) -> Out<Value> {
    let args = args.unwrap_or(Value::Null);
    let g = |k: &str| args[k].as_str().unwrap_or("").to_string();
    let values: BTreeMap<String, String> = serde_json::from_value(args["values"].clone()).unwrap_or_default();
    let consent = Consent::given(&action);
    let mut v = app.vtop.lock().await;
    if !v.has_session() {
        v.login_saved().await.map_err(err)?;
    }
    let sent: Result<Value, VtopError> = async {
        Ok(match action.as_str() {
            "leave" => {
                let pairs: Vec<(String, String)> = ["leaveCode", "visitingPlace", "leaveFromDate", "fromTime", "leaveToDate", "toTime", "reason"].iter().map(|k| (k.to_string(), values.get(*k).cloned().unwrap_or_default())).collect();
                j(v.apply_leave(&consent, &pairs).await?)
            }
            "nightSlip" => j(v.apply_night_slip(&consent, &values).await?),
            "event" => j(v.register_event(&consent, &g("eventId")).await?),
            "feedback" => j(v.submit_class_feedback(&consent, &g("semester"), &g("classId"), &g("text")).await?),
            "paperSeeing" => {
                let chosen: Vec<String> = serde_json::from_value(args["chosen"].clone()).unwrap_or_default();
                j(v.submit_paper_seeing(&consent, &g("semester"), &g("service"), &g("exam"), &chosen).await?)
            }
            "bonafide" => j(v.apply_bonafide(&consent, &g("code"), &values).await?),
            "messComplaint" => {
                let f = file.as_ref().ok_or_else(|| VtopError::Unexpected("Add a photo or PDF first.".into()))?;
                let bytes = decoded(f).map_err(VtopError::Unexpected)?;
                j(v.send_mess_complaint(&consent, &g("text"), &f.name, &f.mime, bytes).await?)
            }
            "upload" => {
                let f = file.as_ref().ok_or_else(|| VtopError::Unexpected("Choose a file first.".into()))?;
                if f.data.len() > 6 * 1024 * 1024 {
                    return Err(VtopError::Unexpected("VTOP takes up to 4 MB.".into()));
                }
                let bytes = decoded(f).map_err(VtopError::Unexpected)?;
                let sem = g("semester");
                let form = v.open_upload(&consent, if sem.is_empty() { None } else { Some(&sem) }, &g("classId"), &g("code")).await?;
                let outcome = v.upload(&consent, &form, &f.name, &f.mime, bytes).await?;
                j(outcome)
            }
            "uploadOtp" => {
                let fields: Vec<(String, String)> = serde_json::from_value(args["fields"].clone()).unwrap_or_default();
                j(v.submit_upload_otp(&consent, &fields, &g("otpField"), &g("otp"), &g("code")).await?)
            }
            "uploadCancel" => {
                v.cancel_upload(&g("classId")).await;
                Value::Null
            }
            _ => return Err(VtopError::Blocked),
        })
    }
    .await;
    keep_session(&app, &v).await;
    sent.map_err(|e| match e {
        VtopError::SessionExpired => "VTOP ended the session, so that wasn't sent. Nothing changed. Try again.".to_string(),
        other => err(other),
    })
}

/// Your reminders, corrections, friends and plans, encrypted with the rest.
#[tauri::command]
async fn yours_load(app: State<'_, App>) -> Out<Option<String>> {
    Ok(app.store.read(YOURS))
}

#[tauri::command]
async fn yours_save(app: State<'_, App>, text: String) -> Out<()> {
    app.store.write(YOURS, &text)
}

#[tauri::command]
fn notify(handle: tauri::AppHandle, title: String, body: String) -> Out<()> {
    handle.notification().builder().title(title).body(body).show().map_err(|e| e.to_string())
}

fn file_rows(dir: &std::path::Path, nested: bool, saved: bool, out: &mut Vec<Value>) {
    let Ok(read) = std::fs::read_dir(dir) else { return };
    for e in read.flatten() {
        let p = e.path();
        if p.is_dir() {
            if nested {
                file_rows(&p, false, saved, out);
            }
            continue;
        }
        let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        if name == ".part" {
            continue;
        }
        let key = if nested { String::new() } else { p.parent().and_then(|d| d.file_name()).map(|n| n.to_string_lossy().to_string()).unwrap_or_default() };
        let size = e.metadata().map(|m| m.len()).unwrap_or(0);
        out.push(json!({ "name": name, "path": p.to_string_lossy(), "size": size, "saved": saved, "key": key, "pdf": name.to_lowercase().ends_with(".pdf") }));
    }
}

/// What daVIT keeps: copies you saved in Downloads/daVIT, and files you've opened, which read offline.
#[tauri::command]
async fn files_list(app: State<'_, App>) -> Out<Value> {
    let mut saved = vec![];
    if let Some(d) = downloads() {
        file_rows(&d, false, true, &mut saved);
    }
    let mut cached = vec![];
    file_rows(app.store.previews(), true, false, &mut cached);
    Ok(json!({ "saved": saved, "cached": cached, "folder": downloads().map(|d| d.to_string_lossy().to_string()) }))
}

fn ours(app: &App, path: &str) -> Out<PathBuf> {
    let p = PathBuf::from(path).canonicalize().map_err(|_| "That file is gone.".to_string())?;
    let ok = [Some(app.store.previews().clone()), downloads()].into_iter().flatten().filter_map(|d| d.canonicalize().ok()).any(|d| p.starts_with(d));
    if ok {
        Ok(p)
    } else {
        Err("daVIT only touches files it fetched.".into())
    }
}

#[tauri::command]
async fn file_delete(app: State<'_, App>, path: String) -> Out<()> {
    let p = ours(&app, &path)?;
    std::fs::remove_file(&p).map_err(|e| e.to_string())?;
    if let Some(parent) = p.parent().filter(|d| d.starts_with(app.store.previews().canonicalize().unwrap_or_default())) {
        if parent != app.store.previews().canonicalize().unwrap_or_default() {
            std::fs::remove_dir(parent).ok();
        }
    }
    Ok(())
}

/// A file made here (a scan as a PDF, the classes as .ics) into Downloads/daVIT.
#[tauri::command]
async fn save_made(name: String, text: Option<String>, data: Option<String>) -> Out<Value> {
    let dir = downloads().ok_or("No Downloads folder on this computer.")?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let bytes = match (text, data) {
        (Some(t), _) => t.into_bytes(),
        (_, Some(d)) => base64::engine::general_purpose::STANDARD.decode(d).map_err(|_| "Couldn't read that.".to_string())?,
        _ => return Err("Nothing to save.".into()),
    };
    let path = dir.join(safe_name(&name));
    std::fs::write(&path, bytes).map_err(|e| e.to_string())?;
    Ok(json!({ "path": path.to_string_lossy(), "name": safe_name(&name), "pdf": name.to_lowercase().ends_with(".pdf") }))
}

/// A week as a QR code's squares, row by row.
#[tauri::command]
fn qr_encode(text: String) -> Out<Value> {
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M).map_err(|_| "That's too long for one QR code.".to_string())?;
    let size = code.width();
    let dark: Vec<bool> = code.to_colors().into_iter().map(|c| c == qrcode::Color::Dark).collect();
    Ok(json!({ "size": size, "dark": dark }))
}

/// Whatever QR code is in this picture.
#[tauri::command]
fn qr_decode(data: String) -> Out<Option<String>> {
    let bytes = base64::engine::general_purpose::STANDARD.decode(data).map_err(|_| "Couldn't read that picture.".to_string())?;
    let img = image::load_from_memory(&bytes).map_err(|_| "Couldn't read that picture.".to_string())?.to_luma8();
    let mut prepared = rqrr::PreparedImage::prepare(img);
    Ok(prepared.detect_grids().into_iter().find_map(|g| g.decode().ok().map(|(_, text)| text)))
}

#[tauri::command]
fn set_background(app: State<'_, App>, on: bool) -> bool {
    app.background.store(on, Ordering::Relaxed);
    app.tray.load(Ordering::Relaxed)
}

#[tauri::command]
fn set_autostart(handle: tauri::AppHandle, on: bool) -> Out<bool> {
    let auto = handle.autolaunch();
    if on { auto.enable() } else { auto.disable() }.map_err(|e| e.to_string())?;
    auto.is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
fn autostart_on(handle: tauri::AppHandle) -> bool {
    handle.autolaunch().is_enabled().unwrap_or(false)
}

/// The next class, on the tray icon: what a home screen widget says on the phone.
#[tauri::command]
fn tray_text(handle: tauri::AppHandle, text: String) {
    if let Some(tray) = handle.tray_by_id("main") {
        let _ = tray.set_tooltip(Some(text));
    }
}

/// Keeps the last raw page of each kind, for fixing a parser. Off unless you turn it on.
#[tauri::command]
async fn save_pages(app: State<'_, App>, on: bool) -> Out<()> {
    let dir = if on { downloads().map(|d| d.join("vtop-pages")) } else { None };
    app.vtop.lock().await.set_page_folder(dir);
    Ok(())
}

fn show_main(handle: &tauri::AppHandle) {
    if let Some(w) = handle.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn make_tray(handle: &tauri::AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(handle, "open", "Open daVIT", true, None::<&str>)?;
    let sync = MenuItem::with_id(handle, "sync", "Sync now", true, None::<&str>)?;
    let quit = MenuItem::with_id(handle, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(handle, &[&open, &sync, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main").menu(&menu).tooltip("daVIT").show_menu_on_left_click(false);
    if let Some(icon) = handle.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.on_menu_event(|h, e| match e.id.as_ref() {
        "open" => show_main(h),
        "sync" => {
            let _ = h.emit("tray-sync", ());
        }
        "quit" => h.exit(0),
        _ => {}
    })
    .on_tray_icon_event(|t, e| {
        if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = e {
            show_main(t.app_handle());
        }
    })
    .build(handle)?;
    Ok(())
}

fn safe_name(name: &str) -> String {
    let n: String = name.chars().map(|c| if "<>:\"/\\|?*".contains(c) || c.is_control() { '_' } else { c }).collect();
    let n = n.trim().trim_matches('.').to_string();
    if n.is_empty() {
        "file".to_string()
    } else {
        n
    }
}

fn downloads() -> Option<PathBuf> {
    dirs::download_dir().map(|d| d.join("daVIT"))
}

/// Fetches a file VTOP serves into the preview cache, ready to read here. [save] also keeps a
/// copy in Downloads/daVIT.
#[tauri::command]
async fn fetch_file(app: State<'_, App>, kind: String, args: BTreeMap<String, String>, key: String, save: bool) -> Out<Value> {
    let folder = app.store.previews().join(safe_name(&key));
    let existing = std::fs::read_dir(&folder).ok().and_then(|mut d| d.find_map(|e| e.ok().map(|e| e.path()).filter(|p| p.is_file())));
    let path = match existing {
        Some(p) => p,
        None => {
            std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
            let tmp = folder.join(".part");
            let mut v = app.vtop.lock().await;
            let (name, kind_of) = v.fetch_file(&kind, &args, &tmp).await.map_err(err)?;
            keep_session(&app, &v).await;
            let mut name = safe_name(&name.unwrap_or_else(|| key.clone()));
            if kind_of == "application/pdf" && !name.to_lowercase().ends_with(".pdf") {
                name.push_str(".pdf");
            }
            let target = folder.join(name);
            std::fs::rename(&tmp, &target).map_err(|e| e.to_string())?;
            target
        }
    };
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let mut saved = Value::Null;
    if save {
        let dir = downloads().ok_or("No Downloads folder on this computer.")?;
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let copy = dir.join(&name);
        std::fs::copy(&path, &copy).map_err(|e| e.to_string())?;
        saved = json!(copy.to_string_lossy());
    }
    Ok(json!({ "path": path.to_string_lossy(), "name": name, "pdf": name.to_lowercase().ends_with(".pdf"), "saved": saved }))
}

/// Opens a file daVIT fetched in whatever opens its type. Only ever one of ours.
#[tauri::command]
async fn open_file(app: State<'_, App>, path: String) -> Out<()> {
    let p = ours(&app, &path)?;
    open::that_detached(p).map_err(|_| "Nothing on this computer opens that file.".to_string())
}

#[tauri::command]
fn open_link(url: String) -> Out<()> {
    if !url.starts_with("https://") && !url.starts_with("http://") && !url.starts_with("mailto:") {
        return Err("That isn't a web link.".into());
    }
    open::that_detached(url).map_err(|e| e.to_string())
}

/// The newest release on GitHub, for "a new version is out". Sends nothing about you.
#[tauri::command]
async fn latest_version() -> Out<Option<String>> {
    let client = reqwest::Client::builder().user_agent("daVIT").build().map_err(|e| e.to_string())?;
    let resp = client.get("https://api.github.com/repos/medriid/davit/releases/latest").send().await.map_err(|e| e.to_string())?;
    let v: Value = resp.json().await.map_err(|e| e.to_string())?;
    Ok(v["tag_name"].as_str().map(|t| t.trim_start_matches('v').to_string()))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // Opening daVIT again brings back the one already in the tray.
        .plugin(tauri_plugin_single_instance::init(|h, _, _| show_main(h)))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .setup(|tauri_app| {
            let dir = tauri_app.path().app_data_dir()?;
            let cache = tauri_app.path().app_cache_dir()?;
            let store = Store::new(dir, cache);
            let mut v = Vtop::new(MODEL.and_then(Solver::parse), BASE);
            if let Some((u, p)) = store.credentials() {
                v.set_credentials(u, p);
                if let Some(s) = store.read(SESSION) {
                    v.restore_session(&s);
                }
            }
            let snapshot = store.read(SNAPSHOT).and_then(|t| serde_json::from_str(&t).ok());
            tauri_app.manage(App { vtop: Mutex::new(v), store, snapshot: Mutex::new(snapshot), background: AtomicBool::new(false), tray: AtomicBool::new(false) });
            // Some Linux desktops have no tray; then closing the window quits, as you'd expect.
            let tray = make_tray(tauri_app.handle()).is_ok();
            tauri_app.state::<App>().tray.store(tray, Ordering::Relaxed);
            if !std::env::args().any(|a| a == "--hidden") {
                show_main(tauri_app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let app = window.state::<App>();
                if app.background.load(Ordering::Relaxed) && app.tray.load(Ordering::Relaxed) {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            status, sign_in, sign_out, sync_now, section, form_read, vtop_send, yours_load, yours_save, notify, files_list, file_delete, save_made, qr_encode, qr_decode,
            set_background, set_autostart, autostart_on, tray_text, save_pages, fetch_file, open_file, open_link, latest_version
        ])
        .run(tauri::generate_context!())
        .expect("daVIT");
}
