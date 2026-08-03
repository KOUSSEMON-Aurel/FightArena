import { CONFIG } from "./config.js";

const C = CONFIG;
const $ = (id) => document.getElementById(id);
const IS_MOBILE = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);

const video = $("video");
const overlay = $("overlay");
const ctx = overlay.getContext("2d");

const el = {
  status: $("status"),
  guide: $("guide"),
  camBtn: $("cam-btn"),
  flipBtn: $("flip-btn"),
  rotBtn: $("rot-btn"),
  fps: $("fps"),
  inf: $("inf"),
  detConf: $("det-conf"),
  detConfVal: $("det-conf-val"),
  trackConf: $("track-conf"),
  trackConfVal: $("track-conf-val"),
  smooth: $("smooth"),
  smoothVal: $("smooth-val"),
  extBarL: $("ext-bar-left"),
  extValL: $("ext-val-left"),
  velBarL: $("vel-bar-left"),
  velValL: $("vel-val-left"),
  phaseL: $("phase-left"),
  countL: $("count-left"),
  peakL: $("peak-left"),
  extBarR: $("ext-bar-right"),
  extValR: $("ext-val-right"),
  velBarR: $("vel-bar-right"),
  velValR: $("vel-val-right"),
  phaseR: $("phase-right"),
  countR: $("count-right"),
  peakR: $("peak-right"),
  guardState: $("guard-state"),
  guardMs: $("guard-ms"),
  calStatus: $("cal-status"),
  calState: $("cal-state"),
  log: $("log"),
};

const armL = createArm("Gauche", 15, 13, 11);
const armR = createArm("Droite", 16, 14, 12);

const guard = { heldMs: 0, active: false, lastActiveMs: 0 };
const det = {
  worker: null,
  workerReady: false,
  workerBusy: false,
  lastVideoTime: -1,
  fpsFrames: 0,
  fpsStart: 0,
  yUp: true,
  prevNow: 0,
  smoothLm: null,
  sentCount: 0,
  skippedCount: 0,
  roundTrips: [],
  processMs: [],
  lastSentAt: 0,
  lastBitmapMs: 0,
  lastStats: null,
  diagAt: 0,
};

const KEY_LANDMARKS = [0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28];
const UPPER_LANDMARKS = [0, 11, 12, 13, 14, 15, 16];
let facing = C.video.facing;
let stream = null;
let lastLm = null;
let rotDeg = Number(localStorage.getItem("fightarena-rot") ?? -90);
const ROT_STEPS = [-90, 90, 0];

const wrap = $("video-wrap");
const sampler = document.createElement("canvas");
const sctx = sampler.getContext("2d");
const SAMPLER_MAX_DIM = 288;

function syncCanvasSize() {
  const w = wrap.clientWidth || 360;
  const h = wrap.clientHeight || 640;
  if (overlay.width !== w || overlay.height !== h) {
    overlay.width = w;
    overlay.height = h;
    const scale = Math.min(1, SAMPLER_MAX_DIM / Math.max(w, h));
    sampler.width = Math.max(2, Math.round(w * scale));
    sampler.height = Math.max(2, Math.round(h * scale));
  }
}

function drawVideoTo(sctx, w, h) {
  const vw = video.videoWidth || 1280;
  const vh = video.videoHeight || 720;
  const rotated = rotDeg !== 0 && vw > vh && h >= w;
  const mirror = facing === "user";
  const dw = rotated ? vh : vw;
  const dh = rotated ? vw : vh;
  const scale = Math.max(w / dw, h / dh);
  sctx.save();
  sctx.clearRect(0, 0, w, h);
  sctx.translate(w / 2, h / 2);
  if (mirror) sctx.scale(-1, 1);
  if (rotated) sctx.rotate((rotDeg * Math.PI) / 180);
  sctx.scale(scale, scale);
  sctx.drawImage(video, -vw / 2, -vh / 2, vw, vh);
  sctx.restore();
}

