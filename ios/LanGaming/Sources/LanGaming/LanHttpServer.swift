import Foundation
import Network

public struct LanHttpRequest {
    public var method: String
    public var path: String
    public var query: [String: String]
    public var headers: [String: String]
    public var body: Data
}

/// Minimal HTTP/1.1 over TCP for LocXX LAN transport (one request per connection).
public final class LanHttpServer {
    public typealias Handler = (LanHttpRequest) -> (status: Int, headers: [String: String], body: Data)

    private let listener: NWListener
    private let queue: DispatchQueue
    private let handler: Handler

    public init(port: UInt16, queueLabel: String = "locxx.lan.http", handler: @escaping Handler) {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            fatalError("invalid port")
        }
        listener = NWListener(using: .tcp, on: nwPort)
        queue = DispatchQueue(label: queueLabel, qos: .userInitiated)
        self.handler = handler
    }

    public func start() {
        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection: connection)
        }
        listener.start(queue: queue)
    }

    public func stop() {
        listener.cancel()
    }

    private func handle(connection: NWConnection) {
        connection.start(queue: queue)
        readMore(connection: connection, buffer: Data()) { [weak self] result in
            guard let self else {
                connection.cancel()
                return
            }
            switch result {
            case .failure:
                let raw = Self.buildResponse(status: 400, headers: ["Content-Type": "text/plain"], body: Data("bad request".utf8))
                connection.send(content: raw, isComplete: true, completion: .contentProcessed({ _ in connection.cancel() }))
            case .success(let req):
                let res = self.handler(req)
                let raw = Self.buildResponse(status: res.status, headers: res.headers, body: res.body)
                connection.send(content: raw, isComplete: true, completion: .contentProcessed({ _ in connection.cancel() }))
            }
        }
    }

    private func readMore(connection: NWConnection, buffer: Data, completion: @escaping (Result<LanHttpRequest, Error>) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 512 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                completion(.failure(error))
                return
            }
            var buf = buffer
            if let data, !data.isEmpty {
                buf.append(data)
            }
            let marker = Data("\r\n\r\n".utf8)
            guard let headerRange = buf.range(of: marker) else {
                if isComplete {
                    completion(.failure(NSError(domain: "LocXXHTTP", code: 1)))
                } else {
                    self.readMore(connection: connection, buffer: buf, completion: completion)
                }
                return
            }
            let headerEnd = headerRange.upperBound
            guard let headerStr = String(data: buf.subdata(in: 0 ..< headerEnd), encoding: .utf8) else {
                completion(.failure(NSError(domain: "LocXXHTTP", code: 2)))
                return
            }
            guard let parsed = Self.parseHeaders(headerStr) else {
                completion(.failure(NSError(domain: "LocXXHTTP", code: 3)))
                return
            }
            let total = headerEnd + parsed.contentLength
            if buf.count >= total {
                let body = buf.subdata(in: headerEnd ..< (headerEnd + parsed.contentLength))
                let req = LanHttpRequest(
                    method: parsed.method,
                    path: parsed.path,
                    query: parsed.query,
                    headers: parsed.headers,
                    body: body
                )
                completion(.success(req))
                return
            }
            if isComplete {
                completion(.failure(NSError(domain: "LocXXHTTP", code: 4)))
                return
            }
            self.readMore(connection: connection, buffer: buf, completion: completion)
        }
    }

    private struct ParsedHeader {
        var method: String
        var path: String
        var query: [String: String]
        var headers: [String: String]
        var contentLength: Int
    }

    private static func parseHeaders(_ headerStr: String) -> ParsedHeader? {
        let lines = headerStr.split(separator: "\r\n", omittingEmptySubsequences: false)
        guard let first = lines.first else { return nil }
        let parts = first.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0])
        let urlPart = String(parts[1])
        let urlPieces = urlPart.split(separator: "?", maxSplits: 1)
        let rawPath = String(urlPieces[0])
        let path = rawPath.hasPrefix("/") ? rawPath : "/\(rawPath)"
        var query: [String: String] = [:]
        if urlPieces.count == 2 {
            for pair in urlPieces[1].split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                if kv.count == 2 {
                    let k = String(kv[0]).removingPercentEncoding ?? String(kv[0])
                    let v = String(kv[1]).removingPercentEncoding ?? String(kv[1])
                    query[k] = v
                }
            }
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if line.isEmpty { break }
            guard let idx = line.firstIndex(of: ":") else { continue }
            let name = String(line[..<idx]).lowercased().trimmingCharacters(in: .whitespaces)
            let val = String(line[line.index(after: idx)...]).trimmingCharacters(in: .whitespaces)
            headers[name] = val
        }
        let cl = Int(headers["content-length"] ?? "0") ?? 0
        return ParsedHeader(method: method, path: path, query: query, headers: headers, contentLength: cl)
    }

    private static func buildResponse(status: Int, headers: [String: String], body: Data) -> Data {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 204: reason = "No Content"
        case 400: reason = "Bad Request"
        case 404: reason = "Not Found"
        case 500: reason = "Internal Server Error"
        default: reason = "Error"
        }
        var text = "HTTP/1.1 \(status) \(reason)\r\n"
        for (k, v) in headers {
            text += "\(k): \(v)\r\n"
        }
        text += "Content-Length: \(body.count)\r\n"
        text += "Connection: close\r\n\r\n"
        var out = Data(text.utf8)
        out.append(body)
        return out
    }
}
