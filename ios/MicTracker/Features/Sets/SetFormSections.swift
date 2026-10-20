import SwiftUI

// =============================================================================
// SetFormSections — the fields of a set, as Form sections. Shared by
// "Log a Set" (LogSetView) and Set Detail → Edit (SetEditView), so both
// look and behave the same.
//
//   Which mic? · When? · How'd it go? · Your material · Recording ·
//   Reflections · Tags
//
// Put it inside a `Form { … }`; it edits a `SetDraft` through a binding.
// =============================================================================
@MainActor
struct SetFormSections: View {
    @Binding var draft: SetDraft

    @Environment(AppStore.self) private var store

    init(draft: Binding<SetDraft>) {
        self._draft = draft
    }

    var body: some View {
        Group {
            micSection
            dateSection
            ratingsSection
            materialSection
            recordingSection
            reflectionsSection
            tagsSection
        }
    }

    // MARK: - Sections

    /// Opens the searchable mic picker.
    private var micSection: some View {
        Section {
            NavigationLink {
                SetMicPickerView(selection: $draft.micID)
            } label: {
                micLabel
            }
        } header: {
            Text("Which mic?")
        }
    }

    @ViewBuilder
    private var micLabel: some View {
        if let id = draft.micID, let mic = store.micsByID[id] {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: SetsLogic.micPickerLabel(mic))
                if !mic.isActive {
                    Text("This mic was removed from your list.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } else if draft.micID != nil {
            Text("Unknown mic")
                .foregroundStyle(.secondary)
        } else {
            Text("Choose a mic…")
                .foregroundStyle(.secondary)
        }
    }

    /// The date can't be later than today.
    private var dateSection: some View {
        Section {
            DatePicker(
                "Date performed",
                selection: dateBinding,
                in: ...Date(),
                displayedComponents: .date
            )
        } header: {
            Text("When?")
        }
    }

    private var ratingsSection: some View {
        Section {
            ratingSlider(
                title: "Set rating (how did YOU do?)",
                value: $draft.setRating,
                help: "1 = total bomb, 10 = absolute killer set"
            )
            ratingSlider(
                title: "Crowd rating (how was the audience?)",
                value: $draft.crowdRating,
                help: "1 = dead silent / hostile, 10 = engaged and laughing at everything"
            )
            VStack(alignment: .leading, spacing: 8) {
                Text("Crowd size")
                Picker("Crowd size", selection: $draft.crowdSize) {
                    ForEach(SetsLogic.crowdSizeOptions(current: draft.crowdSize), id: \.self) { raw in
                        Text(verbatim: SetsLogic.optionTitle(raw)).tag(raw)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            .padding(.vertical, 4)
        } header: {
            Text("How'd it go?")
        }
    }

    private var materialSection: some View {
        Section {
            TextField("Set list — bit names, in order", text: $draft.setList, axis: .vertical)
                .lineLimit(3...8)
            Toggle("Trying new material?", isOn: $draft.newMaterial)
        } header: {
            Text("Your material")
        }
    }

    private var recordingSection: some View {
        Section {
            TextField("Recording link (YouTube, Drive…)", text: $draft.recordingURL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            VStack(alignment: .leading, spacing: 8) {
                Text("Recording type")
                Picker("Recording type", selection: $draft.recordingType) {
                    ForEach(SetsLogic.recordingTypeOptions(current: draft.recordingType), id: \.self) { raw in
                        Text(verbatim: SetsLogic.optionTitle(raw)).tag(raw)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            .padding(.vertical, 4)
        } header: {
            Text("Recording")
        }
    }

    private var reflectionsSection: some View {
        Section {
            TextField("Notes — what worked, what didn't", text: $draft.notes, axis: .vertical)
                .lineLimit(3...8)
            Toggle("Got feedback?", isOn: $draft.gotFeedback)
            if draft.gotFeedback {
                TextField("What feedback did you get?", text: $draft.feedbackNotes, axis: .vertical)
                    .lineLimit(2...6)
            }
            Toggle("Would return?", isOn: $draft.wouldReturn)
        } header: {
            Text("Reflections")
        }
    }

    private var tagsSection: some View {
        Section {
            TagSelector(options: SetsLogic.tagOptions(including: draft.tags), selection: $draft.tags)
                .padding(.vertical, 4)
        } header: {
            Text("Tags")
        } footer: {
            Text("Pick all that apply.")
        }
    }

    // MARK: - Helpers

    /// The draft keeps a plain calendar day; the DatePicker wants a `Date`.
    /// Midnight in the device's time zone round-trips without shifting days.
    private var dateBinding: Binding<Date> {
        Binding(
            get: { draft.date.date() },
            set: { newValue in draft.date = CalendarDate(date: newValue) }
        )
    }

    /// A 1–10 slider with its value shown on the right and a hint below.
    private func ratingSlider(title: String, value: Binding<Int>, help: String) -> some View {
        let sliderValue = Binding<Double>(
            get: { Double(value.wrappedValue) },
            set: { newValue in value.wrappedValue = Int(newValue.rounded()) }
        )
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(verbatim: title)
                Spacer()
                Text(verbatim: "\(value.wrappedValue)/10")
                    .font(.body.monospacedDigit().weight(.semibold))
                    .foregroundStyle(Theme.accent)
            }
            Slider(value: sliderValue, in: 1...10, step: 1)
                .accessibilityLabel(Text(verbatim: title))
                .accessibilityValue(Text(verbatim: "\(value.wrappedValue) out of 10"))
            Text(verbatim: help)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

// =============================================================================
// SetMicPickerView — the searchable "Which mic?" list: active mics grouped
// Monday → Sunday, each labelled "Mon — 7:00 PM — Name @ Venue".
// Tapping a mic selects it and goes back to the form.
// =============================================================================
@MainActor
struct SetMicPickerView: View {
    @Binding var selection: Int?

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""

    init(selection: Binding<Int?>) {
        self._selection = selection
    }

    var body: some View {
        let sections = SetsLogic.micPickerSections(store.activeMics, search: searchText)

        List {
            if sections.isEmpty {
                Text(verbatim: store.activeMics.isEmpty ? "No active mics yet. Add one from Manage (the gear on Tonight)." : "No mics match \"\(searchText)\".")
                    .foregroundStyle(.secondary)
            }
            ForEach(sections) { section in
                Section {
                    ForEach(section.mics) { mic in
                        Button {
                            selection = mic.id
                            dismiss()
                        } label: {
                            HStack {
                                Text(verbatim: SetsLogic.micPickerLabel(mic))
                                    .foregroundStyle(.primary)
                                    .multilineTextAlignment(.leading)
                                Spacer(minLength: 8)
                                if selection == mic.id {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Theme.accent)
                                }
                            }
                        }
                    }
                } header: {
                    Text(verbatim: section.title)
                }
            }
        }
        .listStyle(.insetGrouped)
        .refreshable {
            await store.refreshAll()
        }
        .searchable(text: $searchText, prompt: "Search mics")
        .navigationTitle("Choose a Mic")
        .navigationBarTitleDisplayMode(.inline)
    }
}
