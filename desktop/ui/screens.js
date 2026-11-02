// The everyday screens: Home, Attendance, Academics and Week. Everything from VTOP goes in as
// text nodes, never as HTML.
"use strict";

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag.split(".")[0]);
  const classes = tag.split(".").slice(1);
  if (classes.length) el.className = classes.join(" ");
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className += (el.className ? " " : "") + v;
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k === "value") el.value = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid == null || kid === false) continue;
    el.append(kid.nodeType ? kid : String(kid));
  }
  return el;
}

const button = (label, onclick, cls = "") => h("button.btn", { class: cls, type: "button", onclick }, label);
const empty = (text) => h("div.empty", null, text);
const label = (text) => h("div.label", null, text);
const small = (...text) => h("div.small", null, ...text);

function attendanceBar(row) {
  const f = row.total === 0 ? 100 : (row.attended / row.total) * 100;
  return h("div.bar", { class: Rules.atRisk(row) ? "bad" : "", role: "img", "aria-label": `${Rules.percent(row)}% attendance` },
    h("i", { style: { width: `calc(${f}% - 2px)` } }), h("b"));
}

function pageTitle(title, sub, ...actions) {
  return [h("div.title", null, h("h1.grow", null, title), actions), sub ? h("div.sub", null, sub) : null];
}

// ---- files ----------------------------------------------------------------------------------------

/** A file VTOP serves. View reads it inside the app; Save keeps a copy in Downloads/daVIT. */
function fileRow(title, subtitle, kind, args, key) {
  const note = h("div.small.err");
  const fetch = async (save) => {
    note.textContent = "";
    view.disabled = keep.disabled = true;
    const was = view.textContent;
    view.textContent = "…";
    try {
      const f = await api("fetch_file", { kind, args, key, save });
      if (save) note.textContent = "";
      if (save) keep.textContent = "Saved";
      else showFile(f, title);
    } catch (e) {
      note.textContent = e;
    }
    view.textContent = was;
    view.disabled = keep.disabled = false;
  };
  const view = button("View", () => fetch(false), "solid");
  const keep = button("Save", () => fetch(true));
  return h("div", null,
    h("div.row", { style: { padding: "5px 0" } },
      h("div.grow", null, h("div.strong", null, title), subtitle ? small(subtitle) : null),
      keep, view),
    note);
}

function showFile(file, title) {
  const linux = /Linux/i.test(navigator.userAgent);
  if (!file.pdf || linux) {
    api("open_file", { path: file.path }).catch((e) => toast(e));
    return;
  }
  const close = () => box.remove();
  const box = h("div.viewer", { role: "dialog", "aria-label": title },
    h("header", null,
      button("Back", close),
      h("div.grow.strong.clip", null, title),
      button("Other app", () => api("open_file", { path: file.path }).catch((e) => toast(e)))),
    h("iframe", { src: window.__TAURI__.core.convertFileSrc(file.path), title }));
  box.addEventListener("keydown", (e) => { if (e.key === "Escape") close(); });
  document.body.append(box);
  box.querySelector("button").focus();
}

// ---- home -----------------------------------------------------------------------------------------

function classDetails(slot, isNext, plan, week, cal, now) {
  const m = slot.meeting;
  const mins = m.start - plan.minute;
  const status = m.end <= plan.minute ? "Done" : m.start <= plan.minute ? "On now"
    : (isNext ? "Next, " : "Later, ") + (mins < 60 ? `in ${mins} min` : `in ${Math.floor(mins / 60)} h ${mins % 60} min`);
  const a = slot.attendance;
  return h("div", { style: { marginTop: "12px" } },
    h("div.row", null,
      h("div.grow.strong.clip", null, courseName(slot.course.courseName)),
      slot.course.venue ? mapButton(slot.course.venue) : null),
    small(`${status} · ${slot.isLab ? "Lab" : "Theory"}`),
    a ? [attendanceBar(a), small(`${Rules.percent(a)}% · ${Rules.hint(a, remainingFor(a, week, cal, now))}`)] : null);
}

function mapButton(venue) {
  return button(venue, () => go("map", { find: venue }));
}

const courseName = (n) => n;

