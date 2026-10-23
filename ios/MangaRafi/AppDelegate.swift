import UIKit
import BackgroundTasks
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var window: UIWindow?
    static let refreshId = "com.yildiz.mangarafi.refresh"
    private var main: MainViewController?

    func application(_ app: UIApplication, didFinishLaunchingWithOptions opts: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        let vc = MainViewController()
        main = vc
        w.rootViewController = vc
        w.backgroundColor = UIColor(red: 0x14 / 255.0, green: 0x12 / 255.0, blue: 0x1a / 255.0, alpha: 1)
        w.makeKeyAndVisible()
        window = w

        UNUserNotificationCenter.current().delegate = self
        Notifier.requestAuth()
        ReaderViewController.prepareRules()

        // Arka planda kontrol: iPhone ne zaman çalıştıracağına kendisi karar verir
        BGTaskScheduler.shared.register(forTaskWithIdentifier: AppDelegate.refreshId, using: nil) { task in
            self.handle(task as! BGAppRefreshTask)
        }
        scheduleRefresh()
        return true
    }

    func applicationDidEnterBackground(_ app: UIApplication) { scheduleRefresh() }

    private func scheduleRefresh() {
        let r = BGAppRefreshTaskRequest(identifier: AppDelegate.refreshId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }

    private func handle(_ task: BGAppRefreshTask) {
        scheduleRefresh()
        task.expirationHandler = { CheckRunner.current?.finish("süre doldu") }
        let started = CheckRunner.check(onlyMissing: false) { _ in task.setTaskCompleted(success: true) }
        if !started { task.setTaskCompleted(success: true) }
    }

    // Uygulama açıkken de bildirimi göster
    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound, .list])
    }

    // Bildirime dokununca bölümü aç
    func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let info = r.notification.request.content.userInfo
        if let url = info["url"] as? String {
            let num = (info["num"] as? NSNumber)?.doubleValue ?? -1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.main?.openFromNotification(url: url, num: num) }
        }
        done()
    }
}
