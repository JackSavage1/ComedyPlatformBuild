import SwiftUI
import UIKit

// =============================================================================
// SetDetailView — everything about one logged set (spec 6.6 → Set Detail):
// date, mic, ratings, crowd size, flags, set list (monospaced), notes,
// feedback, the recording link ("▶️ Watch/Listen (video)") and tags.
//
// New on mobile: **Edit** (the Log form, prefilled; saving PATCHes the row)
// and **Delete** (with confirmation; `DELETE my_sets?id=eq.X`).
//
// The set is looked up by id in the store on every render, so after an
// edit the screen shows the saved values straight away.
// =============================================================================
@MainActor
struct SetDetailView: View {
    let setID: Int

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var isConfirmingDelete = false
    @State private var isDeleting = false

    init(setID: Int) {
        self.setID = setID
    }

    var body: some View {
        Group {
            if let entry = store.sets.first(where: { $0.id == setID }) {
                content(entry)
            } else {
                EmptyStateView(
                    title: "Set not found",
                    message: "It may have been deleted. Pull to refresh on History and try again.",
                    systemImage: "questionmark.circle"
                )
            }
        }
        .navigationTitle("Set Details")
        .navigationBarTitleDisplayMode(.inline)
        .loadingOverlay(isDeleting, message: "Deleting…")
    }

    // MARK: - Layout

    private func content(_ entry: SetEntry) -> some View {
        let mic = store.mic(for: entry)

        return List {
            headerSection(entry, mic: mic)
            ratingsSection(entry)
            textSections(entry)
            recordingSection(entry)
            if !entry.tagList.isEmpty {
                Section {
                    TagChips(tags: entry.tagList)
                        .padding(.vertical, 4)
                } header: {
                    Text("Tags")
                }
            }
            actionsSection(entry)
        }
        .listStyle(.insetGrouped)
        .refreshable {
            await store.refreshAll()
        }
        .confirmationDialog(
            "Delete this set from \(entry.datePerformed.mmddyyyy)? This can't be undone.",
            isPresented: $isConfirmingDelete,
            titleVisibility: .visible
        ) {
            Button("Delete set", role: .destructive) {
                Task { await delete() }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: - Sections

    /// "🔥 Tuesday, 10/20/2026" and the mic (tap → Mic Detail).
    private func headerSection(_ entry: SetEntry, mic: Mic?) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: "\(entry.ratingEmoji) \(entry.datePerformed.weekday.name), \(entry.datePerformed.mmddyyyy)")
                    .font(.title3.weight(.bold))
                if entry.isPlanned {
                    Text("PLANNED — not logged yet. Use Edit to fill in your ratings and notes.")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                }
            }
            .padding(.vertical, 4)

            if let mic = mic {
                NavigationLink {
                    MicDetailView(micID: mic.id, date: nil)
                } label: {
                    Label {
                        Text(verbatim: mic.venue.isEmpty ? mic.name : "\(mic.name) @ \(mic.venue)")
                    } icon: {
                        Text(verbatim: "🎤")
                    }
                }
            } else {
                Label {
                    Text("Unknown mic")
                        .foregroundStyle(.secondary)
                } icon: {
                    Text(verbatim: "🎤")
                }
            }
        }
    }

    private func ratingsSection(_ entry: SetEntry) -> some View {
        let setText: String = entry.setRating.map { rating in "\(rating)/10" } ?? "—"
        let crowdText: String = entry.crowdRating.map { rating in "\(rating)/10" } ?? "?"
        let crowdSizeText: String = entry.crowdSizeKind?.displayName ?? "N/A"

        return Section {
            LabeledContent("📊 Set rating", value: setText)
            LabeledContent("👥 Crowd rating", value: crowdText)
            LabeledContent("👥 Crowd size", value: crowdSizeText)
            if entry.newMaterial {
                Text(verbatim: "🆕 New material")
            }
            if let wouldReturn = entry.wouldReturn {
                Text(verbatim: wouldReturn ? "✅ Would return" : "❌ Would NOT return")
            }
        } header: {
            Text("How it went")
        }
    }

    /// Set list (monospaced), notes and feedback — each only when present.
    @ViewBuilder
    private func textSections(_ entry: SetEntry) -> some View {
        if let setList = TextRules.trimmedOrNil(entry.setList) {
            Section {
                Text(verbatim: setList)
                    .font(.system(.body, design: .monospaced))
                    .textSelection(.enabled)
            } header: {
                Text("Set list")
            }
        }
        if let notes = TextRules.trimmedOrNil(entry.notes) {
            Section {
                Text(verbatim: notes)
                    .textSelection(.enabled)
            } header: {
                Text("Notes")
            }
        }
        if entry.gotFeedback {
            Section {
                Text(verbatim: "💬 " + (TextRules.trimmedOrNil(entry.feedbackNotes) ?? "Got feedback (no notes)"))
                    .textSelection(.enabled)
            } header: {
                Text("Feedback")
            }
        }
    }

    /// "▶️ Watch/Listen (video)" when there's a usable link; the raw text
    /// otherwise.
    @ViewBuilder
    private func recordingSection(_ entry: SetEntry) -> some View {
        if let raw = TextRules.trimmedOrNil(entry.recordingUrl) {
            Section {
                if let url = ExternalLinks.recordingURL(for: entry) {
                    Link(destination: url) {
                        Label {
                            Text(verbatim: "▶️ Watch/Listen (\(recordingTypeText(entry)))")
                        } icon: {
                            Image(systemName: "play.circle")
                        }
                    }
                } else {
                    Text(verbatim: raw)
                        .textSelection(.enabled)
                }
            } header: {
                Text("Recording")
            }
        }
    }

    private func actionsSection(_ entry: SetEntry) -> some View {
        Section {
            NavigationLink {
                SetEditView(entry: entry)
            } label: {
                Label {
                    Text(verbatim: entry.isPlanned ? "Log this set" : "Edit set")
                } icon: {
                    Image(systemName: "pencil")
                }
            }
            Button(role: .destructive) {
                isConfirmingDelete = true
            } label: {
                Label("Delete set", systemImage: "trash")
            }
            .disabled(isDeleting)
        }
    }

    // MARK: - Helpers

    /// The stored recording type ("video"), like the web app's button.
    private func recordingTypeText(_ entry: SetEntry) -> String {
        entry.recordingKind?.rawValue ?? "recording"
    }

    @MainActor
    private func delete() async {
        isDeleting = true
        let deleted = await store.deleteSet(id: setID)
        isDeleting = false
        if deleted {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
        }
    }
}

