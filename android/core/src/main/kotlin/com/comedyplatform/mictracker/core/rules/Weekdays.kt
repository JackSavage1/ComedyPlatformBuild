package com.comedyplatform.mictracker.core.rules

import java.time.DayOfWeek

/**
 * Converting between the database's `day_of_week` text ("Monday") and
 * java.time's [DayOfWeek]. Weeks start on Monday (spec 3.5).
 *
 * Names are hard-coded English (not the phone's locale) because that's what
 * the database stores and what the web app shows.
 */
object Weekdays {

    /** Monday → Sunday. */
    val ordered: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

    /** "Monday" … "Sunday" — the values stored in `open_mics.day_of_week`. */
    val fullNames: List<String> = ordered.map { fullName(it) }

    fun fullName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }

    /** "Mon", "Tue", … */
    fun shortName(day: DayOfWeek): String = fullName(day).substring(0, 3)

    /** "Mondays" — used in "Mondays at 7:00 PM". */
    fun pluralName(day: DayOfWeek): String = fullName(day) + "s"

    /**
     * Parses "Monday", " monday ", "MON", "Mon." → MONDAY. Anything else → null.
     */
    fun parse(raw: String?): DayOfWeek? {
        val cleaned = TextRules.trimmedOrNull(raw)?.lowercase()?.trimEnd('.') ?: return null
        return ordered.firstOrNull { day ->
            val full = fullName(day).lowercase()
            cleaned == full || cleaned == full.substring(0, 3) || cleaned == full + "s"
        }
    }

    /** 0 for Monday … 6 for Sunday; unknown days sort last (7). */
    fun sortIndex(raw: String?): Int = parse(raw)?.let { ordered.indexOf(it) } ?: 7
}
