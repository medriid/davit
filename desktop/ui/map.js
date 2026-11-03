// The Navigation screen: the campus in 2.5D on a canvas, with the phone app's data and rules.
// Search a place or a room, tap a building or pin, and get a walking or cycling route.
"use strict";

const CampusMap = {
  data: null,
  load() {
    this.data ||= Promise.all(["campus", "geo", "graph"].map((f) => fetch(`data/${f}.json`).then((r) => r.json()))).then(([campus, geo, graph]) => buildCampus(campus, geo, graph));
    return this.data;
  },
};

const norm = (s) => (s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
const fixName = (s) => (s || "").replace(/Perl Research/g, "Pearl Research");

/** Everything in metres east and north of the campus centre. */
const dayKey = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** A mess's menu for today, or for the same weekday last time the sheet has one. */
function messMenus(map, name, today) {
  const halls = (map.mess?.halls || []).filter((x) => x.at === name || x.name === name).map((x) => x.name);
  if (!halls.length) return [];
  const items = (map.mess?.items || []).filter((m) => halls.includes(m.hall));
  const exact = items.filter((m) => m.date === dayKey(today));
  if (exact.length) return exact;
  const weekday = today.toLocaleDateString("en-GB", { weekday: "long" });
  const same = items.filter((m) => m.day === weekday);
  const last = same.map((m) => m.date).sort().at(-1);
  return same.filter((m) => m.date === last);
}

function buildCampus(campus, geo, graph) {
  const [lon0, lat0] = campus.meta.center;
  const kx = 111320 * Math.cos((lat0 * Math.PI) / 180);
  const ky = 110574;
  const X = (lon) => (lon - lon0) * kx;
  const Y = (lat) => (lat - lat0) * ky;
  const shape = (ring) => ring.map(([a, b]) => [X(a), Y(b)]);
  const polys = (layer) => (geo[layer]?.features || []).flatMap((f) => (f.geometry.type === "Polygon" ? [f.geometry.coordinates] : f.geometry.type === "MultiPolygon" ? f.geometry.coordinates : []).map((rings) => ({ rings: rings.map(shape), props: f.properties || {} })));
  const lines = (layer) => (geo[layer]?.features || []).flatMap((f) => (f.geometry.type === "LineString" ? [f.geometry.coordinates] : f.geometry.type === "MultiLineString" ? f.geometry.coordinates : []).map((l) => ({ line: shape(l), props: f.properties || {} })));

  const seen = new Set();
  const pois = [...campus.pois, ...(campus.places?.items || [])].filter((p) => p.name && !p.unnamed && !seen.has(p.id) && seen.add(p.id)).map((p) => ({ ...p, name: fixName(p.name), x: X(p.lon), y: Y(p.lat), aliases: (p.aliases || []).map(fixName) }));
  const blocks = (campus.prp?.blocks || []).map((b) => ({ ...b, name: fixName(b.name), shape: shape(b.ring) }));
  const solids = [
    ...polys("buildings").filter((b) => !b.props.construction || b.rings[0].length).map((b) => ({ shape: b.rings[0], holes: b.rings.slice(1), name: fixName(b.props.name), cat: b.props.cat, height: Math.max(1, b.props.levels || 1) * 3.2, prp: null })),
    ...blocks.map((b) => ({ shape: b.shape, holes: [], name: b.name, cat: "prp", height: (b.floors?.length || 4) * 3.2, prp: b })),
  ];
  for (const s of solids) {
    const n = s.shape.length;
    s.cx = s.shape.reduce((t, p) => t + p[0], 0) / n;
    s.cy = s.shape.reduce((t, p) => t + p[1], 0) / n;
  }
  const nodes = graph.lat.map((lat, i) => [X(graph.lon[i]), Y(lat)]);
  const adj = nodes.map(() => []);
  graph.edges.forEach((e, i) => {
    adj[e[0]].push([e[1], i]);
    adj[e[1]].push([e[0], i]);
  });
  const codes = [...new Set([...pois.flatMap((p) => [p.name, ...p.aliases]), ...Object.keys(ROOM_CODES)].map(norm).filter((c) => c.length >= 2 && c.length <= 8 && /^[A-Z]/.test(c)))].sort((a, b) => b.length - a.length);
  const all = solids.flatMap((s) => s.shape);
  return {
    categories: campus.categories,
    mess: campus.mess,
    toX: X,
    toY: Y,
    pois, blocks, solids, codes, nodes, adj, edges: graph.edges,
    green: polys("green"), water: polys("water"), boundary: polys("boundary"),
    roads: lines("roads"), paths: lines("paths"), walls: lines("wall"), rail: lines("rail"),
    bounds: { minX: Math.min(...all.map((p) => p[0])), maxX: Math.max(...all.map((p) => p[0])), minY: Math.min(...all.map((p) => p[1])), maxY: Math.max(...all.map((p) => p[1])) },
  };
}

// ---- rooms -------------------------------------------------------------------------------------------

const ROOM_CODES = { MB: "Main Building", MGR: "MGB", MGB: "MGB", CDMR: "CDMM", GDN: "GDN", SJT: "SJT", TT: "TT", SMV: "SMV", PRP: "PRP", LIB: "EV Periyar Library", ANNA: "Anna Auditorium" };

function roomInRange(range, room) {
  if (!range || !range.trim()) return false;
  const r = room.trim().toUpperCase();
  const ground = r.startsWith("G");
  const n = Number(r.replace(/^G/, ""));
  if (!Number.isInteger(n) || /^g/i.test(range.trim()) !== ground) return false;
  const nums = (range.match(/\d+/g) || []).map(Number);
  return nums.length === 0 ? false : nums.length === 1 ? nums[0] === n : n >= nums[0] && n <= nums[1];
}

function floorOf(room) {
  const r = room.trim().toUpperCase();
  if (r.startsWith("G")) return "Ground floor";
  const digits = (/^\d+/.exec(r) || [""])[0];
  const f = digits.length === 3 ? +digits[0] : digits.length === 4 ? +digits.slice(0, 2) : null;
  if (f == null) return null;
  return f === 0 ? "Ground floor" : f === 1 ? "1st floor" : f === 2 ? "2nd floor" : f === 3 ? "3rd floor" : `${f}th floor`;
}

function findPoi(map, name) {
  const n = norm(name);
  const hits = map.pois.filter((p) => norm(p.name) === n || p.aliases.some((a) => norm(a) === n));
  const rank = ["academic", "prp", "lecture", "admin"];
  const place = (p) => (rank.indexOf(p.cat) < 0 ? rank.length : rank.indexOf(p.cat));
  return [...hits].sort((a, b) => place(a) - place(b))[0] || (n.length >= 3 ? map.pois.find((p) => norm(p.name).startsWith(n)) : null) || null;
}

/** A venue like "PRP 327" or "SJT-401": the place, room and floor, as the phone resolves them. */
function resolveVenue(map, venue) {
  const compact = norm(venue);
  if (!compact) return null;
  const code = map.codes.find((c) => compact.startsWith(c) && (compact.length === c.length || /[0-9G]/.test(compact[c.length])));
  if (!code) return null;
  const room = (/^(G?\d+)/.exec(compact.slice(code.length)) || [""])[0];
  const target = ROOM_CODES[code] || code;
  if (target === "PRP" && room) {
    for (const b of map.blocks) {
      const floor = (b.floors || []).find((f) => roomInRange(f.rooms, room));
      if (floor) {
        const n = b.shape.length;
        return { x: b.shape.reduce((t, p) => t + p[0], 0) / n, y: b.shape.reduce((t, p) => t + p[1], 0) / n, title: `${b.name} · room ${room}`, building: b.name, room, floor: floor.label, block: b, poi: map.pois.find((p) => p.cat === "prp" && (p.letter || "").toUpperCase() === (b.letter || "").toUpperCase()) || null };
      }
    }
  }
  const poi = findPoi(map, target);
  if (!poi) return null;
  return { x: poi.x, y: poi.y, title: room ? `${poi.name} ${room}` : poi.name, building: poi.name, room, floor: room ? floorOf(room) : null, poi, block: null };
}

function searchPlaces(map, q) {
  const needle = norm(q);
  if (needle.length < 2) return [];
  const out = [];
  const venue = /\d/.test(q) ? resolveVenue(map, q) : null;
  if (venue) out.push({ ...venue, score: -1, note: venue.floor || "" });
  for (const p of map.pois) {
    const names = [p.name, ...p.aliases].map(norm);
    const score = names.some((n) => n === needle) ? 0 : names.some((n) => n.startsWith(needle)) ? 1 : names.some((n) => n.includes(needle)) ? 2 : -9;
    if (score >= 0) out.push({ x: p.x, y: p.y, title: p.name, poi: p, block: null, score, note: map.categories[p.cat]?.label || "" });
  }
  for (const s of map.solids) {
    if (s.name && norm(s.name).includes(needle) && !out.some((o) => norm(o.title) === norm(s.name))) out.push({ x: s.cx, y: s.cy, title: s.name, solid: s, block: s.prp, score: 3, note: "Building" });
  }
  return out.sort((a, b) => a.score - b.score || a.title.localeCompare(b.title)).slice(0, 8);
}

function inRing(ring, x, y) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    if (ring[i][1] > y !== ring[j][1] > y && x < ((ring[j][0] - ring[i][0]) * (y - ring[i][1])) / (ring[j][1] - ring[i][1]) + ring[i][0]) inside = !inside;
  }
  return inside;
}

