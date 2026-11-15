import Foundation

/// A pinned public manifest is the only network resource used by an installed plugin.
final class TgruPluginDownload: NSObject, URLSessionDataDelegate {
    private var source: TgruPluginSource?
    private var repository: (owner: String, repository: String)?
    private var expectedURL: URL
    private var bytes = Data()
    private var session: URLSession?
    private var completion: ((Result<(TgruPluginSource, TgruPluginManifest), Error>) -> Void)?

    init(source: TgruPluginSource, completion: @escaping (Result<(TgruPluginSource, TgruPluginManifest), Error>) -> Void) {
        self.source = source
        self.expectedURL = source.rawURL
        self.completion = completion
    }

    init(repository: (owner: String, repository: String),
         completion: @escaping (Result<(TgruPluginSource, TgruPluginManifest), Error>) -> Void) {
        self.repository = repository
        self.expectedURL = URL(string: "https://api.github.com/repos/\(repository.owner)/\(repository.repository)/commits/HEAD")!
        self.completion = completion
    }

    func start() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
        self.session = session
        var request = URLRequest(url: expectedURL)
        if repository != nil { request.setValue("application/vnd.github.sha", forHTTPHeaderField: "Accept") }
        request.setValue("TG-ru-plugins", forHTTPHeaderField: "User-Agent")
        session.dataTask(with: request).resume()
    }

    func cancel() {
        session?.invalidateAndCancel()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let response = response as? HTTPURLResponse, response.statusCode == 200,
              response.url == expectedURL, response.expectedContentLength <= Int64(repository != nil ? 128 : TgruPluginManifest.maximumBytes)
        else { completionHandler(.cancel); return }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard bytes.count + data.count <= (repository != nil ? 128 : TgruPluginManifest.maximumBytes) else {
            dataTask.cancel()
            return
        }
        bytes.append(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let result: Result<(TgruPluginSource, TgruPluginManifest), Error>
        if let error { result = .failure(error) }
        else {
            do {
                if let repository {
                    guard let commit = String(data: bytes, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) else {
                        throw TgruPluginError.invalidURL
                    }
                    let source = try TgruPluginSource("https://raw.githubusercontent.com/\(repository.owner)/\(repository.repository)/\(commit)/tgru-plugin.json")
                    self.source = source
                    self.repository = nil
                    expectedURL = source.rawURL
                    bytes.removeAll()
                    session.dataTask(with: expectedURL).resume()
                    return
                }
                guard let source else { throw TgruPluginError.invalidURL }
                result = .success((source, try TgruPluginManifest.parse(bytes)))
            }
            catch { result = .failure(error) }
        }
        bytes.removeAll()
        session.finishTasksAndInvalidate()
        self.session = nil
        let callback = completion
        completion = nil
        DispatchQueue.main.async { callback?(result) }
    }
}
