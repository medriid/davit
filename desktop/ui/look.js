// How daVIT looks, and the first things you see: the ink pairs (the phone's, colour for colour),
// Classic or Editorial, your colours, your mascot, the display card, Welcome and Set up.
"use strict";

// ---- colour arithmetic --------------------------------------------------------------------------

const Colour = {
  rgb(hex) {
    const n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  },
  hex: (c) => `#${c.map((v) => Math.round(Math.min(255, Math.max(0, v))).toString(16).padStart(2, "0")).join("")}`,
  lerp(a, b, t) {
    const [x, y] = [Colour.rgb(a), Colour.rgb(b)];
    return Colour.hex(x.map((v, i) => v + (y[i] - v) * t));
  },
  lum(hex) {
    const [r, g, b] = Colour.rgb(hex).map((v) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  },
  contrast(a, b) {
    const [x, y] = [Colour.lum(a) + 0.05, Colour.lum(b) + 0.05];
    return Math.max(x, y) / Math.min(x, y);
  },
  /** A spot ink printed at a tone: less saturated, and no brighter than it allows. */
  toned(hex, tone) {
    if (tone.saturation >= 1 && tone.brightness >= 1) return hex;
    const [r, g, b] = Colour.rgb(hex).map((v) => v / 255);
    const mx = Math.max(r, g, b);
    const mn = Math.min(r, g, b);
    const d = mx - mn;
    let h = d === 0 ? 0 : mx === r ? 60 * (((g - b) / d) % 6) : mx === g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
    if (h < 0) h += 360;
    const s = (mx === 0 ? 0 : d / mx) * tone.saturation;
    const v = Math.min(mx, tone.brightness);
    const f = (n) => {
      const k = (n + h / 60) % 6;
      return (v - v * s * Math.max(0, Math.min(k, 4 - k, 1))) * 255;
    };
    return Colour.hex([f(5), f(3), f(1)]);
  },
  /** Moved towards black or white, whichever the paper sets off better, until it reads. */
  lifted(hex, paper, min) {
    if (Colour.contrast(hex, paper) >= min) return hex;
    const towards = Colour.contrast("#000000", paper) >= Colour.contrast("#ffffff", paper) ? "#000000" : "#ffffff";
    for (let i = 1; i <= 20; i++) {
      const c = Colour.lerp(hex, towards, i / 20);
      if (Colour.contrast(c, paper) >= min) return c;
    }
    return towards;
  },
  /** Text for a paper in the hue of an ink. */
  textFor(paper, ink) {
    const dark = Colour.contrast("#ffffff", paper) > Colour.contrast("#000000", paper);
    return Colour.lifted(dark ? Colour.lerp(ink, "#ffffff", 0.82) : Colour.lerp(ink, "#000000", 0.35), paper, 7);
  },
};

// ---- the inks -------------------------------------------------------------------------------------

const KINDS = [
  { id: "classes", title: "Classes", note: "Now and next, the week, your teachers", ink: "#0078BF", pastel: "#CDEFC4" },
  { id: "attendance", title: "Attendance", note: "Percentages, at-risk courses, VTOP remarks", ink: "#00A95C", pastel: "#C9E4FB" },
  { id: "assignments", title: "Assignments", note: "What's due, uploads, course materials", ink: "#FFB511", pastel: "#F9DBA9" },
  { id: "exams", title: "Exams and marks", note: "Exam dates, marks, grades and GPA", ink: "#FF48B0", pastel: "#D5CEF7" },
  { id: "reminders", title: "Reminders", note: "Your own reminders, registration", ink: "#FF6C2F", pastel: "#FFE590" },
  { id: "events", title: "Events", note: "Club events and certificates", ink: "#765BA7", pastel: "#F7C9DF" },
  { id: "people", title: "Messages and people", note: "Proctor, messages, faculty, Spot-light", ink: "#00838A", pastel: "#BFE8E3" },
  { id: "vtop", title: "VTOP pages", note: "Hostel, requests and records", ink: "#F65058", pastel: "#F8C7C2" },
];

const PAPER = "#F1E9DA";
const NIGHT_PAPER = "#1E1C19";
const DAY_TONE = { saturation: 0.72, brightness: 0.92, wash: 0.08 };
const NIGHT_TONE = { saturation: 0.6, brightness: 0.82, wash: 0.08 };
const NO_TONE = { saturation: 1, brightness: 1, wash: 0.12 };
const SPOTS = [
  { name: "Scarlet + Teal", a: "#F65058", b: "#00838A" },
  { name: "Sunflower + Federal Blue", a: "#FFB511", b: "#3D5588" },
  { name: "Fluorescent Pink + Blue", a: "#FF48B0", b: "#0078BF" },
  { name: "Bright Red + Black", a: "#F15060", b: "#2A2A2A", grey: true },
  { name: "Lime + Ink", a: "#D3F53B", b: "#2A2A2A", grey: true },
];

const PAIRS = {
  day: [
    ...SPOTS.map((p) => ({ name: p.name, paper: PAPER, a: Colour.toned(p.a, { saturation: 0.9, brightness: 1 }), b: p.b, text: "#161616", ruleAlpha: 0.3, tone: DAY_TONE, sticker: true })),
    { name: "Monochrome glass", paper: "#E5E5E5", a: "#141414", b: "#3A3A3A", text: "#111111", ruleAlpha: 0.22, tone: NO_TONE, glass: true },
    { name: "Editorial", paper: "#EFE8DA", a: "#D7261E", b: "#4A4945", text: "#111111", ruleAlpha: 0.16, tone: NO_TONE, editorial: true },
  ],
  night: [
    ...SPOTS.map((p) => ({
      name: p.name, paper: NIGHT_PAPER, a: Colour.toned(p.a, NIGHT_TONE),
      b: Colour.lifted(p.grey ? "#D9D6CF" : Colour.toned(p.b, { saturation: 0.75, brightness: 1 }), NIGHT_PAPER, 4),
      text: "#E8E2D6", ruleAlpha: 0.3, tone: NIGHT_TONE, dark: true,
    })),
    { name: "Monochrome glass", paper: "#0D0D0D", a: "#F2F2F2", b: "#CFCFCF", text: "#EDEDED", ruleAlpha: 0.22, tone: NO_TONE, dark: true, glass: true },
    { name: "Editorial", paper: "#111112", a: "#D44A3F", b: "#B9B6AF", text: "#ECEAE4", ruleAlpha: 0.12, tone: NO_TONE, dark: true, editorial: true },
  ],
};
const EDITORIAL = 6;
const CLASSIC = [0, 1, 2, 3, 4, 5];

const Look = {
  palette() {
    try {
      return { base: 0, kinds: {}, tinted: true, ...JSON.parse(localStore.get("palette", "{}")) };
    } catch {
      return { base: 0, kinds: {}, tinted: true };
    }
  },
  setPalette(p) {
    localStore.set("palette", JSON.stringify(p));
    applyTheme();
  },
  custom: (p) => !!(p.a || p.b || p.paper || p.nightPaper || p.text || p.nightText || Object.keys(p.kinds || {}).length),
  theme: () => localStore.get("theme", "system"),
  isDark() {
    const t = Look.theme();
    return t === "dark" || (t === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  },
  isEditorial: () => Look.palette().base === EDITORIAL,
  setEditorial(on) {
    const p = Look.palette();
    if (on === (p.base === EDITORIAL)) return;
    if (on) localStore.set("classicBase", String(p.base));
    Look.setPalette({ ...p, base: on ? EDITORIAL : Number(localStore.get("classicBase", "0")) });
  },

  /** The inks to print with, by day or at night: a pair and whatever you changed on top of it. */
  inks(p, dark) {
    const pair = (dark ? PAIRS.night : PAIRS.day)[Math.min(Math.max(p.base, 0), EDITORIAL)];
    const paperOwn = dark ? p.nightPaper : p.paper;
    const paper = paperOwn || pair.paper;
    const paperDark = Colour.lum(paper) <= 0.4;
    const a = p.a ? (dark ? Colour.toned(p.a, pair.tone) : p.a) : pair.a;
    const b = p.b ? (dark || paperDark ? Colour.lifted(p.b, paper, 3) : p.b) : Colour.lifted(pair.b, paper, 3);
    const textOwn = dark ? p.nightText : p.text;
    const text = textOwn || (!p.b && !paperOwn && Colour.contrast(pair.text, paper) >= 4.5 ? pair.text : Colour.textFor(paper, b));
    const inks = { ...pair, paper, a, b, text, dark: paperDark, tinted: p.tinted !== false, sticker: pair.sticker && !paperDark };
    inks.surface = pair.editorial ? (paperDark ? "#1B1B1D" : "#F8F3EA") : paperDark ? "#242424" : "#FFFFFF";
    inks.raised = paperDark ? "#26262A" : "#E4DCCB";
    inks.plates = {};
    inks.fills = {};
    for (const k of KINDS) {
      const yours = p.kinds?.[k.id];
      const plate = dark ? Colour.lifted(Colour.toned(yours || k.ink, pair.tone), paper, 2.2) : yours || Colour.toned(k.ink, pair.tone);
      const wash = inks.sticker ? (yours ? Colour.lerp(paper, yours, 0.42) : k.pastel) : Colour.lerp(paper, plate, pair.tone.wash);
      inks.fills[k.id] = pair.editorial ? inks.surface : pair.glass ? "" : inks.tinted ? wash : paper;
      inks.plates[k.id] = pair.editorial ? "transparent" : inks.sticker ? text : plate;
    }
    return inks;
  },
};

function applyTheme() {
  const p = Look.palette();
  const dark = Look.isDark();
  const i = Look.inks(p, dark);
  const root = document.documentElement;
  root.dataset.theme = i.dark ? "dark" : "light";
  root.dataset.style = i.editorial ? "editorial" : i.glass ? "glass" : "classic";
  const set = (k, v) => root.style.setProperty(k, v);
  const mix = (t) => Colour.lerp(i.paper, i.text, t);
  set("--paper", i.paper);
  set("--ink", i.text);
  set("--muted", mix(i.dark ? 0.62 : 0.66));
  set("--rule", i.text);
  set("--soft-rule", mix(i.ruleAlpha));
  set("--teal", i.b);
  set("--teal-ink", i.editorial ? i.text : Colour.lifted(i.b, i.paper, 4.5));
  set("--coral", i.a);
  set("--on-coral", Colour.contrast("#161616", i.a) >= Colour.contrast("#F8F3EA", i.a) ? "#161616" : "#F8F3EA");
  set("--on-teal", Colour.contrast("#161616", i.b) >= Colour.contrast("#F8F3EA", i.b) ? "#161616" : "#F8F3EA");
  set("--plain", i.editorial ? i.surface : i.paper);
  set("--plate", i.editorial ? "transparent" : i.sticker ? i.text : i.a);
  set("--bar", i.b);
  set("--surface", i.surface);
  set("--raised", i.raised);
  for (const k of KINDS) {
    set(`--${k.id}`, i.fills[k.id] || (i.dark ? "rgba(255,255,255,.07)" : "rgba(255,255,255,.55)"));
    set(`--plate-${k.id}`, i.plates[k.id]);
  }
}

// ---- the mascot -----------------------------------------------------------------------------------

const Mascot = {
  male: ["Tuft", "Spiky", "Quiff", "Curly", "Mohawk", "Buzz"],
  female: ["Long", "Bob", "Ponytail", "Buns", "Pigtails", "Pixie"],
  colours: [["Paper", "#F6F2E9"], ["Peach", "#F8CBA8"], ["Butter", "#F7DF8E"], ["Mint", "#BFE4CF"], ["Sky", "#BBD6EF"], ["Lilac", "#DCC8EE"]],
  parse(code) {
    const p = String(code || "").split(",").map(Number);
    if (p.length !== 4 || p.some(Number.isNaN)) return { female: false, hair: 0, colour: 0, extra: 0 };
    return { female: p[0] === 1, hair: Math.min(5, Math.max(0, p[1])), colour: Math.min(5, Math.max(0, p[2])), extra: Math.min(4, Math.max(0, p[3])) };
  },
  encode: (m) => `${m.female ? 1 : 0},${m.hair},${m.colour},${m.extra}`,
  mine: () => Mascot.parse(localStore.get("mascot", "")),
  extras: (m) => ["None", "Glasses", "Shades", "Headphones", m.female ? "Bow" : "Cap"],
};

/** The mascot in a paper circle with an ink ring. Always printed on paper: at night it would come out as a negative. */
function mascotAvatar(m, size, onclick) {
  const dpr = window.devicePixelRatio || 1;
  const c = h("canvas", { width: Math.round(size * dpr), height: Math.round(size * dpr), role: "img", "aria-label": "Your mascot", style: { width: `${size}px`, height: `${size}px` } });
  drawMascot(c.getContext("2d"), m, size * dpr);
  c.className = "mascot";
  if (!onclick) return c;
  return h("button.mascotbtn", { type: "button", "aria-label": "Profile and settings", onclick }, c);
}

function drawMascot(x, m, px) {
  const p = Look.palette();
  const inks = Look.inks(p, false);
  const u = px / 100;
  const body = Mascot.colours[m.colour][1];
  const text = inks.text;
  const line = 2.2 * u;
  const thin = 1.6 * u;
  x.save();
  x.lineCap = "round";
  x.lineJoin = "round";
  x.scale(u, u);
  const px1 = (w) => { x.lineWidth = w / u; };
  const path = (fn) => { const q = new Path2D(); fn(q); return q; };
  const oval = (q, l, t, r, b) => { q.moveTo(r, (t + b) / 2); q.ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2, 0, 0, 7); };
  const stroke = (q, w, c = text) => { px1(w); x.strokeStyle = c; x.stroke(q); };
  const fill = (q, c) => { x.fillStyle = c; x.fill(q); };
  const solid = (q, ink, off = false, w = thin) => {
    if (off) { x.save(); x.translate(2.2, 1.6); fill(q, ink); x.restore(); } else fill(q, ink);
    if (w) stroke(q, w);
  };
  const dot = (cx, cy, r, c) => { x.beginPath(); x.arc(cx, cy, r, 0, 7); x.fillStyle = c; x.fill(); };
  const cap = (q, top = 16) => {
    q.moveTo(22, 40); q.bezierCurveTo(20, top, 80, top, 78, 40);
    q.bezierCurveTo(70, 32, 58, 30, 50, 33); q.bezierCurveTo(42, 30, 30, 32, 22, 40); q.closePath();
  };

  x.fillStyle = inks.paper;
  x.fillRect(0, 0, 100, 100);
  x.globalCompositeOperation = "multiply";
  x.globalAlpha = 0.14;
  dot(50, 44, 36, inks.b);
  x.globalAlpha = 1;
  x.globalCompositeOperation = "source-over";

  // Behind the head: back hair, buns, pigtails, ponytail.
  if (m.female) {
    const back = {
      0: (q) => { q.moveTo(20, 44); q.bezierCurveTo(18, 10, 82, 10, 80, 44); q.lineTo(83, 82); q.bezierCurveTo(70, 88, 30, 88, 17, 82); q.closePath(); },
      1: (q) => { q.moveTo(19, 44); q.bezierCurveTo(17, 10, 83, 10, 81, 44); q.lineTo(83, 68); q.bezierCurveTo(72, 74, 28, 74, 17, 68); q.closePath(); },
      2: (q) => oval(q, 70, 18, 94, 62),
      3: (q) => { oval(q, 16, 6, 40, 30); oval(q, 60, 6, 84, 30); },
      4: (q) => { oval(q, 6, 42, 24, 80); oval(q, 76, 42, 94, 80); },
    }[m.hair];
    if (back) solid(path(back), inks.b);
    if (m.hair === 2) dot(75, 27, 3.2, inks.a);
    if (m.hair === 4) { dot(20, 46, 3, inks.a); dot(80, 46, 3, inks.a); }
  }

  // Body and collar.
  solid(path((q) => oval(q, 16, 74, 84, 128)), inks.a, true, line);
  stroke(path((q) => { q.moveTo(42, 75); q.lineTo(50, 82); q.lineTo(58, 75); }), thin);

  // Ears and head.
  for (const ex of [20, 80]) {
    const ear = path((q) => q.arc(ex, 47, 7, 0, 7));
    fill(ear, body);
    stroke(ear, line);
    x.globalAlpha = 0.35;
    dot(ex, 47, 3.2, inks.a);
    x.globalAlpha = 1;
  }
  const head = path((q) => q.roundRect(20, 20, 60, 54, 27));
  fill(head, body);
  stroke(head, line);

  // Front hair.
  const brow = (q) => { q.bezierCurveTo(70, 32, 58, 30, 50, 33); q.bezierCurveTo(42, 30, 30, 32, 22, 40); q.closePath(); };
  const poly = (q, pts) => { q.moveTo(pts[0], pts[1]); for (let i = 2; i < pts.length; i += 2) q.lineTo(pts[i], pts[i + 1]); };
  const front = m.female ? {
    1: (q) => { q.moveTo(23, 39); q.bezierCurveTo(21, 14, 79, 14, 77, 39); q.closePath(); },
    3: (q) => { q.moveTo(23, 39); q.bezierCurveTo(21, 14, 79, 14, 77, 39); q.closePath(); },
    4: (q) => { q.moveTo(22, 42); q.bezierCurveTo(20, 15, 80, 15, 78, 42); q.bezierCurveTo(70, 30, 56, 26, 50, 31); q.bezierCurveTo(44, 26, 30, 30, 22, 42); q.closePath(); },
    5: (q) => { q.moveTo(22, 40); q.bezierCurveTo(20, 13, 80, 13, 78, 36); q.bezierCurveTo(70, 30, 56, 30, 46, 36); q.bezierCurveTo(38, 32, 28, 34, 22, 40); q.closePath(); },
  }[m.hair] || ((q) => { q.moveTo(22, 45); q.bezierCurveTo(20, 15, 80, 15, 78, 45); q.bezierCurveTo(74, 32, 62, 26, 50, 27); q.bezierCurveTo(40, 28, 30, 34, 22, 45); q.closePath(); }) : {
    0: (q) => { cap(q); q.moveTo(47, 21); q.bezierCurveTo(44, 9, 59, 6, 61, 15); q.bezierCurveTo(56, 12, 52, 15, 53, 21); q.closePath(); },
    1: (q) => { poly(q, [22, 40, 21, 28, 29, 27, 29, 15, 38, 22, 44, 9, 51, 20, 59, 10, 63, 22, 72, 16, 72, 27, 79, 29, 78, 40]); brow(q); },
    2: (q) => { q.moveTo(22, 40); q.bezierCurveTo(19, 22, 33, 13, 50, 15); q.bezierCurveTo(62, 4, 85, 8, 82, 24); q.bezierCurveTo(78, 20, 72, 20, 67, 22); q.bezierCurveTo(75, 28, 79, 34, 78, 40); brow(q); },
    3: (q) => { for (const [cx, cy] of [[25, 37], [28, 27], [36, 20], [46, 16], [56, 16], [65, 20], [72, 27], [75, 37]]) oval(q, cx - 8, cy - 8, cx + 8, cy + 8); oval(q, 30, 17, 70, 36); },
    4: (q) => { poly(q, [43, 30, 40, 14, 46, 16, 47, 5, 53, 13, 57, 6, 58, 18, 61, 17, 58, 31]); q.closePath(); },
  }[m.hair];
  const capped = !m.female && m.extra === 4;
  if (!capped) {
    if (!m.female && m.hair >= 4) fill(path((q) => cap(q, 18)), Colour.lerp(body, inks.b, 0.45));
    if (front) solid(path(front), inks.b);
  }

  // Face.
  if (m.extra !== 2) {
    for (const ex of [39, 61]) {
      x.beginPath(); x.ellipse(ex, 46.25, 3.8, 4.75, 0, 0, 7); x.fillStyle = text; x.fill();
      dot(ex + 1.3, 44, 1.5, inks.paper);
    }
    if (m.female) {
      stroke(path((q) => { q.moveTo(35.6, 43); q.lineTo(32.4, 40.6); q.moveTo(64.4, 43); q.lineTo(67.6, 40.6); }), thin);
    }
  }
  x.globalCompositeOperation = "multiply";
  x.globalAlpha = 0.5;
  for (const ex of [30, 70]) { x.beginPath(); x.ellipse(ex, 56.5, 4.5, 2.5, 0, 0, 7); x.fillStyle = inks.a; x.fill(); }
  x.globalAlpha = 1;
  x.globalCompositeOperation = "source-over";
  stroke(path((q) => q.ellipse(50, 56.5, 5, 4.5, 0, (20 * Math.PI) / 180, (160 * Math.PI) / 180)), line);

  // Extras.
  if (m.extra === 1) {
    for (const ex of [39, 61]) stroke(path((q) => q.arc(ex, 46.5, 8, 0, 7)), thin);
    stroke(path((q) => { q.moveTo(47, 46); q.lineTo(53, 46); }), thin);
  } else if (m.extra === 2) {
    fill(path((q) => { q.moveTo(29, 41); q.lineTo(48, 41); q.bezierCurveTo(48, 54, 30, 54, 29, 41); q.closePath(); q.moveTo(52, 41); q.lineTo(71, 41); q.bezierCurveTo(70, 54, 52, 54, 52, 41); q.closePath(); }), text);
    stroke(path((q) => { q.moveTo(26, 41); q.lineTo(74, 41); }), 2.4);
    stroke(path((q) => { q.moveTo(33, 44); q.lineTo(37, 44); q.moveTo(56, 44); q.lineTo(60, 44); }), 1.4, inks.paper);
  } else if (m.extra === 3) {
    stroke(path((q) => q.ellipse(50, 47, 35, 37, 0, Math.PI, 2 * Math.PI)), 3.4);
    for (const ex of [13, 77]) {
      const cup = path((q) => q.roundRect(ex, 37, 10, 20, 4));
      fill(cup, inks.a);
      stroke(cup, line);
    }
  } else if (m.extra === 4) {
    if (m.female) {
      solid(path((q) => { q.moveTo(66, 23); q.lineTo(57, 15); q.lineTo(58, 31); q.closePath(); q.moveTo(66, 23); q.lineTo(75, 15); q.lineTo(74, 31); q.closePath(); }), inks.a);
      dot(66, 23, 2.3, text);
    } else {
      solid(path((q) => { q.moveTo(22, 34); q.bezierCurveTo(22, 6, 78, 6, 78, 34); q.closePath(); }), inks.a, false, line);
      stroke(path((q) => { q.moveTo(20, 34); q.lineTo(92, 34); }), 3.4);
      dot(50, 12, 2, text);
    }
  }
  x.restore();
}

/** Chips where the chosen one is solid. */
function choiceRow(options, selected, onselect) {
  return h("div.chips", { role: "radiogroup" }, options.map((t, i) => h("button.chip", { type: "button", role: "radio", "aria-checked": i === selected, class: i === selected ? "on" : "", onclick: () => onselect(i) }, t)));
}

/** The editor, top to bottom: preview and type, colour, hair, extra. */
function mascotEditor(onchange) {
  const box = h("div.stack");
  const draw = () => {
    const m = Mascot.mine();
    const set = (next) => { localStore.set("mascot", Mascot.encode(next)); draw(); onchange?.(); };
    box.replaceChildren(
      h("div.row", { style: { gap: "18px" } }, mascotAvatar(m, 104),
        h("div", null, label("Type"), h("div", { style: { marginTop: "6px" } }, choiceRow(["Male", "Female"], m.female ? 1 : 0, (i) => set({ ...m, female: i === 1 }))))),
      h("div", null, label("Colour"), h("div.chips", { style: { marginTop: "6px" } }, Mascot.colours.map(([name, c], i) => h("button.swatch", { type: "button", "aria-label": name, title: name, class: i === m.colour ? "on" : "", style: { background: c }, onclick: () => set({ ...m, colour: i }) })))),
      h("div", null, label("Hair"), h("div", { style: { marginTop: "6px" } }, choiceRow(m.female ? Mascot.female : Mascot.male, m.hair, (i) => set({ ...m, hair: i })))),
      h("div", null, label("Extra"), h("div", { style: { marginTop: "6px" } }, choiceRow(Mascot.extras(m), m.extra, (i) => set({ ...m, extra: i })))));
  };
  draw();
  return box;
}

// ---- the display card -----------------------------------------------------------------------------

const BannerStats = [["none", "Nothing"], ["exam", "Next exam"], ["attendance", "Attendance"], ["risk", "At risk"], ["due", "Due soon"], ["reminders", "Reminders"], ["days", "Teaching days left"], ["cgpa", "CGPA"]];
const BANNER_STYLES = ["Waves", "Sun", "Confetti", "Blocks", "Stripes", "Halftone"];

const Banner = {
  get() {
    try {
      return { style: 0, photo: false, focus: 0.5, left: "exam", right: "due", nameInBar: false, hidden: false, ...JSON.parse(localStore.get("banner", "{}")) };
    } catch {
      return { style: 0, photo: false, focus: 0.5, left: "exam", right: "due", nameInBar: false, hidden: false };
    }
  },
  set: (b) => localStore.set("banner", JSON.stringify(b)),
  text(stat, s) {
    const today = Dates.addDays(new Date(), 0);
    switch (stat) {
      case "exam": {
        const e = s.exams.map((x) => ({ x, d: Dates.vtop(x.date) })).filter((y) => y.d && y.d >= today).sort((a, b) => a.d - b.d)[0];
        if (!e) return null;
        const days = Dates.between(today, e.d);
        return `${e.x.type} ${days === 0 ? "today" : days === 1 ? "tomorrow" : `in ${days} days`}`;
      }
      case "attendance": { const o = Rules.overall(s.attendance); return o == null ? null : `${o}% overall`; }
      case "risk": return Rules.exempt(s) ? "75% rule: exempt" : `${s.attendance.filter(Rules.atRisk).length} at risk`;
      case "due": {
        const n = s.assignments.flatMap((c) => c.items).filter((a) => { const d = Dates.vtop(a.due); return !a.updated && d && d >= today && Dates.between(today, d) <= 7; }).length;
        return n ? `${n} due this week` : "Nothing due this week";
      }
      case "reminders": { const n = (Yours.data.reminders || []).filter((r) => new Date(`${r.date}T00:00`) >= today).length; return `${n} reminder${n === 1 ? "" : "s"}`; }
      case "days": { const d = calendarOf(s.calendar)?.daysLeft(new Date()); return d == null ? null : `${d} teaching day${d === 1 ? "" : "s"} left`; }
      case "cgpa": return s.grades?.cgpa != null ? `CGPA ${(Math.floor(s.grades.cgpa * 100) / 100).toFixed(2)}` : null;
      default: return null;
    }
  },
};

/** The built-in prints, drawn in your inks and card colours so they follow your theme. */
function drawArt(c, style) {
  const rect = c.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  const W = Math.max(1, rect.width);
  const H = Math.max(1, rect.height);
  if (c.width !== Math.round(W * dpr) || c.height !== Math.round(H * dpr)) {
    c.width = Math.round(W * dpr);
    c.height = Math.round(H * dpr);
  }
  const x = c.getContext("2d");
  const inks = Look.inks(Look.palette(), Look.isDark());
  x.setTransform(dpr, 0, 0, dpr, 0, 0);
  const ramp = [inks.surface, inks.raised, Colour.lerp(inks.surface, inks.a, 0.2)];
  const soft = inks.editorial ? KINDS.map((_, i) => ramp[i % 3]) : KINDS.map((k) => (inks.fills[k.id] && inks.fills[k.id] !== inks.paper ? inks.fills[k.id] : Colour.lerp(inks.paper, inks.plates[k.id] === inks.text ? k.ink : inks.plates[k.id], 0.35)));
  const rectf = (col, l = 0, t = 0, w = W, h2 = H) => { x.fillStyle = col; x.fillRect(l, t, w, h2); };
  x.lineWidth = 2;
  x.strokeStyle = inks.text;
  if (style === 0) {
    rectf(soft[1]);
    [0.3, 0.5, 0.7, 0.88].forEach((base, i) => {
      x.beginPath();
      x.moveTo(0, H);
      x.lineTo(0, H * base);
      for (let px = 0; px <= W; px += 6) x.lineTo(px, H * base + Math.sin((px / W) * 2 * Math.PI * (1.3 + i * 0.4) + i) * H * 0.06);
      x.lineTo(W, H);
      x.closePath();
      x.fillStyle = [soft[2], soft[0], soft[3], soft[5]][i];
      x.fill();
      x.stroke();
    });
  } else if (style === 1) {
    rectf(soft[6]);
    const cx = W * 0.72;
    const cy = H * 0.62;
    for (let r = 1; r <= 6; r++) { x.globalAlpha = (0.12 * (7 - r)) / 6; x.beginPath(); x.arc(cx, cy, H * (0.32 + r * 0.12), 0, 7); x.fillStyle = inks.a; x.fill(); }
    x.globalAlpha = 1;
    x.beginPath(); x.arc(cx, cy, H * 0.32, 0, 7); x.fillStyle = inks.a; x.fill(); x.stroke();
    rectf(soft[0], 0, H * 0.82, W, H * 0.18);
    x.beginPath(); x.moveTo(0, H * 0.82); x.lineTo(W, H * 0.82); x.stroke();
  } else if (style === 2) {
    rectf(soft[4]);
    let seed = 11;
    const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
    for (let i = 0; i < Math.max(26, W / 28); i++) {
      const ox = rnd() * W;
      const oy = rnd() * H;
      const r = (6 + Math.floor(rnd() * 14)) / 2;
      const col = [inks.a, inks.b, soft[3], soft[5], soft[0]][Math.floor(rnd() * 5)];
      const kind = Math.floor(rnd() * 3);
      x.beginPath();
      if (kind === 0) { x.arc(ox, oy, r, 0, 7); x.fillStyle = col; x.fill(); }
      else if (kind === 1) { x.arc(ox, oy, r, 0, 7); x.stroke(); }
      else { x.moveTo(ox, oy); x.lineTo(ox + r * 2, oy - r); x.save(); x.lineWidth = 3; x.strokeStyle = col; x.stroke(); x.restore(); }
    }
  } else if (style === 3) {
    const cols = [0, 0.28, 0.55, 0.8, 1];
    const rows = [0, 0.45, 1];
    let k = 0;
    for (let r = 0; r < 2; r++) for (let q = 0; q < 4; q++) {
      const [l, t, w2, h2] = [W * cols[q], H * rows[r], W * (cols[q + 1] - cols[q]), H * (rows[r + 1] - rows[r])];
      rectf(k === 5 ? inks.a : soft[(k * 3) % soft.length], l, t, w2, h2);
      x.strokeRect(l, t, w2, h2);
      k++;
    }
  } else if (style === 4) {
    rectf(soft[2]);
    x.fillStyle = soft[7];
    for (let px = -H, i = 0; px < W; px += 22, i++) {
      if (i % 2) continue;
      x.beginPath(); x.moveTo(px, H); x.lineTo(px + 22, H); x.lineTo(px + 22 + H, 0); x.lineTo(px + H, 0); x.closePath(); x.fill();
    }
  } else {
    rectf(inks.paper);
    x.fillStyle = inks.a;
    for (let y = 6; y < H; y += 12) for (let px = 6; px < W; px += 12) { x.beginPath(); x.arc(px, y, 12 * 0.48 * (px / W), 0, 7); x.fill(); }
  }
}

/** Your photo, cropped to the card at its focus, or the chosen built-in print. */
function bannerBackground(b, cls = "bannerart") {
  if (b.photo && Yours.data.bannerPhoto) return h(`img.${cls}`, { src: Yours.data.bannerPhoto, alt: "", style: { objectFit: "cover", objectPosition: `50% ${b.focus * 100}%` } });
  const c = h(`canvas.${cls}`, { "aria-hidden": "true" });
  new ResizeObserver(() => drawArt(c, b.style)).observe(c);
  return c;
}

function displayCard(s, compact) {
  const b = Banner.get();
  const m = Mascot.mine();
  const name = s?.profile?.name ? titleCase(s.profile.name) : "";
  const title = b.nameInBar && name ? name.split(" ")[0] : "daVIT";
  const chip = (id) => (s && id !== "none" && id !== "attendance" ? Banner.text(id, s) : null);
  const chips = [chip(b.left), chip(b.right)].filter(Boolean);
  if (b.hidden) return h("div.row", { style: { marginBottom: "14px" } }, h("h1.grow", { style: { textTransform: "none" } }, title), mascotAvatar(m, 44, () => go("settings")));
  return h("div.displaycard", { style: compact ? { marginBottom: 0 } : null },
    bannerBackground(b),
    h("div.displaytop", null,
      h("div.displayname", null, title),
      h("div.grow"),
      mascotAvatar(m, 54, () => go("settings"))),
    chips.length ? h("div.displaychips", null, chips.map((t) => h("span.statchip", null, t))) : null);
}

// ---- style picker and sample ----------------------------------------------------------------------

/** Classic or Editorial, each as a small drawing of its Home. The app switches the moment you pick. */
function stylePicker(onpick) {
  const drawing = (editorial) => {
    const c = h("canvas", { width: 240, height: 118, "aria-hidden": "true", style: { width: "100%", height: "118px", display: "block", borderRadius: "6px" } });
    const x = c.getContext("2d");
    const dark = Look.isDark();
    const p = Look.inks({ base: editorial ? EDITORIAL : 0, kinds: {}, tinted: true }, dark);
    const X = 2.4;
    const Y = 1.18;
    x.fillStyle = p.paper;
    x.fillRect(0, 0, 240, 118);
    if (editorial) {
      x.fillStyle = p.text;
      x.fillRect(8 * X, 8 * Y, 62 * X, 10 * Y);
      x.fillRect(8 * X, 21 * Y, 34 * X, 10 * Y);
      x.fillRect(8 * X, 37 * Y, 84 * X, 1.6 * Y);
      for (const col of [0, 1]) {
        const l = (8 + col * 43) * X;
        x.fillStyle = p.surface;
        x.fillRect(l, 44 * Y, 41 * X, 30 * Y);
        x.strokeStyle = Colour.lerp(p.paper, p.text, p.ruleAlpha);
        x.strokeRect(l, 44 * Y, 41 * X, 30 * Y);
        x.fillStyle = p.text;
        x.fillRect(l + 5 * X, 58 * Y, 16 * X, 11 * Y);
      }
      x.fillStyle = p.a;
      x.fillRect(8 * X, 80 * Y, 84 * X, 13 * Y);
    } else {
      const card = (top, bottom, k) => {
        x.fillStyle = p.text;
        x.beginPath(); x.roundRect(8 * X + 5, top * Y + 5, 80 * X, (bottom - top) * Y, 9); x.fill();
        x.fillStyle = p.fills[k];
        x.beginPath(); x.roundRect(8 * X, top * Y, 80 * X, (bottom - top) * Y, 9); x.fill();
        x.strokeStyle = p.text; x.lineWidth = 2; x.stroke();
      };
      card(8, 56, "classes");
      x.fillStyle = p.text;
      x.fillRect(15 * X, 16 * Y, 28 * X, 8 * Y);
      x.globalAlpha = 0.5;
      x.fillRect(15 * X, 29 * Y, 46 * X, 5 * Y);
      x.globalAlpha = 1;
      x.fillStyle = p.a;
      x.fillRect(15 * X, 41 * Y, 56 * X, 7 * Y);
      card(68, 94, "exams");
      x.fillStyle = p.text;
      x.fillRect(15 * X, 76 * Y, 22 * X, 7 * Y);
    }
    return c;
  };
  const editorial = Look.isEditorial();
  const option = (on, name, line, kind) => h("button.styleoption", { type: "button", role: "radio", "aria-checked": on, class: on ? "on" : "", onclick: () => { Look.setEditorial(kind); onpick(); } }, drawing(kind), h("div.strong", { style: { marginTop: "8px", fontSize: "16px" } }, name), small(line));
  return h("div.styleoptions", { role: "radiogroup", "aria-label": "Style" },
    option(!editorial, "Classic", "Printed cards in the colours you pick", false),
    option(editorial, "Editorial", "A Swiss grid of big numbers and live tiles", true));
}

function inkSwatches(onpick) {
  const p = Look.palette();
  const dark = Look.isDark();
  return h("div.chips", null, CLASSIC.map((i) => {
    const pair = (dark ? PAIRS.night : PAIRS.day)[i];
    return h("button.pairchip", { type: "button", title: pair.name, "aria-label": pair.name, class: !Look.custom(p) && i === p.base ? "on" : "", style: { background: pair.paper }, onclick: () => onpick(i) },
      h("span", { style: { background: pair.a } }), h("span", { style: { background: pair.b } }));
  }));
}

const THEMES = [["system", "System"], ["light", "Light"], ["dark", "Dark"]];

function themeRow(redraw) {
  const t = Look.theme();
  return choiceRow(THEMES.map((x) => x[1]), Math.max(0, THEMES.findIndex((x) => x[0] === t)), (i) => { localStore.set("theme", THEMES[i][0]); applyTheme(); redraw(); });
}

function sampleCard() {
  const r = { attended: 41, total: 50 };
  return h("div.card.classes", null,
    h("div.row", null,
      h("div.grow", null, label("Now"), h("div.strong", { style: { fontSize: "18px" } }, "Applied Science"), small("10:00 – 10:50 · SJT-401")),
      mascotAvatar(Mascot.mine(), 52)),
    attendanceBar(r),
    h("div.row", null, h("div.small.strong.grow", null, "82% · can skip 3"), h("span.badge.on", null, "Live")));
}

// ---- welcome and set up ---------------------------------------------------------------------------

/** Before signing in: make daVIT yours. Everything here stays in Settings afterwards. */
function screenWelcome(onContinue) {
  const box = h("div.welcome");
  const draw = () => {
    const editorial = Look.isEditorial();
    box.replaceChildren(
      h("div.row", { style: { gap: "12px" } }, h("img", { src: "logo.png", alt: "", style: { width: "48px", height: "48px" } }), h("div.wordmark", null, "daVIT")),
      h("h1.hero", null, "Welcome"),
      h("p", { style: { margin: "6px 0 18px" } }, "Your attendance, classes, marks and the campus map, from VTOP. First, pick how it looks."),
      h("div.welcomegrid", null,
        h("div.stack", null,
          label("Style"),
          stylePicker(draw),
          label("Light or dark"),
          themeRow(draw),
          editorial ? null : [label("Colours"), inkSwatches((i) => { Look.setPalette({ base: i, kinds: {}, tinted: true }); draw(); }),
            small(`${(Look.isDark() ? PAIRS.night : PAIRS.day)[Look.palette().base].name}. Any colour for every ink and card later, in Settings.`)]),
        h("div.stack", null,
          label("How it prints"),
          sampleCard(),
          label("Your mascot"),
          mascotEditor(() => draw()))),
      h("div", { style: { marginTop: "22px", maxWidth: "420px" } },
        h("button.btn.solid.wide", { type: "button", onclick: () => { localStore.set("welcomed", "1"); onContinue(); } }, App.signedIn ? "Continue" : "Continue to sign in"),
        small("You can change all of this any time: your mascot, then Look.")));
  };
  draw();
  document.getElementById("app").replaceChildren(h("div.fullpage", null, box));
}

function toggleRow(title, body, on, ontoggle) {
  return h("button.settingrow", { type: "button", role: "switch", "aria-checked": on, onclick: ontoggle },
    h("div.grow", null, h("div.strong", null, title), small(body)),
    h("span.badge", { class: on ? "on" : "" }, on ? "On" : "Off"));
}

function settingRow(title, body, action, onclick) {
  return h("div.settingrow", null, h("div.grow", null, h("div.strong", null, title), small(body)), action ? button(action, onclick) : null);
}

function settingsCard(title, ...kids) {
  return h("div.card.settingscard", null, title ? label(title) : null, ...kids);
}

/** Once, after the first sync: the few things worth deciding now, each one optional. */
function screenSetup(s, onDone) {
  const box = h("div.welcome", { style: { maxWidth: "640px" } });
  const draw = () => {
    const p = AlertPrefs.get();
    box.replaceChildren(
      h("h1.hero", null, "Set up"),
      h("p", { style: { margin: "6px 0 14px" } }, "A few things, each one optional. They're all in Settings later."),
      h("div.stack", null,
        settingsCard("Notifications",
          toggleRow("Let daVIT notify you", "Low attendance, new marks, what's due and exams. Nothing else.", p.on, () => { AlertPrefs.set({ ...p, on: !p.on }); draw(); })),
        settingsCard("Sync",
          toggleRow("Sync in the background", "About every 6 hours while the window is closed, never between 1 and 6 am. daVIT stays in the tray.", Background.on(), () => { Background.set(!Background.on()); draw(); }),
          autostartRow(draw)),
        settingsCard("Before class",
          toggleRow("Remind me before class", "10 minutes before, with the room, for courses near the 75% line.", p.beforeClass > 0, () => { AlertPrefs.set({ ...p, beforeClass: p.beforeClass > 0 ? 0 : 10, beforeClassNearLine: true }); draw(); })),
        s && s.semesters.length > 1 ? settingsCard("Semester", small(`Showing ${s.semester.name.replace(" - VLR", "")}.`),
          h("div", { style: { marginTop: "6px" } }, choiceRow(s.semesters.slice(0, 3).map((x) => x.name.split(" - ")[0]), s.semesters.slice(0, 3).findIndex((x) => x.id === s.semester.id), (i) => {
            const id = s.semesters[i].id;
            if (id !== s.semester.id) { Choices.setSemester(id); sync(true).then(() => { s = App.snapshot; draw(); }); }
          }))) : null),
      h("div", { style: { marginTop: "20px" } }, h("button.btn.solid.wide", { type: "button", onclick: () => { localStore.set("setupDone", "1"); onDone(); } }, "Done")));
  };
  draw();
  document.getElementById("app").replaceChildren(h("div.fullpage", null, box));
}

function autostartRow(redraw) {
  const row = h("div");
  api("autostart_on").then((on) => {
    row.replaceChildren(toggleRow("Start with this computer", "Opens quietly in the tray when you log in.", on, async () => {
      try {
        await api("set_autostart", { on: !on });
      } catch (e) {
        toast(e);
      }
      redraw();
    }));
  }).catch(() => {});
  return row;
}

// ---- your own colours -----------------------------------------------------------------------------

const RISO_INKS = [["Scarlet", "#F65058"], ["Bright Red", "#F15060"], ["Raspberry", "#D1517A"], ["Fluorescent Pink", "#FF48B0"], ["Orange", "#FF6C2F"], ["Sunflower", "#FFB511"], ["Green", "#00A95C"], ["Teal", "#00838A"], ["Blue", "#0078BF"], ["Federal Blue", "#3D5588"], ["Violet", "#765BA7"], ["Black", "#2A2A2A"]];

/** Any colour for every ink and kind of card. The app changes as you pick. */
function screenColours() {
  const page = h("div.page");
  let night = Look.isDark();
  let startingOver = null;
  let editing = null;
  const draw = () => {
    const p = Look.palette();
    const day = Look.inks(p, false);
    const dark = Look.inks(p, true);
    const roles = [
      ["a", "Primary", "Plates, big numbers, the seal", day.a, !!p.a],
      ["b", "Secondary", "Headings, chips, buttons, lines", day.b, !!p.b],
      [night ? "nightPaper" : "paper", night ? "Night paper" : "Paper", night ? "The page at night" : "The page by day", (night ? dark : day).paper, !!(night ? p.nightPaper : p.paper)],
      [night ? "nightText" : "text", night ? "Text at night" : "Text", "Body text and borders", (night ? dark : day).text, !!(night ? p.nightText : p.text)],
    ];
    const setRole = (key, kind, value) => {
      const next = { ...Look.palette(), kinds: { ...(Look.palette().kinds || {}) } };
      if (kind) { if (value) next.kinds[key] = value; else delete next.kinds[key]; } else if (value) next[key] = value; else delete next[key];
      Look.setPalette(next);
    };
    const row = (key, title, note, colour, own, kind) => [
      h("button.settingrow", { type: "button", "aria-label": `Change ${title}`, onclick: () => { editing = editing === key ? null : key; draw(); } },
        h("span.bigswatch", { style: { background: colour } }),
        h("div.grow", null, h("div.strong", null, title), small(note)),
        h("div.right", null, h("div.small.strong", null, colour.toUpperCase()), own ? null : small(key.toLowerCase().includes("text") ? "auto" : "default"))),
      editing === key ? h("div.colourpick", null,
        h("input", { type: "color", value: colour, "aria-label": title, oninput: (e) => setRole(key, kind, e.target.value) , onchange: () => draw() }),
        h("input.field", { value: colour.toUpperCase(), "aria-label": `${title} as a hex code`, style: { width: "110px" }, onchange: (e) => { const v = e.target.value.trim(); if (/^#?[0-9a-f]{6}$/i.test(v)) { setRole(key, kind, v.startsWith("#") ? v : `#${v}`); draw(); } else e.target.value = colour.toUpperCase(); } }),
        h("div.chips", null, RISO_INKS.map(([n, c]) => h("button.swatch", { type: "button", title: n, "aria-label": n, style: { background: c }, onclick: () => { setRole(key, kind, c); draw(); } }))),
        button("Back to default", () => { setRole(key, kind, null); editing = null; draw(); })) : null,
    ];
    page.replaceChildren(...[
      pageTitle("Colours", "Any colour for every ink and kind of card. The app changes as you pick.", button("Back", () => go("settingsPage", { group: "look" }))),
      h("div.row", null, h("div.grow", null, label("Show")), choiceRow(["Day", "Night"], night ? 1 : 0, (i) => { night = i === 1; localStore.set("theme", night ? "dark" : "light"); applyTheme(); draw(); })),
      h("h2", null, "Inks"),
      h("div.card", null, roles.map((r) => row(...r, false))),
      h("h2", null, "Cards"),
      h("div.card", null,
        toggleRow("Tinted cards", "Each card's paper takes a wash of its colour, not just the plate behind it.", p.tinted !== false, () => { Look.setPalette({ ...p, tinted: p.tinted === false }); draw(); }),
        KINDS.map((k) => row(k.id, k.title, k.note, p.kinds?.[k.id] || k.ink, !!p.kinds?.[k.id], true))),
      h("h2", null, "Start from"),
      small("A pair to build on. Starting over drops the colours you changed."),
      h("div", { style: { marginTop: "8px" } }, inkSwatches((i) => {
        if (Look.custom(p)) { startingOver = i; draw(); return; }
        Look.setPalette({ base: i, kinds: {}, tinted: p.tinted });
        draw();
      })),
      startingOver != null ? h("div.row", { style: { marginTop: "10px" } }, h("div.strong", null, `Start over from ${PAIRS.day[startingOver].name}?`),
        button("Start over", () => { Look.setPalette({ base: startingOver, kinds: {}, tinted: p.tinted }); startingOver = null; draw(); }, "solid"), button("Keep mine", () => { startingOver = null; draw(); })) : null,
    ].flat(Infinity).filter(Boolean));
  };
  draw();
  return page;
}

/** Make the top bar yours: a print or your own photo, its two stats, your name. Nothing leaves this computer. */
function screenDisplayCard(s) {
  const page = h("div.page");
  const fileInput = h("input", { type: "file", accept: "image/*", hidden: true, onchange: async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    try {
      Yours.data.bannerPhoto = await shrinkImage(f, 1600, 0.82);
      await Yours.save();
      Banner.set({ ...Banner.get(), photo: true });
      draw();
    } catch {
      toast("Couldn't read that image.");
    }
  } });
  const draw = () => {
    const b = Banner.get();
    const set = (next) => { Banner.set(next); draw(); };
    const stats = BannerStats.filter((x) => x[0] !== "attendance");
    const editorial = Look.isEditorial();
    page.replaceChildren(...[
      pageTitle("Display card", editorial ? "The picture at the top of Home: a print or your own photo. Nothing leaves this computer." : "The top bar on Home, printed on a background of your choice. Nothing leaves this computer.", button("Back", () => history.back())),
      editorial ? editorialPicture(s) : displayCard(s),
      h("h2", null, "Background"),
      h("div.grid3", null, BANNER_STYLES.map((name, i) => h("button.artoption", { type: "button", role: "radio", "aria-checked": !b.photo && b.style === i, class: !b.photo && b.style === i ? "on" : "", onclick: () => set({ ...b, style: i, photo: false }) }, bannerBackground({ style: i, photo: false }, "artthumb"), small(name)))),
      h("div.row.wrap", { style: { marginTop: "10px" } }, button(b.photo ? "Another photo" : "Your own photo", () => fileInput.click()),
        b.photo ? button("Remove photo", async () => { delete Yours.data.bannerPhoto; await Yours.save(); set({ ...b, photo: false }); }) : null),
      fileInput,
      b.photo ? [h("h2", null, "Which part of the photo"), choiceRow(["Top", "Middle", "Bottom"], [0, 0.5, 1].findIndex((x) => Math.abs(x - b.focus) < 0.2), (i) => set({ ...b, focus: [0, 0.5, 1][i] }))] : null,
      editorial ? null : [
        h("h2", null, "First stat"), choiceRow(stats.map((x) => x[1]), Math.max(0, stats.findIndex((x) => x[0] === b.left)), (i) => set({ ...b, left: stats[i][0] })),
        h("h2", null, "Second stat"), choiceRow(stats.map((x) => x[1]), Math.max(0, stats.findIndex((x) => x[0] === b.right)), (i) => set({ ...b, right: stats[i][0] })),
        h("h2", null, "In the bar"),
        h("div.card", null,
          toggleRow("Your name instead of daVIT", "As VTOP has it. Only on your screen.", b.nameInBar, () => set({ ...b, nameInBar: !b.nameInBar })),
          toggleRow("Print the bar on a card", "Off for a plain top bar.", !b.hidden, () => set({ ...b, hidden: !b.hidden }))),
        small("Your mascot, in the bar's corner, is changed in Settings, under Make it yours."),
      ],
    ].flat(Infinity).filter(Boolean));
  };
  draw();
  return page;
}

/** An image file as a JPEG data URI no larger than [max] pixels across. */
function shrinkImage(file, max, quality) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const k = Math.min(1, max / Math.max(img.width, img.height));
      const c = h("canvas", { width: Math.round(img.width * k), height: Math.round(img.height * k) });
      c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      resolve(c.toDataURL("image/jpeg", quality));
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("unreadable")); };
    img.src = url;
  });
}

