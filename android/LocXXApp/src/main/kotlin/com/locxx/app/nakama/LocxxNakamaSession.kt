package com.locxx.app.nakama

import android.os.Handler
import com.heroiclabs.nakama.AbstractSocketListener
import com.heroiclabs.nakama.Client
import com.heroiclabs.nakama.DefaultClient
import com.heroiclabs.nakama.Match
import com.heroiclabs.nakama.MatchData
import com.heroiclabs.nakama.MatchPresenceEvent
import com.heroiclabs.nakama.Session
import com.heroiclabs.nakama.SocketClient
import com.heroiclabs.nakama.createMatchNamed
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** App-defined match opcode: UTF-8 payload is [GameMessageCodec] app JSON (`v`/`kind`/`body`). */
const val LOCXX_NAKAMA_OP_APP: Long = 1

/**
 * Minimal Nakama relay client: device auth, create/join match, [sendAppPayload], presence tracking.
 */
class LocxxNakamaSession(
    private val serverKey: String,
    host: String,
    port: Int,
    useSsl: Boolean,
    private val deviceId: String,
    private val mainHandler: Handler,
    private val onError: (String) -> Unit,
    /** All participants receive match data; [senderUserId] is the author. */
    private val onAppPayload: (senderUserId: String, data: ByteArray) -> Unit,
    /** Current distinct user ids in the match (best-effort, updated on presence events). */
    private val onPresenceUserIds: (List<String>) -> Unit,
) {
    private val conn: NakamaConnectionParams = normalizeNakamaConnection(host, port, useSsl)

    @Volatile
    private var client: Client? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var socket: SocketClient? = null

    @Volatile
    var matchId: String? = null
        private set

    val presenceUserIds: MutableSet<String> =
        Collections.synchronizedSet(linkedSetOf())

    @Volatile
    var selfUserId: String = ""
        private set

    private val closed = AtomicBoolean(false)

    /**
     * Ensures host UI callback runs at most once after the relay match id is known (create response
     * or presence fallback). The callback receives [hostShareCode] when set (short code); otherwise
     * the raw match id.
     */
    private val matchIdPublishOnce = AtomicBoolean(false)

    @Volatile
    private var hostShareCode: String? = null

    private fun publishMatchIdOnce(mid: String, onRoomReady: (String) -> Unit) {
        val trimmed = mid.trim()
        if (trimmed.isEmpty() || !matchIdPublishOnce.compareAndSet(false, true)) return
        matchId = trimmed
        hostOnReadyRef = null
        val share = hostShareCode?.takeIf { it.isNotBlank() } ?: trimmed
        mainHandler.post { onRoomReady(share) }
    }

    @Volatile
    private var hostOnReadyRef: ((String) -> Unit)? = null

    private val listener = object : AbstractSocketListener() {
        override fun onMatchData(matchData: MatchData) {
            if (matchData.opCode != LOCXX_NAKAMA_OP_APP) return
            val uid = matchData.presence?.userId ?: return
            val data = matchData.data ?: return
            mainHandler.post { onAppPayload(uid, data) }
        }

        override fun onMatchPresence(matchPresence: MatchPresenceEvent) {
            val hostCb = hostOnReadyRef
            if (hostCb != null) {
                publishMatchIdOnce(matchPresence.matchId, hostCb)
            }
            synchronized(presenceUserIds) {
                matchPresence.joins?.forEach { p ->
                    p.userId?.let { presenceUserIds.add(it) }
                }
                matchPresence.leaves?.forEach { p ->
                    p.userId?.let { presenceUserIds.remove(it) }
                }
                val snap = presenceUserIds.toList()
                mainHandler.post { onPresenceUserIds(snap) }
            }
        }

        override fun onDisconnect(t: Throwable?) {
            if (t != null && !closed.get()) {
                mainHandler.post { onError(t.message ?: "nakama disconnect") }
            }
        }

        override fun onError(error: com.heroiclabs.nakama.Error) {
            if (!closed.get()) {
                mainHandler.post { onError(error.message ?: "nakama socket error") }
            }
        }
    }

    /**
     * @param onRoomReady Invoked on the main thread with the **short room code** (6 letters/digits) once
     * the relay match exists. The real Nakama match id stays in [matchId].
     */
    fun startHost(displayName: String, onRoomReady: (roomCode: String) -> Unit) {
        matchIdPublishOnce.set(false)
        hostShareCode = null
        hostOnReadyRef = onRoomReady
        thread(name = "locxx-nakama-host") {
            try {
                if (closed.get()) return@thread
                val c = DefaultClient(serverKey, conn.host, conn.port, conn.useSsl)
                client = c
                val sess = authenticateDeviceSessionRest(
                    conn.host,
                    conn.port,
                    conn.useSsl,
                    serverKey,
                    deviceId,
                    displayName.ifBlank { "Player" }.trim(),
                )
                session = sess
                selfUserId = sess.userId
                // DefaultClient.createSocket() uses port 7350; Azure/single-ingress setups use the same
                // public port as gRPC (e.g. 443). Must match [conn.port] or /ws gets a bad host:port → 404 etc.
                val sock = c.createSocket(conn.port)
                socket = sock
                sock.connect(sess, listener).get()
                val roomCode = generateLocxxNakamaRoomCode()
                hostShareCode = roomCode
                val roomKey = locxxNakamaRoomKeyFromCode(roomCode)
                val created = sock.createMatchNamed(roomKey).get()
                publishMatchIdOnce(created.matchId, onRoomReady)
                synchronized(presenceUserIds) {
                    presenceUserIds.clear()
                    presenceUserIds.add(selfUserId)
                }
                mainHandler.post { onPresenceUserIds(presenceUserIds.toList()) }
            } catch (e: Exception) {
                hostOnReadyRef = null
                hostShareCode = null
                if (!closed.get()) mainHandler.post { onError(e.message ?: "nakama host failed") }
            }
        }
    }

    fun startClient(displayName: String, matchIdToJoin: String, onReady: () -> Unit) {
        thread(name = "locxx-nakama-client") {
            try {
                if (closed.get()) return@thread
                val c = DefaultClient(serverKey, conn.host, conn.port, conn.useSsl)
                client = c
                val sess = authenticateDeviceSessionRest(
                    conn.host,
                    conn.port,
                    conn.useSsl,
                    serverKey,
                    deviceId,
                    displayName.ifBlank { "Player" }.trim(),
                )
                session = sess
                selfUserId = sess.userId
                val sock = c.createSocket(conn.port)
                socket = sock
                sock.connect(sess, listener).get()
                matchId = matchIdToJoin
                sock.joinMatch(matchIdToJoin).get()
                synchronized(presenceUserIds) {
                    presenceUserIds.add(selfUserId)
                }
                mainHandler.post { onPresenceUserIds(presenceUserIds.toList()) }
                mainHandler.post { onReady() }
            } catch (e: Exception) {
                if (!closed.get()) mainHandler.post { onError(e.message ?: "nakama join failed") }
            }
        }
    }

    fun sendAppPayload(data: ByteArray) {
        val mid = matchId ?: return
        val sock = socket ?: return
        thread(name = "locxx-nakama-send") {
            try {
                if (!closed.get()) sock.sendMatchData(mid, LOCXX_NAKAMA_OP_APP, data)
            } catch (e: Exception) {
                if (!closed.get()) mainHandler.post { onError(e.message ?: "nakama send") }
            }
        }
    }

    fun close() {
        closed.set(true)
        hostOnReadyRef = null
        hostShareCode = null
        runCatching {
            val mid = matchId
            val sock = socket
            if (mid != null && sock != null) {
                sock.leaveMatch(mid).get()
            }
        }
        runCatching { socket?.disconnectSocket() }
        socket = null
        session = null
        client = null
        matchId = null
        synchronized(presenceUserIds) { presenceUserIds.clear() }
    }
}
