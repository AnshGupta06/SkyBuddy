package com.example.skybuddy.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser as SvgPathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.RectF
import com.example.skybuddy.core.permission.rememberMultiplePermissionsController
import com.example.skybuddy.data.repository.LayoutNode
import com.example.skybuddy.location.LocationTrackerService
import com.example.skybuddy.ui.chat.ChatViewModel
import kotlinx.coroutines.launch
import com.example.skybuddy.ui.journey.GlobalStateDropdown
import com.example.skybuddy.ui.journey.JourneyViewModel
import com.example.skybuddy.ui.theme.BackgroundGray
import com.example.skybuddy.ui.theme.CardBorder
import com.example.skybuddy.ui.theme.ErrorRed
import com.example.skybuddy.ui.theme.OnSurfaceDark
import com.example.skybuddy.ui.theme.OnSurfaceDim
import com.example.skybuddy.ui.theme.OnSurfaceLight
import com.example.skybuddy.ui.theme.PrimaryLight
import com.example.skybuddy.ui.theme.PrimaryPurple
import com.example.skybuddy.ui.theme.PrimarySurface
import com.example.skybuddy.ui.theme.SurfaceVariantLt
import com.example.skybuddy.ui.theme.SurfaceWhite
import com.example.skybuddy.ui.theme.SkyTeal
import com.example.skybuddy.ui.theme.SkyViolet
import androidx.compose.material.icons.filled.Warning

/** Light map field — matches app screen background (off-white). */
private val MapCanvasBackground = BackgroundGray

private val FloorPlateColor = SurfaceWhite
private val WallBodyColor = SurfaceVariantLt

private fun labelTypePriority(type: String): Int = when (type) {
    "GATE" -> 0
    "CHECKPOINT" -> 1
    "BAGGAGE" -> 2
    "DOOR" -> 3
    "LOUNGE" -> 4
    else -> 5
}

private fun LayoutNode.displayLabel(): String = id.replace("_", " ").uppercase()

private fun rectIntersects(a: RectF, b: RectF): Boolean =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

/** Rotate local map offset (north = −Y) by heading radians; matches sin/cos used for step direction. */
private fun offsetRotated(localX: Float, localY: Float, headingRad: Float): Offset {
    val c = kotlin.math.cos(headingRad)
    val s = kotlin.math.sin(headingRad)
    val wx = localX * c - localY * s
    val wy = localX * s + localY * c
    return Offset(wx, wy)
}

private fun worldPoint(center: Offset, localX: Float, localY: Float, headingRad: Float): Offset {
    val r = offsetRotated(localX, localY, headingRad)
    return Offset(center.x + r.x, center.y + r.y)
}

/** Start zoomed in a bit so the terminal fills the screen; pinch to zoom in/out for detail. */
private const val OverviewMapScale = 1.8f

private data class MapBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
)

private fun computeMapBounds(nodes: List<LayoutNode>, viewW: Float, viewH: Float): MapBounds {
    if (nodes.isEmpty()) return MapBounds(0f, 0f, viewW, viewH)
    val pad = 120f
    return MapBounds(
        minX = nodes.minOf { it.x } - pad,
        minY = nodes.minOf { it.y } - pad,
        maxX = nodes.maxOf { it.x } + pad,
        maxY = nodes.maxOf { it.y } + pad
    )
}

private const val WorldTextureMargin = 1200f

/** Large backdrop in map coordinates + gradient (avoids flat white outside the building). */
private fun DrawScope.drawWorldBackground(bounds: MapBounds) {
    val m = WorldTextureMargin
    val bx = bounds.minX - m
    val by = bounds.minY - m
    val bw = bounds.maxX - bounds.minX + 2 * m
    val bh = bounds.maxY - bounds.minY + 2 * m
    if (bw <= 0f || bh <= 0f) return
    // Base terrain color — warm grey-green "earth"
    drawRect(
        color = Color(0xFFE8E6E0),
        topLeft = Offset(bx, by),
        size = Size(bw, bh)
    )
    // Subtle gradient overlay to give depth
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                PrimarySurface.copy(alpha = 0.25f),
                Color.Transparent,
                Color(0xFFD5D3CA).copy(alpha = 0.30f)
            ),
            center = Offset(bx + bw * 0.5f, by + bh * 0.5f),
            radius = kotlin.math.max(bw, bh) * 0.6f
        ),
        topLeft = Offset(bx, by),
        size = Size(bw, bh)
    )
}

