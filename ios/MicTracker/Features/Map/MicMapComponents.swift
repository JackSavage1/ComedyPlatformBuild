import SwiftUI

// =============================================================================
// Small views used by MicMapView (spec 6.5): the pin, filter chips, the
// zoom-preset bar, the stats strip, the borough legend and the card shown
// for the tapped mic.
// =============================================================================

/// A map pin: a circle in the mic's borough color (rule 17).
@MainActor
struct MicMapPinView: View {
    let color: Color
    let isSelected: Bool

    var body: some View {
        let size: CGFloat = isSelected ? 26 : 16
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .overlay(Circle().stroke(Color.white, lineWidth: isSelected ? 3 : 2))
            .shadow(color: Color.black.opacity(0.35), radius: isSelected ? 4 : 2, x: 0, y: 1)
            // A little invisible padding makes small pins easier to tap.
            .padding(6)
            .contentShape(Circle())
    }
}

/// A toggle chip ("Mon", "Brooklyn", …). Filled when on.
@MainActor
struct MicMapFilterChip: View {
    let title: String
    var dotColor: Color?
    let isOn: Bool
    let action: () -> Void

    init(title: String, dotColor: Color? = nil, isOn: Bool, action: @escaping () -> Void) {
        self.title = title
        self.dotColor = dotColor
        self.isOn = isOn
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let dotColor = dotColor {
                    Circle()
                        .fill(dotColor)
                        .frame(width: 8, height: 8)
                }
                Text(verbatim: title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .foregroundStyle(isOn ? Color.white : Color.primary)
            .background(Capsule().fill(isOn ? Theme.accent : Color.primary.opacity(0.08)))
            .overlay(Capsule().stroke(isOn ? Theme.accent : Color.primary.opacity(0.15), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// "All NYC · Manhattan · Brooklyn · Queens/Bronx". Unlike a segmented
/// Picker, tapping the already-highlighted preset still moves the camera
/// back (handy after panning around).
@MainActor
struct MicMapPresetBar: View {
    /// The highlighted preset (nil after the user pans away).
    let selected: MicMapZoomPreset?
    let onSelect: (MicMapZoomPreset) -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(MicMapZoomPreset.allCases) { preset in
                let isOn = preset == selected
                Button {
                    onSelect(preset)
                } label: {
                    Text(verbatim: preset.title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .foregroundStyle(isOn ? Color.white : Color.primary)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(isOn ? Theme.accent : Color.primary.opacity(0.08))
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Zoom to \(preset.title)")
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        }
    }
}

/// "12 Mics on map · 8 Neighborhoods · 3 Boroughs"
@MainActor
struct MicMapStatsStrip: View {
    let micCount: Int
    let neighborhoodCount: Int
    let boroughCount: Int

    var body: some View {
        HStack(spacing: 8) {
            QuickStatTile(title: "Mics on map", value: "\(micCount)", alignment: .center)
            Divider().frame(height: 30)
            QuickStatTile(title: "Neighborhoods", value: "\(neighborhoodCount)", alignment: .center)
            Divider().frame(height: 30)
            QuickStatTile(title: "Boroughs", value: "\(boroughCount)", alignment: .center)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// The borough color key (rule 17), plus "Other" when some mic needs it.
@MainActor
struct MicMapLegend: View {
    let showsOther: Bool

    var body: some View {
        FlowLayout(spacing: 10, lineSpacing: 6) {
            ForEach(BoroughRules.all, id: \.self) { borough in
                legendItem(borough, color: Theme.boroughColor(borough))
            }
            if showsOther {
                legendItem("Other", color: Theme.boroughColor(nil))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Legend: pin colors by borough")
    }

    private func legendItem(_ name: String, color: Color) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 10, height: 10)
            Text(verbatim: name)
                .font(.caption)
        }
    }
}

/// The card for the tapped mic: name, venue, "Mondays at 7:00 PM", cost,
/// neighborhood (or borough), other mics at the same spot, and Details →
/// Mic Detail for the mic's next occurrence (rule 16).
@MainActor
struct MicMapSelectedCard: View {
    let mic: Mic
    /// Next occurrence of the mic's weekday (nil if its day is unreadable).
    let nextDate: CalendarDate?
    /// Other visible mics at the same location.
    let neighbors: [Mic]
    let today: CalendarDate
    let onSelect: (Int) -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Circle()
                    .fill(Theme.boroughColor(mic.borough))
                    .frame(width: 12, height: 12)
                    .padding(.top, 5)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: mic.name)
                        .font(.headline)
                        .lineLimit(2)
                    if !mic.venue.isEmpty {
                        Text(verbatim: "@ \(mic.venue)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }

            Label {
                Text(verbatim: mic.scheduleText)
            } icon: {
                Image(systemName: "clock")
            }
            .font(.subheadline)

            HStack(spacing: 8) {
                CostBadge(cost: mic.cost, showsCostText: true)
                if let place = placeText {
                    Text(verbatim: place)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            if !neighbors.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Also at this spot")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(neighbors) { other in
                                Button {
                                    onSelect(other.id)
                                } label: {
                                    Text(verbatim: neighborLabel(other))
                                        .font(.caption)
                                        .lineLimit(1)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(Capsule().fill(Color.primary.opacity(0.08)))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }

            NavigationLink {
                MicDetailView(micID: mic.id, date: nextDate)
            } label: {
                HStack {
                    Text("Details")
                        .font(.headline)
                    if let nextDate = nextDate {
                        Text(verbatim: nextDateText(nextDate))
                            .font(.subheadline)
                            .opacity(0.85)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                }
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: Color.black.opacity(0.15), radius: 8, x: 0, y: 2)
    }

    /// Neighborhood, else borough (like the web map's tooltip).
    private var placeText: String? {
        TextRules.trimmedOrNil(mic.neighborhood) ?? TextRules.trimmedOrNil(mic.borough)
    }

    /// "Tue · Late Show"
    private func neighborLabel(_ other: Mic) -> String {
        let day = other.weekday?.shortName ?? TextRules.trimmedOrNil(other.dayOfWeek) ?? "?"
        return "\(day) · \(other.name)"
    }

    /// "· tonight", "· tomorrow" or "· Thu 10/23"
    private func nextDateText(_ date: CalendarDate) -> String {
        if date == today { return "· tonight" }
        if date == today.adding(days: 1) { return "· tomorrow" }
        return "· \(date.weekdayAndMMDD)"
    }
}
