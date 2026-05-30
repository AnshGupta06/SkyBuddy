package com.example.skysecurity.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StopSoundBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private val scope = CoroutineScope(Dispatchers.Main)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.d(TAG, "Stop Sound broadcast started")
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Stop Sound broadcast failed (error $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun broadcastStopSound(deviceTag: String) {
        Log.d(TAG, "Initiating remote stop broadcast for tag: $deviceTag")
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) return
        
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) return

        if (isAdvertising) {
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) {}
            isAdvertising = false
        }

        // Payload format: SBSOS_STOP:[Tag]
        val payload = "SBSOS_STOP:[$deviceTag]"
        
        try {
            adapter.name = payload
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to set adapter name", e)
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        // Use a dedicated Manufacturer ID (0xFFFE) to avoid collisions with SOS emitter (0xFFFF)
        // and ensure the payload is in the primary advertisement packet.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false) 
            .addManufacturerData(0xFFFE, payload.toByteArray(Charsets.UTF_8))
            .build()
            
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            
            // Auto-stop after 10 seconds
            scope.launch {
                delay(10000)
                stopBroadcast()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBroadcast() {
        if (!isAdvertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.name = "Android"
        } catch (_: SecurityException) {}
        isAdvertising = false
    }

    companion object {
        private const val TAG = "StopSoundBroadcaster"
    }
}
