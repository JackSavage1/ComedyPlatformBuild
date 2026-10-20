import SwiftUI

// =============================================================================
// ManageMicsListView — Manage → Manage Mics (spec 6.8): every active mic as
// "Mon — Name @ Venue", searchable. Tap → Mic Detail without a date (edit,
// rate or delete it there).
// =============================================================================
@MainActor
struct ManageMicsListView: View {
    @Environment(AppStore.self) private var store
    @State private var searchText = ""

    init() {}

    var body: some View {
        let mics = ManageLogic.searchMics(store.activeMics, query: searchText)

        List {
            Section {
                if mics.isEmpty {
                    Text(verbatim: store.activeMics.isEmpty ? "No active mics yet. Add one from Manage → Add a Mic." : "No mics match \"\(searchText)\".")
                        .foregroundStyle(.secondary)
                }
                ForEach(mics) { mic in
                    NavigationLink {
                        MicDetailView(micID: mic.id, date: nil)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: ManageLogic.micListLabel(mic))
                                .font(.subheadline.weight(.semibold))
                            let details = TextRules.joinNonBlank([mic.timeText, mic.locationText], separator: " · ")
                            if !details.isEmpty {
                                Text(verbatim: details)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } header: {
                Text(verbatim: "\(mics.count) active mics")
            }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $searchText, prompt: "Name, venue or neighborhood")
        .navigationTitle("Manage Mics")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            await store.refreshAll()
        }
    }
}
