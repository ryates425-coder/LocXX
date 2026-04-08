package com.locxx.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locxx.rules.LegalMove
import com.locxx.rules.LocXXRules
import com.locxx.rules.PlayerRowState
import com.locxx.rules.RowId
import com.locxx.rules.rowValues

internal fun moveLabel(m: LegalMove): String = when (m) {
    is LegalMove.WhiteSum -> "White sum ${m.row.name} = ${m.value}"
    is LegalMove.ColorCombo -> "Color ${m.row.name} = ${m.value} (${m.whiteDie.name})"
}

internal fun movesForCell(
    legalMoves: List<LegalMove>,
    row: RowId,
    value: Int
): List<LegalMove> = legalMoves.filter { move ->
    when (move) {
        is LegalMove.WhiteSum -> move.row == row && move.value == value
        is LegalMove.ColorCombo -> move.row == row && move.value == value
    }
}

internal fun qwixxRowSheetTint(row: RowId): Color = when (row) {
    RowId.RED -> Color(0xFFFFEBEE)
    RowId.YELLOW -> Color(0xFFFFFDE7)
    RowId.GREEN -> Color(0xFFE8F5E9)
    RowId.BLUE -> Color(0xFFE3F2FD)
}

/** Subtle dark outline hue for each row (Qwixx-style sheet rows). */
internal fun qwixxRowOutline(row: RowId): Color = when (row) {
    RowId.RED -> Color(0xFFC62828)
    RowId.YELLOW -> Color(0xFFF9A825)
    RowId.GREEN -> Color(0xFF2E7D32)
    RowId.BLUE -> Color(0xFF1565C0)
}

/** Default cell fill: darker than [qwixxRowSheetTint] but still light enough for dark text. */
internal fun qwixxCellDefault(row: RowId): Color = when (row) {
    RowId.RED -> Color(0xFFEF9A9A)
    RowId.YELLOW -> Color(0xFFFFF59D)
    RowId.GREEN -> Color(0xFFA5D6A7)
    RowId.BLUE -> Color(0xFF90CAF9)
}

/** Skipped (dead) cells: very dark tint of the row hue — no strikethrough; read with [skippedCellLabelColor]. */
internal fun qwixxSkippedCellDark(row: RowId): Color = when (row) {
    RowId.RED -> Color(0xFF6D1F2A)
    RowId.YELLOW -> Color(0xFF6B5F00)
    RowId.GREEN -> Color(0xFF14532D)
    RowId.BLUE -> Color(0xFF0D3B66)
}

private val SkippedCellLabelOnDark = Color(0xFFF0F0F0)

private val ScoreCellFontSize = 16.sp

/** Compact rows for narrow landscape phones; tweak with [ScoreRowVerticalPadding]. */
private val ScoreCellRowHeight = 43.dp

private val ScoreRowVerticalPadding = 2.dp

private fun cellNumberAnnotated(value: Int) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
        append(value.toString())
    }
}

@Composable
private fun ScoreCellNumberOrLock(
    row: RowId,
    rowState: PlayerRowState,
    value: Int,
    crossed: Boolean,
    /** Paper skipped or closed row: show same “---” / styling as skipped. */
    skippedAppearance: Boolean,
    color: Color,
    style: TextStyle,
    textAlign: TextAlign,
    globallyLockedRows: Set<RowId> = emptySet()
) {
    when {
        LocXXRules.isLockCellLocked(row, rowState, value) -> {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(24.dp),
                tint = color
            )
        }
        LocXXRules.isGlobalLockBadgeOnly(row, rowState, value, globallyLockedRows) -> {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Row closed",
                modifier = Modifier.size(24.dp),
                tint = color.copy(alpha = 0.5f)
            )
        }
        crossed -> {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Marked",
                modifier = Modifier.size(24.dp),
                tint = color
            )
        }
        skippedAppearance -> {
            Text(
                text = "\u2014\u2014\u2014",
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = color,
                style = style.copy(fontWeight = FontWeight.Medium)
            )
        }
        else -> {
            Text(
                text = cellNumberAnnotated(value),
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = color,
                style = style
            )
        }
    }
}

