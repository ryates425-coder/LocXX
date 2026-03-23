import BluetoothGaming
import Combine
import Foundation
import LocXXRules

final class LocXXViewModel: ObservableObject {
    enum Role {
        case host, client
    }

    @Published var log: [String] = []
    @Published var peers: [(address: String, playerId: Int, displayName: String)] = []
    @Published var match: MatchState?
    @Published var lastRoll: DiceRoll?
    @Published var role: Role?

    private var host: BluetoothGamingHost?
    private var client: BluetoothGamingClient?
    private var listenerBox: ListenerBox?

    func startHost(displayName _: String) {
        stopAll()
        role = .host
        let box = ListenerBox(owner: self)
        listenerBox = box
        let h = BluetoothGamingHost(listener: box)
        host = h
        h.start()
        appendLog("host starting…")
        match = initialMatchState(playerCount: 1)
    }

    func startClient(displayName: String) {
        stopAll()
        role = .client
        let box = ListenerBox(owner: self)
        listenerBox = box
        let c = BluetoothGamingClient(listener: box)
        c.displayName = displayName
        client = c
        _ = c.startScan()
        appendLog("scanning…")
    }

    func stopAll() {
        host?.stop()
        host = nil
        client?.disconnect()
        client = nil
        listenerBox = nil
        peers = []
        role = nil
        match = nil
        lastRoll = nil
    }

    func hostRollDice() {
        guard let h = host, var state = match else { return }
        func d() -> Int { Int.random(in: 1 ... 6) }
        let roll = DiceRoll(
            white1: d(),
            white2: d(),
            red: state.diceInPlay.contains(.red) ? d() : 0,
            yellow: state.diceInPlay.contains(.yellow) ? d() : 0,
            green: state.diceInPlay.contains(.green) ? d() : 0,
            blue: state.diceInPlay.contains(.blue) ? d() : 0
        )
        lastRoll = roll
        do {
            let payload = try GameMessageCodec.encodeRoll(roll, activePlayerIndex: state.activePlayerIndex, cid: Int.random(in: 0 ... 1_000_000))
            let frame = ProtocolCodec.encodeFrame(messageType: WireMessageType.appPayload, payload: payload)
            h.broadcast(frame)
            appendLog("rolled (host)")
        } catch {
            appendLog("encode roll failed")
        }
    }

    fileprivate func appendLog(_ line: String) {
        log.append(line)
        if log.count > 100 { log.removeFirst(log.count - 100) }
    }

    fileprivate func onPeerConnected(address: String, playerId: Int, displayName: String) {
        peers.append((address, playerId, displayName))
        appendLog("peer \(displayName) id=\(playerId)")
        if role == .host {
            let count = peers.count + 1
            let state = initialMatchState(playerCount: count)
            match = state
            broadcastGameState(state)
        }
    }

    fileprivate func onPeerDisconnected(address: String) {
        peers.removeAll { $0.address == address }
        appendLog("disconnected \(address)")
    }

    fileprivate func onMessage(address _: String, messageType: UInt8, payload: Data) {
        guard messageType == WireMessageType.appPayload else { return }
        do {
            let root = try GameMessageCodec.decodeAppPayload(payload)
            let kind = root["kind"] as? String ?? ""
            if kind == "game_state" {
                // Minimal: host broadcasts; client could parse full state here
                appendLog("game_state")
            } else if kind == "roll" {
                let (roll, _) = try GameMessageCodec.parseRoll(root)
                lastRoll = roll
            }
        } catch {
            appendLog("parse error")
        }
    }

    private func broadcastGameState(_ state: MatchState) {
        guard let h = host else { return }
        do {
            let payload = try GameMessageCodec.encodeGameState(state)
            let frame = ProtocolCodec.encodeFrame(messageType: WireMessageType.appPayload, payload: payload)
            h.broadcast(frame)
        } catch {
            appendLog("encode state failed")
        }
    }
}

private final class ListenerBox: BluetoothGamingListener {
    weak var owner: LocXXViewModel?

    init(owner: LocXXViewModel) {
        self.owner = owner
    }

    func onPeerConnected(address: String, playerId: Int, displayName: String) {
        owner?.onPeerConnected(address: address, playerId: playerId, displayName: displayName)
    }

    func onPeerDisconnected(address: String) {
        owner?.onPeerDisconnected(address: address)
    }

    func onMessageReceived(address: String, messageType: UInt8, payload: Data) {
        owner?.onMessage(address: address, messageType: messageType, payload: payload)
    }

    func onError(_ message: String) {
        owner?.appendLog("error: \(message)")
    }
}
