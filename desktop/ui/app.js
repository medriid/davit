// The shell: sign-in, the sidebar, routes and syncing.
"use strict";

const App = { slots: new Map(), snapshot: null, version: "", route: "home", args: null, syncing: false };


const api = (cmd, args) => window.__TAURI__.core.invoke(cmd, args || {});

const localStore = {
  get(k, d) {
    try { return localStorage.getItem(k) ?? d; } catch { return d; }
  },
  set(k, v) {
    try { localStorage.setItem(k, v); } catch { /* the choice lasts this session */ }
  },
};

matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => applyTheme());

function toast(message) {
  const el = document.getElementById("progress");
  if (el) {
    el.hidden = false;
    el.textContent = message;
    setTimeout(() => { el.hidden = true; }, 5000);
  }
}

const NAV = [["home", "Home"], ["attendance", "Attendance"], ["academics", "Academics"], ["week", "Week"], ["map", "Navigation"], ["more", "More"]];
const SCREENS = {
  home: screenHome, attendance: screenAttendance, academics: screenAcademics, week: screenWeek, map: screenMap, more: screenMore,
  circulars: screenCirculars, windows: screenWindows, projects: screenProjects, messages: screenMessages, spotlight: screenSpotlight,
  events: screenEvents, clubs: screenClubs, sports: screenSports, curriculum: screenCurriculum, certificates: screenCertificates,
  rules: screenRules, money: screenMoney, gate: screenGate, signins: screenSignIns, ffcsresult: screenRegistered, hostel: screenHostel,
  reviews: screenReviews, review: screenReview, settings: screenSettings, settingsPage: screenSettingsPage, ffcs: screenFfcsModes, leave: screenLeave, mess: screenMess,
  assignment: screenAssignment, scanner: screenScanner, bonafide: screenBonafide, feedback: screenCourseFeedback, paperseeing: screenPaperSeeing,
  examboard: screenExamBoard, otherexams: screenOtherExams, corrections: screenCorrections, friends: screenFriends, course: screenCourse, search: screenSearch,
  downloads: screenDownloads, activities: screenActivities, allocation: screenAllocation, grades: screenSemesterGrades, people: screenPeople,
  notifications: screenNotifications, colours: screenColours, displaycard: screenDisplayCard, coursenames: screenCourseNames,
};

/** Screens that need no sync behind them. */
const STANDALONE = new Set(["map", "more", "reviews", "review", "settings", "settingsPage", "mess", "ffcs", "hostel", "events", "scanner", "bonafide", "feedback", "paperseeing", "downloads", "activities", "allocation", "people", "notifications", "colours", "displaycard", "coursenames"]);

/** Screens that redraw when a sync brings new data; the rest keep your place, your forms above all. */
const LIVE = new Set(["home", "attendance", "academics", "week", "examboard", "corrections"]);

function go(route, args, keep) {
  if (!keep) history.pushState({ route, args: args || null }, "");
  App.route = route;
  App.args = args || null;
  render();
}

addEventListener("popstate", (e) => {
  if (e.state?.route) go(e.state.route, e.state.args, true);
});

function syncLine() {
  const at = App.snapshot?.syncedAt;
  if (!at) return "Not synced yet";
  const mins = Math.floor((Date.now() / 1000 - at) / 60);
  return mins < 1 ? "Synced just now" : mins < 60 ? `Synced ${mins} min ago` : mins < 1440 ? `Synced ${Math.floor(mins / 60)} h ago` : `Synced ${Math.floor(mins / 1440)} d ago`;
}

function render() {
  const settings = ["settings", "settingsPage", "notifications", "colours", "displaycard", "coursenames"].includes(App.route);
  const route = NAV.some((n) => n[0] === App.route) ? App.route : settings ? "settings" : "more";
  const main = h("main.main", { id: "main" });
  const shell = h("div.shell", null,
    h("nav.side", { "aria-label": "Main" },
      h("div.brand", null, h("img", { src: "logo.png", alt: "" }), "daVIT"),
      NAV.map(([id, title]) => h("button.nav", { type: "button", class: id === route ? "on" : "", "aria-current": id === route ? "page" : null, onclick: () => go(id) }, title)),
      h("div.grow"),
      h("div.synced", { id: "synced" }, syncLine()),
      h("button.nav", { type: "button", id: "syncbtn", onclick: () => sync(true), disabled: App.syncing }, App.syncing ? "Syncing…" : "Sync now"),
      h("button.nav.withmascot", { type: "button", class: route === "settings" ? "on" : "", onclick: () => go("settings") }, h("span", { id: "sidemascot" }, mascotAvatar(Mascot.mine(), 34)), "Settings")),
    h("div", { style: { display: "flex", flexDirection: "column", minWidth: 0, minHeight: 0 } },
      h("div.progress", { id: "progress", hidden: true, role: "status" }),
      main));
  document.getElementById("app").replaceChildren(shell);
  const s = App.snapshot;
  if (!s && !STANDALONE.has(App.route)) {
    main.append(h("div.page", null, App.error ? h("div.err", null, App.error) : empty("Fetching your data from VTOP. The first sync takes a little while."), App.error ? button("Try again", () => sync(true)) : null));
    return;
  }
  try {
    main.append(SCREENS[App.route](Names.apply(s), App.args || {}));
  } catch (e) {
    main.append(h("div.page", null, h("div.err", null, `This screen hit a problem: ${e.message}`)));
    console.error(e);
  }
}