function screenHome(s) {
  const now = new Date();
  const week = weekOf(s, App.slots);
  const cal = calendarOf(s.calendar);
  const plan = planFor(week, now, cal);
  const page = h("div.page");
  const overall = Rules.overall(s.attendance);
  const risky = s.attendance.filter(Rules.atRisk).length;

  const dayNote = plan.day && !plan.day.instructional && plan.day.type !== "None" ? [plan.day.type, plan.day.note].filter(Boolean).join(" · ")
    : plan.day?.instructional && plan.day.order !== Dates.weekday(now) ? `Follows ${Dates.dayName(plan.day.order)}'s timetable` : null;
  const left = cal?.daysLeft(now);
  const summary = [dayNote, plan.today.length ? `${plan.done}/${plan.today.length} done` : "No classes today", left > 0 ? `${left} teaching days left` : null].filter(Boolean).join(" · ");

  const current = plan.now ? plan.today.indexOf(plan.now) : plan.today.findIndex((c) => c.meeting.start > plan.minute);
  const focus = current >= 0 ? current : plan.today.length - 1;
  let picked = focus;
  let opened = current >= 0;
  const detail = h("div");
  const draw = () => {
    path.replaceChildren(...plan.today.map((c, i) => stop(c, i)));
    detail.replaceChildren(...(opened && plan.today[picked] ? [classDetails(plan.today[picked], plan.today[picked] === plan.next && plan.inDays === 0, plan, week, cal, now)] : []));
  };
  const stop = (c, i) => {
    const done = c.meeting.end <= plan.minute;
    const live = c === plan.now;
    const risk = !done && c.attendance && Rules.atRisk(c.attendance);
    const state = live ? "now" : done ? "done" : risk ? `attend ${Rules.mustAttend(c.attendance)}` : c.course.venue || (c.isLab ? "lab" : "theory");
    return h("button.stop", {
      type: "button", class: [done ? "done" : "", live ? "now" : "", i === picked ? "pick" : ""].join(" "),
      "aria-label": `${c.course.courseName}, ${c.isLab ? "lab" : "theory"}, ${Dates.clock(c.meeting.start)} to ${Dates.clock(c.meeting.end)}, ${state}`,
      onclick: () => { picked = i; opened = true; draw(); },
    }, h("div.node", { class: c.isLab ? "lab" : "" }, Dates.clock(c.meeting.start)), h("div.name.clip", null, c.course.courseName), h("div.state.clip", null, state), h("div.mark"));
  };
  const path = h("div.path");

  const nextBlock = () => {
    if (!plan.next || plan.inDays === 0) return null;
    const n = plan.next;
    const day = plan.inDays === 1 ? "tomorrow" : Dates.dayName(Dates.weekday(plan.nextDate));
    const a = n.attendance;
    return [plan.today.length ? h("hr.rule") : h("div", { style: { height: "10px" } }),
      h("div.row", null,
        h("div.grow", null,
          label(`Next · ${day} ${Dates.clock(n.meeting.start)}${plan.today.length ? "" : ` – ${Dates.clock(n.meeting.end)}`}`),
          h("div.strong.clip", null, n.course.courseName)),
        n.course.venue ? mapButton(n.course.venue) : null),
      small([n.isLab ? "Lab" : "Theory", a ? `${Rules.percent(a)}% · ${Rules.hint(a, remainingFor(a, week, cal, now))}` : null].filter(Boolean).join(" · "))];
  };

  const today = Dates.addDays(now, 0);
  const upcomingExam = s.exams.map((e) => ({ e, d: Dates.vtop(e.date) })).filter((x) => x.d && x.d >= today).sort((a, b) => a.d - b.d)[0];
  const due = s.assignments.flatMap((c) => c.items.map((a) => ({ c, a, d: Dates.vtop(a.due) })))
    .filter((x) => !x.a.updated && x.d && x.d >= today && Dates.between(today, x.d) <= 14).sort((a, b) => a.d - b.d);
  const serious = s.feeds.remarks.filter((r) => /debar|revok/i.test(r.remark));
  const pending = s.feeds.feedback.filter((f) => !f.done);
  const reg = s.feeds.registration.find((w) => w.start && new Date(w.end || w.start) > now);
  const open = s.feeds.windows.filter((w) => w.open);

  page.append(...[
    h("div.grid2", null,
      h("div.card.classes", null,
        h("h3", { style: { fontWeight: 600, fontSize: "19px" } }, Dates.long(now)),
        small(summary),
        plan.today.length ? path : null,
        detail,
        nextBlock(),
        s.timetable.length ? null : small(`No timetable for ${s.semester.name} yet.`)),
      h("div.stack", null,
        h("div.row", { style: { gap: "11px" } },
          h("button.card.click.attendance.grow", { type: "button", onclick: () => go("attendance") }, h("span.big", null, overall == null ? "–" : `${overall}%`), " overall"),
          h("button.card.click.attendance.grow", { type: "button", onclick: () => go("attendance") }, h("span.big", { class: risky ? "bad" : "" }, risky), " at risk")),
        h("div.grid3", { style: { gridTemplateColumns: "repeat(3, minmax(0, 1fr))" } },
          [["Marks", "academics", { tab: 0 }], ["Assignments", "academics", { tab: 1 }], ["Materials", "academics", { tab: 4 }], ["Exam board", "academics", { tab: 2 }], ["Circulars", "circulars"], ["More", "more"]]
            .map(([t, r, a]) => h("button.card.click", { type: "button", onclick: () => go(r, a), style: { padding: "11px 12px" } }, h("span.strong", null, t)))),
        serious.length ? h("div.card", { style: { borderColor: "var(--coral)" } }, label("From VTOP"), serious.map((r) => small(`${r.name}: ${r.remark}`))) : null,
        reg ? h("div.card.events", null, label("Registration"), h("div.strong", null, reg.semester || reg.title), small(`${new Date(reg.start).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" })}`)) : null,
        open.length ? h("button.card.click.events", { type: "button", onclick: () => go("windows") }, label("Open on VTOP"), h("div.strong", null, open.map((w) => w.title).join(", "))) : null,
        pending.length ? h("div.card", null, h("div.strong", null, `${pending.length} feedback form${pending.length > 1 ? "s" : ""} pending`), small("Fill them on VTOP: a pending mandatory one can lock its menus.")) : null)),
    upcomingExam ? [h("h2", null, upcomingExam.e.type || "Exam"), examCard(upcomingExam.e, upcomingExam.d, today)] : null,
    h("h2", null, "Due"),
    due.length ? h("div.grid2", null, due.map(({ c, a, d }) => h("div.card.events", null,
      h("div.row", null, h("div.grow", null, h("div.strong", null, a.title), small(`${c.courseName} · ${Dates.short(d)}`)), h("span.badge", { class: Dates.between(today, d) <= 3 ? "on" : "" }, dayWord(Dates.between(today, d)))))))
      : empty("Nothing due in the next two weeks."),
  ].flat(Infinity).filter(Boolean));
  draw();
  return page;
}

