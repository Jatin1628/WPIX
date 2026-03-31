package com.surendramaran.yolov9tflite.ocr

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OCRManager {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(imageProxy: ImageProxy): List<TextBlock> {
        return try {
            val inputImage = InputImage.fromMediaImage(imageProxy.image!!, imageProxy.imageInfo.rotationDegrees)
            val result = textRecognizer.process(inputImage).await()

            val textBlocks = mutableListOf<TextBlock>()
            
            for (block in result.textBlocks) {
                val text = block.text.trim()
                
                // Filter out empty or very short text
                if (text.isNotEmpty() && text.length > 2) {
                    val boundingBox = block.boundingBox
                    textBlocks.add(
                        TextBlock(
                            text = text,
                            confidence = 1.0f, // ML Kit doesn't provide per-block confidence
                            boundingBox = boundingBox
                        )
                    )
                }
            }

            textBlocks
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun extractReadableText(textBlocks: List<TextBlock>): String {
        if (textBlocks.isEmpty()) return ""
        
        return textBlocks
            .map { it.text }
            .filter { text ->
                // Filter noise: only keep text with at least 3 characters or meaningful words
                text.length >= 3 || text.matches(Regex("[a-zA-Z0-9]+"))
            }
            .joinToString(" ")
            .take(500) // Limit to 500 chars for TTS
    }

    fun isValidText(text: String): Boolean {
        if (text.isEmpty() || text.length < 3) return false
        // Check if it contains at least some alphanumeric characters
        return text.any { it.isLetterOrDigit() }
    }
}
