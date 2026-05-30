package com.example.skybuddy.data.network.localllm

data class LLMRequest(
    val systemInstruction: SystemInstruction? = null,
    val contents: List<Content>,
    val tools: List<Tool>? = null
)

data class StreamResponseChunk(
    val text: String? = null,
    val functionCalls: List<Map<String, Any>>? = null,
    val modelParts: List<Map<String, Any>>? = null
)

data class SystemInstruction(
    val role: String = "system",
    val parts: List<Map<String, Any>>
)

data class Content(
    val role: String,
    val parts: List<Map<String, Any>>
)

data class InlineData(
    val mimeType: String,
    val data: String // Base64 encoded
)

data class Tool(
    val functionDeclarations: List<FunctionDeclaration>
)

data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Schema? = null
)

data class Schema(
    val type: String,
    val properties: Map<String, Schema>? = null,
    val required: List<String>? = null,
    val items: Schema? = null,
    val enum: List<String>? = null,
    val description: String? = null
)
