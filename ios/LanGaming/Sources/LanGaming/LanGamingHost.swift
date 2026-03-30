import Foundation

/// HTTP host on `locxxLanPort` using the same wire frames as BLE (`ProtocolCodec` / docs/protocol.md).
public final class LanGamingHost {
    private let listener: LanGamingListener
    private var http: LanHttpServer?
    private let sessionNonce = Data((0 ..< 16).map { _ in UInt8.random(in: 0 ... 255) })
    private var nextPlayerId = 1
    private var tokenToPeer: [String: Peer] = [:]
    private let sync = NSLock()

    private final class Peer {
        let token: String
        let playerId: Int
        let displayName: String
        private let cond = NSCondition()
        private var queue: [Data] = []

        init(token: String, playerId: Int, displayName: String) {
            self.token = token
            self.playerId = playerId
            self.displayName = displayName
        }

        func take(timeout: TimeInterval) -> Data? {
            cond.lock()
            defer { cond.unlock() }
            let deadline = Date().addingTimeInterval(timeout)
            while queue.isEmpty {
                if !cond.wait(until: deadline) {
                    return nil
                }
            }
            return queue.removeFirst()
        }

        func offer(_ data: Data) {
            cond.lock()
            queue.append(data)
            cond.signal()
            cond.unlock()
        }
    }

    public init(listener: LanGamingListener) {
        self.listener = listener
    }

    public func sessionNonceData() -> Data { sessionNonce }

    public func start() {
        let server = LanHttpServer(port: locxxLanPort) { [weak self] req in
            guard let self else {
                return (404, ["Content-Type": "text/plain"], Data("gone".utf8))
            }
            return self.handle(req: req)
        }
        http = server
        server.start()
    }

    public func stop() {
        http?.stop()
        http = nil
        sync.lock()
        tokenToPeer.removeAll()
        nextPlayerId = 1
        sync.unlock()
    }

    public func broadcast(_ frame: Data) {
        sync.lock()
        let peers = Array(tokenToPeer.values)
        sync.unlock()
        let bytes = Data(frame)
        for p in peers {
            p.offer(Data(bytes))
        }
    }

    public func sendToPeer(addressToken: String, frame: Data) {
        sync.lock()
        let peer = tokenToPeer[addressToken]
        sync.unlock()
        peer?.offer(Data(frame))
    }

    private func handle(req: LanHttpRequest) -> (Int, [String: String], Data) {
        switch (req.method, req.path) {
        case ("POST", "/locxx/v1/hello"):
            return serveHello(body: req.body)
        case ("POST", "/locxx/v1/send"):
            return serveSend(token: req.query["token"] ?? "", body: req.body)
        case ("GET", "/locxx/v1/poll"):
            return servePoll(token: req.query["token"] ?? "")
        default:
            return (404, ["Content-Type": "text/plain"], Data("not found".utf8))
        }
    }

    private func serveHello(body: Data) -> (Int, [String: String], Data) {
        let decoded: DecodedFrame
        do {
            decoded = try ProtocolCodec.decodeFrame(body)
        } catch {
            mainError("decode")
            return (400, [:], Data())
        }
        guard decoded.messageType == WireMessageType.clientHello else {
            return (400, ["Content-Type": "text/plain"], Data("expected hello".utf8))
        }
        let hello: ClientHelloPayload
        do {
            hello = try ProtocolCodec.parseClientHello(decoded.payload)
        } catch {
            mainError("hello")
            return (400, [:], Data())
        }
        guard hello.protocolVersion == wireVersion else {
            mainError("protocol mismatch")
            return (400, [:], Data())
        }
        sync.lock()
        guard nextPlayerId <= 8 else {
            sync.unlock()
            mainError("room full")
            return (503, ["Content-Type": "text/plain"], Data("room full".utf8))
        }
        let pid = nextPlayerId
        nextPlayerId += 1
        let token = UUID().uuidString
        tokenToPeer[token] = Peer(token: token, playerId: pid, displayName: hello.displayName)
        sync.unlock()

        let welcome: Data
        do {
            welcome = try ProtocolCodec.buildHostWelcome(
                protocolVersion: wireVersion,
                playerId: UInt8(pid),
                sessionNonce: sessionNonce
            )
        } catch {
            sync.lock()
            tokenToPeer.removeValue(forKey: token)
            sync.unlock()
            return (500, [:], Data())
        }
        DispatchQueue.main.async {
            self.listener.onPeerConnected(address: token, playerId: pid, displayName: hello.displayName)
        }
        let headers: [String: String] = [
            "Content-Type": "application/octet-stream",
            locxxHttpTokenHeader: token,
        ]
        return (200, headers, welcome)
    }

    private func serveSend(token: String, body: Data) -> (Int, [String: String], Data) {
        sync.lock()
        let peer = tokenToPeer[token]
        sync.unlock()
        guard let peer else {
            return (404, [:], Data())
        }
        let decoded: DecodedFrame
        do {
            decoded = try ProtocolCodec.decodeFrame(body)
        } catch {
            mainError("decode")
            return (400, [:], Data())
        }
        switch decoded.messageType {
        case WireMessageType.ping:
            peer.offer(ProtocolCodec.encodeFrame(messageType: WireMessageType.pong))
        default:
            DispatchQueue.main.async {
                self.listener.onMessageReceived(address: token, messageType: decoded.messageType, payload: decoded.payload)
            }
        }
        return (204, [:], Data())
    }

    private func servePoll(token: String) -> (Int, [String: String], Data) {
        sync.lock()
        let peer = tokenToPeer[token]
        sync.unlock()
        guard let peer else {
            return (404, [:], Data())
        }
        if let frame = peer.take(timeout: 55) {
            return (200, ["Content-Type": "application/octet-stream"], frame)
        }
        return (204, [:], Data())
    }

    private func mainError(_ message: String) {
        DispatchQueue.main.async {
            self.listener.onError(message)
        }
    }
}
