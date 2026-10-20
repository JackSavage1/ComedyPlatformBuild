package com.comedyplatform.mictracker.core.rules

import com.comedyplatform.mictracker.core.model.SetEntry
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Rules about logged sets: visits (rule 6), week streak (11), average-rating
 * delta (12), OLS trendline (13), rating emoji (15).
 *
 * A set is LOGGED when `set_rating != null`; a row with no rating is a
 * PLANNED (skeletal) row created by the Going button.
 */
object SetRules {

    // ---------------------------------------------------------------------
    // Rule 6: visits (logged sets only — FIX vs Streamlit, which counted plans)
    // ---------------------------------------------------------------------

    /** mic id → number of LOGGED sets there. "Been here N×" uses this. */
    fun loggedVisitCounts(sets: List<SetEntry>): Map<Int, Int> =
        sets.asSequence()
            .filter { it.isLogged }
            .mapNotNull { it.openMicId }
            .groupingBy { it }
            .eachCount()

    /** Ids of mics with ≥1 logged set. */
    fun visitedMicIds(sets: List<SetEntry>): Set<Int> = loggedVisitCounts(sets).keys

    // ---------------------------------------------------------------------
    // Rule 11: week streak
    // ---------------------------------------------------------------------

    /**
     * Consecutive ISO weeks (Monday start) with ≥1 logged set, counted
     * backwards from the current week. If the current week has none yet, start
     * at last week (a streak isn't broken on Monday morning). No sets → 0.
     *
     * Uses real date math (Monday minus 7 days), so it works across year
     * boundaries and 53-week years.
     */
    fun weekStreak(loggedDates: List<LocalDate>, today: LocalDate): Int {
        if (loggedDates.isEmpty()) return 0
        val weeks = loggedDates.map { DateRules.weekStart(it) }.toSet()
        var cursor = DateRules.weekStart(today)
        if (cursor !in weeks) cursor = cursor.minusWeeks(1)
        var streak = 0
        while (cursor in weeks) {
            streak += 1
            cursor = cursor.minusWeeks(1)
        }
        return streak
    }

    /** [weekStreak] for set rows (planned rows are ignored). */
    fun weekStreakForSets(sets: List<SetEntry>, today: LocalDate): Int =
        weekStreak(sets.filter { it.isLogged }.mapNotNull { it.datePerformed }, today)

    // ---------------------------------------------------------------------
    // Averages and rule 12: rating delta vs last month
    // ---------------------------------------------------------------------

    /** Average set rating of logged sets, or null when there are none. */
    fun averageRating(sets: List<SetEntry>): Double? {
        val ratings = sets.mapNotNull { it.setRating }
        return if (ratings.isEmpty()) null else ratings.average()
    }

    /** "7.3/10" (0.0/10 when there are no logged sets, like the web). */
    fun averageRatingText(sets: List<SetEntry>): String =
        String.format(Locale.US, "%.1f/10", averageRating(sets) ?: 0.0)

    /**
     * Rule 12: avg set rating of logged sets this calendar month minus last
     * calendar month, rounded to one decimal. null unless both months have sets.
     */
    fun monthlyRatingDelta(sets: List<SetEntry>, today: LocalDate): Double? {
        val thisMonth = YearMonth.from(today)
        val lastMonth = thisMonth.minusMonths(1)
        fun avgFor(month: YearMonth): Double? {
            val ratings = sets.filter { set ->
                val date = set.datePerformed
                set.setRating != null && date != null && YearMonth.from(date) == month
            }.mapNotNull { it.setRating }
            return if (ratings.isEmpty()) null else ratings.average()
        }
        val current = avgFor(thisMonth) ?: return null
        val previous = avgFor(lastMonth) ?: return null
        return roundToOneDecimal(current - previous)
    }

    /** "+0.4 vs last month" / "-1.2 vs last month". */
    fun deltaText(delta: Double): String = String.format(Locale.US, "%+.1f vs last month", delta)

    fun roundToOneDecimal(value: Double): Double = (value * 10.0).roundToLong() / 10.0

    // ---------------------------------------------------------------------
    // Rule 13: OLS trendline
    // ---------------------------------------------------------------------

    /** y = intercept + slope · x, where x = days since the first set. */
    data class TrendLine(val slope: Double, val intercept: Double, val firstDate: LocalDate) {
        fun valueAtDays(days: Double): Double = intercept + slope * days

        fun valueAt(date: LocalDate): Double =
            valueAtDays(ChronoUnit.DAYS.between(firstDate, date).toDouble())
    }

    /**
     * Ordinary least squares of rating over date. Needs ≥2 points with ≥2
     * distinct dates, otherwise null.
     */
    fun olsTrend(points: List<Pair<LocalDate, Double>>): TrendLine? {
        if (points.size < 2) return null
        if (points.map { it.first }.distinct().size < 2) return null
        val firstDate = points.minOf { it.first }
        val xs = points.map { ChronoUnit.DAYS.between(firstDate, it.first).toDouble() }
        val ys = points.map { it.second }
        val meanX = xs.average()
        val meanY = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - meanX) * (ys[i] - meanY)
            sxx += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        return TrendLine(slope = slope, intercept = meanY - slope * meanX, firstDate = firstDate)
    }

    /** [olsTrend] for logged set rows. */
    fun olsTrendForSets(sets: List<SetEntry>): TrendLine? =
        olsTrend(
            sets.mapNotNull { set ->
                val date = set.datePerformed ?: return@mapNotNull null
                val rating = set.setRating ?: return@mapNotNull null
                Pair(date, rating.toDouble())
            },
        )

    // ---------------------------------------------------------------------
    // Rule 15: rating emoji
    // ---------------------------------------------------------------------

    /** planned 📋; ≥8 🔥; ≥5 👍; else 😬. */
    fun ratingEmoji(setRating: Int?): String = when {
        setRating == null -> "📋"
        setRating >= 8 -> "🔥"
        setRating >= 5 -> "👍"
        else -> "😬"
    }

    /** "Set: 8/10 · Crowd: 6/10" or "PLANNED — tap to log details". */
    fun summaryText(set: SetEntry): String {
        val rating = set.setRating ?: return "PLANNED — tap to log details"
        val crowd = set.crowdRating?.toString() ?: "?"
        return "Set: $rating/10 · Crowd: $crowd/10"
    }
}
