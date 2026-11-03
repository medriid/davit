// What's yours and stays here: reminders, corrections, friends, saved files, alerts and every
// setting. Plus the screens the phone has that read VTOP once: GPA by session, allocation, activities.
"use strict";

/** Kept encrypted with the rest, loaded before anything draws. */
const Yours = {
  data: {},
  async load() {
    try {
      Yours.data = JSON.parse((await api("yours_load")) || "{}") || {};
    } catch {
      Yours.data = {};
    }
    Yours.data.reminders ||= [];
    Yours.data.corrections ||= [];
    Yours.data.friends ||= [];
  },
  save: () => api("yours_save", { text: JSON.stringify(Yours.data) }),
};

/** What you chose over what daVIT would work out. */
const Choices = {
  get: () => ({ semester: localStore.get("chosenSemester", null), group: localStore.get("chosenGroup", null) }),
  setSemester: (id) => localStore.set("chosenSemester", id),
  setGroup(id) {
    if (id) localStore.set("chosenGroup", id);
    else localStorage.removeItem("chosenGroup");
  },
};

const ALERT_DEFAULTS = { on: true, attendance: true, warnBelow: 75, skipsLeft: 0, muted: [], beforeClass: 0, beforeClassNearLine: true, remarks: true, marks: true, deadlines: true, exams: true, notices: true, meals: [], beforeMeal: 15, projectAreas: [] };

const AlertPrefs = {
  get() {
    try {
      return { ...ALERT_DEFAULTS, ...JSON.parse(localStore.get("alertPrefs", "{}")) };
    } catch {
      return { ...ALERT_DEFAULTS };
    }
  },
  set: (p) => localStore.set("alertPrefs", JSON.stringify(p)),
};

const courseKey = (code, type) => `${code}|${Rules.isLab(type) ? "lab" : "theory"}`;
const below = (r, line) => r.total > 0 && r.attended * 100 < line * r.total;

const Background = {
  on: () => localStore.get("background", "1") === "1",
  set(on) {
    localStore.set("background", on ? "1" : "0");
    api("set_background", { on }).catch(() => {});
  },
  apply: () => api("set_background", { on: Background.on() }).catch(() => {}),
  /** Every 6 hours, never between 1 and 6 am. */
  due(snapshot) {
    const hour = new Date().getHours();
    return Background.on() && snapshot && (hour < 1 || hour >= 6) && Date.now() / 1000 - snapshot.syncedAt > 6 * 3600;
  },
};

// ---- alerts ---------------------------------------------------------------------------------------

/** Each alert goes once, by its key. */
const Alerts = {
  seen() {
    try {
      return new Set(JSON.parse(localStore.get("alertsSeen", "[]")));
    } catch {
      return new Set();
    }
  },
  post(list) {
    if (!AlertPrefs.get().on || !list.length) return;
    const seen = Alerts.seen();
    for (const a of list) {
      if (seen.has(a.key)) continue;
      seen.add(a.key);
      api("notify", { title: a.title, body: a.text }).catch(() => {});
    }
    localStore.set("alertsSeen", JSON.stringify([...seen].slice(-1500)));
  },

  few(items, name, key, one, many) {
    if (items.length <= 3) return items.map((i) => ({ key: key(i), ...one(i) }));
    return [{ key: `${name}:${items.map(key).join("|").length}:${items.length}:${key(items[0])}`, ...many(items.length) }];
  },

  /** What a sync should tell you, from the snapshot before it and after it. Nothing on a first sync. */
  after(old, s, p = AlertPrefs.get(), now = new Date()) {
    if (!old) return [];
    const out = [];
    const today = Dates.addDays(now, 0);
    const same = old.semester.id === s.semester.id;
    if (p.attendance && !Rules.exempt(s) && same) {
      const before = new Map(old.attendance.map((r) => [Rules.key(r), r]));
      for (const r of s.attendance) {
        const prev = before.get(Rules.key(r));
        if (!prev || (prev.attended === r.attended && prev.total === r.total)) continue;
        if (p.muted.includes(courseKey(r.courseCode, r.classType))) continue;
        const tag = `${Rules.key(r)}|${r.attended}/${r.total}`;
        const skips = Rules.canSkipNow(r);
        const plural = (n) => `${n} skip${n === 1 ? "" : "s"}`;
        if (Rules.atRisk(r) && !Rules.atRisk(prev)) {
          out.push({ key: `risk:${tag}`, title: `${r.courseName} is below 75%`, text: `${Rules.percent(r)}% (${r.attended}/${r.total}). Attend the next ${Rules.mustAttend(r)} to get back.` });
        } else if (p.warnBelow > 75 && !Rules.atRisk(r) && below(r, p.warnBelow) && !below(prev, p.warnBelow)) {
          out.push({ key: `line${p.warnBelow}:${tag}`, title: `${r.courseName} is below ${p.warnBelow}%`, text: `${Rules.percent(r)}% (${r.attended}/${r.total}). Still above 75%: ${skips === 0 ? "no skips left." : `${plural(skips)} left.`}` });
        } else if (p.skipsLeft >= 0 && !Rules.atRisk(r) && skips <= p.skipsLeft && Rules.canSkipNow(prev) > p.skipsLeft) {
          out.push({ key: `edge:${tag}`, title: skips === 0 ? `No skips left in ${r.courseName}` : `${plural(skips)} left in ${r.courseName}`, text: `${r.attended}/${r.total}. ${skips === 0 ? "Missing the next class takes you below 75%." : `Miss ${skips + 1} more and you're below 75%.`}` });
        }
      }
    }
    if (p.remarks && old.feeds.remarks.length) {
      const before = new Map(old.feeds.remarks.map((r) => [`${r.code}|${r.type}`, r]));
      for (const r of s.feeds.remarks) {
        const debarred = /debar/i.test(r.remark);
        if (!debarred && !/revok/i.test(r.remark)) continue;
        if (before.get(`${r.code}|${r.type}`)?.remark === r.remark) continue;
        out.push({ key: `remark:${r.code}|${r.type}|${r.remark}`, title: `${debarred ? "Debarred: " : "Revoked: "}${r.name || r.code}`, text: `VTOP's remark: ${r.remark}${r.attendance != null ? ` · attendance ${num(r.attendance)}%` : ""}` });
      }
    }
    if (p.notices && old.feeds.dailyAt > 0) {
      for (const w of s.feeds.registration) {
        if (!w.start || new Date(w.start) < today || old.feeds.registration.some((o) => o.start === w.start)) continue;
        out.push({ key: `reg:${w.start}`, title: `${w.semester || "Course"} registration`, text: `Opens ${new Date(w.start).toLocaleString("en-GB", { weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}` });
      }
    }
    if (p.notices && old.feeds.watchAt > 0) {
      if (old.feeds.circulars.length) {
        const seen = new Set(old.feeds.circulars.map((c) => c.id));
        out.push(...Alerts.few(s.feeds.circulars.filter((c) => !seen.has(c.id)), "circulars", (c) => `circ:${c.id}`, (c) => ({ title: "New circular", text: c.title }), (n) => ({ title: `${n} new circulars`, text: "Open Circulars to read them." })));
      }
      if (old.feeds.projects.length && p.projectAreas.length) {
        const seen = new Set(old.feeds.projects.map((c) => c.id));
        out.push(...Alerts.few(s.feeds.projects.filter((c) => !seen.has(c.id) && p.projectAreas.includes(c.area)), "projects", (c) => `proj:${c.id}`, (c) => ({ title: `New project · ${c.area}`, text: c.title }), (n) => ({ title: `${n} new faculty projects`, text: "In the areas you follow." })));
      }
      const before = new Map(old.feeds.windows.map((w) => [w.id, w]));
      for (const w of s.feeds.windows) {
        if (w.open && before.get(w.id)?.open === false) out.push({ key: `win:${w.id}|${Dates.iso(today)}`, title: `${w.title} is open`, text: "VTOP has opened it. Check the dates and apply there." });
      }
    }
    if (p.marks && old.marks.length && same) {
      const seen = new Set(old.marks.flatMap((c) => c.items.filter((m) => m.scored != null).map((m) => `${c.classId}|${m.title}`)));
      for (const c of s.marks) for (const m of c.items) {
        if (m.scored == null || seen.has(`${c.classId}|${m.title}`)) continue;
        out.push({ key: `mark:${c.classId}|${m.title}`, title: `New mark · ${c.courseName}`, text: `${m.title}: ${num(m.scored)}/${m.max != null ? num(m.max) : "?"}` });
      }
    }
    return out;
  },

  /** Due dates and meals and the minutes before class: asked every minute while daVIT runs. */
  tick(s, p = AlertPrefs.get(), now = new Date()) {
    const out = [];
    const today = Dates.addDays(now, 0);
    if (p.deadlines) {
      for (const r of Yours.data.reminders || []) {
        const n = Dates.between(today, Dates.parse(r.date));
        if (n === 0 || n === 1) out.push({ key: `rem:${r.id}|${n}`, title: `${n === 0 ? "Today: " : "Tomorrow: "}${r.title}`, text: "Your reminder" });
      }
      for (const c of s?.assignments || []) for (const a of c.items) {
        const due = Dates.vtop(a.due);
        if (a.updated || !due) continue;
        const n = Dates.between(today, due);
        if (n === 0 || n === 1) out.push({ key: `due:${c.classId}|${a.title}|${a.due}|${n}`, title: `${n === 0 ? "Due today: " : "Due tomorrow: "}${a.title}`, text: `${c.courseName} · not submitted yet` });
      }
    }
    if (p.exams) {
      for (const e of s?.exams || []) {
        const d = Dates.vtop(e.date);
        if (d && Dates.between(today, d) === 1) out.push({ key: `exam:${e.classId}|${e.type}|${e.date}`, title: `${e.type || "Exam"} tomorrow: ${e.courseName}`, text: [e.time, e.venue, e.seat && `Seat ${e.seat}`].filter(Boolean).join(" · ") });
      }
    }
    if (p.notices) {
      for (const w of s?.feeds.registration || []) {
        if (!w.start) continue;
        const mins = (new Date(w.start) - now) / 60000;
        if (mins > 0 && mins <= 30) out.push({ key: `regsoon:${w.start}`, title: `${w.semester || "Course"} registration in 30 minutes`, text: "Have your courses ready." });
      }
    }
    if (s && p.beforeClass > 0) out.push(...Alerts.beforeClass(s, p, now));
    if (p.meals.length) out.push(...Alerts.meals(p, now));
    return out;
  },

  beforeClass(s, p, now) {
    const out = [];
    const week = weekOf(s, App.slots);
    const cal = calendarOf(s.calendar);
    const minute = now.getHours() * 60 + now.getMinutes();
    for (const slot of classesOn(week, now, cal)) {
      const left = slot.meeting.start - minute;
      if (left <= 0 || left > p.beforeClass) continue;
      const a = slot.attendance;
      const row = a && !Rules.exempt(s) && p.attendance && !p.muted.includes(courseKey(a.courseCode, a.classType)) ? a : null;
      const near = row && (Rules.atRisk(row) || below(row, p.warnBelow) || Rules.canSkipNow(row) <= Math.max(p.skipsLeft, 0) + 2);
      if (p.beforeClassNearLine && !near) continue;
      const skips = row ? Rules.canSkipNow(row) : 0;
      const text = !row ? `In ${left} min · ${slot.isLab ? "Lab" : "Theory"}`
        : Rules.atRisk(row) ? `You're at ${Rules.percent(row)}%. Attend the next ${Rules.mustAttend(row)} to get back to 75%.`
          : skips === 0 ? `${Rules.percent(row)}% now. Miss this one and you drop to ${Math.floor((row.attended * 100) / (row.total + 1))}%, below 75.`
            : `${Rules.percent(row)}% · ${skips} skip${skips === 1 ? "" : "s"} left.`;
      out.push({ key: `class:${slot.course.classId}|${slot.meeting.slot}|${Dates.iso(now)}T${slot.meeting.start}`, title: `${slot.course.courseName} at ${Dates.clock(slot.meeting.start)}${slot.course.venue ? ` · ${slot.course.venue}` : ""}`, text });
    }
    return out;
  },

  meals(p, now) {
    const mess = MessBook.data?.messes[0];
    if (!mess) return [];
    const row = MessBook.row(mess, now);
    if (!row) return [];
    const out = [];
    const minute = now.getHours() * 60 + now.getMinutes();
    for (const meal of p.meals) {
      const start = MessBook.startOf(mess.times[meal]);
      if (start == null) continue;
      const left = start - minute;
      if (left < 0 || left > p.beforeMeal) continue;
      out.push({ key: `meal:${Dates.iso(now)}|${meal}`, title: `${meal} at ${mess.times[meal].split(" to ")[0]}`, text: row[meal.toLowerCase()] || "" });
    }
    return out;
  },
};

const MessBook = {
  data: null,
  load() {
    MessBook.loading ||= fetch("mess.json").then((r) => r.json()).then((d) => { MessBook.data = d; return d; });
    return MessBook.loading;
  },
  row(mess, at) {
    const month = `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, "0")}`;
    return mess.sheets.find((x) => x.month === month)?.rows.find((r) => r.dates.includes(at.getDate())) || null;
  },
  /** "7:30 AM to 9:30 AM" as minutes from midnight. */
  startOf(range) {
    const m = /^(\d{1,2}):(\d{2})\s*(AM|PM)/i.exec(range || "");
    if (!m) return null;
    return ((+m[1] % 12) + (/pm/i.test(m[3]) ? 12 : 0)) * 60 + +m[2];
  },
};

// ---- calendar files -------------------------------------------------------------------------------

const Ics = {
  escape: (s) => String(s || "").replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\n/g, "\\n"),
  fold(line) {
    let out = "";
    let bytes = 0;
    for (const ch of line) {
      const n = new TextEncoder().encode(ch).length;
      if (bytes + n > 75) { out += "\r\n "; bytes = 1; }
      out += ch;
      bytes += n;
    }
    return out;
  },
  /** Classes are in campus time (IST), whatever zone this computer is in. */
  utc(date, minute) {
    const t = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(), 0, minute) - 330 * 60000;
    return new Date(t).toISOString().replace(/[-:]|\.\d{3}/g, "");
  },
  file(events, name = "daVIT classes") {
    const stamp = new Date().toISOString().replace(/[-:]|\.\d{3}/g, "");
    const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//daVIT//EN", "CALSCALE:GREGORIAN", `X-WR-CALNAME:${Ics.escape(name)}`];
    for (const e of events) {
      lines.push("BEGIN:VEVENT", `UID:${e.uid}`, `DTSTAMP:${stamp}`);
      if (e.allDay) lines.push(`DTSTART;VALUE=DATE:${Dates.iso(e.date).replace(/-/g, "")}`);
      else lines.push(`DTSTART:${Ics.utc(e.date, e.start)}`, `DTEND:${Ics.utc(e.date, e.end)}`);
      lines.push(`SUMMARY:${Ics.escape(e.title)}`, `LOCATION:${Ics.escape(e.location)}`, `DESCRIPTION:${Ics.escape(e.notes)}`, "END:VEVENT");
    }
    lines.push("END:VCALENDAR");
    return lines.map((l) => `${Ics.fold(l)}\r\n`).join("");
  },
  /** Every class from today to the end of the semester, holidays and swapped days included. */
  classes(s) {
    const cal = calendarOf(s.calendar);
    if (!cal) return [];
    const last = [cal.lastTheory, cal.lastLab].filter(Boolean).sort((a, b) => b - a)[0];
    if (!last) return [];
    const week = weekOf(s, App.slots);
    const out = [];
    for (let d = Dates.addDays(new Date(), 0); d <= last; d = Dates.addDays(d, 1)) {
      const wd = cal.timetableDay(d);
      if (!wd) continue;
      for (const slot of week[wd] || []) {
        const end = slot.isLab ? cal.lastLab : cal.lastTheory;
        if (end && d > end) continue;
        const c = slot.course;
        out.push({ uid: `${c.classId || c.courseCode}-${slot.meeting.slot}-${Dates.iso(d).replace(/-/g, "")}@davit`, title: c.courseName + (slot.isLab ? " · Lab" : ""), location: c.venue, notes: [c.courseCode, slot.meeting.slot, c.faculty].filter(Boolean).join(" · "), date: d, start: slot.meeting.start, end: slot.meeting.end });
      }
    }
    return out;
  },
  async exam(e) {
    const d = Dates.vtop(e.date);
    const times = /(\d{1,2}):(\d{2})\s*(AM|PM)?\s*(?:-|to)\s*(\d{1,2}):(\d{2})\s*(AM|PM)?/i.exec(e.time || "");
    const mins = (hh, mm, ap) => ((+hh % (ap ? 12 : 24)) + (/pm/i.test(ap || "") ? 12 : 0)) * 60 + +mm;
    const event = { uid: `${e.classId || e.courseCode}-${e.type}-${e.date}@davit`, title: `${e.type || "Exam"}: ${e.courseName}`, location: e.venue === "-" ? "" : e.venue, notes: [e.courseCode, e.seat && `Seat ${e.seat}`].filter(Boolean).join(" · "), date: d, allDay: !times, start: times ? mins(times[1], times[2], times[3]) : 0, end: times ? mins(times[4], times[5], times[6]) : 0 };
    const f = await api("save_made", { name: `${e.courseCode} ${e.type || "exam"}.ics`, text: Ics.file([event], "daVIT exam"), data: null });
    await api("open_file", { path: f.path });
  },
};

