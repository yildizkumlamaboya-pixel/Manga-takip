import UserNotifications

/// Yeni bölüm bildirimleri (telefonun kendi bildirimleri; sessiz saatlerde sessiz).
enum Notifier {
    static func requestAuth() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func quietNow() -> Bool {
        guard Store.setting("quiet_on") == "true" else { return false }
        let from = Int(Store.setting("quiet_from") ?? "0") ?? 0
        let to = Int(Store.setting("quiet_to") ?? "8") ?? 8
        let h = Calendar.current.component(.hour, from: Date())
        return from <= to ? (h >= from && h < to) : (h >= from || h < to)
    }

    static func show(title: String, body: String, url: String, num: Double) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = quietNow() ? nil : .default
        c.userInfo = ["url": url, "num": num]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: url, content: c, trigger: nil))
    }

    static func clearAll() {
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    static func clear(_ url: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [url])
    }
}
