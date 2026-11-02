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

function applyTheme() {
  const t = localStore.get("theme", "light");
  const dark = t === "dark" || (t === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);

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
  reviews: screenReviews, review: screenReview, settings: screenSettings, ffcs: screenFfcs, leave: screenLeave, mess: screenMess,
};

/** Screens that need no sync behind them. */
const STANDALONE = new Set(["map", "more", "reviews", "review", "settings", "mess"]);

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
  const route = NAV.some((n) => n[0] === App.route) ? App.route : App.route === "settings" ? "settings" : App.route === "map" ? "map" : "more";
  const main = h("main.main", { id: "main" });
  const shell = h("div.shell", null,
    h("nav.side", { "aria-label": "Main" },
      h("div.brand", null, h("img", { src: "logo.png", alt: "" }), "daVIT"),
      NAV.map(([id, title]) => h("button.nav", { type: "button", class: id === route ? "on" : "", "aria-current": id === route ? "page" : null, onclick: () => go(id) }, title)),
      h("div.grow"),
      h("div.synced", { id: "synced" }, syncLine()),
      h("button.nav", { type: "button", id: "syncbtn", onclick: () => sync(true), disabled: App.syncing }, App.syncing ? "Syncing…" : "Sync now"),
      h("button.nav", { type: "button", class: route === "settings" ? "on" : "", onclick: () => go("settings") }, "Settings")),
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
  try {
    App.snapshot = await api("sync_now", { everything });
    App.syncing = false;
    // Lists and forms keep their place unless the data under them is the point of the screen.
    if (!["more", "map", "settings", "reviews", "review"].includes(App.route)) render();
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
}

function signIn(canSignIn) {
  const message = h("div.err", { role: "alert" });
  const submit = h("button.btn.solid", { type: "submit", style: { width: "100%", padding: "8px" } }, "Sign in");
  const form = h("form", { onsubmit: async (e) => {
    e.preventDefault();
    message.textContent = "";
    submit.disabled = true;
    submit.textContent = "Signing in…";
    try {
      await api("sign_in", { username: form.elements.username.value, password: form.elements.password.value });
      form.elements.password.value = "";
      boot();
    } catch (err) {
      message.textContent = err;
      submit.disabled = false;
      submit.textContent = "Sign in";
    }
  } },
    h("img", { src: "logo.png", alt: "" }),
    h("h1", { style: { marginBottom: "4px" } }, "daVIT"),
    h("div.sub", null, "VTOP, without the waiting."),
    h("div.stack", null,
      h("label", null, label("VTOP username"), h("input.field", { name: "username", required: true, autocomplete: "username", autofocus: true })),
      h("label", null, label("Password"), h("input.field", { name: "password", type: "password", required: true, autocomplete: "current-password" })),
      message,
      submit,
      small(canSignIn ? "Your password stays in this computer's keychain. The captcha is solved on this computer." : "This build has no captcha model, so it can't sign in. Get daVIT from its releases page.")));
  document.getElementById("app").replaceChildren(h("div.signin", null, form));
}

async function boot() {
  applyTheme();
  const st = await api("status");
  App.slots = parseSlots(st.slots);
  App.version = st.version;
  App.snapshot = st.snapshot || null;
  if (!st.signedIn) {
    signIn(st.canSignIn);
    return;
  }
  history.replaceState({ route: "home", args: null }, "");
  App.route = "home";
  render();
  window.__TAURI__.event?.listen("sync-progress", (e) => {
    const el = document.getElementById("progress");
    if (el && App.syncing) {
      el.hidden = false;
      el.textContent = e.payload;
    }
  });
  const age = App.snapshot ? Date.now() / 1000 - App.snapshot.syncedAt : Infinity;
  if (age > 15 * 60) sync(!App.snapshot ? true : false);
  setInterval(() => {
    const el = document.getElementById("synced");
    if (el) el.textContent = syncLine();
    if (App.snapshot && Date.now() / 1000 - App.snapshot.syncedAt > 6 * 3600) sync(false);
  }, 60000);
}

boot().catch((e) => {
  document.getElementById("app").replaceChildren(h("div.page", null, h("div.err", null, String(e))));
});