/** Draw realistic airport environment features around the terminal. */
private fun DrawScope.drawAirportEnvironment(bounds: MapBounds) {
    val nc = drawContext.canvas.nativeCanvas
    val midX = (bounds.minX + bounds.maxX) / 2f
    val midY = (bounds.minY + bounds.maxY) / 2f

    // ─── Greenery patches (grass fields around airport) ───
    val grassColor = Color(0xFFC2D4A7).copy(alpha = 0.55f)
    val grassDark = Color(0xFFA8BF8A).copy(alpha = 0.40f)
    val grassPatches = listOf(
        // Large patches around the terminal
        RectF(bounds.minX - 900f, bounds.minY - 700f, bounds.minX - 200f, bounds.minY - 80f),
        RectF(bounds.maxX + 200f, bounds.minY - 500f, bounds.maxX + 900f, bounds.minY + 100f),
        RectF(bounds.minX - 800f, bounds.maxY + 150f, bounds.minX + 100f, bounds.maxY + 700f),
        RectF(bounds.maxX + 300f, bounds.maxY + 100f, bounds.maxX + 1000f, bounds.maxY + 600f),
        // Smaller patches
        RectF(bounds.minX - 500f, midY - 200f, bounds.minX - 100f, midY + 200f),
        RectF(bounds.maxX + 150f, midY + 100f, bounds.maxX + 550f, midY + 400f)
    )
    for (patch in grassPatches) {
        drawRoundRect(
            color = grassColor,
            topLeft = Offset(patch.left, patch.top),
            size = Size(patch.width(), patch.height()),
            cornerRadius = CornerRadius(40f, 40f)
        )
        // Inner darker core for depth
        val inset = 30f
        if (patch.width() > inset * 3 && patch.height() > inset * 3) {
            drawRoundRect(
                color = grassDark,
                topLeft = Offset(patch.left + inset, patch.top + inset),
                size = Size(patch.width() - 2 * inset, patch.height() - 2 * inset),
                cornerRadius = CornerRadius(25f, 25f)
            )
        }
    }

    // ─── Tree clusters (small circles suggesting tree canopies) ───
    val treeColor = Color(0xFF7DA65F).copy(alpha = 0.50f)
    val treeShadow = Color(0xFF5C8040).copy(alpha = 0.25f)
    val treePositions = listOf(
        // Near entry road
        Offset(bounds.minX - 200f, bounds.minY + 200f),
        Offset(bounds.minX - 230f, bounds.minY + 260f),
        Offset(bounds.minX - 180f, bounds.minY + 310f),
        // Along the top
        Offset(bounds.minX + 100f, bounds.minY - 200f),
        Offset(bounds.minX + 180f, bounds.minY - 180f),
        Offset(bounds.minX + 250f, bounds.minY - 220f),
        // Right side clusters
        Offset(bounds.maxX + 250f, midY - 80f),
        Offset(bounds.maxX + 300f, midY - 30f),
        Offset(bounds.maxX + 270f, midY + 40f),
        // Bottom
        Offset(midX - 200f, bounds.maxY + 250f),
        Offset(midX - 130f, bounds.maxY + 280f),
        Offset(midX - 160f, bounds.maxY + 330f),
        Offset(midX + 300f, bounds.maxY + 200f),
        Offset(midX + 370f, bounds.maxY + 240f)
    )
    for (pos in treePositions) {
        drawCircle(color = treeShadow, radius = 22f, center = Offset(pos.x + 4f, pos.y + 4f))
        drawCircle(color = treeColor, radius = 18f, center = pos)
    }

    // ─── Access roads (approaching the terminal) ───
    val roadColor = Color(0xFFBDBDBD).copy(alpha = 0.65f)
    val roadStroke = Stroke(width = 32f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val roadCenterStroke = Stroke(
        width = 2f, cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
    )
    val roadCenterColor = Color(0xFFF5F5DC).copy(alpha = 0.7f)

    // Main approach road from bottom-left to terminal entrance
    val approachRoad = Path().apply {
        moveTo(bounds.minX - 900f, bounds.maxY + 500f)
        cubicTo(
            bounds.minX - 400f, bounds.maxY + 100f,
            bounds.minX - 150f, midY + 200f,
            bounds.minX + 50f, midY
        )
    }
    drawPath(path = approachRoad, color = roadColor, style = roadStroke)
    drawPath(path = approachRoad, color = roadCenterColor, style = roadCenterStroke)

    // Departure ramp curving along terminal front
    val departureRamp = Path().apply {
        moveTo(bounds.minX - 100f, midY - 100f)
        lineTo(bounds.minX - 100f, bounds.minY + 50f)
        lineTo(bounds.minX + 200f, bounds.minY - 200f)
    }
    drawPath(path = departureRamp, color = roadColor, style = roadStroke)
    drawPath(path = departureRamp, color = roadCenterColor, style = roadCenterStroke)

    // Service road along the right
    val serviceRoad = Path().apply {
        moveTo(bounds.maxX + 100f, bounds.minY - 400f)
        lineTo(bounds.maxX + 100f, bounds.maxY + 400f)
    }
    drawPath(path = serviceRoad, color = roadColor.copy(alpha = 0.45f), style = Stroke(width = 22f, cap = StrokeCap.Round))

    // ─── Parking lots ───
    val parkingColor = Color(0xFFCBC8BE).copy(alpha = 0.50f)
    val parkingLine = Color(0xFFFFFFFF).copy(alpha = 0.50f)
    // Main parking area
    val px = bounds.minX - 650f
    val py = midY - 150f
    val pw = 300f
    val ph = 300f
    drawRoundRect(
        color = parkingColor,
        topLeft = Offset(px, py),
        size = Size(pw, ph),
        cornerRadius = CornerRadius(12f, 12f)
    )
    drawRoundRect(
        color = Color(0xFF999999).copy(alpha = 0.30f),
        topLeft = Offset(px, py),
        size = Size(pw, ph),
        cornerRadius = CornerRadius(12f, 12f),
        style = Stroke(width = 2f)
    )
    // Parking bay lines
    val baySpacing = 28f
    var bx = px + 20f
    while (bx < px + pw - 20f) {
        drawLine(
            color = parkingLine,
            start = Offset(bx, py + 30f),
            end = Offset(bx, py + ph - 30f),
            strokeWidth = 1.5f
        )
        bx += baySpacing
    }
    // "P" label
    val pPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(100, 80, 80, 80)
        textSize = 42f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    nc.drawText("P", px + pw / 2f, py + ph / 2f + 15f, pPaint)

    // ─── Runways (the signature airport feature) ───
    val runwayColor = Color(0xFF8A8A8A).copy(alpha = 0.50f)
    val runwayMarkColor = Color(0xFFFFFFFF).copy(alpha = 0.55f)

    // Primary runway — long horizontal strip above the terminal
    val rwY = bounds.minY - 500f
    val rwX1 = bounds.minX - 800f
    val rwX2 = bounds.maxX + 800f
    val rwW = 50f
    drawRoundRect(
        color = runwayColor,
        topLeft = Offset(rwX1, rwY - rwW / 2f),
        size = Size(rwX2 - rwX1, rwW),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // Dashed centerline
    drawLine(
        color = runwayMarkColor,
        start = Offset(rwX1 + 30f, rwY),
        end = Offset(rwX2 - 30f, rwY),
        strokeWidth = 3f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f))
    )
    // Threshold markings at each end
    for (end in listOf(rwX1 + 60f, rwX2 - 90f)) {
        for (i in -2..2) {
            drawLine(
                color = runwayMarkColor,
                start = Offset(end, rwY + i * 8f),
                end = Offset(end + 25f, rwY + i * 8f),
                strokeWidth = 3f
            )
        }
    }
    // Runway designator
    val rwPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(90, 255, 255, 255)
        textSize = 28f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    nc.drawText("09", rwX1 + 120f, rwY + 10f, rwPaint)
    nc.drawText("27", rwX2 - 120f, rwY + 10f, rwPaint)

    // ─── Taxiways connecting runway to terminal ───
    val taxiwayColor = Color(0xFF9E9E8E).copy(alpha = 0.40f)
    val taxiwayStroke = Stroke(width = 22f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Taxiway A — from runway down to top of terminal
    val twA = Path().apply {
        moveTo(midX, rwY)
        lineTo(midX, bounds.minY - 40f)
    }
    drawPath(path = twA, color = taxiwayColor, style = taxiwayStroke)
    // Taxiway B — angled
    val twB = Path().apply {
        moveTo(midX + 400f, rwY)
        lineTo(midX + 300f, bounds.minY - 40f)
    }
    drawPath(path = twB, color = taxiwayColor, style = taxiwayStroke)
    // Taxiway labels
    val twPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(70, 50, 50, 50)
        textSize = 16f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    nc.drawText("TWY A", midX + 22f, (rwY + bounds.minY) / 2f, twPaint)
    nc.drawText("TWY B", midX + 380f, (rwY + bounds.minY) / 2f, twPaint)

    // ─── Aircraft stands (small rectangles near the terminal top edge) ───
    val standColor = Color(0xFFB0AEA6).copy(alpha = 0.45f)
    val standW = 40f
    val standH = 60f
    val standY = bounds.minY - standH - 20f
    val standPositions = listOf(400f, 550f, 750f, 950f, 1150f, 1350f)
    for (sx in standPositions) {
        drawRoundRect(
            color = standColor,
            topLeft = Offset(sx, standY),
            size = Size(standW, standH),
            cornerRadius = CornerRadius(4f, 4f)
        )
        drawRoundRect(
            color = Color(0xFF888888).copy(alpha = 0.25f),
            topLeft = Offset(sx, standY),
            size = Size(standW, standH),
            cornerRadius = CornerRadius(4f, 4f),
            style = Stroke(width = 1.5f)
        )
    }

    // ─── Compass rose (top-right area) ───
    val compassCx = bounds.maxX + 500f
    val compassCy = bounds.minY - 300f
    val compassR = 45f
    drawCircle(
        color = Color(0xFFD0CEC6).copy(alpha = 0.50f),
        radius = compassR + 8f,
        center = Offset(compassCx, compassCy)
    )
    drawCircle(
        color = Color(0xFFF5F5F0).copy(alpha = 0.70f),
        radius = compassR,
        center = Offset(compassCx, compassCy)
    )
    drawCircle(
        color = Color(0xFF888888).copy(alpha = 0.40f),
        radius = compassR,
        center = Offset(compassCx, compassCy),
        style = Stroke(width = 2f)
    )
    // N-S-E-W lines
    val nColor = Color(0xFFB03030).copy(alpha = 0.55f)
    val dirColor = Color(0xFF666666).copy(alpha = 0.40f)
    drawLine(nColor, Offset(compassCx, compassCy - compassR + 8f), Offset(compassCx, compassCy), strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(dirColor, Offset(compassCx, compassCy), Offset(compassCx, compassCy + compassR - 8f), strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(dirColor, Offset(compassCx - compassR + 8f, compassCy), Offset(compassCx + compassR - 8f, compassCy), strokeWidth = 2f, cap = StrokeCap.Round)
    val compassLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(130, 60, 60, 60)
        textSize = 16f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    nc.drawText("N", compassCx, compassCy - compassR - 8f, compassLabel)
}


/** Soft vignette so the floor doesn’t read as a flat solid. */
private fun DrawScope.drawFloorAtmosphere(bounds: MapBounds) {
    val w = bounds.maxX - bounds.minX
    val h = bounds.maxY - bounds.minY
    if (w <= 0f || h <= 0f) return
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                PrimarySurface.copy(alpha = 0.0f),
                PrimaryPurple.copy(alpha = 0.04f),
                PrimaryLight.copy(alpha = 0.055f)
            ),
            center = Offset(bounds.minX + w * 0.38f, bounds.minY + h * 0.28f),
            radius = kotlin.math.max(w, h) * 0.72f
        ),
        topLeft = Offset(bounds.minX, bounds.minY),
        size = Size(w, h)
    )
}

