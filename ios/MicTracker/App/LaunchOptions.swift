import Foundation

// =============================================================================
// LaunchOptions — automation hooks for CI screenshots (spec 8.3).
//
//     xcrun simctl launch <udid> com.comedyplatform.mictracker -demo YES -screen stats
//
// Launch arguments of the form `-key value` land in UserDefaults (the
// "argument domain"), so they are read with `UserDefaults.standard`:
//
//   -demo YES        start in demo mode (Tonight tab)
//   -screen <name>   open straight to one screen — see `LaunchScreen`
//
// A normal launch has neither, and then nothing here changes anything.
// The hooks only set the INITIAL state of a few views (which tab, which
// sheet is open, what is pushed); after that the app behaves normally.
// =============================================================================
struct LaunchOptions: Equatable, Sendable {
    /// Start in demo mode.
    let startsInDemo: Bool
    /// The screen to open, if any.
    let screen: LaunchScreen?

    /// - Parameters:
    ///   - demo: the `-demo` flag.
    ///   - screenName: the `-screen` value.
    /// `sign-in` always means "no demo"; every other screen needs demo data,
    /// so it's ignored unless `demo` is on.
    init(demo: Bool, screenName: String?) {
        let screen = screenName.flatMap { LaunchScreen(rawValue: $0.trimmingCharacters(in: .whitespaces).lowercased()) }
        if screen == .signIn {
            self.startsInDemo = false
            self.screen = .signIn
        } else {
            self.startsInDemo = demo
            self.screen = demo ? screen : nil
        }
    }

    /// Reads `-demo` / `-screen` from the launch arguments.
    init(defaults: UserDefaults) {
        self.init(demo: defaults.bool(forKey: "demo"), screenName: defaults.string(forKey: "screen"))
    }

    /// This launch's options (read once).
    static let current = LaunchOptions(defaults: .standard)

    /// No hooks at all — what a normal launch gets.
    static let none = LaunchOptions(demo: false, screenName: nil)

    // MARK: - What each hook site asks

    /// RootView: show the Sign in screen even without Supabase settings.
    var showsSignIn: Bool { screen == .signIn }

    /// Calendar: open the filter sheet.
    var opensCalendarFilters: Bool { screen == .calendarFilters }

    /// Tonight: open the Manage sheet (and maybe push a screen inside it).
    var opensManage: Bool { screen == .manage || screen == .importScrapers || screen == .micForm }

    /// My Sets: start on History instead of Log a Set.
    var opensSetHistory: Bool { screen == .setsHistory || screen == .setDetail }

    /// Stats: scroll so "Set Rating Over Time" is at the top.
    var scrollsToStatsCharts: Bool { screen == .statsCharts }

    /// The screens to push onto a navigation stack at launch.
    func initialRoutes(in stack: LaunchStack) -> [LaunchRoute] {
        switch (stack, screen) {
        case (.tonight, .micDetail?): return [.micDetail]
        case (.sets, .setDetail?): return [.setDetail]
        case (.manage, .importScrapers?): return [.importScrapers]
        case (.manage, .micForm?): return [.micForm]
        default: return []
        }
    }
}

/// Every `-screen` value (spec 8.3), in the order CI screenshots them.
enum LaunchScreen: String, CaseIterable, Sendable {
    case signIn = "sign-in"
    case tonight
    case calendar
    case calendarFilters = "calendar-filters"
    case micDetail = "mic-detail"
    case map
    case setsLog = "sets-log"
    case setsHistory = "sets-history"
    case setDetail = "set-detail"
    case stats
    case statsCharts = "stats-charts"
    case manage
    case importScrapers = "import"
    case micForm = "mic-form"
}

/// The navigation stacks a launch can push onto.
enum LaunchStack: Sendable {
    case tonight
    case sets
    case manage
}

/// A screen pushed at launch. Resolved to a real view by `LaunchRouteView`.
enum LaunchRoute: Hashable, Sendable {
    /// Mic Detail for `LaunchRoute.micDetailID` on its next occurrence.
    case micDetail
    /// Set Detail of the most recent logged set.
    case setDetail
    /// Manage → Import from Scrapers.
    case importScrapers
    /// Manage → Add a Mic.
    case micForm

    /// The demo mic shown by `-screen mic-detail` (spec 8.3).
    static let micDetailID = 4
}
