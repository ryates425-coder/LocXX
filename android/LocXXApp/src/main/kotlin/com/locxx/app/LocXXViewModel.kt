package com.locxx.app



import android.app.Application

import android.bluetooth.BluetoothManager

import android.content.Context

import androidx.lifecycle.AndroidViewModel

import androidx.lifecycle.viewModelScope

import com.locxx.bluetoothgaming.BluetoothGamingClient

import com.locxx.bluetoothgaming.BluetoothGamingHost

import com.locxx.bluetoothgaming.BluetoothGamingListener

import com.locxx.bluetoothgaming.ProtocolCodec

import com.locxx.bluetoothgaming.WireMessageType

import com.locxx.rules.DiceRoll

import com.locxx.rules.DieColor

import com.locxx.rules.LegalMove

import com.locxx.rules.LocXXRollResolution

import com.locxx.rules.LocXXRules

import com.locxx.rules.MatchState

import com.locxx.rules.RollResolutionState

import com.locxx.rules.RowId

import com.locxx.rules.initialMatchState

import com.locxx.rules.rowValues

import kotlin.collections.ArrayDeque

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.update

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

import kotlin.random.Random



data class UiPeer(val address: String, val playerId: Int, val displayName: String)

data class BleScanCandidate(val address: String, val name: String?, val rssi: Int)

private data class SinglePlayerUndoEntry(
    val match: MatchState,
    val resolution: RollResolutionState,
    val crosses: Int,
    val move: LegalMove
)

private fun MatchState.snapshot(): MatchState = copy(
    playerSheets = playerSheets.map { sheet ->
        sheet.copy(rows = sheet.rows.mapValues { (_, st) -> st.copy() })
    }
)

class LocXXViewModel(application: Application) : AndroidViewModel(application) {



    private val _log = MutableStateFlow<List<String>>(emptyList())

    val log: StateFlow<List<String>> = _log.asStateFlow()



    private val _peers = MutableStateFlow<List<UiPeer>>(emptyList())

    val peers: StateFlow<List<UiPeer>> = _peers.asStateFlow()



    private val _match = MutableStateFlow<MatchState?>(null)

    val match: StateFlow<MatchState?> = _match.asStateFlow()



    private val _lastRoll = MutableStateFlow<DiceRoll?>(null)

    val lastRoll: StateFlow<DiceRoll?> = _lastRoll.asStateFlow()



    /** Increments on each physical roll so dice animation replays even if [DiceRoll] equals the previous. */
    private val _diceRollGeneration = MutableStateFlow(0)

    val diceRollGeneration: StateFlow<Int> = _diceRollGeneration.asStateFlow()



    /** False while dice roll animation runs; score sheet legal highlights wait until true. */
    private val _diceRollAnimationSettled = MutableStateFlow(true)

    val diceRollAnimationSettled: StateFlow<Boolean> = _diceRollAnimationSettled.asStateFlow()



    private val _role = MutableStateFlow<Role?>(null)

    val role: StateFlow<Role?> = _role.asStateFlow()



    private val _rollResolution = MutableStateFlow<RollResolutionState?>(null)

    val rollResolution: StateFlow<RollResolutionState?> = _rollResolution.asStateFlow()



    private val _legalMoves = MutableStateFlow<List<LegalMove>>(emptyList())

    val legalMoves: StateFlow<List<LegalMove>> = _legalMoves.asStateFlow()



    private val _gameOverReason = MutableStateFlow<String?>(null)

    val gameOverReason: StateFlow<String?> = _gameOverReason.asStateFlow()



    private val _rowLockCelebration = MutableStateFlow<RowLockCelebrationUi?>(null)

    val rowLockCelebration: StateFlow<RowLockCelebrationUi?> = _rowLockCelebration.asStateFlow()



    fun dismissRowLockCelebration() {
        _rowLockCelebration.value = null
    }



    private val _crossesThisRoll = MutableStateFlow(0)

