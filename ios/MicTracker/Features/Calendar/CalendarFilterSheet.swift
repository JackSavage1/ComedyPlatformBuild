import SwiftUI

// =============================================================================
// CalendarFilterSheet — the Calendar's filter sheet (spec 6.3):
//   Borough (multi) · Neighborhood (multi, limited to the chosen boroughs) ·
//   Cost (single) · Signup Type (single) · Only unvisited · Only going this
//   week · Reset
//
// It edits the Calendar's `CalendarFilters` through a binding, so the list
// behind the sheet updates live. It doesn't need the AppStore: the Calendar
// passes in the active mics (for the neighborhood choices).
// =============================================================================
@MainActor
struct CalendarFilterSheet: View {
    @Binding var filters: CalendarFilters
    /// Active mics — the source of the neighborhood choices.
    let mics: [Mic]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                boroughSection
                neighborhoodSection
                costSection
                signupSection
                toggleSection
                resetSection
            }
            .navigationTitle("Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .tint(Theme.accent)
    }

    // MARK: - Sections

    private var boroughSection: some View {
        Section {
            ForEach(BoroughRules.all, id: \.self) { borough in
                checkRow(
                    title: borough,
                    dotColor: Theme.boroughColor(borough),
                    isOn: filters.boroughs.contains(borough)
                ) {
                    filters.toggleBorough(borough, availableMics: mics)
                }
            }
        } header: {
            Text("Borough")
        } footer: {
            Text(verbatim: filters.boroughs.isEmpty ? "All boroughs" : "Showing only the checked boroughs")
        }
    }

    private var neighborhoodSection: some View {
        let options = CalendarFilters.neighborhoodOptions(from: mics, boroughs: filters.boroughs)
        return Section {
            if options.isEmpty {
                Text("No neighborhoods to choose from.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(options, id: \.self) { neighborhood in
                    checkRow(
                        title: neighborhood,
                        dotColor: nil,
                        isOn: filters.neighborhoods.contains(neighborhood)
                    ) {
                        filters.toggleNeighborhood(neighborhood)
                    }
                }
            }
        } header: {
            Text("Neighborhood")
        } footer: {
            Text(verbatim: filters.boroughs.isEmpty ? "All neighborhoods" : "Neighborhoods in the selected boroughs")
        }
    }

    private var costSection: some View {
        Section {
            Picker("Cost", selection: $filters.cost) {
                ForEach(CostFilter.allCases) { option in
                    Text(verbatim: option.title).tag(option)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } header: {
            Text("Cost")
        }
    }

    private var signupSection: some View {
        Section {
            Picker("Signup Type", selection: $filters.signup) {
                ForEach(SignupFilter.allCases) { option in
                    Text(verbatim: option.title).tag(option)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } header: {
            Text("Signup Type")
        }
    }

    private var toggleSection: some View {
        Section {
            Toggle("Only unvisited", isOn: $filters.onlyUnvisited)
            Toggle("Only going this week", isOn: $filters.onlyGoing)
        } footer: {
            Text("\"Unvisited\" means you haven't logged a set there yet.")
        }
    }

    private var resetSection: some View {
        Section {
            Button("Reset filters", role: .destructive) {
                filters.reset()
            }
            .disabled(!filters.isActive)
        }
    }

    // MARK: - Rows

    /// A tappable row with an optional colored dot and a checkmark when on.
    private func checkRow(
        title: String,
        dotColor: Color?,
        isOn: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let dotColor = dotColor {
                    Circle()
                        .fill(dotColor)
                        .frame(width: 10, height: 10)
                }
                Text(verbatim: title)
                    .foregroundStyle(Color.primary)
                Spacer()
                if isOn {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                }
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}
