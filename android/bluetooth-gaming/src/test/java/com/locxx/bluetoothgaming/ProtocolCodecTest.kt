package com.locxx.bluetoothgaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val p = byteArrayOf(1, 2, 3)
        val f = ProtocolCodec.encodeFrame(WireMessageType.APP_PAYLOAD, p)
        val d = ProtocolCodec.decodeFrame(f).getOrThrow()
        assertEquals(WIRE_VERSION, d.wireVersion)
        assertEquals(WireMessageType.APP_PAYLOAD, d.messageType)
        assertArrayEquals(p, d.payload)
    }

    @Test
    fun clientHelloRoundTrip() {
        val nonce = ByteArray(16) { it.toByte() }
        val frame = ProtocolCodec.buildClientHello(WIRE_VERSION, "Alice", nonce)
        val decoded = ProtocolCodec.decodeFrame(frame).getOrThrow()
        assertEquals(WireMessageType.CLIENT_HELLO, decoded.messageType)
        val hello = ProtocolCodec.parseClientHello(decoded.payload).getOrThrow()
        assertEquals("Alice", hello.displayName)
        assertArrayEquals(nonce, hello.clientNonce)
    }

    @Test
    fun hostWelcomeRoundTrip() {
        val sn = ByteArray(16) { 7 }
        val frame = ProtocolCodec.buildHostWelcome(WIRE_VERSION, 3.toByte(), sn)
        val decoded = ProtocolCodec.decodeFrame(frame).getOrThrow()
        val w = ProtocolCodec.parseHostWelcome(decoded.payload).getOrThrow()
        assertEquals(3, w.playerId.toInt() and 0xFF)
        assertArrayEquals(sn, w.sessionNonce)
    }

}
