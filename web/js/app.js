import { DEFAULT_STATION, nextHighAndLow, height, phase, colorCss } from "./tidemath.js";
import { fetchStations, fetchHighsAndLows } from "./noaa.js";

const $ = (id) => document.getElementById(id);
const START = Math.floor(Date.now() / 1000);
const MY_BEACH_KEY = "tidewidget.mybeach";
const MAX_PER_PAN = 60;     // stay polite to NOAA, same cap as the Android app
const CONCURRENCY = 6;

const state = {
  stations: [],
  byId: new Map(),
  forecasts: new Map(),     // id -> events
  loading: new Set(),
  markers: new Map(),       // id -> L.circleMarker
  offsetHours: 0,
  selected: null,
  myBeach: loadMyBeach(),
};

// ---------- formatting ----------
const fmtWhen = new Intl.DateTimeFormat(undefined, { weekday: "short", hour: "numeric", minute: "2-digit" });
const fmtTime = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const isToday = (sec) => new Date(sec * 1000).toDateString() === new Date().toDateString();
const when = (sec) => (isToday(sec) ? fmtTime : fmtWhen).format(new Date(sec * 1000));
const ft = (h) => `${h.toFixed(1)} ft`;
const label = (s) => [s.name, s.state].filter(Boolean).join(", ");
const timeSec = () => START + Math.round(state.offsetHours * 3600);

function showError(msg) {
  const el = $("error");
  el.textContent = msg; el.hidden = !msg;
  if (msg) setTimeout(() => { if (el.textContent === msg) el.hidden = true; }, 8000);
}

// ---------- storage ----------
function loadMyBeach() {
  try {
    const s = JSON.parse(localStorage.getItem(MY_BEACH_KEY));
    if (s && s.id) return s;
  } catch { /* private mode etc. */ }
  return DEFAULT_STATION;
}
function saveMyBeach(s) {
  state.myBeach = { id: s.id, name: s.name, state: s.state, lat: s.lat, lng: s.lng };
  try { localStorage.setItem(MY_BEACH_KEY, JSON.stringify(state.myBeach)); } catch { /* ignore */ }
}

// ---------- data ----------
async function getForecast(id) {
  if (state.forecasts.has(id)) return state.forecasts.get(id);
  const events = await fetchHighsAndLows(id, START - 6 * 3600, 72);
  state.forecasts.set(id, events);
  return events;
}

async function loadVisible() {
  if (!state.stations.length) return;
  const b = map.getBounds(), c = map.getCenter();
  const todo = state.stations
    .filter((s) => b.contains([s.lat, s.lng]) && !state.forecasts.has(s.id) && !state.loading.has(s.id))
    .sort((a, z) => c.distanceTo([a.lat, a.lng]) - c.distanceTo([z.lat, z.lng]))
    .slice(0, MAX_PER_PAN);
  todo.forEach((s) => state.loading.add(s.id));
  let failures = 0;
  const queue = [...todo];
  await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
    for (let s; (s = queue.shift()); ) {
      try { await getForecast(s.id); paintMarker(s.id); }
      catch { failures++; }
      finally { state.loading.delete(s.id); }
    }
  }));
  if (failures && failures === todo.length) showError("Couldn't reach NOAA for tide predictions. Check your connection and try again.");
}

// ---------- map ----------
const dark = matchMedia("(prefers-color-scheme: dark)").matches;
const map = L.map("map", { preferCanvas: true, zoomControl: false, worldCopyJump: true })
  .setView([state.myBeach.lat, state.myBeach.lng], 9);
L.control.zoom({ position: "bottomright" }).addTo(map);
L.tileLayer(`https://{s}.basemaps.cartocdn.com/${dark ? "dark_all" : "light_all"}/{z}/{x}/{y}{r}.png`, {
  subdomains: "abcd", maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>',
}).addTo(map);
const renderer = L.canvas({ padding: 0.5 });
const UNLOADED = dark ? "#53606f" : "#a7b3c2";

function addMarkers() {
  for (const s of state.stations) {
    const m = L.circleMarker([s.lat, s.lng], {
      renderer, radius: 5, weight: 1.5, color: "#fff", fillColor: UNLOADED, fillOpacity: 0.9,
    }).bindTooltip(label(s), { direction: "top", offset: [0, -6] });
    m.on("click", () => openSheet(s));
    m.addTo(map);
    state.markers.set(s.id, m);
  }
}

