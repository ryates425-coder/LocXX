import Combine
import Foundation
import LanGaming
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

    private var host: LanGamingHost?
    private var client: LanGamingClient?
    private var listenerBox: ListenerBox?
    private var mdnsAdvertiser: LocxxMdnsAdvertiser?
    private var mdnsBrowser: LocxxMdnsBrowser?

    func startHost(displayName _: String) {
        stopAll()
        role = .host
        let box = ListenerBox(owner: self)
        listenerBox = box
        let h = LanGamingHost(listener: box)
        host = h
        h.start()
        let adv = LocxxMdnsAdvertiser()
        adv.start(port: Int(locxxLanPort))
        mdnsAdvertiser = adv
        appendLog("host started — players on same Wi‑Fi can join automatically")
        match = initialMatchState(playerCount: 1)
    }

    func startClient(displayName: String) {
        stopAll()
        role = .client
        let box = ListenerBox(owner: self)
        listenerBox = box
        let c = LanGamingClient(listener: box)
        c.displayName = displayName
        client = c
        appendLog("looking for host on Wi‑Fi…")
        let browser = LocxxMdnsBrowser(
            onResolved: { [weak self] hostForUrl, port in
                guard let self, self.role == .client, let cc = self.client else { return }
                let url = "http://\(hostForUrl):\(port)"
                self.appendLog("found host at \(url)")
                cc.connect(hostBaseUrl: url)
                self.mdnsBrowser?.stop()
                self.mdnsBrowser = nil
            },
            onError: { [weak self] msg in
                self?.appendLog("error: \(msg)")
            }
        )
        browser.start()
        mdnsBrowser = browser
    }

    func connectToLanHost(hostBaseUrl: String) {
        mdnsBrowser?.stop()
        mdnsBrowser = nil
        client?.connect(hostBaseUrl: hostBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines))
        appendLog("connecting…")
    }

    func stopAll() {
        mdnsAdvertiser?.stop()
        mdnsAdvertiser = nil
        mdnsBrowser?.stop()
        mdnsBrowser = nil
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

private final class ListenerBox: LanGamingListener {
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
