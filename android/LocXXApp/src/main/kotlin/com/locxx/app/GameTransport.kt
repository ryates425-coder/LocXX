package com.locxx.app

/**
 * Outbound [GameMessageCodec] payloads. LAN wraps with [com.locxx.langaming.WireMessageType.APP_PAYLOAD];
 * Nakama sends on match opcode [com.locxx.app.nakama.LOCXX_NAKAMA_OP_APP].
 */
fun interface GameTransport {
    fun publishAppPayload(payload: ByteArray)
}
