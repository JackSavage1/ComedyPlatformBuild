package com.comedyplatform.mictracker.ui.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.core.model.CrowdSize
import com.comedyplatform.mictracker.core.rules.SetRules
import com.comedyplatform.mictracker.ui.components.Callout
import com.comedyplatform.mictracker.ui.components.CalloutTone
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import com.comedyplatform.mictracker.ui.theme.AppColors
import com.comedyplatform.mictracker.ui.theme.tagColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Stats (spec 6.7) — a port of the web Performance Dashboard, using LOGGED
 * sets only. Scrolling cards:
 *
 *   1  metrics: Total Sets · Avg Set Rating (+ delta) · Week Streak · Venues Visited
 *   2  Set Rating Over Time (scatter by venue + OLS trendline)
 *   3  Crowd vs Set Rating (dot size = crowd size)
 *   4  Sets by Day of Week        5  Sets by Neighborhood     6  Top 5 Mics
 *   7  Tag Frequency              8  Tags Over Time (stacked bars by month)
 *   9  My Mic Ratings table      10  "rated highly but not visited lately" insight
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val viewModel = appViewModel { container ->
        StatsViewModel(repository = container.micRepository, today = { container.today() })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("📊 Stats") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ErrorBanner(message = state.error, onDismiss = viewModel::dismissError)

            RefreshableBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val data = state.data
                when {
                    !state.hasLoaded && state.error == null -> LoadingBox(message = "Crunching your numbers…")

                    !state.hasLoaded -> ScrollableMessage {
                        EmptyState(
                            title = "Couldn't load your stats",
                            message = "Check your connection, then pull down or tap Retry.",
                            action = { Button(onClick = viewModel::refresh) { Text("Retry") } },
                        )
                    }

                    data == null || data.isEmpty -> ScrollableMessage { StatsEmptyState() }

                    else -> StatsContent(data)
                }
            }
        }
    }
}

/** Keeps pull-to-refresh working on a non-list screen. */
@Composable
private fun ScrollableMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

