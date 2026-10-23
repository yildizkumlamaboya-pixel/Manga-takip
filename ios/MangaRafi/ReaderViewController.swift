import UIKit
import WebKit

/// Okuyucu: bölümü sitenin kendi sayfası olarak gösterir. Üstte listeye dönüş, yenile,
/// önceki/sonraki bölüm. Açılan bölüm okundu sayılır, kaldığın satır hatırlanır.
final class ReaderViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    var onClose: (() -> Void)?
    private let series: String
    private let seriesTitle: String
    private var chapters: [(Double, String)]
    private var current: Double
    private let pageMode: Bool
    private var harvested = false
    private var web: WKWebView!
    private let titleLabel = UILabel()
    private let prevBtn = UIButton(type: .system)
    private let nextBtn = UIButton(type: .system)
    private var restoreY = -1
    private var restoreRatio = -1.0
    private var noSaveUntil = Date.distantFuture
    private var saver: Timer?
    private let siteHost: String
    private static var rules: WKContentRuleList?

    private let bg = UIColor(red: 0x14 / 255.0, green: 0x12 / 255.0, blue: 0x1a / 255.0, alpha: 1)
    private let bar = UIColor(red: 0x1f / 255.0, green: 0x1c / 255.0, blue: 0x28 / 255.0, alpha: 1)
    private let accent = UIColor(red: 1, green: 0x6b / 255.0, blue: 0x4a / 255.0, alpha: 1)

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    /// Reklam engel listesini bir kez derler.
    static func prepareRules() {
        guard rules == nil, let u = Bundle.main.resourceURL?.appendingPathComponent("web/adrules.json"),
              let json = try? String(contentsOf: u, encoding: .utf8) else { return }
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "reklam", encodedContentRuleList: json) { list, _ in
            rules = list
        }
    }

    init(series: String, title: String, chapters: [(Double, String)], num: Double, pageMode: Bool) {
        self.series = series
        self.seriesTitle = title
        self.chapters = chapters.sorted { $0.0 < $1.0 }
        self.current = num
        self.pageMode = pageMode
        var h = URL(string: series)?.host ?? ""
        if h.hasPrefix("www.") { h = String(h.dropFirst(4)) }
        self.siteHost = h
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = bg

        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        if let r = ReaderViewController.rules { cfg.userContentController.add(r) }
        if let u = Bundle.main.resourceURL?.appendingPathComponent("web/reader.js"), let s = try? String(contentsOf: u, encoding: .utf8) {
            cfg.userContentController.addUserScript(WKUserScript(source: s, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
        web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.isOpaque = false
        web.backgroundColor = bg
        web.allowsBackForwardNavigationGestures = true

        // Üst şerit
        let back = UIButton(type: .system)
        back.setTitle("‹ Liste", for: .normal)
        back.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        back.tintColor = .white
        back.addTarget(self, action: #selector(close), for: .touchUpInside)
        titleLabel.textColor = .white
        titleLabel.font = .boldSystemFont(ofSize: 14)
        titleLabel.lineBreakMode = .byTruncatingTail
        let refresh = UIButton(type: .system)
        refresh.setTitle("↻", for: .normal)
        refresh.titleLabel?.font = .systemFont(ofSize: 22)
        refresh.tintColor = .white
        refresh.addTarget(self, action: #selector(refreshPage), for: .touchUpInside)
        let top = UIStackView(arrangedSubviews: [back, titleLabel, refresh])
        top.spacing = 10
        top.alignment = .center
        back.setContentHuggingPriority(.required, for: .horizontal)
        refresh.setContentHuggingPriority(.required, for: .horizontal)

        for (b, t, primary) in [(prevBtn, "‹  Önceki", false), (nextBtn, "Sonraki  ›", true)] {
            b.setTitle(t, for: .normal)
            b.titleLabel?.font = .boldSystemFont(ofSize: 17)
            b.backgroundColor = primary ? accent : UIColor(red: 0x2a / 255.0, green: 0x26 / 255.0, blue: 0x35 / 255.0, alpha: 1)
            b.setTitleColor(primary ? UIColor(red: 0x1a / 255.0, green: 0x0d / 255.0, blue: 0x08 / 255.0, alpha: 1) : .white, for: .normal)
            b.layer.cornerRadius = 12
            b.heightAnchor.constraint(equalToConstant: 48).isActive = true
        }
        prevBtn.addTarget(self, action: #selector(goPrev), for: .touchUpInside)
        nextBtn.addTarget(self, action: #selector(goNext), for: .touchUpInside)
        let nav = UIStackView(arrangedSubviews: [prevBtn, nextBtn])
        nav.spacing = 8
        nav.distribution = .fillEqually

        let header = UIStackView(arrangedSubviews: [top, nav])
        header.axis = .vertical
        header.spacing = 6
        header.isLayoutMarginsRelativeArrangement = true
        header.layoutMargins = UIEdgeInsets(top: 4, left: 10, bottom: 8, right: 10)
        let headerBg = UIView()
        headerBg.backgroundColor = bar

        for v in [headerBg, header, web!] as [UIView] { v.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(v) }
        NSLayoutConstraint.activate([
            headerBg.topAnchor.constraint(equalTo: view.topAnchor),
            headerBg.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            headerBg.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            headerBg.bottomAnchor.constraint(equalTo: header.bottomAnchor),
            header.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            header.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.topAnchor.constraint(equalTo: header.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            web.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        if pageMode {
            titleLabel.text = seriesTitle + " · seri sayfası"
            prevBtn.isEnabled = false; prevBtn.alpha = 0.35
            nextBtn.isEnabled = false; nextBtn.alpha = 0.35
            toast("Sayfa açılınca bölümleri alacağım…")
            if let u = URL(string: series) { web.load(URLRequest(url: u)) }
        } else {
            open(current)
        }
        saver = Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in self?.savePos() }
    }

    // MARK: bölümler
    private func index(of n: Double) -> Int? { chapters.firstIndex { $0.0 == n } }

    private func open(_ n: Double) {
        guard !chapters.isEmpty else { close(); return }
        let i = index(of: n) ?? chapters.count - 1
        current = chapters[i].0
        loadSavedPos(current)
        updateBar()
        if let u = URL(string: chapters[i].1) { web.load(URLRequest(url: u)) }
    }

    private func updateBar() {
        let i = index(of: current)
        titleLabel.text = (seriesTitle.isEmpty ? "" : seriesTitle + " · ") + "Bölüm " + fmt(current)
        let hasPrev = (i ?? 0) > 0, hasNext = i != nil && i! < chapters.count - 1
        prevBtn.isEnabled = hasPrev; prevBtn.alpha = hasPrev ? 1 : 0.35
        nextBtn.isEnabled = hasNext; nextBtn.alpha = hasNext ? 1 : 0.35
    }

    private func fmt(_ n: Double) -> String { n == n.rounded() ? String(Int(n)) : String(n) }

    @objc private func goPrev() { step(-1) }
    @objc private func goNext() { step(1) }

    private func step(_ d: Int) {
        savePos()
        guard let i = index(of: current), i + d >= 0, i + d < chapters.count else { return }
        open(chapters[i + d].0)
    }

    @objc private func close() {
        savePos()
        saver?.invalidate()
        dismiss(animated: true) { self.onClose?() }
    }

    // MARK: sayfa
    func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard a.targetFrame?.isMainFrame ?? true else { decisionHandler(.allow); return }
        let h = a.request.url?.host ?? ""
        // Sitenin kendi sayfaları ve doğrulama açılır; reklam yönlendirmeleri engellenir
        if h.hasSuffix(siteHost) || h.contains("cloudflare") || a.request.url?.scheme == "about" { decisionHandler(.allow) }
        else { decisionHandler(.cancel) }
    }

    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction, windowFeatures f: WKWindowFeatures) -> WKWebView? {
        nil // açılır pencereler (reklam) açılmaz
    }

    func webView(_ w: WKWebView, didFinish n: WKNavigation!) {
        if pageMode && !harvested { harvest(0, last: -1); return }
        if restoreY >= 0 { restoreScroll() } else { noSaveUntil = Date().addingTimeInterval(1.5) }
        chapterShown(w.url)
    }

    /// Sitenin kendi düğmesiyle geçildiyse bölüm numarasını linkten bul, okundu say.
    private func chapterShown(_ url: URL?) {
        if let path = url?.path.replacingOccurrences(of: "/+$", with: "", options: .regularExpression),
           let sp = URL(string: series)?.path.replacingOccurrences(of: "/+$", with: "", options: .regularExpression),
           path.hasPrefix(sp + "/"), let n = numFromPath(String(path.dropFirst(sp.count + 1))) {
            current = n
        }
        updateBar()
        if current >= 0 { Store.markRead(series: series, num: current) }
    }

    private func numFromPath(_ rest: String) -> Double? {
        let last = rest.split(separator: "/").last.map(String.init) ?? ""
        guard let re = try? NSRegularExpression(pattern: "(\\d+)(?:[.\\-_](\\d+))?"),
              let m = re.firstMatch(in: last, range: NSRange(last.startIndex..., in: last)),
              let a = Range(m.range(at: 1), in: last) else { return nil }
        var s = String(last[a])
        if let b = Range(m.range(at: 2), in: last) { s += "." + last[b] }
        return Double(s)
    }

    // MARK: yenile
    @objc private func refreshPage() {
        let fix = (Bundle.main.resourceURL.flatMap { try? String(contentsOf: $0.appendingPathComponent("web/fix.js"), encoding: .utf8) }) ?? "0"
        web.evaluateJavaScript(fix) { [weak self] v, _ in
            guard let self = self else { return }
            let n = (v as? NSNumber)?.intValue ?? 0
            if n > 0 { self.toast("\(n) resim yeniden yükleniyor"); return }
            self.web.evaluateJavaScript("scrollY+','+document.documentElement.scrollHeight") { v, _ in
                let p = (v as? String ?? "").split(separator: ",").compactMap { Double($0) }
                if p.count == 2 { self.restoreY = Int(p[0]); self.restoreRatio = p[1] > 0 ? p[0] / p[1] : -1 }
                self.noSaveUntil = .distantFuture
                self.toast("Sayfa yenileniyor…")
                self.web.reload()
            }
        }
    }

    // MARK: kaldığın satır
    private func loadSavedPos(_ n: Double) {
        restoreY = -1
        noSaveUntil = .distantFuture
        guard let t = Store.position(series), let d = t.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              (o["num"] as? NSNumber)?.doubleValue == n,
              let p = (o["p"] as? NSNumber)?.doubleValue, p < 0.95,
              let y = (o["y"] as? NSNumber)?.intValue, y >= 300 else { return }
        restoreY = y
        let h = (o["h"] as? NSNumber)?.doubleValue ?? 0
        restoreRatio = h > 0 ? Double(y) / h : -1
        toast("Kaldığın yere iniliyor…")
    }

    private func restoreScroll() {
        let y = restoreY, r = restoreRatio
        restoreY = -1
        noSaveUntil = Date().addingTimeInterval(11)
        web.evaluateJavaScript("(function(){var y=\(y),r=\(r),t=0,stop=false;"
            + "addEventListener('touchstart',function(){stop=true;},{once:true,passive:true});"
            + "var h=setInterval(function(){if(stop||t++>40){clearInterval(h);return;}"
            + "var H=document.documentElement.scrollHeight,target=(H>=y+innerHeight)?y:(r>0?r*H:y);"
            + "if(Math.abs(scrollY-target)>40)scrollTo(0,target);},250);})()")
    }

    private func savePos() {
        guard current >= 0, !pageMode, Date() >= noSaveUntil else { return }
        let num = current
        web.evaluateJavaScript("scrollY+','+document.documentElement.scrollHeight+','+innerHeight") { [weak self] v, _ in
            guard let self = self, Date() >= self.noSaveUntil else { return }
            let p = (v as? String ?? "").split(separator: ",").compactMap { Double($0) }
            guard p.count == 3, p[1] > p[2] * 1.5 else { return }
            let o: [String: Any] = ["num": num, "y": Int(p[0]), "h": p[1], "p": min(1, (p[0] + p[2]) / p[1]),
                                    "at": Int(Date().timeIntervalSince1970 * 1000)]
            Store.setPosition(self.series, Store.json(o))
        }
    }

    // MARK: seri sayfasından bölümleri alma
    private func harvest(_ attempt: Int, last: Int) {
        guard !harvested else { return }
        web.evaluateJavaScript("document.documentElement?document.documentElement.outerHTML:''") { [weak self] v, _ in
            guard let self = self else { return }
            let html = v as? String ?? ""
            let len = html.count
            let ready = len > 3000 && !Fetcher.isChallenge(html) && len == last && !self.web.isLoading
            if !ready && attempt < 25 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.harvest(attempt + 1, last: len) }
                return
            }
            self.harvested = true
            CheckRunner.savePage(url: self.series, html: html) { n in
                DispatchQueue.main.async {
                    if n > 0 {
                        if let st = Store.state()[self.series] as? [String: Any], let a = st["chapters"] as? [[Any]] {
                            self.chapters = a.compactMap { c in
                                guard c.count >= 2, let n = (c[0] as? NSNumber)?.doubleValue, let u = c[1] as? String else { return nil }
                                return (n, u)
                            }.sorted { $0.0 < $1.0 }
                        }
                        self.toast("\(n) bölüm bulundu, listene eklendi. Bir bölüme dokunarak okumaya başlayabilirsin.")
                    } else {
                        self.toast("Bu sayfada bölüm bulunamadı")
                    }
                }
            }
        }
    }

    // MARK: küçük bilgi yazısı
    private func toast(_ text: String) {
        let l = PaddedLabel()
        l.text = text
        l.numberOfLines = 0
        l.textColor = .white
        l.font = .systemFont(ofSize: 14)
        l.backgroundColor = UIColor(white: 0.1, alpha: 0.92)
        l.layer.cornerRadius = 12
        l.clipsToBounds = true
        l.textAlignment = .center
        l.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(l)
        NSLayoutConstraint.activate([
            l.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            l.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -30),
            l.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -40),
        ])
        UIView.animate(withDuration: 0.3, delay: 2.8, options: []) { l.alpha = 0 } completion: { _ in l.removeFromSuperview() }
    }
}

final class PaddedLabel: UILabel {
    override func drawText(in rect: CGRect) { super.drawText(in: rect.inset(by: UIEdgeInsets(top: 10, left: 14, bottom: 10, right: 14))) }
    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + 28, height: s.height + 20)
    }
}
