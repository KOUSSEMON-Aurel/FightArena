package com.fightarena.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import org.tensorflow.lite.Interpreter

/**
 * Analyseur CameraX -> MoveNet Thunder (TFLite) -> buffer 33 landmarks.
 *
 * MoveNet Thunder (17 points COCO, input uint8 256x256) : sortie [1,1,17,3]
 * = (y, x, score) normalisés dans l'image d'entrée. Choisi pour comparer la
 * stabilité des gestures avec ML Kit (réputé plus stable en vidéo, forte
 * robustesse à basse latitude, smart cropping interne).
 *
 * Préparation de l'input : rotation vers l'orientation d'affichage (l'image
 * capteur est brute, pas de rotation appliquée par CameraX) + letterbox
 * 256x256 (aspect conservé). Inverse transform pour remapper les keypoints
 * dans le repère image pivotée (pixels) : même contrat que le pipeline ML Kit
 * (landmarks en pixels, image après rotation, largeur = hauteur capteur en
 * portrait). z = 0 (le GestureDetector ne lit jamais z).
 *
 * Après rotation de l'image, x/y sont dans le repère affichage du capteur
 * non miroiré (comme ML Kit) : même géométrie que l'ancien chemin, donc
 * même logique de geste, seule la source change.
 */
class MoveNetAnalyzer(
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

    companion object {
        private const val INPUT_SIZE = 256
        private const val MODEL_ASSET = "movenet_thunder_f16_4.tflite"

        /** Score MoveNet en-dessous duquel le point est considéré invisible. */
        private const val MIN_SCORE = 0.25f

        /** Index MoveNet (COCO 17) -> index BlazePose 33 (repère GestureDetector). */
        private val COCO_TO_BLAZE = intArrayOf(
            0,      // nose
            -1, -1, // yeux
            -1, -1, // oreilles
            11, 12, // épaules
            13, 14, // coudes
            15, 16, // poignets
            23, 24, // hanches
            25, 26, // genoux
            27, 28, // chevilles
        )
    }

    private val interpreter: Interpreter
    private val inputData: ByteBuffer
    private val outputData: Array<Array<Array<FloatArray>>>
    private val frameBitmap: Bitmap
    private val frameCanvas: Canvas

    override val stats = LatencyStats(240)
    override var frameCount = 0
        private set

    private var lastFrameNs = 0L
    private var fps = 0f
    private var callsCount = 0
    private var droppedCount = 0
    private var warmedUp = false

    /** Lissage One-Euro (même anti-saccades que le pipeline ML Kit). */
    private val smoother = LandmarkSmoother()
    private val smoothOut = FloatArray(3)

    /** Backpressure synchrone : 1 inférence à la fois, on drop le reste. */
    @Volatile private var detectionInFlight = false

    init {
        // Delegate GPU écarté : tensorflow-lite-gpu (2.13/2.16/2.17, LiteRT 1.0.1)
        // référence GpuDelegateFactory$Options sans le livrer -> NoClassDefFoundError.
        // CPU 4 threads : suffisant pour tester la stabilité des gestes.
        val options = Interpreter.Options().apply { setNumThreads(4) }

        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val modelBuf = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuf.put(modelBytes).rewind()
        interpreter = Interpreter(modelBuf, options)
        inputData = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
        outputData = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
        frameBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        frameCanvas = Canvas(frameBitmap)
    }

    /** Warm-up : une inférence sur image noire hors flux (compilation shaders GPU). */
    private fun warmUp() {
        try {
            frameBitmap.eraseColor(Color.BLACK)
            val px = IntArray(INPUT_SIZE * INPUT_SIZE)
            frameBitmap.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            inputData.rewind()
            for (p in px) {
                inputData.put((p shr 16 and 0xFF).toByte())
                inputData.put((p shr 8 and 0xFF).toByte())
                inputData.put((p and 0xFF).toByte())
            }
            inputData.rewind()
            interpreter.run(inputData, outputData)
            stats.reset()
            Log.i("MoveNet", "warm-up done")
        } catch (e: Exception) {
            Log.w("MoveNet", "warm-up exception", e)
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
        val startNs = System.nanoTime()
        try {
            // Rotate + letterbox + RGB -> input, et injecte dans interpreter.
            fillPreprocess(imageProxy)
            interpreter.run(inputData, outputData)
            val latencyMs = (System.nanoTime() - startNs) / 1e6f
            frameCount++
            stats.record(latencyMs)
            updateFps()

            // ML Kit retournait les landmarks dans le repère image après rotation :
            // en portrait (90/270), largeur = hauteur capteur et inversement.
            val rotated = imageProxy.imageInfo.rotationDegrees % 180 != 0
            val displayW = if (rotated) imageProxy.height else imageProxy.width
            val displayH = if (rotated) imageProxy.width else imageProxy.height

            val landmarkBuf = FloatArray(33 * 4)
            fillLandmarkBuf(landmarkBuf, displayW, displayH, startNs / 1e9)
            if (landmarkBuf[3] >= 0f) {
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
                        "drop=$droppedCount"
                )
            }
            overlay.onPose(landmarkBuf, latencyMs, displayW, displayH, this)
            detectionInFlight = false
            imageProxy.close()
        } catch (e: Exception) {
            Log.e("MoveNet", "inference failed", e)
            detectionInFlight = false
            imageProxy.close()
        }
    }

    /**
     * Rotation de l'image capteur vers l'orientation d'affichage + letterbox
     * 256x256 (aspect conservé) + conversion RGB -> uint8. Le repère de
     * sortie du modèle est l'image letterboxée : l'inverse transform est
     * appliquée dans fillLandmarkBuf.
     */
    private fun fillPreprocess(imageProxy: ImageProxy) {
        val src = imageProxy.toBitmap()
        val rot = imageProxy.imageInfo.rotationDegrees

        var from = src
        if (rot != 0) {
            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
            val w = src.width
            val h = src.height
            from = Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
        }

        // Letterbox : conserver l'aspect, compléter en noir.
        val scale = min(INPUT_SIZE.toFloat() / from.width, INPUT_SIZE.toFloat() / from.height)
        val drawW = (from.width * scale).toInt()
        val drawH = (from.height * scale).toInt()
        val dx = (INPUT_SIZE - drawW) / 2
        val dy = (INPUT_SIZE - drawH) / 2

        frameBitmap.eraseColor(Color.BLACK)
        frameCanvas.drawBitmap(from, null, Rect(dx, dy, dx + drawW, dy + drawH), null)
        if (from != src) from.recycle()

        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        frameBitmap.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        inputData.rewind()
        for (p in px) {
            inputData.put((p shr 16 and 0xFF).toByte())
            inputData.put((p shr 8 and 0xFF).toByte())
            inputData.put((p and 0xFF).toByte())
        }
        inputData.rewind()
    }

    /**
     * Remappe les 17 keypoints MoveNet (normalisés image 256) dans le buffer
     * 33 landmarks (pixels, image pivotée). Inverse du letterbox.
     */
    private fun fillLandmarkBuf(landmarkBuf: FloatArray, displayW: Int, displayH: Int, t: Double) {
        landmarkBuf.fill(-1f)
        val scale = min(INPUT_SIZE.toFloat() / displayW, INPUT_SIZE.toFloat() / displayH)
        val drawW = (displayW * scale).toInt()
        val drawH = (displayH * scale).toInt()
        val dx = (INPUT_SIZE - drawW) / 2
        val dy = (INPUT_SIZE - drawH) / 2

        var any = false
        val keypoints = outputData[0][0]
        for (k in 0 until 17) {
            val blaze = COCO_TO_BLAZE[k]
            if (blaze < 0) continue
            val score = keypoints[k][2]
            if (score < MIN_SCORE) continue
            // (y, x) normalisés dans l'image 256 (repère MoveNet).
            val nx = keypoints[k][1]
            val ny = keypoints[k][0]
            val px = (nx * INPUT_SIZE - dx) / scale
            val py = (ny * INPUT_SIZE - dy) / scale
            val idx = blaze * 4
            smoother.smooth(blaze, px, py, 0f, displayW, displayH, t, smoothOut)
            landmarkBuf[idx] = smoothOut[0]
            landmarkBuf[idx + 1] = smoothOut[1]
            landmarkBuf[idx + 2] = 0f
            landmarkBuf[idx + 3] = score
            any = true
        }
        if (!any) smoother.reset()
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