    val crossesThisRoll: StateFlow<Int> = _crossesThisRoll.asStateFlow()



    /** Single-player only: snapshots before each cross this roll; cleared on end turn / new roll. */
    private val singlePlayerUndoStack = ArrayDeque<SinglePlayerUndoEntry>()



    private val _bleScanCandidates = MutableStateFlow<List<BleScanCandidate>>(emptyList())

    val bleScanCandidates: StateFlow<List<BleScanCandidate>> = _bleScanCandidates.asStateFlow()



    enum class Role { Host, Client, SinglePlayer }



    private var host: BluetoothGamingHost? = null

    private var client: BluetoothGamingClient? = null



    private val listener = object : BluetoothGamingListener {

        override fun onBleScanCandidate(address: String, name: String?, rssi: Int) {

            if (_role.value != Role.Client) return

            _bleScanCandidates.update { list ->

                val byAddr = list.associateBy { it.address }.toMutableMap()

                val prev = byAddr[address]

                val mergedName = when {

                    !name.isNullOrBlank() -> name

                    else -> prev?.name

                }

                val mergedRssi = if (prev == null || rssi > prev.rssi) rssi else prev.rssi

                byAddr[address] = BleScanCandidate(address, mergedName, mergedRssi)

                byAddr.values.sortedByDescending { it.rssi }.take(30)

            }

        }

        override fun onPeerConnected(address: String, playerId: Int, displayName: String) {

            if (_role.value == Role.Client) {

                _bleScanCandidates.value = emptyList()

            }

            appendLog("peer connected $displayName id=$playerId")

            _peers.update { it + UiPeer(address, playerId, displayName) }

            if (_role.value == Role.Host) {

                val count = _peers.value.size + 1

                val state = initialMatchState(count)

                _match.value = state

                broadcastGameState(state)

            }

        }



        override fun onPeerDisconnected(address: String) {

            appendLog("peer disconnected $address")

            _peers.update { p -> p.filter { it.address != address } }

        }



        override fun onMessageReceived(address: String, messageType: Byte, payload: ByteArray) {

            if (messageType != WireMessageType.APP_PAYLOAD) return

            viewModelScope.launch {

                runCatching {

                    val root = GameMessageCodec.decodeAppPayload(payload)

                    when (root.getString("kind")) {

                        "game_state" -> _match.value = GameMessageCodec.parseGameState(root)

                        "roll" -> {

                            val (roll, _) = GameMessageCodec.parseRoll(root)

                            _diceRollAnimationSettled.value = false

                            _lastRoll.value = roll

                            _diceRollGeneration.update { it + 1 }

                        }

                        else -> appendLog("app msg ${root.getString("kind")}")

                    }

                }.onFailure { appendLog("parse error ${it.message}") }

            }

        }



        override fun onError(message: String) {

            appendLog("error: $message")

        }

    }



    private fun appendLog(line: String) {

        _log.update { (it + line).takeLast(100) }

    }



    fun startHost(displayName: String) {

        stopAll()

        _role.value = Role.Host

        val h = BluetoothGamingHost(getApplication(), listener)

        host = h

        if (h.start()) {

            appendLog("host started, session nonce ready")

            _match.value = initialMatchState(1)

        }

    }



    fun startSinglePlayer() {

        stopAll()

        _role.value = Role.SinglePlayer

        _match.value = initialMatchState(1)

        _gameOverReason.value = null

        appendLog("single player — roll to start a turn")

    }



    fun startClient(displayName: String) {

        stopAll()

        _role.value = Role.Client

        val c = BluetoothGamingClient(getApplication(), listener)

        c.setDisplayName(displayName)

        client = c

        _bleScanCandidates.value = emptyList()

        if (c.startScan()) {

            appendLog("scanning for LocXX host… (or pick a device below)")

        }

    }



