package com.surendramaran.yolov9tflite.voice

sealed class VoiceCommandType {
    data object WhatsInFront : VoiceCommandType()
    data object ReadText : VoiceCommandType()
    data object Stop : VoiceCommandType()
    data class Unknown(val rawText: String) : VoiceCommandType()
}

