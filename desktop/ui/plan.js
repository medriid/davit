// The planners: FFCS, a leave, the CGPA this semester could lead to, the mess menu, and your own
// course names. The rules are Android's.
"use strict";

// ---- FFCS: best clash-free timetables ---------------------------------------------------------------

const EIGHT = 8 * 60;
const AFTERNOON = 14 * 60;

function shortLunch(classes) {
  const morning = classes.filter((c) => c[1] <= AFTERNOON).map((c) => c[1]);
  const afternoon = classes.filter((c) => c[0] >= AFTERNOON).map((c) => c[0]);
  if (!morning.length || !afternoon.length) return false;
  return Math.max(...morning) >= 12 * 60 + 50 && Math.min(...afternoon) === AFTERNOON;
}

function meetingsOf(slot, slots) {
  return splitSlots(slot).flatMap((p) => slots.get(p) || []);
}

function allocationOptions(table, code, title, slots) {
  const col = (...w) => table.headers.findIndex((h) => w.some((x) => h.toLowerCase().includes(x)));
  const slot = Math.max(col("slot"), 0);
  const [venue, faculty, type] = [col("venue"), col("faculty"), col("type")];
  return table.rows.map((r) => {
    const s = (r[slot] || "").replace(/ /g, "").toUpperCase();
    return !s || s === "-" ? null : { code, title, slot: s, venue: r[venue] || "", faculty: r[faculty] || "", type: r[type] || "", meetings: meetingsOf(s, slots) };
  }).filter(Boolean);
}

function score(t, rules) {
  return (rules.daysOff ? -t.daysOff.length * 400 : 0) + (rules.fewGaps ? t.gapMinutes / 5 : 0) + t.eightAmDays * 20 + t.shortLunchDays * 15 + (rules.sameFaculty ? t.splitFaculty * 120 : 0);
}

function assess(order, picked, rules) {
  const picks = [];
  let split = 0;
  const byCode = {};
  order.forEach((p, i) => (byCode[p.code] ||= []).push(i));
  for (const idx of Object.values(byCode)) {
    let common = null;
    if (rules.sameFaculty && idx.length > 1) {
      const sets = idx.map((i) => new Set(picked[i].classes.map((c) => facultyKey(c.faculty))));
      common = [...sets[0]].find((f) => sets.every((s) => s.has(f))) ?? null;
    }
    if (idx.length > 1 && common == null) split++;
    for (const i of idx) picks.push(picked[i].classes.find((c) => common != null && facultyKey(c.faculty) === common) || picked[i].classes[0]);
  }
  const days = {};
  for (let d = 1; d <= 5; d++) days[d] = picks.flatMap((p) => p.meetings.filter((m) => m.day === d)).sort((a, b) => a.start - b.start);
  const eight = Object.values(days).filter((ms) => ms.some((m) => m.start === EIGHT)).length;
  const short = Object.values(days).filter((ms) => shortLunch(ms.map((m) => [m.start, m.end]))).length;
  if (rules.shortLunchDays != null && short > rules.shortLunchDays) return null;
  if (rules.eightAmDays != null && eight > rules.eightAmDays) return null;
  let gaps = 0;
  for (const ms of Object.values(days)) for (let i = 1; i < ms.length; i++) { const g = Math.max(0, ms[i].start - ms[i - 1].end); if (g > 15) gaps += g; }
  return { picks, daysOff: Object.keys(days).filter((d) => !days[d].length).map(Number), eightAmDays: eight, shortLunchDays: short, gapMinutes: gaps, splitFaculty: split };
}

