package com.fightarena.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.quickpose.camera.QuickPoseCameraSwitchView
import ai.quickpose.core.Feature
import ai.quickpose.core.Landmarks
import ai.quickpose.core.QuickPose
import ai.quickpose.core.mp.QuickPoseMP
import com.fightarena.app.LatencyStats
import kotlinx.coroutines.launch

/**
 * Variante QuickPose (MediaPipe BlazePose via SDK propriétaire) du pipeline.
 *
 * Différence clé vs ML Kit : QuickPose fournit sa propre caméra Camera2
 * (QuickPoseCameraSwitchView) et renvoie les 33 landmarks BlazePose dans un
 * repère NORMALISÉ (0..1). On les convertit en pixels pour réutiliser à
 * l'identique le GestureDetector + l'overlay + le lissage One-Euro.
 */
class QuickPoseActivity : ComponentActivity(), PoseSource {

    private lateinit var overlay: PoseOverlayView
    private lateinit var quickPose: QuickPose
    private var cameraView: QuickPoseCameraSwitchView? = null

    /** Clé SDK QuickPose (dev.quickpose.ai). À remplacer par une clé valide
     *  enregistrée pour l'identifiant d'application com.fightarena.app. */
    private val quickPoseKey = "YOUR_QUICKPOSE_SDK_KEY"

    /** Taille d'image de travail : les landmarks normalisés sont convertis
     *  dans ce repère pixels (mêmes proportions que le PreviewView FILL_CENTER). */
    private val workW = 240
    private val workH = 320

    override val stats = LatencyStats(240)
    override var frameCount = 0
        private set

    private val smoother = LandmarkSmoother()
    private val smoothOut = FloatArray(3)

    private lateinit var gestureDetector: GestureDetector

    private fun gestureLabel(ev: GestureEvent): String = when (ev.name) {
        "jab" -> if (ev.side == "left") "JAB GAUCHE" else "JAB DROIT"
        "hook" -> if (ev.side == "left") "CROCHET GAUCHE" else "CROCHET DROIT"
        "uppercut" -> if (ev.side == "left") "UPPERCUT GAUCHE" else "UPPERCUT DROIT"
        "duck" -> "DUCK"
        "duck_degraded" -> "DUCK FAIBLE"
        "dodge" -> if (ev.direction == "left") "ESQUIVE GAUCHE" else "ESQUIVE DROITE"
        "guard" -> "GARDE"
        else -> ev.name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quickpose)
        overlay = findViewById(R.id.poseOverlay)
        overlay.mirrored = true
        gestureDetector = GestureDetector.fromAssets(this) { ev ->
            Log.i(
                "PoseGesture",
                "TRIGGER ${ev.name}" +
                    (ev.side?.let { "_$it" } ?: "") +
                    (ev.direction?.let { "_$it" } ?: "") +
                    " ${ev.signals}"
            )
            overlay.setLastGesture(gestureLabel(ev))
        }

        findViewById<View>(R.id.gestureGuideButton).setOnClickListener {
            startActivity(Intent(this, GestureGuideActivity::class.java))
        }

        quickPose = QuickPose(this, quickPoseKey)
        cameraView = QuickPoseCameraSwitchView(this, quickPose)
        findViewById<android.widget.FrameLayout>(R.id.quickposeCameraContainer)
            .addView(cameraView)

        quickPose.start(
            arrayOf(Feature.ShowPoints()),
            {
                Log.i("QuickPose", "QuickPose started (key=$quickPoseKey)")
            },
            { status, overlayView, results, feedback, landmarks ->
                onQuickPoseFrame(status, landmarks)
            }
        )

        lifecycleScope.launch {
            cameraView?.start(true) // caméra avant
        }
    }

    /**
     * Convertit les landmarks normalisés QuickPose en pixels puis alimente le
     * GestureDetector et l'overlay (même format [x,y,z,lik]*33 que ML Kit).
     */
    private fun onQuickPoseFrame(status: ai.quickpose.core.Status, landmarks: Landmarks?) {
        frameCount++
        val frameNs = System.nanoTime()
        val t = frameNs / 1e9
        val body = landmarks?.allLandmarksForBody() ?: emptyList()
        val buf = FloatArray(33 * 4)

        if (body.isEmpty()) {
            smoother.reset()
            buf.fill(-1f)
        } else {
            for (i in 0 until 33) {
                val idx = i * 4
                val p = if (i < body.size) body[i] else null
                if (p == null || p.presence < 0.5f) {
                    buf[idx] = -1f
                    buf[idx + 1] = -1f
                    buf[idx + 2] = -1f
                    buf[idx + 3] = -1f
                    continue
                }
                val px = p.x * workW
                val py = p.y * workH
                val pz = p.z * workH
                smoother.smooth(i, px, py, pz, workW, workH, t, smoothOut)
                buf[idx] = smoothOut[0]
                buf[idx + 1] = smoothOut[1]
                buf[idx + 2] = smoothOut[2]
                buf[idx + 3] = p.visibility
            }
        }

        val latencyMs = (frameNs - lastFrameNs) / 1_000_000f
        if (lastFrameNs != 0L) stats.record(latencyMs)
        lastFrameNs = frameNs

        gestureDetector.process(buf, t, workH)
        overlay.onPose(buf, latencyMs, workW, workH, this)
    }

    private var lastFrameNs = 0L

    override fun onDestroy() {
        cameraView?.stop()
        quickPose.stop()
        super.onDestroy()
    }
}
