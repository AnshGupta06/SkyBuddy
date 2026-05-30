package com.example.skybuddy.domain.usecase

import com.example.skybuddy.ai.LlmEngine
import com.example.skybuddy.data.db.TimelineEventDao
import com.example.skybuddy.data.db.TimelineEventEntity
import com.example.skybuddy.work.NotificationHelper
import javax.inject.Inject

class EvaluateAmbientBeaconUseCase @Inject constructor(
    private val llmEngine: LlmEngine,
    private val timelineEventDao: TimelineEventDao,
    private val notificationHelper: NotificationHelper
) {
    suspend operator fun invoke(beaconPayload: String, locationName: String): String {
        notificationHelper.notifyFlightUpdate(
            flightNumber = "BEACON",  // Reusing the notification method for debugging
            title = "$locationName RECEIVED",
            message = beaconPayload
        )
        val prompt = "User is at $locationName. Beacon payload: '$beaconPayload'. Act as an intelligent airport  assistant. Write exactly 1 sentence of helpful, engaging information or a tip related to this location and payload. Do not add any conversational preamble or explain what the beacon is."
        val response = llmEngine.generateOneOffText(prompt)
        
        timelineEventDao.insert(
            TimelineEventEntity(
                timestamp = System.currentTimeMillis(),
                role = "SYSTEM_SPATIAL",
                uiComponentType = "MAP_TOAST",
                content = response.trim()
            )
        )
        
        notificationHelper.notifyFlightUpdate(
            flightNumber = "BEACON",  // Reusing the notification method for debugging
            title = "$locationName",
            message = response.trim()
        )

        return response.trim()
    }
}