private fun DrawScope.drawPoiMarker(node: LayoutNode, accent: Color) {
    val c = Offset(node.x, node.y)
    val r = 19f
    drawCircle(color = accent.copy(alpha = 0.14f), radius = r + 7f, center = c)
    drawCircle(color = SurfaceWhite, radius = r, center = c)
    drawCircle(color = accent, radius = r, center = c, style = Stroke(width = 2.2f))

    when (node.type) {
        "GATE" -> drawGlyphPlane(c, accent)
        "SHOP" -> drawGlyphShopBag(c, accent)
        "RESTAURANT", "CAFE" -> drawGlyphDining(c, accent)
        "CHECKPOINT" -> drawGlyphShield(c, accent)
        "BAGGAGE" -> drawGlyphBaggage(c, accent)
        "DOOR" -> drawGlyphDoor(c, accent)
        "LOUNGE" -> drawGlyphLounge(c, accent)
        "LIFT" -> drawGlyphLift(c, accent)
        else -> drawGlyphPin(c, accent)
    }
}

private fun DrawScope.drawGlyphPlane(c: Offset, a: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - 8f)
        lineTo(c.x - 9f, c.y + 3f)
        lineTo(c.x - 3.5f, c.y + 3f)
        lineTo(c.x - 3.5f, c.y + 8f)
        lineTo(c.x + 3.5f, c.y + 8f)
        lineTo(c.x + 3.5f, c.y + 3f)
        lineTo(c.x + 9f, c.y + 3f)
        close()
    }
    drawPath(p, a, style = Fill)
}

