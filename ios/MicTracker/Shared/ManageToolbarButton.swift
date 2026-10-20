import SwiftUI

/// The gear button on Tonight and Calendar (spec 5). It presents
/// `ManageView` in a sheet, wrapped in its own NavigationStack with a Done
/// button, and re-injects the store and the error banner.
///
/// Usage inside a screen that is in a NavigationStack:
///
///     .toolbar {
///         ToolbarItem(placement: .topBarTrailing) { ManageToolbarButton() }
///     }
///
/// or simply `.manageToolbarButton()`.
@MainActor
struct ManageToolbarButton: View {
    @Environment(AppStore.self) private var store
    @State private var isPresented = false

    init() {}

    var body: some View {
        Button {
            isPresented = true
        } label: {
            Image(systemName: "gearshape")
        }
        .accessibilityLabel("Manage")
        .sheet(isPresented: $isPresented) {
            ManageSheet(store: store)
        }
    }
}

/// The sheet's content: ManageView in a NavigationStack with a Done button.
/// `initialRoutes` pushes Import / Add a Mic right away (only the CI
/// screenshot launch hooks use it — see LaunchOptions).
@MainActor
struct ManageSheet: View {
    let store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var path: NavigationPath

    init(store: AppStore, initialRoutes: [LaunchRoute] = []) {
        self.store = store
        _path = State(initialValue: NavigationPath(initialRoutes))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ManageView()
                .launchRouteDestinations()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { dismiss() }
                    }
                }
        }
        .storeErrorBanner()
        .tint(Theme.accent)
        // Outermost, so the banner modifier above can read the store too.
        .environment(store)
    }
}

@MainActor
extension View {
    /// Adds the gear (→ Manage sheet) to the trailing side of the nav bar.
    func manageToolbarButton() -> some View {
        toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ManageToolbarButton()
            }
        }
    }
}
