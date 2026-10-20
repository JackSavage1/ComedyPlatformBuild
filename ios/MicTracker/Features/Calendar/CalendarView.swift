import SwiftUI

// =============================================================================
// CalendarView — the Weekly Calendar tab (spec 6.3, port of
// pages/1_📅_Weekly_Calendar.py).
//
//   ◀ This Week ▶  (last week … 3 weeks out, "Today" jumps back)
//   [Mon][Tue]…[Sun]  day strip with the number of (filtered) mics per day
//   Showing X of Y mics                         [Filters ②]
//   The selected day's mics: ✅/❌ plan status, urgency dot, time, name,
//   cost badge, neighborhood, 🔄 biweekly, signup-deadline line.
//   Tap → Mic Detail for that date. Swipe → Going / Cancelled / Clear.
//
// Week navigation, the selected day and the filters are screen state
// (@State); the mics and plans come from the AppStore.
// =============================================================================
@MainActor
struct CalendarView: View {
    @Environment(AppStore.self) private var store

    /// 0 = this week, −1 = last week, up to +3.
    @State private var weekOffset = 0
    /// The day the user tapped (nil = the default for the week).
    @State private var selectedDate: CalendarDate?
    @State private var filters = CalendarFilters()
    @State private var isShowingFilters = false

    var body: some View {
        // Snapshot everything this render needs, so all parts agree.
        let week = CalendarWeek(offset: weekOffset, today: CalendarDate.today())
        let filtered = filteredMics(for: week)
        let selected = week.selection(preferring: selectedDate)

        NavigationStack {
            VStack(spacing: 0) {
                CalendarWeekHeader(
                    week: week,
                    onPrevious: { moveWeek(by: -1) },
                    onNext: { moveWeek(by: 1) }
                )
                CalendarDayStrip(
                    week: week,
                    counts: week.micCounts(filtered),
                    selectedDate: selected
                ) { date in
                    selectedDate = date
                }
                filterBar(shown: filtered.count, total: store.activeMics.count)
                dayList(date: selected, mics: CalendarWeek.mics(filtered, on: selected), today: week.today)
            }
            .background(Theme.groupedBackground)
            .navigationTitle("Calendar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if weekOffset != 0 {
                        Button("Today") {
                            jumpToThisWeek()
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    ManageToolbarButton()
                }
            }
            .sheet(isPresented: $isShowingFilters) {
                CalendarFilterSheet(filters: $filters, mics: store.activeMics)
            }
            .task(id: weekOffset) {
                await loadPlansForShownWeek()
            }
        }
    }

    // MARK: - Pieces

    /// "Showing X of Y mics" and the Filters button with its badge.
    private func filterBar(shown: Int, total: Int) -> some View {
        HStack(spacing: 8) {
            Text(verbatim: "Showing \(shown) of \(total) mics")
                .font(.caption)
                .foregroundStyle(.secondary)
            // A swipe action (Going / Cancelled / Clear) is saving.
            if store.isBusy {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel("Saving")
            }
            Spacer(minLength: 8)
            Button {
                isShowingFilters = true
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: filters.isActive
                          ? "line.3.horizontal.decrease.circle.fill"
                          : "line.3.horizontal.decrease.circle")
                    Text("Filters")
                    if filters.activeCount > 0 {
                        Text(verbatim: "\(filters.activeCount)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Theme.accent))
                    }
                }
                .font(.subheadline.weight(.medium))
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .accessibilityLabel(filterButtonAccessibilityText)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private var filterButtonAccessibilityText: String {
        filters.isActive ? "Filters, \(filters.activeCount) active" : "Filters"
    }

    /// The selected day's mics.
    private func dayList(date: CalendarDate, mics: [Mic], today: CalendarDate) -> some View {
        List {
            Section {
                if !store.hasLoadedOnce {
                    loadingRow
                } else if mics.isEmpty {
                    EmptyStateView(
                        title: "No mics",
                        message: filters.isActive ? "Some mics may be hidden by your filters." : nil,
                        systemImage: "calendar"
                    )
                    .listRowBackground(Color.clear)
                } else {
                    ForEach(mics) { mic in
                        calendarRow(mic, date: date, today: today)
                    }
                }
            } header: {
                Text(verbatim: "\(date.weekday.name), \(date.shortText)")
                    .font(.headline)
                    .foregroundStyle(Color.primary)
                    .textCase(nil)
            }
        }
        .listStyle(.insetGrouped)
        .refreshable {
            await store.refreshAll()
        }
    }

    /// One mic card. Tap → Mic Detail for `date`; swipe for plan actions.
    private func calendarRow(_ mic: Mic, date: CalendarDate, today: CalendarDate) -> some View {
        let status = store.planStatus(micID: mic.id, on: date)
        return NavigationLink {
            MicDetailView(micID: mic.id, date: date)
        } label: {
            MicRowView(
                mic: mic,
                style: .calendar,
                planStatus: status,
                deadline: mic.signupDeadline(on: date, today: today)
            )
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if status != .going {
                Button {
                    changePlan(for: mic, on: date, to: .going)
                } label: {
                    Label("Going", systemImage: "checkmark.circle")
                }
                .tint(Theme.positive)
            }
            if status != .cancelled {
                Button {
                    changePlan(for: mic, on: date, to: .cancelled)
                } label: {
                    Label("Cancelled", systemImage: "xmark.circle")
                }
                .tint(Theme.negative)
            }
            if status != nil {
                Button {
                    changePlan(for: mic, on: date, to: nil)
                } label: {
                    Label("Clear", systemImage: "arrow.uturn.backward.circle")
                }
                .tint(Color.gray)
            }
        }
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

    // MARK: - Data

    /// Active mics that pass the filters (across the whole week).
    private func filteredMics(for week: CalendarWeek) -> [Mic] {
        let visited = CalendarFilters.visitedMicIDs(from: store.loggedVisitCounts)
        let going = store.goingMicIDs(from: week.monday, through: week.sunday)
        return filters.apply(to: store.activeMics, visitedMicIDs: visited, goingMicIDs: going)
    }

    /// The first load already covers last week … 3 weeks out; after that,
    /// re-fetch the shown week's plans whenever the week changes (cheap, and
    /// keeps the ✅/❌ marks current if they were changed on another device).
    private func loadPlansForShownWeek() async {
        guard store.hasLoadedOnce else { return }
        let week = CalendarWeek(offset: weekOffset, today: CalendarDate.today())
        await store.loadPlans(from: week.monday, to: week.sunday)
    }

    // MARK: - Actions

    private func moveWeek(by delta: Int) {
        let range = ScheduleRules.weekOffsetRange
        let newOffset = min(max(weekOffset + delta, range.lowerBound), range.upperBound)
        guard newOffset != weekOffset else { return }
        weekOffset = newOffset
        selectedDate = nil
    }

    private func jumpToThisWeek() {
        weekOffset = 0
        selectedDate = nil
    }

    /// Going / Cancelled (`status`) or Clear (`nil`) for one mic on one date.
    private func changePlan(for mic: Mic, on date: CalendarDate, to status: PlanStatus?) {
        Task {
            if let status = status {
                await store.setPlanStatus(micID: mic.id, date: date, status: status)
            } else {
                await store.clearPlan(micID: mic.id, date: date)
            }
        }
    }
}