private fun DrawScope.drawGlyphShopBag(c: Offset, a: Color) {
    val p = Path().apply {
        moveTo(c.x - 6f, c.y - 5f)
        lineTo(c.x + 6f, c.y - 5f)
        lineTo(c.x + 6f, c.y + 7f)
        lineTo(c.x - 6f, c.y + 7f)
        close()
    }
    drawPath(p, a, style = Fill)
    drawLine(a, Offset(c.x - 4f, c.y - 5f), Offset(c.x - 4f, c.y - 8f), strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(a, Offset(c.x + 4f, c.y - 5f), Offset(c.x + 4f, c.y - 8f), strokeWidth = 2f, cap = StrokeCap.Round)
    drawArc(
        color = a,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(c.x - 5f, c.y - 10f),
        size = Size(10f, 8f),
        style = Stroke(width = 2f)
    )
}

private fun DrawScope.drawGlyphDining(c: Offset, a: Color) {
    drawLine(a, Offset(c.x - 2f, c.y - 8f), Offset(c.x - 2f, c.y + 8f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    drawLine(a, Offset(c.x + 2f, c.y - 8f), Offset(c.x + 2f, c.y + 8f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    drawLine(a, Offset(c.x - 5f, c.y + 2f), Offset(c.x + 5f, c.y + 2f), strokeWidth = 2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawGlyphShield(c: Offset, a: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - 9f)
        lineTo(c.x + 8f, c.y - 5f)
        lineTo(c.x + 8f, c.y + 3f)
        quadraticBezierTo(c.x + 8f, c.y + 9f, c.x, c.y + 11f)
        quadraticBezierTo(c.x - 8f, c.y + 9f, c.x - 8f, c.y + 3f)
        lineTo(c.x - 8f, c.y - 5f)
        close()
    }
    drawPath(p, a, style = Fill)
    drawPath(p, SurfaceWhite.copy(alpha = 0.5f), style = Stroke(width = 1f))
}

private fun DrawScope.drawGlyphBaggage(c: Offset, a: Color) {
    drawRoundRect(
        color = a,
        topLeft = Offset(c.x - 8f, c.y - 5f),
        size = Size(16f, 11f),
        cornerRadius = CornerRadius(2f, 2f)
    )
    drawLine(
        color = SurfaceWhite.copy(alpha = 0.85f),
        start = Offset(c.x - 5f, c.y - 5f),
        end = Offset(c.x - 5f, c.y - 8f),
        strokeWidth = 2f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawGlyphDoor(c: Offset, a: Color) {
    drawRoundRect(
        color = a,
        topLeft = Offset(c.x - 6f, c.y - 8f),
        size = Size(12f, 16f),
        cornerRadius = CornerRadius(2f, 2f),
        style = Stroke(width = 2.2f)
    )
    drawCircle(color = a, radius = 1.4f, center = Offset(c.x + 3.5f, c.y + 1f))
}

private fun DrawScope.drawGlyphLounge(c: Offset, a: Color) {
    drawRoundRect(
        color = a,
        topLeft = Offset(c.x - 10f, c.y - 4f),
        size = Size(20f, 9f),
        cornerRadius = CornerRadius(3f, 3f)
    )
    drawRoundRect(
        color = SurfaceWhite.copy(alpha = 0.35f),
        topLeft = Offset(c.x - 7f, c.y - 6f),
        size = Size(6f, 4f),
        cornerRadius = CornerRadius(1.5f, 1.5f)
    )
}

private fun DrawScope.drawGlyphLift(c: Offset, a: Color) {
    drawRect(color = a, topLeft = Offset(c.x - 6f, c.y - 8f), size = Size(12f, 16f), style = Stroke(width = 2f))
    drawLine(a, Offset(c.x, c.y - 5f), Offset(c.x, c.y - 2f), strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(a, Offset(c.x - 2.5f, c.y - 3.5f), Offset(c.x + 2.5f, c.y - 3.5f), strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(a, Offset(c.x, c.y + 2f), Offset(c.x, c.y + 5f), strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(a, Offset(c.x - 2.5f, c.y + 3.5f), Offset(c.x + 2.5f, c.y + 3.5f), strokeWidth = 2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawGlyphPin(c: Offset, a: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - 9f)
        quadraticBezierTo(c.x + 9f, c.y - 2f, c.x, c.y + 10f)
        quadraticBezierTo(c.x - 9f, c.y - 2f, c.x, c.y - 9f)
        close()
    }
    drawPath(p, a, style = Fill)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndoorMapScreen(
    flightNumber: String,
    onChatClicked: () -> Unit,
    onHelpClicked: () -> Unit,
    viewModel: IndoorMapViewModel = hiltViewModel(),
    journeyViewModel: JourneyViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val chatState by chatViewModel.state.collectAsState()
    
    LaunchedEffect(uiState.estimatedTimeMinutes) {
        chatViewModel.currentEstimatedTimeMinutes = uiState.estimatedTimeMinutes
    }

    var scale by remember { mutableStateOf(OverviewMapScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showCalibration by remember { mutableStateOf(false) }
    var showSOSSheet by remember { mutableStateOf(false) }
    var isVisualCalibrationMode by remember { mutableStateOf(false) }
    var pendingSOSType by remember { mutableStateOf<String?>(null) }
    var pendingSOSTypeForDialog by remember { mutableStateOf<SOSType?>(null) }
    val context = LocalContext.current

    val permissionsController = rememberMultiplePermissionsController { results ->
        val advertiseGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_ADVERTISE] ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)
        } else true
        
        val connectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_CONNECT] ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        } else true
        
        if (advertiseGranted && connectGranted) {
            pendingSOSType?.let { 
                viewModel.sendSOS(it) 
                val intent = Intent(context, com.example.skybuddy.location.SosAlarmService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
        pendingSOSType = null
    }

    // Pulsing animation for blue dot
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 18f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseR"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseA"
    )

    // ── Request beacon + notification permissions before starting service ──
    val beaconPermController = rememberMultiplePermissionsController { results ->
        // Once permissions are resolved, start the foreground service
        val locationOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (locationOk) {
            val intent = Intent(context, LocationTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            beaconPermController.request(needed.toTypedArray())
        } else {
            // All permissions already granted — start service directly
            val intent = Intent(context, LocationTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // Request battery optimization exemption so the beacon service stays alive
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                val exemptIntent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                context.startActivity(exemptIntent)
            } catch (_: Exception) { /* Some devices don't support this intent */ }
        }
    }

    // Show beacon errors as Snackbars
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.beaconEvents.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // Pre-parse SVG paths once per layout/floor change (avoids re-parsing every frame)
    val cachedPaths = remember(uiState.layout, uiState.currentFloor) {
        val floor = uiState.layout?.floors?.find { it.level == uiState.currentFloor }
        floor?.paths?.associate { layoutPath ->
            layoutPath.d to SvgPathParser().parsePathString(layoutPath.d).toPath()
        } ?: emptyMap()
    }

    Box(modifier = Modifier.fillMaxSize().background(MapCanvasBackground)) {
        // Beacon status Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = constraints.maxWidth.toFloat()
            val canvasHeight = constraints.maxHeight.toFloat()

            // graphicsLayer applies: scale-around-pivot(center) FIRST, then translate.
            // Screen position of map point mx:
            //   screenX = (mx - pivot) * scale + pivot + translationX
            // To center currentX at screen center (pivot = canvasWidth/2):
            //   translationX = (canvasWidth/2 - currentX) * scale
            // Pan offset from gestures is in screen-space pixels (post-scale),
            // so it's added directly to translationX.

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.35f, 5.5f)
                            offset += pan
                        }
                    }
                    .pointerInput(scale, offset, uiState.currentX, uiState.currentY) {
                        detectTapGestures { tapOffset ->
                            // Convert screen tap to map coordinates
                            val cw = size.width.toFloat()
                            val ch = size.height.toFloat()
                            val tx = (cw / 2f - uiState.currentX) * scale + offset.x
                            val ty = (ch / 2f - uiState.currentY) * scale + offset.y
                            val mapX = (tapOffset.x - cw / 2f - tx) / scale + cw / 2f
                            val mapY = (tapOffset.y - ch / 2f - ty) / scale + ch / 2f
                            val floor = uiState.layout?.floors?.find { it.level == uiState.currentFloor }
                            val hitNode = floor?.nodes
                                ?.filter { it.type != "WAYPOINT" }
                                ?.minByOrNull { (it.x - mapX) * (it.x - mapX) + (it.y - mapY) * (it.y - mapY) }
                            if (hitNode != null) {
                                val dist = kotlin.math.sqrt((hitNode.x - mapX) * (hitNode.x - mapX) + (hitNode.y - mapY) * (hitNode.y - mapY))
                                if (dist < 60f) viewModel.selectNode(hitNode)
                                else viewModel.clearSelection()
                            } else viewModel.clearSelection()
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = (canvasWidth / 2f - uiState.currentX) * scale + offset.x,
                        translationY = (canvasHeight / 2f - uiState.currentY) * scale + offset.y
                    )
            ) {
                val floor = uiState.layout?.floors?.find { it.level == uiState.currentFloor }
                val paths = floor?.paths.orEmpty()
                val mapNodesEarly = floor?.nodes.orEmpty()
                val mapBoundsEarly = computeMapBounds(mapNodesEarly, size.width, size.height)

                drawWorldBackground(mapBoundsEarly)
                drawAirportEnvironment(mapBoundsEarly)

                // Pass 1 — fills (floor plate, structures) so the map reads as space, not just lines
                paths.forEach { layoutPath ->
                    val path = cachedPaths[layoutPath.d] ?: return@forEach
                    when (layoutPath.type) {
                        "boundary" -> {
                            drawPath(path = path, color = FloorPlateColor, style = Fill)
                            drawPath(path = path, color = PrimarySurface.copy(alpha = 0.55f), style = Fill)
                        }
                        "wall" -> drawPath(path = path, color = WallBodyColor, style = Fill)
                        "island" -> drawPath(path = path, color = PrimarySurface.copy(alpha = 0.9f), style = Fill)
                        else -> { }
                    }
                }

                val mapNodes = mapNodesEarly
                val mapBounds = mapBoundsEarly
                drawFloorAtmosphere(mapBounds)

                // Pass 2 — edges / outlines
                paths.forEach { layoutPath ->
                    val path = cachedPaths[layoutPath.d] ?: return@forEach
                    when (layoutPath.type) {
                        "boundary" -> {
                            drawPath(
                                path = path,
                                color = PrimaryLight.copy(alpha = 0.22f),
                                style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            drawPath(
                                path = path,
                                color = PrimaryPurple,
                                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            drawPath(
                                path = path,
                                color = CardBorder,
                                style = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        "wall" -> {
                            drawPath(
                                path = path,
                                color = OnSurfaceDim.copy(alpha = 0.45f),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        "island" -> {
                            drawPath(
                                path = path,
                                color = PrimaryPurple.copy(alpha = 0.55f),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            drawPath(
                                path = path,
                                color = PrimaryLight.copy(alpha = 0.5f),
                                style = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        else -> {
                            drawPath(
                                path = path,
                                color = PrimaryLight.copy(alpha = 0.55f),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }

                // POI markers — category glyphs (skip waypoints)
                floor?.nodes?.filter { it.type != "WAYPOINT" }?.forEach { node ->
                    val color = when (node.type) {
                        "CHECKPOINT" -> ErrorRed
                        "SHOP", "RESTAURANT", "CAFE" -> PrimaryPurple
                        "GATE" -> PrimaryPurple
                        "BAGGAGE" -> SkyTeal
                        "LIFT" -> SkyViolet
                        "DOOR" -> PrimaryLight.copy(alpha = 0.9f)
                        "LOUNGE" -> SkyViolet.copy(alpha = 0.95f)
                        else -> OnSurfaceDim
                    }
                    drawPoiMarker(node, color)
                }

                // Labels: collision-aware (fixes overlapping shop names in retail clusters)
                val labelPaint = android.graphics.Paint().apply {
                    color = OnSurfaceDark.toArgb()
                    textSize = 19f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }
                val shadowPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 255, 255, 255)
                    textSize = labelPaint.textSize
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = labelPaint.typeface
                }
                val occupied = mutableListOf<RectF>()
                val labelNodes = floor?.nodes
                    ?.filter { it.type != "WAYPOINT" }
                    ?.sortedWith(
                        compareBy<LayoutNode>({ labelTypePriority(it.type) }, { it.y }, { it.x })
                    ).orEmpty()

                for (node in labelNodes) {
                    val label = node.displayLabel()
                    val w = labelPaint.measureText(label)
                    val pad = 4f
                    var baseline = node.y + 30f
                    val maxAttempts = 12
                    var attempt = 0
                    var placed = false
                    while (attempt < maxAttempts && !placed) {
                        val ascent = labelPaint.ascent()
                        val descent = labelPaint.descent()
                        val r = RectF(
                            node.x - w / 2f - pad,
                            baseline + ascent - pad,
                            node.x + w / 2f + pad,
                            baseline + descent + pad
                        )
                        if (occupied.none { rectIntersects(r, it) }) {
                            occupied.add(r)
                            val nc = drawContext.canvas.nativeCanvas
                            nc.drawText(label, node.x + 1.2f, baseline + 1.2f, shadowPaint)
                            nc.drawText(label, node.x, baseline, labelPaint)
                            placed = true
                        } else {
                            baseline += 20f
                            attempt++
                        }
                    }
                }

                // Draw pathfinding route — gradient path
                if (uiState.currentPath.size > 1) {
                    val pathPath = Path().apply {
                        moveTo(uiState.currentPath.first().x, uiState.currentPath.first().y)
                        for (i in 1 until uiState.currentPath.size) {
                            lineTo(uiState.currentPath[i].x, uiState.currentPath[i].y)
                        }
                    }
                    drawPath(
                        path = pathPath,
                        color = PrimaryLight.copy(alpha = 0.35f),
                        style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = pathPath,
                        color = PrimaryPurple,
                        style = Stroke(
                            width = 3.5f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        )
                    )
                }

                // User location — pulsing ring + navigation arrow (heading in radians)
                val cx = uiState.currentX
                val cy = uiState.currentY
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(PrimaryLight.copy(alpha = pulseAlpha), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = pulseRadius
                    ),
                    radius = pulseRadius,
                    center = Offset(cx, cy)
                )
                val ctr = Offset(cx, cy)
                val h = uiState.currentHeading
                fun w(lx: Float, ly: Float) = worldPoint(ctr, lx, ly, h)
                val navArrow = Path().apply {
                    val p0 = w(0f, -34f)
                    moveTo(p0.x, p0.y)
                    lineTo(w(-16f, 14f).x, w(-16f, 14f).y)
                    lineTo(w(-5f, 14f).x, w(-5f, 14f).y)
                    lineTo(w(-5f, 18f).x, w(-5f, 18f).y)
                    lineTo(w(5f, 18f).x, w(5f, 18f).y)
                    lineTo(w(5f, 14f).x, w(5f, 14f).y)
                    lineTo(w(16f, 14f).x, w(16f, 14f).y)
                    close()
                }
                drawPath(path = navArrow, color = PrimaryPurple, style = Fill)
                drawPath(
                    path = navArrow,
                    color = SurfaceWhite,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // ─── Blocked region overlays (red hatched circles from security) ───
                val blockedNodes = uiState.blockedNodeIds
                if (blockedNodes.isNotEmpty()) {
                    floor?.nodes?.filter { it.id in blockedNodes }?.forEach { node ->
                        val bc = Offset(node.x, node.y)
                        val blockR = 45f
                        drawCircle(color = ErrorRed.copy(alpha = 0.15f), radius = blockR + 12f, center = bc)
                        drawCircle(
                            color = ErrorRed.copy(alpha = 0.4f), radius = blockR, center = bc,
                            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                        )
                        drawLine(ErrorRed.copy(alpha = 0.35f), Offset(bc.x - 22f, bc.y - 22f), Offset(bc.x + 22f, bc.y + 22f), strokeWidth = 2.5f)
                        drawLine(ErrorRed.copy(alpha = 0.35f), Offset(bc.x + 22f, bc.y - 22f), Offset(bc.x - 22f, bc.y + 22f), strokeWidth = 2.5f)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                GlobalStateDropdown(
                    viewModel = journeyViewModel,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isVisualCalibrationMode) {
                    Spacer(Modifier.height(8.dp))
                    MapSearchBar(
                        query = uiState.searchQuery,
                        results = uiState.searchResults,
                        onQueryChange = { viewModel.updateSearch(it) },
                        onResultClick = { node ->
                            viewModel.selectNode(node)
                            viewModel.updateSearch("")
                        }
                    )
                }
            }

            if (isVisualCalibrationMode) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = "Target Crosshair",
                    tint = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    StyledFab(
                        onClick = {
                            val newX = uiState.currentX - (offset.x / scale)
                            val newY = uiState.currentY - (offset.y / scale)
                            viewModel.setLocation(newX, newY)
                            offset = Offset.Zero
                            isVisualCalibrationMode = false
                        },
                        icon = { Icon(Icons.Filled.Check, "Confirm Position", tint = SurfaceWhite, modifier = Modifier.size(22.dp)) }
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledFab(
                        onClick = {
                            offset = Offset.Zero
                            isVisualCalibrationMode = false
                        },
                        icon = { Icon(Icons.Filled.Close, "Cancel", tint = OnSurfaceDark, modifier = Modifier.size(22.dp)) },
                        isSecondary = true
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp)
                ) {
                    uiState.layout?.floors?.forEach { floor ->
                        val isSelected = uiState.currentFloor == floor.level
                        FloatingActionButton(
                            onClick = { viewModel.setFloor(floor.level) },
                            shape = if (isSelected) RoundedCornerShape(16.dp) else CircleShape,
                            containerColor = if (isSelected) PrimaryPurple else SurfaceWhite,
                            modifier = Modifier
                                .padding(bottom = 10.dp)
                                .size(52.dp)
                                .then(
                                    if (!isSelected) {
                                        Modifier.border(BorderStroke(1.dp, CardBorder), CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            Text(
                                text = "F${floor.level}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) SurfaceWhite else OnSurfaceDark,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // ─── SOS FAB ───
                FloatingActionButton(
                    onClick = { showSOSSheet = true },
                    shape = CircleShape,
                    containerColor = ErrorRed,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 155.dp, start = 16.dp)
                        .size(60.dp)
                ) {
                    Text("SOS", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold, color = SurfaceWhite)
                }

                // ─── SOS sent confirmation ───
                if (uiState.sosSent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 150.dp)
                            .background(color = ErrorRed, shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("🚨 SOS Alert Sent!", color = SurfaceWhite, fontWeight = FontWeight.Bold)
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 155.dp, end = 16.dp)
                ) {
                    StyledFab(
                        onClick = {
                            offset = Offset.Zero
                            scale = OverviewMapScale
                        },
                        icon = { Icon(Icons.Filled.MyLocation, "Recenter", tint = OnSurfaceDark, modifier = Modifier.size(22.dp)) },
                        isSecondary = true
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledFab(
                        onClick = { viewModel.simulateStep() },
                        icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, "Simulate Step", tint = OnSurfaceDark, modifier = Modifier.size(22.dp)) },
                        isSecondary = true
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledFab(
                        onClick = { showCalibration = true },
                        icon = { Icon(Icons.Filled.Sync, "Change Position", tint = OnSurfaceDark, modifier = Modifier.size(22.dp)) },
                        isSecondary = true
                    )
                }
            }

            // ─── POI Info Popup Card ───
            AnimatedVisibility(
                visible = uiState.selectedNode != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 155.dp)
                    .padding(horizontal = 16.dp)
            ) {
                uiState.selectedNode?.let { node ->
                    PoiInfoCard(
                        node = node,
                        onDismiss = { viewModel.clearSelection() },
                        onNavigate = { viewModel.navigateToNode(node) }
                    )
                }
            }
        }
        
        // ─── Dynamic Island + Chat Input (bottom stack) ───
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Dynamic Island — always shows at least one line
            DynamicIsland(
                state = uiState.dynamicIsland,
                onTap = { viewModel.toggleIslandExpanded() },
                onDismiss = { viewModel.collapseIsland() }
            )

            // Chat input bar
            Card(
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = BorderStroke(1.dp, CardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChatClicked() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Chat",
                        tint = PrimaryPurple,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Ask SkyBuddy...",
                        color = OnSurfaceDim,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice",
                        tint = OnSurfaceDim,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    if (showCalibration) {
        val currentFloorNodes = uiState.layout?.floors?.find { it.level == uiState.currentFloor }?.nodes ?: emptyList()
        CalibrationBottomSheet(
            nodes = currentFloorNodes,
            onSemanticCalibrate = { node ->
                viewModel.setLocation(node.x, node.y)
                showCalibration = false
            },
            onVisualCalibrate = {
                showCalibration = false
                offset = Offset.Zero
                isVisualCalibrationMode = true
            },
            onDismiss = { showCalibration = false }
        )
    }

    if (showSOSSheet) {
        SOSBottomSheet(
            onSOSSelected = { sosType ->
                showSOSSheet = false
                pendingSOSTypeForDialog = sosType
            },
            onDismiss = { showSOSSheet = false }
        )
    }

    if (pendingSOSTypeForDialog != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingSOSTypeForDialog = null },
            title = { androidx.compose.material3.Text("Send Emergency SOS?") },
            text = { androidx.compose.material3.Text("This will alert airport security and start an alarm on your device. False reports are tracked and can be actioned.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val sosType = pendingSOSTypeForDialog!!
                    pendingSOSTypeForDialog = null
                    
                    val needsAdvertise = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
                    val needsConnect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    
                    if (needsAdvertise || needsConnect) {
                        pendingSOSType = sosType.id
                        val reqs = mutableListOf<String>()
                        if (needsAdvertise) reqs.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                        if (needsConnect) reqs.add(Manifest.permission.BLUETOOTH_CONNECT)
                        permissionsController.request(reqs.toTypedArray())
                    } else {
                        viewModel.sendSOS(sosType.id)
                        val intent = Intent(context, com.example.skybuddy.location.SosAlarmService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                }) {
                    androidx.compose.material3.Text("Confirm & Send SOS")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingSOSTypeForDialog = null }) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StyledFab(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    isSecondary: Boolean = false
) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = if (isSecondary) SurfaceWhite else PrimaryPurple,
        contentColor = if (isSecondary) OnSurfaceDark else SurfaceWhite,
        modifier = Modifier
            .size(52.dp)
            .then(
                if (isSecondary) Modifier.border(BorderStroke(1.dp, CardBorder), CircleShape)
                else Modifier
            )
    ) {
        icon()
    }
}

// ── POI type helpers for display ─────────────────────────────────────────────

private fun poiTypeIcon(type: String): String = when (type) {
    "GATE" -> "✈️"
    "RESTAURANT", "CAFE" -> "🍽️"
    "SHOP" -> "🛍️"
    "LOUNGE" -> "🛋️"
    "CHECKPOINT" -> "🛡️"
    "BAGGAGE" -> "🧳"
    "DOOR" -> "🚪"
    "LIFT" -> "🛗"
    else -> "📍"
}

private fun poiTypeLabel(type: String): String = when (type) {
    "GATE" -> "Gate"
    "RESTAURANT" -> "Restaurant"
    "CAFE" -> "Café"
    "SHOP" -> "Shop"
    "LOUNGE" -> "Lounge"
    "CHECKPOINT" -> "Security"
    "BAGGAGE" -> "Baggage"
    "DOOR" -> "Entrance"
    "LIFT" -> "Lift"
    else -> "Location"
}

private fun poiColor(type: String): Color = when (type) {
    "CHECKPOINT" -> ErrorRed
    "SHOP", "RESTAURANT", "CAFE" -> PrimaryPurple
    "GATE" -> PrimaryPurple
    "BAGGAGE" -> SkyTeal
    "LOUNGE" -> SkyViolet
    "DOOR" -> PrimaryLight
    else -> OnSurfaceDim
}

// ── POI Info Card ────────────────────────────────────────────────────────────

@Composable
private fun PoiInfoCard(
    node: LayoutNode,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = poiTypeIcon(node.type),
                        fontSize = 28.sp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = node.id.replace("_", " "),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = OnSurfaceDark
                        )
                        Text(
                            text = poiTypeLabel(node.type),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = OnSurfaceDim
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Type badge
            Box(
                modifier = Modifier
                    .background(
                        color = poiColor(node.type).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = poiTypeLabel(node.type),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = poiColor(node.type)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Location info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                InfoChip(label = "Terminal", value = "T2")
                InfoChip(label = "Floor", value = "Level 1")
                InfoChip(label = "Zone", value = if (node.x > 500) "Airside" else "Landside")
            }

            Spacer(Modifier.height(16.dp))

            // Navigate button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(listOf(PrimaryPurple, PrimaryLight)),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onNavigate() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = "Navigate",
                        tint = SurfaceWhite,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Navigate Here",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = SurfaceWhite
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceLight,
            fontSize = 10.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = OnSurfaceDark
        )
    }
}

// ── Map Search Bar ───────────────────────────────────────────────────────────

@Composable
private fun MapSearchBar(
    query: String,
    results: List<LayoutNode>,
    onQueryChange: (String) -> Unit,
    onResultClick: (LayoutNode) -> Unit
) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text("Search places...", color = OnSurfaceLight)
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = OnSurfaceDim)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = OnSurfaceDim)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceWhite,
                unfocusedContainerColor = SurfaceWhite,
                focusedBorderColor = PrimaryPurple,
                unfocusedBorderColor = CardBorder,
                cursorColor = PrimaryPurple
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (results.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = BorderStroke(1.dp, CardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    items(results) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultClick(node) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = poiTypeIcon(node.type),
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = node.id.replace("_", " "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = OnSurfaceDark
                                )
                                Text(
                                    text = poiTypeLabel(node.type),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}