// ---- your own reminders ---------------------------------------------------------------------------

function dueSection(s) {
  const today = Dates.addDays(new Date(), 0);
  const box = h("div");
  let adding = false;
  let all = false;
  const draw = () => {
    const assignments = s.assignments.flatMap((c) => c.items.map((a) => ({ c, a, d: Dates.vtop(a.due) })))
      .filter((x) => !x.a.updated && x.d && x.d >= today && Dates.between(today, x.d) <= 14)
      .map(({ c, a, d }) => ({ d, card: h("button.card.click.assignments", { type: "button", onclick: () => go("assignment", { classId: c.classId, code: a.code }) },
        h("div.row", null, h("div.grow", null, h("div.strong", null, a.title), small(`${c.courseName} · ${Dates.short(d)}`)), h("span.badge", { class: Dates.between(today, d) <= 3 ? "on" : "" }, dayWord(Dates.between(today, d))))) }));
    const mine = Yours.data.reminders.filter((r) => Dates.parse(r.date) >= today).map((r) => ({ d: Dates.parse(r.date), card: h("div.card.reminders", null,
      h("div.row", null, h("div.grow", null, h("div.strong", null, r.title), small(`Your reminder · ${Dates.short(Dates.parse(r.date))}`)),
        h("span.badge", { class: Dates.between(today, Dates.parse(r.date)) <= 3 ? "on" : "" }, dayWord(Dates.between(today, Dates.parse(r.date)))),
        button("Done", async () => { Yours.data.reminders = Yours.data.reminders.filter((x) => x.id !== r.id); await Yours.save(); draw(); }))) }));
    const due = [...assignments, ...mine].sort((a, b) => a.d - b.d);
    const title = h("input.field", { placeholder: "Reminder", "aria-label": "What's it for", maxlength: 80 });
    const presets = h("div.chips", { style: { marginBottom: "8px" } }, ["Mess change", "Go to Allmart", "Offline quiz", "Project review", "Report submission"].map((t) => h("button.chip", { type: "button", onclick: () => { title.value = t; title.focus(); } }, t)));
    const when = h("input.field", { type: "date", "aria-label": "When", value: Dates.iso(Dates.addDays(today, 1)), min: Dates.iso(today), style: { width: "170px" } });
    box.replaceChildren(...[
      h("div.row", { style: { margin: "24px 0 10px" } }, h("h2.grow", { style: { margin: 0 } }, "Due"), button(adding ? "Close" : "Add", () => { adding = !adding; draw(); })),
      adding ? h("form.card.reminders", { style: { marginBottom: "10px" }, onsubmit: async (e) => {
        e.preventDefault();
        if (!title.value.trim() || !when.value) return;
        Yours.data.reminders.push({ id: Date.now(), title: title.value.trim(), date: when.value });
        await Yours.save();
        adding = false;
        draw();
      } }, presets, h("div.row.wrap", null, h("div.grow", { style: { minWidth: "200px" } }, title), when, h("button.btn.solid", { type: "submit" }, "Add"))) : null,
      due.length ? h("div.grid2", null, due.slice(0, all ? due.length : 4).map((x) => x.card)) : empty("Nothing due in the next two weeks."),
      due.length > 4 ? h("div", { style: { marginTop: "8px" } }, button(all ? "Show fewer" : `Show ${due.length - 4} more`, () => { all = !all; draw(); })) : null,
    ].flat().filter(Boolean));
  };
  draw();
  return box;
}

// ---- exam board -----------------------------------------------------------------------------------

function screenExamBoard(s) {
  const today = Dates.addDays(new Date(), 0);
  const dated = s.exams.map((e) => ({ e, d: Dates.vtop(e.date) })).filter((x) => x.d).sort((a, b) => a.d - b.d);
  const group = (dated.find((x) => x.d >= today) || dated.at(-1))?.e.type;
  const papers = dated.filter((x) => x.e.type === group);
  const left = papers.filter((x) => x.d >= today).length;
  const known = (v) => (v && v.trim() !== "-" ? v.trim() : null);
  const list = papers.map(({ e, d }, i) => {
    const n = Dates.between(today, d);
    const free = i ? Dates.between(papers[i - 1].d, d) - 1 : null;
    const done = n < 0;
    return [free == null ? null : h("div.small.strong", { style: { padding: "6px 0" } }, free === -1 ? "The same day" : free === 0 ? "The next day" : free === 1 ? "1 free day" : `${free} free days`),
      h("div.card.exams", { style: done ? { opacity: 0.55 } : null },
        h("div.row", { style: { alignItems: "flex-start" } },
          h("div", { style: { width: "64px" } }, h("div.big", null, d.getDate()), small(d.toLocaleDateString("en-GB", { month: "short" })), small(d.toLocaleDateString("en-GB", { weekday: "short" }))),
          h("div.grow", null, h("div.strong", null, e.courseName), small([...new Set([e.time, e.session].filter(Boolean))].join(" · ")), small([known(e.venue), known(e.seat) && `seat ${known(e.seat)}`].filter(Boolean).join(" · ") || "Room and seat not out yet")),
          h("span.badge", { class: !done && n <= 3 ? "on" : "" }, done ? "Done" : n === 0 ? "Today" : n === 1 ? "Tomorrow" : `${n} days`)),
        done ? null : h("div.row.wrap", { style: { marginTop: "10px" } },
          button("Materials", () => go("academics", { tab: 4, code: e.courseCode })),
          button("Add to calendar", () => Ics.exam(e).catch((x) => toast(x))))),
    ];
  });
  const others = [...new Set(dated.map((x) => x.e.type))].filter((t) => t !== group);
  return h("div.page", null,
    pageTitle("Exam board", papers.length ? `${group || "Exams"} · ${papers.length} paper${papers.length === 1 ? "" : "s"}, ${left} to go · ${Dates.short(papers[0].d)} to ${Dates.short(papers.at(-1).d)}` : "Your exam schedule shows here once VTOP publishes it.",
      button("Make-up, arrear and other exams", () => go("otherexams"))),
    list,
    others.length ? small(`Also on VTOP: ${others.join(", ")}. All of them are in Academics, under Exams.`) : null);
}

function screenOtherExams() {
  return simplePage("Other exams", "Make-up, arrear, English proficiency and comprehensive exams.", "otherExams", (list) => (list.length ? list.map((t) => [h("h2", null, t.title), plainTable(t.table)]) : empty("VTOP lists none of these for you.")));
}

// ---- corrections ----------------------------------------------------------------------------------

const Corrections = {
  list: () => Yours.data.corrections,
  state(c, s) {
    const lines = (s.history[c.course] || []).filter((d) => d.date === c.date && d.slot === c.slot);
    if (!lines.length) return "gone";
    return lines.every((d) => d.status === "Present" || d.status === "OnDuty") ? "fixed" : "waiting";
  },
  async toggle(course, date, slot, onDuty) {
    const list = Yours.data.corrections;
    const at = list.findIndex((c) => c.course === course && c.date === date && c.slot === slot && c.onDuty === onDuty);
    if (at >= 0) list.splice(at, 1);
    else list.push({ course, date, slot, onDuty, at: Date.now() });
    await Yours.save();
  },
  /** The still-open ones as a message for your faculty, by VTOP's names. */
  message(raw) {
    const open = Yours.data.corrections.filter((c) => Corrections.state(c, raw) === "waiting");
    if (!open.length) return "";
    const by = new Map();
    for (const c of open) by.set(c.course, [...(by.get(c.course) || []), c]);
    let out = "VTOP shows me absent for these classes, but I was there:\n";
    for (const [key, days] of by) {
      const row = raw.attendance.find((r) => Rules.key(r) === key);
      out += `\n${row ? `${row.courseCode} ${row.courseName} (${row.classType})` : key}\n`;
      for (const c of days.sort((a, b) => Dates.vtop(a.date) - Dates.vtop(b.date))) out += `  ${c.date} · ${c.slot}${c.onDuty ? " · on duty" : " · present"}\n`;
    }
    return out.trimEnd();
  },
  fixed(old, s) {
    return old ? Yours.data.corrections.filter((c) => Corrections.state(c, old) === "waiting" && Corrections.state(c, s) === "fixed") : [];
  },
};

const dayLabel = (t) => Dates.vtop(t)?.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" }) || t;

/** Hands text to whoever wants it: copied, ready to paste into an email or message. */
function copyButton(labelText, text) {
  const b = button(labelText, async () => {
    try {
      await navigator.clipboard.writeText(text());
      b.textContent = "Copied. Paste it anywhere";
    } catch {
      toast("Couldn't reach the clipboard.");
    }
  }, "solid");
  return b;
}

