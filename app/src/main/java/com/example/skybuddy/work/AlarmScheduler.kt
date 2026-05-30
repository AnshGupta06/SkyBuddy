package com.example.skybuddy.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedulePreflightAlarm(flightNumber: String, departureTimeEpoch: Long) {
        val milestones = listOf(
            6 * 60 to ("T-Minus 6 Hours!" to "Time to pack and review your SkyBuddy checklist."),
            3 * 60 to ("Check-in Open" to "Baggage drop is open. Head to the check-in counters."),
            2 * 60 to ("Security Check" to "Time to proceed through security check."),
            1 * 60 to ("Boarding Soon" to "Boarding will start soon. Head towards your gate."),
            45 to ("Hurry Up!" to "Boarding has started! Please proceed to the gate immediately.")
        )

        for ((minutesBefore, texts) in milestones) {
            val alarmTime = departureTimeEpoch - (minutesBefore * 60 * 1000L)
            if (alarmTime > System.currentTimeMillis()) {
                val intent = Intent(context, FlightAlarmReceiver::class.java).apply {
                    putExtra(FlightAlarmReceiver.EXTRA_FLIGHT_NUMBER, flightNumber)
                    putExtra(FlightAlarmReceiver.EXTRA_TITLE, texts.first)
                    putExtra(FlightAlarmReceiver.EXTRA_MESSAGE, texts.second)
                }

                val requestCode = (flightNumber + minutesBefore).hashCode()
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        alarmTime,
                        pendingIntent
                    )
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }

    }
}
