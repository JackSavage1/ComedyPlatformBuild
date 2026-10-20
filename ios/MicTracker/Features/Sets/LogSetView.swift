import SwiftUI
import UIKit

// =============================================================================
// LogSetView — My Sets → "Log a Set" (spec 6.6).
//
// 1. "📋 Planned Sets — Ready to Log": the rows that "Going" created
//    (oldest first). Tap one to prefill the form — saving then UPDATEs that
//    row instead of adding a new one. ✕ (or swipe) removes it with the
//    `delete_planned_set` RPC, for nights you didn't end up going.
// 2. The form (SetFormSections) and a Save button.
// 3. On success: a haptic, a burst of confetti and
//    "✅ Set logged! Keep grinding! 🎤", and the form resets.
//
// The draft and the prefilled row id are owned by SetsView (bindings here),
// so they survive switching to History and back.
// =============================================================================
@MainActor
struct LogSetView: View {
    @Binding var draft: SetDraft
    @Binding var prefillSetID: Int?

    @Environment(AppStore.self) private var store

    @State private var isSaving = false
    @State private var validationMessage: String?
    @State private var successMessage: String?
    @State private var infoMessage: String?
    @State private var removingIDs: Set<Int> = []
    /// Bumped on every successful save; drives the confetti and the toast.
    @State private var celebrationCount = 0
    @State private var isShowingConfetti = false

    init(draft: Binding<SetDraft>, prefillSetID: Binding<Int?>) {
        self._draft = draft
        self._prefillSetID = prefillSetID
    }

    var body: some View {
        let planned = store.plannedSets

        Form {
            if !planned.isEmpty {
                plannedSection(planned)
            }
            if let infoMessage = infoMessage {
                Section {
                    Label {
                        Text(verbatim: infoMessage)
                    } icon: {
                        Image(systemName: "info.circle")
                    }
                    .font(.subheadline)
                }
            }
            if prefillSetID != nil {
                prefillSection
            }
            SetFormSections(draft: $draft)
            saveSection
        }
        .scrollDismissesKeyboard(.interactively)
        .refreshable {
            await store.refreshAll()
        }
        .disabled(isSaving)
        .loadingOverlay(isSaving, message: "Saving your set…")
        .overlay {
            if isShowingConfetti {
                SetConfettiView()
                    .allowsHitTesting(false)
            }
        }
        .overlay(alignment: .bottom) {
            if let successMessage = successMessage {
                successToast(successMessage)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: successMessage)
        .onChange(of: store.sets) { _, sets in
            // The planned row being filled in was removed somewhere else
            // (e.g. its plan was cleared and the row deleted): forget it, so
            // saving adds a new set instead of updating a missing row.
            if let id = prefillSetID, !sets.contains(where: { $0.id == id }) {
                prefillSetID = nil
            }
        }
        .task(id: celebrationCount) {
            guard celebrationCount > 0 else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if Task.isCancelled { return }
            isShowingConfetti = false
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if Task.isCancelled { return }
            successMessage = nil
        }
    }

    // MARK: - Sections

    /// "📋 Planned Sets — Ready to Log"
    private func plannedSection(_ planned: [SetEntry]) -> some View {
        Section {
            ForEach(planned) { entry in
                let isCurrent = prefillSetID == entry.id
                HStack(spacing: 10) {
                    Button {
                        startPrefill(entry)
                    } label: {
                        Text(verbatim: SetsLogic.plannedRowText(entry, micsByID: store.micsByID))
                            .font(.subheadline.weight(isCurrent ? .semibold : .regular))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.borderless)

                    if removingIDs.contains(entry.id) {
                        ProgressView()
                    } else {
                        Button {
                            Task { await removePlanned(entry) }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title3)
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove planned set")
                    }
                }
                .listRowBackground(isCurrent ? Theme.accent.opacity(0.12) : nil)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button {
                        Task { await removePlanned(entry) }
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                    .tint(Theme.negative)
                }
            }
        } header: {
            Text(verbatim: "📋 Planned Sets — Ready to Log")
                .textCase(nil)
        } footer: {
            Text("You marked these as \"Going\" on the calendar. Tap one to fill in your set details, or ✕ to remove it if you didn't go.")
        }
    }

    /// Shown while a planned row is loaded into the form.
    private var prefillSection: some View {
        Section {
            Label {
                Text("Filling in details for your planned set.")
            } icon: {
                Image(systemName: "pencil.circle.fill")
                    .foregroundStyle(Theme.neutral)
            }
            Button("Log a different set instead") {
                clearPrefill()
            }
        }
    }

    private var saveSection: some View {
        Section {
            if let validationMessage = validationMessage {
                Text(verbatim: validationMessage)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.negative)
            }
            Button {
                Task { await save() }
            } label: {
                Text(verbatim: "💾 Save Set")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .disabled(isSaving)
            if let successMessage = successMessage {
                Text(verbatim: successMessage)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.positive)
            }
        }
    }

    private func successToast(_ message: String) -> some View {
        Text(verbatim: message)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(Theme.positive))
            .shadow(color: .black.opacity(0.2), radius: 6, y: 3)
            .padding(.bottom, 16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    // MARK: - Actions

    /// Loads a planned row into the form (its date clamped to today).
    private func startPrefill(_ entry: SetEntry) {
        validationMessage = nil
        infoMessage = nil
        prefillSetID = entry.id
        draft = SetDraft(entry: entry, today: CalendarDate.today())
    }

    /// "Log a different set instead": back to a blank form.
    private func clearPrefill() {
        prefillSetID = nil
        validationMessage = nil
        draft = SetDraft(today: CalendarDate.today())
    }

    @MainActor
    private func save() async {
        validationMessage = nil
        infoMessage = nil
        guard let micID = draft.micID else {
            validationMessage = "Please select a mic!"
            return
        }

        isSaving = true
        let fields = draft.makeFields(micID: micID)
        let saved: Bool
        if let setID = prefillSetID {
            saved = await store.updateSet(id: setID, fields: fields)
        } else {
            saved = await store.logSet(fields)
        }
        isSaving = false

        // On failure AppStore already shows the server's message in the
        // error banner; keep the form as-is so nothing is lost.
        guard saved else { return }

        prefillSetID = nil
        draft = SetDraft(today: CalendarDate.today())
        successMessage = "✅ Set logged! Keep grinding! 🎤"
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        isShowingConfetti = true
        celebrationCount += 1
    }

    /// ✕ / swipe: RPC `delete_planned_set` (it refuses rows that were logged).
    @MainActor
    private func removePlanned(_ entry: SetEntry) async {
        guard !removingIDs.contains(entry.id) else { return }
        infoMessage = nil
        removingIDs.insert(entry.id)
        let deleted = await store.deletePlannedSet(id: entry.id)
        removingIDs.remove(entry.id)

        if deleted {
            if prefillSetID == entry.id {
                clearPrefill()
            }
        } else if store.errorMessage == nil {
            infoMessage = "That set has already been logged, so it wasn't removed."
        }
    }
}
