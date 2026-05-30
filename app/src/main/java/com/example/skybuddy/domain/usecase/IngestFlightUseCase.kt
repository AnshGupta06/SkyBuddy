package com.example.skybuddy.domain.usecase

import android.content.Context
import android.net.Uri
import com.example.skybuddy.ai.LlmEngine
import com.example.skybuddy.core.time.Clock
import com.example.skybuddy.data.db.FlightEntity
import com.example.skybuddy.data.db.TrackingState
import com.example.skybuddy.data.repository.FlightRepository
import com.example.skybuddy.vision.MlKitBarcodeScanner
import com.example.skybuddy.work.AlarmScheduler
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IngestFlightUseCase @Inject constructor(
    private val barcodeScanner: MlKitBarcodeScanner,
    private val textRecognizer: com.example.skybuddy.vision.MlKitTextRecognizer,
    private val llmEngine: LlmEngine,
    private val flightRepository: FlightRepository,
    private val clock: Clock,
    private val alarmScheduler: AlarmScheduler
) {
    suspend operator fun invoke(context: Context, imageUri: Uri): Result<FlightEntity> {
        return try {
            // 1. Try OCR First (Higher priority for Name/Flight number visible on pass)
            val ocrText = textRecognizer.extractText(context, imageUri)
            if (!ocrText.isNullOrBlank()) {
                val ocrResult = processText(ocrText, isBarcode = false)
                if (ocrResult.isSuccess) {
                    val flight = ocrResult.getOrNull()
                    if (flight != null && flight.flightNumber != "UNKNOWN") {
                        return ocrResult
                    }
                }
            }

            // 2. Fallback to Barcode if OCR didn't yield a valid flight
            val rawBarcodeText = barcodeScanner.scanBarcode(context, imageUri)
                ?: return Result.failure(Exception("Could not find flight details in text or barcode"))
            processText(rawBarcodeText, isBarcode = true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend operator fun invoke(bitmap: android.graphics.Bitmap): Result<FlightEntity> {
        return try {
            // 1. Try OCR First
            val ocrText = textRecognizer.extractTextFromBitmap(bitmap)
            if (!ocrText.isNullOrBlank()) {
                val ocrResult = processText(ocrText, isBarcode = false)
                if (ocrResult.isSuccess) {
                    val flight = ocrResult.getOrNull()
                    if (flight != null && flight.flightNumber != "UNKNOWN") {
                        return ocrResult
                    }
                }
            }

            // 2. Fallback to Barcode
            val rawBarcodeText = barcodeScanner.scanBarcodeBitmap(bitmap)
                ?: return Result.failure(Exception("Could not find flight details in text or barcode"))
            processText(rawBarcodeText, isBarcode = true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun processText(rawInput: String, isBarcode: Boolean): Result<FlightEntity> {
        android.util.Log.d("IngestFlightUseCase", "Processing ${if (isBarcode) "barcode" else "OCR"} text: $rawInput")
        return try {
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val currentDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            
            val inputDescription = if (isBarcode) {
                "a raw barcode string (IATA BCBP). Look for 2-char carrier + digits, 3-digit Julian date, and origin/dest codes."
            } else {
                "plain text extracted via OCR from a boarding pass image. Look for explicit labels like 'Flight', 'Gate', 'Seat', 'To', 'From', and 'Date'."
            }

            val prompt = """
                You are a data extraction tool for airport boarding passes. 
                Today's date is $currentDate. The year is $currentYear.
                
                The input is $inputDescription
                
                Rules for extraction:
                1. Flight Number: Look for a 2-character airline code followed by digits. 
                   Common Codes: '6E' (IndiGo), 'AI' (Air India), 'UK' (Vistara), 'SG' (SpiceJet), 'QP' (Akasa), 'AA', 'DL', 'UA', 'BA'.
                   Example: If you see '6E 05072', the flight number is '6E5072' (remove the extra zero).
                2. Origin/Destination: Look for IATA codes or city names.
                3. Date: Calculate the exact YYYY-MM-DD. 
                4. Seat: e.g., '12A', '05C'.
                
                Extract the details and return ONLY a valid JSON object.
                If you cannot find a valid flight number, return {"flightNumber": "UNKNOWN"}.
                
                Expected JSON Format:
                {
                  "flightNumber": "6E5072",
                  "airline": "IndiGo",
                  "origin": "DEL",
                  "destination": "BOM",
                  "gate": "TBD",
                  "seat": "12A",
                  "date": "YYYY-MM-DD",
                  "time": "HH:mm"
                }
                
                Input Text: $rawInput
            """.trimIndent()

            val llmResponse = llmEngine.generateText(prompt)
            android.util.Log.d("IngestFlightUseCase", "LLM Response: $llmResponse")
            
            // More robust JSON extraction: find the first { and last }
            val firstBrace = llmResponse.indexOf('{')
            val lastBrace = llmResponse.lastIndexOf('}')
            
            if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
                return Result.failure(Exception("LLM did not return a valid JSON object. Response: $llmResponse"))
            }
            
            val jsonString = llmResponse.substring(firstBrace, lastBrace + 1)
            val json = JSONObject(jsonString)
            
            // Clean up flight number: uppercase, remove spaces, remove common separators like / or -
            val flightNumber = json.optString("flightNumber", "UNKNOWN")
                .uppercase()
                .replace("\\s+".toRegex(), "")
                .replace("[/-]".toRegex(), "")
            
            val airline = json.optString("airline", "Unknown")
            val origin = json.optString("origin", "Unknown").uppercase()
            val destination = json.optString("destination", "Unknown").uppercase()
            val gate = json.optString("gate", "TBD")
            val seat = json.optString("seat", "TBD")
            val date = json.optString("date", "")
            val time = json.optString("time", "Unknown")
            
            val fullTime = if (date.isNotBlank() && time != "Unknown") "$date $time" else time

            val flight = FlightEntity(
                flightNumber = flightNumber,
                airline = airline,
                origin = origin,
                originCity = "Unknown",
                destination = destination,
                destCity = "Unknown",
                gate = gate,
                terminal = "TBD",
                status = "Scheduled",
                time = time,
                seat = seat,
                lastSyncedAt = clock.nowMillis(),
                departureTimeEpoch = parseDepartureEpoch(fullTime, clock.nowMillis()),
                trackingState = TrackingState.TRACKING.name
            )

            flightRepository.upsert(flight)
            alarmScheduler.schedulePreflightAlarm(flight.flightNumber, flight.departureTimeEpoch)
            Result.success(flight)
        } catch (e: Exception) {
            android.util.Log.e("IngestFlightUseCase", "Ingestion failed", e)
            Result.failure(e)
        }
    }

    /** Parse departure time string into epoch millis. */
    private fun parseDepartureEpoch(depTime: String?, fallback: Long): Long {
        if (depTime.isNullOrBlank()) return fallback
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
        return fallback
    }
}
