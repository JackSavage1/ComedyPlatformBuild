import SwiftUI

// =============================================================================
// ManageScrapeHistoryView — Manage → Scrape History (spec 6.8): every row
// of `scrape_log`, newest first — source, time, status and notes.
// =============================================================================
@MainActor
struct ManageScrapeHistoryView: View {
    @Environment(AppStore.self) private var store
    @State private var hasLoaded = false

    init() {}

    var body: some View {
        List {
            if store.scrapeLog.isEmpty {
                if !hasLoaded && store.isLoadingScrapeLog {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                } else {
                    EmptyStateView(
                        title: "No scrapes logged yet.",
                        message: ManageLogic.scraperCaption,
                        systemImage: "clock.arrow.circlepath"
                    )
                    .listRowBackground(Color.clear)
                }
            } else {
                ForEach(store.scrapeLog) { entry in
                    row(entry)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Scrape History")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await store.loadScrapeLog()
            hasLoaded = true
        }
        .refreshable {
            await store.loadScrapeLog()
        }
    }

    private func row(_ entry: ScrapeLogEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: ScrapedMic.displayName(forSource: entry.source ?? "unknown"))
                    .font(.headline)
                Spacer()
                if let status = TextRules.trimmedOrNil(entry.status) {
                    Text(verbatim: status)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(statusColor(entry.status))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(statusColor(entry.status).opacity(0.15)))
                }
            }
            Text(verbatim: entry.lastScrapedText)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let notes = TextRules.trimmedOrNil(entry.notes) {
                Text(verbatim: notes)
                    .font(.caption)
                    .textSelection(.enabled)
            }
        }
        .padding(.vertical, 2)
    }

    private func statusColor(_ status: String?) -> Color {
        switch ManageLogic.scrapeStatus(status) {
        case .success: return Theme.positive
        case .failure: return Theme.negative
        case .other: return .secondary
        }
    }
}
