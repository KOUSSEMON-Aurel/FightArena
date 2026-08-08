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

    /** Vide la fenêtre : la moyenne repart de zéro (utilisé après le warm-up). */
    fun reset() {
        head = 0
        n = 0
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
class PoseAnalyzer(
    private val overlay: PoseOverlayView,
    context: android.content.Context,
) : ImageAnalysis.Analyzer, PoseSource {

    /** Détection des 6 gestes de combat sur les landmarks filtrés (spec gesture-spec.md). */
    private val gestureDetector = GestureDetector.fromAssets(context) { ev ->
        Log.i(
            "PoseGesture",
            "TRIGGER ${ev.name}" +
                (ev.side?.let { "_$it" } ?: "") +
                (ev.direction?.let { "_$it" } ?: "") +
                " ${ev.signals}"
        )
        overlay.setLastGesture(gestureLabel(ev))
    }

    /** Libellé français du geste pour le HUD. */
    private fun gestureLabel(ev: GestureEvent): String = when (ev.name) {
        "jab" -> if (ev.side == "left") "JAB GAUCHE" else "JAB DROIT"
        "hook" -> if (ev.side == "left") "CROCHET GAUCHE" else "CROCHET DROIT"
        "uppercut" -> if (ev.side == "left") "UPPERCUT GAUCHE" else "UPPERCUT DROIT"
        "duck" -> "DUCK"
        "duck_degraded" -> "DUCK FAIBLE"
        "dodge" -> if (ev.direction == "left") "ESQUIVE GAUCHE" else "ESQUIVE DROITE"
        "guard" -> "GARDE"
        else -> ev.name
    }

    private val detector: PoseDetector = PoseDetection.getClient(
        when (POSE_MODEL) {
            PoseModel.ACCURATE -> AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptionsBase.STREAM_MODE)
                .setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
                .build()
            PoseModel.BASE -> PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                // CPU_GPU : GPU OpenCL si dispo (fallback CPU automatique de ML Kit).
                .setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
                .build()
        }
    )

    override val stats = LatencyStats(240)
    override var frameCount = 0
        private set

    private var lastFrameNs = 0L
    private var fps = 0f
    private var callsCount = 0
    private var droppedCount = 0
    private var warmedUp = false

    /** Lissage One-Euro des 33 landmarks (x, y, z) : anti-saccades sans traînée
     *  sur les gestes rapides. Zéro allocation : écrit dans le buffer partagé. */
    private val smoother = LandmarkSmoother()
    private val smoothOut = FloatArray(3)

    /** Backpressure : true tant que process() n'a pas rendu son résultat.
     *  ML Kit process() est asynchrone : sans garde, chaque frame CameraX
     *  déclenche une inférence et elles s'empilent (calls >> frames),
     *  la latence grossit à chaque frame. On recrée le comportement
     *  synchrone avec KEEP_ONLY_LATEST en droppant les frames pendant
     *  qu'une détection est en cours. */
    @Volatile private var detectionInFlight = false

    /**
     * Warm-up : une détection sur image noire avant le flux réel.
     * Le 1er vrai frame GPU inclut la compilation des shaders Mali (spikes
     * 600-1500ms observés). La détection de warm-up absorbe ce coût hors flux
     * (process asynchrone : le flux camera continue, seule l'inférence warm-up
     * tourne). On reset les stats ensuite pour ne pas polluer l'avg/p95.
     */
    private fun warmUp() {
        try {
            val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.BLACK)
            detector.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { stats.reset(); Log.i("PoseAnalyzer", "warm-up done") }
                .addOnFailureListener { e -> Log.w("PoseAnalyzer", "warm-up failed", e) }
        } catch (e: Exception) {
            Log.w("PoseAnalyzer", "warm-up exception", e)
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!warmedUp) {
            warmedUp = true
            warmUp()
        }
        if (detectionInFlight) {
            droppedCount++
            imageProxy.close()
            return
        }
        detectionInFlight = true
        callsCount++
        val mediaImage = imageProxy.image ?: run {
            detectionInFlight = false
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
                // Filtrage One-Euro (normalisé par dims) + remplissage du buffer publié.
                // Une allocation de 528B par frame, volontairement pas réutilisée :
                // le listener tourne sur un thread ML Kit, l'overlay lit la
                // référence sur le main thread (pas de course d'écriture).
                val landmarkBuf = FloatArray(33 * 4)
                fillLandmarkBuf(pose, landmarkBuf, displayW, displayH, startNs / 1e9)
                if (landmarkBuf[3] >= 0f) {
                    // Détection des gestes sur le buffer FILTRÉ (anti-saccades déjà appliqué)
                    gestureDetector.process(landmarkBuf, startNs / 1e9, displayH)
                } else {
                    gestureDetector.resetAll()
                }
                if (frameCount % 60 == 0) {
                    Log.i(
                        "PosePerf",
                        "frames=$frameCount calls=$callsCount fps=${"%.1f".format(fps)} " +
                            "infer_avg=${"%.1f".format(stats.avg())}ms " +
                            "p95=${"%.1f".format(stats.p95())}ms max=${"%.1f".format(stats.max())}ms " +
                            "drop=$droppedCount " +
                            "world3d=${world3dSummary(landmarkBuf)}"
                    )
                }
                overlay.onPose(landmarkBuf, latencyMs, displayW, displayH, this)
                // Libère le backpressure AVANT close() : l'image est consommée par ML Kit.
                detectionInFlight = false
                imageProxy.close()
            }
            .addOnFailureListener { e: Exception ->
                Log.e("PoseAnalyzer", "pose detection failed", e)
                detectionInFlight = false
                imageProxy.close()
            }
    }

    /**
     * Filtre les 33 landmarks et remplit landmarkBuf ([x,y,z,lik]*33, pixels).
     * Personne absente -> reset du smoother (pas de traînée au retour).
     * Landmark absent -> -1 (invisible, l'overlay ne le dessine pas).
     * Le filtre travaille en coordonnées normalisées (One-Euro est échelle-
     * dépendant) puis re-écrit des pixels.
     */
    private fun fillLandmarkBuf(pose: Pose, landmarkBuf: FloatArray, imageW: Int, imageH: Int, t: Double) {
        if (pose.getAllPoseLandmarks().isEmpty()) {
            smoother.reset()
            landmarkBuf.fill(-1f)
            return
        }
        for (i in 0 until 33) {
            val l = pose.getPoseLandmark(i)
            val idx = i * 4
            if (l == null) {
                landmarkBuf[idx] = -1f
                landmarkBuf[idx + 1] = -1f
                landmarkBuf[idx + 2] = -1f
                landmarkBuf[idx + 3] = -1f
                continue
            }
            val p = l.position3D
            smoother.smooth(i, p.x, p.y, p.z, imageW, imageH, t, smoothOut)
            landmarkBuf[idx] = smoothOut[0]
            landmarkBuf[idx + 1] = smoothOut[1]
            landmarkBuf[idx + 2] = smoothOut[2]
            landmarkBuf[idx + 3] = l.inFrameLikelihood
        }
    }

    /**
     * Résumé 3D relatif (ML Kit : profondeur z en pixels, relative à l'image ;
     * worldLandmarks en mètres approximatifs normalisés sur la hauteur estimée).
     * Utilise les coordonnées FILTRÉES (landmarkBuf) : le log reflète la
     * stabilité réelle après lissage. Retourne "none" si invisible.
     */
    private fun world3dSummary(landmarkBuf: FloatArray): String {
        val hipIdx = PoseLandmark.LEFT_HIP * 4
        val shIdx = PoseLandmark.LEFT_SHOULDER * 4
        if (landmarkBuf[hipIdx + 3] < 0f || landmarkBuf[shIdx + 3] < 0f) return "none"
        val hipZ = landmarkBuf[hipIdx + 2]
        val shZ = landmarkBuf[shIdx + 2]
        val hipY = landmarkBuf[hipIdx + 1]
        val shY = landmarkBuf[shIdx + 1]
        val depthFrac = (hipZ - shZ) / (hipY - shY).let { if (it == 0f) 1f else it }
        return "hip=${"%.0f,%.0f,%.0f".format(landmarkBuf[hipIdx], hipY, hipZ)} " +
            "sh=${"%.0f,%.0f,%.0f".format(landmarkBuf[shIdx], shY, shZ)} " +
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
