package com.surendramaran.yolov9tflite.ocr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.abs

class SpeechManager(context: Context) {
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cooldown mechanism to avoid repetition
    private var lastSpokenText: String = ""
    private var lastSpokenTime: Long = 0
    private val COOLDOWN_MS = 3000L

    private var onTTSReady: (() -> Unit)? = null

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                textToSpeech?.setSpeechRate(0.9f) // Slightly slower for clarity
                ttsReady = true
                onTTSReady?.invoke()
            }
        }
    }

    fun setOnTTSReadyListener(callback: () -> Unit) {
        onTTSReady = callback
        if (ttsReady) {
            callback()
        }
    }

    fun isSameLyRecent(text: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeDifference = abs(currentTime - lastSpokenTime)
        
        return text == lastSpokenText && timeDifference < COOLDOWN_MS
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!ttsReady || !isTTSReady()) return
        if (text.isEmpty()) return

        if (isSameLyRecent(text)) {
            return // Skip if same text was recently spoken
        }

        lastSpokenText = text
        lastSpokenTime = System.currentTimeMillis()

        mainHandler.post {
            textToSpeech?.speak(text, queueMode, null, "ocr")
        }
    }

    fun speakWithPrefix(prefix: String, mainText: String) {
        if (!ttsReady || !isTTSReady()) return
        if (mainText.isEmpty()) return

        lastSpokenText = mainText
        lastSpokenTime = System.currentTimeMillis()

        mainHandler.post {
            textToSpeech?.speak(prefix, TextToSpeech.QUEUE_FLUSH, null, "prefix")
            mainHandler.postDelayed({
                textToSpeech?.speak(mainText, TextToSpeech.QUEUE_ADD, null, "ocr")
            }, 800) // Small delay between prefix and main text
        }
    }

    fun stop() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
        }
    }

    fun isTTSReady(): Boolean = ttsReady

    fun resetCooldown() {
        lastSpokenText = ""
        lastSpokenTime = 0
    }

    fun shutdown() {
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
    }
}