/** The web dashboard's empty-state copy. */
@Composable
private fun StatsEmptyState() {
    EmptyState(
        title = "No sets logged yet!",
        message = "Hit a mic tonight and come back to log it.\nYour first data point is always the hardest. 🎤",
        action = {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Once you start logging sets, this dashboard will show:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                listOf(
                    "📈 Your improvement trajectory over time",
                    "📊 Which venues and neighborhoods you frequent",
                    "🏷️ Tag analysis (are you killing more and bombing less?)",
                    "⭐ Your mic ratings and visit patterns",
                ).forEach { line ->
                    Text(text = "•  $line", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Head to the My Sets tab to log your first performance!",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

@Composable
private fun StatsContent(data: StatsCalculations.StatsData) {
    // One color per venue, shared by both scatter charts (most-visited venue first).
    val venueColors: Map<String, Color> = remember(data.venues) {
        data.venues.mapIndexed { index, venue -> venue to paletteColor(index) }.toMap()
    }
    val venueLegend = remember(data.venues) {
        data.venues.mapIndexed { index, venue -> LegendEntry(venue, paletteColor(index)) }
    }
    val trendColor = MaterialTheme.colorScheme.onSurface

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // ---- 1. Metrics ------------------------------------------------------------
        item(key = "metrics") { MetricsGrid(data) }

        // ---- 2. Set Rating Over Time ------------------------------------------------
        item(key = "rating-over-time") {
            StatsCard(
                title = "📈 Set Rating Over Time",
                subtitle = if (data.trend == null) "Log sets on at least 2 different days to see your trend line." else null,
            ) {
                RatingOverTimeChart(data, venueColors, trendColor)
                ChartLegend(
                    entries = venueLegend +
                        if (data.trend != null) listOf(LegendEntry("Trend (OLS)", trendColor, isLine = true)) else emptyList(),
                )
            }
        }

        // ---- 3. Crowd vs Set Rating -----------------------------------------------------
        item(key = "crowd-vs-set") {
            StatsCard(title = "👥 Crowd vs Set Rating", subtitle = "Bigger dots = bigger crowds") {
                if (data.crowdPoints.isEmpty()) {
                    Text("No crowd ratings yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    CrowdVsSetChart(data, venueColors)
                    ChartLegend(entries = venueLegend)
                }
            }
        }

        // ---- 4. Sets by Day of Week ------------------------------------------------------
        item(key = "by-day") {
            StatsCard(title = "📅 Sets by Day of Week") {
                VerticalBarChart(bars = intensityBars(data.byDay, AppColors.Accent))
            }
        }

        // ---- 5. Sets by Neighborhood ---------------------------------------------------------
        item(key = "by-neighborhood") {
            StatsCard(title = "🗽 Sets by Neighborhood", subtitle = "Top 10") {
                if (data.byNeighborhood.isEmpty()) {
                    Text("Your mics don't have neighborhoods yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    HorizontalBarChart(bars = intensityBars(data.byNeighborhood, AppColors.Info))
                }
            }
        }

        // ---- 6. Top 5 Mics --------------------------------------------------------------------------
        item(key = "top-mics") {
            StatsCard(title = "🏆 Top 5 Mics", subtitle = "By number of visits") {
                HorizontalBarChart(bars = intensityBars(data.topMics, AppColors.Success))
            }
        }

        // ---- 7 + 8. Tags -------------------------------------------------------------------------------
        if (data.hasTags) {
            item(key = "tag-frequency") {
                StatsCard(title = "🏷️ Tag Frequency") {
                    VerticalBarChart(
                        bars = data.tagCounts.map { ChartBar(it.label, it.count.toDouble(), tagColor(it.label)) },
                    )
                }
            }
            item(key = "tags-over-time") {
                StatsCard(title = "🗓️ Tags Over Time", subtitle = "Tagged sets per month") {
                    val series = data.tagSeries.map { s ->
                        StackedSeries(
                            name = s.tag,
                            color = paletteColor(StatsCalculations.tagColorIndex(s.tag)),
                            values = s.counts.map { it.toDouble() },
                        )
                    }
                    StackedBarChart(categories = data.tagMonths, series = series)
                    ChartLegend(entries = series.map { LegendEntry(it.name, it.color) })
                }
            }
        } else {
            item(key = "no-tags") {
                Callout(
                    text = "🏷️ No tags recorded yet. Start tagging your sets to see patterns emerge!",
                    tone = CalloutTone.INFO,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        // ---- 9. My Mic Ratings ------------------------------------------------------------------------
        item(key = "mic-ratings") {
            StatsCard(title = "⭐ My Mic Ratings", subtitle = "Swipe sideways to see every column") {
                MicRatingsTable(data.micRatings)
            }
        }

        // ---- 10. Insight -----------------------------------------------------------------------------
        if (data.insightMics.isNotEmpty()) {
            item(key = "insight") {
                Callout(
                    text = "💡 Mics you rated highly but haven't visited in 30+ days: " +
                        data.insightMics.joinToString(", "),
                    tone = CalloutTone.INFO,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 1. Metrics
// -----------------------------------------------------------------------------

@Composable
private fun MetricsGrid(data: StatsCalculations.StatsData) {
    val delta = data.ratingDelta
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(label = "🎤 Total Sets", value = "${data.totalSets}", modifier = Modifier.weight(1f))
            MetricTile(
                label = "⭐ Avg Set Rating",
                value = data.averageRatingText,
                caption = delta?.let { SetRules.deltaText(it) },
                captionColor = when {
                    delta == null -> Color.Unspecified
                    delta >= 0.0 -> AppColors.Success
                    else -> AppColors.Danger
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(label = "🔥 Week Streak", value = "${data.weekStreak}", modifier = Modifier.weight(1f))
            MetricTile(
                label = "🏠 Venues Visited",
                value = "${data.venuesVisited} / ${data.activeMicCount}",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 2 + 3. Scatter charts
// -----------------------------------------------------------------------------

private val shortDateFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val monthYearFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

/** 0–10 rating axis with a little headroom so a 10 isn't cut in half. */
private fun ratingAxis(): ChartAxis = ChartAxis(
    min = 0.0,
    max = 10.5,
    ticks = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0),
)

@Composable
private fun RatingOverTimeChart(
    data: StatsCalculations.StatsData,
    venueColors: Map<String, Color>,
    trendColor: Color,
) {
    val points = data.ratingPoints
    if (points.isEmpty()) {
        Text("No dated sets yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val chart = remember(data, venueColors, trendColor) {
        val days = points.map { it.date.toEpochDay().toDouble() }
        val firstDay = days.min()
        val lastDay = days.max()
        val span = lastDay - firstDay
        val pad = max(2.0, span * 0.05)
        val formatter = if (span > 300) monthYearFormat else shortDateFormat
        val ticks = ChartMath.evenlySpaced(firstDay, lastDay, if (span < 1.0) 1 else 4)
            .map { it.roundToLong().toDouble() }
            .distinct()
        val xAxis = ChartAxis(
            min = firstDay - pad,
            max = lastDay + pad,
            ticks = ticks,
            label = { day -> LocalDate.ofEpochDay(day.roundToLong()).format(formatter) },
        )
        val series = data.venues.mapNotNull { venue ->
            val venuePoints = points.filter { it.venue == venue }
                .map { ScatterPoint(x = it.date.toEpochDay().toDouble(), y = it.rating.toDouble()) }
            if (venuePoints.isEmpty()) null else ScatterSeries(venue, venueColors[venue] ?: paletteColor(0), venuePoints)
        }
        // Rule 13: y = intercept + slope · (days since the first set), drawn across the data's dates.
        val line = data.trend?.let { trend ->
            ChartLine(
                startX = firstDay,
                startY = trend.valueAtDays(firstDay - trend.firstDate.toEpochDay()),
                endX = lastDay,
                endY = trend.valueAtDays(lastDay - trend.firstDate.toEpochDay()),
                color = trendColor,
            )
        }
        Triple(xAxis, series, line)
    }
    ScatterChart(series = chart.second, xAxis = chart.first, yAxis = ratingAxis(), line = chart.third)
}

@Composable
private fun CrowdVsSetChart(data: StatsCalculations.StatsData, venueColors: Map<String, Color>) {
    val series = remember(data, venueColors) {
        data.venues.mapNotNull { venue ->
            val venuePoints = data.crowdPoints.filter { it.venue == venue }.map { point ->
                ScatterPoint(
                    x = point.crowdRating.toDouble(),
                    y = point.setRating.toDouble(),
                    radius = bubbleRadius(point.bubbleSize),
                )
            }
            if (venuePoints.isEmpty()) null else ScatterSeries(venue, venueColors[venue] ?: paletteColor(0), venuePoints)
        }
    }
    ScatterChart(series = series, xAxis = ratingAxis(), yAxis = ratingAxis())
    Text(
        text = "Across: crowd rating · Up: set rating · Dot size: " +
            CrowdSize.entries.joinToString(" < ") { it.label.lowercase() },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The web's marker sizes (empty 10 … packed 50) scaled to a dot radius in dp. */
private fun bubbleRadius(bubbleSize: Int): Dp = (3f + bubbleSize * 0.17f).dp

// -----------------------------------------------------------------------------
// 4–6. Bars
// -----------------------------------------------------------------------------

/**
 * Bars in one hue whose strength follows the value — like the web's
 * "Reds" / "Blues" / "Greens" color scales.
 */
private fun intensityBars(counts: List<StatsCalculations.Count>, color: Color): List<ChartBar> {
    val maxCount = counts.maxOfOrNull { it.count } ?: 0
    return counts.map { count ->
        val strength = if (maxCount == 0) 0f else count.count.toFloat() / maxCount
        ChartBar(count.label, count.count.toDouble(), color.copy(alpha = 0.4f + 0.6f * strength))
    }
}

// -----------------------------------------------------------------------------
// 9. My Mic Ratings table
// -----------------------------------------------------------------------------

private class TableColumn(val title: String, val width: Dp)

private val ratingColumns = listOf(
    TableColumn("Mic", 160.dp),
    TableColumn("Venue", 140.dp),
    TableColumn("My Rating", 84.dp),
    TableColumn("Visits", 60.dp),
    TableColumn("Avg Set", 72.dp),
    TableColumn("Avg Crowd", 84.dp),
    TableColumn("Last Visit", 96.dp),
)

/** Mic · Venue · My Rating · Visits · Avg Set · Avg Crowd · Last Visit, scrollable sideways. */
@Composable
private fun MicRatingsTable(rows: List<StatsCalculations.MicRatingRow>) {
    val totalWidth = ratingColumns.fold(0.dp) { sum, column -> sum + column.width }
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            ratingColumns.forEach { column ->
                TableCell(text = column.title, width = column.width, header = true)
            }
        }
        HorizontalDivider(modifier = Modifier.width(totalWidth))
        rows.forEach { row ->
            val cells = listOf(
                row.mic,
                row.venue.ifEmpty { "—" },
                StatsCalculations.oneDecimal(row.myRating),
                "${row.visits}",
                StatsCalculations.oneDecimal(row.avgSet),
                StatsCalculations.oneDecimal(row.avgCrowd),
                StatsCalculations.dateOrDash(row.lastVisit),
            )
            Row {
                cells.forEachIndexed { index, text ->
                    TableCell(text = text, width = ratingColumns[index].width, header = false)
                }
            }
            HorizontalDivider(modifier = Modifier.width(totalWidth), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TableCell(text: String, width: Dp, header: Boolean) {
    Text(
        text = text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}
