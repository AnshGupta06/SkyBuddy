package com.example.skybuddy.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

@AndroidEntryPoint
class LocationTrackerService : Service(), SensorEventListener {

    @Inject
    lateinit var indoorLocationManager: IndoorLocationManager

    @Inject
    lateinit var dynamicBeaconReceiver: DynamicBeaconReceiver

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private var watchdog: BeaconWatchdog? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        startForegroundService()
        registerSensors()
        dynamicBeaconReceiver.startScanning()

        // ── Watchdog: keeps BLE scanning alive ──────────────────────────────
        watchdog = BeaconWatchdog(
            context = applicationContext,
            tag = "SkyBuddyBeaconWatchdog",
            isAlive = { dynamicBeaconReceiver.isScanning },
            restart = { dynamicBeaconReceiver.startScanning() }
        ).also { wd ->
            wd.start()
            // Forward watchdog recovery events into the DynamicBeaconReceiver's
            // existing beaconEvents flow so the ViewModel and UI pick them up
            // automatically via the same Snackbar + Dynamic Island pipeline.
            serviceScope.launch {
                wd.recoveryEvents.collect { msg ->
                    dynamicBeaconReceiver.emitEvent(msg)
                }
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "location_tracker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Indoor Positioning",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SkyBuddy Navigation")
            .setContentText("Tracking indoor location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun registerSensors() {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /** Stop the service when the user swipes the app from recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("LocationTrackerService", "Task removed — stopping service")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdog?.stop()
        watchdog = null
        sensorManager.unregisterListener(this)
        dynamicBeaconReceiver.stopScanning()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                indoorLocationManager.onStepDetected()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[0] is azimuth (heading in radians)
                indoorLocationManager.updateHeading(orientation[0])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
