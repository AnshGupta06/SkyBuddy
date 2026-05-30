package com.example.skybuddy.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.example.skybuddy.data.repository.SettingsRepository

interface SpeechRecognizer {
    fun isAvailable(): Boolean
    fun start(callback: SpeechCallback, preferOffline: Boolean = true)
    fun stop()
    fun destroy()
}

interface SpeechCallback {
    fun onResult(text: String)
    fun onError(reason: SpeechError)
}

sealed interface SpeechError {
    data object OfflineModelMissing : SpeechError
    data class Other(val code: Int) : SpeechError
    data object Unavailable : SpeechError
}

@Singleton
class AndroidSpeechRecognizerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : SpeechRecognizer {

    private var recognizer: AndroidSpeechRecognizer? = null
    private var isListening = false

    override fun isAvailable(): Boolean = AndroidSpeechRecognizer.isRecognitionAvailable(context)

    override fun start(callback: SpeechCallback, preferOffline: Boolean) {
        if (!isAvailable() || !settingsRepository.isSttEnabled.value) {
            callback.onError(SpeechError.Unavailable)
            return
        }
        if (isListening) return

        val instance = recognizer ?: AndroidSpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
        }
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                if (error == ERROR_LANGUAGE_UNAVAILABLE) {
                    callback.onError(SpeechError.OfflineModelMissing)
                } else {
                    callback.onError(SpeechError.Other(error))
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let(callback::onResult)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settingsRepository.sttLanguage.value)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        instance.startListening(intent)
        isListening = true
    }

    override fun stop() {
        recognizer?.stopListening()
        isListening = false
    }

    override fun destroy() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }

    companion object {
        // android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE = 13
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
