import SwiftUI
import ComposeApp
import FirebaseCore
import FirebaseMessaging
import UserNotifications
import UIKit

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
  private var remoteRegistrationObserver: NSObjectProtocol?

  func application(_ application: UIApplication,
                   didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil) -> Bool {
    FirebaseApp.configure()
    UNUserNotificationCenter.current().delegate = self
    Messaging.messaging().delegate = self
    remoteRegistrationObserver = NotificationCenter.default.addObserver(
      forName: Notification.Name("ChessTreeRegisterForRemoteNotifications"),
      object: nil,
      queue: .main
    ) { _ in
      application.registerForRemoteNotifications()
    }

    return true
  }

  func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
    MainViewControllerKt.PushTokenUpdated(token: fcmToken)
  }

  func userNotificationCenter(_ center: UNUserNotificationCenter,
                              willPresent notification: UNNotification,
                              withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
    completionHandler([.banner, .sound])
  }

  func userNotificationCenter(_ center: UNUserNotificationCenter,
                              didReceive response: UNNotificationResponse,
                              withCompletionHandler completionHandler: @escaping () -> Void) {
    if let deepLink = response.notification.request.content.userInfo["deepLink"] as? String {
      MainViewControllerKt.OpenGameLink(url: deepLink)
    }
    completionHandler()
  }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
