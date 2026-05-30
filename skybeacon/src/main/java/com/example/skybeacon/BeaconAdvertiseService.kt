package com.example.skybeacon

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.skybuddy.shared.beacon.BeaconWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the SkyBeacon BLE advertising alive.
 *
 * Previously, advertising ran entirely within [MainActivity] and died when
 * the Activity went to background. This service wraps advertising in a
 * foreground notification and attaches a [BeaconWatchdog] that auto-restarts
 * advertising if the OS kills it.
 *
 * Since SkyBeacon doesn't use Hilt, advertising state is shared with the
 * Activity via static references and Intent actions.
 */
class BeaconAdvertiseService : Service() {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var watchdog: BeaconWatchdog? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    var isAdvertising = false
        private set

    private var lastPayload: String? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.d(TAG, "Advertising started via service")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed in service: error $errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        advertiser = btManager?.adapter?.bluetoothLeAdvertiser

        watchdog = BeaconWatchdog(
            context = applicationContext,
            tag = "SkyBeaconWatchdog",
            notificationChannelName = "Beacon Broadcast Health",
            isAlive = { isAdvertising || lastPayload == null },
            restart = {
                lastPayload?.let { startAdvertisingPayload(it) }
            }
        ).also { wd ->
            wd.start()
            serviceScope.launch {
                wd.recoveryEvents.collect { msg ->
                    Log.w(TAG, "Watchdog event: $msg")
                    // Show a Toast on the main thread
                    launch(Dispatchers.Main) {
                        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        Log.d(TAG, "BeaconAdvertiseService created")
    }

    private fun startForegroundNotification() {
        val channelId = "beacon_broadcast_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Beacon Broadcast",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps BLE beacon advertising active in background" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SkyBeacon Active")
            .setContentText("Broadcasting beacon signals...")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()

        startForeground(3001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ADVERTISING -> {
                val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: return START_STICKY
                startAdvertisingPayload(payload)
            }
            ACTION_STOP_ADVERTISING -> {
                stopAdvertisingPayload()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingPayload(payload: String) {
        lastPayload = payload

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return
        advertiser = adapter.bluetoothLeAdvertiser ?: return

        // Stop any current advertising first
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) {}
        isAdvertising = false

        val part1 = payload.take(29)
        val part2 = if (payload.length > 29) payload.substring(29).take(24) else ""

        try {
            adapter.name = part1
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val advertiseDataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(part2.isEmpty())

        if (part2.isNotEmpty()) {
            advertiseDataBuilder.addManufacturerData(0xFFFF, part2.toByteArray(Charsets.UTF_8))
        }
        val advertiseData = advertiseDataBuilder.build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(part2.isNotEmpty())
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingPayload() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.name = "Android"
        } catch (_: SecurityException) {}
        isAdvertising = false
        lastPayload = null
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
        stopAdvertisingPayload()
        instance = null
        Log.d(TAG, "BeaconAdvertiseService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BeaconAdvSvc"
        const val ACTION_START_ADVERTISING = "com.example.skybeacon.action.START_ADVERTISING"
        const val ACTION_STOP_ADVERTISING = "com.example.skybeacon.action.STOP_ADVERTISING"
        const val EXTRA_PAYLOAD = "payload"

        /** Accessible from [MainActivity] for advertising state queries. */
        @Volatile
        var instance: BeaconAdvertiseService? = null
            private set
    }
}
