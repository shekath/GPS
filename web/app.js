// GNSS Globe web app: data loading, location, globe/sky rendering and readout panels.
(function () {
  "use strict";
  const G = window.GnssCore;
  const { sin, cos, sqrt, hypot, abs, min, max, PI } = Math;
  const D2R = PI / 180;
  const R_KM = G.MEAN_RADIUS;
  const $ = (id) => document.getElementById(id);

  const CELESTRAK = "https://celestrak.org/NORAD/elements/gp.php?GROUP=gnss&FORMAT=json";
  const CACHE_KEY = "gnss-globe-omm";
  const REFRESH_MS = 8 * 3600e3;
  const PLACES = [
    ["Greenwich, UK", 51.4779, -0.0015, 46], ["Bengaluru, India", 12.9716, 77.5946, 920],
    ["New York, USA", 40.7128, -74.006, 10], ["São Paulo, Brazil", -23.5505, -46.6333, 760],
    ["Tokyo, Japan", 35.6762, 139.6503, 40], ["Sydney, Australia", -33.8688, 151.2093, 58],
    ["Nairobi, Kenya", -1.2921, 36.8219, 1795], ["Reykjavík, Iceland", 64.1466, -21.9426, 20]
  ];

  // ------------------------------------------------------------------ state
  const state = {
    catalog: [], orbitSource: "loading", orbitAgeH: null,
    base: { lat: PLACES[0][1], lon: PLACES[0][2], alt: PLACES[0][3], name: PLACES[0][0] },
    loc: null, locSource: "simulated", fixes: [], sky: null, selected: null,
    glonassSlots: new Map(), tab: "globe"
  };
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* storage unavailable */ } }
  };

  // ------------------------------------------------------------------ orbit data
  function useOmm(list, source, fetchedAt) {
    const orbits = list.map((o) => { try { return G.makeOrbit(o); } catch (e) { return null; } }).filter(Boolean);
    if (!orbits.length) return false;
    state.catalog = orbits;
    state.orbitSource = source;
    state.orbitAgeH = fetchedAt ? (Date.now() - fetchedAt) / 3600e3 : null;
    state.glonassSlots.clear();
    update();
    return true;
  }

  async function loadOrbits() {
    let cached = null;
    try { cached = JSON.parse(store.get(CACHE_KEY) || "null"); } catch (e) { cached = null; }
    if (cached && cached.data) useOmm(cached.data, "cached", cached.at);
    if (cached && Date.now() - cached.at < REFRESH_MS) { state.orbitSource = "live"; renderStatus(); return; }
    try {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 9000);
      const res = await fetch(CELESTRAK, { signal: ctl.signal });
      clearTimeout(timer);
      if (!res.ok) throw new Error("HTTP " + res.status);
      const data = await res.json();
      if (useOmm(data, "live", Date.now())) store.set(CACHE_KEY, JSON.stringify({ at: Date.now(), data }));
    } catch (e) {
      if (!state.catalog.length) useOmm(G.syntheticCatalog(Date.now()), "synthetic", null);
    }
    renderStatus();
  }

  // ------------------------------------------------------------------ location
  let watchId = null;
  let noiseE = 0, noiseN = 0;
  const gauss = () => sqrt(-2 * Math.log(max(Math.random(), 1e-12))) * cos(2 * PI * Math.random());

  function startGeolocation() {
    if (!("geolocation" in navigator)) { setLocError("This browser has no location support."); return; }
    if (watchId !== null) navigator.geolocation.clearWatch(watchId);
    $("loc-chip").textContent = "Asking for location…";
    watchId = navigator.geolocation.watchPosition((pos) => {
      const c = pos.coords;
      if (state.locSource !== "browser") state.fixes = [];
      state.locSource = "browser";
      state.loc = { lat: c.latitude, lon: c.longitude, alt: c.altitude ?? 0, hasAlt: c.altitude != null, acc: c.accuracy, vacc: c.altitudeAccuracy };
      state.base = { lat: c.latitude, lon: c.longitude, alt: state.loc.alt, name: "Your location" };
      $("place").value = "";
      state.fixes.push({ lat: c.latitude, lon: c.longitude, t: Date.now() });
      if (state.fixes.length > 600) state.fixes.shift();
      if (!centeredOnce) centerOn(c.latitude, c.longitude);
      update();
    }, (err) => {
      setLocError(err.code === 1 ? "Location access denied" : "Location unavailable");
    }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 });
  }

  function setLocError(msg) {
    if (state.locSource === "browser") return;
    state.locError = msg;
    renderStatus();
  }

  /** Simulated receiver: fixes wander a few metres around the chosen place. */
  function simulateFix(now) {
    noiseE = 0.9 * noiseE + gauss() * 1.2;
    noiseN = 0.9 * noiseN + gauss() * 1.2;
    const r = R_KM * 1000, b = state.base;
    state.loc = {
      lat: b.lat + noiseN / r / D2R, lon: b.lon + noiseE / (r * cos(b.lat * D2R)) / D2R,
      alt: b.alt + gauss() * 2, acc: 3.4 + Math.random() * 0.8, vacc: 5.5 + Math.random()
    };
    state.fixes.push({ lat: state.loc.lat, lon: state.loc.lon, t: now });
    if (state.fixes.length > 600) state.fixes.shift();
  }

  function choosePlace(i) {
    const p = PLACES[i];
    if (watchId !== null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
    state.locSource = "simulated";
    state.locError = null;
    state.base = { lat: p[1], lon: p[2], alt: p[3], name: p[0] };
    state.fixes = [];
    centerOn(p[1], p[2]);
    update();
  }

  // ------------------------------------------------------------------ update loop (1 Hz)
  function update() {
    const now = Date.now();
    if (state.locSource === "simulated") simulateFix(now);
    if (!state.loc || !state.catalog.length) { renderStatus(); return; }
    state.sky = G.computeSky(state.catalog, state.loc, now, { glonassSlots: state.glonassSlots });
    state.sun = G.sunEcef(now);
    if (now - trailsAt > 20000) trails.clear();
    renderStatus();
    renderLegend();
    renderCard();
    if (state.tab === "sky" || wide()) renderSky();
    if (state.tab === "sats" || wide()) renderSatList();
    if (state.tab === "acc" || wide()) renderAccuracy();
  }

  const wide = () => window.matchMedia("(min-width: 861px)").matches;
  const fmtKm = (v) => Math.round(v).toLocaleString("en-US") + " km";
  const fmtM = (v) => v >= 1000 ? (v / 1000).toFixed(2) + " km" : v >= 100 ? v.toFixed(0) + " m" : v.toFixed(1) + " m";
  const fmtLatLon = (lat, lon, d = 5) =>
    `${abs(lat).toFixed(d)}°${lat >= 0 ? "N" : "S"} ${abs(lon).toFixed(d)}°${lon >= 0 ? "E" : "W"}`;

  function renderStatus() {
    const lc = $("loc-chip"), oc = $("orbit-chip");
    const simulated = state.locSource === "simulated";
    lc.className = "chip " + (simulated ? "demo" : "good");
    lc.textContent = simulated ? "Simulated position" : "Browser location";
    const map = {
      live: ["info", `${state.catalog.length} live orbits`], cached: ["info", `${state.catalog.length} cached orbits`],
      synthetic: ["demo", "Synthetic orbits"], loading: ["warn", "Loading orbits…"]
    };
    const [cls, txt] = map[state.orbitSource] || map.loading;
    oc.className = "chip " + cls;
    oc.textContent = txt;
    $("banner").hidden = !(simulated || state.orbitSource === "synthetic");
    $("banner").textContent = state.orbitSource === "synthetic" ? "SIMULATED SKY · SYNTHETIC ORBITS" : "SIMULATED POSITION";
    const parts = [];
    if (state.loc) {
      parts.push(fmtLatLon(state.loc.lat, state.loc.lon));
      if (state.loc.acc) parts.push("±" + fmtM(state.loc.acc));
      if (simulated) parts.push(state.base.name);
      else if (state.loc.hasAlt) parts.push("alt " + Math.round(state.loc.alt) + " m");
    }
    if (state.sky) parts.push(`${state.sky.sats.filter((s) => s.used).length}/${state.sky.sats.length} sats`);
    if (state.locError && simulated) parts.push(state.locError);
    $("where").textContent = parts.join("  ·  ") || "—";
    $("use-location").classList.toggle("on", state.locSource === "browser");
  }

  function renderLegend() {
    const groups = new Map();
    for (const s of state.sky.sats) {
      const g = groups.get(s.c) || { used: 0, all: 0 };
      g.all++; if (s.used) g.used++;
      groups.set(s.c, g);
    }
    $("legend").innerHTML = [...groups.entries()].sort((a, b) => a[0].id - b[0].id).map(([c, g]) =>
      `<div><i style="background:${c.color}"></i><span>${c.label}</span><span>${g.used}/${g.all}</span></div>`).join("");
    const t = new Date(state.sky.timeMs);
    $("clock").innerHTML = `${t.toISOString().slice(11, 19)} UTC<br>SGP4 · ${state.catalog.length} orbits`;
  }

  function satLines(s) {
    const rows = [
      ["Distance", `${fmtKm(s.rangeKm)} ± ${s.rangeErrKm.toFixed(1)} km`],
      ["Altitude", fmtKm(s.altKm)],
      ["Elev / Azim", `${s.el.toFixed(1)}° / ${s.az.toFixed(1)}°`],
      ["Travel time", `${s.travelMs.toFixed(2)} ms`],
      ["Range rate", `${s.rateKmS >= 0 ? "+" : ""}${s.rateKmS.toFixed(3)} km/s`],
      ["Signal", `${s.cn0.toFixed(0)} dB-Hz (modelled)`],
      ["Range error", `${fmtM(s.budget.total)} (1σ signal)`]
    ];
    if (s.groundM != null) rows.push(["Ground error", `${fmtM(s.groundM)} horiz · ${fmtM(s.vertM)} vert`]);
    rows.push(["Beneath it", fmtLatLon(s.subLat, s.subLon, 2)]);
    rows.push(["Object", `${s.orbit.name} #${s.orbit.norad}`]);
    return rows;
  }

  const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  function renderCard() {
    const card = $("card");
    const s = state.sky && state.sky.sats.find((x) => x.key === state.selected);
    if (!s) { card.hidden = true; return; }
    card.hidden = false;
    card.innerHTML = `<button class="close" type="button" aria-label="Close">×</button>
      <h3><i style="background:${s.c.color}"></i>${s.label} ${s.c.label}<small>${s.used ? "used in fix" : "in view"}</small></h3>
      <dl class="kv">${satLines(s).map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join("")}</dl>`;
    card.querySelector(".close").onclick = () => select(null);
  }

  function select(key, fly) {
    state.selected = key;
    renderCard();
    if (state.tab === "sats" || wide()) renderSatList();
    if (fly) {
      const s = state.sky.sats.find((x) => x.key === key);
      if (s) centerOn(s.subLat, s.subLon);
    }
  }

  // ------------------------------------------------------------------ side panels
  function renderSatList() {
    const sats = state.sky.sats;
    $("sats-title").textContent = `${sats.length} satellites in view · ${sats.filter((s) => s.used).length} used`;
    $("sat-list").innerHTML = sats.map((s) => `
      <button class="sat${s.key === state.selected ? " sel" : ""}" data-key="${s.key}" type="button">
        <span class="badge${s.used ? "" : " unused"}" style="background:${s.c.color};color:${s.used ? "var(--space)" : s.c.color}">${s.label}</span>
        <span class="name">${s.c.label}${s.used ? " ✓" : ""}</span>
        <span class="range">${fmtKm(s.rangeKm)}</span>
        <span class="meta">el ${s.el.toFixed(0)}° az ${s.az.toFixed(0)}° · alt ${fmtKm(s.altKm)} · ${s.travelMs.toFixed(1)} ms</span>
        <span class="err">± ${s.rangeErrKm.toFixed(1)} km</span>
      </button>`).join("");
  }

  function renderAccuracy() {
    const sky = state.sky, loc = state.loc;
    const tile = (label, value, color) => `<div class="tile" style="--c:${color}"><span>${label}</span><b>${value}</b></div>`;
    $("acc-tiles").innerHTML =
      tile("Reported", loc.acc ? "±" + fmtM(loc.acc) : "—", "var(--accent)") +
      tile("Predicted 95%", sky.predH ? "±" + fmtM(sky.predH * 2) : "—", "var(--warn)") +
      tile("Vertical", loc.vacc ? "±" + fmtM(loc.vacc) : sky.predV ? "±" + fmtM(sky.predV) : "—", "var(--demo)");
    $("acc-note").textContent = state.locSource === "browser"
      ? "Reported is your browser's accuracy figure (95% confidence by the web standard; Android browsers often pass the receiver's 68% value)."
      : "Simulated position: fixes wander a few metres around the chosen place, as a phone's would.";
    const d = sky.dop;
    $("dop-rows").innerHTML = d ? [
      ["GDOP / PDOP", `${d.gdop.toFixed(2)} / ${d.pdop.toFixed(2)}`],
      ["HDOP / VDOP / TDOP", `${d.hdop.toFixed(2)} / ${d.vdop.toFixed(2)} / ${d.tdop.toFixed(2)}`],
      ["Horizontal (1σ / 95%)", `±${fmtM(sky.predH)} / ±${fmtM(sky.predH * 2)}`],
      ["Vertical (1σ)", `±${fmtM(sky.predV)}`],
      ["Geometry", d.pdop < 2 ? "excellent" : d.pdop < 4 ? "good" : d.pdop < 6 ? "moderate" : "poor"]
    ].map(([k, v]) => `<div><span>${k}</span><b>${v}</b></div>`).join("") : "<div>Needs at least 4 satellites.</div>";

    const st = G.fixStats(state.fixes);
    drawScatter(st, loc.acc);
    $("scatter-rows").innerHTML = st ? [
      ["Fixes", `${st.count} over ${st.spanS.toFixed(0)} s`], ["CEP50 / R95", `${fmtM(st.cep50)} / ${fmtM(st.r95)}`],
      ["RMS / max", `${fmtM(st.rms)} / ${fmtM(st.max)}`]
    ].map(([k, v]) => `<div><span>${k}</span><b>${v}</b></div>`).join("") : "<div>Collecting fixes… keep still.</div>";

    const used = sky.sats.filter((s) => s.groundM != null).sort((a, b) => b.groundM - a.groundM);
    const top = max(0.5, ...used.map((s) => s.groundM));
    $("contrib").innerHTML = used.map((s) => `<div class="bar"><span>${s.label}</span>
      <i style="width:${max(2, s.groundM / top * 100)}%;background:${s.c.color}"></i><span>${fmtM(s.groundM)}</span></div>`).join("");
    const b = used.map((s) => s.budget);
    const med = (f) => b.map(f).sort((x, y) => x - y)[b.length >> 1];
    $("budget").innerHTML = b.length ? [
      ["Satellite clock & orbit", med((x) => x.clockEphemeris)], ["Ionosphere (residual)", med((x) => x.ionosphere)],
      ["Troposphere (residual)", med((x) => x.troposphere)], ["Multipath", med((x) => x.multipath)],
      ["Receiver noise", med((x) => x.receiverNoise)], ["Total UERE (1σ)", med((x) => x.total)]
    ].map(([k, v]) => `<div><span>${k}</span><b>${fmtM(v)}</b></div>`).join("") : "";
  }

  function fitCanvas(c) {
    const dpr = window.devicePixelRatio || 1;
    const w = c.clientWidth, h = c.clientHeight;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr); c.height = Math.round(h * dpr);
    }
    const ctx = c.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return [ctx, w, h];
  }

  function drawScatter(st, acc) {
    const [ctx, w, h] = fitCanvas($("scatter"));
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "#111b2e";
    roundRect(ctx, 0, 0, w, h, 14); ctx.fill();
    const cx = w / 2, cy = h / 2;
    const extent = max(max(st ? st.r95 : 0, st ? st.max : 0) * 1.15, max(acc || 0, 5) * 1.2);
    const k = (w / 2 - 14) / extent;
    ctx.strokeStyle = "rgba(96,165,250,0.25)"; ctx.lineWidth = 1;
    line(ctx, 8, cy, w - 8, cy); line(ctx, cx, 8, cx, h - 8);
    ctx.fillStyle = "#8a97ae"; ctx.font = "11px JetBrains Mono, monospace"; ctx.textAlign = "center";
    ctx.fillText("N", cx, 20); ctx.fillText("E", w - 14, cy - 6);
    const step = niceStep(extent / 2);
    ctx.strokeStyle = "#e6edf7"; ctx.lineWidth = 2;
    line(ctx, 14, h - 16, 14 + step * k, h - 16);
    ctx.textAlign = "left"; ctx.fillStyle = "#e6edf7"; ctx.fillText(fmtM(step), 14, h - 22);
    if (acc) {
      ctx.setLineDash([6, 4]); ctx.strokeStyle = "#38bdf8"; ctx.lineWidth = 1.5;
      circle(ctx, cx, cy, acc * k); ctx.stroke(); ctx.setLineDash([]);
    }
    if (!st) return;
    st.offsets.forEach(([e, n], i) => {
      ctx.fillStyle = `rgba(230,240,255,${0.25 + 0.75 * i / max(1, st.offsets.length - 1)})`;
      circle(ctx, cx + e * k, cy - n * k, 2.2); ctx.fill();
    });
    ctx.lineWidth = 2;
    ctx.strokeStyle = "#4ade80"; circle(ctx, cx, cy, st.cep50 * k); ctx.stroke();
    ctx.strokeStyle = "#fbbf24"; circle(ctx, cx, cy, st.r95 * k); ctx.stroke();
    ctx.textAlign = "right"; ctx.font = "11px JetBrains Mono, monospace";
    ctx.fillStyle = "#4ade80"; ctx.fillText("CEP50", w - 12, h - 44);
    ctx.fillStyle = "#fbbf24"; ctx.fillText("R95", w - 12, h - 30);
    ctx.fillStyle = "#38bdf8"; ctx.fillText("reported", w - 12, h - 16);
  }

  function renderSky() {
    const [ctx, w] = fitCanvas($("sky"));
    const cx = w / 2, cy = w / 2, r = w / 2 - 26;
    ctx.clearRect(0, 0, w, w);
    ctx.fillStyle = "#111b2e"; circle(ctx, cx, cy, r); ctx.fill();
    ctx.strokeStyle = "rgba(96,165,250,0.3)"; ctx.lineWidth = 1;
    for (const el of [0, 30, 60]) { circle(ctx, cx, cy, r * (90 - el) / 90); ctx.stroke(); }
    for (let az = 0; az < 360; az += 30) line(ctx, cx, cy, cx + r * sin(az * D2R), cy - r * cos(az * D2R));
    ctx.fillStyle = "#e6edf7"; ctx.font = "600 13px Barlow Condensed, sans-serif"; ctx.textAlign = "center";
    ctx.fillText("N", cx, cy - r - 9); ctx.fillText("S", cx, cy + r + 19);
    ctx.fillText("E", cx + r + 13, cy + 5); ctx.fillText("W", cx - r - 13, cy + 5);
    ctx.fillStyle = "#8a97ae"; ctx.font = "10px JetBrains Mono, monospace";
    ctx.fillText("30°", cx + 14, cy - r * 60 / 90 + 12); ctx.fillText("60°", cx + 14, cy - r * 30 / 90 + 12);
    skyHits = [];
    for (const s of state.sky.sats) {
      const rr = r * (90 - s.el) / 90;
      const x = cx + rr * sin(s.az * D2R), y = cy - rr * cos(s.az * D2R);
      const rad = 4 + (min(50, max(15, s.cn0)) - 15) / 35 * 5;
      ctx.fillStyle = s.c.color + "33"; circle(ctx, x, y, rad * 2.2); ctx.fill();
      if (s.used) { ctx.fillStyle = s.c.color; circle(ctx, x, y, rad); ctx.fill(); }
      else { ctx.strokeStyle = s.c.color; ctx.lineWidth = 2; circle(ctx, x, y, rad); ctx.stroke(); }
      if (s.key === state.selected) { ctx.strokeStyle = "#fff"; ctx.lineWidth = 1.5; circle(ctx, x, y, rad + 5); ctx.stroke(); }
      ctx.fillStyle = "#e6edf7"; ctx.textAlign = "left"; ctx.font = "11px JetBrains Mono, monospace";
      ctx.fillText(s.label, x + rad + 3, y + 4);
      skyHits.push([x, y, s.key]);
    }
    $("signal-bars").innerHTML = state.sky.sats.map((s) => `<div class="bar"><span>${s.label}</span>
      <i style="width:${max(2, s.cn0 / 52 * 100)}%;background:${s.c.color}${s.used ? "" : "55"}"></i><span>${s.cn0.toFixed(0)}</span></div>`).join("");
  }
  let skyHits = [];

  // ------------------------------------------------------------------ globe
  const canvas = $("globe");
  const view = { lat: 20, lon: 0, zoom: 1, tLat: null, tLon: null, vLat: 0, vLon: 0, lastTouch: 0 };
  const opts = { orbits: true, rays: true, all: true, spin: true };
  const CAM = 14;
  let centeredOnce = false;
  let focal = 1, gcx = 0, gcy = 0;
  let px = 0, py = 0, depth = 0;
  const trails = new Map();
  let trailsAt = 0;

  const land = (() => {
    const bin = atob(window.LANDMASK_B64);
    const n = 14000, out = [], golden = PI * (3 - sqrt(5));
    for (let i = 0; i < n; i++) {
      const z = 1 - 2 * (i + 0.5) / n, r = sqrt(1 - z * z), th = golden * i;
      const x = r * cos(th), y = r * sin(th);
      const lat = Math.asin(z) / D2R, lon = Math.atan2(y, x) / D2R;
      const row = Math.min(359, Math.floor((90 - lat) / 0.5)), col = Math.min(719, Math.floor((lon + 180) / 0.5));
      const bit = row * 720 + col;
      if ((bin.charCodeAt(bit >> 3) >> (7 - (bit & 7))) & 1) out.push(x, y, z);
    }
    return new Float32Array(out);
  })();
  const stars = Array.from({ length: 240 }, () => [Math.random(), Math.random(), Math.random() * 6.28]);

  function project(x, y, z) {
    const lo = view.lon * D2R, la = view.lat * D2R;
    const x1 = x * cos(lo) + y * sin(lo), y1 = -x * sin(lo) + y * cos(lo);
    const x2 = x1 * cos(la) + z * sin(la), z2 = -x1 * sin(la) + z * cos(la);
    depth = x2;
    const s = focal / (CAM - x2);
    px = gcx + y1 * s; py = gcy - z2 * s;
  }

  function hidden(x, y, z) {
    const lo = view.lon * D2R, la = view.lat * D2R;
    const x1 = x * cos(lo) + y * sin(lo), y1 = -x * sin(lo) + y * cos(lo);
    const x2 = x1 * cos(la) + z * sin(la), z2 = -x1 * sin(la) + z * cos(la);
    const dx = x2 - CAM;
    const t = min(1, max(0, -(CAM * dx) / (dx * dx + y1 * y1 + z2 * z2)));
    const qx = CAM + t * dx, qy = t * y1, qz = t * z2;
    return t < 1 && qx * qx + qy * qy + qz * qz < 1;
  }

  function centerOn(lat, lon) { view.tLat = lat; view.tLon = lon; centeredOnce = true; }

  function stepCamera(now) {
    if (view.tLat != null) {
      let d = (view.tLon - view.lon) % 360;
      if (d > 180) d -= 360; if (d < -180) d += 360;
      view.lon += d * 0.08;
      view.lat += (max(-85, min(85, view.tLat)) - view.lat) * 0.08;
      if (abs(d) < 0.05 && abs(view.tLat - view.lat) < 0.05) view.tLat = view.tLon = null;
    } else if (abs(view.vLat) + abs(view.vLon) > 0.001) {
      view.lon += view.vLon; view.lat = max(-85, min(85, view.lat + view.vLat));
      view.vLon *= 0.94; view.vLat *= 0.94;
    } else if (opts.spin && now - view.lastTouch > 6000 && !reducedMotion) {
      view.lon += 0.04;
    }
  }
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function drawGlobe(now) {
    const [ctx, w, h] = fitCanvas(canvas);
    stepCamera(now);
    gcx = w / 2; gcy = h * 0.5;
    focal = 0.45 * min(w, h) * CAM / 4.9 * view.zoom;
    ctx.fillStyle = "#05070d"; ctx.fillRect(0, 0, w, h);
    const t = now / 1000;
    for (const [sx, sy, ph] of stars) {
      ctx.fillStyle = `rgba(200,215,255,${0.35 + 0.3 * sin(t * 0.8 + ph)})`;
      ctx.fillRect(sx * w, sy * h, 1.4, 1.4);
    }
    const sky = state.sky;
    if (sky && opts.all) drawCatalog(ctx, false);
    if (sky && opts.orbits) drawTrails(ctx, false);
    if (sky) drawSats(ctx, false);
    drawEarth(ctx);
    if (sky) {
      if (opts.rays) drawRays(ctx, now);
      drawUser(ctx, now);
      if (opts.all) drawCatalog(ctx, true);
      if (opts.orbits) drawTrails(ctx, true);
      drawSats(ctx, true);
    } else {
      ctx.fillStyle = "#e6edf7"; ctx.font = "600 16px Barlow Condensed, sans-serif"; ctx.textAlign = "center";
      ctx.fillText("Loading satellites…", w / 2, h * 0.88);
    }
    requestAnimationFrame(drawGlobe);
  }

  function drawEarth(ctx) {
    const r = focal / sqrt(CAM * CAM - 1);
    let g = ctx.createRadialGradient(gcx, gcy, r * 0.86, gcx, gcy, r * 1.12);
    g.addColorStop(0, "rgba(56,189,248,0)"); g.addColorStop(0.3, "rgba(56,189,248,0.33)"); g.addColorStop(1, "rgba(56,189,248,0)");
    ctx.fillStyle = g; circle(ctx, gcx, gcy, r * 1.12); ctx.fill();
    const sun = state.sun || [1, 0, 0];
    project(sun[0], sun[1], sun[2]);
    const k = depth > 0 ? 0.6 : 1.6;
    g = ctx.createRadialGradient(gcx + (px - gcx) * k, gcy + (py - gcy) * k, 0, gcx + (px - gcx) * k, gcy + (py - gcy) * k, r * 1.6);
    g.addColorStop(0, "#123c78"); g.addColorStop(0.45, "#0a2350"); g.addColorStop(1, "#040c1e");
    ctx.fillStyle = g; circle(ctx, gcx, gcy, r); ctx.fill();
    // graticule
    ctx.strokeStyle = "rgba(96,165,250,0.14)"; ctx.lineWidth = 0.7;
    const horizon = 1 / CAM;
    for (let latD = -60; latD <= 60; latD += 30) surfaceLine(ctx, true, latD, horizon);
    for (let lonD = -180; lonD < 180; lonD += 30) surfaceLine(ctx, false, lonD, horizon);
    // land dots, shaded by daylight
    const colors = ["rgba(52,211,153,0.44)", "rgba(52,211,153,0.6)", "rgba(110,231,183,0.8)", "#a7f3d0"];
    const size = 1.9 * max(0.7, min(2.2, view.zoom));
    const buckets = [[], [], [], []];
    for (let i = 0; i < land.length; i += 3) {
      const x = land[i], y = land[i + 1], z = land[i + 2];
      project(x, y, z);
      if (depth < horizon) continue;
      const day = x * sun[0] + y * sun[1] + z * sun[2];
      buckets[day > 0.15 ? 3 : day > -0.05 ? 2 : depth > 0.5 ? 1 : 0].push(px, py);
    }
    buckets.forEach((b, i) => {
      ctx.fillStyle = colors[i];
      for (let j = 0; j < b.length; j += 2) ctx.fillRect(b[j] - size / 2, b[j + 1] - size / 2, size, size);
    });
  }

  function surfaceLine(ctx, parallel, value, horizon) {
    let ok0 = false, x0 = 0, y0 = 0;
    ctx.beginPath();
    for (let i = 0; i <= 72; i++) {
      const a = (parallel ? -180 + i * 5 : -90 + i * 2.5) * D2R;
      const lat = parallel ? value * D2R : a, lon = parallel ? a : value * D2R;
      project(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat));
      const ok = depth > horizon;
      if (ok && ok0) { ctx.moveTo(x0, y0); ctx.lineTo(px, py); }
      x0 = px; y0 = py; ok0 = ok;
    }
    ctx.stroke();
  }

  function drawCatalog(ctx, front) {
    const horizon = 1 / CAM;
    for (const { c, p } of state.sky.catalog) {
      const x = p[0] / R_KM, y = p[1] / R_KM, z = p[2] / R_KM;
      project(x, y, z);
      if ((depth >= horizon) !== front) continue;
      if (!front && hidden(x, y, z)) continue;
      ctx.fillStyle = c.color + "55"; circle(ctx, px, py, 1.8); ctx.fill();
    }
  }

  function drawTrails(ctx, front) {
    const now = state.sky.timeMs;
    if (!trails.size) trailsAt = now;
    const horizon = 1 / CAM;
    ctx.lineWidth = 1.4;
    for (const s of state.sky.sats) {
      const o = s.orbit;
      let arr = trails.get(o.norad);
      if (!arr) {
        arr = [];
        const steps = 16, span = o.periodMin * 60000 * 0.12;
        for (let i = 0; i <= steps; i++) {
          const p = o.ecef(now + (i - steps / 2) * span / (steps / 2));
          arr.push(p[0] / R_KM, p[1] / R_KM, p[2] / R_KM);
        }
        trails.set(o.norad, arr);
      }
      const n = arr.length / 3, sel = s.key === state.selected;
      let x0 = 0, y0 = 0;
      for (let i = 0; i < n; i++) {
        const x = arr[i * 3], y = arr[i * 3 + 1], z = arr[i * 3 + 2];
        project(x, y, z);
        const cx = px, cy = py;
        if (i > 0 && (depth >= horizon) === front && !(!front && hidden(x, y, z))) {
          const f = 1 - abs(i - n / 2) / (n / 2);
          const a = min(1, (i > n / 2 ? 0.55 : 0.3) * (0.25 + 0.75 * f) * (sel ? 1.6 : 1));
          ctx.strokeStyle = s.c.color + Math.round(a * 255).toString(16).padStart(2, "0");
          line(ctx, x0, y0, cx, cy);
        }
        x0 = cx; y0 = cy;
      }
    }
  }

  function drawRays(ctx, now) {
    const u = state.sky.user;
    const ux = u[0] / R_KM, uy = u[1] / R_KM, uz = u[2] / R_KM;
    const horizon = 1 / CAM;
    ctx.lineWidth = 1.2;
    ctx.setLineDash([8, 6]);
    ctx.lineDashOffset = reducedMotion ? 0 : ((now % 1000) / 1000) * 14;
    for (const s of state.sky.sats) {
      if (!s.used && s.key !== state.selected) continue;
      const sx = s.ecef[0] / R_KM, sy = s.ecef[1] / R_KM, sz = s.ecef[2] / R_KM;
      ctx.beginPath();
      let started = false;
      for (let i = 0; i <= 24; i++) {
        const t = i / 24;
        const x = sx + (ux - sx) * t, y = sy + (uy - sy) * t, z = sz + (uz - sz) * t;
        project(x, y, z);
        if (depth >= horizon || !hidden(x, y, z)) {
          if (!started) { ctx.moveTo(px, py); started = true; } else ctx.lineTo(px, py);
        } else started = false;
      }
      ctx.strokeStyle = s.c.color + (s.key === state.selected ? "ee" : "77");
      ctx.stroke();
    }
    ctx.setLineDash([]);
  }

  function drawUser(ctx, now) {
    const u = state.sky.user;
    project(u[0] / R_KM, u[1] / R_KM, u[2] / R_KM);
    if (depth < 1 / CAM) return;
    const t = reducedMotion ? 0.5 : (now % 2000) / 2000;
    ctx.fillStyle = `rgba(56,189,248,${(1 - t) * 0.6})`; circle(ctx, px, py, 4 + 22 * t); ctx.fill();
    ctx.fillStyle = "#38bdf8"; circle(ctx, px, py, 5); ctx.fill();
    ctx.strokeStyle = "#fff"; ctx.lineWidth = 2; ctx.stroke();
    ctx.fillStyle = "#fff"; ctx.font = "700 12px Barlow Condensed, sans-serif"; ctx.textAlign = "left";
    ctx.fillText("YOU", px + 9, py - 7);
  }

  function drawSats(ctx, front) {
    const horizon = 1 / CAM;
    ctx.font = "11px JetBrains Mono, monospace"; ctx.textAlign = "left";
    for (const s of state.sky.sats) {
      const x = s.ecef[0] / R_KM, y = s.ecef[1] / R_KM, z = s.ecef[2] / R_KM;
      project(x, y, z);
      if ((depth >= horizon) !== front) continue;
      const hid = !front && hidden(x, y, z);
      const sel = s.key === state.selected;
      const r = (3 + (min(50, max(15, s.cn0)) - 15) / 35 * 4) * (0.75 + 0.25 * min(2, view.zoom));
      s._px = px; s._py = py;
      if (!hid) { ctx.fillStyle = s.c.color + "33"; circle(ctx, px, py, r * 2.4); ctx.fill(); }
      const col = s.c.color + (hid ? "40" : "");
      if (s.used) {
        ctx.fillStyle = col; circle(ctx, px, py, r); ctx.fill();
        ctx.strokeStyle = hid ? "#ffffff40" : "#fff"; ctx.lineWidth = 1.2; ctx.stroke();
      } else {
        ctx.strokeStyle = col; ctx.lineWidth = 1.8; circle(ctx, px, py, r); ctx.stroke();
      }
      if (sel) {
        ctx.strokeStyle = "#fff"; ctx.lineWidth = 1.5; circle(ctx, px, py, r + 6); ctx.stroke();
        const ax = px, ay = py, n = hypot(x, y, z);
        project(x / n, y / n, z / n);
        if (depth >= horizon) {
          ctx.setLineDash([3, 3]); ctx.strokeStyle = "rgba(255,255,255,0.6)"; ctx.lineWidth = 1;
          line(ctx, ax, ay, px, py); ctx.setLineDash([]);
          ctx.fillStyle = s.c.color; circle(ctx, px, py, 3); ctx.fill();
        }
        px = ax; py = ay;
      }
      if (!hid) { ctx.fillStyle = sel ? "#fff" : "rgba(255,255,255,0.8)"; ctx.fillText(s.label, px + r + 3, py + 4); }
    }
  }

  // ------------------------------------------------------------------ canvas helpers
  function circle(ctx, x, y, r) { ctx.beginPath(); ctx.arc(x, y, max(0, r), 0, 2 * PI); }
  function line(ctx, a, b, c, d) { ctx.beginPath(); ctx.moveTo(a, b); ctx.lineTo(c, d); ctx.stroke(); }
  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
  function niceStep(v) {
    const p = Math.pow(10, Math.floor(Math.log10(max(v, 0.01)))), m = v / p;
    return p * (m >= 5 ? 5 : m >= 2 ? 2 : 1);
  }

  // ------------------------------------------------------------------ globe input
  const pointers = new Map();
  let pinchDist = 0, downAt = null, moved = false, lastTap = 0;
  canvas.addEventListener("pointerdown", (e) => {
    canvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, [e.clientX, e.clientY]);
    view.vLat = view.vLon = 0; view.tLat = view.tLon = null; view.lastTouch = Date.now();
    downAt = [e.clientX, e.clientY, Date.now()]; moved = false;
    if (pointers.size === 2) { const [a, b] = [...pointers.values()]; pinchDist = hypot(a[0] - b[0], a[1] - b[1]); }
  });
  canvas.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    const prev = pointers.get(e.pointerId);
    pointers.set(e.pointerId, [e.clientX, e.clientY]);
    view.lastTouch = Date.now();
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const d = hypot(a[0] - b[0], a[1] - b[1]);
      if (pinchDist) view.zoom = max(0.6, min(5, view.zoom * d / pinchDist));
      pinchDist = d; moved = true;
      return;
    }
    const dx = e.clientX - prev[0], dy = e.clientY - prev[1];
    if (abs(e.clientX - downAt[0]) + abs(e.clientY - downAt[1]) > 6) moved = true;
    const k = 0.25 / view.zoom;
    view.lon -= dx * k; view.lat = max(-85, min(85, view.lat + dy * k));
    view.vLon = -dx * k * 0.5; view.vLat = dy * k * 0.5;
  });
  const endPointer = (e) => {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinchDist = 0;
    if (!moved && downAt && Date.now() - downAt[2] < 400) {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left, y = e.clientY - rect.top;
      if (Date.now() - lastTap < 300) { if (state.loc) centerOn(state.loc.lat, state.loc.lon); lastTap = 0; return; }
      lastTap = Date.now();
      tapSelect(x, y);
    }
  };
  canvas.addEventListener("pointerup", endPointer);
  canvas.addEventListener("pointercancel", (e) => pointers.delete(e.pointerId));
  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    view.zoom = max(0.6, min(5, view.zoom * Math.exp(-e.deltaY * 0.0015)));
  }, { passive: false });

  function tapSelect(x, y) {
    if (!state.sky) return;
    let best = null, bd = 30;
    for (const s of state.sky.sats) {
      if (s._px == null) continue;
      const d = hypot(s._px - x, s._py - y);
      if (d < bd) { bd = d; best = s; }
    }
    select(best ? best.key : null);
  }

  // ------------------------------------------------------------------ wiring
  function setTab(tab) {
    if (wide() && tab === "globe") tab = state.panelTab || "sats";
    state.tab = tab;
    if (tab !== "globe") state.panelTab = tab;
    document.body.dataset.tab = tab;
    document.querySelectorAll("[role=tab]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === tab)));
    document.querySelectorAll(".view").forEach((v) => { v.hidden = v.dataset.view !== tab; });
    store.set("gnss-globe-tab", tab);
    if (state.sky) update();
  }
  document.querySelectorAll("[role=tab]").forEach((b) => b.addEventListener("click", () => setTab(b.dataset.tab)));

  $("sat-list").addEventListener("click", (e) => {
    const b = e.target.closest(".sat");
    if (!b) return;
    select(+b.dataset.key, true);
    if (!wide()) setTab("globe");
  });
  $("sky").addEventListener("click", (e) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left, y = e.clientY - rect.top;
    const hit = skyHits.reduce((b, h) => { const d = hypot(h[0] - x, h[1] - y); return d < b[0] ? [d, h[2]] : b; }, [28, null]);
    if (hit[1] != null) { select(hit[1]); renderSky(); }
  });
  $("reset").addEventListener("click", () => { state.fixes = []; update(); });
  $("btn-me").addEventListener("click", () => { if (state.loc) centerOn(state.loc.lat, state.loc.lon); });
  $("btn-in").addEventListener("click", () => { view.zoom = min(5, view.zoom * 1.35); });
  $("btn-out").addEventListener("click", () => { view.zoom = max(0.6, view.zoom / 1.35); });
  for (const [id, key] of [["btn-orbits", "orbits"], ["btn-rays", "rays"], ["btn-all", "all"], ["btn-spin", "spin"]]) {
    $(id).addEventListener("click", (e) => {
      opts[key] = !opts[key];
      e.currentTarget.classList.toggle("on", opts[key]);
      e.currentTarget.setAttribute("aria-pressed", String(opts[key]));
    });
  }
  const place = $("place");
  place.innerHTML = `<option value="" disabled>Simulate from…</option>` +
    PLACES.map((p, i) => `<option value="${i}">${p[0]}</option>`).join("");
  place.value = "0";
  place.addEventListener("change", () => choosePlace(+place.value));
  $("use-location").addEventListener("click", startGeolocation);
  window.addEventListener("resize", () => { if (state.sky) setTab(state.tab); });

  setTab(store.get("gnss-globe-tab") || "globe");
  centerOn(state.base.lat, state.base.lon);
  centeredOnce = false;
  simulateFix(Date.now());
  loadOrbits();
  startGeolocation();
  setInterval(update, 1000);
  requestAnimationFrame(drawGlobe);
})();
