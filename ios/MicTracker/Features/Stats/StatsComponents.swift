import SwiftUI
import UIKit

// =============================================================================
// Small building blocks for the Stats tab (and the History summary on the
// My Sets tab): a card container, a big-number tile, and the horizontally
// scrolling "My Mic Ratings" table.
// =============================================================================

/// Colors for cards that sit on the grouped (gray) background. These match
/// a grouped List's row color in both light and dark mode.
enum StatsStyle {
    static let cardFill = Color(uiColor: .secondarySystemGroupedBackground)
    static let tileFill = Color(uiColor: .tertiarySystemGroupedBackground)
}

/// A rounded card with a title, used for every chart on the Stats tab.
@MainActor
struct StatsCard<Content: View>: View {
    let title: String
    let subtitle: String?
    let content: Content

    init(title: String, subtitle: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle = subtitle {
                    Text(verbatim: subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(StatsStyle.cardFill)
        )
    }
}

/// A big number with a small title, like Streamlit's `st.metric`:
///
///     ⭐ Avg Set Rating
///     7.4/10
///     +0.4 vs last month
@MainActor
struct StatsMetricTile: View {
    let title: String
    let value: String
    let caption: String?
    let captionColor: Color
    let fill: Color

    init(title: String, value: String, caption: String? = nil, captionColor: Color = .secondary, fill: Color = StatsStyle.cardFill) {
        self.title = title
        self.value = value
        self.caption = caption
        self.captionColor = captionColor
        self.fill = fill
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(verbatim: value)
                .font(.title3.weight(.semibold))
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            if let caption = caption {
                Text(verbatim: caption)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(captionColor)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(fill)
        )
        .accessibilityElement(children: .combine)
    }
}

/// "My Mic Ratings" — a table wider than the phone, so it scrolls sideways
/// (spec 6.7 card 9).
@MainActor
struct StatsMicRatingsTable: View {
    let rows: [StatsMicRow]

    init(rows: [StatsMicRow]) {
        self.rows = rows
    }

    /// Column titles and widths, left to right.
    private let columns: [(title: String, width: CGFloat)] = [
        ("Mic", 150),
        ("Venue", 130),
        ("My Rating", 72),
        ("Visits", 52),
        ("Avg Set", 62),
        ("Avg Crowd", 74),
        ("Last Visit", 86),
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 8) {
                GridRow {
                    ForEach(columns.indices, id: \.self) { index in
                        Text(verbatim: columns[index].title)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .frame(width: columns[index].width, alignment: .leading)
                    }
                }
                Divider()
                ForEach(rows) { row in
                    GridRow {
                        cell(row.name, column: 0, weight: .semibold)
                        cell(row.venue, column: 1)
                        cell(row.myRatingText, column: 2)
                        cell("\(row.visits)", column: 3)
                        cell(row.averageSetText, column: 4)
                        cell(row.averageCrowdText, column: 5)
                        cell(row.lastVisitText, column: 6)
                    }
                }
            }
            .padding(.bottom, 8)
        }
    }

    private func cell(_ text: String, column: Int, weight: Font.Weight = .regular) -> some View {
        Text(verbatim: text)
            .font(.caption.weight(weight))
            .lineLimit(2)
            .frame(width: columns[column].width, alignment: .leading)
    }
}
