package com.example.skybuddy.audio

import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.example.skybuddy.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

sealed interface TtsStatus {
    data object Initializing : TtsStatus
    data class Ready(val offline: Boolean) : TtsStatus
    data class Failed(val message: String) : TtsStatus
}

interface TextToSpeechService {
    val status: StateFlow<TtsStatus>
    fun speak(text: String)
    /** Queue text after any currently-speaking utterance instead of flushing. */
    fun speakQueued(text: String)
    fun stop()
    fun shutdown()
}

@Singleton
class AndroidTextToSpeech @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository
) : TextToSpeechService, AndroidTextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private val _status = MutableStateFlow<TtsStatus>(TtsStatus.Initializing)
    override val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private var tts: AndroidTextToSpeech? = null
    @Volatile private var initialized = false

    init {
        tts = AndroidTextToSpeech(context, this)
        serviceScope.launch {
            settingsRepository.ttsLanguage.collectLatest { lang ->
                if (initialized) {
                    val locale = Locale.forLanguageTag(lang)
                    tts?.language = locale
                }
            }
        }
        serviceScope.launch {
            settingsRepository.speechRate.collectLatest { rate ->
                if (initialized) tts?.setSpeechRate(rate)
            }
        }
        serviceScope.launch {
            settingsRepository.pitch.collectLatest { pitch ->
                if (initialized) tts?.setPitch(pitch)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != AndroidTextToSpeech.SUCCESS) {
            _status.value = TtsStatus.Failed("TTS engine init failed (code $status)")
            Log.e(TAG, "Initialization failed: $status")
            return
        }
        try {
            val offlineVoice = tts?.voices?.firstOrNull {
                it.locale.language == Locale.US.language && !it.isNetworkConnectionRequired
            }
            if (offlineVoice != null) {
                tts?.voice = offlineVoice
                initialized = true
                _status.value = TtsStatus.Ready(offline = true)
            } else {
                val result = tts?.setLanguage(Locale.US)
                if (result == AndroidTextToSpeech.LANG_MISSING_DATA ||
                    result == AndroidTextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    _status.value = TtsStatus.Failed("Missing offline language data")
                } else {
                    initialized = true
                    _status.value = TtsStatus.Ready(offline = false)
                }
            }
        } catch (t: Throwable) {
            tts?.setLanguage(Locale.US)
            initialized = true
            _status.value = TtsStatus.Ready(offline = false)
        }
    }

    override fun speak(text: String) {
        if (!initialized || !settingsRepository.isTtsEnabled.value) return
        val locale = Locale.forLanguageTag(settingsRepository.ttsLanguage.value)
        tts?.language = locale
        tts?.setSpeechRate(settingsRepository.speechRate.value)
        tts?.setPitch(settingsRepository.pitch.value)
        tts?.speak(text, AndroidTextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun speakQueued(text: String) {
        if (!initialized || !settingsRepository.isTtsEnabled.value) return
        val locale = Locale.forLanguageTag(settingsRepository.ttsLanguage.value)
        tts?.language = locale
        tts?.setSpeechRate(settingsRepository.speechRate.value)
        tts?.setPitch(settingsRepository.pitch.value)
        tts?.speak(text, AndroidTextToSpeech.QUEUE_ADD, null, null)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }

    companion object { private const val TAG = "AndroidTextToSpeech" }
}