function screenCorrections(s) {
  const page = h("div.page");
  const courseLabel = (key) => {
    const r = s.attendance.find((x) => Rules.key(x) === key);
    return r ? `${r.courseName} · ${Rules.isLab(r.classType) ? "Lab" : "Theory"}` : key.split("|")[0];
  };
  const draw = () => {
    const list = Corrections.list();
    const waiting = list.filter((c) => Corrections.state(c, s) === "waiting");
    const done = list.filter((c) => Corrections.state(c, s) !== "waiting");
    const by = new Map();
    for (const c of waiting) by.set(c.course, [...(by.get(c.course) || []), c]);
    const rows = s.attendance.filter((r) => (s.history[Rules.key(r)] || []).some((d) => d.status === "Absent"));
    page.replaceChildren(...[
      pageTitle("Attendance corrections", "Absent on VTOP but you were there? Mark it, send the list, and hear when it's fixed."),
      waiting.length ? [h("h2", null, `Waiting on VTOP · ${waiting.length}`), h("div.grid2", null, [...by].map(([course, days]) => h("div.card.attendance", null, h("div.strong", null, courseLabel(course)),
        days.sort((a, b) => Dates.vtop(b.date) - Dates.vtop(a.date)).map((c) => h("div.row", { style: { marginTop: "8px" } }, h("div.small.grow", null, `${dayLabel(c.date)} · ${c.slot} · ${c.onDuty ? "on duty" : "was there"}`), button("Unmark", async () => { await Corrections.toggle(c.course, c.date, c.slot, c.onDuty); draw(); })))))),
      h("div", { style: { marginTop: "12px" } }, copyButton("Copy the list for your faculty", () => Corrections.message(App.snapshot))),
      small("The list written out: course, date and slot. Paste it into an email or WhatsApp.")] : null,
      done.length ? [h("h2", null, "Settled"), h("div.card", null, done.map((c) => h("div.row", { style: { padding: "4px 0" } }, h("div.grow", null, h("div.small.strong", null, courseLabel(c.course)), small(`${dayLabel(c.date)} · ${Corrections.state(c, s) === "fixed" ? "VTOP fixed it" : "VTOP no longer lists it"}`)), button("Clear", async () => { await Corrections.toggle(c.course, c.date, c.slot, c.onDuty); draw(); }))))] : null,
      h("h2", null, "Your absences"),
      !rows.length ? empty(Object.keys(s.history).length ? "VTOP has you present for every class. Nothing to correct." : "Day-by-day attendance shows here after the next sync.") : [
        small("Mark the ones you were actually at."),
        h("div.grid2", { style: { marginTop: "8px" } }, rows.map((r) => h("div.card.attendance", null, h("div.strong", null, `${r.courseName} · ${Rules.isLab(r.classType) ? "Lab" : "Theory"}`),
          (s.history[Rules.key(r)] || []).filter((d) => d.status === "Absent").sort((a, b) => Dates.vtop(b.date) - Dates.vtop(a.date)).map((d) => {
            const mark = list.find((c) => c.course === Rules.key(r) && c.date === d.date && c.slot === d.slot);
            return h("div", { style: { marginTop: "10px" } }, h("div.small.strong", null, `${dayLabel(d.date)} · ${d.slot}`),
              h("div.chips", { style: { marginTop: "4px" } }, [[false, "I was there"], [true, "On duty"]].map(([od, t]) => {
                const on = mark && mark.onDuty === od;
                return h("button.chip", { type: "button", class: on ? "on" : "", "aria-pressed": !!on, onclick: async () => {
                  if (mark) await Corrections.toggle(Rules.key(r), d.date, d.slot, mark.onDuty);
                  if (!on) await Corrections.toggle(Rules.key(r), d.date, d.slot, od);
                  draw();
                } }, t);
              })));
          })))),
      ],
    ].flat(Infinity).filter(Boolean));
  };
  draw();
  return page;
}

// ---- free time with friends -----------------------------------------------------------------------

const WeekCode = {
  PREFIX: "DAVIT1:",
  encode: (name, classes) => `${WeekCode.PREFIX}${encodeURIComponent(name.trim().slice(0, 30))}:${[...new Set(classes.map((c) => `${c.slot}@${c.block}`))].join(",")}`,
  /** The name and classes in a text, which may be a whole message with the code inside. */
  decode(text) {
    const at = text.indexOf(WeekCode.PREFIX);
    if (at < 0) return null;
    const body = text.slice(at + WeekCode.PREFIX.length).trim().split(/[ \n]/)[0];
    const colon = body.indexOf(":");
    let name = "";
    try {
      name = decodeURIComponent((colon >= 0 ? body.slice(0, colon) : body).replace(/\+/g, " ")).trim();
    } catch { /* a bad name still leaves the classes */ }
    const classes = (colon >= 0 ? body.slice(colon + 1) : "").split(",").map((c) => {
      const [slot, block] = c.split("@");
      const s = (slot || "").trim().toUpperCase();
      return s && /^[A-Z0-9+]+$/.test(s) ? { slot: s, block: (block || "").trim().toUpperCase().slice(0, 8) } : null;
    }).filter(Boolean);
    return !name && !classes.length ? null : { name: name || "Friend", classes };
  },
};

const blockOf = (venue) => (/^[A-Za-z]+/.exec((venue || "").trim()) || [""])[0].toUpperCase();
const sharedWeek = (s) => {
  const seen = new Map();
  for (const c of s?.timetable || []) {
    const x = { slot: c.slot.replace(/ /g, "").toUpperCase(), block: blockOf(c.venue) };
    seen.set(`${x.slot}@${x.block}`, x);
  }
  return [...seen.values()];
};

/** When everyone is out of class on a day, for at least [minutes], within college hours. */
function freeTogether(people, day, minutes = 30) {
  const START = 8 * 60;
  const END = 19 * 60 + 30;
  const meetingsOfClass = (c) => splitSlots(c.slot).flatMap((p) => App.slots.get(p) || []);
  const busy = Object.fromEntries(Object.entries(people).map(([who, classes]) => [who, classes.flatMap((c) => meetingsOfClass(c).filter((m) => m.day === day).map((m) => ({ start: m.start, end: m.end, block: c.block })))]));
  const all = Object.values(busy).flat().sort((a, b) => a.start - b.start);
  const windows = [];
  let free = START;
  for (const b of all) {
    if (b.start - free >= minutes) windows.push([free, Math.min(b.start, END)]);
    free = Math.max(free, b.end);
  }
  if (END - free >= minutes) windows.push([free, END]);
  return windows.filter(([a, b]) => b - a >= minutes).map(([a, b]) => ({ day, start: a, end: b, comingFrom: Object.entries(busy).map(([who, list]) => {
    const last = list.filter((m) => m.end <= a).sort((x, y) => y.end - x.end)[0];
    return last ? [who, last.block] : null;
  }).filter(Boolean) }));
}

const howLong = (m) => (m < 60 ? `${m} min` : m % 60 === 0 ? `${m / 60} h` : `${Math.floor(m / 60)} h ${m % 60} min`);

function screenFriends(s) {
  const page = h("div.page");
  let chosen = null;
  let note = "";
  let pasting = false;
  let scanner = null;
  const mine = sharedWeek(s);
  const defaultName = s?.profile?.name ? titleCase(s.profile.name.split(" ")[0]) : "";
  const addFriend = async (text) => {
    const w = WeekCode.decode(text);
    if (!w || !w.classes.length) return false;
    Yours.data.friends.push({ id: Date.now(), name: w.name, week: WeekCode.encode(w.name, w.classes) });
    await Yours.save();
    note = `Added ${w.name}.`;
    return true;
  };
  const stopCamera = () => { if (scanner) { clearInterval(scanner.timer); scanner.stream.getTracks().forEach((t) => t.stop()); scanner = null; } };
  const picture = h("input", { type: "file", accept: "image/*", hidden: true, onchange: async (e) => {
    const f = e.target.files[0];
    e.target.value = "";
    if (!f) return;
    const data = (await readPicked(f)).data;
    const text = await api("qr_decode", { data }).catch(() => null);
    if (!text || !(await addFriend(text))) note = "That picture has no daVIT week in it.";
    draw();
  } });
  const scan = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true });
      const video = h("video", { autoplay: true, muted: true, playsinline: true, style: { width: "100%", maxWidth: "420px" } });
      video.srcObject = stream;
      const c = h("canvas");
      const timer = setInterval(async () => {
        if (!video.videoWidth) return;
        c.width = video.videoWidth;
        c.height = video.videoHeight;
        c.getContext("2d").drawImage(video, 0, 0);
        const text = await api("qr_decode", { data: c.toDataURL("image/jpeg", 0.8).split(",")[1] }).catch(() => null);
        if (text && scanner) {
          stopCamera();
          if (!(await addFriend(text))) note = "That QR code isn't a daVIT week.";
          draw();
        }
      }, 700);
      scanner = { stream, video, timer };
      draw();
    } catch {
      note = "No camera daVIT can use. Pick a picture of the code, or paste it.";
      draw();
    }
  };
  const draw = () => {
    const name = localStore.get("shareName", "") || defaultName;
    const friends = Yours.data.friends.map((f) => ({ ...f, classes: WeekCode.decode(f.week)?.classes || [] }));
    const withWhom = friends.filter((f) => !chosen || chosen.includes(f.id));
    const people = { You: mine };
    for (const f of withWhom) people[f.name] = f.classes;
    const days = [...new Set([1, 2, 3, 4, 5, ...Object.values(people).flat().flatMap((c) => splitSlots(c.slot).flatMap((p) => (App.slots.get(p) || []).map((m) => m.day)))])].filter((d) => d >= 1 && d <= 6).sort();
    const cal = s ? calendarOf(s.calendar) : null;
    const todayOrder = cal?.timetableDay(new Date()) ?? Dates.weekday(new Date());
    const code = WeekCode.encode(name || "Friend", mine);
    const qr = h("div.qr", { role: "img", "aria-label": "Your week as a QR code, for a friend to scan" });
    if (mine.length) api("qr_encode", { text: code }).then((m) => qr.replaceChildren(qrSvg(m))).catch(() => {});
    const pasted = h("input.field", { placeholder: "Their message or code", "aria-label": "Their message or code" });
    page.replaceChildren(...[
      pageTitle("Free time with friends", "Swap weeks by QR code and see when you're all out of class. Only your class slots and blocks are shared, computer to computer; nothing goes to a server."),
      picture,
      friends.length ? [h("h2", null, "Free together"),
        h("div.chips", { style: { marginBottom: "10px" } }, friends.map((f) => {
          const on = withWhom.includes(f);
          return h("button.chip", { type: "button", class: on ? "on" : "", "aria-pressed": on, onclick: () => { const now = chosen || friends.map((x) => x.id); chosen = on ? now.filter((i) => i !== f.id) : [...now, f.id]; draw(); } }, f.name);
        })),
        h("div.grid2", null, days.map((day) => {
          const windows = freeTogether(people, day);
          return h("div.card.people", null, h("div.row", null, h("div.strong.grow", { style: { fontSize: "17px" } }, Dates.dayName(day)), day === todayOrder ? h("span.badge.on", null, "Today") : null),
            windows.length ? windows.map((w) => h("div", { style: { marginTop: "8px" } }, h("div.strong", null, `${Dates.clock(w.start)} – ${Dates.clock(w.end)} · ${howLong(w.end - w.start)}`), w.comingFrom.length ? small(w.comingFrom.map(([who, block]) => `${who} from ${block || "class"}`).join(" · ")) : null)) : small("No time free together between 08:00 and 19:30."));
        }))] : null,
      h("h2", null, "Friends"),
      note ? h("div.small.strong", { style: { marginBottom: "6px" } }, note) : null,
      friends.length ? h("div.card", null, friends.map((f, i) => [i ? h("hr.hair") : null, h("div.row", null, h("div.grow", null, h("div.strong", null, f.name), small(`${f.classes.length} class${f.classes.length === 1 ? "" : "es"} a week`)), button("Remove", async () => { Yours.data.friends = Yours.data.friends.filter((x) => x.id !== f.id); await Yours.save(); draw(); }))])) : small("Scan a friend's code, or paste one they sent you, to see when you're both free."),
      scanner ? h("div.card", { style: { marginTop: "10px" } }, scanner.video, h("div", { style: { marginTop: "8px" } }, button("Stop the camera", () => { stopCamera(); draw(); }))) : null,
      h("div.row.wrap", { style: { marginTop: "10px" } }, scanner ? null : button("Scan with the camera", scan, "solid"), button("From a picture", () => picture.click()), button("Paste a code they sent", () => { pasting = !pasting; draw(); })),
      pasting ? h("div.row", { style: { marginTop: "8px" } }, pasted, button("Add", async () => { if (await addFriend(pasted.value)) pasting = false; else note = "No daVIT week in that. It starts with DAVIT1:"; draw(); }, "solid")) : null,
      h("h2", null, "Your code"),
      !mine.length ? small("Your code shows here once your timetable has synced.") : [
        h("label", null, label("Shown to friends as"), h("input.field", { value: name, maxlength: 30, style: { maxWidth: "340px" }, onchange: (e) => { localStore.set("shareName", e.target.value.slice(0, 30)); draw(); } })),
        qr,
        small(`${mine.length} classes: their slots and blocks, and the name above. Nothing else.`),
        h("div", { style: { marginTop: "8px" } }, copyButton("Copy it as a message", () => `My week on daVIT, for free time together. Paste this into daVIT, under Free time with friends: ${code}`)),
      ],
    ].flat(Infinity).filter(Boolean));
  };
  draw();
  return page;
}

function qrSvg(m) {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", `-4 -4 ${m.size + 8} ${m.size + 8}`);
  svg.setAttribute("width", "220");
  svg.setAttribute("height", "220");
  const bg = document.createElementNS(NS, "rect");
  for (const [k, v] of [["x", -4], ["y", -4], ["width", m.size + 8], ["height", m.size + 8], ["fill", "#ffffff"]]) bg.setAttribute(k, v);
  svg.append(bg);
  const path = document.createElementNS(NS, "path");
  let d = "";
  m.dark.forEach((on, i) => { if (on) d += `M${i % m.size} ${Math.floor(i / m.size)}h1v1h-1z`; });
  path.setAttribute("d", d);
  path.setAttribute("fill", "#111111");
  svg.append(path);
  return svg;
}

// ---- materials: every course, and search across them all ------------------------------------------

