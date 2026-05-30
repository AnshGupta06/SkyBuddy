package com.example.skybuddy.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.skybuddy.domain.usecase.EvaluateAmbientBeaconUseCase
import com.example.skybuddy.shared.data.BeaconCodec
import com.example.skybuddy.shared.location.BlockedRegionManager
import com.example.skybuddy.shared.location.IndoorLocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicBeaconReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val evaluateAmbientBeacon: EvaluateAmbientBeaconUseCase,
    private val indoorLocationManager: IndoorLocationManager,
    private val blockedRegionManager: BlockedRegionManager
) {
    var isScanning = false
        private set
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val processedBeacons: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    // Track last-seen blocked set to avoid redundant StateFlow emissions
    @Volatile
    private var lastBlockedSet: Set<String> = emptySet()

    // User-facing error/status events — collect in the UI to show Snackbar
    private val _beaconEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val beaconEvents: SharedFlow<String> = _beaconEvents.asSharedFlow()

    /** Called by the watchdog service to inject recovery events into the beacon pipeline. */
    fun emitEvent(msg: String) {
        _beaconEvents.tryEmit(msg)
    }

    /** Beacon offer events: Pair(locationName, offerText) — consumed by Dynamic Island. */
    private val _beaconOffers = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val beaconOffers: SharedFlow<Pair<String, String>> = _beaconOffers.asSharedFlow()

    /** LLM-generated beacon insight text — surfaces in the Dynamic Island expanded view. */
    private val _beaconInsights = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val beaconInsights: SharedFlow<String> = _beaconInsights.asSharedFlow()


    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            try {
                result?.scanRecord?.let { scanRecord ->
                    // Use the advertised device name from the scan record only.
                    // BluetoothDevice.getName() requires BLUETOOTH_CONNECT, which we do not hold.
                    // 1. Collect all potential data sources
                    val dataParts = listOfNotNull(
                        scanRecord.deviceName,
                        scanRecord.getManufacturerSpecificData(0xFFFF)?.let { String(it, Charsets.UTF_8) },
                        scanRecord.getManufacturerSpecificData(0xFFFE)?.let { String(it, Charsets.UTF_8) },
                        scanRecord.getManufacturerSpecificData(0xFFFD)?.let { String(it, Charsets.UTF_8) }
                    )

                    if (dataParts.isEmpty()) return

                    // 2. Search across all parts for our special prefixes
                    
                    // ─── Handle blocked-region beacons ───
                    dataParts.find { it.contains("SBBLK:") }?.let { source ->
                        val index = source.indexOf("SBBLK:")
                        val payload = source.substring(index)
                        val nodeIds = BeaconCodec.decodeBlocked(payload)
                        if (nodeIds != lastBlockedSet) {
                            lastBlockedSet = nodeIds
                            Log.d(TAG, "Blocked regions updated: $nodeIds")
                            blockedRegionManager.setBlockedNodes(nodeIds)
                        }
                        return
                    }

                    // ─── Handle SOS stop signal from security ───
                    dataParts.find { it.contains("SBSOS_STOP:") }?.let { source ->
                        val index = source.indexOf("SBSOS_STOP:")
                        val payload = source.substring(index).removePrefix("SBSOS_STOP:")
                        if (payload.startsWith("[") && payload.contains("]")) {
                            val tag = payload.substring(1, payload.indexOf("]")).trim()
                            val myId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0000"
                            val myTag = myId.takeLast(4).uppercase()
                            
                            if (tag == myTag) {
                                Log.d(TAG, "MATCH! Stopping SOS alarm service for tag: $tag")
                                context.stopService(Intent(context, SosAlarmService::class.java))
                            } else {
                                Log.d(TAG, "Stop signal received for different tag: $tag (mine is $myTag)")
                            }
                        }
                        return
                    }

                    // ─── Handle SOS beacons (just log) ───
                    dataParts.find { it.contains("SBSOS:") }?.let { source ->
                        Log.d(TAG, "SOS beacon seen: $source")
                        return
                    }

                    // ─── Handle shop/offer beacons ───
                    dataParts.find { it.contains("SB:") }?.let { source ->
                        val index = source.indexOf("SB:")
                        val payload = source.substring(index).removePrefix("SB:")
                        val parts = payload.split("|")
                        if (parts.size >= 2) {
                            val locationName = parts[0].trim()
                            val offer = parts[1].trim()

                            val uniqueKey = "$locationName-$offer"
                            if (processedBeacons.add(uniqueKey)) {
                                Log.d(TAG, "Intercepted: $locationName - $offer")

                                when (locationName) {
                                    "Costa" -> indoorLocationManager.calibratePosition(700f, 700f)
                                    "DutyFree" -> indoorLocationManager.calibratePosition(500f, 600f)
                                }

                                // Surface the offer in the Dynamic Island
                                _beaconOffers.tryEmit(locationName to offer)

                                coroutineScope.launch {
                                    val llmInsight = evaluateAmbientBeacon(offer, locationName)
                                    _beaconInsights.tryEmit(llmInsight)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing scan result", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Log.e(TAG, "BLE scan failed: $reason (code=$errorCode)")
            _beaconEvents.tryEmit("Beacon scan failed: $reason")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            val msg = "Beacon scanning unavailable: Bluetooth is ${if (bluetoothAdapter == null) "not available" else "turned off"}"
            Log.w(TAG, msg)
            _beaconEvents.tryEmit(msg)
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Cannot start scanning: BluetoothLeScanner is null (BT may be turning off)")
            _beaconEvents.tryEmit("Beacon scanning unavailable: Bluetooth is turning off")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scanning started (low-latency / aggressive)")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter?.isEnabled == true) {
            val scanner = bluetoothAdapter.bluetoothLeScanner
            scanner?.stopScan(scanCallback)
            isScanning = false
            processedBeacons.clear()
            lastBlockedSet = emptySet()
            Log.d(TAG, "BLE scanning stopped")
        }
    }

    companion object {
        private const val TAG = "DynamicBeaconReceiver"
    }
}
