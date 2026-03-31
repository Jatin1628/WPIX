package com.surendramaran.yolov9tflite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.surendramaran.yolov9tflite.voice.VoiceCommandType
import com.surendramaran.yolov9tflite.voice.VoiceCommandViewModel
import com.surendramaran.yolov9tflite.Constants.LABELS_PATH
import com.surendramaran.yolov9tflite.Constants.MODEL_PATH
import com.surendramaran.yolov9tflite.feedback.DetectionFeedbackViewModel
import com.surendramaran.yolov9tflite.camera.CameraConnectionActivity
import com.surendramaran.yolov9tflite.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenLabelSignature: String? = null

    private lateinit var voiceViewModel: VoiceCommandViewModel

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ocrRunning = false
    private var ocrRunnable: Runnable? = null

    private var lastBoundingBoxes: List<BoundingBox> = emptyList()

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceViewModel.startListening()
        } else {
            toast("Microphone permission denied. Enable it to use voice commands.")
        }
    }

    private lateinit var feedbackViewModel: DetectionFeedbackViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        voiceViewModel = ViewModelProvider(this)[VoiceCommandViewModel::class.java]
        bindVoiceUi()

        binding.connectToCameraButton.setOnClickListener {
            startActivity(Intent(this, CameraConnectionActivity::class.java))
        }

        feedbackViewModel = ViewModelProvider(this)[DetectionFeedbackViewModel::class.java]
        bindFeedbackUi()
        observeSpeechQueue()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
    }

    private fun bindFeedbackUi() {
        binding.walkingModeButton.setOnClickListener {
            feedbackViewModel.toggleWalkingMode()
        }

        lifecycleScope.launch {
            feedbackViewModel.walkingModeButtonText.collectLatest { text ->
                binding.walkingModeButton.text = text
            }
        }

        lifecycleScope.launch {
            feedbackViewModel.uiSummary.collectLatest { summary ->
                binding.detectionSummaryText.text = summary
            }
        }
    }

    private fun observeSpeechQueue() {
        lifecycleScope.launch {
            feedbackViewModel.speechEvents.collectLatest { phrase ->
                if (!ttsReady) return@collectLatest
                // SpeechManager already handles gap + cooldown, so we just speak sequentially.
                textToSpeech?.speak(
                    phrase,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "feedback"
                )
            }
        }
    }

    private fun bindVoiceUi() {
        binding.voiceButton.setOnClickListener {
            val currentlyListening = voiceViewModel.isListening.value == true
            if (currentlyListening) {
                stopAnyOngoingProcessing()
                voiceViewModel.stopListening()
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    voiceViewModel.startListening()
                } else {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        voiceViewModel.isListening.observe(this) { isListening ->
            binding.voiceButton.text = if (isListening) "Stop Voice" else "Start Voice"
        }

        voiceViewModel.commandText.observe(this) { text ->
            // Keep this text as the main UI indicator for recognized commands/errors.
            binding.voiceCommandText.text = text
        }

        voiceViewModel.errorText.observe(this) { error ->
            if (error.isNullOrBlank()) return@observe
            toast(error)
        }

        voiceViewModel.commandEvent.observe(this) { event ->
            val command = event.getContentIfNotHandled() ?: return@observe
            when (command) {
                VoiceCommandType.WhatsInFront -> detectObjects()
                VoiceCommandType.ReadText -> startOCR()
                VoiceCommandType.Stop -> {
                    stopAnyOngoingProcessing()
                    voiceViewModel.stopListening()
                }
                is VoiceCommandType.Unknown -> {
                    // No action; ViewModel already updates UI text.
                }
            }
        }
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(baseContext)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }

    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnyOngoingProcessing()
        if (::voiceViewModel.isInitialized) {
            voiceViewModel.stopListening()
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val TTS_UTTERANCE_ID = "detection"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            lastSpokenLabelSignature = null
            binding.overlay.clear()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        lastBoundingBoxes = boundingBoxes
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }

        // Send detections to intelligent feedback system (speech filtering happens in ViewModel).
        feedbackViewModel.onDetections(boundingBoxes)
    }

    private fun detectObjects() {
        val boxes = lastBoundingBoxes
        if (boxes.isEmpty()) {
            toast("No objects detected yet")
            return
        }
        // Force speech even if the same objects are already announced recently.
        feedbackViewModel.onDetections(boxes, forceAnnounce = true)
    }

    private fun startOCR() {
        if (ocrRunning) return
        ocrRunning = true
        toast("Starting OCR (stub)...")
        binding.voiceCommandText.text = "OCR started (stub)"

        ocrRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            ocrRunning = false
            binding.voiceCommandText.text = "OCR done (stub)"
            toast("OCR complete (stub)")
        }
        ocrRunnable = runnable
        mainHandler.postDelayed(runnable, 2500L)
    }

    private fun stopOCR() {
        if (!ocrRunning) return
        ocrRunning = false
        ocrRunnable?.let { mainHandler.removeCallbacks(it) }
        ocrRunnable = null
        toast("OCR stopped")
    }

    private fun stopAnyOngoingProcessing() {
        stopOCR()
        // Stop any ongoing TTS quickly; voice recognition stop is handled via ViewModel.
        textToSpeech?.stop()
        feedbackViewModel.stopAllSpeech()
    }

    private fun announceDetections(boundingBoxes: List<BoundingBox>) {
        if (!ttsReady) return
        val uniqueNames = boundingBoxes.map { it.clsName }.distinct().sorted()
        if (uniqueNames.isEmpty()) return
        val signature = uniqueNames.joinToString("\u0000")
        if (signature == lastSpokenLabelSignature) return
        lastSpokenLabelSignature = signature
        val phrase = if (uniqueNames.size == 1) {
            "Detected ${uniqueNames.first()}"
        } else {
            "Detected ${uniqueNames.joinToString(", ")}"
        }
        textToSpeech?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
    }

    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