const Fuzzy = {
  STOP: new Set(["and", "of", "the", "for", "in", "to", "with", "using", "a", "an", "on"]),
  /** Lowercased runs of letters and digits, split where letters meet digits. */
  words(s) {
    return (String(s || "").match(/\p{L}+|\p{N}+/gu) || []).map((w) => w.toLowerCase());
  },
  distance(a, b, max) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    let prev2 = [];
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      const cur = [i];
      let low = i;
      for (let j = 1; j <= b.length; j++) {
        const cost = a[i - 1] === b[j - 1] ? 0 : 1;
        let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
        if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, prev2[j - 2] + 1);
        cur[j] = v;
        low = Math.min(low, v);
      }
      if (low > max) return max + 1;
      prev2 = prev;
      prev = cur;
    }
    return prev[b.length];
  },
  score(q, w) {
    if (w === q) return 10;
    if (w.startsWith(q)) return 8 - (2 * (w.length - q.length)) / w.length;
    if (q.length >= 3 && w.includes(q)) return 5;
    if (/\d/.test(q) || /\d/.test(w)) return 0;
    const allowed = q.length < 4 ? 0 : q.length < 8 ? 1 : 2;
    if (!allowed) return 0;
    const d = Math.min(Fuzzy.distance(q, w, allowed), w.length > q.length ? Fuzzy.distance(q, w.slice(0, q.length), allowed) : Infinity);
    return d <= allowed ? 4 - d : 0;
  },
  /** Docs are { fields: [{ text, weight }] }. Every word must match somewhere. */
  rank(docs, query) {
    const terms = Fuzzy.words(query);
    if (!terms.length) return docs.map((item) => ({ item, score: 0 }));
    const phrase = terms.join(" ");
    const out = [];
    for (const item of docs) {
      item.index ||= item.fields.map((f) => {
        const ws = Fuzzy.words(f.text);
        return { ws, weight: f.weight, joined: ws.join(" "), initials: ws.filter((w) => /^\p{L}/u.test(w) && !Fuzzy.STOP.has(w)).map((w) => w[0]).join("") };
      });
      let total = 0;
      let all = true;
      for (const t of terms) {
        let best = 0;
        for (const f of item.index) {
          for (const w of f.ws) best = Math.max(best, Fuzzy.score(t, w) * f.weight);
          if (t.length >= 2 && f.initials.startsWith(t)) best = Math.max(best, 6 * f.weight);
        }
        if (best <= 0) { all = false; break; }
        total += best;
      }
      if (!all) continue;
      if (terms.length > 1) for (const f of item.index) if (f.joined.includes(phrase)) total += 6 * f.weight;
      out.push({ item, score: total });
    }
    return out.sort((a, b) => b.score - a.score);
  },
};

/** Marks what a query matched, as text nodes and <mark>s, never HTML. */
function highlighted(text, query) {
  const terms = Fuzzy.words(query).filter((t) => t.length >= 2);
  if (!terms.length) return [text];
  const low = text.toLowerCase();
  const hits = [];
  for (const t of terms) { let at = low.indexOf(t); while (at >= 0) { hits.push([at, at + t.length]); at = low.indexOf(t, at + t.length); } }
  hits.sort((a, b) => a[0] - b[0]);
  const out = [];
  let i = 0;
  for (const [a, b] of hits) {
    if (a < i) continue;
    if (a > i) out.push(text.slice(i, a));
    out.push(h("mark", null, text.slice(a, b)));
    i = b;
  }
  out.push(text.slice(i));
  return out;
}

const Materials = {
  /** A course's rows: which are your own faculty's, and the key each file opens by. */
  rows(course, page, s) {
    const teaching = (s?.timetable || []).filter((t) => t.courseCode === course.code);
    const names = new Set(teaching.map((t) => facultyKey(t.faculty)).filter(Boolean));
    const ids = new Set((s?.faculty || []).filter((f) => names.has(facultyKey(f.name))).map((f) => f.empId));
    return page.materials.map((m) => ({
      course, material: m,
      moduleTitle: page.modules[m.module] || "",
      mine: (m.uploaderId && ids.has(m.uploaderId)) || names.has(facultyKey(m.uploader)),
      subject: course.code + (/l/i.test((course.type || "").slice(-1)) || /lab/i.test(course.typeName || "") ? " lab" : ""),
      faculty: facultyKey(m.uploader),
      key: `mat_${m.fileId}`,
    }));
  },
  line: (r) => [r.material.kind, titleCase(r.material.uploader), r.material.date, r.material.semester && r.material.semester.toLowerCase() !== (r.course.semesterName || "").toLowerCase() ? r.material.semester : null].filter(Boolean).join(" · "),
};

/** Your course pages, this semester first: what's there and how much is your faculty's. */
function materialsTab(s, args = {}) {
  const holder = h("div");
  sectionInto(holder, "courses", (courses) => {
    if (args.code) {
      const target = courses.find((c) => c.code === args.code && c.semesterId === s.semester.id) || courses.find((c) => c.code === args.code);
      if (target) {
        queueMicrotask(() => {
          history.replaceState({ route: "course", args: { course: target } }, "");
          go("course", { course: target }, true);
        });
        return empty("Opening the course page");
      }
    }
    const order = [...new Set(courses.map((c) => c.semesterId))].sort((a, b) => (a === s.semester.id ? -1 : b === s.semester.id ? 1 : 0));
    return [
      h("button.searchlauncher", { type: "button", onclick: () => go("search") }, "Search every course's materials"),
      order.map((id) => {
        const list = courses.filter((c) => c.semesterId === id);
        return [h("h2", null, list[0].semesterName || "Courses"), h("div.grid3", null, list.map((c) => h("button.card.click.assignments", { type: "button", onclick: () => go("course", { course: c }) }, h("div.strong", null, Names.all()[c.code] || c.name || c.code), small(`${c.code} · ${c.typeName || c.type}`))))];
      }),
      small("Pages open offline once fetched. Files you open stay until you delete them, in Downloads."),
    ];
  });
  return holder;
}

/** One course page: your own faculty's uploads first, by module; everyone else's below, folded. */
function screenCourse(s, args) {
  const c = args.course;
  const box = h("div");
  const body = h("div");
  const byModule = (rows, modules) => {
    const groups = new Map();
    for (const r of rows) groups.set(r.material.module, [...(groups.get(r.material.module) || []), r]);
    return [...groups].sort((a, b) => (a[0] === "" ? 1 : b[0] === "" ? -1 : (+a[0] || 999) - (+b[0] || 999))).map(([mod, list]) => [
      h("div.strong", { style: { margin: "12px 0 2px" } }, mod ? `Module ${mod}${modules[mod] ? ` · ${modules[mod]}` : ""}` : "Other"),
      list.map((r) => [fileRow(r.material.title, Materials.line(r), "material", { fileId: r.material.fileId }, r.key), h("hr.hair")]),
    ]);
  };
  const load = (force) => {
    body.replaceChildren(empty("Reading the course page (big ones take a few seconds)"));
    api("section", { name: "materials", arg: JSON.stringify(c), force: force || null }).then(async (r) => {
      const rows = Materials.rows(c, r.data, s);
      const saved = new Set(((await api("files_list").catch(() => null))?.cached || []).map((f) => f.key));
      let filter = 0;
      let othersOpen = false;
      let q = "";
      const list = h("div");
      const teachers = [...new Set((s?.timetable || []).filter((t) => t.courseCode === c.code).map((t) => t.faculty).filter(Boolean))];
      const draw = () => {
        const shown = (filter === 1 ? rows.filter((x) => x.mine) : filter === 2 ? rows.filter((x) => saved.has(x.key)) : rows)
          .filter((x) => Fuzzy.words(q).every((w) => `${x.material.title} ${x.material.uploader} module ${x.material.module} ${x.moduleTitle}`.toLowerCase().includes(w)));
        const mine = shown.filter((x) => x.mine);
        const others = shown.filter((x) => !x.mine);
        const tabs = [`All ${rows.length}`, `Your faculty ${rows.filter((x) => x.mine).length}`, `Saved ${rows.filter((x) => saved.has(x.key)).length}`];
        list.replaceChildren(...[
          choiceRow(tabs, filter, (i) => { filter = i; draw(); }),
          !rows.length ? empty("Nobody has uploaded anything here yet.") : null,
          mine.length || (filter !== 2 && teachers.length) ? [h("h2", null, `Your faculty${teachers.length ? ` · ${teachers.map(titleCase).join(", ")}` : ""}`), mine.length ? byModule(mine, r.data.modules) : small("Nothing from your faculty on this page yet.")] : null,
          others.length ? [h("div.row", { style: { marginTop: "14px" } }, h("div.label.grow", null, !mine.length && !teachers.length ? "Everyone's uploads" : "Other faculty"), button(othersOpen || !mine.length ? String(others.length) : `Show ${others.length}`, () => { othersOpen = !othersOpen; draw(); })), othersOpen || !mine.length ? byModule(others, r.data.modules) : null] : null,
        ].flat(Infinity).filter(Boolean));
      };
      draw();
      body.replaceChildren(h("input.field", { placeholder: "Search title, teacher or module", "aria-label": "Search this course", style: { marginBottom: "10px" }, oninput: (e) => { q = e.target.value; draw(); } }), list);
    }, (e) => body.replaceChildren(errorNote(e, () => load(true))));
  };
  load();
  box.append(...pageTitle(c.code, `${Names.all()[c.code] || c.name} · ${c.typeName || c.type}`, button("Refresh", () => load(true)), button("Back", () => history.back())).flat().filter(Boolean), body);
  return h("div.page", null, box);
}

/** Search across every course's materials: typos are fine, and it works offline once pages are fetched. */
function screenSearch(s) {
  const page = h("div.page");
  const sub = h("div.sub");
  const results = h("div");
  const docs = [];
  let query = "";
  const filter = { subject: null, faculty: null, module: null, mineOnly: false, savedOnly: false };
  let filtersOpen = false;
  let saved = new Set();
  api("files_list").then((f) => { saved = new Set(f.cached.map((x) => x.key)); }).catch(() => {});
  const recent = () => { try { return JSON.parse(localStore.get("recentSearches", "[]")); } catch { return []; } };
  let remember = null;
  const facets = (rows) => {
    const count = (list, key, detail) => {
      const m = new Map();
      for (const r of list) { const k = key(r); if (!k) continue; m.set(k, { id: k, count: (m.get(k)?.count || 0) + 1, detail: detail?.(r) || "" }); }
      return [...m.values()];
    };
    const fits = (r, skip) => (skip === "subject" || !filter.subject || r.subject === filter.subject) && (skip === "faculty" || !filter.faculty || r.faculty === filter.faculty) && (skip === "module" || !filter.module || r.material.module === filter.module);
    return {
      subjects: count(rows.filter((r) => fits(r, "subject")), (r) => r.subject, (r) => r.course.name).sort((a, b) => a.id.localeCompare(b.id)),
      faculties: count(rows.filter((r) => fits(r, "faculty")), (r) => r.faculty).sort((a, b) => a.id.localeCompare(b.id)),
      modules: count(rows.filter((r) => fits(r, "module")), (r) => r.material.module).sort((a, b) => (+a.id || 999) - (+b.id || 999)),
    };
  };
  const draw = () => {
    const ranked = Fuzzy.rank(docs, query).map((x) => x.item.row).filter((r) => (!filter.mineOnly || r.mine) && (!filter.savedOnly || saved.has(r.key)));
    const hits = ranked.filter((r) => (!filter.subject || r.subject === filter.subject) && (!filter.faculty || r.faculty === filter.faculty) && (!filter.module || r.material.module === filter.module));
    const f = facets(ranked);
    const set = filter.subject || filter.faculty || filter.module;
    const active = [filter.subject && [filter.subject, () => { filter.subject = null; filter.module = null; }], filter.module && [`Module ${filter.module}`, () => { filter.module = null; }], filter.faculty && [titleCase(filter.faculty), () => { filter.faculty = null; }]].filter(Boolean);
    const option = (t, detail, count, on, onpick) => h("button.settingrow", { type: "button", class: on ? "picked" : "", "aria-pressed": on, onclick: onpick }, h("div.grow", null, h("div.strong", null, t), detail ? small(detail) : null), h("span.small", null, count));
    const idle = !query.trim() && !set && !filter.mineOnly && !filter.savedOnly;
    results.replaceChildren(...[
      h("div.chips", { style: { marginBottom: "8px" } },
        h("button.chip", { type: "button", class: set ? "on" : "", "aria-expanded": filtersOpen, onclick: () => { filtersOpen = !filtersOpen; draw(); } }, set ? `Filter · ${active.length}` : "Filter"),
        h("button.chip", { type: "button", class: filter.mineOnly ? "on" : "", onclick: () => { filter.mineOnly = !filter.mineOnly; draw(); } }, "Your faculty"),
        h("button.chip", { type: "button", class: filter.savedOnly ? "on" : "", onclick: () => { filter.savedOnly = !filter.savedOnly; draw(); } }, "Saved"),
        active.map(([t, off]) => h("button.chip.on", { type: "button", "aria-label": `Remove filter ${t}`, onclick: () => { off(); draw(); } }, `${t} ×`)),
        active.length ? h("button.chip", { type: "button", onclick: () => { filter.subject = filter.faculty = filter.module = null; filter.mineOnly = filter.savedOnly = false; draw(); } }, "Clear all") : null),
      filtersOpen ? h("div.card", { style: { marginBottom: "12px", maxHeight: "46vh", overflowY: "auto" } },
        !f.subjects.length && !f.faculties.length ? small("Nothing to filter by. Your course pages may still be coming, or the search left no files.") : null,
        f.subjects.length ? [label("Subject code"), f.subjects.map((o) => option(o.id, o.detail, o.count, o.id === filter.subject, () => { filter.subject = filter.subject === o.id ? null : o.id; filter.module = null; draw(); }))] : null,
        filter.subject && f.modules.length ? [label("Module"), h("div.chips", null, f.modules.map((o) => h("button.chip", { type: "button", class: o.id === filter.module ? "on" : "", onclick: () => { filter.module = filter.module === o.id ? null : o.id; draw(); } }, `Module ${o.id}`)))] : null,
        f.faculties.length ? [h("div.label", { style: { marginTop: "10px" } }, "Faculty"), f.faculties.map((o) => option(titleCase(o.id), "", o.count, o.id === filter.faculty, () => { filter.faculty = filter.faculty === o.id ? null : o.id; draw(); }))] : null,
        h("div", { style: { marginTop: "10px" } }, button(idle ? "Done" : hits.length ? `Show ${hits.length} result${hits.length === 1 ? "" : "s"}` : "Close", () => { filtersOpen = false; draw(); }, "solid"))) : null,
      idle ? [recent().length ? [h("div.row", { style: { margin: "8px 0" } }, h("div.label.grow", null, "Recent"), button("Clear", () => { localStore.set("recentSearches", "[]"); draw(); })), choiceRow(recent(), -1, (i) => { query = recent()[i]; field.value = query; draw(); })] : null,
        h("div.small", { style: { marginTop: "14px" } }, docs.length ? 'Try a topic, "experiment 9", a module, a faculty name or a course code. Typos are fine.' : "Fetching your course pages. Search works offline once they're here.")] : [
        hits.length ? h("div.small.strong", { style: { marginBottom: "6px" } }, `${hits.length} result${hits.length === 1 ? "" : "s"}${hits.length > 150 ? ", the best 150" : ""}`) : empty("Nothing matches. Check the filters, or try fewer words."),
        hits.slice(0, 150).map((r) => {
          const row = fileRow(highlighted(r.material.title, query), null, "material", { fileId: r.material.fileId }, r.key);
          const line = [highlighted(`${r.course.code} ${r.course.name}`, query)];
          if (r.material.module) line.push(" · ", highlighted(`Module ${r.material.module}${r.moduleTitle ? ` ${r.moduleTitle}` : ""}`, query));
          if (r.material.uploader) line.push(" · ", highlighted(titleCase(r.material.uploader), query));
          if (r.material.kind) line.push(` · ${r.material.kind}`);
          if (r.mine) line.push(" · your faculty");
          row.querySelector(".grow").append(small(line));
          return [row, h("hr.hair")];
        }),
      ],
    ].flat(Infinity).filter(Boolean));
    clearTimeout(remember);
    if (hits.length && query.trim()) {
      remember = setTimeout(() => {
        const q = query.trim();
        localStore.set("recentSearches", JSON.stringify([q, ...recent().filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 8)));
      }, 1500);
    }
  };
  const field = h("input.field", { placeholder: "Topic, module, faculty or course", "aria-label": "Search materials", autofocus: true, style: { marginBottom: "10px" }, oninput: (e) => { query = e.target.value; draw(); } });
  page.append(...pageTitle("Search materials", null, button("Back", () => history.back())).flat().filter(Boolean), sub, field, results);
  draw();
  api("section", { name: "courses", arg: null, force: null }).then(async (r) => {
    const courses = r.data.filter((c) => c.semesterId === s.semester.id);
    const list = courses.length ? courses : r.data;
    let read = 0;
    for (const c of list) {
      sub.textContent = `${docs.length} files across ${read} course${read === 1 ? "" : "s"} · fetching ${list.length - read} more`;
      try {
        const m = await api("section", { name: "materials", arg: JSON.stringify(c), force: null });
        for (const row of Materials.rows(c, m.data, s)) {
          docs.push({ row, fields: [{ text: row.material.title, weight: 1 }, { text: `Module ${row.material.module} ${row.moduleTitle}`, weight: 0.7 }, { text: `${c.code} ${c.name}`, weight: 0.6 }, { text: row.material.uploader, weight: 0.5 }] });
        }
      } catch { /* the rest still search */ }
      read++;
      if (page.isConnected) draw();
    }
    sub.textContent = `${docs.length} files across ${read} course${read === 1 ? "" : "s"}`;
  }, (e) => { sub.textContent = String(e); });
  return page;
}

