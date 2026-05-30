package com.example.skybuddy.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.skybuddy.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundGray,
                    titleContentColor = OnSurfaceDark,
                    navigationIconContentColor = OnSurfaceDark
                )
            )
        },
        containerColor = BackgroundGray
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ═══════════════════════════════════════════════════
            // 1. TEXT-TO-SPEECH
            // ═══════════════════════════════════════════════════
            item { SettingsSectionHeader("🗣️  Text-to-Speech") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // Enable + Status
                        SettingsToggleRow(
                            title = "Enable TTS",
                            subtitle = "Speak chatbot responses aloud",
                            icon = Icons.Default.VolumeUp,
                            checked = uiState.isTtsEnabled,
                            onCheckedChange = viewModel::setTtsEnabled,
                            trailing = { TtsStatusChip(uiState.ttsStatus) }
                        )

                        AnimatedVisibility(visible = uiState.isTtsEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                HorizontalDivider(color = DividerColor)

                                // Language picker
                                SettingsDropdown(
                                    label = "Language",
                                    selected = uiState.ttsLanguage,
                                    options = uiState.availableLanguages,
                                    displayTransform = { tag ->
                                        val loc = Locale.forLanguageTag(tag)
                                        "${loc.displayLanguage} (${loc.displayCountry})"
                                    },
                                    onSelected = viewModel::setTtsLanguage
                                )

                                // Speech rate
                                SliderRow(
                                    label = "Speech Rate",
                                    value = uiState.speechRate,
                                    onValueChange = viewModel::setSpeechRate
                                )

                                // Pitch
                                SliderRow(
                                    label = "Pitch",
                                    value = uiState.pitch,
                                    onValueChange = viewModel::setPitch
                                )

                                // Preview button
                                Button(
                                    onClick = viewModel::previewTts,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryPurple.copy(alpha = 0.1f),
                                        contentColor = PrimaryPurple
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Preview Voice", fontWeight = FontWeight.SemiBold)
                                }

                                HorizontalDivider(color = DividerColor)

                                // Installed engines list
                                if (uiState.ttsEngines.isNotEmpty()) {
                                    Text("Installed Engines", style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim)
                                    uiState.ttsEngines.forEach { engine ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Settings, null, tint = OnSurfaceDim, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(engine.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurfaceDark)
                                                Text(engine.packageName, fontSize = 11.sp, color = OnSurfaceLight)
                                            }
                                        }
                                    }
                                }

                                // Link to system TTS settings
                                SystemSettingsLink("Manage TTS Engines") {
                                    context.startActivity(viewModel.createTtsSettingsIntent())
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════
            // 2. SPEECH-TO-TEXT
            // ═══════════════════════════════════════════════════
            item { SettingsSectionHeader("🎙️  Speech-to-Text") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        SettingsToggleRow(
                            title = "Enable STT",
                            subtitle = "Use voice input for chat",
                            icon = Icons.Default.Mic,
                            checked = uiState.isSttEnabled,
                            onCheckedChange = viewModel::setSttEnabled,
                            trailing = {
                                StatusChip(
                                    if (uiState.isSttAvailable) "Available" else "Unavailable",
                                    if (uiState.isSttAvailable) StatusOnTime else ErrorRed
                                )
                            }
                        )

                        AnimatedVisibility(visible = uiState.isSttEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                HorizontalDivider(color = DividerColor)

                                SettingsDropdown(
                                    label = "Recognition Language",
                                    selected = uiState.sttLanguage,
                                    options = uiState.availableSttLocales,
                                    displayTransform = { tag ->
                                        val loc = Locale.forLanguageTag(tag)
                                        "${loc.displayLanguage} (${loc.displayCountry})"
                                    },
                                    onSelected = viewModel::setSttLanguage
                                )

                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Prefer Offline Recognition", fontWeight = FontWeight.SemiBold, color = OnSurfaceDark, fontSize = 14.sp)
                                        Text("Uses downloaded language models", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                                    }
                                    Switch(
                                        checked = uiState.preferOfflineStt,
                                        onCheckedChange = viewModel::setPreferOfflineStt,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = PrimaryPurple,
                                            uncheckedThumbColor = Color.White,
                                            uncheckedTrackColor = Color(0xFFD4D4D8)
                                        )
                                    )
                                }

                                SystemSettingsLink("Download Offline Models") {
                                    context.startActivity(viewModel.createAccessibilitySettingsIntent())
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════
            // 3. BLUETOOTH & BEACONS
            // ═══════════════════════════════════════════════════
            item { SettingsSectionHeader("📡  Bluetooth & Beacons") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, null, tint = PrimaryPurple, modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PrimaryPurple.copy(alpha = 0.1f))
                                .padding(10.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Bluetooth", fontWeight = FontWeight.SemiBold, color = OnSurfaceDark)
                                Text("Required for beacon detection & SOS", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            }
                            StatusChip(
                                if (uiState.isBluetoothEnabled) "Enabled" else "Disabled",
                                if (uiState.isBluetoothEnabled) StatusOnTime else ErrorRed
                            )
                        }

                        SystemSettingsLink("Open Bluetooth Settings") {
                            context.startActivity(viewModel.createBluetoothSettingsIntent())
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════
            // 5. ABOUT & DEVICE INFO
            // ═══════════════════════════════════════════════════
            item { SettingsSectionHeader("ℹ️  About") }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow("App Version", uiState.appVersion)
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                        InfoRow("Device", uiState.deviceModel)
                        InfoRow("Android", uiState.androidVersion)
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                        InfoRow("AI Model", uiState.modelFileName)
                        InfoRow("Model Size", uiState.modelFileSize)
                        InfoRow("GPU Acceleration", if (uiState.gpuAvailable) "Available" else "Not Available")
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
