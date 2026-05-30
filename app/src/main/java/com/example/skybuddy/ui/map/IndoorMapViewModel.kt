package com.example.skybuddy.ui.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skybuddy.shared.data.repository.LayoutNode
import com.example.skybuddy.shared.data.repository.MapLayout
import com.example.skybuddy.shared.data.repository.MapRepository
import com.example.skybuddy.shared.domain.pathfinding.AStarPathfinder
import com.example.skybuddy.shared.location.BlockedRegionManager
import com.example.skybuddy.shared.location.IndoorLocationManager
import com.example.skybuddy.domain.state.JourneyManager
import com.example.skybuddy.ai.LlmEngine
import com.example.skybuddy.ai.tools.AirportKnowledgeBaseTool
import com.example.skybuddy.data.db.TimelineEventDao
import com.example.skybuddy.data.repository.ChecklistRepository
import com.example.skybuddy.data.repository.FlightRepository
import com.example.skybuddy.location.DynamicBeaconReceiver
import com.example.skybuddy.location.SOSBeaconEmitter
import com.example.skybuddy.ui.journey.JourneyPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val layout: MapLayout? = null,
    val currentFloor: Int = 0,
    val currentPath: List<LayoutNode> = emptyList(),
    val navigationStep: String = "",
    val currentX: Float = 500f,
    val currentY: Float = 900f,
    val currentHeading: Float = 0f,
    val blockedNodeIds: Set<String> = emptySet(),
    val sosSent: Boolean = false,
    val selectedNode: LayoutNode? = null,
    val searchQuery: String = "",
    val searchResults: List<LayoutNode> = emptyList(),
    val estimatedTimeMinutes: Int? = null,
    val dynamicIsland: DynamicIslandState = DynamicIslandState()
)

