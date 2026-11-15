import Foundation
import CoreFoundation

struct TgruPluginTemplate: Codable {
    let name: String
    let text: String
}

struct TgruPluginManifest: Codable {
    let schemaVersion: Int
    let id: String
    let name: String
    let version: String
    let description: String
    let templates: [TgruPluginTemplate]

    static let maximumBytes = 16 * 1024

    static func parse(_ data: Data) throws -> TgruPluginManifest {
        guard data.count <= maximumBytes,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set(["schemaVersion", "id", "name", "version", "description", "templates"]),
              let schema = object["schemaVersion"] as? NSNumber,
              CFGetTypeID(schema) != CFBooleanGetTypeID(), schema == 1,
              let id = object["id"] as? String, matches(id, "^[a-z][a-z0-9-]{2,47}$"),
              let name = object["name"] as? String, validText(name, maximum: 64),
              let version = object["version"] as? String, matches(version, "^[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}$"),
              let description = object["description"] as? String, validText(description, maximum: 240),
              let rawTemplates = object["templates"] as? [[String: Any]], (1...12).contains(rawTemplates.count)
        else { throw TgruPluginError.invalidManifest }
        let templates = try rawTemplates.map { raw -> TgruPluginTemplate in
            guard Set(raw.keys) == Set(["name", "text"]),
                  let name = raw["name"] as? String, validText(name, maximum: 64),
                  let text = raw["text"] as? String, validText(text, maximum: 1000, multiline: true)
            else { throw TgruPluginError.invalidManifest }
            return TgruPluginTemplate(name: name, text: text)
        }
        return TgruPluginManifest(schemaVersion: 1, id: id, name: name, version: version,
                                  description: description, templates: templates)
    }

    private static func matches(_ text: String, _ pattern: String) -> Bool {
        guard let range = text.range(of: pattern, options: .regularExpression) else { return false }
        return range == text.startIndex..<text.endIndex
    }

    private static func validText(_ text: String, maximum: Int, multiline: Bool = false) -> Bool {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf16.count <= maximum else { return false }
        return text.unicodeScalars.allSatisfy { scalar in
            scalar.value >= 32 && !(127...159).contains(scalar.value)
                || multiline && (scalar.value == 9 || scalar.value == 10 || scalar.value == 13)
        }
    }
}

enum TgruPluginError: Error {
    case invalidURL
    case invalidManifest
}

struct TgruPluginSource: Codable {
    let owner: String
    let repository: String
    let commit: String
    var rawURL: URL { URL(string: "https://raw.githubusercontent.com/\(owner)/\(repository)/\(commit)/tgru-plugin.json")! }
    var pageURL: URL { URL(string: "https://github.com/\(owner)/\(repository)/blob/\(commit)/tgru-plugin.json")! }

    static func repositoryRoot(_ value: String) throws -> (owner: String, repository: String) {
        guard let url = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", url.host?.lowercased() == "github.com",
              url.user == nil, url.password == nil, url.port == nil, url.query == nil, url.fragment == nil,
              !url.absoluteString.contains("%"), !url.absoluteString.contains("\\")
        else { throw TgruPluginError.invalidURL }
        var parts = url.path.components(separatedBy: "/")
        if parts.last == "" { parts.removeLast() }
        guard parts.count == 3 else { throw TgruPluginError.invalidURL }
        let checked = try TgruPluginSource("https://raw.githubusercontent.com/\(parts[1])/\(parts[2])/\(String(repeating: "0", count: 40))/tgru-plugin.json")
        return (checked.owner, checked.repository)
    }

    private enum CodingKeys: String, CodingKey { case owner, repository, commit }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let owner = try values.decode(String.self, forKey: .owner)
        let repository = try values.decode(String.self, forKey: .repository)
        let commit = try values.decode(String.self, forKey: .commit)
        try self.init("https://raw.githubusercontent.com/\(owner)/\(repository)/\(commit)/tgru-plugin.json")
    }

    init(_ value: String) throws {
        guard let url = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", url.user == nil, url.password == nil, url.port == nil,
              url.query == nil, url.fragment == nil,
              !url.absoluteString.contains("%"), !url.absoluteString.contains("\\")
        else { throw TgruPluginError.invalidURL }
        let parts = url.path.components(separatedBy: "/")
        let host = url.host?.lowercased()
        let commitIndex: Int
        if host == "github.com", parts.count == 6, parts[3] == "blob" { commitIndex = 4 }
        else if host == "raw.githubusercontent.com", parts.count == 5 { commitIndex = 3 }
        else { throw TgruPluginError.invalidURL }
        guard parts[0].isEmpty, parts.last == "tgru-plugin.json",
              parts[1].range(of: "^[A-Za-z0-9][A-Za-z0-9-]{0,38}$", options: .regularExpression) != nil,
              parts[2].range(of: "^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$", options: .regularExpression) != nil,
              parts[commitIndex].range(of: "^[a-fA-F0-9]{40}$", options: .regularExpression) != nil
        else { throw TgruPluginError.invalidURL }
        owner = parts[1]
        repository = parts[2]
        commit = parts[commitIndex].lowercased()
    }
}

struct TgruInstalledPlugin: Codable {
    let source: TgruPluginSource
    let manifest: TgruPluginManifest
    var enabled: Bool
    var key: String { source.owner.lowercased() + "/" + source.repository.lowercased() + "/" + manifest.id }
}

final class TgruPluginStore {
    private let defaults: UserDefaults
    private let storageKey = "tgru.plugins.v1"
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func plugins() -> [TgruInstalledPlugin] {
        guard let data = defaults.data(forKey: storageKey), data.count <= 1024 * 1024,
              let values = try? JSONDecoder().decode([TgruInstalledPlugin].self, from: data), values.count <= 20
        else { return [] }
        return values.filter { value in
            guard let source = try? TgruPluginSource(value.source.pageURL.absoluteString),
                  source.rawURL == value.source.rawURL,
                  let data = try? JSONEncoder().encode(value.manifest),
                  (try? TgruPluginManifest.parse(data)) != nil else { return false }
            return true
        }
    }

    func save(_ plugin: TgruInstalledPlugin) throws {
        _ = try TgruPluginManifest.parse(JSONEncoder().encode(plugin.manifest))
        var values = plugins()
        if let index = values.firstIndex(where: { $0.key == plugin.key }) {
            values[index] = plugin
        } else {
            guard values.count < 20 else { throw TgruPluginError.invalidManifest }
            values.append(plugin)
        }
        defaults.set(try JSONEncoder().encode(values), forKey: storageKey)
    }

    func remove(_ key: String) throws {
        defaults.set(try JSONEncoder().encode(plugins().filter { $0.key != key }), forKey: storageKey)
    }
}
