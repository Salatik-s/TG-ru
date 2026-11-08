import Foundation
import Dispatch
import Network
import MtProtoKit

private final class FamilyNetworkPath {
    static let shared = FamilyNetworkPath()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var cellular = false
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock(); self.cellular = path.usesInterfaceType(.cellular); self.lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "family.transport.path"))
    }
    var networkType: Int32 { lock.lock(); defer { lock.unlock() }; return cellular ? 1 : 0 }
}

private final class FamilyDelegateGate {
    private let lock = NSLock()
    private weak var delegate: MTTcpConnectionInterfaceDelegate?
    private let queue: DispatchQueue
    private var generation: UInt64 = 0
    init(delegate: MTTcpConnectionInterfaceDelegate, queue: DispatchQueue) { self.delegate = delegate; self.queue = queue }
    func reset() { lock.lock(); delegate = nil; generation &+= 1; lock.unlock() }
    func send(lease: FamilyTransportLease? = nil, ticket: FamilyTransportLease.Ticket? = nil,
              _ event: @escaping (MTTcpConnectionInterfaceDelegate) -> Void) {
        lock.lock(); let epoch = generation; lock.unlock()
        queue.async { [self] in
            if let lease, let ticket, !lease.permits(ticket) { return }
            lock.lock(); let target = generation == epoch ? delegate : nil; lock.unlock()
            if let target { event(target) }
        }
    }
}

/// Native TCP byte-stream adapter. The only remote socket is the fixed authenticated WSS endpoint.
/// MTProtoKit retains its encryption/framing, acknowledgements, session management and DC choice.
@available(iOS 13.0, macOS 10.15, *)
@objc(FamilyTcpConnectionInterface)
public final class FamilyTcpConnectionInterface: NSObject, MTTcpConnectionInterface {
    private let queue = DispatchQueue(label: "family.transport.stream", qos: .utility)
    private let gate: FamilyDelegateGate
    private let lease: FamilyTransportLease
    private let startLock = NSLock()
    private var startRequested = false
    private let writeAdmissionLock = NSLock()
    private var reservedWriteBytes = 0, writeAdmissionClosed = false
    // All fields below are confined to queue.
    private var ticket: FamilyTransportLease.Ticket?
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var sessionDelegate: SessionDelegate?
    private var reads = FamilyReadBuffer()
    private var writes = FamilyWriteBuffer()
    private var response = FamilyVLESSResponseDecoder()
    private var opened = false, closed = false, receiving = false, headerSent = false
    private var writeStopped = false, normalCloseReceived = false
    private var connectTimer: DispatchWorkItem?, drainTimer: DispatchWorkItem?
    private var terminalHint: FamilyTransportError?
    private var usage: MTNetworkUsageManager?
    private var readCounter: UInt64 = 0
    private var pendingReads: [UInt64] = []
    private var readTimers: [UInt64: DispatchWorkItem] = [:]

    public init(delegate: MTTcpConnectionInterfaceDelegate, delegateQueue: DispatchQueue,
                lease: FamilyTransportLease = .shared) {
        self.gate = FamilyDelegateGate(delegate: delegate, queue: delegateQueue)
        self.lease = lease
        super.init()
    }

    public func setGetLogPrefix(_ getLogPrefix: (() -> String)?) { /* Never log account/profile/data. */ }

    public func setUsageCalculationInfo(_ info: MTNetworkUsageCalculationInfo?) {
        queue.async { [weak self] in self?.usage = info.map { MTNetworkUsageManager(info: $0) } }
    }

    public func isWebProxyCarrier() -> Bool { false }

    public func connect(toHost host: String, onPort port: UInt16, viaInterface interface: String?,
                        withTimeout timeout: TimeInterval, error errPtr: NSErrorPointer) -> Bool {
        // Validate synchronously; no hostname resolution takes place on the phone for the DC.
        do { _ = try FamilyVLESS.request(uuid: UUID(), host: host, port: port) }
        catch { errPtr?.pointee = FamilyTransportError.invalidDestination.nsError; return false }
        startLock.lock()
        if startRequested { startLock.unlock(); errPtr?.pointee = FamilyTransportError.closed.nsError; return false }
        startRequested = true; startLock.unlock()
        queue.async { [weak self] in self?.begin(host: host, port: port, timeout: timeout) }
        return true
    }

    public func write(_ data: Data) {
        guard !data.isEmpty else { return }
        // Bound bytes BEFORE capturing Data in a dispatch closure; bounding the eventual
        // write queue alone would leave the dispatch queue itself unbounded under load.
        writeAdmissionLock.lock()
        if writeAdmissionClosed { writeAdmissionLock.unlock(); return }
        if data.count > 16 * 1024 * 1024 - reservedWriteBytes {
            writeAdmissionLock.unlock()
            queue.async { [weak self] in self?.fail(.bufferLimit) }; return
        }
        reservedWriteBytes += data.count; writeAdmissionLock.unlock()
        queue.async { [weak self] in
            guard let self, self.authorized() else { return }
            do { try self.writes.append(data); self.pumpWrite() }
            catch { self.fail(.bufferLimit) }
        }
    }

