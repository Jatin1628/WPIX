package com.surendramaran.yolov9tflite.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.surendramaran.yolov9tflite.BoundingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DetectionFeedbackViewModel : ViewModel() {
    private val classifier = ObjectClassifier()
    private val walkingModeController = WalkingModeController()
    private val speechManager = SpeechManager(scope = viewModelScope)

    val walkingModeEnabled: StateFlow<Boolean> = walkingModeController.isWalkingModeEnabled
    val walkingModeButtonText: StateFlow<String> =
        walkingModeEnabled
            .map { enabled ->
                if (enabled) "Pause Walking Mode" else "Start Walking Mode"
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = "Start Walking Mode"
            )

    val speechEvents = speechManager.speechEvents

    private val _uiSummary = MutableStateFlow("No objects")
    val uiSummary: StateFlow<String> = _uiSummary

    fun toggleWalkingMode() {
        walkingModeController.toggle()
    }

    fun onDetections(boxes: List<BoundingBox>, forceAnnounce: Boolean = false) {
        if (boxes.isEmpty()) {
            _uiSummary.value = "No objects"
            return
        }

        val result = classifier.classify(boxes)
        val moving = result.moving
        val stationary = result.stationary

        val movingNames = moving.map { it.clsName.lowercase() }.distinct()
        val stationaryCount = stationary.size
        _uiSummary.value = "Moving: ${moving.size}, Stationary: $stationaryCount"

        var movingAnnouncementQueued = false
        if (movingNames.isNotEmpty()) {
            val movingLabel = pickPriorityMovingLabel(moving)
            movingAnnouncementQueued = speechManager.enqueue(
                text = "${movingLabel.replaceFirstChar { it.uppercase() }} detected",
                cooldownKey = "moving:$movingLabel",
                cooldownMs = if (forceAnnounce) 0L else 3500L
            )
        }

        // Walking mode: speech is limited to moving-object guidance only.
        if (walkingModeEnabled.value) return

        if (!walkingModeEnabled.value && stationaryCount > 0 && movingAnnouncementQueued) {
            speechManager.enqueue(
                text = "$stationaryCount stationary objects detected",
                cooldownKey = "stationary:$stationaryCount",
                cooldownMs = if (forceAnnounce) 0L else 4500L
            )
        }
    }

    fun stopAllSpeech() {
        speechManager.clearQueue()
    }

    private fun pickPriorityMovingLabel(movingBoxes: List<BoundingBox>): String {
        // Higher confidence first; if tie, larger area tends to be more relevant nearby.
        return movingBoxes
            .maxWithOrNull(
                compareBy<BoundingBox> { it.cnf }
                    .thenBy { it.w * it.h }
            )
            ?.clsName
            ?.lowercase()
            ?: "object"
    }
}

