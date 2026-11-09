import Foundation
import UIKit
import WebKit

/// Native controls and result text live in the Telegram bubble. WebKit is an invisible execution engine only.
final class FamilyPythonInlineView: UIView {
    private let runButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let blockButton = UIButton(type: .system)
    private let input = UITextField()
    private let output = UITextView()
    private var blocks: [String] = []
    private var selected = 0
    private var identity = ""
    private var runtime: FamilyPythonRuntime?
    override init(frame: CGRect) {
        super.init(frame: frame)
        runButton.setTitle("Запустить", for: .normal); stopButton.setTitle("Стоп", for: .normal)
        runButton.addTarget(self, action: #selector(run), for: .touchUpInside)
        stopButton.addTarget(self, action: #selector(stop), for: .touchUpInside)
        stopButton.isEnabled = false
        input.placeholder = "Ввод для input()"; input.borderStyle = .roundedRect
        input.autocorrectionType = .no; input.autocapitalizationType = .none
        input.accessibilityLabel = "Ввод для Python"
        output.isEditable = false; output.isSelectable = true; output.backgroundColor = .clear
        output.textContainerInset = UIEdgeInsets(top: 4, left: 0, bottom: 4, right: 0)
        output.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 13, weight: .regular))
        output.adjustsFontForContentSizeCategory = true
        output.text = "Результат появится здесь."
        output.accessibilityLabel = "Результат Python"
        let controls = UIStackView(arrangedSubviews: [blockButton, runButton, stopButton])
        controls.axis = .horizontal; controls.distribution = .fillEqually; controls.spacing = 4
        let stack = UIStackView(arrangedSubviews: [controls, input, output])
        stack.axis = .vertical; stack.spacing = 4; stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            controls.heightAnchor.constraint(equalToConstant: 44), input.heightAnchor.constraint(equalToConstant: 44)
        ])
    }
    required init?(coder: NSCoder) { return nil }
    deinit { runtime?.stop() }
    func configure(identity: String, blocks: [String], textColor: UIColor, accent: UIColor) {
        if self.identity != identity || self.blocks != blocks {
            stop(); self.identity = identity; self.blocks = blocks; selected = 0
            output.text = "Результат появится здесь."; input.text = nil
        }
        output.textColor = textColor; input.textColor = textColor
        runButton.tintColor = accent; stopButton.tintColor = accent; blockButton.tintColor = accent
        updateMenu()
    }
    private func updateMenu() {
        blockButton.setTitle(blocks.count > 1 ? "Python \(selected + 1)" : "Python", for: .normal)
        blockButton.accessibilityLabel = "Блок Python \(selected + 1) из \(blocks.count)"
        if #available(iOS 14.0, *) {
            blockButton.showsMenuAsPrimaryAction = true
            blockButton.menu = UIMenu(children: blocks.indices.map { index in
                UIAction(title: "Блок \(index + 1)", state: index == selected ? .on : .off) { [weak self] _ in
                    self?.stop(); self?.selected = index; self?.output.text = "Результат появится здесь."; self?.updateMenu()
                }
            })
        }
    }
    @objc private func run() {
        guard blocks.indices.contains(selected), UserDefaults.standard.bool(forKey: "family.features.python") else { return }
        stop(); input.resignFirstResponder()
        runButton.isEnabled = false; stopButton.isEnabled = true
        output.text = "Запуск Python…"
        let instance = FamilyPythonRuntime()
        runtime = instance
        instance.run(code: blocks[selected], input: input.text ?? "") { [weak self, weak instance] type, text in
            guard let self, let instance, self.runtime === instance else { return }
            if type == "loading" { self.output.text = "Запуск Python…" }
            else if type == "running" { self.output.text = "Выполняется…" }
            else {
                self.output.text = text
                self.runButton.isEnabled = true; self.stopButton.isEnabled = false
                self.runtime?.stop(); self.runtime = nil
            }
        }
    }
    @objc private func stop() {
        if runtime != nil { output.text = "Выполнение остановлено." }
        runtime?.stop(); runtime = nil; runButton.isEnabled = true; stopButton.isEnabled = false
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { stop() }
    }
}