    public func readData(toLength length: UInt, withTimeout timeout: TimeInterval, tag: Int) {
        queue.async { [weak self] in
            guard let self, self.authorized() else { return }
            guard length <= UInt(FamilyVLESS.maximumMessage) else { self.fail(.invalidRead); return }
            self.readCounter &+= 1
            let requestId = self.readCounter
            self.pendingReads.append(requestId)
            do { self.deliver(try self.reads.read(length: Int(length), tag: tag)) }
            catch { self.fail(.invalidRead) }
            // MTProtoKit requests -1 and owns the response watchdog. Partial callbacks keep it alive.
            // A positive per-read timeout still fails closed; completion invalidates it via request ID.
            if timeout.isFinite && timeout > 0 && !self.closed && self.pendingReads.contains(requestId) {
                let work = DispatchWorkItem { [weak self] in
                    guard let self, !self.closed, self.pendingReads.contains(requestId) else { return }
                    self.fail(.connectionTimeout)
                }
                self.readTimers[requestId] = work
                self.queue.asyncAfter(deadline: .now() + timeout, execute: work)
            }
        }
    }

    public func disconnect() { queue.async { [weak self] in self?.finish(error: nil) } }

    public func resetDelegate() {
        gate.reset()
        queue.async { [weak self] in self?.finish(error: nil) }
    }

