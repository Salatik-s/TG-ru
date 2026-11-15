import UIKit

@main
final class PluginSmokeApp: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Smoke", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = PluginSmokeScene.self
        return configuration
    }
}

final class PluginSmokeScene: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var checks: [String] = []
    private let store = TgruPluginStore()

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        self.window = window
        do {
            UserDefaults.standard.removeObject(forKey: "tgru.plugins.v1")
            let source = try TgruPluginSource("https://github.com/test-author/test-plugin/blob/\(String(repeating: "a", count: 40))/tgru-plugin.json")
            let data = try JSONSerialization.data(withJSONObject: [
                "schemaVersion": 1, "id": "smoke-test", "name": "Быстрые ответы",
                "version": "1.0.0", "description": "Проверка плагинов TG-ru",
                "templates": [["name": "Приветствие", "text": "Привет!"], ["name": "Ответ", "text": "Спасибо, отвечу позже."]]
            ])
            try store.save(TgruInstalledPlugin(source: source, manifest: TgruPluginManifest.parse(data), enabled: true))
            let controller = TgruPluginController()
            let navigation = UINavigationController(rootViewController: controller)
            window.rootViewController = navigation
            window.makeKeyAndVisible()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                self?.exercise(controller, navigation)
            }
        } catch { finish(error: String(describing: error)) }
    }

    private struct Failed: Error { let detail: String }
    private func check(_ condition: Bool, _ description: String) throws {
        guard condition else { throw Failed(detail: description) }
        checks.append(description)
    }

    private func exercise(_ controller: TgruPluginController, _ navigation: UINavigationController) {
        UIView.setAnimationsEnabled(false)
        defer { UIView.setAnimationsEnabled(true) }
        do {
            controller.loadViewIfNeeded()
            try check(controller.title == "Плагины TG-ru", "Plugin screen rendered")
            try check(controller.tableView(controller.tableView, numberOfRowsInSection: 1) == 1, "Installed manifest displayed")
            let sourceRow = controller.tableView(controller.tableView, cellForRowAt: IndexPath(row: 0, section: 1))
            try check(sourceRow.detailTextLabel?.text?.contains("test-author/test-plugin") == true, "Source and version visible")
            controller.tableView(controller.tableView, didSelectRowAt: IndexPath(row: 0, section: 1))
            navigation.view.layoutIfNeeded()
            guard let detail = navigation.topViewController as? UITableViewController, detail !== controller else {
                throw Failed(detail: "Plugin details did not open")
            }
            detail.loadViewIfNeeded()
            try check(detail.tableView(detail.tableView, numberOfRowsInSection: 1) == 2, "Both templates available")
            let cell = detail.tableView(detail.tableView, cellForRowAt: IndexPath(row: 0, section: 0))
            guard let toggle = cell.accessoryView as? UISwitch else { throw Failed(detail: "Enable switch missing") }
            toggle.isOn = false
            toggle.sendActions(for: .valueChanged)
            try check(!store.plugins()[0].enabled, "Disable persisted")
            try check(detail.tableView(detail.tableView, numberOfRowsInSection: 1) == 0, "Disabled templates hidden")
            toggle.isOn = true
            toggle.sendActions(for: .valueChanged)
            try check(store.plugins()[0].enabled, "Enable persisted")
            detail.tableView.delegate?.tableView?(detail.tableView, didSelectRowAt: IndexPath(row: 0, section: 1))
            guard let preview = detail.presentedViewController as? UIAlertController else {
                throw Failed(detail: "Template preview missing")
            }
            try check(preview.message == "Привет!", "Template content shown")
            try check(preview.actions.contains { $0.title == "Скопировать" }, "Explicit copy action offered")
            preview.dismiss(animated: false)
            navigation.popToRootViewController(animated: false)
            try check(store.plugins().count == 1, "Plugin retained after reopening")
            finish(error: nil)
        } catch { finish(error: String(describing: error)) }
    }

    private func finish(error: String?) {
        let result: [String: Any] = ["ok": error == nil, "checks": checks, "error": error ?? ""]
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        do {
            try FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
            try JSONSerialization.data(withJSONObject: result, options: .prettyPrinted)
                .write(to: documents.appendingPathComponent("smoke-result.json"), options: .atomic)
            print("TGRU_PLUGIN_SIMULATOR_RESULT \(error == nil ? "PASS" : "FAIL")")
        } catch { print("TGRU_PLUGIN_SIMULATOR_RESULT_WRITE_FAILED") }
    }
}