function fullBodyVisible(lm) {
  return (
    lm.length === 33 &&
    KEY_LANDMARKS.every((i) => lm[i].visibility !== undefined && lm[i].visibility >= 0.4)
  );
}

function upperBodyVisible(lm) {
  return (
    lm.length === 33 &&
    UPPER_LANDMARKS.every((i) => lm[i].visibility !== undefined && lm[i].visibility >= 0.5)
  );
}

function mapLmToScreen(lm, vw, vh, cw, ch) {
  const rotated = rotDeg !== 0 && vw > vh && ch >= cw;
  const mirror = facing === "user";
  const dw = rotated ? vh : vw;
  const dh = rotated ? vw : vh;
  const scale = Math.max(cw / dw, ch / dh);
  const rad = (rotDeg * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);
  return lm.map((p) => {
    let px = (p.x * vw - vw / 2) * scale;
    let py = (p.y * vh - vh / 2) * scale;
    const rx = px * cos - py * sin;
    const ry = px * sin + py * cos;
    px = mirror ? -rx : rx;
    py = ry;
    return { ...p, x: px + cw / 2, y: py + ch / 2 };
  });
}

const cal = { recording: null, clips: [], cleanCount: 0, sloppyCount: 0 };
const events = [];
const MAX_LOG = 8;

function createArm(label, wristIdx, elbowIdx, shoulderIdx) {
  return {
    label,
    wristIdx,
    elbowIdx,
    shoulderIdx,
    phase: "idle",
    cooldownUntil: 0,
    count: 0,
    ext: 0,
    vel: 0,
    peakExt: 0,
    peakVel: 0,
    prevWrist: null,
  };
}

function dist3(a, b) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  const dz = a.z - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

function isAbove(a, b) {
  return det.yUp ? a.y < b.y : a.y > b.y;
}

function logEvent(text) {
  events.unshift(text);
  if (events.length > MAX_LOG) events.pop();
  el.log.innerHTML = events.map((e) => `<div>${e}</div>`).join("");
}

const diagEl = document.createElement("div");
diagEl.id = "diag";
diagEl.style.cssText =
  "position:fixed;bottom:4px;left:4px;font:10px/1.4 monospace;color:#0f0;background:rgba(0,0,0,.6);padding:4px 6px;z-index:99;white-space:pre;border-radius:6px;pointer-events:none;max-width:96vw";
document.body.appendChild(diagEl);

const D_avg = (arr) => (arr.length === 0 ? 0 : arr.reduce((a, b) => a + b, 0) / arr.length);
const D_pct = (arr, p) => {
  if (arr.length === 0) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * s.length))];
};

function updateDiag() {
  const ws = det.lastStats;
  if (!ws) return;
  const rt = D_pct(det.roundTrips, 0.95);
  const pr = D_pct(det.processMs, 0.95);
  const line = [
    `thr=${ws.threads} tens=${ws.numTensors}`,
    `fp=${ws.fromPixels.p95} inf=${ws.infer.p95} tot=${ws.total.p95}ms`,
    `rt=${Math.round(rt)}ms proc=${Math.round(pr)}ms`,
    `sent=${det.sentCount} skip=${det.skippedCount}`,
  ].join("\n");
  diagEl.textContent = line;
}

function flash(cssClass) {
  document.body.classList.remove(cssClass);
  void document.body.offsetWidth;
  document.body.classList.add(cssClass);
  setTimeout(() => document.body.classList.remove(cssClass), 150);
}

