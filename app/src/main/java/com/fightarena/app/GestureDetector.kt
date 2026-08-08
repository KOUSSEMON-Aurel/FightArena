package com.fightarena.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

/** Un geste détecté au passage en TRIGGER (jamais deux fois sans RESET). */
data class GestureEvent(
    val name: String,          // jab, hook, uppercut, duck, duck_degraded, dodge, guard
    val side: String?,         // "left"/"right" pour les bras, null sinon
    val direction: String?,    // "left"/"right" pour l'esquive
    val signals: String,       // valeurs réelles pour calibration (ex: "ext=0.98 v=3.2")
)

/**
 * Détecteur des 6 gestes de combat (spec docs/gesture-spec.md v1.0).
 *
 * Consomme le buffer de landmarks FILTRÉS (One-Euro + prédiction, pixels) à
 * chaque frame et émet un GestureEvent au TRIGGER. Tous les seuils viennent de
 * assets/config/detection.json (jamais de valeur en dur dans le code).
 *
 * v4 (playtest) : les références EMA au repos se figeaient quand le joueur
 * s'accroupissait/se déplaçait puis bloquaient la stabilité du corps pour le
 * reste de la session. Tout est maintenant LOCAL :
 *  - stabilité du corps = amplitude du bassin sur une fenêtre glissante de
 *    6 frames (~300 ms), aucune référence lointaine ;
 *  - duck = raccourcissement du TRONC (s'accroupir plie le buste ; s'asseoir
 *    descend tout ensemble sans raccourcir le tronc) ;
 *  - hook = inclinaison de la ligne des épaules OU compression de l'axe des
 *    épaules (rotation de face : la ligne reste horizontale mais l'axe
 *    rétrécit en projection) ;
 *  - jab = extension + vitesse + amplitude réelle, sans z ML Kit (bruité,
 *    voir doc Google "Z-values are less accurate than x and y-values") ;
 *  - uppercut = poing bas + montée rapide coude plié, sans référence hanche.
 *
 * Machine à états : IDLE -> START -> HOLD -> TRIGGER -> RESET. Un geste ne
 * compte qu'au passage en TRIGGER et doit repasser par RESET pour se
 * redéclencher. Cooldown entre coups + anti-triche (amplitude ET vitesse).
 *
 * Testable en JVM : le constructeur prend la config en texte brut
 * (GestureDetectorTest rejoue des séquences synthétiques sans téléphone).
 */