async function sync(everything) {
  if (App.syncing) return;
  App.syncing = true;
  App.error = null;
  const btn = document.getElementById("syncbtn");
  if (btn) { btn.disabled = true; btn.textContent = "Syncing…"; }
  const old = App.snapshot;
  App.stages = [];
  App.stageAt = performance.now();
  try {
    App.snapshot = await api("sync_now", { everything, choices: Choices.get() });
    App.syncing = false;
    App.timings = App.stages;
    App.noteChanges(old, App.snapshot);
    Alerts.post(Alerts.after(old, App.snapshot));
    Alerts.post(Corrections.fixed(old, App.snapshot).map((c) => ({ key: `fixed:${c.course}|${c.date}|${c.slot}`, title: "VTOP fixed your attendance", text: `${c.course.split("|")[0]} · ${dayLabel(c.date)} · ${c.slot}` })));
    App.tick();
    // Lists and forms keep their place unless the data under them is the point of the screen.
    if (LIVE.has(App.route) || !old) render();
    else document.getElementById("synced").textContent = syncLine();
  } catch (e) {
    App.syncing = false;
    App.error = String(e);
    if (App.snapshot) toast(String(e));
    else render();
  }
  const after = document.getElementById("syncbtn");
  if (after) { after.disabled = false; after.textContent = "Sync now"; }
  const bar = document.getElementById("progress");
  if (bar && !App.error) bar.hidden = true;
  // The first time there's data to set up around.
  if (App.snapshot && !old && localStore.get("setupDone", "") !== "1") screenSetup(App.snapshot, () => { history.replaceState({ route: "home", args: null }, ""); App.route = "home"; render(); });
}

/** Home's "Since you last looked": new marks, and each course whose count moved. An empty sync keeps the last list. */
App.noteChanges = (old, s) => {
  if (!old) return;
  const list = [];
  if (old.marks.length) {
    const seen = new Set(old.marks.flatMap((c) => c.items.filter((m) => m.scored != null).map((m) => `${c.classId}|${m.title}`)));
    for (const c of s.marks) for (const m of c.items) if (m.scored != null && !seen.has(`${c.classId}|${m.title}`)) list.push(`+ Mark: ${c.courseName} · ${m.title} · ${num(m.scored)}/${m.max != null ? num(m.max) : "?"}`);
  }
  const before = new Map(old.attendance.map((r) => [Rules.key(r), r]));
  for (const r of s.attendance) {
    const p = before.get(Rules.key(r));
    if (!p || (p.attended === r.attended && p.total === r.total)) continue;
    const what = r.total > p.total && r.total - p.total === r.attended - p.attended ? "present" : r.total > p.total && r.attended === p.attended ? "absent" : "updated";
    list.push(`${what === "absent" ? "−" : "+"} ${r.courseName}: ${what} · ${r.attended}/${r.total}`);
  }
  if (list.length) {
    Yours.data.changes = list;
    Yours.save().catch(() => {});
  }
};

/** What the tray's tooltip says, and whatever's due this minute. */
App.tick = () => {
  const s = App.snapshot;
  Alerts.post(Alerts.tick(s ? Names.apply(s) : null));
  if (!s) return;
  const now = new Date();
  const plan = planFor(weekOf(s, App.slots), now, calendarOf(s.calendar));
  const slot = plan.now || plan.next;
  const when = plan.now ? "Now" : plan.inDays === 0 ? "Next" : plan.inDays === 1 ? "Tomorrow" : plan.inDays ? `In ${plan.inDays} days` : "Next";
  const a = slot?.attendance;
  api("tray_text", { text: slot ? `${when}: ${slot.course.courseName} at ${Dates.clock(slot.meeting.start)}${slot.course.venue ? ` · ${slot.course.venue}` : ""}${a ? `\n${Rules.percent(a)}% attendance` : ""}` : "daVIT" }).catch(() => {});
};

