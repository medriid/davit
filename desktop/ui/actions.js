// Everything that sends something to VTOP. Each reads VTOP's own form, shows you all of it, and
// goes only on the confirm button: that button is the one caller of `vtop_send`.
"use strict";

const VTOP_DAY = /^(\d{2})-([A-Za-z]{3})-(\d{4})$/;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** VTOP's labels are often SHOUTED: short ones become "Raman K", longer ones a sentence. */
function sentence(s) {
  const up = (s.match(/\p{Lu}/gu) || []).length;
  const low = (s.match(/\p{Ll}/gu) || []).length;
  if (up <= low) return s;
  const words = s.trim().split(/\s+/);
  if (words.length <= 3 && !s.includes("(")) return words.map((w) => (w.length === 1 ? w : w[0] + w.slice(1).toLowerCase())).join(" ");
  const t = s.trim().toLowerCase();
  return t[0].toUpperCase() + t.slice(1);
}

/** VTOP's label without its asides: "Enter Visa Place" becomes "Visa Place". */
const fieldTitle = (f) => f.label.replace(/^(Please |Enter |enter |Select |Choose )/, "").replace(/\s*\([^)]*\)/g, "").trim() || f.label;

/** A field's value as a person reads it: the option's label, a spelled-out date or time. */
function fieldShown(f, value) {
  const v = (value ?? "").trim();
  if (f.kind === "Choice") {
    const o = f.options.find((x) => x[0] === value);
    return o ? sentence(o[1]) : v;
  }
  if (f.kind === "Date" || f.kind === "VtopDate") {
    const d = f.kind === "Date" ? new Date(`${v}T00:00`) : Dates.vtop(v);
    return d && !Number.isNaN(+d) ? d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" }) : v;
  }
  if (f.kind === "Time" && /^\d{1,2}:\d{2}$/.test(v)) {
    const [hh, mm] = v.split(":").map(Number);
    return Dates.clock(hh * 60 + mm);
  }
  return v;
}

/** The first thing missing, in the form's words; null when it can be sent. */
function firstProblem(fields, values) {
  const f = fields.find((x) => x.required && !x.readOnly && !(values[x.name] ?? "").trim());
  if (!f) return null;
  return f.kind === "Choice" ? `Pick ${fieldTitle(f).toLowerCase()}.` : `Fill in ${fieldTitle(f).toLowerCase()}.`;
}

/** Yyyy-mm-dd to VTOP's dd-MMM-yyyy, and back, so a date box can stand in for its text one. */
const toVtopDay = (iso) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return m ? `${m[3]}-${MONTHS[Number(m[2]) - 1]}-${m[1]}` : iso;
};
const fromVtopDay = (v) => {
  const m = VTOP_DAY.exec(v || "");
  if (!m) return "";
  const i = MONTHS.findIndex((x) => x.toLowerCase() === m[2].toLowerCase());
  return i < 0 ? "" : `${m[3]}-${String(i + 1).padStart(2, "0")}-${m[1]}`;
};

/** Every field of a VTOP form, each in the app's own control. The value goes as VTOP's input would send it. */
function formFields(fields, values, onchange) {
  const box = h("div.stack");
  const draw = () => {
    box.replaceChildren(...fields.map((f) => {
      const v = values[f.name] ?? f.value ?? "";
      const set = (next) => { values[f.name] = next; onchange(f.name, next); };
      if (f.kind === "Choice") {
        const opts = f.options;
        return h("div", null, label(fieldTitle(f)), h("div", { style: { marginTop: "4px" } }, opts.length <= 8
          ? choiceRow(opts.map((o) => sentence(o[1])), opts.findIndex((o) => o[0] === v), (i) => { set(opts[i][0]); draw(); })
          : h("select.field", { "aria-label": fieldTitle(f), onchange: (e) => set(e.target.value) }, h("option", { value: "" }, "Choose"), opts.map((o) => h("option", { value: o[0], selected: o[0] === v }, sentence(o[1]))))));
      }
      if (f.kind === "LongText") {
        return h("label", null, label(fieldTitle(f) + (f.maxLength ? ` · ${v.length}/${f.maxLength}` : "")), h("textarea.field", { rows: 3, maxlength: f.maxLength, disabled: f.readOnly, oninput: (e) => set(e.target.value) }, v));
      }
      const type = f.kind === "Date" || f.kind === "VtopDate" ? "date" : f.kind === "Time" ? "time" : f.kind === "Number" ? "number" : "text";
      const shown = f.kind === "VtopDate" ? fromVtopDay(v) : v;
      return h("label", null, label(fieldTitle(f)), h("input.field", {
        type, value: shown, maxlength: f.maxLength, disabled: f.readOnly,
        oninput: (e) => set(f.kind === "VtopDate" ? toVtopDay(e.target.value) : e.target.value),
      }));
    }));
  };
  draw();
  return box;
}

/** The last step before anything reaches VTOP: everything that will be sent, and one Send. */
function sendReview(question, lines, sendLabel, onsend, onedit, warning) {
  const note = h("div.small.err", { role: "alert" });
  const send = h("button.btn.solid.wide", { type: "button", onclick: async () => {
    send.disabled = edit.disabled = true;
    send.textContent = "Sending";
    note.textContent = "";
    try {
      await onsend();
    } catch (e) {
      note.textContent = e;
    }
    send.disabled = edit.disabled = false;
    send.textContent = sendLabel;
  } }, sendLabel);
  const edit = h("button.btn.wide", { type: "button", onclick: onedit }, "Edit");
  return h("div.review", null, h("hr.rule"), label(question),
    h("table.reviewlines", null, h("tbody", null, lines.filter((l) => (l[1] ?? "").toString().trim()).map(([k, v]) => h("tr", null, h("th", null, k), h("td", null, v))))),
    warning ? h("div.small.err", null, warning) : null,
    note, h("div.stack", { style: { marginTop: "10px" } }, send, edit));
}

/** What VTOP said back, in its words, until you dismiss it. */
function replyCard(reply, ondismiss) {
  return h("div.card", { style: { marginBottom: "12px", "--p": reply.ok ? "var(--teal)" : "var(--coral)" }, role: "status" },
    label(reply.ok ? "Sent" : "VTOP said"), h("div", { style: { margin: "6px 0 10px" } }, reply.message), button("OK", ondismiss));
}

const errorNote = (e, retry) => h("div.stack", null, h("div.err", null, String(e)), retry ? h("div", null, button("Try again", retry)) : null);

/** A file picked here, read for the upload. */
function readPicked(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve({ name: file.name, mime: file.type, size: file.size, data: String(r.result).split(",")[1] || "" });
    r.onerror = () => reject(new Error("Couldn't read that file."));
    r.readAsDataURL(file);
  });
}

