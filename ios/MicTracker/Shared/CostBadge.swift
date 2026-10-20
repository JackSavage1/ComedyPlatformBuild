import SwiftUI

/// A small capsule showing the cost bucket (rule 2): 🆓 Free, 🍺 Drink min,
/// 💵 Paid. Pass `showsCostText: true` to show the raw cost text instead of
/// the bucket label (e.g. "💵 $5").
@MainActor
struct CostBadge: View {
    let cost: String?
    var showsCostText: Bool = false

    init(cost: String?, showsCostText: Bool = false) {
        self.cost = cost
        self.showsCostText = showsCostText
    }

    var body: some View {
        let kind = CostKind(cost: cost)
        Text("\(kind.emoji) \(label(for: kind))")
            .font(.caption.weight(.semibold))
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(tint(for: kind).opacity(0.18)))
            .overlay(Capsule().stroke(tint(for: kind).opacity(0.35), lineWidth: 0.5))
            .accessibilityLabel("Cost: \(MicRules.costText(cost))")
    }

    private func label(for kind: CostKind) -> String {
        if showsCostText, let text = TextRules.trimmedOrNil(cost) {
            return text
        }
        return kind.label
    }

    private func tint(for kind: CostKind) -> Color {
        switch kind {
        case .free: return Theme.positive
        case .drinkMin: return Theme.warning
        case .paid: return Theme.neutral
        }
    }
}
