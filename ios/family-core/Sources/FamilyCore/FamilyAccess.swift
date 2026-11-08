import Foundation
import Darwin

public enum FamilyContinuousTime {
    // Includes suspend; wall-clock edits cannot extend a credential lease.
    public static func now() -> TimeInterval {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return Double(mach_continuous_time()) * Double(info.numer) / Double(info.denom) / 1_000_000_000
    }
}

public struct FamilyAccessState: Equatable {
    public var status: String = "unknown"
    public var error: String = ""
    public var busy = false
    public var owner = false
    public var deviceID = ""
    public var retryAt: TimeInterval = 0
    public var retrySeconds: Int { max(0, Int(ceil(retryAt - FamilyContinuousTime.now()))) }
}

@MainActor public final class FamilyAccess {
    public static let shared = FamilyAccess()
    public static let changed = Notification.Name("TG-ru.FamilyAccess.changed.v1")
    public var onAuthorizationChanged: ((UUID?, TimeInterval) -> Void)?
    public private(set) var state = FamilyAccessState()
    public private(set) var grantUntil: TimeInterval = 0
    public var isAllowed: Bool { grantUntil > FamilyContinuousTime.now() }
    private var identity: FamilyIdentity?
    private var generation: UInt64 = 0
    private var request: Task<Void, Never>?
    private var refreshTimer: Timer?
    private var expiryTimer: Timer?
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public var deviceName: String {
        get { defaults.string(forKey: "tg.ru.device.name.v1") ?? "iPhone" }
        set {
            let name = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty && name.utf16.count <= 80 { defaults.set(name, forKey: "tg.ru.device.name.v1") }
        }
    }

    public func refresh() { begin(invitation: nil) }

    public func submit(invitation: String) {
        let code = invitation.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard FamilyProtocol.matches(code, "[A-Z0-9-]{1,64}") else {
            state.error = "invalid_invite_format"; publish(); return
        }
        guard state.retrySeconds == 0 else { return }
        begin(invitation: code)
    }

    public func checkAfterResume() {
        if !isAllowed { clearGrant() }
        refresh()
    }

    public func disconnect() {
        generation &+= 1
        request?.cancel(); request = nil
        clearGrant(); state.busy = false; state.error = ""; publish()
    }

    private func begin(invitation: String?) {
        guard !state.busy else { return }
        generation &+= 1
        let epoch = generation
        state.busy = true; state.error = ""; publish()
        let name = deviceName
        request = Task { [weak self] in
            guard let self else { return }
            defer {
                if epoch == self.generation { self.state.busy = false; self.request = nil; self.publish() }
            }
            do {
                if self.identity == nil { self.identity = try FamilyIdentity(defaults: self.defaults) }
                guard let identity = self.identity else { throw FamilyFailure.keyUnavailable }
                self.state.deviceID = identity.deviceID
                let initial = try await self.proof(action: invitation == nil ? .status : .invite,
                    payload: invitation.map { ["code": $0, "name": name] } ?? [:], identity: identity)
                try Task.checkCancellation()
                guard epoch == self.generation else { return }
                self.apply(initial)
                guard initial.status == "approved" else { self.clearGrant(); return }
                let config = try await self.proof(action: .config, payload: [:], identity: identity)
                try Task.checkCancellation()
                guard epoch == self.generation else { return }
                self.apply(config)
                let uuid = try config.selectedGatewayUUID()
                // Authorization is never read from preferences or retained across process launches.
                self.grantUntil = FamilyContinuousTime.now() + 75
                self.onAuthorizationChanged?(uuid, 75)
                self.armTimers()
            } catch {
                guard epoch == self.generation else { return }
                self.clearGrant()
                self.state.error = Self.errorCode(error)
                self.state.owner = false
                if case FamilyFailure.server(let code, let wait) = error {
                    if code == "blocked" || code == "revoked" { self.state.status = code }
                    self.state.retryAt = FamilyContinuousTime.now() + Double(wait)
                }
            }
        }
    }

