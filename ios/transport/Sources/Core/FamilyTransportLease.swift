import Foundation
import Dispatch
#if canImport(Darwin)
import Darwin
#endif

/// App and each extension must authorize independently after an authenticated backend refresh.
public final class FamilyTransportLease {
    public static let shared = FamilyTransportLease()
    public struct Ticket {
        public let uuid: UUID
        fileprivate let generation: UInt64
        fileprivate let registration: UUID
    }
    private let lock = NSLock()
    private let clock: () -> UInt64
    private let timerQueue = DispatchQueue(label: "family.transport.lease")
    private var uuid: UUID?
    private var expires: UInt64 = 0, generation: UInt64 = 0
    private var flows: [UUID: () -> Void] = [:]
    private var timer: DispatchSourceTimer?

    public init(clock: @escaping () -> UInt64 = FamilyTransportLease.continuousNanoseconds) { self.clock = clock }

    public static func continuousNanoseconds() -> UInt64 {
        #if canImport(Darwin)
        // Unlike mach_absolute_time, this includes device sleep.
        return clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)
        #else
        // Non-Apple host tests inject their clock; this path is not an iOS lease implementation.
        return DispatchTime.now().uptimeNanoseconds
        #endif
    }

    @discardableResult public func authorize(uuid newUUID: UUID, validFor seconds: TimeInterval) -> Bool {
        guard seconds.isFinite && seconds > 0 && seconds <= 75,
              newUUID != UUID(uuidString: "00000000-0000-0000-0000-000000000000")! else { invalidate(); return false }
        let now = clock(), duration = UInt64(seconds * 1_000_000_000)
        guard duration > 0 && now <= UInt64.max - duration else { invalidate(); return false }
        var callbacks: [() -> Void] = []
        lock.lock()
        if uuid != newUUID || now >= expires {
            callbacks = Array(flows.values); flows.removeAll(); generation &+= 1
        }
        uuid = newUUID; expires = now + duration
        timer?.cancel()
        let updatedTimer = DispatchSource.makeTimerSource(queue: timerQueue)
        updatedTimer.schedule(deadline: .now() + seconds, leeway: .milliseconds(10))
        updatedTimer.setEventHandler { [weak self] in _ = self?.checkValidity() }
        timer = updatedTimer; updatedTimer.resume()
        lock.unlock()
        callbacks.forEach { $0() }
        return true
    }

    public func invalidate() {
        lock.lock()
        uuid = nil; expires = 0; generation &+= 1
        timer?.cancel(); timer = nil
        let callbacks = Array(flows.values); flows.removeAll()
        lock.unlock()
        callbacks.forEach { $0() }
    }

    /// Call on foreground/resume as well as before every network read/write/delivery.
    @discardableResult public func checkValidity() -> Bool {
        lock.lock()
        let valid = uuid != nil && clock() < expires
        var callbacks: [() -> Void] = []
        if !valid && uuid != nil {
            uuid = nil; expires = 0; generation &+= 1
            timer?.cancel(); timer = nil
            callbacks = Array(flows.values); flows.removeAll()
        }
        lock.unlock(); callbacks.forEach { $0() }
        return valid
    }

    public func openFlow(onInvalidation: @escaping () -> Void) -> Ticket? {
        _ = checkValidity()
        lock.lock(); defer { lock.unlock() }
        guard let uuid, clock() < expires else { return nil }
        let registration = UUID(); flows[registration] = onInvalidation
        return Ticket(uuid: uuid, generation: generation, registration: registration)
    }

    public func permits(_ ticket: Ticket) -> Bool {
        guard checkValidity() else { return false }
        lock.lock(); defer { lock.unlock() }
        return uuid == ticket.uuid && generation == ticket.generation && clock() < expires
    }

    public func closeFlow(_ ticket: Ticket) {
        lock.lock(); flows.removeValue(forKey: ticket.registration); lock.unlock()
    }

    deinit { timer?.cancel() }
}
