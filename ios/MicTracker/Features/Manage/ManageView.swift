import SwiftUI

// =============================================================================
// ManageView — the gear menu on Tonight and Calendar (spec 6.8, a port of
// pages/4_⚙️_Settings.py plus the Map page's geocoding tools).
//
//   ➕ Add a Mic                → Mic Form (empty)
//   📥 Import from Scrapers  (n) → review queue: Add / Dismiss / Add all
//   🕘 Scrape History            → scrape_log, newest first
//   🎤 Manage Mics              → searchable list → Mic Detail (no date)
//   📍 Geocoding                → coordinates X / Y, "Geocode with device"
//   👤 Account                  → signed-in email, Sign out
//
// Contract (see Shared/ManageToolbarButton.swift): this view is shown in a
// sheet that ALREADY wraps it in a NavigationStack with a "Done" button, so
// it must not add either. Sub-screens are pushed with NavigationLink.
// =============================================================================
@MainActor
struct ManageView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var isConfirmingSignOut = false

    var body: some View {
        List {
            addSection
            scraperSection
            micsSection
            accountSection
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Manage")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // For the "Import from Scrapers" badge.
            await store.loadScraped()
        }
        .refreshable {
            await store.refreshAll()
            await store.loadScraped()
        }
        .confirmationDialog(
            "Sign out of Mic Tracker?",
            isPresented: $isConfirmingSignOut,
            titleVisibility: .visible
        ) {
            Button("Sign out", role: .destructive) {
                signOut()
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: - Sections

    private var addSection: some View {
        Section {
            NavigationLink {
                MicFormView()
            } label: {
                Label("Add a Mic", systemImage: "plus.circle")
            }
        }
    }

    private var scraperSection: some View {
        Section {
            NavigationLink {
                ManageImportView()
            } label: {
                Label("Import from Scrapers", systemImage: "tray.and.arrow.down")
            }
            .badge(store.scrapedMics.count)

            NavigationLink {
                ManageScrapeHistoryView()
            } label: {
                Label("Scrape History", systemImage: "clock.arrow.circlepath")
            }
        } header: {
            Text("Scrapers")
        } footer: {
            Text(verbatim: ManageLogic.scraperCaption)
        }
    }

    private var micsSection: some View {
        Section {
            NavigationLink {
                ManageMicsListView()
            } label: {
                LabeledContent {
                    Text(verbatim: "\(store.activeMics.count)")
                } label: {
                    Label("Manage Mics", systemImage: "music.mic")
                }
            }

            NavigationLink {
                ManageGeocodingView()
            } label: {
                LabeledContent {
                    Text(verbatim: ManageLogic.coordinatesText(activeMics: store.activeMics))
                } label: {
                    Label("Geocoding", systemImage: "mappin.and.ellipse")
                }
            }
        } header: {
            Text("Your mics")
        }
    }

    private var accountSection: some View {
        Section {
            LabeledContent("Signed in as", value: signedInEmail)
            Button(role: .destructive) {
                isConfirmingSignOut = true
            } label: {
                Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
            }
        } header: {
            Text("Account")
        }
    }

    // MARK: - Helpers

    private var signedInEmail: String {
        if let email = store.auth.email, !email.isEmpty {
            return email
        }
        if case let .ready(email) = store.authState, !email.isEmpty {
            return email
        }
        return "Unknown"
    }

    /// Closes the sheet, then signs out (RootView then shows Sign in).
    private func signOut() {
        // Grab the store now: the sheet (and its environment) goes away
        // before the sign-out request finishes.
        let appStore = store
        dismiss()
        Task { @MainActor in
            await appStore.signOut()
        }
    }
}
