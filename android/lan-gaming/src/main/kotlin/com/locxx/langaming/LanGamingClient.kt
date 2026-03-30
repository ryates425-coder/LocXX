package com.locxx.langaming

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * HTTP client: POST [CLIENT_HELLO] to `/locxx/v1/hello`, then long-poll `/locxx/v1/poll` and POST
 * frames to `/locxx/v1/send` (see docs/protocol.md).
 */
class LanGamingClient(
    private val listener: LanGamingListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null

    var displayName: String = "Player"

    private var baseUrl: String? = null
    private var token: String? = null

    fun connect(hostBaseUrl: String) {
        disconnect()
        val normalized = hostBaseUrl.trimEnd('/')
        baseUrl = normalized
        val name = displayName
        runJob = scope.launch(Dispatchers.IO) {
            try {
                helloAndRun(normalized, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onErrorUi(e.message ?: "client")
            }
        }
    }

    private suspend fun helloAndRun(base: String, name: String) {
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hello = ProtocolCodec.buildClientHello(WIRE_VERSION, name, nonce)
        val conn = (URL("$base/locxx/v1/hello").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/octet-stream")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        conn.outputStream.use { it.write(hello) }
        val code = conn.responseCode
        if (code != 200) {
            onErrorUi("hello http $code")
            conn.disconnect()
            return
        }
        val tok = conn.getHeaderField(LOCXX_HTTP_TOKEN_HEADER) ?: run {
            onErrorUi("missing token")
            conn.disconnect()
            return
        }
        val welcome = conn.inputStream.use { it.readBytes() }
        conn.disconnect()

        val decoded = ProtocolCodec.decodeFrame(welcome).getOrElse {
            onErrorUi("welcome decode")
            return
        }
        if (decoded.messageType != WireMessageType.HOST_WELCOME) {
            onErrorUi("expected welcome")
            return
        }
        val w = ProtocolCodec.parseHostWelcome(decoded.payload).getOrElse {
            onErrorUi("welcome parse")
            return
        }
        token = tok
        onPeerConnectedUi(tok, w.playerId.toInt(), name)

        while (coroutineContext.isActive && token == tok) {
            val pollConn = (URL(
                "$base/locxx/v1/poll?token=${
                    URLEncoder.encode(
                        tok,
                        Charsets.UTF_8.name()
                    )
                }"
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 120_000
            }
            val pollCode = try {
                pollConn.responseCode
            } catch (e: Exception) {
                onErrorUi(e.message ?: "poll")
                pollConn.disconnect()
                break
            }
            if (pollCode == HttpURLConnection.HTTP_NO_CONTENT) {
                pollConn.disconnect()
                continue
            }
            if (pollCode != 200) {
                onErrorUi("poll http $pollCode")
                pollConn.disconnect()
                break
            }
            val body = pollConn.inputStream.use { it.readBytes() }
            pollConn.disconnect()
            if (body.isEmpty()) continue
            val pollResult = ProtocolCodec.decodeFrame(body)
            if (pollResult.isFailure) {
                onErrorUi("poll decode")
                continue
            }
            val pollDecoded = pollResult.getOrThrow()
            when (pollDecoded.messageType) {
                WireMessageType.PING -> sendFrame(ProtocolCodec.encodeFrame(WireMessageType.PONG))
                else -> onMessageReceivedUi(tok, pollDecoded.messageType, pollDecoded.payload)
            }
        }
        token = null
        baseUrl = null
        if (coroutineContext.isActive) {
            onPeerDisconnectedUi(tok)
        }
    }

    fun sendFrame(frame: ByteArray) {
        val b = baseUrl ?: return
        val tok = token ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val conn = (URL(
                    "$b/locxx/v1/send?token=${
                        URLEncoder.encode(
                            tok,
                            Charsets.UTF_8.name()
                        )
                    }"
                ).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/octet-stream")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 30_000
                }
                conn.outputStream.use { it.write(frame) }
                val c = conn.responseCode
                conn.disconnect()
                if (c != HttpURLConnection.HTTP_NO_CONTENT) {
                    onErrorUi("send http $c")
                }
            }.onFailure { e ->
                onErrorUi(e.message ?: "send")
            }
        }
    }

    fun disconnect() {
        val t = token
        runJob?.cancel()
        runJob = null
        token = null
        baseUrl = null
        if (t != null) {
            mainHandler.post { listener.onPeerDisconnected(t) }
        }
    }

    private fun onPeerConnectedUi(addr: String, pid: Int, name: String) {
        mainHandler.post { listener.onPeerConnected(addr, pid, name) }
    }

    private fun onPeerDisconnectedUi(addr: String) {
        mainHandler.post { listener.onPeerDisconnected(addr) }
    }

    private fun onMessageReceivedUi(addr: String, type: Byte, payload: ByteArray) {
        mainHandler.post { listener.onMessageReceived(addr, type, payload) }
    }

    private fun onErrorUi(msg: String) {
        mainHandler.post { listener.onError(msg) }
    }
}
