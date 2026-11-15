import XCTest
import UIKit
import ChatListUI
import FamilyPrivacy

/// Add to an iOS-hosted XCTest target linked against the patched ChatListUI module.
/// These are real UIKit view/control tests, prepared but not executed on Windows.
final class FeaturesUIContracts: XCTestCase {
    private let keys = ["family.features.localSpeech", "family.features.documents", "family.features.python"]
    private var saved: [String: Any] = [:]
    private var privacySaved: [FamilyPrivacyFeature: Bool] = [:]
    override func setUp() {
        super.setUp()
        for key in keys {
            if let value = UserDefaults.standard.object(forKey: key) { saved[key] = value }
            UserDefaults.standard.removeObject(forKey: key)
        }
        for feature in FamilyPrivacyFeature.allCases {
            privacySaved[feature] = FamilyPrivacySettings.shared.isEnabled(feature)
            FamilyPrivacySettings.shared.setEnabled(false, for: feature)
        }
    }
    override func tearDown() {
        for key in keys {
            if let value = saved[key] { UserDefaults.standard.set(value, forKey: key) }
            else { UserDefaults.standard.removeObject(forKey: key) }
        }
        saved.removeAll(); super.tearDown()
        for (feature, value) in privacySaved { FamilyPrivacySettings.shared.setEnabled(value, for: feature) }
        privacySaved.removeAll()
    }
    @MainActor func testRetiredControlsAreAbsent() {
        let settings = FamilyFeatureSettingsController(accountID: "qa-account")
        settings.loadViewIfNeeded()
        for section in 0..<settings.numberOfSections(in: settings.tableView) {
            let cell = settings.tableView(settings.tableView, cellForRowAt: IndexPath(row: 0, section: section))
            let label = cell.textLabel?.text ?? ""
            XCTAssertFalse(label.contains("Удалённые"))
            XCTAssertFalse(label.contains("Не отправлять"))
            XCTAssertFalse(label.contains("Сохранять удалённый"))
        }
    }
    @MainActor func testNativeSettingsAreReachableAndOptIn() throws {
        let settings = FamilyFeatureSettingsController(accountID: "qa-account")
        let navigation = UINavigationController(rootViewController: settings)
        navigation.loadViewIfNeeded(); settings.loadViewIfNeeded()
        XCTAssertEqual(navigation.viewControllers.first, settings)
        XCTAssertEqual(settings.title, "Дополнения")
        XCTAssertEqual(settings.numberOfSections(in: settings.tableView), 5)
        XCTAssertNotNil(settings.navigationItem.leftBarButtonItem)
        for section in keys.indices {
            let cell = settings.tableView(settings.tableView, cellForRowAt: IndexPath(row: 0, section: section + 1))
            let toggle = try XCTUnwrap(cell.accessoryView as? UISwitch)
            XCTAssertFalse(toggle.isOn)
            XCTAssertFalse(try XCTUnwrap(toggle.accessibilityLabel).isEmpty)
            toggle.isOn = true; toggle.sendActions(for: .valueChanged)
            XCTAssertTrue(UserDefaults.standard.bool(forKey: keys[section]))
        }
        let reopened = FamilyFeatureSettingsController(accountID: "qa-account"); reopened.loadViewIfNeeded()
        for section in keys.indices {
            let cell = reopened.tableView(reopened.tableView, cellForRowAt: IndexPath(row: 0, section: section + 1))
            XCTAssertTrue(try XCTUnwrap(cell.accessoryView as? UISwitch).isOn)
        }
    }
    @MainActor func testDynamicTypeAndUnsupportedFeaturesAreNotAdvertised() throws {
        let settings = FamilyFeatureSettingsController(accountID: "qa-account"); settings.loadViewIfNeeded()
        for section in keys.indices {
            let cell = settings.tableView(settings.tableView, cellForRowAt: IndexPath(row: 0, section: section + 1))
            XCTAssertEqual(cell.textLabel?.numberOfLines, 0)
            XCTAssertEqual(cell.textLabel?.adjustsFontForContentSizeCategory, true)
            XCTAssertFalse((cell.textLabel?.text ?? "").lowercased().contains("ghost"))
            XCTAssertFalse((cell.textLabel?.text ?? "").lowercased().contains("плагин"))
        }
    }
    @MainActor func testPluginSectionOpensNativeInstaller() throws {
        let settings = FamilyFeatureSettingsController(accountID: "qa-account")
        let navigation = UINavigationController(rootViewController: settings)
        settings.loadViewIfNeeded()
        let cell = settings.tableView(settings.tableView, cellForRowAt: IndexPath(row: 0, section: 0))
        XCTAssertEqual(cell.textLabel?.text, "Плагины TG-ru")
        settings.tableView(settings.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        XCTAssertEqual(navigation.viewControllers.count, 2)
        navigation.topViewController?.loadViewIfNeeded()
        XCTAssertEqual(navigation.topViewController?.title, "Плагины TG-ru")
    }
}
