import SwiftUI

// =============================================================================
// RootView — the gate in front of the tabs (spec 5):
//     Not configured → Sign in → Not authorized → MainTabView
// =============================================================================
@MainActor
struct RootView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        Group {
            switch store.auth.state {
            case .notConfigured:
                NotConfiguredView(config: store.config)
            case .restoring:
                LaunchView()
            case .signedOut:
                SignInView()
            case .notAuthorized(let email):
                NotAuthorizedView(email: email)
            case .accessCheckFailed(let email, let message):
                NotAuthorizedView(email: email, failureMessage: message)
            case .ready:
                MainTabView()
            }
        }
        .animation(.default, value: store.auth.state)
        .task {
            await store.bootstrap()
        }
    }
}

/// Shown for a moment at launch while the saved session is checked.
@MainActor
private struct LaunchView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text(verbatim: "🎤")
                .font(.system(size: 64))
            ProgressView()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
