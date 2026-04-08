package com.heroiclabs.nakama

import com.google.common.util.concurrent.ListenableFuture

/**
 * [SocketClient.createMatch] always sends an empty name; the server then picks a random relay id.
 * With a non-empty name, Nakama uses `uuidV5(DNS, name)` so clients can join via a short room code.
 *
 * Lives in this package so we can use package-private [MatchCreateMessage] / [WebSocketEnvelope];
 * [WebSocketClient.send] is still private, so one reflective call remains.
 */
fun SocketClient.createMatchNamed(roomKey: String): ListenableFuture<Match> {
    val ws = this as? WebSocketClient ?: error("nakama socket must be WebSocketClient")
    val msg = MatchCreateMessage()
    msg.name = roomKey
    val env = WebSocketEnvelope()
    env.matchCreate = msg
    val send = WebSocketClient::class.java.getDeclaredMethod("send", WebSocketEnvelope::class.java)
    send.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return send.invoke(ws, env) as ListenableFuture<Match>
}
