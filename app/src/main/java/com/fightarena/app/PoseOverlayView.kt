package com.fightarena.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Overlay : squelette 33 points, guide de placement (spec section 1) et HUD de perf.
 * Entrée : landmarkBuf [x,y,z,lik]*33 en pixels image (repère ML Kit, x->droite,
 * y->bas), déjà lissé One-Euro par PoseAnalyzer ; lik < 0 = landmark absent.
 * Zero-alloc dans onDraw : mapping précalculé + buffers réutilisés.
 */
class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** Bornes de la hauteur du tronc en fraction de la hauteur d'image
         *  (calibré 70-150px sur 480px = distance de jeu 2.0-2.2 m de la spec).
         *  Normalisé : indépendant de la résolution d'analyse. */
        val TRONC_MIN_FRAC = 0.14f
        val TRONC_MAX_FRAC = 0.32f
        /** Seuil spec pour la logique de gestes (inchangé). */
        const val MIN_VISIBILITY = 0.5f
        /** Seuil d'affichage plus permissif : montre les points même peu fiables. */
        const val DISPLAY_VISIBILITY = 0.3f

        /** Connexions du squelette, indices MediaPipe/ML Kit (33 landmarks). */
        val SKELETON = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 7),
            intArrayOf(0, 4), intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 8),
            intArrayOf(9, 10), intArrayOf(11, 12),
            intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(15, 17), intArrayOf(15, 19),
            intArrayOf(15, 21), intArrayOf(17, 19),
            intArrayOf(12, 14), intArrayOf(14, 16), intArrayOf(16, 18), intArrayOf(16, 20),
            intArrayOf(16, 22), intArrayOf(18, 20),
            intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
            intArrayOf(23, 25), intArrayOf(24, 26), intArrayOf(25, 27), intArrayOf(26, 28),
            intArrayOf(27, 29), intArrayOf(28, 30), intArrayOf(29, 31), intArrayOf(30, 32),
            intArrayOf(27, 31), intArrayOf(28, 32),
        )
    }

    private val skeletonPaint = Paint().apply {
        color = Color.rgb(0, 255, 140)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val jointPaint = Paint().apply {
        color = Color.rgb(0, 200, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
    }
    private val warnPaint = Paint(textPaint).apply { color = Color.rgb(255, 80, 80) }
    private val okPaint = Paint(textPaint).apply { color = Color.rgb(0, 255, 140) }
    private val gesturePaint = Paint().apply {
        color = Color.rgb(255, 200, 60)
        textSize = 64f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    /** Dernier geste détecté affiché à l'écran (null = rien). @Volatile : écrit
     *  depuis le thread ML Kit, lu par onDraw sur le main thread. */
    @Volatile
    private var lastGesture: String? = null
    private var lastGestureNs = 0L
    private val GESTURE_SHOW_MS = 1500L

    /** Affiche le dernier geste détecté (appelé depuis le thread ML Kit). */
    fun setLastGesture(text: String) {
        lastGesture = text
        lastGestureNs = System.nanoTime()
        postInvalidateOnAnimation()
    }

    private var landmarkBuf: FloatArray? = null
    private var latencyMs = 0f
    private var imageW = 1
    private var imageH = 1
    private var frameCount = 0
    private var fps = 0f
    private var lastFrameNs = 0L
    private var stats: LatencyStats? = null

    // Mapping précalculé (recalculé seulement si taille ou dimension image change)
    private var mapScale = 1f
    private var mapOffsetX = 0f
    private var mapOffsetY = 0f
    // Buffers réutilisés : zéro allocation dans onDraw
    private val linePts = FloatArray(4 * SKELETON.size)
    private val tmpA = FloatArray(2)
    private val tmpB = FloatArray(2)
    private val tmpP = FloatArray(2)

    /** Preview en miroir (caméra avant) : on inverse l'axe X des landmarks pour rester aligné. */
    var mirrored = false

    fun onPose(landmarkBuf: FloatArray, latencyMs: Float, imageWidth: Int, imageHeight: Int, analyzer: PoseSource) {
        this.landmarkBuf = landmarkBuf
        updateFrame(latencyMs, imageWidth, imageHeight, analyzer)
    }

    private fun updateFrame(latencyMs: Float, imageWidth: Int, imageHeight: Int, analyzer: PoseSource) {
        this.latencyMs = latencyMs
        if (imageWidth != this.imageW || imageHeight != this.imageH) {
            this.imageW = imageWidth
            this.imageH = imageHeight
            recomputeMap()
        }
        this.frameCount = analyzer.frameCount
        this.stats = analyzer.stats
        val now = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dt = (now - lastFrameNs) / 1e9f
            if (dt > 0f) fps = 0.9f * fps + 0.1f * (1f / dt)
        }
        lastFrameNs = now
        postInvalidateOnAnimation()
    }

    private fun recomputeMap() {
        if (imageW <= 0 || imageH <= 0) return
        mapScale = maxOf(width / imageW.toFloat(), height / imageH.toFloat())
        mapOffsetX = (width - imageW * mapScale) / 2f
        mapOffsetY = (height - imageH * mapScale) / 2f
    }

    /**
     * Mapping image -> écran identique au PreviewView (FILL_CENTER) :
     * on agrandit l'image pour remplir la vue (crop symétrique), puis on applique le miroir.
     */
    private fun mapTo(x: Float, y: Float, out: FloatArray): FloatArray {
        val mx = mapOffsetX + x * mapScale
        val my = mapOffsetY + y * mapScale
        out[0] = if (mirrored) width - mx else mx
        out[1] = my
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val landmarkBuf = landmarkBuf ?: return

        drawPlacementGuide(canvas, landmarkBuf)
        drawSkeleton(canvas, landmarkBuf)
        drawLastGesture(canvas)
        drawHud(canvas)
    }

    /** Gros texte centré du dernier geste, fondu après 1.5 s. */
    private fun drawLastGesture(canvas: Canvas) {
        val g = lastGesture ?: return
        val age = (System.nanoTime() - lastGestureNs) / 1_000_000
        if (age > GESTURE_SHOW_MS) return
        val fade = (GESTURE_SHOW_MS - age).coerceIn(0L, 400L) * 255 / 400
        gesturePaint.alpha = fade.toInt().coerceIn(0, 255)
        val textW = gesturePaint.measureText(g)
        canvas.drawText(g, (width - textW) / 2f, height * 0.35f, gesturePaint)
    }

    /** Retourne null si le landmark i est absent (lik < 0) ou peu fiable. */
    private fun lm(buf: FloatArray, i: Int, out: FloatArray): FloatArray? {
        val idx = i * 4
        if (buf[idx + 3] < DISPLAY_VISIBILITY) return null
        return mapTo(buf[idx], buf[idx + 1], out)
    }

    private fun drawSkeleton(canvas: Canvas, buf: FloatArray) {
        var edge = 0
        for (e in SKELETON) {
            val a = lm(buf, e[0], tmpA) ?: continue
            val b = lm(buf, e[1], tmpB) ?: continue
            linePts[edge] = a[0]; linePts[edge + 1] = a[1]
            linePts[edge + 2] = b[0]; linePts[edge + 3] = b[1]
            edge += 4
        }
        if (edge > 0) canvas.drawLines(linePts, 0, edge, skeletonPaint)

        for (i in 0 until 33) {
            val idx = i * 4
            if (buf[idx + 3] < DISPLAY_VISIBILITY) continue
            val p = mapTo(buf[idx], buf[idx + 1], tmpP)
            canvas.drawCircle(p[0], p[1], 7f, jointPaint)
        }
    }

    /** Guide de placement : hauteur du tronc (spec, distance 2.0-2.2 m). */
    private fun drawPlacementGuide(canvas: Canvas, buf: FloatArray) {
        val sIdx = PoseLandmark.LEFT_SHOULDER * 4
        val dIdx = PoseLandmark.RIGHT_SHOULDER * 4
        val lhIdx = PoseLandmark.LEFT_HIP * 4
        val rhIdx = PoseLandmark.RIGHT_HIP * 4
        if (buf[sIdx + 3] < DISPLAY_VISIBILITY || buf[dIdx + 3] < DISPLAY_VISIBILITY ||
            buf[lhIdx + 3] < DISPLAY_VISIBILITY || buf[rhIdx + 3] < DISPLAY_VISIBILITY
        ) return

        val shoulderY = (buf[sIdx + 1] + buf[dIdx + 1]) / 2f
        val hipY = (buf[lhIdx + 1] + buf[rhIdx + 1]) / 2f
        val troncPx = Math.abs(shoulderY - hipY)
        val troncFrac = troncPx / imageH

        val msg: String
        val paint: Paint
        val color = when {
            troncFrac < TRONC_MIN_FRAC -> { msg = "AVANCEZ (tronc ${(troncFrac * 100).toInt()}%)"; warnPaint }
            troncFrac > TRONC_MAX_FRAC -> { msg = "RECULEZ (tronc ${(troncFrac * 100).toInt()}%)"; warnPaint }
            else -> { msg = "DISTANCE OK (tronc ${(troncFrac * 100).toInt()}%)"; okPaint }
        }
        paint = color
        canvas.drawText(msg, 20f, 50f, paint)
    }

    private fun drawHud(canvas: Canvas) {
        val stats = stats ?: return
        val line = "fps ${"%.1f".format(fps)}  |  infer avg ${"%.1f".format(stats.avg())}ms  " +
            "p95 ${"%.1f".format(stats.p95())}ms  max ${"%.1f".format(stats.max())}ms  |  frames $frameCount"
        canvas.drawText(line, 20f, height - 30f, textPaint)
        canvas.drawText("frame ${"%.1f".format(latencyMs)}ms", 20f, height - 70f, textPaint)
    }
}
