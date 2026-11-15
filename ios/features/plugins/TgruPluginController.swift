import UIKit

final class TgruPluginController: UITableViewController {
    private let store = TgruPluginStore()
    private var plugins: [TgruInstalledPlugin] = []
    private var download: TgruPluginDownload?

    init() { super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { return nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Плагины TG-ru"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 64
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        plugins = store.plugins()
        tableView.reloadData()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isMovingFromParent || navigationController?.isBeingDismissed == true { download?.cancel() }
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        section == 0 ? 2 : plugins.count
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 1 ? "Установленные" : nil
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return "Плагины этой версии добавляют наборы текстовых шаблонов. Выберите шаблон, скопируйте и вставьте его в сообщение." }
        return plugins.isEmpty ? "Установленных плагинов пока нет." : "Откройте плагин, чтобы выбрать шаблон, выключить или удалить его."
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1)
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.accessoryType = .disclosureIndicator
        if indexPath.section == 0 {
            cell.textLabel?.text = indexPath.row == 0 ? (download == nil ? "Установить по ссылке GitHub" : "Получаем описание плагина…") : "Как создать свой плагин"
            cell.isUserInteractionEnabled = download == nil || indexPath.row != 0
        } else {
            let plugin = plugins[indexPath.row]
            cell.textLabel?.text = plugin.manifest.name
            cell.detailTextLabel?.text = "\(plugin.source.owner)/\(plugin.source.repository) · \(plugin.manifest.version) · \(plugin.enabled ? "Включён" : "Выключен")"
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 1 {
            navigationController?.pushViewController(TgruPluginDetailController(plugin: plugins[indexPath.row], store: store), animated: true)
        } else if indexPath.row == 0 {
            askForLink()
        } else {
            UIApplication.shared.open(URL(string: "https://github.com/Salatik-s/TG-ru-plugins")!)
        }
    }

    private func askForLink() {
        guard download == nil else { return }
        let alert = UIAlertController(title: "Ссылка на плагин", message: "Вставьте ссылку на репозиторий GitHub с tgru-plugin.json в корне или постоянную ссылку на этот файл.", preferredStyle: .alert)
        alert.addTextField {
            $0.placeholder = "https://github.com/автор/плагин"
            $0.keyboardType = .URL
            $0.autocorrectionType = .no
            $0.autocapitalizationType = .none
        }
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Проверить", style: .default) { [weak self, weak alert] _ in
            guard let self, let value = alert?.textFields?.first?.text else { return }
            self.fetch(value)
        })
        present(alert, animated: true)
    }

    private func fetch(_ value: String) {
        let completed: (Result<(TgruPluginSource, TgruPluginManifest), Error>) -> Void = { [weak self] result in
            guard let self else { return }
            self.download = nil
            self.tableView.reloadData()
            guard self.viewIfLoaded?.window != nil else { return }
            switch result {
            case .success(let (source, manifest)): self.preview(source, manifest)
            case .failure: self.showError("Не удалось получить плагин. Проверьте ссылку и подключение. Поддерживается формат TG-ru 1, файл до 16 КБ.")
            }
        }
        let download: TgruPluginDownload
        do {
            if let source = try? TgruPluginSource(value) {
                download = TgruPluginDownload(source: source, completion: completed)
            } else {
                download = TgruPluginDownload(repository: try TgruPluginSource.repositoryRoot(value), completion: completed)
            }
        } catch {
            showError("Нужна ссылка на репозиторий GitHub или постоянная ссылка на tgru-plugin.json. Инструкция есть в разделе «Как создать свой плагин».")
            return
        }
        self.download = download
        tableView.reloadData()
        download.start()
    }

    private func preview(_ source: TgruPluginSource, _ manifest: TgruPluginManifest) {
        let plugin = TgruInstalledPlugin(source: source, manifest: manifest, enabled: true)
        let previous = store.plugins().first { $0.key == plugin.key }
        let summary = "\(manifest.description)\n\nВерсия \(manifest.version) · шаблонов \(manifest.templates.count)\nИсточник: \(source.owner)/\(source.repository)\nКоммит: \(source.commit)\n\nПосле установки шаблоны будут доступны в этом разделе."
        let alert = UIAlertController(title: manifest.name, message: summary, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: previous == nil ? "Установить" : "Заменить установленную версию", style: .default) { [weak self] _ in
            guard let self else { return }
            do {
                var installed = plugin
                installed.enabled = previous?.enabled ?? true
                try self.store.save(installed)
                self.plugins = self.store.plugins()
                self.tableView.reloadData()
            } catch { self.showError("Не удалось сохранить плагин. Можно установить до 20 плагинов. Удалите ненужный и попробуйте снова.") }
        })
        present(alert, animated: true)
    }

    private func showError(_ message: String) {
        let alert = UIAlertController(title: "Плагины TG-ru", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Закрыть", style: .cancel))
        present(alert, animated: true)
    }
}

private final class TgruPluginDetailController: UITableViewController {
    private var plugin: TgruInstalledPlugin
    private let store: TgruPluginStore
    init(plugin: TgruInstalledPlugin, store: TgruPluginStore) {
        self.plugin = plugin
        self.store = store
        super.init(style: .insetGrouped)
    }
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = plugin.manifest.name
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 58
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 3 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        section == 1 ? (plugin.enabled ? plugin.manifest.templates.count : 0) : 1
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 0 ? "\(plugin.manifest.description)\n\(plugin.source.owner)/\(plugin.source.repository) · \(plugin.manifest.version)" : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        switch indexPath.section {
        case 0:
            cell.textLabel?.text = "Включён"
            let toggle = UISwitch()
            toggle.isOn = plugin.enabled
            toggle.accessibilityLabel = "Плагин включён"
            toggle.addTarget(self, action: #selector(changed(_:)), for: .valueChanged)
            cell.accessoryView = toggle
            cell.selectionStyle = .none
        case 1:
            cell.textLabel?.text = plugin.manifest.templates[indexPath.row].name
            cell.accessoryType = .disclosureIndicator
        default:
            cell.textLabel?.text = "Удалить плагин"
            cell.textLabel?.textColor = .systemRed
        }
        return cell
    }
    @objc private func changed(_ toggle: UISwitch) {
        plugin.enabled = toggle.isOn
        do { try store.save(plugin) }
        catch { plugin.enabled.toggle(); toggle.isOn = plugin.enabled }
        tableView.reloadData()
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 1 && plugin.enabled {
            let template = plugin.manifest.templates[indexPath.row]
            let alert = UIAlertController(title: template.name, message: template.text, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
            alert.addAction(UIAlertAction(title: "Скопировать", style: .default) { _ in UIPasteboard.general.string = template.text })
            present(alert, animated: true)
        } else if indexPath.section == 2 {
            let alert = UIAlertController(title: "Удалить плагин?", message: plugin.manifest.name, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
            alert.addAction(UIAlertAction(title: "Удалить", style: .destructive) { [weak self] _ in
                guard let self else { return }
                do {
                    try self.store.remove(self.plugin.key)
                    self.navigationController?.popViewController(animated: true)
                } catch {}
            })
            present(alert, animated: true)
        }
    }
}
