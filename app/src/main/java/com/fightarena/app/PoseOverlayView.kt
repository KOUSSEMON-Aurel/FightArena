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
    private var mpPts: List<FloatArray>? = null
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
    // Buffers réutilisés : zéro allocation dans onDraw (au lieu de ~730/frame)
    private val linePts = FloatArray(4 * SKELETON.size)
    private val tmpA = FloatArray(2)
    private val tmpB = FloatArray(2)
    private val tmpP = FloatArray(2)

    /** Preview en miroir (caméra avant) : on inverse l'axe X des landmarks pour rester aligné. */
    var mirrored = false

    fun onPose(pose: Pose, latencyMs: Float, imageWidth: Int, imageHeight: Int, analyzer: PoseSource) {
        this.pose = pose
        this.mpPts = null
        updateFrame(latencyMs, imageWidth, imageHeight, analyzer)
    }

    /**
     * Résultat MediaPipe Tasks : 33 points [x_px, y_px, visibility] dans le repère
     * image redressé (même convention que ML Kit). Liste vide = aucune pose.
     */
    fun onMediaPipePose(
        pts: List<FloatArray>,
        latencyMs: Float,
        imageWidth: Int,
        imageHeight: Int,
        analyzer: PoseSource,
    ) {
        this.pose = null
        this.mpPts = pts
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

    /** Recalcul du mapping FILL_CENTER — ne dépend que des tailles, pas du contenu. */
    private fun recomputeMap() {
        if (imageW <= 0 || imageH <= 0) return
        mapScale = maxOf(width / imageW.toFloat(), height / imageH.toFloat())
        mapOffsetX = (width - imageW * mapScale) / 2f
        mapOffsetY = (height - imageH * mapScale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMap()
    }

    /**
     * Mapping image -> écran identique au PreviewView (FILL_CENTER) :
     * on agrandit l'image pour remplir la vue (crop symétrique), puis on applique le miroir.
     * Écrit dans `out` (pré-alloué) — zéro allocation.
     */
    private fun map(x: Float, y: Float, out: FloatArray) {
        val px = mapOffsetX + x * mapScale
        val py = mapOffsetY + y * mapScale
        out[0] = if (mirrored) width - px else px
        out[1] = py
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pose = pose
        if (pose == null && mpPts == null) return

        drawPlacementGuide(canvas, pose)
        drawSkeleton(canvas, pose)
        drawHud(canvas)
    }

    /** Point i en coordonnées image [x, y], null si invisible (trop peu fiable). */
    private fun imagePoint(pose: Pose?, i: Int): FloatArray? {
        val p: FloatArray
        val visibility: Float
        if (pose != null) {
            val lm = pose.getPoseLandmark(i) ?: return null
            visibility = lm.inFrameLikelihood
            p = floatArrayOf(lm.position.x, lm.position.y)
        } else {
            p = mpPts?.getOrNull(i) ?: return null
            visibility = p[2]
        }
        if (visibility < DISPLAY_VISIBILITY) return null
        return p
    }

    private fun drawSkeleton(canvas: Canvas, pose: Pose?) {
        var n = 0
        for (edge in SKELETON) {
            val a = imagePoint(pose, edge[0]) ?: continue
            val b = imagePoint(pose, edge[1]) ?: continue
            map(a[0], a[1], tmpA)
            map(b[0], b[1], tmpB)
            linePts[n] = tmpA[0]; linePts[n + 1] = tmpA[1]
            linePts[n + 2] = tmpB[0]; linePts[n + 3] = tmpB[1]
            n += 4
        }
        if (n > 0) canvas.drawLines(linePts, 0, n, skeletonPaint)
        for (i in 0 until 33) {
            val p = imagePoint(pose, i) ?: continue
            map(p[0], p[1], tmpP)
            canvas.drawCircle(tmpP[0], tmpP[1], 7f, jointPaint)
        }
    }

    /** Guide de placement : hauteur du tronc (spec, distance 2.0-2.2 m). */
    private fun drawPlacementGuide(canvas: Canvas, pose: Pose?) {
        val s = imagePoint(pose, 11) ?: return
        val d = imagePoint(pose, 12) ?: return
        val lh = imagePoint(pose, 23) ?: return
        val rh = imagePoint(pose, 24) ?: return

        val shoulderY = (s[1] + d[1]) / 2f
        val hipY = (lh[1] + rh[1]) / 2f
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
