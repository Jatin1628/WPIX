package com.surendramaran.yolov9tflite.feedback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WalkingModeController {
    private val _isWalkingModeEnabled = MutableStateFlow(false)
    val isWalkingModeEnabled: StateFlow<Boolean> = _isWalkingModeEnabled.asStateFlow()

    fun toggle() {
        _isWalkingModeEnabled.value = !_isWalkingModeEnabled.value
    }

    fun setEnabled(enabled: Boolean) {
        _isWalkingModeEnabled.value = enabled
    }
}

