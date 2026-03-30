package com.locxx.langaming

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object LocXXBleUuids {
    val SERVICE: UUID = UUID.fromString("A0B40001-9267-4521-8F90-ACCD260FF718")
    val CLIENT_TO_HOST: UUID = UUID.fromString("A0B40002-9267-4521-8F90-ACCD260FF718")
    val HOST_TO_CLIENT: UUID = UUID.fromString("A0B40003-9267-4521-8F90-ACCD260FF718")
}

object WireMessageType {
    const val CLIENT_HELLO: Byte = 0x01
    const val HOST_WELCOME: Byte = 0x02
    const val ACK: Byte = 0x03
    const val PING: Byte = 0x04
    const val PONG: Byte = 0x05
    const val APP_PAYLOAD: Byte = 0x10
}

const val WIRE_VERSION: Byte = 0x01
const val MAX_FRAME_PAYLOAD = 4096

const val LOCXX_LAN_PORT = 28765

object ProtocolCodec {

    fun encodeFrame(messageType: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_FRAME_PAYLOAD) { "payload too large" }
        return ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(WIRE_VERSION)
            put(messageType)
            putShort(payload.size.toShort())
            put(payload)
        }.array()
    }

    fun decodeFrame(frame: ByteArray): Result<DecodedFrame> {
        if (frame.size < 4) return Result.failure(IllegalArgumentException("frame too short"))
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val ver = buf.get()
        if (ver != WIRE_VERSION) return Result.failure(IllegalArgumentException("bad wire version"))
        val type = buf.get()
        val len = buf.short.toInt() and 0xFFFF
        if (len > MAX_FRAME_PAYLOAD) return Result.failure(IllegalArgumentException("bad length"))
        if (frame.size != 4 + len) return Result.failure(IllegalArgumentException("size mismatch"))
        val payload = ByteArray(len)
        buf.get(payload)
        return Result.success(DecodedFrame(ver, type, payload))
    }

    fun buildClientHello(protocolVersion: Byte, displayName: String, clientNonce: ByteArray): ByteArray {
        require(clientNonce.size == 16)
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 64)
        val payload = ByteBuffer.allocate(1 + 1 + nameBytes.size + 16).apply {
            put(protocolVersion)
            put(nameBytes.size.toByte())
            put(nameBytes)
            put(clientNonce)
        }.array()
        return encodeFrame(WireMessageType.CLIENT_HELLO, payload)
    }

    fun parseClientHello(payload: ByteArray): Result<ClientHelloPayload> {
        if (payload.size < 1 + 1 + 16) return Result.failure(IllegalArgumentException("hello too short"))
        val pv = payload[0]
        val nl = payload[1].toInt() and 0xFF
        if (2 + nl + 16 != payload.size) return Result.failure(IllegalArgumentException("hello name"))
        val name = String(payload, 2, nl, Charsets.UTF_8)
        val nonce = payload.copyOfRange(2 + nl, 2 + nl + 16)
        return Result.success(ClientHelloPayload(pv, name, nonce))
    }

    fun buildHostWelcome(protocolVersion: Byte, playerId: Byte, sessionNonce: ByteArray): ByteArray {
        require(sessionNonce.size == 16)
        val payload = ByteBuffer.allocate(1 + 1 + 16).apply {
            put(protocolVersion)
            put(playerId)
            put(sessionNonce)
        }.array()
        return encodeFrame(WireMessageType.HOST_WELCOME, payload)
    }

    fun parseHostWelcome(payload: ByteArray): Result<HostWelcomePayload> {
        if (payload.size != 18) return Result.failure(IllegalArgumentException("welcome size"))
        return Result.success(
            HostWelcomePayload(
                protocolVersion = payload[0],
                playerId = payload[1],
                sessionNonce = payload.copyOfRange(2, 18)
            )
        )
    }

    fun buildAck(acknowledgedMessageType: Byte, correlationId: Int): ByteArray {
        val p = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).apply {
            put(acknowledgedMessageType)
            putInt(correlationId)
        }.array()
        return encodeFrame(WireMessageType.ACK, p)
    }

    fun parseAck(payload: ByteArray): Result<AckPayload> {
        if (payload.size != 5) return Result.failure(IllegalArgumentException("ack size"))
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val mt = buf.get()
        val cid = buf.int
        return Result.success(AckPayload(mt, cid))
    }
}

data class DecodedFrame(
    val wireVersion: Byte,
    val messageType: Byte,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedFrame
        if (wireVersion != other.wireVersion) return false
        if (messageType != other.messageType) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = wireVersion.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class ClientHelloPayload(
    val protocolVersion: Byte,
    val displayName: String,
    val clientNonce: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClientHelloPayload
        if (protocolVersion != other.protocolVersion) return false
        if (displayName != other.displayName) return false
        if (!clientNonce.contentEquals(other.clientNonce)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = protocolVersion.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + clientNonce.contentHashCode()
        return result
    }
}

data class HostWelcomePayload(
    val protocolVersion: Byte,
    val playerId: Byte,
    val sessionNonce: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HostWelcomePayload
        if (protocolVersion != other.protocolVersion) return false
        if (playerId != other.playerId) return false
        if (!sessionNonce.contentEquals(other.sessionNonce)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = protocolVersion.hashCode()
        result = 31 * result + playerId.hashCode()
        result = 31 * result + sessionNonce.contentHashCode()
        return result
    }
}

data class AckPayload(val acknowledgedMessageType: Byte, val correlationId: Int)
