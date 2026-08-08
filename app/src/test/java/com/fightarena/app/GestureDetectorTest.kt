package com.fightarena.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harnais de test JVM : rejoue des séquences de landmarks SYNTHÉTIQUES dans
 * GestureDetector et vérifie les TRIGGERs. La config vient de detection.json
 * (même fichier que l'app) : on peut calibrer les seuils et rejouer sans
 * téléphone. imageH = 1000, tronc = 200 px, épaules y=500, hanches y=700,
 * nez (360,440), largeur épaules = 160 px.
 */
private fun lm(b: FloatArray, i: Int, x: Float, y: Float, lik: Float = 1f) {
    val o = i * 4
    b[o] = x
    b[o + 1] = y
    b[o + 2] = 0f
    b[o + 3] = lik
}

class GestureDetectorTest {

    // ------------------------------------------------------------- helpers

    private data class P(
        val noseY: Float = 440f,
        val shL: Pair<Float, Float> = 280f to 500f,
        val shR: Pair<Float, Float> = 440f to 500f,
        val elL: Pair<Float, Float> = 270f to 590f,
        val elR: Pair<Float, Float> = 430f to 590f,
        val wrL: Pair<Float, Float> = 300f to 660f,
        val wrR: Pair<Float, Float> = 420f to 660f,
        val hipL: Pair<Float, Float> = 320f to 700f,
        val hipR: Pair<Float, Float> = 400f to 700f,
        val wrLik: Float = 1f,
    ) {
        fun buf(): FloatArray {
            val b = FloatArray(33 * 4)
            lm(b, 0, 360f, noseY)
            lm(b, 11, shL.first, shL.second)
            lm(b, 12, shR.first, shR.second)
            lm(b, 13, elL.first, elL.second)
            lm(b, 14, elR.first, elR.second)
            lm(b, 15, wrL.first, wrL.second, wrLik)
            lm(b, 16, wrR.first, wrR.second, wrLik)
            lm(b, 23, hipL.first, hipL.second)
            lm(b, 24, hipR.first, hipR.second)
            return b
        }
    }

    private val neutral = P()

    private fun run(poses: List<FloatArray>, stepS: Double = 0.05): List<GestureEvent> {
        val cfg = File("src/main/assets/config/detection.json").readText()
        val events = mutableListOf<GestureEvent>()
        val det = GestureDetector({ events.add(it) }, cfg)
        poses.forEachIndexed { i, p -> det.process(p, i * stepS, 1000) }
        return events
    }

    private fun names(events: List<GestureEvent>) =
        events.map { it.name + (it.side?.let { "_$it" } ?: "") + (it.direction?.let { "_$it" } ?: "") }

    // ------------------------------------------------------ positive tests

    @Test
    fun `jab droite - bras replie puis extension rapide`() {
        // 2 frames neutres (fenêtre bassin = 3 frames), poignet lik 0.3 (visStrike)
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(elR = 455f to 570f, wrR = 440f to 540f, wrLik = 0.3f).buf(), // replié (ext 0.20)
                P(elR = 445f to 420f, wrR = 440f to 430f, wrLik = 0.3f).buf(), // mi-extension (ext 0.35)
                P(elR = 445f to 420f, wrR = 440f to 340f, wrLik = 0.3f).buf(), // ext 0.80 -> START
                P(elR = 445f to 420f, wrR = 440f to 310f, wrLik = 0.3f).buf(), // ext 0.95, v=3.0 -> TRIGGER
            )
        )
        assertEquals(listOf("jab_right"), names(ev))
    }

    @Test
    fun `hook droite - rotation epaules + coude plie`() {
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(shR = 440f to 460f, elR = 450f to 470f, wrR = 480f to 440f).buf(), // rot 14 -> START
                P(shR = 440f to 450f, elR = 455f to 475f, wrR = 505f to 430f).buf(), // rot 17, vlat 3.0 -> TRIGGER
            )
        )
        assertEquals(listOf("hook_right"), names(ev))
    }

    @Test
    fun `hook droite - compression de l'axe des epaules seul (rotation de face)`() {
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(shL = 300f to 500f, shR = 420f to 500f, elR = 400f to 520f, wrR = 390f to 500f).buf(),
                P(shL = 315f to 500f, shR = 405f to 500f, elR = 400f to 520f, wrR = 380f to 500f).buf(),
            )
        )
        assertEquals(listOf("hook_right"), names(ev))
    }

    @Test
    fun `uppercut droite - poing bas puis montee coude plie`() {
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // poing sous le coude
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // base tenue 100ms
                P(elR = 450f to 565f, wrR = 445f to 560f).buf(), // montée coude+poignet (rise=0.45, eRise=0.175)
            )
        )
        assertEquals(listOf("uppercut_right"), names(ev))
    }

    @Test
    fun `uppercut - pas de re-declenchement sans retour en garde (anti double-trigger)`() {
        // TRIGGER, poignet remonte au-dessus du coude, puis re-tombe et remonte
        // immédiatement (rebond du même coup) : le ré-armement exige resetHoldMs
        // de retour en garde -> le 2e événement ne sort pas.
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // poing bas
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // base tenue 100ms
                P(elR = 450f to 565f, wrR = 445f to 560f).buf(), // montée -> TRIGGER
                P(elR = 430f to 590f, wrR = 420f to 550f).buf(), // main au-dessus du coude (retour)
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // re-tombe (rebond)
                P(elR = 450f to 565f, wrR = 445f to 560f).buf(), // remonte trop vite
            )
        )
        assertEquals(listOf("uppercut_right"), names(ev))
    }

    @Test
    fun `uppercut - re-declenchement apres retour en garde complet`() {
        // TRIGGER, retour en garde maintenu (resetHoldMs), base re-tenue (baseHoldMs),
        // nouvelle montée -> 2e événement légitime.
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // poing bas
                P(elR = 450f to 600f, wrR = 440f to 650f).buf(), // base tenue 100ms
                P(elR = 450f to 565f, wrR = 445f to 560f).buf(), // montée -> TRIGGER 1
                P(elR = 430f to 590f, wrR = 420f to 550f).buf(), // retour en garde
                neutral.buf(),                                   // 150ms de retour maintenu
                neutral.buf(),                                   // re-arm
                neutral.buf(),                                   // base tenue
                neutral.buf(),                                   // base tenue (100ms)
                P(elR = 450f to 565f, wrR = 445f to 560f).buf(), // montée -> TRIGGER 2
            )
        )
        assertEquals(listOf("uppercut_right", "uppercut_right"), names(ev))
    }

    @Test
    fun `duck et jab simultanes - les deux evenements sortent`() {
        // Inquiétude validée : le squat+uppercut du vrai combat. Les machines duck
        // et jab sont indépendantes : le duck (nez sous les épaules + tronc plié)
        // pendant l'extension du bras ne doit bloquer ni l'un ni l'autre.
        val ducked = P(noseY = 560f, shL = 280f to 540f, shR = 440f to 540f)
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                ducked.copy(elR = 455f to 570f, wrR = 440f to 540f).buf(), // accroupi + bras replié
                ducked.copy(elR = 445f to 420f, wrR = 440f to 430f).buf(), // accroupi + mi-extension
                ducked.copy(elR = 445f to 420f, wrR = 440f to 340f).buf(), // accroupi + extension -> jab
                ducked.buf(),   // maintien duck
                ducked.buf(),
                ducked.buf(),
                ducked.buf(),
                ducked.buf(),   // 250ms de maintien -> duck
                neutral.buf(),
            )
        )
        assertEquals(listOf("jab_right", "duck"), names(ev))
    }

    @Test
    fun `duck - nez sous les epaules + tronc raccourci maintenu`() {
        val ducked = P(noseY = 560f, shL = 280f to 540f, shR = 440f to 540f) // tronc 200 -> 160
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()
        poses += neutral.buf()
        poses += ducked.buf() // START
        poses += ducked.buf() // HOLD
        repeat(6) { poses += ducked.buf() } // 250 ms de maintien
        poses += neutral.buf() // relevé
        val ev = run(poses)
        assertEquals(listOf("duck"), names(ev))
    }

    @Test
    fun `dodge droite - bassin qui glisse vite sur le cote`() {
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(hipL = 340f to 700f, hipR = 420f to 700f).buf(), // disp 0.125
                P(hipL = 360f to 700f, hipR = 440f to 700f).buf(), // disp 0.25
            )
        )
        assertEquals(listOf("dodge_right"), names(ev))
    }

    @Test
    fun `garde - deux poignets au-dessus des epaules maintenus 500ms`() {
        val guard = P(wrL = 330f to 430f, wrR = 390f to 430f)
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()
        poses += neutral.buf()
        poses += guard.buf()
        repeat(14) { poses += guard.buf() } // 0.7 s
        poses += neutral.buf()
        val ev = run(poses)
        assertEquals(listOf("guard"), names(ev))
    }

    @Test
    fun `tous les gestes en sequence sur un seul detecteur`() {
        val ducked = P(noseY = 560f, shL = 280f to 540f, shR = 440f to 540f)
        val guard = P(wrL = 330f to 430f, wrR = 390f to 430f)
        val poses = mutableListOf<FloatArray>()
        // jab (fold poignet au-dessus du coude : n'arme pas l'uppercut)
        poses += neutral.buf(); poses += neutral.buf()
        poses += P(elR = 455f to 570f, wrR = 440f to 540f).buf()
        poses += P(elR = 445f to 420f, wrR = 440f to 430f).buf()
        poses += P(elR = 445f to 420f, wrR = 440f to 340f).buf()
        poses += P(elR = 445f to 420f, wrR = 440f to 310f).buf() // TRIGGER jab
        repeat(8) { poses += neutral.buf() }
        // hook (2 frames de rotation)
        poses += P(shR = 440f to 460f, elR = 450f to 470f, wrR = 480f to 440f).buf()
        poses += P(shR = 440f to 450f, elR = 455f to 475f, wrR = 505f to 430f).buf() // TRIGGER hook
        repeat(8) { poses += neutral.buf() }
        // uppercut (après, remonter la main pour reset TRIGGERED)
        poses += P(elR = 450f to 600f, wrR = 440f to 650f).buf()
        poses += P(elR = 450f to 600f, wrR = 440f to 650f).buf() // base tenue 100ms
        poses += P(elR = 450f to 565f, wrR = 445f to 560f).buf()
        poses += P(elR = 430f to 590f, wrR = 420f to 550f).buf() // main haute
        repeat(7) { poses += neutral.buf() }
        // duck
        poses += ducked.buf(); repeat(6) { poses += ducked.buf() }
        repeat(8) { poses += neutral.buf() }
        // dodge
        poses += P(hipL = 340f to 700f, hipR = 420f to 700f).buf()
        poses += P(hipL = 360f to 700f, hipR = 440f to 700f).buf()
        repeat(8) { poses += neutral.buf() }
        // guard
        poses += guard.buf()
        repeat(14) { poses += guard.buf() }
        val ev = run(poses)
        assertEquals(
            listOf("jab_right", "hook_right", "uppercut_right", "duck", "dodge_right", "guard"),
            names(ev)
        )
    }

    // ------------------------------------------------------ negative tests

    @Test
    fun `pas de jab si extension lente (vitesse insuffisante)`() {
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        poses += P(elR = 435f to 560f, wrR = 430f to 580f).buf() // replié
        // extension horizontale lente sur 0.8 s (v ~0.6 < 1.8), montée verticale < 0.3
        for (i in 1..16) {
            val x = 430f + i * 12f
            poses += P(elR = 435f to 560f, wrR = x to 580f).buf()
        }
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de jab si le bassin bouge (corps instable)`() {
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        poses += P(elR = 435f to 560f, wrR = 430f to 580f, hipL = 340f to 700f, hipR = 420f to 700f).buf()
        poses += P(elR = 445f to 420f, wrR = 440f to 320f, hipL = 320f to 700f, hipR = 400f to 700f).buf()
        poses += P(elR = 445f to 420f, wrR = 440f to 320f, hipL = 340f to 700f, hipR = 420f to 700f).buf()
        poses += P(elR = 445f to 420f, wrR = 440f to 320f, hipL = 320f to 700f, hipR = 400f to 700f).buf()
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de duck si on s'assoit sans plier le tronc`() {
        // Tout descend de 30 px : tronc inchangé -> trunkDrop = 0
        val sitting = P(noseY = 470f, shL = 280f to 530f, shR = 440f to 530f, hipL = 320f to 730f, hipR = 400f to 730f)
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        repeat(10) { poses += sitting.buf() }
        poses += neutral.buf()
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de duck si maintien trop court`() {
        val ducked = P(noseY = 560f, shL = 280f to 540f, shR = 440f to 540f)
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        poses += ducked.buf() // START
        poses += ducked.buf() // HOLD
        poses += ducked.buf() // 100 ms seulement
        poses += neutral.buf() // relevé avant 250 ms
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de hook si bras tendu`() {
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(shR = 440f to 460f, elR = 490f to 455f, wrR = 540f to 450f).buf(), // bras tendu ~180
            )
        )
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de hook si rotation trop lente`() {
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        // Rotation progressive 0 -> 14 degrés sur 20 frames : la ref EMA suit
        for (i in 1..20) {
            val dy = i * 2f // 2 px/frame -> 40 px au total, ref qui poursuit
            poses += P(shR = 440f to 500f - dy, elR = 450f to 470f, wrR = 480f to 440f).buf()
        }
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas d'uppercut si bras tendu pendant la montee`() {
        val ev = run(
            listOf(
                neutral.buf(),
                neutral.buf(),
                P(elR = 440f to 590f, wrR = 440f to 640f).buf(), // poing bas, bras tendu
                P(elR = 440f to 540f, wrR = 440f to 580f).buf(), // montée, coude tendu
            )
        )
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de dodge si deplacement lent (la reference EMA suit)`() {
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        // 4 px/frame -> disp 0.025/frame < startDisp 0.05, la ref poursuit
        for (i in 1..20) {
            val dx = i * 4f
            poses += P(hipL = 320f + dx to 700f, hipR = 400f + dx to 700f).buf()
        }
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }

    @Test
    fun `pas de garde avec une seule main`() {
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf(); poses += neutral.buf()
        repeat(14) { poses += P(wrL = 330f to 430f).buf() } // main gauche seulement
        val ev = run(poses)
        assertEquals(emptyList<String>(), names(ev))
    }
}
