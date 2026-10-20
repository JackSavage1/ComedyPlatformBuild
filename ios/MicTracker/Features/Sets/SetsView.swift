import SwiftUI

// =============================================================================
// SetsView — the My Sets tab (spec 6.6, a port of pages/2_🎤_My_Sets.py).
//
//   [ Log a Set | History ]           segmented control
//
//   Log a Set:  planned sets ("📝 Log: …", ✕ to remove) + the full form
//   History:    summary tiles, filters, every set (tap → Set Detail;
//               tap a planned row → back to Log a Set, prefilled)
//
// The form's contents, the planned row being logged and the History filters
// live HERE (not in the two child views) so switching segments doesn't
// throw away what you typed.
//
// Contract: this view owns its NavigationStack (MainTabView doesn't wrap it).
// =============================================================================
@MainActor
struct SetsView: View {
    @Environment(AppStore.self) private var store

    /// History first only for `-screen sets-history` / `set-detail` (CI screenshots).
    @State private var mode: SetsMode = LaunchOptions.current.opensSetHistory ? .history : .log
    /// What the Log a Set form currently holds.
    @State private var draft = SetDraft(today: CalendarDate.today())
    /// The planned `my_sets` row being filled in (saving UPDATEs it), or nil
    /// for a brand-new set (saving INSERTs).
    @State private var prefillSetID: Int?
    @State private var historyFilter = SetHistoryFilter()
    /// Empty unless `-screen set-detail` pushed Set Detail at launch.
    @State private var path = NavigationPath(LaunchOptions.current.initialRoutes(in: .sets))

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                Picker("Section", selection: $mode) {
                    ForEach(SetsMode.allCases) { option in
                        Text(verbatim: option.title).tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

                switch mode {
                case .log:
                    LogSetView(draft: $draft, prefillSetID: $prefillSetID)
                case .history:
                    SetHistoryView(filter: $historyFilter) { entry in
                        startLogging(entry)
                    }
                }
            }
            .background(Theme.groupedBackground)
            .navigationTitle("My Sets")
            .navigationBarTitleDisplayMode(.inline)
            .launchRouteDestinations()
        }
    }

    /// History → tap a planned row: fill the form from it and switch tabs.
    private func startLogging(_ entry: SetEntry) {
        prefillSetID = entry.id
        draft = SetDraft(entry: entry, today: CalendarDate.today())
        mode = .log
    }
}
