import SwiftUI

/// A dismissible red banner showing an error message (spec 5: "on failure,
/// a dismissible error with the server message").
@MainActor
struct ErrorBanner: View {
    let message: String
    var onDismiss: () -> Void

    init(message: String, onDismiss: @escaping () -> Void) {
        self.message = message
        self.onDismiss = onDismiss
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.white)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(4)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss error")
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Theme.negative)
        )
        .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
    }
}

/// Shows `AppStore.errorMessage` as a banner pinned to the top of the view it
/// is applied to. MainTabView and the Manage sheet already apply it, so
/// feature screens usually don't need to.
@MainActor
struct StoreErrorBannerModifier: ViewModifier {
    @Environment(AppStore.self) private var store

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if let message = store.errorMessage {
                    ErrorBanner(message: message) {
                        store.clearError()
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 4)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: store.errorMessage)
    }
}

@MainActor
extension View {
    /// Overlays the store's current error (if any) as a dismissible banner.
    func storeErrorBanner() -> some View {
        modifier(StoreErrorBannerModifier())
    }
}
