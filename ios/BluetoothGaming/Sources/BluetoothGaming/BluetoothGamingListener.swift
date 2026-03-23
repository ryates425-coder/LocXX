import Foundation

public protocol BluetoothGamingListener: AnyObject {
    func onPeerConnected(address: String, playerId: Int, displayName: String)
    func onPeerDisconnected(address: String)
    func onMessageReceived(address: String, messageType: UInt8, payload: Data)
    func onError(_ message: String)
}

public extension BluetoothGamingListener {
    func onPeerConnected(address: String, playerId: Int, displayName: String) {}
    func onPeerDisconnected(address: String) {}
    func onMessageReceived(address: String, messageType: UInt8, payload: Data) {}
    func onError(_ message: String) {}
}
