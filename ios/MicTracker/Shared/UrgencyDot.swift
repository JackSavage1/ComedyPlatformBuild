import SwiftUI

/// The little colored dot showing how fast a mic's list fills (rule 3):
/// red = high, yellow = medium, green = normal.
@MainActor
struct UrgencyDot: View {
    let urgency: Urgency
    var size: CGFloat = 10

    init(urgency: Urgency, size: CGFloat = 10) {
        self.urgency = urgency
        self.size = size
    }

    init(mic: Mic, size: CGFloat = 10) {
        self.init(urgency: mic.urgencyLevel, size: size)
    }

    var body: some View {
        Circle()
            .fill(Theme.urgencyColor(urgency))
            .frame(width: size, height: size)
            .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        switch urgency {
        case .high: return "High urgency"
        case .medium: return "Medium urgency"
        case .normal: return "Normal urgency"
        }
    }
}
