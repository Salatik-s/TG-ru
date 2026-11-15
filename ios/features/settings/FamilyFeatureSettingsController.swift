import Foundation
import UIKit

/// Presented by the chat-list's additional native ellipsis. Every switch has an integration hook.
@objc(FamilyFeatureSettingsController)
public final class FamilyFeatureSettingsController: UITableViewController {
    private let accountID: String
    private var checkingUpdate = false
    private let items: [(String, String, String)] = [
        ("family.features.localSpeech", "Расшифровка на устройстве", "Голосовые и кружки. Требуется поддержка языка в iOS; звук не отправляется на сервер распознавания."),
        ("family.features.documents", "Просмотр PDF", "Масштаб и переход к странице средствами iOS. Документы Word открываются в системном просмотрщике."),
        ("family.features.python", "Python в сообщениях", "Запуск выбранного кода на устройстве. До 10 секунд, без доступа к чатам и сети.")
    ]
    public init(accountID: String) { self.accountID = accountID; super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { return nil }
    override public func viewDidLoad() {
        super.viewDidLoad(); title = "Дополнения"
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .close, target: self, action: #selector(close))
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 88
    }
    @objc private func close() { dismiss(animated: true) }
    override public func numberOfSections(in tableView: UITableView) -> Int { return items.count + 2 }
    override public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { return 1 }
    override public func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return "Установка собственных наборов текстовых шаблонов из GitHub." }
        let section = section - 1
        if section < items.count { return items[section].2 }
        return "Проверка версии на официальном сайте TG-ru."
    }
    override public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        if indexPath.section == 0 {
            cell.textLabel?.text = "Плагины TG-ru"
            cell.accessoryType = .disclosureIndicator
            return cell
        }
        let section = indexPath.section - 1
        if section == items.count {
            cell.textLabel?.text = "Проверить обновления"
            cell.accessoryType = .disclosureIndicator
            return cell
        }
        let label = items[section].1
        cell.textLabel?.text = label; cell.textLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.selectionStyle = .none
        let toggle = UISwitch(); toggle.tag = section
        toggle.isOn = UserDefaults.standard.bool(forKey: items[section].0)
        toggle.accessibilityLabel = label
        toggle.addTarget(self, action: #selector(changed(_:)), for: .valueChanged)
        cell.accessoryView = toggle; return cell
    }
    override public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0 {
            navigationController?.pushViewController(TgruPluginController(), animated: true)
            return
        }
        if indexPath.section - 1 == items.count { checkUpdate(); return }

    }

    private struct UpdateList: Decodable {
        struct Release: Decodable {
            let platform: String
            let arch: String
            let filename: String
            let version: String
            let build: Int?
        }
        let releases: [Release]
    }

    private func checkUpdate() {
        guard !checkingUpdate else { return }
        checkingUpdate = true
        let endpoint = URL(string: "https://tg.24projects.ru/updates/clients.json")!
        URLSession.shared.dataTask(with: URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)) { [weak self] data, response, _ in
            let list = (response as? HTTPURLResponse)?.statusCode == 200 && response?.url == endpoint
                ? data.flatMap { try? JSONDecoder().decode(UpdateList.self, from: $0) } : nil
            DispatchQueue.main.async {
                guard let self else { return }
                self.checkingUpdate = false
                guard self.viewIfLoaded?.window != nil, self.presentedViewController == nil else { return }
                let installed = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
                let build = Int(installed)
                let latest = list?.releases.filter { $0.platform == "ios" && $0.arch == "arm64" && $0.filename == "TG-ru-iOS.ipa" && ($0.build ?? 0) > 0 }
                    .max { ($0.build ?? 0) < ($1.build ?? 0) }
                let available = latest.flatMap { release -> String? in
                    guard let build, let remoteBuild = release.build, remoteBuild > build else { return nil }
                    return release.version
                }
                let message: String
                if list == nil { message = "Не удалось проверить обновления." }
                else if latest == nil { message = "Сборка iOS для обновления пока не опубликована." }
                else if let available { message = "Доступна версия \(available)." }
                else if build == nil { message = "Откройте страницу загрузки, чтобы посмотреть доступную сборку." }
                else { message = "Установлена актуальная версия." }
                let alert = UIAlertController(title: "Обновления TG-ru", message: message, preferredStyle: .alert)
                if available != nil || latest != nil && build == nil {
                    alert.addAction(UIAlertAction(title: "Открыть загрузки", style: .default) { _ in
                        UIApplication.shared.open(URL(string: "https://tg.24projects.ru/desktop/#ios")!)
                    })
                }
                alert.addAction(UIAlertAction(title: "Закрыть", style: .cancel))
                self.present(alert, animated: true)
            }
        }.resume()
    }
    @objc private func changed(_ sender: UISwitch) {
        if items.indices.contains(sender.tag) {
            UserDefaults.standard.set(sender.isOn, forKey: items[sender.tag].0)
        }
        NotificationCenter.default.post(name: Notification.Name("FamilyFeaturesChanged"), object: nil)
    }
}
