import SwiftUI

// =============================================================================
// MicTrackerApp — the entry point (like app.py for the Streamlit version).
//
// It creates the one AppStore for the whole app and hands it to every screen
// through the SwiftUI environment. Screens read it with
//     @Environment(AppStore.self) private var store
// =============================================================================
@main
@MainActor
struct MicTrackerApp: App {
    @State private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .tint(Theme.accent)
        }
    }
}
