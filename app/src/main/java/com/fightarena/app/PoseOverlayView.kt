package com.fightarena.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Overlay : squelette 33 points, guide de placement (spec section 1) et HUD de perf.
 * Coordonnées ML Kit : repère de l'image analysée (paysage), x->droite, y->bas.
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

    private var pose: Pose? = null
    private var latencyMs = 0f
    private var imageW = 1
    private var imageH = 1
    private var frameCount = 0
    private var fps = 0f
    private var lastFrameNs = 0L
    private var stats: LatencyStats? = null

    /** Preview en miroir (caméra avant) : on inverse l'axe X des landmarks pour rester aligné. */
    var mirrored = false

    fun onPose(pose: Pose, latencyMs: Float, imageWidth: Int, imageHeight: Int, analyzer: PoseAnalyzer) {
        this.pose = pose
        this.latencyMs = latencyMs
        this.imageW = imageWidth
        this.imageH = imageHeight
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

    /**
     * Mapping image -> écran identique au PreviewView (FILL_CENTER) :
     * on agrandit l'image pour remplir la vue (crop symétrique), puis on applique le miroir.
     */
    private fun map(lm: PoseLandmark): FloatArray {
        val scale = maxOf(width / imageW.toFloat(), height / imageH.toFloat())
        val offsetX = (width - imageW * scale) / 2f
        val offsetY = (height - imageH * scale) / 2f
        val x = offsetX + lm.position.x * scale
        val y = offsetY + lm.position.y * scale
        return if (mirrored) floatArrayOf(width - x, y) else floatArrayOf(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pose = pose ?: return

        drawPlacementGuide(canvas, pose)
        drawSkeleton(canvas, pose)
        drawHud(canvas)
    }

    private fun drawSkeleton(canvas: Canvas, pose: Pose) {
        val pts = FloatArray(4)
        fun lm(i: Int): FloatArray? {
            val l = pose.getPoseLandmark(i) ?: return null
            if (l.inFrameLikelihood < DISPLAY_VISIBILITY) return null
            return map(l)
        }

        for (edge in SKELETON) {
            val a = lm(edge[0]) ?: continue
            val b = lm(edge[1]) ?: continue
            pts[0] = a[0]; pts[1] = a[1]; pts[2] = b[0]; pts[3] = b[1]
            canvas.drawLines(pts, skeletonPaint)
        }
        for (l in pose.getAllPoseLandmarks()) {
            if (l.inFrameLikelihood < DISPLAY_VISIBILITY) continue
            val p = map(l)
            canvas.drawCircle(p[0], p[1], 7f, jointPaint)
        }
    }

    /** Guide de placement : hauteur du tronc (spec, distance 2.0-2.2 m). */
    private fun drawPlacementGuide(canvas: Canvas, pose: Pose) {
        val s = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val d = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        if (s == null || d == null || lh == null || rh == null) return

        val shoulderY = (s.position.y + d.position.y) / 2f
        val hipY = (lh.position.y + rh.position.y) / 2f
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
