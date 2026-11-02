// The app's rules, the same as Android's. Attendance is integer arithmetic on the exact fraction:
// 75% means 4·attended ≥ 3·total, and a shown percentage rounds down, so "75%" always means in.
"use strict";

const Rules = {
  percent: (r) => (r.total === 0 ? 100 : Math.floor((r.attended * 100) / r.total)),
  atRisk: (r) => 4 * r.attended < 3 * r.total,
  canSkipNow: (r) => Math.max(0, Math.trunc((4 * r.attended - 3 * r.total) / 3)),
  mustAttend: (r) => Math.max(0, 3 * r.total - 4 * r.attended),
  skipTillEnd: (r, left) => Math.floor((4 * r.attended + left - 3 * r.total) / 4),
  exempt: (s) => (s.grades?.cgpa ?? 0) >= 9,

  hint(r, left) {
    if (left == null) return Rules.atRisk(r) ? `attend the next ${Rules.mustAttend(r)} to reach 75%` : `can skip ${Rules.canSkipNow(r)} and stay at 75%`;
    const k = Rules.skipTillEnd(r, left);
    if (left === 0) return Rules.atRisk(r) ? "no classes left to reach 75%" : "no classes left";
    if (k < 0) return `75% can't be reached with ${left} classes left`;
    if (Rules.atRisk(r)) return `attend the next ${Rules.mustAttend(r)} to reach 75% (${left} left)`;
    if (k >= left) return left === 1 ? "can skip the last one" : `can skip all ${left} left`;
    return `can skip ${k} of the ${left} left`;
  },

  // The mean of each course's exact fraction, rounded down once at the end.
  overall(rows) {
    const held = rows.filter((r) => r.total > 0);
    if (!held.length) return null;
    let num = 0n, den = 1n;
    for (const r of held) {
      num = num * BigInt(r.total) + BigInt(r.attended) * den;
      den *= BigInt(r.total);
    }
    return Number((num * 100n) / (den * BigInt(held.length)));
  },

  isLab: (type) => /lab/i.test(type || ""),
  key: (r) => `${r.courseCode}|${r.classType}`,
};

function parseSlots(text) {
  const map = new Map();
  for (const raw of (text || "").split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const [slot, ...fields] = line.split(" ");
    const mins = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(2));
    map.set(slot, fields.map((f) => {
      const [day, range] = f.split(":");
      const [a, b] = range.split("-");
      return { day: Number(day), start: mins(a), end: mins(b), slot };
    }));
  }
  return map;
}

function splitSlots(slot) {
  const parts = (slot || "").split("+").map((x) => x.trim().toUpperCase()).filter(Boolean);
  if (!parts.length || !parts.every((x) => x.startsWith("L"))) return parts;
  const out = [];
  for (let i = 0; i < parts.length; i += 2) out.push(parts.slice(i, i + 2).join("+"));
  return out;
}

function matchAttendance(course, rows) {
  return rows.find((r) => r.classId && r.classId === course.classId)
    || rows.find((r) => r.courseCode === course.courseCode && Rules.isLab(r.classType) === Rules.isLab(course.courseType))
    || rows.find((r) => r.courseCode === course.courseCode) || null;
}

function weekOf(snapshot, slots) {
  const week = {};
  for (const course of snapshot.timetable) {
    const att = matchAttendance(course, snapshot.attendance);
    for (const part of splitSlots(course.slot)) {
      for (const m of slots.get(part) || []) {
        (week[m.day] ||= []).push({ course, meeting: m, attendance: att, isLab: Rules.isLab(course.courseType) || m.slot.startsWith("L") });
      }
    }
  }
  for (const d of Object.keys(week)) week[d].sort((a, b) => a.meeting.start - b.meeting.start);
  return week;
}