@Composable
internal fun ScoreRowRow(
    row: RowId,
    rowState: PlayerRowState,
    legalMoves: List<LegalMove>,
    expandCellsToWidth: Boolean,
    qwixxRowTint: Boolean,
    globallyLockedRows: Set<RowId> = emptySet(),
    canUndoCell: (RowId, Int) -> Boolean = { _, _ -> false },
    onCellClick: (row: RowId, value: Int, moves: List<LegalMove>) -> Unit
) {
    val lockGlowTransition = rememberInfiniteTransition(label = "lockGlow${row.name}")
    val lockGlowAlpha by lockGlowTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lockGlowAlpha"
    )
    val rowGloballyClosedOthers =
        row in globallyLockedRows && !rowState.locked && rowState.crossCount == 0
    val rowBg = if (qwixxRowTint) {
        val base = qwixxRowSheetTint(row)
        if (rowGloballyClosedOthers) base.copy(alpha = 0.48f) else base
    } else {
        Color.Transparent
    }
    val rowShape = RoundedCornerShape(8.dp)
    val rowOutlineColor = if (qwixxRowTint) {
        qwixxRowOutline(row).copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    }
    val cellShape = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(rowBg)
            .border(width = 1.dp, color = rowOutlineColor, shape = rowShape)
            .padding(horizontal = 4.dp, vertical = ScoreRowVerticalPadding)
    ) {
        val values = rowValues(row)
        if (expandCellsToWidth) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 2.dp
                val n = values.size
                val cellW = ((maxWidth - gap * (n - 1)) / n).coerceAtLeast(1.dp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (i in values.indices) {
                        val value = values[i]
                        val crossed = LocXXRules.isValueCrossed(row, rowState, value)
                        val paperSkipped = LocXXRules.isValueSkipped(row, rowState, value)
                        val skippedAppearance = paperSkipped ||
                            (
                                LocXXRules.isRowClosedOnSheet(row, rowState, globallyLockedRows) &&
                                    !crossed &&
                                    !LocXXRules.isRowLastValueCell(row, value)
                                )
                        val lockPulse = LocXXRules.isLockCellReadyToMark(row, rowState, value)
                        val moves = movesForCell(legalMoves, row, value)
                        val highlight = moves.isNotEmpty()
                        val canUndo = canUndoCell(row, value)
                        val defaultCell =
                            if (qwixxRowTint) qwixxCellDefault(row)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        val bg = when {
                            crossed -> MaterialTheme.colorScheme.tertiaryContainer
                            highlight -> MaterialTheme.colorScheme.primaryContainer
                            skippedAppearance && qwixxRowTint -> qwixxSkippedCellDark(row)
                            skippedAppearance -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                            else -> defaultCell
                        }
                        val cellLabelColor = when {
                            skippedAppearance && qwixxRowTint -> SkippedCellLabelOnDark
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        val crossedOutline = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        val primaryGlow = MaterialTheme.colorScheme.primary
                        Surface(
                            modifier = Modifier
                                .width(cellW)
                                .height(ScoreCellRowHeight)
                                .then(
                                    when {
                                        lockPulse -> Modifier.border(
                                            width = (2.5f + 2f * lockGlowAlpha).dp,
                                            color = primaryGlow.copy(alpha = 0.4f + 0.55f * lockGlowAlpha),
                                            shape = cellShape
                                        )
                                        crossed -> Modifier.border(2.dp, crossedOutline, cellShape)
                                        else -> Modifier
                                    }
                                )
                                .clickable(enabled = highlight || canUndo) {
                                    onCellClick(row, value, moves)
                                },
                            shape = cellShape,
                            color = bg,
                            shadowElevation = when {
                                lockPulse -> (2f + 7f * lockGlowAlpha).dp
                                crossed -> 3.dp
                                highlight -> 2.dp
                                else -> 0.dp
                            }
                        ) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                val cellStyle =
                                    MaterialTheme.typography.labelLarge.copy(fontSize = ScoreCellFontSize)
                                ScoreCellNumberOrLock(
                                    row = row,
                                    rowState = rowState,
                                    value = value,
                                    crossed = crossed,
                                    skippedAppearance = skippedAppearance,
                                    color = cellLabelColor,
                                    style = cellStyle,
                                    textAlign = TextAlign.Center,
                                    globallyLockedRows = globallyLockedRows
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                for (i in values.indices) {
                    val value = values[i]
                    val crossed = LocXXRules.isValueCrossed(row, rowState, value)
                    val paperSkipped = LocXXRules.isValueSkipped(row, rowState, value)
                    val skippedAppearance = paperSkipped ||
                        (
                            LocXXRules.isRowClosedOnSheet(row, rowState, globallyLockedRows) &&
                                !crossed &&
                                !LocXXRules.isRowLastValueCell(row, value)
                            )
                    val lockPulse = LocXXRules.isLockCellReadyToMark(row, rowState, value)
                    val moves = movesForCell(legalMoves, row, value)
                    val highlight = moves.isNotEmpty()
                    val canUndo = canUndoCell(row, value)
                    val defaultCell =
                        if (qwixxRowTint) qwixxCellDefault(row)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    val cellBg = when {
                        crossed -> MaterialTheme.colorScheme.tertiaryContainer
                        highlight -> MaterialTheme.colorScheme.primaryContainer
                        skippedAppearance && qwixxRowTint -> qwixxSkippedCellDark(row)
                        skippedAppearance -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                        else -> defaultCell
                    }
                    val cellLabelColor = when {
                        skippedAppearance && qwixxRowTint -> SkippedCellLabelOnDark
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val primaryGlowScroll = MaterialTheme.colorScheme.primary
                    Button(
                        onClick = { onCellClick(row, value, moves) },
                        enabled = highlight || canUndo,
                        modifier = Modifier
                            .padding(0.dp)
                            .then(
                                when {
                                    lockPulse -> Modifier.border(
                                        width = (2.5f + 2f * lockGlowAlpha).dp,
                                        color = primaryGlowScroll.copy(alpha = 0.4f + 0.55f * lockGlowAlpha),
                                        shape = cellShape
                                    )
                                    crossed -> Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                                        cellShape
                                    )
                                    else -> Modifier
                                }
                            ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cellBg,
                            disabledContainerColor = cellBg,
                            contentColor = cellLabelColor,
                            disabledContentColor = cellLabelColor
                        )
                    ) {
                        val cellStyle =
                            MaterialTheme.typography.labelLarge.copy(fontSize = ScoreCellFontSize)
                        ScoreCellNumberOrLock(
                            row = row,
                            rowState = rowState,
                            value = value,
                            crossed = crossed,
                            skippedAppearance = skippedAppearance,
                            color = cellLabelColor,
                            style = cellStyle,
                            textAlign = TextAlign.Center,
                            globallyLockedRows = globallyLockedRows
                        )
                    }
                }
            }
        }
    }
}
