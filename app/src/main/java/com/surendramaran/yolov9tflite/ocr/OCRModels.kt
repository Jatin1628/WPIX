package com.surendramaran.yolov9tflite.ocr

import android.graphics.Rect

data class OCRState(
    val isScanning: Boolean = false,
    val statusText: String = "Ready",
    val detectedText: String = "",
    val isReading: Boolean = false,
    val errorMessage: String? = null
)

data class TextBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect?
)
