package com.comedyplatform.mictracker.core.rules

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Calendar-date math and the date formats used across the app.
 *
 * Everything works on [LocalDate] (a calendar date with no time zone), so a
 * date never shifts by a day through UTC. "Today" is passed in by the caller
 * (the device's local date) so these functions are easy to test.
 * Formats are English (Locale.US) to match the web app.
 */
object DateRules {

    private val fullDateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
    private val monthDayFormat = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
    private val usDateFormat = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
    private val shortMonthDayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val shortMonthDayYearFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val monthKeyFormat = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)

    /** Monday of the week containing [date] (weeks start Monday). */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Rule 16: [weekday]'s next date from [today] — today itself if it matches. */
    fun nextOccurrence(weekday: DayOfWeek, today: LocalDate): LocalDate =
        today.with(TemporalAdjusters.nextOrSame(weekday))

    /** The date of [weekday] inside the week that starts on [monday]. */
    fun dateInWeek(monday: LocalDate, weekday: DayOfWeek): LocalDate =
        monday.plusDays(Weekdays.ordered.indexOf(weekday).toLong())

    /** The 7 dates Mon → Sun of the week starting [monday]. */
    fun weekDates(monday: LocalDate): List<LocalDate> = (0L..6L).map { monday.plusDays(it) }

    // ---------------------------------------------------------------------
    // Calendar week navigation (spec 6.3). Offset range −1 … +3.
    // ---------------------------------------------------------------------

    const val MIN_WEEK_OFFSET = -1
    const val MAX_WEEK_OFFSET = 3

    /** Monday of the week [offset] weeks away from the week containing [today]. */
    fun mondayForOffset(today: LocalDate, offset: Int): LocalDate = weekStart(today).plusWeeks(offset.toLong())

    /** "Last Week" (−1) / "This Week" (0) / "Next Week" (1) / "N Weeks Out". */
    fun weekTitle(offset: Int): String = when {
        offset < 0 -> "Last Week"
        offset == 0 -> "This Week"
        offset == 1 -> "Next Week"
        else -> "$offset Weeks Out"
    }

    /** "Oct 20 – Oct 26, 2026" for the week starting [monday]. */
    fun weekRangeText(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        return "${monday.format(shortMonthDayFormat)} – ${sunday.format(shortMonthDayYearFormat)}"
    }

    // ---------------------------------------------------------------------
    // Formats
    // ---------------------------------------------------------------------

    /** "Monday, October 20, 2026" (the Tonight header). */
    fun fullDate(date: LocalDate): String = date.format(fullDateFormat)

    /** "10/20". */
    fun monthDay(date: LocalDate): String = date.format(monthDayFormat)

    /** "10/20/2026". */
    fun usDate(date: LocalDate): String = date.format(usDateFormat)

    /** "Mon". */
    fun shortWeekday(date: LocalDate): String = Weekdays.shortName(date.dayOfWeek)

    /** "Monday". */
    fun weekdayName(date: LocalDate): String = Weekdays.fullName(date.dayOfWeek)

    /** "2026-10" — the month bucket used by "Tags Over Time". */
    fun monthKey(date: LocalDate): String = date.format(monthKeyFormat)

    /** The calendar month containing [date]. */
    fun yearMonth(date: LocalDate): YearMonth = YearMonth.from(date)

    /** "yyyy-MM-dd" — the format sent to and received from Supabase. */
    fun iso(date: LocalDate): String = date.toString()
}
