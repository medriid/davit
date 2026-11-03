//! VTOP's forms as its pages render them, and what it says back: the Android `Forms`,
//! `PaperSeeing` and upload parsers, held to them by `tests/parity.rs`.

use super::html::*;
use super::parsers as p;
use scraper::{ElementRef, Html};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct FormField {
    pub name: String,
    pub label: String,
    /// Text, LongText, Number, Choice, Date (yyyy-MM-dd), VtopDate (dd-MMM-yyyy) or Time (HH:mm).
    pub kind: String,
    pub value: String,
    pub options: Vec<(String, String)>,
    pub max_length: Option<i32>,
    pub required: bool,
    pub read_only: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct VtopForm {
    pub id: String,
    pub fields: Vec<FormField>,
    pub hidden: Vec<(String, String)>,
    pub control: String,
    pub submit_to: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct FormPage {
    pub form: Option<VtopForm>,
    pub message: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Reply {
    pub ok: bool,
    pub message: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PaperCourse {
    pub value: String,
    pub code: String,
    pub title: String,
    pub details: Vec<(String, String)>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PaperSeeingPage {
    pub message: String,
    pub courses: Vec<PaperCourse>,
    pub fields: Vec<(String, String)>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ClassFeedback {
    pub class_id: String,
    pub code: String,
    pub title: String,
    pub r#type: String,
    pub faculty: String,
    pub slot: String,
    pub venue: String,
    pub open: bool,
    pub given: String,
    pub max_length: i32,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LeaveForm {
    pub proctor: String,
    pub types: Vec<(String, String)>,
    pub fields: Vec<(String, String)>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct UploadForm {
    pub class_id: String,
    pub code: String,
    pub fields: Vec<(String, String)>,
    pub file_field: String,
    pub file_at: usize,
}

/// `{"kind":"Uploaded"|"NeedsOtp"|"Refused", ...}`, for the screen to step through.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all_fields = "camelCase")]
pub enum UploadOutcome {
    Uploaded { updated: Option<String> },
    NeedsOtp { email: String, fields: Vec<(String, String)>, otp_field: String, error: Option<String> },
    Refused { message: String },
}

fn by_id<'a>(doc: &'a Html, id: &str) -> Option<ElementRef<'a>> {
    doc.tree.nodes().filter_map(ElementRef::wrap).find(|e| e.value().attr("id") == Some(id))
}

fn visible<'a>(form: ElementRef<'a>) -> Vec<ElementRef<'a>> {
    all(form, "input, select, textarea")
        .into_iter()
        .filter(|e| !attr(*e, "name").is_empty() && !["hidden", "button", "submit", "reset", "image", "file", "checkbox"].contains(&attr(*e, "type").to_lowercase().as_str()))
        .collect()
}

fn label_for(e: ElementRef, doc: &Html) -> Option<String> {
    let id = attr(e, "id");
    if id.is_empty() {
        return None;
    }
    dall(doc, "label").into_iter().find(|l| attr(*l, "for") == id).map(text).filter(|t| !t.is_empty())
}

fn parent(e: ElementRef) -> Option<ElementRef> {
    e.parent().and_then(ElementRef::wrap)
}

fn label_of(e: ElementRef, doc: &Html) -> String {
    if let Some(l) = label_for(e, doc) {
        return l;
    }
    // The label in the same group: <div><label>From *</label><div><input></div></div>.
    let mut group = parent(e);
    for _ in 0..3 {
        let Some(g) = group else { continue };
        let name = g.value().name();
        if name == "td" || name == "form" {
            continue;
        }
        // A group holding other fields too would lend this one their label.
        if all(g, "input:not([type=hidden]), select, textarea").len() > 1 {
            continue;
        }
        if let Some(l) = first(g, "label").map(text).filter(|t| !t.is_empty()) {
            return l;
        }
        group = parent(g);
    }
    // label | field in a table row: the cell before.
    if let Some(td) = closest(e, "td") {
        let before = td.prev_siblings().filter_map(ElementRef::wrap).next();
        if let Some(t) = before.map(text).filter(|t| !t.is_empty()) {
            return t;
        }
    }
    let placeholder = attr(e, "placeholder");
    if placeholder.is_empty() { attr(e, "name").to_string() } else { placeholder.to_string() }
}

fn radio_text(r: ElementRef) -> String {
    let own: String = parent(r).map(|p| p.children().filter_map(|c| c.value().as_text().map(|t| t.to_string())).collect::<Vec<_>>().join("")).unwrap_or_default();
    let own = clean(&own);
    if !own.is_empty() {
        return own;
    }
    r.next_siblings().filter_map(ElementRef::wrap).next().map(text).unwrap_or_default()
}

fn group_label(first_radio: ElementRef) -> String {
    let mut at = parent(first_radio);
    for _ in 0..3 {
        let Some(a) = at else { break };
        if let Some(up) = parent(a) {
            let before: Vec<ElementRef> = up.children().filter_map(ElementRef::wrap).take_while(|c| c.id() != a.id()).collect();
            if let Some(l) = before.into_iter().rev().find(|c| c.value().name() == "label" || first(*c, "label").is_some()) {
                return text(l);
            }
        }
        at = parent(a);
    }
    String::new()
}

fn trim_label(s: &str) -> String {
    s.trim_end_matches(['*', ' ', ':']).to_string()
}

/// The page's own words: VTOP's `…Message` paragraphs, alerts and red text, or a short body.
pub fn message(doc: &Html) -> String {
    let mut own: Vec<String> = vec![];
    for e in dall(doc, "[id$=Message], .alert, .text-danger, span[style*=red], font[color=red], h3, h4") {
        let t = text(e);
        if !t.is_empty() && t != "-" && !own.contains(&t) {
            own.push(t);
        }
    }
    if !own.is_empty() {
        return own.join(" ").chars().take(300).collect();
    }
    doc_text(doc).chars().take(200).collect()
}

/// The form [id] (or the first form with a visible field) on the page.
pub fn read(html: &str, id: Option<&str>) -> FormPage {
    let doc = Html::parse_document(html);
    let named = id.and_then(|i| by_id(&doc, i));
    let form = named.or_else(|| dall(&doc, "form").into_iter().find(|f| !visible(*f).is_empty())).filter(|f| !visible(*f).is_empty());
    let Some(form) = form else { return FormPage { form: None, message: message(&doc) } };
    let mut fields = vec![];
    let mut radios: Vec<String> = vec![];
    for e in visible(form) {
        let name = attr(e, "name").to_string();
        let label = label_of(e, &doc);
        let required = e.value().attr("required").is_some() || label.ends_with('*');
        let read_only = e.value().attr("readonly").is_some() || e.value().attr("disabled").is_some();
        let max_length = attr(e, "maxlength").parse::<i32>().ok();
        let clean_label = trim_label(&label);
        let kind_attr = attr(e, "type").to_lowercase();
        match e.value().name() {
            "select" => {
                let options = all(e, "option").into_iter().filter_map(|o| {
                    let v = attr(o, "value").trim().to_string();
                    if v.is_empty() { None } else { Some((v, text(o))) }
                }).collect();
                let value = first(e, "option[selected]").map(|o| attr(o, "value").to_string()).unwrap_or_default();
                fields.push(FormField { name, label: clean_label, kind: "Choice".into(), value, options, max_length: None, required, read_only });
            }
            "textarea" => fields.push(FormField { name, label: clean_label, kind: "LongText".into(), value: text(e), options: vec![], max_length, required, read_only }),
            _ if kind_attr == "radio" => {
                if radios.contains(&name) {
                    continue;
                }
                radios.push(name.clone());
                let group: Vec<ElementRef> = all(form, "input").into_iter().filter(|r| attr(*r, "type").eq_ignore_ascii_case("radio") && attr(*r, "name") == name).collect();
                let options = group.iter().map(|r| {
                    let shown = label_for(*r, &doc).unwrap_or_else(|| radio_text(*r));
                    (attr(*r, "value").to_string(), if shown.is_empty() { attr(*r, "value").to_string() } else { shown })
                }).collect();
                let value = group.iter().find(|r| r.value().attr("checked").is_some()).map(|r| attr(*r, "value").to_string()).unwrap_or_default();
                let g = group_label(group[0]);
                fields.push(FormField { name, label: if g.is_empty() { clean_label } else { g }, kind: "Choice".into(), value, options, max_length: None, required: true, read_only });
            }
            _ => {
                let low = name.to_lowercase();
                let kind = match kind_attr.as_str() {
                    "date" => "Date",
                    "time" => "Time",
                    "number" => "Number",
                    _ if low.contains("date") || e.value().has_class("datepicker", scraper::CaseSensitivity::AsciiCaseInsensitive) => "VtopDate",
                    "text" if low.contains("time") => "Time",
                    _ => "Text",
                };
                fields.push(FormField { name, label: clean_label, kind: kind.into(), value: attr(e, "value").to_string(), options: vec![], max_length, required, read_only });
            }
        }
    }
    let hidden = all(form, "input[type=hidden]").into_iter().filter(|h| !attr(*h, "name").is_empty()).map(|h| (attr(h, "name").to_string(), attr(h, "value").to_string())).collect();
    let form_id = attr(form, "id").to_string();
    let wanted = if form_id.is_empty() { "-" } else { form_id.as_str() };
    let button = all(form, "[data-urltext]").into_iter().find(|b| attr(*b, "data-isprimarycontrol") == "true" || attr(*b, "data-formname") == form_id)
        .or_else(|| dall(&doc, "[data-urltext]").into_iter().find(|b| attr(*b, "data-formname") == wanted));
    FormPage {
        form: Some(VtopForm {
            id: form_id,
            fields,
            hidden,
            control: button.map(|b| attr(b, "data-controlname").to_string()).unwrap_or_default(),
            submit_to: button.map(|b| attr(b, "data-urltext").to_string()).unwrap_or_default(),
        }),
        message: message(&doc),
    }
}

/// VTOP's answer to a request: its message box, `#info`/`#jsonBom` values, or the page's text.
pub fn reply(html: &str) -> Reply {
    let doc = Html::parse_document(html);
    let mut stated: Vec<String> = ["info", "jsonBom", "success", "message"]
        .iter()
        .filter_map(|id| by_id(&doc, id))
        .map(|e| if e.value().name() == "input" { clean(attr(e, "value")) } else { text(e) })
        .collect();
    let said = re(r#"var\s+(?:info|infoMessage)\s*=\s*["']([^"']+)["']"#);
    for s in dall(&doc, "script") {
        let data: String = s.text().collect();
        stated.extend(said.captures_iter(&data).map(|m| clean(&m[1])));
    }
    stated.retain(|t| !t.is_empty() && t != "null");
    let mut words = stated.join(" ");
    if words.is_empty() {
        words = message(&doc);
    }
    if words.is_empty() {
        words = doc_text(&doc);
    }
    if words.is_empty() {
        words = "VTOP sent back an empty page.".to_string();
    }
    let lower = words.to_lowercase();
    let bad = ["already", "invalid", "error", "not ", "cannot", "can't", "fail", "please", "kindly", "required", "exceed", "closed"];
    let good = ["success", "applied", "submitted", "saved", "registered"];
    Reply { ok: good.iter().any(|g| lower.contains(g)) && !bad.iter().any(|b| lower.contains(b)), message: words.chars().take(300).collect() }
}

fn field_of(e: ElementRef) -> Option<(String, String)> {
    let name = attr(e, "name");
    if name.is_empty() || e.value().attr("disabled").is_some() {
        return None;
    }
    let value = match e.value().name() {
        "select" => first(e, "option[selected]").or_else(|| first(e, "option")).map(|o| attr(o, "value").to_string()).unwrap_or_default(),
        "textarea" => text(e),
        _ => match attr(e, "type").to_lowercase().as_str() {
            "file" | "button" | "submit" | "reset" | "image" => return None,
            "checkbox" | "radio" => {
                e.value().attr("checked")?;
                let v = attr(e, "value");
                if v.is_empty() { "on".to_string() } else { v.to_string() }
            }
            _ => attr(e, "value").to_string(),
        },
    };
    Some((name.to_string(), value))
}

pub fn form_fields(form: ElementRef) -> Vec<(String, String)> {
    all(form, "input, select, textarea").into_iter().filter_map(field_of).collect()
}

fn fields_before(form: ElementRef, input: ElementRef) -> usize {
    all(form, "input, select, textarea").into_iter().take_while(|e| e.id() != input.id()).filter(|e| field_of(*e).is_some()).count()
}

pub fn paper_seeing(html: &str) -> PaperSeeingPage {
    let doc = Html::parse_document(html);
    let message = by_id(&doc, "jsonBom").map(|e| clean(attr(e, "value"))).unwrap_or_default();
    let fields = by_id(&doc, "paperSeeingView").map(|f| form_fields(f).into_iter().filter(|x| x.0 != "frontValue").collect()).unwrap_or_default();
    let mut courses = vec![];
    for b in dall(&doc, "input[name=frontValue]") {
        let value = attr(b, "value");
        if value.trim().is_empty() {
            continue;
        }
        let Some(tr) = closest(b, "tr") else { continue };
        let row = cells(tr);
        let headers = closest(tr, "table").map(|t| table(t).headers).unwrap_or_default();
        let mut details = vec![];
        for (i, v) in row.iter().enumerate() {
            let head = headers.get(i).cloned().unwrap_or_default();
            let low = head.to_lowercase();
            if v.is_empty() || head.is_empty() || low.contains("select") || head == "#" || low.contains("s.no") || (low.contains("sl") && v.chars().all(char::is_numeric)) {
                continue;
            }
            details.push((head, v.clone()));
        }
        let col = |words: &[&str]| details.iter().find(|(h, _)| words.iter().any(|w| h.to_lowercase().contains(&w.to_lowercase()))).map(|x| x.1.clone()).unwrap_or_default();
        courses.push(PaperCourse { value: value.to_string(), code: col(&["Course Code", "Code"]), title: col(&["Course Title", "Title", "Course Name"]), details: details.clone() });
    }
    PaperSeeingPage { message, courses, fields }
}

/// `academics/common/processViewFeedbackList`: a box per class, while open a textarea and a Submit.
pub fn class_feedback(html: &str) -> Vec<ClassFeedback> {
    let doc = Html::parse_document(html);
    let mut out: Vec<ClassFeedback> = vec![];
    for h in dall(&doc, "h5") {
        let has_class_table = |p: &ElementRef| all(*p, "table").into_iter().any(|t| text(t).contains("Class Number"));
        let Some(bx) = h.ancestors().filter_map(ElementRef::wrap).find(has_class_table) else { continue };
        let mut spans: Vec<String> = all(h, "span span").into_iter().map(text).filter(|t| !t.is_empty()).collect();
        if spans.is_empty() {
            spans.push(text(h));
        }
        let (code, title) = match spans[0].split_once(" - ") {
            Some((a, b)) => (a.trim().to_string(), b.trim().to_string()),
            None => (spans[0].trim().to_string(), String::new()),
        };
        let r#type = spans.get(1).map(|t| t.trim_matches(['(', ')', ' ']).to_string()).unwrap_or_default();
        let Some(class_table) = all(bx, "table").into_iter().find(|t| text(*t).contains("Class Number")) else { continue };
        let t = table(class_table);
        let area = first(bx, "textarea[id^=courseFeedback_]");
        let button = first(bx, "button[onclick*=processSaveCourseFeedback]");
        let from_button = button.map(|b| attr(b, "value").to_string()).filter(|v| !v.trim().is_empty());
        let from_area = area.map(|a| attr(a, "id").trim_start_matches("courseFeedback_").to_string());
        let from_row = if t.rows.is_empty() { None } else { Some(t.value(0, t.find(&["Class Number"]))) };
        let Some(class_id) = from_button.or(from_area).or(from_row) else { continue };
        let cell = |words: &[&str]| if t.rows.is_empty() { String::new() } else { t.value(0, t.find(words)) };
        let nil = |v: String| if v.eq_ignore_ascii_case("NIL") { String::new() } else { v };
        let given = if area.is_none() {
            all(bx, "table").into_iter().find(|d| text(*d).contains("Description")).map(table).and_then(|d| d.rows.first().and_then(|r| r.first().cloned())).unwrap_or_default()
        } else {
            String::new()
        };
        if out.iter().any(|c| c.class_id == class_id) {
            continue;
        }
        out.push(ClassFeedback {
            class_id, code, title, r#type,
            faculty: nil(cell(&["Faculty"])),
            slot: nil(cell(&["Slot"])),
            venue: nil(cell(&["Venue"])),
            open: area.is_some() && button.is_some(),
            given,
            max_length: 100,
        });
    }
    out
}

/// `hostels/student/leave/2`: `#LeaveRequestForm` with its `leaveCode` choices; "PROCTOR NAME: X (id)" above it.
pub fn leave_form(html: &str) -> Option<LeaveForm> {
    let doc = Html::parse_document(html);
    let form = by_id(&doc, "LeaveRequestForm").or_else(|| dall(&doc, "form").into_iter().find(|f| first(*f, "select[name=leaveCode]").is_some()))?;
    let types = all(form, "select[name=leaveCode] option").into_iter().filter_map(|o| {
        let v = attr(o, "value").trim().to_string();
        if v.is_empty() { None } else { Some((v, text(o))) }
    }).collect();
    let words = doc_text(&doc);
    let proctor = re(r"(?i)PROCTOR NAME\s*:\s*(.+?\(\s*\w+\s*\))").captures(&words).map(|m| m[1].trim().to_string()).unwrap_or_default();
    let fields = all(form, "input[type=hidden]").into_iter().filter(|e| !attr(*e, "name").is_empty()).map(|e| (attr(e, "name").to_string(), attr(e, "value").to_string())).collect();
    Some(LeaveForm { proctor, types, fields })
}

pub fn leave_outcome(html: &str) -> Reply {
    let doc = Html::parse_document(html);
    let mut words = doc_text(&doc);
    if words.is_empty() {
        words = "VTOP sent back an empty page.".to_string();
    }
    let lower = words.to_lowercase();
    let bad = ["already", "invalid", "error", "not ", "cannot", "can't", "fail", "please", "kindly", "required"];
    let good = ["success", "applied", "submitted", "saved"];
    Reply { ok: good.iter().any(|g| lower.contains(g)) && !bad.iter().any(|b| lower.contains(b)), message: words.chars().take(300).collect() }
}

fn warnings(doc: &Html) -> Option<String> {
    let mut said: Vec<String> = vec![];
    for e in dall(doc, "span[style*=red], font[color=red], .text-danger, .alert-danger, .alert") {
        let t = text(e);
        if !t.is_empty() && t != "-" && !said.contains(&t) {
            said.push(t);
        }
    }
    if said.is_empty() { None } else { Some(said.join(" ")) }
}

/// `#daUpload` with its file input; a page without one says why.
pub fn upload_form(html: &str, class_id: &str, code: &str) -> Result<UploadForm, String> {
    let doc = Html::parse_document(html);
    let has_file = |f: &ElementRef| first(*f, "input[type=file]").is_some();
    let form = by_id(&doc, "daUpload").filter(has_file).or_else(|| dall(&doc, "form").into_iter().find(has_file));
    let Some(form) = form else {
        return Err(warnings(&doc).unwrap_or_else(|| "VTOP didn't open the upload form for this assignment.".to_string()));
    };
    let file = first(form, "input[type=file]").ok_or("VTOP didn't open the upload form for this assignment.")?;
    let name = attr(file, "name");
    Ok(UploadForm {
        class_id: class_id.to_string(),
        code: code.to_string(),
        fields: form_fields(form),
        file_field: if name.is_empty() { "studDaUpload".to_string() } else { name.to_string() },
        file_at: fields_before(form, file),
    })
}

/// "Uploaded successfully" anywhere means done; an OTP form means a replacement is waiting for
/// the emailed code; anything else is VTOP's message.
pub fn upload_outcome(html: &str, code: &str, error_header: Option<&str>, message_header: Option<&str>) -> UploadOutcome {
    let doc = Html::parse_document(html);
    let words = doc_text(&doc);
    if words.to_lowercase().contains("uploaded successfully") {
        return UploadOutcome::Uploaded { updated: p::assignments(html).into_iter().find(|a| a.code == code).and_then(|a| a.updated) };
    }
    let by_input = dfirst(&doc, "input[name=otpEmail]").and_then(|i| closest(i, "form"));
    if let Some(otp) = by_id(&doc, "daUploadOtpAlert").or(by_input) {
        let input = first(otp, "input[name=otpEmail]").or_else(|| all(otp, "input").into_iter().find(|i| ["", "text", "number", "password", "tel"].contains(&attr(*i, "type").to_lowercase().as_str())));
        let name = input.map(|i| attr(i, "name")).filter(|n| !n.is_empty()).unwrap_or("otpEmail").to_string();
        let said = warnings(&doc).filter(|w| {
            let w = w.to_lowercase();
            ["invalid", "incorrect", "wrong", "expired", "mismatch", "not valid"].iter().any(|x| w.contains(x))
        });
        let email = re(r"[A-Za-z0-9._%+*-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}").find(&words).map(|m| m.as_str().to_string()).unwrap_or_default();
        return UploadOutcome::NeedsOtp { email, fields: form_fields(otp).into_iter().filter(|f| f.0 != name).collect(), otp_field: name, error: said };
    }
    let header = [message_header, error_header].into_iter().flatten().map(str::trim).find(|h| !h.is_empty() && h.parse::<i64>().is_err());
    let message = header.map(str::to_string).or_else(|| warnings(&doc)).unwrap_or_else(|| "VTOP didn't confirm the upload. Check the assignment on VTOP.".to_string());
    UploadOutcome::Refused { message }
}

/// VTOP's mess complaint page: its notes, or why the form isn't there.
pub fn mess_complaint_notes(html: &str) -> Result<Vec<String>, String> {
    let doc = Html::parse_document(html);
    if by_id(&doc, "messComplaintForm").is_none() {
        let said = message(&doc);
        return Err(if said.is_empty() { "VTOP isn't taking mess complaints right now.".to_string() } else { said });
    }
    let note = dall(&doc, "span").into_iter().find(|s| text(*s).contains("Note"));
    let whole: String = note.map(|n| n.text().collect::<Vec<_>>().join("\n")).unwrap_or_default();
    let number = re(r"^\d+\.\s*");
    Ok(whole.split('\n').map(|l| number.replace(&clean(l), "").to_string()).filter(|l| !l.is_empty() && !l.to_lowercase().starts_with("note")).collect())
}
