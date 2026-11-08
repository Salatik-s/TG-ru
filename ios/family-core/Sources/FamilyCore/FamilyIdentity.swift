import Foundation
import Security

/// A private per-installation key. No shared access group, export or cloud synchronization.
public final class FamilyIdentity {
    private let key: SecKey
    public let publicKeySPKI: Data
    public let deviceID: String

    public init(defaults: UserDefaults = .standard) throws {
        let preference = "tg.ru.installation.key.id.v1"
        let installation: String
        if let value = defaults.string(forKey: preference), UUID(uuidString: value) != nil {
            installation = value
        } else {
            installation = UUID().uuidString
            defaults.set(installation, forKey: preference)
        }
        let tag = Data(((Bundle.main.bundleIdentifier ?? "com.salatik.tgru") + ".family.p256." + installation).utf8)
        let query: [String: Any] = [kSecClass as String: kSecClassKey,
                                    kSecAttrApplicationTag as String: tag,
                                    kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                                    kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
                                    kSecReturnRef as String: true]
        var stored: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &stored)
        if status == errSecSuccess, let stored {
            guard CFGetTypeID(stored) == SecKeyGetTypeID() else { throw FamilyFailure.keyUnavailable }
            key = (stored as! SecKey)
        } else if status == errSecItemNotFound {
            let privateAttributes: [String: Any] = [kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
            var attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits as String: 256, kSecPrivateKeyAttrs as String: privateAttributes]
            var error: Unmanaged<CFError>?
            #if os(iOS) && !targetEnvironment(simulator)
            attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
            if let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                                            .privateKeyUsage, nil) {
                attributes[kSecPrivateKeyAttrs as String] = [kSecAttrIsPermanent as String: true,
                    kSecAttrApplicationTag as String: tag, kSecAttrAccessControl as String: access]
            }
            #endif
            if let created = SecKeyCreateRandomKey(attributes as CFDictionary, &error) {
                key = created
            } else {
                // Simulator or devices without an available enclave still use the private device-only Keychain.
                attributes.removeValue(forKey: kSecAttrTokenID as String)
                attributes[kSecPrivateKeyAttrs as String] = privateAttributes
                error = nil
                guard let created = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
                    throw FamilyFailure.keyUnavailable
                }
                key = created
            }
        } else { throw FamilyFailure.keyUnavailable }
        guard let publicKey = SecKeyCopyPublicKey(key),
              let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw FamilyFailure.keyUnavailable
        }
        publicKeySPKI = try FamilyProtocol.spki(x963: raw)
        deviceID = FamilyProtocol.sha256(publicKeySPKI)
    }

    public func signature(message: String) throws -> Data {
        let algorithm: SecKeyAlgorithm = .ecdsaSignatureMessageX962SHA256
        guard SecKeyIsAlgorithmSupported(key, .sign, algorithm), message.utf8.count < 1024,
              let result = SecKeyCreateSignature(key, algorithm, Data(message.utf8) as CFData, nil) else {
            throw FamilyFailure.keyUnavailable
        }
        return result as Data
    }
}
