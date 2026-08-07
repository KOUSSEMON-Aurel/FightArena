package com.fightarena.app

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter (Casiez et al., CHI 2012) — lissage adaptatif temps réel,
 * + extrapolation dead-reckoning pour compenser le lag du filtre.
 *
 * Deux paramètres :
 * - minCutoff : cutoff minimum (Hz). Bas = lisse fort quand l'articulation est
 *   immobile (anti-bruit du modèle sur les coordonnées).
 * - beta : sensibilité à la vitesse. Élevé = suit les gestes rapides (jab) sans
 *   traînée, même avec un cutoff bas.
 *
 * Valeurs retenues (temps réel ~20fps, 33 landmarks, ML Kit ACCURATE) :
 * minCutoff=1.0 Hz (lag basse vitesse ~160ms, stable), beta=0.04 (réactivité
 * haute vitesse), dCutoff=20 Hz (fps réel — la dérivée suit le mouvement).
 * Le filtre est échelle-dépendant : les entrées doivent être normalisées (0-1)
 * avant filtrage (cf. LandmarkSmoother).
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

    /**
     * Extrapolation dead-reckoning : position filtrée + vitesse lissée * lead.
     * Compense le lag du filtre (et une partie du pipeline) en mouvement ;
     * à l'arrêt (vitesse ~0) la prédiction est nulle, donc aucun jitter ajouté.
     * À appeler juste après filter() (utilise xPrev/dxPrev de la frame courante).
     */
    fun predicted(leadSeconds: Double): Double = xPrev + dxPrev * leadSeconds

    fun reset() {
        tPrev = -1.0
    }

    private fun smoothingFactor(cutoff: Double, dt: Double): Double {
        val r = 2.0 * PI * cutoff * dt
        return r / (r + 1.0)
    }
}

/**
 * Lisseur des 33 landmarks ML Kit (x, y pixels + z relatif) : 99 filtres
 * One-Euro pré-alloués, un par coordonnée. Zéro allocation par frame :
 * le résultat est écrit dans le tableau out fourni par l'appelant.
 *
 * ML Kit retourne des pixels (repère image), alors que One-Euro est calibré
 * sur des valeurs normalisées : le smoother normalise par la taille de l'image
 * avant filtrage, puis re-dé-normalise pour les sorties.
 *
 * Tuning (Casiez et al., CHI 2012 + retours pose estimation) :
 * - minCutoff=1.0 Hz : lag basse vitesse ~160ms (0.5 Hz donnait ~320ms).
 * - beta=0.04 : suit les gestes rapides sans traînée.
 * - dCutoff=20 Hz (fps réel) : la dérivée suit le mouvement.
 * - leadSeconds=0.05 : prédiction x/y ~50ms (1 frame à 20fps) — compense le
 *   lag du filtre ; le z (bruité, diagnostique world3d) n'est pas prédit.
 */
class LandmarkSmoother(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.04,
    private val dCutoff: Double = 20.0,
    private val leadSeconds: Double = 0.05,
) {
    private val fx = Array(33) { OneEuroFilter(minCutoff, beta, dCutoff) }
    private val fy = Array(33) { OneEuroFilter(minCutoff, beta, dCutoff) }
    private val fz = Array(33) { OneEuroFilter(minCutoff, beta, dCutoff) }

    /**
     * Filtre les coordonnées pixels du landmark i à l'instant t (secondes) et
     * écrit [x, y, z] lissés (pixels, repère image) dans out[0..2].
     * x et y sont prédits (dead-reckoning), z non (trop bruité pour prédire).
     */
    fun smooth(i: Int, x: Float, y: Float, z: Float, imageW: Int, imageH: Int, t: Double, out: FloatArray) {
        val nx = x / imageW
        val ny = y / imageH
        val nz = z / imageH
        val fxx = fx[i]
        val fyy = fy[i]
        fxx.filter(nx.toDouble(), t)
        fyy.filter(ny.toDouble(), t)
        out[0] = (fxx.predicted(leadSeconds) * imageW).toFloat()
        out[1] = (fyy.predicted(leadSeconds) * imageH).toFloat()
        out[2] = (fz[i].filter(nz.toDouble(), t) * imageH).toFloat()
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
