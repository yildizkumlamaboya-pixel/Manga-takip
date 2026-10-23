import WebKit

/// Arayüz dosyalarını uygulamanın içinden verir: rafi://app/index.html → web/index.html
final class AppScheme: NSObject, WKURLSchemeHandler {
    static let scheme = "rafi"

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url, let base = Bundle.main.resourceURL?.appendingPathComponent("web") else { return }
        var path = url.path
        if path.isEmpty || path == "/" { path = "/index.html" }
        let file = base.appendingPathComponent(String(path.dropFirst()))
        guard !path.contains(".."), let data = try? Data(contentsOf: file) else {
            task.didFailWithError(NSError(domain: "rafi", code: 404))
            return
        }
        let mime: String
        switch file.pathExtension.lowercased() {
        case "html": mime = "text/html"
        case "js", "mjs": mime = "text/javascript"
        case "css": mime = "text/css"
        case "png": mime = "image/png"
        case "svg": mime = "image/svg+xml"
        case "json": mime = "application/json"
        default: mime = "application/octet-stream"
        }
        let resp = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                   headerFields: ["Content-Type": mime + "; charset=utf-8", "Cache-Control": "no-store",
                                                  "Access-Control-Allow-Origin": "*"])!
        task.didReceive(resp)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}

/// WKUserContentController bırakılmasın diye zayıf aracı.
final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ t: WKScriptMessageHandler) { target = t }
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        target?.userContentController(c, didReceive: m)
    }
}
