import Foundation
import CryptoKit

public enum FamilyFailure: Error, Equatable {
    case invalidResponse, invalidPayload, keyUnavailable, cancelled, networkUnavailable
    case server(String, Int)
}

public enum FamilyProofAction: String {
    case invite, status, config, manage
}

public enum FamilyProtocol {
    public static let endpoint = URL(string: "https://24projects.ru:8443")!
    public static let emptyHash = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"

    public static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: "\\A(?:" + pattern + ")\\z", options: .regularExpression) != nil
    }

    public static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    // This is the canonical DER SPKI expected by the existing Node/P-256 service.
    public static func spki(x963: Data) throws -> Data {
        guard x963.count == 65, x963.first == 4 else { throw FamilyFailure.keyUnavailable }
        return Data([0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,
                     0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]) + x963
    }

    public static func canonicalString(_ value: String) -> String {
        var result = "\""
        for scalar in value.unicodeScalars {
            switch scalar.value {
            case 0x22: result += "\\\""
            case 0x5c: result += "\\\\"
            case 8: result += "\\b"
            case 9: result += "\\t"
            case 10: result += "\\n"
            case 12: result += "\\f"
            case 13: result += "\\r"
            case 0..<32: result += String(format: "\\u%04x", scalar.value)
            default: result.unicodeScalars.append(scalar)
            }
        }
        return result + "\""
    }

    public static func canonicalManagement(command: String, deviceID: String? = nil, hours: Int = 24) throws -> String {
        switch command {
        case "list": return "{\"command\":\"list\"}"
        case "invite":
            guard (1...720).contains(hours) else { throw FamilyFailure.invalidPayload }
            return "{\"command\":\"invite\",\"hours\":\(hours)}"
        case "approve", "revoke":
            guard let deviceID, matches(deviceID, "[a-f0-9]{64}") else { throw FamilyFailure.invalidPayload }
            return "{\"command\":\"\(command)\",\"device_id\":\"\(deviceID)\"}"
        default: throw FamilyFailure.invalidPayload
        }
    }

    public static func validateChallenge(message: String, challengeID: String, deviceID: String,
                                         expectedDeviceID: String, action: FamilyProofAction,
                                         canonicalManagement: String? = nil) -> Bool {
        guard message.utf8.count < 1024, deviceID == expectedDeviceID,
              matches(expectedDeviceID, "[a-f0-9]{64}"),
              matches(challengeID, "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") else { return false }
        let lines = message.components(separatedBy: "\n")
        guard lines.count == 5, lines[0] == "familygram-v1", lines[1] == challengeID,
              matches(lines[2], "[A-Za-z0-9_-]{43}"), lines[3] == action.rawValue,
              matches(lines[4], "[a-f0-9]{64}") else { return false }
        switch action {
        case .invite: return true // Host-salted invitation commitment is signed exactly over authenticated TLS.
        case .manage:
            guard let canonicalManagement else { return false }
            return lines[4] == sha256(Data(canonicalManagement.utf8))
        case .config, .status: return lines[4] == emptyHash
        }
    }
}

public struct FamilyGateway: Decodable, Equatable {
    public let `protocol`: String
    public let host: String
    public let port: Int
    public let uuid: String
    public let security: String
    public let transport: String?
    public let path: String?
    public let server_name: String
    public let flow: String?
    public let fingerprint: String?

    public func validatedUUID() throws -> UUID {
        guard self.protocol == "vless", host == "24projects.ru", server_name == host, port == 8443,
              security == "tls", transport == "ws", path == "/v1/transport",
              (flow ?? "").isEmpty, (fingerprint ?? "").isEmpty,
              let value = UUID(uuidString: uuid), value.uuidString.lowercased() == uuid.lowercased(),
              value != UUID(uuidString: "00000000-0000-0000-0000-000000000000")! else {
            throw FamilyFailure.invalidResponse
        }
        return value
    }
}

public struct FamilyEnvelope: Decodable {
    public let device_id: String
    public let status: String
    public let failures: Int
    public let cooldown_until: Int64
    public let server_time: Int64
    public let phone_owner: Bool?
    public let gateway: FamilyGateway?
    public let gateway_fallbacks: [FamilyGateway]?

    public func validated(deviceID: String) throws -> FamilyEnvelope {
        guard device_id == deviceID, ["new", "pending", "approved", "blocked", "revoked"].contains(status),
              failures >= 0, failures <= 10000, cooldown_until >= 0, server_time > 0,
              (gateway_fallbacks?.count ?? 0) <= 4 else { throw FamilyFailure.invalidResponse }
        return self
    }

    public func selectedGatewayUUID() throws -> UUID {
        guard status == "approved" else { throw FamilyFailure.server("not_approved", 0) }
        if let candidate = gateway_fallbacks?.first(where: { $0.security == "tls" && $0.transport == "ws" }) {
            return try candidate.validatedUUID()
        }
        guard let gateway else { throw FamilyFailure.invalidResponse }
        return try gateway.validatedUUID()
    }
}