function updateArmUI(arm, extEl, extBar, velEl, velBar, phaseEl, countEl, peakEl) {
  extEl.textContent = arm.ext.toFixed(2);
  extBar.style.width = `${Math.min(120, (arm.ext / 1.2) * 100)}%`;
  velEl.textContent = arm.vel.toFixed(2);
  velBar.style.width = `${Math.min(100, (arm.vel / 4) * 100)}%`;
  phaseEl.textContent = arm.phase;
  phaseEl.className = `phase ${arm.phase}`;
  countEl.textContent = String(arm.count);
  peakEl.textContent = `pic ext ${arm.peakExt.toFixed(2)} · pic vel ${arm.peakVel.toFixed(2)}`;
}

function fireJab(arm) {
  arm.count += 1;
  arm.phase = "reset";
  arm.cooldownUntil = performance.now() + C.jab.cooldownMs;
  arm.peakExt = 0;
  arm.peakVel = 0;
  logEvent(`JAB ${arm.label} (${arm.count})`);
  flash("hit");
}

function updateJab(arm, lm, torso, dtMs) {
  const shoulder = lm[arm.shoulderIdx];
  const wrist = lm[arm.wristIdx];
  if (shoulder.visibility < 0.5 || wrist.visibility < 0.5) {
    arm.phase = "idle";
    arm.prevWrist = null;
    arm.ext = 0;
    arm.vel = 0;
    return;
  }
  const ext = dist3(wrist, shoulder) / torso;
  let vel = 0;
  if (arm.prevWrist) {
    const d = dist3(wrist, arm.prevWrist) / torso;
    const inst = dtMs > 0 ? (d / dtMs) * 1000 : 0;
    vel = inst > 10 ? arm.vel : arm.vel + C.jab.velAlpha * (inst - arm.vel);
  }
  arm.prevWrist = { x: wrist.x, y: wrist.y, z: wrist.z };
  arm.ext = ext;
  arm.vel = vel;
  arm.peakExt = Math.max(arm.peakExt, ext);
  arm.peakVel = Math.max(arm.peakVel, vel);

  if (performance.now() < arm.cooldownUntil) return;

  if (arm.phase === "reset") {
    if (ext < C.jab.resetExt) arm.phase = "idle";
    return;
  }
  if (arm.phase === "idle") {
    if (ext > C.jab.startExt) arm.phase = "start";
    return;
  }
  if (arm.phase === "start") {
    if (ext >= C.jab.triggerExt && vel >= C.jab.triggerVel) {
      fireJab(arm);
    } else if (ext < C.jab.resetExt) {
      arm.phase = "idle";
    }
  }
}

function updateGuard(lm, dtMs) {
  const nose = lm[0];
  const ls = lm[11];
  const rs = lm[12];
  const lw = lm[15];
  const rw = lm[16];
  const shoulderW = dist3(ls, rs);
  const bothAbove = isAbove(lw, ls) && isAbove(rw, rs);
  const nearAxis =
    Math.abs(lw.x - nose.x) < C.guard.noseAxisFactor * shoulderW &&
    Math.abs(rw.x - nose.x) < C.guard.noseAxisFactor * shoulderW;
  const inGuard = bothAbove && nearAxis;

  if (inGuard) {
    guard.heldMs += dtMs;
  } else {
    guard.heldMs = 0;
    if (guard.active) {
      guard.active = false;
      logEvent("GARDE relâchée");
    }
  }
  if (guard.heldMs >= C.guard.holdMs && !guard.active) {
    guard.active = true;
    guard.lastActiveMs = performance.now();
    logEvent("GARDE active (bloc)");
    flash("block");
  }
}

