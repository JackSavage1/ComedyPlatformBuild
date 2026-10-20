import SwiftUI

/// A centered spinner card over a dimmed background, for network work that
/// blocks the screen (first load, saving, geocoding).
@MainActor
struct LoadingOverlay: View {
    var message: String?

    init(message: String? = nil) {
        self.message = message
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.15)
                .ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                    .controlSize(.large)
                if let message = message {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(24)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message ?? "Loading")
    }
}

@MainActor
extension View {
    /// Covers the view with a `LoadingOverlay` while `isShowing` is true.
    func loadingOverlay(_ isShowing: Bool, message: String? = nil) -> some View {
        overlay {
            if isShowing {
                LoadingOverlay(message: message)
            }
        }
    }
}
