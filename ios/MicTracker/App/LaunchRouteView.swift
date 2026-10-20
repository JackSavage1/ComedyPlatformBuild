import SwiftUI

// =============================================================================
// LaunchRouteView — the screens `-screen …` pushes at launch (LaunchOptions).
//
// Tonight, My Sets and the Manage sheet start their NavigationStack with the
// routes from `LaunchOptions.current.initialRoutes(in:)` (empty on a normal
// launch) and register `.launchRouteDestinations()` to build them.
// =============================================================================
@MainActor
struct LaunchRouteView: View {
    let route: LaunchRoute

    @Environment(AppStore.self) private var store

    init(route: LaunchRoute) {
        self.route = route
    }

    var body: some View {
        switch route {
        case .micDetail:
            // Wait for the first load, so the screen gets its date context
            // (the mic's next occurrence, rule 16) from the start.
            if store.hasLoadedOnce {
                let micID = LaunchRoute.micDetailID
                let nextDate = store.mic(id: micID)?.weekday.map {
                    ScheduleRules.nextOccurrence(of: $0, from: CalendarDate.today())
                }
                MicDetailView(micID: micID, date: nextDate)
            } else {
                ProgressView()
            }
        case .setDetail:
            // The newest logged set (sets are sorted newest first).
            if let newest = store.loggedSets.first {
                SetDetailView(setID: newest.id)
            } else {
                ProgressView()
            }
        case .importScrapers:
            ManageImportView()
        case .micForm:
            MicFormView()
        }
    }
}

@MainActor
extension View {
    /// Builds `LaunchRoute` values pushed onto this NavigationStack. Apply
    /// once to the stack's root view.
    func launchRouteDestinations() -> some View {
        navigationDestination(for: LaunchRoute.self) { route in
            LaunchRouteView(route: route)
        }
    }
}
