import XCTest
import CryptoKit
@testable import FamilyCore

final class FamilyProtocolTests: XCTestCase {
    let id = String(repeating: "a", count: 64)
    let challenge = "12345678-1234-1234-1234-123456789abc"
    func message(_ action: String, _ hash: String = FamilyProtocol.emptyHash) -> String {
        "familygram-v1\n\(challenge)\n\(String(repeating: "x", count: 43))\n\(action)\n\(hash)"
    }
    func testExactChallengeBinding() {
        XCTAssertTrue(FamilyProtocol.validateChallenge(message: message("status"), challengeID: challenge,
            deviceID: id, expectedDeviceID: id, action: .status))
        for bad in [message("config"), message("status") + "\n", message("status", String(repeating:"b",count:64))] {
            XCTAssertFalse(FamilyProtocol.validateChallenge(message: bad, challengeID: challenge,
                deviceID: id, expectedDeviceID: id, action: .status))
        }
        XCTAssertFalse(FamilyProtocol.validateChallenge(message: message("status"), challengeID: challenge,
            deviceID: String(repeating:"b",count:64), expectedDeviceID:id, action:.status))
    }
    func testManagementActionPayloadBinding() throws {
        let canonical = try FamilyProtocol.canonicalManagement(command:"revoke",deviceID:id)
        let bound = message("manage", FamilyProtocol.sha256(Data(canonical.utf8)))
        XCTAssertTrue(FamilyProtocol.validateChallenge(message:bound,challengeID:challenge,deviceID:id,
            expectedDeviceID:id,action:.manage,canonicalManagement:canonical))
        XCTAssertFalse(FamilyProtocol.validateChallenge(message:bound,challengeID:challenge,deviceID:id,
            expectedDeviceID:id,action:.manage,canonicalManagement:try FamilyProtocol.canonicalManagement(command:"approve",deviceID:id)))
        XCTAssertThrowsError(try FamilyProtocol.canonicalManagement(command:"invite",hours:721))
        XCTAssertThrowsError(try FamilyProtocol.canonicalManagement(command:"revoke",deviceID:"short"))
    }
    func testSPKIAndDERP256() throws {
        let key = P256.Signing.PrivateKey()
        let encoded = try FamilyProtocol.spki(x963:key.publicKey.x963Representation)
        XCTAssertEqual(encoded,key.publicKey.derRepresentation)
        XCTAssertEqual(FamilyProtocol.sha256(encoded).count,64)
        XCTAssertThrowsError(try FamilyProtocol.spki(x963:Data(repeating:0,count:65)))
    }
    func testCanonicalEscaping() {
        XCTAssertEqual(FamilyProtocol.canonicalString("a\"\\\n\t\u{1f}я"),"\"a\\\"\\\\\\n\\t\\u001fя\"")
    }
    func testOnlyFixedWSSGateway() throws {
        let json = #"{"protocol":"vless","host":"24projects.ru","port":8443,"uuid":"12345678-1234-1234-1234-123456789abc","security":"tls","transport":"ws","path":"/v1/transport","server_name":"24projects.ru","flow":"","fingerprint":""}"#
        let decoder = JSONDecoder()
        _ = try decoder.decode(FamilyGateway.self,from:Data(json.utf8)).validatedUUID()
        for bad in [json.replacingOccurrences(of:"8443",with:"443"),json.replacingOccurrences(of:"24projects.ru",with:"example.com"),json.replacingOccurrences(of:"/v1/transport",with:"/other"),json.replacingOccurrences(of:"12345678-1234-1234-1234-123456789abc",with:"00000000-0000-0000-0000-000000000000")] {
            XCTAssertThrowsError(try decoder.decode(FamilyGateway.self,from:Data(bad.utf8)).validatedUUID())
        }
    }
}