const buildingAt = (map, x, y) => map.solids.filter((s) => inRing(s.shape, x, y)).sort((a, b) => ringArea(a.shape) - ringArea(b.shape))[0] || null;

function ringArea(r) {
  let s = 0;
  for (let i = 0; i < r.length; i++) {
    const j = (i + 1) % r.length;
    s += r[i][0] * r[j][1] - r[j][0] * r[i][1];
  }
  return Math.abs(s) / 2;
}

// ---- routes ------------------------------------------------------------------------------------------

function nearestNode(map, x, y) {
  let best = 0;
  let d = Infinity;
  map.nodes.forEach((n, i) => {
    if (!map.adj[i].length) return;
    const v = (n[0] - x) ** 2 + (n[1] - y) ** 2;
    if (v < d) {
      d = v;
      best = i;
    }
  });
  return best;
}

/** The quickest way along the campus network; cycling keeps off what bikes can't use. */
function findRoute(map, from, to, mode) {
  const cost = mode === "Cycle" ? 4 : 3;
  const start = nearestNode(map, from.x, from.y);
  const goal = nearestNode(map, to.x, to.y);
  const dist = new Float64Array(map.nodes.length).fill(Infinity);
  const prev = new Int32Array(map.nodes.length).fill(-1);
  const via = new Int32Array(map.nodes.length).fill(-1);
  const heap = [[0, start]];
  dist[start] = 0;
  const push = (item) => {
    heap.push(item);
    let i = heap.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (heap[p][0] <= heap[i][0]) break;
      [heap[p], heap[i]] = [heap[i], heap[p]];
      i = p;
    }
  };
  const pop = () => {
    const top = heap[0];
    const last = heap.pop();
    if (heap.length) {
      heap[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1;
        const r = l + 1;
        let m = i;
        if (l < heap.length && heap[l][0] < heap[m][0]) m = l;
        if (r < heap.length && heap[r][0] < heap[m][0]) m = r;
        if (m === i) break;
        [heap[m], heap[i]] = [heap[i], heap[m]];
        i = m;
      }
    }
    return top;
  };
  while (heap.length) {
    const [d, n] = pop();
    if (d > dist[n]) continue;
    if (n === goal) break;
    for (const [to2, e] of map.adj[n]) {
      const c = map.edges[e][cost];
      if (c < 0) continue;
      if (d + c < dist[to2]) {
        dist[to2] = d + c;
        prev[to2] = n;
        via[to2] = e;
        push([d + c, to2]);
      }
    }
  }
  if (dist[goal] === Infinity) return null;
  const pts = [];
  let metres = 0;
  let flags = 0;
  for (let n = goal; n !== -1; n = prev[n]) {
    pts.unshift(map.nodes[n]);
    if (via[n] !== -1) {
      metres += map.edges[via[n]][2];
      flags |= map.edges[via[n]][5];
    }
  }
  const hop = (a, b) => Math.hypot(a.x - b[0], a.y - b[1]);
  const seconds = dist[goal] + (hop(from, pts[0]) + hop(to, pts.at(-1))) / (mode === "Cycle" ? 4 : 1.3);
  return { pts: [[from.x, from.y], ...pts, [to.x, to.y]], metres: Math.round(metres + hop(from, pts[0]) + hop(to, pts.at(-1))), seconds, steps: !!(flags & 1), indoor: !!(flags & 2), unpaved: !!(flags & 8) };
}