    fun stopAll() {

        host?.stop()

        host = null

        client?.disconnect()

        client = null

        _peers.value = emptyList()

        _role.value = null

        _match.value = null

        _lastRoll.value = null

        _diceRollGeneration.value = 0

        _diceRollAnimationSettled.value = true

        _rollResolution.value = null

        _legalMoves.value = emptyList()

        _gameOverReason.value = null

        _rowLockCelebration.value = null

        _crossesThisRoll.value = 0

        _bleScanCandidates.value = emptyList()

        singlePlayerUndoStack.clear()

    }



    fun connectToBleDevice(address: String) {

        if (_role.value != Role.Client) return

        val c = client ?: return

        val adapter = (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        val dev = try {

            adapter.getRemoteDevice(address)

        } catch (_: IllegalArgumentException) {

            appendLog("invalid Bluetooth address")

            return

        }

        appendLog("connecting to $address…")

        c.connect(dev)

    }



    /**

     * Host: broadcast roll. Single player: local roll and open turn resolution.

     */

    fun rollDice() {

        when (_role.value) {

            Role.Host -> rollDiceHost()

            Role.SinglePlayer -> rollDiceSinglePlayer()

            else -> {}

        }

    }



    private fun rollDiceHost() {

        val h = host ?: return

        val state = _match.value ?: return

        val rnd = Random.Default

        val roll = buildDiceRoll(state, rnd)

        _diceRollAnimationSettled.value = false

        _lastRoll.value = roll

        _diceRollGeneration.update { it + 1 }

        val payload = GameMessageCodec.encodeRoll(roll, state.activePlayerIndex, cid = rnd.nextInt())

        val frame = ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload)

        h.broadcast(frame)

        appendLog("rolled white=${roll.whiteSum()} (host)")

    }



    private fun rollDiceSinglePlayer() {

        if (_gameOverReason.value != null) {

            appendLog("game over — restart single player")

            return

        }

        if (_rollResolution.value != null) {

            appendLog("end turn before rolling again")

            return

        }

        val state = _match.value ?: return

        val rnd = Random.Default

        val roll = buildDiceRoll(state, rnd)

        applySinglePlayerRoll(roll)

        appendLog("rolled white=${roll.whiteSum()}")

    }



    /**
     * Debug double-tap on the animated dice strip (single-player): first white die plays the
     * row-lock celebration only; colored dice apply a rigged roll for that row’s lock cell.
     */
    fun onSinglePlayerDieDoubleTap(slot: LocXXDieSlot) {
        when (slot) {
            LocXXDieSlot.WHITE1 -> playFirstWhiteDieLockCelebration()
            LocXXDieSlot.WHITE2 -> Unit
            else -> rollDiceSinglePlayerDebugLock(slot)
        }
    }



    /**
     * Debug: double-tap a **colored** die on the strip to roll with values fixed so the lock cell
     * for that row is achievable. White dice are ignored (no rigged roll).
     */
    fun rollDiceSinglePlayerDebugLock(slot: LocXXDieSlot) {

        if (_role.value != Role.SinglePlayer) return

        if (_gameOverReason.value != null) {

            appendLog("game over — restart single player")

            return

        }

        if (_rollResolution.value != null) {

            appendLog("end turn before rolling again")

            return

        }

        val state = _match.value ?: return

        val row = when (slot) {
            LocXXDieSlot.WHITE1, LocXXDieSlot.WHITE2 -> return
            LocXXDieSlot.RED -> RowId.RED
            LocXXDieSlot.YELLOW -> RowId.YELLOW
            LocXXDieSlot.GREEN -> RowId.GREEN
            LocXXDieSlot.BLUE -> RowId.BLUE
        }

        val roll = buildDebugLockDiceRoll(state, row)

        applySinglePlayerRoll(roll)

        appendLog("debug: rigged roll for ${row.name} lock (${rowValues(row).last()})")

    }



