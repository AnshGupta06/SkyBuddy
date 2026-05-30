package com.example.skybuddy.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.skybuddy.ui.theme.CardBorder
import com.example.skybuddy.ui.theme.OnSurfaceDark
import com.example.skybuddy.ui.theme.OnSurfaceDim
import com.example.skybuddy.ui.theme.PrimaryLight
import com.example.skybuddy.ui.theme.PrimaryPurple
import com.example.skybuddy.ui.theme.PrimarySurface
import com.example.skybuddy.ui.theme.SurfaceWhite
import kotlinx.coroutines.delay

// ── Dynamic Island Icon Types ────────────────────────────────────────────────

enum class DynamicIslandIcon {
    NAVIGATE, TIP, OFFER, WARNING, ARRIVAL;

    val vector: ImageVector
        get() = when (this) {
            NAVIGATE -> Icons.Filled.Navigation
            TIP -> Icons.Filled.AutoAwesome
            OFFER -> Icons.Filled.LocalOffer
            WARNING -> Icons.Filled.Warning
            ARRIVAL -> Icons.AutoMirrored.Filled.NavigateNext
        }
}

// ── Dynamic Island State ─────────────────────────────────────────────────────

data class DynamicIslandState(
    /** Always-visible compact text: "→ Baggage Claim · 3 min" */
    val compactText: String = "",
    /** LLM-generated expanded tip / offer / insight */
    val expandedText: String = "",
    /** True while LLM is generating the expanded text */
    val isLoading: Boolean = false,
    /** True when the card is expanded (user tapped or auto-expanded) */
    val isExpanded: Boolean = false,
    /** Icon type for the compact pill */
    val icon: DynamicIslandIcon = DynamicIslandIcon.NAVIGATE,
    /** Optional beacon offer headline shown in compact mode */
    val offerPreview: String? = null
)

// ── Theme-matching colors ────────────────────────────────────────────────────

private val OfferAmber = Color(0xFFD97706)
private val WarningRed = Color(0xFFEF4444)

// ── Composable ───────────────────────────────────────────────────────────────

@Composable
fun DynamicIsland(
    state: DynamicIslandState,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.compactText.isBlank() && state.offerPreview == null) return

    val infiniteTransition = rememberInfiniteTransition(label = "island")

    // ── Shimmer for loading state ──
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    // Same Card style as the chat input: white bg, rounded 32dp, 1dp border, 8dp elevation
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onTap() }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (state.isExpanded) 14.dp else 12.dp)
        ) {
            // ── Compact row (always visible) ──
            CompactRow(
                state = state,
                isExpanded = state.isExpanded,
                onDismiss = onDismiss
            )

            // ── Expanded content ──
            AnimatedVisibility(
                visible = state.isExpanded,
                enter = fadeIn(tween(200)) + slideInVertically(
                    spring(stiffness = Spring.StiffnessMedium)
                ) { -it / 3 },
                exit = fadeOut(tween(150)) + slideOutVertically { -it / 3 }
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    // Accent divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.2f)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        PrimaryPurple.copy(alpha = 0.35f),
                                        PrimaryLight.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(Modifier.height(10.dp))

                    if (state.isLoading) {
                        ShimmerLoadingRow(shimmerOffset)
                    } else if (state.expandedText.isNotBlank()) {
                        TypewriterText(text = state.expandedText)
                    }

                    Spacer(Modifier.height(6.dp))

                    // Attribution
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = PrimaryPurple.copy(alpha = 0.3f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "SkyBuddy",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim.copy(alpha = 0.4f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Compact Row ──────────────────────────────────────────────────────────────

@Composable
private fun CompactRow(
    state: DynamicIslandState,
    isExpanded: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon — purple accent matching the app
        val iconTint = when (state.icon) {
            DynamicIslandIcon.OFFER -> OfferAmber
            DynamicIslandIcon.WARNING -> WarningRed
            else -> PrimaryPurple
        }
        Icon(
            imageVector = state.icon.vector,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(12.dp))

        // Animated compact text
        Column(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = state.offerPreview ?: state.compactText,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically { it / 2 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically { -it / 2 })
                },
                label = "compactText"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Close button when expanded, or subtle dot when collapsed
        if (isExpanded) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Collapse",
                tint = OnSurfaceDim.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(PrimaryPurple.copy(alpha = 0.25f))
            )
        }
    }
}

// ── Shimmer Loading ──────────────────────────────────────────────────────────

@Composable
private fun ShimmerLoadingRow(shimmerOffset: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { index ->
            val width = if (index == 0) 0.85f else 0.6f
            Box(
                modifier = Modifier
                    .fillMaxWidth(width)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .drawBehind {
                        val shimmerBrush = Brush.horizontalGradient(
                            colors = listOf(
                                PrimarySurface,
                                PrimaryLight.copy(alpha = 0.2f),
                                PrimarySurface
                            ),
                            startX = size.width * (shimmerOffset - 0.3f),
                            endX = size.width * (shimmerOffset + 0.3f)
                        )
                        drawRect(brush = shimmerBrush)
                    }
            )
        }
    }
}

// ── Typewriter Text ──────────────────────────────────────────────────────────

@Composable
private fun TypewriterText(text: String) {
    var displayedChars by remember(text) { mutableIntStateOf(0) }

    LaunchedEffect(text) {
        displayedChars = 0
        for (i in text.indices) {
            delay(18L)
            displayedChars = i + 1
        }
    }

    Text(
        text = text.take(displayedChars),
        style = MaterialTheme.typography.bodySmall.copy(
            lineHeight = 18.sp,
            letterSpacing = 0.1.sp
        ),
        color = OnSurfaceDim,
        modifier = Modifier.fillMaxWidth()
    )
}