/** Tries every clash-free mix of slots, best first; {found, complete} or {missing, relax} when nothing fits. */
function findTimetables(subjects, options, rules, limit = 10, millis = 3000, explain = true) {
  const parts = [];
  const missing = [];
  for (const s of subjects) {
    const all = options[s.code] || [];
    if (!all.length) { missing.push(`${s.code} has no classes in the allocation`); continue; }
    const byType = {};
    for (const c of all) (byType[c.type] ||= []).push(c);
    for (const [type, list] of Object.entries(byType)) {
      const allowed = list.filter((c) => !s.faculty.length || s.faculty.some((f) => facultyKey(f) === facultyKey(c.faculty)));
      const fits = allowed.filter((c) => c.meetings.length && c.meetings.every((m) => m.start >= rules.earliest && (rules.latest == null || m.end <= rules.latest)));
      const bySlot = {};
      for (const c of fits) (bySlot[c.slot] ||= []).push(c);
      const choices = Object.values(bySlot).map((classes) => ({ classes, meetings: classes[0].meetings.filter((m) => m.day >= 1 && m.day <= 5) }));
      if (!choices.length) missing.push(`${s.code} ${type}`.trim() + (allowed.length ? ": none within your start and end times" : ": none with the faculty you picked"));
      else parts.push({ code: s.code, type, choices });
    }
  }
  if (missing.length) return { missing, relax: [] };

  const order = [...parts].sort((a, b) => a.choices.length - b.choices.length);
  const days = [null, [], [], [], [], []];
  const clashes = (c) => c.meetings.some((m) => days[m.day].some(([a, b]) => m.start < b && a < m.end));
  const best = [];
  const picked = [];
  const deadline = Date.now() + millis;
  let timedOut = false;
  let nodes = 0;
  const worst = () => (best.length < limit ? Infinity : score(best.at(-1), rules));
  const bound = () => {
    let b = 0;
    for (let d = 1; d <= 5; d++) b += !days[d].length ? (rules.daysOff ? -400 : 0) : (days[d].some((x) => x[0] === EIGHT) ? 20 : 0) + (shortLunch(days[d]) ? 15 : 0);
    return b;
  };
  const go = (i) => {
    if (timedOut) return;
    if ((++nodes & 1023) === 0 && Date.now() > deadline) { timedOut = true; return; }
    if (i === order.length) {
      const t = assess(order, picked, rules);
      if (!t) return;
      best.push(t);
      best.sort((a, b) => score(a, rules) - score(b, rules));
      if (best.length > limit) best.pop();
      return;
    }
    const ranked = order[i].choices.filter((c) => !clashes(c)).sort((a, b) => {
      const cost = (c) => new Set(c.meetings.filter((m) => !days[m.day].length).map((m) => m.day)).size * 1000 + c.meetings.filter((m) => m.start === EIGHT).length * 20;
      return cost(a) - cost(b);
    });
    for (const c of ranked) {
      for (const m of c.meetings) days[m.day].push([m.start, m.end]);
      const count = (f) => [1, 2, 3, 4, 5].filter((d) => f(days[d])).length;
      const ok = (rules.eightAmDays == null || count((x) => x.some((y) => y[0] === EIGHT)) <= rules.eightAmDays)
        && (rules.shortLunchDays == null || count(shortLunch) <= rules.shortLunchDays) && bound() < worst();
      if (ok) {
        picked[i] = c;
        go(i + 1);
      }
      for (const m of c.meetings) days[m.day].splice(days[m.day].findIndex((x) => x[0] === m.start && x[1] === m.end), 1);
      if (timedOut) return;
    }
  };
  go(0);
  if (best.length) return { found: best, complete: !timedOut };
  if (!explain) return { missing: [], relax: [] };
  const works = (s, r) => !!findTimetables(s, options, r, 1, 800, false).found;
  const relax = [
    rules.eightAmDays != null && works(subjects, { ...rules, eightAmDays: null }) && "8:00 starts",
    rules.shortLunchDays != null && works(subjects, { ...rules, shortLunchDays: null }) && "Short lunches",
    rules.earliest > EIGHT && works(subjects, { ...rules, earliest: EIGHT }) && "Start time",
    rules.latest != null && works(subjects, { ...rules, latest: null }) && "End time",
    subjects.some((s) => s.faculty.length) && works(subjects.map((s) => ({ ...s, faculty: [] })), rules) && "Faculty choices",
  ].filter(Boolean);
  return { missing: relax.length ? [] : [timedOut ? "Couldn't find one that keeps every rule in time. Loosen a rule or take out a subject." : "No mix of these subjects avoids a clash with the classes allotted."], relax };
}

