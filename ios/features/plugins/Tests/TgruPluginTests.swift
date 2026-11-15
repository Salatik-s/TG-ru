import Foundation
import XCTest
@testable import TgruPlugins

final class TgruPluginTests: XCTestCase {
    private let commit = String(repeating: "a", count: 40)
    private var manifest: [String: Any] { [
        "schemaVersion": 1, "id": "quick-replies", "name": "Ответы",
        "version": "1.0.0", "description": "Короткие ответы",
        "templates": [["name": "Приветствие", "text": "Привет!\nКак дела?"]]
    ] }
    private func data(_ object: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: object) }

    func testPinnedSourcesAndRoot() throws {
        let source = try TgruPluginSource("https://github.com/author/plugin/blob/\(commit)/tgru-plugin.json")
        XCTAssertEqual(source.rawURL.absoluteString, "https://raw.githubusercontent.com/author/plugin/\(commit)/tgru-plugin.json")
        XCTAssertEqual(try TgruPluginSource.repositoryRoot("https://github.com/author/plugin/").repository, "plugin")
        for value in [
            "http://github.com/author/plugin",
            "https://github.com/author/plugin?x=1",
            "https://github.com/author/plugin/tree/main",
            "https://github.com@evil.test/author/plugin",
            "https://github.com:443/author/plugin",
            "https://github.com/author/%70lugin"
        ] { XCTAssertThrowsError(try TgruPluginSource.repositoryRoot(value)) }
        for value in [
            "https://raw.githubusercontent.com/author/plugin/main/tgru-plugin.json",
            "https://github.com/author/plugin/blob/\(commit)/tgru-plugin.json?raw=1",
            "https://github.com/author/plugin/blob/\(commit)/other.json"
        ] { XCTAssertThrowsError(try TgruPluginSource(value)) }
    }

    func testManifestRejectsExecutionAndOversize() throws {
        XCTAssertEqual(try TgruPluginManifest.parse(data(manifest)).templates[0].text, "Привет!\nКак дела?")
        for key in ["script", "permissions", "url"] {
            var value = manifest
            value[key] = "anything"
            XCTAssertThrowsError(try TgruPluginManifest.parse(data(value)))
        }
        var value = manifest
        value["schemaVersion"] = true
        XCTAssertThrowsError(try TgruPluginManifest.parse(data(value)))
        value = manifest
        value["id"] = "valid-id\n"
        XCTAssertThrowsError(try TgruPluginManifest.parse(data(value)))
        value = manifest
        value["version"] = "1.0.0\n"
        XCTAssertThrowsError(try TgruPluginManifest.parse(data(value)))
        value = manifest
        value["templates"] = [["name": "Long", "text": String(repeating: "x", count: 1001)]]
        XCTAssertThrowsError(try TgruPluginManifest.parse(data(value)))
        value["templates"] = [["name": "Control", "text": "\u{0080}"]]
        XCTAssertThrowsError(try TgruPluginManifest.parse(data(value)))
        XCTAssertThrowsError(try TgruPluginManifest.parse(Data(repeating: 32, count: 16385)))
    }

    func testInstallReplaceToggleRemoveAndSourcesStaySeparate() throws {
        let suite = "tgru-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = TgruPluginStore(defaults: defaults)
        let source = try TgruPluginSource("https://raw.githubusercontent.com/author/plugin/\(commit)/tgru-plugin.json")
        var plugin = TgruInstalledPlugin(source: source, manifest: try TgruPluginManifest.parse(data(manifest)), enabled: true)
        try store.save(plugin)
        XCTAssertEqual(store.plugins().count, 1)
        plugin.enabled = false
        try store.save(plugin)
        XCTAssertEqual(store.plugins().count, 1)
        XCTAssertFalse(store.plugins()[0].enabled)
        let otherSource = try TgruPluginSource("https://raw.githubusercontent.com/other/plugin/\(commit)/tgru-plugin.json")
        try store.save(TgruInstalledPlugin(source: otherSource, manifest: plugin.manifest, enabled: true))
        XCTAssertEqual(store.plugins().count, 2)
        try store.remove(plugin.key)
        XCTAssertEqual(store.plugins().count, 1)
        XCTAssertEqual(store.plugins()[0].source.owner, "other")
    }
}
