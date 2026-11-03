// Everything else VTOP shows, one screen each, plus reviews and settings.
"use strict";

/** A section read through the app's cache: a loading line, then [draw] with the data. */
async function sectionView(name, draw, arg) {
  const box = h("div");
  await sectionInto(box, name, draw, arg);
  return box;
}

function sectionInto(box, name, draw, arg) {
  box.replaceChildren(empty("Reading VTOP…"));
  return api("section", { name, arg: arg ?? null, force: null }).then(
    (r) => box.replaceChildren(...[draw(r.data)].flat(Infinity).filter((x) => x != null && x !== false).map((x) => (x.nodeType ? x : document.createTextNode(String(x))))),
    (e) => box.replaceChildren(h("div.err", null, e), button("Try again", () => sectionInto(box, name, draw, arg))),
  );
}

function simplePage(title, sub, name, draw, arg) {
  const box = h("div");
  sectionInto(box, name, draw, arg);
  return h("div.page", null, pageTitle(title, sub, button("Refresh", () => {
    box.replaceChildren(empty("Reading VTOP…"));
    api("section", { name, arg: arg ?? null, force: true }).then(() => sectionInto(box, name, draw, arg), (e) => box.replaceChildren(h("div.err", null, e)));
  })), box);
}

const search = (placeholder, oninput) => h("input.field", { placeholder, "aria-label": placeholder, oninput: (e) => oninput(e.target.value), style: { marginBottom: "12px" } });
const shownDate = (iso) => Dates.parse(iso)?.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) || iso;

// ---- watched lists, kept with the sync ------------------------------------------------------------

function screenCirculars(s) {
  const all = [...s.feeds.circulars].sort((a, b) => b.date.localeCompare(a.date));
  const list = h("div");
  const draw = (q) => {
    const words = q.toLowerCase().split(" ").filter(Boolean);
    const found = all.filter((c) => words.every((w) => `${c.title} ${c.office} ${c.group}`.toLowerCase().includes(w)));
    list.replaceChildren(...(found.length ? found.slice(0, 80).map((c) => fileRow(c.title, [shownDate(c.date), c.group].filter(Boolean).join(" · "), "circular", { id: c.id }, `circ_${c.id}`)) : [empty(all.length ? `Nothing matches "${q}".` : "Circulars arrive with the next sync.")]));
  };
  draw("");
  return h("div.page", null, pageTitle("Circulars", "Official PDFs from VTOP's circular portal. New ones reach you as alerts."), search("Search circulars", draw), h("div.card", null, list));
}

function screenWindows(s) {
  const w = [...s.feeds.windows].sort((a, b) => b.open - a.open);
  return h("div.page", null, pageTitle("What's open", "daVIT checks these once a day and tells you when one opens. You apply on VTOP."),
    w.length ? h("div.card", null, w.map((x, i) => [i ? h("hr.hair") : null, h("div.row", null, h("div.grow", null, h("div.strong", null, x.title), x.note ? small(x.note) : null), h("span.badge", { class: x.open ? "on" : "" }, x.open ? "Open" : "Closed"))])) : empty("This fills in with the next sync."));
}

