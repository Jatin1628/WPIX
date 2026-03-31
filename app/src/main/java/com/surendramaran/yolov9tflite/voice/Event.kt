package com.surendramaran.yolov9tflite.voice

/**
 * Wrapper for data exposed via LiveData that represents a one-time event.
 * Prevents re-processing the same event after configuration changes.
 */
class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    fun getContentIfNotHandled(): T? {
        if (hasBeenHandled) return null
        hasBeenHandled = true
        return content
    }

    fun peekContent(): T = content
}

