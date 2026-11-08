import Foundation

/// Default system TLS trust; fixed origin; no redirects, cookies or cache; streaming response bound.
final class FamilyHTTP: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var completion: ((Result<Data, Error>) -> Void)?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var bytes = Data()
    private var status = 0
    private var finished = false

    static func post(path: String, body: Data) async throws -> Data {
        guard ["/v1/challenge", "/v1/complete"].contains(path), body.count <= 4096 else {
            throw FamilyFailure.invalidPayload
        }
        let client = FamilyHTTP()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                client.start(path: path, body: body) { continuation.resume(with: $0) }
            }
        }, onCancel: { client.finish(.failure(FamilyFailure.cancelled)) })
    }

    private func start(path: String, body: Data, complete: @escaping (Result<Data, Error>) -> Void) {
        lock.lock()
        if finished { lock.unlock(); complete(.failure(FamilyFailure.cancelled)); return }
        completion = complete
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 12
        configuration.tlsMinimumSupportedProtocolVersion = .TLSv12
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: FamilyProtocol.endpoint.appendingPathComponent(String(path.dropFirst())))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body
        let task = session.dataTask(with: request)
        self.task = task
        lock.unlock()
        task.resume()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
        finish(.failure(FamilyFailure.invalidResponse))
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let response = response as? HTTPURLResponse,
              response.url?.scheme == "https", response.url?.host == "24projects.ru", response.url?.port == 8443,
              response.mimeType?.lowercased() == "application/json",
              response.expectedContentLength <= 131072, !(300..<400).contains(response.statusCode) else {
            completionHandler(.cancel); finish(.failure(FamilyFailure.invalidResponse)); return
        }
        lock.lock(); status = response.statusCode; lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if finished { lock.unlock(); return }
        if data.count > 131072 - bytes.count { lock.unlock(); finish(.failure(FamilyFailure.invalidResponse)); return }
        bytes.append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let value = bytes; let code = status; lock.unlock()
        if error != nil { finish(.failure(FamilyFailure.networkUnavailable)); return }
        if code == 200 { finish(.success(value)); return }
        struct ServerError: Decodable { let error: String; let retry_after: Int? }
        let allowed = Set(["blocked", "revoked", "invalid_invite", "cooldown", "rate_limited", "already_enrolled",
                           "expired_or_replayed", "invalid_proof", "not_approved", "gateway_unavailable",
                           "too_many_challenges", "owner_required", "management_unavailable", "operation_in_progress",
                           "device_not_found", "device_not_pending", "cannot_modify_owner_phone", "invalid_management",
                           "owner_must_be_approved"])
        if let body = try? JSONDecoder().decode(ServerError.self, from: value), allowed.contains(body.error) {
            finish(.failure(FamilyFailure.server(body.error, min(86400, max(0, body.retry_after ?? 0)))))
        } else { finish(.failure(FamilyFailure.networkUnavailable)) }
    }

    private func finish(_ result: Result<Data, Error>) {
        lock.lock()
        if finished { lock.unlock(); return }
        finished = true
        let complete = completion; completion = nil
        let session = self.session; self.session = nil
        task = nil; bytes.removeAll(keepingCapacity: false)
        lock.unlock()
        session?.invalidateAndCancel()
        complete?(result)
    }
}
