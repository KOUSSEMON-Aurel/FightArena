import { load } from "@tensorflow-models/pose-detection/dist/movenet/detector.js";
import "@tensorflow/tfjs-backend-wasm";
import "@tensorflow/tfjs-backend-webgpu";
import * as tf from "@tensorflow/tfjs-core";
import { setWasmPaths, getThreadsCount, setThreadsCount } from "@tensorflow/tfjs-backend-wasm";
import wasmBaseUrl from "@tensorflow/tfjs-backend-wasm/dist/tfjs-backend-wasm.wasm?url";
import wasmSimdUrl from "@tensorflow/tfjs-backend-wasm/dist/tfjs-backend-wasm-simd.wasm?url";
import wasmThreadedUrl from "@tensorflow/tfjs-backend-wasm/dist/tfjs-backend-wasm-threaded-simd.wasm?url";

const MOVENET_TO_MEDIAPIPE = {
  0: 0,
  1: 1,
  2: 2,
  3: 3,
  4: 4,
  5: 11,
  6: 12,
  7: 13,
  8: 14,
  9: 15,
  10: 16,
  11: 23,
  12: 24,
  13: 25,
  14: 26,
  15: 27,
  16: 28,
};

let detector = null;
let isProcessing = false;
let minPoseScore = 0.25;

const LOG_EVERY = 15;
const stats = { frames: 0, waitMs: [], fromPixelsMs: [], inferMs: [], totalMs: [] };

function push(arr, v) {
  arr.push(v);
  if (arr.length > 60) arr.shift();
}
function avg(arr) {
  if (arr.length === 0) return 0;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}
function pct(arr, p) {
  if (arr.length === 0) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * s.length))];
}
function max(arr) {
  if (arr.length === 0) return 0;
  return Math.max(...arr);
}

function summarize() {
  let threads = 0;
  try {
    threads = getThreadsCount();
  } catch (e) {
    threads = 0;
  }
  return {
    type: "STATS",
    frames: stats.frames,
    backend: tf.getBackend(),
    threads,
    numTensors: tf.memory().numTensors,
    wait: { avg: +avg(stats.waitMs).toFixed(1), p95: +pct(stats.waitMs, 0.95).toFixed(1), max: +max(stats.waitMs).toFixed(1) },
    fromPixels: { avg: +avg(stats.fromPixelsMs).toFixed(1), p95: +pct(stats.fromPixelsMs, 0.95).toFixed(1), max: +max(stats.fromPixelsMs).toFixed(1) },
    infer: { avg: +avg(stats.inferMs).toFixed(1), p95: +pct(stats.inferMs, 0.95).toFixed(1), max: +max(stats.inferMs).toFixed(1) },
    total: { avg: +avg(stats.totalMs).toFixed(1), p95: +pct(stats.totalMs, 0.95).toFixed(1), max: +max(stats.totalMs).toFixed(1) },
  };
}

async function init(data) {
  const { modelUrl, minPoseScore: mps, backend: wanted, numThreads } = data;
  minPoseScore = mps ?? 0.25;
  const t0 = performance.now();
  console.log(
    "[worker] init:",
    JSON.stringify({
      crossOriginIsolated: self.crossOriginIsolated,
      hardwareConcurrency: navigator.hardwareConcurrency,
      ua: navigator.userAgent,
    })
  );
  let backend = null;
  let backendDetail = "";
  let mtOk = false;
  const base = new URL(wasmBaseUrl, self.location.href).href;
  const simd = new URL(wasmSimdUrl, self.location.href).href;
  const threaded = new URL(wasmThreadedUrl, self.location.href).href;
  setWasmPaths({
    "tfjs-backend-wasm.wasm": base,
    "tfjs-backend-wasm-simd.wasm": simd,
    "tfjs-backend-wasm-threaded-simd.wasm": threaded,
  });
  setThreadsCount(numThreads || 8);
  const backends = [];
  const pick = async (name) => {
    try {
      await tf.setBackend(name);
      await tf.ready();
      if (tf.getBackend() === name) {
        backends.push(`${name}:ok`);
        return true;
      }
      backends.push(`${name}:?`);
      return false;
    } catch (e) {
      backends.push(`${name}:${String(e.message || e).slice(0, 80)}`);
      return false;
    }
  };
  console.log("[worker] webgpu dispo:", JSON.stringify({ selfWebGPU: !!self.WebGPU, navigatorGpu: !!navigator.gpu }));
  const want = (wanted === "webgpu" || wanted === "webgl") ? wanted : "wasm";
  if ((await pick(want)) || (want !== "wasm" && (await pick("wasm")))) {
    backend = tf.getBackend();
    backendDetail = backend;
  }
  if (backend === "wasm") {
    try {
      mtOk = await tf.env().getAsync("WASM_HAS_MULTITHREAD_SUPPORT");
    } catch (e2) {
      console.error("[worker] flag mt:", e2);
    }
  }
  if (!backend) {
    console.error("[worker] AUCUN backend disponible:", JSON.stringify(backends));
    await pick("webgl");
    throw new Error(`Aucun backend utilisable: ${JSON.stringify(backends)}`);
  }
  console.log("[worker] backend sélectionné:", JSON.stringify({ backend, backends }));
  const t1 = performance.now();
  let simdOk = false;
  try {
    simdOk = await tf.env().getAsync("WASM_HAS_SIMD_SUPPORT");
  } catch (e) {
    console.error("[worker] flag simd:", e);
  }
  const threads = backend === "wasm" ? getThreadsCount() : 0;
  console.log(
    "[worker] backend prêt:",
    JSON.stringify({
      backend,
      backendDetail,
      simdOk,
      mtSupported: mtOk,
      threads,
      mt: threads > 1,
      initMs: +(t1 - t0).toFixed(1),
    })
  );
  const t2 = performance.now();
  detector = await load({
    modelType: "SinglePose.Lightning",
    modelUrl,
    enableSmoothing: false,
    minPoseScore,
  });
  console.log(
    "[worker] modèle chargé:",
    JSON.stringify({
      modelMs: +(performance.now() - t2).toFixed(1),
      modelUrl,
      minPoseScore,
      numTensors: tf.memory().numTensors,
    })
  );
  postMessage({
    type: "INIT_DONE",
    delegate: backend === "webgpu" ? "GPU+WG" : backend === "webgl" ? "GPU" : mtOk && threads > 1 ? "WASM+MT" : "WASM",
    backend,
    threads,
    mtSupported: mtOk,
    simdOk,
    backends,
  });
}

