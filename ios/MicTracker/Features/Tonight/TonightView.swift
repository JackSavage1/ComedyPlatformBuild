import SwiftUI

// =============================================================================
// TonightView — the home tab (spec 6.2, a port of the Streamlit app.py page).
//
//   📆 Monday, October 20, 2026
//   🌙 Tonight's Mics — Monday         tap a mic → Mic Detail for TODAY
//   📅 Coming Up Tomorrow — Tuesday    tap a mic → Mic Detail for TOMORROW
//   Quick Stats: Total Active Mics · Neighborhoods · Most Mics In
//
// All data comes from the shared AppStore (MainTabView loads it on launch);
// pull down to refresh. The gear in the top bar opens Manage.
// =============================================================================
@MainActor
struct TonightView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        // Read "today" once per render so every section agrees on the date.
        let today = CalendarDate.today()

        NavigationStack {
            List {
                dateHeader(today)
                tonightSection(today)
                tomorrowSection(today.adding(days: 1))
                quickStatsSection
            }
            .listStyle(.insetGrouped)
            .refreshable {
                await store.refreshAll()
            }
            .navigationTitle("Tonight")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    ManageToolbarButton()
                }
            }
        }
    }

    // MARK: - Sections

    /// "📆 Monday, October 20, 2026"
    private func dateHeader(_ today: CalendarDate) -> some View {
        Section {
            Text(verbatim: "📆 \(today.longText)")
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
        }
    }

    /// Tonight's mics as full rows (dot · time · name · @ venue · cost).
    @ViewBuilder
    private func tonightSection(_ today: CalendarDate) -> some View {
        let dayName = today.weekday.name
        let mics = store.activeMicsOnDay(today.weekday)

        Section {
            if !store.hasLoadedOnce {
                loadingRow
            } else if mics.isEmpty {
                Text(verbatim: "No open mics listed for \(dayName). Rest day! 😴")
                    .foregroundStyle(.secondary)
            } else {
                Text(verbatim: "\(mics.count) mics tonight")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                ForEach(mics) { mic in
                    NavigationLink {
                        MicDetailView(micID: mic.id, date: today)
                    } label: {
                        MicRowView(mic: mic, style: .standard)
                    }
                }
            }
        } header: {
            sectionHeader("🌙 Tonight's Mics — \(dayName)")
        }
    }

    /// Tomorrow's mics as compact one-liners.
    @ViewBuilder
    private func tomorrowSection(_ tomorrow: CalendarDate) -> some View {
        let dayName = tomorrow.weekday.name
        let mics = store.activeMicsOnDay(tomorrow.weekday)

        Section {
            if !store.hasLoadedOnce {
                loadingRow
            } else if mics.isEmpty {
                Text(verbatim: "No open mics listed for \(dayName).")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(mics) { mic in
                    NavigationLink {
                        MicDetailView(micID: mic.id, date: tomorrow)
                    } label: {
                        MicRowView(mic: mic, style: .compact)
                    }
                }
            }
        } header: {
            sectionHeader("📅 Coming Up Tomorrow — \(dayName)")
        }
    }

    /// Total Active Mics · Neighborhoods · Most Mics In.
    private var quickStatsSection: some View {
        let mics = store.activeMics
        return Section {
            HStack(alignment: .top, spacing: 12) {
                QuickStatTile(title: "Total Active Mics", value: "\(mics.count)")
                QuickStatTile(title: "Neighborhoods", value: "\(TonightQuickStats.neighborhoodCount(mics))")
                QuickStatTile(title: "Most Mics In", value: TonightQuickStats.topBoroughText(mics))
            }
            .padding(.vertical, 4)
        } header: {
            sectionHeader("Quick Stats")
        }
    }

    // MARK: - Small pieces

    /// Section headers keep their own capitalization (grouped lists would
    /// otherwise upper-case them).
    private func sectionHeader(_ text: String) -> some View {
        Text(verbatim: text)
            .font(.headline)
            .foregroundStyle(Color.primary)
            .textCase(nil)
    }

    /// Shown until the first load finishes (or if it failed).
    @ViewBuilder
    private var loadingRow: some View {
        if store.isLoading {
            HStack(spacing: 10) {
                ProgressView()
                Text("Loading mics…")
                    .foregroundStyle(.secondary)
            }
        } else {
            Text("Couldn't load mics yet. Pull down to try again.")
                .foregroundStyle(.secondary)
        }
    }
}