// =============================================================================
// SetEditView — Set Detail → Edit: the same fields as "Log a Set",
// prefilled from the row; saving PATCHes it (explicit nulls for anything
// cleared) and goes back to Set Detail.
// =============================================================================
@MainActor
struct SetEditView: View {
    let setID: Int

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var draft: SetDraft
    @State private var isSaving = false
    @State private var validationMessage: String?

    init(entry: SetEntry) {
        self.setID = entry.id
        _draft = State(initialValue: SetDraft(entry: entry, today: CalendarDate.today()))
    }

    var body: some View {
        Form {
            if let validationMessage = validationMessage {
                Section {
                    Text(verbatim: validationMessage)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.negative)
                }
            }
            SetFormSections(draft: $draft)
            Section {
                Button {
                    Task { await save() }
                } label: {
                    Text(verbatim: "💾 Save Changes")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .disabled(isSaving)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Edit Set")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    Task { await save() }
                }
                .disabled(isSaving)
            }
        }
        .disabled(isSaving)
        .loadingOverlay(isSaving, message: "Saving…")
    }

    @MainActor
    private func save() async {
        validationMessage = nil
        guard let micID = draft.micID else {
            validationMessage = "Please select a mic!"
            return
        }
        isSaving = true
        let saved = await store.updateSet(id: setID, fields: draft.makeFields(micID: micID))
        isSaving = false
        if saved {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
        }
    }
}
