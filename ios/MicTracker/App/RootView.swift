import SwiftUI

// =============================================================================
// RootView — the gate in front of the tabs (spec 5):
//     Not configured → Sign in → Not authorized → MainTabView
//
// Demo mode ("Try the demo", spec 8) skips the gate and goes straight to
// the tabs; "Exit demo" brings the gate back.
// =============================================================================
@MainActor
struct RootView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        Group {
            if store.isDemoMode {
                MainTabView()
            } else if forcesSignIn {
                SignInView()
            } else {
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
        }
        .animation(.default, value: store.auth.state)
        .animation(.default, value: store.isDemoMode)
        .task {
            await store.bootstrap()
        }
    }

    /// `-screen sign-in` (CI screenshots): show Sign in even when the build
    /// has no Supabase settings and would otherwise say "Not configured".
    private var forcesSignIn: Bool {
        guard LaunchOptions.current.showsSignIn else { return false }
        switch store.auth.state {
        case .notConfigured, .restoring, .signedOut:
            return true
        case .notAuthorized, .accessCheckFailed, .ready:
            return false
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