    private func apply(_ response: FamilyEnvelope) {
        state.status = response.status
        state.owner = response.status == "approved" && response.phone_owner == true
        let wait = min(Int64(86400), max(0, response.cooldown_until - response.server_time))
        state.retryAt = FamilyContinuousTime.now() + Double(wait)
    }

    private func armTimers() {
        refreshTimer?.invalidate(); expiryTimer?.invalidate()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 75, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.isAllowed else { return }
                self.clearGrant(); self.publish()
            }
        }
    }

    private func clearGrant() {
        grantUntil = 0
        refreshTimer?.invalidate(); refreshTimer = nil
        expiryTimer?.invalidate(); expiryTimer = nil
        onAuthorizationChanged?(nil, 0)
    }

    private func publish() { NotificationCenter.default.post(name: Self.changed, object: self) }

    private func proof(action: FamilyProofAction, payload: [String: Any], identity: FamilyIdentity,
                       canonicalManagement: String? = nil) async throws -> FamilyEnvelope {
        let data = try await proofData(action: action, payload: payload, identity: identity,
                                       canonicalManagement: canonicalManagement)
        return try JSONDecoder().decode(FamilyEnvelope.self, from: data).validated(deviceID: identity.deviceID)
    }

    private func proofData(action: FamilyProofAction, payload: [String: Any], identity: FamilyIdentity,
                           canonicalManagement: String? = nil) async throws -> Data {
        struct Challenge: Decodable { let device_id: String; let challenge_id: String; let message: String }
        let input: [String: Any] = ["public_key": identity.publicKeySPKI.base64EncodedString(),
                                   "action": action.rawValue, "payload": payload]
        let challengeData = try await FamilyHTTP.post(path: "/v1/challenge", body: JSONSerialization.data(withJSONObject: input))
        let challenge = try JSONDecoder().decode(Challenge.self, from: challengeData)
        guard FamilyProtocol.validateChallenge(message: challenge.message, challengeID: challenge.challenge_id,
            deviceID: challenge.device_id, expectedDeviceID: identity.deviceID, action: action,
            canonicalManagement: canonicalManagement) else { throw FamilyFailure.invalidResponse }
        try Task.checkCancellation()
        let signature = try identity.signature(message: challenge.message)
        let body: [String: Any] = ["challenge_id": challenge.challenge_id, "signature": signature.base64EncodedString()]
        return try await FamilyHTTP.post(path: "/v1/complete", body: JSONSerialization.data(withJSONObject: body))
    }

    /// The server rechecks the installation binding for every management operation.
    public func manage(command: String, deviceID: String? = nil, hours: Int = 24) async throws -> Data {
        guard isAllowed, state.owner, let identity else { throw FamilyFailure.server("owner_required", 0) }
        let canonical = try FamilyProtocol.canonicalManagement(command: command, deviceID: deviceID, hours: hours)
        guard let payload = try JSONSerialization.jsonObject(with: Data(canonical.utf8)) as? [String: Any] else {
            throw FamilyFailure.invalidPayload
        }
        do {
            let data = try await proofData(action: .manage, payload: payload, identity: identity, canonicalManagement: canonical)
            let envelope = try JSONDecoder().decode(FamilyEnvelope.self, from: data).validated(deviceID: identity.deviceID)
            guard envelope.phone_owner == true && envelope.status == "approved" else { throw FamilyFailure.server("owner_required", 0) }
            return data
        } catch {
            if case FamilyFailure.server(let code, _) = error {
                if code == "owner_required" { state.owner = false; publish() }
                if code == "revoked" || code == "blocked" { disconnect(); state.status = code; publish() }
            }
            throw error
        }
    }

    public static func errorCode(_ error: Error) -> String {
        if case FamilyFailure.server(let code, _) = error { return code }
        if case FamilyFailure.keyUnavailable = error { return "keystore_error" }
        if case FamilyFailure.invalidResponse = error { return "invalid_response" }
        return "network_error"
    }
}