class GestureDetector(
    private val listener: (GestureEvent) -> Unit,
    jsonText: String,
) {

    companion object {
        /** Construction Android : lit la config depuis les assets. */
        fun fromAssets(context: Context, listener: (GestureEvent) -> Unit): GestureDetector {
            val text = context.assets.open("config/detection.json")
                .bufferedReader().use { it.readText() }
            return GestureDetector(listener, text)
        }
    }

    private data class Cfg(
        val cooldownMs: Double,
        val minLikelihood: Float,
        val strikeMinLikelihood: Float,
        val trunkMinFrac: Float, val trunkMaxFrac: Float,
        val maxBodyShift: Float, val maxLateralShift: Float,
        val jabStartExt: Float, val jabTriggerExt: Float, val jabTriggerSpeed: Float, val jabAmpRequired: Float,
        val jabResetExt: Float,
        val hookStartRotDeg: Float, val hookTriggerRotDeg: Float, val hookMaxElbowDeg: Float,
        val hookTriggerSpeed: Float, val hookAmpRequiredDeg: Float, val hookResetRotDeg: Float,
        val upRiseDistance: Float, val upRiseMaxSeconds: Float, val upMaxElbowDeg: Float,
        val upElbowRise: Float, val upMaxWristLateral: Float, val upBaseHoldMs: Double, val upResetHoldMs: Double,
        val duckTriggerDepth: Float, val duckHoldMinMs: Double, val duckHoldMaxMs: Double,
        val duckTrunkDrop: Float, val duckDegradedValue: Float,
        val dodgeStartDisp: Float, val dodgeTriggerDisp: Float, val dodgeTriggerMaxSeconds: Float, val dodgeResetDisp: Float,
        val dodgeMaxVertShift: Float,
        val guardHoldMs: Double, val guardMaxWristWidth: Float, val guardMinLikelihood: Float,
        val hookVsDodgeMs: Double,
    )

    private val cfg: Cfg = run {
        val j = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw IllegalStateException("config/detection.json illisible ou absent", e)
        }
        val common = j.getJSONObject("common")
        val jab = j.getJSONObject("jab")
        val hook = j.getJSONObject("hook")
        val up = j.getJSONObject("uppercut")
        val duck = j.getJSONObject("duck")
        val dodge = j.getJSONObject("dodge")
        val guard = j.getJSONObject("guard")
        val conflicts = j.getJSONObject("conflicts")
        Cfg(
            cooldownMs = common.getDouble("cooldownMs"),
            minLikelihood = common.getDouble("minLikelihood").toFloat(),
            strikeMinLikelihood = common.getDouble("strikeMinLikelihood").toFloat(),
            trunkMinFrac = common.getDouble("trunkMinFrac").toFloat(),
            trunkMaxFrac = common.getDouble("trunkMaxFrac").toFloat(),
            maxBodyShift = common.getDouble("maxBodyShift").toFloat(),
            maxLateralShift = common.getDouble("maxLateralShift").toFloat(),
            jabStartExt = jab.getDouble("startExt").toFloat(),
            jabTriggerExt = jab.getDouble("triggerExt").toFloat(),
            jabTriggerSpeed = jab.getDouble("triggerSpeedUnitsPerSec").toFloat(),
            jabAmpRequired = jab.getDouble("ampRequired").toFloat(),
            jabResetExt = jab.getDouble("resetExt").toFloat(),
            hookStartRotDeg = hook.getDouble("startRotDeg").toFloat(),
            hookTriggerRotDeg = hook.getDouble("triggerRotDeg").toFloat(),
            hookMaxElbowDeg = hook.getDouble("maxElbowDeg").toFloat(),
            hookTriggerSpeed = hook.getDouble("triggerSpeedUnitsPerSec").toFloat(),
            hookAmpRequiredDeg = hook.getDouble("ampRequiredDeg").toFloat(),
            hookResetRotDeg = hook.getDouble("resetRotDeg").toFloat(),
            upRiseDistance = up.getDouble("riseDistance").toFloat(),
            upRiseMaxSeconds = up.getDouble("riseMaxSeconds").toFloat(),
            upMaxElbowDeg = up.getDouble("maxElbowDeg").toFloat(),
            upElbowRise = up.getDouble("elbowRise").toFloat(),
            upMaxWristLateral = up.getDouble("maxWristLateral").toFloat(),
            upBaseHoldMs = up.getDouble("baseHoldMs"),
            upResetHoldMs = up.getDouble("resetHoldMs"),
            duckTriggerDepth = duck.getDouble("triggerDepth").toFloat(),
            duckHoldMinMs = duck.getDouble("holdMinMs"),
            duckHoldMaxMs = duck.getDouble("holdMaxMs"),
            duckTrunkDrop = duck.getDouble("trunkDrop").toFloat(),
            duckDegradedValue = duck.getDouble("degradedValue").toFloat(),
            dodgeStartDisp = dodge.getDouble("startDisp").toFloat(),
            dodgeTriggerDisp = dodge.getDouble("triggerDisp").toFloat(),
            dodgeTriggerMaxSeconds = dodge.getDouble("triggerMaxSeconds").toFloat(),
            dodgeResetDisp = dodge.getDouble("resetDisp").toFloat(),
            dodgeMaxVertShift = dodge.getDouble("maxVertShift").toFloat(),
            guardHoldMs = guard.getDouble("holdMs"),
            guardMaxWristWidth = guard.getDouble("maxWristWidth").toFloat(),
            guardMinLikelihood = guard.getDouble("minLikelihood").toFloat(),
            hookVsDodgeMs = conflicts.getDouble("hookVsDodgeMs"),
        )
    }

    private enum class Phase { IDLE, START, HOLD, TRIGGERED }

    private class State {
        var phase = Phase.IDLE
        var enterT = 0.0
        var triggerAt = 0.0
        var prev = 0f       // valeur précédente (extension / x poignet / y poignet)
        var recentMin = Float.MAX_VALUE // point bas récent (anti-coup sans amplitude)
        var wristStartY = 0f // uppercut : point bas récent du poignet (base de la montée)
        var wristStartX = 0f // uppercut : abscisse du point bas (trajectoire verticale)
        var elbowStartY = 0f // uppercut : point bas récent du coude
        var wristBaseT = -1.0 // uppercut : instant du dernier point bas (fenêtre 0.6 s)
        var aboveElbowSinceT = -1.0 // uppercut : depuis quand le poignet est au-dessus du coude
        fun start(t: Double) { phase = Phase.START; enterT = t }
        fun hold(t: Double) { phase = Phase.HOLD; enterT = t }
        fun idle() { phase = Phase.IDLE; enterT = 0.0 }
        val inFlight: Boolean get() = phase != Phase.IDLE
    }

    /** Fenêtre glissante du bassin : la stabilité du corps est mesurée sur les
     *  ~300 dernières ms (6 frames), pas contre une référence EMA lointaine
     *  qui se figeait et bloquait toute détection après un accroupissement. */
    private class HipWindow {
        private val xs = FloatArray(6)
        private val ys = FloatArray(6)
        private var head = 0
        private var n = 0
        fun push(x: Float, y: Float) {
            xs[head] = x; ys[head] = y
            head = (head + 1) % xs.size
            if (n < xs.size) n++
        }
        fun ready() = n >= 3
        fun rangeX(): Float {
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (i in 0 until n) { mn = min(mn, xs[i]); mx = max(mx, xs[i]) }
            return mx - mn
        }
        fun rangeY(): Float {
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (i in 0 until n) { mn = min(mn, ys[i]); mx = max(mx, ys[i]) }
            return mx - mn
        }
        fun reset() { head = 0; n = 0 }
    }

    /** Données pré-calculées d'une frame (objet réutilisé, zéro alloc). */
    private class Frame {
        var valid = false
        var t = 0.0
        var dt = 0.0
        var trunk = 0f           // dist(milieu épaules, milieu hanches)
        var shoulderW = 0f       // dist(épaule L, épaule D)
        var midSx = 0f; var midSy = 0f
        var midHx = 0f; var midHy = 0f
        var noseX = 0f; var noseY = 0f
        val x = FloatArray(33)
        val y = FloatArray(33)
        val z = FloatArray(33)
        val lik = FloatArray(33)
        var shoulderRotDeg = 0f  // inclinaison ligne épaules vs horizontale
        /** true si le bassin est stable sur la fenêtre glissante (coups autorisés). */
        var bodyStable = false
    }

    private val frame = Frame()
    private val hipWindow = HipWindow()
    private var lastT = -1.0
    // Cooldown PAR CANAL : un bras ne bloque plus l'autre. v4.2.
    private var lastStrikeArmLT = -1.0
    private var lastStrikeArmRT = -1.0
    private var lastHookT = -1.0
    private var lastDodgeT = -1.0
    // Références "au repos" (EMA lent, mis à jour seulement au repos)
    private var hipRefX = Float.NaN
    private var hipRefY = Float.NaN
    private var shoulderRotRef = Float.NaN
    private var shoulderWRef = Float.NaN
    private var trunkRef = Float.NaN
    private var lastGuardLogT = -1.0

    private val jabL = State(); private val jabR = State()
    private val hookL = State(); private val hookR = State()
    private val upL = State(); private val upR = State()
    private val duck = State()
    private val dodge = State()
    private val guard = State()
    private val duckDegraded = State()
    private var lastDiagT = -1.0
    private var diagPrevExt = 0f
    private var diagPrevT = 0.0

    /** Analyse une frame de landmarks filtrés [x,y,z,lik]*33 (pixels).
     *  imageH = hauteur d'image : sert à valider la taille du tronc (garde-fou
     *  anti "personne assise / trop loin / trop proche"). */
    fun process(landmarkBuf: FloatArray, t: Double, imageH: Int) {
        fillFrame(landmarkBuf, t)
        if (!frame.valid) { resetAll(); lastT = -1.0; return }
        // Garde-fou : tronc hors bornes (assis, trop loin/proche) = pas de détection
        if (frame.trunk < cfg.trunkMinFrac * imageH || frame.trunk > cfg.trunkMaxFrac * imageH) {
            resetAll(); lastT = -1.0; return
        }
        if (lastT > 0.0) frame.dt = (t - lastT).coerceIn(1e-3, 0.5)
        lastT = t

        // Stabilité du corps : amplitude du bassin sur la fenêtre glissante
        hipWindow.push(frame.midHx, frame.midHy)
        frame.bodyStable = hipWindow.ready() &&
            hipWindow.rangeX() < cfg.maxLateralShift * frame.shoulderW &&
            hipWindow.rangeY() < cfg.maxBodyShift * frame.trunk

        // Référence de repos des hanches (dodge). EMA lent, figée hors fenêtre.
        val hipDispX = abs(frame.midHx - hipRefX)
        val hipDispY = abs(frame.midHy - hipRefY)
        if (hipRefX.isNaN()) { hipRefX = frame.midHx; hipRefY = frame.midHy }
        else if (hipDispX < cfg.dodgeStartDisp * frame.shoulderW && hipDispY < 0.1f * frame.trunk) {
            hipRefX += 0.02f * (frame.midHx - hipRefX)
            hipRefY += 0.02f * (frame.midHy - hipRefY)
        }

        // Référence de rotation des épaules (EMA lent, figée quand le buste tourne)
        if (shoulderRotRef.isNaN()) shoulderRotRef = frame.shoulderRotDeg
        else if (abs(frame.shoulderRotDeg - shoulderRotRef) < cfg.hookStartRotDeg * 0.75f) {
            shoulderRotRef += 0.02f * (frame.shoulderRotDeg - shoulderRotRef)
        }

        // Largeur d'épaules au repos : la compression = rotation du buste de face
        if (shoulderWRef.isNaN()) shoulderWRef = frame.shoulderW
        else if (abs(frame.shoulderW - shoulderWRef) < 0.08f * shoulderWRef) {
            shoulderWRef += 0.02f * (frame.shoulderW - shoulderWRef)
        }

        // Tronc au repos : le duck = tronc qui RACCOURCIT (s'accroupir plie le buste)
        if (trunkRef.isNaN()) trunkRef = frame.trunk
        else if (abs(frame.trunk - trunkRef) < 0.03f * trunkRef) {
            trunkRef += 0.02f * (frame.trunk - trunkRef)
        }

        updateJab(jabL, 11, 13, 15, "left", t)
        updateJab(jabR, 12, 14, 16, "right", t)
        updateHook(hookL, 11, 13, 15, "left", t)
        updateHook(hookR, 12, 14, 16, "right", t)
        updateUppercut(upL, 13, 15, "left", t)
        updateUppercut(upR, 14, 16, "right", t)
        updateDuck(t)
        updateDodge(t)
        updateGuard(t)
        diagLog(t)
    }

    /** Reset complet (personne hors cadre) : toutes les machines repartent à zéro. */
    fun resetAll() {
        for (s in listOf(jabL, jabR, hookL, hookR, upL, upR, duck, dodge, guard, duckDegraded)) {
            s.idle(); s.recentMin = Float.MAX_VALUE
            s.triggerAt = 0.0
            s.aboveElbowSinceT = -1.0
        }
        lastStrikeArmLT = -1.0
        lastStrikeArmRT = -1.0
        lastHookT = -1.0
        lastDodgeT = -1.0
        hipWindow.reset()
        hipRefX = Float.NaN
        hipRefY = Float.NaN
        shoulderRotRef = Float.NaN
        shoulderWRef = Float.NaN
        trunkRef = Float.NaN
    }

    // ---------------------------------------------------------------- frames

    private fun fillFrame(buf: FloatArray, t: Double) {
        val f = frame
        f.t = t
        f.valid = false
        val sL = buf[11 * 4]; val sLy = buf[11 * 4 + 1]; val sLlik = buf[11 * 4 + 3]
        val sR = buf[12 * 4]; val sRy = buf[12 * 4 + 1]; val sRlik = buf[12 * 4 + 3]
        val hL = buf[23 * 4]; val hLy = buf[23 * 4 + 1]; val hLlik = buf[23 * 4 + 3]
        val hR = buf[24 * 4]; val hRy = buf[24 * 4 + 1]; val hRlik = buf[24 * 4 + 3]
        val noseX = buf[0]; val noseY = buf[0 * 4 + 1]; val noseLik = buf[3]
        if (sLlik < cfg.minLikelihood || sRlik < cfg.minLikelihood ||
            hLlik < cfg.minLikelihood || hRlik < cfg.minLikelihood ||
            noseLik < cfg.minLikelihood
        ) return

        for (i in 0 until 33) {
            val idx = i * 4
            f.x[i] = buf[idx]
            f.y[i] = buf[idx + 1]
            f.z[i] = buf[idx + 2]
            f.lik[i] = buf[idx + 3]
        }
        f.midSx = (sL + sR) / 2f; f.midSy = (sLy + sRy) / 2f
        f.midHx = (hL + hR) / 2f; f.midHy = (hLy + hRy) / 2f
        f.noseX = noseX; f.noseY = noseY
        f.trunk = dist(f.midSx, f.midSy, f.midHx, f.midHy)
        f.shoulderW = dist(sL, sLy, sR, sRy)
        if (f.trunk < 1f) return
        // Inclinaison de la ligne épaules vs horizontale caméra (hook)
        f.shoulderRotDeg = (abs(atan2((sRy - sLy).toDouble(), (sR - sL).toDouble())) * 180.0 / PI).toFloat()
        f.valid = true
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    private fun visible(i: Int) = frame.lik[i] >= cfg.minLikelihood

    /** Visibilité assouplie pour les frappes : bras tendus = poignets/coudes
     *  souvent en limite de cadre, ML Kit continue de les rapporter (lik 0.3-0.5). */
    private fun visStrike(i: Int) = frame.lik[i] >= cfg.strikeMinLikelihood

    /** Angle au coude (épaule-coude-poignet) en degrés. Bras tendu ~180. */
    private fun elbowDeg(s: Int, e: Int, w: Int): Float {
        val f = frame
        if (!visStrike(s) || !visStrike(e) || !visStrike(w)) return 180f
        val v1x = f.x[s] - f.x[e]; val v1y = f.y[s] - f.y[e]
        val v2x = f.x[w] - f.x[e]; val v2y = f.y[w] - f.y[e]
        val m1 = sqrt(v1x * v1x + v1y * v1y); val m2 = sqrt(v2x * v2x + v2y * v2y)
        if (m1 < 1e-3f || m2 < 1e-3f) return 180f
        val cosA = ((v1x * v2x + v1y * v2y) / (m1 * m2)).coerceIn(-1f, 1f)
        return (acos(cosA.toDouble()) * 180.0 / PI).toFloat()
    }

    /** Un coup n'est émis qu'après le cooldown de SON CANAL (bras gauche ou
     *  bras droit, v4.2) : deux frappes du même bras s'espacent de cooldownMs,
     *  un jab droit puis un jab gauche rapides passent. */
    private fun strikeAllowed(t: Double, arm: String): Boolean {
        val last = if (arm == "left") lastStrikeArmLT else lastStrikeArmRT
        val allow = last <= 0.0 || (t - last) * 1000.0 >= cfg.cooldownMs - 1.0
        if (!allow) return false
        if (arm == "left") lastStrikeArmLT = t else lastStrikeArmRT = t
        return true
    }

    /** Anti-conflit hook vs esquive : exclusivité mutuelle 300 ms (spec §5). */
    private fun hookAllowed(t: Double): Boolean {
        if (lastDodgeT > 0.0 && (t - lastDodgeT) * 1000.0 < cfg.hookVsDodgeMs + 1.0) return false
        lastHookT = t
        return true
    }

    private fun dodgeAllowed(t: Double): Boolean {
        if (lastHookT > 0.0 && (t - lastHookT) * 1000.0 < cfg.hookVsDodgeMs) return false
        lastDodgeT = t
        return true
    }

    // ------------------------------------------------------------------ jab

    /** Jab : extension = dist(poignet, épaule)/tronc ; vitesse = d(ext)/dt.
     *  Anti-faux-positifs : amplitude réelle (point bas suivi), corps stable.
     *  Le z ML Kit est retiré : trop bruité pour une confirmation (doc Google). */
    private fun updateJab(s: State, sh: Int, el: Int, wr: Int, side: String, t: Double) {
        val f = frame
        if (!visStrike(sh) || !visStrike(wr) || !visStrike(el)) { s.idle(); return }
        val hipIdx = if (side == "left") 23 else 24
        if (!visible(hipIdx)) { s.idle(); return }
        val ext = dist(f.x[wr], f.y[wr], f.x[sh], f.y[sh]) / f.trunk
        val v = if (f.dt > 0.0) (ext - s.prev) / f.dt.toFloat() else 0f
        s.prev = ext
        when (s.phase) {
            Phase.IDLE -> {
                // Suit le point bas réel : un coup = bras d'abord replié puis étendu d'une vraie amplitude
                if (ext < cfg.jabStartExt) s.recentMin = ext
                else if (ext - s.recentMin >= cfg.jabAmpRequired) s.start(t)
            }
            Phase.START, Phase.HOLD -> {
                if (!f.bodyStable) { s.idle(); return }  // bassin qui bouge = pas un coup
                if (ext >= cfg.jabTriggerExt && v >= cfg.jabTriggerSpeed) {
                    if (strikeAllowed(t, side)) {
                        s.phase = Phase.TRIGGERED; s.triggerAt = t
                        emit("jab", side, null, "ext=%.2f v=%.1f".format(ext, v))
                    }
                } else if (ext < cfg.jabResetExt) s.idle()
            }
            Phase.TRIGGERED -> if (ext < cfg.jabResetExt) s.idle()
        }
    }

    // ----------------------------------------------------------------- hook

    /** Hook : rotation du buste = inclinaison de la ligne épaules OU compression
     *  de l'axe (rotation de face) + coude plié + vitesse latérale du poignet.
     *  Anti-faux-positifs : amplitude réelle de rotation, corps stable. */
    private fun rotSignal(): Float {
        val f = frame
        val rot = abs(f.shoulderRotDeg - shoulderRotRef)
        val compression = if (shoulderWRef.isNaN() || shoulderWRef <= 0f) 0f
        else ((shoulderWRef - f.shoulderW) / shoulderWRef * 90f).coerceIn(0f, 90f)
        return max(rot, compression)
    }

    private fun updateHook(s: State, sh: Int, el: Int, wr: Int, side: String, t: Double) {
        val f = frame
        if (!visStrike(sh) || !visStrike(el) || !visStrike(wr)) { s.idle(); return }
        val rot = rotSignal()
        val elb = elbowDeg(sh, el, wr)
        val vlat = if (f.dt > 0.0) abs(f.x[wr] - s.prev) / f.dt.toFloat() / f.shoulderW else 0f
        s.prev = f.x[wr]
        when (s.phase) {
            Phase.IDLE -> {
                // Amplitude réelle : la rotation doit partir d'un buste droit
                if (rot < cfg.hookStartRotDeg) s.recentMin = rot
                else if (rot - s.recentMin >= cfg.hookAmpRequiredDeg) s.start(t)
            }
            Phase.START, Phase.HOLD -> {
                if (!f.bodyStable) { s.idle(); return }  // déplacement latéral du bassin = dodge, pas hook
                if (rot >= cfg.hookTriggerRotDeg && elb < cfg.hookMaxElbowDeg && vlat >= cfg.hookTriggerSpeed) {
                    if (strikeAllowed(t, side) && hookAllowed(t)) {
                        s.phase = Phase.TRIGGERED; s.triggerAt = t
                        emit("hook", side, null, "rot=%.0f elb=%.0f vlat=%.1f".format(rot, elb, vlat))
                    }
                } else if (rot < cfg.hookResetRotDeg) s.idle()
            }
            Phase.TRIGGERED -> if (rot < cfg.hookResetRotDeg) s.idle()
        }
    }

    // -------------------------------------------------------------- uppercut

    /** Uppercut : départ bas (poignet sous le coude), montée rapide, coude plié.
     *  Le coude est vérifié À CHAQUE frame (et au TRIGGER) : lever un bras
     *  tendu est rejeté. Pas de verrouillage mémoire : un coude qui s'est
     *  tendu à un moment ne bloque pas les essais suivants.
     *  v4.1 (discrimination par trajectoire) : en plus de la montée du poignet,
     *  le COUDE doit monter avec lui (lever de garde = coude statique -> rejeté)
     *  et le poignet doit monter droit (départ de hook = trajectoire latérale
     *  -> rejeté). La base est glissante : tant que le bras redescend, elle
     *  suit le point bas (fenêtre riseMaxSeconds).
     *  v4.2 (anti double-trigger) : après un TRIGGER, le poignet doit être
     *  redescendu sous le coude et maintenu bas pendant resetHoldMs avant qu'un
     *  nouveau départ soit armé (un même coup qui rebondit ne se compte pas
     *  deux fois), et la base doit être tenue baseHoldMs avant que la montée
     *  compte (pas de montée depuis une base traversée en passant). */
    private fun updateUppercut(s: State, el: Int, wr: Int, side: String, t: Double) {
        val f = frame
        val hipIdx = if (side == "left") 23 else 24
        if (!visStrike(wr) || !visStrike(el) || !visible(hipIdx)) { s.idle(); return }
        val wristBelowElbow = f.y[wr] > f.y[el]
        when (s.phase) {
            Phase.IDLE -> {
                if (wristBelowElbow) {
                    // Premier coup de la session : armement immédiat.
                    // Après un TRIGGER : ré-armement seulement si le poignet est
                    // redescendu sous le coude depuis resetHoldMs (base re-stable).
                    val neverFired = s.triggerAt <= 0.0
                    val resetDone = s.aboveElbowSinceT >= 0.0 &&
                        (t - s.aboveElbowSinceT) * 1000.0 >= cfg.upResetHoldMs - 1.0
                    if (neverFired || resetDone) {
                        s.start(t)
                        s.wristStartY = f.y[wr]; s.wristStartX = f.x[wr]
                        s.elbowStartY = f.y[el]; s.wristBaseT = t
                    }
                } else {
                    s.aboveElbowSinceT = t
                }
            }
            Phase.START, Phase.HOLD -> {
                if (!f.bodyStable) { s.idle(); return }  // bassin qui bouge = pas un coup
                // Base glissante : le poignet descend plus bas que la base -> on re-arme
                if (wristBelowElbow && f.y[wr] > s.wristStartY && (t - s.wristBaseT) < cfg.upRiseMaxSeconds) {
                    s.wristStartY = f.y[wr]; s.wristStartX = f.x[wr]
                    s.elbowStartY = f.y[el]; s.wristBaseT = t
                }
                val elb = elbowDeg(if (side == "left") 11 else 12, el, wr)
                val rise = (s.wristStartY - f.y[wr]) / f.trunk
                val elbowRise = (s.elbowStartY - f.y[el]) / f.trunk
                val wristLat = abs(f.x[wr] - s.wristStartX) / f.shoulderW
                val baseHeldMs = (t - s.wristBaseT) * 1000.0
                if (rise >= cfg.upRiseDistance && elbowRise >= cfg.upElbowRise &&
                    wristLat <= cfg.upMaxWristLateral &&
                    baseHeldMs >= cfg.upBaseHoldMs - 1.0 && baseHeldMs < cfg.upRiseMaxSeconds * 1000.0 + 1.0 &&
                    elb < cfg.upMaxElbowDeg
                ) {
                    if (strikeAllowed(t, side)) {
                        s.phase = Phase.TRIGGERED; s.triggerAt = t
                        s.aboveElbowSinceT = -1.0
                        emit("uppercut", side, null, "rise=%.2f elb=%.0f eRise=%.2f lat=%.2f".format(rise, elb, elbowRise, wristLat))
                    }
                } else if (!wristBelowElbow) {
                    s.aboveElbowSinceT = t
                    s.idle()
                }
            }
            Phase.TRIGGERED -> if (!wristBelowElbow) {
                s.aboveElbowSinceT = t
                s.idle()
            }
        }
    }

    // ----------------------------------------------------------------- duck

    /** Duck : nez sous la ligne des épaules + TRONC raccourci (épaules qui se
     *  rapprochent des hanches), maintenu 250-400 ms.
     *  Anti-faux-positifs : s'asseoir descend tout le corps SANS raccourcir le
     *  tronc (genoux non fléchis) -> rejeté par trunkDrop. */
    private fun updateDuck(t: Double) {
        val f = frame
        if (!visible(0)) { duck.idle(); return }
        val depth = (f.noseY - f.midSy) / f.trunk  // > 0 = nez sous la ligne des épaules
        val trunkDrop = if (trunkRef.isNaN() || trunkRef <= 0f) 0f
        else ((trunkRef - f.trunk) / trunkRef).coerceIn(0f, 1f)  // > 0 = buste plié
        when (duck.phase) {
            Phase.IDLE -> if (depth > 0f && trunkDrop >= cfg.duckTrunkDrop) duck.start(t)
            Phase.START -> {
                // Le timer ne démarre qu'une fois le seuil franchi (pas depuis le START)
                if (depth >= cfg.duckTriggerDepth) duck.hold(t)
                else if (depth <= 0f || trunkDrop < cfg.duckTrunkDrop * 0.5f) duck.idle()
            }
            Phase.HOLD -> {
                val held = (t - duck.enterT) * 1000.0
                when {
                    depth < cfg.duckTriggerDepth -> {
                        // Redescendu sous le seuil : on repart du départ (ou reset si relevé)
                        if (depth <= 0f) duck.idle() else duck.start(t)
                    }
                    held > cfg.duckHoldMaxMs -> {
                        // Crouch trop long : pas un duck (défense statique), verrouillé jusqu'au relevé
                        duck.phase = Phase.TRIGGERED
                    }
                    held >= cfg.duckHoldMinMs -> {
                        duck.phase = Phase.TRIGGERED; duck.triggerAt = t
                        emit("duck", null, null, "depth=%.2f drop=%.2f hold=%.0fms".format(depth, trunkDrop, held))
                    }
                }
            }
            Phase.TRIGGERED -> if (depth <= 0f) duck.idle()
        }
        // Duck dégradé : buste partiellement plié sans nez sous les épaules.
        when (duckDegraded.phase) {
            Phase.IDLE -> if (trunkDrop >= cfg.duckTrunkDrop * 0.66f && depth <= 0f) duckDegraded.start(t)
            Phase.START, Phase.HOLD -> {
                val held = (t - duckDegraded.enterT) * 1000.0
                if (trunkDrop >= cfg.duckTrunkDrop * 0.66f && depth <= 0f) {
                    if (held >= cfg.duckHoldMinMs) {
                        duckDegraded.phase = Phase.TRIGGERED; duckDegraded.triggerAt = t
                        emit("duck_degraded", null, null, "value=%.2f".format(cfg.duckDegradedValue))
                    }
                } else duckDegraded.idle()
            }
            Phase.TRIGGERED -> if (trunkDrop < cfg.duckTrunkDrop * 0.33f) duckDegraded.idle()
        }
    }

    // ----------------------------------------------------------------- dodge

    /** Esquive latérale : déplacement du bassin >= 0.2 x largeur épaules, < 0.6 s, direction stockée.
     *  Anti-faux-positifs : déplacement latéral PUR (le bassin ne doit pas monter/descendre). */
    private fun updateDodge(t: Double) {
        val f = frame
        if (hipRefX.isNaN()) { dodge.idle(); return }
        val disp = (f.midHx - hipRefX) / f.shoulderW
        val vertShift = abs(f.midHy - hipRefY) / f.trunk
        when (dodge.phase) {
            Phase.IDLE -> if (abs(disp) > cfg.dodgeStartDisp && vertShift < cfg.dodgeMaxVertShift) dodge.start(t)
            Phase.START, Phase.HOLD -> {
                val dir = if (disp > 0f) "right" else "left"
                if (vertShift >= cfg.dodgeMaxVertShift) {
                    dodge.idle()  // bassin qui monte/descend = pas une esquive (assis/debout)
                } else if (abs(disp) >= cfg.dodgeTriggerDisp && (t - dodge.enterT) < cfg.dodgeTriggerMaxSeconds) {
                    if (dodgeAllowed(t)) {
                        dodge.phase = Phase.TRIGGERED; dodge.triggerAt = t
                        emit("dodge", null, dir, "disp=%.2f dir=%s".format(disp, dir))
                    }
                } else if ((t - dodge.enterT) >= cfg.dodgeTriggerMaxSeconds && abs(disp) < cfg.dodgeTriggerDisp) {
                    dodge.idle()  // trop lent = pas une esquive
                } else if (abs(disp) < cfg.dodgeResetDisp) {
                    dodge.idle()
                    hipRefX = f.midHx  // bassin revenu : on ré-ancre la référence
                }
            }
            Phase.TRIGGERED -> if (abs(disp) < cfg.dodgeResetDisp) { dodge.idle(); hipRefX = f.midHx }
        }
    }

    // ----------------------------------------------------------------- guard

    /** Garde : 2 poignets au-dessus des épaules et proches de l'axe du nez, maintenus >= 500 ms. */
    private fun updateGuard(t: Double) {
        val f = frame
        // Seuil de confiance permissif : mains devant le visage = landmarks moins fiables
        fun vis(i: Int) = f.lik[i] >= cfg.guardMinLikelihood
        if (!vis(15) || !vis(16) || !vis(11) || !vis(12) || !vis(0)) { guard.idle(); return }
        fun inGuard(w: Int, s: Int): Boolean =
            f.y[w] < f.y[s] && abs(f.x[w] - f.noseX) < cfg.guardMaxWristWidth * f.shoulderW
        val both = inGuard(15, 11) && inGuard(16, 12)
        when (guard.phase) {
            Phase.IDLE -> if (inGuard(15, 11) || inGuard(16, 12)) guard.start(t)
            Phase.START -> if (both) guard.hold(t) else if (!inGuard(15, 11) && !inGuard(16, 12)) guard.idle()
            Phase.HOLD -> {
                val held = (t - guard.enterT) * 1000.0
                if (!both) {
                    guard.start(t)  // un poignet sorti : on repart du 1er poignet
                } else if (held >= cfg.guardHoldMs) {
                    guard.phase = Phase.TRIGGERED; guard.triggerAt = t
                    emit("guard", null, null, "hold=%.0fms".format(held))
                } else if (t - lastGuardLogT > 0.5) {
                    // Diagnostic (throttlé) : valeurs réelles pour calibrer si ça ne déclenche pas
                    lastGuardLogT = t
                    fun relY(w: Int, s: Int) = (f.y[w] - f.y[s]) / f.trunk
                    fun relX(w: Int) = abs(f.x[w] - f.noseX) / f.shoulderW
                    Log.i(
                        "PoseGesture",
                        "GUARD_PENDING hold=%.0fms yL=%.2f yR=%.2f xL=%.2f xR=%.2f".format(
                            held, relY(15, 11), relY(16, 12), relX(15), relX(16)
                        )
                    )
                }
            }
            Phase.TRIGGERED -> if (!both) guard.idle()
        }
    }

    private fun emit(name: String, side: String?, direction: String?, signals: String) {
        listener(GestureEvent(name, side, direction, signals))
    }

    /** Log diagnostic (0.5 s throttlé) pendant qu'une machine de frappe est en
     *  START/HOLD sans déclencher : montre les valeurs réelles pour calibrer
     *  detection.json si un geste ne passe jamais. */
    private fun diagLog(t: Double) {
        val active = listOf(jabL, jabR, hookL, hookR, upL, upR).any {
            it.phase == Phase.START || it.phase == Phase.HOLD
        }
        if (!active || t - lastDiagT < 0.5) return
        lastDiagT = t
        val f = frame
        val sh = 12; val wr = 16
        val ext = dist(f.x[wr], f.y[wr], f.x[sh], f.y[sh]) / f.trunk
        val v = if (diagPrevT > 0.0) (ext - diagPrevExt) / ((t - diagPrevT).coerceIn(1e-3, 0.5)).toFloat() else 0f
        diagPrevExt = ext; diagPrevT = t
        val trunkDrop = if (trunkRef.isNaN() || trunkRef <= 0f) 0f else ((trunkRef - f.trunk) / trunkRef)
        Log.i(
            "PoseGesture",
            "DIAG ext=%.2f v=%.1f rot=%.0f elb=%.0f vlat=%.1f drop=%.2f rise=%.2f stable=%b likW=%.2f".format(
                ext, v, rotSignal(), elbowDeg(12, 14, 16), abs(f.x[wr] - jabR.prev) / maxOf(f.dt.toFloat(), 1e-3f) / f.shoulderW,
                trunkDrop, (upR.wristStartY - f.y[wr]) / f.trunk, f.bodyStable, f.lik[16]
            )
        )
    }
}