const dayWord = (n) => (n === 0 ? "Today" : n === 1 ? "Tomorrow" : `${n} days`);

function examCard(e, d, today) {
  const n = d ? Dates.between(today, d) : null;
  return h("div.card.exams", null,
    h("div.row", null,
      h("div", { style: { width: "66px" } }, h("div.big", null, d ? d.getDate() : "?"), small(d ? d.toLocaleDateString("en-GB", { month: "short", weekday: "short" }) : "")),
      h("div.grow", null,
        h("div.strong", null, e.courseName),
        small([e.time, e.session].filter(Boolean).join(" · ")),
        small(e.venue || e.seat ? [e.venue, e.seat && `Seat ${e.seat}`].filter(Boolean).join(" · ") : "Room and seat not out yet")),
      n != null && n >= 0 ? h("span.badge", { class: n <= 3 ? "on" : "" }, dayWord(n)) : null));
}

// ---- attendance -----------------------------------------------------------------------------------

function screenAttendance(s) {
  const now = new Date();
  const week = weekOf(s, App.slots);
  const cal = calendarOf(s.calendar);
  const rows = [...s.attendance].sort((a, b) => Rules.percent(a) - Rules.percent(b));
  const risky = rows.filter(Rules.atRisk).length;
  const exempt = Rules.exempt(s);
  const open = new Set();
  const list = h("div.grid2");
  const card = (r) => {
    const left = remainingFor(r, week, cal, now);
    const remark = s.feeds.remarks.find((x) => x.code === r.courseCode && Rules.isLab(x.type) === Rules.isLab(r.classType) || (x.code === r.courseCode && !x.type));
    const course = s.timetable.find((t) => t.classId && t.classId === r.classId) || s.timetable.find((t) => t.courseCode === r.courseCode && Rules.isLab(t.courseType) === Rules.isLab(r.classType));
    const days = s.history[Rules.key(r)] || [];
    const body = h("div");
    const toggle = () => {
      if (open.has(r)) open.delete(r); else open.add(r);
      draw();
    };
    const draw = () => {
      body.replaceChildren(...(open.has(r) ? [
        h("hr.hair"),
        course?.faculty ? h("button.btn", { type: "button", onclick: () => go("academics", { tab: 5, faculty: course.faculty }) }, `Faculty: ${titleCase(course.faculty)}`) : null,
        h("div.chips", { style: { marginTop: "8px" } }, days.slice(-40).reverse().map((d) => h("span.badge", { class: d.status === "Absent" ? "on" : "", title: d.slot }, `${d.date.slice(0, 5)} ${d.status === "Present" ? "P" : d.status === "Absent" ? "A" : d.status === "OnDuty" ? "OD" : "?"}`))),
      ].filter(Boolean) : []));
    };
    draw();
    return h("div.card.attendance", null,
      h("div.row", { onclick: toggle, style: { cursor: "pointer" } },
        h("div", { style: { width: "78px" } }, h("div.big", { class: Rules.atRisk(r) && !exempt ? "bad" : "" }, `${Rules.percent(r)}%`), small(`${r.attended}/${r.total}`)),
        h("div.grow", null, h("div.strong.clip", null, r.courseName), small(`${r.courseCode} · ${Rules.isLab(r.classType) ? "Lab" : "Theory"}`))),
      attendanceBar(r),
      small(Rules.hint(r, left).replace(/^./, (c) => c.toUpperCase())),
      remark && /debar|revok/i.test(remark.remark) ? h("div.small.err", null, `VTOP: ${remark.remark}`) : null,
      body);
  };
  list.append(...rows.map(card));
  return h("div.page", null,
    pageTitle("Attendance", null, button("Plan a leave", () => go("leave"))),
    h("div.chips", { style: { marginBottom: "14px" } },
      h("span.badge", { class: exempt ? "" : "on" }, exempt ? "CGPA 9+: exempt from 75%" : "Threshold 75%"),
      h("span.badge", null, `${risky} at risk`), h("span.badge", null, `${rows.length - risky} safe`)),
    rows.length ? list : empty(`VTOP has no attendance for ${s.semester.name} yet.`));
}

