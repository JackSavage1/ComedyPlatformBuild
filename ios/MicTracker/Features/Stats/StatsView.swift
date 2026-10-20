import SwiftUI

// =============================================================================
// StatsView — the Stats tab (spec 6.7, a port of pages/3_📊_Dashboard.py).
//
// A scrolling column of cards, all computed from LOGGED sets:
//    1. Metrics: 🎤 Total Sets · ⭐ Avg Set Rating (+ month delta) ·
//       🔥 Week Streak · 🏠 Venues Visited
//    2. Set Rating Over Time (+ OLS trendline)
//    3. Crowd vs Set Rating
//    4. Sets by Day of Week
//    5. Sets by Neighborhood (top 10)
//    6. Top 5 Mics
//    7. Tag Frequency            ┐ only when some set has tags
//    8. Tags Over Time           ┘
//    9. My Mic Ratings table (scrolls sideways)
//   10. 💡 insight: highly rated mics you haven't visited in a while
//
// All the math lives in StatsSnapshot; this file is layout only.
// Contract: this view owns its NavigationStack (MainTabView doesn't wrap it).
// =============================================================================
@MainActor
struct StatsView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        // Build every series once per render so all cards agree.
        let snapshot = StatsSnapshot(
            sets: store.sets,
            micsByID: store.micsByID,
            activeMicCount: store.activeMics.count,
            today: CalendarDate.today()
        )

        NavigationStack {
            ScrollView {
                if !store.hasLoadedOnce && store.sets.isEmpty {
                    ProgressView("Loading your stats…")
                        .padding(.top, 60)
                        .frame(maxWidth: .infinity)
                } else if snapshot.isEmpty {
                    StatsEmptyState()
                } else {
                    cards(snapshot)
                }
            }
            .background(Theme.groupedBackground)
            .refreshable {
                await store.refreshAll()
            }
            .navigationTitle("Stats")
        }
    }

    // MARK: - Cards

    private func cards(_ snapshot: StatsSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            metricsGrid(snapshot)

            StatsCard(title: "📈 Set Rating Over Time", subtitle: trendSubtitle(snapshot)) {
                StatsRatingOverTimeChart(points: snapshot.ratingPoints, trend: snapshot.trend)
            }

            StatsCard(title: "👥 Crowd vs Set Rating", subtitle: "Bigger dots = bigger crowds") {
                StatsCrowdVsSetChart(points: snapshot.ratingPoints)
            }

            StatsCard(title: "📅 Sets by Day of Week") {
                StatsDayOfWeekChart(counts: snapshot.dayCounts)
            }

            StatsCard(title: "🏙️ Sets by Neighborhood", subtitle: "Top 10") {
                if snapshot.neighborhoodCounts.isEmpty {
                    placeholder("None of your mics has a neighborhood yet.")
                } else {
                    StatsHorizontalBarChart(
                        counts: snapshot.neighborhoodCounts,
                        accessibilityTitle: "Sets by neighborhood",
                        color: { _ in Theme.neutral }
                    )
                }
            }

            StatsCard(title: "🏆 Top 5 Mics", subtitle: "By number of visits") {
                if snapshot.topMics.isEmpty {
                    placeholder("No mic data yet.")
                } else {
                    StatsHorizontalBarChart(
                        counts: snapshot.topMics,
                        accessibilityTitle: "Top five mics by visits",
                        color: { _ in Theme.positive }
                    )
                }
            }

            tagCards(snapshot)

            StatsCard(title: "⭐ My Mic Ratings", subtitle: "Swipe sideways to see every column") {
                StatsMicRatingsTable(rows: snapshot.micRows)
            }

            if let insight = snapshot.insightText {
                insightCard(insight)
            }
        }
        .padding(16)
    }

    /// The four big numbers in a 2 × 2 grid.
    private func metricsGrid(_ snapshot: StatsSnapshot) -> some View {
        Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            GridRow {
                StatsMetricTile(title: "🎤 Total Sets", value: "\(snapshot.totalSets)")
                StatsMetricTile(
                    title: "⭐ Avg Set Rating",
                    value: snapshot.averageText,
                    caption: snapshot.deltaText,
                    captionColor: deltaColor(snapshot.ratingDelta)
                )
            }
            GridRow {
                StatsMetricTile(title: "🔥 Week Streak", value: "\(snapshot.weekStreak)")
                StatsMetricTile(title: "🏠 Venues Visited", value: snapshot.venuesText)
            }
        }
    }

    /// Tag Frequency + Tags Over Time, or a nudge to start tagging.
    @ViewBuilder
    private func tagCards(_ snapshot: StatsSnapshot) -> some View {
        if snapshot.tagCounts.isEmpty {
            StatsCard(title: "🏷️ Tag Analysis") {
                placeholder("No tags recorded yet. Start tagging your sets to see patterns emerge!")
            }
        } else {
            StatsCard(title: "🏷️ Tag Frequency", subtitle: "Green = good signs, red = rough nights") {
                StatsHorizontalBarChart(
                    counts: snapshot.tagCounts,
                    accessibilityTitle: "Tag frequency",
                    color: { item in Theme.tagColor(item.label) }
                )
            }
            StatsCard(title: "🗓️ Tags Over Time", subtitle: "Per month") {
                StatsTagsOverTimeChart(items: snapshot.tagsOverTime)
            }
        }
    }

    private func insightCard(_ text: String) -> some View {
        Text(verbatim: text)
            .font(.subheadline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Theme.neutral.opacity(0.15))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Theme.neutral.opacity(0.4), lineWidth: 1)
            )
    }

    private func placeholder(_ text: String) -> some View {
        Text(verbatim: text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
    }

    // MARK: - Helpers

    /// Green for an improvement, red for a dip, gray for no change.
    private func deltaColor(_ delta: Double?) -> Color {
        guard let delta = delta else { return .secondary }
        if delta > 0 { return Theme.positive }
        if delta < 0 { return Theme.negative }
        return .secondary
    }

    private func trendSubtitle(_ snapshot: StatsSnapshot) -> String {
        snapshot.trend == nil
            ? "Log sets on at least two different days to see your trendline"
            : "Dashed line = your overall trend"
    }
}

/// The dashboard's "No sets logged yet!" message (same copy as the web app).
@MainActor
struct StatsEmptyState: View {
    init() {}

    private let bullets = [
        "📈 Your improvement trajectory over time",
        "📊 Which venues and neighborhoods you frequent",
        "🏷️ Tag analysis (are you killing more and bombing less?)",
        "⭐ Your mic ratings and visit patterns",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(verbatim: "No sets logged yet!")
                .font(.title2.weight(.bold))
            Text(verbatim: "Hit a mic tonight and come back to log it.\nYour first data point is always the hardest. 🎤")
                .font(.body)
            Text(verbatim: "Once you start logging sets, this dashboard will show:")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 8) {
                ForEach(bullets, id: \.self) { bullet in
                    Text(verbatim: bullet)
                        .font(.subheadline)
                }
            }
            Text(verbatim: "Head to the My Sets tab to log your first performance!")
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(StatsStyle.cardFill)
        )
        .padding(16)
    }
}
