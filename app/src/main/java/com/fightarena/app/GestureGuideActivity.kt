package com.fightarena.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.fightarena.app.Gesture

/**
 * Galerie d'apprentissage : stickman animé de chaque geste + consignes.
 * Navigation : précédent/suivant, et bascule gauche/droite pour les gestes
 * de bras (le stickman se reflète en miroir).
 */
class GestureGuideActivity : ComponentActivity() {

    private data class Guide(
        val name: String,
        val instructions: String,
        val gesture: Gesture,
    )

    private val guides = listOf(
        Guide(
            "JAB (coup droit)",
            "Depuis la garde, tends le poing VITE vers la caméra (bras tendu, " +
                "épaules stables) puis ramène. L'amplitude compte : bras bien replié avant, tendu après.",
            Gesture.JAB,
        ),
        Guide(
            "CROCHET (hook)",
            "Tourne le buste et frappe latéralement : coude plié à ~90°, poing qui " +
                "part du côté, épaules qui pivotent. Reviens en garde.",
            Gesture.HOOK,
        ),
        Guide(
            "UPPERCUT",
            "Fléchis les genoux (poing bas, coude plié serré), puis remonte le poing " +
                "vite de bas en haut sous le menton. Le coude reste plié pendant toute la montée.",
            Gesture.UPPERCUT,
        ),
        Guide(
            "DUCK (esquive basse)",
            "Accroupis-toi franchement : les ÉPAULES descendent (genoux fléchis), " +
                "reste ~0,3 s, remonte. S'asseoir ne compte pas : il faut que les épaules " +
                "descendent plus que les hanches.",
            Gesture.DUCK,
        ),
        Guide(
            "DODGE (esquive latérale)",
            "En garde, fais UN PAS RAPIDE de côté : tout le corps se déplace " +
                "latéralement (bassin qui bouge), puis reviens. Mouvement sec, < 0,6 s.",
            Gesture.DODGE,
        ),
        Guide(
            "GARDE",
            "Les deux poings devant le visage (coudes pliés), maintiens ≥ 0,5 s " +
                "sans bouger, puis baisse les bras.",
            Gesture.GUARD,
        ),
    )

    private var index = 0
    private var sideLeft = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_guide)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.prevButton).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.nextButton).setOnClickListener { step(1) }
        findViewById<Button>(R.id.sideButton).setOnClickListener {
            sideLeft = !sideLeft
            updateSideButton()
            refresh()
        }
        refresh()
    }

    private fun step(delta: Int) {
        index = (index + delta + guides.size) % guides.size
        sideLeft = false
        refresh()
    }

    private fun updateSideButton() {
        findViewById<Button>(R.id.sideButton).visibility =
            if (guides[index].gesture.hasSide) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.sideButton).text = if (sideLeft) "GAUCHE" else "DROIT"
    }

    private fun refresh() {
        val g = guides[index]
        findViewById<TextView>(R.id.gestureName).text =
            "(${index + 1}/${guides.size}) ${g.name}"
        findViewById<TextView>(R.id.gestureInstructions).text = g.instructions
        updateSideButton()
        findViewById<StickmanAnimatorView>(R.id.stickman).setGesture(g.gesture, sideLeft)
    }
}
