import Foundation

public enum LocXXBleUUIDs {
    public static let service = UUID(uuidString: "A0B40001-9267-4521-8F90-ACCD260FF718")!
    public static let clientToHost = UUID(uuidString: "A0B40002-9267-4521-8F90-ACCD260FF718")!
    public static let hostToClient = UUID(uuidString: "A0B40003-9267-4521-8F90-ACCD260FF718")!
}

public enum WireMessageType {
    public static let clientHello: UInt8 = 0x01
    public static let hostWelcome: UInt8 = 0x02
    public static let ack: UInt8 = 0x03
    public static let ping: UInt8 = 0x04
    public static let pong: UInt8 = 0x05
    public static let appPayload: UInt8 = 0x10
}

public let wireVersion: UInt8 = 0x01
public let maxFramePayload = 4096

public struct DecodedFrame: Equatable {
    public let wireVersion: UInt8
    public let messageType: UInt8
    public let payload: Data
}

public struct ClientHelloPayload: Equatable {
    public let protocolVersion: UInt8
    public let displayName: String
    public let clientNonce: Data
}

public struct HostWelcomePayload: Equatable {
    public let protocolVersion: UInt8
    public let playerId: UInt8
    public let sessionNonce: Data
}

public struct AckPayload: Equatable {
    public let acknowledgedMessageType: UInt8
    public let correlationId: UInt32
}

public enum ProtocolCodec {
    public static func encodeFrame(messageType: UInt8, payload: Data = Data()) -> Data {
        precondition(payload.count <= maxFramePayload)
        var buf = Data()
        buf.append(wireVersion)
        buf.append(messageType)
        var len = UInt16(payload.count).bigEndian
        buf.append(Data(bytes: &len, count: 2))
        buf.append(payload)
        return buf
    }

    public static func decodeFrame(_ frame: Data) throws -> DecodedFrame {
        guard frame.count >= 4 else { throw NSError(domain: "ProtocolCodec", code: 1) }
        let ver = frame[0]
        guard ver == wireVersion else { throw NSError(domain: "ProtocolCodec", code: 2) }
        let type = frame[1]
        let len = Int(frame[2]) << 8 | Int(frame[3])
        guard len <= maxFramePayload, frame.count == 4 + len else { throw NSError(domain: "ProtocolCodec", code: 3) }
        let payload = frame.subdata(in: 4 ..< (4 + len))
        return DecodedFrame(wireVersion: ver, messageType: type, payload: payload)
    }

    public static func buildClientHello(protocolVersion: UInt8, displayName: String, clientNonce: Data) throws -> Data {
        guard clientNonce.count == 16 else { throw NSError(domain: "ProtocolCodec", code: 4) }
        let nameData = Data(displayName.utf8)
        guard nameData.count <= 64 else { throw NSError(domain: "ProtocolCodec", code: 5) }
        var p = Data()
        p.append(protocolVersion)
        p.append(UInt8(nameData.count))
        p.append(nameData)
        p.append(clientNonce)
        return encodeFrame(messageType: WireMessageType.clientHello, payload: p)
    }

    public static func parseClientHello(_ payload: Data) throws -> ClientHelloPayload {
        guard payload.count >= 2 + 16 else { throw NSError(domain: "ProtocolCodec", code: 6) }
        let pv = payload[0]
        let nl = Int(payload[1])
        guard 2 + nl + 16 == payload.count else { throw NSError(domain: "ProtocolCodec", code: 7) }
        let name = String(data: payload.subdata(in: 2 ..< (2 + nl)), encoding: .utf8) ?? ""
        let nonce = payload.subdata(in: (2 + nl) ..< (2 + nl + 16))
        return ClientHelloPayload(protocolVersion: pv, displayName: name, clientNonce: nonce)
    }

    public static func buildHostWelcome(protocolVersion: UInt8, playerId: UInt8, sessionNonce: Data) throws -> Data {
        guard sessionNonce.count == 16 else { throw NSError(domain: "ProtocolCodec", code: 8) }
        var p = Data()
        p.append(protocolVersion)
        p.append(playerId)
        p.append(sessionNonce)
        return encodeFrame(messageType: WireMessageType.hostWelcome, payload: p)
    }

    public static func parseHostWelcome(_ payload: Data) throws -> HostWelcomePayload {
        guard payload.count == 18 else { throw NSError(domain: "ProtocolCodec", code: 9) }
        let nonce = payload.subdata(in: 2 ..< 18)
        return HostWelcomePayload(protocolVersion: payload[0], playerId: payload[1], sessionNonce: nonce)
    }

    public static func buildAck(acknowledgedMessageType: UInt8, correlationId: UInt32) -> Data {
        var p = Data()
        p.append(acknowledgedMessageType)
        var cid = correlationId.bigEndian
        p.append(Data(bytes: &cid, count: 4))
        return encodeFrame(messageType: WireMessageType.ack, payload: p)
    }

    public static func parseAck(_ payload: Data) throws -> AckPayload {
        guard payload.count == 5 else { throw NSError(domain: "ProtocolCodec", code: 10) }
        let mt = payload[0]
        let cid = (UInt32(payload[1]) << 24) | (UInt32(payload[2]) << 16) | (UInt32(payload[3]) << 8) | UInt32(payload[4])
        return AckPayload(acknowledgedMessageType: mt, correlationId: cid)
    }
}
