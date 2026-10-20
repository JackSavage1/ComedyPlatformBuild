package com.comedyplatform.mictracker.ui.sets

import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.SetWithMic
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.SetRules
import com.comedyplatform.mictracker.core.rules.TagRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The small, pure calculations behind My Sets (spec 6.6). Plain Kotlin with
 * no Android or Compose imports, so they're easy to read and to test.
 *
 * The business rules themselves (visits, streak, emoji, tags…) live in
 * :core — these functions only arrange data for this screen.
 */
object SetsRules {

    // ---------------------------------------------------------------------
    // Log a Set
    // ---------------------------------------------------------------------

    /** Planned (skeletal) rows, OLDEST first — the "Ready to Log" list. */
    fun plannedOldestFirst(sets: List<SetWithMic>): List<SetWithMic> =
        sets.filter { it.set.isPlanned }
            .sortedWith(
                compareBy<SetWithMic> { it.set.datePerformed ?: LocalDate.MAX }
                    .thenBy { it.set.id },
            )

    /** "📝 Log: Mic @ Venue — 10/20/2026". */
    fun plannedLabel(item: SetWithMic): String = "📝 Log: ${item.micAndVenue} — ${item.set.dateText}"

    /** The picker label: "Mon — 7:00 PM — Name @ Venue" (parts that are missing are skipped). */
    fun micPickerLabel(mic: Mic): String {
        val parts = mutableListOf<String>()
        if (mic.shortDayText.isNotEmpty()) parts += mic.shortDayText
        if (mic.timeText.isNotEmpty()) parts += mic.timeText
        parts += if (mic.venueName.isEmpty()) mic.displayName else "${mic.displayName} @ ${mic.venueName}"
        return parts.joinToString(" — ")
    }

    /** A titled group of mics in the picker ("Monday" … "Sunday", then "Other days"). */
    data class MicGroup(val title: String, val mics: List<Mic>)

    /**
     * Active mics grouped by weekday Mon→Sun (spec 6.6), each group sorted by
     * start time. Mics whose day can't be parsed go into a final "Other days"
     * group. Empty groups are dropped.
     */
    fun micPickerGroups(activeMics: List<Mic>, query: String = ""): List<MicGroup> {
        val matching = activeMics.filter { matchesSearch(it, query) }
        val groups = mutableListOf<MicGroup>()
        for (day in Weekdays.ordered) {
            val mics = matching.filter { it.weekday == day }.sortedWith(MicRules.byStartTime)
            if (mics.isNotEmpty()) groups += MicGroup(Weekdays.fullName(day), mics)
        }
        val unknown = matching.filter { it.weekday == null }.sortedBy { it.displayName.lowercase() }
        if (unknown.isNotEmpty()) groups += MicGroup("Other days", unknown)
        return groups
    }

    /**
     * Case-insensitive search over name, venue, neighborhood, borough and day.
     * Every word of the query must match somewhere ("tues brooklyn" works).
     */
    fun matchesSearch(mic: Mic, query: String): Boolean {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOfNotNull(
            mic.name,
            mic.venue,
            mic.neighborhood,
            mic.borough,
            mic.dayOfWeek,
            mic.timeText,
        ).joinToString(" ").lowercase()
        return words.all { haystack.contains(it) }
    }

    private val longDateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    /** "Mon, Oct 20, 2026" — the Date performed button. */
    fun longDate(date: LocalDate): String = date.format(longDateFormat)

    /**
     * Material's DatePicker works in "UTC midnight" milliseconds. Converting
     * with UTC on both sides keeps the calendar date exact (spec 3.5: never
     * let a date shift through a time zone).
     */
    fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The inverse of [toPickerMillis]. */
    fun fromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /** Spec 6.6: the date picker's max is today, so a future (planned) date is clamped to today. */
    fun clampToToday(date: LocalDate, today: LocalDate): LocalDate = if (date.isAfter(today)) today else date

