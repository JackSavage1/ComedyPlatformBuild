import SwiftUI

// =============================================================================
// SetHistoryView — My Sets → "History" (spec 6.6).
//
//   Summary:  Total Sets · Avg Set Rating · Most Visited · Week Streak
//   Filters:  mic · minimum set rating · tags (any of)
//   "Showing N sets", then every set, newest first:
//       🔥 10/20/2026 — Comedy Night @ The Creek
//       Set: 8/10 · Crowd: 6/10                    [tag pills]
//
// Tap a logged set → Set Detail. Tap a planned row → `onLogPlanned`, which
// switches to "Log a Set" with that row prefilled.
// =============================================================================
@MainActor
struct SetHistoryView: View {
    @Binding var filter: SetHistoryFilter
    let onLogPlanned: (SetEntry) -> Void

    @Environment(AppStore.self) private var store

    init(filter: Binding<SetHistoryFilter>, onLogPlanned: @escaping (SetEntry) -> Void) {
        self._filter = filter
        self.onLogPlanned = onLogPlanned
    }

    var body: some View {
        let sets = store.sets
        let micsByID = store.micsByID

        List {
            if sets.isEmpty {
                if !store.hasLoadedOnce {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                    .listRowBackground(Color.clear)
                } else {
                    emptyState
                }
            } else {
                summarySection(SetsLogic.historySummary(sets: sets, micsByID: micsByID, today: CalendarDate.today()))
                filtersSection(sets: sets, micsByID: micsByID)
                resultsSection(SetsLogic.filteredHistory(sets: sets, micsByID: micsByID, filter: filter), micsByID: micsByID)
            }
        }
        .listStyle(.insetGrouped)
        .refreshable {
            await store.refreshAll()
        }
    }

    // MARK: - Sections

    private var emptyState: some View {
        EmptyStateView(
            title: "No sets logged yet!",
            message: "Hit a mic tonight and come back to log it. Your first data point is always the hardest. 🎤",
            systemImage: "music.mic"
        )
        .listRowBackground(Color.clear)
    }

    /// The four tiles at the top.
    private func summarySection(_ summary: SetHistorySummary) -> some View {
        Section {
            Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                GridRow {
                    StatsMetricTile(title: "Total Sets", value: summary.totalText)
                    StatsMetricTile(title: "Avg Set Rating", value: summary.averageText)
                }
                GridRow {
                    StatsMetricTile(title: "Most Visited", value: summary.mostVisitedText)
                    StatsMetricTile(title: "Week Streak", value: summary.streakText)
                }
            }
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
        }
    }

    private func filtersSection(sets: [SetEntry], micsByID: [Int: Mic]) -> some View {
        let micNames = SetsLogic.historyMicNames(sets: sets, micsByID: micsByID)
        let tagOptions = SetsLogic.historyTagOptions(sets: sets)

        return Section {
            Picker("Mic", selection: $filter.micName) {
                Text("All").tag("")
                ForEach(micNames, id: \.self) { name in
                    Text(verbatim: name).tag(name)
                }
            }
            Stepper(value: $filter.minRating, in: 1...10) {
                HStack {
                    Text("Min set rating")
                    Spacer()
                    Text(verbatim: "\(filter.minRating)/10")
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            if !tagOptions.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Tags (any of)")
                        .font(.subheadline)
                    TagSelector(options: tagOptions, selection: $filter.tags)
                }
                .padding(.vertical, 4)
            }
            if filter.activeCount > 0 {
                Button("Reset filters") {
                    filter = SetHistoryFilter()
                }
            }
        } header: {
            Text("Filters")
        } footer: {
            Text("Planned sets are always shown — they don't have a rating yet.")
        }
    }

    private func resultsSection(_ shown: [SetEntry], micsByID: [Int: Mic]) -> some View {
        Section {
            if shown.isEmpty {
                Text("No sets match these filters.")
                    .foregroundStyle(.secondary)
            }
            ForEach(shown) { entry in
                if entry.isPlanned {
                    Button {
                        onLogPlanned(entry)
                    } label: {
                        SetHistoryRow(entry: entry, micAndVenue: SetsLogic.micAndVenue(for: entry, micsByID: micsByID))
                    }
                } else {
                    NavigationLink {
                        SetDetailView(setID: entry.id)
                    } label: {
                        SetHistoryRow(entry: entry, micAndVenue: SetsLogic.micAndVenue(for: entry, micsByID: micsByID))
                    }
                }
            }
        } header: {
            Text(verbatim: "Showing \(shown.count) sets")
                .textCase(nil)
        }
    }
}

// =============================================================================
// SetHistoryRow — one set in History:
//     🔥 10/20/2026 — Comedy Night @ The Creek
//     Set: 8/10 · Crowd: 6/10
//     [killed] [crowd_work]
// =============================================================================
@MainActor
struct SetHistoryRow: View {
    let entry: SetEntry
    let micAndVenue: String

    init(entry: SetEntry, micAndVenue: String) {
        self.entry = entry
        self.micAndVenue = micAndVenue
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            (Text(verbatim: "\(entry.ratingEmoji) ")
                + Text(verbatim: entry.datePerformed.mmddyyyy).bold()
                + Text(verbatim: " — \(micAndVenue)"))
                .font(.subheadline)
                .foregroundStyle(Color.primary)
                .multilineTextAlignment(.leading)
            Text(verbatim: SetsLogic.historyResultText(entry))
                .font(.caption.weight(entry.isPlanned ? .semibold : .regular))
                .foregroundStyle(entry.isPlanned ? Theme.accent : Color.secondary)
            let tags = entry.tagList
            if !tags.isEmpty {
                TagChips(tags: tags)
                    .padding(.top, 2)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
