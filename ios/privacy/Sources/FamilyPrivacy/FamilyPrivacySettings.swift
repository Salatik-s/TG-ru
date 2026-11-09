import Foundation

public enum FamilyPrivacyFeature: String, CaseIterable {
    case hideReadReceipts, hideTyping, hideOnline, hideStoryViews, archiveDeletedMessages
}

/// No transport credentials or message contents are stored in preferences.
public final class FamilyPrivacySettings {
    public static let shared = FamilyPrivacySettings(defaults: .standard)
    public static let changed = Notification.Name("FamilyPrivacySettingsChanged")
    private let defaults: UserDefaults
    private let lock = NSLock()
    public init(defaults: UserDefaults) { self.defaults = defaults }
    private func key(_ feature: FamilyPrivacyFeature) -> String { "family.privacy.v1." + feature.rawValue }
    public func isEnabled(_ feature: FamilyPrivacyFeature) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return defaults.bool(forKey: key(feature))
    }
    public func setEnabled(_ enabled: Bool, for feature: FamilyPrivacyFeature) {
        lock.lock(); defaults.set(enabled, forKey: key(feature)); lock.unlock()
        NotificationCenter.default.post(name: Self.changed, object: self)
    }
    /// Exact RPC names only. Read eligibility comes from the Postbox transaction;
    /// unknown, encrypted and expiring content must keep Telegram's normal semantics.
    public func shouldSuppress(method: String, online: Bool? = nil, readEligible: Bool = false) -> Bool {
        switch method {
        case "messages.setTyping": return isEnabled(.hideTyping)
        case "account.updateStatus": return online == true && isEnabled(.hideOnline)
        case "stories.readStories", "stories.incrementStoryViews": return isEnabled(.hideStoryViews)
        case "messages.readHistory", "channels.readHistory", "messages.readDiscussion",
             "messages.readMessageContents", "channels.readMessageContents":
            return readEligible && isEnabled(.hideReadReceipts)
        default: return false
        }
    }
}