function screenDownloads() {
  const page = h("div.page");
  let sure = false;
  const kinds = { mat: "Course materials", qp: "Question papers", da: "Your submissions", spot: "Spot-light", syl: "Syllabi", fee: "Fees letters", circ: "Circulars", cert: "Certificates", ev: "Certificates", evw: "Certificates", evb: "Certificates", evg: "Certificates", regulations: "Rules" };
  const draw = async () => {
    const f = await api("files_list");
    const all = [...f.cached, ...f.saved];
    const total = all.reduce((n, x) => n + x.size, 0);
    const row = (x) => h("div.row", { style: { padding: "6px 0" } },
      h("div.grow", null, h("div.strong", null, x.name), small(sizeText(x.size))),
      button("Open", () => showFile(x, x.name)),
      button("Delete", async () => { await api("file_delete", { path: x.path }).catch((e) => toast(e)); draw(); }));
    const groups = new Map();
    for (const x of f.cached) { const k = kinds[x.key.split("_")[0]] || "Other"; groups.set(k, [...(groups.get(k) || []), x]); }
    page.replaceChildren(...[
      pageTitle("Downloads", `${all.length} file${all.length === 1 ? "" : "s"} · ${sizeText(total)}. They open without a connection.`, button("Back", () => history.back())),
      !all.length ? empty("Nothing here yet. Materials, question papers, letters and syllabi you open are kept here.") : null,
      f.saved.length ? [h("h2", null, "Saved in Downloads/daVIT"), h("div.card.assignments", null, f.saved.map(row))] : null,
      [...groups].sort().map(([k, list]) => [h("h2", null, k), h("div.card.assignments", null, list.sort((a, b) => a.name.localeCompare(b.name)).map(row))]),
      all.length ? h("div", { style: { marginTop: "18px" } }, button(sure ? `Press again to delete all ${all.length}` : "Delete all downloads", async () => {
        if (sure) for (const x of all) await api("file_delete", { path: x.path }).catch(() => {});
        sure = !sure;
        draw();
      }, sure ? "solid" : "")) : null,
    ].flat(Infinity).filter(Boolean));
  };
  draw().catch((e) => page.replaceChildren(h("div.err", null, String(e))));
  return page;
}

// ---- records --------------------------------------------------------------------------------------

/** Each row of a table as a card: the named column as its title, the rest as label and value. */
function tableCards(t, titleWords, kind, skip = []) {
  const title = Math.max(0, t.headers.findIndex((x) => titleWords.some((w) => x.toLowerCase().includes(w.toLowerCase()))));
  return h("div.grid2", null, t.rows.map((row) => h(`div.card.${kind}`, null, h("div.strong", null, row[title] ?? ""),
    t.headers.map((head, c) => {
      const v = row[c] ?? "";
      if (c === title || !v || v === "-" || skip.some((w) => head.toLowerCase().includes(w.toLowerCase()))) return null;
      return h("div.row", { style: { alignItems: "flex-start", marginTop: "4px" } }, h("div.small", { style: { width: "118px", flex: "none" } }, head), h("div.small.strong.grow", null, v));
    }))));
}

function screenActivities(s) {
  const page = h("div.page");
  page.append(...pageTitle("Activities and QCM", "Your co and extra-curricular record, and what quality circle meetings raised. Read only.", button("Back", () => history.back())).flat().filter(Boolean));
  const picker = h("div");
  const body = h("div", null, empty("Reading VTOP's records"));
  page.append(picker, body);
  const load = (id) => {
    body.replaceChildren(empty("Reading this semester"));
    sectionInto(body, "activities", (d) => [
      h("h2", null, "Co and extra-curricular"),
      d.record.rows.length ? tableCards(d.record, ["Activity", "Event", "Title", "Name"], "events", ["S.No", "Sl"]) : small("Nothing recorded this semester."),
      h("h2", null, "Quality circle meetings"),
      d.qcm.rows.length ? tableCards(d.qcm, ["Course Title", "Title"], "people", ["Sem Code", "Class Nbr"]) : small("No meetings recorded this semester."),
    ], id);
  };
  api("section", { name: "activitySemesters", arg: null, force: null }).then((r) => {
    const list = r.data;
    const first = list.find((x) => x.id === s?.semester.id) || list[0];
    if (!first) { body.replaceChildren(empty("VTOP lists no semesters here.")); return; }
    picker.replaceChildren(h("select.field", { "aria-label": "Semester", style: { maxWidth: "340px" }, onchange: (e) => load(e.target.value) }, list.map((x) => h("option", { value: x.id, selected: x.id === first.id }, x.name))));
    load(first.id);
  }, (e) => body.replaceChildren(errorNote(e)));
  return page;
}

function screenAllocation() {
  const page = h("div.page");
  const top = h("div.sub", null, "Classes allotted for next semester. Read only: registering happens on VTOP.");
  const body = h("div", null, empty("Reading course allocation"));
  page.append(...pageTitle("Course allocation", null, button("Back", () => history.back())).flat().filter(Boolean), top, body);
  api("section", { name: "allocation", arg: null, force: null }).then((r) => {
    const a = r.data;
    if (a.semester) top.textContent = `Classes allotted for ${a.semester}. Read only: registering happens on VTOP.`;
    if (!a.categories.length) { body.replaceChildren(empty("VTOP shows no curriculum categories here.")); return; }
    const courses = h("div");
    const classes = h("div");
    const cat = h("select.field", { "aria-label": "Category", style: { marginBottom: "10px" }, onchange: (e) => {
      classes.replaceChildren();
      if (!e.target.value) return;
      sectionInto(courses, "allocationCourses", (list) => (list.length
        ? h("select.field", { "aria-label": "Course", style: { marginBottom: "10px" }, onchange: (x) => x.target.value && sectionInto(classes, "allocationClasses", (t) => [h("h2", null, "Classes"), t.rows.length ? tableCards(t, ["Slot"], "classes") : small("No classes allotted for this course yet.")], x.target.value) }, h("option", { value: "" }, "Course"), list.map(([v, t]) => h("option", { value: v }, t)))
        : small("No courses in this category for you.")), e.target.value);
    } }, h("option", { value: "" }, "Category"), a.categories.map(([id, t]) => h("option", { value: id }, t)));
    body.replaceChildren(cat, courses, classes);
  }, (e) => body.replaceChildren(errorNote(e)));
  return page;
}

const POINTS = { S: 10, A: 9, B: 8, C: 7, D: 6, E: 5, F: 0, N: 0 };

function sessionGpas(g) {
  const groups = new Map();
  for (const e of g?.entries || []) groups.set(e.examMonth, [...(groups.get(e.examMonth) || []), e]);
  const out = [];
  for (const [session, list] of groups) {
    const graded = list.filter((e) => e.credits > 0 && e.grade && POINTS[e.grade.toUpperCase()] != null);
    const credits = graded.reduce((n, e) => n + e.credits, 0);
    if (credits > 0 && session) out.push({ session, credits, gpa: graded.reduce((n, e) => n + e.credits * POINTS[e.grade.toUpperCase()], 0) / credits });
  }
  return out;
}

function screenSemesterGrades(s) {
  const sessions = sessionGpas(s.grades);
  const result = h("div", { style: { marginTop: "12px" } });
  let picked = null;
  const chips = h("div");
  const drawChips = () => chips.replaceChildren(h("div.chips", null, s.semesters.map((x) => h("button.chip", { type: "button", class: picked === x.id ? "on" : "", onclick: () => {
    picked = x.id;
    drawChips();
    sectionInto(result, "semesterGrades", (g) => (!g.courses.length && g.gpa == null ? small("VTOP has no results for that semester.") : h("div.card.exams", null,
      g.gpa != null ? h("div.strong", { style: { fontSize: "18px", marginBottom: "8px" } }, `GPA ${(Math.floor(g.gpa * 100) / 100).toFixed(2)}`) : null,
      g.courses.map((e) => h("div.row", { style: { padding: "4px 0" } }, h("div.grow", null, h("div.strong", null, Names.all()[e.courseCode] || e.courseTitle || e.courseCode), small(`${e.courseCode} · ${num(e.credits)} credits`)), h("span.badge", { class: e.grade ? "on" : "" }, e.grade ?? "–"))))), x.id);
  } }, x.name.replace(" - VLR", "")))));
  drawChips();
  return h("div.page", null, pageTitle("Semester GPA", "From your grade history; pick a semester for VTOP's own results page.", button("Back", () => history.back())),
    sessions.length ? h("div.grid2", null, [...sessions].reverse().map((x) => h("div.card.exams", null, h("div.row", null, h("div.grow", null, h("div.strong", { style: { fontSize: "17px" } }, x.session), small(`${num(x.credits)} graded credits`)), h("div.big", null, (Math.floor(x.gpa * 100) / 100).toFixed(2))))))
      : empty(s.grades ? "No graded semesters yet. They appear after your first results." : "Grades arrive with the next sync."),
    s.semesters.length ? [h("h2", null, "VTOP's results page"), chips, result] : null);
}

