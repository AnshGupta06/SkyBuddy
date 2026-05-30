package com.example.skybuddy.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FlightAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val flightNumber = intent.getStringExtra(EXTRA_FLIGHT_NUMBER) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Flight Update"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "You have an upcoming flight $flightNumber."

        Log.d("FlightAlarmReceiver", "Alarm fired for $flightNumber: $title")
        
        notificationHelper.notifyFlightUpdate(
            flightNumber = flightNumber,
            title = title,
            message = message
        )
    }

    companion object {
        const val EXTRA_FLIGHT_NUMBER = "flight_number"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
    }
}
