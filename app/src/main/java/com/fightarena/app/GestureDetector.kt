package com.fightarena.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.acos
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
 * Adaptation ML Kit vs spec : la spec suppose pose_world_landmarks 3D en
 * mètres ; ML Kit fournit un z relatif bruité. Les signaux sont donc calculés
 * en 2D pixels normalisés par le tronc (distance épaule-hanche) — scale-
 * invariant, calibré pour la distance de jeu standard (2.0-2.2 m).
 *
 * Machine à états : IDLE -> START -> HOLD -> TRIGGER -> RESET. Un geste ne
 * compte qu'au passage en TRIGGER et doit repasser par RESET pour se
 * redéclencher. Cooldown entre coups + anti-triche (amplitude ET vitesse).
 */
class GestureDetector(context: Context, private val listener: (GestureEvent) -> Unit) {

    private data class Cfg(
        val cooldownMs: Double,
        val minLikelihood: Float,
        val jabStartExt: Float, val jabTriggerExt: Float, val jabTriggerSpeed: Float, val jabResetExt: Float,
        val hookStartRotDeg: Float, val hookTriggerRotDeg: Float, val hookMaxElbowDeg: Float,
        val hookTriggerSpeed: Float, val hookResetRotDeg: Float,
        val upHipDropStart: Float, val upRiseDistance: Float, val upRiseMaxSeconds: Float, val upMaxElbowDeg: Float,
        val duckTriggerDepth: Float, val duckHoldMinMs: Double, val duckHoldMaxMs: Double,
        val duckDegradedValue: Float,
        val dodgeStartDisp: Float, val dodgeTriggerDisp: Float, val dodgeTriggerMaxSeconds: Float, val dodgeResetDisp: Float,
        val guardHoldMs: Double, val guardMaxWristWidth: Float,
        val hookVsDodgeMs: Double,
    )

    private val cfg: Cfg = run {
        val j = try {
            val text = context.assets.open("config/detection.json")
                .bufferedReader().use { it.readText() }
            JSONObject(text)
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
            jabStartExt = jab.getDouble("startExt").toFloat(),
            jabTriggerExt = jab.getDouble("triggerExt").toFloat(),
            jabTriggerSpeed = jab.getDouble("triggerSpeedUnitsPerSec").toFloat(),
            jabResetExt = jab.getDouble("resetExt").toFloat(),
            hookStartRotDeg = hook.getDouble("startRotDeg").toFloat(),
            hookTriggerRotDeg = hook.getDouble("triggerRotDeg").toFloat(),
            hookMaxElbowDeg = hook.getDouble("maxElbowDeg").toFloat(),
            hookTriggerSpeed = hook.getDouble("triggerSpeedUnitsPerSec").toFloat(),
            hookResetRotDeg = hook.getDouble("resetRotDeg").toFloat(),
            upHipDropStart = up.getDouble("hipDropStart").toFloat(),
            upRiseDistance = up.getDouble("riseDistance").toFloat(),
            upRiseMaxSeconds = up.getDouble("riseMaxSeconds").toFloat(),
            upMaxElbowDeg = up.getDouble("maxElbowDeg").toFloat(),
            duckTriggerDepth = duck.getDouble("triggerDepth").toFloat(),
            duckHoldMinMs = duck.getDouble("holdMinMs"),
            duckHoldMaxMs = duck.getDouble("holdMaxMs"),
            duckDegradedValue = duck.getDouble("degradedValue").toFloat(),
            dodgeStartDisp = dodge.getDouble("startDisp").toFloat(),
            dodgeTriggerDisp = dodge.getDouble("triggerDisp").toFloat(),
            dodgeTriggerMaxSeconds = dodge.getDouble("triggerMaxSeconds").toFloat(),
            dodgeResetDisp = dodge.getDouble("resetDisp").toFloat(),
            guardHoldMs = guard.getDouble("holdMs"),
            guardMaxWristWidth = guard.getDouble("maxWristWidth").toFloat(),
            hookVsDodgeMs = conflicts.getDouble("hookVsDodgeMs"),
        )
    }