// ---- academics ------------------------------------------------------------------------------------

const ACADEMICS = ["Marks", "Assignments", "Exams", "Grades", "Materials", "Faculty"];

function screenAcademics(s, args = {}) {
  let tab = args.tab ?? 0;
  const body = h("div");
  const tabs = h("div.tabs", { role: "tablist" });
  const draw = () => {
    tabs.replaceChildren(...ACADEMICS.map((t, i) => h("button.tab", { type: "button", role: "tab", "aria-selected": i === tab, class: i === tab ? "on" : "", onclick: () => { tab = i; args = {}; draw(); } }, t)));
    body.replaceChildren(([marksTab, assignmentsTab, examsTab, gradesTab, materialsTab, facultyTab][tab])(s, args));
  };
  draw();
  return h("div.page", null, pageTitle("Academics", null), tabs, body);
}

const num = (n) => (n == null ? "–" : Number.isInteger(n) ? String(n) : String(+n.toFixed(2)));

function marksTab(s) {
  if (!s.marks.length) return empty(`No marks for ${s.semester.name} yet.`);
  return h("div.grid2", null, s.marks.map((c) => {
    const total = c.items.reduce((t, m) => t + (m.weighted ?? 0), 0);
    const of = c.items.filter((m) => m.scored != null).reduce((t, m) => t + (m.weightage ?? 0), 0);
    return h("div.card.exams", null,
      h("div.row", null,
        h("div.grow", null, h("div.strong", null, c.courseName), small(`${c.courseCode} · ${c.courseType}`)),
        h("div.right", null, h("div.big", null, num(+total.toFixed(2))), small(`of ${num(of)}`))),
      h("hr.rule"),
      c.items.length ? c.items.map((m) => h("div.row", { style: { padding: "2px 0" } },
        h("div.grow", null, h("div.strong", null, m.title), m.remark ? small(m.remark) : m.status && !/present/i.test(m.status) ? small(m.status) : null),
        h("div.right", null, h("div.strong", null, m.scored == null ? "–" : `${num(m.scored)}/${num(m.max)}`), small(m.weighted == null ? "" : `${num(m.weighted)} of ${num(m.weightage)}`)))) : small("Nothing yet."));
  }));
}

