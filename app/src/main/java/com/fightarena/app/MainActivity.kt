package com.fightarena.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    /** Vrai = MediaPipe Tasks (world landmarks 3D) ; faux = ML Kit Pose Detection. */
    private val useMediaPipeTasks = true

    private lateinit var overlay: PoseOverlayView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var mediaPipeAnalyzer: MediaPipePoseAnalyzer? = null
    private var camera: Camera? = null
    private var torchOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        overlay = findViewById(R.id.poseOverlay)
        overlay.mirrored = true
        initTorchButton()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            previewView.scaleX = -1f

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    if (useMediaPipeTasks) {
                        mediaPipeAnalyzer = MediaPipePoseAnalyzer(overlay)
                        it.setAnalyzer(analysisExecutor, mediaPipeAnalyzer!!)
                    } else {
                        it.setAnalyzer(analysisExecutor, PoseAnalyzer(overlay))
                    }
                }

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initTorchButton() {
        val torchButton = findViewById<ImageButton>(R.id.torchButton)
        torchButton.setOnClickListener {
            val cam = camera
            if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                Toast.makeText(this, R.string.no_flash, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            torchButton.alpha = if (torchOn) 1f else 0.4f
        }
    }

    override fun onDestroy() {
        mediaPipeAnalyzer?.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
