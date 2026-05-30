package com.example.skybuddy.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight info about an installed TTS engine. */
data class TtsEngineInfo(
    val label: String,
    val packageName: String
)

/** Info about a single TTS voice. */
data class VoiceInfo(
    val name: String,
    val locale: Locale,
    val requiresNetwork: Boolean,
    val quality: Int
) {
    /** Human-friendly label, e.g. "English (US) — en-us-x-tpd-local" */
    val displayLabel: String
        get() {
            val lang = locale.displayLanguage
            val country = locale.displayCountry.ifEmpty { null }
            val prefix = if (country != null) "$lang ($country)" else lang
            return prefix
        }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("skybuddy_settings", Context.MODE_PRIVATE)

    // ── TTS core ────────────────────────────────────────────────
    private val _ttsLanguage = MutableStateFlow(prefs.getString("tts_language", "en-US") ?: "en-US")
    val ttsLanguage: StateFlow<String> = _ttsLanguage.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(prefs.getBoolean("tts_enabled", true))
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    // ── TTS extended ────────────────────────────────────────────
    private val _speechRate = MutableStateFlow(prefs.getFloat("tts_speech_rate", 1.0f))
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _pitch = MutableStateFlow(prefs.getFloat("tts_pitch", 1.0f))
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow(prefs.getString("tts_voice_name", null))
    val selectedVoiceName: StateFlow<String?> = _selectedVoiceName.asStateFlow()

    // ── STT ─────────────────────────────────────────────────────
    private val _sttLanguage = MutableStateFlow(prefs.getString("stt_language", "en-US") ?: "en-US")
    val sttLanguage: StateFlow<String> = _sttLanguage.asStateFlow()

    private val _isSttEnabled = MutableStateFlow(prefs.getBoolean("stt_enabled", true))
    val isSttEnabled: StateFlow<Boolean> = _isSttEnabled.asStateFlow()

    private val _preferOfflineStt = MutableStateFlow(prefs.getBoolean("prefer_offline_stt", true))
    val preferOfflineStt: StateFlow<Boolean> = _preferOfflineStt.asStateFlow()

    // ── AI Engine ───────────────────────────────────────────────
    private val _aiEngineMode = MutableStateFlow(prefs.getString("ai_engine_mode", "auto") ?: "auto")
    val aiEngineMode: StateFlow<String> = _aiEngineMode.asStateFlow()

    // ── Real device queries ─────────────────────────────────────

    /** Query installed TTS engines from the Android system. */
    fun queryInstalledTtsEngines(tts: AndroidTts): List<TtsEngineInfo> {
        return try {
            tts.engines.map { engine ->
                TtsEngineInfo(
                    label = engine.label,
                    packageName = engine.name
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Query actually installed voices from the TTS engine. */
    fun queryAvailableVoices(tts: AndroidTts): List<VoiceInfo> {
        return try {
            tts.voices?.map { voice ->
                VoiceInfo(
                    name = voice.name,
                    locale = voice.locale,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                    quality = voice.quality
                )
            }?.sortedWith(compareBy({ it.locale.displayLanguage }, { it.requiresNetwork }))
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Get distinct language tags from available voices. */
    fun queryAvailableLanguages(tts: AndroidTts): List<String> {
        return try {
            tts.availableLanguages
                ?.map { it.toLanguageTag() }
                ?.distinct()
                ?.sorted()
                ?: listOf("en-US")
        } catch (_: Exception) {
            listOf("en-US")
        }
    }

    // ── Setters ─────────────────────────────────────────────────

    fun setTtsLanguage(lang: String) {
        prefs.edit().putString("tts_language", lang).apply()
        _ttsLanguage.value = lang
    }

    fun setSttLanguage(lang: String) {
        prefs.edit().putString("stt_language", lang).apply()
        _sttLanguage.value = lang
    }

    fun setTtsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_enabled", enabled).apply()
        _isTtsEnabled.value = enabled
    }

    fun setSttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("stt_enabled", enabled).apply()
        _isSttEnabled.value = enabled
    }

    fun setSpeechRate(rate: Float) {
        prefs.edit().putFloat("tts_speech_rate", rate).apply()
        _speechRate.value = rate
    }

    fun setPitch(pitch: Float) {
        prefs.edit().putFloat("tts_pitch", pitch).apply()
        _pitch.value = pitch
    }

    fun setSelectedVoiceName(name: String?) {
        prefs.edit().putString("tts_voice_name", name).apply()
        _selectedVoiceName.value = name
    }

    fun setPreferOfflineStt(prefer: Boolean) {
        prefs.edit().putBoolean("prefer_offline_stt", prefer).apply()
        _preferOfflineStt.value = prefer
    }

    fun setAiEngineMode(mode: String) {
        prefs.edit().putString("ai_engine_mode", mode).apply()
        _aiEngineMode.value = mode
    }
}
