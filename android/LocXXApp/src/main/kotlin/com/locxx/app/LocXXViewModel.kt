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

import com.locxx.rules.initialMatchState

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.update

import kotlinx.coroutines.launch

import kotlin.random.Random



data class UiPeer(val address: String, val playerId: Int, val displayName: String)

data class BleScanCandidate(val address: String, val name: String?, val rssi: Int)

class LocXXViewModel(application: Application) : AndroidViewModel(application) {



    private val _log = MutableStateFlow<List<String>>(emptyList())

    val log: StateFlow<List<String>> = _log.asStateFlow()



    private val _peers = MutableStateFlow<List<UiPeer>>(emptyList())

    val peers: StateFlow<List<UiPeer>> = _peers.asStateFlow()



    private val _match = MutableStateFlow<MatchState?>(null)

    val match: StateFlow<MatchState?> = _match.asStateFlow()



    private val _lastRoll = MutableStateFlow<DiceRoll?>(null)

    val lastRoll: StateFlow<DiceRoll?> = _lastRoll.asStateFlow()



    private val _role = MutableStateFlow<Role?>(null)

    val role: StateFlow<Role?> = _role.asStateFlow()



    private val _rollResolution = MutableStateFlow<RollResolutionState?>(null)

    val rollResolution: StateFlow<RollResolutionState?> = _rollResolution.asStateFlow()



    private val _legalMoves = MutableStateFlow<List<LegalMove>>(emptyList())

    val legalMoves: StateFlow<List<LegalMove>> = _legalMoves.asStateFlow()



    private val _gameOverReason = MutableStateFlow<String?>(null)

    val gameOverReason: StateFlow<String?> = _gameOverReason.asStateFlow()



    private val _crossesThisRoll = MutableStateFlow(0)

    val crossesThisRoll: StateFlow<Int> = _crossesThisRoll.asStateFlow()



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

                            _lastRoll.value = roll

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

        _rollResolution.value = null

        _legalMoves.value = emptyList()

        _gameOverReason.value = null

        _crossesThisRoll.value = 0

        _bleScanCandidates.value = emptyList()

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

        _lastRoll.value = roll

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

        _lastRoll.value = roll

        _rollResolution.value = RollResolutionState(roll)

        _crossesThisRoll.value = 0

        refreshLegalMoves()

        appendLog("rolled white=${roll.whiteSum()}")

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



    private fun refreshLegalMoves() {

        val res = _rollResolution.value

        val state = _match.value

        if (res == null || state == null) {

            _legalMoves.value = emptyList()

            return

        }

        val sheet = state.playerSheets[state.activePlayerIndex]

        _legalMoves.value = LocXXRollResolution.legalActiveMoves(

            res.roll,

            sheet,

            state.diceInPlay,

            res.pathChoice,

            res.whiteUsedForColor

        )

    }



    fun applyLegalMove(move: LegalMove) {

        if (_role.value != Role.SinglePlayer) return

        val state = _match.value ?: return

        val res = _rollResolution.value ?: return

        val idx = state.activePlayerIndex

        val newState = LocXXRules.applyCrossToMatch(state, idx, move.row, move.value).getOrElse {

            appendLog("illegal move: ${it.message}")

            return

        }

        val newRes = LocXXRollResolution.afterMove(res, move)

        _match.value = newState

        _rollResolution.value = newRes

        _crossesThisRoll.update { it + 1 }

        refreshLegalMoves()

        checkGameOver(newState)

    }



    /** When no legal crosses remain, take a penalty and end the turn. */

    fun singlePlayerPenalty() {

        if (_role.value != Role.SinglePlayer) return

        if (_rollResolution.value == null) return

        if (_crossesThisRoll.value > 0) return

        if (_legalMoves.value.isNotEmpty()) return

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

        appendLog("penalty (${newSheet.penalties})")

        checkGameOver(newState)

    }



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