function paintMarker(id) {
  const m = state.markers.get(id), ev = state.forecasts.get(id);
  if (!m || !ev) return;
  const p = phase(ev, timeSec());
  m.setStyle({ fillColor: p == null ? UNLOADED : colorCss(p), radius: 7, fillOpacity: 1 });
}
const paintAll = () => state.forecasts.forEach((_, id) => paintMarker(id));

map.on("moveend", loadVisible);

// ---------- time scrubber ----------
function setOffset(h) {
  state.offsetHours = h;
  $("slider").value = h;
  $("time").textContent = h === 0 ? "Now" : fmtWhen.format(new Date(timeSec() * 1000));
  $("now").hidden = h === 0;
  paintAll();
}
$("slider").addEventListener("input", (e) => { stopPlay(); setOffset(parseFloat(e.target.value)); });
$("now").addEventListener("click", () => { stopPlay(); setOffset(0); });

let playTimer = null;
function stopPlay() { clearInterval(playTimer); playTimer = null; $("play").textContent = "▶"; }
$("play").addEventListener("click", () => {
  if (playTimer) return stopPlay();
  if (state.offsetHours >= 24) setOffset(0);
  $("play").textContent = "❚❚";
  playTimer = setInterval(() => {
    const next = Math.min(24, state.offsetHours + 0.25);
    setOffset(next);
    if (next >= 24) stopPlay();
  }, 120);
});

// ---------- curves ----------
function drawCurve(svg, events, { w, h, hours = 24, dot = true, pad = 4 }) {
  const ns = "http://www.w3.org/2000/svg";
  svg.replaceChildren();
  const now = Math.floor(Date.now() / 1000);
  const pts = [];
  for (let i = 0; i <= hours * 4; i++) {
    const v = height(events, now + i * 900);
    if (v != null) pts.push([i, v]);
  }
  if (pts.length < 2) return false;
  const vs = pts.map((p) => p[1]);
  const lo = Math.min(...vs), hi = Math.max(Math.max(...vs), lo + 0.1);
  const x = (i) => (w * i) / (hours * 4);
  const y = (v) => pad + (h - 2 * pad) * (1 - (v - lo) / (hi - lo));
  const d = pts.map(([i, v], k) => `${k ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join("");
  const id = `g${Math.random().toString(36).slice(2, 8)}`;
  svg.innerHTML = `<defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="var(--line)" stop-opacity="0.35"/><stop offset="1" stop-color="var(--line)" stop-opacity="0.02"/></linearGradient></defs>
    <path d="${d}L${w},${h}L0,${h}Z" fill="url(#${id})"/>
    <path d="${d}" fill="none" stroke="var(--line)" stroke-width="2.5" vector-effect="non-scaling-stroke" stroke-linejoin="round"/>`;
  if (dot) {
    const c = document.createElementNS(ns, "circle");
    c.setAttribute("cx", x(pts[0][0])); c.setAttribute("cy", y(pts[0][1])); c.setAttribute("r", 4);
    c.setAttribute("fill", "var(--line)");
    svg.appendChild(c);
  }
  return true;
}

// ---------- station sheet ----------
async function openSheet(s) {
  state.selected = s;
  $("sheet").hidden = false;
  $("sheet-title").textContent = label(s);
  $("sheet-sub").textContent = `NOAA station ${s.id} · Loading…`;
  $("sheet-curve").replaceChildren();
  $("sheet-list").replaceChildren();
  updateUseButton();
  try {
    const ev = await getForecast(s.id);
    paintMarker(s.id);
    if (state.selected !== s) return;
    renderSheet(s, ev);
  } catch (e) {
    if (state.selected === s) $("sheet-sub").textContent = `NOAA station ${s.id} · Couldn't load predictions (${e.message})`;
  }
}