    private enum class Phase { IDLE, START, HOLD, TRIGGERED }

    private class State {
        var phase = Phase.IDLE
        var enterT = 0.0
        var triggerAt = 0.0
        var prev = 0f       // valeur précédente (extension / x poignet / y poignet)
        var wristStartY = 0f // uppercut : position basse du poignet au START
        var elbowOk = true   // uppercut : coude resté plié pendant la montée
        fun start(t: Double) { phase = Phase.START; enterT = t }
        fun hold(t: Double) { phase = Phase.HOLD; enterT = t }
        fun idle() { phase = Phase.IDLE; enterT = 0.0 }
        val inFlight: Boolean get() = phase != Phase.IDLE
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
        val lik = FloatArray(33)
        var shoulderRotDeg = 0f  // inclinaison ligne épaules vs horizontale
    }

    private val frame = Frame()
    private var lastT = -1.0
    private var lastStrikeT = -1.0
    private var lastHookT = -1.0
    private var lastDodgeT = -1.0
    // Références "au repos" (EMA lent, mis à jour seulement au repos)
    private var hipRefX = Float.NaN
    private var hipRefY = Float.NaN

    private var lastDiagT = -1.0
    private var prevWristR = 0f

    private val jabL = State(); private val jabR = State()
    private val hookL = State(); private val hookR = State()
    private val upL = State(); private val upR = State()
    private val duck = State()
    private val dodge = State()
    private val guard = State()
    private val duckDegraded = State()

