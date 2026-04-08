package com.locxx.app



import android.app.Application

import android.os.Handler

import android.os.Looper

import androidx.lifecycle.AndroidViewModel

import androidx.lifecycle.viewModelScope

import com.locxx.langaming.LanGamingClient

import com.locxx.langaming.LanGamingHost

import com.locxx.langaming.LanGamingListener

import com.locxx.langaming.LOCXX_LAN_PORT

import com.locxx.langaming.LocxxMdnsAdvertiser

import com.locxx.langaming.LocxxMdnsBrowser

import com.locxx.langaming.ProtocolCodec

import com.locxx.langaming.WireMessageType

import java.net.Inet4Address

import java.net.NetworkInterface

import com.locxx.rules.DiceRoll

import com.locxx.rules.DieColor

import com.locxx.rules.LegalMove

import com.locxx.rules.LocXXRollResolution

import com.locxx.rules.LocXXRules

import com.locxx.rules.MatchState

import com.locxx.rules.PlayerSheet

import com.locxx.rules.RollResolutionState

import com.locxx.rules.RowId

import com.locxx.rules.initialMatchState

import com.locxx.rules.rowValues

import com.locxx.rules.withDerivedGlobalLocksAndDice

import kotlin.collections.ArrayDeque

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.SharingStarted

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.combine

import kotlinx.coroutines.flow.stateIn

import kotlinx.coroutines.flow.update

import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.delay

import kotlinx.coroutines.isActive

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import kotlin.random.Random

import java.net.HttpURLConnection

import java.net.URL

import org.json.JSONArray
import org.json.JSONObject



data class UiPeer(val address: String, val playerId: Int, val displayName: String)



/** Shown when a joiner has found a host via mDNS (before TCP hello). */
data class JoinHostPrompt(val baseUrl: String, val hostDisplayName: String)

private data class SinglePlayerUndoEntry(
    val match: MatchState,
    val resolution: RollResolutionState,
    val crosses: Int,
    val move: LegalMove
)

/** LAN: one score sheet cross to undo (revert this seat only; global dice/locks recomputed). */
private data class LanUndoEntry(
    val sheet: PlayerSheet,
    val resolution: RollResolutionState,
    val crossesThisRoll: Int,
    val move: LegalMove
)

private fun sheetDeepCopy(sheet: PlayerSheet): PlayerSheet =
    sheet.copy(rows = sheet.rows.mapValues { (_, st) -> st.copy() })

/** Rows where [sheet] has five marks and may take the lock bonus (same as score-sheet pulse). */
private fun lockReadyRowsForSheet(sheet: PlayerSheet): Set<RowId> =
    RowId.entries.filter { r ->
        val st = sheet.rows[r] ?: return@filter false
        val lockValue = rowValues(r).last()
        LocXXRules.isLockCellReadyToMark(r, st, lockValue)
    }.toSet()