const sizeText = (n) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${Math.round(n / 1024)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

// ---- assignment upload ----------------------------------------------------------------------------

const Uploads = {
  MAX: 4 * 1024 * 1024,
  TYPES: ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"],
  /** Where each assignment's upload is; kept here so leaving the screen doesn't lose an OTP step. */
  state: new Map(),
};

function screenAssignment(s, args) {
  const course = s.assignments.find((c) => c.classId === args.classId);
  const a = course?.items.find((x) => x.code === args.code);
  const page = h("div.page");
  if (!course || !a) {
    page.append(...pageTitle("Assignment", null, button("Back", () => history.back())).flat().filter(Boolean), empty("This assignment isn't in the last sync any more."));
    return page;
  }
  const today = Dates.addDays(new Date(), 0);
  const day = Dates.vtop(a.due);
  const n = day ? Dates.between(today, day) : null;
  page.append(...pageTitle("Assignment", course.courseName, button("Back", () => history.back())).flat().filter(Boolean),
    h("div.card.assignments", null,
      h("div.row", null, h("div.grow", null, h("div.strong", { style: { fontSize: "18px" } }, a.title), small(`${course.courseCode} · ${course.courseType}`)),
        a.updated ? h("span.badge", null, "Done") : n != null && n >= 0 ? h("span.badge", { class: n <= 3 ? "on" : "" }, dayWord(n)) : null),
      h("hr.rule"),
      h("div.strong", null, [day && `Due ${Dates.short(day)}`, a.max != null && `out of ${num(a.max)}`, a.weightage != null && `${num(a.weightage)}% of the course`].filter(Boolean).join(" · ")),
      small(a.updated ? `Last uploaded ${a.updated}` : "Nothing uploaded yet")));
  if (!a.code) {
    page.append(small("VTOP's page gave no code for this one, so it can't be downloaded or uploaded from here. Use VTOP for it."));
    return page;
  }
  if (a.hasQuestion || a.hasSubmission) {
    page.append(h("h2", null, "Files"), h("div.card.assignments", null,
      a.hasQuestion ? fileRow("Question paper", "From your faculty", "question", { code: a.code, classId: course.classId }, `qp_${course.classId}_${a.code}`) : null,
      a.hasSubmission ? fileRow("Your submission", a.updated ? `Uploaded ${a.updated}` : null, "submission", { code: a.code, classId: course.classId }, `da_${course.classId}_${a.code}`) : null));
  }
  const replacing = !!(a.updated || a.hasSubmission);
  page.append(h("h2", null, replacing ? "Replace your file" : "Upload"), uploadPanel({ classId: course.classId, code: a.code, title: a.title, course: course.courseCode, semester: s.semester.id, replacing, updated: a.updated, canUpload: a.canUpload, past: day && day < today }));
  return page;
}

function uploadPanel(target) {
  const key = `${target.classId}|${target.code}`;
  const box = h("div");
  const set = (st) => { if (st) Uploads.state.set(key, st); else Uploads.state.delete(key); draw(); };
  const input = h("input", { type: "file", hidden: true, accept: Uploads.TYPES.map((x) => `.${x}`).join(","), onchange: async (e) => {
    const f = e.target.files[0];
    e.target.value = "";
    if (!f) return;
    const ext = f.name.split(".").pop().toLowerCase();
    if (!Uploads.TYPES.includes(ext)) return set({ step: "failed", message: "VTOP takes PDF, Word, Excel or PowerPoint." });
    if (f.size > Uploads.MAX) return set({ step: "failed", message: `That file is ${sizeText(f.size)}. VTOP takes up to 4 MB.` });
    set({ step: "ready", file: await readPicked(f) });
  } });
  const scan = () => go("scanner", { target });
  const draw = () => {
    const st = Uploads.state.get(key);
    if (!st && !target.canUpload) {
      box.replaceChildren(small(target.past ? "The deadline has passed, so VTOP doesn't take uploads for this any more." : "VTOP isn't offering an upload for this one right now."));
      return;
    }
    if (!st) {
      box.replaceChildren(input, small(`PDF, Word or Excel, up to 4 MB.${target.replacing ? " Replacing your file needs a 6-digit code VTOP emails you; you'll type it here." : ""}`),
        h("div.row.wrap", { style: { marginTop: "10px" } }, button(target.replacing ? "New file" : "Choose file", () => input.click(), "solid"), button("Scan", scan)),
        small("Scan turns pictures of your pages into one PDF, cleaned up and compressed under 4 MB."));
      return;
    }
    box.replaceChildren(input, uploadStep(target, st, set, () => input.click(), scan));
  };
  draw();
  return box;
}

/** One step after a file is picked: the confirm (the only thing that sends), VTOP's emailed code, done, or what went wrong. */
function uploadStep(target, st, set, pick, scan) {
  if (st.step === "ready") {
    return h("div.card", null, label("Upload this?"), h("div.strong", { style: { fontSize: "17px", marginTop: "6px" } }, st.file.name),
      small(`${sizeText(st.file.size)} · as "${target.title}"`),
      st.file.note ? h("div.small.strong", null, st.file.note) : null,
      target.replacing ? h("div.small", { style: { marginTop: "6px" } }, `This replaces the file you uploaded${target.updated ? ` on ${target.updated}` : ""}. VTOP will email you a code to confirm it.`) : null,
      h("div.stack", { style: { marginTop: "12px" } },
        h("button.btn.solid.wide", { type: "button", onclick: async () => {
          set({ step: "sending" });
          try {
            const r = await api("vtop_send", { action: "upload", args: { classId: target.classId, code: target.code, semester: target.semester }, file: { name: st.file.name, mime: st.file.mime, data: st.file.data } });
            if (r.kind === "Uploaded") {
              set({ step: "done", updated: r.updated, replaced: target.replacing });
              sync(false);
            } else if (r.kind === "NeedsOtp") {
              set({ step: "otp", file: st.file.name, ...r });
            } else {
              set({ step: "failed", message: r.message });
            }
          } catch (e) {
            set({ step: "failed", message: String(e) });
          }
        } }, target.replacing ? "Yes, replace it" : "Yes, upload"),
        h("div.row", null, pick ? button("Another file", pick) : null, button("Cancel", () => set(null)))));
  }
  if (st.step === "sending") return h("div.card", null, h("div.strong", null, "Sending to VTOP"), h("div.bar", null, h("i", { style: { width: "40%" } })), small("Waiting for VTOP"));
  if (st.step === "otp") {
    const note = h("div.small.err", { role: "alert" }, st.error || "");
    const code = h("input.field", { inputmode: "numeric", maxlength: 6, autocomplete: "one-time-code", "aria-label": "6-digit code", placeholder: "6-digit code", oninput: (e) => { e.target.value = e.target.value.replace(/\D/g, ""); confirmBtn.disabled = e.target.value.length !== 6; } });
    const confirmBtn = h("button.btn.solid.wide", { type: "button", disabled: true, onclick: async () => {
      confirmBtn.disabled = true;
      try {
        const r = await api("vtop_send", { action: "uploadOtp", args: { fields: st.fields, otpField: st.otpField, otp: code.value, code: target.code } });
        if (r.kind === "Uploaded") {
          set({ step: "done", updated: r.updated, replaced: true });
          sync(false);
        } else if (r.kind === "NeedsOtp") {
          set({ ...st, ...r, step: "otp" });
        } else {
          set({ step: "failed", message: r.message });
        }
      } catch (e) {
        note.textContent = e;
        confirmBtn.disabled = false;
      }
    } }, "Confirm code");
    return h("div.card.assignments", null, label("Check your email"),
      small(`VTOP emailed a 6-digit code to ${st.email || "your VIT email"}. Enter it to replace your file with ${st.file}. Until then your old file stays.`),
      h("div", { style: { marginTop: "10px" } }, code), note,
      h("div.stack", { style: { marginTop: "10px" } }, confirmBtn,
        h("button.btn.wide", { type: "button", onclick: async () => { await api("vtop_send", { action: "uploadCancel", args: { classId: target.classId } }).catch(() => {}); set(null); } }, "Cancel, keep the old file")));
  }
  if (st.step === "done") {
    return h("div.card", { style: { "--p": "var(--teal)" } },
      h("div.row", null, h("div.grow", null, label(st.replaced ? "Replaced" : "Uploaded"), h("div.strong", { style: { marginTop: "6px" } }, 'VTOP said "Uploaded successfully".'), st.updated ? small(`Last updated ${st.updated}`) : null),
        h("div.stamp", { "aria-hidden": "true" }, "Done")),
      h("div", { style: { marginTop: "10px" } }, button("OK", () => set(null))));
  }
  return h("div.card", { style: { "--p": "var(--coral)" } }, label("Not uploaded"), h("div", { style: { margin: "6px 0 10px" } }, st.message),
    h("div.row.wrap", null, pick ? button("Choose", pick, "solid") : null, scan ? button("Scan", scan) : null, button("Close", () => set(null))));
}

// ---- requests -------------------------------------------------------------------------------------

/** A VTOP form that opens on request: Start, the form, Review, Send. */
function formFlow(opts) {
  const box = h("div");
  let page = null;
  let values = {};
  let reviewing = false;
  let reply = null;
  const open = async (btn) => {
    if (btn) { btn.disabled = true; btn.textContent = "Opening VTOP's form"; }
    try {
      page = await opts.read();
      values = {};
      for (const f of page.form?.fields || []) values[f.name] = f.value;
      reviewing = false;
      draw();
    } catch (e) {
      box.replaceChildren(errorNote(e, () => open()));
    }
  };
  const draw = () => {
    const parts = [reply ? replyCard(reply, () => { reply = null; draw(); }) : null];
    const form = page?.form;
    if (!form) {
      parts.push(page ? h("div.card.vtop", { style: { marginBottom: "10px" } }, page.message || opts.closed) : null, small(opts.intro),
        h("div", { style: { marginTop: "10px" } }, h("button.btn.solid", { type: "button", onclick: (e) => open(e.target) }, page ? "Check again" : opts.start)));
    } else {
      const fields = form.fields.filter((f) => !(opts.hide || []).includes(f.name));
      const problem = firstProblem(fields, values);
      parts.push(h("div.card.vtop", null,
        formFields(fields, values, () => { if (reviewing) { reviewing = false; draw(); } else if (firstProblem(fields, values) !== problem) draw(); }),
        problem ? h("div.small.err", { style: { marginTop: "10px" } }, problem) : null,
        reviewing && !problem
          ? sendReview(opts.question, fields.map((f) => [fieldTitle(f), fieldShown(f, values[f.name])]), opts.send || "Send to VTOP", async () => {
            reply = await opts.submit(values);
            if (reply.ok) { page = null; reviewing = false; opts.done?.(); }
            draw();
          }, () => { reviewing = false; draw(); }, opts.warning)
          : h("div.stack", { style: { marginTop: "12px" } },
            h("button.btn.solid.wide", { type: "button", disabled: !!problem, onclick: () => { reviewing = true; draw(); } }, "Review"),
            h("button.btn.wide", { type: "button", onclick: () => { page = null; draw(); } }, "Discard"))));
    }
    box.replaceChildren(...parts.flat().filter(Boolean));
  };
  draw();
  return box;
}

function screenBonafide() {
  const page = h("div.page");
  page.append(...pageTitle("Bonafide", "Apply for a bonafide certificate on VTOP. It goes only after you review it and press Apply.", button("Back", () => history.back())).flat().filter(Boolean));
  const body = h("div", null, empty("Reading VTOP's bonafide form"));
  page.append(body);
  let kinds = null;
  let code = "";
  let form = null;
  let values = {};
  let reviewing = false;
  let reply = null;
  let message = "";
  const load = async () => {
    try {
      kinds = await api("form_read", { name: "bonafideKinds" });
      draw();
    } catch (e) {
      body.replaceChildren(errorNote(e, load));
    }
  };
  const pick = async (c) => {
    code = c;
    form = null;
    message = "";
    reviewing = false;
    draw();
    try {
      const p = await api("form_read", { name: "bonafide", args: { code: c } });
      form = p.form;
      message = p.message;
      values = {};
      for (const f of form?.fields || []) values[f.name] = f.value;
    } catch (e) {
      message = String(e);
    }
    draw();
  };
  const draw = () => {
    const parts = [reply ? replyCard(reply, () => { reply = null; draw(); }) : null,
      label("What it's for"),
      h("select.field", { "aria-label": "What it's for", style: { margin: "4px 0 12px" }, onchange: (e) => e.target.value && pick(e.target.value) }, h("option", { value: "" }, "Choose"), kinds.map(([v, t]) => h("option", { value: v, selected: v === code }, sentence(t))))];
    if (code && !form) parts.push(message ? small(message) : empty("Opening that form on VTOP"));
    if (form) {
      const fields = form.fields.filter((f) => f.name !== "certificateCode");
      const problem = firstProblem(fields.map((f) => ({ ...f, required: true })), values);
      parts.push(h("h2", null, "Details"), h("div.card.vtop", null,
        formFields(fields, values, () => { if (reviewing) { reviewing = false; draw(); } else if (firstProblem(fields.map((f) => ({ ...f, required: true })), values) !== problem) draw(); }),
        fields.some((f) => f.readOnly) ? small("VTOP sets the locked dates for this kind.") : null,
        problem ? h("div.small.err", { style: { marginTop: "10px" } }, problem) : null,
        reviewing && !problem
          ? sendReview("Apply for this bonafide on VTOP?", [["Service", sentence(kinds.find((k) => k[0] === code)?.[1] || "")], ...fields.map((f) => [fieldTitle(f), fieldShown(f, values[f.name])])], "Apply on VTOP", async () => {
            reply = await api("vtop_send", { action: "bonafide", args: { code, values } });
            if (reply.ok) { code = ""; form = null; reviewing = false; }
            draw();
          }, () => { reviewing = false; draw(); })
          : h("div", { style: { marginTop: "12px" } }, h("button.btn.solid.wide", { type: "button", disabled: !!problem, onclick: () => { reviewing = true; draw(); } }, "Review"))));
    }
    body.replaceChildren(...parts.flat().filter(Boolean));
  };
  load();
  return page;
}

function screenCourseFeedback() {
  const page = h("div.page");
  const top = pageTitle("Course feedback", "A note to each class's teacher, any time. VTOP keeps it as sent.", button("Back", () => history.back()));
  page.append(...top.flat().filter(Boolean));
  const body = h("div", null, empty("Reading your classes from VTOP"));
  page.append(body);
  let reply = null;
  const load = async () => {
    try {
      const d = await api("form_read", { name: "feedback" });
      body.replaceChildren(...[reply ? replyCard(reply, () => { reply = null; load(); }) : null,
        small("Say what worked or didn't and why: VTOP asks for specific, polite feedback, and it can't be changed once sent."),
        d.classes.length ? h("div.grid2", { style: { marginTop: "12px" } }, d.classes.map((c) => feedbackCard(c, d.semester.id, (r) => { reply = r; load(); }))) : empty("VTOP lists no classes for feedback."),
      ].filter(Boolean));
    } catch (e) {
      body.replaceChildren(errorNote(e, load));
    }
  };
  load();
  return page;
}

function feedbackCard(c, semester, onreply) {
  const names = Names.all();
  const box = h("div");
  let text = "";
  let reviewing = false;
  const draw = () => {
    if (!c.open) {
      box.replaceChildren(small(c.given ? `Sent: ${c.given}` : "Feedback sent."));
      return;
    }
    const count = label(`Your feedback · ${text.length}/${c.maxLength}`);
    const action = () => (reviewing
      ? sendReview("Send this to VTOP?", [["Class", [c.code, c.type].filter(Boolean).join(" · ")], ["Feedback", text.trim()]], "Send feedback", async () => {
        const r = await api("vtop_send", { action: "feedback", args: { semester, classId: c.classId, text } });
        reviewing = false;
        onreply(r);
      }, () => { reviewing = false; draw(); }, "VTOP won't let you change or delete it afterwards.")
      : h("div", { style: { marginTop: "10px" } }, h("button.btn.solid", { type: "button", disabled: !text.trim(), onclick: () => { reviewing = true; draw(); } }, "Review")));
    let slot = action();
    box.replaceChildren(count, h("textarea.field", { rows: 3, maxlength: c.maxLength, "aria-label": "Your feedback", oninput: (e) => {
      text = e.target.value;
      count.textContent = `Your feedback · ${text.length}/${c.maxLength}`;
      reviewing = false;
      const next = action();
      slot.replaceWith(next);
      slot = next;
    } }, text), slot);
  };
  draw();
  return h("div.card.classes", null,
    h("div.strong", null, names[c.code] || c.title || c.code),
    small([c.code, c.type, c.slot].filter(Boolean).join(" · ")),
    c.faculty ? small(titleCase(c.faculty.split(" - ")[0])) : null,
    h("div", { style: { marginTop: "10px" } }, box));
}

function screenPaperSeeing() {
  const page = h("div.page");
  page.append(...pageTitle("Paper seeing", "See your answer paper, or ask for a re-evaluation. VTOP raises a fee for it, paid on VTOP.", button("Back", () => history.back())).flat().filter(Boolean));
  const body = h("div", null, empty("Reading VTOP's paper seeing form"));
  page.append(body);
  let form = null;
  const values = {};
  let courses = null;
  let chosen = new Set();
  let reviewing = false;
  let reply = null;
  let error = null;
  const load = async () => {
    try {
      const p = await api("form_read", { name: "paperSeeing" });
      form = p.form;
      if (!form) { body.replaceChildren(empty(p.message || "VTOP didn't show the paper seeing form.")); return; }
      for (const f of form.fields) values[f.name] = f.value;
      draw();
    } catch (e) {
      body.replaceChildren(errorNote(e, load));
    }
  };
  const draw = () => {
    const ready = values.semesterSubId && values.serviceType && values.examType;
    const field = (n) => form.fields.find((f) => f.name === n);
    const parts = [reply ? replyCard(reply, () => { reply = null; draw(); }) : null,
      error ? h("div.err", null, error) : null,
      h("div.card.vtop", null,
        formFields(form.fields, values, () => { courses = null; chosen = new Set(); reviewing = false; error = null; draw(); }),
        h("div", { style: { marginTop: "12px" } }, h("button.btn.solid.wide", { type: "button", disabled: !ready, onclick: async (e) => {
          e.target.disabled = true;
          e.target.textContent = "Asking VTOP";
          try {
            courses = await api("form_read", { name: "paperCourses", args: { semester: values.semesterSubId, service: values.serviceType, exam: values.examType } });
            chosen = new Set();
          } catch (err) {
            error = String(err);
          }
          draw();
        } }, "Show courses")),
        ready ? null : small(firstProblem(form.fields.map((f) => ({ ...f, required: true })), values) || ""))];
    if (courses) {
      parts.push(h("h2", null, "Courses"));
      if (!courses.courses.length) parts.push(empty(courses.message || "VTOP offers no courses for this."));
      else {
        parts.push(h("div.stack", null, courses.courses.map((c) => {
          const on = chosen.has(c.value);
          return h("button.card.click.vtop", { type: "button", "aria-pressed": on, onclick: () => { if (on) chosen.delete(c.value); else chosen.add(c.value); reviewing = false; draw(); } },
            h("div.row", null, h("div.grow", null, h("div.strong", null, Names.all()[c.code] || c.title || c.code || c.value), small(c.details.filter(([k, v]) => v !== c.title && !/title/i.test(k)).map(([k, v]) => (/code/i.test(k) ? v : `${k}: ${v}`)).join(" · "))), h("span.badge", { class: on ? "on" : "" }, on ? "Picked" : "Pick")));
        })));
        parts.push(reviewing && chosen.size
          ? sendReview("Send this request to VTOP?", [
            ["Service", fieldShown(field("serviceType"), values.serviceType)], ["Semester", fieldShown(field("semesterSubId"), values.semesterSubId)], ["Exam", fieldShown(field("examType"), values.examType)],
            ["Courses", courses.courses.filter((c) => chosen.has(c.value)).map((c) => c.code || c.title).join(", ")],
          ], "Send request", async () => {
            reply = await api("vtop_send", { action: "paperSeeing", args: { semester: values.semesterSubId, service: values.serviceType, exam: values.examType, chosen: [...chosen] } });
            if (reply.ok) { courses = null; chosen = new Set(); }
            reviewing = false;
            draw();
          }, () => { reviewing = false; draw(); }, "VTOP raises a fee invoice for this when it's sent. Pay it on VTOP; daVIT never pays anything.")
          : h("div", { style: { marginTop: "12px" } }, h("button.btn.solid.wide", { type: "button", disabled: !chosen.size, onclick: () => { reviewing = true; draw(); } }, "Review")));
      }
    }
    body.replaceChildren(...parts.flat().filter(Boolean));
  };
  load();
  return page;
}

// ---- hostel ---------------------------------------------------------------------------------------

function leaveApplication(onsent) {
  const box = h("div");
  let form = null;
  let reply = null;
  let error = null;
  let reviewing = false;
  const stamp = (d, hh) => { const c = new Date(d); c.setHours(hh, 0, 0, 0); return c; };
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const from = stamp(Dates.addDays(new Date(), 1), 9);
  const to = stamp(Dates.addDays(new Date(), 2), 18);
  const v = { type: "", place: "", reason: "", fromDate: iso(from), fromTime: "09:00", toDate: iso(to), toTime: "18:00" };
  const problems = () => {
    const a = new Date(`${v.fromDate}T${v.fromTime}`);
    const b = new Date(`${v.toDate}T${v.toTime}`);
    return [!v.type && "Pick a leave type.", !v.place.trim() && "Say where you're going.", !v.reason.trim() && "Give a reason.", a < Date.now() - 300000 && "Leave can't start in the past.", !(b > a) && "Return has to be after leaving."].filter(Boolean);
  };
  const draw = () => {
    const parts = [reply ? replyCard(reply, () => { reply = null; draw(); }) : null];
    if (!form) {
      parts.push(small("daVIT reads VTOP's leave form (its leave types and your proctor) when you start, and sends nothing until you review it and press Send."),
        error ? h("div.err", { style: { marginTop: "8px" } }, error) : null,
        h("div", { style: { marginTop: "10px" } }, h("button.btn.solid", { type: "button", onclick: async (e) => {
          e.target.disabled = true;
          e.target.textContent = "Opening VTOP's form";
          error = null;
          try {
            const f = await api("form_read", { name: "leave" });
            if (!f || !f.types.length) error = "VTOP didn't show a leave form (it's for hostellers, and closes at times).";
            else form = f;
          } catch (err) {
            error = String(err);
          }
          draw();
        } }, "Start an application")));
    } else {
      const issue = problems();
      const typeName = () => form.types.find((t) => t[0] === v.type)?.[1] || "";
      const refresh = () => { reviewing = false; const next = problems()[0] || ""; note.textContent = next; review.disabled = !!next; slot.replaceChildren(review, discard); };
      const note = h("div.small.err", { style: { marginTop: "10px" } }, issue[0] || "");
      const review = h("button.btn.solid.wide", { type: "button", disabled: !!issue.length, onclick: () => { reviewing = true; draw(); } }, "Review");
      const discard = h("button.btn.wide", { type: "button", onclick: () => { form = null; reviewing = false; draw(); } }, "Discard");
      const slot = h("div.stack", { style: { marginTop: "12px" } }, review, discard);
      const when = (l, dk, tk) => h("div.row.wrap", null, h("div.grow", { style: { minWidth: "130px" } }, label(l)),
        h("input.field", { type: "date", "aria-label": `${l} date`, value: v[dk], style: { width: "170px" }, oninput: (e) => { v[dk] = e.target.value; refresh(); } }),
        h("input.field", { type: "time", "aria-label": `${l} time`, value: v[tk], style: { width: "130px" }, oninput: (e) => { v[tk] = e.target.value; refresh(); } }));
      parts.push(h("div.card.vtop", null,
        form.proctor ? small(`Goes to your proctor, ${titleCase(form.proctor)}`) : null,
        h("div.stack", { style: { marginTop: "10px" } },
          h("div", null, label("Leave type"), h("div", { style: { marginTop: "4px" } }, choiceRow(form.types.map((t) => sentence(t[1])), form.types.findIndex((t) => t[0] === v.type), (i) => { v.type = form.types[i][0]; reviewing = false; draw(); }))),
          h("label", null, label("Visiting place"), h("input.field", { maxlength: 80, value: v.place, oninput: (e) => { v.place = e.target.value; refresh(); } })),
          when("Leaving", "fromDate", "fromTime"), when("Back", "toDate", "toTime"),
          h("label", null, label("Reason"), h("input.field", { maxlength: 200, value: v.reason, oninput: (e) => { v.reason = e.target.value; refresh(); } }))),
        reviewing && !issue.length
          ? sendReview("Send this to VTOP?", [["Type", sentence(typeName())], ["Place", v.place.trim()], ["Leaving", `${fieldShown({ kind: "Date" }, v.fromDate)}, ${fieldShown({ kind: "Time" }, v.fromTime)}`], ["Back", `${fieldShown({ kind: "Date" }, v.toDate)}, ${fieldShown({ kind: "Time" }, v.toTime)}`], ["Reason", v.reason.trim()]], "Send to VTOP", async () => {
            reply = await api("vtop_send", { action: "leave", args: { values: { leaveCode: v.type, visitingPlace: v.place.trim(), leaveFromDate: v.fromDate, fromTime: v.fromTime, leaveToDate: v.toDate, toTime: v.toTime, reason: v.reason.trim() } } });
            if (reply.ok) { form = null; reviewing = false; v.place = ""; v.reason = ""; onsent?.(); }
            draw();
          }, () => { reviewing = false; draw(); })
          : [note, slot]));
    }
    box.replaceChildren(...parts.flat(Infinity).filter(Boolean));
  };
  draw();
  return box;
}

function messComplaint() {
  const box = h("div");
  let notes = null;
  let reply = null;
  let error = null;
  let text = "";
  let file = null;
  let reviewing = false;
  const input = h("input", { type: "file", hidden: true, accept: ".jpg,.jpeg,.png,.pdf", onchange: async (e) => {
    const f = e.target.files[0];
    e.target.value = "";
    if (!f) return;
    if (f.size > 5 * 1024 * 1024) { error = "The photo or PDF has to be under 5 MB."; draw(); return; }
    file = await readPicked(f);
    error = null;
    reviewing = false;
    draw();
  } });
  const draw = () => {
    const parts = [input, reply ? replyCard(reply, () => { reply = null; draw(); }) : null, error ? h("div.err", { style: { marginBottom: "8px" } }, error) : null];
    if (!notes) {
      parts.push(small("Reads VTOP's mess complaint page first. Nothing goes until you review it."),
        h("div", { style: { marginTop: "10px" } }, h("button.btn", { type: "button", onclick: async (e) => {
          e.target.disabled = true;
          e.target.textContent = "Reading VTOP";
          error = null;
          try {
            notes = await api("form_read", { name: "messComplaint" });
          } catch (err) {
            error = String(err);
          }
          draw();
        } }, "Make a complaint")));
    } else {
      const count = label(`The complaint · ${text.length}/200`);
      const go1 = h("button.btn.solid.wide", { type: "button", disabled: !text.trim() || !file, onclick: () => { reviewing = true; draw(); } }, "Review");
      parts.push(h("div.card.vtop", null, notes.length ? h("div.stack", { style: { gap: "2px", marginBottom: "10px" } }, notes.map((n) => small(n))) : null,
        count, h("textarea.field", { rows: 3, maxlength: 200, oninput: (e) => { text = e.target.value; count.textContent = `The complaint · ${text.length}/200`; go1.disabled = !text.trim() || !file; } }, text),
        h("div.row.wrap", { style: { marginTop: "8px" } }, button(file ? "Another file" : "Add a photo or PDF", () => input.click()), file ? small(`${file.name} · ${sizeText(file.size)}`) : null),
        reviewing
          ? sendReview("Send this complaint to VTOP?", [["Complaint", text.trim()], ["File", file?.name]], "Send to VTOP", async () => {
            reply = await api("vtop_send", { action: "messComplaint", args: { text }, file: { name: file.name, mime: file.mime, data: file.data } });
            if (reply.ok) { text = ""; file = null; notes = null; }
            reviewing = false;
            draw();
          }, () => { reviewing = false; draw(); })
          : h("div", { style: { marginTop: "12px" } }, go1)));
    }
    box.replaceChildren(...parts.flat(Infinity).filter(Boolean));
  };
  draw();
  return box;
}

function screenHostel(s) {
  const hostel = s?.profile?.hostel;
  const sub = hostel ? [hostel.block, hostel.room && `Room ${hostel.room}`, hostel.mess].filter(Boolean).join(" · ") : "Your requests, status and history.";
  const status = (t) => h("span.badge", { class: /reject|cancel|denied/i.test(t || "") ? "on" : "" }, sentence(t || ""));
  const slips = (l) => h("div.stack", null, l.map((x) => h("div.card.vtop", null, h("div.row", null, h("div.grow", null, h("div.strong", null, x.venue || x.purpose), small([x.from, x.time].filter(Boolean).join(" · ")), x.purpose ? small(x.purpose) : null), status(x.status)))));
  const leaves = (l) => h("div.stack", null, l.map((x) => h("div.card.vtop", null, h("div.row", null, h("div.grow", null, h("div.strong", null, x.place || x.type), small(`${x.from} to ${x.to}`), small([x.type, x.reason].filter(Boolean).join(" · "))), status(x.status)))));
  const list = (active, past, draw, none) => (active.length || past.length ? [draw(active), past.length ? [h("div.label", { style: { margin: "12px 0 6px" } }, "History"), draw([...past].reverse().slice(0, 12))] : null] : [empty(none)]);
  const reload = () => { api("section", { name: "hostel", arg: null, force: true }).then(() => { if (App.route === "hostel") render(); }).catch(() => {}); };
  const messBox = h("div");
  sectionInto(messBox, "mess", (m) => (m ? h("div.card.vtop", null, h("div.row", null, h("div.grow", null, m.lines[0] ? h("div.strong", null, m.lines[0]) : null, m.lines.slice(1).map((l) => small(sentence(l))), m.title ? small(m.title) : null), h("span.badge", { class: m.open ? "on" : "" }, m.open ? "Change open" : "Change closed"))) : small("VTOP's mess page said nothing.")));
  const body = h("div");
  sectionInto(body, "hostel", (d) => [
    h("h2", null, "Leave"), list(d.activeLeave, d.pastLeave, leaves, "No leave requests."),
    h("h2", null, "Night slips"), list(d.activeSlips, d.pastSlips, slips, "No night slips."),
  ]);
  return h("div.page", null, pageTitle("Hostel", sub, button("Refresh", () => { messBox.replaceChildren(empty("Reading VTOP…")); api("section", { name: "hostel", arg: null, force: true }).finally(() => render()); })),
    h("h2", null, "Mess"), messBox, small("Changing your mess happens on VTOP."),
    h("div", { style: { marginTop: "8px" } }, button("Mess menu", () => go("mess"))),
    h("h2", null, "Apply for leave"), leaveApplication(reload),
    body,
    h("h2", null, "Apply for a night slip"),
    formFlow({
      start: "Start a night slip",
      intro: "daVIT reads VTOP's night slip form when you start, and sends nothing until you review it and press Send.",
      closed: "VTOP isn't taking night slips right now.",
      question: "Send this night slip to VTOP?",
      read: () => api("form_read", { name: "nightSlip" }),
      submit: (values) => api("vtop_send", { action: "nightSlip", args: { values } }),
      done: reload,
    }),
    h("h2", null, "Mess complaint"), messComplaint());
}

// ---- club events ----------------------------------------------------------------------------------

const eventDays = (d) => (d || "").replace(/\s+to\s+/i, " – ");

function screenEvents(s) {
  const page = h("div.page");
  let reply = null;
  const body = h("div");
  const draw = (force) => {
    body.replaceChildren(empty("Reading VTOP's event registration"));
    api("section", { name: "clubs", arg: null, force: force || null }).then((r) => {
      const { page: p, general } = r.data;
      const open = p.upcoming.filter((e) => e.canRegister);
      const shut = p.upcoming.filter((e) => !e.canRegister);
      body.replaceChildren(...[
        reply ? replyCard(reply, () => { reply = null; draw(); }) : null,
        p.registered.length ? [h("h2", null, "Registered"), h("div.stack", null, p.registered.map((e) => h("div.card.events", null,
          h("div.row", null, h("div.grow", null, h("div.strong", null, e.title), small([clubName(e.club), eventDays(e.dates)].filter(Boolean).join(" · "))), e.status ? h("span.badge", null, sentence(e.status)) : null),
          e.participation ? fileRow("Participation certificate", null, "eventCertificate", { eventId: e.id, winner: "false" }, `ev_${e.id}`) : null,
          e.winner ? fileRow("Winner certificate", null, "eventCertificate", { eventId: e.id, winner: "true" }, `evw_${e.id}`) : null)))] : null,
        h("h2", null, "Open for registration"),
        open.length ? h("div.grid2", null, open.map((e) => listingCard(e, (rep) => { reply = rep; draw(true); }))) : empty("No club event is taking registrations right now."),
        shut.length ? [h("h2", null, "Registration over"), h("div.card.events", null, shut.map((e, i) => [i ? h("hr.hair") : null, h("div.row", null, h("div.grow", null, h("div.strong", null, e.title), small([clubName(e.club), eventDays(e.dates)].filter(Boolean).join(" · "))), h("span.badge", null, sentence(e.state || "Closed")))]))] : null,
        p.board.length || general.length ? [h("h2", null, "Certificates"), h("div.card.events", null,
          p.board.map((b) => fileRow(`${sentence(b.position)}, ${clubName(b.club)}`, b.year, "boardCertificate", { associationId: b.associationId, generatedYear: b.generatedYear, roleId: b.roleId }, `evb_${b.associationId}_${b.generatedYear}_${b.roleId}`)),
          general.map((g) => fileRow(g.name, "General event", "generalCertificate", { eventId: g.eventId, certificateId: g.certificateId, participantId: g.participantId }, `evg_${g.eventId}_${g.certificateId}`)))] : null,
      ].flat(Infinity).filter(Boolean));
    }, (e) => body.replaceChildren(errorNote(e, () => draw(true))));
  };
  draw();
  page.append(...pageTitle("Club events", "Registering goes to VTOP only after you confirm it.", button("Refresh", () => draw(true))).flat().filter(Boolean), body);
  return page;
}

function listingCard(e, onreply) {
  const box = h("div", { style: { marginTop: "10px" } });
  const ask = () => box.replaceChildren(sendReview("Register for this on VTOP?", [["Event", e.title], ["Club", clubName(e.club)], ["When", [eventDays(e.dates), e.time].filter(Boolean).join(" · ")]], "Register", async () => {
    onreply(await api("vtop_send", { action: "event", args: { eventId: e.id } }));
  }, plain));
  const plain = () => box.replaceChildren(button("Register", ask, "solid"));
  plain();
  return h("div.card.events", null,
    h("div.strong", { style: { fontSize: "17px" } }, e.title), small(clubName(e.club)),
    small([eventDays(e.dates), e.time].filter(Boolean).join(" · ")), e.venue ? small(e.venue) : null,
    e.registration ? small(`Registration: ${e.registration}`) : null,
    e.description ? h("details", { style: { marginTop: "6px" } }, h("summary.small.strong", { style: { cursor: "pointer" } }, "More"), small(e.description)) : null,
    box);
}

// ---- the scanner ----------------------------------------------------------------------------------

/** Pictures of pages into one PDF: from files or a webcam, cropped, turned and cleaned, under 4 MB. */
const Scan = {
  FILTERS: ["Clean", "Original", "Black and white"],
  pages: [],
  name: "",
  nextId: 1,

  /** Draws a page into a canvas at [scale]: crop (with perspective), turn, filter. */
  render(page, maxSide) {
    const src = page.image;
    const q = page.quad || [[0, 0], [1, 0], [1, 1], [0, 1]];
    const dist = (a, b) => Math.hypot((a[0] - b[0]) * src.width, (a[1] - b[1]) * src.height);
    let w = Math.max(dist(q[0], q[1]), dist(q[3], q[2]));
    let hh = Math.max(dist(q[0], q[3]), dist(q[1], q[2]));
    const k = Math.min(1, maxSide / Math.max(w, hh));
    w = Math.max(1, Math.round(w * k));
    hh = Math.max(1, Math.round(hh * k));
    const c = h("canvas", { width: w, height: hh });
    const x = c.getContext("2d");
    const flat = q.every((p, i) => p[0] === [0, 1, 1, 0][i] && p[1] === [0, 0, 1, 1][i]);
    if (flat) {
      x.drawImage(src, 0, 0, w, hh);
    } else {
      // Inverse-map each pixel through the four corners.
      const from = h("canvas", { width: src.width, height: src.height });
      const fx = from.getContext("2d");
      fx.drawImage(src, 0, 0);
      const s = fx.getImageData(0, 0, src.width, src.height);
      const d = x.createImageData(w, hh);
      for (let y = 0; y < hh; y++) {
        const v = y / Math.max(1, hh - 1);
        for (let px = 0; px < w; px++) {
          const u = px / Math.max(1, w - 1);
          const sx = (1 - v) * ((1 - u) * q[0][0] + u * q[1][0]) + v * ((1 - u) * q[3][0] + u * q[2][0]);
          const sy = (1 - v) * ((1 - u) * q[0][1] + u * q[1][1]) + v * ((1 - u) * q[3][1] + u * q[2][1]);
          const si = (Math.min(src.height - 1, Math.round(sy * (src.height - 1))) * src.width + Math.min(src.width - 1, Math.round(sx * (src.width - 1)))) * 4;
          const di = (y * w + px) * 4;
          d.data[di] = s.data[si];
          d.data[di + 1] = s.data[si + 1];
          d.data[di + 2] = s.data[si + 2];
          d.data[di + 3] = 255;
        }
      }
      x.putImageData(d, 0, 0);
    }
    const turned = page.turn % 2 ? h("canvas", { width: hh, height: w }) : h("canvas", { width: w, height: hh });
    const t = turned.getContext("2d");
    t.translate(turned.width / 2, turned.height / 2);
    t.rotate((page.turn * Math.PI) / 2);
    t.drawImage(c, -w / 2, -hh / 2);
    if (page.filter !== 1) Scan.clean(turned, page.filter === 2);
    return turned;
  },

  /** Lifts the paper to white and the writing to dark, by how bright each part of the page is. */
  clean(c, mono) {
    const x = c.getContext("2d");
    const img = x.getImageData(0, 0, c.width, c.height);
    const d = img.data;
    const cell = Math.max(8, Math.round(Math.max(c.width, c.height) / 40));
    const cols = Math.ceil(c.width / cell);
    const rows = Math.ceil(c.height / cell);
    const bright = new Float32Array(cols * rows);
    for (let r = 0; r < rows; r++) for (let q = 0; q < cols; q++) {
      let best = 0;
      for (let y = r * cell; y < Math.min(c.height, (r + 1) * cell); y += 3) for (let px = q * cell; px < Math.min(c.width, (q + 1) * cell); px += 3) {
        const i = (y * c.width + px) * 4;
        best = Math.max(best, (d[i] + d[i + 1] + d[i + 2]) / 3);
      }
      bright[r * cols + q] = Math.max(best, 40);
    }
    for (let y = 0; y < c.height; y++) for (let px = 0; px < c.width; px++) {
      const i = (y * c.width + px) * 4;
      const b = bright[Math.floor(y / cell) * cols + Math.floor(px / cell)];
      if (mono) {
        const v = ((d[i] + d[i + 1] + d[i + 2]) / 3 / b) * 255 > 170 ? 255 : 0;
        d[i] = d[i + 1] = d[i + 2] = v;
      } else {
        for (let k = 0; k < 3; k++) d[i + k] = Math.min(255, Math.max(0, ((d[i + k] / b) * 255 - 40) * 1.2));
      }
    }
    x.putImageData(img, 0, 0);
  },

  /** A PDF of JPEG pages: each page's bytes as they are, wrapped by hand. */
  pdf(jpegs) {
    const enc = new TextEncoder();
    const parts = [];
    let at = 0;
    const offsets = [];
    const add = (p) => { const b = typeof p === "string" ? enc.encode(p) : p; parts.push(b); at += b.length; };
    const object = (n, head, stream) => {
      offsets[n] = at;
      add(`${n} 0 obj\n${head}\n`);
      if (stream) { add(stream); add("\nendstream\n"); }
      add("endobj\n");
    };
    add("%PDF-1.4\n");
    const count = jpegs.length;
    object(1, "<< /Type /Catalog /Pages 2 0 R >>");
    object(2, `<< /Type /Pages /Count ${count} /Kids [${jpegs.map((_, i) => `${3 + i * 3} 0 R`).join(" ")}] >>`);
    jpegs.forEach((j, i) => {
      const base = 3 + i * 3;
      // Fitted to A4 at the picture's own shape.
      const k = Math.min(595 / j.width, 842 / j.height);
      const [pw, ph] = [+(j.width * k).toFixed(2), +(j.height * k).toFixed(2)];
      const draw = enc.encode(`q ${pw} 0 0 ${ph} 0 0 cm /Im0 Do Q`);
      object(base, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${pw} ${ph}] /Resources << /XObject << /Im0 ${base + 1} 0 R >> >> /Contents ${base + 2} 0 R >>`);
      object(base + 1, `<< /Type /XObject /Subtype /Image /Width ${j.width} /Height ${j.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${j.bytes.length} >>\nstream`, j.bytes);
      object(base + 2, `<< /Length ${draw.length} >>\nstream`, draw);
    });
    const start = at;
    const total = 3 + count * 3;
    let xref = `xref\n0 ${total}\n0000000000 65535 f \n`;
    for (let n = 1; n < total; n++) xref += `${String(offsets[n]).padStart(10, "0")} 00000 n \n`;
    add(`${xref}trailer\n<< /Size ${total} /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`);
    const out = new Uint8Array(at);
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  },

  /** The scan as a PDF, stepping down size and quality until it's under the limit. */
  async build(limit = 3.9 * 1024 * 1024) {
    const tries = [[2200, 0.8], [1800, 0.72], [1500, 0.65], [1250, 0.58], [1050, 0.5], [850, 0.45], [700, 0.4]];
    let out = null;
    for (const [side, quality] of tries) {
      const jpegs = [];
      for (const p of Scan.pages) {
        const c = Scan.render(p, side);
        const uri = c.toDataURL("image/jpeg", quality);
        const bytes = Uint8Array.from(atob(uri.split(",")[1]), (ch) => ch.charCodeAt(0));
        jpegs.push({ bytes, width: c.width, height: c.height });
        await new Promise((f) => setTimeout(f));
      }
      out = Scan.pdf(jpegs);
      if (out.length <= limit) return { bytes: out, fits: true };
    }
    return { bytes: out, fits: false };
  },
};

const toBase64 = (bytes) => {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
};

function screenScanner(s, args) {
  const target = args.target || null;
  const page = h("div.page");
  if (!Scan.name) Scan.name = target ? target.title.replace(/[^\w -]/g, "").trim() || "Scan" : "Scan";
  let camera = null;
  let stream = null;
  let cropping = null;
  let result = null;
  let working = false;
  const stop = () => { stream?.getTracks().forEach((t) => t.stop()); stream = null; camera = null; };
  const addImage = (image) => { Scan.pages.push({ id: Scan.nextId++, image, turn: 0, filter: 0, quad: null }); };
  const input = h("input", { type: "file", hidden: true, multiple: true, accept: "image/*", onchange: async (e) => {
    for (const f of e.target.files) {
      try {
        addImage(await createImageBitmap(f));
      } catch {
        toast(`Couldn't read ${f.name}.`);
      }
    }
    e.target.value = "";
    draw();
  } });
  const openCamera = async () => {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 2560 }, height: { ideal: 1440 } } });
      camera = h("video", { autoplay: true, muted: true, playsinline: true, style: { width: "100%", maxHeight: "60vh", background: "#111" } });
      camera.srcObject = stream;
      draw();
    } catch {
      toast("No camera daVIT can use. Add pictures from files instead.");
    }
  };
  const shoot = async () => {
    const c = h("canvas", { width: camera.videoWidth, height: camera.videoHeight });
    c.getContext("2d").drawImage(camera, 0, 0);
    addImage(await createImageBitmap(c));
    draw();
  };
  const thumb = (p) => {
    const c = Scan.render(p, 420);
    c.className = "scanthumb";
    return c;
  };
  const draw = () => {
    if (cropping) { page.replaceChildren(cropView(cropping)); return; }
    const parts = [pageTitle("Scan", target ? `For "${target.title}". Goes to VTOP only after you confirm it.` : "Pictures of pages into one PDF.", button("Back", () => { stop(); history.back(); })), input];
    if (camera) {
      parts.push(h("div.card", null, camera, h("div.row.wrap", { style: { marginTop: "10px" } }, button("Take the page", shoot, "solid"), button("Done with the camera", () => { stop(); draw(); }))));
    } else {
      parts.push(h("div.row.wrap", null, button("Add pictures", () => input.click(), "solid"), button("Use the camera", openCamera)));
    }
    if (Scan.pages.length) {
      parts.push(h("h2", null, `${Scan.pages.length} page${Scan.pages.length > 1 ? "s" : ""}`), h("div.scanpages", null, Scan.pages.map((p, i) => h("div.card", { style: { padding: "8px" } }, thumb(p),
        h("div.chips", { style: { marginTop: "6px" } },
          button("Crop", () => { cropping = p; draw(); }), button("Turn", () => { p.turn = (p.turn + 1) % 4; draw(); }),
          button(Scan.FILTERS[p.filter], () => { p.filter = (p.filter + 1) % 3; draw(); }),
          i > 0 ? button("Earlier", () => { Scan.pages.splice(i - 1, 0, Scan.pages.splice(i, 1)[0]); draw(); }) : null,
          Scan.pages[i + 1] ? button("Join with next", async () => {
            const [a, b] = [Scan.render(p, 2200), Scan.render(Scan.pages[i + 1], 2200)];
            const width = Math.max(a.width, b.width);
            const c = h("canvas", { width, height: a.height + b.height });
            const x = c.getContext("2d");
            x.fillStyle = "#fff";
            x.fillRect(0, 0, c.width, c.height);
            x.drawImage(a, (width - a.width) / 2, 0);
            x.drawImage(b, (width - b.width) / 2, a.height);
            Scan.pages.splice(i, 2, { id: Scan.nextId++, image: await createImageBitmap(c), turn: 0, filter: 1, quad: null });
            draw();
          }) : null,
          button("Remove", () => { Scan.pages.splice(i, 1); draw(); })),
      ))),
      h("h2", null, "The PDF"),
      h("label", null, label("Name"), h("input.field", { value: Scan.name, maxlength: 60, style: { maxWidth: "420px" }, oninput: (e) => { Scan.name = e.target.value; } })));
      const fileName = () => `${(Scan.name.trim() || "Scan").replace(/\.pdf$/i, "")}.pdf`;
      const make = async (use) => {
        working = true;
        draw();
        const built = await Scan.build();
        working = false;
        if (!built.fits) toast("Even at the smallest size this is over 4 MB, so VTOP won't take it. Use fewer pages.");
        await use(built);
        draw();
      };
      if (working) parts.push(empty("Making the PDF"));
      else if (result) parts.push(result);
      else {
        parts.push(h("div.row.wrap", { style: { marginTop: "10px" } },
          target ? button(target.replacing ? "Replace on VTOP" : "Upload to VTOP", () => make(async (b) => {
            if (!b.fits) return;
            const key = `${target.classId}|${target.code}`;
            Uploads.state.set(key, { step: "ready", file: { name: fileName(), mime: "application/pdf", size: b.bytes.length, data: toBase64(b.bytes), note: `${Scan.pages.length} page${Scan.pages.length > 1 ? "s" : ""}, compressed to fit` } });
            Scan.pages = [];
            Scan.name = "";
            stop();
            history.back();
          }), "solid") : null,
          button("Save the PDF", () => make(async (b) => {
            const f = await api("save_made", { name: fileName(), data: toBase64(b.bytes), text: null });
            result = h("div.card", { style: { marginTop: "10px" } }, h("div.strong", null, `Saved in Downloads/daVIT · ${sizeText(b.bytes.length)}`), h("div.row.wrap", { style: { marginTop: "8px" } }, button("View", () => showFile(f, f.name)), button("Keep editing", () => { result = null; draw(); })));
          }))));
      }
    } else {
      parts.push(small("Photos work best flat on a table in good light. Add as many pages as you need; pictures can share a page."));
    }
    page.replaceChildren(...parts.flat(Infinity).filter(Boolean));
  };
  const cropView = (p) => {
    const q = (p.quad || [[0, 0], [1, 0], [1, 1], [0, 1]]).map((c) => [...c]);
    const cw = Math.min(760, innerWidth - 300);
    const k = cw / p.image.width;
    const c = h("canvas", { width: cw, height: Math.round(p.image.height * k), class: "cropcanvas", "aria-label": "Drag the four corners to the page's edges" });
    const x = c.getContext("2d");
    const paint = () => {
      x.drawImage(p.image, 0, 0, c.width, c.height);
      x.strokeStyle = "#f65058";
      x.lineWidth = 2;
      x.beginPath();
      q.forEach((pt, i) => { const m = i ? x.lineTo.bind(x) : x.moveTo.bind(x); m(pt[0] * c.width, pt[1] * c.height); });
      x.closePath();
      x.stroke();
      for (const pt of q) { x.beginPath(); x.arc(pt[0] * c.width, pt[1] * c.height, 9, 0, 7); x.fillStyle = "#f65058"; x.fill(); x.strokeStyle = "#fff"; x.stroke(); }
    };
    let held = -1;
    const at = (e) => { const r = c.getBoundingClientRect(); return [Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))]; };
    c.addEventListener("pointerdown", (e) => {
      const here = at(e);
      let best = 0.08;
      held = -1;
      q.forEach((pt, i) => { const d = Math.hypot(pt[0] - here[0], pt[1] - here[1]); if (d < best) { best = d; held = i; } });
      if (held >= 0) c.setPointerCapture(e.pointerId);
    });
    c.addEventListener("pointermove", (e) => { if (held >= 0) { q[held] = at(e); paint(); } });
    c.addEventListener("pointerup", () => { held = -1; });
    paint();
    return h("div", null, ...pageTitle("Crop", "Drag the four corners to the page's edges.").flat().filter(Boolean), c,
      h("div.row.wrap", { style: { marginTop: "10px" } },
        button("Done", () => { p.quad = q; cropping = null; draw(); }, "solid"), button("Reset", () => { p.quad = null; cropping = null; draw(); }), button("Cancel", () => { cropping = null; draw(); })));
  };
  draw();
  return page;
}
