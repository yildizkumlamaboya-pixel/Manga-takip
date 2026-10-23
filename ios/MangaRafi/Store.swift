import Foundation

/// Telefondaki veriler: list.json (takip listesi + kaldığın yer), state.json (bölümler).
/// Ayarlar UserDefaults'ta durur.
enum Store {
    private static let lock = NSRecursiveLock()
    static var dir: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }
    private static func file(_ name: String) -> URL? {
        guard name == "list.json" || name == "state.json" else { return nil }
        return dir.appendingPathComponent(name)
    }

    static func read(_ name: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let f = file(name) else { return nil }
        return try? String(contentsOf: f, encoding: .utf8)
    }

    static func write(_ name: String, _ text: String) {
        lock.lock(); defer { lock.unlock() }
        guard let f = file(name) else { return }
        try? text.write(to: f, atomically: true, encoding: .utf8)
    }

    static func list() -> [[String: Any]] {
        guard let t = read("list.json"), let d = t.data(using: .utf8),
              let a = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] else { return [] }
        return a
    }

    static func state() -> [String: Any] {
        guard let t = read("state.json"), let d = t.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return [:] }
        return o
    }

    static func json(_ o: Any, pretty: Bool = false) -> String {
        guard JSONSerialization.isValidJSONObject(o),
              let d = try? JSONSerialization.data(withJSONObject: o, options: pretty ? [.prettyPrinted] : []) else { return "null" }
        return String(data: d, encoding: .utf8) ?? "null"
    }

    /// Kontrolün okuduğu tek seriyi yazar (seri bu arada listeden çıkarıldıysa yazmaz).
    static func saveEntry(url: String, entryJSON: String) {
        lock.lock(); defer { lock.unlock() }
        guard list().contains(where: { ($0["url"] as? String) == url }),
              let d = entryJSON.data(using: .utf8), let e = try? JSONSerialization.jsonObject(with: d) else { return }
        var st = state()
        st[url] = e
        write("state.json", json(st))
    }

    /// Kaldığın bölümü kaydeder (sadece ileri gider).
    static func markRead(series: String, num: Double) {
        lock.lock(); defer { lock.unlock() }
        var l = list()
        var changed = false
        for i in l.indices where (l[i]["url"] as? String) == series {
            let prev = (l[i]["lastRead"] as? NSNumber)?.doubleValue ?? -1
            if num > prev { l[i]["lastRead"] = num; changed = true }
        }
        if changed { write("list.json", json(l, pretty: true)) }
    }

    // MARK: ayarlar
    static let defaults = UserDefaults.standard
    static func setting(_ k: String) -> String? { defaults.string(forKey: "s_" + k) }
    static func setSetting(_ k: String, _ v: String) { defaults.set(v, forKey: "s_" + k) }

    static var localStore: [String: String] {
        get { defaults.dictionary(forKey: "ls") as? [String: String] ?? [:] }
        set { defaults.set(newValue, forKey: "ls") }
    }

    static func position(_ series: String) -> String? { defaults.string(forKey: "pos:" + series) }
    static func setPosition(_ series: String, _ v: String) { defaults.set(v, forKey: "pos:" + series) }

    static func allPositions() -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in defaults.dictionaryRepresentation() where k.hasPrefix("pos:") {
            if let s = v as? String { out[String(k.dropFirst(4))] = s }
        }
        return out
    }

    static var lastCheck: String {
        "\(defaults.string(forKey: "lastRun") ?? "")|\(defaults.string(forKey: "lastSummary") ?? "")"
    }
}

/// JavaScript'e güvenli metin olarak gömmek için.
func jsString(_ s: String) -> String {
    let d = (try? JSONSerialization.data(withJSONObject: [s])) ?? Data("[\"\"]".utf8)
    let a = String(data: d, encoding: .utf8) ?? "[\"\"]"
    return String(a.dropFirst().dropLast())
}

func isoNow() -> String {
    let f = ISO8601DateFormatter()
    return f.string(from: Date())
}