    /** Analyse une frame de landmarks filtrés [x,y,z,lik]*33 (pixels). */
    fun process(landmarkBuf: FloatArray, t: Double) {
        fillFrame(landmarkBuf, t)
        if (!frame.valid) { resetAll(); lastT = -1.0; return }
        if (lastT > 0.0) frame.dt = (t - lastT).coerceIn(1e-3, 0.5)
        lastT = t

        // Référence de repos des hanches (EMA 2 %/frame, figée en mouvement)
        val hipDispX = abs(frame.midHx - hipRefX)
        val hipDispY = abs(frame.midHy - hipRefY)
        if (hipRefX.isNaN()) { hipRefX = frame.midHx; hipRefY = frame.midHy }
        else if (hipDispX < cfg.dodgeStartDisp * frame.shoulderW && hipDispY < 0.1f * frame.trunk) {
            hipRefX += 0.02f * (frame.midHx - hipRefX)
            hipRefY += 0.02f * (frame.midHy - hipRefY)
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

    /** Log périodique (1/s, force le diagnostic même au repos) de toutes les
     *  métriques utiles pour débusquer les TRIGGERs fantômes. */
    private fun diagLog(t: Double) {
        if (t - lastDiagT < 1.0) return
        lastDiagT = t
        val f = frame
        val extL = dist(f.x[13], f.y[13], f.x[15], f.y[15]) / f.trunk
        val extR = dist(f.x[14], f.y[14], f.x[16], f.y[16]) / f.trunk
        val vlat = abs(f.x[16] - prevWristR) / maxOf(f.dt.toFloat(), 1e-3f) / f.shoulderW
        prevWristR = f.x[16]
        val drop = (f.midHy - hipRefY) / f.trunk
        val depth = (f.noseY - f.midSy) / f.trunk
        Log.i(
            "PoseGesture",
            "DIAG trunk=%.0f rot=%.0f elb=%.0f vlat=%.1f extL=%.2f extR=%.2f drop=%.2f depth=%.2f stable=%b likW=%.2f".format(
                f.trunk, f.shoulderRotDeg, elbowDeg(12, 14, 16), vlat,
                if (visible(15)) extL else -1f, if (visible(16)) extR else -1f,
                if (hipRefY.isNaN()) 0f else drop, depth, f.valid, f.lik[12]
            )
        )
    }

    /** Reset complet (personne hors cadre) : toutes les machines repartent à zéro. */
    fun resetAll() {
        for (s in listOf(jabL, jabR, hookL, hookR, upL, upR, duck, dodge, guard, duckDegraded)) s.idle()
        lastStrikeT = -1.0
        lastHookT = -1.0
        lastDodgeT = -1.0
        hipRefX = Float.NaN
        hipRefY = Float.NaN
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
            f.lik[i] = buf[idx + 3]
        }
        f.midSx = (sL + sR) / 2f; f.midSy = (sLy + sRy) / 2f
        f.midHx = (hL + hR) / 2f; f.midHy = (hLy + hRy) / 2f
        f.noseX = noseX; f.noseY = noseY
        f.trunk = dist(f.midSx, f.midSy, f.midHx, f.midHy)
        f.shoulderW = dist(sL, sLy, sR, sRy)
        if (f.trunk < 1f) return
        // Inclinaison de la ligne épaules vs horizontale (hook, spec §3.2) :
        // angle ABSOLU 0-90°, indépendant du sens (caméra frontale miroir).
        val rawDeg = (Math.atan2((sRy - sLy).toDouble(), (sR - sL).toDouble()) * 180.0 / PI).toFloat()
        val tilt = abs(rawDeg)
        f.shoulderRotDeg = if (tilt > 90f) 180f - tilt else tilt
        f.valid = true
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    private fun visible(i: Int) = frame.lik[i] >= cfg.minLikelihood

    /** Angle au coude (épaule-coude-poignet) en degrés. Bras tendu ~180. */
    private fun elbowDeg(s: Int, e: Int, w: Int): Float {
        val f = frame
        if (!visible(s) || !visible(e) || !visible(w)) return 180f
        val v1x = f.x[s] - f.x[e]; val v1y = f.y[s] - f.y[e]
        val v2x = f.x[w] - f.x[e]; val v2y = f.y[w] - f.y[e]
        val m1 = sqrt(v1x * v1x + v1y * v1y); val m2 = sqrt(v2x * v2x + v2y * v2y)
        if (m1 < 1e-3f || m2 < 1e-3f) return 180f
        val cosA = ((v1x * v2x + v1y * v2y) / (m1 * m2)).coerceIn(-1f, 1f)
        return (acos(cosA.toDouble()) * 180.0 / PI).toFloat()
    }

    /** Un coup n'est émis qu'après le cooldown global entre coups. */
    private fun strikeAllowed(t: Double): Boolean {
        if (lastStrikeT > 0.0 && (t - lastStrikeT) * 1000.0 < cfg.cooldownMs) return false
        lastStrikeT = t
        return true
    }

    /** Anti-conflit hook vs esquive : exclusivité mutuelle 300 ms (spec §5). */
    private fun hookAllowed(t: Double): Boolean {
        if (lastDodgeT > 0.0 && (t - lastDodgeT) * 1000.0 < cfg.hookVsDodgeMs) return false
        lastHookT = t
        return true
    }

    private fun dodgeAllowed(t: Double): Boolean {
        if (lastHookT > 0.0 && (t - lastHookT) * 1000.0 < cfg.hookVsDodgeMs) return false
        lastDodgeT = t
        return true
    }

    // ------------------------------------------------------------------ jab

    /** Jab : extension = dist(poignet, épaule)/dist(épaule, hanche) ; vitesse = d(ext)/dt. */
    private fun updateJab(s: State, sh: Int, el: Int, wr: Int, side: String, t: Double) {
        val f = frame
        if (!visible(sh) || !visible(wr) || !visible(el)) { s.idle(); return }
        val hipIdx = if (side == "left") 23 else 24
        if (!visible(hipIdx)) { s.idle(); return }
        val ext = dist(f.x[wr], f.y[wr], f.x[sh], f.y[sh]) / dist(f.x[sh], f.y[sh], f.x[hipIdx], f.y[hipIdx])
        val v = if (f.dt > 0.0) (ext - s.prev) / f.dt.toFloat() else 0f
        s.prev = ext
        when (s.phase) {
            Phase.IDLE -> if (ext > cfg.jabStartExt) s.start(t)
            Phase.START, Phase.HOLD -> {
                if (ext >= cfg.jabTriggerExt && v >= cfg.jabTriggerSpeed) {
                    if (strikeAllowed(t)) {
                        s.phase = Phase.TRIGGERED; s.triggerAt = t
                        emit("jab", side, null, "ext=%.2f v=%.1f".format(ext, v))
                    }
                } else if (ext < cfg.jabResetExt) s.idle()
            }
            Phase.TRIGGERED -> if (ext < cfg.jabResetExt) s.idle()
        }
    }

    // ----------------------------------------------------------------- hook

    /** Hook : rotation du buste + coude plié + vitesse latérale du poignet. */
    private fun updateHook(s: State, sh: Int, el: Int, wr: Int, side: String, t: Double) {
        val f = frame
        if (!visible(sh) || !visible(el) || !visible(wr)) { s.idle(); return }
        val rot = f.shoulderRotDeg
        val elb = elbowDeg(sh, el, wr)
        val vlat = if (f.dt > 0.0) abs(f.x[wr] - s.prev) / f.dt.toFloat() / f.shoulderW else 0f
        s.prev = f.x[wr]
        when (s.phase) {
            Phase.IDLE -> if (rot > cfg.hookStartRotDeg) s.start(t)
            Phase.START, Phase.HOLD -> {
                if (rot >= cfg.hookTriggerRotDeg && elb < cfg.hookMaxElbowDeg && vlat >= cfg.hookTriggerSpeed) {
                    if (strikeAllowed(t) && hookAllowed(t)) {
                        s.phase = Phase.TRIGGERED; s.triggerAt = t
                        emit("hook", side, null, "rot=%.0f elb=%.0f vlat=%.1f".format(rot, elb, vlat))
                    }
                } else if (rot < cfg.hookResetRotDeg) s.idle()
            }
            Phase.TRIGGERED -> if (rot < cfg.hookResetRotDeg) s.idle()
        }
    }

    // -------------------------------------------------------------- uppercut

    /** Uppercut : départ bas (poignet sous les coudes, hanche abaissée), montée rapide, coude plié. */
    private fun updateUppercut(s: State, el: Int, wr: Int, side: String, t: Double) {
        val f = frame
        val hipIdx = if (side == "left") 23 else 24
        if (!visible(wr) || !visible(el) || !visible(hipIdx)) { s.idle(); return }
        val wristBelowElbow = f.y[wr] > f.y[el]
        val hipDrop = (f.midHy - hipRefY) / f.trunk
        when (s.phase) {
            Phase.IDLE -> {
                if (wristBelowElbow && hipDrop >= cfg.upHipDropStart) {
                    s.start(t); s.wristStartY = f.y[wr]; s.elbowOk = true
                }
            }
            Phase.START, Phase.HOLD -> {
                val elb = elbowDeg(if (side == "left") 11 else 12, el, wr)
                if (elb >= cfg.upMaxElbowDeg) s.elbowOk = false  // bras tendu pendant la montée = invalide
                val rise = (s.wristStartY - f.y[wr]) / f.trunk
                if (rise >= cfg.upRiseDistance && (t - s.enterT) < cfg.upRiseMaxSeconds && s.elbowOk) {
                    if (strikeAllowed(t)) {
                        s.phase = Phase.TRIGGERED; s.triggerAt = t
                        emit("uppercut", side, null, "rise=%.2f elb=%.0f".format(rise, elb))
                    }
                } else if (!wristBelowElbow) s.idle()
            }
            Phase.TRIGGERED -> if (!wristBelowElbow) s.idle()
        }
    }

    // ----------------------------------------------------------------- duck

    /** Duck : nez sous la ligne des épaules, maintenu 250-400 ms. Dégradé = genoux sans chute des épaules. */
    private fun updateDuck(t: Double) {
        val f = frame
        if (!visible(0)) { duck.idle(); return }
        val depth = (f.noseY - f.midSy) / f.trunk  // > 0 = nez sous la ligne des épaules
        when (duck.phase) {
            Phase.IDLE -> if (depth > 0f) duck.start(t)
            Phase.START, Phase.HOLD -> {
                val held = (t - duck.enterT) * 1000.0
                if (depth >= cfg.duckTriggerDepth) {
                    if (held >= cfg.duckHoldMinMs) {
                        duck.phase = Phase.TRIGGERED; duck.triggerAt = t
                        emit("duck", null, null, "depth=%.2f hold=%.0fms".format(depth, held))
                    }
                } else if (depth <= 0f) duck.idle()
            }
            Phase.TRIGGERED -> if (depth <= 0f) duck.idle()
        }
        // Duck dégradé : hanche abaissée sans nez sous les épaules (spec §3.4, défense partielle).
        val hipDrop = (f.midHy - hipRefY) / f.trunk
        when (duckDegraded.phase) {
            Phase.IDLE -> if (hipDrop >= cfg.duckTriggerDepth * 0.66f && depth <= 0f) duckDegraded.start(t)
            Phase.START, Phase.HOLD -> {
                val held = (t - duckDegraded.enterT) * 1000.0
                if (hipDrop >= cfg.duckTriggerDepth * 0.66f && depth <= 0f) {
                    if (held >= cfg.duckHoldMinMs) {
                        duckDegraded.phase = Phase.TRIGGERED; duckDegraded.triggerAt = t
                        emit("duck_degraded", null, null, "value=%.2f".format(cfg.duckDegradedValue))
                    }
                } else duckDegraded.idle()
            }
            Phase.TRIGGERED -> if (hipDrop < cfg.duckTriggerDepth * 0.33f) duckDegraded.idle()
        }
    }

    // ----------------------------------------------------------------- dodge

    /** Esquive latérale : déplacement du bassin >= 0.2 x largeur épaules, < 0.6 s, direction stockée. */
    private fun updateDodge(t: Double) {
        val f = frame
        if (hipRefX.isNaN()) { dodge.idle(); return }
        val disp = (f.midHx - hipRefX) / f.shoulderW
        when (dodge.phase) {
            Phase.IDLE -> if (abs(disp) > cfg.dodgeStartDisp) dodge.start(t)
            Phase.START, Phase.HOLD -> {
                val dir = if (disp > 0f) "right" else "left"
                if (abs(disp) >= cfg.dodgeTriggerDisp && (t - dodge.enterT) < cfg.dodgeTriggerMaxSeconds) {
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
        if (!visible(15) || !visible(16) || !visible(11) || !visible(12) || !visible(0)) { guard.idle(); return }
        fun inGuard(w: Int, s: Int): Boolean =
            f.y[w] < f.y[s] && abs(f.x[w] - f.noseX) < cfg.guardMaxWristWidth * f.shoulderW
        val both = inGuard(15, 11) && inGuard(16, 12)
        when (guard.phase) {
            Phase.IDLE -> if (inGuard(15, 11) || inGuard(16, 12)) guard.start(t)
            Phase.START -> if (both) guard.hold(t) else if (!inGuard(15, 11) && !inGuard(16, 12)) guard.idle()
            Phase.HOLD -> {
                val held = (t - guard.enterT) * 1000.0
                if (!both) guard.start(t)  // un poignet sorti : on repart du 1er poignet
                else if (held >= cfg.guardHoldMs) {
                    guard.phase = Phase.TRIGGERED; guard.triggerAt = t
                    emit("guard", null, null, "hold=%.0fms".format(held))
                }
            }
            Phase.TRIGGERED -> if (!both) guard.idle()
        }
    }

    private fun emit(name: String, side: String?, direction: String?, signals: String) {
        listener(GestureEvent(name, side, direction, signals))
    }
}
