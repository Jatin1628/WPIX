package com.surendramaran.yolov9tflite.ocr

import android.app.Application
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class OCRViewModel(application: Application) : AndroidViewModel(application) {
    private val ocrManager = OCRManager()
    private val speechManager = SpeechManager(application)

    private val _ocrState = MutableStateFlow(OCRState())
    val ocrState: StateFlow<OCRState> = _ocrState

    private var isProcessing = false
    private var lastProcessedTime = AtomicLong(0)
    private val FRAME_THROTTLE_MS = 1000L // Process ~1 frame per second

    init {
        speechManager.setOnTTSReadyListener {
            updateState { it.copy(statusText = "Ready to read") }
        }
    }

    fun startOCR() {
        updateState { it.copy(isScanning = true, statusText = "Scanning...") }
    }

    fun stopOCR() {
        speechManager.stop()
        updateState { it.copy(isScanning = false, statusText = "Stopped", isReading = false) }
    }

    fun processFrame(imageProxy: ImageProxy) {
        if (!_ocrState.value.isScanning || isProcessing) {
            return
        }

        // Throttle frame processing
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime.get() < FRAME_THROTTLE_MS) {
            return
        }

        isProcessing = true
        lastProcessedTime.set(currentTime)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val textBlocks = ocrManager.recognizeText(imageProxy)
                val readableText = ocrManager.extractReadableText(textBlocks)

                if (readableText.isNotEmpty() && ocrManager.isValidText(readableText)) {
                    if (!speechManager.isSameLyRecent(readableText)) {
                        updateState {
                            it.copy(
                                detectedText = readableText,
                                statusText = "Reading text",
                                isReading = true
                            )
                        }
                        speechManager.speakWithPrefix("Reading text", readableText)
                    }
                } else {
                    updateState { it.copy(statusText = "No text found") }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateState { it.copy(errorMessage = "OCR Error: ${e.message}") }
            } finally {
                isProcessing = false
            }
        }
    }

    private fun updateState(block: (OCRState) -> OCRState) {
        _ocrState.value = block(_ocrState.value)
    }

    override fun onCleared() {
        speechManager.shutdown()
        super.onCleared()
    }
}
