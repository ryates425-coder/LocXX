import LanGaming
import XCTest

final class ProtocolCodecTests: XCTestCase {
    func testEncodeDecodeRoundTrip() {
        let p = Data([1, 2, 3])
        let f = ProtocolCodec.encodeFrame(messageType: WireMessageType.appPayload, payload: p)
        let d = try ProtocolCodec.decodeFrame(f)
        XCTAssertEqual(d.wireVersion, wireVersion)
        XCTAssertEqual(d.messageType, WireMessageType.appPayload)
        XCTAssertEqual(d.payload, p)
    }

    func testClientHelloRoundTrip() {
        let nonce = Data((0 ..< 16).map { UInt8($0) })
        let frame = try ProtocolCodec.buildClientHello(protocolVersion: wireVersion, displayName: "Alice", clientNonce: nonce)
        let decoded = try ProtocolCodec.decodeFrame(frame)
        XCTAssertEqual(decoded.messageType, WireMessageType.clientHello)
        let hello = try ProtocolCodec.parseClientHello(decoded.payload)
        XCTAssertEqual(hello.displayName, "Alice")
        XCTAssertEqual(hello.clientNonce, nonce)
    }

    func testHostWelcomeRoundTrip() {
        let sn = Data(repeating: 7, count: 16)
        let frame = try ProtocolCodec.buildHostWelcome(protocolVersion: wireVersion, playerId: 3, sessionNonce: sn)
        let decoded = try ProtocolCodec.decodeFrame(frame)
        let w = try ProtocolCodec.parseHostWelcome(decoded.payload)
        XCTAssertEqual(Int(w.playerId), 3)
        XCTAssertEqual(w.sessionNonce, sn)
    }
}
