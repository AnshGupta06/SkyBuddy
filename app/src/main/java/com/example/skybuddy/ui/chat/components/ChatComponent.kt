package com.example.skybuddy.ui.chat.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.skybuddy.core.permission.rememberPermissionController
import com.example.skybuddy.ui.chat.ChatViewModel
import com.example.skybuddy.ui.chat.VoiceEvent
import com.example.skybuddy.ui.theme.BackgroundGray
import com.example.skybuddy.ui.theme.GlassCard
import com.example.skybuddy.ui.theme.OnSurfaceDark
import com.example.skybuddy.ui.theme.OnSurfaceDim
import com.example.skybuddy.ui.theme.PrimaryPurple
import com.example.skybuddy.ui.theme.ErrorRed

private val quickReplies = listOf(
    "📋 Guide me",
    "🍔 Food nearby",
    "🛒 Duty-free",
    "🌟 Offers today",
    "🚪 Where's my gate?",
    "🚻 Restroom",
    "✈️ Flight status",
    "🧳 Baggage claim"
)

/**
 * Google-Maps-style chat component.
 *
 * Layout (top-to-bottom in the BottomSheet):
 *  - Top: Floating input bar & quick replies (visible at peek)
 *  - Bottom: Chat messages area with solid background (visible when expanded)
 */
@Composable
fun ChatComponent(
    viewModel: ChatViewModel,
    flightNumber: String?,
    onExpandRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val voiceController = viewModel.voiceController
    val state by viewModel.state.collectAsState()
    val timelineEvents by viewModel.timelineEvents.collectAsState()
    val voiceEvent by voiceController.events.collectAsState()
    val isListening by voiceController.isListening.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val hasConversation = timelineEvents.isNotEmpty() || state.isThinking

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val prompt = if (flightNumber == "help") "Where do I go?" else state.input.trim()
            viewModel.sendImage(prompt, bitmap)
            onExpandRequest?.invoke()
        }
    }
    val cameraPermission = rememberPermissionController { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    // Auto-scroll to the latest message
    LaunchedEffect(timelineEvents.size) {
        if (timelineEvents.isNotEmpty()) listState.animateScrollToItem(timelineEvents.size - 1)
    }
    LaunchedEffect(state.streamingResponse) {
        if (state.isThinking && timelineEvents.isNotEmpty()) {
            listState.animateScrollToItem(timelineEvents.size)
        }
    }

    // ── STT: handle voice recognition results ──
    LaunchedEffect(voiceEvent) {
        when (val ev = voiceEvent) {
            is VoiceEvent.Heard -> {
                viewModel.onInputChanged(ev.text)
                viewModel.sendText()
                onExpandRequest?.invoke()
                voiceController.consume()
            }
            is VoiceEvent.Error -> voiceController.consume()
            null -> Unit
        }
    }

    // ── TTS: speak full response for non-streaming paths ──
    LaunchedEffect(timelineEvents.lastOrNull()?.id) {
        val last = timelineEvents.lastOrNull()
        if (last?.uiComponentType == "TEXT" && last.role == "GEMMA") {
            if (!viewModel.didStreamingTtsHandle()) {
                voiceController.speak(last.content)
            }
        }
    }

    val recordPermission = rememberPermissionController { granted ->
        if (granted) voiceController.startListening()
    }

    // Helper to send and expand
    fun doSend() {
        viewModel.sendText()
        onExpandRequest?.invoke()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // Removed BackgroundGray to allow the bottom sheet peek area to be transparent
    ) {
        // ── 1. Input Bar (Top of sheet, floats at bottom when collapsed) ──
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            cornerRadius = 32.dp // Pill shape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic button
                val micPulseScale by animateFloatAsState(
                    targetValue = if (isListening) 1.2f else 1f,
                    animationSpec = if (isListening) {
                        infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    } else {
                        tween(200)
                    },
                    label = "micPulse"
                )

                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceController.stopListening()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) voiceController.startListening()
                            else recordPermission.request(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .scale(micPulseScale)
                        .clip(CircleShape)
                        .background(if (isListening) PrimaryPurple else Color.White)
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice input",
                        tint = if (isListening) Color.White else PrimaryPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Text field
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isListening) "Listening..." else "Ask SkyBuddy...", color = OnSurfaceDim, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = PrimaryPurple,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                // Camera button
                IconButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) cameraLauncher.launch(null)
                        else cameraPermission.request(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = "Camera",
                        tint = OnSurfaceDim,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Send button
                IconButton(
                    onClick = { doSend() },
                    enabled = state.input.isNotBlank() && !state.isThinking,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.input.isNotBlank() && !state.isThinking) PrimaryPurple
                            else Color(0xFFE5E7EB)
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (state.input.isNotBlank() && !state.isThinking) Color.White else OnSurfaceDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 2. Quick Reply Chips (Always visible just below the input bar) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickReplies.forEach { chip ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.9f))
                        .clickable {
                            viewModel.onInputChanged(chip.replace(Regex("^[\\p{So}\\p{Cn}] "), ""))
                            doSend()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        chip,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDark,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        // ── 3. Chat History Container (Solid Background for expanded state) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(BackgroundGray)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            if (hasConversation) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Close / Clear row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "SkyBuddy Chat",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = OnSurfaceDark
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(ErrorRed.copy(alpha = 0.1f))
                                .clickable { viewModel.clearChat() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Clear Chat", style = MaterialTheme.typography.labelSmall, color = ErrorRed)
                        }
                    }

                    // Messages
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(timelineEvents, key = { it.id }) { ConversationFlowItem(it) }

                        if (state.isThinking) {
                            item(key = "streaming_bubble") {
                                StreamingBubble(
                                    response = state.streamingResponse,
                                    isStreamingResponse = state.isStreamingResponse,
                                    toolLabel = state.toolStatusLabel
                                )
                            }
                        }
                    }
                }
            } else {
                // Welcome empty state
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("👋", fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Hi! I'm SkyBuddy",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = OnSurfaceDark
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ask me about food, shops, gates,\nnavigation, offers & more!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ── Streaming bubble: shows response tokens as they arrive ──

@Composable
fun StreamingBubble(
    response: String,
    isStreamingResponse: Boolean,
    toolLabel: String?
) {
    val hasResponse = response.isNotBlank()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .animateContentSize()
        ) {
            // ── Tool call indicator (while tools are running) ──
            if (toolLabel != null && !hasResponse && !isStreamingResponse) {
                ThinkingIndicator(toolLabel)
                Spacer(Modifier.height(4.dp))
            }

            // ── Streaming response text ──
            if (hasResponse) {
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 18.dp
                            )
                        )
                        .background(Color.White)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .animateContentSize()
                ) {
                    MarkdownText(
                        markdown = response,
                        textColor = OnSurfaceDark,
                        accentColor = PrimaryPurple
                    )
                }
            }

            // ── Still waiting for any output ──
            if (!hasResponse && !isStreamingResponse) {
                ThinkingIndicator(toolLabel)
            }
        }
    }
}

@Composable
fun ThinkingDots(
    modifier: Modifier = Modifier,
    color: Color = PrimaryPurple.copy(alpha = 0.5f)
) {
    val transition = rememberInfiniteTransition(label = "dots")
    val offsets = (0..2).map { i ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -4f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, delayMillis = i * 120, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot$i"
        )
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        offsets.forEach { anim ->
            val y by anim
            Box(
                modifier = Modifier
                    .offset(y = y.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
fun ThinkingIndicator(toolLabel: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
    ) {
        ThinkingDots()
        Spacer(Modifier.width(4.dp))
        Text(
            text = toolLabel ?: "Thinking...",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}
