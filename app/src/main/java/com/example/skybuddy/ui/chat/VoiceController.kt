package com.example.skybuddy.ui.chat

import com.example.skybuddy.audio.SpeechCallback
import com.example.skybuddy.audio.SpeechError
import com.example.skybuddy.audio.SpeechRecognizer
import com.example.skybuddy.audio.TextToSpeechService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VoiceEvent {
    data class Heard(val text: String) : VoiceEvent
    data class Error(val reason: SpeechError) : VoiceEvent
}

@Singleton
class VoiceController @Inject constructor(
    private val recognizer: SpeechRecognizer,
    private val tts: TextToSpeechService
) {
    private val _events = MutableStateFlow<VoiceEvent?>(null)
    val events: StateFlow<VoiceEvent?> = _events.asStateFlow()
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    val ttsStatus get() = tts.status

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        if (newMuted) tts.stop() // Stop any current speech immediately
    }

    fun startListening() {
        _isListening.value = true
        recognizer.start(object : SpeechCallback {
            override fun onResult(text: String) {
                _isListening.value = false
                _events.value = VoiceEvent.Heard(text)
            }
            override fun onError(reason: SpeechError) {
                _isListening.value = false
                _events.value = VoiceEvent.Error(reason)
            }
        })
    }

    fun stopListening() {
        recognizer.stop()
        _isListening.value = false
    }

    fun speak(text: String) {
        if (!_isMuted.value) tts.speak(text)
    }

    /** Queue text after the current utterance (for line-by-line streaming TTS). */
    fun speakQueued(text: String) {
        if (!_isMuted.value) tts.speakQueued(text)
    }

    /** Stop any current and queued speech. */
    fun stopSpeaking() = tts.stop()

    fun consume() {
        _events.value = null
        _isListening.value = false
    }

    fun shutdown() {
        recognizer.destroy()
    }
}
