import Charts
import SwiftUI

// =============================================================================
// StatsCharts — the Swift Charts views on the Stats tab (spec 6.7 cards 2–8).
//
// Each chart takes ready-made series from `StatsSnapshot` and only decides
// how to draw them. Swift Charts basics, if you haven't used it:
//
//     Chart(items) { item in
//         BarMark(x: .value("Day", item.label), y: .value("Sets", item.count))
//     }
//
// `.value("Label", x)` names a value for the axes and VoiceOver. Marks with
// `.foregroundStyle(by: .value("Venue", …))` are colored per group and get a
// legend automatically.
// =============================================================================

// MARK: - Card 2: Set Rating Over Time

/// Scatter of set ratings by date, colored by venue, with the OLS trendline.
@MainActor
struct StatsRatingOverTimeChart: View {
    let points: [StatsRatingPoint]
    let trend: StatsTrendSegment?

    init(points: [StatsRatingPoint], trend: StatsTrendSegment?) {
        self.points = points
        self.trend = trend
    }

    /// The trendline's two ends (empty when there's no trend).
    private var trendEnds: [StatsTrendEnd] {
        guard let trend = trend else { return [] }
        return [
            StatsTrendEnd(id: 0, date: trend.start.date(), value: trend.startValue),
            StatsTrendEnd(id: 1, date: trend.end.date(), value: trend.endValue),
        ]
    }

    var body: some View {
        Chart {
            ForEach(points) { point in
                PointMark(
                    x: .value("Date", point.date.date()),
                    y: .value("Set rating", point.setRating)
                )
                .foregroundStyle(by: .value("Venue", point.venue))
            }
            // Drawn as its own series so it never joins up with the dots.
            ForEach(trendEnds) { end in
                LineMark(
                    x: .value("Date", end.date),
                    y: .value("Set rating", end.value),
                    series: .value("Series", "Trend")
                )
                .foregroundStyle(Theme.accent)
                .lineStyle(StrokeStyle(lineWidth: 2, dash: [6, 4]))
            }
        }
        .chartYScale(domain: 0.0...10.0)
        .chartYAxis {
            AxisMarks(values: .stride(by: 2))
        }
        .chartLegend(position: .bottom, alignment: .leading)
        .frame(height: 260)
        .accessibilityLabel("Set rating over time")
    }
}

/// One end of the trendline.
struct StatsTrendEnd: Identifiable {
    let id: Int
    let date: Date
    let value: Double
}

// MARK: - Card 3: Crowd vs Set Rating

/// Crowd rating (x) against set rating (y); bigger dots = bigger crowds.
@MainActor
struct StatsCrowdVsSetChart: View {
    let points: [StatsRatingPoint]

    init(points: [StatsRatingPoint]) {
        self.points = points
    }

    /// Only sets that have a crowd rating can be placed on this chart.
    private var plottable: [StatsRatingPoint] {
        points.filter { $0.crowdRating != nil }
    }

    var body: some View {
        Chart {
            ForEach(plottable) { point in
                PointMark(
                    x: .value("Crowd rating", point.crowdRating ?? 0),
                    y: .value("Set rating", point.setRating)
                )
                .foregroundStyle(by: .value("Venue", point.venue))
                // symbolSize is an area in points²: 10 → 40 (small) … 50 → 200 (big).
                .symbolSize(CGFloat(point.crowdWeight * 4))
                .opacity(0.8)
            }
        }
        .chartXScale(domain: 0.0...10.0)
        .chartYScale(domain: 0.0...10.0)
        .chartXAxis {
            AxisMarks(values: .stride(by: 2))
        }
        .chartYAxis {
            AxisMarks(values: .stride(by: 2))
        }
        .chartXAxisLabel("Crowd rating")
        .chartYAxisLabel("Set rating")
        .chartLegend(position: .bottom, alignment: .leading)
        .frame(height: 260)
        .accessibilityLabel("Crowd rating versus set rating")
    }
}

// MARK: - Card 4: Sets by Day of Week

/// Vertical bars Monday → Sunday.
@MainActor
struct StatsDayOfWeekChart: View {
    let counts: [StatsCount]

    init(counts: [StatsCount]) {
        self.counts = counts
    }

    var body: some View {
        let step = StatsSnapshot.countAxisStep(maxCount: counts.map { $0.count }.max() ?? 0)
        Chart(counts) { item in
            BarMark(
                x: .value("Day", item.label),
                y: .value("Sets", item.count)
            )
            .foregroundStyle(Theme.accent)
        }
        .chartYAxis {
            AxisMarks(values: .stride(by: step))
        }
        .frame(height: 200)
        .accessibilityLabel("Sets by day of week")
    }
}

// MARK: - Cards 5, 6, 7: horizontal bar charts

/// Horizontal bars, biggest first, with the count printed at the end of
/// each bar. Used for neighborhoods, top mics and tag frequency.
@MainActor
struct StatsHorizontalBarChart: View {
    let counts: [StatsCount]
    /// Read by VoiceOver for the whole chart ("Sets by neighborhood").
    let accessibilityTitle: String
    /// The color of each bar (lets Tag Frequency use rule 14 colors).
    let color: (StatsCount) -> Color

    init(counts: [StatsCount], accessibilityTitle: String, color: @escaping (StatsCount) -> Color) {
        self.counts = counts
        self.accessibilityTitle = accessibilityTitle
        self.color = color
    }

    var body: some View {
        let step = StatsSnapshot.countAxisStep(maxCount: counts.map { $0.count }.max() ?? 0)
        Chart(counts) { item in
            BarMark(
                x: .value("Count", item.count),
                y: .value("Name", item.label)
            )
            .foregroundStyle(color(item))
            .annotation(position: .trailing) {
                Text(verbatim: "\(item.count)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .chartXAxis {
            AxisMarks(values: .stride(by: step))
        }
        // About 30 points per bar keeps labels readable.
        .frame(height: CGFloat(max(counts.count, 1)) * 30 + 30)
        .accessibilityLabel(Text(verbatim: accessibilityTitle))
    }
}

// MARK: - Card 8: Tags Over Time

/// Stacked bars: one bar per month, one colored segment per tag.
@MainActor
struct StatsTagsOverTimeChart: View {
    let items: [StatsTagMonth]

    init(items: [StatsTagMonth]) {
        self.items = items
    }

    var body: some View {
        let step = StatsSnapshot.countAxisStep(maxCount: StatsSnapshot.largestMonthTotal(items))
        Chart(items) { item in
            BarMark(
                x: .value("Month", item.month),
                y: .value("Count", item.count)
            )
            .foregroundStyle(by: .value("Tag", item.tag))
        }
        .chartYAxis {
            AxisMarks(values: .stride(by: step))
        }
        .chartLegend(position: .bottom, alignment: .leading)
        .frame(height: 240)
        .accessibilityLabel("Tags over time")
    }
}