/** A route's changes of direction: kinks under 30° aren't turns, and very short legs are looked past. */
function routeTurns(pts) {
  const legs = [];
  for (let i = 1; i < pts.length; i++) {
    const m = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
    if (m >= 4) legs.push({ m, bearing: Math.atan2(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]) });
    else if (legs.length) legs.at(-1).m += m;
  }
  const out = [];
  let run = 0;
  for (let i = 0; i < legs.length; i++) {
    run += legs[i].m;
    if (i + 1 === legs.length) break;
    let d = ((legs[i + 1].bearing - legs[i].bearing) * 180) / Math.PI;
    while (d > 180) d -= 360;
    while (d <= -180) d += 360;
    if (Math.abs(d) < 30) continue;
    const side = d > 0 ? "right" : "left";
    const a = Math.abs(d);
    out.push({ metres: Math.round(run), words: a <= 55 ? `Slight ${side}` : a <= 130 ? `Turn ${side}` : `Sharp ${side}` });
    run = 0;
  }
  return { turns: out, last: Math.round(run) };
}

const walkTime = (s) => (s < 90 ? `${Math.max(1, Math.round(s / 60))} min` : s < 3600 ? `${Math.round(s / 60)} min` : `${Math.floor(s / 3600)} h ${Math.round((s % 3600) / 60)} min`);
const distance = (m) => (m < 1000 ? `${m} m` : `${(m / 1000).toFixed(1)} km`);

// ---- the screen --------------------------------------------------------------------------------------

const CHIPS = [["Classes", ["academic", "lecture", "prp", "admin"]], ["Food", ["mess", "canteen", "shop", "vending"]], ["Hostels", ["hostel"]], ["Gates", ["gate", "transport"]], ["ATMs", ["atm"]], ["Print", ["print"]], ["Laundry", ["laundry"]], ["Health", ["health", "toilet"]], ["Sports", ["sports", "green"]], ["Bikes", ["cycle", "parking"]]];

function screenMap(s, args = {}) {
  const canvas = h("canvas", { tabindex: "0", "aria-label": "Campus map. Drag to move, scroll to zoom, right-drag to turn." });
  const field = h("input.field", { placeholder: "Search places, rooms (PRP 327)…", "aria-label": "Search the map", value: args.find || "" });
  const results = h("div.mapresults", { hidden: true, role: "listbox" });
  const chips = h("div.chips.mapchips");
  const buttons = h("div.mapbuttons");
  const cards = h("div.mapcards");
  const page = h("div.mappage", null, canvas, h("div.maptop", null, h("div", { style: { position: "relative" } }, field, results), chips), buttons, cards, h("div.mapcredit", null, "Map by Jeman · © OpenStreetMap"));
  CampusMap.load().then((map) => mapScreen(map, s, args, { page, canvas, field, results, chips, buttons, cards }), () => cards.replaceChildren(h("div.card", null, "Couldn't open the map.")));
  return page;
}

