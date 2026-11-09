import Foundation
import UIKit
import PDFKit

/// Called only with a completed local MediaBox resource. Other document formats retain Telegram's Quick Look.
func familyPresentPDF(from parent: UIViewController, path: String, title: String, canShare: Bool) -> Bool {
    guard UserDefaults.standard.bool(forKey: "family.features.documents"),
          let size = try? URL(fileURLWithPath: path).resourceValues(forKeys: [.fileSizeKey]).fileSize,
          size > 0, size <= 64 * 1024 * 1024,
          let document = PDFDocument(url: URL(fileURLWithPath: path)) else { return false }
    let controller = FamilyPDFController(document: document, title: title, canShare: canShare)
    let navigation = UINavigationController(rootViewController: controller)
    navigation.modalPresentationStyle = .fullScreen
    parent.present(navigation, animated: true)
    return true
}

private final class FamilyPDFController: UIViewController, PDFViewDelegate {
    private let document: PDFDocument
    private let pdfView = PDFView()
    private let canShare: Bool
    private let pageButton = UIButton(type: .system)
    private var observer: NSObjectProtocol?
    init(document: PDFDocument, title: String, canShare: Bool) {
        self.document = document; self.canShare = canShare
        super.init(nibName: nil, bundle: nil); self.title = title
    }
    required init?(coder: NSCoder) { return nil }
    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        pdfView.backgroundColor = .secondarySystemBackground
        pdfView.delegate = self; pdfView.document = document
        pdfView.displayMode = .singlePageContinuous; pdfView.displayDirection = .vertical
        pdfView.autoScales = true; pdfView.translatesAutoresizingMaskIntoConstraints = false
        pdfView.accessibilityLabel = "Документ PDF"
        view.addSubview(pdfView)
        NSLayoutConstraint.activate([
            pdfView.leadingAnchor.constraint(equalTo: view.leadingAnchor), pdfView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            pdfView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), pdfView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)
        ])
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .close, target: self, action: #selector(close))
        if canShare { navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .action, target: self, action: #selector(share)) }
        pageButton.addTarget(self, action: #selector(jump), for: .touchUpInside)
        pageButton.titleLabel?.font = .preferredFont(forTextStyle: .body)
        pageButton.titleLabel?.adjustsFontForContentSizeCategory = true
        pageButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        pageButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 88).isActive = true
        toolbarItems = [
            UIBarButtonItem(title: "−", style: .plain, target: self, action: #selector(zoomOut)),
            UIBarButtonItem(title: "+", style: .plain, target: self, action: #selector(zoomIn)),
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
            UIBarButtonItem(customView: pageButton),
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
            UIBarButtonItem(title: "По размеру", style: .plain, target: self, action: #selector(fit))
        ]
        toolbarItems?[0].accessibilityLabel = "Уменьшить масштаб"
        toolbarItems?[1].accessibilityLabel = "Увеличить масштаб"
        navigationController?.setToolbarHidden(false, animated: false)
        observer = NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: pdfView, queue: .main) { [weak self] _ in self?.updatePage() }
        updatePage()
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if document.isLocked { unlock() } else { fit() }
    }
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        let wasFitted = pdfView.autoScales
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in if wasFitted { self?.fit() } }
    }
    // PDF links never launch a browser or other application from this local viewer.
    func pdfViewWillClick(onLink sender: PDFView, with url: URL) { }
    @objc private func close() { dismiss(animated: true) }
    @objc private func fit() {
        let fit = pdfView.scaleFactorForSizeToFit
        guard fit > 0 else { return }
        pdfView.minScaleFactor = fit * 0.5; pdfView.maxScaleFactor = max(fit * 8, 8)
        pdfView.autoScales = true; pdfView.scaleFactor = fit
    }
    @objc private func zoomIn() { pdfView.autoScales = false; pdfView.scaleFactor = min(pdfView.maxScaleFactor, pdfView.scaleFactor * 1.25) }
    @objc private func zoomOut() { pdfView.autoScales = false; pdfView.scaleFactor = max(pdfView.minScaleFactor, pdfView.scaleFactor / 1.25) }
    private func updatePage() {
        let page = pdfView.currentPage.map { document.index(for: $0) + 1 } ?? 0
        pageButton.setTitle("\(page) / \(document.pageCount)", for: .normal)
        pageButton.accessibilityLabel = "Страница \(page) из \(document.pageCount). Перейти к странице"
    }
    @objc private func jump() {
        guard document.pageCount > 0 else { return }
        let alert = UIAlertController(title: "Перейти к странице", message: "1–\(document.pageCount)", preferredStyle: .alert)
        alert.addTextField { $0.keyboardType = .numberPad; $0.placeholder = "Номер страницы" }
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Перейти", style: .default) { [weak self, weak alert] _ in
            guard let self, let text = alert?.textFields?.first?.text, let number = Int(text),
                  number >= 1, number <= self.document.pageCount, let page = self.document.page(at: number - 1) else { return }
            self.pdfView.go(to: page)
        })
        present(alert, animated: true)
    }
    private func unlock() {
        let alert = UIAlertController(title: "Пароль документа", message: nil, preferredStyle: .alert)
        alert.addTextField { $0.isSecureTextEntry = true; $0.placeholder = "Пароль" }
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel) { [weak self] _ in self?.close() })
        alert.addAction(UIAlertAction(title: "Открыть", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            if self.document.unlock(withPassword: alert?.textFields?.first?.text ?? "") { self.fit(); self.updatePage() }
            else { self.unlock() }
        })
        present(alert, animated: true)
    }
    @objc private func share() {
        guard canShare, let url = document.documentURL else { return }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItem
        present(sheet, animated: true)
    }
}
