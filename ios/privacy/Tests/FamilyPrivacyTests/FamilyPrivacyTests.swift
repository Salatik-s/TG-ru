import Foundation
import XCTest
@testable import FamilyPrivacy

final class FamilyPrivacyTests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!
    private var settings: FamilyPrivacySettings!
    override func setUp() {
        super.setUp()
        suite = "FamilyPrivacyTests." + UUID().uuidString
        defaults = UserDefaults(suiteName: suite)!
        settings = FamilyPrivacySettings(defaults: defaults)
    }
    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }
    func testDefaultsOffAndExplicitChoicePersists() {
        for feature in FamilyPrivacyFeature.allCases { XCTAssertFalse(settings.isEnabled(feature)) }
        settings.setEnabled(true, for: .hideTyping)
        let reopened = FamilyPrivacySettings(defaults: UserDefaults(suiteName: suite)!)
        XCTAssertTrue(reopened.isEnabled(.hideTyping))
        XCTAssertFalse(reopened.isEnabled(.archiveDeletedMessages))
    }
    func testNoFakeGlobalGhostAndExactFunctionNames() {
        for feature in FamilyPrivacyFeature.allCases { settings.setEnabled(true, for: feature) }
        for name in ["updates.getDifference", "messages.sendMessage", "messages.readEncryptedHistory", "messages.setEncryptedTyping", "account.updateStatus.extra", "READHISTORY", "readHistory", "stories.getStoriesByID"] {
            XCTAssertFalse(settings.shouldSuppress(method: name, online: true, readEligible: true), name)
        }
        XCTAssertTrue(settings.shouldSuppress(method: "messages.setTyping"))
        XCTAssertTrue(settings.shouldSuppress(method: "stories.readStories"))
        XCTAssertTrue(settings.shouldSuppress(method: "stories.incrementStoryViews"))
    }
    func testOfflineAndUnknownStatusAlwaysPass() {
        settings.setEnabled(true, for: .hideOnline)
        XCTAssertTrue(settings.shouldSuppress(method: "account.updateStatus", online: true))
        XCTAssertFalse(settings.shouldSuppress(method: "account.updateStatus", online: false))
        XCTAssertFalse(settings.shouldSuppress(method: "account.updateStatus"))
    }
    func testEveryReadRequiresProvenOrdinaryContext() {
        settings.setEnabled(true, for: .hideReadReceipts)
        for name in ["messages.readHistory", "channels.readHistory", "messages.readMessageContents", "channels.readMessageContents", "messages.readDiscussion"] {
            XCTAssertFalse(settings.shouldSuppress(method: name), name)
            XCTAssertFalse(settings.shouldSuppress(method: name, readEligible: false), name)
            XCTAssertTrue(settings.shouldSuppress(method: name, readEligible: true), name)
        }
        settings.setEnabled(false, for: .hideReadReceipts)
        XCTAssertFalse(settings.shouldSuppress(method: "messages.readHistory", readEligible: true))
    }
    private func item(_ id: Int32, account: String = "1", text: String = "fixture text", at: TimeInterval = 10000000) -> FamilyArchivedMessage {
        FamilyArchivedMessage(accountID: account, peerID: "2", messageID: id, timestamp: 1, deletedAt: at, text: text)
    }
    func testBoundsDeduplicationRetentionAndUnicodeClipping() {
        let now: TimeInterval = 10000000
        let items = (1...900).map { item(Int32($0), text: String(repeating: "я", count: 9000)) }
        let result = FamilyDeletedArchive.bounded(items + [items[0], item(1000, at: 1), item(1001, at: now + 61)], now: now)
        XCTAssertLessThanOrEqual(result.count, 500)
        XCTAssertLessThanOrEqual(result.reduce(0) { $0 + $1.text.utf8.count + 256 }, 2_000_000)
        XCTAssertTrue(result.allSatisfy { $0.text.count == 4096 })
        XCTAssertFalse(result.contains { $0.messageID >= 1000 })
        XCTAssertEqual(Set(result.map { $0.messageID }).count, result.count)
    }
    func testArchiveIsEncryptedScopedAndCanClearOneAccount() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = Data(repeating: 7, count: 32)
        settings.setEnabled(true, for: .archiveDeletedMessages)
        let archive = FamilyDeletedArchive(directory: directory, settings: settings, keyProvider: { key }, now: { 10000000 })
        archive.record([item(1, text: "known private fixture phrase"), item(2, account: "other")])
        let first = expectation(description: "recorded")
        archive.messages(accountID: "1") { values in
            XCTAssertTrue(Thread.isMainThread)
            XCTAssertEqual(values.map { $0.messageID }, [1]); first.fulfill()
        }
        wait(for: [first], timeout: 5)
        let bytes = try Data(contentsOf: directory.appendingPathComponent("received-text.aesgcm"))
        XCTAssertNil(bytes.range(of: Data("known private fixture phrase".utf8)))
        let cleared = expectation(description: "cleared")
        archive.clear(accountID: "1") { cleared.fulfill() }
        wait(for: [cleared], timeout: 5)
        let second = expectation(description: "other retained")
        archive.messages(accountID: "other") { values in XCTAssertEqual(values.map { $0.messageID }, [2]); second.fulfill() }
        wait(for: [second], timeout: 5)
        let third = expectation(description: "own removed")
        archive.messages(accountID: "1") { values in XCTAssertTrue(values.isEmpty); third.fulfill() }
        wait(for: [third], timeout: 5)
    }
    func testDisabledArchiveDoesNotCreateFile() {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let archive = FamilyDeletedArchive(directory: directory, settings: settings, keyProvider: { Data(repeating: 1, count: 32) })
        archive.record([item(1)])
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }
    func testWrongKeyAndTamperedArchiveHaveNoPlaintextFallback() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        settings.setEnabled(true, for: .archiveDeletedMessages)
        let archive = FamilyDeletedArchive(directory: directory, settings: settings, keyProvider: { Data(repeating: 1, count: 32) }, now: { 10000000 })
        archive.record([item(1)])
        let saved = expectation(description: "saved")
        archive.messages(accountID: "1") { values in XCTAssertEqual(values.count, 1); saved.fulfill() }
        wait(for: [saved], timeout: 5)
        let wrong = FamilyDeletedArchive(directory: directory, settings: settings, keyProvider: { Data(repeating: 2, count: 32) }, now: { 10000000 })
        let rejected = expectation(description: "wrong key")
        wrong.messages(accountID: "1") { values in XCTAssertTrue(values.isEmpty); rejected.fulfill() }
        wait(for: [rejected], timeout: 5)
        let file = directory.appendingPathComponent("received-text.aesgcm")
        var bytes = try Data(contentsOf: file); bytes[bytes.count - 1] ^= 1; try bytes.write(to: file)
        let tampered = expectation(description: "tampered")
        archive.messages(accountID: "1") { values in XCTAssertTrue(values.isEmpty); tampered.fulfill() }
        wait(for: [tampered], timeout: 5)
    }
}
