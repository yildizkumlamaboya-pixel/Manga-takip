import UIKit
import WebKit

/// Yeni bölüm kontrolü: görünmez bir sayfada checker.js çalışır (ayrıştırma Android ile aynı kod),
/// sayfa indirme, kayıt ve bildirim Swift tarafında yapılır.
final class CheckRunner: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    private(set) static var current: CheckRunner?
    private static var live: [CheckRunner] = [] // çalışırken bellekte tut
    private var web: WKWebView?
    private var completion: ((String) -> Void)?
    private let mode: String
    private let onlyMissing: Bool
    private var done = false

    var isRunning: Bool { !done }

    /// Kontrol başlat; zaten çalışıyorsa false döner.
    @discardableResult
    static func check(onlyMissing: Bool, completion: @escaping (String) -> Void) -> Bool {
        if let c = current, c.isRunning { return false }
        let r = CheckRunner(mode: "check", onlyMissing: onlyMissing, extra: [:], completion: completion)
        current = r
        return true
    }

    /// Okuyucuda açılan seri sayfasından bölümleri al (bulunan bölüm sayısı döner).
    static func savePage(url: String, html: String, completion: @escaping (Int) -> Void) {
        _ = CheckRunner(mode: "page", onlyMissing: false, extra: ["url": url, "html": html]) { s in completion(Int(s) ?? 0) }
    }

    private init(mode: String, onlyMissing: Bool, extra: [String: Any], completion: @escaping (String) -> Void) {
        self.mode = mode
        self.onlyMissing = onlyMissing
        self.completion = completion
        super.init()
        CheckRunner.live.append(self)
        DispatchQueue.main.async { self.start(extra) }
    }

    private func start(_ extra: [String: Any]) {
        var initData: [String: Any] = ["mode": mode, "onlyMissing": onlyMissing, "list": Store.list(), "state": Store.state()]
        for (k, v) in extra { initData[k] = v }
        let cfg = WKWebViewConfiguration()
        cfg.setURLSchemeHandler(AppScheme(), forURLScheme: AppScheme.scheme)
        cfg.userContentController.add(WeakHandler(self), name: "ck")
        cfg.userContentController.addUserScript(WKUserScript(source: "window.__ck=" + Store.json(initData) + ";",
                                                             injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 10, height: 10), configuration: cfg)
        w.alpha = 0.01
        w.isUserInteractionEnabled = false
        w.navigationDelegate = self
        Window.attach(w)
        web = w
        w.load(URLRequest(url: URL(string: "\(AppScheme.scheme)://app/checker.html")!))
        // En fazla 5 dakika
        DispatchQueue.main.asyncAfter(deadline: .now() + 300) { [weak self] in self?.finish("Kontrol zaman aşımına uğradı") }
    }

    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let o = m.body as? [String: Any], let cmd = o["cmd"] as? String else { return }
        switch cmd {
        case "fetch":
            let id = o["id"] as? Int ?? 0
            let url = o["url"] as? String ?? ""
            let reply: (Result<String, Error>) -> Void = { [weak self] r in
                DispatchQueue.main.async {
                    switch r {
                    case .success(let body): self?.web?.evaluateJavaScript("__ckDone(\(id),true,\(jsString(body)))")
                    case .failure(let e): self?.web?.evaluateJavaScript("__ckDone(\(id),false,\(jsString(e.localizedDescription)))")
                    }
                }
            }
            if (o["browser"] as? Bool) == true { BrowserFetch.get(url, completion: reply) } else { Fetcher.get(url, completion: reply) }
        case "entry":
            if let url = o["url"] as? String, let e = o["entry"] as? String { Store.saveEntry(url: url, entryJSON: e) }
        case "notify":
            Notifier.show(title: o["title"] as? String ?? "Yeni bölüm", body: o["body"] as? String ?? "",
                          url: o["url"] as? String ?? "", num: (o["num"] as? NSNumber)?.doubleValue ?? -1)
        case "done":
            finish(o["summary"] as? String ?? "")
        default: break
        }
    }

    func finish(_ summary: String) {
        guard !done else { return }
        done = true
        if mode == "check" && !onlyMissing {
            Store.defaults.set(isoNow(), forKey: "lastRun")
            Store.defaults.set(summary, forKey: "lastSummary")
        }
        web?.stopLoading()
        web?.removeFromSuperview()
        web = nil
        let c = completion
        completion = nil
        if CheckRunner.current === self { CheckRunner.current = nil }
        CheckRunner.live.removeAll { $0 === self }
        c?(summary)
    }
}
