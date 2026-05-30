package com.example.skybuddy.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.skybuddy.ai.tools.SkyBuddyToolSet
import com.example.skybuddy.di.IoDispatcher
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val acceleration: AccelerationCompat,
    private val toolSet: SkyBuddyToolSet,
    @IoDispatcher private val io: CoroutineDispatcher
) : LlmEngine {

    private val ready = AtomicBoolean(false)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val isReady: Boolean get() = ready.get()

    fun expectedModelPath(): String = File(context.filesDir, MODEL_FILE).absolutePath

    override fun initialize(preferred: Backend?): Flow<InitState> = flow {
        // CPU for text generation (GPU delegate hangs on a kgsl fence on some device families),
        // but Vision MUST use GPU on devices that don't support advanced CPU vector instructions.
        emit(InitState.Loading(Backend.CPU, InitStage.ProbingDevice))
        val attempt = tryInitialize(Backend.CPU) { stage ->
            emit(InitState.Loading(Backend.CPU, stage))
        }
        if (attempt == null) emit(InitState.Ready(Backend.CPU))
        else emit(InitState.Failed(Backend.CPU, attempt.message ?: "CPU init failed", attempt))
    }.flowOn(io)

    private suspend inline fun tryInitialize(
        backend: Backend,
        crossinline progress: suspend (InitStage) -> Unit
    ): Throwable? = try {
        val modelPath = expectedModelPath()
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found at $modelPath")
        }

        progress(InitStage.OpeningModel)

        val textBackend = LiteRtBackend.CPU()
        val visionBackend = if (acceleration.isGpuAvailable()) LiteRtBackend.GPU() else LiteRtBackend.CPU()
        val config = EngineConfig(
            modelPath = modelPath,
            backend = textBackend,
            visionBackend = visionBackend,
            cacheDir = context.cacheDir.absolutePath
        )
        val newEngine = Engine(config)

        progress(InitStage.AllocatingTensors)
        newEngine.initialize()
        engine = newEngine

        progress(InitStage.Warmup)
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            tools = listOf(tool(toolSet))
        )
        conversation = newEngine.createConversation(conversationConfig)
        ready.set(true)
        null
    } catch (t: Throwable) {
        ready.set(false)
        runCatching { engine?.close() }
        engine = null
        conversation = null
        t
    }

    override suspend fun generateText(prompt: String): String = withContext(io) {
        val conv = conversation ?: return@withContext "Error: model not loaded."
        val builder = StringBuilder()
        try {
            conv.sendMessageAsync(prompt).collect { chunk -> builder.append(chunk) }
            builder.toString()
        } catch (t: Throwable) {
            "Error generating response: ${t.message}"
        }
    }

    override fun generateTextStreaming(prompt: String): Flow<String> {
        return flow<String> {
            val conv = conversation
            if (conv == null) {
                emit("Error: model not loaded.")
                return@flow
            }
            try {
                conv.sendMessageAsync(prompt).collect { chunk ->
                    emit(chunk.toString())
                }
            } catch (t: Throwable) {
                emit("Error generating response: ${t.message}")
            }
        }.flowOn(io)
    }

    override suspend fun generateOneOffText(prompt: String): String = withContext(io) {
        var tempEngine: Engine? = null
        var tempConv: Conversation? = null
        val builder = StringBuilder()
        try {
            val config = EngineConfig(
                modelPath = expectedModelPath(),
                backend = LiteRtBackend.CPU(),
                visionBackend = LiteRtBackend.GPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            tempEngine = Engine(config)
            tempEngine.initialize()

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                tools = emptyList() // No tools needed for one-off beacon toast
            )
            tempConv = tempEngine.createConversation(conversationConfig)
            tempConv.sendMessageAsync(prompt).collect { chunk -> builder.append(chunk) }
            builder.toString()
        } catch (t: Throwable) {
            "Error generating response: ${t.message}"
        } finally {
            runCatching { tempConv?.close() }
            runCatching { tempEngine?.close() }
        }
    }

    override suspend fun generateMultimodal(prompt: String, bitmap: Bitmap): String = withContext(io) {
        val conv = conversation ?: return@withContext "Error: model not loaded."
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val contents = Contents.of(Content.ImageBytes(stream.toByteArray()), Content.Text(prompt))
        val builder = StringBuilder()
        try {
            conv.sendMessageAsync(contents).collect { chunk -> builder.append(chunk) }
            builder.toString()
        } catch (t: Throwable) {
            "Error processing multimodal request: ${t.message}"
        }
    }

    override fun close() {
        ready.set(false)
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    companion object {
        private const val TAG = "LiteRtLlmEngine"
        const val MODEL_FILE = "gemma.litertlm"
        private val SYSTEM_PROMPT = """
You are SkyBuddy, an AI travel companion based on a fine-tuned gemma4 model. You run fully on-device.

## MANDATORY TOOL RULES — follow these before doing anything else

RULE 1 — ALWAYS call search when the user asks about ANY of:
  food, eat, drink, coffee, tea, restaurant, cafe, bar, snack, breakfast, lunch, dinner, menu, price, cost, veg, vegetarian, cuisine, biryani, dosa, burger, pizza, sandwich,
  shop, store, retail, buy, pharmacy, medicine, book, clothes,
  lounge, relax, shower, sleep,
  toilet, restroom, ATM, wifi, charging, prayer room, kids area, smoking, baggage, trolley,
  gate, terminal, airside, security, post-security, landside, near gate, walking time, how far, directions, where is.
  DO NOT answer these questions from memory. You MUST call search        first.

RULE 2 — Call getFlightStatus when asked about gate, departure time, delay, or terminal for the active flight.

RULE 3 — Call checkLoungeAccess when the user mentions a credit card and asks about lounge access.

RULE 4 — Call checkSeatDetails or setMySeat for any seat-related question or when parsing a boarding pass.

RULE 5 — Call saveBag / getBagDescription for luggage-related questions.

RULE 6 — Call saveReceipt / getReceipts for expense or receipt questions.


## Response style
- Under 120 words unless a full menu is requested.
- Lead food answers with top 3 popular items and prices.
- Always state airside vs landside.
- Mention closing time if the venue closes within 1 hour.
        """.trimIndent()
    }
}
