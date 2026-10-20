import SwiftUI

// =============================================================================
// The top of the Calendar screen (spec 6.3):
//
//      ◀        This Week         ▶
//           Oct 20 – Oct 26, 2026
//   [Mon] [Tue] [Wed] [Thu] [Fri] [Sat] [Sun]   ← day strip with mic counts
// =============================================================================

/// ◀ title / date range ▶
@MainActor
struct CalendarWeekHeader: View {
    let week: CalendarWeek
    let onPrevious: () -> Void
    let onNext: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onPrevious) {
                Image(systemName: "chevron.left")
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 44)
            }
            .disabled(!week.canGoBack)
            .accessibilityLabel("Previous week")

            Spacer(minLength: 0)

            VStack(spacing: 2) {
                Text(verbatim: week.title)
                    .font(.headline)
                Text(verbatim: week.rangeText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)

            Spacer(minLength: 0)

            Button(action: onNext) {
                Image(systemName: "chevron.right")
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 44)
            }
            .disabled(!week.canGoForward)
            .accessibilityLabel("Next week")
        }
        .padding(.horizontal, 8)
    }
}

/// Seven tappable day chips, Monday → Sunday.
@MainActor
struct CalendarDayStrip: View {
    let week: CalendarWeek
    /// Filtered mic count per date.
    let counts: [CalendarDate: Int]
    let selectedDate: CalendarDate
    let onSelect: (CalendarDate) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(week.dates, id: \.self) { date in
                Button {
                    onSelect(date)
                } label: {
                    CalendarDayChip(
                        date: date,
                        count: counts[date] ?? 0,
                        isToday: week.isToday(date),
                        isPast: week.isPast(date),
                        isSelected: date == selectedDate
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }
}

/// One day in the strip: "Mon", "10/20" and the number of (filtered) mics.
/// Today is accent red; past days are dimmed; the selected day is filled.
@MainActor
struct CalendarDayChip: View {
    let date: CalendarDate
    let count: Int
    let isToday: Bool
    let isPast: Bool
    let isSelected: Bool

    var body: some View {
        VStack(spacing: 2) {
            Text(verbatim: date.weekday.shortName)
                .font(.caption.weight(.semibold))
            Text(verbatim: date.mmdd)
                .font(.caption2.monospacedDigit())
            Text(verbatim: "\(count)")
                .font(.caption2.weight(.bold).monospacedDigit())
                .padding(.horizontal, 6)
                .padding(.vertical, 1)
                .background(Capsule().fill(countBackground))
        }
        .foregroundStyle(textColor)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(chipFill)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(borderColor, lineWidth: isToday ? 2 : 1)
        )
        .opacity(isPast && !isSelected ? 0.45 : 1)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : [.isButton])
    }

    // MARK: Colors

    private var textColor: Color {
        if isToday && isSelected { return .white }
        if isToday { return Theme.accent }
        return .primary
    }

    private var chipFill: Color {
        if isToday && isSelected { return Theme.accent }
        if isSelected { return Color.primary.opacity(0.12) }
        return Color.clear
    }

    private var borderColor: Color {
        if isToday { return Theme.accent }
        if isSelected { return Color.primary.opacity(0.35) }
        return Color.primary.opacity(0.12)
    }

    private var countBackground: Color {
        if isToday && isSelected { return Color.white.opacity(0.25) }
        return Color.primary.opacity(0.08)
    }

    private var accessibilityText: String {
        var text = "\(date.weekday.name) \(date.mmdd), \(count) \(count == 1 ? "mic" : "mics")"
        if isToday { text += ", today" }
        return text
    }
}