    /** Adds [tag] if missing, removes it if present — keeping the fixed option order (rule 14). */
    fun toggleTag(current: List<String>, tag: String): List<String> {
        if (tag in current) return current.filterNot { it == tag }
        val wanted = current + tag
        // Known options first, in the web form's order, then any unknown tags from an older row.
        val known = TagRules.options.filter { it in wanted }
        val unknown = wanted.filter { it !in TagRules.options }
        return known + unknown
    }

    // ---------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------

    /** The four numbers at the top of the History tab. */
    data class HistorySummary(
        /** "12" or "12 (+2 planned)". */
        val totalSets: String,
        /** "7.3/10". */
        val averageRating: String,
        /** "Mic (5×)" or "N/A". */
        val mostVisited: String,
        /** "3 weeks". */
        val weekStreak: String,
    )

    fun summary(sets: List<SetWithMic>, today: LocalDate): HistorySummary {
        val entries = sets.map { it.set }
        val loggedCount = entries.count { it.isLogged }
        val plannedCount = entries.size - loggedCount
        val total = if (plannedCount > 0) "$loggedCount (+$plannedCount planned)" else "$loggedCount"
        return HistorySummary(
            totalSets = total,
            averageRating = SetRules.averageRatingText(entries),
            mostVisited = mostVisitedText(sets),
            weekStreak = "${SetRules.weekStreakForSets(entries, today)} weeks",
        )
    }

    /**
     * Rule 6 applied to the "Most Visited" metric: the mic with the most LOGGED
     * sets, as "Name (N×)". Ties go to the alphabetically first name. "N/A" when
     * nothing has been logged.
     */
    fun mostVisitedText(sets: List<SetWithMic>): String {
        val counts = SetRules.loggedVisitCounts(sets.map { it.set })
        if (counts.isEmpty()) return "N/A"
        val nameById = sets.mapNotNull { item -> item.set.openMicId?.let { it to item.micName } }.toMap()
        val best = counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { it.value }
                    .thenBy { (nameById[it.key] ?: "").lowercase() },
            )
            .first()
        return "${nameById[best.key] ?: "Unknown mic"} (${best.value}×)"
    }

    /** Mic names present in the history, sorted — the options after "All" in the mic filter. */
    fun micFilterOptions(sets: List<SetWithMic>): List<String> =
        sets.map { it.micName }.distinct().sortedBy { it.lowercase() }

    /** Every tag used in the history, sorted. */
    fun tagFilterOptions(sets: List<SetWithMic>): List<String> =
        sets.flatMap { it.set.tagList }.distinct().sorted()

    /**
     * The History filters (spec 6.6):
     *  - [micName]: null = All, else exact mic name
     *  - [minRating]: 1–10; PLANNED rows are always shown
     *  - [tags]: keep rows having ANY of these (empty = no tag filter)
     */
    fun filterHistory(
        sets: List<SetWithMic>,
        micName: String?,
        minRating: Int,
        tags: Set<String>,
    ): List<SetWithMic> = sets.filter { item ->
        val rating = item.set.setRating
        (micName == null || item.micName == micName) &&
            (rating == null || rating >= minRating) &&
            (tags.isEmpty() || item.set.tagList.any { it in tags })
    }

    /** "Showing 1 set" / "Showing 12 sets". */
    fun showingText(count: Int): String = if (count == 1) "Showing 1 set" else "Showing $count sets"

    /** "Edit" banner text for a set loaded into the form. */
    fun editingText(item: SetWithMic?): String =
        if (item == null) "Editing a logged set." else "Editing your set at ${item.micAndVenue} on ${item.set.dateText}."

    /** "Planned" banner text for a planned row loaded into the form. */
    fun fillingText(item: SetWithMic?): String =
        if (item == null) {
            "Filling in details for your planned set."
        } else {
            "Filling in details for your planned set at ${item.micAndVenue} on ${item.set.dateText}."
        }
}
