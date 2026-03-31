package com.surendramaran.yolov9tflite.camera

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraConnectionViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        data object Searching : UiState()
        data class NoCamera(val reason: String? = null) : UiState()
        data class CameraFound(
            val cameraIds: List<String>,
            val lensFacing: Int? // null when unknown
        ) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Searching)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun scanForCameras() {
        _uiState.value = UiState.Searching

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val appContext = getApplication<Application>().applicationContext
                val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                val ids = cameraManager.cameraIdList?.toList().orEmpty()
                if (ids.isEmpty()) {
                    _uiState.value = UiState.NoCamera("No camera IDs returned by the system.")
                    return@launch
                }

                // Basic lens facing information (useful for choosing CameraX selector).
                val firstId = ids.first()
                val characteristics = cameraManager.getCameraCharacteristics(firstId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                _uiState.value = UiState.CameraFound(
                    cameraIds = ids,
                    lensFacing = lensFacing
                )
            } catch (e: SecurityException) {
                _uiState.value = UiState.NoCamera("Camera permission is missing.")
            } catch (e: CameraAccessException) {
                _uiState.value = UiState.NoCamera("Camera access failed: ${e.message}")
            } catch (e: Exception) {
                _uiState.value = UiState.NoCamera("Unexpected error: ${e.message}")
            }
        }
    }
}