/** True if [wire] does not drop any of this seat's row locks or crossed cells vs [prev] (peer undos must not revert us). */
private fun wireLanSheetCoversPrev(prev: PlayerSheet, wire: PlayerSheet): Boolean {
    for (row in RowId.entries) {
        val p = prev.rows[row] ?: return false
        val w = wire.rows[row] ?: return false
        if (p.locked && !w.locked) return false
        if (!w.crossedIndices.containsAll(p.crossedIndices)) return false
    }
    return true
}

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



    /** Host: one-shot message when a client connects (shown in UI). */
    private val _peerJoinedPopup = MutableStateFlow<String?>(null)

    val peerJoinedPopup: StateFlow<String?> = _peerJoinedPopup.asStateFlow()



    fun dismissPeerJoinedPopup() {

        _peerJoinedPopup.value = null

    }



    private val _joinHostPrompt = MutableStateFlow<JoinHostPrompt?>(null)

    val joinHostPrompt: StateFlow<JoinHostPrompt?> = _joinHostPrompt.asStateFlow()



    private val _joinAwaitingHostStart = MutableStateFlow(false)

    val joinAwaitingHostStart: StateFlow<Boolean> = _joinAwaitingHostStart.asStateFlow()



    private val _joinSessionHostDisplayName = MutableStateFlow("")

    val joinSessionHostDisplayName: StateFlow<String> = _joinSessionHostDisplayName.asStateFlow()



    private var joinDiscoveryJob: Job? = null



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



    private val _localPlayerIndex = MutableStateFlow(0)

    /** 0-based index into [MatchState.playerSheets] for this device. */
    val localPlayerIndex: StateFlow<Int> = _localPlayerIndex.asStateFlow()



    private val _resolutionByPlayer = MutableStateFlow<List<RollResolutionState>?>(null)

    val rollResolution: StateFlow<RollResolutionState?> = combine(
        _resolutionByPlayer,
        _localPlayerIndex
    ) { list, idx ->
        list?.getOrNull(idx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)



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



    /** LAN: seat indices that pressed Done for the current open roll (rollers + others); next roll when all are in this set. */
    private val _whitePhaseAcks = MutableStateFlow<Set<Int>>(emptySet())

    val whitePhaseAcks: StateFlow<Set<Int>> = _whitePhaseAcks.asStateFlow()



    /**
     * Client: per-seat row colors where that player may mark the lock cell, from host [game_state]
     * (orthogonal to masked sheets during an open roll).
     */
    private val _lanLockReadyBySeat = MutableStateFlow<List<Set<RowId>>?>(null)

    val lanLockReadyBySeat: StateFlow<List<Set<RowId>>?> = _lanLockReadyBySeat.asStateFlow()



    /** Host: client-reported lock-ready rows for seats still private until Done (merged into broadcasts). */
    private val remoteLockReadyHints = mutableMapOf<Int, Set<RowId>>()



    /** Client: host broadcast after “Start game” so this device opens the score sheet. */
    private val _lanSessionPlayStarted = MutableStateFlow(false)

    val lanSessionPlayStarted: StateFlow<Boolean> = _lanSessionPlayStarted.asStateFlow()



    /** Host: play was started so late-joining clients get [game_started] after connect. */
    private val _hostHasStartedLanPlay = MutableStateFlow(false)



    /** Client: host stopped hosting; show then call [dismissHostEndedMessage]. */
    private val _hostEndedSessionMessage = MutableStateFlow<String?>(null)

    val hostEndedSessionMessage: StateFlow<String?> = _hostEndedSessionMessage.asStateFlow()



    fun dismissHostEndedMessage() {

        _hostEndedSessionMessage.value = null

    }



    private val _sessionDisplayName = MutableStateFlow("")



    private val _lanPlayerDisplayNames = MutableStateFlow<List<String>>(emptyList())



    val lanPlayerDisplayNames: StateFlow<List<String>> = _lanPlayerDisplayNames.asStateFlow()



    /** Seat index → display name for score sheet endgame UI (host uses live peers; clients use host broadcast). */
    fun seatDisplayName(seat: Int): String {

        val state = _match.value

        val r = _role.value

        if (state == null || seat !in 0 until state.playerCount) {

            return "Player ${seat + 1}"

        }

        val wired = _lanPlayerDisplayNames.value

        if (wired.size == state.playerCount) {

            return wired[seat].ifBlank { "Player ${seat + 1}" }

        }

        if (r == Role.Host) {

            if (seat == 0) return _sessionDisplayName.value.ifBlank { "Player 1" }

            return _peers.value.find { it.playerId == seat }?.displayName?.ifBlank { null }

                ?: "Player ${seat + 1}"

        }

        if (r == Role.Client && seat == _localPlayerIndex.value) {

            return _sessionDisplayName.value.ifBlank { "Player ${seat + 1}" }

        }

        return _peers.value.find { it.playerId == seat }?.displayName?.ifBlank { null }

            ?: "Player ${seat + 1}"

    }



    /** Single-player only: snapshots before each cross this roll; cleared on end turn / new roll. */
    private val singlePlayerUndoStack = ArrayDeque<SinglePlayerUndoEntry>()

    /** Single-player: tap penalty again before the next roll to undo a voluntary penalty. */
    private data class SinglePlayerVoluntaryPenaltyUndo(
        val match: MatchState,
        val resolution: RollResolutionState,
        val crossesThisRoll: Int,
        val undoStackEntries: List<SinglePlayerUndoEntry>
    )

    private var singlePlayerVoluntaryPenaltyUndo: SinglePlayerVoluntaryPenaltyUndo? = null

    /** LAN host: full state before voluntary penalty tap (marks/crosses/undo stack) for restore. */
    private data class LanVoluntaryPenaltyUndo(
        val seat: Int,
        val matchWithMarks: MatchState,
        val resolutions: List<RollResolutionState>,
        val crosses: Int,
        val rollerLanUndoStack: List<LanUndoEntry>
    )

    private var lanVoluntaryPenaltyUndo: LanVoluntaryPenaltyUndo? = null

    /** LAN client: roller voluntary penalty — full restore snapshot (local until Done). */
    private var clientLanVoluntaryPenaltyUndo: LanVoluntaryPenaltyUndo? = null

    /** Host: per-seat stack of crosses this open roll (undo before Done). */
    private val lanUndoStacks: MutableMap<Int, ArrayDeque<LanUndoEntry>> = mutableMapOf()

    /** Client: marks / state for this roll until Done (all local). */
    private val clientLanUndoStack = ArrayDeque<LanUndoEntry>()



    /** Host-only: full match at open roll, to undo active player’s crosses on voluntary penalty. */
    private var multiplayerRollStartSnapshot: MatchState? = null



    enum class Role { Host, Client, SinglePlayer, JoinSearching }



    private var host: LanGamingHost? = null

    private var client: LanGamingClient? = null

    private var mdnsAdvertiser: LocxxMdnsAdvertiser? = null

    private var mdnsBrowser: LocxxMdnsBrowser? = null



    private val listener = object : LanGamingListener {

        override fun onPeerConnected(address: String, playerId: Int, displayName: String) {

            appendLog("peer connected $displayName id=$playerId")

            if (_role.value == Role.Client) {

                // Host is always sheet index 0. LAN assigns joining clients playerId 1,2,… matching that seat.

                _localPlayerIndex.value = playerId.coerceAtLeast(0)

            }

            _peers.update { it + UiPeer(address, playerId, displayName) }

            if (_role.value == Role.Host) {

                val label = displayName.trim().ifBlank { "Player ${playerId + 1}" }

                _peerJoinedPopup.value = "$label has joined your game!"

                val count = _peers.value.size + 1

                val state = initialMatchState(count)

                _match.value = state

                broadcastGameState(state)

                if (_hostHasStartedLanPlay.value) {

                    val h = host ?: return

                    h.broadcast(

                        ProtocolCodec.encodeFrame(

                            WireMessageType.APP_PAYLOAD,

                            GameMessageCodec.encodeGameStarted()

                        )

                    )

                }

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

                    val kind = root.getString("kind")

                    if (_role.value == Role.Host && kind.startsWith("intent_")) {

                        handleHostRemoteIntent(address, root)

                        return@runCatching

                    }

                    when (kind) {

                        "game_started" -> {

                            if (_role.value == Role.Client) {

                                _lanSessionPlayStarted.value = true

                            }

                        }

                        "host_exited" -> {

                            if (_role.value == Role.Client) {

                                val hostLabel = _joinSessionHostDisplayName.value.trim().ifBlank {

                                    _lanPlayerDisplayNames.value.getOrNull(0)?.trim().orEmpty()

                                }.ifBlank { "The host" }

                                _joinAwaitingHostStart.value = false

                                _joinSessionHostDisplayName.value = ""

                                _hostEndedSessionMessage.value =
                                    "$hostLabel is no longer hosting."

                                stopAll(preserveHostEndedMessage = true)

                            }

                        }

                        "game_state" -> applyIncomingGameState(root)

                        "roll" -> applyIncomingRoll(root)

                        else -> appendLog("app msg $kind")

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

        _sessionDisplayName.value = displayName

        _role.value = Role.Host

        _localPlayerIndex.value = 0

        val h = LanGamingHost(listener)

        h.advertisedHostDisplayName = displayName.trim()

        host = h

        if (h.beginHosting()) {

            val adv = LocxxMdnsAdvertiser(
                getApplication(),
                LOCXX_LAN_PORT,
                displayName.trim(),
            ) { msg ->

                appendLog("error: $msg")

            }

            adv.start()

            mdnsAdvertiser = adv

            appendLog("host started — players on same Wi‑Fi can join automatically")

            _match.value = initialMatchState(1)

        }

    }



    fun startSinglePlayer(displayName: String? = null) {

        joinDiscoveryJob?.cancel()

        joinDiscoveryJob = null

        val resolvedName = when {

            !displayName.isNullOrBlank() -> displayName

            _sessionDisplayName.value.isNotBlank() -> _sessionDisplayName.value

            else -> null

        }

        tearDownNetworkingAndLanGameState(preserveHostEndedMessage = false)

        _sessionDisplayName.value = resolvedName ?: "Player"

        _role.value = Role.SinglePlayer

        _match.value = initialMatchState(1)

        _gameOverReason.value = null

        appendLog("single player — roll to start a turn")

    }



    fun startClient(displayName: String) {

        if (_role.value == Role.Host) {

            appendLog("cannot join while hosting — use Leave session first")

            return

        }

        stopAll()

        _sessionDisplayName.value = displayName

        _role.value = Role.Client

        val c = LanGamingClient(listener)

        c.displayName = displayName

        client = c

        appendLog("looking for host on Wi‑Fi…")

        val browser = LocxxMdnsBrowser(

            getApplication(),

            Handler(Looper.getMainLooper()),

            shouldAcceptResolved = { _, _ -> true },

            onResolved = { hostForUrl, port, _ ->

                if (_role.value != Role.Client) return@LocxxMdnsBrowser

                val cc = client ?: return@LocxxMdnsBrowser

                val url = "http://$hostForUrl:$port"

                appendLog("found host at $url")

                cc.connect(url.trimEnd('/'))

                mdnsBrowser?.stop()

                mdnsBrowser = null

            },

            onError = { msg -> appendLog("error: $msg") }

        )

        browser.start()

        mdnsBrowser = browser

    }



    /**
     * Host on LAN and browse simultaneously: join a discovered game if its address sorts after ours
     * (deterministic tie-break); otherwise stay host and wait for the other device to connect.
     */
    fun startAutoLanMatchmaking(displayName: String) {

        stopAll()

        _sessionDisplayName.value = displayName

        _role.value = Role.Host

        _localPlayerIndex.value = 0

        val h = LanGamingHost(listener)

        h.advertisedHostDisplayName = displayName.trim()

        host = h

        if (!h.beginHosting()) {

            appendLog("could not start LAN host")

            _role.value = null

            return

        }

        val adv = LocxxMdnsAdvertiser(
            getApplication(),
            LOCXX_LAN_PORT,
            displayName.trim(),
        ) { msg ->

            appendLog("error: $msg")

        }

        adv.start()

        mdnsAdvertiser = adv

        _match.value = initialMatchState(1)

        appendLog("hosting — also looking for other LocXX games on Wi‑Fi…")

        val localIpv4 = locxxCollectLanIpv4HostStrings()

        val browser = LocxxMdnsBrowser(

            getApplication(),

            Handler(Looper.getMainLooper()),

            shouldAcceptResolved = { hostStr, port ->

                locxxShouldJoinRemoteLocxxHost(hostStr, port, localIpv4)

            },

            onResolved = { hostForUrl, port, _ ->

                switchAutoHostToClient(hostForUrl, port, displayName)

            },

            onError = { msg -> appendLog("error: $msg") }

        )

        browser.start()

        mdnsBrowser = browser

    }



    private fun switchAutoHostToClient(remoteHost: String, remotePort: Int, displayName: String) {

        if (_role.value != Role.Host) return

        appendLog("joining another host at $remoteHost:$remotePort")

        if (_peers.value.isNotEmpty()) {

            runCatching {

                val h = host ?: return@runCatching

                h.broadcast(

                    ProtocolCodec.encodeFrame(

                        WireMessageType.APP_PAYLOAD,

                        GameMessageCodec.encodeHostExited()

                    )

                )

            }

        }

        mdnsBrowser?.stop()

        mdnsBrowser = null

        mdnsAdvertiser?.stop()

        mdnsAdvertiser = null

        host?.endHosting()

        host = null

        client?.disconnect()

        client = null

        _peers.value = emptyList()

        _match.value = null

        _lastRoll.value = null

        _diceRollGeneration.value = 0

        _diceRollAnimationSettled.value = true

        _resolutionByPlayer.value = null

        multiplayerRollStartSnapshot = null

        _legalMoves.value = emptyList()

        _gameOverReason.value = null

        _rowLockCelebration.value = null

        _crossesThisRoll.value = 0

        _whitePhaseAcks.value = emptySet()

        _lanSessionPlayStarted.value = false

        _hostHasStartedLanPlay.value = false

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = null

        lanVoluntaryPenaltyUndo = null

        clientLanVoluntaryPenaltyUndo = null

        clearLanUndoStacks()

        _localPlayerIndex.value = 0

        _lanPlayerDisplayNames.value = emptyList()

        _role.value = Role.Client

        val c = LanGamingClient(listener)

        c.displayName = displayName

        client = c

        val base = remoteHost.trimEnd('/')

        val url = "http://$base:$remotePort".trimEnd('/')

        c.connect(url)

    }



    /**
     * Stops mDNS, hosting, client, and clears multiplayer match state.
     * Does not change [Role] or display name — use when switching to single-player so UI does not
     * see a null role and restart auto LAN matchmaking.
     */
    private fun tearDownNetworkingAndLanGameState(preserveHostEndedMessage: Boolean = false) {

        if (_role.value == Role.Host) {

            runCatching {

                val h = host ?: return@runCatching

                h.broadcast(

                    ProtocolCodec.encodeFrame(

                        WireMessageType.APP_PAYLOAD,

                        GameMessageCodec.encodeHostExited()

                    )

                )

            }

        }

        mdnsAdvertiser?.stop()

        mdnsAdvertiser = null

        mdnsBrowser?.stop()

        mdnsBrowser = null

        host?.endHosting()

        host = null

        client?.disconnect()

        client = null

        _peers.value = emptyList()

        _match.value = null

        _lastRoll.value = null

        _diceRollGeneration.value = 0

        _diceRollAnimationSettled.value = true

        _resolutionByPlayer.value = null

        multiplayerRollStartSnapshot = null

        _legalMoves.value = emptyList()

        _gameOverReason.value = null

        _rowLockCelebration.value = null

        _crossesThisRoll.value = 0

        _whitePhaseAcks.value = emptySet()

        _lanSessionPlayStarted.value = false

        _hostHasStartedLanPlay.value = false

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = null

        lanVoluntaryPenaltyUndo = null

        clientLanVoluntaryPenaltyUndo = null

        clearLanUndoStacks()

        remoteLockReadyHints.clear()

        _lanLockReadyBySeat.value = null

        _localPlayerIndex.value = 0

        _lanPlayerDisplayNames.value = emptyList()

        _peerJoinedPopup.value = null

        if (!preserveHostEndedMessage) {

            _hostEndedSessionMessage.value = null

        }

    }



    fun stopAll(preserveHostEndedMessage: Boolean = false) {

        joinDiscoveryJob?.cancel()

        joinDiscoveryJob = null

        _joinHostPrompt.value = null

        _joinAwaitingHostStart.value = false

        _joinSessionHostDisplayName.value = ""

        tearDownNetworkingAndLanGameState(preserveHostEndedMessage)

        _role.value = null

        _sessionDisplayName.value = ""

    }



    /** Host: stop advertising and notify joined clients (landing page). */
    fun stopHostingLobby() {

        if (_role.value != Role.Host) return

        val nameKeep = _sessionDisplayName.value

        tearDownNetworkingAndLanGameState(preserveHostEndedMessage = false)

        _role.value = null

        _sessionDisplayName.value = nameKeep

    }



    /** Join flow: discover hosts until one is found, then expose [joinHostPrompt]. */
    fun startJoinHostDiscovery(displayName: String) {

        joinDiscoveryJob?.cancel()

        if (_role.value == Role.Host) {

            appendLog("cannot join while hosting")

            return

        }

        stopAll()

        val trimmed = displayName.trim()

        _sessionDisplayName.value = trimmed

        _role.value = Role.JoinSearching

        joinDiscoveryJob = viewModelScope.launch {

            while (isActive && _role.value == Role.JoinSearching && _joinHostPrompt.value == null) {

                val found = discoverOneHostAddress(28_000L)

                if (!isActive || _role.value != Role.JoinSearching) break

                if (found == null) {

                    delay(1_600)

                    continue

                }

                val url =
                    "http://${found.host}:${found.port}".trimEnd('/')

                val hostLabel = resolveJoinHostDisplayName(url, found.txtDisplayName)

                if (!isActive || _role.value != Role.JoinSearching) break

                _joinHostPrompt.value = JoinHostPrompt(url, hostLabel)

            }

        }

    }



    /** Cancel join browsing or dismiss the “join this host?” offer without connecting. */
    fun cancelJoinHostFlow() {

        joinDiscoveryJob?.cancel()

        joinDiscoveryJob = null

        _joinHostPrompt.value = null

        if (_role.value == Role.JoinSearching) {

            _role.value = null

        }

    }



    fun acceptJoinHostOffer(prompt: JoinHostPrompt) {

        joinDiscoveryJob?.cancel()

        joinDiscoveryJob = null

        _joinHostPrompt.value = null

        val label = prompt.hostDisplayName.trim().ifBlank { "Host" }

        _joinSessionHostDisplayName.value = label

        _joinAwaitingHostStart.value = true

        connectClientToHostUrl(_sessionDisplayName.value, prompt.baseUrl)

    }



    fun cancelClientWaitForHost() {

        _joinAwaitingHostStart.value = false

        _joinSessionHostDisplayName.value = ""

        if (_role.value != Role.Client) return

        val nameKeep = _sessionDisplayName.value

        client?.disconnect()

        tearDownNetworkingAndLanGameState(preserveHostEndedMessage = false)

        _role.value = null

        _sessionDisplayName.value = nameKeep

    }



    fun clearJoinAwaitingHostStart() {

        _joinAwaitingHostStart.value = false

        _joinSessionHostDisplayName.value = ""

    }



    private data class LocxxDiscoveredLanHost(
        val host: String,
        val port: Int,
        val txtDisplayName: String?,
    )



    private suspend fun discoverOneHostAddress(timeoutMs: Long): LocxxDiscoveredLanHost? {

        val deferred = CompletableDeferred<LocxxDiscoveredLanHost?>()

        var browser: LocxxMdnsBrowser? = null

        browser = LocxxMdnsBrowser(

            getApplication(),

            Handler(Looper.getMainLooper()),

            shouldAcceptResolved = { _, _ -> true },

            onResolved = { hostStr, port, txt ->

                if (deferred.complete(LocxxDiscoveredLanHost(hostStr, port, txt))) {

                    browser?.stop()

                }

            },

            onError = { msg -> appendLog("join browse: $msg") }

        )

        browser.start()

        val timeoutJob = viewModelScope.launch {

            delay(timeoutMs)

            if (deferred.complete(null)) {

                browser?.stop()

            }

        }

        val out = deferred.await()

        timeoutJob.cancel()

        browser?.stop()

        return out

    }



    /** Prefer mDNS TXT `dn` (API 34+); otherwise fetch [session_info] with short retries (server may not be ready immediately). */
    private suspend fun resolveJoinHostDisplayName(
        baseUrl: String,
        mdnsTxtDisplayName: String?,
    ): String {

        mdnsTxtDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        repeat(8) { attempt ->

            val n = runCatching { fetchHostSessionInfo(baseUrl) }.getOrElse { "Host" }.trim()

            if (n.isNotEmpty() && n != "Host") return n

            if (attempt < 7) delay(300L)

        }

        return runCatching { fetchHostSessionInfo(baseUrl) }.getOrElse { "Host" }.trim()
            .ifBlank { "Host" }

    }



    private suspend fun fetchHostSessionInfo(baseUrl: String): String =

        withContext(Dispatchers.IO) {

            val root = baseUrl.trim().trimEnd('/')

            val conn = (URL("$root/locxx/v1/session_info").openConnection() as HttpURLConnection).apply {

                requestMethod = "GET"

                connectTimeout = 6_000

                readTimeout = 6_000

            }

            try {

                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext "Host"

                val text = conn.inputStream.bufferedReader().use { it.readText() }

                JSONObject(text).optString("hostDisplayName").trim().ifBlank { "Host" }

            } catch (_: Exception) {

                "Host"

            } finally {

                conn.disconnect()

            }

        }



    private fun connectClientToHostUrl(displayName: String, baseUrl: String) {

        _sessionDisplayName.value = displayName

        _role.value = Role.Client

        _localPlayerIndex.value = 0

        val c = LanGamingClient(listener)

        c.displayName = displayName

        client = c

        appendLog("connecting to ${baseUrl.trimEnd('/')}…")

        c.connect(baseUrl.trimEnd('/'))

    }



    /** Host: notify all joined clients to open the play screen (call when starting the match). */
    fun broadcastLanGameStarted() {

        if (_role.value != Role.Host) return

        val h = host ?: return

        _hostHasStartedLanPlay.value = true

        val frame = ProtocolCodec.encodeFrame(

            WireMessageType.APP_PAYLOAD,

            GameMessageCodec.encodeGameStarted()

        )

        h.broadcast(frame)

        appendLog("game started (broadcast to clients)")

    }



    private fun clearLanUndoStacks() {

        lanUndoStacks.clear()

        clientLanUndoStack.clear()

        clientLanVoluntaryPenaltyUndo = null

    }



    private fun clearLanUndoForSeat(seat: Int) {

        lanUndoStacks.remove(seat)

        if (_role.value == Role.Client && seat == _localPlayerIndex.value) {

            clientLanUndoStack.clear()

            clientLanVoluntaryPenaltyUndo = null

        }

    }



    private fun pushLanUndo(seat: Int, entry: LanUndoEntry) {

        lanUndoStacks.getOrPut(seat) { ArrayDeque() }.addLast(entry)

    }



    fun connectToLanHost(hostBaseUrl: String) {

        if (_role.value != Role.Client) return

        val c = client ?: return

        mdnsBrowser?.stop()

        mdnsBrowser = null

        appendLog("connecting to ${hostBaseUrl.trim()}…")

        c.connect(hostBaseUrl.trim())

    }



    /**

     * Host: broadcast roll. Single player: local roll and open turn resolution.

     */

    fun rollDice() {

        when (_role.value) {

            Role.Host -> rollDiceHost()

            Role.Client -> rollDiceClient()

            Role.SinglePlayer -> rollDiceSinglePlayer()

            else -> {}

        }

    }



    /** Host device when this seat is active: roll locally. */
    private fun rollDiceHost() {

        val state = _match.value ?: return

        if (state.activePlayerIndex != _localPlayerIndex.value) {

            appendLog("only the active player can roll")

            return

        }

        if (!canStartMultiplayerRoll()) return

        performAuthoritativeRoll()

    }



    /** Active player on a client device: ask host to roll. */
    private fun rollDiceClient() {

        val state = _match.value ?: return

        if (state.activePlayerIndex != _localPlayerIndex.value) return

        if (!canStartMultiplayerRoll()) return

        val c = client ?: return

        val payload = GameMessageCodec.encodeIntent(_localPlayerIndex.value, "roll", JSONObject())

        val frame = ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload)

        c.sendFrame(frame)

    }



    private fun canStartMultiplayerRoll(): Boolean {

        if (_gameOverReason.value != null) return false

        if (_resolutionByPlayer.value != null) {

            appendLog("end turn before rolling again")

            return false

        }

        return true

    }



    /**
     * Host-only: execute dice roll for the current [MatchState.activePlayerIndex], broadcast roll + state.
     * Used by the hosting device when it is the active seat, or when honoring [intent_roll].
     */
    private fun performAuthoritativeRoll() {

        val state = _match.value ?: return

        if (_resolutionByPlayer.value != null) return

        val rnd = Random.Default

        val roll = buildDiceRoll(state, rnd)

        performAuthoritativeRollWithRoll(roll, rnd.nextInt())

    }

    /** Host-only: broadcast a specific roll (normal or debug rigged). */
    private fun performAuthoritativeRollWithRoll(roll: DiceRoll, cid: Int) {

        val h = host ?: return

        val state = _match.value ?: return

        if (_resolutionByPlayer.value != null) return

        _diceRollAnimationSettled.value = false

        _lastRoll.value = roll

        _diceRollGeneration.update { it + 1 }

        openRollForMatch(roll, takeHostSnapshot = true)

        val payload = GameMessageCodec.encodeRoll(roll, state.activePlayerIndex, cid)

        val frame = ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload)

        h.broadcast(frame)

        broadcastGameState(_match.value!!)

        appendLog("rolled white=${roll.whiteSum()} (active=${state.activePlayerIndex})")

    }



    private fun rollDiceSinglePlayer() {

        if (_gameOverReason.value != null) {

            appendLog("game over — restart single player")

            return

        }

        if (_resolutionByPlayer.value != null) {

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
     * LAN: same debug gestures as single-player (double-tap colored die = rigged roll toward that row’s lock).
     * Host performs the roll locally; client sends [intent_debug_roll].
     */
    fun onLanDieDoubleTap(slot: LocXXDieSlot) {
        if (_gameOverReason.value != null) {
            appendLog("game over")
            return
        }
        when (_role.value) {
            Role.Host -> onHostLanDieDoubleTap(slot)
            Role.Client -> onClientLanDieDoubleTap(slot)
            else -> {}
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

        if (_resolutionByPlayer.value != null) {

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



    private fun onHostLanDieDoubleTap(slot: LocXXDieSlot) {

        val state = _match.value ?: return

        if (state.activePlayerIndex != _localPlayerIndex.value) return

        if (!canStartMultiplayerRoll()) return

        when (slot) {

            LocXXDieSlot.WHITE1 -> playFirstWhiteDieLockCelebration()

            LocXXDieSlot.WHITE2 -> Unit

            else -> {

                val row = debugRowFromColoredDieSlot(slot) ?: return

                val roll = buildDebugLockDiceRoll(state, row)

                performAuthoritativeRollWithRoll(roll, Random.Default.nextInt())

                appendLog("debug: rigged roll for ${row.name} lock (${rowValues(row).last()})")

            }

        }

    }

    private fun onClientLanDieDoubleTap(slot: LocXXDieSlot) {

        val state = _match.value ?: return

        if (state.activePlayerIndex != _localPlayerIndex.value) return

        if (!canStartMultiplayerRoll()) return

        val c = client ?: return

        when (slot) {

            LocXXDieSlot.WHITE1 -> playFirstWhiteDieLockCelebration()

            LocXXDieSlot.WHITE2 -> Unit

            else -> {

                val row = debugRowFromColoredDieSlot(slot) ?: return

                val roll = buildDebugLockDiceRoll(state, row)

                val cid = Random.Default.nextInt()

                val body = GameMessageCodec.buildDebugRollBody(roll, cid)

                val payload = GameMessageCodec.encodeIntent(_localPlayerIndex.value, "debug_roll", body)

                c.sendFrame(ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload))

                appendLog("debug: requested rigged roll for ${row.name} lock")

            }

        }

    }

    private fun debugRowFromColoredDieSlot(slot: LocXXDieSlot): RowId? = when (slot) {

        LocXXDieSlot.WHITE1, LocXXDieSlot.WHITE2 -> null

        LocXXDieSlot.RED -> RowId.RED

        LocXXDieSlot.YELLOW -> RowId.YELLOW

        LocXXDieSlot.GREEN -> RowId.GREEN

        LocXXDieSlot.BLUE -> RowId.BLUE

    }

    /** Confetti / sound / overlay for the red row — no dice change (debug: double-tap first white die). */
    private fun playFirstWhiteDieLockCelebration() {

        val m = _match.value ?: return

        when (_role.value) {

            Role.SinglePlayer -> {}

            Role.Host, Role.Client -> {

                if (m.activePlayerIndex != _localPlayerIndex.value) return

            }

            else -> return

        }

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

        _resolutionByPlayer.value = listOf(RollResolutionState(roll))

        _crossesThisRoll.value = 0

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = null

        lanVoluntaryPenaltyUndo = null

        clientLanVoluntaryPenaltyUndo = null

        refreshLegalMoves()

    }



    private fun openRollForMatch(roll: DiceRoll, takeHostSnapshot: Boolean) {

        val state = _match.value ?: return

        if (takeHostSnapshot && _role.value == Role.Host) {

            multiplayerRollStartSnapshot = state.snapshot()

        } else if (_role.value == Role.Client) {

            multiplayerRollStartSnapshot = state.snapshot()

        }

        _resolutionByPlayer.value = List(state.playerCount) { RollResolutionState(roll) }

        _crossesThisRoll.value = 0

        _whitePhaseAcks.value = emptySet()

        clearLanUndoStacks()

        remoteLockReadyHints.clear()

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = null

        lanVoluntaryPenaltyUndo = null

        clientLanVoluntaryPenaltyUndo = null

        refreshLegalMoves()

    }



    private fun allPlayersPressedRollDone(): Boolean {

        val state = _match.value ?: return true

        val n = state.playerCount

        val acks = _whitePhaseAcks.value

        return (0 until n).all { it in acks }

    }



    /** True while a roll is open and at least one seat has not pressed Done — defers game-over UI until the phase ends. */
    private fun shouldDeferMultiplayerGameOverCheck(): Boolean {

        if (_resolutionByPlayer.value == null) return false

        return !allPlayersPressedRollDone()

    }



    /** LAN: roller has full marks; others white-sum only; no moves after that seat presses Done. */
    private fun multiplayerLegalMovesForSeat(

        state: MatchState,

        list: List<RollResolutionState>,

        playerIndex: Int

    ): List<LegalMove> {

        val res = list.getOrNull(playerIndex) ?: return emptyList()

        val sheet = state.playerSheets[playerIndex]

        if (playerIndex in _whitePhaseAcks.value) return emptyList()

        val roller = state.activePlayerIndex

        val globalLocks = state.globallyLockedRows

        if (playerIndex == roller) {

            val voluntaryLock = when (_role.value) {

                Role.Host -> lanVoluntaryPenaltyUndo?.seat == playerIndex

                Role.Client -> clientLanVoluntaryPenaltyUndo?.seat == playerIndex

                else -> false

            }

            if (voluntaryLock) return emptyList()

        }

        return if (playerIndex == roller) {

            LocXXRollResolution.legalMoves(

                isActivePlayer = true,

                roll = res.roll,

                sheet = sheet,

                diceInPlay = state.diceInPlay,

                resolution = res,

                globallyLockedRows = globalLocks

            )

        } else {

            LocXXRollResolution.legalMoves(

                isActivePlayer = false,

                roll = res.roll,

                sheet = sheet,

                diceInPlay = state.diceInPlay,

                resolution = res,

                globallyLockedRows = globalLocks

            ).filterIsInstance<LegalMove.WhiteSum>()

        }

    }



    /** Inactive seat: Done = finished with optional white for this roll (pass or mark). */
    private fun inactiveAckHost(playerIndex: Int) {

        val state = _match.value ?: return

        if (_resolutionByPlayer.value == null) return

        if (playerIndex !in state.playerSheets.indices) return

        if (playerIndex == state.activePlayerIndex) return

        if (playerIndex in _whitePhaseAcks.value) return

        clearLanUndoForSeat(playerIndex)

        _whitePhaseAcks.update { it + playerIndex }

        refreshLegalMoves()

        broadcastGameState(_match.value!!)

        tryFinishMultiplayerRollIfEveryoneDone()

    }



    /** Voluntary penalty: revert this roll’s marks, +1 penalty; roller locked until Done or undo. */
    private fun rollerApplyVoluntaryPenaltyHost(playerIndex: Int) {

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        if (playerIndex != state.activePlayerIndex) return

        if (playerIndex in _whitePhaseAcks.value) return

        if (playerIndex !in list.indices) return

        val rollStart = multiplayerRollStartSnapshot ?: run {

            appendLog("reject penalty (no roll snapshot)")

            return

        }

        val preMatch = state.snapshot()

        val preResolutions =

            list.map { it.copy(whiteUsedForColor = it.whiteUsedForColor.toSet()) }

        val preCrosses = _crossesThisRoll.value

        val preStack =

            lanUndoStacks[playerIndex]?.map { e ->

                LanUndoEntry(

                    sheetDeepCopy(e.sheet),

                    e.resolution.copy(whiteUsedForColor = e.resolution.whiteUsedForColor.toSet()),

                    e.crossesThisRoll,

                    e.move

                )

            } ?: emptyList()

        val roll = list[playerIndex].roll

        val freshRes =

            RollResolutionState(roll = roll, whiteSumUsed = false, whiteUsedForColor = emptySet())

        val revertedSheets = state.playerSheets.toMutableList()

        revertedSheets[playerIndex] = sheetDeepCopy(rollStart.playerSheets[playerIndex])

        var reverted = applyDeferredOrDerivedGlobals(state.copy(playerSheets = revertedSheets))

        _match.value = reverted

        val newList = list.toMutableList()

        newList[playerIndex] = freshRes

        _resolutionByPlayer.value = newList

        _crossesThisRoll.value = 0

        lanUndoStacks.remove(playerIndex)

        lanVoluntaryPenaltyUndo =

            LanVoluntaryPenaltyUndo(

                seat = playerIndex,

                matchWithMarks = preMatch,

                resolutions = preResolutions,

                crosses = preCrosses,

                rollerLanUndoStack = preStack

            )

        applyRollerPenaltyCore()

        _match.value?.let { checkGameOverMulti(it) }

        refreshLegalMoves()

        broadcastGameState(_match.value!!)

    }



    /** Roller: Done — optional pass penalty if no marks and no voluntary penalty this roll; then white-phase ack. */
    private fun rollerFinishRollAckHost(playerIndex: Int) {

        val state = _match.value ?: return

        if (_resolutionByPlayer.value == null) return

        if (playerIndex != state.activePlayerIndex) return

        if (playerIndex in _whitePhaseAcks.value) return

        val crosses = _crossesThisRoll.value

        if (crosses == 0) {

            if (lanVoluntaryPenaltyUndo?.seat != playerIndex) {

                lanVoluntaryPenaltyUndo = null

                applyRollerPenaltyCore()

                _match.value?.let { checkGameOverMulti(it) }

            } else {

                lanVoluntaryPenaltyUndo = null

            }

        } else {

            lanVoluntaryPenaltyUndo = null

        }

        clearLanUndoForSeat(playerIndex)

        _whitePhaseAcks.update { it + playerIndex }

        refreshLegalMoves()

        broadcastGameState(_match.value!!)

        tryFinishMultiplayerRollIfEveryoneDone()

    }

    /** Host: roller pressed Done after remote merge; sheet already includes any pass penalty. */
    private fun rollerFinishRollAckFromMerged(playerIndex: Int) {

        val state = _match.value ?: return

        if (_resolutionByPlayer.value == null) return

        if (playerIndex != state.activePlayerIndex) return

        if (playerIndex in _whitePhaseAcks.value) return

        lanVoluntaryPenaltyUndo = null

        clearLanUndoForSeat(playerIndex)

        _whitePhaseAcks.update { it + playerIndex }

        refreshLegalMoves()

        broadcastGameState(_match.value!!)

        tryFinishMultiplayerRollIfEveryoneDone()

    }

    /** Host: apply one seat’s submitted sheet + resolution from a client Done intent. */
    private fun mergeClientSubmittedSeat(
        playerIndex: Int,
        sheet: PlayerSheet,
        res: RollResolutionState,
        crossesThisRoll: Int
    ): Boolean {

        val state = _match.value ?: return false

        val list = _resolutionByPlayer.value ?: return false

        if (playerIndex in _whitePhaseAcks.value) return false

        if (playerIndex !in list.indices) return false

        if (res.roll != list[playerIndex].roll) {

            appendLog("reject done merge (roll mismatch)")

            return false

        }

        val newSheets = state.playerSheets.toMutableList()

        newSheets[playerIndex] = sheetDeepCopy(sheet)

        var merged = applyDeferredOrDerivedGlobals(state.copy(playerSheets = newSheets))

        _match.value = merged

        remoteLockReadyHints.remove(playerIndex)

        val newList = list.toMutableList()

        newList[playerIndex] = res.copy(whiteUsedForColor = res.whiteUsedForColor.toSet())

        _resolutionByPlayer.value = newList

        if (playerIndex == state.activePlayerIndex) {

            _crossesThisRoll.value = crossesThisRoll

        }

        refreshLegalMoves()

        checkGameOverMulti(merged)

        return true

    }



    /** Host: voluntary penalty (no crosses) this roll — restore match and reopen roller white phase. */
    private fun undoLanVoluntaryPenaltyHost(playerIndex: Int): Boolean {

        val u = lanVoluntaryPenaltyUndo ?: return false

        if (u.seat != playerIndex) return false

        val state = _match.value ?: return false

        if (_resolutionByPlayer.value == null) return false

        if (playerIndex != state.activePlayerIndex) return false

        if (playerIndex in _whitePhaseAcks.value) return false

        val restored = applyDeferredOrDerivedGlobals(u.matchWithMarks.snapshot())

        _match.value = restored

        _resolutionByPlayer.value =

            u.resolutions.map { it.copy(whiteUsedForColor = it.whiteUsedForColor.toSet()) }

        _crossesThisRoll.value = u.crosses

        if (u.rollerLanUndoStack.isEmpty()) {

            lanUndoStacks.remove(playerIndex)

        } else {

            lanUndoStacks[playerIndex] =

                ArrayDeque(

                    u.rollerLanUndoStack.map { e ->

                        LanUndoEntry(

                            sheetDeepCopy(e.sheet),

                            e.resolution.copy(whiteUsedForColor = e.resolution.whiteUsedForColor.toSet()),

                            e.crossesThisRoll,

                            e.move

                        )

                    }

                )

        }

        lanVoluntaryPenaltyUndo = null

        refreshLegalMoves()

        broadcastGameState(_match.value!!)

        if (playerIndex == _localPlayerIndex.value) {

            playLocxxUndoMarkDing()

        }

        appendLog("lan voluntary penalty undone")

        checkGameOverMulti(restored)

        return true

    }



    private fun applyRollerPenaltyCore() {

        val cur = _match.value ?: return

        val idx = cur.activePlayerIndex

        val snap = multiplayerRollStartSnapshot

        val merged = if (snap != null) {

            cur.copySheet(

                idx,

                snap.playerSheets[idx].copy(penalties = snap.playerSheets[idx].penalties + 1)

            )

        } else {

            val sheet = cur.playerSheets[idx]

            cur.copySheet(idx, sheet.copy(penalties = sheet.penalties + 1))

        }

        val fixed = applyDeferredOrDerivedGlobals(merged)

        _match.value = fixed

        appendLog("penalty (${fixed.playerSheets[idx].penalties} for seat $idx)")

        if (idx == _localPlayerIndex.value) {

            playLocxxPenaltyThud()

        }

    }

    private fun rollerApplyVoluntaryPenaltyClientLocal(playerIndex: Int) {

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        if (playerIndex != state.activePlayerIndex) return

        if (playerIndex in _whitePhaseAcks.value) return

        if (playerIndex !in list.indices) return

        val rollStart = multiplayerRollStartSnapshot ?: return

        val preMatch = state.snapshot()

        val preResolutions =

            list.map { it.copy(whiteUsedForColor = it.whiteUsedForColor.toSet()) }

        val preCrosses = _crossesThisRoll.value

        val preStack =

            clientLanUndoStack.map { e ->

                LanUndoEntry(

                    sheetDeepCopy(e.sheet),

                    e.resolution.copy(whiteUsedForColor = e.resolution.whiteUsedForColor.toSet()),

                    e.crossesThisRoll,

                    e.move

                )

            }

        val roll = list[playerIndex].roll

        val freshRes =

            RollResolutionState(roll = roll, whiteSumUsed = false, whiteUsedForColor = emptySet())

        val revertedSheets = state.playerSheets.toMutableList()

        revertedSheets[playerIndex] = sheetDeepCopy(rollStart.playerSheets[playerIndex])

        var reverted = applyDeferredOrDerivedGlobals(state.copy(playerSheets = revertedSheets))

        _match.value = reverted

        val newList = list.toMutableList()

        newList[playerIndex] = freshRes

        _resolutionByPlayer.value = newList

        _crossesThisRoll.value = 0

        clientLanUndoStack.clear()

        clientLanVoluntaryPenaltyUndo =

            LanVoluntaryPenaltyUndo(

                seat = playerIndex,

                matchWithMarks = preMatch,

                resolutions = preResolutions,

                crosses = preCrosses,

                rollerLanUndoStack = preStack

            )

        applyRollerPenaltyCore()

        _match.value?.let { checkGameOverMulti(it) }

        refreshLegalMoves()

    }

    private fun undoLanVoluntaryPenaltyClientLocal(playerIndex: Int): Boolean {

        val u = clientLanVoluntaryPenaltyUndo ?: return false

        if (u.seat != playerIndex) return false

        val state = _match.value ?: return false

        if (_resolutionByPlayer.value == null) return false

        if (playerIndex != state.activePlayerIndex) return false

        if (playerIndex in _whitePhaseAcks.value) return false

        val restored = applyDeferredOrDerivedGlobals(u.matchWithMarks.snapshot())

        _match.value = restored

        _resolutionByPlayer.value =

            u.resolutions.map { it.copy(whiteUsedForColor = it.whiteUsedForColor.toSet()) }

        _crossesThisRoll.value = u.crosses

        clientLanUndoStack.clear()

        for (e in u.rollerLanUndoStack) clientLanUndoStack.addLast(e)

        clientLanVoluntaryPenaltyUndo = null

        refreshLegalMoves()

        appendLog("lan voluntary penalty undone (local)")

        checkGameOverMulti(restored)

        playLocxxUndoMarkDing()

        return true

    }



    private fun tryFinishMultiplayerRollIfEveryoneDone() {

        if (!allPlayersPressedRollDone()) return

        val state = _match.value ?: return

        if (_resolutionByPlayer.value == null) return

        val advanced = advanceActivePlayer(state)

        finishMultiplayerRollPhase(advanced)

    }



    /** Soft chime when Done / end turn completes without a pass penalty (see [multiplayerDone], [endSinglePlayerTurn]). */
    private fun playLocxxDoneAckChime() {

        playLocxxMarkDingPreview(LocxxMarkDingVariant.SOFT_CHIME)

    }



    /** LAN: unified Done (roller ends turn for this roll; others finish white phase). */
    fun multiplayerDone() {

        when (_role.value) {

            Role.Host -> {

                val st = _match.value ?: return

                val idx = _localPlayerIndex.value

                if (idx == st.activePlayerIndex) {

                    val voluntaryAlready = lanVoluntaryPenaltyUndo?.seat == idx

                    val penaltyFromDone = _crossesThisRoll.value == 0 && !voluntaryAlready

                    rollerFinishRollAckHost(idx)

                    if (!penaltyFromDone) {

                        playLocxxDoneAckChime()

                    }

                } else {

                    inactiveAckHost(idx)

                    playLocxxDoneAckChime()

                }

            }

            Role.Client -> {

                val st = _match.value ?: return

                val idx = _localPlayerIndex.value

                if (idx == st.activePlayerIndex) {

                    val voluntaryAlready = clientLanVoluntaryPenaltyUndo?.seat == idx

                    val penaltyFromDone = _crossesThisRoll.value == 0 && !voluntaryAlready

                    when {

                        _crossesThisRoll.value == 0 && !voluntaryAlready -> {

                            clientLanVoluntaryPenaltyUndo = null

                            applyRollerPenaltyCore()

                            _match.value?.let { checkGameOverMulti(it) }

                        }

                        _crossesThisRoll.value == 0 && voluntaryAlready -> {

                            clientLanVoluntaryPenaltyUndo = null

                        }

                        else -> {

                            clientLanVoluntaryPenaltyUndo = null

                        }

                    }

                    sendEndTurnIntent()

                    if (!penaltyFromDone) {

                        playLocxxDoneAckChime()

                    }

                } else {

                    sendAckWhiteIntent()

                    playLocxxDoneAckChime()

                }

            }

            else -> {}

        }

    }



    fun acknowledgeWhiteDicePhase() {

        multiplayerDone()

    }



    private fun sendAckWhiteIntent() {

        val c = client ?: return

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        val idx = _localPlayerIndex.value

        if (idx in _whitePhaseAcks.value) return

        val sheet = state.playerSheets[idx]

        val res = list.getOrNull(idx) ?: return

        val body = GameMessageCodec.buildLanDonePhaseBody(sheet, res, crossesThisRoll = 0)

        val payload = GameMessageCodec.encodeIntent(idx, "ack_white", body)

        c.sendFrame(ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload))

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

        val list = _resolutionByPlayer.value

        val state = _match.value

        val localIdx = _localPlayerIndex.value

        if (list == null || state == null || localIdx !in state.playerSheets.indices) {

            _legalMoves.value = emptyList()

            return

        }

        val res = list.getOrNull(localIdx) ?: return

        val sheet = state.playerSheets[localIdx]

        val mpLan = _role.value == Role.Host || _role.value == Role.Client

        if (mpLan) {

            _legalMoves.value = multiplayerLegalMovesForSeat(state, list, localIdx)

            return

        }

        if (_role.value == Role.SinglePlayer && singlePlayerVoluntaryPenaltyUndo != null) {

            _legalMoves.value = emptyList()

            return

        }

        var moves = LocXXRollResolution.legalMoves(

            isActivePlayer = true,

            roll = res.roll,

            sheet = sheet,

            diceInPlay = state.diceInPlay,

            resolution = res,

            globallyLockedRows = state.globallyLockedRows

        )

        _legalMoves.value = moves

    }



    fun applyLegalMove(move: LegalMove) {

        when (_role.value) {

            Role.SinglePlayer -> applyLegalMoveSinglePlayer(move)

            Role.Host -> applyLegalMoveHost(move)

            Role.Client -> applyLegalMoveClientLocal(move)

            else -> {}

        }

    }



    private fun applyLegalMoveSinglePlayer(move: LegalMove) {

        if (singlePlayerVoluntaryPenaltyUndo != null) return

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        val idx = state.activePlayerIndex

        val res = list.getOrNull(idx) ?: return

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

        val newList = list.toMutableList()

        newList[idx] = newRes

        _resolutionByPlayer.value = newList

        _crossesThisRoll.update { it + 1 }

        singlePlayerVoluntaryPenaltyUndo = null

        val rb = state.playerSheets[idx].rows[move.row]
        val ra = newState.playerSheets[idx].rows[move.row]
        if (ra?.locked == true && rb?.locked != true) {
            val sheet = newState.playerSheets[idx]
            val pts = LocXXRules.rowPoints(sheet, move.row)
            _rowLockCelebration.value = RowLockCelebrationUi(move.row, pts)
        }

        playLocxxMarkDing()

        refreshLegalMoves()

    }



    private fun applyLegalMoveHost(move: LegalMove) {

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        val localIdx = _localPlayerIndex.value

        if (

            lanVoluntaryPenaltyUndo?.seat == state.activePlayerIndex &&

                localIdx == state.activePlayerIndex

        ) {

            return

        }

        val res = list.getOrNull(localIdx) ?: return

        val legal = multiplayerLegalMovesForSeat(state, list, localIdx)

        if (move !in legal) {

            appendLog("illegal move")

            return

        }

        applyMoveAuthoritative(state, res, move, localIdx)

    }

    private fun applyLegalMoveClientLocal(move: LegalMove) {

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        val localIdx = _localPlayerIndex.value

        if (

            clientLanVoluntaryPenaltyUndo?.seat == state.activePlayerIndex &&

                localIdx == state.activePlayerIndex

        ) {

            return

        }

        val res = list.getOrNull(localIdx) ?: return

        val legal = multiplayerLegalMovesForSeat(state, list, localIdx)

        if (move !in legal) {

            appendLog("illegal move")

            return

        }

        if (!applyLanCrossAuthoritativeCore(state, res, move, localIdx)) return

        playLocxxMarkDing()

    }



    /**
     * Apply a cross to authoritative LAN state only (no network).
     * @return false if the cross was rejected by rules.
     */
    private fun applyLanCrossAuthoritativeCore(
        state: MatchState,
        res: RollResolutionState,
        move: LegalMove,
        scoringPlayerIndex: Int
    ): Boolean {
        val sheetBefore = sheetDeepCopy(state.playerSheets[scoringPlayerIndex])
        val resolutionBefore =
            res.copy(whiteUsedForColor = res.whiteUsedForColor.toSet())
        val crossesBefore = _crossesThisRoll.value
        val newState = LocXXRules.applyCrossToMatch(
            state,
            scoringPlayerIndex,
            move.row,
            move.value,
            removeLockedColorDieImmediately = false
        ).getOrElse {
            appendLog("illegal move: ${it.message}")
            return false
        }
        val newRes = LocXXRollResolution.afterMove(res, move)
        val list = _resolutionByPlayer.value ?: return false
        val newList = list.toMutableList()
        newList[scoringPlayerIndex] = newRes
        _match.value = newState
        _resolutionByPlayer.value = newList
        if (scoringPlayerIndex == state.activePlayerIndex) {
            _crossesThisRoll.update { it + 1 }
            when (_role.value) {
                Role.Host -> lanVoluntaryPenaltyUndo = null
                Role.Client -> clientLanVoluntaryPenaltyUndo = null
                else -> {}
            }
        }
        val undoEntry = LanUndoEntry(sheetBefore, resolutionBefore, crossesBefore, move)
        if (_role.value == Role.Host) {
            pushLanUndo(scoringPlayerIndex, undoEntry)
        } else if (_role.value == Role.Client && scoringPlayerIndex == _localPlayerIndex.value) {
            clientLanUndoStack.addLast(undoEntry)
        }
        val rb = state.playerSheets[scoringPlayerIndex].rows[move.row]
        val ra = newState.playerSheets[scoringPlayerIndex].rows[move.row]
        if (ra?.locked == true && rb?.locked != true && scoringPlayerIndex == _localPlayerIndex.value) {
            val sheet = newState.playerSheets[scoringPlayerIndex]
            val pts = LocXXRules.rowPoints(sheet, move.row)
            _rowLockCelebration.value = RowLockCelebrationUi(move.row, pts)
        }
        refreshLegalMoves()
        if (_role.value == Role.Client && scoringPlayerIndex == _localPlayerIndex.value) {
            sendLockReadyHintToHost()
        }
        return true
    }

    private fun applyMoveAuthoritative(
        state: MatchState,
        res: RollResolutionState,
        move: LegalMove,
        scoringPlayerIndex: Int
    ) {
        if (!applyLanCrossAuthoritativeCore(state, res, move, scoringPlayerIndex)) return
        val newState = _match.value ?: return
        broadcastGameState(newState)
        if (_role.value == Role.Host) {
            checkGameOverMulti(newState)
        }
        if (scoringPlayerIndex == _localPlayerIndex.value) {
            playLocxxMarkDing()
        }
    }

    /** Client: penalty thud only when our seat’s penalty count rises ([applyRollerPenaltyCore] handles host device). */
    private fun maybeNotifyPenaltyThud(prev: MatchState?, newState: MatchState) {

        if (_role.value != Role.Client) return

        if (prev == null) return

        val i = _localPlayerIndex.value

        if (i !in prev.playerSheets.indices || i !in newState.playerSheets.indices) return

        if (newState.playerSheets[i].penalties > prev.playerSheets[i].penalties) {

            playLocxxPenaltyThud()

        }

    }

    /** Client: undo ding when our penalties decrease during an open roll (voluntary penalty undo). */
    private fun maybeNotifyLanVoluntaryPenaltyUndoClient(prev: MatchState?, newState: MatchState) {

        if (_role.value != Role.Client) return

        if (_resolutionByPlayer.value == null) return

        if (prev == null) return

        val i = _localPlayerIndex.value

        if (i !in prev.playerSheets.indices || i !in newState.playerSheets.indices) return

        if (newState.playerSheets[i].penalties < prev.playerSheets[i].penalties) {

            clientLanVoluntaryPenaltyUndo = null

            playLocxxUndoMarkDing()

        }

    }


    /** Client: show row-lock fanfare when our sheet gains a personal lock (synced from host). */
    private fun maybeNotifyLocalRowLockCelebration(prev: MatchState?, newState: MatchState) {

        if (_role.value != Role.Client) return

        val idx = _localPlayerIndex.value

        if (prev == null || idx !in prev.playerSheets.indices || idx !in newState.playerSheets.indices) return

        val before = prev.playerSheets[idx]

        val after = newState.playerSheets[idx]

        for (row in RowId.entries) {

            val rb = before.rows[row] ?: continue

            val ra = after.rows[row] ?: continue

            if (ra.locked && !rb.locked) {

                val pts = LocXXRules.rowPoints(after, row)

                _rowLockCelebration.value = RowLockCelebrationUi(row, pts)

                return

            }

        }

    }



    /** Host: tap same cell again to remove last cross by this seat on the open roll. */
    private fun tryUndoLanHost(playerIndex: Int, row: RowId, value: Int): Boolean {

        val state = _match.value ?: return false

        val list = _resolutionByPlayer.value ?: return false

        if (lanVoluntaryPenaltyUndo?.seat == playerIndex) return false

        if (playerIndex in _whitePhaseAcks.value) return false

        if (playerIndex !in list.indices) return false

        val stack = lanUndoStacks[playerIndex] ?: return false

        val e = stack.lastOrNull() ?: return false

        if (e.move.row != row || e.move.value != value) return false

        stack.removeLast()

        if (stack.isEmpty()) lanUndoStacks.remove(playerIndex)

        _rowLockCelebration.value = null

        val newSheets = state.playerSheets.toMutableList()

        newSheets[playerIndex] = sheetDeepCopy(e.sheet)

        var merged = state.copy(playerSheets = newSheets)

        merged = applyDeferredOrDerivedGlobals(merged)

        _match.value = merged

        val newList = list.toMutableList()

        newList[playerIndex] =

            e.resolution.copy(whiteUsedForColor = e.resolution.whiteUsedForColor.toSet())

        _resolutionByPlayer.value = newList

        if (playerIndex == state.activePlayerIndex) {

            _crossesThisRoll.value = e.crossesThisRoll

        }

        refreshLegalMoves()

        broadcastGameState(merged)

        checkGameOverMulti(merged)

        if (playerIndex == _localPlayerIndex.value) {

            playLocxxUndoMarkDing()

        }

        return true

    }



    private fun isFreshRollResolutionList(roll: DiceRoll, list: List<RollResolutionState>): Boolean {

        val z = RollResolutionState(roll, whiteSumUsed = false, whiteUsedForColor = emptySet())

        return list.all { it == z }

    }



    /** True if tapping this cell will undo the last cross (same row/value as that move). */
    fun canUndoCell(row: RowId, value: Int): Boolean {

        when (_role.value) {

            Role.SinglePlayer -> {

                if (singlePlayerVoluntaryPenaltyUndo != null) return false

                val e = singlePlayerUndoStack.lastOrNull() ?: return false

                return e.move.row == row && e.move.value == value

            }

            Role.Host -> {

                val idx = _localPlayerIndex.value

                if (_resolutionByPlayer.value == null) return false

                if (lanVoluntaryPenaltyUndo?.seat == idx) return false

                if (idx in _whitePhaseAcks.value) return false

                val e = lanUndoStacks[idx]?.lastOrNull() ?: return false

                return e.move.row == row && e.move.value == value

            }

            Role.Client -> {

                val idx = _localPlayerIndex.value

                if (_resolutionByPlayer.value == null) return false

                if (

                    clientLanVoluntaryPenaltyUndo?.seat == idx &&

                        _match.value?.activePlayerIndex == idx

                ) {

                    return false

                }

                if (idx in _whitePhaseAcks.value) return false

                val e = clientLanUndoStack.lastOrNull() ?: return false

                return e.move.row == row && e.move.value == value

            }

            else -> return false

        }

    }



    /** Undo the last cross this roll by tapping the same score cell again. */
    fun tryUndoCell(row: RowId, value: Int): Boolean {

        when (_role.value) {

            Role.SinglePlayer -> {

                if (singlePlayerVoluntaryPenaltyUndo != null) return false

                val e = singlePlayerUndoStack.lastOrNull() ?: return false

                if (e.move.row != row || e.move.value != value) return false

                singlePlayerUndoStack.removeLast()

                _rowLockCelebration.value = null

                _match.value = e.match

                _resolutionByPlayer.value = listOf(e.resolution)

                _crossesThisRoll.value = e.crosses

                refreshLegalMoves()

                checkGameOver(e.match)

                playLocxxUndoMarkDing()

                return true

            }

            Role.Host -> return tryUndoLanHost(_localPlayerIndex.value, row, value)

            Role.Client -> {

                val idx = _localPlayerIndex.value

                if (_resolutionByPlayer.value == null) return false

                if (

                    clientLanVoluntaryPenaltyUndo?.seat == idx &&

                        _match.value?.activePlayerIndex == idx

                ) {

                    return false

                }

                if (idx in _whitePhaseAcks.value) return false

                val e = clientLanUndoStack.lastOrNull() ?: return false

                if (e.move.row != row || e.move.value != value) return false

                clientLanUndoStack.removeLast()

                val st = _match.value ?: return false

                val lr = _resolutionByPlayer.value ?: return false

                val newSheets = st.playerSheets.toMutableList()

                newSheets[idx] = sheetDeepCopy(e.sheet)

                var merged = st.copy(playerSheets = newSheets)

                merged = applyDeferredOrDerivedGlobals(merged)

                _match.value = merged

                val newList = lr.toMutableList()

                newList[idx] =

                    e.resolution.copy(whiteUsedForColor = e.resolution.whiteUsedForColor.toSet())

                _resolutionByPlayer.value = newList

                if (idx == st.activePlayerIndex) {

                    _crossesThisRoll.value = e.crossesThisRoll

                }

                _rowLockCelebration.value = null

                refreshLegalMoves()

                sendLockReadyHintToHost()

                playLocxxUndoMarkDing()

                return true

            }

            else -> return false

        }

    }



    /** Call when [AnimatedLocXXDiceStrip] finishes tumbling and spinning. */
    fun notifyDiceRollAnimationFinished() {

        _diceRollAnimationSettled.value = true

    }



    /**
     * Voluntary penalty while a roll is open: +1 penalty; roll stays open (tap End turn when finished).
     * Any crosses this roll are cleared first (Qwixx-style). Tap again to undo before End turn or the next roll.
     * With no roll open, tapping undoes the last voluntary penalty.
     */
    fun singlePlayerPenalty() {

        if (_role.value != Role.SinglePlayer) return

        if (singlePlayerVoluntaryPenaltyUndo != null) {

            restoreSinglePlayerVoluntaryPenaltyUndo()

            return

        }

        if (_resolutionByPlayer.value == null) return

        val list = _resolutionByPlayer.value ?: return

        val res0 = list.getOrNull(0) ?: return

        val preTapMatch = (_match.value ?: return).snapshot()

        val preTapRes = res0.copy(whiteUsedForColor = res0.whiteUsedForColor.toSet())

        val preTapCrosses = _crossesThisRoll.value

        val preTapStack = singlePlayerUndoStack.toList()

        val bottom = singlePlayerUndoStack.firstOrNull()

        if (bottom != null) {

            _match.value = bottom.match

            _resolutionByPlayer.value = listOf(bottom.resolution)

            _crossesThisRoll.value = 0

        }

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = SinglePlayerVoluntaryPenaltyUndo(

            preTapMatch,

            preTapRes,

            preTapCrosses,

            preTapStack

        )

        val state = _match.value ?: return

        val idx = state.activePlayerIndex

        val sheet = state.playerSheets[idx]

        val penalized =

            applyDeferredOrDerivedGlobals(state.copySheet(idx, sheet.copy(penalties = sheet.penalties + 1)))

        _match.value = penalized

        appendLog("penalty (${penalized.playerSheets[idx].penalties})")

        playLocxxPenaltyThud()

        refreshLegalMoves()

        checkGameOver(penalized)

    }



    /** Single-player: tap penalty again to undo a voluntary penalty (during the roll or before the next roll). */
    fun canUndoSinglePlayerVoluntaryPenalty(): Boolean =

        _role.value == Role.SinglePlayer && singlePlayerVoluntaryPenaltyUndo != null



    private fun restoreSinglePlayerVoluntaryPenaltyUndo() {

        val u = singlePlayerVoluntaryPenaltyUndo ?: return

        if (_role.value != Role.SinglePlayer) return

        _match.value = u.match.snapshot()

        _resolutionByPlayer.value = listOf(

            u.resolution.copy(whiteUsedForColor = u.resolution.whiteUsedForColor.toSet())

        )

        _crossesThisRoll.value = u.crossesThisRoll

        singlePlayerUndoStack.clear()

        for (e in u.undoStackEntries) singlePlayerUndoStack.addLast(e)

        singlePlayerVoluntaryPenaltyUndo = null

        _gameOverReason.value = null

        refreshLegalMoves()

        playLocxxUndoMarkDing()

        appendLog("penalty undone")

        _match.value?.let { checkGameOver(it) }

    }



    /**

     * Finish the current roll. If you made no crosses, you take one penalty (Qwixx-style).

     */

    fun endSinglePlayerTurn() {

        if (_role.value != Role.SinglePlayer) return

        if (_resolutionByPlayer.value == null) return

        if (_crossesThisRoll.value == 0) {

            if (singlePlayerVoluntaryPenaltyUndo != null) {

                singlePlayerVoluntaryPenaltyUndo = null

                val state = _match.value ?: return

                _resolutionByPlayer.value = null

                multiplayerRollStartSnapshot = null

                _crossesThisRoll.value = 0

                _legalMoves.value = emptyList()

                singlePlayerUndoStack.clear()

                playLocxxDoneAckChime()

                checkGameOver(state)

                return

            }

            applyPenaltyAndEndTurn()

            return

        }

        val state = _match.value ?: return

        _resolutionByPlayer.value = null

        multiplayerRollStartSnapshot = null

        _crossesThisRoll.value = 0

        _legalMoves.value = emptyList()

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = null

        playLocxxDoneAckChime()

        checkGameOver(state)

    }



    private fun applyPenaltyAndEndTurn() {

        val state = _match.value ?: return

        val idx = state.activePlayerIndex

        val sheet = state.playerSheets[idx]

        val newSheet = sheet.copy(penalties = sheet.penalties + 1)

        val newState = state.copySheet(idx, newSheet)

        _match.value = newState

        _resolutionByPlayer.value = null

        multiplayerRollStartSnapshot = null

        _crossesThisRoll.value = 0

        _legalMoves.value = emptyList()

        singlePlayerUndoStack.clear()

        appendLog("penalty (${newSheet.penalties})")

        playLocxxPenaltyThud()

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



    /** LAN: game end when any player hits 4 penalties or 2 rows lock globally. */
    private fun checkGameOverMulti(state: MatchState) {

        when (_role.value) {

            Role.Host, Role.Client -> {

                val penalties = state.playerSheets.map { it.penalties }

                val met = LocXXRules.gameShouldEnd(penalties, state.lockedRowCount())

                if (!met) {

                    _gameOverReason.value = null

                    return

                }

                if (shouldDeferMultiplayerGameOverCheck()) {

                    _gameOverReason.value = null

                    return

                }

                _gameOverReason.value = "Game over"

                appendLog("game over")

            }

            else -> {}

        }

    }



    fun endMultiplayerTurn() {

        multiplayerDone()

    }



    fun multiplayerPenalty() {

        when (_role.value) {

            Role.Host -> {

                val st = _match.value ?: return

                if (st.activePlayerIndex != _localPlayerIndex.value) return

                if (_resolutionByPlayer.value == null) return

                if (canUndoLanVoluntaryPenalty()) {

                    undoLanVoluntaryPenaltyHost(st.activePlayerIndex)

                } else {

                    rollerApplyVoluntaryPenaltyHost(st.activePlayerIndex)

                }

            }

            Role.Client -> {

                val st = _match.value ?: return

                if (st.activePlayerIndex != _localPlayerIndex.value) return

                if (_resolutionByPlayer.value == null) return

                if (canUndoLanVoluntaryPenalty()) {

                    undoLanVoluntaryPenaltyClientLocal(st.activePlayerIndex)

                } else {

                    rollerApplyVoluntaryPenaltyClientLocal(st.activePlayerIndex)

                }

            }

            else -> {}

        }

    }



    /** LAN: tap penalty again after a voluntary penalty (no crosses) to undo before the roll closes. */
    fun canUndoLanVoluntaryPenalty(): Boolean {

        if (_gameOverReason.value != null) return false

        if (_resolutionByPlayer.value == null) return false

        val st = _match.value ?: return false

        val idx = _localPlayerIndex.value

        if (idx != st.activePlayerIndex) return false

        if (idx in _whitePhaseAcks.value) return false

        return when (_role.value) {

            Role.Host -> lanVoluntaryPenaltyUndo?.seat == idx

            Role.Client -> clientLanVoluntaryPenaltyUndo?.seat == idx

            else -> false

        }

    }



    private fun sendEndTurnIntent() {

        val c = client ?: return

        val state = _match.value ?: return

        val list = _resolutionByPlayer.value ?: return

        val idx = _localPlayerIndex.value

        if (state.activePlayerIndex != idx) return

        val sheet = state.playerSheets[idx]

        val res = list.getOrNull(idx) ?: return

        val body = GameMessageCodec.buildLanDonePhaseBody(sheet, res, _crossesThisRoll.value)

        val payload = GameMessageCodec.encodeIntent(idx, "end_turn", body)

        c.sendFrame(ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload))

    }



    private fun finishMultiplayerRollPhase(advanced: MatchState) {

        val synced = advanced.withDerivedGlobalLocksAndDice()

        _match.value = synced

        _resolutionByPlayer.value = null

        _crossesThisRoll.value = 0

        _whitePhaseAcks.value = emptySet()

        _legalMoves.value = emptyList()

        multiplayerRollStartSnapshot = null

        _lastRoll.value = null

        lanVoluntaryPenaltyUndo = null

        clearLanUndoStacks()

        remoteLockReadyHints.clear()

        refreshLegalMoves()

        val h = host

        if (h != null) {

            broadcastGameState(synced)

        }

        checkGameOverMulti(synced)

    }



    private fun advanceActivePlayer(state: MatchState): MatchState =

        state.copy(activePlayerIndex = (state.activePlayerIndex + 1) % state.playerCount)



    /**
     * LAN open roll: keep [MatchState.globallyLockedRows] / [MatchState.diceInPlay] fixed at roll start.
     * Between phases, derive from all sheets (same as [finishMultiplayerRollPhase] commit).
     */
    private fun applyDeferredOrDerivedGlobals(state: MatchState): MatchState {

        val snap = multiplayerRollStartSnapshot

        return if (_resolutionByPlayer.value != null && snap != null) {

            state.copy(

                globallyLockedRows = snap.globallyLockedRows,

                diceInPlay = snap.diceInPlay

            )

        } else {

            state.withDerivedGlobalLocksAndDice()

        }

    }

    /**
     * LAN broadcast: seats that have not pressed Done this roll show their sheet as at roll open
     * so in-progress marks (and penalties) stay private until Done.
     */
    private fun publicLanMatchForBroadcast(authoritative: MatchState): MatchState {
        if (_resolutionByPlayer.value == null) return authoritative
        val snap = multiplayerRollStartSnapshot ?: return authoritative
        val acks = _whitePhaseAcks.value
        val sheets = authoritative.playerSheets.mapIndexed { i, sh ->
            if (i in acks) sh else snap.playerSheets[i]
        }
        return authoritative.copy(playerSheets = sheets)
    }

    /**
     * Host score-sheet display: full truth for this device’s seat; other seats only advance after they press Done.
     */
    fun multiplayerMatchForScoreSheet(authoritative: MatchState): MatchState {
        if (_role.value != Role.Host) return authoritative
        if (_resolutionByPlayer.value == null) return authoritative
        val snap = multiplayerRollStartSnapshot ?: return authoritative
        val acks = _whitePhaseAcks.value
        val local = _localPlayerIndex.value
        val sheets = authoritative.playerSheets.mapIndexed { i, sh ->
            when {
                i == local -> sh
                i in acks -> sh
                else -> snap.playerSheets[i]
            }
        }
        return authoritative.copy(playerSheets = sheets)
    }

    private fun handleHostRemoteIntent(address: String, root: JSONObject) {

        val kind = root.getString("kind")

        val body = root.getJSONObject("body")

        val playerIndex = body.getInt("playerIndex")

        val state = _match.value ?: return

        if (playerIndex !in state.playerSheets.indices) return

        when (kind) {

            "intent_lock_ready" -> {

                if (_resolutionByPlayer.value == null) return

                val rowsJa = body.optJSONArray("rows") ?: JSONArray()

                val set = buildSet<RowId> {

                    for (j in 0 until rowsJa.length()) {

                        runCatching { add(RowId.valueOf(rowsJa.getString(j))) }

                    }

                }

                remoteLockReadyHints[playerIndex] = set

                broadcastGameState(state)

                return

            }

            "intent_roll" -> {

                if (playerIndex != state.activePlayerIndex) {

                    appendLog("reject roll from $address (not active seat)")

                    return

                }

                if (!canStartMultiplayerRoll()) return

                performAuthoritativeRoll()

                return

            }

            "intent_debug_roll" -> {

                if (playerIndex != state.activePlayerIndex) {

                    appendLog("reject debug_roll from $address (not active seat)")

                    return

                }

                if (!canStartMultiplayerRoll()) return

                val roll = runCatching { GameMessageCodec.parseDebugRollBody(body) }.getOrElse {

                    appendLog("reject debug_roll (bad dice) from $address")

                    return

                }

                val cid = if (body.has("cid")) body.getInt("cid") else Random.Default.nextInt()

                performAuthoritativeRollWithRoll(roll, cid)

                appendLog("debug: rigged roll from client (white=${roll.whiteSum()})")

                return

            }

            else -> Unit

        }

        val list = _resolutionByPlayer.value ?: return

        if (playerIndex !in list.indices) return

        fun parseDoneOrNull(): Triple<PlayerSheet, RollResolutionState, Int>? {

            if (!body.has("playerSheet") || !body.has("rollResolution")) return null

            return runCatching {

                val sheet = GameMessageCodec.playerDoneSheetFromJson(body.getJSONObject("playerSheet"))

                val roll = list[playerIndex].roll

                val aux = GameMessageCodec.rollResolutionAuxFromJson(

                    body.getJSONObject("rollResolution"),

                    roll

                )

                val crosses = body.optInt("crossesThisRoll", 0)

                Triple(sheet, aux, crosses)

            }.getOrElse {

                appendLog("reject done payload from $address")

                null

            }

        }

        when (kind) {

            "intent_ack_white" -> {

                if (playerIndex == state.activePlayerIndex) {

                    appendLog("reject ack_white from roller $address")

                    return

                }

                val done = parseDoneOrNull() ?: return

                if (!mergeClientSubmittedSeat(playerIndex, done.first, done.second, crossesThisRoll = 0)) return

                inactiveAckHost(playerIndex)

            }

            "intent_end_turn" -> {

                if (playerIndex != state.activePlayerIndex) {

                    appendLog("reject end_turn from inactive $address")

                    return

                }

                val done = parseDoneOrNull() ?: return

                if (!mergeClientSubmittedSeat(playerIndex, done.first, done.second, done.third)) return

                rollerFinishRollAckFromMerged(playerIndex)

            }

            else -> {}

        }

    }



    private fun applyIncomingGameState(root: JSONObject) {

        if (_role.value != Role.Client) return

        val prevMatch = _match.value

        val prevResList = _resolutionByPlayer.value

        /** Last-applied roller cross count from host (inactive seats mirror this field between game_state packets). */
        val prevRollerCrossesThisRoll = _crossesThisRoll.value

        val wire = GameMessageCodec.decodeGameStateWire(root)

        val openRollPhase =
            wire.openRoll != null &&
                wire.resolutionsByPlayer != null &&
                wire.resolutionsByPlayer.size == wire.match.playerCount

        // "Fresh" must mean a new die roll started, not merely that the host still shows default
        // resolutions for every seat (the host often does not merge client crosses until Done).
        // Otherwise every game_state looks fresh, we skip sheet preservation, and marks vanish.
        val prevOpenRoll = prevResList?.firstOrNull()?.roll
        val isFreshRoll =
            openRollPhase &&
                wire.whitePhaseAcks.isEmpty() &&
                isFreshRollResolutionList(wire.openRoll!!, wire.resolutionsByPlayer!!) &&
                (prevOpenRoll == null || prevOpenRoll != wire.openRoll)

        val localIdx = _localPlayerIndex.value

        var mergedMatch = wire.match

        if (openRollPhase && !isFreshRoll) {

            val prevSh = prevMatch?.playerSheets?.getOrNull(localIdx)

            val wireSh = mergedMatch.playerSheets.getOrNull(localIdx)

            val rollerRemovedCross =
                localIdx != wire.match.activePlayerIndex &&
                    wire.activeCrossesThisRoll < prevRollerCrossesThisRoll

            if (prevSh != null && wireSh != null) {

                if (rollerRemovedCross || !wireLanSheetCoversPrev(prevSh, wireSh)) {

                    val sheets = mergedMatch.playerSheets.toMutableList()

                    sheets[localIdx] = prevSh

                    mergedMatch = mergedMatch.copy(playerSheets = sheets)

                }

            }

        }

        _match.value = mergedMatch

        wire.playerDisplayNames?.let { names ->

            if (names.size == wire.match.playerCount) {

                _lanPlayerDisplayNames.value = names

            }

        }

        if (wire.openRoll != null && wire.resolutionsByPlayer != null &&

            wire.resolutionsByPlayer.size == wire.match.playerCount) {

            val roll = wire.openRoll

            var list = wire.resolutionsByPlayer

            if (openRollPhase && !isFreshRoll && localIdx !in wire.whitePhaseAcks) {

                val pl = prevResList

                if (pl != null && localIdx in pl.indices && localIdx in list.indices &&

                    list[localIdx].roll == pl[localIdx].roll

                ) {

                    list = list.toMutableList().also { it[localIdx] = pl[localIdx] }

                }

            }

            val preserveLocalRollerCrosses = openRollPhase && !isFreshRoll &&

                localIdx == mergedMatch.activePlayerIndex &&

                localIdx !in wire.whitePhaseAcks

            if (isFreshRoll) {

                multiplayerRollStartSnapshot = mergedMatch.snapshot()

                clientLanUndoStack.clear()

                clientLanVoluntaryPenaltyUndo = null

            }

            _lastRoll.value = roll

            _resolutionByPlayer.value = list

            if (isFreshRoll || !preserveLocalRollerCrosses) {

                _crossesThisRoll.value = wire.activeCrossesThisRoll

            }

            _whitePhaseAcks.value = wire.whitePhaseAcks

            _lanLockReadyBySeat.value = wire.lockReadyBySeat

            if (localIdx in wire.whitePhaseAcks) {

                clientLanVoluntaryPenaltyUndo = null

                clientLanUndoStack.clear()

            }

        } else {

            _resolutionByPlayer.value = null

            _crossesThisRoll.value = 0

            _lastRoll.value = null

            _whitePhaseAcks.value = emptySet()

            _lanLockReadyBySeat.value = null

            clearLanUndoStacks()

        }

        singlePlayerUndoStack.clear()

        singlePlayerVoluntaryPenaltyUndo = null

        _diceRollAnimationSettled.value = true

        refreshLegalMoves()

        checkGameOverMulti(mergedMatch)

        maybeNotifyPenaltyThud(prevMatch, mergedMatch)

        maybeNotifyLanVoluntaryPenaltyUndoClient(prevMatch, mergedMatch)

        maybeNotifyLocalRowLockCelebration(prevMatch, mergedMatch)

    }



    private fun applyIncomingRoll(root: JSONObject) {

        if (_role.value != Role.Client) return

        val (roll, active) = GameMessageCodec.parseRoll(root)

        val state = _match.value ?: return

        if (active != state.activePlayerIndex) {

            appendLog("roll active idx mismatch (server $active local state ${state.activePlayerIndex})")

        }

        _diceRollAnimationSettled.value = false

        _lastRoll.value = roll

        _diceRollGeneration.update { it + 1 }

        openRollForMatch(roll, takeHostSnapshot = false)

        appendLog("roll recv white=${roll.whiteSum()}")

    }



    fun singlePlayerScore(): Int {

        val state = _match.value ?: return 0

        return LocXXRules.totalScore(state.playerSheets[state.activePlayerIndex])

    }



    private fun playerNamesForLanBroadcast(state: MatchState): List<String>? {

        if (_role.value != Role.Host) return null

        val n = state.playerCount

        return List(n) { seat ->

            when {

                seat == 0 -> _sessionDisplayName.value.ifBlank { "Player 1" }

                else -> _peers.value.find { it.playerId == seat }?.displayName?.ifBlank { null }

                    ?: "Player ${seat + 1}"

            }

        }

    }

    /** Host: one list entry per seat; derived from authoritative sheets plus client [remoteLockReadyHints]. */
    private fun lockReadyBySeatForBroadcast(authoritative: MatchState): List<Set<RowId>> =
        List(authoritative.playerCount) { i ->
            lockReadyRowsForSheet(authoritative.playerSheets[i]).union(
                remoteLockReadyHints[i] ?: emptySet()
            )
        }

    /** Client: tell host which rows have a pulsating lock cell on our sheet (for opponent headers while masked). */
    private fun sendLockReadyHintToHost() {
        if (_role.value != Role.Client) return
        if (_resolutionByPlayer.value == null) return
        val c = client ?: return
        val state = _match.value ?: return
        val idx = _localPlayerIndex.value
        val sheet = state.playerSheets.getOrNull(idx) ?: return
        val rows = lockReadyRowsForSheet(sheet)
        val ja = JSONArray()
        rows.sortedBy { it.ordinal }.forEach { ja.put(it.name) }
        val body = JSONObject().put("rows", ja)
        val payload = GameMessageCodec.encodeIntent(idx, "lock_ready", body)
        c.sendFrame(ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload))
    }

    private fun broadcastGameState(state: MatchState) {

        if (_role.value == Role.Host) {

            val open = _resolutionByPlayer.value

            _lanLockReadyBySeat.value =

                if (open != null && open.isNotEmpty()) {

                    lockReadyBySeatForBroadcast(state)

                } else {

                    null

                }

        }

        val outgoing = publicLanMatchForBroadcast(state)

        val wireNames = playerNamesForLanBroadcast(state)

        val h = host ?: return

        val list = _resolutionByPlayer.value

        val payload =

            if (list != null && list.isNotEmpty()) {

                val roll = list.first().roll

                GameMessageCodec.encodeGameState(

                    outgoing,

                    openRoll = roll,

                    resolutionsByPlayer = list,

                    activeCrossesThisRoll = _crossesThisRoll.value,

                    whitePhaseAcks = _whitePhaseAcks.value,

                    playerDisplayNames = wireNames,

                    lockReadyBySeat = lockReadyBySeatForBroadcast(state)

                )

            } else {

                GameMessageCodec.encodeGameState(

                    outgoing,

                    openRoll = null,

                    resolutionsByPlayer = null,

                    activeCrossesThisRoll = 0,

                    whitePhaseAcks = emptySet(),

                    playerDisplayNames = wireNames

                )

            }

        val frame = ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, payload)

        h.broadcast(frame)

    }

}

private fun locxxStripMdnsHost(host: String): String =
    host.trim().removePrefix("[").removeSuffix("]")

private fun locxxCollectLanIpv4HostStrings(): Set<String> {
    val out = mutableSetOf<String>()
    runCatching {
        val en = NetworkInterface.getNetworkInterfaces() ?: return out
        while (en.hasMoreElements()) {
            val ni = en.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress || a !is Inet4Address) continue
                a.hostAddress?.let { out.add(it) }
            }
        }
    }
    return out
}

private fun locxxParseIpv4ToInt(host: String): Int? {
    val h = locxxStripMdnsHost(host)
    val parts = h.split('.')
    if (parts.size != 4) return null
    var n = 0
    for (p in parts) {
        val o = p.toIntOrNull() ?: return null
        if (o !in 0..255) return null
        n = (n shl 8) or o
    }
    return n
}

/**
 * True if we should connect to [hostStr]:[port] as client (remote must not be us; tie-break so only one
 * side joins the other).
 */
private fun locxxShouldJoinRemoteLocxxHost(
    hostStr: String,
    port: Int,
    localIpv4: Set<String>,
): Boolean {
    if (port != LOCXX_LAN_PORT) return false
    val remoteRaw = locxxStripMdnsHost(hostStr)
    if (remoteRaw in localIpv4) return false
    val remoteInt = locxxParseIpv4ToInt(remoteRaw)
    val localInts = localIpv4.mapNotNull { locxxParseIpv4ToInt(it) }
    if (remoteInt != null && localInts.isNotEmpty()) {
        val localChosen = localInts.maxOrNull() ?: return true
        return remoteInt > localChosen
    }
    val localRef = localIpv4.minOrNull() ?: return true
    return remoteRaw > localRef
}

