package com.example.skybuddy.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import android.os.Build
import com.example.skybuddy.core.permission.rememberMultiplePermissionsController
import com.example.skybuddy.core.permission.rememberPermissionController
import java.io.File
import com.example.skybuddy.ui.flight.ExpandableFlightCard
import com.example.skybuddy.ui.theme.BackgroundGray
import com.example.skybuddy.ui.theme.GlassCard
import com.example.skybuddy.ui.theme.OnSurfaceDark
import com.example.skybuddy.ui.theme.OnSurfaceDim
import com.example.skybuddy.ui.theme.PrimaryPurple
import com.example.skybuddy.ui.vault.DatabaseViewerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onFlightTapped: (String) -> Unit,
    onSettingsClicked: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val upcoming by viewModel.upcoming.collectAsState()
    val past by viewModel.past.collectAsState()
    var showVault by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ── Permissions ──
    val permissionsController = rememberMultiplePermissionsController { _ -> }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionsController.request(missingPermissions.toTypedArray())
        }
    }

    // ── Image pickers ──
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            uri?.let {
                coroutineScope.launch {
                    val result = viewModel.ingestFlight(context, it)
                    if (result.isFailure) {
                        Toast.makeText(context, result.exceptionOrNull()?.message ?: "Failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                tempPhotoUri?.let { uri ->
                    coroutineScope.launch {
                        val result = viewModel.ingestFlight(context, uri)
                        if (result.isFailure) {
                            Toast.makeText(context, result.exceptionOrNull()?.message ?: "Failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    )

    val cameraPermission = rememberPermissionController { granted ->
        if (granted) {
            val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            tempPhotoUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Staggered entrance ──
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); showContent = true }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Top Bar ──
        item {
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -it / 4 }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "My Flights",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = OnSurfaceDark
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onSettingsClicked, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = OnSurfaceDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Refresh button
                        IconButton(
                            onClick = { viewModel.refreshAll() },
                            enabled = !ui.isRefreshing,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (ui.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = PrimaryPurple,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Refresh",
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        // Saved badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(PrimaryPurple)
                                .clickable { showVault = true }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.BookmarkBorder,
                                    contentDescription = "Saved",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Saved", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ── Add Flight Card ──
        item {
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(500, delayMillis = 150)) + slideInVertically(tween(500, delayMillis = 150)) { it / 4 }
            ) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {

                        Text(
                            "Add a Flight",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = OnSurfaceDark
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scan your boarding pass or enter a flight number",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )

                        Spacer(Modifier.height(16.dp))

                        // ── Scan & Upload buttons (primary action) ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Scan Boarding Pass
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(PrimaryPurple.copy(alpha = 0.08f))
                                    .clickable {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.CAMERA
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            tempPhotoUri = uri
                                            cameraLauncher.launch(uri)
                                        } else {
                                            cameraPermission.request(Manifest.permission.CAMERA)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Filled.CameraAlt,
                                        contentDescription = "Scan",
                                        tint = PrimaryPurple,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Scan Pass",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = PrimaryPurple
                                    )
                                }
                            }

                            // Upload from Gallery
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFFF3F4F6))
                                    .clickable {
                                        photoPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = "Gallery",
                                        tint = OnSurfaceDark,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "From Gallery",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = OnSurfaceDark
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // ── OR divider ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE5E7EB))
                            Text(
                                "OR",
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = OnSurfaceDim
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE5E7EB))
                        }

                        Spacer(Modifier.height(18.dp))

                        // ── Flight number input ──
                        OutlinedTextField(
                            value = ui.input,
                            onValueChange = viewModel::onInputChanged,
                            placeholder = {
                                Text("e.g. AI101, 6E5072, UK833", color = OnSurfaceDim, fontSize = 14.sp)
                            },
                            label = { Text("Flight Number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FlightTakeoff,
                                    contentDescription = null,
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryPurple,
                                unfocusedBorderColor = Color(0xFFD4D4D8),
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color(0xFFFAFAFC),
                                focusedLabelColor = PrimaryPurple,
                                unfocusedLabelColor = OnSurfaceDim,
                                cursorColor = PrimaryPurple
                            )
                        )

                        // ── Track button (appears when input is not blank) ──
                        if (ui.input.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(PrimaryPurple)
                                    .clickable(enabled = !ui.isAdding) { viewModel.addFlight() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (ui.isAdding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Search,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Track Flight",
                                            color = Color.White,
                                            fontWeight = FontWeight.W600,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Status message ──
        ui.message?.let { message ->
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceDark,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::dismissMessage) {
                            Text("Dismiss", color = PrimaryPurple, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ── Flight List ──
        if (upcoming.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader("Upcoming Flights")
            }
            items(upcoming, key = { it.flightNumber }) { flight ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) {
                            viewModel.deleteFlight(flight.flightNumber)
                            true
                        } else false
                    },
                    positionalThreshold = { totalDistance -> totalDistance * 0.6f }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }
                ) {
                    ExpandableFlightCard(
                        flight = flight,
                        onClick = { onFlightTapped(flight.flightNumber) }
                    )
                }
            }
        }

        if (past.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader("Past Flights")
            }
            items(past, key = { it.flightNumber }) { flight ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) {
                            viewModel.deleteFlight(flight.flightNumber)
                            true
                        } else false
                    },
                    positionalThreshold = { totalDistance -> totalDistance * 0.6f }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }
                ) {
                    ExpandableFlightCard(
                        flight = flight,
                        onClick = { onFlightTapped(flight.flightNumber) }
                    )
                }
            }
        }

        // ── Empty state ──
        if (upcoming.isEmpty() && past.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.FlightTakeoff,
                        contentDescription = null,
                        tint = OnSurfaceDim.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No flights tracked yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = OnSurfaceDim
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enter a flight number or scan your boarding pass\nto start tracking with SkyBuddy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Bottom spacing
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showVault) {
        DatabaseViewerDialog(onDismiss = { showVault = false })
    }
}

// ── Section Header ──────────────────────────────────────────
@Composable
private fun SectionHeader(title: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = OnSurfaceDark
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(PrimaryPurple)
        )
    }
}
