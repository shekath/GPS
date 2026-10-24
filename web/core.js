// GNSS Globe web core: geometry, orbits, sky prediction and error model.
// Mirrors the Android app (Geo.kt, Orbit.kt, ErrorModel.kt, DemoSky.kt). No DOM access.
(function (root) {
  "use strict";
  const SGP4 = root.SGP4 || (typeof require !== "undefined" ? require("./sgp4.js") : null);
  const { sin, cos, sqrt, atan2, asin, abs, hypot, PI } = Math;
  const D2R = PI / 180, R2D = 180 / PI;

  // ------------------------------------------------------------------ geometry (WGS-84, km)
  const A = 6378.137, F = 1 / 298.257223563, E2 = F * (2 - F);
  const MEAN_RADIUS = 6371.0088;
  const C_KM_S = 299792.458;

  function geodeticToEcef(lat, lon, altKm) {
    const la = lat * D2R, lo = lon * D2R;
    const n = A / sqrt(1 - E2 * sin(la) * sin(la));
    return [(n + altKm) * cos(la) * cos(lo), (n + altKm) * cos(la) * sin(lo), (n * (1 - E2) + altKm) * sin(la)];
  }

  function ecefToGeodetic(p) {
    const lon = atan2(p[1], p[0]);
    const r = hypot(p[0], p[1]);
    let lat = atan2(p[2], r * (1 - E2)), alt = 0;
    for (let i = 0; i < 6; i++) {
      const n = A / sqrt(1 - E2 * sin(lat) * sin(lat));
      alt = r / cos(lat) - n;
      lat = atan2(p[2], r * (1 - E2 * n / (n + alt)));
    }
    return [lat * R2D, lon * R2D, alt];
  }

  function azEl(lat, lon, obs, tgt) {
    const dx = tgt[0] - obs[0], dy = tgt[1] - obs[1], dz = tgt[2] - obs[2];
    const la = lat * D2R, lo = lon * D2R;
    const e = -sin(lo) * dx + cos(lo) * dy;
    const n = -sin(la) * cos(lo) * dx - sin(la) * sin(lo) * dy + cos(la) * dz;
    const u = cos(la) * cos(lo) * dx + cos(la) * sin(lo) * dy + sin(la) * dz;
    let az = atan2(e, n) * R2D;
    if (az < 0) az += 360;
    return [az, asin(u / hypot(dx, dy, dz)) * R2D];
  }

  const dist = (a, b) => hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

  // ------------------------------------------------------------------ constellations
  const CONSTELLATIONS = {
    GPS: { id: 1, label: "GPS", prefix: "G", color: "#4ADE80" },
    SBAS: { id: 2, label: "SBAS", prefix: "S", color: "#B0BEC5" },
    GLONASS: { id: 3, label: "GLONASS", prefix: "R", color: "#F87171" },
    QZSS: { id: 4, label: "QZSS", prefix: "J", color: "#C084FC" },
    BEIDOU: { id: 5, label: "BeiDou", prefix: "C", color: "#FBBF24" },
    GALILEO: { id: 6, label: "Galileo", prefix: "E", color: "#60A5FA" },
    IRNSS: { id: 7, label: "NavIC", prefix: "I", color: "#F472B6" },
    UNKNOWN: { id: 0, label: "Other", prefix: "?", color: "#9CA3AF" }
  };

  function constellationFromName(name) {
    const n = name.toUpperCase();
    if (n.includes("NAVSTAR") || n.startsWith("GPS")) return CONSTELLATIONS.GPS;
    if (n.includes("GLONASS") || n.startsWith("COSMOS")) return CONSTELLATIONS.GLONASS;
    if (n.includes("GALILEO") || n.startsWith("GSAT")) return CONSTELLATIONS.GALILEO;
    if (n.includes("BEIDOU")) return CONSTELLATIONS.BEIDOU;
    if (n.startsWith("QZS") || n.includes("MICHIBIKI")) return CONSTELLATIONS.QZSS;
    if (n.includes("IRNSS") || n.startsWith("NVS")) return CONSTELLATIONS.IRNSS;
    return CONSTELLATIONS.UNKNOWN;
  }

  function parsePrn(name, c) {
    const n = name.toUpperCase();
    let m = null;
    if (c === CONSTELLATIONS.BEIDOU) m = /\(C(\d+)\)/.exec(n) || /PRN\s*[A-Z]?(\d+)/.exec(n);
    else if (c === CONSTELLATIONS.GALILEO) m = /PRN\s*[A-Z]?(\d+)/.exec(n) || /\(E(\d+)\)/.exec(n);
    else if (c === CONSTELLATIONS.GPS || c === CONSTELLATIONS.QZSS) m = /PRN\s*[A-Z]?(\d+)/.exec(n);
    return m ? parseInt(m[1], 10) : null;
  }

  // ------------------------------------------------------------------ orbits
  function temeToEcef(p, tMs) {
    const th = SGP4.gstime(tMs / 86400000 + 2440587.5);
    const c = cos(th), s = sin(th);
    return [c * p[0] + s * p[1], -s * p[0] + c * p[1], p[2]];
  }

  function makeOrbit(o) {
    const epochMs = typeof o.EPOCH === "number" ? o.EPOCH : Date.parse(o.EPOCH.endsWith("Z") ? o.EPOCH : o.EPOCH + "Z");
    if (!isFinite(epochMs)) return null;
    const sat = SGP4.Sgp4(epochMs / 86400000 + 2440587.5 - 2433281.5, +o.BSTAR || 0, +o.ECCENTRICITY,
      o.ARG_OF_PERICENTER * D2R, o.INCLINATION * D2R, o.MEAN_ANOMALY * D2R,
      o.MEAN_MOTION * 2 * PI / 1440, o.RA_OF_ASC_NODE * D2R);
    if (!(sat.semiMajorAxisKm > 15000)) return null; // navigation satellites only
    const c = constellationFromName(o.OBJECT_NAME || "");
    return {
      name: o.OBJECT_NAME, norad: +o.NORAD_CAT_ID, epochMs, c, prn: parsePrn(o.OBJECT_NAME || "", c),
      periodMin: 2 * PI / sat.noUnkozai,
      ecef: (tMs) => temeToEcef(sat.position((tMs - epochMs) / 60000), tMs),
      ageDays: (tMs) => abs(tMs - epochMs) / 86400000,
      // SGP4 with published GNSS elements: a few km near epoch, growing with element age.
      errKm: (tMs) => 3 + 2 * abs(tMs - epochMs) / 86400000
    };
  }

  /** Walker-style layouts approximating GPS, Galileo, GLONASS, BeiDou and QZSS (offline fallback). */
  function syntheticCatalog(nowMs) {
    const out = [];
    let id = 90000;
    const add = (name, mm, e, inc, raan, argp, m) => out.push({
      OBJECT_NAME: name, NORAD_CAT_ID: id++, EPOCH: nowMs, MEAN_MOTION: mm, ECCENTRICITY: e,
      INCLINATION: inc, RA_OF_ASC_NODE: raan, ARG_OF_PERICENTER: argp, MEAN_ANOMALY: m, BSTAR: 0
    });
    const pad = (n) => String(n).padStart(2, "0");
    for (let p = 0; p < 6; p++) for (let s = 0; s < 5; s++)
      add(`GPS SIM (PRN ${p * 5 + s + 1})`, 2.00561, 0.008, 55, p * 60 + 12, 30, s * 72 + p * 15);
    for (let p = 0; p < 3; p++) for (let s = 0; s < 8; s++)
      add(`GALILEO SIM (PRN E${pad(p * 8 + s + 1)})`, 1.70475, 0.0003, 56, p * 120 + 40, 0, s * 45 + p * 15);
    for (let p = 0; p < 3; p++) for (let s = 0; s < 8; s++)
      add(`GLONASS SIM ${p * 8 + s + 1}`, 2.13102, 0.001, 64.8, p * 120 + 80, 0, s * 45 + p * 15);
    for (let p = 0; p < 3; p++) for (let s = 0; s < 8; s++)
      add(`BEIDOU SIM M (C${19 + p * 8 + s})`, 1.86232, 0.001, 55, p * 120 + 100, 0, s * 45 + p * 15);
    for (let k = 0; k < 3; k++) add(`BEIDOU SIM G (C${59 + k})`, 1.00272, 0.0002, 0.5, 80 + k * 60, 0, 30);
    for (let k = 0; k < 3; k++) add(`QZS SIM (PRN ${194 + k})`, 1.00272, 0.075, 41, 150 + k * 120, 270, 10 + k * 120);
    return out;
  }

  // ------------------------------------------------------------------ error model
  /** 1-sigma user-equivalent range error budget for a single-frequency receiver, metres. */
  function rangeBudget(elDeg, cn0) {
    const el = Math.max(elDeg, 2) * D2R;
    const k = MEAN_RADIUS * cos(el) / (MEAN_RADIUS + 350);
    const obliquity = 1 / sqrt(1 - k * k);
    const tropoMap = 1.001 / sqrt(0.002001 + sin(el) * sin(el));
    const b = {
      clockEphemeris: 1.0,
      ionosphere: 2.0 * obliquity,
      troposphere: 0.2 * tropoMap,
      multipath: 1.0 + 3.0 * Math.exp(-Math.max(elDeg, 0) / 15),
      receiverNoise: 0.5 * sqrt(Math.pow(10, (45 - Math.min(55, Math.max(10, cn0))) / 10))
    };
    b.total = hypot(b.clockEphemeris, b.ionosphere, b.troposphere, b.multipath, b.receiverNoise);
    return b;
  }

  function invert4(m) {
    const a = m.map((r, i) => [...r, ...[0, 0, 0, 0].map((_, j) => (i === j ? 1 : 0))]);
    for (let col = 0; col < 4; col++) {
      let piv = col;
      for (let r = col + 1; r < 4; r++) if (abs(a[r][col]) > abs(a[piv][col])) piv = r;
      if (abs(a[piv][col]) < 1e-12) return null;
      [a[col], a[piv]] = [a[piv], a[col]];
      const d = a[col][col];
      for (let c = 0; c < 8; c++) a[col][c] /= d;
      for (let r = 0; r < 4; r++) if (r !== col) {
        const f = a[r][col];
        if (f) for (let c = 0; c < 8; c++) a[r][c] -= f * a[col][c];
      }
    }
    return a.map((r) => r.slice(4));
  }

  /** Least-squares fix geometry for [[az, el], ...] in degrees. */
  function dop(azEls) {
    const n = azEls.length;
    if (n < 4) return null;
    const g = azEls.map(([az, el]) => {
      const a = az * D2R, e = el * D2R;
      return [-cos(e) * sin(a), -cos(e) * cos(a), -sin(e), 1];
    });
    const gtg = [0, 1, 2, 3].map((r) => [0, 1, 2, 3].map((c) => g.reduce((s, row) => s + row[r] * row[c], 0)));
    const q = invert4(gtg);
    if (!q || q[0][0] < 0 || q[1][1] < 0 || q[2][2] < 0 || q[3][3] < 0) return null;
    const hGain = [], vGain = [];
    for (const row of g) {
      // column of S = Q·Gᵀ: how a range error on this satellite moves (E, N, U, clock)
      const s = [0, 1, 2, 3].map((r) => q[r][0] * row[0] + q[r][1] * row[1] + q[r][2] * row[2] + q[r][3] * row[3]);
      hGain.push(hypot(s[0], s[1]));
      vGain.push(abs(s[2]));
    }
    return {
      gdop: sqrt(q[0][0] + q[1][1] + q[2][2] + q[3][3]), pdop: sqrt(q[0][0] + q[1][1] + q[2][2]),
      hdop: sqrt(q[0][0] + q[1][1]), vdop: sqrt(q[2][2]), tdop: sqrt(q[3][3]), hGain, vGain
    };
  }

  /** Scatter of fixes around their mean, metres. */
  function fixStats(fixes) {
    if (fixes.length < 3) return null;
    const mLat = fixes.reduce((s, f) => s + f.lat, 0) / fixes.length;
    const mLon = fixes.reduce((s, f) => s + f.lon, 0) / fixes.length;
    const r = MEAN_RADIUS * 1000, cl = cos(mLat * D2R);
    const offsets = fixes.map((f) => [(f.lon - mLon) * D2R * cl * r, (f.lat - mLat) * D2R * r]);
    const radii = offsets.map((o) => hypot(o[0], o[1])).sort((a, b) => a - b);
    const pct = (p) => radii[Math.floor((radii.length - 1) * p)];
    return {
      count: fixes.length, offsets, cep50: pct(0.5), r95: pct(0.95),
      rms: sqrt(radii.reduce((s, v) => s + v * v, 0) / radii.length), max: radii[radii.length - 1],
      spanS: (fixes[fixes.length - 1].t - fixes[0].t) / 1000
    };
  }

  /** Low-precision solar direction (Earth-fixed unit vector). */
  function sunEcef(tMs) {
    const d = tMs / 86400000 + 2440587.5 - 2451545.0;
    const g = (((357.529 + 0.98560028 * d) % 360) + 360) % 360 * D2R;
    const q = (((280.459 + 0.98564736 * d) % 360) + 360) % 360;
    const l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * D2R;
    const e = (23.439 - 0.00000036 * d) * D2R;
    return temeToEcef([cos(l), cos(e) * sin(l), sin(e) * sin(l)], tMs);
  }

  // ------------------------------------------------------------------ sky prediction
  const MAX_USED = 18;

  /**
   * Satellites above the observer with distance, error margins and fix geometry.
   * A browser cannot read the phone's GNSS chip, so the set a receiver would track and
   * use is predicted from the orbits (strongest signals ≥ 10° elevation).
   */
  function computeSky(catalog, loc, nowMs, options) {
    const user = geodeticToEcef(loc.lat, loc.lon, (loc.alt || 0) / 1000);
    const glonassSlot = options && options.glonassSlots ? options.glonassSlots : new Map();
    const all = [], sats = [];
    for (const o of catalog) {
      if (o.c === CONSTELLATIONS.UNKNOWN) continue;
      let p;
      try { p = o.ecef(nowMs); } catch (e) { continue; }
      if (!p.every(isFinite)) continue;
      all.push({ o, p });
      const [az, el] = azEl(loc.lat, loc.lon, user, p);
      if (el < 5) continue;
      let svid = o.prn;
      if (o.c === CONSTELLATIONS.GLONASS) {
        if (!glonassSlot.has(o.norad)) glonassSlot.set(o.norad, glonassSlot.size % 24 + 1);
        svid = glonassSlot.get(o.norad);
      }
      if (svid == null) svid = o.norad % 100;
      const bias = ((o.norad * 7919) % 7) - 3;
      const cn0 = Math.min(52, Math.max(12, 18 + 29 * sin(el * D2R) + bias));
      const range = dist(p, user);
      const pNext = o.ecef(nowMs + 1000);
      const [subLat, subLon, alt] = ecefToGeodetic(p);
      sats.push({
        key: o.c.id * 1000 + svid, label: o.c.prefix + svid, c: o.c, svid, orbit: o, ecef: p,
        az, el, cn0, used: false, rangeKm: range, rangeErrKm: o.errKm(nowMs),
        rateKmS: dist(pNext, user) - range, altKm: alt, subLat, subLon,
        travelMs: range / C_KM_S * 1000, budget: rangeBudget(el, cn0), groundM: null, vertM: null
      });
    }
    const used = sats.filter((s) => s.el > 10 && s.cn0 > 25).sort((a, b) => b.cn0 - a.cn0).slice(0, MAX_USED);
    used.forEach((s) => { s.used = true; });
    const geo = dop(used.map((s) => [s.az, s.el]));
    let predH = null, predV = null;
    if (geo) {
      let h2 = 0, v2 = 0;
      used.forEach((s, j) => {
        s.groundM = geo.hGain[j] * s.budget.total;
        s.vertM = geo.vGain[j] * s.budget.total;
        h2 += s.groundM * s.groundM; v2 += s.vertM * s.vertM;
      });
      predH = sqrt(h2); predV = sqrt(v2);
    }
    sats.sort((a, b) => (b.used - a.used) || (b.cn0 - a.cn0));
    const usedOrbits = new Set(sats.map((s) => s.orbit));
    return {
      timeMs: nowMs, user, sats, dop: geo, predH, predV,
      catalog: all.filter((x) => !usedOrbits.has(x.o)).map((x) => ({ c: x.o.c, p: x.p }))
    };
  }

  const api = {
    D2R, R2D, MEAN_RADIUS, C_KM_S, CONSTELLATIONS, geodeticToEcef, ecefToGeodetic, azEl, dist,
    constellationFromName, parsePrn, makeOrbit, syntheticCatalog, rangeBudget, dop, fixStats,
    sunEcef, computeSky, temeToEcef
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.GnssCore = api;
})(typeof self !== "undefined" ? self : this);
