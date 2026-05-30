package com.example.skybuddy.shared.beacon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Reusable watchdog that keeps beacon BLE scanning/advertising alive.
 *
 * It holds a partial [PowerManager.WakeLock] so the CPU doesn't sleep,
 * and runs a periodic heartbeat (every [intervalMs]) that calls [isAlive].
 * When [isAlive] returns `false` it:
 *   1. Calls [restart] to bring the beacon back up.
 *   2. Fires a push notification.
 *   3. Emits a message on [recoveryEvents] for the UI to display.
 */
class BeaconWatchdog(
    private val context: Context,
    private val tag: String,
    private val notificationChannelId: String = "beacon_watchdog",
    private val notificationChannelName: String = "Beacon Health",
    private val intervalMs: Long = 30_000L,
    private val isAlive: () -> Boolean,
    private val restart: () -> Unit
) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _recoveryEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val recoveryEvents: SharedFlow<String> = _recoveryEvents.asSharedFlow()

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureNotificationChannel()
    }

    fun start() {
        acquireWakeLock()
        startHeartbeat()
        Log.d(tag, "Watchdog started (interval=${intervalMs}ms)")
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        releaseWakeLock()
        Log.d(tag, "Watchdog stopped")
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    @Suppress("WakelockTimeout") // Intentional: we hold it for the service lifetime
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "skybuddy:$tag"
        ).apply { acquire() }
        Log.d(tag, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(tag, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ── Heartbeat ────────────────────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!isAlive()) {
                    Log.w(tag, "Beacon not alive — triggering recovery")
                    try {
                        restart()
                        val msg = "⚠️ Beacon service recovered automatically"
                        _recoveryEvents.tryEmit(msg)
                        fireNotification(
                            title = "Beacon Recovered",
                            body = "The beacon service went to sleep and was restarted automatically."
                        )
                        Log.i(tag, "Recovery completed successfully")
                    } catch (e: Exception) {
                        Log.e(tag, "Recovery failed", e)
                        _recoveryEvents.tryEmit("❌ Beacon recovery failed: ${e.message}")
                        fireNotification(
                            title = "Beacon Recovery Failed",
                            body = "The beacon service could not be restarted: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                notificationChannelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a beacon service sleeps and is recovered"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun fireNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(context, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Use a tag-based stable ID so we don't stack identical notifications
        notificationManager.notify(tag.hashCode(), notification)
    }
}
