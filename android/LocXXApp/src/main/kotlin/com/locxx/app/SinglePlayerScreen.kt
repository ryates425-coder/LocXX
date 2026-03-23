package com.locxx.app

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.locxx.rules.LegalMove
import com.locxx.rules.LocXXRules
import com.locxx.rules.PlayerRowState
import com.locxx.rules.RowId
import com.locxx.rules.rowValues

@Composable
fun SinglePlayerSection(vm: LocXXViewModel, modifier: Modifier = Modifier) {
    val match by vm.match.collectAsState()
    val lastRoll by vm.lastRoll.collectAsState()
    val resolution by vm.rollResolution.collectAsState()
    val legalMoves by vm.legalMoves.collectAsState()
    val gameOver by vm.gameOverReason.collectAsState()
    val crosses by vm.crossesThisRoll.collectAsState()

    val sheet = match?.playerSheets?.getOrNull(0) ?: return
    var ambiguous by remember { mutableStateOf<List<LegalMove>?>(null) }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Single player", style = MaterialTheme.typography.titleMedium)
        Text(
            "Score: ${LocXXRules.totalScore(sheet)}  Penalties: ${sheet.penalties}",
            style = MaterialTheme.typography.bodyLarge
        )
        lastRoll?.let { r ->
            Text(
                "Dice: W1=${r.white1} W2=${r.white2} R=${r.red} Y=${r.yellow} G=${r.green} B=${r.blue}  sum=${r.whiteSum()}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        resolution?.let {
            Text(
                "Turn: ${it.pathChoice.name}  color whites used: ${it.whiteUsedForColor.map { d -> d.name }}",
                style = MaterialTheme.typography.labelSmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { vm.rollDice() },
                enabled = gameOver == null && resolution == null
            ) { Text("Roll") }
            Button(
                onClick = { vm.endSinglePlayerTurn() },
                enabled = gameOver == null && resolution != null
            ) { Text("End turn") }
            Button(
                onClick = { vm.singlePlayerPenalty() },
                enabled = gameOver == null &&
                    resolution != null &&
                    legalMoves.isEmpty() &&
                    crosses == 0
            ) { Text("Penalty") }
        }
        gameOver?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error)
            Button(onClick = { vm.startSinglePlayer() }) { Text("Play again") }
        }
        Spacer(Modifier.height(4.dp))
        RowId.entries.forEach { row ->
            ScoreRowRow(
                row = row,
                rowState = sheet.rows[row] ?: return@forEach,
                lockedGlobally = match?.globallyLockedRows?.contains(row) == true,
                legalMoves = legalMoves,
                expandCellsToWidth = isLandscape,
                onCellClick = { moves ->
                    when (moves.size) {
                        0 -> {}
                        1 -> vm.applyLegalMove(moves.first())
                        else -> ambiguous = moves
                    }
                }
            )
        }
    }

    ambiguous?.let { options ->
        AlertDialog(
            onDismissRequest = { ambiguous = null },
            title = { Text("Choose move") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { m ->
                        TextButton(onClick = {
                            vm.applyLegalMove(m)
                            ambiguous = null
                        }) {
                            Text(moveLabel(m))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ambiguous = null }) { Text("Cancel") }
            }
        )
    }
}

private fun moveLabel(m: LegalMove): String = when (m) {
    is LegalMove.WhiteSum -> "White sum ${m.row.name} = ${m.value}"
    is LegalMove.ColorCombo -> "Color ${m.row.name} = ${m.value} (${m.whiteDie.name})"
}

private fun isValueCrossed(row: RowId, state: PlayerRowState, value: Int): Boolean {
    val values = rowValues(row)
    val idx = values.indexOf(value)
    if (idx < 0) return false
    return idx <= state.lastCrossedIndex
}

private fun movesForCell(
    legalMoves: List<LegalMove>,
    row: RowId,
    value: Int
): List<LegalMove> = legalMoves.filter { move ->
    when (move) {
        is LegalMove.WhiteSum -> move.row == row && move.value == value
        is LegalMove.ColorCombo -> move.row == row && move.value == value
    }
}

@Composable
private fun ScoreRowRow(
    row: RowId,
    rowState: PlayerRowState,
    lockedGlobally: Boolean,
    legalMoves: List<LegalMove>,
    expandCellsToWidth: Boolean,
    onCellClick: (List<LegalMove>) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${row.name}${if (lockedGlobally) " (locked)" else ""}${if (rowState.locked) " [row closed]" else ""}",
            style = MaterialTheme.typography.labelSmall
        )
        val values = rowValues(row)
        if (expandCellsToWidth) {
            // Surface + clickable avoids Material3 Button's 48dp min touch target crushing narrow cells.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 2.dp
                val n = values.size
                val cellW = ((maxWidth - gap * (n - 1)) / n).coerceAtLeast(1.dp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    for (i in values.indices) {
                        val value = values[i]
                        val crossed = isValueCrossed(row, rowState, value)
                        val moves = movesForCell(legalMoves, row, value)
                        val highlight = moves.isNotEmpty()
                        val label = if (crossed) "×" else value.toString()
                        val bg = when {
                            crossed -> MaterialTheme.colorScheme.tertiaryContainer
                            highlight -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        }
                        Surface(
                            modifier = Modifier
                                .width(cellW)
                                .height(40.dp)
                                .clickable(enabled = highlight) { onCellClick(moves) },
                            shape = RoundedCornerShape(4.dp),
                            color = bg,
                            shadowElevation = if (highlight) 2.dp else 0.dp
                        ) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.labelMedium
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
                    .padding(vertical = 2.dp)
            ) {
                for (i in values.indices) {
                    val value = values[i]
                    val crossed = isValueCrossed(row, rowState, value)
                    val moves = movesForCell(legalMoves, row, value)
                    val highlight = moves.isNotEmpty()
                    Button(
                        onClick = { onCellClick(moves) },
                        enabled = highlight,
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Text(
                            text = if (crossed) "×" else value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
