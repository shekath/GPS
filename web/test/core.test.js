// Sanity checks for the web core. Run: node web/test/core.test.js
const core = require("../core.js");
const assert = (c, m) => { if (!c) { console.error("FAIL:", m); process.exit(1); } };
const now = 1792454400000;
const cat = core.syntheticCatalog(now).map(core.makeOrbit);
assert(cat.length === 108 && cat.every(Boolean), "synthetic catalog builds");
for (const [lat, lon] of [[51.48, 0], [12.97, 77.59], [-33.9, 151.2], [70, -150]]) {
  for (let h = 0; h < 24; h += 6) {
    const sky = core.computeSky(cat, { lat, lon, alt: 50 }, now + h * 3600e3);
    const used = sky.sats.filter((s) => s.used);
    assert(used.length >= 6 && sky.dop && sky.dop.hdop < 3, `usable sky at ${lat},${lon} +${h}h`);
    assert(sky.sats.every((s) => s.el >= 5 && s.rangeKm > 19000 && s.rangeKm < 50000), "ranges plausible");
  }
}
const sky = core.computeSky(cat, { lat: 51.48, lon: 0, alt: 50 }, now);
const s = sky.sats[0];
console.log(`${sky.sats.length} visible, ${sky.sats.filter((x) => x.used).length} used, HDOP ${sky.dop.hdop.toFixed(2)}, ` +
  `predicted ±${sky.predH.toFixed(1)} m; ${s.label} ${s.rangeKm.toFixed(0)} ± ${s.rangeErrKm.toFixed(1)} km, ${s.travelMs.toFixed(2)} ms`);
assert(core.parsePrn("GPS BIIR-2  (PRN 13)", core.CONSTELLATIONS.GPS) === 13, "prn gps");
assert(core.parsePrn("GSAT0101 (PRN E11)", core.CONSTELLATIONS.GALILEO) === 11, "prn gal");
assert(core.parsePrn("BEIDOU-3 M1 (C19)", core.CONSTELLATIONS.BEIDOU) === 19, "prn bds");
const o = core.makeOrbit({ OBJECT_NAME: "GPS BIII-1 (PRN 04)", NORAD_CAT_ID: 43873, EPOCH: "2026-10-20T00:00:00.000000",
  MEAN_MOTION: 2.00563, ECCENTRICITY: 0.0105, INCLINATION: 55.2, RA_OF_ASC_NODE: 120.3, ARG_OF_PERICENTER: 45.6, MEAN_ANOMALY: 300.1, BSTAR: 0 });
assert(o && o.prn === 4 && Math.abs(o.epochMs - now) < 1, "OMM epoch parsed as UTC");
console.log("core OK");
