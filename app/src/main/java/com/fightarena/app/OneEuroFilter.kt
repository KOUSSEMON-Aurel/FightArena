package com.fightarena.app

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter (Casiez et al., CHI 2012) — lissage adaptatif temps réel.
 *
 * Deux paramètres :
 * - minCutoff : cutoff minimum (Hz). Bas = lisse fort quand l'articulation est
 *   immobile (anti-bruit du lite sur les coordonnées).
 * - beta : sensibilité à la vitesse. Élevé = suit les gestes rapides (jab) sans
 *   traînée, même avec un cutoff bas.
 *
 * Valeurs retenues (temps réel ~20fps, 33 landmarks) : minCutoff=1.0 Hz,
 * beta=0.007, dCutoff=1.0 Hz — la combinaison standard pour du suivi de pose.
 */
class OneEuroFilter(
    private val minCutoff: Double,
    private val beta: Double,
    private val dCutoff: Double,
) {
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = -1.0

    /** Filtre la valeur x à l'instant t (secondes). Retourne x brut au 1er appel. */
    fun filter(x: Double, t: Double): Double {
        if (tPrev < 0.0) {
            xPrev = x
            dxPrev = 0.0
            tPrev = t
            return x
        }
        val dt = t - tPrev
        val alphaD = smoothingFactor(dCutoff, dt)
        val dx = (x - xPrev) / dt
        val dxHat = alphaD * dx + (1.0 - alphaD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val alpha = smoothingFactor(cutoff, dt)
        val xHat = alpha * x + (1.0 - alpha) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        tPrev = t
        return xHat
    }

    fun reset() {
        tPrev = -1.0
    }

    private fun smoothingFactor(cutoff: Double, dt: Double): Double {
        val r = 2.0 * PI * cutoff * dt
        return r / (r + 1.0)
    }
}

/**
 * Lisseur des 33 landmarks BlazePose (x, y, z normalisés) : 99 filtres One-Euro,
 * un par coordonnée. Zéro allocation par frame (filtres pré-alloués).
 */
class LandmarkSmoother(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val dCutoff: Double = 1.0,
) {
    private val fx = Array(33) { OneEuroFilter(minCutoff, beta, dCutoff) }
    private val fy = Array(33) { OneEuroFilter(minCutoff, beta, dCutoff) }
    private val fz = Array(33) { OneEuroFilter(minCutoff, beta, dCutoff) }

    /** Lisse les coordonnées normalisées du landmark i ; retourne [x, y, z] lissés. */
    fun smooth(i: Int, x: Double, y: Double, z: Double, t: Double): DoubleArray {
        return doubleArrayOf(fx[i].filter(x, t), fy[i].filter(y, t), fz[i].filter(z, t))
    }

    /** À appeler quand la personne sort du cadre : évite la traînée au retour. */
    fun reset() {
        for (i in 0 until 33) {
            fx[i].reset()
            fy[i].reset()
            fz[i].reset()
        }
    }
}
