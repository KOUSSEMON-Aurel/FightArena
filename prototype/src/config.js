export const CONFIG = {
  model: {
    url: "/models/movenet-lightning/model.json",
    minPoseScore: 0.25,
    backend: "wasm",
    numThreads: 8,
  },
  video: {
    width: 720,
    height: 1280,
    facing: "user",
  },
  jab: {
    startExt: 0.6,
    triggerExt: 0.95,
    triggerVel: 2.5,
    resetExt: 0.65,
    cooldownMs: 250,
    velAlpha: 0.3,
  },
  guard: {
    holdMs: 500,
    noseAxisFactor: 0.5,
  },
  placement: {
    torsoMin: 0.25,
    torsoMax: 0.65,
  },
  calibration: {
    windowMs: 3000,
  },
};