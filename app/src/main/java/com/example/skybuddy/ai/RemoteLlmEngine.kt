package com.example.skybuddy.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.skybuddy.BuildConfig
import com.example.skybuddy.ai.tools.SkyBuddyToolSet
import com.example.skybuddy.data.network.localllm.Content
import com.example.skybuddy.data.network.localllm.LLMRequest
import com.example.skybuddy.data.network.localllm.FunctionDeclaration
import com.example.skybuddy.data.network.localllm.InlineData
import com.example.skybuddy.data.network.localllm.Schema
import com.example.skybuddy.data.network.localllm.SystemInstruction
import com.example.skybuddy.data.network.localllm.Tool
import com.example.skybuddy.di.IoDispatcher
import com.google.ai.edge.litertlm.ToolParam
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteLlmEngine @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val toolSet: SkyBuddyToolSet,
    @IoDispatcher private val io: CoroutineDispatcher
) : LlmEngine {

    override val isReady: Boolean = true

    override fun initialize(preferred: Backend?): Flow<InitState> = flow {
        emit(InitState.Ready(Backend.CPU))
    }

    private val SYSTEM_PROMPT = """
        You are SkyBuddy, the AI airport companion for Surat Airport. You help travelers find food, shops, services, navigate the terminal, and manage their journey. You are their personal guide from entry to boarding.

        ## MANDATORY TOOL RULES

        RULE 1 — ALWAYS call the search tool when the user asks about ANY of these topics. NEVER answer from memory:
          - Food & Drink: ANY dish name (dosa, biryani, burger, pizza, coffee, chai, idli, pasta, noodles, fries, sandwich, etc.), ANY cuisine (Indian, South Indian, Chinese, Italian, Continental, etc.), food, eat, drink, restaurant, cafe, bar, snack, breakfast, lunch, dinner, menu, price, cost, veg, vegetarian, non-veg, halal, cheap, expensive, healthy
          - Shopping: shop, store, retail, buy, duty-free, perfume, cosmetics, clothing, books, electronics, gifts, souvenirs
          - Services: pharmacy, medicine, lounge, relax, shower, sleep, toilet, restroom, ATM, wifi, charging, prayer room, kids area, smoking, baggage, trolley, wheelchair
          - Navigation: gate, terminal, airside, landside, security, directions, where is, how to reach, how far, walking time, near gate, find, locate
          The search uses fuzzy matching on names, tags, cuisine types, menu item names, and descriptions — so "dosa" will match South Indian restaurants that serve dosa.

        RULE 2 — Call getFlightStatus for gate, departure, delay, or terminal questions about the active flight.

        RULE 3 — Call checkLoungeAccess when the user mentions a credit card and asks about lounge access.

        RULE 4 — Call checkSeatDetails or setMySeat for seat-related questions or boarding pass parsing.

        RULE 5 — Call saveBag / getBagDescription for luggage-related questions.

        RULE 6 — Call saveReceipt / getReceipts for expense or receipt questions.

        RULE 7 — If the user asks for things 'nearby', 'near me', 'around me', or 'closest', pass searchRadius=200 to the search tool. The system context provides the user's current X,Y coordinates — the search tool will automatically filter by radius.

        RULE 8 — Call getCurrentOffers when the user asks about offers, discounts, deals, or rewards. Offers include SKU-level details.

        RULE 9 — Call getJourneyGuide when the user asks 'what do I do next', 'how do I board', 'guide me', 'first time at airport', 'airport procedure', 'steps to gate', or any question about the passenger journey from entry to boarding.

        RULE 10 — Call getAirportFacilities when the user asks about check-in counters, baggage drop, security checkpoints, immigration, baggage claim, information desk, porter service, cloakroom, or any airport infrastructure question.

        ## Multilingual Support
        - If the user writes in Hindi, respond in Hindi. If they write in any other language, respond in that language.
        - For Hindi: use simple, conversational Hindi (Hinglish is okay). Example: "Aapka gate G3 hai, yahan se 5 min door."

        ## Response style
        - Be concise: under 100 words unless a full menu or journey guide is requested.
        - For food results: lead with the top 2-3 popular items with prices (₹).
        - Always mention if a place is airside (post-security) or landside (pre-security).
        - When showing a place, say: "I can navigate you there — just say 'navigate to [name]'."
        - If multiple results, present them as a numbered list with ratings (⭐).
        - Mention closing time if the venue closes within 1 hour.
        - Use emoji sparingly to make responses scannable: 🍕 for food, 🛍️ for shops, 🚻 for services, 🗺️ for navigation.
        - Be warm, proactive, and helpful — you're a friendly airport companion!
        - For first-time travelers, proactively offer to show the journey guide.
    """.trimIndent()

    private fun getTools(): List<Tool> {
        val declarations = SkyBuddyToolSet::class.java.methods
            .filter { it.isAnnotationPresent(com.google.ai.edge.litertlm.Tool::class.java) }
            .map { method ->
                val annotation = method.getAnnotation(com.google.ai.edge.litertlm.Tool::class.java)!!
                val params = method.parameters
                val properties = mutableMapOf<String, Schema>()
                val required = mutableListOf<String>()

                params.forEach { param ->
                    val paramAnnotation = param.getAnnotation(ToolParam::class.java)
                    properties[param.name] = Schema(
                        type = "STRING",
                        description = paramAnnotation?.description ?: ""
                    )
                    required.add(param.name)
                }

                FunctionDeclaration(
                    name = method.name,
                    description = annotation.description,
                    parameters = if (properties.isNotEmpty()) Schema(
                        type = "OBJECT",
                        properties = properties,
                        required = required
                    ) else null
                )
            }
        return listOf(Tool(functionDeclarations = declarations))
    }

    private fun buildRequest(prompt: String, bitmap: Bitmap? = null): LLMRequest {
        val parts = mutableListOf<Map<String, Any>>()
        if (bitmap != null) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            parts.add(mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to base64)))
        }
        parts.add(mapOf("text" to prompt))

        return LLMRequest(
            systemInstruction = SystemInstruction(parts = listOf(mapOf("text" to SYSTEM_PROMPT))),
            contents = listOf(Content(role = "user", parts = parts)),
            tools = getTools()
        )
    }

    private suspend fun executeRequestObj(request: LLMRequest): BufferedSource = withContext(io) {
        val requestBody = moshi.adapter(LLMRequest::class.java).toJson(request)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val baseUrl = BuildConfig.LOCAL_API_BASE_URL
        if (baseUrl.isBlank()) {
            throw Exception("LOCAL_API_BASE_URL is missing. Please check your local.properties and sync the project.")
        }
        val url = if (baseUrl.contains("?")) {
            "$baseUrl&password=${BuildConfig.LOCAL_API_PASSWORD}"
        } else {
            "$baseUrl?password=${BuildConfig.LOCAL_API_PASSWORD}"
        }
        
        val requestHTTP = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val response = client.newCall(requestHTTP).execute()
        if (!response.isSuccessful) {
            throw Exception("API call failed with ${response.code}: ${response.message}")
        }
        response.body?.source() ?: throw Exception("Empty response body")
    }

    private suspend fun executeRequest(prompt: String, bitmap: Bitmap? = null): BufferedSource {
        return executeRequestObj(buildRequest(prompt, bitmap))
    }

    override suspend fun generateText(prompt: String): String = withContext(io) {
        try {
            val source = executeRequest(prompt)
            val builder = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line()
                if (!line.isNullOrBlank()) {
                    try {
                        val chunk = moshi.adapter(com.example.skybuddy.data.network.localllm.StreamResponseChunk::class.java).fromJson(line)
                        chunk?.text?.let { builder.append(it) }
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            builder.toString()
        } catch (e: Exception) {
            "Error generating response from remote API: ${e.message}"
        }
    }

    override fun generateTextStreaming(prompt: String): Flow<String> = flow {
        try {
            var currentRequest = buildRequest(prompt)
            var isComplete = false

            while (!isComplete) {
                isComplete = true
                val source = executeRequestObj(currentRequest)
                var hasToolCall = false
                val currentCalls = mutableListOf<Map<String, Any>>()
                val currentModelParts = mutableListOf<Map<String, Any>>()
                
                while (!source.exhausted()) {
                    val line = source.readUtf8Line()
                    if (!line.isNullOrBlank()) {
                        try {
                            val chunk = moshi.adapter(com.example.skybuddy.data.network.localllm.StreamResponseChunk::class.java).fromJson(line)
                            chunk?.text?.let { if (it.isNotEmpty()) emit(it) }
                            
                            if (chunk?.modelParts != null && chunk.modelParts.isNotEmpty()) {
                                hasToolCall = true
                                currentModelParts.addAll(chunk.modelParts)
                                chunk.modelParts.forEach { part ->
                                    val fCall = part["functionCall"] as? Map<String, Any>
                                    if (fCall != null) {
                                        currentCalls.add(fCall)
                                    }
                                }
                            } else if (chunk?.functionCalls != null && chunk.functionCalls.isNotEmpty()) {
                                hasToolCall = true
                                currentCalls.addAll(chunk.functionCalls)
                            }
                        } catch (e: Exception) {
                            // ignore parsing error for this chunk
                        }
                    }
                }
                
                if (hasToolCall && currentCalls.isNotEmpty()) {
                    isComplete = false
                    val newContents = currentRequest.contents.toMutableList()
                    
                    // Add model's tool calls exactly as emitted
                    val modelPartsToAppend = if (currentModelParts.isNotEmpty()) {
                        currentModelParts
                    } else {
                        currentCalls.map { mapOf("functionCall" to it) }
                    }
                    
                    newContents.add(Content(
                        role = "model",
                        parts = modelPartsToAppend
                    ))
                    
                    // Execute tools locally
                    val responses = currentCalls.map { call ->
                        val name = call["name"] as? String ?: "unknown"
                        val argsMap = call["args"] as? Map<String, Any>
                        
                        val method = toolSet::class.java.methods.find { it.name == name }
                        val resultObj: Any = if (method != null) {
                            val args = method.parameters.map { param ->
                                argsMap?.get(param.name)?.toString() ?: ""
                            }.toTypedArray()
                            method.invoke(toolSet, *args) ?: mapOf("result" to "Success")
                        } else {
                            mapOf("error" to "Tool not found")
                        }
                        
                        val resultMap = if (resultObj is Map<*, *>) {
                            resultObj as Map<String, Any>
                        } else if (resultObj is String) {
                            mapOf("content" to resultObj)
                        } else {
                            mapOf("result" to resultObj.toString())
                        }
                        
                        mapOf("functionResponse" to mapOf(
                            "name" to name,
                            "response" to resultMap
                        ))
                    }
                    
                    // Send results back as user/function
                    newContents.add(Content(
                        role = "user",
                        parts = responses
                    ))
                    
                    currentRequest = currentRequest.copy(contents = newContents)
                }
            }
        } catch (e: Exception) {
            emit("Error generating streaming response from remote API: ${e.message}")
        }
    }.flowOn(io)

    override suspend fun generateOneOffText(prompt: String): String = generateText(prompt)

    override suspend fun generateMultimodal(prompt: String, bitmap: Bitmap): String = withContext(io) {
        try {
            val source = executeRequest(prompt, bitmap)
            val builder = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line()
                if (!line.isNullOrBlank()) {
                    try {
                        val chunk = moshi.adapter(com.example.skybuddy.data.network.localllm.StreamResponseChunk::class.java).fromJson(line)
                        chunk?.text?.let { builder.append(it) }
                    } catch (e: Exception) { }
                }
            }
            builder.toString()
        } catch (e: Exception) {
            "Error generating response from remote API: ${e.message}"
        }
    }

    override fun close() {
        // Nothing to close
    }
}