function screenProjects(s) {
  const all = [...s.feeds.projects].sort((a, b) => b.posted.localeCompare(a.posted));
  const areas = Object.entries(all.reduce((m, p) => (p.area ? ((m[p.area] = (m[p.area] || 0) + 1), m) : m), {})).sort((a, b) => b[1] - a[1]).map((x) => x[0]);
  let q = "";
  let area = "";
  const list = h("div.grid2");
  const chips = h("div.chips", { style: { marginBottom: "12px" } });
  const count = h("div.strong", { style: { marginBottom: "8px" } });
  const follow = h("div", { style: { marginBottom: "10px" } });
  const draw = () => {
    chips.replaceChildren(...areas.map((a) => h("button.chip", { type: "button", class: a === area ? "on" : "", onclick: () => { area = area === a ? "" : a; draw(); } }, a)));
    const p = AlertPrefs.get();
    const on = p.projectAreas.includes(area);
    follow.replaceChildren(...(area ? [button(on ? `Stop alerts for ${area}` : `Alert me about new ${area} projects`, () => { AlertPrefs.set({ ...p, projectAreas: on ? p.projectAreas.filter((x) => x !== area) : [...p.projectAreas, area] }); draw(); }, on ? "" : "solid")] : []));
    const words = q.toLowerCase().split(" ").filter(Boolean);
    const found = all.filter((p) => (!area || p.area === area) && words.every((w) => `${p.title} ${p.area} ${p.faculty}`.toLowerCase().includes(w)));
    count.textContent = `${found.length} project${found.length === 1 ? "" : "s"}`;
    list.replaceChildren(...found.slice(0, 60).map((p) => {
      const who = p.faculty.replace(/^Dr\./i, "");
      return h("div.card", null, h("div.strong", null, p.title), small([p.area, shownDate(p.posted)].filter(Boolean).join(" · ")), small([titleCase(who), p.school].filter(Boolean).join(" · ")), h("div", { style: { marginTop: "5px" } }, ratingLink(null, who)));
    }));
  };
  draw();
  return h("div.page", null, pageTitle("Faculty projects", "Research open to students. Ask to join on VTOP."), search("Title, area or teacher", (v) => { q = v; draw(); }), chips, follow, count, all.length ? list : empty("This fills in with the next sync."));
}

// ---- sections read when opened --------------------------------------------------------------------

function screenMessages() {
  const group = (title, list) => [h("h2", null, title), list.length ? h("div.stack", null, list.map((m) => h("div.card", null, h("div.strong", null, m.text), small([m.from, m.course, m.date].filter(Boolean).join(" · "))))) : empty("Nothing right now.")];
  return simplePage("Messages", "They expire on VTOP after a while.", "messages", (d) => [group("Class", d.class), group("Proctor", d.proctor)]);
}

function screenSpotlight() {
  return simplePage("Spot-light", "VTOP's notices.", "spotlight", (items) => {
    const sections = Object.entries(items.reduce((m, x) => ((m[x.section || "Notices"] ||= []).push(x), m), {}));
    return sections.length ? sections.map(([t, list]) => [h("h2", null, t), h("div.card", null, list.map((s) => s.download
      ? fileRow(s.title, "File", "spotlight", { path: s.download }, `spot_${s.download.split("/").pop()}`)
      : h("div.row", { style: { padding: "5px 0" } }, h("div.grow.strong", null, s.title), button("Open", () => api("open_link", { url: s.link })))))]) : empty("No notices.");
  });
}

function screenClubs() {
  return simplePage("All clubs", "Every club and chapter on VTOP. Joining stays on VTOP.", "clubDirectory", (d) => {
    const list = h("div.card");
    const draw = (q) => {
      const found = d.all.filter((c) => c.name.toLowerCase().includes(q.trim().toLowerCase()));
      list.replaceChildren(...(found.length ? found.slice(0, 100).map((c, i) => [i ? h("hr.hair") : null, h("div.strong", null, clubName(c.name)), small([titleCase(c.type), c.status].filter(Boolean).join(" · "))]).flat().filter(Boolean) : [empty("Nothing matches.")]));
    };
    draw("");
    return [d.mine.length ? [h("h2", null, "Yours"), h("div.card", { style: { marginBottom: "14px" } }, d.mine.map((c) => h("div.row", { style: { padding: "2px 0" } }, h("div.grow.strong", null, clubName(c.name)), c.status ? h("span.badge", null, c.status) : null)))] : null,
      search(`Search ${d.all.length} clubs`, draw), list];
  });
}

function screenSports() {
  return simplePage("Sports and gym", "Fees include GST. Registering and paying stay on VTOP.", "sports", (d) => [
    d.mine.length ? [h("h2", null, "Yours"), h("div.card", null, d.mine.map((m) => [h("div.strong", null, m.name), small(m.status)]))] : null,
    h("h2", null, "Facilities"),
    d.facilities.length ? h("div.grid2", null, d.facilities.map((f) => h("div.card", null, h("div.row", null, h("div.grow", null, h("div.strong", null, f.name), small(`₹${f.fee}`)), h("span.badge", null, `${f.seats} seats`))))) : empty("VTOP lists none right now."),
  ]);
}

