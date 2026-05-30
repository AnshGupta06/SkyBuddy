package com.example.skybuddy.ui.nav

object Routes {
    const val ONBOARDING = "onboarding"
    const val MODEL_LOAD = "model_load"
    const val HOME = "home"
    const val JOURNEY_SELECT_PATTERN = "journey_select/{flightNumber}"
    const val INDOOR_MAP_PATTERN = "indoor_map/{flightNumber}"
    const val CHAT_PATTERN = "chat/{flightNumber}"
    const val FLIGHT_INFO_PATTERN = "flight_info/{flightNumber}"
    const val SETTINGS = "settings"

    fun journeySelect(flightNumber: String): String = "journey_select/${android.net.Uri.encode(flightNumber)}"
    fun indoorMap(flightNumber: String): String = "indoor_map/${android.net.Uri.encode(flightNumber)}"
    fun chat(flightNumber: String): String = "chat/${android.net.Uri.encode(flightNumber)}"
    fun flightInfo(flightNumber: String): String = "flight_info/${android.net.Uri.encode(flightNumber)}"
}
