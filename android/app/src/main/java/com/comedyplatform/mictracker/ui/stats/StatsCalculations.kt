package com.comedyplatform.mictracker.ui.stats

import com.comedyplatform.mictracker.core.model.CrowdSize
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.SetRules
import com.comedyplatform.mictracker.core.rules.TagRules
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Everything the Stats tab (spec 6.7, a port of the web Dashboard) shows,
 * computed from LOGGED sets only — planned rows (no rating yet) would skew
 * every chart, exactly like the web app filters them out.
 *
 * Plain Kotlin with no Compose imports: the screen turns this into charts.
 */
object StatsCalculations {

    /** Label for sets whose mic has no venue (or whose mic row is missing). */
    const val UNKNOWN_VENUE = "Unknown venue"

    /** One dot on "Set Rating Over Time". */
    data class RatingPoint(val date: LocalDate, val rating: Int, val venue: String)

    /** One dot on "Crowd vs Set Rating"; [bubbleSize] is the web's marker size (10/20/35/50). */
    data class CrowdPoint(val crowdRating: Int, val setRating: Int, val bubbleSize: Int, val venue: String)

    /** A labelled count (a bar). */
    data class Count(val label: String, val count: Int)

    /** One tag's counts per month, aligned with [StatsData.tagMonths]. */
    data class TagMonthSeries(val tag: String, val counts: List<Int>)

    /** A row of the "My Mic Ratings" table. */
    data class MicRatingRow(
        val mic: String,
        val venue: String,
        /** The owner's 0–10 rating of the mic (open_mics.mic_rating), or null. */
        val myRating: Double?,
        val visits: Int,
        val avgSet: Double?,
        val avgCrowd: Double?,
        val lastVisit: LocalDate?,
    )

    data class StatsData(
        val totalSets: Int,
        /** "7.3/10". */
        val averageRatingText: String,
        /** Rule 12, or null when this month or last month has no sets. */
        val ratingDelta: Double?,
        /** Rule 11. */
        val weekStreak: Int,
        /** Distinct venues of logged sets. */
        val venuesVisited: Int,
        val activeMicCount: Int,
        /** Venues in color order (most sets first) — shared by both scatter charts' legends. */
        val venues: List<String>,
        val ratingPoints: List<RatingPoint>,
        /** Rule 13, or null with fewer than 2 distinct dates. */
        val trend: SetRules.TrendLine?,
        val crowdPoints: List<CrowdPoint>,
        /** Mon → Sun, zeros included. */
        val byDay: List<Count>,
        /** Top 10. */
        val byNeighborhood: List<Count>,
        /** Top 5 by visit count. */
        val topMics: List<Count>,
        /** Every tag, most used first. Empty when no set has tags. */
        val tagCounts: List<Count>,
        /** "2026-08", "2026-09", … — every month from the first to the last tagged set. */
        val tagMonths: List<String>,
        val tagSeries: List<TagMonthSeries>,
        /** Sorted by visits (most first). */
        val micRatings: List<MicRatingRow>,
        /** Rule from the web: mic_rating ≥ 7 and last visit more than 30 days ago. */
        val insightMics: List<String>,
    ) {
        val isEmpty: Boolean get() = totalSets == 0
        val hasTags: Boolean get() = tagCounts.isNotEmpty()
    }