function screenCurriculum() {
  return simplePage("Curriculum", null, "curriculum", (d) => [
    h("div.grid3", null, d.categories.map((c) => h("div.card", null, h("div.strong", null, c.name), small(`${num(c.credits)} of ${num(c.max)} credits`)))),
    Object.entries(d.courses.reduce((m, c) => ((m[c.group] ||= []).push(c), m), {})).map(([g, list]) => [h("h2", null, g), h("div.card", null, list.map((c) => fileRow(`${c.code} · ${c.title}`, [c.type, c.credits != null && `${num(c.credits)} credits`, c.ltpj].filter(Boolean).join(" · "), "syllabus", { courseCode: c.code }, `syl_${c.code}`)))]),
  ]);
}

function screenCertificates() {
  return simplePage("Certificates", "graVITas, University Day and Six Sigma.", "certificates", (list) => [
    list.length ? Object.entries(list.reduce((m, c) => ((m[c.source] ||= []).push(c), m), {})).map(([src, certs]) => [h("h2", null, src), h("div.card", null, certs.map((c) => fileRow(titleCase(c.title), c.detail, "certificate", { ...c.params, path: c.path, menu: c.menu }, `cert_${c.path}_${Object.values(c.params).join("_")}`.replace(/[^\w.-]/g, "_"))))]) : empty("No certificates on these pages yet."),
    h("div", { style: { marginTop: "14px" } }, button("Club event certificates", () => go("events"))),
  ]);
}

function screenRules() {
  let tab = 0;
  let q = "";
  return simplePage("Rules and FAQ", "VTOP's own words, kept for offline.", "rules", (d) => {
    const body = h("div");
    const tabs = h("div.tabs");
    const draw = () => {
      tabs.replaceChildren(...["FAQ", "Malpractice"].map((t, i) => h("button.tab", { type: "button", class: i === tab ? "on" : "", onclick: () => { tab = i; draw(); } }, t)));
      const words = q.toLowerCase().split(" ").filter(Boolean);
      const hit = (text) => words.every((w) => text.toLowerCase().includes(w));
      if (tab === 0) {
        const found = d.faqs.filter((f) => hit(`${f.question} ${f.answer}`));
        const groups = Object.entries(found.reduce((m, f) => ((m[f.domain] ||= []).push(f), m), {}));
        body.replaceChildren(...(groups.length ? groups.flatMap(([g, list]) => [h("h2", null, titleCase(g)), h("div.card", null, list.map((f) => h("details", { style: { padding: "4px 0" } }, h("summary.strong", { style: { cursor: "pointer" } }, f.question), h("div.small", { style: { marginTop: "4px" } }, f.answer || "VTOP gives no answer."))))]) : [empty("No answers here.")]));
      } else {
        const groups = Object.entries(d.malpractice.filter((r) => hit(`${r.nature} ${r.action}`)).reduce((m, r) => ((m[r.exam] ||= []).push(r), m), {}));
        body.replaceChildren(...groups.flatMap(([g, list]) => [h("h2", null, g), h("div.card", null, list.map((r, i) => [i ? h("hr.hair") : null, h("div.strong", null, r.nature), small(r.action)]))]));
      }
    };
    draw();
    return [h("div.card", { style: { marginBottom: "10px" } }, fileRow("Academic regulations", "PDF", "regulations", {}, "regulations")), tabs, search("Search", (v) => { q = v; draw(); }), body];
  });
}