function updatePlacement(normLm) {
  el.guide.classList.remove("hidden");
  const nose = normLm[0];
  const lAnkle = normLm[27];
  const rAnkle = normLm[28];
  const h = video.videoHeight || wrap.clientHeight || 720;
  const headCut = nose.y < 0.03;
  const feetCut = lAnkle.y > 0.97 || rAnkle.y > 0.97;
  if (headCut || feetCut) {
    el.guide.textContent = headCut ? "Tête coupée : reculez" : "Pieds coupés : reculez";
    el.guide.className = "pill warn";
    return;
  }
  const torsoPx =
    (Math.abs(normLm[11].y - normLm[23].y) + Math.abs(normLm[12].y - normLm[24].y)) * 0.5 * h;
  const ratio = h > 0 ? torsoPx / h : 0;
  if (ratio < C.placement.torsoMin) {
    el.guide.textContent = "Trop loin : avancez";
    el.guide.className = "pill warn";
  } else if (ratio > C.placement.torsoMax) {
    el.guide.textContent = "Trop près : reculez";
    el.guide.className = "pill warn";
  } else {
    el.guide.textContent = "Position OK";
    el.guide.className = "pill ok";
  }
}

function resetArm(arm) {
  arm.phase = "idle";
  arm.ext = 0;
  arm.vel = 0;
  arm.peakExt = 0;
  arm.peakVel = 0;
  arm.prevWrist = null;
}

function resetAll() {
  resetArm(armL);
  resetArm(armR);
  guard.heldMs = 0;
  guard.active = false;
  el.guardState.textContent = "INACTIVE";
  el.guardMs.textContent = "0 ms";
}

function smoothLandmarks(lm) {
  const alpha = Number(el.smooth.value || 0);
  if (alpha <= 0 || alpha >= 1) return lm;
  if (!det.smoothLm) {
    det.smoothLm = lm.map((p) => ({ x: p.x, y: p.y, z: p.z, visibility: p.visibility }));
    return det.smoothLm;
  }
  const s = det.smoothLm;
  for (let i = 0; i < lm.length; i++) {
    const a = i === 0 ? 1 : alpha;
    s[i].x += a * (lm[i].x - s[i].x);
    s[i].y += a * (lm[i].y - s[i].y);
    s[i].z += a * (lm[i].z - s[i].z);
    s[i].visibility = lm[i].visibility;
  }
  return s;
}

const MOVENET_CONNECTIONS = [
  [0, 11],
  [0, 12],
  [11, 12],
  [11, 13],
  [13, 15],
  [12, 14],
  [14, 16],
  [11, 23],
  [12, 24],
  [23, 24],
  [23, 25],
  [25, 27],
  [24, 26],
  [26, 28],
];

function drawFrame(lm) {
  const cw = wrap.clientWidth;
  const ch = wrap.clientHeight;
  for (const [a, b] of MOVENET_CONNECTIONS) {
    const pa = lm[a];
    const pb = lm[b];
    if (pa.visibility < 0.4 || pb.visibility < 0.4) continue;
    ctx.beginPath();
    ctx.moveTo(pa.x * cw, pa.y * ch);
    ctx.lineTo(pb.x * cw, pb.y * ch);
    ctx.strokeStyle = "#00e5ff";
    ctx.lineWidth = 2;
    ctx.stroke();
  }
  for (const p of lm) {
    if (p.visibility < 0.4) continue;
    ctx.beginPath();
    ctx.arc(p.x * cw, p.y * ch, 3, 0, Math.PI * 2);
    ctx.fillStyle = "#ffffff";
    ctx.fill();
  }

  const lw = lm[armL.wristIdx];
  const rw = lm[armR.wristIdx];
  ctx.beginPath();
  ctx.arc(lw.x * cw, lw.y * ch, 6, 0, Math.PI * 2);
  ctx.fillStyle = "#ff2d55";
  ctx.fill();
  ctx.beginPath();
  ctx.arc(rw.x * cw, rw.y * ch, 6, 0, Math.PI * 2);
  ctx.fill();
  if (guard.active) {
    ctx.strokeStyle = "#ffd700";
    ctx.lineWidth = 3;
    ctx.strokeRect(0, 0, cw, ch);
  }
}

function renderFrame() {
  const cw = wrap.clientWidth;
  const ch = wrap.clientHeight;
  ctx.clearRect(0, 0, cw, ch);
  ctx.drawImage(sampler, 0, 0, cw, ch);
  if (lastLm) drawFrame(lastLm);
}