function toMediaPipeLandmarks(pose, imgW, imgH) {
  const lm = new Array(33);
  for (let i = 0; i < 33; i++) lm[i] = { x: 0, y: 0, z: 0, visibility: 0 };
  for (let i = 0; i < pose.keypoints.length; i++) {
    const kp = pose.keypoints[i];
    const idx = MOVENET_TO_MEDIAPIPE[i];
    if (idx === undefined) continue;
    lm[idx] = {
      x: kp.x / imgW,
      y: kp.y / imgH,
      z: 0,
      visibility: kp.score,
    };
  }
  return lm;
}

function serialize(poses, imgW, imgH) {
  if (!poses || poses.length === 0) return { landmarks: [] };
  return { landmarks: [toMediaPipeLandmarks(poses[0], imgW, imgH)] };
}

self.onmessage = async (ev) => {
  const { type } = ev.data;
  if (type === "INIT") {
    try {
      await init(ev.data);
    } catch (err) {
      console.error("[worker] INIT_ERROR:", err);
      postMessage({ type: "INIT_ERROR", error: err.message || String(err), stack: err.stack });
    }
    return;
  }
  if (type === "DETECT_VIDEO") {
    const recv = performance.now();
    let waitMs = 0;
    while (isProcessing) {
      await new Promise((r) => setTimeout(r, 5));
      waitMs += 5;
    }
    isProcessing = true;
    const { bitmap, timestampMs } = ev.data;
    if (!detector) {
      bitmap.close();
      postMessage({ type: "DETECT_ERROR", error: "Not initialized" });
      isProcessing = false;
      return;
    }
    try {
      const imgW = bitmap.width;
      const imgH = bitmap.height;
      const t0 = performance.now();
      const tensor = tf.browser.fromPixels(bitmap);
      const t1 = performance.now();
      const poses = await detector.estimatePoses(tensor, undefined, timestampMs);
      const t2 = performance.now();
      tensor.dispose();
      const fromPixelsMs = t1 - t0;
      const inferMs = t2 - t1;
      const inferenceTime = t2 - t0;
      push(stats.waitMs, waitMs);
      push(stats.fromPixelsMs, fromPixelsMs);
      push(stats.inferMs, inferMs);
      push(stats.totalMs, inferenceTime);
      stats.frames += 1;

      const pose = poses && poses[0];
      const scores = pose ? pose.keypoints.map((k) => k.score) : [];
      const sMin = scores.length ? Math.min(...scores) : -1;
      const sMax = scores.length ? Math.max(...scores) : -1;
      const sAvg = scores.length ? scores.reduce((a, b) => a + b, 0) / scores.length : -1;
      if (stats.frames % LOG_EVERY === 0) {
        const sum = summarize();
        console.log("[worker] STATS", JSON.stringify(sum));
        console.log(
          "[worker] last frame:",
          JSON.stringify({
            waitMs: +waitMs.toFixed(1),
            fromPixelsMs: +fromPixelsMs.toFixed(1),
            inferMs: +inferMs.toFixed(1),
            totalMs: +inferenceTime.toFixed(1),
            size: `${imgW}x${imgH}`,
          })
        );
        postMessage(sum);
      } else if (stats.frames % 3 === 0) {
        console.log(
          "[worker] frame:",
          JSON.stringify({
            totalMs: +inferenceTime.toFixed(1),
            score: pose && pose.score !== undefined ? +pose.score.toFixed(2) : "none",
            visMin: +sMin.toFixed(2),
            visAvg: +sAvg.toFixed(2),
            visMax: +sMax.toFixed(2),
          })
        );
      }
      postMessage({ type: "DETECT_RESULT", result: serialize(poses, imgW, imgH), inferenceTime });
    } catch (err) {
      console.error("[worker] DETECT_ERROR:", err);
      postMessage({ type: "DETECT_ERROR", error: err.message || String(err), stack: err.stack, numTensors: tf.memory().numTensors });
    } finally {
      bitmap.close();
      isProcessing = false;
    }
  }
};
