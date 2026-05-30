package com.example.skybuddy.ai.tools

import androidx.annotation.Keep
import com.example.skybuddy.data.repository.FlightRepository
import com.example.skybuddy.data.repository.LuggageRepository
import com.example.skybuddy.data.repository.ReceiptRepository
import com.example.skybuddy.data.repository.ChecklistRepository
import com.example.skybuddy.location.SOSBeaconEmitter
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

import com.example.skybuddy.shared.location.IndoorLocationManager

@Keep
@Singleton
class SkyBuddyToolSet @Inject constructor(
    private val application: android.app.Application,
    private val flightRepository: FlightRepository,
    private val luggageRepository: LuggageRepository,
    private val receiptRepository: ReceiptRepository,
    private val airportKb: AirportKnowledgeBaseTool,
    private val indoorLocationManager: IndoorLocationManager,
    private val checklistRepository: ChecklistRepository,
    private val sosBeaconEmitter: SOSBeaconEmitter
) : ToolSet {

    private val activeFlight = AtomicReference<String?>(null)

    // Set by ChatViewModel before each turn; fires a UI status update when a tool starts
    var onToolStarted: ((label: String) -> Unit)? = null

    @Volatile var didQueryReceipts: Boolean = false
        private set

    @Volatile var didQueryLuggage: Boolean = false
        private set

    @Volatile var didTouchFlight: Boolean = false
        private set
        
    @Volatile var didModifyChecklist: Boolean = false
        private set

    fun setActiveFlight(flightNumber: String?) {
        activeFlight.set(flightNumber?.uppercase())
    }

    val activeFlightNumber: String? get() = activeFlight.get()

    fun resetTracking() {
        didQueryReceipts = false
        didQueryLuggage = false
        didTouchFlight = false
        didModifyChecklist = false
        onToolStarted = null
    }

    /** Fires the UI callback with a human-readable status label. */
    private fun notifyTool(label: String) = onToolStarted?.invoke(label)

    @Keep
    @Tool(description = "Search the Bangalore Airport (BLR) database for food, shops, and services. Use this for ANY question about what is available at the airport.")
    fun search(
        @ToolParam(description = "The search query, e.g. 'coffee' or 'pharmacy'.")
        query: String,
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: searchAirport($query)")
        notifyTool("🔍 Searching airport database...")
        try {
            val cx = indoorLocationManager.currentX.value.toDouble()
            val cy = indoorLocationManager.currentY.value.toDouble()
            val res = airportKb.search(
                query = query,
                topK = 5
            )
            android.util.Log.d("SkyBuddy", "Tool: searchAirport result length: ${res.length}")
            res
        } catch (e: Exception) {
            android.util.Log.e("SkyBuddy", "Error in searchAirport: ", e)
            "{\"error\": \"Search failed\", \"pois\": []}"
        }
    }

    @Keep
    @Tool(description = "Get a list of currently active shopping or dining offers/rewards in the airport.")
    fun getCurrentOffers(): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: getCurrentOffers")
        notifyTool("🎁 Fetching current offers...")
        """
        [
            {"store": "Bengaluru Duty Free", "offer": "20% off all perfumes and cosmetics", "skus": ["Chanel No.5", "Dior Sauvage", "Jo Malone"], "validUntil": "Today midnight"},
            {"store": "Starbucks", "offer": "Free upsize on any beverage for SkyBuddy users", "skus": ["Latte", "Cappuccino", "Cold Brew"], "validUntil": "Today"},
            {"store": "Relay Books", "offer": "Buy 2 get 1 free on selected magazines and bestsellers", "skus": ["Magazines", "Paperbacks"], "validUntil": "This week"},
            {"store": "Burger King", "offer": "Free fries with any Whopper meal", "skus": ["Whopper Meal", "Chicken Royale Meal"], "validUntil": "Today"},
            {"store": "Forest Essentials", "offer": "Complimentary gift wrapping + 15% off on orders above ₹2000", "skus": ["Kumkumadi Oil", "Night Cream Set"], "validUntil": "This month"},
            {"store": "M.A.C Cosmetics", "offer": "Free lipstick with purchase of ₹3000+", "skus": ["Ruby Woo", "Velvet Teddy", "Diva"], "validUntil": "This week"},
            {"store": "Punjab Grill", "offer": "10% off for boarding pass holders", "skus": ["Butter Chicken", "Biryani", "Thali"], "validUntil": "Today"},
            {"store": "Dosa Plaza", "offer": "Combo: Masala Dosa + Filter Coffee for ₹199", "skus": ["Masala Dosa", "Filter Coffee"], "validUntil": "Today"}
        ]
        """.trimIndent()
    }

    @Keep
    @Tool(description = "Get the step-by-step passenger journey guide from airport entry to boarding. Call this when the user asks 'what do I do next', 'how to board', 'steps to gate', 'first time at airport', 'airport procedure', or 'guide me'.")
    fun getJourneyGuide(): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: getJourneyGuide")
        notifyTool("📋 Getting journey guide...")
        """
        {
            "journeySteps": [
                {"step": 1, "name": "Airport Entry", "description": "Enter the terminal. Show your ticket/boarding pass at the entrance.", "estimatedTime": "2 min", "tips": "Keep your ID and ticket ready."},
                {"step": 2, "name": "Check-in Counter", "description": "Go to your airline's check-in counter to get your boarding pass and drop checked luggage.", "estimatedTime": "10-20 min", "tips": "Use web check-in to save time. Check-in counters are on Ground Floor."},
                {"step": 3, "name": "Baggage Drop", "description": "If you've done web check-in, go directly to the baggage drop counter.", "estimatedTime": "5-10 min", "tips": "Tag your bag with your name and flight details."},
                {"step": 4, "name": "Security Check", "description": "Proceed to the security checkpoint. Remove laptop, belt, and liquids from your bag.", "estimatedTime": "10-15 min", "tips": "Liquids must be under 100ml in a clear bag. No sharp objects allowed."},
                {"step": 5, "name": "Immigration (International)", "description": "For international flights, proceed to immigration after security.", "estimatedTime": "10-20 min", "tips": "Keep your passport and boarding pass ready."},
                {"step": 6, "name": "Duty-Free & Shopping", "description": "Browse duty-free shops, food courts, and retail stores in the airside area.", "estimatedTime": "As needed", "tips": "Ask SkyBuddy for current offers!"},
                {"step": 7, "name": "Boarding Gate", "description": "Head to your boarding gate. Check screens for any gate changes.", "estimatedTime": "5-10 min walk", "tips": "Be at the gate 30 min before departure. Say 'navigate to my gate' for directions."},
                {"step": 8, "name": "Boarding", "description": "Board the aircraft when your zone/group is called.", "estimatedTime": "15-20 min", "tips": "Keep boarding pass and ID handy. Window seats board first."}
            ]
        }
        """.trimIndent()
    }

    @Keep
    @Tool(description = "Get information about airport facilities like check-in counters, baggage drop, security checkpoints, lounges, and other services. Call this when the user asks about airport procedures, facilities, or 'where is check-in'.")
    fun getAirportFacilities(): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: getAirportFacilities")
        notifyTool("🏢 Checking airport facilities...")
        """
        {
            "facilities": [
                {"name": "Check-in Counters", "location": "Ground Floor, T1", "mapX": 150, "mapY": 570, "hours": "24/7", "notes": "Counters A1-A10 for domestic, B1-B5 for international."},
                {"name": "Baggage Drop", "location": "Ground Floor, T1", "mapX": 200, "mapY": 560, "hours": "24/7", "notes": "Self-service kiosks available for web check-in passengers."},
                {"name": "Security Checkpoint", "location": "Level 2, T1", "mapX": 250, "mapY": 500, "hours": "24/7", "notes": "Peak hours: 6-9 AM, 5-8 PM. Use Priority Lane if eligible."},
                {"name": "Immigration", "location": "Level 2, T1", "mapX": 300, "mapY": 480, "hours": "24/7", "notes": "International flights only. E-gates available for Indian passports."},
                {"name": "Boarding Gates", "location": "Level 3, T1", "mapX": 400, "mapY": 90, "hours": "24/7", "notes": "Gates G1-G10. Check screens for your gate number."},
                {"name": "Baggage Claim", "location": "Arrivals, Ground Floor", "mapX": 220, "mapY": 300, "hours": "24/7", "notes": "Belts 1-6. Monitor screens for your belt number."},
                {"name": "Airport Information Desk", "location": "Near Entrance, T1", "mapX": 130, "mapY": 580, "hours": "06:00-23:00", "notes": "Multilingual staff available."},
                {"name": "Porter / Trolley Service", "location": "All levels", "mapX": 135, "mapY": 575, "hours": "24/7", "notes": "Free trolleys available. Porters charge ₹100-200."},
                {"name": "Left Luggage / Cloakroom", "location": "Arrivals, Ground Floor", "mapX": 240, "mapY": 310, "hours": "24/7", "notes": "₹100/bag/day for storage."}
            ]
        }
        """.trimIndent()
    }

    @Keep
    @Tool(description = "Save a detailed visual description of the user's checked luggage.")
    fun saveBag(
        @ToolParam(description = "A detailed description of the bag.")
        description: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: saveBag")
        notifyTool("🧳 Saving bag description...")
        didQueryLuggage = true
        luggageRepository.save(description, activeFlightNumber)
        if (activeFlightNumber != null) "SUCCESS: Bag saved for flight $activeFlightNumber."
        else "SUCCESS: Bag saved."
    }

    @Keep
    @Tool(description = "Retrieve the saved visual description of the user's checked luggage.")
    fun getBagDescription(): Map<String, String> = bridge {
        android.util.Log.d("SkyBuddy", "Tool: getBagDescription")
        notifyTool("🧳 Looking up bag description...")
        didQueryLuggage = true
        val bag = activeFlightNumber?.let { luggageRepository.latestForFlight(it) }
            ?: luggageRepository.latest()
        if (bag != null) mapOf("description" to bag.description)
        else mapOf("error" to "No bag saved.")
    }

    @Keep
    @Tool(description = "Retrieve the saved expense receipts for the active flight.")
    fun getReceipts(): List<Map<String, String>> = bridge {
        android.util.Log.d("SkyBuddy", "Tool: getReceipts")
        notifyTool("🧾 Fetching expense receipts...")
        didQueryReceipts = true
        val rows = activeFlightNumber?.let { receiptRepository.allForFlight(it) }
            ?: receiptRepository.all()
        rows.map {
            mapOf(
                "vendor" to it.vendor,
                "amount" to it.amount,
                "currency" to it.currency
            )
        }
    }

    @Keep
    @Tool(description = "Get real-time status, gate, terminal, and seat for the active flight.")
    fun getFlightStatus(): Map<String, Any> = bridge {
        android.util.Log.d("SkyBuddy", "Tool: getFlightStatus")
        notifyTool("✈️ Checking flight status...")
        val number = activeFlightNumber ?: return@bridge mapOf("error" to "No active flight.")
        didTouchFlight = true
        val entity = flightRepository.getFlight(number)
        if (entity != null) mapOf(
            "flightNumber" to entity.flightNumber,
            "status" to entity.status,
            "gate" to entity.gate,
            "terminal" to entity.terminal,
            "time" to entity.time,
            "airline" to entity.airline,
            "seat" to entity.seat
        ) else mapOf("error" to "Flight not found.")
    }

    @Keep
    @Tool(description = "Check if the user has lounge access based on their credit card.")
    fun checkLoungeAccess(
        @ToolParam(description = "The credit card name.")
        creditCard: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: checkLoungeAccess($creditCard)")
        notifyTool("🏙️ Checking lounge access...")
        val terminal = activeFlightNumber
            ?.let { flightRepository.getFlight(it) }
            ?.terminal
            ?: "your terminal"
        when {
            creditCard.contains("Amex", ignoreCase = true) ||
                creditCard.contains("Platinum", ignoreCase = true) ||
                creditCard.contains("Centurion", ignoreCase = true) ->
                "Yes, you have access to the Centurion Lounge in $terminal."
            creditCard.contains("Chase", ignoreCase = true) ||
                creditCard.contains("Sapphire", ignoreCase = true) ||
                creditCard.contains("Priority Pass", ignoreCase = true) ->
                "Yes, you have access to the Sapphire Lounge in $terminal."
            else -> "No, $creditCard does not typically provide access in $terminal."
        }
    }

    @Keep
    @Tool(description = "Check seat details and comfort information for the user's seat.")
    fun checkSeatDetails(): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: checkSeatDetails")
        notifyTool("💺 Looking up seat details...")
        val number = activeFlightNumber ?: return@bridge "No active flight."
        val seat = flightRepository.getFlight(number)?.seat
        if (seat.isNullOrBlank() || seat.equals("Unknown", true)) {
            "Seat is not yet known. Call setMySeat to save it."
        } else describeSeat(seat)
    }

    @Keep
    @Tool(description = "Save the user's seat number on the active flight.")
    fun setMySeat(
        @ToolParam(description = "The seat number, e.g. 12A.")
        seatNumber: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: setMySeat($seatNumber)")
        notifyTool("💺 Saving seat number...")
        val number = activeFlightNumber ?: return@bridge "No active flight."
        val seat = seatNumber.trim().uppercase()
        flightRepository.updateSeat(number, seat)
        didTouchFlight = true
        "SUCCESS: Seat saved."
    }

    @Keep
    @Tool(description = "Save an expense receipt for the active flight.")
    fun saveReceipt(
        @ToolParam(description = "The vendor name.") vendor: String,
        @ToolParam(description = "The amount.") amount: String,
        @ToolParam(description = "The currency.") currency: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: saveReceipt($vendor, $amount, $currency)")
        notifyTool("🧾 Saving receipt from $vendor...")
        didQueryReceipts = true
        receiptRepository.save(vendor, amount, currency, activeFlightNumber)
        "SUCCESS: Receipt saved."
    }

    private fun describeSeat(seat: String): String = when {
        seat.endsWith("A") || seat.endsWith("F") ->
            "Seat $seat is a window seat."
        seat.endsWith("C") || seat.endsWith("D") ->
            "Seat $seat is an aisle seat."
        else -> "Seat $seat is a middle seat."
    }

    @Keep
    @Tool(description = "Add an item to the preflight checklist. (This tool automatically displays the updated checklist to the user; do not call showChecklist after this.)")
    fun addChecklistItem(
        @ToolParam(description = "The checklist item description.") text: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: addChecklistItem($text)")
        notifyTool("✅ Adding to checklist...")
        val flight = activeFlightNumber ?: return@bridge "ERROR: No active flight."
        val id = checklistRepository.addItem(flight, text)
        didModifyChecklist = true
        "SUCCESS: Added checklist item with ID $id"
    }

    @Keep
    @Tool(description = "Remove an item from the preflight checklist. (This tool automatically displays the updated checklist to the user; do not call showChecklist after this.)")
    fun removeChecklistItem(
        @ToolParam(description = "The ID of the checklist item to remove.") id: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: removeChecklistItem($id)")
        notifyTool("❌ Removing from checklist...")
        val flight = activeFlightNumber ?: return@bridge "ERROR: No active flight."
        checklistRepository.removeItem(flight, id)
        didModifyChecklist = true
        "SUCCESS: Removed checklist item."
    }

    @Keep
    @Tool(description = "Mark a preflight checklist item as completed or incomplete. (This tool automatically displays the updated checklist to the user; do not call showChecklist after this.)")
    fun setChecklistItemCompleted(
        @ToolParam(description = "The ID of the checklist item.") id: String,
        @ToolParam(description = "Whether the item is completed (true/false).") completed: Boolean
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: setChecklistItemCompleted($id, $completed)")
        notifyTool("✅ Updating checklist...")
        val flight = activeFlightNumber ?: return@bridge "ERROR: No active flight."
        checklistRepository.setCompleted(flight, id, completed)
        didModifyChecklist = true
        "SUCCESS: Updated checklist item."
    }

    @Keep
    @Tool(description = "Displays the preflight checklist to the user.")
    fun showChecklist(): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: showChecklist")
        notifyTool("📋 Displaying checklist...")
        didModifyChecklist = true
        "SUCCESS: Checklist displayed."
    }

    @Keep
    @Tool(description = "List all airport navigation nodes (gates, security, baggage drop, etc.), with an optional search query.")
    fun listNavigationNodes(
        @ToolParam(description = "Optional search query to filter nodes.") query: String? = null
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: listNavigationNodes($query)")
        notifyTool("📍 Listing map nodes...")
        try {
            airportKb.searchMapNodes(query ?: "", topK = 20).joinToString("\n") {
                "Node ID: ${it.name}, Type: ${it.type}, Display: ${it.name.replace("_", " ")}"
            }
        } catch (e: Exception) {
            "ERROR: Could not list navigation nodes."
        }
    }

    @Keep
    @Tool(description = "Set navigation destination to a specific node ID on the indoor map.")
    fun setNavigation(
        @ToolParam(description = "The Node ID to navigate to.") nodeId: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: setNavigation($nodeId)")
        notifyTool("🧭 Setting navigation target...")
        indoorLocationManager.setDestinationNodeId(nodeId)
        "SUCCESS: Navigation target set to $nodeId"
    }

    @Keep
    @Tool(description = "Send an SOS alert with a specific emergency type. Exact valid types are: SUSPICIOUS, EMERGENCY, MEDICAL, FIRE, OTHER.")
    fun sendSOS(
        @ToolParam(description = "The SOS type (must be one of: SUSPICIOUS, EMERGENCY, MEDICAL, FIRE, OTHER).") type: String
    ): String = bridge {
        android.util.Log.d("SkyBuddy", "Tool: sendSOS($type)")
        notifyTool("🚨 Sending SOS...")
        val cx = indoorLocationManager.currentX.value
        val cy = indoorLocationManager.currentY.value
        sosBeaconEmitter.emitSOS(type, cx, cy)
        
        // Start the alarm service
        val intent = android.content.Intent(application, com.example.skybuddy.location.SosAlarmService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
        
        "SUCCESS: SOS of type $type has been emitted."
    }

    private fun <T> bridge(block: suspend () -> T): T = runBlocking {
        android.util.Log.d("SkyBuddy", "Tool bridge: entering")
        try {
            withTimeout(TOOL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { block() }
            }
        } finally {
            android.util.Log.d("SkyBuddy", "Tool bridge: exiting")
        }
    }

    companion object {
        private const val TOOL_TIMEOUT_MS = 15_000L
    }
}