function processFrame(lm, nowMs, dtMs) {
  lastLm = null;
  if (!lm) {
    det.smoothLm = null;
    resetAll();
    el.guide.classList.remove("hidden");
    el.guide.textContent = "Aucun corps détecté";
    el.guide.className = "pill warn";
    return;
  }
  det.yUp = lm[0].y < lm[23].y;

  const smoothed = smoothLandmarks(lm);
  lastLm = smoothed;

  updatePlacement(smoothed);
  if (!upperBodyVisible(smoothed)) {
    resetAll();
    return;
  }
  const torso = (dist3(lm[11], lm[23]) + dist3(lm[12], lm[24])) * 0.5;
  updateJab(armL, lm, torso, dtMs);
  updateJab(armR, lm, torso, dtMs);
  updateGuard(lm, dtMs);
  updateArmUI(armL, el.extValL, el.extBarL, el.velValL, el.velBarL, el.phaseL, el.countL, el.peakL);
  updateArmUI(armR, el.extValR, el.extBarR, el.velValR, el.velBarR, el.phaseR, el.countR, el.peakR);
  el.guardState.textContent = guard.active ? "ACTIVE (bloc)" : "INACTIVE";
  el.guardState.className = `big ${guard.active ? "on" : ""}`;
  el.guardMs.textContent = `${Math.round(guard.heldMs)} ms`;
  recordCalibrationFrame(armL, armR, nowMs);
}

function recordCalibrationFrame() {
  if (!cal.recording) return;
  const now = performance.now();
  cal.recording.frames.push({
    t: now - cal.recording.t0,
    extL: armL.ext,
    velL: armL.vel,
    extR: armR.ext,
    velR: armR.vel,
    guard: guard.active,
    trigL: armL.phase === "reset",
    trigR: armR.phase === "reset",
  });
  if (now - cal.recording.t0 >= C.calibration.windowMs) {
    stopCalibration();
  }
}

function stopCalibration() {
  const rec = cal.recording;
  cal.recording = null;
  const peakExt = Math.max(...rec.frames.map((f) => Math.max(f.extL, f.extR)));
  const peakVel = Math.max(...rec.frames.map((f) => Math.max(f.velL, f.velR)));
  const trigger = rec.frames.some((f) => f.trigL || f.trigR);
  const guardMs = rec.frames.filter((f) => f.guard).length * (C.calibration.windowMs / rec.frames.length);
  const clip = { label: rec.label, peakExt, peakVel, trigger, guardMs: Math.round(guardMs) };
  cal.clips.push(clip);
  if (rec.label === "propre") cal.cleanCount += 1;
  else cal.sloppyCount += 1;
  updateCalUI();
  logEvent(`Clip ${rec.label} : pic ext ${peakExt.toFixed(2)}, pic vel ${peakVel.toFixed(2)}, déclenché: ${trigger ? "oui" : "non"}`);
}

function updateCalUI() {
  el.calStatus.textContent = `${cal.cleanCount} propre / ${cal.sloppyCount} mou`;
  el.calState.textContent = cal.recording ? `ENREGISTREMENT (${recLabel(cal.recording.label)})...` : "Prêt";
}

function recLabel(l) {
  return l === "propre" ? "propre" : "mou";
}

function exportCsv() {
  const rows = [["label", "peakExt", "peakVel", "trigger", "guardMs"]];
  for (const c of cal.clips) {
    rows.push([c.label, c.peakExt.toFixed(3), c.peakVel.toFixed(3), c.trigger ? 1 : 0, c.guardMs]);
  }
  const csv = rows.map((r) => r.join(";")).join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "calibration.csv";
  a.click();
  URL.revokeObjectURL(a.href);
}

