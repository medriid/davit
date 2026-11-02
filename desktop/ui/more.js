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

const MORE = [
  ["Plan", [
    ["ffcs", "FFCS", "Next semester's best timetables"], ["ffcsresult", "FFCS result", "What you registered for"], ["leave", "Plan a leave", "What days away cost"], ["mess", "Mess menu", "Today's meals"],
  ]],
  ["News and people", [
    ["messages", "Messages", "Class and proctor"], ["circulars", "Circulars", "Every official PDF"], ["windows", "What's open", "Registrations open now"],
    ["spotlight", "Spot-light", "VTOP's notices and files"], ["reviews", "Faculty reviews", "Ratings, offline"], ["projects", "Faculty projects", "Research open to students"],
    ["events", "Club events", "Certificates too"], ["clubs", "All clubs", "Every club and yours"], ["sports", "Sports and gym", "Fees and seats left"],
  ]],
  ["Records", [
    ["curriculum", "Curriculum", "Credits and syllabus PDFs"], ["certificates", "Certificates", "graVITas and University Day"], ["rules", "Rules and FAQ", "Regulations, malpractice"],
    ["money", "Receipts and dues", "Fees letters, library"], ["gate", "Gate log", "Biometric in and out"], ["signins", "Sign-in activity", "Every login, with its IP"],
    ["hostel", "Hostel", "Leave, night slips, mess"],
  ]],
];

function screenMore() {
  return h("div.page", null, pageTitle("More from VTOP", "Read only. Applying, registering and paying stay on VTOP."),
    MORE.map(([title, items]) => [h("h2", null, title), h("div.grid3", null, items.map(([r, t, note]) => h("button.card.click", { type: "button", onclick: () => go(r) }, h("div.strong", null, t), small(note))))]));
}

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
  const draw = () => {
    chips.replaceChildren(...areas.map((a) => h("button.chip", { type: "button", class: a === area ? "on" : "", onclick: () => { area = area === a ? "" : a; draw(); } }, a)));
    const words = q.toLowerCase().split(" ").filter(Boolean);
    const found = all.filter((p) => (!area || p.area === area) && words.every((w) => `${p.title} ${p.area} ${p.faculty}`.toLowerCase().includes(w)));
    count.textContent = `${found.length} project${found.length === 1 ? "" : "s"}`;
    list.replaceChildren(...found.slice(0, 60).map((p) => {
      const who = p.faculty.replace(/^Dr\./i, "");
      return h("div.card", null, h("div.strong", null, p.title), small([p.area, shownDate(p.posted)].filter(Boolean).join(" · ")), small([titleCase(who), p.school].filter(Boolean).join(" · ")), h("div", { style: { marginTop: "5px" } }, ratingLink(null, who)));
    }));
  };
  draw();
  return h("div.page", null, pageTitle("Faculty projects", "Research open to students. Ask to join on VTOP."), search("Title, area or teacher", (v) => { q = v; draw(); }), chips, count, all.length ? list : empty("This fills in with the next sync."));
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

function screenEvents(s) {
  const up = s.feeds.events;
  return simplePage("Club events", "Register for events on VTOP.", "clubs", ({ page, general }) => [
    h("h2", null, "Upcoming"),
    page.upcoming.length ? h("div.grid2", null, page.upcoming.map((e) => h("div.card.events", null, h("div.strong", null, e.title), small(e.club), small([e.dates, e.time, e.venue].filter(Boolean).join(" · ")), e.state ? small(e.state) : null))) : (up.length ? up.map((e) => small(`${e.date || ""} ${e.title}`)) : empty("Nothing scheduled.")),
    page.registered.length ? [h("h2", null, "You registered for"), h("div.card", null, page.registered.map((e) => h("div", null, h("div.row", null, h("div.grow", null, h("div.strong", null, e.title), small([e.club, e.dates, e.status].filter(Boolean).join(" · ")))),
      e.participation ? fileRow("Participation certificate", null, "eventCertificate", { eventId: e.id, winner: "false" }, `ev_${e.id}`) : null,
      e.winner ? fileRow("Winner certificate", null, "eventCertificate", { eventId: e.id, winner: "true" }, `evw_${e.id}`) : null)))] : null,
  ]);
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
  return simplePage("FFCS result", "The classes you registered for next semester, as soon as VTOP has them.", "registered", (r) => (r && r.courses.length
    ? [h("div.strong", null, r.semester.name), plainTable({ headers: ["Code", "Course", "Type", "Slot", "Room", "Faculty", "Credits"], rows: r.courses.map((c) => [c.courseCode, c.courseName, c.courseType, c.slot, c.venue, titleCase(c.faculty), num(c.credits)]) })]
    : empty("VTOP shows no registered classes for next semester yet.")));
}

