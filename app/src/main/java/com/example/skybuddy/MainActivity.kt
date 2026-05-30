package com.example.skybuddy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.skybuddy.location.LocationTrackerService
import com.example.skybuddy.ui.nav.Routes
import com.example.skybuddy.ui.nav.SkyBuddyNavGraph
import com.example.skybuddy.ui.onboarding.ModelDownloader
import com.example.skybuddy.ui.theme.SkyBuddyTheme
import com.example.skybuddy.work.FlightSyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelDownloader: ModelDownloader
    @Inject lateinit var flightSyncScheduler: FlightSyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { flightSyncScheduler.ensureScheduled() }
        setContent {
            SkyBuddyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var start by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        val ready = withContext(Dispatchers.IO) { modelDownloader.isDownloaded() }
                        start = if (ready) Routes.MODEL_LOAD else Routes.ONBOARDING
                    }
                    val resolvedStart = start
                    if (resolvedStart != null) {
                        val navController = rememberNavController()
                        SkyBuddyNavGraph(navController = navController, startDestination = resolvedStart)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, LocationTrackerService::class.java))
    }
}
