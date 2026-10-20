import SwiftUI

// =============================================================================
// MicDetailView — everything about one mic (spec 6.4). Shared by Tonight,
// Calendar, Map and Manage.
//
// Input: the mic's id, plus an optional date context ("this mic on Thu
// 10/23"). With a date, the screen shows Going / Cancelled / Clear and the
// signup deadline for that date.
//
// Push it either way:
//     NavigationLink { MicDetailView(micID: mic.id, date: today) } label: { … }
//     NavigationLink(value: MicDetailRoute(micID: mic.id, date: today)) { … }
//         …plus `.micDetailDestination()` once on the NavigationStack's root.
// =============================================================================

/// A navigation value for `MicDetailView` (value-based NavigationLink).
struct MicDetailRoute: Hashable {
    let micID: Int
    let date: CalendarDate?

    init(micID: Int, date: CalendarDate? = nil) {
        self.micID = micID
        self.date = date
    }
}

@MainActor
extension View {
    /// Registers `MicDetailRoute` → `MicDetailView`. Apply once to the root
    /// view inside a NavigationStack (not inside a List/ForEach).
    func micDetailDestination() -> some View {
        navigationDestination(for: MicDetailRoute.self) { route in
            MicDetailView(micID: route.micID, date: route.date)
        }
    }
}

@MainActor
struct MicDetailView: View {
    let micID: Int
    let date: CalendarDate?

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    /// The slider's value (0 = not rated).
    @State private var rating: Double = 0
    @State private var isConfirmingDelete = false
    @State private var isWorking = false
    /// Which request is running, for the small inline spinners.
    @State private var isUpdatingPlan = false
    @State private var isSavingRating = false
    @State private var ratingMessage: String?

    init(micID: Int, date: CalendarDate? = nil) {
        self.micID = micID
        self.date = date
    }

