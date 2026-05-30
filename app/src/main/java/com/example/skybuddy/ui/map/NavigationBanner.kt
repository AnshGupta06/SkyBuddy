package com.example.skybuddy.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skybuddy.ui.theme.PrimaryPurple

@Composable
fun NavigationBanner(
    stepText: String,
    estimatedTimeMinutes: Int? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = stepText.isNotBlank(),
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                slideInVertically(spring(stiffness = Spring.StiffnessMedium)) { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .shadow(
                    elevation = 8.dp,
                    shape = shape,
                    spotColor = Color.Black.copy(alpha = 0.12f),
                    ambientColor = Color.Black.copy(alpha = 0.06f)
                )
                .clip(shape)
                .background(Color.White)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Purple accent bar
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(PrimaryPurple)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    tint = PrimaryPurple,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(22.dp)
                )
                Text(
                    text = if (estimatedTimeMinutes != null) "$stepText (Est. $estimatedTimeMinutes min walk)" else stepText,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color(0xFF1A1A2E),
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                        .weight(1f)
                )
            }
        }
    }
}