function planGrid(picks) {
  const meetings = picks.flatMap((p) => p.meetings.map((m) => ({ m, p })));
  const first = Math.floor(Math.min(480, ...meetings.map((x) => x.m.start)) / 60);
  const last = Math.ceil(Math.max(1020, ...meetings.map((x) => x.m.end)) / 60);
  const hourH = 30;
  const height = (last - first) * hourH;
  const grid = h("div.week", { style: { gridTemplateColumns: "32px repeat(5, minmax(0, 1fr))" } });
  grid.append(h("div.head"), ...["Mon", "Tue", "Wed", "Thu", "Fri"].map((d) => h("div.head", null, d)));
  grid.append(h("div", { style: { position: "relative", height: `${height}px` } }, Array.from({ length: last - first }, (_, i) => h("div.hour", { style: { position: "absolute", top: `${i * hourH}px`, left: 0, right: 0 } }, first + i))));
  for (let d = 1; d <= 5; d++) {
    grid.append(h("div.lane", { style: { height: `${height}px` } },
      meetings.filter((x) => x.m.day === d).map(({ m, p }) => h("div.slot", { class: Rules.isLab(p.type) || p.slot.startsWith("L") ? "lab" : "", title: `${p.title} · ${p.slot} · ${p.faculty}`, style: { top: `${((m.start - first * 60) / 60) * hourH}px`, height: `${((m.end - m.start) / 60) * hourH - 1}px` } }, h("div.clip", null, p.code)))));
  }
  return grid;
}

