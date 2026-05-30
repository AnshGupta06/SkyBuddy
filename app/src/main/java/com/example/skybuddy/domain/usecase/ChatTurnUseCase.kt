package com.example.skybuddy.domain.usecase

import android.graphics.Bitmap
import com.example.skybuddy.ai.LlmEngine
import com.example.skybuddy.ai.tools.SkyBuddyToolSet
import com.example.skybuddy.data.db.FlightEntity
import com.example.skybuddy.data.db.LuggageEntity
import com.example.skybuddy.data.db.ReceiptEntity
import com.example.skybuddy.data.repository.FlightRepository
import com.example.skybuddy.data.repository.LuggageRepository
import com.example.skybuddy.data.repository.ReceiptRepository
import javax.inject.Inject

data class ChatTurnResult(
    val response: String,
    val flight: FlightEntity? = null,
    val luggage: LuggageEntity? = null,
    val receipts: List<ReceiptEntity>? = null,
    val checklistModified: Boolean = false
)

class ChatTurnUseCase @Inject constructor(
    private val llm: LlmEngine,
    private val tools: SkyBuddyToolSet,
    private val flightRepository: FlightRepository,
    private val luggageRepository: LuggageRepository,
    private val receiptRepository: ReceiptRepository
) {
    suspend fun text(
        prompt: String,
        activeFlightNumber: String?,
        onToolStarted: ((label: String) -> Unit)? = null
    ): ChatTurnResult {
        tools.resetTracking()
        tools.onToolStarted = onToolStarted
        tools.setActiveFlight(activeFlightNumber)
        val wrapped = withFlightContext(prompt, activeFlightNumber)
        val response = llm.generateText(wrapped)
        return collectSideEffects(response, activeFlightNumber)
    }

    /**
     * Streaming variant — collects tokens from the LLM flow and forwards
     * each raw chunk to [onChunk]. Returns the full result after the flow completes.
     */
    suspend fun textStreaming(
        prompt: String,
        activeFlightNumber: String?,
        onToolStarted: ((label: String) -> Unit)? = null,
        onChunk: (String) -> Unit
    ): ChatTurnResult {
        tools.resetTracking()
        tools.onToolStarted = onToolStarted
        tools.setActiveFlight(activeFlightNumber)
        val wrapped = withFlightContext(prompt, activeFlightNumber)

        val fullText = StringBuilder()
        llm.generateTextStreaming(wrapped).collect { chunk ->
            fullText.append(chunk)
            onChunk(chunk)
        }

        return collectSideEffects(fullText.toString(), activeFlightNumber)
    }

    suspend fun image(
        prompt: String,
        bitmap: Bitmap,
        activeFlightNumber: String?,
        onToolStarted: ((label: String) -> Unit)? = null
    ): ChatTurnResult {
        tools.resetTracking()
        tools.onToolStarted = onToolStarted
        tools.setActiveFlight(activeFlightNumber)
        val wrapped = withFlightContext(prompt, activeFlightNumber)
        val response = llm.generateMultimodal(wrapped, bitmap)
        return collectSideEffects(response, activeFlightNumber)
    }

    private suspend fun withFlightContext(prompt: String, flightNumber: String?): String {
        if (flightNumber.isNullOrBlank()) return prompt
        val flight = flightRepository.getFlight(flightNumber) ?: return prompt
        val seatKnown = flight.seat.isNotBlank() && !flight.seat.equals("Unknown", true)
        val seatNote = if (seatKnown) "seat ${flight.seat}"
        else "seat is UNKNOWN — politely ask the user to upload a boarding pass photo or tell you their seat, then call setMySeat to save it"
        val context = "[Active flight: ${flight.flightNumber}, ${flight.airline}, gate ${flight.gate}, terminal ${flight.terminal}, status ${flight.status}, $seatNote.]"
        return "$context\n$prompt"
    }

    private suspend fun collectSideEffects(response: String, activeFlightNumber: String?): ChatTurnResult {
        val receipts = if (tools.didQueryReceipts) {
            val rows = activeFlightNumber?.let { receiptRepository.allForFlight(it) }
                ?: receiptRepository.all()
            rows.takeIf { it.isNotEmpty() }
        } else null

        val luggage = if (tools.didQueryLuggage) {
            activeFlightNumber?.let { luggageRepository.latestForFlight(it) }
                ?: luggageRepository.latest()
        } else null

        val flight = activeFlightNumber?.takeIf { tools.didTouchFlight }
            ?.let { flightRepository.getFlight(it) }

        return ChatTurnResult(
            response = response,
            flight = flight,
            luggage = luggage,
            receipts = receipts,
            checklistModified = tools.didModifyChecklist
        )
    }
}
