import Foundation

// =============================================================================
// AppConfig — the two Supabase settings (spec section 2).
//
// They come from ios/Config/Secrets.xcconfig (never committed) → build
// settings SUPABASE_URL / SUPABASE_ANON_KEY → Info.plist keys SupabaseURL /
// SupabaseAnonKey → here. When they're missing (CI has none) both are empty
// and RootView shows the "Not configured" screen instead of crashing.
// =============================================================================
struct AppConfig: Equatable, Sendable {
    /// e.g. https://abcd1234.supabase.co (no trailing slash). nil when missing
    /// or unusable.
    let supabaseURL: URL?
    /// The project's anon / publishable key ("" when missing).
    let anonKey: String
    /// What Info.plist actually contained, for the Not Configured screen.
    let rawURLText: String

    init(urlString: String?, anonKey: String?) {
        let rawURL = AppConfig.clean(urlString)
        self.rawURLText = rawURL
        self.supabaseURL = AppConfig.normalizedURL(from: rawURL)
        self.anonKey = AppConfig.clean(anonKey)
    }

    /// Both values present and the URL looks like a URL.
    var isConfigured: Bool { supabaseURL != nil && !anonKey.isEmpty }

    /// Legacy anon keys are JWTs ("eyJ…") and may also be sent as a Bearer
    /// token. The newer "sb_publishable_…" keys must only go in `apikey`.
    var anonKeyIsJWT: Bool { anonKey.hasPrefix("eyJ") }

    /// A human hint for the most common mistake: `https://…` in an xcconfig
    /// is cut at the `//` (treated as a comment) and arrives as "https:".
    var urlProblemHint: String? {
        if rawURLText.isEmpty { return "SUPABASE_URL is empty." }
        if supabaseURL == nil {
            if rawURLText.hasSuffix(":") || rawURLText.hasSuffix(":/") {
                return "SUPABASE_URL arrived as \"\(rawURLText)\". In .xcconfig files // starts a comment — write the URL as https:/$()/your-ref.supabase.co"
            }
            return "SUPABASE_URL \"\(rawURLText)\" isn't a valid https URL."
        }
        return nil
    }

    var anonKeyProblemHint: String? {
        anonKey.isEmpty ? "SUPABASE_ANON_KEY is empty." : nil
    }

    /// Reads the values the build put into Info.plist.
    static func fromBundle(_ bundle: Bundle = .main) -> AppConfig {
        AppConfig(
            urlString: bundle.object(forInfoDictionaryKey: "SupabaseURL") as? String,
            anonKey: bundle.object(forInfoDictionaryKey: "SupabaseAnonKey") as? String
        )
    }

    /// An unconfigured instance (handy for previews and tests).
    static let empty = AppConfig(urlString: nil, anonKey: nil)

    // MARK: - Helpers

    /// Trims whitespace and stray quotes, and treats an unexpanded "$(VAR)"
    /// as empty.
    private static func clean(_ value: String?) -> String {
        var text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        text = text.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        if text.hasPrefix("$(") { return "" }
        return text
    }

    /// Accepts "https://ref.supabase.co" or "https://ref.supabase.co/" and
    /// returns it without the trailing slash. nil if it isn't an http(s) URL
    /// with a host.
    static func normalizedURL(from raw: String) -> URL? {
        var text = raw
        while text.hasSuffix("/") { text.removeLast() }
        guard !text.isEmpty,
              let url = URL(string: text),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              let host = components.host, !host.isEmpty
        else { return nil }
        return url
    }
}