function screenMoney() {
  return simplePage("Receipts and dues", "Paying stays on VTOP.", "money", (d) => [
    d.library.length ? [h("h2", null, "Library"), h("div.card", null, d.library.map(([k, v]) => h("div.row", null, h("div.grow", null, k), h("span.strong", null, v == null ? "–" : `₹${v.toFixed(2)}`))))] : null,
    d.letters.length ? [h("h2", null, "Fees letters"), h("div.card", null, d.letters.map((f) => fileRow(titleCase(f.description), `Year ${f.year} · term ${f.term}`, "feeLetter", { batchId: f.batchId }, `fee_${f.batchId}`)))] : null,
    h("h2", null, "Receipts"),
    d.receipts.length ? plainTable({ headers: ["Date", "Receipt", "Invoice", "Amount", "Campus"], rows: d.receipts.map((r) => [r.date, r.number, r.invoice, r.amount == null ? "–" : `₹${r.amount}`, r.campus]) }) : empty("No receipts on VTOP."),
  ]);
}

function screenGate() {
  return simplePage("Gate log", "Your biometric entries and exits for the last 7 days.", "gate", (t) => (t.rows.length ? plainTable(t) : empty("No entries in the last 7 days.")), "7");
}

function screenSignIns() {
  return simplePage("Sign-in activity", "Your VTOP logins for about three months. daVIT's look like the website's, and your IP changes as you move between networks, so a new IP alone isn't a worry.", "signIns",
    (list) => (list.length ? plainTable({ headers: ["When", "IP address", "App"], rows: list.slice(0, 100).map((x) => [x.time, x.ip, x.app]) }) : empty("VTOP lists none.")));
}

function screenRegistered() {
  return simplePage("FFCS result", "The classes you registered for next semester, as soon as VTOP has them.", "registered", (r) => {
    if (!r || !r.courses.length) return empty("No registered classes for next semester yet. They show here once you've registered on VTOP.");
    const names = Names.all();
    const courses = r.courses.map((c) => ({ ...c, courseName: names[c.courseCode] || c.courseName }));
    const picks = courses.map((c) => ({ code: c.courseCode, title: c.courseName, slot: c.slot, venue: c.venue, faculty: c.faculty, type: c.courseType }));
    const clashes = Plan.clashes(picks);
    const codes = new Set(courses.map((c) => c.courseCode)).size;
    const credits = courses.reduce((n, c) => n + c.credits, 0);
    let view = 0;
    const body = h("div");
    const tabs = h("div");
    const draw = () => {
      tabs.replaceChildren(choiceRow(["Timetable", "Courses"], view, (i) => { view = i; draw(); }));
      body.replaceChildren(view === 0 ? planGrid(picks.map((p) => ({ ...p, meetings: Plan.meetings(p) }))) : plainTable({ headers: ["Code", "Course", "Type", "Slot", "Room", "Faculty", "Credits"], rows: courses.map((c) => [c.courseCode, c.courseName, c.courseType, c.slot, c.venue, titleCase(c.faculty), num(c.credits)]) }));
    };
    draw();
    return [h("div.strong", { style: { marginBottom: "10px" } }, r.semester.name),
      h("div.grid3", { style: { marginBottom: "12px" } }, [[codes, codes === 1 ? "course" : "courses"], [num(credits), "credits"], [clashes.length, clashes.length === 1 ? "clash" : "clashes"]].map(([n, t]) => h("div.card.classes", null, h("span.big", null, n), ` ${t}`))),
      h("div.row.wrap", { style: { marginBottom: "12px" } }, h("div.grow", null, tabs), h("span.badge", null, courses.every((c) => c.approved) ? "Approved" : "Not approved yet")),
      clashes.length ? h("div.card.exams", { style: { marginBottom: "12px", "--p": "var(--coral)" } }, label("Clashes"), clashes.map((c) => small(`${c.a.code} and ${c.b.code}: ${Dates.dayName(c.day)} ${Dates.clock(c.start)}`))) : null,
      body];
  });
}

// ---- reviews --------------------------------------------------------------------------------------

const Reviews = {
  book: null,
  load() {
    this.book ||= fetch("data/reviews.json").then((r) => r.json()).then((d) => {
      const byEmp = new Map(d.faculty.filter((f) => f.empId).map((f) => [f.empId.trim().toUpperCase(), f]));
      const names = new Map();
      for (const f of d.faculty) {
        if (f.campus !== "vellore") continue;
        const k = matchKey(f.name);
        names.set(k, names.has(k) ? null : f);
      }
      return {
        faculty: d.faculty,
        snapshotAt: d.snapshotAt,
        find: (emp, name) => (emp && byEmp.get(String(emp).trim().toUpperCase())) || names.get(matchKey(name)) || null,
        byId: (id) => d.faculty.find((f) => f.id === id),
      };
    });
    return this.book;
  },
};

