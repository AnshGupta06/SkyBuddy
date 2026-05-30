package com.example.skybuddy.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skybuddy.data.db.FlightEntity
import com.example.skybuddy.data.db.LuggageEntity
import com.example.skybuddy.data.db.ReceiptEntity
import com.example.skybuddy.data.db.TimelineEventDao
import com.example.skybuddy.data.db.TimelineEventEntity
import com.example.skybuddy.data.repository.FlightRepository
import com.example.skybuddy.data.repository.ChecklistRepository
import com.example.skybuddy.data.repository.ChecklistItemEntity
import com.example.skybuddy.domain.state.JourneyManager
import com.example.skybuddy.ui.journey.JourneyPhase
import com.example.skybuddy.domain.usecase.ChatTurnUseCase
import com.example.skybuddy.domain.usecase.DescribeLuggageUseCase
import com.example.skybuddy.domain.usecase.RecognizeReceiptUseCase
import com.example.skybuddy.location.IndoorLocationManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import com.example.skybuddy.data.repository.SettingsRepository

data class ChatUiState(
    val input: String = "",
    val isThinking: Boolean = false,
    val toolStatusLabel: String? = null,
    /** Accumulated tokens shown during streaming. */
    val streamingResponse: String = "",
    /** True while the LLM is emitting tokens. */
    val isStreamingResponse: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val application: Application,
    private val flightRepository: FlightRepository,
    private val timelineEventDao: TimelineEventDao,
    private val chatTurn: ChatTurnUseCase,
    private val recognizeReceipt: RecognizeReceiptUseCase,
    private val describeLuggage: DescribeLuggageUseCase,
    private val journeyManager: JourneyManager,
    private val indoorLocationManager: IndoorLocationManager,
    private val settingsRepository: SettingsRepository,
    private val checklistRepository: ChecklistRepository,
    val voiceController: VoiceController
) : ViewModel() {

    val destinationNodeId = indoorLocationManager.destinationNodeId

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    val timelineEvents: StateFlow<List<TimelineEventEntity>> = timelineEventDao.getAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var pinnedFlightNumber: String? = null
    private val _pinnedFlight = MutableStateFlow<FlightEntity?>(null)
    val pinnedFlight: StateFlow<FlightEntity?> = _pinnedFlight.asStateFlow()

    val currentPhase: StateFlow<JourneyPhase> = journeyManager.currentPhase

    private val _checklistItems = MutableStateFlow<List<ChecklistItemEntity>>(emptyList())
    val checklistItems: StateFlow<List<ChecklistItemEntity>> = _checklistItems.asStateFlow()

    // TTS tracking
    private var ttsSpokenUpTo = 0
    private var streamingTtsHandledTurn = false

    fun setFlightContext(flightNumber: String?) {
        if (flightNumber.isNullOrBlank() || flightNumber == "help" || flightNumber == "timeline") return
        if (flightNumber == pinnedFlightNumber) return
        pinnedFlightNumber = flightNumber
        viewModelScope.launch {
            flightRepository.observeFlight(flightNumber).collect { flight ->
                _pinnedFlight.value = flight
            }
        }
        viewModelScope.launch {
            checklistRepository.observeItems(flightNumber).collect { items ->
                _checklistItems.value = items
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            timelineEventDao.clearAll()
            _state.update { it.copy(streamingResponse = "", isThinking = false, isStreamingResponse = false, toolStatusLabel = null) }
            voiceController.stopSpeaking()
        }
    }

    var currentEstimatedTimeMinutes: Int? = null

    fun onInputChanged(value: String) = _state.update { it.copy(input = value) }

    fun toggleChecklistItem(id: String, completed: Boolean) {
        val flight = pinnedFlightNumber ?: return
        checklistRepository.setCompleted(flight, id, completed)
    }

    fun ensureChecklistVisible() {
        if (currentPhase.value != JourneyPhase.HOME) return
        if (checklistItems.value.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            val recent = timelineEventDao.getRecentEvents(1)
            if (recent.isEmpty() || recent.first().uiComponentType != "CHECKLIST_CARD") {
                timelineEventDao.insert(
                    TimelineEventEntity(
                        timestamp = System.currentTimeMillis(),
                        role = "SYSTEM_SPATIAL",
                        uiComponentType = "CHECKLIST_CARD",
                        content = ""
                    )
                )
            }
        }
    }

    fun advancePhase(newPhase: JourneyPhase) {
        journeyManager.setPhase(newPhase)
    }

    private fun getSpatialContext(): String {
        val x = indoorLocationManager.currentX.value
        val y = indoorLocationManager.currentY.value
        val phase = journeyManager.currentPhase.value.displayName
        val outLang = settingsRepository.ttsLanguage.value
        val inLang = settingsRepository.sttLanguage.value
        val estTimeStr = currentEstimatedTimeMinutes?.let { " Estimated time to destination: $it minutes." } ?: ""
        
        return "[SYSTEM CONTEXT: User is at X:$x, Y:$y. State is $phase.$estTimeStr " +
               "The user's preferred input language is $inLang and preferred output language is $outLang. " +
               "ALWAYS respond in $outLang. If the user speaks in $inLang, translate your knowledge to $outLang.]"
    }

    private fun shouldSpeak(): Boolean = settingsRepository.isTtsEnabled.value

    private fun speakNewLinesIfNeeded(text: String) {
        if (!shouldSpeak()) return
        while (true) {
            val nl = text.indexOf('\n', ttsSpokenUpTo)
            if (nl == -1) break
            val line = text.substring(ttsSpokenUpTo, nl).trim()
            if (line.isNotEmpty()) {
                if (ttsSpokenUpTo == 0) voiceController.speak(line)
                else voiceController.speakQueued(line)
            }
            ttsSpokenUpTo = nl + 1
        }
    }

    private fun speakRemainder(text: String) {
        if (!shouldSpeak()) return
        if (ttsSpokenUpTo < text.length) {
            val rem = text.substring(ttsSpokenUpTo).trim()
            if (rem.isNotEmpty()) {
                if (ttsSpokenUpTo == 0) voiceController.speak(rem)
                else voiceController.speakQueued(rem)
            }
        }
    }

    fun sendText(hiddenContext: String? = null): String? {
        val prompt = _state.value.input.trim()
        if (prompt.isBlank() || _state.value.isThinking) return null

        viewModelScope.launch {
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis(),
                role = "USER",
                uiComponentType = "TEXT",
                content = prompt
            ))

            ttsSpokenUpTo = 0
            streamingTtsHandledTurn = settingsRepository.isTtsEnabled.value
            _state.update {
                it.copy(
                    input = "",
                    isThinking = true,
                    toolStatusLabel = null,
                    streamingResponse = "",
                    isStreamingResponse = false
                )
            }

            val spatialContext = getSpatialContext()
            val finalContext = if (hiddenContext != null) "$spatialContext\n$hiddenContext" else spatialContext
            val query = "$finalContext\n$prompt"

            val result = chatTurn.textStreaming(query, pinnedFlightNumber, { toolLabel ->
                _state.update { it.copy(toolStatusLabel = toolLabel) }
            }) { chunk ->
                _state.update {
                    it.copy(
                        isStreamingResponse = true,
                        streamingResponse = it.streamingResponse + chunk,
                        toolStatusLabel = null
                    )
                }
                speakNewLinesIfNeeded(_state.value.streamingResponse)
            }

            speakRemainder(_state.value.streamingResponse)
            applyTurn(result.response, result.flight, result.luggage, result.receipts, result.checklistModified)
        }
        return prompt
    }

    fun sendImage(prompt: String, bitmap: Bitmap, hiddenContext: String? = null) {
        if (_state.value.isThinking) return
        val actualPrompt = prompt.ifBlank { "Image" }

        viewModelScope.launch {
            // Save bitmap to internal storage so it persists in the chat
            val savedUri = saveBitmapToInternal(bitmap)

            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis(),
                role = "USER",
                uiComponentType = "TEXT",
                content = actualPrompt,
                localImageUri = savedUri
            ))

            streamingTtsHandledTurn = false
            _state.update {
                it.copy(
                    input = "",
                    isThinking = true,
                    toolStatusLabel = null,
                    streamingResponse = "",
                    isStreamingResponse = false
                )
            }

            val spatialContext = getSpatialContext()
            val finalContext = if (hiddenContext != null) "$spatialContext\n$hiddenContext" else spatialContext
            val query = "$finalContext\n$actualPrompt"
            val result = chatTurn.image(query, bitmap, pinnedFlightNumber) { toolLabel ->
                _state.update { it.copy(toolStatusLabel = toolLabel) }
            }

            applyTurn(result.response, result.flight, result.luggage, result.receipts, result.checklistModified)
        }
    }

    /**
     * Saves a Bitmap to internal storage under a `chat_images/` directory.
     * Returns the file URI as a String, or null if saving fails.
     */
    private suspend fun saveBitmapToInternal(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(application.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Failed to save image", e)
            null
        }
    }

    fun captureReceipt(bitmap: Bitmap) {
        viewModelScope.launch {
            val text = recognizeReceipt(bitmap).ifBlank { "No text detected on receipt" }
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis(),
                role = "GEMMA",
                uiComponentType = "TEXT",
                content = "Receipt: $text"
            ))
        }
    }

    fun captureLuggage(bitmap: Bitmap) {
        viewModelScope.launch {
            val description = describeLuggage(bitmap)
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis(),
                role = "GEMMA",
                uiComponentType = "TEXT",
                content = "Saved bag: $description"
            ))
        }
    }

    fun didStreamingTtsHandle(): Boolean = streamingTtsHandledTurn

    private suspend fun applyTurn(
        response: String,
        flight: FlightEntity?,
        luggage: LuggageEntity?,
        receipts: List<ReceiptEntity>?,
        checklistModified: Boolean
    ) {
        timelineEventDao.insert(TimelineEventEntity(
            timestamp = System.currentTimeMillis(),
            role = "GEMMA",
            uiComponentType = "TEXT",
            content = response
        ))

        flight?.let {
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis() + 1,
                role = "GEMMA",
                uiComponentType = "FLIGHT_CARD",
                content = moshi.adapter(FlightEntity::class.java).toJson(it)
            ))
        }

        luggage?.let {
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis() + 2,
                role = "GEMMA",
                uiComponentType = "LUGGAGE_CARD",
                content = moshi.adapter(LuggageEntity::class.java).toJson(it)
            ))
        }

        if (!receipts.isNullOrEmpty()) {
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, ReceiptEntity::class.java)
            val adapter: com.squareup.moshi.JsonAdapter<List<ReceiptEntity>> = moshi.adapter(type)
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis() + 3,
                role = "GEMMA",
                uiComponentType = "RECEIPT_CARD",
                content = adapter.toJson(receipts)
            ))
        }

        if (checklistModified) {
            timelineEventDao.insert(TimelineEventEntity(
                timestamp = System.currentTimeMillis() + 4,
                role = "GEMMA",
                uiComponentType = "CHECKLIST_CARD",
                content = "" // Checklist items are loaded from ViewModel state
            ))
        }

        _state.update {
            it.copy(
                isThinking = false,
                toolStatusLabel = null,
                streamingResponse = "",
                isStreamingResponse = false
            )
        }
    }
}
