package com.fightarena.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Écrit un landmark [x,y,z,lik] dans le buffer 33*4. Top-level : accessible
 *  depuis la classe de test et la data class P (le fichier GestureDetectorTest
 *  a son propre `lm` privé, d'où un nom différent). */
fun lmBuf(b: FloatArray, i: Int, x: Float, y: Float, lik: Float = 1f) {
    val o = i * 4
    b[o] = x
    b[o + 1] = y
    b[o + 2] = 0f
    b[o + 3] = lik
}

/**
 * Scénarios réalistes de combat (playtest virtuel) : rejoue des séquences de
 * landmarks SYNTHÉTIQUES "joueur réel" dans GestureDetector et vérifie les
 * TRIGGERs. Couvre ce que les tests unitaires du détecteur ne couvrent pas :
 * enchaînements rapides sans retour à la position neutre, cooldown par canal,
 * croisement hook/esquive (le bassin suit le hook chez les joueurs naturels),
 * balancement du corps, duck trop long, frame de visibilité perdue, mouvements
 * SACCADÉS (à-coups réels du joueur, timings irréguliers).
 *
 * Convention des poses : imageH = 1000, tronc = 200 px (épaules y=500,
 * hanches y=700), largeur épaules = 160 px (shL x=280, shR x=440).
 * extension = dist(poignet, épaule) / 200. Rotation de la ligne épaules en
 * degrés. Config : src/main/assets/config/detection.json (comme l'app).
 *
 * Rampe de jab correcte (loi du détecteur) : fold (ext < 0.65, recentMin
 * suivi) -> SAUT à ext >= 0.80 en une frame (START : amplitude réelle >=
 * ampRequired) -> frame suivante ext >= 0.85 avec v >= 1.8 (TRIGGER). Une
 * extension intermédiaire (ex 0.6) réécrase recentMin et tue la suite.
 */
class GestureScenarioTest {

    private data class P(
        val nose: Float = 440f,
        val noseLik: Float = 1f,
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
            lmBuf(b, 0, 360f, nose, noseLik)
            lmBuf(b, 11, shL.first, shL.second)
            lmBuf(b, 12, shR.first, shR.second)
            lmBuf(b, 13, elL.first, elL.second)
            lmBuf(b, 14, elR.first, elR.second)
            lmBuf(b, 15, wrL.first, wrL.second, wrLik)
            lmBuf(b, 16, wrR.first, wrR.second, wrLik)
            lmBuf(b, 23, hipL.first, hipL.second)
            lmBuf(b, 24, hipR.first, hipR.second)
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

    /** Variante des ho document timestamps : la caméra ML Kit échantillonne
     *  ~20 fps, mais l'action réelle du joueur arrive par à-coups (contractions
     *  musculaires + latence de détection) : les dt sont irréguliers. `times`
     *  donne l'horodatage EXPLICITE de chaque frame (réels, pas un pas fixe). */
    private fun runAt(poses: List<FloatArray>, times: List<Double>): List<GestureEvent> {
        require(poses.size == times.size)
        val cfg = File("src/main/assets/config/detection.json").readText()
        val events = mutableListOf<GestureEvent>()
        val det = GestureDetector({ events.add(it) }, cfg)
        poses.forEachIndexed { i, p -> det.process(p, times[i], 1000) }
        return events
    }

    private fun names(events: List<GestureEvent>) =
        events.map { it.name + (it.side?.let { "_$it" } ?: "") + (it.direction?.let { "_$it" } ?: "") }

    // rampe d'extension : poing à l'extension `e` (0.2=fold, 0.8=arm, 0.95=trigger)
    private fun extR(
        e: Float,
        noseLik: Float = 1f,
        hipL: Pair<Float, Float> = 320f to 700f,
        hipR: Pair<Float, Float> = 400f to 700f,
    ) = P(noseLik = noseLik, hipL = hipL, hipR = hipR, wrR = 440f to (500f - 200f * e))

    private fun extL(
        e: Float,
        hipL: Pair<Float, Float> = 320f to 700f,
        hipR: Pair<Float, Float> = 400f to 700f,
    ) = P(hipL = hipL, hipR = hipR, wrL = 280f to (500f - 200f * e))

    // ---------------------------------------------------------------------
    //  Enchaînements rapides (combo) sans revenir à la position neutre
    // ---------------------------------------------------------------------

    @Test
    fun `combo 1-2 - jab alternant gauche droite sous le cooldown d' un bras passe`() {
        // v4.2 : le cooldown est PAR CANAL (bras gauche / bras droit
        // indépendants). Un jab gauche puis un jab droit 150 ms plus tard
        // doivent passer tous les deux.
        val poses = listOf(
            neutral.buf(),                       // t=0.00
            neutral.buf(),                       // t=0.05
            extL(0.2f).buf(),                    // t=0.10 fold gauche (ext 0.2)
            extL(0.8f).buf(),                    // t=0.15 ext 0.8, amp 0.6 -> START
            extL(0.95f).buf(),                   // t=0.20 ext 0.95 (v=3.0) -> TRIGGER jab LEFT
            extR(0.2f).buf(),                    // t=0.25 fold droite (canal droit neuf)
            extR(0.8f).buf(),                    // t=0.30 -> START
            extR(0.95f).buf(),                   // t=0.35 -> TRIGGER jab RIGHT (cooldown par canal OK)
            neutral.buf(),                       // t=0.40
        )
        assertEquals(listOf("jab_left", "jab_right"), names(run(poses)))
    }

    @Test
    fun `meme bras - double jab trop rapide refuse, le troisieme passe apres cooldown`() {
        // 2e coup du même bras 150 ms après la 1er : cooldown 250 ms -> refusé
        // (les conditions trigger sont remplies mais la frappe est bloquée, la
        // machine reste HOLD puis retombe à l'idle au repli). Le 3e coup
        // 300 ms plus tard passe.
        val poses = listOf(
            neutral.buf(),                       // t=0.00
            extR(0.2f).buf(),                    // t=0.05 fold
            extR(0.8f).buf(),                    // t=0.10 -> START (amp 0.6)
            extR(0.95f).buf(),                   // t=0.15 ext 0.95 v=3.0 -> TRIGGER jab_right (cooldown 0.15)
            extR(0.2f).buf(),                    // t=0.20 repli -> idle
            extR(0.8f).buf(),                    // t=0.25 -> START
            extR(0.95f).buf(),                   // t=0.30 ext 0.95 -> cooldown 150 ms -> REFUSÉ (pas d'event)
            extR(0.2f).buf(),                    // t=0.35 repli -> idle
            extR(0.8f).buf(),                    // t=0.40 -> START
            extR(0.95f).buf(),                   // t=0.45 -> cooldown 300 ms -> TRIGGER jab_right (2)
            neutral.buf(),                       // t=0.50
        )
        assertEquals(listOf("jab_right", "jab_right"), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  Croisement hook / esquive : le bassin suit le hook chez les joueurs
    // ---------------------------------------------------------------------

    @Test
    fun `hook - le bassin suit le crochet donc pas d'esquive parasite`() {
        // Ancien bug classique : le hook fait pivoter les hanches -> la détection
        // esquive partait aussi. Exclusivité mutuelle 300 ms : seul le hook sort.
        val poses = listOf(
            neutral.buf(),
            neutral.buf(),
            // Hook frame 1 (rotation 14°) + bassin décalé de +40 px d'un coup
            P(shR = 440f to 460f, elR = 450f to 470f, wrR = 480f to 440f,
                hipL = 340f to 700f, hipR = 420f to 700f).buf(),  // t=0.10 hook START + dodge START
            // Hook frame 2 (rotation 15.7°, coude 90°, vlat) + bassin tenu
            P(shR = 440f to 450f, elR = 455f to 475f, wrR = 505f to 430f,
                hipL = 340f to 700f, hipR = 420f to 700f).buf(),  // t=0.15 -> hook TRIGGER, dodge bloqué
            neutral.buf(),
        )
        assertEquals(listOf("hook_right"), names(run(poses)))
    }

    @Test
    fun `esquive - un hook tente dans les 300ms est neutralise`() {
        // L'inverse : l'esquive déclenche, le hook du frame d'après est bloqué
        // par hookVsDodgeMs -> seul le dodge sort.
        val poses = listOf(
            neutral.buf(),                                   // t=0.00
            neutral.buf(),                                   // t=0.05
            P(hipL = 340f to 700f, hipR = 420f to 700f).buf(),   // t=0.10 disp 0.25 -> dodge START
            P(hipL = 360f to 700f, hipR = 440f to 700f).buf(),   // t=0.15 disp 0.5 -> dodge TRIGGER (dir right)
            // Hook dès le frame suivant : rotation + coude + vlat
            P(shL = 280f to 455f, elL = 270f to 475f, wrL = 215f to 430f,
                hipL = 360f to 700f, hipR = 440f to 700f).buf(), // t=0.20 -> hook bloqué par lastDodgeT
            neutral.buf(),
        )
        assertEquals(listOf("dodge_right"), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  Stabilité du corps au combat : balancement du joueur
    // ---------------------------------------------------------------------

    @Test
    fun `balancement naturel du joueur le jab passe`() {
        // Le joueur bouge le bassin ±3px en y et ±5px en x (rythmique) pendant
        // l'extension : la fenêtre de 6 frames reste < 0.2×largeur épaules en x
        // et < 0.14×tronc en y -> bodyStable = true, le coup passe.
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()                       // t=0.00
        poses += neutral.buf()                       // t=0.05
        // 6 frames de balance avant et pendant le coup (fenêtre pleine)
        repeat(3) { i ->
            val yy = if (i % 2 == 0) 704f else 696f
            poses += P(hipL = 330f to yy, hipR = 410f to yy).buf()
            poses += P(hipL = 330f to yy, hipR = 410f to yy).buf()
        }
        // jab droit pendant le balance (fold -> arm -> trigger)
        poses += extR(0.2f, hipL = 330f to 704f, hipR = 410f to 704f).buf()
        poses += extR(0.8f, hipL = 330f to 696f, hipR = 410f to 696f).buf()
        poses += extR(0.95f, hipL = 330f to 704f, hipR = 410f to 704f).buf()
        poses += neutral.buf()
        assertEquals(listOf("jab_right"), names(run(poses)))
    }

    @Test
    fun `balance exaspere (±30px) - pas de frac - le coup est sacrifie`() {
        // Le bassin oscille trop vite et trop loin : fenêtre 60px > 20% largeur
        // épaules -> bodyStable = false -> les frappes partent en IDLE.
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()
        poses += neutral.buf()
        var yy = 700f
        repeat(8) {
            poses += P(hipL = 300f to yy, hipR = 430f to yy).buf()
            poses += P(hipL = 300f to yy, hipR = 430f to yy).buf()
            yy += if (it % 2 == 0) 30f else -30f
        }
        poses += extR(0.2f, hipL = 300f to 700f, hipR = 430f to 700f).buf()
        poses += extR(0.95f, hipL = 300f to 700f, hipR = 430f to 700f).buf()
        poses += neutral.buf()
        assertEquals(emptyList<String>(), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  Chaîne serrée sans retour de garde : jab -> hook -> uppercut
    // ---------------------------------------------------------------------

    @Test
    fun `chaine rapprochee jab R - hook L - uppercut R sans retour neutre`() {
        // 3 coups en 450 ms sans repasser par la position neutre. Chaque canal
        // (bras gauche / droite) respecte son cooldown : jab droite à 0.20,
        // hook gauche à 0.30 (canal gauche neuf), uppercut droite à 0.45
        // (0.45-0.20 = 250 ms >= cooldown 250 ms).
        val poses = listOf(
            neutral.buf(),                                      // t=0.00
            neutral.buf(),                                      // t=0.05
            extR(0.2f).buf(),                                   // t=0.10 fold
            extR(0.8f).buf(),                                   // t=0.15 START
            extR(0.95f).buf(),                                  // t=0.20 -> TRIGGER jab_right
            // hook LEFT : rotation ligne épaules 14°, coude < 110°, vlat
            P(shL = 280f to 462f, shR = 440f to 500f, elL = 270f to 470f,
                wrL = 240f to 440f).buf(),                      // t=0.25 rot 13.4° -> START
            P(shL = 280f to 455f, shR = 440f to 500f, elL = 265f to 475f,
                wrL = 215f to 430f).buf(),                      // t=0.30 rot 15.7° -> TRIGGER hook_left
            // uppercut RIGHT : poing bas tenu 2 frames puis montée 100 ms
            P(elR = 450f to 600f, wrR = 440f to 650f).buf(),    // t=0.35 base (poing sous coude)
            P(elR = 450f to 600f, wrR = 440f to 650f).buf(),    // t=0.40 base tenue
            P(elR = 450f to 565f, wrR = 445f to 560f).buf(),    // t=0.45 -> TRIGGER uppercut_right (250 ms)
            neutral.buf(),
        )
        assertEquals(listOf("jab_right", "hook_left", "uppercut_right"), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  Mouvements SACCADÉS : à-coups réels du joueur, dt irréguliers
    // ---------------------------------------------------------------------

    @Test
    fun `saccade - enchainement irregulier de 3 coups reconnus quand meme`() {
        // Le joueur enchaîne jab > hook > uppercut par à-cours (bursts) avec des
        // niveaux de frames irréguliers (0.02s..0.07s) : chaque coup est reconnu
        // car les vitesses (v, vlat) utilisent le vrai dt entre frames.
        val poses = listOf(
            neutral.buf(),                            // 0.000
            neutral.buf(),                            // 0.061
            extR(0.2f).buf(),                         // 0.110 fold
            extR(0.8f).buf(),                         // 0.129 START (amp 0.6)
            extR(0.95f).buf(),                        // 0.157 v=(0.95-0.8)/0.028=5.4 -> jab_right
            P(shL = 280f to 462f, shR = 440f to 500f, elL = 270f to 470f,
                wrL = 240f to 440f).buf(),            // 0.231 rot 13.4° -> START
            P(shL = 280f to 455f, shR = 440f to 500f, elL = 265f to 475f,
                wrL = 215f to 430f).buf(),            // 0.259 rot 15.7°, vlat 5.4 -> hook_left
            P(elR = 450f to 600f, wrR = 440f to 650f).buf(),   // 0.307 base
            P(elR = 450f to 600f, wrR = 440f to 650f).buf(),   // 0.341 base tenue
            P(elR = 450f to 565f, wrR = 445f to 560f).buf(),   // 0.445 rise (base 138 ms, cooldown 288 ms) -> uppercut_right
            neutral.buf(),                            // 0.500
        )
        val times = listOf(0.000, 0.061, 0.110, 0.129, 0.157, 0.231, 0.259, 0.307, 0.341, 0.445, 0.500)
        assertEquals(listOf("jab_right", "hook_left", "uppercut_right"), names(runAt(poses, times)))
    }

    @Test
    fun `coup en une seule frame - flash d'extension sans suivi - jamais nul`() {
        // Saccade extrême : le poing va au bout en UNE seule frame puis revient
        // aussitôt (pas de frame d'appui). La rampe START/TRIGGER exige 2 frames
        // consécutives (amplitude PUIS vitesse) : rien ne doit sortir, même en
        // répétant le flash.
        val poses = listOf(
            neutral.buf(),                       // 0.00
            neutral.buf(),                       // 0.05
            extR(0.2f).buf(),                    // 0.10 fold (recentMin = 0.2)
            extR(0.95f).buf(),                   // 0.15 flash : amp -> START mais PAS de trigger dans la même frame
            extR(0.2f).buf(),                    // 0.20 repli -> idle (ext < 0.7) : coup avorté
            extR(0.2f).buf(),                    // 0.25 re-fold
            extR(0.95f).buf(),                   // 0.30 flash -> START
            extR(0.2f).buf(),                    // 0.35 repli -> idle
            neutral.buf(),                       // 0.40
        )
        assertEquals(emptyList<String>(), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  duck
    // ---------------------------------------------------------------------

    @Test
    fun `duck degrade - genoux flechis sans chute des epaules`() {
        // Le joueur a laissé tout son corps descendre de 60 px (hanches incluses) :
        // le tronc se raccourcit (200->160, drop 0.2) mais le nez reste
        // AU-DESSUS de la ligne des épaules (ne pas confondre avec un vrai
        // duck) -> duck_degraded, pas de duck.
        val halfDuck = P(
            nose = 500f,
            shL = 280f to 560f, shR = 440f to 560f,
            hipL = 320f to 720f, hipR = 400f to 720f,
        )
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()
        poses += neutral.buf()
        repeat(8) { poses += halfDuck.buf() }  // 0.4 s -> TRIGGER duck_degraded
        poses += neutral.buf()
        assertEquals(listOf("duck_degraded"), names(run(poses)))
    }

    @Test
    fun `duck trop long - un seul evenement a 250ms puis statique verrouille`() {
        // v4 : un crouch maintenu émet UN duck à 250 ms (holdMin), puis la
        // machine verrouille (phase TRIGGERED) tant que le joueur ne se relève
        // pas -> aucun re-trigger pendant les 600 ms de pression. Le verrouillage
        // anti-re-trigger est le comportement voulu (1 impact au lieu de N).
        val ducked = P(nose = 560f, shL = 280f to 540f, shR = 440f to 540f)
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()
        poses += neutral.buf()
        repeat(14) { poses += ducked.buf() }  // 0.65 s maintenu
        poses += neutral.buf()                // relevé
        assertEquals(listOf("duck"), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  Robustesse
    // ---------------------------------------------------------------------

    @Test
    fun `frame perdue en plein jab (visibility 1 frame) - reset complet`() {
        // Une frame où ML Kit perd la personne (lik nez < minLikelihood ->
        // frame invalide -> resetAll()) annule le coup EN COURS ; le joueur doit
        // re-faire un nouveau fold+extension pour re-trigger : seul le
        // deuxième coup compté.
        val poses = mutableListOf<FloatArray>()
        poses += neutral.buf()                            // t=0.00
        poses += neutral.buf()                            // t=0.05
        poses += extR(0.2f).buf()                         // t=0.10 fold
        poses += extR(0.8f).buf()                         // t=0.15 START
        poses += extR(0.95f, noseLik = 0.4f).buf()        // t=0.20 ext 0.95 mais frame INVALIDE -> reset
        poses += extR(0.2f).buf()                         // t=0.25 re-fold (canaux réarmés)
        poses += extR(0.8f).buf()                         // t=0.30 START
        poses += extR(0.95f).buf()                        // t=0.35 -> TRIGGER jab_right (unique)
        poses += neutral.buf()
        assertEquals(listOf("jab_right"), names(run(poses)))
    }

    @Test
    fun `personne trop loin (tronc hors bornes) - aucun geste`() {
        // Téléphone trop loin : tronc 136 px < 0.14*imageH=140 -> resetAll()
        // à chaque frame, la machine ne répond plus du tout (sans crasher).
        val far = P(shL = 300f to 600f, shR = 420f to 600f,  // trunk ≈ 137 px
                    elL = 290f to 640f, elR = 410f to 640f, wrL = 310f to 680f, wrR = 410f to 680f,
                    hipL = 340f to 740f, hipR = 420f to 740f)
        val poses = mutableListOf<FloatArray>()
        poses += far.buf()
        poses += far.buf()
        poses += far.copy(wrR = 420f to 520f).buf()  // gros geste quand même
        poses += neutral.buf()
        assertEquals(emptyList<String>(), names(run(poses)))
    }

    // ---------------------------------------------------------------------
    //  Garde pendant l'attaque
    // ---------------------------------------------------------------------

    @Test
    fun `garde levee pendant l'attaque - le start de garde ne bloque pas le jab`() {
        // Le joueur lève le poing gauche en garde pendant qu'il frappe du
        // poing droit : garde reste START (jamais les deux mains) -> aucun
        // événement garde parasite, le jab passe.
        val poses = listOf(
            neutral.buf(),                                   // t=0.00
            neutral.buf(),                                   // t=0.05
            P(wrL = 330f to 430f, wrR = 440f to 460f).buf(), // t=0.10 fold + garde gauche levée
            P(wrL = 330f to 430f, wrR = 440f to 340f).buf(), // t=0.15 START + garde
            P(wrL = 330f to 430f, wrR = 440f to 310f).buf(), // t=0.20 TRIGGER jab_right (garde toujours START)
            neutral.buf(),                                   // t=0.25
        )
        assertEquals(listOf("jab_right"), names(run(poses)))
    }
}