function screenHostel() {
  const slips = (l) => (l.length ? h("div.stack", null, l.map((x) => h("div.card", null, h("div.strong", null, x.purpose || x.venue), small([x.from, x.to, x.time, x.status].filter(Boolean).join(" · "))))) : empty("None."));
  const leaves = (l) => (l.length ? h("div.stack", null, l.map((x) => h("div.card", null, h("div.strong", null, x.place), small([x.type, x.from, x.to, x.status].filter(Boolean).join(" · "))))) : empty("None."));
  return simplePage("Hostel", "Applying stays on VTOP or the daVIT phone app.", "hostel", (d) => [
    h("h2", null, "Leave"), leaves(d.activeLeave), h("h2", null, "Night slips"), slips(d.activeSlips),
    h("h2", null, "Past leave"), leaves(d.pastLeave.slice(0, 20)), h("h2", null, "Past night slips"), slips(d.pastSlips.slice(0, 20)),
  ]);
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
  const draw = async () => {
    const book = await Reviews.load();
    chips.replaceChildren(...[["vellore", "Vellore"], ["chennai", "Chennai"]].map(([id, t]) => h("button.chip", { type: "button", class: id === campus ? "on" : "", onclick: () => { campus = id; draw(); } }, t)));
    const words = q.toLowerCase().split(" ").filter(Boolean);
    const found = book.faculty.filter((f) => f.campus === campus && (words.length ? words.every((w) => `${f.name} ${f.department} ${(f.reviews || []).map((r) => `${r.course} ${r.courseName}`).join(" ")}`.toLowerCase().includes(w)) : f.reviewCount > 0)).sort((a, b) => b.reviewCount - a.reviewCount);
    list.replaceChildren(...(found.length ? found.slice(0, 50).map((f) => h("button.card.click", { type: "button", onclick: () => go("review", { id: f.id }) }, h("div.row", null, h("div.grow", null, h("div.strong", null, f.name), small(f.department), small(f.reviewCount ? `${f.reviewCount} review${f.reviewCount > 1 ? "s" : ""}` : "No reviews yet")), f.reviewCount ? h("div.big", null, Number(f.rating).toFixed(1)) : null))) : [empty(`Nobody matches "${q}".`)]));
  };
  draw();
  return h("div.page", null, pageTitle("Faculty reviews", "Student ratings from No8AM. Works offline, and stops at 1 November 2026."), search("Name, department or course", (v) => { q = v; draw(); }), chips, list);
}

function screenReview(_, args) {
  const box = h("div.page");
  Reviews.load().then((book) => {
    const f = book.byId(args.id);
    if (!f) return box.replaceChildren(empty("That teacher isn't in the reviews."));
    box.append(...pageTitle(f.name, [f.designation, f.department].filter(Boolean).join(" · "), button("Back", () => history.back())).flat().filter(Boolean),
      h("div.card", { style: { marginBottom: "14px" } }, h("span.big", null, f.reviewCount ? Number(f.rating).toFixed(1) : "–"), ` from ${f.reviewCount} review${f.reviewCount === 1 ? "" : "s"}`),
      h("div.stack", null, (f.reviews || []).map((r) => h("div.card", null, h("div.row", null, h("div.grow.strong", null, [r.course, r.courseName].filter(Boolean).join(" · ")), h("span.badge", null, `${r.rating}/5`)), h("div", { style: { marginTop: "4px" } }, r.text), small(r.date || "")))));
  });
  return box;
}

// ---- settings -------------------------------------------------------------------------------------

function screenSettings(s) {
  const theme = localStore.get("theme", "light");
  const version = h("span", null, `Version ${App.version}`);
  api("latest_version").then((v) => { if (v && v !== App.version) version.append(" · ", button(`${v} is out`, () => api("open_link", { url: "https://github.com/medriid/davit/releases/latest" }))); }).catch(() => {});
  const confirmBox = h("div");
  return h("div.page", null, pageTitle("Settings", null),
    h("div.stack", { style: { maxWidth: "620px" } },
      h("div.card", null, label("Look"), h("div.tabs", { style: { marginBottom: 0 } }, [["light", "Light"], ["dark", "Dark"], ["system", "Follow system"]].map(([id, t]) => h("button.tab", { type: "button", class: id === theme ? "on" : "", onclick: () => { localStore.set("theme", id); applyTheme(); go("settings"); } }, t)))),
      s ? h("div.card", null, label("Semester"), h("div.strong", null, s.semester.name.replace(" - VLR", "")), small("daVIT follows your current semester by itself: the newest with a class held."), s.calendar?.group ? small(`Calendar: ${s.calendar.group}`) : null) : null,
      s?.profile ? h("div.card", null, label("Account"), h("div.strong", null, s.profile.name), s.profile.hostel ? small([s.profile.hostel.block, s.profile.hostel.room, s.profile.hostel.mess].filter(Boolean).join(" · ")) : null) : null,
      courseNamesCard(s),
      h("div.card", null, label("Privacy"), small("Your password stays in this computer's keychain and your data is encrypted on this computer. There's no server, account or analytics. daVIT only reads VTOP: applying, registering and paying stay on VTOP."),
        h("div", { style: { marginTop: "8px" } }, button("Sign out and erase", () => {
          confirmBox.replaceChildren(small("This erases your password and everything daVIT stored here."), h("div.row", { style: { marginTop: "6px" } }, button("Erase", async () => { await api("sign_out"); boot(); }, "solid"), button("Keep", () => confirmBox.replaceChildren())));
        })), confirmBox),
      h("div.card", null, label("About"), version, small("Campus map by Jeman (MIT) · map data © OpenStreetMap contributors · faculty reviews from No8AM · fonts Bagel Fat One and IBM Plex Mono (OFL)"), small("Apache-2.0. Not affiliated with VIT."))));
}
