import SwiftUI

// =============================================================================
// MainTabView — the five tabs, in spec order (spec 5):
//     Tonight · Calendar · Map · My Sets · Stats
//
// Each tab's root view owns its own NavigationStack (this view does NOT wrap
// them). Tonight and Calendar add the gear button with ManageToolbarButton.
//
// This view also:
//   * loads all data when the tabs first appear (and again when the app
//     comes back to the foreground after a while),
//   * shows AppStore.errorMessage as a dismissible banner over every tab,
//   * shows a spinner during the very first load.
// =============================================================================

/// The tabs, in order.
enum MainTab: Hashable {
    case tonight
    case calendar
    case map
    case sets
    case stats

    /// The tab a `-screen …` launch starts on (Tonight for everything else).
    init(launch screen: LaunchScreen?) {
        switch screen {
        case .calendar?, .calendarFilters?: self = .calendar
        case .map?: self = .map
        case .setsLog?, .setsHistory?, .setDetail?: self = .sets
        case .stats?, .statsCharts?: self = .stats
        default: self = .tonight
        }
    }
}

@MainActor
struct MainTabView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase
    @State private var selection = MainTab(launch: LaunchOptions.current.screen)

    var body: some View {
        TabView(selection: $selection) {
            TonightView()
                .tabItem { Label("Tonight", systemImage: "house") }
                .tag(MainTab.tonight)

            CalendarView()
                .tabItem { Label("Calendar", systemImage: "calendar") }
                .tag(MainTab.calendar)

            MicMapView()
                .tabItem { Label("Map", systemImage: "map") }
                .tag(MainTab.map)

            SetsView()
                .tabItem { Label("My Sets", systemImage: "music.mic") }
                .tag(MainTab.sets)

            StatsView()
                .tabItem { Label("Stats", systemImage: "chart.bar") }
                .tag(MainTab.stats)
        }
        .storeErrorBanner()
        .loadingOverlay(store.isLoading && !store.hasLoadedOnce, message: "Loading mics…")
        .task {
            await store.refreshAll()
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            // Coming back after 5+ minutes: refresh so "tonight" and plans
            // are current (the date may even have changed).
            let isStale = store.lastRefreshed.map { Date().timeIntervalSince($0) > 300 } ?? true
            if isStale && !store.isLoading {
                Task { await store.refreshAll() }
            }
        }
    }
}
