import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

public enum FamilyTransportError: Int, Error {
    case authorizationRequired = 1, invalidDestination, invalidConfiguration
    case invalidResponse, truncatedResponse, bufferLimit, invalidRead, closed
    case connectionTimeout, tlsOrNetworkFailure, redirectDenied, unsupportedMessage
    case truncatedRead, drainTimeout

    public var nsError: NSError {
        // No host, UUID, message bytes or platform error strings in diagnostics.
        NSError(domain: "FamilyTransport", code: rawValue, userInfo: nil)
    }
}

public enum FamilyVLESS {
    public static let gatewayURL = URL(string: "wss://24projects.ru:8443/v1/transport")!
    public static let maximumMessage = 16 * 1024 * 1024

    /// VLESS v0, no addons / Vision, TCP destination. WSS supplies authenticated TLS.
    public static func request(uuid: UUID, host: String, port: UInt16) throws -> Data {
        // Validate the complete Swift string before the C API: inet_pton may accept
        // platform extensions and a NUL suffix would otherwise be silently truncated.
        guard port != 0, !host.isEmpty, host.utf8.count <= 253,
              host.utf8.allSatisfy({ byte in
                  (48...57).contains(byte) || (65...90).contains(byte) || (97...122).contains(byte)
                      || byte == 45 || byte == 46 || byte == 58
              }) else { throw FamilyTransportError.invalidDestination }
        var identity = uuid.uuid
        var result = Data([0])
        withUnsafeBytes(of: &identity) { result.append(contentsOf: $0) }
        result.append(contentsOf: [0, 1, UInt8(port >> 8), UInt8(port & 0xff)])
        var ipv4 = in_addr()
        var ipv6 = in6_addr()
        if host.withCString({ inet_pton(AF_INET, $0, &ipv4) }) == 1 {
            result.append(1)
            withUnsafeBytes(of: &ipv4) { result.append(contentsOf: $0) }
        } else if host.withCString({ inet_pton(AF_INET6, $0, &ipv6) }) == 1 {
            result.append(3)
            withUnsafeBytes(of: &ipv6) { result.append(contentsOf: $0) }
        } else {
            let labels = host.split(separator: ".", omittingEmptySubsequences: false)
            guard labels.allSatisfy({ label in
                !label.isEmpty && label.utf8.count <= 63 && label.first != "-" && label.last != "-"
                    && label.utf8.allSatisfy { (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 }
            }) else { throw FamilyTransportError.invalidDestination }
            result.append(2); result.append(UInt8(host.utf8.count)); result.append(contentsOf: host.utf8)
        }
        return result
    }
}

/// Response header may be split across arbitrary WS messages, including one byte at a time.
public struct FamilyVLESSResponseDecoder {
    private var prefix = Data()
    private var remainingAddons: Int?
    public private(set) var complete = false
    public init() { }

    public mutating func consume(_ input: Data) throws -> Data {
        if complete { return input }
        var offset = 0
        while prefix.count < 2 && offset < input.count {
            prefix.append(input[input.startIndex + offset]); offset += 1
        }
        guard prefix.count == 2 else { return Data() }
        guard prefix[0] == 0 else { throw FamilyTransportError.invalidResponse }
        if remainingAddons == nil { remainingAddons = Int(prefix[1]) }
        let skip = min(remainingAddons!, input.count - offset)
        remainingAddons! -= skip; offset += skip
        guard remainingAddons == 0 else { return Data() }
        complete = true; prefix.removeAll(keepingCapacity: false)
        return Data(input.dropFirst(offset))
    }

    public func finish() throws {
        if !complete { throw FamilyTransportError.truncatedResponse }
    }
}