function loop(now) {
  if (det.fpsFrames === 0) det.fpsStart = now;
  det.fpsFrames += 1;
  const elapsed = now - det.fpsStart;
  if (elapsed >= 1000) {
    el.fps.textContent = `${det.fpsFrames} fps`;
    det.fpsFrames = 0;
    det.fpsStart = now;
  }
  if (video.readyState >= 2) {
    const cw = wrap.clientWidth;
    const ch = wrap.clientHeight;
    drawVideoTo(sctx, sampler.width, sampler.height);
    if (
      det.workerReady &&
      !det.workerBusy &&
      video.currentTime !== det.lastVideoTime
    ) {
      det.lastVideoTime = video.currentTime;
      sendFrame(now);
    } else if (det.workerReady && det.workerBusy) {
      det.skippedCount += 1;
    }
    renderFrame();
  }
  if (now - det.diagAt >= 2000) {
    det.diagAt = now;
    if (det.lastStats) {
      updateDiag();
      console.info(
        "[main] diag:",
        JSON.stringify({
          fps: el.fps.textContent,
          inf: el.inf.textContent,
          sent: det.sentCount,
          skip: det.skippedCount,
          rtP95: Math.round(D_pct(det.roundTrips, 0.95)),
          procP95: Math.round(D_pct(det.processMs, 0.95)),
          lastBitmapMs: Math.round(det.lastBitmapMs * 10) / 10,
        })
      );
    }
  }
  requestAnimationFrame(loop);
}

async function sendFrame(nowMs) {
  det.workerBusy = true;
  try {
    const t0 = performance.now();
    const bitmap = await createImageBitmap(sampler);
    det.lastBitmapMs = performance.now() - t0;
    const timestampMs = nowMs > det.lastTimestampMs ? nowMs : (det.lastTimestampMs ?? 0) + 1;
    det.lastTimestampMs = timestampMs;
    det.lastSentAt = performance.now();
    det.sentCount += 1;
    det.worker.postMessage(
      { type: "DETECT_VIDEO", bitmap, timestampMs },
      [bitmap]
    );
  } catch (err) {
    console.error("[main] sendFrame:", err);
    det.workerBusy = false;
  }
}

function wireWorker() {
  det.worker = new Worker(new URL("./pose-worker.js", import.meta.url), { type: "module" });
  det.worker.onmessage = (ev) => {
    const { type } = ev.data;
    if (type === "INIT_DONE") {
      det.workerReady = true;
      el.status.textContent = `MoveNet prêt · ${ev.data.delegate}`;
      console.info("[main] INIT_DONE:", JSON.stringify(ev.data));
      return;
    }
    if (type === "STATS") {
      det.lastStats = ev.data;
      console.info("[main] worker-stats:", JSON.stringify(ev.data));
      updateDiag();
      return;
    }
    if (type === "DELEGATE_FALLBACK") {
      el.status.textContent = `GPU indisponible, passage CPU...`;
      return;
    }
    if (type === "INIT_ERROR") {
      el.status.textContent = `Erreur modèle : ${ev.data.error}`;
      console.error("[main] INIT_ERROR:", ev.data);
      return;
    }
    if (type === "DETECT_RESULT") {
      det.workerBusy = false;
      el.inf.textContent = `${Math.round(ev.data.inferenceTime)} ms`;
      const now = performance.now();
      const roundTrip = now - det.lastSentAt;
      det.roundTrips.push(roundTrip);
      if (det.roundTrips.length > 60) det.roundTrips.shift();
      const dtMs = now - (det.prevNow ?? now);
      det.prevNow = now;
      const lm = ev.data.result?.landmarks?.[0] ?? null;
      const pt0 = performance.now();
      processFrame(lm, now, dtMs);
      det.processMs.push(performance.now() - pt0);
      if (det.processMs.length > 60) det.processMs.shift();
      if (det.sentCount % 5 === 0) {
        console.info(
          "[main] result:",
          JSON.stringify({
            inf: Math.round(ev.data.inferenceTime),
            roundTrip: Math.round(roundTrip),
            procMs: Math.round((performance.now() - pt0) * 10) / 10,
            detected: lm ? true : false,
            busy: det.workerBusy,
          })
        );
      }
      return;
    }
    if (type === "DETECT_ERROR") {
      det.workerBusy = false;
      console.error("[main] DETECT_ERROR:", ev.data);
    }
  };
}

