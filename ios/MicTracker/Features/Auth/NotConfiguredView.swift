import SwiftUI

/// Shown when the build has no Supabase URL / key (spec 2): explains the
/// Secrets.xcconfig file to create. CI builds always land here. "Try the
/// demo" (spec 8.1) opens the app with sample data instead.
@MainActor
struct NotConfiguredView: View {
    let config: AppConfig

    init(config: AppConfig) {
        self.config = config
    }

    private let template = """
    // ios/Config/Secrets.xcconfig  (never commit this file)
    SUPABASE_URL = https:/$()/YOUR-PROJECT-REF.supabase.co
    SUPABASE_ANON_KEY = YOUR-ANON-OR-PUBLISHABLE-KEY
    """

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: "🎤 Mic Tracker")
                        .font(.largeTitle.weight(.bold))
                    Text("Not configured")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                }

                Text("This build doesn't know which Supabase project to talk to. Create the secrets file, then build and run again.")
                    .font(.body)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Just looking around? The demo uses sample data and needs no setup.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    TryDemoButton()
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("1. Copy ios/Config/Secrets.example.xcconfig to ios/Config/Secrets.xcconfig")
                    Text("2. Fill in your project's URL and anon key (Supabase dashboard → Project Settings → API).")
                    Text("3. Keep the $() in the URL — in .xcconfig files // starts a comment.")
                    Text("4. Run `xcodegen generate` in ios/ and rebuild.")
                }
                .font(.subheadline)

                Text(verbatim: template)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.cardBackground, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                if let hint = config.urlProblemHint {
                    Label {
                        Text(verbatim: hint)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill")
                    }
                    .font(.footnote)
                    .foregroundStyle(Theme.warning)
                }
                if let hint = config.anonKeyProblemHint {
                    Label {
                        Text(verbatim: hint)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill")
                    }
                    .font(.footnote)
                    .foregroundStyle(Theme.warning)
                }

                Text("Full instructions: docs/MOBILE_SETUP.md")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(24)
        }
    }
}

/// "Try the demo" (spec 8.1): a full-width secondary button that switches
/// the app to the bundled sample data. Used by Sign in and Not configured.
@MainActor
struct TryDemoButton: View {
    @Environment(AppStore.self) private var store

    init() {}

    var body: some View {
        Button {
            store.enterDemo()
        } label: {
            Label("Try the demo", systemImage: "play.circle")
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
        .buttonStyle(.bordered)
    }
}
