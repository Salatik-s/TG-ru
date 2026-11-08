import UIKit
import FamilyCore

@MainActor public final class FamilyGateViewController: UIViewController {
    private let name = UITextField()
    private let invite = UITextField()
    private let detail = UILabel()
    private let fingerprint = UILabel()
    private let submit = UIButton(type: .system)
    private let refresh = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var observer: NSObjectProtocol?
    private var tick: Timer?

    public override func viewDidLoad() {
        super.viewDidLoad()
        isModalInPresentation = true
        view.backgroundColor = .systemGroupedBackground
        let scroll = UIScrollView()
        scroll.keyboardDismissMode = .interactive
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        let stack = UIStackView()
        stack.axis = .vertical; stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 40),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -32),
            stack.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40)
        ])
        let title = UILabel(); title.text = "TG-ru"; title.font = .preferredFont(forTextStyle: .largeTitle)
        title.adjustsFontForContentSizeCategory = true
        stack.addArrangedSubview(title)
        let introduction = UILabel()
        introduction.text = "Доступ для семьи\nВведите одноразовое приглашение. Затем владелец разрешит подключение этого телефона."
        introduction.font = .preferredFont(forTextStyle: .body); introduction.numberOfLines = 0
        introduction.adjustsFontForContentSizeCategory = true
        #if FAMILY_COMPILE_ONLY
        introduction.text = "Проверка сборки iOS. В этой сборке ещё не настроены собственные Telegram API-данные; вход и подключения отключены."
        #endif
        stack.addArrangedSubview(introduction)
        configure(name, placeholder: "Название телефона", accessibility: "family.device.name")
        name.text = FamilyAccess.shared.deviceName
        name.textContentType = .nickname
        configure(invite, placeholder: "Одноразовый код", accessibility: "family.invitation")
        invite.autocapitalizationType = .allCharacters; invite.autocorrectionType = .no
        invite.isSecureTextEntry = true
        stack.addArrangedSubview(name); stack.addArrangedSubview(invite)
        submit.setTitle("Продолжить", for: .normal)
        submit.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        submit.accessibilityIdentifier = "family.invite.submit"
        submit.addTarget(self, action: #selector(sendInvitation), for: .touchUpInside)
        submit.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        stack.addArrangedSubview(submit)
        refresh.setTitle("Проверить доступ", for: .normal)
        refresh.addTarget(self, action: #selector(checkAccess), for: .touchUpInside)
        refresh.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        stack.addArrangedSubview(refresh); stack.addArrangedSubview(spinner)
        detail.font = .preferredFont(forTextStyle: .body); detail.numberOfLines = 0
        detail.accessibilityIdentifier = "family.access.status"
        detail.adjustsFontForContentSizeCategory = true
        stack.addArrangedSubview(detail)
        fingerprint.font = .monospacedSystemFont(ofSize: 14, weight: .regular)
        fingerprint.textColor = .secondaryLabel; fingerprint.numberOfLines = 0
        stack.addArrangedSubview(fingerprint)
        observer = NotificationCenter.default.addObserver(forName: FamilyAccess.changed, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.render() }
        }
        tick = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.render() }
        }
        render()
    }

    private func configure(_ field: UITextField, placeholder: String, accessibility: String) {
        field.placeholder = placeholder; field.borderStyle = .roundedRect
        field.font = .preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.accessibilityIdentifier = accessibility
        field.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
    }

    @objc private func sendInvitation() {
        FamilyAccess.shared.deviceName = name.text ?? "iPhone"
        FamilyAccess.shared.submit(invitation: invite.text ?? "")
        invite.text = ""; view.endEditing(true)
    }

    @objc private func checkAccess() { view.endEditing(true); FamilyAccess.shared.refresh() }

    private func render() {
        #if FAMILY_COMPILE_ONLY
        submit.isEnabled = false; refresh.isEnabled = false; name.isEnabled = false; invite.isEnabled = false
        detail.text = "Тестовая сборка. Ожидается конфигурация приложения."
        spinner.stopAnimating()
        return
        #else
        let state = FamilyAccess.shared.state
        submit.isEnabled = !state.busy && state.retrySeconds == 0 && !["pending", "blocked", "revoked"].contains(state.status)
        refresh.isEnabled = !state.busy
        name.isEnabled = !state.busy && state.status != "pending"
        invite.isEnabled = submit.isEnabled
        state.busy ? spinner.startAnimating() : spinner.stopAnimating()
        if !state.deviceID.isEmpty {
            let prefix = String(state.deviceID.prefix(16)).uppercased()
            fingerprint.text = "Отпечаток телефона\n" + stride(from: 0, to: 16, by: 4).map {
                String(prefix.dropFirst($0).prefix(4))
            }.joined(separator: " ")
        }
        if state.retrySeconds > 0 {
            detail.text = "Повторить можно через \(state.retrySeconds / 60):\(String(format: "%02d", state.retrySeconds % 60))."
        } else if state.busy { detail.text = "Проверяем подключение…" }
        else if !state.error.isEmpty { detail.text = Self.message(state.error) }
        else {
            switch state.status {
            case "pending": detail.text = "Ожидаем разрешение владельца. Сравните отпечаток телефона в его настройках «Устройства семьи», затем нажмите «Проверить доступ»."
            case "blocked": detail.text = "Доступ этой установки заблокирован после неверных кодов. Обратитесь к владельцу."
            case "revoked": detail.text = "Владелец отозвал доступ этого телефона."
            case "approved": detail.text = "Доступ разрешён. Подключаем Telegram…"
            default: detail.text = "Приглашение можно получить у владельца приложения."
            }
        }
        #endif
    }

    private static func message(_ code: String) -> String {
        switch code {
        case "invalid_invite", "invalid_invite_format": return "Код не подошёл. Проверьте приглашение владельца."
        case "gateway_unavailable": return "Семейный сервер пока не готов к подключению. Попробуйте проверить доступ ещё раз."
        case "keystore_error": return "Не удалось открыть защищённый ключ этого телефона. Повторите после разблокировки устройства."
        case "blocked": return "Доступ этой установки заблокирован. Обратитесь к владельцу."
        case "revoked": return "Владелец отозвал доступ этого телефона."
        default: return "Не удалось подключиться к семейному серверу. Проверьте интернет и нажмите «Проверить доступ»."
        }
    }

    deinit { if let observer { NotificationCenter.default.removeObserver(observer) }; tick?.invalidate() }
}

/// A separate native gate window prevents navigation around an unapproved installation.
@MainActor public final class FamilyGateWindow {
    private weak var host: UIWindow?
    private var gate: UIWindow?
    private var observer: NSObjectProtocol?
    public init(host: UIWindow) {
        self.host = host
        observer = NotificationCenter.default.addObserver(forName: FamilyAccess.changed, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.update() }
        }
        update()
    }
    public func update() {
        if let gate, let host, gate.windowScene !== host.windowScene {
            gate.isHidden = true; self.gate = nil
        }
        if FamilyAccess.shared.isAllowed {
            gate?.isHidden = true; gate = nil
            host?.makeKey()
        } else if gate == nil, let host {
            let window: UIWindow
            if let scene = host.windowScene { window = UIWindow(windowScene: scene) }
            else { window = UIWindow(frame: host.bounds) }
            window.windowLevel = UIWindow.Level(rawValue: host.windowLevel.rawValue + 1)
            window.rootViewController = FamilyGateViewController()
            gate = window; window.makeKeyAndVisible()
        } else {
            gate?.makeKey()
        }
    }
    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
}
