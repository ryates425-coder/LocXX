package com.locxx.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

internal fun formatWinnerNamesEnglish(names: List<String>): String {
    val n = names.map { it.trim() }.filter { it.isNotEmpty() }
    if (n.isEmpty()) return "Another player"
    if (n.size == 1) return n[0]
    if (n.size == 2) return "${n[0]} and ${n[1]}"
    return n.dropLast(1).joinToString(", ") + ", and ${n.last()}"
}

private data class WinConfettiBit(
    val xFrac: Float,
    val delayFrac: Float,
    val fallScale: Float,
    val drift: Float,
    val wobblePhase: Float,
    val color: Color,
    val radius: Float,
    val isStrip: Boolean,
    val spin: Float
)

/**
 * Full-screen win celebration: confetti + fanfare (matches row-lock celebration tone).
 * Tap or short wait to dismiss.
 */
@Composable
fun GameWonCelebrationOverlay(
    finalScore: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        playSnotzeeBonusHorn()
        delay(3200)
        onDismiss()
    }

    val gold = Color(0xFFFFD700)
    val accent = Color(0xFFC48154)
    val bits = remember {
        val rng = Random(42_001)
        List(96) {
            WinConfettiBit(
                xFrac = rng.nextFloat(),
                delayFrac = rng.nextFloat() * 0.35f,
                fallScale = 0.75f + rng.nextFloat() * 0.55f,
                drift = (rng.nextFloat() - 0.5f) * 1.8f,
                wobblePhase = rng.nextFloat() * 6.28f,
                color = listOf(gold, accent, Color.White, Color(0xFFE91E63))[rng.nextInt(4)],
                radius = 3f + rng.nextFloat() * 5f,
                isStrip = rng.nextBoolean(),
                spin = rng.nextFloat() * 6.28f
            )
        }
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 2800))
    }

    val p = progress.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
            .background(Color.Black.copy(alpha = 0.38f))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            for (b in bits) {
                val t = ((p - b.delayFrac).coerceIn(0f, 1f)) / (1f - b.delayFrac).coerceAtLeast(0.05f)
                val yy = -30f + t * (h + 60f) * b.fallScale
                val xx = b.xFrac * w + b.drift * w * 0.4f * t + sin(t * 8f + b.wobblePhase) * 18f * t
                if (yy < -20f || yy > h + 40f) continue
                if (b.isStrip) {
                    rotateRad(t * 4f + b.spin, Offset(xx, yy)) {
                        drawRoundRect(
                            color = b.color,
                            topLeft = Offset(xx - b.radius * 2f, yy - b.radius * 0.6f),
                            size = Size(b.radius * 4f, b.radius * 1.2f),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }
                } else {
                    drawCircle(color = b.color, radius = b.radius, center = Offset(xx, yy))
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "You won!",
                color = gold,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = 54.sp
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Final score: $finalScore",
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 48.sp
            )
        }
    }
}

/** Loser endgame: dim overlay and message only (no confetti, no horn). */
@Composable
fun GameLostOverlay(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
            .background(Color.Black.copy(alpha = 0.48f))
    ) {
        Text(
            text = message,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )
    }
}
