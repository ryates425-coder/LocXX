import BluetoothGaming
import XCTest

final class ProtocolCodecTests: XCTestCase {
    func testRoundTrip() throws {
        let p = Data([1, 2, 3])
        let f = ProtocolCodec.encodeFrame(messageType: WireMessageType.appPayload, payload: p)
        let d = try ProtocolCodec.decodeFrame(f)
        XCTAssertEqual(d.messageType, WireMessageType.appPayload)
        XCTAssertEqual(d.payload, p)
    }

    func testClientHello() throws {
        let nonce = Data(repeating: 3, count: 16)
        let f = try ProtocolCodec.buildClientHello(protocolVersion: wireVersion, displayName: "Bob", clientNonce: nonce)
        let d = try ProtocolCodec.decodeFrame(f)
        let h = try ProtocolCodec.parseClientHello(d.payload)
        XCTAssertEqual(h.displayName, "Bob")
        XCTAssertEqual(h.clientNonce, nonce)
    }
}
