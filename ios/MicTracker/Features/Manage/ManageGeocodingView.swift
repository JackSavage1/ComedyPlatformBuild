import SwiftUI

// =============================================================================
// ManageGeocodingView — Manage → Geocoding (spec 6.8).
//
// Map pins need latitude/longitude. Mics added by hand or by the scrapers
// often only have a street address, so "Geocode with device" looks each
// address up with Apple's geocoder (AppStore.geocodeMissingCoordinates →
// GeocodingService): "{address}, {borough}, New York, NY", then
// "{address}, New York, NY"; results outside NYC are rejected (rule 18);
// one request per second. Each hit is saved with a PATCH as it's found,
// so stopping halfway keeps what was already done.
// =============================================================================
@MainActor
struct ManageGeocodingView: View {
    @Environment(AppStore.self) private var store

    /// The running batch (so "Stop" can cancel it).
    @State private var geocodeTask: Task<Void, Never>?
    /// "Geocoded 3 of 5" when the batch finishes.
    @State private var resultMessage: String?

    init() {}

    var body: some View {
        let missing = store.micsMissingCoordinates
        let withAddress = ManageLogic.geocodableCount(missing)

        List {
            Section {
                LabeledContent("Mics with coordinates", value: ManageLogic.coordinatesText(activeMics: store.activeMics))

                if let progress = store.geocodeProgress {
                    progressRow(progress)
                    if geocodeTask != nil {
                        Button("Stop") {
                            geocodeTask?.cancel()
                        }
                    }
                } else {
                    Button {
                        startGeocoding()
                    } label: {
                        Label("Geocode with device", systemImage: "location.magnifyingglass")
                    }
                    .disabled(withAddress == 0 || store.isBusy)
                }

                if let resultMessage = resultMessage {
                    Text(verbatim: resultMessage)
                        .font(.subheadline.weight(.semibold))
                }
            } footer: {
                Text(verbatim: footerText(missingCount: missing.count, withAddress: withAddress))
            }

            Section {
                if missing.isEmpty {
                    Text(verbatim: "Every active mic is on the map. 🎉")
                        .foregroundStyle(.secondary)
                }
                ForEach(missing) { mic in
                    NavigationLink {
                        MicDetailView(micID: mic.id, date: nil)
                    } label: {
                        missingRow(mic)
                    }
                }
            } header: {
                Text(verbatim: "Missing coordinates (\(missing.count))")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Geocoding")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await store.refreshAll()
        }
    }

    // MARK: - Rows

    private func progressRow(_ progress: GeocodeProgress) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
            Text(verbatim: "\(progress.done) of \(progress.total) checked · \(progress.succeeded) found")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            if let name = progress.currentMicName {
                Text(verbatim: "Looking up \(name)…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 4)
    }

    private func missingRow(_ mic: Mic) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: mic.name)
                .font(.subheadline.weight(.semibold))
            if !mic.venue.isEmpty {
                Text(verbatim: mic.venue)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let address = TextRules.trimmedOrNil(mic.address) {
                Text(verbatim: address)
                    .font(.caption)
            } else {
                Text(verbatim: "No address — add one with Edit mic")
                    .font(.caption)
                    .foregroundStyle(Theme.negative)
            }
        }
    }

    private func footerText(missingCount: Int, withAddress: Int) -> String {
        if missingCount == 0 {
            return "Nothing to geocode."
        }
        if withAddress == 0 {
            return "None of the missing mics has an address yet, so there's nothing to look up."
        }
        return "Looks up \(withAddress) address(es) with Apple Maps on this phone, about one per second, and saves each pin that lands inside NYC."
    }

    // MARK: - Actions

    private func startGeocoding() {
        resultMessage = nil
        let appStore = store
        geocodeTask = Task { @MainActor in
            let summary = await appStore.geocodeMissingCoordinates()
            resultMessage = summary.attempted == 0 ? "No mics with an address to geocode." : summary.message
            geocodeTask = nil
        }
    }
}
