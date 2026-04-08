package com.locxx.app

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.locxx.rules.MatchState
import com.locxx.rules.PlayerSheet
import androidx.compose.material3.AlertDialog
import com.locxx.rules.LegalMove
import com.locxx.rules.LocXXRules
import com.locxx.rules.RowId
import com.locxx.rules.rowValues
import kotlinx.coroutines.delay

private sealed class GameEndPrimaryOverlay {
    data class Win(val finalScore: Int) : GameEndPrimaryOverlay()
    data class Loss(val message: String) : GameEndPrimaryOverlay()
}

@Composable
fun SinglePlayerGameScreen(
    vm: LocXXViewModel,
    onExit: () -> Unit,
    onOpenSoundSettings: () -> Unit = {},
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

    var showExitGameConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = { showExitGameConfirm = true })

    val matchAuthoritative by vm.match.collectAsState()
    val match = matchAuthoritative?.let { vm.multiplayerMatchForScoreSheet(it) }
    val lastRoll by vm.lastRoll.collectAsState()
    val diceRollGeneration by vm.diceRollGeneration.collectAsState()
    val diceRollAnimationSettled by vm.diceRollAnimationSettled.collectAsState()
    val resolution by vm.rollResolution.collectAsState()
    val legalMoves by vm.legalMoves.collectAsState()
    val legalMovesForSheet = if (diceRollAnimationSettled) legalMoves else emptyList()
    val gameOver by vm.gameOverReason.collectAsState()
    val rowLockCelebration by vm.rowLockCelebration.collectAsState()
    val role by vm.role.collectAsState()
    val localPlayerIndex by vm.localPlayerIndex.collectAsState()
    val whitePhaseAcks by vm.whitePhaseAcks.collectAsState()
    val lanLockReadyBySeat by vm.lanLockReadyBySeat.collectAsState()
    val peers by vm.peers.collectAsState()
    val lanPlayerDisplayNames by vm.lanPlayerDisplayNames.collectAsState()

    val sheet = match?.playerSheets?.getOrNull(localPlayerIndex)
    val globallyLockedRows = match?.globallyLockedRows ?: emptySet()
    val isActiveSeat = match?.activePlayerIndex == localPlayerIndex
    val playerCount = match?.playerCount ?: 0
    val rollDoneCountLan =
        if (playerCount > 0) whitePhaseAcks.size else 0
    val selfRollDone = localPlayerIndex in whitePhaseAcks
    val lanMp = role == LocXXViewModel.Role.Host || role == LocXXViewModel.Role.Client
    val canRollDice =
        gameOver == null && resolution == null &&
            isActiveSeat &&
            (role == LocXXViewModel.Role.SinglePlayer ||
                role == LocXXViewModel.Role.Host ||
                role == LocXXViewModel.Role.Client)
    val canSinglePlayerEndTurn =
        gameOver == null && resolution != null && role == LocXXViewModel.Role.SinglePlayer
    /** LAN: any seat may press Done once per open roll (roller ends roll; others finish white phase). */
    val canPressDoneLan =
        lanMp && gameOver == null && resolution != null && !selfRollDone
    val canRollerPenaltyLan =
        lanMp && gameOver == null && resolution != null && isActiveSeat && !selfRollDone
    val endTurnOrDonePulsate =
        canSinglePlayerEndTurn || canPressDoneLan
    val endTurnPulseTransition = rememberInfiniteTransition(label = "endTurnDonePulse")
    val endTurnPulseScale by endTurnPulseTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "endTurnDoneScale"
    )
    var ambiguous by remember { mutableStateOf<List<LegalMove>?>(null) }
    var showGameOverBreakdown by remember { mutableStateOf(false) }
    var showCurrentScoreBreakdown by remember { mutableStateOf(false) }
    var yourTurnRollTrigger by remember { mutableIntStateOf(0) }

    var yourTurnRollVisible by remember { mutableStateOf(false) }

    var gameOverEndOverlayDismissed by remember { mutableStateOf(false) }

    val opponentHeaderScroll = rememberScrollState()

    LaunchedEffect(canRollDice, lanMp, gameOver) {

        if (lanMp && canRollDice && gameOver == null) {

            yourTurnRollTrigger++

        }

    }

    LaunchedEffect(yourTurnRollTrigger) {

        if (yourTurnRollTrigger == 0) return@LaunchedEffect

        yourTurnRollVisible = true

        delay(YourTurnToRollCueMsBeforeDismiss.toLong())

        yourTurnRollVisible = false

    }

    LaunchedEffect(gameOver) {

        if (gameOver != null) {

            yourTurnRollVisible = false

            gameOverEndOverlayDismissed = false

            showGameOverBreakdown = false

        } else {

            gameOverEndOverlayDismissed = false

            showGameOverBreakdown = false

        }

    }

    val gameEndPrimaryOverlay: GameEndPrimaryOverlay? = run {
        if (gameOver == null || gameOverEndOverlayDismissed) return@run null
        val m = match ?: return@run null
        val s = sheet ?: return@run null
        peers.size
        lanPlayerDisplayNames.size
        when {
            role == LocXXViewModel.Role.SinglePlayer ->
                null
            lanMp -> {
                val scores =
                    m.playerSheets.mapIndexed { idx, sh -> idx to LocXXRules.totalScore(sh) }
                val maxScore = scores.maxOf { it.second }
                val winnerSeats = scores.filter { it.second == maxScore }.map { it.first }.toSet()
                if (localPlayerIndex in winnerSeats) {
                    GameEndPrimaryOverlay.Win(LocXXRules.totalScore(s))
                } else {
                    val labels = winnerSeats.sorted().map { vm.seatDisplayName(it) }
                    val msg =
                        "${formatWinnerNamesEnglish(labels)} won the game. Better luck next time."
                    GameEndPrimaryOverlay.Loss(msg)
                }
            }
            else -> null
        }
    }

    LaunchedEffect(gameOver, gameEndPrimaryOverlay, sheet) {
        if (gameOver != null && gameEndPrimaryOverlay == null && sheet != null) {
            showGameOverBreakdown = true
        }
    }

    Surface(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
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
                    if (lanMp && playerCount > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(opponentHeaderScroll)
                        ) {
                            MultiplayerOpponentSummaries(
                                match = match,
                                localPlayerIndex = localPlayerIndex,
                                lockReadyHintBySeat = lanLockReadyBySeat,
                                seatDisplayName = { vm.seatDisplayName(it) }
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    RowId.entries.forEach { row ->
                        ScoreRowRow(
                            row = row,
                            rowState = sheet.rows[row] ?: return@forEach,
                            legalMoves = legalMovesForSheet,
                            expandCellsToWidth = true,
                            qwixxRowTint = true,
                            globallyLockedRows = globallyLockedRows,
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
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Score: ${LocXXRules.totalScore(sheet)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { showCurrentScoreBreakdown = true }
                                    .semantics {
                                        contentDescription = "Current score, tap for breakdown"
                                    }
                            )
                            PenaltyBoxes(
                                penaltyCount = sheet.penalties,
                                penaltyClickEnabled = when {
                                    role == LocXXViewModel.Role.SinglePlayer ->
                                        canSinglePlayerEndTurn || vm.canUndoSinglePlayerVoluntaryPenalty()
                                    lanMp ->
                                        canRollerPenaltyLan || vm.canUndoLanVoluntaryPenalty()
                                    else -> false
                                },
                                onPenaltyClick = {
                                    when (role) {
                                        LocXXViewModel.Role.SinglePlayer -> vm.singlePlayerPenalty()
                                        LocXXViewModel.Role.Host, LocXXViewModel.Role.Client ->
                                            vm.multiplayerPenalty()
                                        else -> {}
                                    }
                                }
                            )
                        }
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
                            onRollAnimationFinished = { vm.notifyDiceRollAnimationFinished() },
                            onDieDoubleTap = {
                                when (role) {
                                    LocXXViewModel.Role.SinglePlayer ->
                                        vm.onSinglePlayerDieDoubleTap(it)
                                    LocXXViewModel.Role.Host, LocXXViewModel.Role.Client ->
                                        if (isActiveSeat) vm.onLanDieDoubleTap(it)
                                    else -> {}
                                }
                            },
                            whiteDiceOnly = lanMp && !isActiveSeat
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (lanMp && gameOver == null && resolution != null) {
                            Text(
                                text = "Done $rollDoneCountLan/$playerCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { vm.rollDice() },
                            enabled = canRollDice
                        ) { Text("Roll") }
                        Button(
                            onClick = {
                                when (role) {
                                    LocXXViewModel.Role.SinglePlayer -> vm.endSinglePlayerTurn()
                                    LocXXViewModel.Role.Host, LocXXViewModel.Role.Client ->
                                        vm.multiplayerDone()
                                    else -> {}
                                }
                            },
                            modifier = Modifier.graphicsLayer {
                                val s = if (endTurnOrDonePulsate) endTurnPulseScale else 1f
                                scaleX = s
                                scaleY = s
                            },
                            enabled = canSinglePlayerEndTurn || canPressDoneLan
                        ) {
                            Text(if (lanMp) "Done" else "End turn")
                        }
                        Button(
                            onClick = { showExitGameConfirm = true }
                        ) {
                            Text("Exit Game")
                        }
                        TextButton(onClick = onOpenSoundSettings) {
                            Text("Settings")
                        }
                    }
                }
            }
        }
        YourTurnToRollCue(visible = yourTurnRollVisible)
        RowLockCelebrationOverlay(
            celebration = rowLockCelebration,
            onDismiss = { vm.dismissRowLockCelebration() },
            modifier = Modifier.fillMaxSize()
        )
        when (val end = gameEndPrimaryOverlay) {
            is GameEndPrimaryOverlay.Win ->
                GameWonCelebrationOverlay(
                    finalScore = end.finalScore,
                    onDismiss = {
                        gameOverEndOverlayDismissed = true
                        showGameOverBreakdown = sheet != null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            is GameEndPrimaryOverlay.Loss ->
                GameLostOverlay(
                    message = end.message,
                    onDismiss = {
                        gameOverEndOverlayDismissed = true
                        showGameOverBreakdown = sheet != null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            null -> Unit
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

    if (showCurrentScoreBreakdown && sheet != null) {
        AlertDialog(
            onDismissRequest = { showCurrentScoreBreakdown = false },
            modifier = Modifier.fillMaxWidth(0.62f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text("Current Score") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    GameOverScoreBreakdownRow(sheet = sheet)
                }
            },
            confirmButton = {
                TextButton(onClick = { showCurrentScoreBreakdown = false }) {
                    Text("Close")
                }
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
            dismissButton = {
                TextButton(onClick = { showGameOverBreakdown = false }) {
                    Text("OK")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGameOverBreakdown = false
                        vm.startSinglePlayer()
                    }
                ) { Text("Play again") }
            }
        )
    }

    if (showExitGameConfirm) {
        val exitDetail = when (role) {
            LocXXViewModel.Role.Host ->
                "Return to the main menu? Everyone connected will be disconnected."
            LocXXViewModel.Role.Client ->
                "Return to the main menu? You will leave this session."
            else -> "Return to the main menu?"
        }
        AlertDialog(
            onDismissRequest = { showExitGameConfirm = false },
            title = { Text("Exit game?") },
            text = {
                Text(
                    exitDetail,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            dismissButton = {
                TextButton(onClick = { showExitGameConfirm = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitGameConfirm = false
                        vm.stopAll()
                        onExit()
                    }
                ) { Text("Exit") }
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

@Composable
private fun MultiplayerOpponentSummaries(
    match: MatchState,
    localPlayerIndex: Int,
    lockReadyHintBySeat: List<Set<RowId>>?,
    seatDisplayName: (Int) -> String
) {
    val opponentSeats =
        remember(match.playerCount, localPlayerIndex) {
            (0 until match.playerCount).filter { it != localPlayerIndex }
        }
    if (opponentSeats.isEmpty()) return
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 14.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (seat in opponentSeats) {
            val sh = match.playerSheets.getOrNull(seat) ?: continue
            val locked = RowId.entries.filter { r -> sh.rows[r]?.locked == true }
            val fromSheet = RowId.entries.filter { r ->
                val st = sh.rows[r] ?: return@filter false
                val lockValue = rowValues(r).last()
                LocXXRules.isLockCellReadyToMark(r, st, lockValue)
            }.toSet()
            val lockPulseRows =
                fromSheet.union(lockReadyHintBySeat?.getOrNull(seat).orEmpty())
            OpponentSummaryLine(
                isCurrentTurn = match.activePlayerIndex == seat,
                displayName = seatDisplayName(seat).take(10),
                score = LocXXRules.totalScore(sh),
                penalties = sh.penalties.coerceIn(0, 4),
                lockedRows = locked,
                lockPulseRows = lockPulseRows,
                opponentSeat = seat,
                labelStyle = labelStyle
            )
        }
    }
}

@Composable
private fun OpponentSummaryLine(
    isCurrentTurn: Boolean,
    displayName: String,
    score: Int,
    penalties: Int,
    lockedRows: List<RowId>,
    lockPulseRows: Set<RowId>,
    opponentSeat: Int,
    labelStyle: TextStyle
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrentTurn) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color(0xFF2E7D32), CircleShape)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "$displayName: $score/$penalties/",
                style = labelStyle,
                maxLines = 1
            )
            OpponentLockIconsRow(
                opponentSeat = opponentSeat,
                lockedRows = lockedRows,
                lockPulseRows = lockPulseRows
            )
        }
    }
}

@Composable
private fun OpponentLockIconsRow(
    opponentSeat: Int,
    lockedRows: List<RowId>,
    lockPulseRows: Set<RowId>
) {
    val lockedSet = lockedRows.toSet()
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (row in RowId.entries) {
            val locked = row in lockedSet
            val pulseLock = row in lockPulseRows && !locked
            val base = qwixxRowOutline(row)
            if (pulseLock) {
                val lockPulse = rememberInfiniteTransition(label = "oppLockPulse${opponentSeat}_${row.name}")
                val lockPulseScale by lockPulse.animateFloat(
                    initialValue = 0.82f,
                    targetValue = 1.18f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "oppLockPulseScale${opponentSeat}_${row.name}"
                )
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer {
                                scaleX = lockPulseScale
                                scaleY = lockPulseScale
                                transformOrigin = TransformOrigin.Center
                            },
                        tint = base
                    )
                }
            } else {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = if (locked) base else base.copy(alpha = 0.45f)
                )
            }
        }
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
