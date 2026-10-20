import SwiftUI

// =============================================================================
// MicFormView — add a new mic or edit an existing one (spec 6.9; port of the
// "Add a Mic" / "Manage Mics" forms on the Streamlit Settings page).
//
//   MicFormView()               → Add (from Manage → Add a Mic)
//   MicFormView(editing: mic)   → Edit (from Mic Detail → Edit mic)
//
// It is PUSHED onto an existing NavigationStack, so it doesn't make its own.
//
// Saving:
//   * Validates the required fields with the web app's message.
//   * Add: if there's an address, geocode it on the device first (NYC-only
//     results, rule 18) and include latitude/longitude when found; POST
//     with null keys omitted; then "Added {name} @ {venue} ({day})!".
//   * Edit: PATCH every field (explicit nulls for cleared ones). If the
//     address changed, re-geocode (a cleared or unplaceable address clears
//     the old pin, which was for the old address).
//   * On success: a confirmation alert, then the screen closes.
// =============================================================================
@MainActor
struct MicFormView: View {
    private let editingMic: Mic?

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    /// Everything the form edits. `MicFields` (Core/Payloads.swift) knows how
    /// to normalize, validate and encode itself.
    @State private var fields: MicFields
    @State private var validationMessage: String?
    @State private var isSaving = false
    @State private var progressMessage = "Saving…"
    /// The alert on screen (validation problem or "saved"), if any.
    @State private var alertInfo: MicFormAlert?

    init(editing mic: Mic? = nil) {
        self.editingMic = mic
        _fields = State(initialValue: mic.map { MicFields(mic: $0) } ?? MicFormTime.newMicFields())
    }

    private var isEditing: Bool { editingMic != nil }

    private var screenTitle: String { isEditing ? "Edit Mic" : "Add a Mic" }
    private var toolbarSaveTitle: String { isEditing ? "Save" : "Add" }