const Dates = {
  iso(d) {
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  },
  parse(s) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s || "");
    return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
  },
  // VTOP's "15-Oct-2026" or "15-10-2026".
  vtop(s) {
    const m = /^(\d{1,2})-([A-Za-z]{3}|\d{2})-(\d{4})/.exec((s || "").trim());
    if (!m) return null;
    const month = /\d/.test(m[2]) ? +m[2] - 1 : "jan feb mar apr may jun jul aug sep oct nov dec".split(" ").indexOf(m[2].toLowerCase());
    return month < 0 ? null : new Date(+m[3], month, +m[1]);
  },
  weekday: (d) => (d.getDay() === 0 ? 7 : d.getDay()),
  addDays: (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n),
  between: (a, b) => Math.round((Dates.addDays(b, 0) - Dates.addDays(a, 0)) / 864e5),
  clock: (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`,
  short: (d) => d.toLocaleDateString("en-GB", { day: "numeric", month: "short" }),
  long: (d) => d.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" }),
  dayName: (n) => ["", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"][n],
};

// The calendar's cells, read as Android's AcademicCalendar.fromVtop reads them.
function calendarOf(cal) {
  if (!cal) return null;
  const names = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];
  const days = new Map();
  for (const c of cal.cells) {
    const date = Dates.parse(c.date);
    const label = c.label.toLowerCase();
    let d;
    if (label.startsWith("instructional")) {
      const m = /(\w+)\s+day\s+order/i.exec(c.note);
      const order = m ? names.indexOf(m[1].toLowerCase()) + 1 : 0;
      d = { type: "Instructional", order: order > 0 ? order : Dates.weekday(date), note: c.note };
    } else if (label.startsWith("holiday") || label.includes("no instruction") || label.includes("non instruction")) {
      d = { type: "Holiday", order: 0, note: c.note };
    } else if (label.startsWith("cat")) {
      d = { type: /ii\s*$|2/.test(label.split("(")[0].trim()) ? "CAT-2" : "CAT-1", order: 0, note: "" };
    } else if (label.includes("mid term")) {
      d = { type: "MTT", order: 0, note: "" };
    } else if (!label.trim()) {
      d = { type: "None", order: 0, note: "" };
    } else {
      d = { type: c.label, order: 0, note: c.note };
    }
    d.date = date;
    d.instructional = d.type === "Instructional";
    d.exam = /^CAT|^FAT$|^MTT$/i.test(d.type);
    days.set(c.date, d);
  }
  const ins = [...days.values()].filter((d) => d.instructional).sort((a, b) => a.date - b.date);
  const lastWith = (...w) => [...ins].reverse().find((d) => d.note && w.every((x) => d.note.toLowerCase().includes(x)))?.date;
  const any = lastWith("last", "instruction");
  const lastDay = ins.at(-1)?.date ?? null;
  const lastTheory = lastWith("last", "theory") || any || lastDay;
  const lastLab = lastWith("last", "lab") || any || lastDay;
  return {
    group: cal.group,
    day: (date) => days.get(Dates.iso(date)) || null,
    days: [...days.values()].sort((a, b) => a.date - b.date),
    lastTheory,
    lastLab,
    timetableDay(date) {
      const d = days.get(Dates.iso(date));
      if (!d) return Dates.weekday(date);
      if (!d.instructional) return null;
      return d.order >= 1 && d.order <= 7 ? d.order : Dates.weekday(date);
    },
    daysLeft: (from) => ins.filter((d) => d.date >= Dates.addDays(from, 0) && (!lastTheory || d.date <= lastTheory)).length,
  };
}

function classesOn(week, date, cal) {
  const wd = cal ? cal.timetableDay(date) : Dates.weekday(date);
  return wd ? week[wd] || [] : [];
}

function planFor(week, at, cal) {
  const minute = at.getHours() * 60 + at.getMinutes();
  const today = classesOn(week, at, cal);
  const now = today.find((s) => minute >= s.meeting.start && minute < s.meeting.end) || null;
  let next = today.find((s) => s.meeting.start > minute) || null;
  let inDays = 0;
  let nextDate = next ? at : null;
  if (!next) {
    for (let d = 1; d <= 21; d++) {
      const when = Dates.addDays(at, d);
      const first = classesOn(week, when, cal)[0];
      if (first) {
        next = first;
        inDays = d;
        nextDate = when;
        break;
      }
    }
  }
  return { today, now, next, inDays, nextDate, minute, done: today.filter((s) => s.meeting.end <= minute).length, day: cal?.day(at) || null };
}

// Classes of this row's course still to come, from now to the last theory or lab day.
function remainingFor(row, week, cal, at) {
  if (!cal) return null;
  const end = Rules.isLab(row.classType) ? cal.lastLab : cal.lastTheory;
  if (!end) return null;
  const meetings = Object.values(week).flat().filter((s) => s.attendance && Rules.key(s.attendance) === Rules.key(row)).map((s) => s.meeting);
  if (!meetings.length) return null;
  const minute = at.getHours() * 60 + at.getMinutes();
  let count = 0;
  for (let d = Dates.addDays(at, 0); d <= end; d = Dates.addDays(d, 1)) {
    const wd = cal.timetableDay(d);
    if (!wd) continue;
    const today = Dates.iso(d) === Dates.iso(at);
    count += meetings.filter((m) => m.day === wd && (!today || m.start > minute)).length;
  }
  return count;
}

const facultyKey = (name) => (name || "").toUpperCase().replace(/^(DR|PROF|MR|MRS|MS)\.?\s*/, "").replace(/[^A-Z ]/g, " ").trim().replace(/\s+/g, " ");
const matchKey = (name) => facultyKey(name).split(" ").sort().join(" ");
const titleCase = (s) => (s || "").toLowerCase().replace(/(^|[\s(.-])(\p{L})/gu, (m, a, b) => a + b.toUpperCase());
const clubName = (s) => titleCase(s).replace(/\(([^()]+)\)/g, (m, x) => `(${x.toUpperCase()})`).replace(/\bVit\b/g, "VIT");

if (typeof module !== "undefined") module.exports = { Rules, parseSlots, splitSlots, Dates, calendarOf, planFor, remainingFor, weekOf, facultyKey, matchKey, clubName };
