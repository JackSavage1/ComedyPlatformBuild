import SwiftUI

/// A friendly centered message for empty lists ("No mics", "You're up to
/// date.", the No-sets-yet copy, …).
@MainActor
struct EmptyStateView: View {
    let title: String
    var message: String?
    var systemImage: String

    init(title: String, message: String? = nil, systemImage: String = "music.mic") {
        self.title = title
        self.message = message
        self.systemImage = systemImage
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
            if let message = message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
    }
}
