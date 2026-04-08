package com.locxx.langaming

import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

const val LOCXX_HTTP_TOKEN_HEADER = "X-LocXX-Token"

/** Socket read timeout (ms) passed to [NanoHTTPD.start]; same default as NanoHTTPD (5 min). */
private const val LOCXX_NANOHTTPD_READ_TIMEOUT_MS = 5 * 60 * 1000

/**
 * HTTP host on [LOCXX_LAN_PORT]: handshake and long-poll transport for the same wire frames as BLE
 * (see [ProtocolCodec] / docs/protocol.md).
 */
class LanGamingHost(
    private val listener: LanGamingListener
) : NanoHTTPD(LOCXX_LAN_PORT) {

    /**
     * Never gzip responses. Android’s [HttpURLConnection] often sends Accept-Encoding: gzip; NanoHTTPD
     * would then use chunked Transfer-Encoding + [GZIPOutputStream] even for short `text/plain` bodies
     * (e.g. long-poll 204). If the client closes the socket first (timeout, app backgrounded, network
     * change), writing gzip headers fails with [SocketException] and spams “Could not send response”.
     * Binary game frames are tiny; gzip is unnecessary here.
     */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    /** Shown to joining players before they connect ([serveSessionInfo]). */
    @Volatile
    var advertisedHostDisplayName: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionNonce: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    private val nextPlayerId = AtomicInteger(1)
    private val tokenToPeer = ConcurrentHashMap<String, Peer>()

    private data class Peer(
        val token: String,
        val playerId: Int,
        val displayName: String,
        val outbound: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue()
    )

    fun sessionNonceBytes(): ByteArray = sessionNonce.copyOf()

    fun beginHosting(): Boolean =
        try {
            start(LOCXX_NANOHTTPD_READ_TIMEOUT_MS, false)
            true
        } catch (e: Exception) {
            listener.onErrorUi("start: ${e.message ?: "failed"}")
            false
        }

    fun endHosting() {
        stop()
        tokenToPeer.clear()
        nextPlayerId.set(1)
    }

    fun broadcast(frame: ByteArray) {
        val data = frame.copyOf()
        for (p in tokenToPeer.values) {
            p.outbound.offer(data.copyOf())
        }
    }

    fun sendToPeer(addressToken: String, frame: ByteArray) {
        tokenToPeer[addressToken]?.outbound?.offer(frame.copyOf())
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.substringBefore('?').let { p ->
            if (p.startsWith("/")) p else "/$p"
        }
        return try {
            when (session.method) {
                Method.POST -> when (path) {
                    "/locxx/v1/hello" -> serveHello(session)
                    "/locxx/v1/send" -> serveSend(session)
                    else -> notFound()
                }
                Method.GET -> when (path) {
                    "/locxx/v1/poll" -> servePoll(session)
                    "/locxx/v1/session_info" -> serveSessionInfo()
                    else -> notFound()
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                e.message ?: "error"
            )
        }
    }

    private fun serveHello(session: IHTTPSession): Response {
        val body = readRequestBody(session)
        val decoded = ProtocolCodec.decodeFrame(body).getOrElse {
            return text(Response.Status.BAD_REQUEST, it.message ?: "frame")
        }
        if (decoded.messageType != WireMessageType.CLIENT_HELLO) {
            return text(Response.Status.BAD_REQUEST, "expected hello")
        }
        val hello = ProtocolCodec.parseClientHello(decoded.payload).getOrElse {
            return text(Response.Status.BAD_REQUEST, it.message ?: "hello")
        }
        if (hello.protocolVersion != WIRE_VERSION) {
            return text(Response.Status.BAD_REQUEST, "protocol mismatch")
        }
        val pid = nextPlayerId.getAndIncrement()
        if (pid > 8) {
            nextPlayerId.decrementAndGet()
            return text(Response.Status.BAD_REQUEST, "room full")
        }
        val token = UUID.randomUUID().toString()
        tokenToPeer[token] = Peer(token = token, playerId = pid, displayName = hello.displayName)
        val welcome = ProtocolCodec.buildHostWelcome(WIRE_VERSION, pid.toByte(), sessionNonce)
        listener.onPeerConnectedUi(token, pid, hello.displayName)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            ByteArrayInputStream(welcome),
            welcome.size.toLong()
        ).apply { addHeader(LOCXX_HTTP_TOKEN_HEADER, token) }
    }

    private fun serveSend(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return text(Response.Status.BAD_REQUEST, "token")
        val peer = tokenToPeer[token] ?: return text(Response.Status.NOT_FOUND, "unknown token")
        val body = readRequestBody(session)
        val decoded = ProtocolCodec.decodeFrame(body).getOrElse {
            listener.onErrorUi(it.message ?: "decode")
            return text(Response.Status.BAD_REQUEST, "decode")
        }
        when (decoded.messageType) {
            WireMessageType.PING -> {
                peer.outbound.offer(ProtocolCodec.encodeFrame(WireMessageType.PONG))
            }
            else -> {
                listener.onMessageReceivedUi(token, decoded.messageType, decoded.payload)
            }
        }
        return newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, "")
    }

    private fun servePoll(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return text(Response.Status.BAD_REQUEST, "token")
        val peer = tokenToPeer[token] ?: return text(Response.Status.NOT_FOUND, "unknown token")
        val frame = peer.outbound.poll(55, TimeUnit.SECONDS)
        return if (frame == null) {
            newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, "")
        } else {
            newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(frame),
                frame.size.toLong()
            )
        }
    }

    private fun notFound(): Response = text(Response.Status.NOT_FOUND, "not found")

    private fun serveSessionInfo(): Response {
        val json = JSONObject().apply {
            put("hostDisplayName", advertisedHostDisplayName)
        }
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    }

    private fun text(status: Response.Status, msg: String): Response =
        newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, msg)

    private fun readRequestBody(session: IHTTPSession): ByteArray {
        val rawLen = session.headers["content-length"] ?: return ByteArray(0)
        val len = rawLen.toIntOrNull() ?: 0
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len)
        var off = 0
        val input = session.inputStream
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    private fun LanGamingListener.onPeerConnectedUi(token: String, pid: Int, name: String) {
        mainHandler.post { onPeerConnected(token, pid, name) }
    }

    private fun LanGamingListener.onMessageReceivedUi(token: String, type: Byte, payload: ByteArray) {
        mainHandler.post { onMessageReceived(token, type, payload) }
    }

    private fun LanGamingListener.onErrorUi(msg: String) {
        mainHandler.post { onError(msg) }
    }
}
