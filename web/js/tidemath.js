// Tide math, ported from tidecore/TideMath.kt so web and Android agree.
// An event is { t: epochSeconds (UTC), h: feet above MLLW, kind: "H" | "L" }.

export const DEFAULT_STATION = { id: "9414290", name: "San Francisco", state: "CA", lat: 37.8063, lng: -122.4659 };

/** Parse a CO-OPS "hilo" predictions response. Throws on a NOAA error body. */
export function parsePredictions(body) {
  const resp = typeof body === "string" ? JSON.parse(body) : body;
  if (resp.error) throw new Error(`NOAA: ${resp.error.message}`);
  return (resp.predictions || [])
    .filter((p) => p.type === "H" || p.type === "L")
    .map((p) => ({ t: Date.parse(p.t.replace(" ", "T") + ":00Z") / 1000, h: parseFloat(p.v), kind: p.type }))
    .filter((e) => Number.isFinite(e.t) && Number.isFinite(e.h))
    .sort((a, b) => a.t - b.t);
}

/** Parse the CO-OPS metadata station list. */
export function parseStations(body) {
  const resp = typeof body === "string" ? JSON.parse(body) : body;
  return (resp.stations || []).map((s) => ({ id: String(s.id), name: s.name, state: s.state || null, lat: +s.lat, lng: +s.lng }));
}

/** The next high and next low strictly after nowSec. */
export function nextHighAndLow(events, nowSec) {
  const upcoming = events.filter((e) => e.t > nowSec);
  return { high: upcoming.find((e) => e.kind === "H") || null, low: upcoming.find((e) => e.kind === "L") || null };
}

function bracket(events, atSec) {
  const i = events.findIndex((e) => e.t > atSec);
  if (i <= 0) return null;
  const a = events[i - 1], b = events[i];
  return { a, b, f: (atSec - a.t) / (b.t - a.t) };
}

const ease = (f) => (1 - Math.cos(Math.PI * f)) / 2;

/** Water height at atSec, cosine-interpolated between consecutive highs and lows. */
export function height(events, atSec) {
  const br = bracket(events, atSec);
  return br ? br.a.h + (br.b.h - br.a.h) * ease(br.f) : null;
}

/** 0 at low tide, 1 at high tide, relative to the surrounding tides. Colors the map. */
export function phase(events, atSec) {
  const br = bracket(events, atSec);
  if (!br) return null;
  return br.a.kind === "L" ? ease(br.f) : 1 - ease(br.f);
}

/** True while the water is coming in. */
export function rising(events, atSec) {
  const br = bracket(events, atSec);
  return br ? br.b.kind === "H" : null;
}

const STOPS = [
  [0.0, [33, 89, 204]], [0.35, [26, 179, 191]],
  [0.65, [250, 204, 64]], [1.0, [224, 56, 51]],
];

/** Blue at low tide, through teal and yellow, to red at high tide. Returns [r, g, b]. */
export function colorRgb(p) {
  p = Math.min(1, Math.max(0, p));
  let j = STOPS.findIndex((s) => s[0] >= p);
  if (j < 1) j = 1;
  const [p0, c0] = STOPS[j - 1], [p1, c1] = STOPS[j];
  const f = (p - p0) / (p1 - p0);
  return c0.map((c, k) => Math.trunc(c + (c1[k] - c) * f));
}

export const colorCss = (p) => `rgb(${colorRgb(p).join(",")})`;
