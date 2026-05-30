package com.example.skysecurity.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skybuddy.shared.data.repository.LayoutNode
import com.example.skybuddy.shared.data.repository.MapLayout
import com.example.skybuddy.shared.data.repository.MapRepository
import com.example.skybuddy.shared.domain.pathfinding.AStarPathfinder
import com.example.skybuddy.shared.location.IndoorLocationManager
import com.example.skysecurity.location.BlockedRegionBroadcaster
import com.example.skysecurity.location.StopSoundBroadcaster
import com.example.skysecurity.location.SOSAlert
import com.example.skysecurity.location.SOSBeaconScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityMapState(
    val layout: MapLayout? = null,
    val currentFloor: Int = 0,
    val currentX: Float = 500f,
    val currentY: Float = 900f,
    val currentPath: List<LayoutNode> = emptyList(),
    val alerts: List<SOSAlert> = emptyList(),
    val blockedNodeIds: Set<String> = emptySet(),
    val navigatingToAlert: SOSAlert? = null
)

@HiltViewModel
class SecurityMapViewModel @Inject constructor(
    private val mapRepository: MapRepository,
    private val indoorLocationManager: IndoorLocationManager,
    private val sosScanner: SOSBeaconScanner,
    private val blockedBroadcaster: BlockedRegionBroadcaster,
    private val stopSoundBroadcaster: StopSoundBroadcaster
) : ViewModel() {

    /** Beacon error/status events — collect in the UI to show Snackbar. */
    val beaconEvents: Flow<String> = channelFlow {
        launch { sosScanner.scanEvents.collect { send(it) } }
        launch { blockedBroadcaster.broadcastEvents.collect { send(it) } }
    }

    private val pathfinder = AStarPathfinder()
    private val _state = MutableStateFlow(SecurityMapState())

    val uiState: StateFlow<SecurityMapState> = combine(
        _state,
        indoorLocationManager.currentX,
        indoorLocationManager.currentY,
        sosScanner.alerts,
        blockedBroadcaster.broadcastingNodeIds
    ) { state, x, y, alerts, blocked ->
        state.copy(currentX = x, currentY = y, alerts = alerts, blockedNodeIds = blocked)
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityMapState())

    private var lastAlertCount = 0

    init {
        loadMap()
        // Scanning is now managed by BeaconForegroundService — no manual start here

        // Auto-navigate to new alerts as they arrive
        viewModelScope.launch {
            sosScanner.alerts.collectLatest { alerts ->
                if (alerts.size > lastAlertCount && alerts.isNotEmpty()) {
                    val newest = alerts.first()
                    if (!newest.acknowledged) {
                        navigateToAlert(newest)
                    }
                }
                lastAlertCount = alerts.size
            }
        }
    }

    private fun loadMap() {
        viewModelScope.launch {
            try {
                val layout = mapRepository.getMapLayout()
                _state.update { it.copy(layout = layout) }
                // Start security at the entrance
                val entrance = layout.floors.flatMap { it.nodes }.find { it.id == "ENTRANCE" }
                if (entrance != null) {
                    indoorLocationManager.calibratePosition(entrance.x, entrance.y)
                    val floor = layout.floors.find { f -> f.nodes.any { it.id == "ENTRANCE" } }?.level ?: 1
                    _state.update { it.copy(currentFloor = floor) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setFloor(floor: Int) {
        _state.update { it.copy(currentFloor = floor) }
    }

    fun setLocation(x: Float, y: Float) {
        indoorLocationManager.calibratePosition(x, y)
    }

    fun navigateToAlert(alert: SOSAlert) {
        _state.update { it.copy(navigatingToAlert = alert) }
        if (alert.locationX != null && alert.locationY != null) {
            val layout = _state.value.layout ?: return
            val floor = _state.value.currentFloor
            val startX = indoorLocationManager.currentX.value
            val startY = indoorLocationManager.currentY.value
            // Find nearest node to alert coordinates for pathfinding
            val targetNode = layout.floors
                .find { it.level == floor }?.nodes
                ?.minByOrNull {
                    val dx = it.x - alert.locationX
                    val dy = it.y - alert.locationY
                    dx * dx + dy * dy
                }
            if (targetNode != null) {
                viewModelScope.launch(Dispatchers.Default) {
                    val path = pathfinder.findPath(layout, floor, startX, startY, targetNode)
                    _state.update { it.copy(currentPath = path) }
                }
            }
        }
    }

    fun clearNavigation() {
        _state.update { it.copy(navigatingToAlert = null, currentPath = emptyList()) }
    }

    fun acknowledgeAlert(alertId: String) {
        sosScanner.acknowledgeAlert(alertId)
    }

    fun toggleBlockedNode(nodeId: String) {
        val current = blockedBroadcaster.broadcastingNodeIds.value
        val updated = if (nodeId in current) current - nodeId else current + nodeId
        blockedBroadcaster.broadcastBlockedNodes(updated)
    }

    fun clearBlockedRegions() {
        // Broadcast the clear signal (empty set) so the main app removes
        // all blocked regions. stopBroadcast() would just stop advertising
        // without notifying the main app.
        blockedBroadcaster.broadcastBlockedNodes(emptySet())
    }

    fun stopUserSound(alert: SOSAlert) {
        // identity format is "Name|Tag"
        val parts = alert.identity.split("|")
        if (parts.size >= 2) {
            val tag = parts[1].trim()
            stopSoundBroadcaster.broadcastStopSound(tag)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Scanning is now managed by BeaconForegroundService — do not stop here
    }
}
