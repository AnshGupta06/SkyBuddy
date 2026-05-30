package com.example.skybuddy.ui.flight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.skybuddy.ui.theme.GradientButton
import com.example.skybuddy.ui.theme.OnSurfaceDim
import com.example.skybuddy.ui.theme.PrimaryLight
import com.example.skybuddy.ui.theme.PrimaryPurple
import com.example.skybuddy.ui.theme.StatusDelayed
import com.example.skybuddy.ui.theme.StatusOnTime
import kotlinx.coroutines.delay

@Composable
fun FlightInfoScreen(
    flightNumber: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenServices: (String) -> Unit,
    viewModel: FlightInfoViewModel = hiltViewModel()
) {
    val flight by viewModel.flight.collectAsState()
    val scrollState = rememberScrollState()

    var showHero by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var showBottom by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100); showHero = true
        delay(200); showCard = true
        delay(300); showBottom = true
    }

    val f = flight

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F7))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // ── Hero Section ──
            AnimatedVisibility(
                visible = showHero,
                enter = fadeIn(tween(500))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(PrimaryPurple, PrimaryLight)
                            )
                        )
                ) {
                    // Top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        // Flight number
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Flight, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                f?.flightNumber ?: flightNumber,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { /* Share */ }) {
                            Icon(Icons.Filled.Share, "Share", tint = Color.White)
                        }
                    }

                    // Hero content
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 20.dp, bottom = 60.dp, end = 20.dp)
                    ) {
                        Text(
                            "Last Updated ${formatFlightDate(f?.lastSyncedAt ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${f?.origin ?: "---"} to ${f?.destination ?: "---"}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        // Status pill
                        val isDelayed = f?.status.equals("Delayed", true) || f?.status.equals("Cancelled", true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                f?.status ?: "Scheduled",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ── Dark Journey Timeline Card ──
            AnimatedVisibility(
                visible = showCard,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-40).dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF01A1A2E))
                        .padding(24.dp)
                ) {
                    Column {
                        // Departure
                        Row(verticalAlignment = Alignment.Top) {
                            // Timeline dots
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(20.dp)
                            ) {
                                Icon(Icons.Filled.Circle, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(60.dp)
                                        .background(Color.White.copy(alpha = 0.3f))
                                )
                                Icon(Icons.Filled.Circle, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    formatFlightDate(f?.departureTimeEpoch ?: 0L),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        f?.origin ?: "---",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        f?.originCity ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }

                                Spacer(Modifier.height(32.dp))

                                // Arrival
                                Text(
                                    formatFlightTime(f?.time ?: ""),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.FlightLand, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        f?.destination ?: "---",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        f?.destCity ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ── Countdown & Suggestion ──
                        CountdownTimerAndSuggestion(f?.departureTimeEpoch ?: 0L)
                        Spacer(Modifier.height(20.dp))

                        // ── Info Grid ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            InfoTile("Gate", f?.gate ?: "-", Icons.Filled.MeetingRoom, Modifier.weight(1f))
                            InfoTile("Baggage", "${f?.terminal ?: "-"}", Icons.Filled.Luggage, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            InfoTile("Seat", f?.seat ?: "-", Icons.Filled.Flight, Modifier.weight(1f))
                            InfoTile("Parking", "-", Icons.Filled.LocalParking, Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── Action Buttons ──
            AnimatedVisibility(
                visible = showBottom,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 }
            ) {
                Column(
                    modifier = Modifier
                        .offset(y = (-20).dp)
                        .padding(horizontal = 16.dp)
                ) {
                    // Chat with SkyBuddy
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Ask SkyBuddy", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF1A1A2E))
                                Text("Get help about this flight", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            }
                            IconButton(
                                onClick = { onOpenChat(f?.flightNumber ?: flightNumber) },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryPurple.copy(alpha = 0.1f))
                            ) {
                                Icon(Icons.Filled.Flight, contentDescription = "Chat", tint = PrimaryPurple, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Services button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Airport Services", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF1A1A2E))
                                Text("Travel, Shop, Dine & Facilities", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            }
                            IconButton(
                                onClick = { onOpenServices(f?.flightNumber ?: flightNumber) },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEA580C).copy(alpha = 0.1f))
                            ) {
                                Icon(Icons.Filled.Luggage, contentDescription = "Services", tint = Color(0xFFEA580C), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }

        // ── Pinned Save Flight CTA ──
        AnimatedVisibility(
            visible = showBottom,
            enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(tween(400, delayMillis = 200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                GradientButton(
                    text = "Save Flight",
                    onClick = { /* Already saved via tracking */ },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── Info Tile (inside dark card) ────────────────────────────
@Composable
private fun InfoTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(12.dp)
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

// ── Countdown Timer & Suggestions ───────────────────────────
@Composable
private fun CountdownTimerAndSuggestion(departureTimeEpoch: Long) {
    if (departureTimeEpoch == 0L) return

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(departureTimeEpoch) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(60000L) // Update every minute
        }
    }

    val remainingMillis = departureTimeEpoch - currentTime
    val minutesLeft = (remainingMillis / (60 * 1000)).toInt()

    if (minutesLeft > 0) {
        val hours = minutesLeft / 60
        val mins = minutesLeft % 60
        val timeString = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        val suggestion = when {
            minutesLeft > 180 -> "Plenty of time. Relax, have a meal, or explore the airport shops."
            minutesLeft > 120 -> "Baggage drop is likely open. Time to check in your luggage."
            minutesLeft > 60 -> "Head towards security check. Grab a quick bite if you haven't."
            minutesLeft > 45 -> "Boarding starting soon. Please proceed to your gate."
            minutesLeft > 20 -> "Boarding is in progress. Hurry up and head to the gate immediately!"
            else -> "Final call! Please proceed to the gate immediately."
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFEA580C).copy(alpha = 0.15f))
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Timer, contentDescription = "Timer", tint = Color(0xFFEA580C), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$timeString until departure",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFEA580C)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                .padding(16.dp)
        ) {
            Text(
                "Flight has departed or is departing now.",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF10B981)
            )
        }
    }
}