function screenReviews() {
  let q = "";
  let campus = "vellore";
  const list = h("div.grid2");
  const chips = h("div.chips", { style: { marginBottom: "12px" } });
  const head = h("h2", { style: { marginTop: 0 } });
  const draw = async () => {
    const book = await Reviews.load();
    chips.replaceChildren(...[["vellore", "Vellore"], ["chennai", "Chennai"]].map(([id, t]) => h("button.chip", { type: "button", class: id === campus ? "on" : "", onclick: () => { campus = id; draw(); } }, t)));
    const words = q.toLowerCase().split(" ").filter(Boolean);
    const found = book.faculty.filter((f) => f.campus === campus && (words.length ? words.every((w) => `${f.name} ${f.department} ${(f.reviews || []).map((r) => `${r.course} ${r.courseName}`).join(" ")}`.toLowerCase().includes(w)) : f.reviewCount > 0)).sort((a, b) => b.reviewCount - a.reviewCount);
    head.textContent = q.trim() ? `${found.length} found` : "Most reviewed";
    list.replaceChildren(...(found.length ? found.slice(0, 50).map((f) => h("button.card.click", { type: "button", onclick: () => go("review", { id: f.id }) }, h("div.row", null, h("div.grow", null, h("div.strong", null, f.name), small(f.department), small(f.reviewCount ? `${f.reviewCount} review${f.reviewCount > 1 ? "s" : ""}` : "No reviews yet")), f.reviewCount ? h("div.big", null, Number(f.rating).toFixed(1)) : null))) : [empty(`Nobody matches "${q}".`)]));
  };
  draw();
  return h("div.page", null, pageTitle("Faculty reviews", "Student ratings from No8AM. Works offline, and stops at 1 November 2026."), search("Name, department or course", (v) => { q = v; draw(); }), chips, head, list);
}

function screenReview(_, args) {
  const box = h("div.page");
  Reviews.load().then((book) => {
    const f = book.byId(args.id);
    if (!f) return box.replaceChildren(empty("That teacher isn't in the reviews."));
    box.append(...pageTitle(f.name, [f.designation, f.department].filter(Boolean).join(" · "), button("Back", () => history.back())).flat().filter(Boolean),
      h("div.card", { style: { marginBottom: "14px" } }, h("span.big", null, f.reviewCount ? Number(f.rating).toFixed(1) : "–"), ` from ${f.reviewCount} review${f.reviewCount === 1 ? "" : "s"}`),
      h("div.stack", null, (f.reviews || []).map((r) => {
        const cutoffs = ["s", "a", "b", "c", "d", "e", "f"].filter((g) => r.cutoffs?.[g] != null).map((g) => `${g.toUpperCase()} ${r.cutoffs[g]}`);
        const stats = [r.classStrength != null && `Class of ${r.classStrength}`, r.classMean != null && `Mean ${r.classMean}`, r.classSd != null && `SD ${r.classSd}`].filter(Boolean);
        const source = r.source === "reddit" ? "From Reddit" : r.source === "compiled" ? "Compiled by No8AM" : "Written on No8AM";
        return h("div.card.people", null,
          h("div.row", null, h("div.grow.strong", null, [r.course, r.courseName].filter(Boolean).join(" · ") || "Course not given"), h("span.badge.on", null, `${r.rating} of 5`)),
          h("div", { style: { marginTop: "6px" } }, r.text),
          cutoffs.length ? small(`Cutoffs: ${cutoffs.join(" · ")}`) : null,
          stats.length ? small(stats.join(" · ")) : null,
          (r.replies || []).map((x) => [h("hr.hair"), label(`Reply · ${x.date || ""}`), small(x.text)]),
          small([r.date, source, r.paraphrased && "Paraphrased"].filter(Boolean).join(" · ")));
      })));
  });
  return box;
}
