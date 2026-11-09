import Foundation
import CryptoKit
import Security

public struct FamilyArchivedMessage: Codable, Equatable {
    public let accountID: String
    public let peerID: String
    public let messageID: Int32
    public let timestamp: Int32
    public let deletedAt: TimeInterval
    public let text: String
    public init(accountID: String, peerID: String, messageID: Int32, timestamp: Int32, deletedAt: TimeInterval = Date().timeIntervalSince1970, text: String) {
        self.accountID = accountID; self.peerID = peerID; self.messageID = messageID
        self.timestamp = timestamp; self.deletedAt = deletedAt; self.text = String(text.prefix(4096))
    }
}

/// Text-only, local AES-GCM archive. The random key is private to this app's
/// Keychain and cannot migrate through backups. No PTS or Telegram media is stored.
public final class FamilyDeletedArchive {
    public static let shared = FamilyDeletedArchive()
    private let queue = DispatchQueue(label: "family.deleted-archive")
    private let settings: FamilyPrivacySettings
    private let directory: URL
    private let keyProvider: () throws -> Data
    private let now: () -> TimeInterval
    private var pending = 0
    private let admissionLock = NSLock()
    static let maximumCount = 500
    static let maximumBytes = 2_500_000
    static let retention: TimeInterval = 30 * 86400

    private convenience init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.init(directory: base.appendingPathComponent("FamilyDeletedArchive", isDirectory: true), settings: .shared, keyProvider: Self.deviceKey)
    }
    init(directory: URL, settings: FamilyPrivacySettings, keyProvider: @escaping () throws -> Data, now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        self.directory = directory; self.settings = settings; self.keyProvider = keyProvider; self.now = now
    }
    private var file: URL { directory.appendingPathComponent("received-text.aesgcm") }

    static func bounded(_ values: [FamilyArchivedMessage], now: TimeInterval) -> [FamilyArchivedMessage] {
        var seen = Set<String>()
        var result: [FamilyArchivedMessage] = []
        var size = 0
        for value in values.sorted(by: { $0.deletedAt > $1.deletedAt }) {
            guard !value.text.isEmpty, value.deletedAt <= now + 60, value.deletedAt >= now - retention,
                  value.accountID.count <= 32, value.peerID.count <= 32, value.messageID > 0 else { continue }
            let id = "\(value.accountID):\(value.peerID):\(value.messageID)"
            guard seen.insert(id).inserted else { continue }
            let clipped = FamilyArchivedMessage(accountID: value.accountID, peerID: value.peerID, messageID: value.messageID, timestamp: value.timestamp, deletedAt: value.deletedAt, text: value.text)
            let cost = clipped.text.utf8.count + 256
            guard result.count < maximumCount, size + cost <= 2_000_000 else { continue }
            result.append(clipped); size += cost
        }
        return result
    }

    /// Admission is bounded even if deletions arrive faster than the disk. Called
    /// only after the corresponding Postbox transaction has committed.
    public func record(_ values: [FamilyArchivedMessage]) {
        guard settings.isEnabled(.archiveDeletedMessages), !values.isEmpty else { return }
        admissionLock.lock()
        guard pending < 8 else { admissionLock.unlock(); return }
        pending += 1; admissionLock.unlock()
        let clipped = Array(values.prefix(256))
        queue.async {
            defer { self.admissionLock.lock(); self.pending -= 1; self.admissionLock.unlock() }
            guard self.settings.isEnabled(.archiveDeletedMessages) else { return }
            do {
                let key = SymmetricKey(data: try self.keyProvider())
                let old = try self.read(key: key)
                let data = try JSONEncoder().encode(Self.bounded(clipped + old, now: self.now()))
                let sealed = try AES.GCM.seal(data, using: key, authenticating: Data("FamilyDeletedArchive/v1".utf8))
                guard let encrypted = sealed.combined, encrypted.count <= Self.maximumBytes else { return }
                try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
                var excluded = URLResourceValues(); excluded.isExcludedFromBackup = true
                var directory = self.directory; try directory.setResourceValues(excluded)
                #if os(iOS)
                try encrypted.write(to: self.file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                #else
                try encrypted.write(to: self.file, options: .atomic)
                #endif
                try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: self.file.path)
            } catch { /* Archive failure never changes Telegram's transaction or logs content. */ }
        }
    }
    private func read(key: SymmetricKey) throws -> [FamilyArchivedMessage] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let attrs = try FileManager.default.attributesOfItem(atPath: file.path)
        guard let size = attrs[.size] as? NSNumber, size.intValue <= Self.maximumBytes else { throw ArchiveError.invalid }
        let encrypted = try Data(contentsOf: file)
        let data = try AES.GCM.open(AES.GCM.SealedBox(combined: encrypted), using: key, authenticating: Data("FamilyDeletedArchive/v1".utf8))
        return Self.bounded(try JSONDecoder().decode([FamilyArchivedMessage].self, from: data), now: now())
    }
    public func messages(accountID: String, completion: @escaping ([FamilyArchivedMessage]) -> Void) {
        queue.async {
            let result = (try? self.read(key: SymmetricKey(data: self.keyProvider()))) ?? []
            DispatchQueue.main.async { completion(result.filter { $0.accountID == accountID }) }
        }
    }
    public func clear(accountID: String? = nil, completion: (() -> Void)? = nil) {
        queue.async {
            if let accountID = accountID {
                do {
                    let key = SymmetricKey(data: try self.keyProvider())
                    let remaining = try self.read(key: key).filter { $0.accountID != accountID }
                    if remaining.isEmpty { try? FileManager.default.removeItem(at: self.file) }
                    else {
                        let sealed = try AES.GCM.seal(JSONEncoder().encode(remaining), using: key, authenticating: Data("FamilyDeletedArchive/v1".utf8))
                        if let data = sealed.combined {
                            #if os(iOS)
                            try data.write(to: self.file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                            #else
                            try data.write(to: self.file, options: .atomic)
                            #endif
                            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: self.file.path)
                        }
                    }
                } catch { /* Fixed failure semantics; no plaintext fallback. */ }
            } else { try? FileManager.default.removeItem(at: self.file) }
            if let completion = completion { DispatchQueue.main.async(execute: completion) }
        }
    }
    private enum ArchiveError: Error { case invalid, keychain }
    private static func deviceKey() throws -> Data {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.salatik.tgru.received-text.v1", kSecAttrAccount as String: "local-key"]
        var read = query; read[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, data.count == 32 { return data }
        guard status == errSecItemNotFound else { throw ArchiveError.keychain }
        var bytes = Data(count: 32)
        let randomStatus = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw ArchiveError.keychain }
        var add = query; add[kSecValueData as String] = bytes
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(add as CFDictionary, nil)
        if added == errSecDuplicateItem { return try deviceKey() }
        guard added == errSecSuccess else { throw ArchiveError.keychain }
        return bytes
    }
}