function assignmentsTab(s) {
  if (!s.assignments.length) return empty(`No assignments for ${s.semester.name} yet.`);
  const today = Dates.addDays(new Date(), 0);
  return h("div.stack", null,
    small("Uploading stays on VTOP or the daVIT phone app."),
    h("div.grid2", null, s.assignments.filter((c) => c.items.length).map((c) => h("div.card.events", null,
      h("div.strong", null, c.courseName), small(`${c.courseCode} · ${c.courseType}`),
      c.items.map((a) => {
        const d = Dates.vtop(a.due);
        const n = d ? Dates.between(today, d) : null;
        return h("div", null, h("hr.hair"),
          h("div.row", null, h("div.grow", null, h("div.strong", null, a.title), small([a.due && `Due ${a.due}`, a.max != null && `${num(a.max)} marks`, a.updated ? `Uploaded ${a.updated}` : null].filter(Boolean).join(" · "))),
            a.updated ? h("span.badge", null, "Done") : n != null && n >= 0 ? h("span.badge", { class: n <= 3 ? "on" : "" }, dayWord(n)) : null),
          a.hasQuestion ? fileRow("Question paper", null, "question", { code: a.code, classId: c.classId }, `qp_${c.classId}_${a.code}`) : null,
          a.hasSubmission ? fileRow("Your upload", null, "submission", { code: a.code, classId: c.classId }, `da_${c.classId}_${a.code}`) : null);
      })))));
}

function examsTab(s) {
  const today = Dates.addDays(new Date(), 0);
  const dated = s.exams.map((e) => ({ e, d: Dates.vtop(e.date) })).filter((x) => x.d).sort((a, b) => a.d - b.d);
  const group = (dated.find((x) => x.d >= today) || dated.at(-1))?.e.type;
  const papers = dated.filter((x) => x.e.type === group);
  const others = h("div");
  const more = button("Make-up, arrear and other exams", async () => {
    more.disabled = true;
    others.replaceChildren(await sectionView("otherExams", (list) => list.length ? list.map((t) => [h("h2", null, t.title), plainTable(t.table)]) : empty("VTOP lists none of these for you.")));
    more.disabled = false;
  });
  return h("div.stack", null,
    h("div.row.wrap", null, h("div.strong.grow", null, papers.length ? `${group || "Exams"} · ${papers.length} paper${papers.length > 1 ? "s" : ""}, ${papers.filter((x) => x.d >= today).length} to go` : "Your exam schedule shows here once VTOP publishes it."), more),
    papers.map(({ e, d }, i) => {
      const gap = i ? Dates.between(papers[i - 1].d, d) - 1 : null;
      return [gap > 0 ? small(`${gap} free day${gap > 1 ? "s" : ""}`) : gap === -1 ? small("The same day") : null, examCard(e, d, today)];
    }),
    others);
}

