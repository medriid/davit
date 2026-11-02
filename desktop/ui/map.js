// The campus map: the same data as the phone app, drawn as SVG. Find any block or room.
"use strict";

const CampusMap = {
  data: null,
  load() {
    this.data ||= Promise.all([fetch("data/campus.json").then((r) => r.json()), fetch("data/geo.json").then((r) => r.json())]).then(([campus, geo]) => ({ campus, geo }));
    return this.data;
  },
};

const STYLE = {
  boundary: { fill: "none", stroke: "var(--soft-rule)", width: 2 },
  green: { fill: "color-mix(in srgb, var(--classes) 75%, transparent)", stroke: "none" },
  water: { fill: "var(--attendance)", stroke: "none" },
  waterway: { fill: "none", stroke: "var(--attendance)", width: 2 },
  roads: { fill: "none", stroke: "var(--muted)", width: 2.2 },
  paths: { fill: "none", stroke: "var(--soft-rule)", width: 1 },
  buildings: { fill: "var(--plain)", stroke: "var(--rule)", width: 1 },
  overlays: { fill: "var(--plain)", stroke: "var(--rule)", width: 1 },
  prp: { fill: "var(--plain)", stroke: "var(--rule)", width: 1 },
};

// A room like "PRP 327" or "SJT-210": its building's letters.
function placeFor(campus, text) {
  const q = (text || "").toUpperCase().replace(/[^A-Z0-9 ]/g, " ").trim();
  if (!q) return null;
  const pois = campus.pois;
  const named = (p) => [p.name, ...(p.aliases || [])].map((x) => x.toUpperCase());
  const exact = pois.find((p) => named(p).some((n) => n === q));
  if (exact) return exact;
  const letters = (/^[A-Z]+/.exec(q.replace(/\s/g, "")) || [""])[0];
  return (letters.length >= 2 && pois.find((p) => named(p).some((n) => n === letters || n.split(/\s+/).includes(letters)))) || pois.find((p) => named(p).some((n) => n.includes(q))) || null;
}

function screenMap(_, args = {}) {
  const page = h("div.page");
  const holder = h("div.map", { role: "img", "aria-label": "Campus map" }, empty("Opening the map…"));
  const info = h("div.small", { style: { minHeight: "20px", margin: "6px 0" } });
  const field = h("input.field", { placeholder: "Search places, rooms (PRP 327)…", "aria-label": "Search the map", value: args.find || "" });
  page.append(...pageTitle("Navigation", null).flat().filter(Boolean), field, info, holder);

  CampusMap.load().then(({ campus, geo }) => {
    const [[w, s], [e, n]] = campus.meta.bounds;
    const scale = 111320;
    const k = Math.cos((campus.meta.center[1] * Math.PI) / 180);
    const X = (lon) => (lon - w) * scale * k;
    const Y = (lat) => (n - lat) * scale;
    const width = X(e);
    const height = Y(s);
    const svgNs = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNs, "svg");
    let view = { x: 0, y: 0, w: width, h: height };
    let labels = null;
    // Names keep their size on screen, and only show once there's room for them.
    const setView = () => {
      svg.setAttribute("viewBox", `${view.x} ${view.y} ${view.w} ${view.h}`);
      if (!labels) return;
      labels.setAttribute("display", view.w < 1500 ? "inline" : "none");
      for (const t of labels.children) {
        t.setAttribute("font-size", (view.w / 95).toFixed(1));
        t.style.strokeWidth = `${(view.w / 300).toFixed(1)}px`;
      }
    };
    setView();
    const pathOf = (g) => {
      const ring = (c) => c.map(([a, b], i) => `${i ? "L" : "M"}${X(a).toFixed(1)} ${Y(b).toFixed(1)}`).join("") ;
      if (g.type === "Polygon") return g.coordinates.map((r) => `${ring(r)}Z`).join("");
      if (g.type === "MultiPolygon") return g.coordinates.flat().map((r) => `${ring(r)}Z`).join("");
      if (g.type === "LineString") return ring(g.coordinates);
      if (g.type === "MultiLineString") return g.coordinates.map(ring).join("");
      return "";
    };
    for (const layer of ["green", "water", "waterway", "paths", "roads", "buildings", "overlays", "prp", "boundary"]) {
      const st = STYLE[layer];
      const d = (geo[layer]?.features || []).map((f) => pathOf(f.geometry)).join("");
      if (!d) continue;
      const p = document.createElementNS(svgNs, "path");
      p.setAttribute("d", d);
      p.setAttribute("fill", st.fill);
      p.setAttribute("fill-rule", "evenodd");
      p.setAttribute("stroke", st.stroke);
      p.setAttribute("stroke-width", st.width || 0);
      svg.append(p);
    }
    const pin = document.createElementNS(svgNs, "g");
    labels = document.createElementNS(svgNs, "g");
    const main = new Set(["academic", "lecture", "hostel", "mess", "gate", "prp"]);
    for (const p of campus.pois.filter((x) => main.has(x.cat))) {
      const t = document.createElementNS(svgNs, "text");
      t.setAttribute("x", X(p.lon));
      t.setAttribute("y", Y(p.lat));
      t.setAttribute("font-size", 11);
      t.setAttribute("text-anchor", "middle");
      t.textContent = p.name;
      labels.append(t);
    }
    svg.append(labels, pin);
    setView();
    holder.replaceChildren(svg);

    const show = (text) => {
      pin.replaceChildren();
      if (!text.trim()) { info.textContent = ""; return; }
      const p = placeFor(campus, text);
      if (!p) { info.textContent = `Couldn't find "${text}" on the map.`; return; }
      info.textContent = `${p.name}${text.toUpperCase() !== p.name.toUpperCase() ? ` (${text})` : ""}`;
      const c = document.createElementNS(svgNs, "circle");
      c.setAttribute("cx", X(p.lon));
      c.setAttribute("cy", Y(p.lat));
      c.setAttribute("r", 11);
      c.setAttribute("fill", "var(--coral)");
      c.setAttribute("stroke", "var(--rule)");
      c.setAttribute("stroke-width", 3);
      pin.append(c);
      const size = 500;
      view = { x: X(p.lon) - size / 2, y: Y(p.lat) - size / 3, w: size, h: (size * height) / width };
      setView();
    };
    let timer;
    field.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => show(field.value), 200); });
    show(field.value);

    holder.addEventListener("wheel", (ev) => {
      ev.preventDefault();
      const f = ev.deltaY > 0 ? 1.15 : 0.87;
      const nw = Math.min(width * 1.2, Math.max(120, view.w * f));
      const box = holder.getBoundingClientRect();
      const px = (ev.clientX - box.left) / box.width;
      const py = (ev.clientY - box.top) / box.height;
      const nh = (nw * view.h) / view.w;
      view = { x: view.x + (view.w - nw) * px, y: view.y + (view.h - nh) * py, w: nw, h: nh };
      setView();
    }, { passive: false });
    let drag = null;
    holder.addEventListener("pointerdown", (ev) => { drag = { x: ev.clientX, y: ev.clientY, v: { ...view } }; holder.setPointerCapture(ev.pointerId); });
    holder.addEventListener("pointermove", (ev) => {
      if (!drag) return;
      const box = holder.getBoundingClientRect();
      view.x = drag.v.x - ((ev.clientX - drag.x) * view.w) / box.width;
      view.y = drag.v.y - ((ev.clientY - drag.y) * view.h) / box.height;
      setView();
    });
    holder.addEventListener("pointerup", () => { drag = null; });
  }, () => holder.replaceChildren(empty("Couldn't open the map.")));
  return page;
}
