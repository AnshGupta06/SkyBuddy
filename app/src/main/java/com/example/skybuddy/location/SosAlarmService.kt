package com.example.skybuddy.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.skybuddy.R

class SosAlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null

    companion object {
        const val ACTION_STOP_ALARM = "com.example.skybuddy.action.STOP_ALARM"
        const val CHANNEL_ID = "sos_alarm_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            stopAlarm()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, SosAlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚨 EMERGENCY SOS ACTIVE")
            .setContentText("Security staff have been notified of your location. Stay calm.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, android.R.drawable.ic_dialog_alert))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS ALARM", stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(android.graphics.Color.RED)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, R.raw.sos_alarm)
                mediaPlayer?.isLooping = true
            }
            if (mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            Log.e("SosAlarmService", "Failed to play alarm sound", e)
        }

        return START_STICKY
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("SosAlarmService", "Error stopping MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOS Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS active alarm"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
