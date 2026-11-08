package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.data.model.AssistantMode

/** Glowing AI orb. IDLE breathes, LISTENING reacts to the real microphone level, THINKING spins, SPEAKING pulses. */
@Composable
fun OrbVisualizer(mode: AssistantMode, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    val pulse by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val lv by animateFloatAsState(level, tween(120), label = "level")
    val (c1, c2) = when (mode) {
        AssistantMode.IDLE -> Color(0xFF8E7CFF) to Color(0xFFFF7AC8)
        AssistantMode.LISTENING -> Color(0xFF4FD8FF) to Color(0xFF8E7CFF)
        AssistantMode.THINKING -> Color(0xFFFFC857) to Color(0xFFFF7AC8)
        AssistantMode.SPEAKING -> Color(0xFFFF7AC8) to Color(0xFFFFB3E0)
        AssistantMode.ERROR -> Color(0xFFFF5252) to Color(0xFF8E0000)
    }
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f
        val scale = when (mode) {
            AssistantMode.IDLE -> 1f + (pulse - 1f) * 0.4f
            AssistantMode.LISTENING -> 0.9f + lv * 0.3f
            AssistantMode.SPEAKING -> pulse
            else -> 1f
        }
        val core = r * 0.55f * scale
        drawCircle(Brush.radialGradient(listOf(c2.copy(alpha = 0.55f), Color.Transparent), center = center, radius = r), radius = r * scale.coerceAtLeast(0.9f))
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), c1, c2.copy(alpha = 0.9f)),
                center = Offset(center.x - core * 0.25f, center.y - core * 0.25f), radius = core * 1.4f),
            radius = core
        )
        if (mode == AssistantMode.THINKING)
            drawArc(Color.White.copy(alpha = 0.85f), startAngle = phase * 360f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(center.x - core * 1.25f, center.y - core * 1.25f), size = Size(core * 2.5f, core * 2.5f),
                style = Stroke(width = 6f, cap = StrokeCap.Round))
        if (mode == AssistantMode.LISTENING || mode == AssistantMode.SPEAKING)
            for (i in 0..1) { val p = (phase + i * 0.5f) % 1f; drawCircle(c1.copy(alpha = (1f - p) * 0.35f), radius = core * (1f + p * 0.9f), style = Stroke(3f)) }
    }
}
