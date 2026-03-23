import CoreBluetooth
import Foundation

/// BLE peripheral hosting a LocXX session (see `docs/protocol.md`).
public final class BluetoothGamingHost: NSObject {
    private let listener: BluetoothGamingListener
    private var peripheralManager: CBPeripheralManager!
    private var service: CBMutableService!
    private var hostToClient: CBMutableCharacteristic!
    private var clientToHost: CBMutableCharacteristic!

    private let sessionNonce = Data((0 ..< 16).map { _ in UInt8.random(in: 0 ... 255) })
    private var nextPlayerId = 1
    private var addressToPeer: [String: (playerId: Int, displayName: String)] = [:]
    private var subscribedCentrals: [CBCentral] = []
    private var pendingStart = false

    public init(listener: BluetoothGamingListener) {
        self.listener = listener
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    }

    public func sessionNonceData() -> Data { sessionNonce }

    public func start() {
        if peripheralManager.state == .poweredOn {
            beginAdvertising()
        } else {
            pendingStart = true
        }
    }

    private func beginAdvertising() {
        buildService()
        peripheralManager.removeAllServices()
        peripheralManager.add(service)
        let data: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "LocXX-Host",
            CBAdvertisementDataServiceUUIDsKey: [LocXXBleUUIDs.service],
        ]
        peripheralManager.startAdvertising(data)
    }

    public func stop() {
        pendingStart = false
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        addressToPeer.removeAll()
        subscribedCentrals.removeAll()
        nextPlayerId = 1
    }

    public func sendToPeer(deviceAddress: String, frame: Data) {
        guard let central = subscribedCentrals.first(where: { $0.identifier.uuidString == deviceAddress }) else { return }
        notify(central: central, data: frame)
    }

    public func broadcast(_ frame: Data) {
        for c in subscribedCentrals {
            notify(central: c, data: frame)
        }
    }

    private func notify(central: CBCentral, data: Data) {
        hostToClient?.value = data
        _ = peripheralManager.updateValue(data, for: hostToClient, onSubscribedCentrals: [central])
    }

    private func buildService() {
        let c2h = CBMutableCharacteristic(
            type: CBUUID(nsuuid: LocXXBleUUIDs.clientToHost),
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let h2c = CBMutableCharacteristic(
            type: CBUUID(nsuuid: LocXXBleUUIDs.hostToClient),
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        clientToHost = c2h
        hostToClient = h2c
        let svc = CBMutableService(type: CBUUID(nsuuid: LocXXBleUUIDs.service), primary: true)
        svc.characteristics = [c2h, h2c]
        service = svc
    }

    private func handleWrite(central: CBCentral, data: Data) {
        let decoded: DecodedFrame
        do {
            decoded = try ProtocolCodec.decodeFrame(data)
        } catch {
            listener.onError("decode")
            return
        }
        let addr = central.identifier.uuidString
        switch decoded.messageType {
        case WireMessageType.clientHello:
            do {
                let hello = try ProtocolCodec.parseClientHello(decoded.payload)
                guard hello.protocolVersion == wireVersion else {
                    listener.onError("protocol mismatch")
                    return
                }
                guard nextPlayerId <= 8 else {
                    listener.onError("room full")
                    return
                }
                let pid = nextPlayerId
                nextPlayerId += 1
                addressToPeer[addr] = (pid, hello.displayName)
                let welcome = try ProtocolCodec.buildHostWelcome(
                    protocolVersion: wireVersion,
                    playerId: UInt8(pid),
                    sessionNonce: sessionNonce
                )
                notify(central: central, data: welcome)
                listener.onPeerConnected(address: addr, playerId: pid, displayName: hello.displayName)
            } catch {
                listener.onError("hello")
            }
        case WireMessageType.ping:
            notify(central: central, data: ProtocolCodec.encodeFrame(messageType: WireMessageType.pong))
        default:
            listener.onMessageReceived(address: addr, messageType: decoded.messageType, payload: decoded.payload)
        }
    }
}

extension BluetoothGamingHost: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            if pendingStart {
                pendingStart = false
                beginAdvertising()
            }
        case .unknown, .resetting:
            break
        case .unsupported, .unauthorized, .poweredOff:
            listener.onError("Bluetooth unavailable (\(peripheral.state.rawValue))")
        @unknown default:
            break
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for req in requests {
            if req.characteristic.uuid == CBUUID(nsuuid: LocXXBleUUIDs.clientToHost), let data = req.value, let central = req.central {
                handleWrite(central: central, data: data)
            }
            peripheral.respond(to: req, withResult: .success)
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        if characteristic.uuid == CBUUID(nsuuid: LocXXBleUUIDs.hostToClient) {
            if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
                subscribedCentrals.append(central)
            }
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        let addr = central.identifier.uuidString
        addressToPeer.removeValue(forKey: addr)
        listener.onPeerDisconnected(address: addr)
    }
}