    private func begin(host: String, port: UInt16, timeout: TimeInterval) {
        guard !closed else { return }
        guard let ticket = lease.openFlow(onInvalidation: { [weak self] in
            self?.queue.async { [weak self] in self?.fail(.authorizationRequired) }
        }) else { fail(.authorizationRequired); return }
        self.ticket = ticket
        guard authorized() else { return }
        do { try writes.append(FamilyVLESS.request(uuid: ticket.uuid, host: host, port: port)) }
        catch { fail(.invalidDestination); return }

        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = timeout.isFinite && timeout > 0 ? min(timeout, 30) : 12
        configuration.timeoutIntervalForResource = 24 * 60 * 60
        let delegate = SessionDelegate(owner: self)
        let delegateQueue = OperationQueue()
        delegateQueue.maxConcurrentOperationCount = 1
        delegateQueue.underlyingQueue = queue
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: delegateQueue)
        self.session = session; self.sessionDelegate = delegate
        let socket = session.webSocketTask(with: FamilyVLESS.gatewayURL)
        socket.maximumMessageSize = FamilyVLESS.maximumMessage
        self.socket = socket
        let work = DispatchWorkItem { [weak self] in self?.fail(.connectionTimeout) }
        connectTimer = work
        queue.asyncAfter(deadline: .now() + configuration.timeoutIntervalForRequest, execute: work)
        guard authorized() else { return }
        socket.resume()
    }

    private func didOpen() {
        guard authorized(), !opened else { return }
        opened = true; connectTimer?.cancel(); connectTimer = nil
        if let ticket { gate.send(lease: lease, ticket: ticket) { $0.connectionInterfaceDidConnect() } }
        // Do not wait for the VLESS response here: the DC may first need MTProto's request.
        // The VLESS request was queued first, before any application data.
        pumpWrite(); receive()
    }

    private func authorized() -> Bool {
        guard !closed else { return false }
        guard let ticket, lease.permits(ticket) else { fail(.authorizationRequired); return false }
        return true
    }

    private func pumpWrite() {
        guard opened, !writeStopped, authorized(), let socket, let data = writes.next() else { return }
        socket.send(.data(data)) { [weak self] error in
            self?.queue.async { [weak self] in
                guard let self, self.authorized() else { return }
                guard error == nil else { self.transportClosed(error: .tlsOrNetworkFailure); return }
                guard !self.writeStopped else { return }
                self.writes.acknowledge()
                if self.headerSent {
                    self.releaseWriteBytes(data.count)
                    self.usage?.addOutgoingBytes(UInt(data.count), interface: FamilyNetworkPath.shared.networkType == 1 ? MTNetworkUsageManagerInterfaceWWAN : MTNetworkUsageManagerInterfaceOther)
                } else { self.headerSent = true }
                self.pumpWrite()
            }
        }
    }

    private func receive() {
        guard opened, !receiving, !reads.ended, authorized(), let socket else { return }
        receiving = true
        socket.receive { [weak self] result in
            self?.queue.async { [weak self] in
                guard let self else { return }
                self.receiving = false
                guard self.authorized() else { return }
                switch result {
                case let .success(.data(data)):
                    do {
                        let payload = try self.response.consume(data)
                        self.usage?.addIncomingBytes(UInt(payload.count), interface: FamilyNetworkPath.shared.networkType == 1 ? MTNetworkUsageManagerInterfaceWWAN : MTNetworkUsageManagerInterfaceOther)
                        self.deliver(try self.reads.append(payload))
                        self.receive()
                    } catch let error as FamilyTransportError { self.fail(error) }
                    catch { self.fail(.invalidResponse) }
                case .success(.string): self.fail(.unsupportedMessage)
                case .failure:
                    self.endReads(error: self.terminalHint ?? (self.normalCloseReceived ? nil : .tlsOrNetworkFailure))
                @unknown default: self.fail(.unsupportedMessage)
                }
            }
        }
    }

    private func endReads(error: FamilyTransportError?) {
        guard !closed && !reads.ended else { return }
        do { try response.finish() }
        catch { fail(.truncatedResponse); return }
        deliver(reads.end(error: error))
        scheduleDrainTimeout()
    }

    private func scheduleDrainTimeout() {
        guard !closed, drainTimer == nil else { return }
        let work = DispatchWorkItem { [weak self] in self?.fail(.drainTimeout) }
        drainTimer = work; queue.asyncAfter(deadline: .now() + 5, execute: work)
    }

    private func transportClosed(error: FamilyTransportError?, normal: Bool = false) {
        guard !closed else { return }
        if let error { terminalHint = error }
        if normal { normalCloseReceived = true }
        writeStopped = true; writes.clear()
        if !opened { fail(error ?? .tlsOrNetworkFailure); return }
        // URLSession may still have complete binary messages queued for receive(). Drain them.
        if receiving { scheduleDrainTimeout() }
        else { endReads(error: error) }
    }

    private func deliver(_ events: [FamilyReadEvent]) {
        for event in events {
            switch event {
            case let .partial(count, tag):
                if let ticket { gate.send(lease: lease, ticket: ticket) { $0.connectionInterfaceDidReadPartialData(ofLength: UInt(count), tag: tag) } }
            case let .data(data, tag):
                if !pendingReads.isEmpty { readTimers.removeValue(forKey: pendingReads.removeFirst())?.cancel() }
                let networkType = FamilyNetworkPath.shared.networkType
                if let ticket { gate.send(lease: lease, ticket: ticket) { $0.connectionInterfaceDidRead(data, withTag: tag, networkType: networkType) } }
            case let .closed(error): finish(error: error)
            }
        }
    }

    private func fail(_ error: FamilyTransportError) { deliver(reads.abort(error)); if !closed { finish(error: error) } }

    private func finish(error: FamilyTransportError?) {
        guard !closed else { return }
        closed = true
        writeAdmissionLock.lock(); writeAdmissionClosed = true; reservedWriteBytes = 0; writeAdmissionLock.unlock()
        readTimers.values.forEach { $0.cancel() }; readTimers.removeAll(); pendingReads.removeAll()
        connectTimer?.cancel(); connectTimer = nil; drainTimer?.cancel(); drainTimer = nil
        writes.clear()
        if let ticket { lease.closeFlow(ticket) }
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        session?.invalidateAndCancel(); session = nil; sessionDelegate = nil
        gate.send { $0.connectionInterfaceDidDisconnectWithError(error?.nsError) }
    }

    private func releaseWriteBytes(_ count: Int) {
        writeAdmissionLock.lock(); reservedWriteBytes = max(0, reservedWriteBytes - count); writeAdmissionLock.unlock()
    }

    deinit {
        gate.reset(); connectTimer?.cancel(); drainTimer?.cancel(); readTimers.values.forEach { $0.cancel() }
        if let ticket { lease.closeFlow(ticket) }
        socket?.cancel(with: .goingAway, reason: nil); session?.invalidateAndCancel()
    }

    private final class SessionDelegate: NSObject, URLSessionWebSocketDelegate {
        weak var owner: FamilyTcpConnectionInterface?
        init(owner: FamilyTcpConnectionInterface) { self.owner = owner }
        func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) { owner?.didOpen() }
        func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
            owner?.transportClosed(error: closeCode == .normalClosure ? nil : .tlsOrNetworkFailure, normal: closeCode == .normalClosure)
        }
        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            owner?.transportClosed(error: error == nil ? nil : .tlsOrNetworkFailure)
        }
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil); owner?.fail(.redirectDenied)
        }
        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
                // System chain, expiry and hostname verification; no trust bypass or private CA override.
                completionHandler(.performDefaultHandling, nil)
            } else { completionHandler(.cancelAuthenticationChallenge, nil) }
        }
    }
}
