import SwiftUI
import shared

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    lazy var log = koin.loggerWithTag(tag: "AppDelegate")

    func application(_ application: UIApplication, didFinishLaunchingWithOptions
        launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        startKoin()

        let viewController = UIHostingController(rootView: ContentView())

        self.window = UIWindow(frame: UIScreen.main.bounds)
        self.window?.rootViewController = viewController
        self.window?.makeKeyAndVisible()

        log.v(message: {"App Started"})
        return true
    }
}

struct ContentView: View {
    var body: some View {
        ThumbRatingScreen()
    }
}
