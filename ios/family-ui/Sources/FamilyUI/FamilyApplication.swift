import UIKit
import FamilyCore
import FamilyTransport

/// Installs the gate independently of Telegram account data and before network authorization.
@MainActor public enum FamilyApplication {
    private static var bootstrap: Bootstrap?

    public static func install(host: UIWindow) {
        if let bootstrap, bootstrap.host === host { bootstrap.gate.update(); return }
        bootstrap = Bootstrap(host: host)
    }

    private final class Bootstrap {
        weak var host: UIWindow?
        let gate: FamilyGateWindow
        var foreground: NSObjectProtocol?
        init(host: UIWindow) {
            self.host = host
            FamilyTransportLease.shared.invalidate()
            FamilyAccess.shared.onAuthorizationChanged = { uuid, seconds in
                if let uuid { FamilyTransportLease.shared.authorize(uuid: uuid, validFor: seconds) }
                else { FamilyTransportLease.shared.invalidate() }
            }
            gate = FamilyGateWindow(host: host)
            #if !FAMILY_COMPILE_ONLY
            foreground = NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification,
                                                                object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    FamilyTransportLease.shared.checkValidity()
                    FamilyAccess.shared.checkAfterResume()
                    self?.gate.update()
                }
            }
            FamilyAccess.shared.refresh()
            #endif
        }
        deinit { if let foreground { NotificationCenter.default.removeObserver(foreground) } }
    }
}
