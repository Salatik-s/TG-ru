import Foundation
import UIKit
import FamilyPrivacy

/// Presented by the chat-list's additional native ellipsis. Every switch has an integration hook.
@objc(FamilyFeatureSettingsController)
public final class FamilyFeatureSettingsController: UITableViewController {
    private let accountID: String
    private let items: [(String, String, String)] = [
        ("family.features.localSpeech", "Расшифровка на устройстве", "Голосовые и кружки. Требуется поддержка языка в iOS; звук не отправляется на сервер распознавания."),
        ("family.features.documents", "Просмотр PDF", "Масштаб и переход к странице средствами iOS. Документы Word открываются в системном просмотрщике."),
        ("family.features.python", "Python в сообщениях", "Запуск выбранного кода на устройстве. До 10 секунд, без доступа к чатам и сети.")
    ]
    private let privacy: [(FamilyPrivacyFeature, String, String)] = [
        (.hideReadReceipts, "Не отправлять отметки о прочтении", "Для обычных облачных сообщений. Секретные, исчезающие и неподдерживаемые типы сообщений обрабатываются как в Telegram."),
        (.hideTyping, "Не отправлять «печатает»", "Скрывает отправку статуса набора текста в облачных чатах."),
        (.hideOnline, "Не отправлять статус «онлайн»", "Не меняет уже известное серверу время последнего посещения."),
        (.hideStoryViews, "Не отмечать истории просмотренными", "Не отправляет отметку о просмотре истории с этого приложения."),
        (.archiveDeletedMessages, "Сохранять удалённый текст", "Сохраняются новые удаления уже полученного облачного текста, до 30 дней. Без медиа, секретных и исчезающих сообщений.")
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
    override public func numberOfSections(in tableView: UITableView) -> Int { return items.count + privacy.count + 1 }
    override public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { return 1 }
    override public func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section < items.count { return items[section].2 }
        if section < items.count + privacy.count { return privacy[section - items.count].2 }
        return "Только записи текущего аккаунта на этом устройстве. Старые удаления восстановить нельзя."
    }
    override public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        let section = indexPath.section
        if section == items.count + privacy.count {
            cell.textLabel?.text = "Удалённые сообщения"; cell.accessoryType = .disclosureIndicator
            return cell
        }
        let label = section < items.count ? items[section].1 : privacy[section - items.count].1
        cell.textLabel?.text = label; cell.textLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.selectionStyle = .none
        let toggle = UISwitch(); toggle.tag = indexPath.section
        toggle.isOn = section < items.count ? UserDefaults.standard.bool(forKey: items[section].0) : FamilyPrivacySettings.shared.isEnabled(privacy[section - items.count].0)
        toggle.accessibilityLabel = label
        toggle.addTarget(self, action: #selector(changed(_:)), for: .valueChanged)
        cell.accessoryView = toggle; return cell
    }
    override public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.section == items.count + privacy.count else { return }
        navigationController?.pushViewController(FamilyArchiveController(accountID: accountID), animated: true)
    }
    @objc private func changed(_ sender: UISwitch) {
        if items.indices.contains(sender.tag) {
            UserDefaults.standard.set(sender.isOn, forKey: items[sender.tag].0)
        } else {
            let index = sender.tag - items.count
            guard privacy.indices.contains(index) else { return }
            FamilyPrivacySettings.shared.setEnabled(sender.isOn, for: privacy[index].0)
        }
        NotificationCenter.default.post(name: Notification.Name("FamilyFeaturesChanged"), object: nil)
    }
}

private final class FamilyArchiveController: UITableViewController {
    private let accountID: String
    private var messages: [FamilyArchivedMessage] = []
    private let emptyLabel = UILabel()
    private let dateFormat = DateFormatter()
    init(accountID: String) {
        self.accountID = accountID; super.init(style: .insetGrouped)
        dateFormat.dateStyle = .medium; dateFormat.timeStyle = .short
    }
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Удалённые сообщения"
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 100
        emptyLabel.text = "Загрузка…"; emptyLabel.textAlignment = .center; emptyLabel.numberOfLines = 0
        emptyLabel.font = .preferredFont(forTextStyle: .body); emptyLabel.adjustsFontForContentSizeCategory = true
        emptyLabel.textColor = .secondaryLabel; tableView.backgroundView = emptyLabel
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Очистить", style: .plain, target: self, action: #selector(clear))
        refreshControl = UIRefreshControl(); refreshControl?.addTarget(self, action: #selector(reload), for: .valueChanged)
        reload()
    }
    @objc private func reload() {
        FamilyDeletedArchive.shared.messages(accountID: accountID) { [weak self] values in
            guard let self else { return }
            self.messages = values; self.tableView.reloadData(); self.refreshControl?.endRefreshing()
            self.emptyLabel.text = "Нет доступных записей"
            self.tableView.backgroundView = values.isEmpty ? self.emptyLabel : nil
            self.navigationItem.rightBarButtonItem?.isEnabled = !values.isEmpty
        }
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { return messages.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let value = messages[indexPath.row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.text = String(value.text.prefix(180)); cell.textLabel?.numberOfLines = 3
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.text = dateFormat.string(from: Date(timeIntervalSince1970: TimeInterval(value.timestamp)))
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1); cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.accessoryType = .disclosureIndicator; return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let controller = UIViewController(); controller.title = "Сохранённый текст"
        let text = UITextView(); text.isEditable = false; text.isSelectable = true
        text.font = .preferredFont(forTextStyle: .body); text.adjustsFontForContentSizeCategory = true
        text.text = messages[indexPath.row].text; text.backgroundColor = .systemBackground
        text.textContainerInset = UIEdgeInsets(top: 16, left: 16, bottom: 32, right: 16)
        controller.view = text; navigationController?.pushViewController(controller, animated: true)
    }
    @objc private func clear() {
        let alert = UIAlertController(title: "Очистить локальный архив?", message: "Сохранённый текст текущего аккаунта будет удалён с этого устройства.", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Очистить", style: .destructive) { [weak self] _ in
            guard let self else { return }
            FamilyDeletedArchive.shared.clear(accountID: self.accountID) { [weak self] in self?.reload() }
        })
        present(alert, animated: true)
    }
}
