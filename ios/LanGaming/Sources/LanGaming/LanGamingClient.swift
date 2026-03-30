import Foundation
import Security

public final class LanGamingClient {
    private let listener: LanGamingListener
    private var task: Task<Void, Never>?
    public var displayName: String = "Player"
    private var token: String?
    private var baseUrl: String?

    public init(listener: LanGamingListener) {
        self.listener = listener
    }

    public func connect(hostBaseUrl: String) {
        disconnect()
        let trimmed = hostBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        var base = trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !base.lowercased().hasPrefix("http") {
            base = "http://\(base)"
        }
        baseUrl = base
        task = Task { [weak self] in
            guard let self else { return }
            await self.run(base: base)
        }
    }

    public func disconnect() {
        task?.cancel()
        task = nil
        let t = token
        token = nil
        baseUrl = nil
        if let t {
            DispatchQueue.main.async {
                self.listener.onPeerDisconnected(address: t)
            }
        }
    }

    public func sendFrame(_ frame: Data) {
        guard let base = baseUrl, let tok = token else { return }
        let enc = tok.addingPercentEncoding(withAllowedCharacters: CharacterSet.urlQueryAllowed) ?? tok
        guard let u = URL(string: "\(base)/locxx/v1/send?token=\(enc)") else { return }
        Task {
            var req = URLRequest(url: u)
            req.httpMethod = "POST"
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            req.httpBody = frame
            do {
                let (_, resp) = try await URLSession.shared.data(for: req)
                guard let http = resp as? HTTPURLResponse else { return }
                if http.statusCode != 204 {
                    DispatchQueue.main.async {
                        self.listener.onError("send http \(http.statusCode)")
                    }
                }
            } catch {
                if self.isCancelledLike(error) { return }
                DispatchQueue.main.async {
                    self.listener.onError(error.localizedDescription)
                }
            }
        }
    }

    private func run(base: String) async {
        do {
            var nonce = Data(count: 16)
            _ = nonce.withUnsafeMutableBytes { ptr in
                SecRandomCopyBytes(kSecRandomDefault, 16, ptr.baseAddress!)
            }
            let hello = try ProtocolCodec.buildClientHello(protocolVersion: wireVersion, displayName: displayName, clientNonce: nonce)
            var req = URLRequest(url: URL(string: "\(base)/locxx/v1/hello")!)
            req.httpMethod = "POST"
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            req.httpBody = hello
            let (welcomeData, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                DispatchQueue.main.async { self.listener.onError("hello http") }
                return
            }
            let hdr = http.value(forHTTPHeaderField: locxxHttpTokenHeader) ?? http.value(forHTTPHeaderField: "X-LocXX-Token")
            guard let assigned = hdr else {
                DispatchQueue.main.async { self.listener.onError("missing token") }
                return
            }
            let decoded = try ProtocolCodec.decodeFrame(welcomeData)
            guard decoded.messageType == WireMessageType.hostWelcome else {
                DispatchQueue.main.async { self.listener.onError("expected welcome") }
                return
            }
            let w = try ProtocolCodec.parseHostWelcome(decoded.payload)
            let tok = assigned
            token = tok
            DispatchQueue.main.async {
                self.listener.onPeerConnected(address: tok, playerId: Int(w.playerId), displayName: self.displayName)
            }
            try await pollLoop(base: base, tok: tok)
            token = nil
            baseUrl = nil
            if !Task.isCancelled {
                DispatchQueue.main.async {
                    self.listener.onPeerDisconnected(address: tok)
                }
            }
        } catch {
            token = nil
            baseUrl = nil
            if isCancelledLike(error) { return }
            DispatchQueue.main.async {
                self.listener.onError(error.localizedDescription)
            }
        }
    }

    private func isCancelledLike(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func pollLoop(base: String, tok: String) async throws {
        let allowed = CharacterSet.urlQueryAllowed
        while !Task.isCancelled {
            let enc = tok.addingPercentEncoding(withAllowedCharacters: allowed) ?? tok
            guard let u = URL(string: "\(base)/locxx/v1/poll?token=\(enc)") else { break }
            var req = URLRequest(url: u)
            req.httpMethod = "GET"
            req.timeoutInterval = 120
            let (data, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse else { break }
            if http.statusCode == 204 { continue }
            guard http.statusCode == 200 else {
                DispatchQueue.main.async { self.listener.onError("poll http \(http.statusCode)") }
                break
            }
            if data.isEmpty { continue }
            let decoded = try ProtocolCodec.decodeFrame(data)
            switch decoded.messageType {
            case WireMessageType.ping:
                sendFrame(ProtocolCodec.encodeFrame(messageType: WireMessageType.pong))
            default:
                DispatchQueue.main.async {
                    self.listener.onMessageReceived(address: tok, messageType: decoded.messageType, payload: decoded.payload)
                }
            }
        }
    }
}
