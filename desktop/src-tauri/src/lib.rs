pub mod store;
pub mod sync;
pub mod vtop;

use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::PathBuf;
use store::Store;
use sync::Snapshot;
use tauri::{Emitter, Manager, State};
use tauri_plugin_notification::NotificationExt;
use tokio::sync::Mutex;
use vtop::captcha::Solver;
use vtop::client::{VtopError, Vtop, BASE};

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

struct App {
    vtop: Mutex<Vtop>,
    store: Store,
    snapshot: Mutex<Option<Snapshot>>,
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
async fn sync_now(window: tauri::Window, handle: tauri::AppHandle, app: State<'_, App>, everything: bool) -> Out<Snapshot> {
    let mut v = app.vtop.lock().await;
    let old = app.snapshot.lock().await.clone();
    let idle = app.store.read("session-at").and_then(|t| t.parse::<i64>().ok()).map(|t| chrono::Utc::now().timestamp() - t).unwrap_or(i64::MAX);
    let progress = |what: &str| {
        let _ = window.emit("sync-progress", what);
    };
    let new = sync::sync(&mut v, old.as_ref(), everything, idle > 3600, &progress).await.map_err(err)?;
    keep_session(&app, &v).await;
    let _ = app.store.write(SNAPSHOT, &serde_json::to_string(&new).unwrap_or_default());
    for (title, body) in sync::alerts(old.as_ref(), &new) {
        let _ = handle.notification().builder().title(title).body(body).show();
    }
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
            "money" => json!({ "receipts": v.receipts().await?, "letters": v.fee_letters().await.unwrap_or_default(), "library": v.library().await.unwrap_or_default() }),
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
    let p = PathBuf::from(&path).canonicalize().map_err(|_| "That file is gone.".to_string())?;
    let ok = [Some(app.store.previews().clone()), downloads()].into_iter().flatten().filter_map(|d| d.canonicalize().ok()).any(|d| p.starts_with(d));
    if !ok {
        return Err("daVIT only opens files it fetched.".into());
    }
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
        .plugin(tauri_plugin_notification::init())
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
            tauri_app.manage(App { vtop: Mutex::new(v), store, snapshot: Mutex::new(snapshot) });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![status, sign_in, sign_out, sync_now, section, fetch_file, open_file, open_link, latest_version])
        .run(tauri::generate_context!())
        .expect("daVIT");
}