function gradesTab(s) {
  const g = s.grades;
  const sems = h("div");
  const pick = h("select.field", { style: { maxWidth: "340px" }, onchange: async (e) => {
    if (!e.target.value) return;
    sems.replaceChildren(await sectionView("semesterGrades", (r) => r.courses.length
      ? [r.gpa != null ? h("div.strong", null, `GPA ${r.gpa}`) : null, plainTable({ headers: ["Code", "Course", "Credits", "Grade"], rows: r.courses.map((c) => [c.courseCode, c.courseTitle, num(c.credits), c.grade ?? "–"]) })]
      : empty("No results for this semester yet."), e.target.value));
  } }, h("option", { value: "" }, "VTOP's results for a semester…"), s.semesters.map((x) => h("option", { value: x.id }, x.name.replace(" - VLR", ""))));
  const counts = {};
  for (const e of g?.entries || []) if (e.grade) counts[e.grade] = (counts[e.grade] || 0) + e.credits;
  return h("div.stack", null,
    h("div.card.exams", null, h("div.row", null, h("div.grow", null, label("CGPA"), small(g?.creditsEarned != null ? `${num(g.creditsEarned)} credits earned` : "")), h("div.big", null, g?.cgpa != null ? (Math.floor(g.cgpa * 100) / 100).toFixed(2) : "–"))),
    semesterPlanner(s),
    h("h2", null, "VTOP's results"),
    pick, sems,
    g?.curriculum?.length ? [h("h2", null, "Credits"), h("div.grid2", null, g.curriculum.map((c) => h("div.card", null, h("div.row", null, h("div.grow.strong", null, c.name), h("span", null, `${num(c.earned)}/${num(c.required)}`)))))] : null,
    g?.entries?.length ? [h("h2", null, "Grade history"), plainTable({ headers: ["Code", "Course", "Credits", "Grade", "Exam"], rows: g.entries.map((e) => [e.courseCode, e.courseTitle, num(e.credits), e.grade ?? "–", e.examMonth]) })] : empty("No grades yet. They appear after your first results."));
}