async function initWorker() {
  el.status.textContent = "Chargement du modèle IA...";
  wireWorker();
  det.worker.postMessage({
    type: "INIT",
    modelUrl: C.model.url,
    minPoseScore: C.model.minPoseScore,
    backend: C.model.backend,
    numThreads: C.model.numThreads,
  });
  await new Promise((resolve, reject) => {
    const onMsg = (ev) => {
      if (ev.data.type === "INIT_DONE") {
        det.worker.removeEventListener("message", onMsg);
        resolve();
      }
      if (ev.data.type === "INIT_ERROR") {
        det.worker.removeEventListener("message", onMsg);
        reject(new Error(ev.data.error));
      }
    };
    det.worker.addEventListener("message", onMsg);
  });
}

async function startCamera() {
  if (stream) {
    stream.getTracks().forEach((t) => t.stop());
  }
  stream = await navigator.mediaDevices.getUserMedia({
    video: {
      facingMode: facing,
      width: { ideal: C.video.width },
      height: { ideal: C.video.height },
    },
    audio: false,
  });
  video.srcObject = stream;
  await video.play();
  syncCanvasSize();
  el.flipBtn.classList.remove("hidden");
  el.rotBtn.classList.remove("hidden");
  el.rotBtn.textContent = `⟳ ${rotDeg}°`;
  el.camBtn.textContent = "Redémarrer";
}

video.addEventListener("loadedmetadata", () => {
  syncCanvasSize();
  drawVideoTo(sctx, sampler.width, sampler.height);
});

window.addEventListener("resize", syncCanvasSize);

el.camBtn.addEventListener("click", async () => {
  el.camBtn.disabled = true;
  try {
    await initWorker();
    await startCamera();
    el.camBtn.disabled = false;
    requestAnimationFrame(loop);
    logEvent("Pipeline démarré");
  } catch (err) {
    el.status.textContent = `Erreur : ${err.message}`;
    el.camBtn.disabled = false;
  }
});

el.flipBtn.addEventListener("click", async () => {
  facing = facing === "user" ? "environment" : "user";
  await startCamera();
});

el.rotBtn.addEventListener("click", () => {
  const i = ROT_STEPS.indexOf(rotDeg);
  rotDeg = ROT_STEPS[(i + 1) % ROT_STEPS.length];
  localStorage.setItem("fightarena-rot", String(rotDeg));
  el.rotBtn.textContent = `⟳ ${rotDeg}°`;
  logEvent(`Rotation vidéo : ${rotDeg}°`);
});

for (const [slider, valEl, unit] of [
  [el.detConf, el.detConfVal, ""],
  [el.trackConf, el.trackConfVal, ""],
  [el.smooth, el.smoothVal, ""],
]) {
  const fmt = slider.id === "smooth" ? "0.00" : "0.0";
  valEl.textContent = Number(slider.value).toFixed(fmt === "0.00" ? 2 : 1);
  slider.addEventListener("input", () => {
    valEl.textContent = Number(slider.value).toFixed(fmt === "0.00" ? 2 : 1);
  });
}

$("cal-clean").addEventListener("click", () => startCalibration("propre"));
$("cal-mou").addEventListener("click", () => startCalibration("mou"));
$("export-btn").addEventListener("click", exportCsv);

function startCalibration(label) {
  if (cal.recording) return;
  cal.recording = { label, t0: performance.now(), frames: [] };
  updateCalUI();
  logEvent(`Enregistrement ${recLabel(label)} en cours...`);
}
