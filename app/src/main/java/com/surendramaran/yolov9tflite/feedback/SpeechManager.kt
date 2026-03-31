package com.surendramaran.yolov9tflite.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class SpeechManager(
    private val scope: CoroutineScope,
    private val gapMs: Long = 2000L,
    private val defaultCooldownMs: Long = 4000L
) {
    data class SpeechItem(
        val text: String,
        val cooldownKey: String,
        val cooldownMs: Long
    )

    private val queue = Channel<SpeechItem>(capacity = Channel.UNLIMITED)
    private val lastAnnouncedAt = mutableMapOf<String, Long>()
    private val queuedKeys = mutableSetOf<String>()

    private val _speechEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val speechEvents: SharedFlow<String> = _speechEvents

    init {
        scope.launch {
            for (item in queue) {
                queuedKeys.remove(item.cooldownKey)

                val now = System.currentTimeMillis()
                val lastTs = lastAnnouncedAt[item.cooldownKey] ?: 0L
                val inCooldown = (now - lastTs) < item.cooldownMs
                if (inCooldown) continue

                _speechEvents.emit(item.text)
                lastAnnouncedAt[item.cooldownKey] = now
                delay(gapMs)
            }
        }
    }

    fun enqueue(text: String, cooldownKey: String, cooldownMs: Long = defaultCooldownMs): Boolean {
        // Prevent queue flood for same repeating item across frames.
        val now = System.currentTimeMillis()
        val lastTs = lastAnnouncedAt[cooldownKey] ?: 0L
        val inCooldown = (now - lastTs) < cooldownMs
        if (inCooldown) return false

        if (queuedKeys.contains(cooldownKey)) return false
        queuedKeys.add(cooldownKey)
        queue.trySend(SpeechItem(text = text, cooldownKey = cooldownKey, cooldownMs = cooldownMs))
        return true
    }

    fun clearQueue() {
        // Drain without using experimental Channel.isEmpty.
        while (true) {
            val result = queue.tryReceive()
            if (!result.isSuccess) break
        }
        queuedKeys.clear()
    }
}