    /** Confetti / sound / overlay for the red row — no dice change (debug: double-tap first white die). */
    private fun playFirstWhiteDieLockCelebration() {
        if (_role.value != Role.SinglePlayer) return
        val m = _match.value ?: return
        val idx = m.activePlayerIndex
        val sheet = m.playerSheets[idx]
        val row = RowId.RED
        val pts = LocXXRules.rowPoints(sheet, row)
        viewModelScope.launch {
            _rowLockCelebration.value = null
            delay(1)
            _rowLockCelebration.value = RowLockCelebrationUi(row, pts)
        }
    }



    private fun applySinglePlayerRoll(roll: DiceRoll) {

        _diceRollAnimationSettled.value = false

        _lastRoll.value = roll

        _diceRollGeneration.update { it + 1 }

        _rollResolution.value = RollResolutionState(roll)

        _crossesThisRoll.value = 0

        singlePlayerUndoStack.clear()

        refreshLegalMoves()

    }



    private fun buildDiceRoll(state: MatchState, rnd: Random): DiceRoll {

        fun d() = rnd.nextInt(1, 7)

        return DiceRoll(

            white1 = d(),

            white2 = d(),

            red = if (state.diceInPlay.contains(DieColor.RED)) d() else 0,

            yellow = if (state.diceInPlay.contains(DieColor.YELLOW)) d() else 0,

            green = if (state.diceInPlay.contains(DieColor.GREEN)) d() else 0,

            blue = if (state.diceInPlay.contains(DieColor.BLUE)) d() else 0

        )

    }



    /** Whites 6+6 and color dice 6 when in play — lock value 12 on red/yellow. Whites 1+1 and color 1 — lock value 2 on green/blue. */
    private fun buildDebugLockDiceRoll(state: MatchState, row: RowId): DiceRoll {

        fun dColor(dc: DieColor, value: Int) = if (state.diceInPlay.contains(dc)) value else 0

        return when (row) {

            RowId.RED, RowId.YELLOW -> DiceRoll(

                white1 = 6,

                white2 = 6,

                red = dColor(DieColor.RED, 6),

                yellow = dColor(DieColor.YELLOW, 6),

                green = dColor(DieColor.GREEN, 6),

                blue = dColor(DieColor.BLUE, 6)

            )

            RowId.GREEN, RowId.BLUE -> DiceRoll(

                white1 = 1,

                white2 = 1,

                red = dColor(DieColor.RED, 4),

                yellow = dColor(DieColor.YELLOW, 4),

                green = dColor(DieColor.GREEN, if (row == RowId.GREEN) 1 else 4),

                blue = dColor(DieColor.BLUE, if (row == RowId.BLUE) 1 else 4)

            )

        }

    }



    private fun refreshLegalMoves() {

        val res = _rollResolution.value

        val state = _match.value

        if (res == null || state == null) {

            _legalMoves.value = emptyList()

            return

        }

        val sheet = state.playerSheets[state.activePlayerIndex]

        _legalMoves.value = LocXXRollResolution.legalMoves(

            isActivePlayer = true,

            roll = res.roll,

            sheet = sheet,

            diceInPlay = state.diceInPlay,

            resolution = res

        )

    }



    fun applyLegalMove(move: LegalMove) {

        if (_role.value != Role.SinglePlayer) return

        val state = _match.value ?: return

        val res = _rollResolution.value ?: return

        val idx = state.activePlayerIndex

        val snapMatch = state.snapshot()

        val snapRes = res.copy()

        val snapCrosses = _crossesThisRoll.value

        val newState = LocXXRules.applyCrossToMatch(state, idx, move.row, move.value).getOrElse {

            appendLog("illegal move: ${it.message}")

            return

        }

        val newRes = LocXXRollResolution.afterMove(res, move)

        singlePlayerUndoStack.addLast(SinglePlayerUndoEntry(snapMatch, snapRes, snapCrosses, move))

        _match.value = newState

        _rollResolution.value = newRes

        _crossesThisRoll.update { it + 1 }

        val addedLocks = newState.globallyLockedRows - state.globallyLockedRows
        if (addedLocks.isNotEmpty()) {
            val r = addedLocks.first()
            val sheet = newState.playerSheets[idx]
            val pts = LocXXRules.rowPoints(sheet, r)
            _rowLockCelebration.value = RowLockCelebrationUi(r, pts)
        }

        refreshLegalMoves()

        // Game over (e.g. second lock) is checked only when the turn ends, not when locking mid-turn.

    }