/** Anyone VTOP lists, by name. */
function screenPeople(s) {
  const results = h("div");
  let timer = null;
  const person = (p) => {
    const get = (w) => p.fields.find(([k]) => k.toLowerCase().includes(w))?.[1] || "";
    return h("div.card.people", null, label(p.role), h("div.strong", null, titleCase(get("name"))), small(get("designation")),
      h("div.row.wrap", { style: { marginTop: "5px" } }, get("cabin") ? button(`Cabin ${get("cabin")}`, () => go("map", { find: get("cabin") })) : null, get("mail") ? button(get("mail"), () => api("open_link", { url: `mailto:${get("mail")}` })) : null));
  };
  const hit = (x) => {
    const details = h("div");
    let open = false;
    return h("div.card.people", null,
      h("div.row", null, h("div.grow", null, h("div.strong", null, titleCase(x.name)), small([x.designation, x.school].filter(Boolean).join(" · "))),
        button("Cabin", async (e) => {
          open = !open;
          e.target.textContent = open ? "Less" : "Cabin";
          if (!open) { details.replaceChildren(); return; }
          details.replaceChildren(small("Opening their card on VTOP"));
          try {
            const r = await api("section", { name: "faculty", arg: x.empId, force: null });
            const c = r.data;
            details.replaceChildren(...(!c ? [small("VTOP didn't open this card.")] : [c.department ? small(c.department) : null, !c.cabin && !c.email ? small("No cabin or email on VTOP.") : null,
              h("div.row.wrap", { style: { marginTop: "6px" } }, c.cabin ? button(`Cabin ${c.cabin}`, () => go("map", { find: c.cabin })) : null, c.email ? button(c.email, () => api("open_link", { url: `mailto:${c.email}` })) : null)].filter(Boolean)));
          } catch (err) {
            details.replaceChildren(h("div.small.err", null, String(err)));
          }
        })),
      h("div", { style: { marginTop: "5px" } }, ratingLink(x.empId, x.name)), details);
  };
  const search = (q) => {
    clearTimeout(timer);
    const clean = q.replace(/[^\p{L} ]/gu, "").trim().replace(/ +/g, " ").toLowerCase();
    if (clean.replace(/ /g, "").length < 3) { results.replaceChildren(); return; }
    timer = setTimeout(() => {
      sectionInto(results, "facultySearch", (list) => (list.length ? [h("div.grid2", null, list.slice(0, 30).map(hit)), list.length > 30 ? small(`${list.length - 30} more; type more of the name.`) : null] : small(`VTOP has nobody called "${clean}".`)), clean);
    }, 650);
  };
  const who = h("div");
  sectionInto(who, "people", (p) => h("div.grid2", null, p.proctor ? person(p.proctor) : null, (p.hodDean || []).map(person)));
  return h("div.page", null, pageTitle("Proctor, HOD, dean", "Who to ask, and anyone else on VTOP.", button("Back", () => history.back())),
    who, h("h2", null, "Anyone on VTOP"), h("input.field", { placeholder: "Three letters of a name", "aria-label": "Search VTOP's faculty", style: { marginBottom: "12px" }, oninput: (e) => search(e.target.value) }), results);
}

// ---- the hub --------------------------------------------------------------------------------------

/** The same screens as the phone's More, in the same groups. */
function hubGroups(s) {
  return [
    ["Plan", [["examboard", "Exam board", "Every paper, room and seat", "exams"], ["ffcs", "FFCS", "Next semester's timetable, smart or by hand", "classes"], ["ffcsresult", "FFCS result", "What you registered for", "classes"], ["friends", "Free with friends", "Swap weeks by QR code", "people"], ["corrections", "Attendance corrections", "Absent by mistake? Keep track", "attendance"], ["leave", "Plan a leave", "What days away cost", "attendance"]]],
    ["Every day", [["mess", "Mess menu", "Today's meals, with reminders", "events"]]],
    ["News and people", [["messages", "Messages", "Class and proctor", "people"], ["circulars", "Circulars", "Every official PDF, searchable", "people"], ["windows", "What's open", "Registrations open right now", "people"], ["spotlight", "Spot-light", "VTOP's notices and files", "people"], ["events", "Club events", "Register, certificates", "events"], ["clubs", "All clubs", "Every club and yours", "events"], ["sports", "Sports and gym", "Fees and seats left", "events"], ["projects", "Faculty projects", "Research open to students", "people"], ["reviews", "Faculty reviews", "Ratings and reviews, offline", "people"], ["people", "Proctor, HOD, dean", "Who to ask", "people"]]],
    ["Requests", [s?.profile?.hostel || !s ? ["hostel", "Hostel", "Leave, night slips, mess", "vtop"] : null, ["bonafide", "Bonafide", "Apply for a certificate", "vtop"], ["feedback", "Course feedback", "To your teachers, any time", "vtop"], ["paperseeing", "Paper seeing", "Or re-evaluation, after results", "vtop"]].filter(Boolean)],
    ["Records", [["grades", "Semester GPA", "Each term's results", "exams"], ["curriculum", "Curriculum", "Credits and syllabus PDFs", "classes"], ["allocation", "Course allocation", "Next semester's classes", "classes"], ["activities", "Activities and QCM", "Co-curricular, class meetings", "events"], ["money", "Receipts and dues", "Fees letters, library", "vtop"], ["gate", "Gate log", "Biometric in and out", "vtop"], ["certificates", "Certificates", "graVITas and University Day", "events"], ["rules", "Rules and FAQ", "Regulations, malpractice, answers", "vtop"], ["signins", "Sign-in activity", "Every login, with its IP", "vtop"]]],
    ["Files", [["search", "Search materials", "Across every course", "assignments"], ["scanner", "Scan", "Pictures of pages into a PDF", "assignments"], ["downloads", "Downloads", "Saved on this computer", "assignments"]]],
  ];
}

/** Editorial Home's index, numbered like a paper's. */
const HUB = [
  ["Study", [["Marks", "academics", { tab: 0 }], ["Assignments", "academics", { tab: 1 }], ["Exam board", "examboard"], ["Grades & CGPA", "academics", { tab: 3 }], ["Materials", "academics", { tab: 4 }], ["Faculty", "academics", { tab: 5 }]]],
  ["Time", [["Week", "week"], ["Curriculum", "curriculum"], ["FFCS", "ffcs"], ["FFCS result", "ffcsresult"], ["Free with friends", "friends"], ["Allocation", "allocation"]]],
  ["Campus", [["Hostel", "hostel"], ["Mess menu", "mess"], ["Map", "map"], ["Club events", "events"], ["Messages", "messages"], ["Spot-light", "spotlight"], ["Circulars", "circulars"], ["What's open", "windows"], ["Faculty projects", "projects"], ["All clubs", "clubs"], ["Sports and gym", "sports"], ["Faculty reviews", "reviews"], ["Proctor & HOD", "people"]]],
  ["Requests & records", [["Bonafide", "bonafide"], ["Course feedback", "feedback"], ["Paper seeing", "paperseeing"], ["Receipts", "money"], ["Gate log", "gate"], ["Certificates", "certificates"], ["Rules and FAQ", "rules"], ["Sign-in activity", "signins"], ["Activities", "activities"], ["Corrections", "corrections"]]],
  ["Yours", [["Course names", "coursenames"], ["Downloads", "downloads"], ["Display card", "displaycard"], ["Settings", "settings"]]],
];

function screenMore(s) {
  return h("div.page", null, pageTitle("More from VTOP", null),
    hubGroups(s).map(([title, items]) => [h("h2", null, title), h("div.grid3", null, items.map(([r, t, note, kind]) => h(`button.card.click.${kind}`, { type: "button", onclick: () => go(r) }, h("div.strong", null, t), small(note))))]));
}

// ---- settings -------------------------------------------------------------------------------------

const SETTINGS_GROUPS = [["look", "Look"], ["yours", "Make it yours"], ["alerts", "Notifications"], ["sync", "Sync and semester"], ["computer", "On this computer"], ["about", "About and help"]];

const SETTINGS_INDEX = [
  ["Style: Classic or Editorial", "ui theme look layout", "look"], ["Light or dark", "dark mode night theme", "look"], ["Colours", "ink pair palette colour color", "look"], ["Your own colours", "custom palette color", "look"],
  ["Display card", "banner photo background top bar picture", "yours"], ["Your mascot", "avatar face profile picture", "yours"], ["Course names", "alias rename nickname subjects", "yours"],
  ["Notifications", "alerts", "alerts"], ["Attendance warnings", "75 percent line alert", "alerts"], ["Reminders before class", "before class reminder", "alerts"], ["Meal reminders", "mess menu food breakfast lunch snacks dinner", "alerts"], ["New marks, deadlines, exams, registration", "alerts marks deadline exam registration remarks", "alerts"],
  ["Sync now", "refresh update data", "sync"], ["Sync in the background", "background refresh tray", "sync"], ["Start with this computer", "login startup boot autostart", "sync"], ["Semester", "term", "sync"], ["Calendar class group", "freshers group saturday", "sync"],
  ["App lock", "pin lock security", "computer"], ["Classes as a file", "ics export calendar google outlook", "computer"], ["Downloads", "files offline saved", "computer"], ["Next class on the tray icon", "widget tray", "computer"],
  ["Updates", "version new release install", "about"], ["Update by itself", "auto update", "about"], ["Credits", "thanks licence", "about"], ["Save VTOP pages", "debug parser", "about"],
];

function screenSettings(s) {
  const page = h("div.page");
  let query = "";
  const list = h("div");
  const open = (g) => (g === "alerts" ? go("notifications") : go("settingsPage", { group: g }));
  const summary = (g) => {
    const p = AlertPrefs.get();
    if (g === "look") return [Look.isEditorial() ? "Editorial" : "Classic", { system: "Follows the system", light: "Light", dark: "Dark" }[Look.theme()], Look.isEditorial() ? null : Look.custom(Look.palette()) ? "your own colours" : PAIRS.day[Look.palette().base].name].filter(Boolean).join(" · ");
    if (g === "yours") { const n = Object.keys(Names.all()).length; return `Display card, mascot, course names${n ? ` · ${n} renamed` : ""}`; }
    if (g === "alerts") return p.on ? alertSummary(p) : "Off";
    if (g === "sync") return [Background.on() ? "Every 6 hours" : "Background sync off", s?.semester.name.split(" - ")[0]].filter(Boolean).join(" · ");
    if (g === "computer") return [localStore.get("lockHash", "") ? "App lock on" : null, "downloads, calendar file"].filter(Boolean).join(" · ");
    return `daVIT ${App.version}`;
  };
  const draw = () => {
    const q = query.trim().toLowerCase();
    if (q) {
      const hits = SETTINGS_INDEX.filter(([n, w]) => n.toLowerCase().includes(q) || w.includes(q));
      list.replaceChildren(...(hits.length ? hits.map(([n, , g]) => settingRow(n, SETTINGS_GROUPS.find((x) => x[0] === g)[1], "Open", () => open(g))) : [small("Nothing called that. Try another word.")]));
    } else {
      list.replaceChildren(h("div.grid2", null, SETTINGS_GROUPS.map(([g, t]) => h("button.card.click", { type: "button", onclick: () => open(g) }, h("div.strong", { style: { fontSize: "16px" } }, t), small(summary(g))))));
    }
  };
  draw();
  const hostel = s?.profile?.hostel;
  const you = h("div.card", { style: { marginBottom: "12px" } }, h("div.row", null,
    s?.profile?.photo ? h("img", { src: s.profile.photo, alt: "", style: { width: "48px", height: "48px", objectFit: "cover", borderRadius: "8px" } }) : mascotAvatar(Mascot.mine(), 48),
    h("div.grow", null, h("div.strong", { style: { fontSize: "17px" } }, s?.profile?.name || "Signed in"), small([s?.semester.name.split(" - ")[0], hostel?.room && `Room ${hostel.room}`].filter(Boolean).join(" · ")))));
  const confirmBox = h("div");
  page.append(...pageTitle("Settings", null).flat().filter(Boolean), you,
    h("input.field", { placeholder: "Find a setting", "aria-label": "Find a setting", style: { marginBottom: "12px" }, oninput: (e) => { query = e.target.value; draw(); } }), list,
    h("div", { style: { marginTop: "16px" } }, button("Sign out", () => {
      confirmBox.replaceChildren(small("This erases your password and everything daVIT stored here."), h("div.row", { style: { marginTop: "6px" } }, button("Yes, sign out", async () => { await api("sign_out"); boot(); }, "solid"), button("Keep me signed in", () => confirmBox.replaceChildren())));
    }), confirmBox));
  return page;
}

function alertSummary(p) {
  return [p.attendance ? `Below ${p.warnBelow}%` : "No attendance warnings", p.skipsLeft >= 0 ? (p.skipsLeft === 0 ? "No skips left" : `${p.skipsLeft} skip${p.skipsLeft === 1 ? "" : "s"} left`) : null, p.beforeClass > 0 ? `${p.beforeClass} min before class` : null].filter(Boolean).join(" · ");
}

function screenSettingsPage(s, args) {
  const page = h("div.page");
  const redraw = () => go("settingsPage", args, true);
  const title = SETTINGS_GROUPS.find((x) => x[0] === args.group)?.[1] || "Settings";
  const body = { look: lookSettings, yours: yoursSettings, sync: syncSettings, computer: computerSettings, about: aboutSettings }[args.group](s, redraw);
  page.append(...pageTitle(title, null, button("Back", () => history.back())).flat().filter(Boolean), h("div.stack", { style: { maxWidth: "720px" } }, body));
  return page;
}

function lookSettings(_, redraw) {
  const editorial = Look.isEditorial();
  return [
    settingsCard("Style", h("div", { style: { marginTop: "8px" } }, stylePicker(redraw))),
    settingsCard("Light or dark", h("div", { style: { marginTop: "8px" } }, themeRow(redraw))),
    editorial ? null : settingsCard("Colours", h("div", { style: { margin: "8px 0" } }, inkSwatches((i) => { const p = Look.palette(); Look.setPalette({ base: i, kinds: {}, tinted: p.tinted }); redraw(); })),
      small(Look.custom(Look.palette()) ? "Your own colours." : PAIRS.day[Look.palette().base].name),
      settingRow("Your own colours", "Any colour for every ink, and for each kind of card.", "Choose", () => go("colours"))),
  ];
}

