package com.locxx.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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

/** Sunset / WLW–associated 5-stripe palette (first white die). */
private val LocxxInclusivityWlwStripes: List<Color> = listOf(
    Color(0xFFD52D00),
    Color(0xFFFF9B54),
    Color(0xFFFFFFFF),
    Color(0xFFE4ACC9),
    Color(0xFFA40062)
)

/** Progress Pride–inspired horizontal stripes: marginalized colors + rainbow (second white die). */
private val LocxxInclusivityProgressStripes: List<Color> = listOf(
    Color(0xFF000000),
    Color(0xFF784F17),
    Color(0xFF62CDFF),
    Color(0xFFFF8DCF),
    Color(0xFFFFFFFF),
    Color(0xFFE40303),
    Color(0xFFFF8C00),
    Color(0xFFFFED00),
    Color(0xFF008026),
    Color(0xFF004CFF),
    Color(0xFF732982)
)

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

@Composable
private fun HorizontalStripedDieFace(stripes: List<Color>, modifier: Modifier = Modifier) {
    Column(modifier) {
        stripes.forEach { c ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(c)
            )
        }
    }
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
    val inclusivityOn by LocxxInclusivityDiceConfig.enabledState
    val showInclusivityStripes = inclusivityOn &&
        (slot == LocXXDieSlot.WHITE1 || slot == LocXXDieSlot.WHITE2)
    val face = faceColorForDieSlot(slot)
    val dot = dotColorForDieSlot(slot)
    val inclusivityPip = Color(0xFF151515)
    val pips = pipIndicesForValue(value)
    val corner = RoundedCornerShape(size * 0.22f)
    Box(
        modifier = modifier
            .size(size)
            .clip(corner)
            .then(
                if (showInclusivityStripes) Modifier else Modifier.background(face)
            )
            .border(2.dp, SnotzeePlainDiceBorder, corner)
            .padding(size * 0.1f)
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.16f))) {
            when {
                showInclusivityStripes && slot == LocXXDieSlot.WHITE1 ->
                    HorizontalStripedDieFace(LocxxInclusivityWlwStripes, Modifier.fillMaxSize())
                showInclusivityStripes && slot == LocXXDieSlot.WHITE2 ->
                    HorizontalStripedDieFace(LocxxInclusivityProgressStripes, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize().background(face))
            }
        }
        if (placeholder) {
            Text(
                "—",
                modifier = Modifier.align(Alignment.Center),
                color = if (showInclusivityStripes) {
                    inclusivityPip.copy(alpha = 0.55f)
                } else {
                    dot.copy(alpha = 0.45f)
                },
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
                                val pipColor = if (showInclusivityStripes) inclusivityPip else dot
                                Box(
                                    Modifier
                                        .size(size * 0.14f)
                                        .clip(CircleShape)
                                        .background(pipColor)
                                        .then(
                                            if (showInclusivityStripes) {
                                                Modifier.border(
                                                    1.dp,
                                                    Color.White.copy(alpha = 0.88f),
                                                    CircleShape
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
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
    modifier: Modifier = Modifier,
    /** When true, only the two white dice are drawn (passive players in multiplayer). */
    whiteDiceOnly: Boolean = false
) {
    val allSlots = listOf(
        LocXXDieSlot.WHITE1 to white1,
        LocXXDieSlot.WHITE2 to white2,
        LocXXDieSlot.RED to red,
        LocXXDieSlot.YELLOW to yellow,
        LocXXDieSlot.GREEN to green,
        LocXXDieSlot.BLUE to blue
    )
    val slots = if (whiteDiceOnly) allSlots.take(2) else allSlots
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
    onRollAnimationFinished: () -> Unit = {},
    /** Debug: double-tap — first white die is up to the app; second white ignored; colored dice rigged lock roll (single-player). */
    onDieDoubleTap: ((LocXXDieSlot) -> Unit)? = null,
    /** Passive multiplayer: show only whites (matches score-card options). */
    whiteDiceOnly: Boolean = false
) {
    if (roll == null) {
        if (onDieDoubleTap == null) {
            LocXXDiceStrip(
                1,
                1,
                1,
                1,
                1,
                1,
                hasRoll = false,
                dieSize = dieSize,
                modifier = modifier,
                whiteDiceOnly = whiteDiceOnly
            )
            return
        }
        // Same order as non-null branch so debug double-tap works when there is no lastRoll yet (LAN idle).
        val allSlots = listOf(
            LocXXDieSlot.WHITE1,
            LocXXDieSlot.WHITE2,
            LocXXDieSlot.RED,
            LocXXDieSlot.YELLOW,
            LocXXDieSlot.GREEN,
            LocXXDieSlot.BLUE
        )
        val slots = if (whiteDiceOnly) allSlots.take(2) else allSlots
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            slots.forEach { slot ->
                val tapMod =
                    if (slot != LocXXDieSlot.WHITE2) {
                        val cb = onDieDoubleTap
                        Modifier.pointerInput(slot, onDieDoubleTap) {
                            detectTapGestures(onDoubleTap = { cb(slot) })
                        }
                    } else {
                        Modifier
                    }
                Box(modifier = tapMod) {
                    SnotzeeStyleDie(
                        value = 1,
                        slot = slot,
                        size = dieSize,
                        placeholder = true
                    )
                }
            }
        }
        return
    }

    val dieCount = if (whiteDiceOnly) 2 else 6
    val rotations = remember(animationKey, whiteDiceOnly) { List(dieCount) { Animatable(0f) } }
    val rng = remember { Random.Default }
    var displayRoll by remember(roll, animationKey) {
        mutableStateOf(roll.randomFaceTick(rng))
    }

    LaunchedEffect(roll, animationKey, whiteDiceOnly) {
        playLocxxDiceRollFlutter()
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

    val allSlots = listOf(
        LocXXDieSlot.WHITE1 to displayRoll.white1,
        LocXXDieSlot.WHITE2 to displayRoll.white2,
        LocXXDieSlot.RED to displayRoll.red,
        LocXXDieSlot.YELLOW to displayRoll.yellow,
        LocXXDieSlot.GREEN to displayRoll.green,
        LocXXDieSlot.BLUE to displayRoll.blue
    )
    val slots = if (whiteDiceOnly) allSlots.take(2) else allSlots
    val allInactive = listOf(
        roll.white1 == 0,
        roll.white2 == 0,
        roll.red == 0,
        roll.yellow == 0,
        roll.green == 0,
        roll.blue == 0
    )
    val inactive = if (whiteDiceOnly) allInactive.take(2) else allInactive
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slots.forEachIndexed { index, (slot, v) ->
            val inactiveDie = inactive[index]
            val spin = rotations[index].value
            val tapMod = if (onDieDoubleTap != null && slot != LocXXDieSlot.WHITE2) {
                val cb = onDieDoubleTap
                Modifier.pointerInput(slot, onDieDoubleTap) {
                    detectTapGestures(onDoubleTap = { cb(slot) })
                }
            } else {
                Modifier
            }
            Box(
                modifier = tapMod.graphicsLayer {
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
