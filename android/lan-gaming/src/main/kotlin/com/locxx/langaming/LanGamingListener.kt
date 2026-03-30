package com.locxx.langaming

interface LanGamingListener {
    fun onPeerConnected(address: String, playerId: Int, displayName: String) {}
    fun onPeerDisconnected(address: String) {}
    fun onMessageReceived(address: String, messageType: Byte, payload: ByteArray) {}
    fun onError(message: String) {}
}