@HiltViewModel
class IndoorMapViewModel @Inject constructor(
    private val mapRepository: MapRepository,
    private val journeyManager: JourneyManager,
    private val indoorLocationManager: IndoorLocationManager,
    private val blockedRegionManager: BlockedRegionManager,
    private val sosBeaconEmitter: SOSBeaconEmitter,
    private val dynamicBeaconReceiver: DynamicBeaconReceiver,
    private val airportKnowledgeBaseTool: AirportKnowledgeBaseTool,
    private val llmEngine: LlmEngine,
    private val flightRepository: FlightRepository,
    private val checklistRepository: ChecklistRepository,
    private val timelineEventDao: TimelineEventDao
) : ViewModel() {

    /** Beacon error/status events — collect in the UI to show Snackbar. */
    val beaconEvents = kotlinx.coroutines.flow.channelFlow {
        launch { dynamicBeaconReceiver.beaconEvents.collect { send(it) } }
        launch { sosBeaconEmitter.sosEvents.collect { send(it) } }
    }

    private val pathfinder = AStarPathfinder()
    private val _internalState = MutableStateFlow(MapUiState())
    
    private var globalStartId: String = ""
    private var globalGoalId: String = ""
    private var randomGateId: String? = null

    private var proactiveTipJob: Job? = null
    private var autoCollapseJob: Job? = null
    private var periodicRefreshJob: Job? = null
    /** Track raw distance for route drift nudge (more sensitive than integer minutes). */
    private var lastDistancePixels: Float? = null
    private var distanceIncreaseCount = 0
    /** Track last boarding alert tier to avoid repeat alerts. */
    private var lastBoardingTier: Int = -1
    /** Track last seen chat message to only echo new ones. */
    private var lastSeenChatTimestamp: Long = 0L

    val uiState: StateFlow<MapUiState> = combine(
        _internalState,
        indoorLocationManager.currentX,
        indoorLocationManager.currentY,
        indoorLocationManager.currentHeading,
        blockedRegionManager.blockedNodeIds
    ) { state, x, y, heading, blocked ->
        state.copy(
            currentX = x,
            currentY = y,
            currentHeading = heading,
            blockedNodeIds = blocked
        )
    }
    .distinctUntilChanged()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapUiState()
    )

    init {
        loadMap()
        viewModelScope.launch {
            journeyManager.currentPhase.collectLatest { phase ->
                updatePathForPhase(phase)
            }
        }
        // Re-route when blocked regions change (debounce to avoid rapid recalc storms
        // from BLE scan callbacks that fire multiple times per second)
        viewModelScope.launch {
            blockedRegionManager.blockedNodeIds
                .debounce(500L)
                .distinctUntilChanged()
                .collectLatest {
                    recalculatePath()
                }
        }
        
        // Observe external navigation requests (e.g., from AI tools)
        viewModelScope.launch {
            indoorLocationManager.destinationNodeId.collectLatest { nodeId ->
                if (nodeId != null) {
                    val layout = _internalState.value.layout
                    if (layout != null) {
                        val normalizedQuery = nodeId.replace("_", " ")
                        val node = layout.floors.flatMap { it.nodes }.find { it.id.replace("_", " ").equals(normalizedQuery, ignoreCase = true) }
                        if (node != null) {
                            navigateToNode(node)
                        }
                    }
                }
            }
        }

        // ── Beacon offer events → Dynamic Island compact preview ──
        viewModelScope.launch {
            dynamicBeaconReceiver.beaconOffers.collect { (location, offer) ->
                updateIsland { it.copy(
                    offerPreview = "☕ $location: $offer",
                    icon = DynamicIslandIcon.OFFER,
                    isLoading = true,
                    isExpanded = true,
                    expandedText = ""
                ) }
                // Revert the offer preview after 20s
                viewModelScope.launch {
                    delay(20_000)
                    val current = _internalState.value.dynamicIsland
                    if (current.offerPreview?.contains(location) == true) {
                        updateIsland { it.copy(offerPreview = null, icon = DynamicIslandIcon.NAVIGATE) }
                    }
                }
            }
        }

        // ── Beacon LLM insight text → Dynamic Island expanded view ──
        viewModelScope.launch {
            dynamicBeaconReceiver.beaconInsights.collect { insight ->
                updateIsland { it.copy(
                    expandedText = insight,
                    isLoading = false,
                    isExpanded = true
                ) }
                scheduleAutoCollapse(12_000)
            }
        }

        // ── Watchdog recovery events → Dynamic Island warning ──
        viewModelScope.launch {
            dynamicBeaconReceiver.beaconEvents.collect { msg ->
                if (msg.contains("recovered", ignoreCase = true) || msg.contains("recovery", ignoreCase = true)) {
                    updateIsland { it.copy(
                        expandedText = msg,
                        isExpanded = true,
                        isLoading = false,
                        icon = DynamicIslandIcon.WARNING
                    ) }
                    scheduleAutoCollapse(8_000)
                }
            }
        }

        // ── Trigger 1: Boarding Countdown ──
        viewModelScope.launch {
            flightRepository.observeUpcoming().collectLatest { flights ->
                val nearest = flights.firstOrNull() ?: return@collectLatest
                val minutesUntilDeparture = ((nearest.departureTimeEpoch - System.currentTimeMillis()) / 60_000).toInt()
                val boardingMinutes = minutesUntilDeparture - 30 // boarding ~30min before departure
                val tier = when {
                    boardingMinutes <= 5 -> 0
                    boardingMinutes <= 15 -> 1
                    boardingMinutes <= 30 -> 2
                    boardingMinutes <= 60 -> 3
                    else -> 4
                }
                if (tier != lastBoardingTier && tier <= 3) {
                    lastBoardingTier = tier
                    val msg = when (tier) {
                        0 -> "🚨 Final call! Boarding closes in $boardingMinutes min"
                        1 -> "⏰ Boarding soon, $boardingMinutes min left. Head to your gate!"
                        2 -> "✈️ ${nearest.flightNumber} boards in ~$boardingMinutes min"
                        else -> "🕐 ${nearest.flightNumber} departs in ~$minutesUntilDeparture min, plenty of time"
                    }
                    updateIsland { it.copy(
                        expandedText = msg,
                        isExpanded = true,
                        isLoading = false,
                        icon = if (tier <= 1) DynamicIslandIcon.WARNING else DynamicIslandIcon.TIP
                    ) }
                    scheduleAutoCollapse(if (tier <= 1) 15_000 else 10_000)
                }
            }
        }

        // ── Trigger 3: Chat Echo (new AI response → island preview) ──
        viewModelScope.launch {
            timelineEventDao.getAllEvents().collectLatest { events ->
                val lastAi = events.lastOrNull { it.role == "MODEL" }
                if (lastAi != null && lastAi.timestamp > lastSeenChatTimestamp) {
                    lastSeenChatTimestamp = lastAi.timestamp
                    val preview = lastAi.content
                        .replace(Regex("[*#_`]"), "") // strip markdown
                        .trim().take(80)
                        .let { if (lastAi.content.length > 80) "$it..." else it }
                    // Only show if island isn't busy with something higher-priority
                    val island = _internalState.value.dynamicIsland
                    if (!island.isLoading && island.offerPreview == null) {
                        updateIsland { it.copy(
                            expandedText = "💬 $preview",
                            isExpanded = true,
                            icon = DynamicIslandIcon.TIP
                        ) }
                        scheduleAutoCollapse(8_000)
                    }
                }
            }
        }

        // ── Periodic proactive refresh (60s) ──
        startPeriodicRefresh()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Dynamic Island Control
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateIsland(transform: (DynamicIslandState) -> DynamicIslandState) {
        _internalState.update { it.copy(dynamicIsland = transform(it.dynamicIsland)) }
    }

    fun toggleIslandExpanded() {
        val current = _internalState.value.dynamicIsland
        if (current.isExpanded) {
            collapseIsland()
        } else {
            expandIsland()
        }
    }

    fun collapseIsland() {
        autoCollapseJob?.cancel()
        updateIsland { it.copy(isExpanded = false) }
    }

    private fun expandIsland() {
        updateIsland { it.copy(isExpanded = true) }
        // If no expanded content yet, generate one via LLM
        if (_internalState.value.dynamicIsland.expandedText.isBlank() && !_internalState.value.dynamicIsland.isLoading) {
            triggerLlmTip(buildContextualPrompt())
        }
        scheduleAutoCollapse(12_000)
    }

    private fun scheduleAutoCollapse(delayMs: Long) {
        autoCollapseJob?.cancel()
        autoCollapseJob = viewModelScope.launch {
            delay(delayMs)
            collapseIsland()
        }
        // Reset the 60s idle timer whenever content is shown
        startPeriodicRefresh()
    }

    // ── LLM tip generation ───────────────────────────────────────────────────

    private fun triggerLlmTip(extraContext: String) {
        proactiveTipJob?.cancel()
        startPeriodicRefresh() // Reset 60s idle timer
        proactiveTipJob = viewModelScope.launch(Dispatchers.IO) {
            updateIsland { it.copy(isLoading = true, expandedText = "", isExpanded = true) }

            try {
                val prompt = buildIslandPrompt(extraContext)
                val response = llmEngine.generateOneOffText(prompt)
                val cleanResponse = response.trim()
                    .removePrefix("\"").removeSuffix("\"")
                    .take(200)

                updateIsland { it.copy(
                    expandedText = cleanResponse,
                    isLoading = false
                ) }

                scheduleAutoCollapse(10_000)
            } catch (e: Exception) {
                Log.w("DynamicIsland", "LLM tip failed", e)
                updateIsland { it.copy(isLoading = false, isExpanded = false) }
            }
        }
    }

    private fun buildIslandPrompt(extraContext: String): String {
        val state = _internalState.value
        val phase = journeyManager.currentPhase.value.displayName
        val eta = state.estimatedTimeMinutes?.let { "$it min walk" } ?: "unknown"
        val destination = globalGoalId.replace("_", " ")

        return """You are SkyBuddy, a proactive airport assistant embedded in a navigation UI.
            |The user is at phase: $phase. Navigating to: $destination. ETA: $eta.
            |$extraContext
            |Write exactly 1 short, helpful, and engaging sentence (max 30 words). 
            |Be proactive and specific. Include an emoji. No preamble.
        """.trimMargin()
    }

    private fun buildContextualPrompt(): String {
        val state = _internalState.value
        val nearbyPois = state.layout?.floors
            ?.find { it.level == state.currentFloor }
            ?.nodes
            ?.filter { it.type != "WAYPOINT" }
            ?.sortedBy { (it.x - state.currentX).let { dx -> dx * dx } + (it.y - state.currentY).let { dy -> dy * dy } }
            ?.take(3)
            ?.joinToString(", ") { "${it.id.replace("_", " ")} (${it.type})" }
            ?: "none"

        return "Nearby points of interest: $nearbyPois. Give a helpful tip about the user's current situation or nearby options."
    }

    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                val island = _internalState.value.dynamicIsland
                if (!island.isExpanded && !island.isLoading) {
                    triggerLlmTip(buildContextualPrompt())
                }
            }
        }
    }

    // ── Compact text formatting ──────────────────────────────────────────────

    private fun updateCompactText(stepText: String, estimatedTime: Int? = null) {
        val arrow = "→"
        val destination = stepText
            .removePrefix("Step 1: ").removePrefix("Step 2: ").removePrefix("Step 3: ")
            .removePrefix("Proceed to ").removePrefix("Head to ").removePrefix("Navigating to ")
            .trim().removeSuffix(".")
        val etaStr = estimatedTime?.let { " · ${it} min" } ?: ""
        val compactText = "$arrow $destination$etaStr"

        updateIsland { it.copy(
            compactText = compactText,
            icon = if (stepText.contains("arrived", ignoreCase = true)) 
                DynamicIslandIcon.ARRIVAL else DynamicIslandIcon.NAVIGATE
        ) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Core Map Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadMap() {
        viewModelScope.launch {
            try {
                val layout = mapRepository.getMapLayout()
                _internalState.update { it.copy(layout = layout) }
                updatePathForPhase(journeyManager.currentPhase.value)
                
                val destId = indoorLocationManager.destinationNodeId.value
                if (destId != null) {
                    val node = layout.floors.flatMap { it.nodes }.find { it.id.equals(destId, ignoreCase = true) }
                    if (node != null) navigateToNode(node)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updatePathForPhase(phase: JourneyPhase) {
        val layout = _internalState.value.layout ?: return
        var stepText = ""
        
        if (randomGateId == null) {
            val gates = layout.floors.flatMap { it.nodes }.filter { it.type == "GATE" }
            randomGateId = if (gates.isNotEmpty()) gates.random().id else "GATE_C1"
        }
        val targetGate = randomGateId!!

        when (phase) {
            JourneyPhase.HOME, JourneyPhase.AIRPORT_ENTRANCE -> {
                globalStartId = "ENTRANCE"
                globalGoalId = "BAGGAGE_DROP"
                stepText = "Proceed to Baggage Drop"
            }
            JourneyPhase.BAGGAGE_DROP -> {
                globalStartId = "BAGGAGE_DROP"
                globalGoalId = "SECURITY_CHECK"
                stepText = "Head to Security"
            }
            JourneyPhase.SECURITY_CHECKPOINT -> {
                globalStartId = "SECURITY_CHECK"
                globalGoalId = targetGate
                stepText = "Head to Gate ${targetGate.replace("GATE_", "")}"
            }
            JourneyPhase.GATE -> {
                globalStartId = targetGate
                globalGoalId = targetGate
                stepText = "You have arrived at your gate"
            }
        }

        var startFloor = 1
        var startX = 500f
        var startY = 900f
        
        layout.floors.forEach { floor ->
            val node = floor.nodes.find { it.id == globalStartId }
            if (node != null) {
                startFloor = floor.level
                startX = node.x
                startY = node.y
            }
        }
        
        indoorLocationManager.calibratePosition(startX, startY)
        
        _internalState.update { 
            it.copy(
                currentFloor = startFloor,
                navigationStep = stepText
            )
        }

        // Update Dynamic Island compact text
        updateCompactText(stepText)

        // ── Proactive: phase change → LLM tip ──
        triggerLlmTip("Journey phase just changed to: ${phase.displayName}. User is now heading to ${globalGoalId.replace("_", " ")}. Give an encouraging, helpful tip about this next step.")

        // ── Trigger 2: Checklist Nag at SECURITY phase ──
        if (phase == JourneyPhase.SECURITY_CHECKPOINT) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(12_000) // Show after the phase-change LLM tip auto-collapses
                val flights = flightRepository.getAll()
                val flightNum = flights.firstOrNull()?.flightNumber
                if (flightNum != null) {
                    val items = checklistRepository.observeItems(flightNum).value
                    val unchecked = items.filter { !it.completed }.take(3)
                    if (unchecked.isNotEmpty()) {
                        val list = unchecked.joinToString(", ") { it.text }
                        updateIsland { it.copy(
                            expandedText = "📋 Before security: $list",
                            isExpanded = true,
                            isLoading = false,
                            icon = DynamicIslandIcon.WARNING
                        ) }
                        scheduleAutoCollapse(10_000)
                    }
                } else {
                    Log.d("SkyPulse", "Checklist nag skipped: no flights in DB")
                }
            }
        }

        // Reset drift tracking on phase change
        lastDistancePixels = null
        distanceIncreaseCount = 0

        recalculatePath()
    }

    fun setFloor(floor: Int) {
        val oldFloor = _internalState.value.currentFloor
        _internalState.update { it.copy(currentFloor = floor) }
        recalculatePath()
        // ── Trigger 4: Floor Change Context ──
        if (floor != oldFloor) {
            triggerLlmTip("User just switched to Floor $floor. Tell them what's interesting on this floor.")
        }
    }

    private fun recalculatePath() {
        val layout = _internalState.value.layout ?: run {
            Log.d("SkyPulse", "recalculatePath: no layout loaded")
            return
        }
        val currentFloor = _internalState.value.currentFloor
        val goalId = globalGoalId

        if (globalStartId == globalGoalId || goalId.isEmpty()) {
            Log.d("SkyPulse", "recalculatePath: skipped (start==goal or empty goal)")
            _internalState.update { it.copy(currentPath = emptyList()) }
            return
        }

        val goalNode = _internalState.value.selectedNode 
            ?: layout.floors.flatMap { it.nodes }.find { it.id == goalId }
        
        if (goalNode == null) {
            Log.d("SkyPulse", "recalculatePath: goalNode '$goalId' not found in layout")
            _internalState.update { it.copy(currentPath = emptyList()) }
            return
        }

        val startX = indoorLocationManager.currentX.value
        val startY = indoorLocationManager.currentY.value
        val blocked = blockedRegionManager.blockedNodeIds.value

        Log.d("SkyPulse", "recalculatePath: ($startX,$startY) -> ${goalNode.id} floor=$currentFloor")

        viewModelScope.launch(Dispatchers.Default) {
            val path = pathfinder.findPath(layout, currentFloor, startX, startY, goalNode, blocked)
            
            var distancePixels = 0f
            if (path.size > 1) {
                for (i in 0 until path.size - 1) {
                    val p1 = path[i]
                    val p2 = path[i + 1]
                    distancePixels += kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y)
                }
            }
            // Estimate: 60 pixels ≈ 1 minute walk
            val timeMinutes = if (path.size > 1) (distancePixels / 60f).toInt().coerceAtLeast(1) else null

            _internalState.update { it.copy(
                currentPath = path,
                estimatedTimeMinutes = timeMinutes
            ) }

            // Update compact text with new ETA
            updateCompactText(_internalState.value.navigationStep, timeMinutes)

            // ── Trigger 5: Route Drift Nudge (uses raw pixel distance for sensitivity) ──
            if (distancePixels > 0f && lastDistancePixels != null) {
                if (distancePixels > lastDistancePixels!! + 5f) { // 5px threshold to avoid noise
                    distanceIncreaseCount++
                    if (distanceIncreaseCount >= 3) {
                        val island = _internalState.value.dynamicIsland
                        if (!island.isExpanded && !island.isLoading) {
                            updateIsland { it.copy(
                                expandedText = "🔄 You might be heading the wrong way! Your destination is getting further.",
                                isExpanded = true,
                                isLoading = false,
                                icon = DynamicIslandIcon.WARNING
                            ) }
                            scheduleAutoCollapse(8_000)
                        }
                        distanceIncreaseCount = 0
                    }
                } else {
                    distanceIncreaseCount = 0
                }
            }
            lastDistancePixels = distancePixels
        }
    }

    fun setLocation(x: Float, y: Float) {
        indoorLocationManager.calibratePosition(x, y)
        recalculatePath()
    }

    fun simulateStep() {
        indoorLocationManager.onStepDetected()
        recalculatePath()
    }

    fun sendSOS(typeId: String) {
        val x = indoorLocationManager.currentX.value
        val y = indoorLocationManager.currentY.value
        sosBeaconEmitter.emitSOS(typeId, x, y)
        _internalState.update { it.copy(sosSent = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _internalState.update { it.copy(sosSent = false) }
        }
    }

    fun selectNode(node: LayoutNode) {
        _internalState.update { it.copy(selectedNode = node) }
    }

    fun clearSelection() {
        _internalState.update { it.copy(selectedNode = null) }
    }

    fun navigateToNode(node: LayoutNode) {
        globalGoalId = node.id
        val label = node.id.replace("_", " ")
        _internalState.update {
            it.copy(
                selectedNode = null,
                navigationStep = "Navigating to $label"
            )
        }
        updateCompactText("Navigating to $label")
        triggerLlmTip("User started navigating to $label (${node.type}). Give a helpful, proactive tip about this destination.")
        recalculatePath()
    }

    fun updateSearch(query: String) {
        _internalState.update { state ->
            val results = if (query.isBlank()) emptyList()
            else {
                airportKnowledgeBaseTool.searchMapNodes(query, topK = 8).map {
                    LayoutNode(
                        id = it.name, // Use name for display in the MapSearchBar
                        type = it.type,
                        x = it.mapX.toFloat(),
                        y = it.mapY.toFloat()
                    )
                }
            }
            state.copy(searchQuery = query, searchResults = results)
        }
    }
}
