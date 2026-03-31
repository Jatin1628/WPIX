package com.surendramaran.yolov9tflite.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import kotlin.math.max

class VoiceCommandManager(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onListeningStateChanged(isListening: Boolean)
        fun onCommand(command: VoiceCommandType, rawText: String)
        fun onError(message: String)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isUserStopped = true
    private var networkRetryCount = 0

    private var restartRunnable: Runnable? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callback.onListeningStateChanged(isUserStopped.not())
        }

        override fun onBeginningOfSpeech() {
            // Keep it simple: listening is active until results/error arrives.
            callback.onListeningStateChanged(isUserStopped.not())
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // When the system thinks speech is finished, immediately restart for continuous listening.
            restartListeningIfNeeded(RestartReason.END_OF_SPEECH)
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Recognition client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions for audio recording"
                SpeechRecognizer.ERROR_NETWORK -> "Network error in speech recognition"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout in speech recognition"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
                SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected (timeout)"
                else -> "Speech recognition error: $error"
            }

            callback.onError(message)

            when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    networkRetryCount++
                    restartListeningIfNeeded(
                        RestartReason.NETWORK_ERROR,
                        delayMs = (1000L * networkRetryCount.toLong()).coerceAtMost(5000L)
                    )
                }
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Retry quickly for transient errors/timeouts.
                    restartListeningIfNeeded(RestartReason.RETRY)
                }
                else -> {
                    // For other errors, stop restarting (avoids tight loops).
                    isUserStopped = true
                    callback.onListeningStateChanged(false)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val best = matchCommand(matches)

            if (best != null) {
                callback.onCommand(best.command, best.rawText)
                if (best.command is VoiceCommandType.Stop) {
                    // Stop continuous listening on the explicit "Stop" command.
                    isUserStopped = true
                    callback.onListeningStateChanged(false)
                    return
                }
            } else {
                callback.onCommand(VoiceCommandType.Unknown(matches.firstOrNull() ?: ""), matches.firstOrNull() ?: "")
            }

            // After results, restart quickly for continuous listening.
            networkRetryCount = 0
            restartListeningIfNeeded(RestartReason.RESULTS, delayMs = DEFAULT_RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    enum class RestartReason { END_OF_SPEECH, RESULTS, RETRY, NETWORK_ERROR }

    private data class CommandMatch(val command: VoiceCommandType, val rawText: String)

    fun startListening(locale: Locale = Locale.getDefault()) {
        isUserStopped = false

        mainHandler.post {
            ensureRecognizer()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Tighter settings can reduce perceived latency.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }

            try {
                // Cancel any pending restart.
                restartRunnable?.let { mainHandler.removeCallbacks(it) }
                restartRunnable = null

                speechRecognizer?.startListening(intent)
                callback.onListeningStateChanged(true)
            } catch (e: SecurityException) {
                isUserStopped = true
                callback.onListeningStateChanged(false)
                callback.onError("Missing RECORD_AUDIO permission")
            } catch (e: Exception) {
                isUserStopped = true
                callback.onListeningStateChanged(false)
                callback.onError("Failed to start listening: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun stopListening() {
        isUserStopped = true
        networkRetryCount = 0
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
                // Ignore.
            }
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
                // Ignore.
            }
            callback.onListeningStateChanged(false)
        }
    }

    fun destroy() {
        stopListening()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
                // Ignore.
            }
            speechRecognizer = null
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).also { sr ->
            sr.setRecognitionListener(recognitionListener)
        }
    }

    private fun restartListeningIfNeeded(
        reason: RestartReason,
        delayMs: Long = DEFAULT_RESTART_DELAY_MS
    ) {
        if (isUserStopped) return

        // Remove any previous restart.
        restartRunnable?.let { mainHandler.removeCallbacks(it) }

        restartRunnable = Runnable {
            if (!isUserStopped) {
                // We do not attempt to reuse locale from here; SpeechRecognizer works with system language.
                try {
                    val locale = Locale.getDefault()
                    startListening(locale)
                    Log.d(LOG_TAG, "Restart listening (reason=$reason)")
                } catch (_: Exception) {
                    // If restart fails, onError will likely handle it.
                }
            }
        }

        mainHandler.postDelayed(restartRunnable as Runnable, delayMs)
    }

    private fun matchCommand(possiblePhrases: List<String>): CommandMatch? {
        if (possiblePhrases.isEmpty()) return null

        // Normalize all candidates first.
        val normalizedInput = possiblePhrases.map { normalize(it) }

        // Strong keyword rules (fast + robust for noisy ASR).
        for ((index, norm) in normalizedInput.withIndex()) {
            if (norm.contains("stop")) return CommandMatch(VoiceCommandType.Stop, possiblePhrases[index])
            val hasRead = norm.contains("read")
            val hasText = norm.contains("text")
            if (hasRead && hasText) return CommandMatch(VoiceCommandType.ReadText, possiblePhrases[index])
            val hasFront = norm.contains("front")
            val hasWhat = norm.contains("what")
            if (hasFront && hasWhat) return CommandMatch(VoiceCommandType.WhatsInFront, possiblePhrases[index])
        }

        // Fuzzy matching fallback.
        val canonical = mapOf(
            VoiceCommandType.WhatsInFront to listOf(
                "what is in front",
                "whats in front",
                "what's in front",
                "what s in front",
                "what in front"
            ),
            VoiceCommandType.ReadText to listOf(
                "read text",
                "read the text",
                "read out text",
                "read text please"
            ),
            VoiceCommandType.Stop to listOf(
                "stop",
                "stop listening",
                "cancel"
            )
        )

        var best: CommandMatch? = null
        var bestScore = 0f

        for (i in normalizedInput.indices) {
            val input = normalizedInput[i]
            val raw = possiblePhrases[i]
            for ((command, candidates) in canonical) {
                val score = candidates
                    .map { levenshteinSimilarity(input, normalize(it)) }
                    .maxOrNull() ?: 0f

                if (score > bestScore) {
                    bestScore = score
                    best = CommandMatch(command, raw)
                }
            }
        }

        // Threshold to avoid speaking wrong commands.
        return if (best != null && bestScore >= FUZZY_MATCH_THRESHOLD) best else null
    }

    private fun normalize(s: String): String {
        return s
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshteinSimilarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f

        val dist = levenshteinDistance(a, b)
        val maxLen = max(a.length, b.length).coerceAtLeast(1)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        val dp = IntArray((n + 1) * (m + 1))
        fun idx(i: Int, j: Int) = i * (m + 1) + j

        for (i in 0..n) dp[idx(i, 0)] = i
        for (j in 0..m) dp[idx(0, j)] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                val deletion = dp[idx(i - 1, j)] + 1
                val insertion = dp[idx(i, j - 1)] + 1
                val substitution = dp[idx(i - 1, j - 1)] + cost
                dp[idx(i, j)] = minOf(deletion, insertion, substitution)
            }
        }

        return dp[idx(n, m)]
    }

    companion object {
        private const val LOG_TAG = "VoiceCommandManager"
        private const val DEFAULT_RESTART_DELAY_MS = 150L
        private const val FUZZY_MATCH_THRESHOLD = 0.58f
    }
}

