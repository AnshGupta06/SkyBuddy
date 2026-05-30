package com.example.skybuddy.shared.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class IndoorLocationManager @Inject constructor() {
    private val _currentX = MutableStateFlow(500f)
    val currentX: StateFlow<Float> = _currentX.asStateFlow()

    private val _currentY = MutableStateFlow(900f)
    val currentY: StateFlow<Float> = _currentY.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f) // in radians
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    private val _destinationNodeId = MutableStateFlow<String?>(null)
    val destinationNodeId: StateFlow<String?> = _destinationNodeId.asStateFlow()

    fun setDestinationNodeId(nodeId: String?) {
        _destinationNodeId.value = nodeId
    }

    // Fixed stride length in map units
    private val strideLength = 3f

    // Minimum heading change (radians) before emitting a new value.
    // ~0.5° — filters out sensor jitter that would otherwise trigger
    // 60 recompositions/second on the map Canvas.
    private val headingThreshold = 0.009f

    fun updateHeading(headingRadians: Float) {
        if (abs(headingRadians - _currentHeading.value) >= headingThreshold) {
            _currentHeading.value = headingRadians
        }
    }

    fun onStepDetected() {
        val heading = _currentHeading.value
        val dx = sin(heading) * strideLength
        val dy = -cos(heading) * strideLength // -cos because Y is usually down on canvas

        val newX = (_currentX.value + dx).coerceIn(0f, 1600f) // Generous bounds matching map extents
        val newY = (_currentY.value + dy).coerceIn(0f, 1000f) // Generous bounds matching map extents

        _currentX.update { newX }
        _currentY.update { newY }
    }

    fun calibratePosition(x: Float, y: Float) {
        _currentX.value = x
        _currentY.value = y
    }
}