    var body: some View {
        Group {
            if let mic = store.micsByID[micID] {
                content(for: mic)
            } else {
                EmptyStateView(
                    title: "Mic not found",
                    message: "It may have been removed. Pull to refresh and try again.",
                    systemImage: "questionmark.circle"
                )
            }
        }
        .navigationTitle(store.micsByID[micID]?.name ?? "Mic")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: date) {
            if let date = date {
                await store.ensurePlansLoaded(for: date)
            }
        }
    }

    // MARK: - Layout

    private func content(for mic: Mic) -> some View {
        List {
            infoSection(mic)
            if let date = date {
                planSection(mic, date: date)
            }
            if mic.urgencyLevel != .normal {
                urgencySection(mic)
            }
            linksSection(mic)
            ratingSection(mic)
            manageSection(mic)
        }
        .listStyle(.insetGrouped)
        .refreshable {
            await store.refreshAll()
        }
        .onChange(of: mic.micRating, initial: true) { _, newValue in
            rating = newValue ?? 0
        }
        .confirmationDialog(
            "Remove \(mic.name) from your database? Your set history is kept.",
            isPresented: $isConfirmingDelete,
            titleVisibility: .visible
        ) {
            Button("Delete mic", role: .destructive) {
                Task { await deleteMic(mic) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: - Sections

    /// Name, venue, address, location, schedule, cost, set length, signup.
    private func infoSection(_ mic: Mic) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: mic.name)
                    .font(.title2.weight(.bold))
                if !mic.venue.isEmpty {
                    Text(verbatim: mic.venue)
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }
                if !mic.isActive {
                    Text("This mic was removed from your list.")
                        .font(.caption)
                        .foregroundStyle(Theme.negative)
                }
            }
            .padding(.vertical, 4)

            if let address = TextRules.trimmedOrNil(mic.address) {
                addressRow(address: address, mic: mic)
            }
            if !mic.locationText.isEmpty {
                Label {
                    Text(verbatim: mic.locationText)
                } icon: {
                    Image(systemName: "map")
                }
            }
            Label {
                Text(verbatim: scheduleLine(for: mic))
            } icon: {
                Image(systemName: "clock")
            }
            HStack {
                Label {
                    Text(verbatim: mic.costText)
                } icon: {
                    Image(systemName: "dollarsign.circle")
                }
                Spacer()
                CostBadge(cost: mic.cost)
            }
            Label {
                Text(verbatim: mic.setLengthText)
            } icon: {
                Image(systemName: "timer")
            }
            if let method = mic.signupMethodKind {
                Label {
                    Text(verbatim: "Signup: \(method.displayName)")
                } icon: {
                    Image(systemName: "square.and.pencil")
                }
            }
            if let signupNotes = TextRules.trimmedOrNil(mic.signupNotes) {
                Label {
                    Text(verbatim: signupNotes)
                } icon: {
                    Image(systemName: "pin")
                }
            }
        }
    }

    @ViewBuilder
    private func addressRow(address: String, mic: Mic) -> some View {
        if let appleMaps = ExternalLinks.appleMapsURL(for: mic) {
            Link(destination: appleMaps) {
                Label {
                    Text(verbatim: address)
                } icon: {
                    Image(systemName: "mappin.and.ellipse")
                }
            }
            .contextMenu {
                Link(destination: appleMaps) {
                    Label("Open in Apple Maps", systemImage: "map")
                }
                if let googleMaps = ExternalLinks.googleMapsURL(address: address, borough: mic.borough) {
                    Link(destination: googleMaps) {
                        Label("Open in Google Maps", systemImage: "globe")
                    }
                }
            }
        } else {
            Label {
                Text(verbatim: address)
            } icon: {
                Image(systemName: "mappin.and.ellipse")
            }
        }
    }

    /// Status banner, signup deadline and Going / Cancelled / Clear.
    private func planSection(_ mic: Mic, date: CalendarDate) -> some View {
        let status = store.planStatus(micID: mic.id, on: date)
        let deadline = mic.signupDeadline(on: date, today: CalendarDate.today())

        return Section {
            if let status = status {
                statusBanner(status)
            }
            if let deadline = deadline {
                Text(verbatim: deadline.text)
                    .font(.subheadline.weight(deadline.isUrgent ? .semibold : .regular))
            }
            HStack(spacing: 10) {
                if status != .going {
                    Button {
                        Task { await changePlan(mic, date: date, to: .going) }
                    } label: {
                        Text("Going")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                if status != .cancelled {
                    Button {
                        Task { await changePlan(mic, date: date, to: .cancelled) }
                    } label: {
                        Text("Cancelled")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                if status != nil {
                    Button {
                        Task { await changePlan(mic, date: date, to: nil) }
                    } label: {
                        Text("Clear")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(.gray)
                }
            }
            .disabled(isWorking)
            if isUpdatingPlan {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Updating your plan…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text(verbatim: "\(date.weekday.name) \(date.mmdd)")
        }
    }

    private func statusBanner(_ status: PlanStatus) -> some View {
        let isGoing = status == .going
        return Label {
            Text(isGoing ? "You're going to this mic!" : "This mic is marked as cancelled")
        } icon: {
            Image(systemName: isGoing ? "checkmark.circle.fill" : "xmark.circle.fill")
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Theme.planColor(status))
        .listRowBackground(Theme.planColor(status).opacity(0.15))
    }

    /// High = warning style, medium = info style (rule 3 note).
    private func urgencySection(_ mic: Mic) -> some View {
        let isHigh = mic.urgencyLevel == .high
        let tint = isHigh ? Theme.urgencyHigh : Theme.neutral
        let prefix = isHigh ? "🔴 HIGH URGENCY — " : "🟡 MEDIUM — "
        let message = mic.urgencyMessage ?? ""

        return Section {
            Label {
                Text(verbatim: prefix + message)
            } icon: {
                Image(systemName: isHigh ? "exclamationmark.triangle.fill" : "info.circle.fill")
            }
            .font(.subheadline.weight(.medium))
            .foregroundStyle(tint)
            .listRowBackground(tint.opacity(0.12))
        }
    }

    /// Notes 💡, Instagram, Sign Up →, venue website.
    @ViewBuilder
    private func linksSection(_ mic: Mic) -> some View {
        let notes = TextRules.trimmedOrNil(mic.notes)
        let rawInstagram = TextRules.trimmedOrNil(mic.instagram)
        let instagramURL = ExternalLinks.instagramURL(for: mic)
        let signupURL = mic.signupLink
        let signupNote = SignupRules.signupNote(from: mic.signupUrl)
        let venueURL = mic.venueLink

        if notes != nil || rawInstagram != nil || signupURL != nil || signupNote != nil || venueURL != nil {
            Section {
                if let notes = notes {
                    Label {
                        Text(verbatim: notes)
                    } icon: {
                        Text(verbatim: "💡")
                    }
                }
                if let instagramURL = instagramURL, let handle = mic.instagramHandle {
                    Link(destination: instagramURL) {
                        Label {
                            Text(verbatim: "@\(handle)")
                        } icon: {
                            Image(systemName: "camera")
                        }
                    }
                } else if let rawInstagram = rawInstagram {
                    Label {
                        Text(verbatim: rawInstagram)
                    } icon: {
                        Image(systemName: "camera")
                    }
                }
                if let signupURL = signupURL {
                    Link(destination: signupURL) {
                        HStack {
                            Text("Sign Up →")
                                .font(.headline)
                            Spacer()
                            Image(systemName: "arrow.up.right.square")
                        }
                        .foregroundStyle(Theme.accent)
                    }
                } else if let signupNote = signupNote {
                    Label {
                        Text(verbatim: signupNote)
                    } icon: {
                        Image(systemName: "note.text")
                    }
                }
                if let venueURL = venueURL {
                    Link(destination: venueURL) {
                        Label("Venue website", systemImage: "safari")
                    }
                }
            }
        }
    }

    /// "Been here N×" and the owner's 0–10 rating slider.
    private func ratingSection(_ mic: Mic) -> some View {
        let visits = store.loggedVisitCount(micID: mic.id)
        let unchanged = abs((mic.micRating ?? 0) - rating) < 0.001

        return Section {
            if visits > 0 {
                Text(verbatim: "✅ Been here \(visits)×")
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("My rating")
                    Spacer()
                    Text(verbatim: ratingText)
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Slider(value: $rating, in: 0...10, step: 0.5)
                    .accessibilityLabel("My rating")
                    .accessibilityValue(ratingText)
                HStack(spacing: 10) {
                    Button {
                        Task { await saveRating(mic) }
                    } label: {
                        Text("Update rating")
                    }
                    .buttonStyle(.bordered)
                    .disabled(isWorking || unchanged)
                    if isSavingRating {
                        ProgressView()
                    }
                }
            }
            .padding(.vertical, 4)
            if let ratingMessage = ratingMessage {
                Text(verbatim: ratingMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("History & rating")
        }
    }

    /// Edit (→ Mic Form) and Delete (with confirmation).
    private func manageSection(_ mic: Mic) -> some View {
        Section {
            NavigationLink(destination: MicFormView(editing: mic)) {
                Label("Edit mic", systemImage: "pencil")
            }
            Button(role: .destructive) {
                isConfirmingDelete = true
            } label: {
                Label("Delete mic", systemImage: "trash")
            }
            .disabled(isWorking || !mic.isActive)
        }
    }

    // MARK: - Text helpers

    private var ratingText: String {
        rating <= 0 ? "Not rated" : String(format: "%.1f/10", rating)
    }

    /// "Mondays at 7:00 PM · 🔄 Biweekly"
    private func scheduleLine(for mic: Mic) -> String {
        var line = mic.scheduleText
        if mic.isBiweekly {
            line += line.isEmpty ? "🔄 Biweekly" : " · 🔄 Biweekly"
        }
        return line
    }

    // MARK: - Actions

    private func changePlan(_ mic: Mic, date: CalendarDate, to status: PlanStatus?) async {
        isWorking = true
        isUpdatingPlan = true
        defer {
            isWorking = false
            isUpdatingPlan = false
        }
        if let status = status {
            await store.setPlanStatus(micID: mic.id, date: date, status: status)
        } else {
            await store.clearPlan(micID: mic.id, date: date)
        }
    }

    private func saveRating(_ mic: Mic) async {
        isWorking = true
        isSavingRating = true
        defer {
            isWorking = false
            isSavingRating = false
        }
        ratingMessage = nil
        let newRating = rating
        if await store.updateMicRating(micID: mic.id, rating: newRating) {
            ratingMessage = newRating <= 0 ? "Rating cleared" : "Rating updated to \(String(format: "%.1f", newRating))/10"
        }
    }

    private func deleteMic(_ mic: Mic) async {
        isWorking = true
        let deleted = await store.deleteMic(micID: mic.id)
        isWorking = false
        if deleted {
            dismiss()
        }
    }
}