function renderSheet(s, ev) {
  const now = Date.now() / 1000;
  const h = height(ev, now);
  $("sheet-sub").textContent = `NOAA station ${s.id}` + (h != null ? ` · ${ft(h)} now` : "");
  drawCurve($("sheet-curve"), ev, { w: 400, h: 140 });
  const items = ev.filter((e) => e.t > now).slice(0, 6).map((e) => {
    const li = document.createElement("li");
    const hi = e.kind === "H";
    li.innerHTML = `<span class="arrow ${hi ? "hi" : "lo"}">${hi ? "▲ High" : "▼ Low"}</span>
      <span class="when">${fmtWhen.format(new Date(e.t * 1000))}</span><span class="ht">${ft(e.h)}</span>`;
    return li;
  });
  $("sheet-list").replaceChildren(...items);
}

function updateUseButton() {
  const mine = state.selected && state.selected.id === state.myBeach.id;
  $("sheet-use").disabled = !!mine;
  $("sheet-use").textContent = mine ? "This is my beach" : "Make this my beach";
}

$("sheet-close").addEventListener("click", () => { $("sheet").hidden = true; state.selected = null; });
$("sheet-use").addEventListener("click", () => {
  if (!state.selected) return;
  saveMyBeach(state.selected);
  updateUseButton();
  renderMyBeach();
});
document.addEventListener("keydown", (e) => { if (e.key === "Escape") $("sheet-close").click(); });

// ---------- my beach card (the widget) ----------
async function renderMyBeach() {
  const s = state.myBeach;
  $("mb-name").textContent = s.name;
  let ev;
  try { ev = await getForecast(s.id); }
  catch { $("mb-high").textContent = "Offline"; $("mb-low").textContent = "—"; return; }
  if (s !== state.myBeach) return;
  const now = Date.now() / 1000;
  const { high, low } = nextHighAndLow(ev, now);
  const row = (e) => (e ? `${when(e.t)} <small>${ft(e.h)}</small>` : "—");
  $("mb-high").innerHTML = row(high);
  $("mb-low").innerHTML = row(low);
  drawCurve($("mb-curve"), ev, { w: 200, h: 36, pad: 3 });
  paintMarker(s.id);
}
$("mybeach").addEventListener("click", () => {
  const s = state.byId.get(state.myBeach.id) || state.myBeach;
  map.flyTo([s.lat, s.lng], Math.max(map.getZoom(), 10), { duration: 0.8 });
  openSheet(s);
});

// Like the widget, refresh just after each tide turn; a minute tick is simple and cheap.
setInterval(() => { renderMyBeach(); if (state.selected && state.forecasts.has(state.selected.id)) renderSheet(state.selected, state.forecasts.get(state.selected.id)); }, 60_000);

// ---------- search ----------
let results = [], active = -1;
function renderResults() {
  const ul = $("results");
  ul.replaceChildren(...results.map((s, i) => {
    const li = document.createElement("li");
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", i === active);
    li.innerHTML = `${s.name}${s.state ? `, ${s.state}` : ""} <small>${s.id}</small>`;
    li.addEventListener("mousedown", (e) => { e.preventDefault(); pick(s); });
    return li;
  }));
  ul.hidden = !results.length;
}
function pick(s) {
  $("search").value = ""; results = []; renderResults(); $("search").blur();
  map.flyTo([s.lat, s.lng], 11, { duration: 0.8 });
  openSheet(s);
}
$("search").addEventListener("input", (e) => {
  const q = e.target.value.trim().toLowerCase();
  active = -1;
  results = q.length < 2 ? [] : state.stations
    .filter((s) => s.id === q || `${s.name} ${s.state || ""}`.toLowerCase().includes(q))
    .slice(0, 8);
  renderResults();
});
$("search").addEventListener("keydown", (e) => {
  if (!results.length) return;
  if (e.key === "ArrowDown") { active = (active + 1) % results.length; renderResults(); e.preventDefault(); }
  else if (e.key === "ArrowUp") { active = (active - 1 + results.length) % results.length; renderResults(); e.preventDefault(); }
  else if (e.key === "Enter") pick(results[Math.max(active, 0)]);
  else if (e.key === "Escape") { results = []; renderResults(); }
});
$("search").addEventListener("blur", () => setTimeout(() => { results = []; renderResults(); }, 100));

// ---------- start ----------
setOffset(0);
renderMyBeach();
fetchStations()
  .then((list) => {
    state.stations = list;
    list.forEach((s) => state.byId.set(s.id, s));
    addMarkers();
    paintAll();
    loadVisible();
  })
  .catch((e) => showError(e.message));
