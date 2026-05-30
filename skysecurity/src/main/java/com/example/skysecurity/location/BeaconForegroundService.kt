package com.example.skysecurity.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.skybuddy.shared.beacon.BeaconWatchdog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the SkySecurity BLE scanner alive.
 *
 * Previously, `SOSBeaconScanner` ran entirely in Activity scope and died
 * when the Activity went to background. This service wraps the scanner in a
 * foreground notification so Android doesn't kill it, and attaches a
 * [BeaconWatchdog] that auto-restarts scanning if the OS still manages to
 * shut it down (e.g., extreme Doze pressure).
 */
@AndroidEntryPoint
class BeaconForegroundService : Service() {

    @Inject lateinit var sosScanner: SOSBeaconScanner
    @Inject lateinit var blockedBroadcaster: BlockedRegionBroadcaster

    private var watchdog: BeaconWatchdog? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        sosScanner.startScanning()

        watchdog = BeaconWatchdog(
            context = applicationContext,
            tag = "SkySecurityBeaconWatchdog",
            notificationChannelName = "Security Beacon Health",
            isAlive = { sosScanner.isScanning },
            restart = { sosScanner.startScanning() }
        ).also { wd ->
            wd.start()
            // Forward recovery events so UI can show Snackbar
            serviceScope.launch {
                wd.recoveryEvents.collect { msg ->
                    sosScanner.emitEvent(msg)
                }
            }
        }

        Log.d(TAG, "BeaconForegroundService created")
    }

    private fun startForegroundNotification() {
        val channelId = "security_beacon_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Security Beacon",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the SOS scanner active in background" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SkySecurity Active")
            .setContentText("Monitoring for SOS beacons...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        startForeground(2001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — stopping service")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdog?.stop()
        watchdog = null
        sosScanner.stopScanning()
        Log.d(TAG, "BeaconForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SecurityBeaconSvc"
    }
}
