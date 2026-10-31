import { test } from "node:test";
import assert from "node:assert/strict";
import { parsePredictions, parseStations, nextHighAndLow, height, phase, rising, colorRgb, DEFAULT_STATION } from "../js/tidemath.js";

// Same fixture as tidecore's TideMathTest, so web and Android agree.
const sample = `{"predictions":[
  {"t":"2026-10-31 02:10","v":"5.412","type":"H"},
  {"t":"2026-10-31 08:25","v":"0.913","type":"L"},
  {"t":"2026-10-31 14:40","v":"4.801","type":"H"},
  {"t":"2026-10-31 20:55","v":"1.204","type":"L"}]}`;
const at = (s) => Date.parse(s.replace(" ", "T") + ":00Z") / 1000;
const events = parsePredictions(sample);
const near = (a, b, eps = 0.01) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);

test("parses", () => {
  assert.equal(events.length, 4);
  assert.equal(events[0].kind, "H");
  assert.equal(events[1].h, 0.913);
  assert.equal(events[0].t, at("2026-10-31 02:10"));
});

test("next high and low", () => {
  const n = nextHighAndLow(events, at("2026-10-31 09:00"));
  assert.equal(n.high.t, at("2026-10-31 14:40"));
  assert.equal(n.low.t, at("2026-10-31 20:55"));
  assert.equal(nextHighAndLow(events, at("2026-10-31 21:00")).high, null);
});

test("interpolation", () => {
  near(height(events, at("2026-10-31 08:25") + 1), 0.913);
  const mid = at("2026-10-31 11:32") + 30;
  near(height(events, mid), (0.913 + 4.801) / 2);
  near(phase(events, mid), 0.5);
  assert.equal(rising(events, mid), true);
  assert.equal(height(events, at("2026-10-31 22:00")), null);
});

test("colors run blue to red", () => {
  assert.deepEqual(colorRgb(0), [33, 89, 204]);
  assert.deepEqual(colorRgb(1), [224, 56, 51]);
  assert.deepEqual(colorRgb(3), colorRgb(1));
});

test("surfaces NOAA errors", () => {
  assert.throws(() => parsePredictions(`{"error":{"message":"No Predictions data was found."}}`), /NOAA/);
});

test("parses stations", () => {
  const s = parseStations(`{"count":1,"stations":[{"id":"9414290","name":"San Francisco","state":"CA","lat":37.8063,"lng":-122.4659,"extra":1}]}`);
  assert.deepEqual(s, [DEFAULT_STATION]);
});
