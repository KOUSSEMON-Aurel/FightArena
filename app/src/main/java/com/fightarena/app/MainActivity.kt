package com.fightarena.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    /** Vrai = MediaPipe Tasks (world landmarks 3D) ; faux = ML Kit Pose Detection. */
    private val useMediaPipeTasks = true

    private lateinit var overlay: PoseOverlayView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val videoExecutor = Executors.newSingleThreadExecutor()
    private var mediaPipeAnalyzer: MediaPipePoseAnalyzer? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var running = true
    private var forceCpu = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        overlay = findViewById(R.id.poseOverlay)
        initTorchButton()

        // Mode test vidéo : --es video_test benchmark|20fps (rejoue /sdcard/pose_test/)
        val videoTest = intent.getStringExtra("video_test")
        if (videoTest != null) {
            overlay.mirrored = false  // les frames vidéo sont déjà dans le repère écran
            // A/B modèle : --es model lite (lite CPU rapide) ; --es force_cpu 1
            // (pas de swap GPU, mesure CPU pur)
            val testModel = intent.getStringExtra("model") ?: "full"
            forceCpu = intent.getBooleanExtra("force_cpu", false)
            mediaPipeAnalyzer = MediaPipePoseAnalyzer(overlay, testModel, forceCpu)
            startVideoTest(videoTest)
            return
        }

        // Capture propre : --es hide_overlay 1 (masque les points pour un screenrecord)
        if (intent.getBooleanExtra("hide_overlay", false)) {
            overlay.visibility = View.GONE
        }

        overlay.mirrored = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Rejoue les frames /data/local/tmp/pose_test/ (push adb) dans le pipeline
     * de pose, sans caméra. benchmark = aussi vite que possible (attend chaque
     * résultat) ; 20fps = rythme caméra. Métriques via logcat PosePerf.
     */
    private fun startVideoTest(mode: String) {
        videoExecutor.execute {
            val dir = File("/data/local/tmp/pose_test")
            val files = dir.listFiles()?.filter { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
                ?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) {
                Log.e("VideoTest", "no frames in $dir")
                return@execute
            }
            val pool = ArrayList<Bitmap>(60)
            for (f in files) {
                if (pool.size >= 60) break
                pool.add(BitmapFactory.decodeFile(f.absolutePath) ?: continue)
            }
            Log.i("VideoTest", "mode=$mode frames=${pool.size}")
            val analyzer = mediaPipeAnalyzer ?: return@execute
            val isBenchmark = mode == "benchmark"
            // Benchmark : on attend le GPU AVANT de lancer la boucle. Mesure le
            // temps d'init GPU sans contention CPU (l'inférence CPU à 146ms/frame
            // rallongeait l'init de 13s dans les premiers runs). Sauté en CPU pur.
            if (isBenchmark && !forceCpu) {
                val waitStart = System.nanoTime()
                while (!analyzer.gpuReady && running) Thread.sleep(50)
                Log.i(
                    "VideoTest",
                    "GPU ready after ${"%.1f".format((System.nanoTime() - waitStart) / 1e6f)}ms",
                )
            }
            var i = 0
            while (running) {
                val bmp = pool[i % pool.size]
                analyzer.processBitmap(bmp, bmp.width, bmp.height, 0)
                if (isBenchmark) {
                    val done = CountDownLatch(1)
                    analyzer.frameDone = { done.countDown() }
                    if (!done.await(5, TimeUnit.SECONDS)) {
                        Log.w("VideoTest", "timeout waiting result")
                    }
                } else {
                    Thread.sleep(50)  // ~20 fps comme la caméra
                }
                i++
            }
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
                        // Analyse en petite résolution : MediaPipe redimensionne vers
                        // son entrée fixe (256×256 full) de toute façon, donc 640×480
                        // ne sert qu'à payer une copie CPU 4x plus grosse à chaque frame.
                        // Le preview reste plein écran (use case séparé).
                        Size(320, 240),
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
        running = false
        mediaPipeAnalyzer?.close()
        analysisExecutor.shutdown()
        videoExecutor.shutdown()
        super.onDestroy()
    }
}