function signIn(canSignIn) {
  const message = h("div.err", { role: "alert" });
  const step = h("div.small", { role: "status" });
  const submit = h("button.btn.solid.wide", { type: "submit" }, "Sign in");
  const form = h("form", { onsubmit: async (e) => {
    e.preventDefault();
    message.textContent = "";
    submit.disabled = true;
    submit.textContent = "Signing in…";
    step.textContent = "Solving VTOP's captcha here, then checking your password.";
    try {
      await api("sign_in", { username: form.elements.username.value, password: form.elements.password.value });
      form.elements.password.value = "";
      boot();
    } catch (err) {
      message.textContent = err;
      step.textContent = "";
      submit.disabled = false;
      submit.textContent = "Sign in";
    }
  } },
    h("div.row", { style: { gap: "12px", marginBottom: "22px" } }, h("img", { src: "logo.png", alt: "", style: { width: "52px", height: "52px", margin: 0 } }), h("div.wordmark", null, "daVIT")),
    h("h1.hero", { style: { marginBottom: "14px" } }, "Sign in with VTOP"),
    [["Stays on this computer", "Your password is sealed in this computer's keychain."], ["Talks only to VTOP", "No server of ours in between. The captcha is solved here."], ["No accounts, no tracking", "Nothing to sign up for. No analytics."]].map(([t, b]) => h("div.row", { style: { alignItems: "flex-start", padding: "4px 0" } }, h("span.strong", { style: { width: "22px" } }, "✦"), h("div.grow", null, h("div.strong", null, t), small(b)))),
    h("div.stack", { style: { marginTop: "18px" } },
      h("label", null, label("VTOP username"), h("input.field", { name: "username", required: true, autocomplete: "username", autofocus: true, spellcheck: "false", oninput: (e) => { e.target.value = e.target.value.replace(/\s/g, "").toUpperCase(); } })),
      h("label", null, label("VTOP password"), h("input.field", { name: "password", type: "password", required: true, autocomplete: "current-password" })),
      message, submit, step,
      h("button.btn.wide", { type: "button", onclick: () => screenWelcome(() => signIn(canSignIn)) }, "Change the look"),
      small(canSignIn ? "Wrong passwords aren't retried: VTOP locks accounts after a few failures." : "This build has no captcha model, so it can't sign in. Get daVIT from its releases page.")));
  document.getElementById("app").replaceChildren(h("div.signin", null, form));
}

async function boot() {
  applyTheme();
  await Yours.load();
  const st = await api("status");
  App.slots = parseSlots(st.slots);
  App.version = st.version;
  App.snapshot = st.snapshot || null;
  App.signedIn = st.signedIn;
  if (localStore.get("welcomed", "") !== "1") {
    await new Promise((resolve) => screenWelcome(resolve));
  }
  if (!st.signedIn) {
    signIn(st.canSignIn);
    return;
  }
  if (localStore.get("lockHash", "")) await Lock.ask();
  history.replaceState({ route: "home", args: null }, "");
  App.route = "home";
  MessBook.load().catch(() => {});
  Background.apply();
  if (localStore.get("savePages", "0") === "1") api("save_pages", { on: true }).catch(() => {});
  render();
  if (App.snapshot && localStore.get("setupDone", "") !== "1") screenSetup(App.snapshot, render);
  if (!App.listening) {
    App.listening = true;
    window.__TAURI__.event?.listen("sync-progress", (e) => {
      const at = performance.now();
      if (App.stages) {
        if (App.stages.length) App.stages.at(-1)[1] = Math.round(at - App.stageAt);
        App.stages.push([e.payload, 0]);
      }
      App.stageAt = at;
      const el = document.getElementById("progress");
      if (el && App.syncing) {
        el.hidden = false;
        el.textContent = e.payload;
      }
    });
    window.__TAURI__.event?.listen("tray-sync", () => sync(true));
    setInterval(() => {
      const el = document.getElementById("synced");
      if (el) el.textContent = syncLine();
      App.tick();
      if (Background.due(App.snapshot)) {
        sync(false);
        localStore.set("lastBackground", new Date().toLocaleString("en-GB", { weekday: "short", hour: "2-digit", minute: "2-digit" }));
      }
      Updates.daily();
    }, 60000);
  }
  App.tick();
  const age = App.snapshot ? Date.now() / 1000 - App.snapshot.syncedAt : Infinity;
  if (age > 15 * 60) sync(!App.snapshot);
}

boot().catch((e) => {
  document.getElementById("app").replaceChildren(h("div.page", null, h("div.err", null, String(e))));
});
