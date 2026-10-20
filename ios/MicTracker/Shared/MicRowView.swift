import SwiftUI

/// A mic in a list. Three looks:
///
/// * `.standard` (Tonight): urgency dot · time · **name** · "@ venue" · cost
/// * `.compact`  (Tomorrow): one line "time — name @ venue (neighborhood) · cost"
/// * `.calendar` (Calendar day list): ✅/❌ status · dot · time · **name** ·
///   cost badge + neighborhood + 🔄 · signup-deadline line
///
/// Wrap it in a `NavigationLink` to make it tappable.
@MainActor
struct MicRowView: View {
    enum Style: Hashable {
        case standard
        case compact
        case calendar
    }

    let mic: Mic
    var style: Style
    /// Going / Cancelled for the row's date (calendar style shows ✅ / ❌).
    var planStatus: PlanStatus?
    /// Rule 7 deadline for the row's date (calendar style shows it).
    var deadline: SignupDeadline?

    init(mic: Mic, style: Style = .standard, planStatus: PlanStatus? = nil, deadline: SignupDeadline? = nil) {
        self.mic = mic
        self.style = style
        self.planStatus = planStatus
        self.deadline = deadline
    }

    var body: some View {
        switch style {
        case .standard:
            standardRow
        case .compact:
            compactRow
        case .calendar:
            calendarRow
        }
    }

    // MARK: - Standard (Tonight)

    private var standardRow: some View {
        HStack(alignment: .top, spacing: 12) {
            UrgencyDot(mic: mic)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: mic.timeText)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                Text(verbatim: mic.name)
                    .font(.headline)
                if !mic.venue.isEmpty {
                    Text(verbatim: "@ \(mic.venue)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text(verbatim: mic.costText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            CostBadge(cost: mic.cost)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Compact (Tomorrow)

    private var compactRow: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            UrgencyDot(mic: mic, size: 8)
            Text(verbatim: compactText)
                .font(.subheadline)
                .lineLimit(2)
        }
        .accessibilityElement(children: .combine)
    }

    private var compactText: String {
        var text = mic.timeText.isEmpty ? mic.name : "\(mic.timeText) — \(mic.name)"
        if !mic.venue.isEmpty { text += " @ \(mic.venue)" }
        if let neighborhood = TextRules.trimmedOrNil(mic.neighborhood) { text += " (\(neighborhood))" }
        text += " · \(mic.costText)"
        return text
    }

    // MARK: - Calendar

    private var calendarRow: some View {
        HStack(alignment: .top, spacing: 10) {
            UrgencyDot(mic: mic)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: statusPrefix + mic.timeText)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                Text(verbatim: mic.name)
                    .font(.headline)
                HStack(spacing: 6) {
                    CostBadge(cost: mic.cost)
                    if let neighborhood = TextRules.trimmedOrNil(mic.neighborhood) {
                        Text(verbatim: neighborhood)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    if mic.isBiweekly {
                        Text(verbatim: "🔄")
                            .font(.caption)
                            .accessibilityLabel("Biweekly")
                    }
                }
                if let deadline = deadline {
                    Text(verbatim: deadline.text)
                        .font(.caption.weight(deadline.isUrgent ? .semibold : .regular))
                        .foregroundStyle(deadline == .passed ? Color.secondary : Color.primary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .opacity(planStatus == .cancelled ? 0.6 : 1)
        .accessibilityElement(children: .combine)
    }

    private var statusPrefix: String {
        switch planStatus {
        case .going?: return "✅ "
        case .cancelled?: return "❌ "
        case nil: return ""
        }
    }
}
