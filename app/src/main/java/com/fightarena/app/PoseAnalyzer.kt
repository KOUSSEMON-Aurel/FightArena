package com.fightarena.app

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
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

/**
 * Modèle de détection de pose utilisé par l'app.
 *
 * Le modèle ACCURATE (BlazePose full, 256x256) est plus stable sur les coordonnées
 * x/y (angles coude/épaule fiables pour la spec) mais plus lent ; le modèle BASE
 * (lite, 192x192) est embarqué aussi pour permettre une comparaison ultérieure
 * (perf vs stabilité) par simple changement de constante.
 */
enum class PoseModel(val label: String) {
    ACCURATE("full"),
    BASE("lite")
}

private val POSE_MODEL: PoseModel = PoseModel.ACCURATE

/** Source de résultats de pose : fournit les stats et le compteur pour le HUD. */
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
        when (POSE_MODEL) {
            PoseModel.ACCURATE -> AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
                .setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU)
                .build()
            PoseModel.BASE -> PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                // CPU forcé : pas de mini-benchmark d'accélération (crashe en natif sur
                // certains SoC) et comportement stable et prévisible sur tous les téléphones.
                .setPreferredHardwareConfigs(PoseDetectorOptions.CPU)
                .build()
        }
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
                            "world3d=${world3dSummary(pose)}"
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

    /**
     * Résumé 3D relatif (ML Kit : profondeur z en pixels, relative à l'image ;
     * worldLandmarks en mètres approximatifs normalisés sur la hauteur estimée).
     * Retourne "hipX,hipY,hipZ shX,shY,shZ" ou "none" si invisibles.
     */
    private fun world3dSummary(pose: Pose): String {
        val hip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP) ?: return "none"
        val shoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) ?: return "none"
        val all = pose.getAllPoseLandmarks()
        if (all.isEmpty()) return "none"
        val hipZ = all[PoseLandmark.LEFT_HIP].position3D.z
        val shZ = all[PoseLandmark.LEFT_SHOULDER].position3D.z
        val hipY = all[PoseLandmark.LEFT_HIP].position3D.y
        val shY = all[PoseLandmark.LEFT_SHOULDER].position3D.y
        val depthFrac = (hipZ - shZ) / (hipY - shY).let { if (it == 0f) 1f else it }
        return "hip=${"%.0f,%.0f,%.0f".format(hip.position3D.x, hipY, hipZ)} " +
            "sh=${"%.0f,%.0f,%.0f".format(shoulder.position3D.x, shY, shZ)} " +
            "depth_slope=${"%.2f".format(depthFrac)}"
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
