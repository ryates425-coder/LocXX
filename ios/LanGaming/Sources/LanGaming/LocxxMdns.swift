import Darwin
import Foundation

/// Bonjour / DNS-SD type (include trailing dot for browser).
public enum LocxxMdns {
    public static let serviceType = "_locxx._tcp."
}

/// Publishes LocXX on the LAN so clients can find the host without typing a URL.
public final class LocxxMdnsAdvertiser {
    private var netService: NetService?

    public init() {}

    public func start(port: Int) {
        stop()
        let name = "LocXX-\(UUID().uuidString.prefix(8))"
        let svc = NetService(domain: "local.", type: LocxxMdns.serviceType, name: name, port: Int32(port))
        svc.includesPeerToPeer = false
        svc.publish()
        netService = svc
    }

    public func stop() {
        netService?.stop()
        netService = nil
    }
}

/// Finds the first published LocXX host and returns `hostForUrl` + port (for `http://` / IPv6 brackets).
public final class LocxxMdnsBrowser: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var resolvers: [NetService] = []
    private let lock = NSLock()
    private var resolvedOnce = false
    private let onResolved: (String, Int) -> Void
    private let onError: (String) -> Void

    public init(onResolved: @escaping (String, Int) -> Void, onError: @escaping (String) -> Void) {
        self.onResolved = onResolved
        self.onError = onError
    }

    public func start() {
        lock.lock()
        resolvedOnce = false
        resolvers = []
        lock.unlock()
        browser.delegate = self
        browser.searchForServices(ofType: LocxxMdns.serviceType, inDomain: "local.")
    }

    public func stop() {
        browser.stop()
        lock.lock()
        for s in resolvers {
            s.stop()
        }
        resolvers = []
        lock.unlock()
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing _: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !resolvedOnce else { return }
        service.delegate = self
        resolvers.append(service)
        service.resolve(withTimeout: 20)
    }

    public func netServiceBrowser(_: NetServiceBrowser, didNotSearch error: [String: NSNumber]) {
        onError("Bonjour browse failed")
    }

    public func netService(_ sender: NetService, didNotResolve error: [String: NSNumber]) {
        lock.lock()
        resolvers.removeAll { $0 == sender }
        lock.unlock()
    }

    public func netServiceDidResolveAddress(_ sender: NetService) {
        lock.lock()
        defer { lock.unlock() }
        guard !resolvedOnce else { return }
        let port = Int(sender.port)
        guard port > 0 else { return }
        if let host = ipv4Host(from: sender) {
            resolvedOnce = true
            browser.stop()
            for s in resolvers { s.stop() }
            resolvers = []
            DispatchQueue.main.async { self.onResolved(host, port) }
            return
        }
        if let host = ipv6Host(from: sender) {
            resolvedOnce = true
            browser.stop()
            for s in resolvers { s.stop() }
            resolvers = []
            DispatchQueue.main.async { self.onResolved(host, port) }
        }
    }
}

private func ipv4Host(from service: NetService) -> String? {
    guard let addresses = service.addresses else { return nil }
    for data in addresses {
        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let ok: Int32 = data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return Int32(-1) }
            return getnameinfo(
                base.assumingMemoryBound(to: sockaddr.self),
                socklen_t(data.count),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
        }
        if ok != 0 { continue }
        let s = String(cString: hostname)
        if s.contains(".") && !s.contains(":") { return s }
    }
    return nil
}

private func ipv6Host(from service: NetService) -> String? {
    guard let addresses = service.addresses else { return nil }
    for data in addresses {
        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let ok: Int32 = data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return Int32(-1) }
            return getnameinfo(
                base.assumingMemoryBound(to: sockaddr.self),
                socklen_t(data.count),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
        }
        if ok != 0 { continue }
        let s = String(cString: hostname)
        if s.contains(":") { return "[\(s.split(separator: "%").first.map(String.init) ?? s)]" }
    }
    return nil
}