    fun compute(sets: List<SetWithMic>, activeMicCount: Int, today: LocalDate): StatsData {
        val logged = sets.filter { it.set.isLogged }
        val entries = logged.map { it.set }
        val ratingRows = micRatings(logged)

        return StatsData(
            totalSets = logged.size,
            averageRatingText = SetRules.averageRatingText(entries),
            ratingDelta = SetRules.monthlyRatingDelta(entries, today),
            weekStreak = SetRules.weekStreakForSets(entries, today),
            venuesVisited = logged.mapNotNull { realVenue(it) }.distinct().size,
            activeMicCount = activeMicCount,
            venues = venueOrder(logged),
            ratingPoints = logged.mapNotNull { item ->
                val date = item.set.datePerformed ?: return@mapNotNull null
                val rating = item.set.setRating ?: return@mapNotNull null
                RatingPoint(date, rating, venueKey(item))
            },
            trend = SetRules.olsTrendForSets(entries),
            crowdPoints = logged.mapNotNull { item ->
                val crowd = item.set.crowdRating ?: return@mapNotNull null
                val rating = item.set.setRating ?: return@mapNotNull null
                CrowdPoint(crowd, rating, CrowdSize.bubbleSize(item.set.crowdSize), venueKey(item))
            },
            byDay = Weekdays.ordered.map { day ->
                Count(Weekdays.shortName(day), logged.count { it.mic?.weekday == day })
            },
            byNeighborhood = topCounts(logged.mapNotNull { TextRules.trimmedOrNull(it.mic?.neighborhood) }, limit = 10),
            topMics = topMicCounts(logged, limit = 5),
            tagCounts = topCounts(entries.flatMap { it.tagList }, limit = Int.MAX_VALUE),
            tagMonths = tagMonths(logged).map { DateRules.monthKey(it.atDay(1)) },
            tagSeries = tagSeries(logged),
            micRatings = ratingRows,
            insightMics = insightMics(ratingRows, today),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers (internal so they can be unit-tested on their own)
    // ---------------------------------------------------------------------

    /** The mic's venue, or null when unknown. */
    internal fun realVenue(item: SetWithMic): String? = TextRules.trimmedOrNull(item.mic?.venue)

    /** The venue used to color dots ("Unknown venue" when missing). */
    internal fun venueKey(item: SetWithMic): String = realVenue(item) ?: UNKNOWN_VENUE

    /** Venues ordered by number of sets (most first), ties alphabetically — the color order. */
    internal fun venueOrder(logged: List<SetWithMic>): List<String> =
        logged.map { venueKey(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }

    /** Counts of [labels], most first, ties alphabetically, at most [limit]. */
    internal fun topCounts(labels: List<String>, limit: Int): List<Count> =
        labels.groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(limit)
            .map { Count(it.key, it.value) }

    /** Logged sets per mic (grouped by mic id, labelled with the mic name). */
    internal fun topMicCounts(logged: List<SetWithMic>, limit: Int): List<Count> =
        logged.groupBy { it.set.openMicId }
            .map { (_, rows) -> Count(rows.first().micName, rows.size) }
            .sortedWith(compareByDescending<Count> { it.count }.thenBy { it.label.lowercase() })
            .take(limit)

    /** Every month from the first to the last set that has tags (empty when none do). */
    internal fun tagMonths(logged: List<SetWithMic>): List<YearMonth> {
        val months = logged
            .filter { it.set.tagList.isNotEmpty() }
            .mapNotNull { it.set.datePerformed?.let { date -> YearMonth.from(date) } }
        val first = months.minOrNull() ?: return emptyList()
        val last = months.maxOrNull() ?: return emptyList()
        val result = mutableListOf<YearMonth>()
        var cursor = first
        while (cursor <= last) {
            result += cursor
            cursor = cursor.plusMonths(1)
        }
        return result
    }

    /**
     * One series per tag (most used first) with its count in each month of
     * [tagMonths] — the data behind "Tags Over Time".
     */
    internal fun tagSeries(logged: List<SetWithMic>): List<TagMonthSeries> {
        val months = tagMonths(logged)
        if (months.isEmpty()) return emptyList()
        val order = topCounts(logged.flatMap { it.set.tagList }, limit = Int.MAX_VALUE).map { it.label }
        return order.map { tag ->
            val counts = months.map { month ->
                logged.count { item ->
                    val date = item.set.datePerformed
                    date != null && YearMonth.from(date) == month && tag in item.set.tagList
                }
            }
            TagMonthSeries(tag, counts)
        }
    }

    /** The "My Mic Ratings" table: one row per mic, most visited first. */
    internal fun micRatings(logged: List<SetWithMic>): List<MicRatingRow> =
        logged.groupBy { it.set.openMicId }
            .map { (_, rows) ->
                val first = rows.first()
                val crowd = rows.mapNotNull { it.set.crowdRating }
                MicRatingRow(
                    mic = first.micName,
                    venue = first.venue,
                    myRating = first.mic?.micRating,
                    visits = rows.size,
                    avgSet = rows.mapNotNull { it.set.setRating }.takeIf { it.isNotEmpty() }?.average(),
                    avgCrowd = if (crowd.isEmpty()) null else crowd.average(),
                    lastVisit = rows.mapNotNull { it.set.datePerformed }.maxOrNull(),
                )
            }
            .sortedWith(
                compareByDescending<MicRatingRow> { it.visits }
                    .thenByDescending { it.lastVisit ?: LocalDate.MIN }
                    .thenBy { it.mic.lowercase() },
            )

    /** Mics rated ≥ 7 whose last visit was more than 30 days ago. */
    internal fun insightMics(rows: List<MicRatingRow>, today: LocalDate): List<String> {
        val cutoff = today.minusDays(30)
        return rows.filter { row ->
            val rating = row.myRating
            val last = row.lastVisit
            rating != null && rating >= 7.0 && last != null && last.isBefore(cutoff)
        }.map { it.mic }.distinct()
    }

    // ---------------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------------

    /** "7.5", or "—" when missing. */
    fun oneDecimal(value: Double?): String =
        if (value == null) "—" else String.format(Locale.US, "%.1f", value)

    /** "10/20/2026", or "—". */
    fun dateOrDash(date: LocalDate?): String = if (date == null) "—" else DateRules.usDate(date)

    /** Tag order used for stable "Tags Over Time" colors: the fixed options first, then others. */
    fun tagColorIndex(tag: String): Int {
        val index = TagRules.options.indexOf(tag)
        return if (index >= 0) index else TagRules.options.size + (tag.hashCode() and 0x7fffffff) % 10
    }
}
