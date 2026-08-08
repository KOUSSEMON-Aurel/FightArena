package com.fightarena.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Stickman 2D animé montrant chaque geste de combat (galerie d'apprentissage).
 *
 * Le squelette est défini en poses keyframes : coordonnées normalisées avec
 * 1 unité = hauteur du tronc (épaule-hanche), origine = milieu des hanches,
 * y vers le bas, x vers la droite (vue de face). Les clips enchaînent les
 * poses par interpolation linéaire et bouclent en continu.
 *
 * 13 joints : 0 nez, 1/2 épaules G/D, 3/4 coudes G/D, 5/6 poignets G/D,
 * 7/8 hanches G/D, 9/10 genoux G/D, 11/12 chevilles G/D.
 */
/** Une pose = 13 joints x (x,y), en unités tronc (1 unité = hauteur épaule-hanche).
 *  Origine = milieu des hanches, y vers le bas, x vers la droite (vue de face). */
private fun pose(vararg v: Float): FloatArray = v

// ---------------------------------------------------------------- poses

private val P_GUARD = pose(
    0f, -1.35f,                  // nez
    -0.28f, -1.00f, 0.28f, -1.00f, // épaules
    -0.35f, -0.60f, 0.35f, -0.60f, // coudes
    -0.13f, -1.05f, 0.13f, -1.05f, // poignets (garde haute)
    -0.17f, 0.00f, 0.17f, 0.00f,   // hanches
    -0.20f, 1.05f, 0.20f, 1.05f,   // genoux
    -0.16f, 2.05f, 0.16f, 2.05f,   // chevilles
)
private val P_GUARD2 = pose(
    0f, -1.33f,
    -0.28f, -1.00f, 0.28f, -1.00f,
    -0.36f, -0.58f, 0.36f, -0.58f,
    -0.16f, -1.12f, 0.16f, -1.12f, // poings légèrement avancés
    -0.17f, 0.00f, 0.17f, 0.00f,
    -0.20f, 1.05f, 0.20f, 1.05f,
    -0.16f, 2.05f, 0.16f, 2.05f,
)
private val P_JAB = pose(
    0f, -1.35f,
    -0.28f, -1.00f, 0.28f, -1.00f,
    -0.35f, -0.60f, 0.52f, -1.00f, // coude D presque aligné (bras tendu)
    -0.13f, -1.05f, 0.80f, -0.95f, // poing D étendu vers l'avant
    -0.17f, 0.00f, 0.17f, 0.00f,
    -0.20f, 1.05f, 0.20f, 1.05f,
    -0.16f, 2.05f, 0.16f, 2.05f,
)
private val P_HOOK = pose(
    0.05f, -1.30f,                 // tête suit la rotation
    -0.30f, -1.15f, 0.20f, -0.88f, // épaules : rotation (D avance/baisse)
    -0.32f, -0.65f, 0.50f, -0.75f, // coude D plié, écarté
    -0.13f, -1.05f, 0.85f, -0.90f, // poing D en crochet latéral
    -0.17f, 0.00f, 0.17f, 0.00f,
    -0.20f, 1.05f, 0.20f, 1.05f,
    -0.16f, 2.05f, 0.16f, 2.05f,
)
private val P_UP_BAS = pose(
    0f, -1.45f,
    -0.28f, -1.10f, 0.28f, -1.10f,
    -0.30f, -0.75f, 0.28f, -0.75f, // coudes pliés serrés
    -0.10f, -0.45f, 0.12f, -0.42f, // poings bas (charge)
    -0.20f, 0.18f, 0.20f, 0.18f,   // hanches fléchies
    -0.26f, 1.15f, 0.26f, 1.15f,
    -0.16f, 2.05f, 0.16f, 2.05f,
)
private val P_UP_HAUT = pose(
    0f, -1.35f,
    -0.28f, -1.00f, 0.28f, -1.00f,
    -0.35f, -0.60f, 0.22f, -0.72f, // coude D plié
    -0.13f, -1.05f, 0.06f, -1.20f, // poing D remonté sous le menton
    -0.17f, 0.00f, 0.17f, 0.00f,
    -0.20f, 1.05f, 0.20f, 1.05f,
    -0.16f, 2.05f, 0.16f, 2.05f,
)
private val P_DUCK = pose(
    0f, -0.75f,                    // nez sous les épaules
    -0.30f, -0.50f, 0.30f, -0.50f, // épaules abaissées
    -0.34f, -0.15f, 0.34f, -0.15f,
    -0.12f, -0.48f, 0.12f, -0.48f, // poings remontés en garde basse
    -0.20f, 0.30f, 0.20f, 0.30f,   // hanches basses
    -0.34f, 1.10f, 0.34f, 1.10f,   // genoux fléchis
    -0.16f, 2.05f, 0.16f, 2.05f,
)

private fun shifted(base: FloatArray, dx: Float): FloatArray {
    val out = base.copyOf()
    for (i in 0 until out.size step 2) out[i] += dx
    return out
}

// ----------------------------------------------------------------- clips

data class Clip(val poses: Array<FloatArray>, val durations: FloatArray)

private val clipJab = Clip(
    arrayOf(P_GUARD, P_JAB, P_GUARD, P_GUARD),
    floatArrayOf(0.35f, 0.22f, 0.40f, 0.25f),
)
private val clipHook = Clip(
    arrayOf(P_GUARD, P_HOOK, P_GUARD, P_GUARD),
    floatArrayOf(0.35f, 0.30f, 0.40f, 0.25f),
)
private val clipUppercut = Clip(
    arrayOf(P_GUARD, P_UP_BAS, P_UP_HAUT, P_GUARD),
    floatArrayOf(0.35f, 0.30f, 0.25f, 0.45f),
)
private val clipDuck = Clip(
    arrayOf(P_GUARD, P_DUCK, P_DUCK, P_GUARD),
    floatArrayOf(0.30f, 0.30f, 0.40f, 0.40f),
)
private val clipDodge = Clip(
    arrayOf(P_GUARD, shifted(P_GUARD, 0.55f), P_GUARD, P_GUARD),
    floatArrayOf(0.30f, 0.20f, 0.40f, 0.25f),
)
private val clipGuard = Clip(
    arrayOf(P_GUARD, P_GUARD2, P_GUARD),
    floatArrayOf(0.80f, 0.80f, 0.80f),
)

/** Les 6 gestes avec leur clip d'animation. */
enum class Gesture(val clip: Clip, val hasSide: Boolean) {
    JAB(clipJab, true),
    HOOK(clipHook, true),
    UPPERCUT(clipUppercut, true),
    DUCK(clipDuck, false),
    DODGE(clipDodge, true),
    GUARD(clipGuard, false),
}

/**
 * Stickman 2D animé montrant chaque geste de combat (galerie d'apprentissage).
 *
 * Le squelette est défini en poses keyframes (interpolation linéaire, boucle).
 *
 * 13 joints : 0 nez, 1/2 épaules G/D, 3/4 coudes G/D, 5/6 poignets G/D,
 * 7/8 hanches G/D, 9/10 genoux G/D, 11/12 chevilles G/D.
 */
class StickmanAnimatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val NOSE = 0; const val SHL = 1; const val SHR = 2
        const val ELBL = 3; const val ELBR = 4
        const val WRIL = 5; const val WRIR = 6
        const val HIPL = 7; const val HIPR = 8
        const val KNEEL = 9; const val KNEER = 10
        const val ANKLL = 11; const val ANKLR = 12

        /** Connexions : index pairs = début de segment dans la pose (x,y). */
        val BONES = arrayOf(
            intArrayOf(SHL, SHR), intArrayOf(SHL, ELBL), intArrayOf(ELBL, WRIL),
            intArrayOf(SHR, ELBR), intArrayOf(ELBR, WRIR),
            intArrayOf(SHL, HIPL), intArrayOf(SHR, HIPR),
            intArrayOf(HIPL, HIPR), intArrayOf(HIPL, KNEEL), intArrayOf(KNEEL, ANKLL),
            intArrayOf(HIPR, KNEER), intArrayOf(KNEER, ANKLR),
        )
    }

    // ------------------------------------------------------------- dessin

    private val bonePaint = Paint().apply {
        color = Color.rgb(0, 255, 140)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val jointPaint = Paint().apply {
        color = Color.rgb(0, 200, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val headPaint = Paint(bonePaint)
    private val dirPaint = Paint().apply {
        color = Color.rgb(255, 200, 60)
        textSize = 34f
        isAntiAlias = true
    }

    private var clip: Clip = Gesture.GUARD.clip
    private var flipX = false
    private var clipStartNs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (clipStartNs == 0L) clipStartNs = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var attached = false

    /** Sélectionne le geste animé (avec miroir pour le côté gauche). */
    fun setGesture(g: Gesture, flip: Boolean) {
        clip = g.clip
        flipX = flip
        clipStartNs = 0L
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        clipStartNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Pose interpolée courante dans le clip (dans une FloatArray réutilisée). */
    private fun currentPose(out: FloatArray) {
        var total = 0.0
        for (d in clip.durations) total += d
        val elapsed = if (total <= 0.0) 0.0
        else ((System.nanoTime() - clipStartNs) / 1e9) % total
        var acc = 0.0
        var k = 0
        for (i in clip.durations.indices) {
            acc += clip.durations[i]
            if (elapsed < acc || i == clip.durations.lastIndex) { k = i; break }
        }
        val from = clip.poses[k]
        val to = clip.poses[(k + 1) % clip.poses.size]
        val dPrev = if (k == 0) 0.0 else { var s = 0.0; for (i in 0 until k) s += clip.durations[i]; s }
        val t = ((elapsed - dPrev) / clip.durations[k]).toFloat().coerceIn(0f, 1f)
        for (i in from.indices) {
            out[i] = from[i] + (to[i] - from[i]) * t
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pose = FloatArray(26)
        currentPose(pose)

        val unit = minOf(width, height) / 5.5f
        val cx = width / 2f
        val cy = height * 0.55f
        fun px(x: Float) = cx + (if (flipX) -x else x) * unit
        fun py(y: Float) = cy + y * unit

        val pts = FloatArray(4 * BONES.size)
        var i = 0
        for (b in BONES) {
            pts[i] = px(pose[b[0] * 2]); pts[i + 1] = py(pose[b[0] * 2 + 1])
            pts[i + 2] = px(pose[b[1] * 2]); pts[i + 3] = py(pose[b[1] * 2 + 1])
            i += 4
        }
        canvas.drawLines(pts, bonePaint)

        val headR = 0.16f * unit
        canvas.drawCircle(px(pose[NOSE * 2]), py(pose[NOSE * 2 + 1] + headR * 0.6f), headR, headPaint)
        for (j in 1 until 13) {
            canvas.drawCircle(px(pose[j * 2]), py(pose[j * 2 + 1]), 8f, jointPaint)
        }
    }
}
