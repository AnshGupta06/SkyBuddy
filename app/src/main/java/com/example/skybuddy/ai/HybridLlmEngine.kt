package com.example.skybuddy.ai

import android.graphics.Bitmap
import com.example.skybuddy.data.network.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridLlmEngine @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val localEngine: LiteRtLlmEngine,
    private val remoteEngine: RemoteLlmEngine
) : LlmEngine {

    private val activeEngine: LlmEngine
        get() = if (networkMonitor.isOnline()) remoteEngine else localEngine

    override val isReady: Boolean
        get() = activeEngine.isReady

    override fun initialize(preferred: Backend?): Flow<InitState> {
        return localEngine.initialize(preferred)
    }

    override suspend fun generateText(prompt: String): String {
        return activeEngine.generateText(prompt)
    }

    override fun generateTextStreaming(prompt: String): Flow<String> {
        return activeEngine.generateTextStreaming(prompt)
    }

    override suspend fun generateOneOffText(prompt: String): String {
        return activeEngine.generateOneOffText(prompt)
    }

    override suspend fun generateMultimodal(prompt: String, bitmap: Bitmap): String {
        return activeEngine.generateMultimodal(prompt, bitmap)
    }

    override fun close() {
        localEngine.close()
        remoteEngine.close()
    }
}