function yoursSettings() {
  return [
    settingsCard("On Home",
      settingRow("Display card", Look.isEditorial() ? "The picture at the top of Home." : "The top bar: a print or your photo, two stats, your name.", "Change", () => go("displaycard")),
      settingRow("Course names", "Call a course whatever you call it.", "Rename", () => go("coursenames"))),
    settingsCard("Your mascot", h("div", { style: { marginTop: "8px" } }, mascotEditor(() => { const side = document.getElementById("sidemascot"); if (side) side.replaceChildren(mascotAvatar(Mascot.mine(), 34)); }))),
  ];
}

function syncSettings(s, redraw) {
  const groups = s?.calendar?.groups || [];
  const manual = Choices.get().group;
  const timings = App.timings || [];
  let open = false;
  const stages = h("div");
  return [
    settingsCard("Sync",
      h("div", { style: { margin: "8px 0" } }, h("button.btn.solid.wide", { type: "button", onclick: async (e) => { e.target.disabled = true; e.target.textContent = "Syncing"; await sync(true); redraw(); } }, "Sync now")),
      timings.length ? [settingRow("Last sync", `${(timings.reduce((n, t) => n + t[1], 0) / 1000).toFixed(1)} s over ${timings.length} stages`, "Stages", () => { open = !open; stages.replaceChildren(...(open ? timings.map(([t, ms]) => h("div.row", { style: { padding: "1px 0" } }, h("div.small.grow", null, t), h("div.small.strong", null, `${ms} ms`))) : [])); }), stages] : null,
      toggleRow("Sync in the background", `About every 6 hours while the window is closed, never between 1 and 6 am. Closing the window leaves daVIT in the tray.${localStore.get("lastBackground", "") ? ` Last: ${localStore.get("lastBackground", "")}.` : ""}`, Background.on(), () => { Background.set(!Background.on()); redraw(); }),
      autostartRow(redraw)),
    s && (s.semesters.length > 1 || groups.length) ? settingsCard("Semester",
      s.semesters.slice(0, 4).map((x) => h("button.settingrow", { type: "button", role: "radio", "aria-checked": x.id === s.semester.id, disabled: x.id === s.semester.id, onclick: async (e) => { e.currentTarget.disabled = true; Choices.setSemester(x.id); toast("Reading that semester"); await sync(true); redraw(); } },
        h("span.radio", { class: x.id === s.semester.id ? "on" : "" }), h("div.grow", null, x.name.replace(" - VLR", "")))),
      groups.length ? [h("hr.hair"), h("div.strong", null, "Calendar class group"),
        small(`${manual ? `You chose ${groups.find((g) => g.id === manual)?.name || manual}.` : s.calendar?.group ? `Detected from your attendance: ${s.calendar.group}.` : "Not detected yet; using General."} Freshers' Saturdays and some exam days differ by group.`),
        h("div", { style: { marginTop: "8px" } }, choiceRow(["Auto", ...groups.map((g) => g.id)], manual ? groups.findIndex((g) => g.id === manual) + 1 : 0, async (i) => { Choices.setGroup(i === 0 ? null : groups[i - 1].id); toast("Reading the calendar"); await sync(true); redraw(); }))] : null) : null,
  ];
}

function computerSettings(s, redraw) {
  const locked = !!localStore.get("lockHash", "");
  const pinBox = h("div");
  const askPin = () => {
    const a = h("input.field", { type: "password", inputmode: "numeric", maxlength: 12, placeholder: "A PIN of 4 or more digits", "aria-label": "New PIN", style: { maxWidth: "260px" } });
    const note = h("div.small.err");
    pinBox.replaceChildren(h("div.row.wrap", { style: { marginTop: "8px" } }, a, button("Set PIN", async () => {
      if (!/^\d{4,12}$/.test(a.value)) { note.textContent = "4 to 12 digits."; return; }
      await Lock.set(a.value);
      redraw();
    }, "solid")), note);
    a.focus();
  };
  return [
    settingsCard("Security",
      toggleRow("App lock", locked ? "On: daVIT asks for your PIN when it opens." : "Ask for a PIN when daVIT opens, kept only on this computer.", locked, () => { if (locked) { Lock.clear(); redraw(); } else askPin(); }), pinBox),
    settingsCard("On your desktop",
      settingRow("Next class on the tray icon", "Hover over the tray icon for the class now or next and its attendance. It's the desktop's home screen widget.", null),
      settingRow("Sits above other windows?", "Your system's own snap and always-on-top tools work on daVIT's window.", null)),
    settingsCard("Calendar",
      s?.calendar ? settingRow("Classes as a file", "An .ics file of the semester, holidays and swapped days included, for Google Calendar, Outlook or Apple Calendar.", "Save", async () => {
        const events = Ics.classes(s);
        if (!events.length) { toast("No classes left this semester."); return; }
        const f = await api("save_made", { name: "daVIT classes.ics", text: Ics.file(events), data: null });
        toast(`Saved ${events.length} classes in Downloads/daVIT`);
        await api("open_file", { path: f.path }).catch(() => {});
      }) : settingRow("Classes as a file", "Needs the academic calendar, which comes with the next sync.", null),
      small("Phones add classes to the calendar directly. A computer has no shared calendar for daVIT to write to, so this is a file your calendar app imports.")),
    settingsCard("Stored here",
      settingRow("Downloads", "Files you've opened and saved. They read offline.", "Open", () => go("downloads")),
      settingRow("Campus map", "Jeman's map of the campus, built into this app.", null)),
  ];
}

function aboutSettings(_, redraw) {
  const link = (url) => () => api("open_link", { url });
  const status = h("div");
  const row = (message, action, act) => status.replaceChildren(settingRow(`daVIT ${App.version}`, message, action, act));
  const check = async () => {
    row("Checking GitHub", null);
    try {
      const u = await window.__TAURI__.updater?.check?.();
      if (u) row(`${u.version} is out.`, "Install", () => Updates.install(u, row));
      else row("You're on the newest version.", "Check again", check);
    } catch {
      api("latest_version").then((v) => (v && v !== App.version ? row(`${v} is out.`, "Get it", link("https://github.com/medriid/davit/releases/latest")) : row("You're on the newest version.", "Check again", check))).catch(() => row("Couldn't reach GitHub.", "Try again", check));
    }
  };
  row("Check GitHub for a newer release.", "Check", check);
  const pages = localStore.get("savePages", "0") === "1";
  return [
    settingsCard("Updates", status,
      toggleRow("Update by itself", "Installs new versions when they're out, checked once a day. They're signed, so only daVIT's own updates install. When a release can't update itself, you're told it's out.", localStore.get("autoUpdate", "1") === "1", () => { localStore.set("autoUpdate", localStore.get("autoUpdate", "1") === "1" ? "0" : "1"); redraw(); })),
    settingsCard("Privacy", small("Your password stays in this computer's keychain and your data is encrypted on this computer. There's no server, account or analytics. Money, passwords and course registration stay on VTOP; anything else daVIT sends goes only after you've seen it and confirmed.")),
    settingsCard("Credits",
      settingRow("Campus map by Jeman", "Shared under the MIT licence.", "Jeman", link("https://www.linkedin.com/in/jemankalita/")),
      settingRow("Faculty reviews by No8AM", "Student ratings and reviews, as of 1 November 2026.", "No8AM", link("https://no8am.site/")),
      settingRow("Map data", "© OpenStreetMap contributors, under the Open Database Licence.", "OSM", link("https://www.openstreetmap.org/copyright")),
      small("Fonts: Bagel Fat One and IBM Plex Mono, under the SIL Open Font Licence. Apache-2.0. Not affiliated with VIT.")),
    settingsCard("Debugging",
      toggleRow("Save VTOP pages", "Keeps the last raw page of each kind in Downloads/daVIT/vtop-pages so a broken parser can be fixed. Your username and tokens are scrubbed.", pages, () => { localStore.set("savePages", pages ? "0" : "1"); api("save_pages", { on: !pages }).catch(() => {}); redraw(); })),
  ];
}

const Updates = {
  async install(u, row) {
    row(`Downloading ${u.version}`, null);
    try {
      await u.downloadAndInstall();
      await window.__TAURI__.process.relaunch();
    } catch (e) {
      row(`Couldn't install it: ${e}`, "Get it", () => api("open_link", { url: "https://github.com/medriid/davit/releases/latest" }));
    }
  },
  /** Quietly, once a day, when you've left it on. */
  async daily() {
    if (localStore.get("autoUpdate", "1") !== "1") return;
    const last = Number(localStore.get("updateChecked", "0"));
    if (Date.now() - last < 864e5) return;
    localStore.set("updateChecked", String(Date.now()));
    try {
      const u = await window.__TAURI__.updater?.check?.();
      if (u) await u.downloadAndInstall();
      if (u) api("notify", { title: `daVIT ${u.version} is ready`, body: "It starts the next time you open daVIT." }).catch(() => {});
    } catch {
      // A release without signed updates: at least say it's out, once.
      const v = await api("latest_version").catch(() => null);
      if (v && v !== App.version) Alerts.post([{ key: `update:${v}`, title: `daVIT ${v} is out`, text: "Get it from Settings, under About and help." }]);
    }
  },
};

