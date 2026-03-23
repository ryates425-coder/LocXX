package com.locxx.bluetoothgaming

interface BluetoothGamingListener {
    /** Every BLE advertisement while scanning (for manual host selection when UUID filtering fails). */
    fun onBleScanCandidate(address: String, name: String?, rssi: Int) {}

    fun onPeerConnected(address: String, playerId: Int, displayName: String) {}
    fun onPeerDisconnected(address: String) {}
    fun onMessageReceived(address: String, messageType: Byte, payload: ByteArray) {}
    fun onError(message: String) {}
}
