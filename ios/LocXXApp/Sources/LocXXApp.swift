import SwiftUI

@main
struct LocXXApp: App {
    @StateObject private var model = LocXXViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}
