package com.fightarena.app

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.min

/** Stats rolling de latence : avg / p95 / max sur une fenêtre glissante. */
class LatencyStats(private val windowSize: Int = 240) {
    private val samples = FloatArray(windowSize)
    private var head = 0
    private var n = 0
    val count: Int get() = n

    fun record(ms: Float) {
        samples[head] = ms
        head = (head + 1) % windowSize
        if (n < windowSize) n++
    }

    fun avg(): Float {
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) sum += samples[i]
        return sum / n
    }

    fun p95(): Float {
        if (n == 0) return 0f
        val sorted = samples.copyOf(n)
        sorted.sort()
        return sorted[min((n * 0.95).toInt(), n - 1)]
    }

    fun max(): Float {
        if (n == 0) return 0f
        var m = 0f
        for (i in 0 until n) if (samples[i] > m) m = samples[i]
        return m
    }
}

/** Source de pose commune aux analyseurs (ML Kit ou MediaPipe Tasks) pour l'overlay. */
interface PoseSource {
    val stats: LatencyStats
    val frameCount: Int
}

/**
 * Analyseur CameraX -> ML Kit Pose Detection (BlazePose 33 points, STREAM_MODE).
 * Mesure la latence de chaque inference et pousse le résultat vers l'overlay.
 */
class PoseAnalyzer(private val overlay: PoseOverlayView) : ImageAnalysis.Analyzer, PoseSource {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            // CPU forcé : pas de mini-benchmark d'accélération (crashe en natif sur
            // certains SoC) et comportement stable et prévisible sur tous les téléphones.
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU)
            .build()
    )

    override val stats = LatencyStats(240)
    override var frameCount = 0
        private set

    private var lastFrameNs = 0L
    private var fps = 0f

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val startNs = System.nanoTime()

        detector.process(inputImage)
            .addOnSuccessListener { pose: Pose ->
                val latencyMs = (System.nanoTime() - startNs) / 1e6f
                frameCount++
                stats.record(latencyMs)
                updateFps()
                // ML Kit retourne les landmarks dans le repère de l'image après rotation :
                // en portrait (90/270), largeur = hauteur du capteur et inversement.
                val rotated = imageProxy.imageInfo.rotationDegrees % 180 != 0
                val displayW = if (rotated) imageProxy.height else imageProxy.width
                val displayH = if (rotated) imageProxy.width else imageProxy.height
                if (frameCount % 60 == 0) {
                    Log.i(
                        "PosePerf",
                        "frames=$frameCount fps=${"%.1f".format(fps)} " +
                            "infer_avg=${"%.1f".format(stats.avg())}ms " +
                            "p95=${"%.1f".format(stats.p95())}ms max=${"%.1f".format(stats.max())}ms " +
                            "tensors_none"
                    )
                }
                overlay.onPose(pose, latencyMs, displayW, displayH, this)
                imageProxy.close()
            }
            .addOnFailureListener { e: Exception ->
                Log.e("PoseAnalyzer", "pose detection failed", e)
                imageProxy.close()
            }
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dt = (now - lastFrameNs) / 1e9f
            if (dt > 0f) fps = 0.9f * fps + 0.1f * (1f / dt)
        }
        lastFrameNs = now
    }
}
