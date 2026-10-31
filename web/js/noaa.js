// NOAA CO-OPS client. Free, no key, US coasts. The API sends CORS headers,
// so the browser can call it directly from GitHub Pages.
import { parsePredictions, parseStations } from "./tidemath.js";

const API = "https://api.tidesandcurrents.noaa.gov";

const pad = (n) => String(n).padStart(2, "0");
const ymd = (d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}`;

/** All stations with tide predictions. Prefers the snapshot baked in at deploy time. */
export async function fetchStations() {
  for (const url of ["./stations.json", `${API}/mdapi/prod/webapi/stations.json?type=tidepredictions`]) {
    try {
      const r = await fetch(url);
      if (!r.ok) continue;
      const list = parseStations(await r.json());
      if (list.length) return list;
    } catch { /* try the next source */ }
  }
  throw new Error("Couldn't load the station list from NOAA");
}

/** Highs and lows from 12h before fromSec through `hours` after it. */
export async function fetchHighsAndLows(stationId, fromSec = Date.now() / 1000, hours = 72) {
  const begin = new Date((fromSec - 12 * 3600) * 1000);
  const q = new URLSearchParams({
    station: stationId, product: "predictions", interval: "hilo", datum: "MLLW",
    units: "english", time_zone: "gmt", format: "json",
    begin_date: ymd(begin), range: String(hours + 36), application: "TideWidget",
  });
  const r = await fetch(`${API}/api/prod/datagetter?${q}`);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return parsePredictions(await r.json());
}
