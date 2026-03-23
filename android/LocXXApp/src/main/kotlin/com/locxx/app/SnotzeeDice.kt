package com.locxx.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locxx.rules.DiceRoll
import com.locxx.rules.DieColor
import com.locxx.rules.LegalMove
import com.locxx.rules.RowId
import com.locxx.rules.dieColor
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * LocXX die order: white, white, red, yellow, green, blue — face colors aligned with
 * Snotzee plain-dice mode (white #fff, border #aaa; dark pips on white).
 * Colored dice use saturated faces with light pips.
 */
enum class LocXXDieSlot {
    WHITE1,
    WHITE2,
    RED,
    YELLOW,
    GREEN,
    BLUE
}

private val SnotzeePlainDiceFace = Color(0xFFFFFFFF)
private val SnotzeePlainDiceBorder = Color(0xFFAAAAAA)
private val SnotzeePlainDot = Color(0xFF111111)

private val LocXXRedFace = Color(0xFFE53935)
private val LocXXYellowFace = Color(0xFFFBC02D)
private val LocXXGreenFace = Color(0xFF34D399)
private val LocXXBlueFace = Color(0xFF3B82F6)

fun faceColorForDieSlot(slot: LocXXDieSlot): Color = when (slot) {
    LocXXDieSlot.WHITE1, LocXXDieSlot.WHITE2 -> SnotzeePlainDiceFace
    LocXXDieSlot.RED -> LocXXRedFace
    LocXXDieSlot.YELLOW -> LocXXYellowFace
    LocXXDieSlot.GREEN -> LocXXGreenFace
    LocXXDieSlot.BLUE -> LocXXBlueFace
}

fun dotColorForDieSlot(slot: LocXXDieSlot): Color = when (slot) {
    LocXXDieSlot.WHITE1, LocXXDieSlot.WHITE2 -> SnotzeePlainDot
    else -> Color(0xFFF8FAFC)
}

/** Pip layout matches Snotzee 3×3 grid indices (row-major 0–8). */
private fun pipIndicesForValue(value: Int): Set<Int> = when (value.coerceIn(1, 6)) {
    1 -> setOf(4)
    2 -> setOf(0, 8)
    3 -> setOf(0, 4, 8)
    4 -> setOf(0, 2, 6, 8)
    5 -> setOf(0, 2, 4, 6, 8)
    6 -> setOf(0, 2, 3, 5, 6, 8)
    else -> emptySet()
}

@Composable
fun SnotzeeStyleDie(
    value: Int,
    slot: LocXXDieSlot,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    placeholder: Boolean = false
) {
    val face = faceColorForDieSlot(slot)
    val dot = dotColorForDieSlot(slot)
    val pips = pipIndicesForValue(value)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(face)
            .border(2.dp, SnotzeePlainDiceBorder, RoundedCornerShape(size * 0.22f))
            .padding(size * 0.1f)
    ) {
        if (placeholder) {
            Text(
                "—",
                modifier = Modifier.align(Alignment.Center),
                color = dot.copy(alpha = 0.45f),
                fontSize = (size.value * 0.35f).sp,
                textAlign = TextAlign.Center
            )
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until 3) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in 0 until 3) {
                        val idx = r * 3 + c
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (idx in pips) {
                                Box(
                                    Modifier
                                        .size(size * 0.14f)
                                        .clip(CircleShape)
                                        .background(dot)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocXXDiceStrip(
    white1: Int,
    white2: Int,
    red: Int,
    yellow: Int,
    green: Int,
    blue: Int,
    hasRoll: Boolean,
    dieSize: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val slots = listOf(
        LocXXDieSlot.WHITE1 to white1,
        LocXXDieSlot.WHITE2 to white2,
        LocXXDieSlot.RED to red,
        LocXXDieSlot.YELLOW to yellow,
        LocXXDieSlot.GREEN to green,
        LocXXDieSlot.BLUE to blue
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slots.forEach { (slot, v) ->
            val inactive = hasRoll && v == 0
            SnotzeeStyleDie(
                value = v.coerceIn(1, 6),
                slot = slot,
                size = dieSize,
                placeholder = !hasRoll || inactive
            )
        }
    }
}

private fun RowId.toLocXXDieSlot(): LocXXDieSlot = when (this) {
    RowId.RED -> LocXXDieSlot.RED
    RowId.YELLOW -> LocXXDieSlot.YELLOW
    RowId.GREEN -> LocXXDieSlot.GREEN
    RowId.BLUE -> LocXXDieSlot.BLUE
}

/**
 * Shows the exact dice faces that produce [move] for this [roll] (whites, or white + color + sum).
 */
@Composable
fun LegalMoveDiceVisual(
    roll: DiceRoll,
    move: LegalMove,
    dieSize: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val opSize = (dieSize.value * 0.42f).sp
    val eqSize = (dieSize.value * 0.38f).sp
    val sumSize = (dieSize.value * 0.52f).sp
    val opColor = MaterialTheme.colorScheme.onSurfaceVariant
    val sumColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (move) {
            is LegalMove.WhiteSum -> {
                SnotzeeStyleDie(roll.white1, LocXXDieSlot.WHITE1, size = dieSize)
                Text("+", fontSize = opSize, color = opColor)
                SnotzeeStyleDie(roll.white2, LocXXDieSlot.WHITE2, size = dieSize)
                Text("=", fontSize = eqSize, color = opColor)
                Text(
                    "${move.value}",
                    fontSize = sumSize,
                    fontWeight = FontWeight.SemiBold,
                    color = sumColor
                )
            }
            is LegalMove.ColorCombo -> {
                val wSlot = when (move.whiteDie) {
                    DieColor.WHITE1 -> LocXXDieSlot.WHITE1
                    DieColor.WHITE2 -> LocXXDieSlot.WHITE2
                    else -> LocXXDieSlot.WHITE1
                }
                val wv = roll.value(move.whiteDie)
                val cv = roll.value(move.row.dieColor())
                val colorSlot = move.row.toLocXXDieSlot()
                SnotzeeStyleDie(
                    value = wv.coerceIn(1, 6),
                    slot = wSlot,
                    size = dieSize,
                    placeholder = wv == 0
                )
                Text("+", fontSize = opSize, color = opColor)
                SnotzeeStyleDie(
                    value = cv.coerceIn(1, 6),
                    slot = colorSlot,
                    size = dieSize,
                    placeholder = cv == 0
                )
                Text("=", fontSize = eqSize, color = opColor)
                Text(
                    "${move.value}",
                    fontSize = sumSize,
                    fontWeight = FontWeight.SemiBold,
                    color = sumColor
                )
            }
        }
    }
}

/** Tumble faces while spinning; staggered 2.5D rotation per die (Snotzee-style roll). */
private fun DiceRoll.randomFaceTick(rng: Random): DiceRoll = copy(
    white1 = if (white1 > 0) rng.nextInt(1, 7) else 0,
    white2 = if (white2 > 0) rng.nextInt(1, 7) else 0,
    red = if (red > 0) rng.nextInt(1, 7) else 0,
    yellow = if (yellow > 0) rng.nextInt(1, 7) else 0,
    green = if (green > 0) rng.nextInt(1, 7) else 0,
    blue = if (blue > 0) rng.nextInt(1, 7) else 0
)

private const val SnotzeeRollTumbleMs = 820
private const val SnotzeeRollSpinMs = 640
private const val SnotzeeDieStaggerMs = 28

@Composable
fun AnimatedLocXXDiceStrip(
    roll: DiceRoll?,
    animationKey: Int = 0,
    dieSize: Dp = 40.dp,
    modifier: Modifier = Modifier,
    onRollAnimationFinished: () -> Unit = {}
) {
    if (roll == null) {
        LocXXDiceStrip(1, 1, 1, 1, 1, 1, hasRoll = false, dieSize = dieSize, modifier = modifier)
        return
    }

    val rotations = remember { List(6) { Animatable(0f) } }
    val rng = remember { Random.Default }
    var displayRoll by remember(roll, animationKey) {
        mutableStateOf(roll.randomFaceTick(rng))
    }

    LaunchedEffect(roll, animationKey) {
        val endAt = System.currentTimeMillis() + SnotzeeRollTumbleMs
        coroutineScope {
            launch {
                while (System.currentTimeMillis() < endAt && isActive) {
                    displayRoll = roll.randomFaceTick(rng)
                    delay(40)
                }
                displayRoll = roll
            }
            rotations.forEachIndexed { i, anim ->
                launch {
                    delay(i * SnotzeeDieStaggerMs.toLong())
                    anim.snapTo(0f)
                    anim.animateTo(
                        targetValue = 360f * 3f,
                        animationSpec = tween(SnotzeeRollSpinMs, easing = FastOutSlowInEasing)
                    )
                    anim.snapTo(0f)
                }
            }
        }
        onRollAnimationFinished()
    }

    val slots = listOf(
        LocXXDieSlot.WHITE1 to displayRoll.white1,
        LocXXDieSlot.WHITE2 to displayRoll.white2,
        LocXXDieSlot.RED to displayRoll.red,
        LocXXDieSlot.YELLOW to displayRoll.yellow,
        LocXXDieSlot.GREEN to displayRoll.green,
        LocXXDieSlot.BLUE to displayRoll.blue
    )
    val inactive = listOf(
        roll.white1 == 0,
        roll.white2 == 0,
        roll.red == 0,
        roll.yellow == 0,
        roll.green == 0,
        roll.blue == 0
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slots.forEachIndexed { index, (slot, v) ->
            val inactiveDie = inactive[index]
            val spin = rotations[index].value
            Box(
                modifier = Modifier.graphicsLayer {
                    rotationZ = if (inactiveDie) 0f else spin
                }
            ) {
                SnotzeeStyleDie(
                    value = v.coerceIn(1, 6),
                    slot = slot,
                    size = dieSize,
                    placeholder = inactiveDie
                )
            }
        }
    }
}
