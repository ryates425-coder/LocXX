import Darwin
import Foundation

public enum LanGamingLanIp {
    /// Non-loopback IPv4 candidates (typical Wi‑Fi / LAN).
    public static func listLanIpv4() -> [String] {
        var addresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let addr = p.pointee.ifa_addr
            if let addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let len = socklen_t(addr.pointee.sa_len)
                if getnameinfo(addr, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let s = String(cString: host)
                    if s != "127.0.0.1" {
                        addresses.append(s)
                    }
                }
            }
            ptr = p.pointee.ifa_next
        }
        return addresses
    }
}