function screenFfcs() {
  const saved = () => JSON.parse(localStore.get("ffcs", "[]"));
  const rules = { earliest: EIGHT, latest: null, eightAmDays: null, shortLunchDays: null, daysOff: true, fewGaps: true, sameFaculty: true };
  const subjects = [];
  const options = {};
  const chosen = h("div.stack");
  const results = h("div");
  const courseSelect = h("select.field", { "aria-label": "Course" }, h("option", { value: "" }, "Pick a category first"));
  const page = h("div.page");
  page.append(...pageTitle("FFCS", "Pick your subjects and how you like your days, and get the best clash-free timetables from VTOP's course allocation.").flat().filter(Boolean));

  const drawChosen = () => {
    chosen.replaceChildren(...subjects.map((s) => {
      const names = [...new Set((options[s.code] || []).map((c) => c.faculty).filter(Boolean))].sort();
      return h("div.card.classes", null,
        h("div.row", null, h("div.grow.strong", null, `${s.code} · ${s.title}`), button("Remove", () => { subjects.splice(subjects.indexOf(s), 1); drawChosen(); })),
        small(`${(options[s.code] || []).length} classes allotted · ${s.faculty.length ? `${s.faculty.length} faculty you'd take` : "Any faculty"}`),
        h("div.chips", { style: { marginTop: "6px" } }, names.map((n) => h("button.chip", { type: "button", class: s.faculty.includes(n) ? "on" : "", onclick: () => { s.faculty = s.faculty.includes(n) ? s.faculty.filter((x) => x !== n) : [...s.faculty, n]; drawChosen(); } }, titleCase(n)))));
    }));
    if (!subjects.length) chosen.replaceChildren(empty("No subjects yet."));
  };
  drawChosen();

  const rule = (text, input) => h("label.row", { style: { justifyContent: "space-between" } }, h("span", null, text), input);
  const pick = (values, get, set) => h("select", { class: "field", style: { width: "150px" }, onchange: (e) => set(e.target.value === "" ? null : Number(e.target.value)) }, values.map(([v, t]) => h("option", { value: v ?? "", selected: get === v }, t)));
  const check = (key, text) => rule(text, h("input", { type: "checkbox", checked: rules[key], onchange: (e) => { rules[key] = e.target.checked; } }));
  const times = (from, to) => { const out = []; for (let m = from; m <= to; m += 60) out.push([m, Dates.clock(m)]); return out; };

  const search_ = () => {
    if (!subjects.length) { results.replaceChildren(empty("Add a subject first.")); return; }
    const r = findTimetables(subjects, options, rules);
    if (!r.found) {
      results.replaceChildren(h("h2", null, "Nothing fits"), ...r.missing.map((m) => small(m)), r.relax.length ? small(`Dropping one of these would give timetables: ${r.relax.join(", ")}.`) : "");
      return;
    }
    results.replaceChildren(h("h2", null, `Best ${r.found.length}${r.complete ? "" : ", in the time it had"}`), h("div.grid2", null, r.found.map((t, i) => h("div.card", null,
      h("div.row", null, h("div.strong.grow", null, `Option ${i + 1}`), button("Use this", () => {
        Plan.set(App.allocationSemester || "", t.picks.map(({ meetings, ...p }) => p));
        go("ffcs");
      }, "solid"), button("Save", (e) => {
        const list = saved();
        list.unshift({ name: `Plan ${list.length + 1}`, picks: t.picks.map(({ meetings, ...p }) => p) });
        localStore.set("ffcs", JSON.stringify(list));
        e.target.textContent = "Saved";
        drawSaved();
      })),
      small([t.daysOff.length ? `${t.daysOff.length} day${t.daysOff.length > 1 ? "s" : ""} off` : "No day off", `${t.eightAmDays} 8:00 start${t.eightAmDays === 1 ? "" : "s"}`, `${t.shortLunchDays} short lunch${t.shortLunchDays === 1 ? "" : "es"}`, `${Math.round(t.gapMinutes / 60)} h between classes`, t.splitFaculty ? `${t.splitFaculty} split faculty` : null].filter(Boolean).join(" · ")),
      h("div", { style: { margin: "8px 0" } }, planGrid(t.picks)),
      plainTable({ headers: ["Code", "Slot", "Room", "Faculty"], rows: t.picks.map((p) => [p.code, p.slot, p.venue, titleCase(p.faculty)]) })))));
  };

  const savedBox = h("div");
  const drawSaved = () => {
    const list = saved();
    savedBox.replaceChildren(...(list.length ? [h("h2", null, "Saved"), h("div.grid2", null, list.map((p, i) => h("div.card", null,
      h("div.row", null, h("div.strong.grow", null, p.name), button("Delete", () => { const l = saved(); l.splice(i, 1); localStore.set("ffcs", JSON.stringify(l)); drawSaved(); })),
      h("div", { style: { margin: "8px 0" } }, planGrid(p.picks.map((x) => ({ ...x, meetings: meetingsOf(x.slot, App.slots) })))),
      plainTable({ headers: ["Code", "Slot", "Room", "Faculty"], rows: p.picks.map((x) => [x.code, x.slot, x.venue, titleCase(x.faculty)]) }))))] : []));
  };
  drawSaved();

  const top = h("div");
  page.append(top, h("div.grid2", { style: { marginTop: "12px" } },
    h("div", null, label("Subjects"), chosen),
    h("div.card", null, label("Your rules"), h("div.stack", { style: { marginTop: "6px" } },
      rule("Classes from", pick(times(480, 720), rules.earliest, (v) => { rules.earliest = v ?? EIGHT; })),
      rule("Done by", pick([[null, "Any time"], ...times(960, 1200)], rules.latest, (v) => { rules.latest = v; })),
      rule("8:00 starts, at most", pick([[null, "Don't mind"], [0, "None"], [1, "1 day"], [2, "2 days"], [3, "3 days"]], rules.eightAmDays, (v) => { rules.eightAmDays = v; })),
      rule("Short lunches, at most", pick([[null, "Don't mind"], [0, "None"], [1, "1 day"], [2, "2 days"]], rules.shortLunchDays, (v) => { rules.shortLunchDays = v; })),
      check("daysOff", "Prefer days off"), check("fewGaps", "Prefer less waiting"), check("sameFaculty", "Same faculty for theory and lab"),
      button("Find timetables", search_, "solid")))), results, savedBox);

  sectionInto(top, "allocation", (a) => {
    App.allocationSemester = a.semester;
    if (!a.categories.length) return empty("VTOP's course allocation isn't open right now.");
    const cat = h("select.field", { "aria-label": "Category", onchange: async (e) => {
      if (!e.target.value) return;
      const r = await api("section", { name: "allocationCourses", arg: e.target.value, force: null });
      courseSelect.replaceChildren(h("option", { value: "" }, "Choose a course"), ...r.data.map(([code, t]) => h("option", { value: code }, t)));
    } }, h("option", { value: "" }, "Curriculum category"), a.categories.map(([id, t]) => h("option", { value: id }, t)));
    const note = h("div.small.err");
    return [small(a.semester ? `Allocation for ${a.semester}.` : ""), h("div.row.wrap", { style: { marginTop: "6px" } }, h("div.grow", { style: { minWidth: "220px" } }, cat), h("div.grow", { style: { minWidth: "220px" } }, courseSelect), button("Add", async () => {
      const code = courseSelect.value;
      if (!code || subjects.some((s) => s.code === code)) return;
      const title = courseSelect.selectedOptions[0].textContent.split(" - ").slice(1).join(" - ");
      note.textContent = "";
      try {
        const r = await api("section", { name: "allocationClasses", arg: code, force: null });
        options[code] = allocationOptions(r.data, code, title, App.slots);
        subjects.push({ code, title, faculty: [] });
        drawChosen();
      } catch (e) { note.textContent = e; }
    }, "solid")), note];
  });
  return page;
}

// ---- plan a leave ------------------------------------------------------------------------------------

function afterLeave(s, dates, week, cal, at) {
  const today = Dates.addDays(at, 0);
  const chosen = dates.map(Dates.parse).filter((d) => d && d >= today);
  const set = new Set(chosen.map(Dates.iso));
  const last = chosen.length ? new Date(Math.max(...chosen)) : null;
  const minute = at.getHours() * 60 + at.getMinutes();
  return s.attendance.map((row) => {
    const meetings = Object.values(week).flat().filter((x) => x.attendance && Rules.key(x.attendance) === Rules.key(row)).map((x) => x.meeting);
    const end = cal && (Rules.isLab(row.classType) ? cal.lastLab : cal.lastTheory);
    const counted = (d) => {
      if (end && d > end) return 0;
      const wd = cal ? cal.timetableDay(d) : Dates.weekday(d);
      if (!wd) return 0;
      const isToday = Dates.iso(d) === Dates.iso(at);
      return meetings.filter((m) => m.day === wd && (!isToday || m.start > minute)).length;
    };
    const missed = chosen.reduce((n, d) => n + counted(d), 0);
    let between = 0;
    if (last) for (let d = today; d <= last; d = Dates.addDays(d, 1)) if (!set.has(Dates.iso(d))) between += counted(d);
    const after = { ...row, attended: row.attended + between, total: row.total + between + missed };
    const remaining = remainingFor(row, week, cal, at);
    const left = remaining == null ? null : Math.max(0, remaining - missed - between);
    const verdict = Rules.exempt(s) ? "Exempt" : !Rules.atRisk(after) ? "Safe" : left == null || Rules.skipTillEnd(after, left) >= 0 ? "Recoverable" : "Out of reach";
    return { row, missed, between, after, left, verdict };
  }).sort((a, b) => (a.missed === 0) - (b.missed === 0) || (a.verdict === "Safe") - (b.verdict === "Safe") || Rules.percent(a.after) - Rules.percent(b.after));
}

function screenLeave(s) {
  const now = new Date();
  const week = weekOf(s, App.slots);
  const cal = calendarOf(s.calendar);
  const dates = new Set();
  const list = h("div.grid2");
  const picked = h("div.chips", { style: { margin: "8px 0" } });
  const draw = () => {
    picked.replaceChildren(...[...dates].sort().map((d) => h("button.chip.on", { type: "button", title: "Remove", onclick: () => { dates.delete(d); draw(); } }, `${Dates.short(Dates.parse(d))} ×`)));
    const out = dates.size ? afterLeave(s, [...dates], week, cal, now) : [];
    list.replaceChildren(...out.map((x) => h("div.card.attendance", null,
      h("div.row", null, h("div.grow", null, h("div.strong", null, x.row.courseName), small(`${Rules.isLab(x.row.classType) ? "Lab" : "Theory"} · misses ${x.missed} · ${x.between} attended before`)),
        h("span.badge", { class: x.verdict === "Out of reach" ? "on" : "" }, x.verdict)),
      h("div.row", { style: { marginTop: "4px" } }, small(`${Rules.percent(x.row)}% now`), h("span", null, "→"), h("span.big", { class: Rules.atRisk(x.after) ? "bad" : "" }, `${Rules.percent(x.after)}%`), small(`${x.after.attended}/${x.after.total}`)),
      x.left != null ? small(Rules.hint(x.after, x.left)) : null)));
    if (!dates.size) list.replaceChildren(empty("Add the days you'd be away."));
  };
  draw();
  const from = h("input.field", { type: "date", min: Dates.iso(now), "aria-label": "From", style: { width: "170px" } });
  const to = h("input.field", { type: "date", min: Dates.iso(now), "aria-label": "To", style: { width: "170px" } });
  return h("div.page", null, pageTitle("Plan a leave", "What days away will cost, assuming you attend everything else until then."),
    h("div.row.wrap", null, from, h("span", null, "to"), to, button("Add", () => {
      const a = Dates.parse(from.value);
      const b = Dates.parse(to.value) || a;
      if (!a) return;
      for (let d = a; d <= b; d = Dates.addDays(d, 1)) dates.add(Dates.iso(d));
      draw();
    }, "solid")), picked, list);
}

// ---- CGPA, this semester ----------------------------------------------------------------------------

const GRADES = [["S", 10], ["A", 9], ["B", 8], ["C", 7], ["D", 6], ["E", 5], ["F", 0]];

function gradeRecord(history) {
  const points = Object.fromEntries([...GRADES, ["N", 0]]);
  const graded = (history?.entries || []).filter((e) => e.credits > 0 && (e.grade || "").toUpperCase() in points);
  return [graded.reduce((n, e) => n + e.credits, 0), graded.reduce((n, e) => n + e.credits * points[e.grade.toUpperCase()], 0)];
}

function semesterPlanner(s) {
  const seen = new Set();
  const courses = s.timetable.filter((c) => c.credits > 0 && !seen.has(`${c.courseCode}|${c.courseType}`) && seen.add(`${c.courseCode}|${c.courseType}`));
  if (!courses.length) return null;
  const picks = courses.map(() => 1);
  const out = courses.map(() => false);
  const value = h("div.big");
  const note = small("");
  const rows = h("div");
  const [credits, points] = gradeRecord(s.grades);
  const draw = () => {
    const counted = courses.map((c, i) => [c, i]).filter(([, i]) => !out[i]);
    const addC = counted.reduce((n, [c]) => n + c.credits, 0);
    const addP = counted.reduce((n, [c, i]) => n + c.credits * GRADES[picks[i]][1], 0);
    const total = credits + addC;
    value.textContent = total > 0 ? (Math.floor(((points + addP) / total) * 100) / 100).toFixed(2) : "–";
    const needed = addC > 0 ? (9 * (credits + addC) - points) / addC : null;
    note.textContent = needed == null ? "" : needed <= 0 ? "You're above 9 whatever happens." : needed > 10 ? "9 is out of reach this semester." : `Needs an average of ${needed.toFixed(2)} for 9.`;
    rows.replaceChildren(...courses.map((c, i) => h("div", null, h("hr.hair"),
      h("div.row.wrap", null,
        h("div.grow", { style: { minWidth: "200px", opacity: out[i] ? 0.5 : 1 } }, h("div.strong", null, c.courseName), small(`${c.courseCode} · ${Rules.isLab(c.courseType) ? "Lab" : "Theory"} · ${num(c.credits)} credits`)),
        out[i] ? null : h("div.chips", null, GRADES.map(([g], k) => h("button.chip", { type: "button", class: k === picks[i] ? "on" : "", onclick: () => { picks[i] = k; draw(); } }, g))),
        button(out[i] ? "Count it" : "Leave out", () => { out[i] = !out[i]; draw(); })))));
  };
  draw();
  return [h("h2", null, "Plan this semester"), h("div.card.exams", null, h("div.row", null, h("div.grow", null, label("CGPA if these hold"), note), value), rows)];
}

// ---- mess menu ---------------------------------------------------------------------------------------

function screenMess() {
  const page = h("div.page");
  const bell = h("div");
  page.append(...pageTitle("Mess menu", null, button("Reminders", () => { open = !open; drawBell(); })).flat().filter(Boolean), bell);
  let open = false;
  const box = h("div");
  page.append(box);
  const meals = ["Breakfast", "Lunch", "Snacks", "Dinner"];
  const drawBell = () => {
    const mess = MessBook.data?.messes[0];
    const p = AlertPrefs.get();
    bell.replaceChildren(...(open && mess ? [h("div.card.events", { style: { marginBottom: "12px" } }, label("Meal reminders"),
      meals.map((m) => (mess.times[m]
        ? toggleRow(m, mess.times[m], p.meals.includes(m), () => { AlertPrefs.set({ ...p, meals: p.meals.includes(m) ? p.meals.filter((x) => x !== m) : [...p.meals, m] }); drawBell(); draw(); })
        : h("div.settingrow", null, h("div.grow", null, h("div.strong", null, m), small("No time known, so no reminder."))))),
      h("div.strong", { style: { marginTop: "10px" } }, "How early"),
      h("div", { style: { marginTop: "6px" } }, choiceRow(["When it starts", "10 min", "15 min", "30 min"], [0, 10, 15, 30].indexOf(p.beforeMeal), (i) => { AlertPrefs.set({ ...p, beforeMeal: [0, 10, 15, 30][i] }); drawBell(); })),
      !p.on ? small("Notifications are off, so these won't show. Turn them on in Settings.") : null)] : []));
  };
  let at = Dates.addDays(new Date(), 0);
  const draw = () => {
    const mess = MessBook.data?.messes[0];
    if (!mess) return;
    const month = `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, "0")}`;
    const sheet = mess.sheets.find((x) => x.month === month);
    const row = MessBook.row(mess, at);
    const p = AlertPrefs.get();
    box.replaceChildren(
      small(`${mess.name} · ${mess.hostel}. ${mess.timesNote}`),
      h("div.row.wrap", { style: { margin: "10px 0" } }, button("Previous day", () => { at = Dates.addDays(at, -1); draw(); }), h("input.field", { type: "date", "aria-label": "Day", value: Dates.iso(at), style: { width: "170px" }, onchange: (e) => { const d = Dates.parse(e.target.value); if (d) { at = d; draw(); } } }), button("Next day", () => { at = Dates.addDays(at, 1); draw(); }), button("Today", () => { at = Dates.addDays(new Date(), 0); draw(); }), h("div.strong", null, Dates.long(at))),
      row ? h("div.grid2", null, meals.map((m) => h("div.card.events", null, h("div.row", null, h("div.grow.strong", null, m), p.meals.includes(m) ? h("span.badge", null, "Reminder on") : null, mess.times[m] ? small(mess.times[m]) : null), h("div", { style: { marginTop: "4px" } }, row[m.toLowerCase()] || "Not on the sheet."))))
        : empty(sheet ? "The sheet has no row for this date." : "No sheet for this month yet. Nothing is filled in for it."));
  };
  MessBook.load().then(() => { draw(); drawBell(); });
  return page;
}

// ---- your own course names ---------------------------------------------------------------------------

const Names = {
  all: () => JSON.parse(localStore.get("courseNames", "{}")),
  apply(s) {
    if (!s) return s;
    const names = Names.all();
    if (!Object.keys(names).length) return s;
    const rename = (x, codeKey, nameKey) => (names[x[codeKey]] ? { ...x, [nameKey]: names[x[codeKey]] } : x);
    return {
      ...s,
      attendance: s.attendance.map((r) => rename(r, "courseCode", "courseName")),
      timetable: s.timetable.map((r) => rename(r, "courseCode", "courseName")),
      marks: s.marks.map((r) => rename(r, "courseCode", "courseName")),
      exams: s.exams.map((r) => rename(r, "courseCode", "courseName")),
      assignments: s.assignments.map((r) => rename(r, "courseCode", "courseName")),
    };
  },
};
