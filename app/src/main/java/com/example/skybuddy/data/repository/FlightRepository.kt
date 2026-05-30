package com.example.skybuddy.data.repository

import android.util.Log
import com.example.skybuddy.BuildConfig
import com.example.skybuddy.core.result.AppResult
import com.example.skybuddy.core.result.ErrorReason
import com.example.skybuddy.core.time.Clock
import com.example.skybuddy.data.db.FlightDao
import com.example.skybuddy.data.db.FlightEntity
import com.example.skybuddy.data.db.TrackingState
import com.example.skybuddy.data.network.NetworkMonitor
import com.example.skybuddy.data.network.airlabs.AirLabsApi
import com.example.skybuddy.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlightRepository @Inject constructor(
    private val flightDao: FlightDao,
    private val airLabsApi: AirLabsApi,
    private val networkMonitor: NetworkMonitor,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    fun observeUpcoming(): Flow<List<FlightEntity>> = flightDao.observeUpcoming()
    fun observePast(): Flow<List<FlightEntity>> = flightDao.observePast()
    fun observeFlight(flightNumber: String): Flow<FlightEntity?> = flightDao.observeFlight(flightNumber.uppercase())

    suspend fun getFlight(flightNumber: String): FlightEntity? = withContext(io) {
        flightDao.getFlight(flightNumber.uppercase())
    }

    suspend fun getAll(): List<FlightEntity> = withContext(io) { flightDao.getAll() }

    suspend fun updateSeat(flightNumber: String, seat: String) = withContext(io) {
        flightDao.updateSeat(flightNumber.uppercase(), seat)
    }

    suspend fun upsert(flight: FlightEntity) = withContext(io) {
        flightDao.upsert(flight)
    }

    suspend fun deleteFlight(flightNumber: String) = withContext(io) {
        flightDao.delete(flightNumber.uppercase())
    }

    /** Mark flights that have already departed as COMPLETED. */
    suspend fun completePastFlights() = withContext(io) {
        // 6 hours after departure time → considered completed
        val cutoff = clock.nowMillis() - (6 * 60 * 60 * 1000)
        flightDao.completePastFlights(cutoff)
    }

    suspend fun refresh(flightNumber: String): AppResult<FlightEntity> = withContext(io) {
        val number = flightNumber.uppercase()
        val apiKey = BuildConfig.AIRLABS_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_AIRLABS_KEY") {
            val cached = flightDao.getFlight(number)
            if (cached != null) return@withContext AppResult.Success(cached)
            
            // If no cache and no API key, provide mock data for common flights or a generic demo flight
            Log.w(TAG, "AirLabs key not configured. Providing mock data for $number")
            val mockFlight = generateMockFlight(number)
            flightDao.upsert(mockFlight)
            return@withContext AppResult.Success(mockFlight)
        }
        if (!networkMonitor.isOnline()) {
            val cached = flightDao.getFlight(number)
            return@withContext if (cached != null) AppResult.Success(cached)
            else AppResult.Error(ErrorReason.Offline)
        }
        try {
            val response = airLabsApi.getFlightSchedule(apiKey, number)
            val schedules = response.response
            if (schedules.isNullOrEmpty()) {
                val cached = flightDao.getFlight(number)
                if (cached != null) return@withContext AppResult.Success(cached)
                
                // Fallback to mock data if not found in API and not in cache
                Log.i(TAG, "Flight $number not found in AirLabs. Using mock data.")
                val mockFlight = generateMockFlight(number)
                flightDao.upsert(mockFlight)
                return@withContext AppResult.Success(mockFlight)
            }
            val schedule = schedules.first()
            val statusStr = schedule.status?.replaceFirstChar { it.uppercase() } ?: "Scheduled"
            val isCompleted = statusStr.equals("Landed", true) ||
                statusStr.equals("Arrived", true) ||
                statusStr.equals("Cancelled", true)
            val state = if (isCompleted) TrackingState.COMPLETED else TrackingState.TRACKING

            val flight = FlightEntity(
                flightNumber = number,
                airline = parseAirline(number),
                origin = schedule.depIata ?: "Unknown",
                originCity = "Unknown",
                destination = schedule.arrIata ?: "Unknown",
                destCity = "Unknown",
                gate = schedule.depGate ?: "TBD",
                terminal = schedule.depTerminal ?: "TBD",
                status = statusStr,
                time = schedule.depTime ?: "Unknown",
                seat = existingSeat(number),
                lastSyncedAt = clock.nowMillis(),
                departureTimeEpoch = parseDepartureEpoch(schedule.depTime),
                trackingState = state.name
            )
            flightDao.upsert(flight)
            AppResult.Success(flight)
        } catch (e: Exception) {
            Log.e(TAG, "AirLabs sync failed for $number", e)
            val cached = flightDao.getFlight(number)
            if (cached != null) AppResult.Success(cached)
            else AppResult.Error(ErrorReason.Network(e.message ?: "Unknown network error"), e)
        }
    }

    suspend fun refreshAllTracked(): List<Pair<FlightEntity, FlightEntity?>> = withContext(io) {
        val tracked = flightDao.getTracked()
        coroutineScope {
            tracked.map { old ->
                async {
                    val result = refresh(old.flightNumber)
                    val updated = (result as? AppResult.Success<FlightEntity>)?.value
                    old to updated
                }
            }.awaitAll()
        }
    }

    private fun parseAirline(flightNumber: String): String = when {
        flightNumber.startsWith("AA", true) -> "American Airlines"
        flightNumber.startsWith("DL", true) -> "Delta Air Lines"
        flightNumber.startsWith("UA", true) -> "United Airlines"
        flightNumber.startsWith("WN", true) -> "Southwest Airlines"
        flightNumber.startsWith("BA", true) -> "British Airways"
        flightNumber.startsWith("LH", true) -> "Lufthansa"
        flightNumber.startsWith("AI", true) -> "Air India"
        flightNumber.startsWith("6E", true) -> "IndiGo"
        flightNumber.startsWith("UK", true) -> "Vistara"
        flightNumber.startsWith("SG", true) -> "SpiceJet"
        flightNumber.startsWith("QP", true) -> "Akasa Air"
        flightNumber.startsWith("I5", true) -> "AIX Connect"
        else -> flightNumber.take(2).uppercase()
    }

    /** Preserve existing seat if it was previously set (e.g. from boarding pass scan). */
    private suspend fun existingSeat(flightNumber: String): String {
        val existing = flightDao.getFlight(flightNumber)?.seat
        return if (!existing.isNullOrBlank() && !existing.equals("Unknown", true)) existing
        else "Unknown"
    }

    private fun generateMockFlight(number: String): FlightEntity {
        val (origin, dest) = when {
            number.startsWith("AA") || number.startsWith("DL") || number.startsWith("UA") -> "JFK" to "LAX"
            number.startsWith("BA") || number.startsWith("LH") -> "LHR" to "FRA"
            else -> "DEL" to "BOM"
        }
        return FlightEntity(
            flightNumber = number,
            airline = parseAirline(number),
            origin = origin,
            originCity = "Origin City",
            destination = dest,
            destCity = "Destination City",
            gate = "T${(1..3).random()}-${(10..99).random()}",
            terminal = (1..3).random().toString(),
            status = "Scheduled (Mock)",
            time = "2024-05-04 12:00",
            seat = "${(1..30).random()}${listOf("A", "B", "C", "D", "E", "F").random()}",
            lastSyncedAt = clock.nowMillis(),
            // Set departure to 65 minutes from now so the 60-minute alarm triggers in 5 minutes
            departureTimeEpoch = clock.nowMillis() + (65 * 60 * 1000L),
            trackingState = TrackingState.TRACKING.name
        )
    }

    companion object { private const val TAG = "FlightRepository" }

    /** Parse departure time string from API into epoch millis. */
    private fun parseDepartureEpoch(depTime: String?): Long {
        if (depTime.isNullOrBlank()) return clock.nowMillis()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "HH:mm"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val date = sdf.parse(depTime) ?: continue
                val epoch = date.time
                // If pattern was "HH:mm" only, the date defaults to Jan 1 1970.
                // In that case, use today's date with the parsed time.
                if (epoch < 86_400_000L) {
                    val now = java.util.Calendar.getInstance()
                    now.set(java.util.Calendar.HOUR_OF_DAY, date.hours)
                    now.set(java.util.Calendar.MINUTE, date.minutes)
                    now.set(java.util.Calendar.SECOND, 0)
                    now.set(java.util.Calendar.MILLISECOND, 0)
                    return now.timeInMillis
                }
                return epoch
            } catch (_: Exception) { /* try next */ }
        }
        return clock.nowMillis()
    }
}
