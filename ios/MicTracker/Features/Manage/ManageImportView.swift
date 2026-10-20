import SwiftUI

// =============================================================================
// ManageImportView — Manage → Import from Scrapers (spec 6.8).
//
// The nightly GitHub Actions job (scripts/run_scrapers.py) drops new
// listings into `scraped_mics` with status "pending". Here you review them,
// grouped by source (Bad Slava / FireMics):
//   * Add      → RPC import_scraped_mic (copies it into open_mics)
//   * Dismiss  → RPC dismiss_scraped_mic (never shown again)
//   * Add all  → every pending listing from that source
// =============================================================================
@MainActor
struct ManageImportView: View {
    @Environment(AppStore.self) private var store

    /// Listings with an Add/Dismiss request in flight.
    @State private var workingIDs: Set<Int> = []
    /// The source an "Add all" is running for.
    @State private var addingAllSource: String?
    /// The group waiting for "Add all" confirmation.
    @State private var pendingAddAll: ManageScrapedGroup?
    @State private var isConfirmingAddAll = false
    @State private var statusMessage: String?
    @State private var hasLoaded = false

    init() {}

    var body: some View {
        let groups = ManageLogic.scrapedGroups(store.scrapedMics)

        List {
            if let statusMessage = statusMessage {
                Section {
                    Label {
                        Text(verbatim: statusMessage)
                    } icon: {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(Theme.positive)
                    }
                    .font(.subheadline)
                }
            }

            if groups.isEmpty {
                Section {
                    if !hasLoaded && store.isLoadingScraped {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                    } else {
                        EmptyStateView(
                            title: "You're up to date.",
                            message: "No new listings from the scrapers.",
                            systemImage: "checkmark.seal"
                        )
                    }
                } footer: {
                    Text(verbatim: ManageLogic.scraperCaption)
                }
            } else {
                ForEach(groups) { group in
                    groupSection(group)
                }
                Section {
                    EmptyView()
                } footer: {
                    Text(verbatim: ManageLogic.scraperCaption)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Import")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await store.loadScraped()
            hasLoaded = true
        }
        .refreshable {
            await store.loadScraped()
        }
        .confirmationDialog(
            addAllTitle,
            isPresented: $isConfirmingAddAll,
            titleVisibility: .visible
        ) {
            Button("Add all") {
                if let group = pendingAddAll {
                    Task { await addAll(group) }
                }
            }
            Button("Cancel", role: .cancel) {
                pendingAddAll = nil
            }
        }
    }

    // MARK: - Sections

    private func groupSection(_ group: ManageScrapedGroup) -> some View {
        Section {
            ForEach(group.listings) { listing in
                listingRow(listing)
            }
        } header: {
            HStack {
                Text(verbatim: "\(group.title) (\(group.listings.count))")
                Spacer()
                if addingAllSource == group.source {
                    ProgressView()
                } else {
                    Button("Add all") {
                        pendingAddAll = group
                        isConfirmingAddAll = true
                    }
                    .font(.caption.weight(.semibold))
                    .disabled(addingAllSource != nil)
                }
            }
            .textCase(nil)
        }
    }

    private func listingRow(_ listing: ScrapedMic) -> some View {
        let isWorking = workingIDs.contains(listing.id) || addingAllSource == listing.source

        return VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: ManageLogic.scrapedTitle(listing))
                .font(.subheadline.weight(.semibold))
            if let address = TextRules.trimmedOrNil(listing.address) {
                Text(verbatim: address)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(verbatim: ManageLogic.scrapedDetails(listing))
                .font(.caption)
            if let signup = TextRules.trimmedOrNil(listing.signupUrl) {
                Text(verbatim: signup)
                    .font(.caption)
                    .foregroundStyle(Theme.neutral)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
            }
            HStack(spacing: 10) {
                // Borderless so each button gets its own taps inside the row.
                Button {
                    Task { await add(listing) }
                } label: {
                    actionLabel("Add", systemImage: "plus", color: Theme.positive)
                }
                .buttonStyle(.borderless)

                Button {
                    Task { await dismissListing(listing) }
                } label: {
                    actionLabel("Dismiss", systemImage: "xmark", color: .gray)
                }
                .buttonStyle(.borderless)

                if isWorking {
                    ProgressView()
                }
            }
            .padding(.top, 4)
            .disabled(isWorking)
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                Task { await add(listing) }
            } label: {
                Label("Add", systemImage: "plus")
            }
            .tint(Theme.positive)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button {
                Task { await dismissListing(listing) }
            } label: {
                Label("Dismiss", systemImage: "xmark")
            }
            .tint(.gray)
        }
    }

    /// A small capsule that looks like a button.
    private func actionLabel(_ title: String, systemImage: String, color: Color) -> some View {
        Label {
            Text(verbatim: title)
        } icon: {
            Image(systemName: systemImage)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(color)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(color.opacity(0.15)))
    }

    private var addAllTitle: String {
        guard let group = pendingAddAll else { return "Add all listings?" }
        return "Add all \(group.listings.count) listings from \(group.title)?"
    }

    // MARK: - Actions

    @MainActor
    private func add(_ listing: ScrapedMic) async {
        guard !workingIDs.contains(listing.id) else { return }
        workingIDs.insert(listing.id)
        let newID = await store.importScraped(id: listing.id)
        workingIDs.remove(listing.id)
        if newID != nil {
            statusMessage = "Added \(listing.name) (\(listing.weekday?.name ?? listing.dayOfWeek ?? "?"))!"
        }
    }

    @MainActor
    private func dismissListing(_ listing: ScrapedMic) async {
        guard !workingIDs.contains(listing.id) else { return }
        workingIDs.insert(listing.id)
        let dismissed = await store.dismissScraped(id: listing.id)
        workingIDs.remove(listing.id)
        if dismissed {
            statusMessage = "Dismissed \(listing.name)."
        }
    }

    @MainActor
    private func addAll(_ group: ManageScrapedGroup) async {
        pendingAddAll = nil
        addingAllSource = group.source
        let added = await store.importAllScraped(source: group.source)
        addingAllSource = nil
        statusMessage = "Added \(added) of \(group.listings.count) from \(group.title)."
    }
}