    var body: some View {
        Form {
            basicInfoSection
            scheduleSection
            detailsSection
            signupSection
            urgencySection
            notesSection
            saveSection
        }
        .navigationTitle(screenTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(toolbarSaveTitle) {
                    Task { await save() }
                }
                .disabled(isSaving)
            }
        }
        .disabled(isSaving)
        .loadingOverlay(isSaving, message: progressMessage)
        .alert(alertInfo?.title ?? "", isPresented: isShowingAlert, presenting: alertInfo) { info in
            Button("OK") {
                if info.closesForm {
                    dismiss()
                }
            }
        } message: { info in
            Text(verbatim: info.message)
        }
    }

    // MARK: - Sections

    private var basicInfoSection: some View {
        Section {
            TextField("Mic name *", text: $fields.name)
                .textInputAutocapitalization(.words)
            TextField("Venue *", text: $fields.venue)
                .textInputAutocapitalization(.words)
            TextField("Address (e.g. 123 Main St)", text: optionalText(\.address))
                .textContentType(.fullStreetAddress)
            TextField("Neighborhood (e.g. East Village)", text: optionalText(\.neighborhood))
                .textInputAutocapitalization(.words)
            Picker("Borough", selection: $fields.borough) {
                Text("Not set").tag(String?.none)
                ForEach(boroughOptions, id: \.self) { borough in
                    Text(verbatim: borough).tag(String?.some(borough))
                }
            }
        } header: {
            Text("Basic Info")
        } footer: {
            if !isEditing {
                Text("If you enter an address, the phone looks it up so the mic shows on the map.")
            }
        }
    }

    private var scheduleSection: some View {
        Section {
            Picker("Day of week *", selection: $fields.dayOfWeek) {
                ForEach(dayOptions, id: \.self) { day in
                    Text(verbatim: day).tag(day)
                }
            }
            DatePicker("Start time *", selection: startTimeBinding, displayedComponents: .hourAndMinute)
            if MicFormTime.isUnreadable(fields.startTime) {
                Text(verbatim: "The saved start time \"\(fields.startTime)\" couldn't be read. Pick a time to replace it.")
                    .font(.footnote)
                    .foregroundStyle(Theme.warning)
            }
            HStack {
                Text("Display time")
                Spacer(minLength: 12)
                TextField("e.g. 7:00 PM", text: optionalText(\.displayTime))
                    .multilineTextAlignment(.trailing)
            }
        } header: {
            Text("Schedule")
        } footer: {
            Text("Display time fills in from the start time; you can type your own (e.g. \"7pm sharp\").")
        }
    }

    private var detailsSection: some View {
        Section {
            TextField("Cost (e.g. Free, $5, 1 drink min)", text: optionalText(\.cost))
            Stepper(value: setLengthBinding, in: 0...setLengthUpperBound) {
                HStack {
                    Text("Set length")
                    Spacer()
                    Text(verbatim: setLengthLabel)
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Details")
        }
    }

    private var signupSection: some View {
        Section {
            Picker("Signup method", selection: $fields.signupMethod) {
                Text("Not set").tag(String?.none)
                ForEach(signupMethodOptions, id: \.self) { raw in
                    Text(verbatim: SignupMethod(raw: raw)?.displayName ?? raw).tag(String?.some(raw))
                }
            }
            TextField("Signup URL (https://…)", text: optionalText(\.signupUrl))
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Signup notes (e.g. Arrive 30 min early)", text: optionalText(\.signupNotes))
            TextField("Venue website (https://…)", text: optionalText(\.venueUrl))
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Instagram (e.g. @comedyclub)", text: optionalText(\.instagram))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } header: {
            Text("Signup")
        }
    }

    private var urgencySection: some View {
        Section {
            Picker("Urgency", selection: $fields.urgency) {
                ForEach(urgencyOptions, id: \.self) { level in
                    Text(verbatim: level.title).tag(level.rawValue)
                }
            }
            .pickerStyle(.segmented)
            TextField("Urgency note (e.g. Signs up fast!)", text: optionalText(\.urgencyNote))
            Stepper(value: $fields.advanceDays, in: 0...advanceDaysUpperBound) {
                HStack {
                    Text("Advance days to sign up")
                    Spacer()
                    Text(verbatim: "\(fields.advanceDays)")
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            Toggle("Biweekly", isOn: $fields.isBiweekly)
        } header: {
            Text("Urgency")
        } footer: {
            Text("Advance days: how many days before the mic the signup opens or closes (0 = same day).")
        }
    }

    private var notesSection: some View {
        Section {
            TextField("Any other details about this mic", text: optionalText(\.notes), axis: .vertical)
                .lineLimit(3...8)
        } header: {
            Text("Notes")
        }
    }

    private var saveSection: some View {
        Section {
            if let message = validationMessage {
                Label {
                    Text(verbatim: message)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .foregroundStyle(Theme.negative)
            }
            Button {
                Task { await save() }
            } label: {
                Text(verbatim: isEditing ? "Save Changes" : "➕ Add Mic")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .disabled(isSaving)
        } footer: {
            Text("* required")
        }
    }

    // MARK: - Picker options

    /// The five boroughs, plus the mic's current value if it's something else.
    private var boroughOptions: [String] {
        var options = BoroughRules.all
        if let current = TextRules.trimmedOrNil(fields.borough), !options.contains(current) {
            options.append(current)
        }
        return options
    }

    /// Monday…Sunday, plus the stored text if it isn't a real day name.
    private var dayOptions: [String] {
        var options = Weekday.allCases.map { $0.name }
        let current = fields.dayOfWeek
        if !current.isEmpty && !options.contains(current) {
            options.append(current)
        }
        return options
    }

    /// in_person, online, email, instagram_dm, plus an unusual stored value.
    private var signupMethodOptions: [String] {
        var options = SignupMethod.standardCases.map { $0.rawValue }
        if let current = fields.signupMethod, !options.contains(current) {
            options.append(current)
        }
        return options
    }

    /// normal / medium / high (the web form's order).
    private var urgencyOptions: [Urgency] {
        [.normal, .medium, .high]
    }

    // MARK: - Bindings

    /// Binds a TextField to an optional text field of `MicFields`
    /// (nil shows as ""; blanks become nil when the form is normalized).
    private func optionalText(_ keyPath: WritableKeyPath<MicFields, String?>) -> Binding<String> {
        Binding(
            get: { fields[keyPath: keyPath] ?? "" },
            set: { newValue in fields[keyPath: keyPath] = newValue }
        )
    }

    /// The DatePicker edits a `Date`; we store "HH:mm" and auto-fill the
    /// display time (rule 19).
    private var startTimeBinding: Binding<Date> {
        Binding(
            get: {
                let parts = MicFormTime.pickerComponents(for: fields.startTime)
                return MicFormTime.pickerDate(hour: parts.hour, minute: parts.minute)
            },
            set: { newDate in
                let parts = MicFormTime.hourAndMinute(of: newDate)
                fields = MicFormTime.applying(hour: parts.hour, minute: parts.minute, to: fields)
            }
        )
    }

    /// Set length 0 = unknown (saved as null).
    private var setLengthBinding: Binding<Int> {
        Binding(
            get: { fields.setLengthMin ?? 0 },
            set: { newValue in fields.setLengthMin = newValue > 0 ? newValue : nil }
        )
    }

    /// 0–30 like the web form, stretched if an existing mic already has more.
    private var setLengthUpperBound: Int {
        max(30, fields.setLengthMin ?? 0)
    }

    private var advanceDaysUpperBound: Int {
        max(60, fields.advanceDays)
    }

    private var setLengthLabel: String {
        if let minutes = fields.setLengthMin, minutes > 0 {
            return "\(minutes) min"
        }
        return "Unknown"
    }

    // MARK: - Saving

    private func save() async {
        guard !isSaving else { return }
        validationMessage = nil
        if let problem = fields.validationError {
            validationMessage = problem
            alertInfo = MicFormAlert(title: "Missing info", message: problem, closesForm: false)
            return
        }

        isSaving = true
        defer { isSaving = false }

        if let mic = editingMic {
            await saveChanges(to: mic)
        } else {
            await addNewMic()
        }
    }

    /// POST a new mic (geocoding its address first when there is one).
    private func addNewMic() async {
        var toSave = fields
        var mapNote: String?

        if TextRules.trimmedOrNil(toSave.address) != nil {
            progressMessage = "Finding the address on the map…"
            let point = await store.geocode(address: toSave.address, borough: toSave.borough)
            toSave.latitude = point?.latitude
            toSave.longitude = point?.longitude
            if point == nil {
                mapNote = "Couldn't place this address on the map. You can retry later from Manage → Geocoding."
            }
        } else {
            toSave.latitude = nil
            toSave.longitude = nil
        }

        progressMessage = "Saving…"
        guard await store.addMic(toSave) != nil else { return }

        let saved = toSave.normalized()
        var message = "Added \(saved.name) @ \(saved.venue) (\(saved.dayOfWeek))!"
        if let mapNote = mapNote {
            message += "\n\n" + mapNote
        }
        fields = toSave
        showSuccess(message)
    }

    /// PATCH every field; re-geocode only when the address changed.
    private func saveChanges(to mic: Mic) async {
        var toSave = fields
        var mapNote: String?
        let oldAddress = TextRules.trimmedOrNil(mic.address)
        let newAddress = TextRules.trimmedOrNil(toSave.address)
        let addressChanged = oldAddress != newAddress

        if addressChanged {
            if newAddress != nil {
                progressMessage = "Finding the new address on the map…"
                let point = await store.geocode(address: toSave.address, borough: toSave.borough)
                toSave.latitude = point?.latitude
                toSave.longitude = point?.longitude
                if point == nil {
                    mapNote = "Couldn't place the new address on the map. You can retry later from Manage → Geocoding."
                }
            } else {
                // No address any more → the old pin no longer means anything.
                toSave.latitude = nil
                toSave.longitude = nil
            }
        }

        progressMessage = "Saving…"
        let saved = await store.updateMic(id: mic.id, fields: toSave, includeCoordinates: addressChanged)
        guard saved else { return }

        var message = "Saved changes to \(toSave.normalized().name)."
        if let mapNote = mapNote {
            message += "\n\n" + mapNote
        }
        fields = toSave
        showSuccess(message)
    }

    private func showSuccess(_ message: String) {
        alertInfo = MicFormAlert(
            title: isEditing ? "Mic updated" : "Mic added",
            message: message,
            closesForm: true
        )
    }

    /// Drives the alert from `alertInfo`.
    private var isShowingAlert: Binding<Bool> {
        Binding(
            get: { alertInfo != nil },
            set: { isShown in
                if !isShown { alertInfo = nil }
            }
        )
    }
}

/// What the Mic Form's alert shows. A "saved" alert closes the form on OK.
private struct MicFormAlert {
    let title: String
    let message: String
    let closesForm: Bool
}