const Lock = {
  async hash(pin, salt) {
    const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${salt}:${pin}`));
    return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
  },
  async set(pin) {
    const salt = crypto.randomUUID();
    localStore.set("lockSalt", salt);
    localStore.set("lockHash", await Lock.hash(pin, salt));
  },
  clear() {
    localStorage.removeItem("lockHash");
    localStorage.removeItem("lockSalt");
  },
  /** Holds the window until the PIN is right. */
  ask() {
    return new Promise((resolve) => {
      const note = h("div.small.err", { role: "alert" });
      const pin = h("input.field", { type: "password", inputmode: "numeric", maxlength: 12, "aria-label": "PIN", autofocus: true });
      const form = h("form", { onsubmit: async (e) => {
        e.preventDefault();
        if ((await Lock.hash(pin.value, localStore.get("lockSalt", ""))) === localStore.get("lockHash", "")) resolve();
        else { note.textContent = "That's not it."; pin.value = ""; pin.focus(); }
      } }, h("img", { src: "logo.png", alt: "" }), h("h1", { style: { marginBottom: "12px", textTransform: "none" } }, "daVIT"),
      h("div.stack", null, h("label", null, label("Your PIN"), pin), note, h("button.btn.solid.wide", { type: "submit" }, "Unlock"),
        small("Forgot it? Sign-out isn't reachable from here: remove daVIT's data folder, and you'll sign in again.")));
      document.getElementById("app").replaceChildren(h("div.signin", null, form));
      pin.focus();
    });
  },
};

function screenNotifications(s) {
  const page = h("div.page");
  let sent = false;
  const draw = () => {
    const p = AlertPrefs.get();
    const set = (next) => { AlertPrefs.set(next); draw(); };
    const flip = (k) => () => set({ ...p, [k]: !p[k] });
    const exempt = s && Rules.exempt(s);
    const rows = s?.attendance || [];
    const areas = [...new Set((s?.feeds.projects || []).map((x) => x.area).filter(Boolean))].sort();
    page.replaceChildren(...[
      pageTitle("Notifications", p.on ? alertSummary(p) : "Off", button("Back", () => history.back())),
      h("div.stack", { style: { maxWidth: "720px" } },
        settingsCard(null, toggleRow("Notifications", "Checked after each sync; reminders at the minute you set, while daVIT is running.", p.on, flip("on"))),
        p.on ? [
          settingsCard("Attendance",
            exempt ? small("Your CGPA is 9 or more, so attendance doesn't hold you back. These stay off until it does.") : null,
            toggleRow("Attendance warnings", "When a course slips, once per change.", p.attendance, flip("attendance")),
            p.attendance ? [h("div.strong", { style: { marginTop: "10px" } }, "Warn me below"), small("75% is VTOP's line; a higher one warns while there's still room."),
              h("div", { style: { marginTop: "6px" } }, choiceRow(["75%", "80%", "85%"], [75, 80, 85].indexOf(p.warnBelow), (i) => set({ ...p, warnBelow: [75, 80, 85][i] }))),
              h("div.strong", { style: { marginTop: "10px" } }, "When skips run low"), small("Skips are classes you can miss in a row and stay at 75%."),
              h("div", { style: { marginTop: "6px" } }, choiceRow(["Never", "None left", "1 left", "2 left", "3 left"], [-1, 0, 1, 2, 3].indexOf(p.skipsLeft), (i) => set({ ...p, skipsLeft: [-1, 0, 1, 2, 3][i] })))] : null),
          p.attendance && rows.length ? settingsCard("Courses", small("Mute a course you've made peace with; the rest still warn."), rows.map((r) => {
            const key = courseKey(r.courseCode, r.classType);
            const on = !p.muted.includes(key);
            return toggleRow(r.courseName, `${r.classType} · ${Rules.percent(r)}% · ${r.attended}/${r.total}`, on, () => set({ ...p, muted: on ? [...p.muted, key] : p.muted.filter((x) => x !== key) }));
          })) : null,
          settingsCard("Before class", small("A reminder before class, with the room and what missing it would cost."),
            h("div", { style: { marginTop: "6px" } }, choiceRow(["Off", "5 min", "10 min", "15 min", "20 min"], [0, 5, 10, 15, 20].indexOf(p.beforeClass), (i) => set({ ...p, beforeClass: [0, 5, 10, 15, 20][i] }))),
            p.beforeClass > 0 ? [h("div.strong", { style: { marginTop: "10px" } }, "For"), h("div", { style: { marginTop: "6px" } }, choiceRow(["Courses near the line", "Every class"], p.beforeClassNearLine ? 0 : 1, (i) => set({ ...p, beforeClassNearLine: i === 0 }))),
              small(p.beforeClassNearLine ? `Near the line: below ${p.warnBelow}%, or two skips or fewer from 75%.` : "Every class, with its room.")] : null),
          settingsCard("Meals", settingRow("Meal reminders", p.meals.length ? `${p.meals.length} of 4 meals, set from the bell on the mess menu.` : "Off. Set them from the bell on the mess menu.", "Mess menu", () => go("mess"))),
          settingsCard("Also tell me about",
            toggleRow("VTOP remarks", "A course marked Debarred or Revoked, or a change in which exams it covers.", p.remarks, flip("remarks")),
            toggleRow("New marks", "A quiz, CAT or assignment score as it appears.", p.marks, flip("marks")),
            toggleRow("Deadlines", "Assignments due today or tomorrow, and your own reminders.", p.deadlines, flip("deadlines")),
            toggleRow("Exams", "The day before, with the time, venue and seat.", p.exams, flip("exams")),
            toggleRow("Registration", "A new registration window, and 30 minutes before it opens.", p.notices, flip("notices"))),
          areas.length ? settingsCard("Faculty projects", small("New research projects in the areas you follow."), h("div.chips", { style: { marginTop: "6px" } }, areas.map((a) => {
            const on = p.projectAreas.includes(a);
            return h("button.chip", { type: "button", class: on ? "on" : "", "aria-pressed": on, onclick: () => set({ ...p, projectAreas: on ? p.projectAreas.filter((x) => x !== a) : [...p.projectAreas, a] }) }, a);
          }))) : null,
          settingsCard("Check", settingRow("Send a test", sent ? "Sent. If nothing showed up, check your system's notification settings for daVIT." : "Posts one notification now, so you see how they look.", "Send", () => { api("notify", { title: "daVIT notifications work", body: "This is how a reminder looks." }).catch((e) => toast(e)); sent = true; draw(); })),
        ] : null),
    ].flat(Infinity).filter(Boolean));
  };
  draw();
  return page;
}

function screenCourseNames(s) {
  const seen = new Map();
  for (const t of s?.timetable || []) if (t.courseCode && !seen.has(t.courseCode)) seen.set(t.courseCode, App.snapshot?.timetable.find((x) => x.courseCode === t.courseCode)?.courseName || t.courseName);
  const names = Names.all();
  const set = (code, v) => {
    const all = Names.all();
    if (v.trim()) all[code] = v.trim().slice(0, 40); else delete all[code];
    localStore.set("courseNames", JSON.stringify(all));
  };
  return h("div.page", null, pageTitle("Course names", "Call a course whatever you call it. It shows everywhere in this app, and nowhere else.", button("Back", () => history.back())),
    seen.size ? h("div.nameslist", null, [...seen].map(([code, name], i) => h("div.namerow", null,
      h("span.label", { style: { width: "34px", color: "var(--muted)" } }, String(i + 1).padStart(2, "0")),
      h("div.grow", null, h("div.strong", null, name), small(code),
        h("input.field", { style: { marginTop: "8px" }, placeholder: "Your name for it", "aria-label": `Your name for ${name}`, maxlength: 40, value: names[code] || "", onchange: (e) => set(code, e.target.value) })))))
      : empty("Your courses show here after the first sync."));
}

// ---- by hand, in FFCS -----------------------------------------------------------------------------

const Plan = {
  get() {
    try {
      return { semester: "", picks: [], ...JSON.parse(localStore.get("plan", "{}")) };
    } catch {
      return { semester: "", picks: [] };
    }
  },
  set: (semester, picks) => localStore.set("plan", JSON.stringify({ semester, picks })),
  saved() {
    try {
      return JSON.parse(localStore.get("ffcs", "[]"));
    } catch {
      return [];
    }
  },
  setSaved: (list) => localStore.set("ffcs", JSON.stringify(list)),
  meetings: (p) => splitSlots(p.slot).flatMap((x) => App.slots.get(x) || []),
  overlap(a, b) {
    for (const x of Plan.meetings(a)) for (const y of Plan.meetings(b)) if (x.day === y.day && x.start < y.end && y.start < x.end) return { day: x.day, start: Math.max(x.start, y.start) };
    return null;
  },
  clashes(picks) {
    const out = [];
    for (let i = 0; i < picks.length; i++) for (let j = i + 1; j < picks.length; j++) { const o = Plan.overlap(picks[i], picks[j]); if (o) out.push({ a: picks[i], b: picks[j], ...o }); }
    return out;
  },
  summary(picks) {
    const days = new Map();
    for (const p of picks) for (const m of Plan.meetings(p)) days.set(m.day, [...(days.get(m.day) || []), m]);
    const off = [1, 2, 3, 4, 5].filter((d) => !days.get(d)?.length);
    const eight = [...days.values()].filter((l) => l.some((m) => m.start === 480)).length;
    const courses = new Set(picks.map((p) => p.code)).size;
    return `${courses} course${courses === 1 ? "" : "s"} · ${off.length ? `${off.map((d) => Dates.dayName(d).slice(0, 3)).join(", ")} off` : "no day off"} · 8:00 on ${eight} day${eight === 1 ? "" : "s"}`;
  },
  text(picks, semester) {
    return `My timetable plan${semester ? ` for ${semester}` : ""}\n\n${[...picks].sort((a, b) => a.code.localeCompare(b.code)).map((p) => `${p.code} ${p.title} (${p.type}) · ${p.slot} · ${p.venue} · ${p.faculty}`).join("\n")}`;
  },
};

function byHandPanel() {
  const box = h("div");
  let alloc = null;
  let courses = null;
  let category = "";
  let opened = null;
  let options = null;
  let note = "";
  let clearing = false;
  let naming = false;
  const sameClass = (a, b) => a.code === b.code && a.slot === b.slot && a.venue === b.venue && a.faculty === b.faculty && a.type === b.type;
  const draw = () => {
    const { semester, picks } = Plan.get();
    const sem = alloc?.semester || semester;
    const set = (next) => { Plan.set(sem, next); draw(); };
    const clashes = Plan.clashes(picks);
    const saved = Plan.saved();
    const parts = [note ? h("div.small.strong", { style: { marginBottom: "10px" } }, note) : null,
      saved.length ? [h("h2", null, `Saved plans · ${saved.length}`), h("div.card", null, saved.map((sp, i) => h("div.row", { style: { padding: "5px 0" } }, h("div.grow", null, h("div.strong", null, sp.name), small(Plan.summary(sp.picks))),
        button("Open", () => { Plan.set(sp.semester || sem, sp.picks); note = `Opened "${sp.name}". Changes here don't touch the saved copy until you save again.`; draw(); }, "solid"),
        button("Delete", () => { const l = Plan.saved(); l.splice(i, 1); Plan.setSaved(l); draw(); })))) ] : null];
    if (picks.length) {
      const name = h("input.field", { placeholder: "Name this plan", "aria-label": "Name this plan", maxlength: 40, style: { maxWidth: "320px" } });
      parts.push(small(`${new Set(picks.map((p) => p.code)).size} courses · ${clashes.length ? `${clashes.length} clash${clashes.length === 1 ? "" : "es"}` : "no clashes"}`),
        semester && alloc && semester !== alloc.semester ? small(`Made for ${semester}.`) : null,
        clashes.length ? h("div.card.exams", { style: { margin: "10px 0", "--p": "var(--coral)" } }, label("Clashes"), clashes.map((c) => small(`${c.a.code} and ${c.b.code}: ${Dates.dayName(c.day)} ${Dates.clock(c.start)}`))) : null,
        h("h2", null, "Your week"), planGrid(picks.map((p) => ({ ...p, meetings: Plan.meetings(p) }))),
        h("h2", null, "Your classes"), h("div.card", null, [...picks].sort((a, b) => a.code.localeCompare(b.code)).map((p) => h("div.row", { style: { padding: "5px 0" } }, h("div.grow", null, h("div.strong", null, `${p.code} ${Names.all()[p.code] || p.title}`), small([p.type, p.slot, p.venue, titleCase(p.faculty)].filter(Boolean).join(" · "))), button("Remove", () => set(picks.filter((x) => x !== p)))))),
        h("div.row.wrap", { style: { marginTop: "10px" } }, copyButton("Copy the plan", () => Plan.text(picks, sem)),
          button(clearing ? "Yes, start over" : "Start over", () => { if (clearing) { clearing = false; set([]); } else { clearing = true; draw(); } }),
          naming ? [name, button("Save", () => { Plan.setSaved([{ id: Date.now(), name: name.value.trim() || "Plan", semester: sem, picks }, ...Plan.saved()]); note = `Saved as "${name.value.trim() || "Plan"}".`; naming = false; draw(); }, "solid")] : button("Save as…", () => { naming = true; draw(); })));
    }
    parts.push(h("h2", null, "Add a course"));
    if (!alloc) parts.push(empty("Reading course allocation"));
    else if (!alloc.categories.length) parts.push(empty("VTOP shows no course allocation yet. It opens a little before registration."));
    else {
      parts.push(h("select.field", { "aria-label": "Category", style: { marginBottom: "10px", maxWidth: "520px" }, onchange: async (e) => {
        category = e.target.value;
        courses = null;
        opened = null;
        options = null;
        draw();
        if (category) { courses = (await api("section", { name: "allocationCourses", arg: category, force: null })).data; draw(); }
      } }, h("option", { value: "" }, "Pick a category to see its courses"), alloc.categories.map(([id, t]) => h("option", { value: id, selected: id === category }, t))));
      if (category && !courses) parts.push(empty("Reading the courses"));
      if (courses) {
        if (!courses.length) parts.push(small("No courses allotted in this category."));
        parts.push(h("div.stack", null, courses.map(([code, text]) => {
          const title = text.includes(" - ") ? text.split(" - ").slice(1).join(" - ") : text;
          const isOpen = opened === code;
          const chosen = picks.filter((p) => p.code === code);
          return h("div.card.classes", null,
            h("button.linkrow", { type: "button", "aria-expanded": isOpen, onclick: async () => {
              if (isOpen) { opened = null; options = null; draw(); return; }
              opened = code;
              options = null;
              draw();
              try {
                options = allocationOptions((await api("section", { name: "allocationClasses", arg: code, force: null })).data, code, title, App.slots);
              } catch (e) {
                options = [];
                note = String(e);
              }
              draw();
            } }, h("div.strong", null, `${code} ${Names.all()[code] || title}`), small(chosen.length ? `In your plan: ${chosen.map((c) => c.slot).join(", ")}` : "Not in your plan · open for its classes")),
            isOpen ? (!options ? empty("Reading the classes") : !options.length ? small("VTOP lists no classes for this course yet.") : Object.entries(options.reduce((m, o) => ((m[o.type] ||= []).push(o), m), {})).map(([type, group]) => [
              h("div.label", { style: { marginTop: "12px" } }, type || "Classes"),
              group.map((o) => {
                const pick = { code: o.code, title: o.title, slot: o.slot, venue: o.venue, faculty: o.faculty, type: o.type };
                const on = picks.some((p) => sameClass(p, pick));
                const against = picks.filter((p) => !(p.code === pick.code && p.type === pick.type) && Plan.overlap(pick, p));
                return h("button.linkrow", { type: "button", "aria-pressed": on, style: { marginTop: "8px" }, onclick: () => set(on ? picks.filter((p) => !sameClass(p, pick)) : [...picks.filter((p) => !(p.code === pick.code && p.type === pick.type)), pick]) },
                  h("div.row", null, h("div.grow", null, h("div.small.strong", null, `${pick.slot} · ${pick.venue}`), small(titleCase(pick.faculty)), small(Plan.meetings(pick).sort((a, b) => a.day - b.day || a.start - b.start).map((m) => `${Dates.dayName(m.day).slice(0, 3)} ${Dates.clock(m.start)}`).join(" · ") || "Times unknown"), against.length ? h("div.small.err", null, `Clashes with ${against.map((x) => x.code).join(", ")}`) : null), h("span.badge", { class: on ? "on" : "" }, on ? "Picked" : "Pick")));
              }),
            ])) : null);
        })));
      }
    }
    box.replaceChildren(...parts.flat(Infinity).filter(Boolean));
  };
  draw();
  api("section", { name: "allocation", arg: null, force: null }).then((r) => { alloc = r.data; draw(); }, (e) => { box.prepend(errorNote(e)); });
  return box;
}

/** The two ways into FFCS: Smart finds timetables, By hand keeps what you pick. */
function screenFfcsModes() {
  let mode = Plan.get().picks.length ? 1 : 0;
  const body = h("div");
  const tabs = h("div");
  const draw = () => {
    tabs.replaceChildren(choiceRow(["Smart", "By hand"], mode, (i) => { mode = i; draw(); }));
    if (mode === 0) {
      const smart = screenFfcs();
      body.replaceChildren(smart);
    } else {
      body.replaceChildren(h("div.page", { style: { padding: 0 } }, ...pageTitle("FFCS", "Pick a class for each course from VTOP's course allocation, and see the week it makes and any clash at once. Read only: registering happens on VTOP.").flat().filter(Boolean), byHandPanel()));
    }
  };
  draw();
  return h("div.page", null, h("div", { style: { marginBottom: "14px" } }, tabs), body);
}