    /** True if tapping this cell will undo the last cross (same row/value as that move). */
    fun canUndoCell(row: RowId, value: Int): Boolean {

        if (_role.value != Role.SinglePlayer) return false

        val e = singlePlayerUndoStack.lastOrNull() ?: return false

        return e.move.row == row && e.move.value == value

    }



    /** Undo the last cross this roll by tapping the same score cell again. */
    fun tryUndoCell(row: RowId, value: Int): Boolean {

        if (_role.value != Role.SinglePlayer) return false

        val e = singlePlayerUndoStack.lastOrNull() ?: return false

        if (e.move.row != row || e.move.value != value) return false

        singlePlayerUndoStack.removeLast()

        _rowLockCelebration.value = null

        _match.value = e.match

        _rollResolution.value = e.resolution

        _crossesThisRoll.value = e.crosses

        refreshLegalMoves()

        checkGameOver(e.match)

        return true

    }



    /** Call when [AnimatedLocXXDiceStrip] finishes tumbling and spinning. */
    fun notifyDiceRollAnimationFinished() {

        _diceRollAnimationSettled.value = true

    }



    /**
     * Take a penalty and end the turn. Always allowed while a roll is open.
     * Any crosses made this roll are undone first (Qwixx-style voluntary penalty).
     */
    fun singlePlayerPenalty() {

        if (_role.value != Role.SinglePlayer) return

        if (_rollResolution.value == null) return

        val bottom = singlePlayerUndoStack.firstOrNull()
        if (bottom != null) {
            _match.value = bottom.match
            _rollResolution.value = bottom.resolution
        }
        singlePlayerUndoStack.clear()

        applyPenaltyAndEndTurn()

    }



    /**

     * Finish the current roll. If you made no crosses, you take one penalty (Qwixx-style).

     */

    fun endSinglePlayerTurn() {

        if (_role.value != Role.SinglePlayer) return

        if (_rollResolution.value == null) return

        if (_crossesThisRoll.value == 0) {

            applyPenaltyAndEndTurn()

            return

        }

        val state = _match.value ?: return

        _rollResolution.value = null

        _crossesThisRoll.value = 0

        _legalMoves.value = emptyList()

        singlePlayerUndoStack.clear()

        checkGameOver(state)

    }



    private fun applyPenaltyAndEndTurn() {

        val state = _match.value ?: return

        val idx = state.activePlayerIndex

        val sheet = state.playerSheets[idx]

        val newSheet = sheet.copy(penalties = sheet.penalties + 1)

        val newState = state.copySheet(idx, newSheet)

        _match.value = newState

        _rollResolution.value = null

        _crossesThisRoll.value = 0

        _legalMoves.value = emptyList()

        singlePlayerUndoStack.clear()

        appendLog("penalty (${newSheet.penalties})")

        checkGameOver(newState)

    }



    /** Single-player: called when a turn ends (End turn or penalty), not after each cross. */
    private fun checkGameOver(state: MatchState) {

        val sheet = state.playerSheets[state.activePlayerIndex]

        if (LocXXRules.gameShouldEnd(listOf(sheet.penalties), state.lockedRowCount())) {

            val score = LocXXRules.totalScore(sheet)

            _gameOverReason.value = "Game over — score $score"

            appendLog(_gameOverReason.value!!)

        }

    }



    fun singlePlayerScore(): Int {

        val state = _match.value ?: return 0

        return LocXXRules.totalScore(state.playerSheets[state.activePlayerIndex])

    }



    private fun broadcastGameState(state: MatchState) {

        val h = host ?: return

        val payload = GameMessageCodec.encodeGameState(state)

        val frame = ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload)

        h.broadcast(frame)

    }

}