private final class FamilyPythonRuntime: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKURLSchemeHandler {
    private var webView: WKWebView?
    private var timer: DispatchWorkItem?
    private var callback: ((String, String) -> Void)?
    private var payload: String?
    private var runID = UUID().uuidString
    private var running = false
    private var root: URL?
    private let allowed: Set<String> = ["index.html", "controller.mjs", "worker.mjs", "runtime/pyodide.mjs", "runtime/pyodide.asm.mjs", "runtime/pyodide.asm.wasm", "runtime/python_stdlib.zip", "runtime/pyodide-lock.json"]
    private let origin = "familypython://bundle/runner/"
    private let csp = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; worker-src 'self'; style-src 'none'; img-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    func run(code: String, input: String, callback: @escaping (String, String) -> Void) {
        self.callback = callback
        guard code.utf8.count <= 65536, input.utf8.count <= 16384 else { fail("Код или ввод превышает лимит."); return }
        guard let bundleURL = Bundle.main.url(forResource: "FamilyPython", withExtension: "bundle"),
              let bundle = Bundle(url: bundleURL),
              let entry = bundle.url(forResource: "index", withExtension: "html", subdirectory: "family-python/runner") else {
            fail("Среда Python отсутствует в этой сборке."); return
        }
        root = entry.deletingLastPathComponent().standardizedFileURL
        guard let data = try? JSONSerialization.data(withJSONObject: ["id": runID, "code": code, "input": input]), let json = String(data: data, encoding: .utf8) else { fail("Не удалось подготовить код."); return }
        payload = json
        arm(seconds: 35, message: "Среда Python не запустилась. Повторите попытку.")
        // Defense in depth: navigation rejection alone would not block worker/subresource requests.
        let rules = "[{\"trigger\":{\"url-filter\":\"^(https?|wss?|ftp)://\"},\"action\":{\"type\":\"block\"}}]"
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "FamilyPythonNoNetworkV1", encodedContentRuleList: rules) { [weak self] rule, error in
            guard let self, self.callback != nil else { return }
            guard let rule, error == nil else { self.fail("Не удалось включить изоляцию Python."); return }
            let configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = .nonPersistent()
            configuration.userContentController.add(rule)
            configuration.userContentController.add(self, name: "familyPython")
            configuration.setURLSchemeHandler(self, forURLScheme: "familypython")
            let webView = WKWebView(frame: .zero, configuration: configuration)
            self.webView = webView; webView.navigationDelegate = self
            webView.load(URLRequest(url: URL(string: self.origin + "index.html")!))
        }
    }
    func stop() {
        timer?.cancel(); timer = nil; callback = nil; payload = nil
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "familyPython")
        webView?.navigationDelegate = nil; webView?.stopLoading(); webView = nil
    }
    private func arm(seconds: Double, message: String) {
        timer?.cancel()
        let deadline = DispatchWorkItem { [weak self] in self?.fail(message) }
        timer = deadline; DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: deadline)
    }
    private func fail(_ text: String) { callback?("error", text); stop() }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "familyPython", message.frameInfo.isMainFrame,
              message.frameInfo.request.url?.absoluteString == origin + "index.html",
              let event = message.body as? [String: Any], let type = event["type"] as? String else { return }
        if type == "controller_ready" {
            guard let payload else { return }; self.payload = nil
            webView?.evaluateJavaScript("globalThis.executePython(\(payload))") { [weak self] _, error in
                if error != nil { self?.fail("Не удалось запустить код Python.") }
            }
            return
        }
        guard event["id"] as? String == runID else { return }
        if type == "running", !running { running = true; arm(seconds: 10, message: "Выполнение остановлено: превышен лимит 10 секунд.") }
        guard ["loading", "running", "result", "error"].contains(type) else { return }
        let text = String((event["text"] as? String ?? "").prefix(65536))
        callback?(type, text)
        if type == "result" || type == "error" { stop() }
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(navigationAction.request.url?.absoluteString == origin + "index.html" && navigationAction.targetFrame?.isMainFrame == true ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail("Среда Python не загрузилась.") }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail("Среда Python не загрузилась.") }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { fail("iOS остановила среду Python. Повторите попытку.") }
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url, let root, url.scheme == "familypython", url.host == "bundle",
              url.user == nil, url.password == nil, url.port == nil, url.query == nil, url.fragment == nil,
              url.path.hasPrefix("/runner/") else { urlSchemeTask.didFailWithError(URLError(.noPermissionsToReadFile)); return }
        let name = String(url.path.dropFirst("/runner/".count))
        guard allowed.contains(name) else { urlSchemeTask.didFailWithError(URLError(.noPermissionsToReadFile)); return }
        let file = root.appendingPathComponent(name).standardizedFileURL
        guard file.path.hasPrefix(root.path + "/"), let data = try? Data(contentsOf: file, options: .mappedIfSafe), data.count <= 32 * 1024 * 1024 else {
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist)); return
        }
        let mime = name.hasSuffix(".mjs") ? "text/javascript" : name.hasSuffix(".wasm") ? "application/wasm" : name.hasSuffix(".html") ? "text/html" : name.hasSuffix(".json") ? "application/json" : "application/octet-stream"
        guard let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": mime, "Content-Security-Policy": csp, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"]) else {
            urlSchemeTask.didFailWithError(URLError(.badServerResponse)); return
        }
        urlSchemeTask.didReceive(response); urlSchemeTask.didReceive(data); urlSchemeTask.didFinish()
    }
    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) { }
}
