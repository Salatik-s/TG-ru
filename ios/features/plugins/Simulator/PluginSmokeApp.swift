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
    private var download: TgruPluginDownload?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        self.window = window
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()
        UserDefaults.standard.removeObject(forKey: "tgru.plugins.v1")
        download = TgruPluginDownload(repository: ("Salatik-s", "TG-ru-plugins")) { [weak self] result in
            guard let self else { return }
            self.download = nil
            do {
                let (source, manifest) = try result.get()
                try self.check(manifest.id == "quick-replies", "Public GitHub repository returned the example manifest")
                try self.store.save(TgruInstalledPlugin(source: source, manifest: manifest, enabled: true))
                self.showPlugins(window)
            } catch { self.finish(error: String(describing: error)) }
        }
        download?.start()
    }

    private func showPlugins(_ window: UIWindow) {
        let controller = TgruPluginController()
        let navigation = UINavigationController(rootViewController: controller)
        window.rootViewController = navigation
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.exercise(controller, navigation)
        }
    }

    private struct Failed: Error { let detail: String }
    private func check(_ condition: Bool, _ description: String) throws {
        guard condition else { throw Failed(detail: description) }
        checks.append(description)
    }

    private func exercise(_ controller: TgruPluginController, _ navigation: UINavigationController) {
        do {
            controller.loadViewIfNeeded()
            try check(controller.title == "Плагины TG-ru", "Plugin screen rendered")
            try check(controller.tableView(controller.tableView, numberOfRowsInSection: 1) == 1, "Installed manifest displayed")
            let sourceRow = controller.tableView(controller.tableView, cellForRowAt: IndexPath(row: 0, section: 1))
            try check(sourceRow.detailTextLabel?.text?.lowercased().contains("salatik-s/tg-ru-plugins") == true, "Source and version visible")
            controller.tableView(controller.tableView, didSelectRowAt: IndexPath(row: 0, section: 1))
            navigation.view.layoutIfNeeded()
            guard let detail = navigation.topViewController as? UITableViewController, detail !== controller else {
                throw Failed(detail: "Plugin details did not open")
            }
            // A push sets topViewController before UIKit attaches its view. A user
            // cannot tap a template until that transition has finished.
            waitUntil("Plugin details attached after navigation", condition: {
                detail.viewIfLoaded?.window != nil && navigation.transitionCoordinator == nil
            }) { [weak self] in self?.exerciseDetails(detail, navigation) }
        } catch { finish(error: String(describing: error)) }
    }

    private func exerciseDetails(_ detail: UITableViewController, _ navigation: UINavigationController) {
        do {
            try check(detail.tableView(detail.tableView, numberOfRowsInSection: 1) == 3, "All three templates available")
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
            waitUntil("Template preview presented", condition: {
                guard let preview = detail.presentedViewController as? UIAlertController else { return false }
                return preview.viewIfLoaded?.window != nil && !preview.isBeingPresented
            }) { [weak self] in self?.verifyPreview(detail, navigation) }
        } catch { finish(error: String(describing: error)) }
    }

    private func verifyPreview(_ detail: UITableViewController, _ navigation: UINavigationController) {
        do {
            guard let preview = detail.presentedViewController as? UIAlertController else {
                throw Failed(detail: "Template preview missing after presentation")
            }
            try check(preview.message == "Получил, спасибо! Посмотрю и вернусь с ответом.", "Template content shown")
            try check(preview.actions.contains { $0.title == "Скопировать" }, "Explicit copy action offered")
            preview.dismiss(animated: false) { [weak self] in
                guard let self else { return }
                navigation.popToRootViewController(animated: false)
                do {
                    try self.check(self.store.plugins().count == 1, "Plugin retained after reopening")
                    self.finish(error: nil)
                } catch { self.finish(error: String(describing: error)) }
            }
        } catch { finish(error: String(describing: error)) }
    }

    private func waitUntil(_ description: String, remaining: Int = 50,
                           condition: @escaping () -> Bool, completion: @escaping () -> Void) {
        if condition() {
            checks.append(description)
            completion()
        } else if remaining == 0 {
            finish(error: "Timed out: " + description)
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.waitUntil(description, remaining: remaining - 1, condition: condition, completion: completion)
            }
        }
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
