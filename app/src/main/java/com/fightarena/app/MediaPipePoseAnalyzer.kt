package com.fightarena.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Analyseur CameraX -> MediaPipe Tasks PoseLandmarker (BlazePose full,
 * pose_landmarker_full.task, mode LIVE_STREAM).
 *
 * Contrepartie exacte de l'ancien ML Kit accurate (tier full) : même modèle
 * BlazePose 256x256, mesuré plus rapide (24ms vs 31.6ms). Contrairement à
 * ML Kit, le résultat expose aussi les WORLD landmarks 3D.
 *
 * Points de design :
 * - Rotation déléguée à MediaPipe via ImageProcessingOptions.setRotationDegrees
 *   (le pipeline natif C++/GPU tourne l'image ; zéro boucle pixel Kotlin).
 * - Un seul bitmap réutilisé (buffer) : zéro allocation par frame → pas de GC.
 * - PAS de miroir dans la rotation : le miroir est géré par PoseOverlayView
 *   (mirrored=true) + previewView.scaleX=-1f → un seul endroit.
 * - Init EAGER main thread (GPU exige le même thread d'init et d'usage ; init
 *   lazy sur le thread d'analyse = crash natif silencieux sur Mali).
 * - Lissage One-Euro (LandmarkSmoother) sur x/y/z normalisés avant mise à
 *   l'échelle : filtre le bruit du modèle, garde la réactivité des gestes.
 */
class MediaPipePoseAnalyzer(
    private val overlay: PoseOverlayView,
    private val model: String = "lite",
    private val forceCpu: Boolean = false,
) :
    ImageAnalysis.Analyzer, PoseSource {

    override val stats = LatencyStats(240)
    override var frameCount = 0
        private set

    // ── Landmarker : CPU immédiat, GPU en arrière-plan, watchdog ───────────
    // L'init GPU sur le main thread ANR (8s de compilation de shaders Mali,
    // "Input dispatching timed out"). Solution : CPU init eager (rapide, ~100ms),
    // puis GPU créé sur un thread dédié ; swap dès qu'il est prêt. Le watchdog
    // bascule GPU -> CPU si l'inférence GPU reste > 35ms après 40 frames.
    private val analysisExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()
    private val gpuInitExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var poseLandmarker: PoseLandmarker
    @Volatile private var delegateUsed = "?"
    @Volatile private var generation = 0
    private var watchdogChecked = false

    // ── A/B test CPU vs GPU ────────────────────────────────────────────────
    // true  -> init CPU uniquement, pas de swap GPU, pas de watchdog GPU
    // false -> comportement normal : CPU eager puis swap GPU en arrière-plan
    private val FORCE_CPU_ABTEST: Boolean
    // Modèle cible : "lite" (pose_landmarker_lite.task, défaut) ou "full".
    // Le CPU eager utilise TOUJOURS le lite (~2x plus rapide) : c'est le warm-up
    // pendant l'init GPU (~12s de shaders). Le swap final utilise modelName.
    private val modelName: String

    init {
        modelName = model
        FORCE_CPU_ABTEST = forceCpu
        fun create(delegate: Delegate, gen: Int, useModel: String): PoseLandmarker {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(
                    if (useModel == "lite") "pose_landmarker_lite.task" else "pose_landmarker_full.task"
                )
                .setDelegate(delegate)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
                .setResultListener { result: PoseLandmarkerResult, _ ->
                    // Libère toujours le backpressure, même si le résultat vient
                    // d'un analyseur périmé (génération mismatch après swap GPU) :
                    // sinon detectionInFlight reste vrai à vie et plus rien ne passe.
                    detectionInFlight = false
                    if (generation == gen) onResult(result)
                }
                .setErrorListener { e ->
                    detectionInFlight = false
                    Log.e("MediaPipeAnalyzer", "live stream error", e)
                }
                .build()
            return PoseLandmarker.createFromOptions(overlay.context.applicationContext, options)
        }
        // CPU d'abord : init rapide sur le main thread, pas d'ANR. Toujours le
        // lite (2x plus rapide) : c'est la phase de warm-up pendant l'init GPU.
        poseLandmarker = try {
            create(Delegate.CPU, 0, "lite")
        } catch (e: RuntimeException) {
            Log.e("MediaPipeAnalyzer", "CPU init failed", e)
            throw e
        }
        delegateUsed = "cpu"
        Log.i("MediaPipeAnalyzer", "PoseLandmarker init OK delegate=cpu model=lite (warm-up)")
        // GPU en arrière-plan : swap atomique quand prêt (jamais sur le main).
        // Désactivé en mode A/B CPU (FORCE_CPU_ABTEST=true).
        if (!FORCE_CPU_ABTEST) gpuInitExecutor.execute {
            val initStartNs = System.nanoTime()
            try {
                val d = create(Delegate.GPU, 1, modelName)
                if (closed) {
                    try { d.close() } catch (_: Exception) {}
                    return@execute
                }
                generation = 1
                poseLandmarker = d
                delegateUsed = "gpu"
                gpuReady = true
                // Reset des stats : la fenêtre contient les frames CPU lentes de
                // l'init GPU (~12s de compilation shaders). Sans reset, le watchdog
                // croit que le GPU est lent et rebascule en CPU (faux positif).
                stats.reset()
                frameCount = 0
                emptyCount = 0
                watchdogChecked = false
                Log.i(
                    "MediaPipeAnalyzer",
                    "PoseLandmarker GPU ready, swapped (background init) in " +
                        "${"%.1f".format((System.nanoTime() - initStartNs) / 1e6f)}ms",
                )
            } catch (e: Exception) {
                Log.w("MediaPipeAnalyzer", "GPU init failed on background thread, keep CPU", e)
            }
        }
    }

    /** true dès que le GPU est actif (false = inférence CPU en attendant). */
    @Volatile var gpuReady = false

    @Volatile private var closed = false

    // Bascule GPU -> CPU si l'inférence GPU mesurée est lente (Mali et al.).
    // Exécutée sur le thread d'analyse : le CPU n'a pas de contrainte de thread.
    private fun maybeWatchdog() {
        if (watchdogChecked || delegateUsed != "gpu") return
        watchdogChecked = true
        if (frameCount < 40 || stats.avg() <= 35f) return
        Log.w("MediaPipeAnalyzer", "GPU infer avg=${"%.1f".format(stats.avg())}ms > 35ms -> switch CPU")
        generation = 2
        analysisExecutor.execute {
            try { poseLandmarker.close() } catch (_: Exception) {}
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .setDelegate(Delegate.CPU)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result: PoseLandmarkerResult, _ ->
                        if (generation == 2) onResult(result)
                    }
                    .setErrorListener { e -> Log.e("MediaPipeAnalyzer", "live stream error", e) }
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(
                    overlay.context.applicationContext,
                    options,
                )
                delegateUsed = "cpu"
                Log.i("MediaPipeAnalyzer", "PoseLandmarker rebuilt delegate=cpu (watchdog)")
            } catch (e: Exception) {
                Log.e("MediaPipeAnalyzer", "CPU rebuild failed", e)
            }
        }
    }

    // ── Buffer réutilisé — zéro allocation par frame ───────────────────────
    private var buffer: Bitmap? = null   // bitmap source (w×h), réutilisé
    private var rotBmp: Bitmap? = null   // bitmap redressé (portrait), réutilisé
    private val rotateMatrix = Matrix()  // réutilisé (pas de new Matrix à chaque frame)

    // ── Mesures ────────────────────────────────────────────────────────────
    private var lastFrameNs = 0L
    private var fps = 0f
    private var lastStartNs = 0L
    private var pendingImageW = 1
    private var pendingImageH = 1
    private var callCount = 0
    private var droppedCount = 0
    private var cycMs = 0f
    private var copyMs = 0f
    private var detMs = 0f
    private var lastAnalyzeNs = 0L
    private var gapMs = 0f   // temps entre 2 entrées analyze() : caméra seule
    private var emptyCount = 0  // frames résultat sans aucune pose (perte de détection)

    /** Mode vidéo de test : appelé une fois par résultat traité (frame consommée). */
    @Volatile var frameDone: (() -> Unit)? = null

    /** Backpressure : true tant que detectAsync n'a pas retourné son résultat.
     *  Évite l'accumulation dans la file LIVE_STREAM (lag croissant vidéo/points). */
    @Volatile private var detectionInFlight = false

    // ── Debug one-shot (premier frame + dump PNG) ───────────────────────────
    private var debugLogged = false
    private var frameDumped = false
    private val debugFile: java.io.File by lazy {
        java.io.File(overlay.context.applicationContext.filesDir, "frame_debug.txt")
    }

    private fun dumpFrame(tag: String, bmp: Bitmap) {
        if (frameDumped) return
        try {
            val f = java.io.File(overlay.context.applicationContext.filesDir, "$tag.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e("MediaPipeAnalyzer", "dump failed", e)
        }
    }

    private fun debugLine(msg: String) {
        try { debugFile.appendText("$msg\n") } catch (_: Exception) {}
    }

    // ── Analyse ────────────────────────────────────────────────────────────
    override fun analyze(imageProxy: ImageProxy) {
        val cycStart = System.nanoTime()
        callCount++
        if (lastAnalyzeNs != 0L) {
            val g = (System.nanoTime() - lastAnalyzeNs) / 1e6f
            gapMs = 0.9f * gapMs + 0.1f * g
        }
        lastAnalyzeNs = System.nanoTime()

        // BACKPRESSURE : si la détection précédente n'est pas encore revenue,
        // on drop la frame (close + return). Sans ça, detectAsync (non bloquant)
        // + CameraX KEEP_ONLY_LATEST empilent les frames dans la file interne de
        // MediaPipe : la latence grossit à chaque frame (calls >> frames) et
        // l'affichage décroche de la vidéo. ML Kit était synchrone donc jamais
        // de backlog ; on recrée ce comportement ici.
        if (detectionInFlight) {
            droppedCount++
            imageProxy.close()
            return
        }

        val image = imageProxy.image ?: run { imageProxy.close(); return }
        val rotation = imageProxy.imageInfo.rotationDegrees

        if (!debugLogged) {
            val msg = "frame ${image.width}x${image.height} rot=$rotation format=${imageProxy.format} delegate=$delegateUsed"
            Log.i("MediaPipeAnalyzer", msg)
            debugLine(msg)
            debugLogged = true
        }

        val w = image.width
        val h = image.height

        // Chemin ML Kit : on passe le MediaImage YUV natif directement à
        // MediaPipe (MediaImageBuilder = zéro copie bitmap, zéro conversion
        // RGBA CPU). La rotation est appliquée en interne par le pipeline GPU
        // via ImageProcessingOptions ; les landmarks sortent donc dans le
        // repère REDRESSE -> pendingImageW/H = w/h redressés (cf processBitmap).
        val mpImage = try {
            MediaImageBuilder(image).build()
        } catch (e: Exception) {
            Log.e("MediaPipeAnalyzer", "MediaImageBuilder error", e)
            imageProxy.close()
            return
        }

        // detectAsync consomme l'image de façon synchrone : le proxy ne doit
        // être fermé qu'APRÈS l'appel (sinon "Image is already closed").
        processBitmap(mpImage, w, h, rotation)
        imageProxy.close()
        cycMs = 0.9f * cycMs + 0.1f * ((System.nanoTime() - cycStart) / 1e6f)
    }

    /**
     * Mode vidéo de test : convertit le Bitmap en MPImage puis délègue au
     * chemin commun (rotation=0 : les frames de test sont déjà upright).
     */
    fun processBitmap(src: Bitmap, srcW: Int, srcH: Int, rotation: Int) {
        processBitmap(BitmapImageBuilder(src).build(), srcW, srcH, rotation)
    }

    /**
     * Détection async — reçoit un MPImage (YUV natif caméra via MediaImageBuilder,
     * ou Bitmap via BitmapImageBuilder pour le mode vidéo de test). La rotation
     * est déléguée à MediaPipe (ImageProcessingOptions) : le pipeline GPU tourne
     * l'image en interne, zéro copie Canvas CPU. L'image envoyée est la source
     * brute (non redressée) : les landmarks sortent dans le repère REDRESSE ->
     * pendingImageW/H = w/h redressés, l'overlay mappe comme avant.
     */
    fun processBitmap(mpImage: MPImage, srcW: Int, srcH: Int, rotation: Int) {
        val isRotated = rotation % 180 != 0
        val rw = if (isRotated) srcH else srcW
        val rh = if (isRotated) srcW else srcH
        pendingImageW = rw
        pendingImageH = rh

        lastStartNs = System.nanoTime()
        val detStart = System.nanoTime()
        try {
            detectionInFlight = true
            if (rotation != 0) {
                // CameraX : rotation horaire pour redresse (270). MediaPipe :
                // rotationDegrees antihoraire -> il faut inverser le sens
                // ((360-rot)%360). Sans ça, image tournée de 180° -> squelette
                // à l'envers / points désorganisés.
                val mpRot = (360 - rotation) % 360
                val opts = ImageProcessingOptions.builder()
                    .setRotationDegrees(mpRot)
                    .build()
                poseLandmarker.detectAsync(mpImage, opts, SystemClock.uptimeMillis())
            } else {
                poseLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
            }
        } catch (e: Exception) {
            detectionInFlight = false
            Log.e("MediaPipeAnalyzer", "detectAsync error", e)
        }
        detMs = 0.9f * detMs + 0.1f * ((System.nanoTime() - detStart) / 1e6f)
    }

    // ── Lissage One-Euro (x/y/z normalisés, 99 filtres pré-alloués) ────────
    // A/B lag : true=lissé, false=brut (comportement ML Kit d'origine).
    // NOTE : beta=0.007 était calibré pixels (0-640) mais le filtre reçoit du
    // normalisé 0-1 -> cutoff bloqué ~1Hz -> lag ~160ms. beta=5.0 = équivalent
    // normalisé du réglage standard (voir OneEuroFilter.kt).
    private val ONEEURO_ENABLED = false
    private val smoother = LandmarkSmoother()

    // ── Résultat async ─────────────────────────────────────────────────────
    private fun onResult(result: PoseLandmarkerResult) {
        detectionInFlight = false
        val latencyMs = (System.nanoTime() - lastStartNs) / 1e6f
        frameCount++
        stats.record(latencyMs)
        updateFps()
        maybeWatchdog()

        val landmarks = result.landmarks().firstOrNull().orEmpty()
        val worldLms = result.worldLandmarks().firstOrNull().orEmpty()
        if (landmarks.isEmpty()) {
            emptyCount++
            if (emptyCount == 1 || emptyCount % 60 == 0) {
                Log.w("MediaPipeAnalyzer", "no pose detected (empty frames=$emptyCount of $frameCount)")
            }
            smoother.reset()  // personne dans le cadre : évite la traînée au retour
            overlay.onMediaPipePose(emptyList(), latencyMs, pendingImageW, pendingImageH, this)
        } else {
            val w = pendingImageW.toFloat()
            val h = pendingImageH.toFloat()
            // Chemin rapide (OneEuro off) : une ArrayList + 33 FloatArray par
            // résultat (réutilisée par l'overlay). Pas de double-branche
            // if(sx!=null) par point.
            val pts = ArrayList<FloatArray>(landmarks.size)
            if (ONEEURO_ENABLED) {
                val t = SystemClock.uptimeMillis() / 1000.0
                for ((i, lm) in landmarks.withIndex()) {
                    val sx = smoother.smooth(i, lm.x().toDouble(), lm.y().toDouble(), lm.z().toDouble(), t)
                    pts.add(
                        floatArrayOf(
                            (sx[0] * w).toFloat(),
                            (sx[1] * h).toFloat(),
                            lm.visibility().orElse(0f),
                        ),
                    )
                }
            } else {
                for (lm in landmarks) {
                    pts.add(floatArrayOf(lm.x() * w, lm.y() * h, lm.visibility().orElse(0f)))
                }
            }
            overlay.onMediaPipePose(pts, latencyMs, pendingImageW, pendingImageH, this)
        }

        frameDone?.invoke()  // mode vidéo : signale que la frame est consommée

        if (frameCount % 60 == 0) {
            val msg =
                "frames=$frameCount calls=$callCount fps=${"%.1f".format(fps)} " +
                "infer_avg=${"%.1f".format(stats.avg())}ms " +
                "p95=${"%.1f".format(stats.p95())}ms max=${"%.1f".format(stats.max())}ms " +
                "cyc=${"%.1f".format(cycMs)}ms gap=${"%.1f".format(gapMs)}ms " +
                "copy=${"%.1f".format(copyMs)}ms det=${"%.1f".format(detMs)}ms " +
                "lm=${landmarks.size} empty=$emptyCount drop=$droppedCount " +
                "delegate=$delegateUsed engine=mediapipe " +
                "world3d=${worldMetrics(worldLms)}"
            Log.i("PosePerf", msg)
            debugLine(msg)
        }
    }

    /**
     * Métriques 3D réelles (mètres) extraites des worldLandmarks : hauteur
     * debout (sommet crâne - sol), largeur épaules, longueur tronc,
     * envergure bras et profondeur max (z). C'est la preuve chiffrée que la
     * 3D monde est vivante — ML Kit ne pouvait donner aucun de ces nombres.
     */
    private fun worldMetrics(worldLms: List<Landmark>): String {
        if (worldLms.isEmpty()) return "none"
        fun d(a: Int, b: Int): Float {
            val pa = worldLms[a]; val pb = worldLms[b]
            val dx = pa.x() - pb.x(); val dy = pa.y() - pb.y(); val dz = pa.z() - pb.z()
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        val shoulderW = d(11, 12)
        val hipW = d(23, 24)
        val trunk = (d(11, 23) + d(12, 24)) / 2f
        val reach = (d(0, 15) + d(0, 16)) / 2f
        val headToHip = (d(0, 23) + d(0, 24)) / 2f
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (wl in worldLms) {
            if (wl.z() < minZ) minZ = wl.z()
            if (wl.z() > maxZ) maxZ = wl.z()
        }
        return "sh=${"%.2f".format(shoulderW)}m hip=${"%.2f".format(hipW)}m " +
            "trunk=${"%.2f".format(trunk)}m reach=${"%.2f".format(reach)}m " +
            "height=${"%.2f".format(headToHip * 2)}m depth=${"%.2f".format(maxZ - minZ)}m"
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dt = (now - lastFrameNs) / 1e9f
            if (dt > 0f) fps = 0.9f * fps + 0.1f * (1f / dt)
        }
        lastFrameNs = now
    }

    fun close() {
        closed = true
        try { poseLandmarker.close() } catch (_: Exception) {}
        analysisExecutor.shutdownNow()
        gpuInitExecutor.shutdownNow()
    }
}