// ---- editorial layouts ----------------------------------------------------------------------------

/** The display card in the Editorial look: your picture, wide, with the overall attendance set on it. */
function editorialPicture(s, opens) {
  const b = Banner.get();
  const overall = s ? Rules.overall(s.attendance) : null;
  const risky = s && !Rules.exempt(s) ? s.attendance.filter(Rules.atRisk).length : 0;
  return h(opens ? "button.tile.picture" : "div.tile.picture", opens ? { type: "button", onclick: opens, "aria-label": `Attendance ${overall ?? "unknown"} percent overall. Opens attendance.` } : null,
    bannerBackground(b),
    h("div.caption", null, label("Attendance"),
      h("div.row", { style: { alignItems: "flex-end", gap: "10px" } },
        h("span.display", { style: { fontSize: "48px", color: overall != null && overall < 75 ? "var(--coral)" : null } }, overall == null ? "–" : `${overall}%`),
        h("span.small.strong", { style: { color: risky ? "var(--coral)" : "var(--muted)", marginBottom: "6px" } }, risky ? `${risky} at risk` : "all clear"))));
}

function tile(cls, onclick, description, ...kids) {
  return h(`button.tile${cls}`, { type: "button", onclick, "aria-label": description }, ...kids);
}

const greeting = (hour) => (hour < 5 ? "Still up" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening");

/** Home in the Editorial look: a masthead, then the day as a grid of live tiles, then the index. */
function homeEditorial(s) {
  const now = new Date();
  const today = Dates.addDays(now, 0);
  const week = weekOf(s, App.slots);
  const cal = calendarOf(s.calendar);
  const plan = planFor(week, now, cal);
  const first = s.profile?.name ? titleCase(s.profile.name.split(" ")[0]) : null;
  const slot = plan.now || plan.next;
  const due = s.assignments.flatMap((c) => c.items.map((a) => ({ c, a, d: Dates.vtop(a.due) })))
    .filter((x) => !x.a.updated && x.d && x.d >= today && Dates.between(today, x.d) <= 7).sort((a, b) => a.d - b.d);
  const exam = s.exams.map((e) => ({ e, d: Dates.vtop(e.date) })).filter((x) => x.d && x.d >= today).sort((a, b) => a.d - b.d)[0];
  const marks = s.marks.flatMap((c) => c.items.filter((m) => m.scored != null && m.max != null).map((m) => ({ c, m })));
  const nextLabel = plan.now ? "On now" : plan.inDays === 0 ? "Next" : plan.inDays === 1 ? "Tomorrow" : plan.inDays != null ? `In ${plan.inDays} days` : "Next";
  const ticker = h("div.tilebody");
  let at = 0;
  const showMark = () => {
    const pick = marks[at];
    ticker.replaceChildren(
      label(marks.length > 1 ? `Marks · ${at + 1} of ${marks.length}` : "Marks"), h("div.grow"),
      ...(pick ? [h("div.row", { style: { alignItems: "baseline", gap: "2px" } }, h("span.display", { style: { fontSize: "44px" } }, num(pick.m.scored)), h("span.display", { style: { fontSize: "22px", color: "var(--muted)" } }, `/${num(pick.m.max)}`)),
        h("div.strong.clip", null, pick.c.courseName), h("div.small.clip", { style: { color: "var(--muted)" } }, pick.m.title)] : [h("span.display", { style: { fontSize: "44px", color: "var(--muted)" } }, "–"), h("div.strong", null, "None posted yet")]));
  };
  showMark();
  if (marks.length > 1 && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
    const timer = setInterval(() => {
      if (!ticker.isConnected) { clearInterval(timer); return; }
      at = (at + 1) % marks.length;
      ticker.classList.remove("turn");
      void ticker.offsetWidth;
      ticker.classList.add("turn");
      showMark();
    }, 3200);
  }
  const hubTile = ([t, r, a]) => tile(".small", () => go(r, a), t, h("div.arrow", null, "↗"), h("div.grow"), h("div.strong", null, t));
  const sections = HUB.filter(([, items]) => items.length);
  return h("div.page.editorial", null,
    h("div.row", null,
      h("div.grow", null, label(`${Dates.long(now)}`), h("div.small", { style: { color: "var(--muted)" } }, s.semester.name.replace(" - VLR", ""))),
      mascotAvatar(Mascot.mine(), 44, () => go("settings"))),
    h("div.headline", null, `${greeting(now.getHours())}${first ? `, ${first}.` : "."}`),
    h("hr.firm"),
    editorialPicture(s, () => go("attendance")),
    h("div.tiles", null,
      tile(plan.now ? ".inverse" : "", () => go("week"), slot ? `${nextLabel}: ${slot.course.courseName} at ${Dates.clock(slot.meeting.start)}` : "No classes. Opens the week.",
        h("div.tilebody", null, h("div.label", { style: plan.now ? { color: "var(--coral)" } : null }, nextLabel), h("div.grow"),
          h("span.display", { style: { fontSize: "40px" } }, slot ? Dates.clock(slot.meeting.start) : "–"),
          h("div.strong", null, slot ? slot.course.courseName : "Nothing scheduled"),
          slot?.course.venue ? h("div.small", null, slot.course.venue) : null)),
      tile("", () => go("academics", { tab: 0 }), marks.length ? `Marks, ${marks.length} posted. Opens marks.` : "No marks yet. Opens marks.", ticker),
      tile("", () => (due[0] ? go("assignment", { classId: due[0].c.classId, code: due[0].a.code }) : go("academics", { tab: 1 })), `${due.length} due this week. Opens academics.`,
        h("div.tilebody", null, label("Due this week"), h("div.grow"), h("span.display", { style: { fontSize: "54px", color: due.length ? null : "var(--muted)" } }, due.length),
          h("div.small.clip", null, due[0] ? `${due[0].a.title} · ${dayWord(Dates.between(today, due[0].d))}` : "Nothing due"))),
      tile(".raised", () => go("examboard"), exam ? `${exam.e.type} in ${Dates.between(today, exam.d)} days` : "No exams scheduled",
        h("div.tilebody", null, label(exam?.e.type || "Exams"), h("div.grow"),
          exam ? [h("div.row", { style: { alignItems: "baseline", gap: "6px" } }, h("span.display", { style: { fontSize: "54px" } }, Dates.between(today, exam.d)), h("span.small.strong", null, "days")), h("div.small.clip", null, `${exam.e.courseName} · ${Dates.short(exam.d)}`)]
            : [h("span.display", { style: { fontSize: "54px", color: "var(--muted)" } }, "–"), h("div.small", null, "None scheduled")]))),
    tile(".red", () => go("leave"), "Plan a leave",
      h("div.row", { style: { width: "100%" } }, h("div.grow", null, h("div.display", { style: { fontSize: "28px" } }, "Plan a leave"), h("div.small", null, "What a day away would cost each course")), h("div.display", { style: { fontSize: "34px" } }, "→"))),
    sections.map(([title, items], n) => [
      h("div.hubhead", null, h("span.label", { style: { color: "var(--coral)", width: "34px" } }, String(n + 1).padStart(2, "0")), h("span.display", { style: { fontSize: "22px" } }, title)),
      h("div.hub", null, items.map(hubTile)),
    ]));
}

/** Attendance in the Editorial look: a numbered index, worst first; a line opens it. */
function attendanceEditorial(s) {
  const now = new Date();
  const week = weekOf(s, App.slots);
  const cal = calendarOf(s.calendar);
  const exempt = Rules.exempt(s);
  const rows = [...s.attendance].sort((a, b) => Rules.percent(a) - Rules.percent(b));
  let open = rows.find(Rules.atRisk) ? Rules.key(rows.find(Rules.atRisk)) : null;
  const list = h("div");
  const draw = () => {
    list.replaceChildren(...rows.map((r, i) => {
      const isOpen = open === Rules.key(r);
      const risky = Rules.atRisk(r) && !exempt;
      const remark = s.feeds.remarks.find((x) => x.code === r.courseCode && Rules.isLab(x.type) === Rules.isLab(r.classType));
      return h("div.indexrow", null,
        h("button.indexhead", { type: "button", "aria-expanded": isOpen, onclick: () => { open = isOpen ? null : Rules.key(r); draw(); } },
          h("span.label", { style: { width: "34px", color: "var(--muted)" } }, String(i + 1).padStart(2, "0")),
          h("div.grow", null, h("div.strong", { style: { fontSize: "17px" } }, r.courseName), h("div.small", { style: { color: "var(--muted)" } }, `${Rules.isLab(r.classType) ? "Lab" : "Theory"} · ${r.attended}/${r.total}`)),
          h("span.display", { style: { fontSize: "34px", color: risky ? "var(--coral)" : null } }, `${Rules.percent(r)}%`)),
        isOpen ? h("div", { style: { padding: "0 0 14px 34px" } }, attendanceBar(r),
          h("div.small", { style: { marginTop: "6px" } }, `${Rules.hint(r, remainingFor(r, week, cal, now)).replace(/^./, (c) => c.toUpperCase())}.`),
          remark?.remark && remark.remark !== "-" ? h("div.small", { style: { color: /debar|revok/i.test(remark.remark) ? "var(--coral)" : "var(--muted)" } }, `VTOP: ${remark.remark}`) : null,
          h("div.row", { style: { gap: "18px", marginTop: "10px" } },
            h("button.linkish", { type: "button", onclick: () => go("leave") }, "Plan a leave →"), h("button.linkish", { type: "button", onclick: () => go("corrections") }, "Marked wrong? →"))) : null);
    }));
  };
  draw();
  return h("div.page.editorial", null,
    h("div.headline", null, "Attendance"),
    h("div.small", { style: { color: "var(--muted)", marginTop: "6px" } }, exempt ? "CGPA 9 or above: the 75% rule doesn't apply to you." : `${rows.filter(Rules.atRisk).length} under 75% · ${rows.filter((r) => !Rules.atRisk(r)).length} safe · worst first`),
    h("hr.firm"),
    rows.length ? list : empty(`VTOP has no attendance for ${s.semester.name} yet.`));
}

if (typeof module !== "undefined") module.exports = { Colour, Look, PAIRS, KINDS, Mascot };
