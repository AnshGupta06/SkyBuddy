package com.example.skybuddy.ui.settings

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skybuddy.ai.AccelerationCompat
import com.example.skybuddy.ai.LiteRtLlmEngine

import com.example.skybuddy.audio.TextToSpeechService
import com.example.skybuddy.audio.TtsStatus
import com.example.skybuddy.data.network.NetworkMonitor
import com.example.skybuddy.data.repository.SettingsRepository
import com.example.skybuddy.data.repository.TtsEngineInfo
import com.example.skybuddy.data.repository.VoiceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class SettingsUiState(
    // TTS
    val isTtsEnabled: Boolean = true,
    val ttsLanguage: String = "en-US",
    val availableLanguages: List<String> = listOf("en-US"),
    val availableVoices: List<VoiceInfo> = emptyList(),
    val selectedVoiceName: String? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val ttsEngines: List<TtsEngineInfo> = emptyList(),
    val ttsStatus: TtsStatus = TtsStatus.Initializing,

    // STT
    val isSttEnabled: Boolean = true,
    val sttLanguage: String = "en-US",
    val availableSttLocales: List<String> = listOf("en-US"),
    val preferOfflineStt: Boolean = true,
    val isSttAvailable: Boolean = true,


    // Bluetooth / Beacon
    val isBluetoothEnabled: Boolean = false,

    // Device info
    val modelFileName: String = LiteRtLlmEngine.MODEL_FILE,
    val modelFileSize: String = "—",
    val gpuAvailable: Boolean = false,
    val appVersion: String = "1.0",
    val deviceModel: String = Build.MODEL,
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val ttsService: TextToSpeechService,
    private val networkMonitor: NetworkMonitor,
    private val accelerationCompat: AccelerationCompat
) : ViewModel() {

    // Probe TTS engine for real voices — created lazily, cleaned up in onCleared
    private var probeTts: AndroidTts? = null
    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    private val _languages = MutableStateFlow(listOf("en-US"))
    private val _engines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    private val _sttLocales = MutableStateFlow(listOf("en-US"))

    init {
        // Initialize a probe TTS to query voices/engines
        probeTts = AndroidTts(appContext) { status ->
            if (status == AndroidTts.SUCCESS) {
                val tts = probeTts ?: return@AndroidTts
                _voices.value = repository.queryAvailableVoices(tts)
                _languages.value = repository.queryAvailableLanguages(tts)
                _engines.value = repository.queryInstalledTtsEngines(tts)
            }
        }
        // Gather available STT locales
        querySttLocales()
    }

    private fun querySttLocales() {
        // SpeechRecognizer doesn't expose locale list easily;
        // we use a known set of supported locales and filter by device availability
        val common = listOf(
            "en-US", "en-GB", "en-IN", "hi-IN", "fr-FR", "de-DE",
            "es-ES", "it-IT", "ja-JP", "ko-KR", "zh-CN", "zh-TW",
            "pt-BR", "ru-RU", "ar-SA", "nl-NL", "pl-PL", "tr-TR"
        )
        _sttLocales.value = common.filter { tag ->
            try {
                val locale = Locale.forLanguageTag(tag)
                locale.displayLanguage.isNotEmpty()
            } catch (_: Exception) { false }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            repository.isTtsEnabled,
            repository.ttsLanguage,
            repository.speechRate,
            repository.pitch,
            repository.selectedVoiceName
        ) { ttsEnabled, ttsLang, rate, pitch, voiceName ->
            TtsPrefs(ttsEnabled, ttsLang, rate, pitch, voiceName)
        },
        combine(
            repository.isSttEnabled,
            repository.sttLanguage,
            repository.preferOfflineStt
        ) { sttEnabled, sttLang, offlineStt ->
            SttPrefs(sttEnabled, sttLang, offlineStt)
        },
        combine(
            ttsService.status,
            _voices,
            _languages,
            _engines,
            _sttLocales
        ) { ttsStatus, voices, languages, engines, sttLocales ->
            DeviceState(ttsStatus, voices, languages, engines, sttLocales)
        },
        networkMonitor.observe()
    ) { ttsPrefs, sttPrefs, device, isOnline ->

        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btEnabled = try { btManager?.adapter?.isEnabled == true } catch (_: SecurityException) { false }

        val modelFile = File(appContext.filesDir, LiteRtLlmEngine.MODEL_FILE)
        val modelSize = if (modelFile.exists()) {
            val mb = modelFile.length() / (1024.0 * 1024.0)
            if (mb > 1024) String.format("%.1f GB", mb / 1024) else String.format("%.1f MB", mb)
        } else "Not found"



        val version = try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) { "1.0" }

        SettingsUiState(
            isTtsEnabled = ttsPrefs.ttsEnabled,
            ttsLanguage = ttsPrefs.ttsLang,
            availableLanguages = device.languages,
            availableVoices = device.voices,
            selectedVoiceName = ttsPrefs.voiceName,
            speechRate = ttsPrefs.rate,
            pitch = ttsPrefs.pitch,
            ttsEngines = device.engines,
            ttsStatus = device.ttsStatus,

            isSttEnabled = sttPrefs.sttEnabled,
            sttLanguage = sttPrefs.sttLang,
            availableSttLocales = device.sttLocales,
            preferOfflineStt = sttPrefs.offlineStt,
            isSttAvailable = SpeechRecognizer.isRecognitionAvailable(appContext),

            isBluetoothEnabled = btEnabled,

            modelFileName = LiteRtLlmEngine.MODEL_FILE,
            modelFileSize = modelSize,
            gpuAvailable = accelerationCompat.isGpuAvailable(),
            appVersion = version
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    // ── Actions ─────────────────────────────────────────────────

    fun setTtsEnabled(enabled: Boolean) = repository.setTtsEnabled(enabled)
    fun setTtsLanguage(lang: String) = repository.setTtsLanguage(lang)
    fun setSpeechRate(rate: Float) = repository.setSpeechRate(rate)
    fun setPitch(pitch: Float) = repository.setPitch(pitch)
    fun setSelectedVoiceName(name: String?) = repository.setSelectedVoiceName(name)

    fun setSttEnabled(enabled: Boolean) = repository.setSttEnabled(enabled)
    fun setSttLanguage(lang: String) = repository.setSttLanguage(lang)
    fun setPreferOfflineStt(prefer: Boolean) = repository.setPreferOfflineStt(prefer)



    /** Speak a sample sentence so the user can hear the current voice/rate/pitch. */
    fun previewTts() {
        ttsService.speak("Hello! I am SkyBuddy, your travel companion.")
    }

    fun refreshTtsVoices() {
        val tts = probeTts ?: return
        _voices.value = repository.queryAvailableVoices(tts)
        _languages.value = repository.queryAvailableLanguages(tts)
        _engines.value = repository.queryInstalledTtsEngines(tts)
    }

    /** Create an intent to open Android TTS system settings. */
    fun createTtsSettingsIntent(): Intent =
        Intent("com.android.settings.TTS_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    /** Create an intent to open Android Bluetooth settings. */
    fun createBluetoothSettingsIntent(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    /** Create an intent to open Android accessibility settings. */
    fun createAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    override fun onCleared() {
        super.onCleared()
        probeTts?.shutdown()
        probeTts = null
    }

    // ── Internal data holders for combine ───────────────────────
    private data class TtsPrefs(
        val ttsEnabled: Boolean,
        val ttsLang: String,
        val rate: Float,
        val pitch: Float,
        val voiceName: String?
    )
    private data class SttPrefs(
        val sttEnabled: Boolean,
        val sttLang: String,
        val offlineStt: Boolean
    )
    private data class DeviceState(
        val ttsStatus: TtsStatus,
        val voices: List<VoiceInfo>,
        val languages: List<String>,
        val engines: List<TtsEngineInfo>,
        val sttLocales: List<String>
    )
}
