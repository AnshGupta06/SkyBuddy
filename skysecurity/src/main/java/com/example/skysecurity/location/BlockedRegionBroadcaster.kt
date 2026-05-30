package com.example.skysecurity.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import com.example.skybuddy.shared.data.BeaconCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockedRegionBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private val _broadcastingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val broadcastingNodeIds: StateFlow<Set<String>> = _broadcastingNodeIds.asStateFlow()

    // User-facing error/status events — collect in the UI to show Snackbar
    private val _broadcastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val broadcastEvents: SharedFlow<String> = _broadcastEvents.asSharedFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.d(TAG, "Blocked region broadcast started")
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val msg = "Blocked region broadcast failed (error $errorCode)"
            Log.e(TAG, msg)
            _broadcastEvents.tryEmit(msg)
        }
    }

    /**
     * Start (or restart) advertising the current set of blocked node IDs.
     *
     * When [nodeIds] is empty, we still broadcast "SBBLK:" (the clear signal)
     * and keep advertising indefinitely. The previous implementation only
     * advertised the clear for 2 seconds via a Handler.postDelayed — if the
     * main app's scanner didn't happen to catch that narrow window the clear
     * was silently lost. Now the clear stays advertised until the security
     * officer explicitly blocks something new or stops the broadcast.
     */
    @SuppressLint("MissingPermission")
    fun broadcastBlockedNodes(nodeIds: Set<String>) {
        _broadcastingNodeIds.value = nodeIds

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            val msg = "Cannot broadcast: Bluetooth is ${if (adapter == null) "not available" else "turned off"}"
            Log.w(TAG, msg)
            _broadcastEvents.tryEmit(msg)
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            val msg = "Cannot broadcast: BLE advertiser unavailable"
            Log.e(TAG, msg)
            _broadcastEvents.tryEmit(msg)
            return
        }

        // Stop any current advertisement before restarting
        if (isAdvertising) {
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) {}
            isAdvertising = false
        }

        // Build payload — BeaconCodec encodes blocked set as a compact hex
        // bitfield that always fits in the 29-char BLE name limit.
        // Empty set -> "SBBLK:" (clear signal for the main app).
        val payload = BeaconCodec.encodeBlocked(nodeIds)
        Log.d(TAG, "Broadcasting payload (${payload.length} chars): $payload")

        try {
            adapter.name = payload
        } catch (e: SecurityException) {
            val msg = "Blocked region broadcast failed: missing Bluetooth permission"
            Log.e(TAG, msg, e)
            _broadcastEvents.tryEmit(msg)
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        // Use dedicated Manufacturer ID (0xFFFD) for blocked regions
        // to ensure it stays in the primary advertisement packet.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(0xFFFD, payload.toByteArray(Charsets.UTF_8))
            .build()
            
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            val msg = "Blocked region broadcast failed: missing advertise permission"
            Log.e(TAG, msg, e)
            _broadcastEvents.tryEmit(msg)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBroadcast() {
        _broadcastingNodeIds.value = emptySet()

        if (!isAdvertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.name = "Android"
        } catch (_: SecurityException) {}
        isAdvertising = false
        Log.d(TAG, "Blocked region broadcast stopped")
    }

    companion object {
        private const val TAG = "BlockedBroadcaster"
    }
}
