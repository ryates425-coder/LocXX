package com.locxx.app

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.locxx.rules.PlayerSheet
import androidx.compose.material3.AlertDialog
import com.locxx.rules.LegalMove
import com.locxx.rules.LocXXRules
import com.locxx.rules.RowId

@Composable
fun SinglePlayerGameScreen(
    vm: LocXXViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val prev = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = prev
        }
    }

    BackHandler(onBack = onExit)

    val match by vm.match.collectAsState()
    val lastRoll by vm.lastRoll.collectAsState()
    val diceRollGeneration by vm.diceRollGeneration.collectAsState()
    val diceRollAnimationSettled by vm.diceRollAnimationSettled.collectAsState()
    val resolution by vm.rollResolution.collectAsState()
    val legalMoves by vm.legalMoves.collectAsState()
    val legalMovesForSheet = if (diceRollAnimationSettled) legalMoves else emptyList()
    val gameOver by vm.gameOverReason.collectAsState()

    val sheet = match?.playerSheets?.getOrNull(0)
    var ambiguous by remember { mutableStateOf<List<LegalMove>?>(null) }
    var showGameOverBreakdown by remember { mutableStateOf(false) }

    LaunchedEffect(gameOver) {
        if (gameOver != null) showGameOverBreakdown = true
        else showGameOverBreakdown = false
    }

    Surface(modifier.fillMaxSize()) {
        if (sheet == null) {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = onExit) { Text("Back") }
                Text("No active game.")
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Score: ${LocXXRules.totalScore(sheet)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        PenaltyBoxes(
                            penaltyCount = sheet.penalties,
                            penaltyClickEnabled = gameOver == null && resolution != null,
                            onPenaltyClick = { vm.singlePlayerPenalty() }
                        )
                    }
                    RowId.entries.forEach { row ->
                        ScoreRowRow(
                            row = row,
                            rowState = sheet.rows[row] ?: return@forEach,
                            legalMoves = legalMovesForSheet,
                            expandCellsToWidth = true,
                            qwixxRowTint = true,
                            canUndoCell = { r, v -> vm.canUndoCell(r, v) },
                            onCellClick = { r, v, moves ->
                                when {
                                    moves.size == 1 -> vm.applyLegalMove(moves.first())
                                    moves.size > 1 -> ambiguous = moves
                                    else -> vm.tryUndoCell(r, v)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        AnimatedLocXXDiceStrip(
                            roll = lastRoll,
                            animationKey = diceRollGeneration,
                            dieSize = 40.dp,
                            onRollAnimationFinished = { vm.notifyDiceRollAnimationFinished() }
                        )
                    }

                    Row(
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
                        TextButton(onClick = onExit) { Text("Back") }
                    }
                }

                gameOver?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.startSinglePlayer() }) { Text("Play again") }
                }
            }
        }
    }

    ambiguous?.let { options ->
        AlertDialog(
            onDismissRequest = { ambiguous = null },
            title = { Text("Choose move") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val roll = lastRoll
                    options.forEach { m ->
                        TextButton(
                            onClick = {
                                vm.applyLegalMove(m)
                                ambiguous = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (roll != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    LegalMoveDiceVisual(roll = roll, move = m, dieSize = 40.dp)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = moveLabel(m),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(moveLabel(m))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ambiguous = null }) { Text("Cancel") }
            }
        )
    }

    if (showGameOverBreakdown && sheet != null) {
        AlertDialog(
            onDismissRequest = { showGameOverBreakdown = false },
            modifier = Modifier.fillMaxWidth(0.62f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text("Game over") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Total",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GameOverScoreBreakdownRow(sheet = sheet)
                }
            },
            confirmButton = {
                TextButton(onClick = { showGameOverBreakdown = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun GameOverRowScoreBox(
    row: RowId,
    sheet: PlayerSheet,
    boxStyle: TextStyle,
    outline: Color,
    shape: RoundedCornerShape,
    numberColor: Color
) {
    val pts = LocXXRules.rowPoints(sheet, row)
    Box(
        modifier = Modifier
            .background(qwixxCellDefault(row), shape)
            .border(1.5.dp, outline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(pts.toString(), style = boxStyle, color = numberColor)
    }
}

@Composable
private fun GameOverScoreBreakdownRow(sheet: PlayerSheet) {
    val scroll = rememberScrollState()
    val boxStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    val opStyle = MaterialTheme.typography.titleLarge
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val shape = RoundedCornerShape(10.dp)
    val numberColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GameOverRowScoreBox(RowId.RED, sheet, boxStyle, outline, shape, numberColor)
        Text("+", style = opStyle)
        GameOverRowScoreBox(RowId.YELLOW, sheet, boxStyle, outline, shape, numberColor)
        Text("+", style = opStyle)
        GameOverRowScoreBox(RowId.GREEN, sheet, boxStyle, outline, shape, numberColor)
        Text("+", style = opStyle)
        GameOverRowScoreBox(RowId.BLUE, sheet, boxStyle, outline, shape, numberColor)
        Text("−", style = opStyle)
        val penaltyPts = sheet.penalties * 5
        Box(
            modifier = Modifier
                .background(Color(0xFFF5F5F5), shape)
                .border(1.5.dp, outline, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(penaltyPts.toString(), style = boxStyle, color = numberColor)
        }
        Text("=", style = opStyle)
        Text(
            LocXXRules.totalScore(sheet).toString(),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private const val PenaltyBoxSlots = 4

@Composable
private fun PenaltyBoxes(
    penaltyCount: Int,
    penaltyClickEnabled: Boolean,
    onPenaltyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val count = penaltyCount.coerceIn(0, PenaltyBoxSlots)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Penalties",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .semantics { contentDescription = "Take penalty" }
                .clickable(
                    enabled = penaltyClickEnabled,
                    onClick = onPenaltyClick
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(PenaltyBoxSlots) { index ->
                    val filled = index < count
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(3.dp)
                            )
                            .background(
                                color = if (filled) {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(3.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (filled) {
                        Text(
                            text = "X",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
