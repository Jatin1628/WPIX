package com.surendramaran.yolov9tflite.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.surendramaran.yolov9tflite.R
import com.surendramaran.yolov9tflite.databinding.ActivityCameraConnectionBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CameraConnectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraConnectionBinding
    private lateinit var viewModel: CameraConnectionViewModel

    private var cameraProvider: ProcessCameraProvider? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.scanForCameras()
        } else {
            showNoCamera("Camera permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[CameraConnectionViewModel::class.java]
        observeState()

        if (hasCameraPermission()) {
            viewModel.scanForCameras()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.retryButton.setOnClickListener {
            viewModel.scanForCameras()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    CameraConnectionViewModel.UiState.Searching -> showSearching()
                    is CameraConnectionViewModel.UiState.NoCamera -> showNoCamera(state.reason)
                    is CameraConnectionViewModel.UiState.CameraFound -> showConnectedAndPreview(state)
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showSearching() {
        Log.d(TAG, "Searching for cameras...")
        binding.statusText.text = "Searching..."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.previewView.visibility = View.GONE

        cameraProvider?.unbindAll()
    }

    private fun showNoCamera(reason: String?) {
        Log.d(TAG, "No camera found. reason=$reason")
        binding.statusText.text = "No camera found"
        binding.progressBar.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.previewView.visibility = View.GONE

        cameraProvider?.unbindAll()
    }

    private fun showConnectedAndPreview(
        state: CameraConnectionViewModel.UiState.CameraFound
    ) {
        Log.d(TAG, "Camera connected. cameras=${state.cameraIds.size}, lensFacing=${state.lensFacing}")
        binding.statusText.text = "Camera Connected"
        binding.progressBar.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE

        // Best effort: bind a preview using CameraX.
        bindPreviewWithBestEffort()
    }

    private fun bindPreviewWithBestEffort() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            try {
                cameraProvider?.unbindAll()

                val preview = Preview.Builder().build().also { p ->
                    p.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                // Use back camera first, then front as fallback.
                val selectorBack = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.bindToLifecycle(this, selectorBack, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind back camera preview, trying front.", e)
                try {
                    cameraProvider?.unbindAll()
                    val preview = Preview.Builder().build().also { p ->
                        p.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }
                    val selectorFront = CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider?.bindToLifecycle(this, selectorFront, preview)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to bind any camera preview.", e2)
                    showNoCamera("Camera exists but preview cannot be bound.")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }

    companion object {
        private const val TAG = "CameraConnection"
    }
}