function plainTable(t) {
  const keep = t.headers.map((_, i) => i).filter((i) => !/^(#|s\.?\s?no\.?|sl\.?\s?no\.?)$/i.test(t.headers[i]));
  return h("div.scroll-x", null, h("table.t", null,
    h("thead", null, h("tr", null, keep.map((i) => h("th", null, t.headers[i])))),
    h("tbody", null, t.rows.map((r) => h("tr", null, keep.map((i) => h("td", null, r[i] ?? "")))))));
}

function materialsTab(s) {
  return sectionView("courses", (courses) => {
    const box = h("div");
    const mine = courses.filter((c) => c.semesterId === s.semester.id);
    const rest = courses.filter((c) => c.semesterId !== s.semester.id);
    const pickCourse = (c) => async () => {
      box.replaceChildren(await sectionView("materials", (m) => {
        let q = "";
        const list = h("div");
        const draw = () => {
          const words = q.toLowerCase().split(" ").filter(Boolean);
          const found = m.materials.filter((x) => words.every((w) => `${x.title} ${x.uploader} ${x.module} m${x.module}`.toLowerCase().includes(w)));
          const byModule = {};
          for (const x of found) (byModule[x.module] ||= []).push(x);
          list.replaceChildren(...Object.keys(byModule).sort((a, b) => a - b).flatMap((mod) => [
            h("h2", null, mod ? `Module ${mod}${m.modules[mod] ? `: ${m.modules[mod]}` : ""}` : "Other"),
            ...byModule[mod].slice(0, 120).map((x) => fileRow(x.title, [titleCase(x.uploader), x.date].filter(Boolean).join(" · "), "material", { fileId: x.fileId }, `mat_${x.fileId}`)),
          ]));
          if (!found.length) list.replaceChildren(empty(m.materials.length ? `Nothing matches "${q}".` : "No materials on VTOP for this course yet."));
        };
        draw();
        return [h("div.row", { style: { margin: "8px 0" } }, button("All courses", draw0), h("input.field", { placeholder: "Search title, teacher or module", "aria-label": "Search materials", oninput: (e) => { q = e.target.value; draw(); } })), list];
      }, JSON.stringify(c)));
    };
    const grid = (list) => h("div.grid3", null, list.map((c) => h("button.card.click.classes", { type: "button", onclick: pickCourse(c) }, h("div.strong", null, c.name || c.code), small(`${c.code} · ${c.type}`))));
    const draw0 = () => box.replaceChildren(grid(mine), rest.length ? h("h2", null, "Other semesters") : "", rest.length ? grid(rest) : "");
    draw0();
    return box;
  });
}

function facultyTab(s, args) {
  const box = h("div");
  sectionInto(box, "people", (p) => facultyBody(s, args, p));
  return box;
}

function facultyBody(s, args, people) {
  let q = args.faculty ? titleCase(args.faculty) : "";
  const details = new Map(s.faculty.map((f) => [facultyKey(f.name), f]));
  const courses = Object.values(s.timetable.filter((t) => t.courseCode).reduce((m, t) => ((m[t.courseCode] ||= []).push(t), m), {}));
  const list = h("div.grid2");
  const draw = () => {
    const words = q.toLowerCase().split(" ").filter(Boolean);
    const found = courses.filter((parts) => words.every((w) => parts.map((p) => `${p.courseName} ${p.courseCode} ${p.faculty} ${p.slot} ${p.venue}`).join(" ").toLowerCase().includes(w)));
    list.replaceChildren(...found.map((parts) => h("div.card.classes", null,
      h("div.strong", null, parts[0].courseName), small(parts[0].courseCode),
      Object.values(parts.reduce((m, p) => ((m[facultyKey(p.faculty)] ||= []).push(p), m), {})).map((same) => {
        const who = same[0].faculty;
        const f = details.get(facultyKey(who));
        return h("div", null, h("hr.hair"),
          label(`${same.map((x) => (Rules.isLab(x.courseType) ? "Lab" : "Theory")).join(" + ")} · ${same.map((x) => x.slot).join(", ")}`),
          h("div.strong", null, titleCase(who || "Not assigned yet")),
          f ? small([f.designation, [f.department, f.school].filter(Boolean).join(", ")].filter(Boolean).join(" · ")) : null,
          h("div.row.wrap", { style: { marginTop: "5px" } },
            ratingLink(f?.empId, who),
            f?.cabin ? button(`Cabin ${f.cabin}`, () => go("map", { find: f.cabin })) : null,
            f?.email ? button(f.email, () => api("open_link", { url: `mailto:${f.email}` })) : null));
      }))));
    if (!found.length) list.replaceChildren(empty(`None of your courses match "${q}".`));
  };
  draw();
  const person = (p) => {
    const get = (w) => p.fields.find(([k]) => k.toLowerCase().includes(w))?.[1] || "";
    return h("div.card", null, label(p.role), h("div.strong", null, titleCase(get("name"))), small(get("designation")),
      h("div.row.wrap", { style: { marginTop: "5px" } }, get("cabin") ? button(`Cabin ${get("cabin")}`, () => go("map", { find: get("cabin") })) : null, get("mail") ? button(get("mail"), () => api("open_link", { url: `mailto:${get("mail")}` })) : null));
  };
  return [
    h("input.field", { placeholder: "Course, code or any teacher", "aria-label": "Search faculty", value: q, oninput: (e) => { q = e.target.value; draw(); }, style: { marginBottom: "12px" } }),
    list,
    h("h2", null, "Who to ask"),
    h("div.grid2", null, people.proctor ? person(people.proctor) : null, (people.hodDean || []).map(person)),
  ];
}

function ratingLink(empId, name) {
  const slot = h("span");
  Reviews.load().then((book) => {
    const f = book.find(empId, name);
    if (f && f.reviewCount > 0) slot.append(button(`Rated ${Number(f.rating).toFixed(1)} · ${f.reviewCount} review${f.reviewCount > 1 ? "s" : ""}`, () => go("review", { id: f.id })));
  });
  return slot;
}

// ---- week -----------------------------------------------------------------------------------------

function screenWeek(s) {
  const week = weekOf(s, App.slots);
  const cal = calendarOf(s.calendar);
  let mode = 0;
  const body = h("div");
  const tabs = h("div.tabs");
  const draw = () => {
    tabs.replaceChildren(...["Grid", "List", "Calendar"].map((t, i) => h("button.tab", { type: "button", class: i === mode ? "on" : "", onclick: () => { mode = i; draw(); } }, t)));
    body.replaceChildren(mode === 0 ? gridView(week, cal) : mode === 1 ? listView(week, cal) : calendarView(cal));
  };
  draw();
  return h("div.page", null, pageTitle("Week", cal?.group ? `Academic calendar: ${cal.group}` : null), tabs, body);
}

function weekDates(today) {
  const wd = Dates.weekday(today);
  const monday = wd === 7 ? Dates.addDays(today, 1) : Dates.addDays(today, 1 - wd);
  return [0, 1, 2, 3, 4, 5].map((i) => Dates.addDays(monday, i));
}

function gridView(week, cal) {
  const today = new Date();
  const dates = weekDates(today);
  const lanes = dates.map((d) => ({ d, classes: classesOn(week, d, cal) }));
  const all = lanes.flatMap((l) => l.classes.map((c) => c.meeting));
  const first = Math.floor(Math.min(480, ...all.map((m) => m.start)) / 60);
  const last = Math.ceil(Math.max(1080, ...all.map((m) => m.end)) / 60);
  const hourH = 52;
  const height = (last - first) * hourH;
  const grid = h("div.week", { style: { gridTemplateColumns: `44px repeat(${dates.length}, minmax(0, 1fr))` } });
  grid.append(h("div.head"));
  for (const { d } of lanes) {
    const note = cal?.day(d);
    grid.append(h("div.head", { title: note?.note || "" }, d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric" }), note && note.order && note.order !== Dates.weekday(d) ? h("div.small", null, `as ${Dates.dayName(note.order).slice(0, 3)}`) : note && !note.instructional && note.type !== "None" ? h("div.small", null, note.type) : null));
  }
  grid.append(h("div", { style: { position: "relative", height: `${height}px` } }, Array.from({ length: last - first }, (_, i) => h("div.hour", { style: { position: "absolute", top: `${i * hourH}px`, left: 0, right: 0 } }, String(first + i).padStart(2, "0")))));
  for (const { d, classes } of lanes) {
    grid.append(h("div.lane", { class: Dates.iso(d) === Dates.iso(today) ? "today" : "", style: { height: `${height}px` } },
      Array.from({ length: last - first }, (_, i) => h("div.line", { style: { top: `${i * hourH}px` } })),
      classes.map((c) => h("div.slot", { class: c.isLab ? "lab" : "", title: `${c.course.courseName} · ${c.course.venue}`, style: { top: `${((c.meeting.start - first * 60) / 60) * hourH}px`, height: `${((c.meeting.end - c.meeting.start) / 60) * hourH - 2}px` } }, h("div.strong.clip", null, shortName(c.course.courseName)), h("div.clip", null, c.course.venue)))));
  }
  return h("div.scroll-x", null, h("div", { style: { minWidth: "640px" } }, grid));
}

const shortName = (n) => (n.length <= 14 ? n : n.split(/\s+/).map((w) => w[0]).join("").toUpperCase());

function listView(week, cal) {
  const today = new Date();
  return h("div.grid2", null, weekDates(today).map((d) => {
    const classes = classesOn(week, d, cal);
    const note = cal?.day(d);
    return h("div.card", { class: Dates.iso(d) === Dates.iso(today) ? "classes" : "" },
      h("div.strong", null, Dates.long(d)),
      note && !note.instructional && note.type !== "None" ? small([note.type, note.note].filter(Boolean).join(": ")) : null,
      classes.length ? classes.map((c) => h("div.row", { style: { marginTop: "4px" } }, h("span.strong", null, Dates.clock(c.meeting.start)), h("div.grow.clip", null, c.course.courseName), h("span.small", null, c.course.venue))) : small("No classes"));
  }));
}

function calendarView(cal) {
  if (!cal) return empty("The academic calendar shows here once VTOP publishes it.");
  const months = [...new Set(cal.days.map((d) => `${d.date.getFullYear()}-${d.date.getMonth()}`))];
  const today = Dates.iso(new Date());
  return months.map((key) => {
    const [y, m] = key.split("-").map(Number);
    const first = new Date(y, m, 1);
    const count = new Date(y, m + 1, 0).getDate();
    const cells = Array.from({ length: Dates.weekday(first) - 1 }, () => h("span"));
    for (let i = 1; i <= count; i++) {
      const date = new Date(y, m, i);
      const d = cal.day(date);
      cells.push(h("div", { class: [d?.exam ? "exam" : "", d && !d.instructional && !d.exam ? "off" : "", Dates.iso(date) === today ? "is-today" : ""].join(" ") },
        h("div.strong", null, i),
        d?.instructional && d.order !== Dates.weekday(date) ? `as ${Dates.dayName(d.order).slice(0, 3)}` : d && !d.instructional && d.type !== "None" ? (d.note || d.type) : ""));
    }
    return [h("h2", null, first.toLocaleDateString("en-GB", { month: "long", year: "numeric" })),
      h("div.cal", null, ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((x) => h("span.label", null, x)), cells)];
  });
}
