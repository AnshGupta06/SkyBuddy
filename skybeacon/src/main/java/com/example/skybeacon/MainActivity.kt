package com.example.skybeacon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AlertDialog
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private lateinit var statusText: TextView

    // Mock UUID for SkyBeacon
    private val pUuid = ParcelUuid(UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        
        checkPermissions()
        startBeaconService()
        requestBatteryOptExemption()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        
        if (adapter == null || !adapter.isMultipleAdvertisementSupported) {
            statusText.text = "Advertising not supported on this device."
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser

        setupNavigation()
        setupSOS()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // Hide bottom nav on login screen
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.loginFragment) {
                bottomNav.visibility = View.GONE
            } else {
                bottomNav.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSOS() {
        val fabSos = findViewById<FloatingActionButton>(R.id.fab_sos)
        fabSos.setOnClickListener {
            val prefs = getSharedPreferences("skybeacon_prefs", Context.MODE_PRIVATE)
            val sosMsg = prefs.getString("custom_sos_message", "SB:SOS|EMERGENCY ALERT - Please contact airport staff immediately") ?: "SB:SOS|EMERGENCY ALERT - Please contact airport staff immediately"

            AlertDialog.Builder(this)
                .setTitle("Emergency SOS")
                .setMessage("Send Emergency Alert to all users?")
                .setPositiveButton("Confirm") { _, _ ->
                    triggerBroadcast(sosMsg.take(53))
                    
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = com.example.skybeacon.data.AppDatabase.getDatabase(this@MainActivity)
                        db.broadcastLogDao().insertLog(
                            com.example.skybeacon.data.BroadcastLog(
                                shopName = "Global",
                                broadcastType = "SOS",
                                content = sosMsg
                            )
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Trigger a broadcast by delegating to the [BeaconAdvertiseService].
     * Falls back to in-process advertising if the service isn't ready.
     */
    fun triggerBroadcast(payload: String) {
        val intent = Intent(this, BeaconAdvertiseService::class.java).apply {
            action = BeaconAdvertiseService.ACTION_START_ADVERTISING
            putExtra(BeaconAdvertiseService.EXTRA_PAYLOAD, payload)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "Broadcasting via service..."
    }

    fun haltBroadcast() {
        val intent = Intent(this, BeaconAdvertiseService::class.java).apply {
            action = BeaconAdvertiseService.ACTION_STOP_ADVERTISING
        }
        startService(intent)
        statusText.text = "Stopped Advertising"
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun startBeaconService() {
        val intent = Intent(this, BeaconAdvertiseService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestBatteryOptExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) { /* Some devices don't support this intent */ }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, BeaconAdvertiseService::class.java))
    }
}
