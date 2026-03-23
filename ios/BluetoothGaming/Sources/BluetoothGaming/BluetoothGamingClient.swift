import CoreBluetooth
import Foundation
import Security

/// BLE central that joins a LocXX host.
public final class BluetoothGamingClient: NSObject {
    private let listener: BluetoothGamingListener
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var hostToClient: CBCharacteristic?
    private var clientToHost: CBCharacteristic?
    private var connectedAddress: String?
    private var helloSent = false
    private var pendingScan = false

    public init(listener: BluetoothGamingListener) {
        self.listener = listener
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    public var displayName: String = "Player"

    public func startScan() {
        if central.state == .poweredOn {
            beginScan()
        } else {
            pendingScan = true
        }
    }

    private func beginScan() {
        central.scanForPeripherals(withServices: [CBUUID(nsuuid: LocXXBleUUIDs.service)], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false,
        ])
    }

    public func stopScan() {
        central?.stopScan()
    }

    public func connect(_ peripheral: CBPeripheral) {
        stopScan()
        helloSent = false
        self.peripheral = peripheral
        peripheral.delegate = self
        connectedAddress = peripheral.identifier.uuidString
        central.connect(peripheral, options: nil)
    }

    public func sendFrame(_ frame: Data) {
        guard let p = peripheral, let c2h = clientToHost else { return }
        p.writeValue(frame, for: c2h, type: .withResponse)
    }

    public func disconnect() {
        stopScan()
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        connectedAddress = nil
        hostToClient = nil
        clientToHost = nil
    }

    private func sendHello() {
        var nonce = Data(count: 16)
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard let hello = try? ProtocolCodec.buildClientHello(
            protocolVersion: wireVersion,
            displayName: displayName,
            clientNonce: nonce
        ) else { return }
        sendFrame(hello)
    }

    private func handleNotification(_ data: Data) {
        let decoded: DecodedFrame
        do {
            decoded = try ProtocolCodec.decodeFrame(data)
        } catch {
            listener.onError("decode")
            return
        }
        let addr = connectedAddress ?? ""
        switch decoded.messageType {
        case WireMessageType.hostWelcome:
            do {
                let w = try ProtocolCodec.parseHostWelcome(decoded.payload)
                listener.onPeerConnected(
                    address: addr,
                    playerId: Int(w.playerId),
                    displayName: displayName
                )
            } catch {
                listener.onError("welcome")
            }
        case WireMessageType.ping:
            sendFrame(ProtocolCodec.encodeFrame(messageType: WireMessageType.pong))
        default:
            listener.onMessageReceived(address: addr, messageType: decoded.messageType, payload: decoded.payload)
        }
    }
}

extension BluetoothGamingClient: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn, pendingScan {
            pendingScan = false
            beginScan()
        }
        switch central.state {
        case .unknown, .resetting:
            break
        case .poweredOn:
            break
        case .unsupported, .unauthorized, .poweredOff:
            listener.onError("Bluetooth unavailable (\(central.state.rawValue))")
        @unknown default:
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        connect(peripheral)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([CBUUID(nsuuid: LocXXBleUUIDs.service)])
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let addr = connectedAddress ?? ""
        connectedAddress = nil
        listener.onPeerDisconnected(address: addr)
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        listener.onError(error?.localizedDescription ?? "connect failed")
    }
}

extension BluetoothGamingClient: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let s = peripheral.services?.first else {
            listener.onError("services")
            return
        }
        peripheral.discoverCharacteristics(
            [CBUUID(nsuuid: LocXXBleUUIDs.clientToHost), CBUUID(nsuuid: LocXXBleUUIDs.hostToClient)],
            for: s
        )
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            listener.onError("characteristics")
            return
        }
        for c in service.characteristics ?? [] {
            if c.uuid == CBUUID(nsuuid: LocXXBleUUIDs.hostToClient) {
                hostToClient = c
                peripheral.setNotifyValue(true, for: c)
            }
            if c.uuid == CBUUID(nsuuid: LocXXBleUUIDs.clientToHost) {
                clientToHost = c
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil else { return }
        if characteristic.isNotifying, characteristic.uuid == CBUUID(nsuuid: LocXXBleUUIDs.hostToClient), !helloSent {
            helloSent = true
            sendHello()
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let data = characteristic.value else { return }
        if characteristic.uuid == CBUUID(nsuuid: LocXXBleUUIDs.hostToClient) {
            handleNotification(data)
        }
    }
}
