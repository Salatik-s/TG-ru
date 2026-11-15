import Foundation
import XCTest
@testable import FamilyPrivacy

final class FamilyPrivacyTests: XCTestCase {
    func testRetiredSettingsCannotBeEnabledByOldPreferencesOrControls() {
        let suite = "RetiredPrivacy." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        for feature in FamilyPrivacyFeature.allCases {
            defaults.set(true, forKey: "family.privacy.v1." + feature.rawValue)
        }
        let settings = FamilyPrivacySettings(defaults: defaults)
        for feature in FamilyPrivacyFeature.allCases {
            XCTAssertNil(defaults.object(forKey: "family.privacy.v1." + feature.rawValue))
            settings.setEnabled(true, for: feature)
            XCTAssertFalse(settings.isEnabled(feature))
            defaults.set(true, forKey: "family.privacy.v1." + feature.rawValue)
            XCTAssertFalse(settings.isEnabled(feature))
        }
        for method in ["messages.setTyping", "account.updateStatus", "stories.readStories", "stories.incrementStoryViews", "messages.readHistory", "channels.readHistory", "messages.readDiscussion", "messages.readMessageContents", "channels.readMessageContents"] {
            XCTAssertFalse(settings.shouldSuppress(method: method, online: true, readEligible: true), method)
        }
    }

    func testRetiredArchiveDoesNotWriteReceivedText() {
        let suite = "RetiredArchive." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = FamilyPrivacySettings(defaults: defaults)
        settings.setEnabled(true, for: .archiveDeletedMessages)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let archive = FamilyDeletedArchive(directory: directory, settings: settings, keyProvider: { Data(repeating: 1, count: 32) })
        archive.record([FamilyArchivedMessage(accountID: "1", peerID: "2", messageID: 1, timestamp: 1, deletedAt: 10000000, text: "not retained")])
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }
}
