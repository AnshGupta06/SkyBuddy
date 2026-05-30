package com.example.skysecurity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.skysecurity.location.SOSAlert
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertListScreen(
    onBack: () -> Unit,
    onNavigateToAlert: (SOSAlert) -> Unit,
    viewModel: SecurityMapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SOS Alerts", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2D2D44),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF1A1A2E)
    ) { padding ->
        if (uiState.alerts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No alerts", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("All clear — monitoring for SOS signals", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E8EA0))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.alerts, key = { it.id }) { alert ->
                    AlertCard(
                        alert = alert,
                        timeStr = dateFormat.format(Date(alert.timestamp)),
                        onAcknowledge = { viewModel.acknowledgeAlert(alert.id) },
                        onStopSound = { viewModel.stopUserSound(alert) },
                        onNavigate = {
                            viewModel.navigateToAlert(alert)
                            onNavigateToAlert(alert)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    alert: SOSAlert,
    timeStr: String,
    onAcknowledge: () -> Unit,
    onStopSound: () -> Unit,
    onNavigate: () -> Unit
) {
    val typeColor = when {
        alert.type.contains("EMERGENCY") || alert.type.contains("EMERGE") -> Color(0xFFFF3B30)
        alert.type.contains("SUSPICIOUS") || alert.type.contains("SUSPIC") -> Color(0xFFFF9500)
        alert.type.contains("MEDICAL") || alert.type.contains("MEDIC") -> Color(0xFF34C759)
        alert.type.contains("FIRE") -> Color(0xFFFF6B35)
        else -> Color(0xFF8E8EA0)
    }
    val typeIcon = when {
        alert.type.contains("EMERGENCY") || alert.type.contains("EMERGE") -> Icons.Filled.Warning
        alert.type.contains("SUSPICIOUS") || alert.type.contains("SUSPIC") -> Icons.Filled.Visibility
        alert.type.contains("MEDICAL") || alert.type.contains("MEDIC") -> Icons.Filled.LocalHospital
        alert.type.contains("FIRE") -> Icons.Filled.LocalFireDepartment
        else -> Icons.Filled.Report
    }
    val displayLabel = when {
        alert.type.contains("EMERGENCY") || alert.type.contains("EMERGE") -> "Emergency"
        alert.type.contains("SUSPICIOUS") || alert.type.contains("SUSPIC") -> "Suspicious Behaviour"
        alert.type.contains("MEDICAL") || alert.type.contains("MEDIC") -> "Medical"
        alert.type.contains("FIRE") -> "Fire"
        else -> "Other SOS"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (alert.acknowledged) Color(0xFF2D2D44).copy(alpha = 0.5f) else Color(0xFF2D2D44),
        border = if (!alert.acknowledged) ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(typeColor.copy(alpha = 0.5f))
        ) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(typeColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (alert.acknowledged) Color(0xFF8E8EA0) else Color.White
                    )
                    Text("Reported by: ${alert.identity}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0E0E0))
                    Text(timeStr, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E8EA0))
                }
                if (alert.acknowledged) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF34C759).copy(alpha = 0.15f)) {
                        Text("ACK", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF34C759))
                    }
                }
            }

            if (alert.locationX != null && alert.locationY != null) {
                Spacer(Modifier.height(8.dp))
                Text("Location: (${alert.locationX}, ${alert.locationY})",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E8EA0))
            }

            if (!alert.acknowledged) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAcknowledge,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Acknowledge") }
                    Button(
                        onClick = onNavigate,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = typeColor)
                    ) {
                        Icon(Icons.Filled.Navigation, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Navigate")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStopSound,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6))
                ) {
                    Icon(Icons.Filled.VolumeOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop User Sound", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
