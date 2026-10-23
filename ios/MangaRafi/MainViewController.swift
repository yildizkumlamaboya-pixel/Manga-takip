import UIKit
import WebKit
import UniformTypeIdentifiers

/// Ana ekran: arayüz uygulamanın içinden (web klasörü) yüklenir, "MangaApp" köprüsüyle konuşur.
final class MainViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate, UIDocumentPickerDelegate {
    private var web: WKWebView!
    private let bg = UIColor(red: 0x14 / 255.0, green: 0x12 / 255.0, blue: 0x1a / 255.0, alpha: 1)

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = bg
        let cfg = WKWebViewConfiguration()
        cfg.setURLSchemeHandler(AppScheme(), forURLScheme: AppScheme.scheme)
        cfg.userContentController.add(WeakHandler(self), name: "mt")
        let shim = (try? String(contentsOf: Bundle.main.resourceURL!.appendingPathComponent("web/shim.js"), encoding: .utf8)) ?? ""
        cfg.userContentController.addUserScript(WKUserScript(source: "window.__mtInit=" + initData() + ";\n" + shim,
                                                             injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = bg
        web.scrollView.backgroundColor = bg
        web.navigationDelegate = self
        web.uiDelegate = self
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            web.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        web.load(URLRequest(url: URL(string: "\(AppScheme.scheme)://app/index.html")!))
        NotificationCenter.default.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    private func initData() -> String {
        var settings: [String: String] = ["quiet_on": "false", "quiet_from": "0", "quiet_to": "8"]
        for k in ["quiet_on", "quiet_from", "quiet_to"] { if let v = Store.setting(k) { settings[k] = v } }
        var files: [String: Any] = [:]
        if let l = Store.read("list.json") { files["list.json"] = l }
        if let s = Store.read("state.json") { files["state.json"] = s }
        let d: [String: Any] = ["files": files, "settings": settings, "pos": Store.allPositions(),
                                "ls": Store.localStore, "lastCheck": Store.lastCheck,
                                "checking": CheckRunner.current?.isRunning ?? false]
        return Store.json(d)
    }

    func js(_ code: String) { web?.evaluateJavaScript(code) }

    /// Telefondaki güncel verileri arayüze verir ve ekranı tazeler.
    func refreshUI(summary: String = "") {
        js("__mtSet('files.list.json',\(jsString(Store.read("list.json") ?? "[]")));"
           + "__mtSet('files.state.json',\(jsString(Store.read("state.json") ?? "{}")));"
           + "__mtSet('pos',\(Store.json(Store.allPositions())));"
           + "__mtSet('lastCheck',\(jsString(Store.lastCheck)));"
           + "__mtSet('checking',\(CheckRunner.current?.isRunning ?? false));"
           + "window.__nativeCheckDone && window.__nativeCheckDone(\(jsString(summary)));")
    }

    @objc private func becameActive() {
        refreshUI()
        // Son kontrolün üstünden 15 dakika geçtiyse kendiliğinden kontrol et
        let last = ISO8601DateFormatter().date(from: Store.defaults.string(forKey: "lastRun") ?? "") ?? .distantPast
        if Date().timeIntervalSince(last) > 15 * 60, !Store.list().isEmpty { startCheck(onlyMissing: false) }
    }

    func startCheck(onlyMissing: Bool) {
        let started = CheckRunner.check(onlyMissing: onlyMissing) { [weak self] summary in
            DispatchQueue.main.async { self?.refreshUI(summary: summary) }
        }
        js("__mtSet('checking',\(started || (CheckRunner.current?.isRunning ?? false)))")
    }

    // MARK: köprü
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let o = m.body as? [String: Any], let cmd = o["cmd"] as? String else { return }
        switch cmd {
        case "write":
            if let n = o["n"] as? String, let t = o["t"] as? String { Store.write(n, t) }
        case "check":
            startCheck(onlyMissing: (o["only"] as? Bool) ?? false)
        case "openReader":
            guard let u = o["u"] as? String else { return }
            openReader(series: u, title: o["t"] as? String ?? "", chaptersJSON: o["c"] as? String ?? "[]",
                       num: (o["n"] as? NSNumber)?.doubleValue ?? -1, pageMode: false)
        case "openSite":
            guard let u = o["u"] as? String else { return }
            openReader(series: u, title: o["t"] as? String ?? "", chaptersJSON: "[]", num: -1, pageMode: true)
        case "setSetting":
            if let k = o["k"] as? String, let v = o["v"] as? String { Store.setSetting(k, v) }
        case "ls":
            guard let k = o["k"] as? String else { return }
            var s = Store.localStore
            if let v = o["v"] as? String { s[k] = v } else { s.removeValue(forKey: k) }
            Store.localStore = s
        case "lsClear":
            Store.localStore = [:]
        case "share":
            share(items: [o["t"] as? String ?? ""])
        case "export":
            exportBackup()
        case "import":
            let p = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText, .data])
            p.delegate = self
            present(p, animated: true)
        case "importGithub":
            importGithub(o["r"] as? String ?? "")
        case "clearNotifs":
            Notifier.clearAll()
        case "clearNotif":
            if let u = o["u"] as? String { Notifier.clear(u) }
        default: break
        }
    }

    func openReader(series: String, title: String, chaptersJSON: String, num: Double, pageMode: Bool) {
        var chapters: [(Double, String)] = []
        if let d = chaptersJSON.data(using: .utf8), let a = try? JSONSerialization.jsonObject(with: d) as? [[Any]] {
            for c in a where c.count >= 2 {
                if let n = (c[0] as? NSNumber)?.doubleValue, let u = c[1] as? String { chapters.append((n, u)) }
            }
        }
        let r = ReaderViewController(series: series, title: title, chapters: chapters, num: num, pageMode: pageMode)
        r.onClose = { [weak self] in self?.refreshUI() }
        r.modalPresentationStyle = .fullScreen
        (presentedViewController ?? self).present(r, animated: true)
    }

    /// Bildirime dokununca: o serinin bölümünü aç.
    func openFromNotification(url: String, num: Double) {
        guard let st = Store.state()[url] as? [String: Any] else { return }
        let title = st["title"] as? String ?? ""
        let chapters = Store.json(st["chapters"] ?? [])
        if presentedViewController != nil { dismiss(animated: false) }
        openReader(series: url, title: title, chaptersJSON: chapters, num: num, pageMode: false)
    }

    // MARK: paylaş / yedek
    private func share(items: [Any]) {
        let a = UIActivityViewController(activityItems: items, applicationActivities: nil)
        a.popoverPresentationController?.sourceView = view
        present(a, animated: true)
    }

    private func exportBackup() {
        let list = Store.list(), state = Store.state()
        if list.isEmpty { js("window.__nativeToast && __nativeToast('Listen boş')"); return }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        let b: [String: Any] = ["app": "manga-rafi", "version": 1, "exportedAt": isoNow(), "list": list, "state": state]
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("manga-rafi-yedek-\(f.string(from: Date())).json")
        try? Store.json(b, pretty: true).write(to: url, atomically: true, encoding: .utf8)
        share(items: [url])
    }

    /// Yedek dosyasını (ya da sadece liste dizisini) yazar; seri sayısını döndürür.
    private func importData(_ text: String) -> Int? {
        guard let d = text.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) else { return nil }
        var list: [[String: Any]] = []
        var state: [String: Any] = [:]
        if let a = o as? [[String: Any]] { list = a }
        else if let b = o as? [String: Any], let a = b["list"] as? [[String: Any]] {
            list = a
            state = b["state"] as? [String: Any] ?? [:]
        } else { return nil }
        guard list.allSatisfy({ ($0["url"] as? String)?.isEmpty == false }) else { return nil }
        for k in state.keys { if var e = state[k] as? [String: Any] { e.removeValue(forKey: "via"); e.removeValue(forKey: "phoneAt"); state[k] = e } }
        Store.write("list.json", Store.json(list, pretty: true))
        Store.write("state.json", Store.json(state))
        return list.count
    }

    private func imported(_ n: Int?) {
        DispatchQueue.main.async {
            guard let n = n else { self.js("window.__nativeToast && __nativeToast('Dosya tanınmadı')"); return }
            self.refreshUI()
            self.js("window.__nativeImported && __nativeImported(\(n))")
        }
    }

    func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        let ok = u.startAccessingSecurityScopedResource()
        defer { if ok { u.stopAccessingSecurityScopedResource() } }
        imported((try? String(contentsOf: u, encoding: .utf8)).flatMap { importData($0) })
    }

    private func importGithub(_ repo: String) {
        var r = repo.trimmingCharacters(in: .whitespaces)
        r = r.replacingOccurrences(of: "https://github.com/", with: "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard r.range(of: "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", options: .regularExpression) != nil else {
            js("__nativeToast('\"kullanıcı/depo\" biçiminde yaz')"); return
        }
        let base = "https://raw.githubusercontent.com/\(r)/data/"
        Fetcher.get(base + "list.json") { lr in
            guard case .success(let list) = lr else {
                DispatchQueue.main.async { self.js("__nativeToast('Liste alınamadı')") }
                return
            }
            Fetcher.get(base + "state.json") { sr in
                let state: String
                if case .success(let s) = sr { state = s } else { state = "{}" }
                self.imported(self.importData("{\"list\":\(list),\"state\":\(state)}"))
            }
        }
    }

    // MARK: dış linkler tarayıcıda
    func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let u = a.request.url else { decisionHandler(.cancel); return }
        if u.scheme == AppScheme.scheme || u.scheme == "about" { decisionHandler(.allow); return }
        UIApplication.shared.open(u)
        decisionHandler(.cancel)
    }

    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction, windowFeatures f: WKWindowFeatures) -> WKWebView? {
        if let u = a.request.url, u.scheme != AppScheme.scheme { UIApplication.shared.open(u) }
        return nil
    }

    func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = UIAlertController(title: nil, message: m, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Tamam", style: .default) { _ in completionHandler() })
        present(a, animated: true)
    }

    func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = UIAlertController(title: nil, message: m, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Vazgeç", style: .cancel) { _ in completionHandler(false) })
        a.addAction(UIAlertAction(title: "Evet", style: .default) { _ in completionHandler(true) })
        present(a, animated: true)
    }
}