function mapScreen(map, s, args, ui) {
  const { canvas, field, results, chips, buttons, cards } = ui;
  const ctx = canvas.getContext("2d");
  let w = 1;
  let h_ = 1;
  const cam = { x: 0, y: 0, scale: 0.4, rot: 0, tilt: localStore.get("mapFlat", "0") === "1" ? 0 : 1 };
  CampusMap.cam = cam;
  let chip = null;
  let flat = cam.tilt === 0;
  let me = null;
  let meNote = "";
  let picked = null;
  let route = null;
  let routing = null;
  let mode = "Walk";
  let flight = null;
  let dirty = true;

  const palette = () => {
    const css = getComputedStyle(document.documentElement);
    const v = (n) => css.getPropertyValue(n).trim();
    return { paper: v("--paper"), ink: v("--ink"), rule: v("--rule"), soft: v("--soft-rule"), muted: v("--muted"), green: v("--classes"), water: v("--attendance"), roof: v("--plain"), wall: v("--soft-rule"), teal: v("--teal"), coral: v("--coral"), plate: v("--plate") };
  };

  const squash = () => 1 - 0.4 * cam.tilt;
  const lift = () => 0.85 * cam.tilt;
  const px = (x, y) => {
    const dx = x - cam.x;
    const dy = y - cam.y;
    return w / 2 + (dx * Math.cos(cam.rot) - dy * Math.sin(cam.rot)) * cam.scale;
  };
  const py = (x, y, z = 0) => {
    const dx = x - cam.x;
    const dy = y - cam.y;
    return h_ / 2 - ((dx * Math.sin(cam.rot) + dy * Math.cos(cam.rot)) * squash() + z * lift()) * cam.scale;
  };
  const ground = (sx, sy) => {
    const rx = (sx - w / 2) / cam.scale;
    const ry = (h_ / 2 - sy) / (cam.scale * squash());
    return [cam.x + rx * Math.cos(cam.rot) + ry * Math.sin(cam.rot), cam.y - rx * Math.sin(cam.rot) + ry * Math.cos(cam.rot)];
  };
  const fitScale = () => Math.min(w / (map.bounds.maxX - map.bounds.minX), h_ / ((map.bounds.maxY - map.bounds.minY) * squash())) * 0.92;
  const clampScale = (v) => Math.min(9, Math.max(fitScale() * 0.6, v));
  const clampPan = () => {
    cam.x = Math.min(map.bounds.maxX + 150, Math.max(map.bounds.minX - 150, cam.x));
    cam.y = Math.min(map.bounds.maxY + 150, Math.max(map.bounds.minY - 150, cam.y));
  };
  const request = () => {
    if (!dirty) {
      dirty = true;
      requestAnimationFrame(frame);
    }
  };

  const fly = (to) => {
    const from = { ...cam };
    const start = performance.now();
    flight = (now) => {
      const t = Math.min(1, (now - start) / 380);
      const e = 1 - (1 - t) ** 3;
      for (const k of Object.keys(to)) cam[k] = from[k] + (to[k] - from[k]) * e;
      if (t >= 1) flight = null;
    };
    request();
  };

  const path = (ring, z = 0) => {
    ring.forEach((p, i) => (i ? ctx.lineTo(px(p[0], p[1]), py(p[0], p[1], z)) : ctx.moveTo(px(p[0], p[1]), py(p[0], p[1], z))));
  };

  function frame(now) {
    dirty = false;
    if (!canvas.isConnected) return;
    if (flight) flight(now);
    if (inertia) stepInertia();
    const c = palette();
    const ratio = devicePixelRatio || 1;
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.fillStyle = c.paper;
    ctx.fillRect(0, 0, w, h_);
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    const fill = (list, color) => {
      ctx.fillStyle = color;
      for (const a of list) {
        ctx.beginPath();
        a.rings.forEach((r) => { path(r); ctx.closePath(); });
        ctx.fill("evenodd");
      }
    };
    fill(map.green, c.green);
    fill(map.water, c.water);
    const stroke = (list, color, width, dash) => {
      ctx.strokeStyle = color;
      ctx.lineWidth = width;
      ctx.setLineDash(dash || []);
      ctx.beginPath();
      for (const a of list) path(a.line);
      ctx.stroke();
      ctx.setLineDash([]);
    };
    ctx.strokeStyle = c.soft;
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (const b of map.boundary) path(b.rings[0]);
    ctx.stroke();
    stroke(map.rail, c.muted, 1.5, [6, 5]);
    stroke(map.walls, c.soft, 1);
    if (cam.scale > 0.55) stroke(map.paths, c.muted, Math.max(0.8, cam.scale * 1.1), [Math.max(2, cam.scale * 2), Math.max(3, cam.scale * 3)]);
    stroke(map.roads, c.muted, Math.max(2.2, cam.scale * 7));
    stroke(map.roads, c.paper, Math.max(1, cam.scale * 5.4));

    if (route) {
      ctx.setLineDash([]);
      ctx.strokeStyle = c.rule;
      ctx.lineWidth = Math.max(5, cam.scale * 5);
      ctx.beginPath();
      path(route.pts);
      ctx.stroke();
      ctx.strokeStyle = c.coral;
      ctx.lineWidth = Math.max(3, cam.scale * 3.4);
      ctx.stroke();
    }

    // Far buildings first, so near ones stand in front.
    const depth = (b) => b.cx * Math.sin(cam.rot) + b.cy * Math.cos(cam.rot);
    const order = [...map.solids].sort((a, b) => depth(b) - depth(a));
    const raised = cam.tilt > 0.02;
    for (const b of order) {
      const selected = picked && (picked.solid === b || (picked.block && b.prp === picked.block));
      const roof = selected ? c.coral : b.prp?.color && !document.documentElement.dataset.theme?.includes("dark") ? b.prp.color : c.roof;
      if (raised) {
        ctx.fillStyle = selected ? c.rule : c.wall;
        ctx.strokeStyle = c.rule;
        ctx.lineWidth = 0.8;
        const n = b.shape.length;
        for (let i = 0; i < n; i++) {
          const p = b.shape[i];
          const q = b.shape[(i + 1) % n];
          ctx.beginPath();
          ctx.moveTo(px(p[0], p[1]), py(p[0], p[1]));
          ctx.lineTo(px(q[0], q[1]), py(q[0], q[1]));
          ctx.lineTo(px(q[0], q[1]), py(q[0], q[1], b.height));
          ctx.lineTo(px(p[0], p[1]), py(p[0], p[1], b.height));
          ctx.closePath();
          ctx.fill();
          ctx.stroke();
        }
      }
      ctx.fillStyle = roof;
      ctx.strokeStyle = c.rule;
      ctx.lineWidth = selected ? 2 : 1;
      ctx.beginPath();
      path(b.shape, raised ? b.height : 0);
      ctx.closePath();
      for (const hole of b.holes) { path(hole, raised ? b.height : 0); ctx.closePath(); }
      ctx.fill("evenodd");
      ctx.stroke();
    }

    drawPins(c);
    if (me) mark([me.x, me.y], c.coral, null, c);
    drawLabels(c);
    if (route) {
      mark(route.pts[0], c.teal, "A", c);
      mark(route.pts.at(-1), c.coral, "B", c);
    } else if (picked) {
      mark([picked.x, picked.y], c.coral, "", c);
    }
    if (flight || inertia) request();
  }

  function mark(p, color, letter, c) {
    const x = px(p[0], p[1]);
    const y = py(p[0], p[1]);
    ctx.fillStyle = c.plate;
    ctx.beginPath();
    ctx.arc(x + 2, y + 2, 9, 0, 7);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.strokeStyle = c.rule;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(x, y, 9, 0, 7);
    ctx.fill();
    ctx.stroke();
    if (letter) {
      ctx.fillStyle = c.rule;
      ctx.font = "600 11px 'IBM Plex Mono', monospace";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(letter, x, y + 0.5);
    }
  }

  const pinsNow = () => {
    if (chip) return map.pois.filter((p) => chip.includes(p.cat));
    return [];
  };

  function drawPins(c) {
    for (const p of pinsNow()) {
      const x = px(p.x, p.y);
      const y = py(p.x, p.y);
      if (x < -10 || y < -10 || x > w + 10 || y > h_ + 10) continue;
      ctx.fillStyle = map.categories[p.cat]?.color || c.teal;
      ctx.strokeStyle = c.rule;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(x, y, 6, 0, 7);
      ctx.fill();
      ctx.stroke();
    }
  }

  function drawLabels(c) {
    ctx.font = "600 12px 'IBM Plex Mono', monospace";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.lineWidth = 3.5;
    ctx.strokeStyle = c.paper;
    const taken = [];
    const put = (text, x, y) => {
      const tw = ctx.measureText(text).width / 2 + 3;
      if (x < -tw || x > w + tw || y < 0 || y > h_) return;
      if (taken.some((t) => Math.abs(t[0] - x) < t[2] + tw && Math.abs(t[1] - y) < 15)) return;
      taken.push([x, y, tw]);
      ctx.fillStyle = c.ink;
      ctx.strokeText(text, x, y);
      ctx.fillText(text, x, y);
    };
    if (picked) put(picked.title, px(picked.x, picked.y), py(picked.x, picked.y) - 20);
    if (chip) for (const p of pinsNow()) if (cam.scale > 0.75) put(p.name, px(p.x, p.y), py(p.x, p.y) - 13);
    if (cam.scale > 0.5) {
      const big = [...map.solids].filter((b) => b.name).sort((a, b) => ringArea(b.shape) - ringArea(a.shape));
      for (const b of big.slice(0, cam.scale > 1.1 ? 150 : 40)) put(b.name, px(b.cx, b.cy), py(b.cx, b.cy, raisedHeight(b)));
    }
  }
  const raisedHeight = (b) => (cam.tilt > 0.02 ? b.height : 0);

  // ---- moving around ----------------------------------------------------------------------------------

  let drag = null;
  let inertia = null;
  const stepInertia = () => {
    inertia.vx *= 0.92;
    inertia.vy *= 0.92;
    const [a, b] = ground(w / 2 - inertia.vx, h_ / 2 - inertia.vy);
    cam.x = a;
    cam.y = b;
    clampPan();
    if (Math.hypot(inertia.vx, inertia.vy) < 0.25) inertia = null;
  };
  canvas.addEventListener("contextmenu", (e) => e.preventDefault());
  canvas.addEventListener("pointerdown", (e) => {
    canvas.focus({ preventScroll: true });
    canvas.setPointerCapture(e.pointerId);
    flight = null;
    inertia = null;
    drag = { x: e.clientX, y: e.clientY, moved: 0, turn: e.button === 2 || e.shiftKey, vx: 0, vy: 0, t: performance.now() };
    results.hidden = true;
  });
  canvas.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const dx = e.clientX - drag.x;
    const dy = e.clientY - drag.y;
    drag.moved += Math.abs(dx) + Math.abs(dy);
    drag.x = e.clientX;
    drag.y = e.clientY;
    if (drag.turn) {
      cam.rot += dx * 0.006;
    } else {
      const [a, b] = ground(w / 2 - dx, h_ / 2 - dy);
      cam.x = a;
      cam.y = b;
      clampPan();
      const now = performance.now();
      const dt = Math.max(1, now - drag.t);
      drag.vx = (dx / dt) * 16;
      drag.vy = (dy / dt) * 16;
      drag.t = now;
    }
    request();
  });
  const release = (e) => {
    if (!drag) return;
    const wasDrag = drag.moved > 5;
    if (wasDrag && !drag.turn && performance.now() - drag.t < 60 && Math.hypot(drag.vx, drag.vy) > 1) {
      inertia = { vx: drag.vx, vy: drag.vy };
      request();
    }
    drag = null;
    if (!wasDrag && e.type === "pointerup" && e.button === 0) tap(e);
  };
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);

  const zoomAt = (sx, sy, factor) => {
    const [a, b] = ground(sx, sy);
    cam.scale = clampScale(cam.scale * factor);
    const [c, d] = ground(sx, sy);
    cam.x += a - c;
    cam.y += b - d;
    clampPan();
    request();
  };
  const local = (e) => {
    const r = canvas.getBoundingClientRect();
    return [e.clientX - r.left, e.clientY - r.top];
  };
  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    flight = null;
    inertia = null;
    const [sx, sy] = local(e);
    // A trackpad's two fingers move the map; a mouse wheel or a pinch zooms.
    if (!e.ctrlKey && Math.abs(e.deltaX) > 0.5 && e.deltaMode === 0) {
      const [a, b] = ground(w / 2 + e.deltaX, h_ / 2 + e.deltaY);
      cam.x = a;
      cam.y = b;
      clampPan();
      request();
      return;
    }
    zoomAt(sx, sy, Math.exp(-e.deltaY * (e.ctrlKey ? 0.012 : 0.0016)));
  }, { passive: false });
  canvas.addEventListener("dblclick", (e) => {
    const [sx, sy] = local(e);
    zoomAt(sx, sy, e.shiftKey ? 0.6 : 1.7);
  });
  canvas.addEventListener("keydown", (e) => {
    const step = 90;
    const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    if (moves[e.key]) {
      e.preventDefault();
      const [a, b] = ground(w / 2 + moves[e.key][0], h_ / 2 + moves[e.key][1]);
      fly({ x: a, y: b });
    } else if (e.key === "+" || e.key === "=") {
      zoomAt(w / 2, h_ / 2, 1.3);
    } else if (e.key === "-") {
      zoomAt(w / 2, h_ / 2, 0.77);
    } else if (e.key === "Escape") {
      picked = null;
      route = null;
      routing = null;
      drawCards();
      request();
    }
  });

  function fit() {
    fly({ x: (map.bounds.minX + map.bounds.maxX) / 2, y: (map.bounds.minY + map.bounds.maxY) / 2, scale: fitScale(), rot: 0 });
  }

  function tap(e) {
    const [sx, sy] = local(e);
    let hit = null;
    let best = 14;
    for (const p of pinsNow()) {
      const d = Math.hypot(px(p.x, p.y) - sx, py(p.x, p.y) - sy);
      if (d < best) {
        best = d;
        hit = { x: p.x, y: p.y, title: p.name, poi: p, block: null };
      }
    }
    if (!hit) {
      // The roof first where it stands in front of the ground under the pointer.
      const near = [...map.solids].sort((a, b) => (b.cx * Math.sin(cam.rot) + b.cy * Math.cos(cam.rot)) - (a.cx * Math.sin(cam.rot) + a.cy * Math.cos(cam.rot))).reverse();
      for (const b of near) {
        const lifted = (raisedHeight(b) * lift() * cam.scale);
        const [gx, gy] = ground(sx, sy + lifted);
        if (inRing(b.shape, gx, gy)) {
          const poi = findPoi(map, b.name || "") || map.pois.find((p) => inRing(b.shape, p.x, p.y)) || null;
          hit = { x: b.cx, y: b.cy, title: b.name || poi?.name || "Building", solid: b, block: b.prp, poi };
          break;
        }
      }
    }
    if (hit) choose(hit, false);
    else if (!routing) {
      picked = null;
      drawCards();
      request();
    }
  }

  function choose(spot, flyThere = true) {
    results.hidden = true;
    if (routing && !routing.from) {
      routing.from = spot;
      makeRoute();
      return;
    }
    route = null;
    routing = null;
    picked = spot;
    if (!spot.solid) spot.solid = buildingAt(map, spot.x, spot.y);
    if (flyThere) fly({ x: spot.x, y: spot.y, scale: Math.max(cam.scale, 1.3) });
    drawCards();
    request();
  }

  function makeRoute() {
    route = findRoute(map, routing.from, routing.to, mode);
    if (route) {
      const xs = route.pts.map((p) => p[0]);
      const ys = route.pts.map((p) => p[1]);
      const span = Math.max(Math.max(...xs) - Math.min(...xs), (Math.max(...ys) - Math.min(...ys)) * 1.1, 60);
      fly({ x: (Math.min(...xs) + Math.max(...xs)) / 2, y: (Math.min(...ys) + Math.max(...ys)) / 2, scale: clampScale(Math.min(w, h_) / (span * 1.5)), rot: 0 });
    }
    drawCards();
    request();
  }

  // ---- what's around the map --------------------------------------------------------------------------

  function drawChips() {
    chips.replaceChildren(...CHIPS.map(([name, cats]) => h("button.chip", { type: "button", class: chip === cats ? "on" : "", "aria-pressed": chip === cats, onclick: () => { chip = chip === cats ? null : cats; drawChips(); request(); } }, name)));
  }

  /** A computer's location is usually Wi-Fi's guess, so it says how far off it may be. */
  function findMe() {
    const say = (t) => { meNote = t; drawCards(); };
    if (!navigator.geolocation) { say("This computer can't say where it is. Search for a place instead."); return; }
    say("Finding you…");
    navigator.geolocation.getCurrentPosition((at) => {
      me = { x: map.toX(at.coords.longitude), y: map.toY(at.coords.latitude) };
      const off = Math.round(at.coords.accuracy || 0);
      say(off > 100 ? `That's a rough guess, within about ${off} m. Computers have no GPS.` : "");
      fly({ x: me.x, y: me.y, scale: Math.max(cam.scale, 1.2) });
      request();
    }, () => say("This computer won't say where it is. Turn on location in its settings, or search for a place instead."), { enableHighAccuracy: true, timeout: 12000 });
  }

  function drawButtons() {
    const round = (text, title, onclick) => h("button.mapbtn", { type: "button", title, "aria-label": title, onclick }, text);
    const needle = round("N", "North up", () => fly({ rot: 0 }));
    buttons.replaceChildren(
      round("+", "Zoom in", () => zoomAt(w / 2, h_ / 2, 1.4)),
      round("−", "Zoom out", () => zoomAt(w / 2, h_ / 2, 0.71)),
      needle,
      round(flat ? "3D" : "2D", flat ? "Show in 2.5D" : "Show flat", () => {
        flat = !flat;
        localStore.set("mapFlat", flat ? "1" : "0");
        fly({ tilt: flat ? 0 : 1 });
        drawButtons();
      }),
      round("⛶", "Fit the whole campus", fit),
      round("◎", "Find me", findMe),
    );
  }

  const classCard = () => {
    if (!s) return null;
    const now = new Date();
    const week = weekOf(s, App.slots);
    const plan = planFor(week, now, calendarOf(s.calendar));
    const slot = plan.now || plan.next;
    if (!slot?.course.venue) return null;
    const spot = resolveVenue(map, slot.course.venue);
    if (!spot) return null;
    const when = plan.now ? "Now" : plan.inDays === 0 ? `Next · ${Dates.clock(slot.meeting.start)}` : `${plan.inDays === 1 ? "Tomorrow" : Dates.dayName(Dates.weekday(plan.nextDate))} · ${Dates.clock(slot.meeting.start)}`;
    return h("div.card", null, h("div.row", null, h("div.grow", null, label(when), h("div.strong.clip", null, slot.course.courseName), small([spot.title, spot.floor].filter(Boolean).join(" · "))), button("Show", () => choose(spot), "solid")));
  };

  function drawCards() {
    cards.replaceChildren(...[meNote ? h("div.card", null, small(meNote)) : null, routeCard(), !routing && picked ? placeCard(picked) : null, !routing && !picked ? classCard() : null].filter(Boolean));
  }

  function placeCard(p) {
    const poi = p.poi;
    const block = p.block;
    const floors = block?.floors || poi?.floors || [];
    const room = p.room;
    const shell = p.block || p.solid;
    const inside = shell ? map.pois.filter((q) => !q.unnamed && q.id !== poi?.id && inRing(shell.shape, q.x, q.y)) : [];
    const today = new Date();
    const menus = poi?.cat === "mess" ? messMenus(map, poi.name, today) : [];
    return h("div.card.placecard", null,
      h("div.row", null, h("div.grow", null, h("div.strong", null, p.title), small([p.floor, map.categories[poi?.cat || p.solid?.cat]?.label].filter(Boolean).join(" · "))), button("Close", () => { picked = null; drawCards(); request(); })),
      poi?.desc && !/OSM|OpenStreetMap/.test(poi.desc) ? small(poi.desc) : null,
      poi?.hours ? small(`Open ${poi.hours}`) : null,
      inside.length ? [h("div.label", { style: { marginTop: "8px" } }, p.block ? "Inside" : "Also in this building"), inside.slice(0, 10).map((q) => h("button.linkrow", { type: "button", onclick: () => choose({ x: q.x, y: q.y, title: q.name, poi: q, block: null }) }, h("div.row", null, h("span.small.strong.grow", null, q.name), q.cat !== poi?.cat ? small(map.categories[q.cat]?.label || "") : null))), inside.length > 10 ? small(`+ ${inside.length - 10} more`) : null] : null,
      menus.length ? [h("div.label", { style: { marginTop: "8px" } }, `Today's menu${menus[0].date !== dayKey(today) ? ` (from ${menus[0].date})` : ""}`), menus.map((m) => h("div", { style: { marginTop: "4px" } }, h("div.small.strong", null, m.meal), small(m.menu))), small("Menus can change at the counter.")] : null,
      floors.length ? [h("div.label", { style: { marginTop: "6px" } }, "Floors"), h("div.chips", null, floors.map((f) => h("span.badge", { class: p.floor === f.label ? "on" : "" }, `${/^ground/i.test(f.label) ? "G" : parseInt(f.label, 10) || f.level} · ${f.rooms || "–"}`)))] : null,
      h("div.row", { style: { marginTop: "9px" } }, button("Route here", () => { routing = { to: p, from: null }; route = null; drawCards(); }, "solid")));
  }

  function routeCard() {
    if (!routing) return null;
    const close = button("End", () => { routing = null; route = null; drawCards(); request(); });
    if (!routing.from) {
      const starts = [["Gate 1", findPoi(map, "Gate 1")], ["Main Building", findPoi(map, "Main Building")]].filter((x) => x[1]);
      return h("div.card.classes", null,
        h("div.row", null, h("div.grow", null, label("Route to"), h("div.strong", null, routing.to.title)), close),
        small("Where are you starting? Click a place on the map or search for one."),
        h("div.chips", { style: { marginTop: "6px" } }, starts.map(([t, p]) => h("button.chip", { type: "button", onclick: () => choose({ x: p.x, y: p.y, title: p.name, poi: p }) }, t))));
    }
    const modes = h("div.chips", null, ["Walk", "Cycle"].map((m) => h("button.chip", { type: "button", class: m === mode ? "on" : "", onclick: () => { mode = m; makeRoute(); } }, m)));
    if (!route) return h("div.card.classes", null, h("div.row", null, h("div.grow.strong", null, "No route found"), close), modes, small("Try the other way of getting there."));
    const notes = [route.steps && "has steps", route.indoor && "goes indoors", route.unpaved && "unpaved shortcut"].filter(Boolean).join(", ");
    const { turns, last } = routeTurns(route.pts);
    return h("div.card.classes.routecard", null,
      h("div.row", null, h("div.grow", null, label(`${routing.from.title} to ${routing.to.title}`), h("div.big", null, walkTime(route.seconds)), small(`${distance(route.metres)}${notes ? ` · ${notes}` : ""}`)), close),
      modes,
      h("hr.hair"),
      h("div.routesteps", null, turns.map((t) => h("div.row", null, h("span.grow.strong", null, t.words), h("span.small", null, `in ${distance(t.metres)}`))), h("div.row", null, h("span.grow.strong", null, routing.to.floor ? `Arrive, ${routing.to.floor}` : "Arrive"), h("span.small", null, `in ${distance(last)}`))));
  }

  let timer;
  const runSearch = () => {
    const found = searchPlaces(map, field.value);
    results.replaceChildren(...found.map((f) => h("button.mapresult", { type: "button", role: "option", onclick: () => { field.value = f.title; choose(f); } }, h("span.strong", null, f.title), f.note ? h("span.small", null, f.note) : null)));
    results.hidden = !found.length;
    if (field.value.trim().length >= 2 && !found.length) {
      results.replaceChildren(h("div.empty", { style: { padding: "6px 10px" } }, `Nothing called "${field.value}" on the map.`));
      results.hidden = false;
    }
  };
  field.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(runSearch, 90); });
  field.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      const f = searchPlaces(map, field.value)[0];
      if (f) { field.value = f.title; choose(f); }
    } else if (e.key === "Escape") {
      results.hidden = true;
    }
  });

  const sized = () => {
    const box = canvas.parentElement.getBoundingClientRect();
    if (!box.width || !box.height) return;
    const first = w === 1;
    w = box.width;
    h_ = box.height;
    const ratio = devicePixelRatio || 1;
    canvas.width = Math.round(w * ratio);
    canvas.height = Math.round(h_ * ratio);
    canvas.style.width = `${w}px`;
    canvas.style.height = `${h_}px`;
    if (first) {
      cam.x = (map.bounds.minX + map.bounds.maxX) / 2;
      cam.y = (map.bounds.minY + map.bounds.maxY) / 2;
      cam.scale = fitScale();
    }
    dirty = false;
    frame(performance.now());
  };
  const watcher = new ResizeObserver(() => { if (canvas.isConnected) sized(); else watcher.disconnect(); });
  watcher.observe(canvas.parentElement);
  sized();
  drawChips();
  drawButtons();
  drawCards();
  if (args.find) {
    const spot = resolveVenue(map, args.find) || searchPlaces(map, args.find)[0];
    if (spot) choose(spot);
    else {
      field.focus();
      runSearch();
    }
  }
  document.fonts?.ready.then(request);
}

if (typeof module !== "undefined") module.exports = { buildCampus, resolveVenue, searchPlaces, findRoute, routeTurns, roomInRange, floorOf, findPoi, inRing };
