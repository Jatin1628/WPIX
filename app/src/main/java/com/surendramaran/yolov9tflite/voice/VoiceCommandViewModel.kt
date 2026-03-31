package com.surendramaran.yolov9tflite.voice

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class VoiceCommandViewModel(application: Application) : AndroidViewModel(application),
    VoiceCommandManager.Callback {

    private val audioPermission = android.Manifest.permission.RECORD_AUDIO

    private val _isListening = MutableLiveData(false)
    val isListening: LiveData<Boolean> = _isListening

    private val _commandText = MutableLiveData<String>("")
    val commandText: LiveData<String> = _commandText

    private val _commandEvent = MutableLiveData<Event<VoiceCommandType>>()
    val commandEvent: LiveData<Event<VoiceCommandType>> = _commandEvent

    private val _errorText = MutableLiveData<String?>()
    val errorText: LiveData<String?> = _errorText

    private val manager = VoiceCommandManager(
        context = application.applicationContext,
        callback = this
    )

    fun startListening() {
        if (!hasAudioPermission()) {
            _errorText.value = "Microphone permission (RECORD_AUDIO) is required."
            _isListening.value = false
            return
        }
        _errorText.value = null
        _commandText.value = ""
        manager.startListening()
    }

    fun stopListening() {
        manager.stopListening()
        _isListening.value = false
    }

    override fun onCleared() {
        super.onCleared()
        manager.destroy()
    }

    // VoiceCommandManager.Callback
    override fun onListeningStateChanged(isListening: Boolean) {
        _isListening.postValue(isListening)
    }

    override fun onCommand(command: VoiceCommandType, rawText: String) {
        val label = when (command) {
            VoiceCommandType.WhatsInFront -> "What's in front"
            VoiceCommandType.ReadText -> "Read text"
            VoiceCommandType.Stop -> "Stop"
            is VoiceCommandType.Unknown -> "Unknown"
        }

        _commandText.postValue("Heard: \"$rawText\" -> $label")
        _errorText.postValue(null)
        _commandEvent.postValue(Event(command))

        if (command is VoiceCommandType.Stop) {
            // Ensure listening remains stopped after the explicit voice command.
            stopListening()
        }
    }

    override fun onError(message: String) {
        _errorText.postValue(message)
        _isListening.postValue(false)
    }

    private fun hasAudioPermission(): Boolean {
        val ctx = getApplication<Application>()
        return ctx.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED
    }
}

