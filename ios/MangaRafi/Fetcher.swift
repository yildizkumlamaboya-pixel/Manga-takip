import Foundation
import UIKit
import WebKit

/// Seri sayfalarını indirir. Bot koruması varsa sayfayı görünmez bir tarayıcıda açar.
enum Fetcher {
    static let ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    static func isChallenge(_ s: String) -> Bool {
        let head = s.prefix(20000)
        if head.range(of: "<title>\\s*(Just a moment|Attention Required|Bir dakika)", options: [.regularExpression, .caseInsensitive]) != nil { return true }
        return s.contains("cf_chl_opt") || s.contains("cf-chl-widget")
    }

    static func get(_ url: String, completion: @escaping (Result<String, Error>) -> Void) {
        guard let u = URL(string: url) else { completion(.failure(err("geçersiz adres"))); return }
        var r = URLRequest(url: u, timeoutInterval: 25)
        r.setValue(ua, forHTTPHeaderField: "User-Agent")
        r.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        r.setValue("tr-TR,tr;q=0.9,en;q=0.6", forHTTPHeaderField: "Accept-Language")
        URLSession.shared.dataTask(with: r) { data, resp, error in
            if let error = error { completion(.failure(error)); return }
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let body = data.flatMap { String(data: $0, encoding: .utf8) ?? String(data: $0, encoding: .isoLatin1) } ?? ""
            if code != 200 { completion(.failure(err("HTTP \(code)"))); return }
            if isChallenge(body) { completion(.failure(err("tarayıcı doğrulaması istedi"))); return }
            completion(.success(body))
        }.resume()
    }

    static func err(_ m: String) -> NSError { NSError(domain: "rafi", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }
}

/// Görünmez tarayıcıyla sayfa alma (okuyucuyla aynı çerezleri kullanır, doğrulama kendiliğinden geçer).
final class BrowserFetch: NSObject, WKNavigationDelegate {
    private static var live: [BrowserFetch] = []
    private let web: WKWebView
    private let completion: (Result<String, Error>) -> Void
    private var lastLen = -1
    private var finished = false
    private let started = Date()

    static func get(_ url: String, completion: @escaping (Result<String, Error>) -> Void) {
        DispatchQueue.main.async {
            let f = BrowserFetch(url: url, completion: completion)
            live.append(f)
        }
    }

    private init(url: String, completion: @escaping (Result<String, Error>) -> Void) {
        let cfg = WKWebViewConfiguration()
        web = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: cfg)
        self.completion = completion
        super.init()
        web.customUserAgent = Fetcher.ua
        web.navigationDelegate = self
        web.alpha = 0.01
        web.isUserInteractionEnabled = false
        Window.attach(web)
        if let u = URL(string: url) { web.load(URLRequest(url: u)) }
        poll()
    }

    private func poll() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self = self, !self.finished else { return }
            self.web.evaluateJavaScript("document.documentElement ? document.documentElement.outerHTML : ''") { v, _ in
                let html = v as? String ?? ""
                let ready = !self.web.isLoading && html.count > 3000 && !Fetcher.isChallenge(html) && html.count == self.lastLen
                self.lastLen = html.count
                if ready { self.finish(.success(html)); return }
                if Date().timeIntervalSince(self.started) > 25 {
                    self.finish(html.count > 3000 && !Fetcher.isChallenge(html) ? .success(html) : .failure(Fetcher.err("tarayıcı doğrulaması geçilemedi")))
                    return
                }
                self.poll()
            }
        }
    }

    private func finish(_ r: Result<String, Error>) {
        guard !finished else { return }
        finished = true
        web.stopLoading()
        web.removeFromSuperview()
        completion(r)
        BrowserFetch.live.removeAll { $0 === self }
    }
}

/// Görünmez web görünümlerini pencereye takmak için (iOS, pencerede olmayan sayfaların zamanlayıcılarını yavaşlatabiliyor).
enum Window {
    static func attach(_ v: UIView) {
        let w = UIApplication.shared.windows.first { $0.isKeyWindow } ?? UIApplication.shared.windows.first
        if let w = w { w.insertSubview(v, at: 0) }
    }
}
