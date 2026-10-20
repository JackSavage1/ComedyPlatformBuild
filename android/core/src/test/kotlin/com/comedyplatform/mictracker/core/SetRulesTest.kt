package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.model.SetEntry
import com.comedyplatform.mictracker.core.rules.DateRules
import com.comedyplatform.mictracker.core.rules.SetRules
import com.comedyplatform.mictracker.core.rules.TagRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class SetRulesTest {

    private fun logged(id: Int, micId: Int, date: LocalDate, rating: Int, crowd: Int? = null, tags: String? = null) =
        SetEntry(id = id, openMicId = micId, datePerformed = date, setRating = rating, crowdRating = crowd, tags = tags)

    private fun planned(id: Int, micId: Int, date: LocalDate) =
        SetEntry(id = id, openMicId = micId, datePerformed = date)

    // ---------------------------------------------------------------- rule 6

    @Test
    fun visitsCountLoggedSetsOnly() {
        val day = LocalDate.of(2026, 10, 1)
        val sets = listOf(
            logged(1, 10, day, 7),
            logged(2, 10, day.plusDays(7), 8),
            planned(3, 10, day.plusDays(14)),
            planned(4, 11, day),
            logged(5, 12, day, 3),
        )
        assertEquals(mapOf(10 to 2, 12 to 1), SetRules.loggedVisitCounts(sets))
        assertEquals(setOf(10, 12), SetRules.visitedMicIds(sets))
    }

    // ---------------------------------------------------------------- rule 11

    @Test
    fun streakIsZeroWithoutSets() {
        assertEquals(0, SetRules.weekStreak(emptyList(), LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun streakCountsConsecutiveWeeksIncludingThisWeek() {
        val today = LocalDate.of(2026, 10, 22) // Thursday
        val dates = listOf(
            LocalDate.of(2026, 10, 20), // this week
            LocalDate.of(2026, 10, 14), // last week
            LocalDate.of(2026, 10, 5), // two weeks ago
            LocalDate.of(2026, 9, 21), // gap (four weeks ago) — not counted
        )
        assertEquals(3, SetRules.weekStreak(dates, today))
    }

    @Test
    fun streakStartsLastWeekWhenThisWeekHasNoSetYet() {
        val mondayMorning = LocalDate.of(2026, 10, 19)
        val dates = listOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 10))
        assertEquals(2, SetRules.weekStreak(dates, mondayMorning))
    }

    @Test
    fun streakIsZeroWhenLastSetWasTwoWeeksAgo() {
        val today = LocalDate.of(2026, 10, 22)
        assertEquals(0, SetRules.weekStreak(listOf(LocalDate.of(2026, 10, 8)), today))
    }

    @Test
    fun streakAcrossYearBoundaryAnd53WeekYear() {
        // 2026 has ISO week 53 (Dec 28, 2026 – Jan 3, 2027). The Streamlit
        // dashboard wrapped from week 1 to week 52 and broke the streak here.
        val today = LocalDate.of(2027, 1, 6) // Wednesday, ISO 2027-W01
        val dates = listOf(
            LocalDate.of(2027, 1, 5), // 2027-W01
            LocalDate.of(2026, 12, 30), // 2026-W53
            LocalDate.of(2026, 12, 22), // 2026-W52
            LocalDate.of(2026, 12, 15), // 2026-W51
        )
        assertEquals(4, SetRules.weekStreak(dates, today))
    }

    @Test
    fun streakIgnoresPlannedRows() {
        val today = LocalDate.of(2026, 10, 22)
        val sets = listOf(planned(1, 1, LocalDate.of(2026, 10, 20)), logged(2, 1, LocalDate.of(2026, 10, 13), 6))
        assertEquals(1, SetRules.weekStreakForSets(sets, today))
    }

    // ---------------------------------------------------------------- rule 12

    @Test
    fun monthlyDeltaNeedsBothMonths() {
        val today = LocalDate.of(2026, 10, 20)
        val sets = listOf(
            logged(1, 1, LocalDate.of(2026, 10, 2), 8),
            logged(2, 1, LocalDate.of(2026, 10, 9), 7),
            logged(3, 1, LocalDate.of(2026, 9, 30), 6),
            logged(4, 1, LocalDate.of(2026, 9, 1), 8),
            planned(5, 1, LocalDate.of(2026, 10, 15)),
        )
        // 7.5 − 7.0
        assertEquals(0.5, SetRules.monthlyRatingDelta(sets, today)!!, 0.0001)
        assertEquals("+0.5 vs last month", SetRules.deltaText(0.5))
        assertEquals("-1.2 vs last month", SetRules.deltaText(-1.2))
        assertNull(SetRules.monthlyRatingDelta(sets.filter { it.datePerformed!!.monthValue == 10 }, today))
    }

    @Test
    fun monthlyDeltaInJanuaryComparesWithDecember() {
        val today = LocalDate.of(2027, 1, 10)
        val sets = listOf(logged(1, 1, LocalDate.of(2027, 1, 3), 9), logged(2, 1, LocalDate.of(2026, 12, 28), 6))
        assertEquals(3.0, SetRules.monthlyRatingDelta(sets, today)!!, 0.0001)
    }

    @Test
    fun averageRatingText() {
        val day = LocalDate.of(2026, 10, 1)
        assertEquals("0.0/10", SetRules.averageRatingText(emptyList()))
        assertEquals("7.5/10", SetRules.averageRatingText(listOf(logged(1, 1, day, 7), logged(2, 1, day, 8))))
    }

    // ---------------------------------------------------------------- rule 13

    @Test
    fun olsNeedsTwoDistinctDates() {
        val day = LocalDate.of(2026, 10, 1)
        assertNull(SetRules.olsTrend(listOf(Pair(day, 5.0))))
        assertNull(SetRules.olsTrend(listOf(Pair(day, 5.0), Pair(day, 7.0))))
    }

    @Test
    fun olsFitsAPerfectLine() {
        val day = LocalDate.of(2026, 10, 1)
        val trend = SetRules.olsTrend(
            listOf(Pair(day, 4.0), Pair(day.plusDays(2), 5.0), Pair(day.plusDays(4), 6.0)),
        )
        assertNotNull(trend)
        assertEquals(0.5, trend!!.slope, 1e-9)
        assertEquals(4.0, trend.intercept, 1e-9)
        assertEquals(6.0, trend.valueAt(day.plusDays(4)), 1e-9)
    }

    @Test
    fun olsLeastSquares() {
        val day = LocalDate.of(2026, 1, 1)
        // x = 0,1,2,3  y = 1,3,2,4  → slope 0.8, intercept 1.3
        val trend = SetRules.olsTrend(
            listOf(Pair(day, 1.0), Pair(day.plusDays(1), 3.0), Pair(day.plusDays(2), 2.0), Pair(day.plusDays(3), 4.0)),
        )!!
        assertEquals(0.8, trend.slope, 1e-9)
        assertEquals(1.3, trend.intercept, 1e-9)
    }

    @Test
    fun olsForSetsIgnoresPlanned() {
        val day = LocalDate.of(2026, 1, 1)
        val sets = listOf(logged(1, 1, day, 5), planned(2, 1, day.plusDays(3)), logged(3, 1, day.plusDays(10), 7))
        val trend = SetRules.olsTrendForSets(sets)!!
        assertEquals(0.2, trend.slope, 1e-9)
    }

    // ---------------------------------------------------------------- rule 14

    @Test
    fun tagsParseJoinAndColor() {
        assertEquals(listOf("killed", "crowd_work"), TagRules.parse(" killed, ,crowd_work ,"))
        assertEquals(emptyList<String>(), TagRules.parse(null))
        assertEquals("killed,crowd_work", TagRules.join(listOf("killed", " crowd_work ", "killed")))
        assertNull(TagRules.join(emptyList()))
        assertEquals(TagRules.Tone.POSITIVE, TagRules.tone("killed"))
        assertEquals(TagRules.Tone.POSITIVE, TagRules.tone("got_spotted"))
        assertEquals(TagRules.Tone.NEGATIVE, TagRules.tone("bombed"))
        assertEquals(TagRules.Tone.NEGATIVE, TagRules.tone("nervous"))
        assertEquals(TagRules.Tone.NEUTRAL, TagRules.tone("new_bit"))
        assertEquals(TagRules.Tone.NEUTRAL, TagRules.tone("something_else"))
        assertEquals(0xFF2ECC71, TagRules.colorArgb("confident"))
        assertEquals(0xFFE74C3C, TagRules.colorArgb("bombed"))
        assertEquals(0xFF3498DB, TagRules.colorArgb("long_set"))
        assertEquals(10, TagRules.options.size)
        assertEquals("Crowd Work", TagRules.label("crowd_work"))
    }

    // ---------------------------------------------------------------- rule 15

    @Test
    fun ratingEmoji() {
        assertEquals("📋", SetRules.ratingEmoji(null))
        assertEquals("🔥", SetRules.ratingEmoji(8))
        assertEquals("🔥", SetRules.ratingEmoji(10))
        assertEquals("👍", SetRules.ratingEmoji(5))
        assertEquals("👍", SetRules.ratingEmoji(7))
        assertEquals("😬", SetRules.ratingEmoji(4))
        assertEquals("😬", SetRules.ratingEmoji(1))
    }

    @Test
    fun summaryText() {
        val day = LocalDate.of(2026, 1, 1)
        assertEquals("Set: 8/10 · Crowd: 6/10", SetRules.summaryText(logged(1, 1, day, 8, crowd = 6)))
        assertEquals("Set: 8/10 · Crowd: ?/10", SetRules.summaryText(logged(1, 1, day, 8)))
        assertEquals("PLANNED — tap to log details", SetRules.summaryText(planned(2, 1, day)))
    }

    // ---------------------------------------------------------------- rule 16 + calendar helpers

    @Test
    fun nextOccurrence() {
        val tuesday = LocalDate.of(2026, 10, 20)
        assertEquals(tuesday, DateRules.nextOccurrence(DayOfWeek.TUESDAY, tuesday))
        assertEquals(LocalDate.of(2026, 10, 26), DateRules.nextOccurrence(DayOfWeek.MONDAY, tuesday))
        assertEquals(LocalDate.of(2026, 10, 25), DateRules.nextOccurrence(DayOfWeek.SUNDAY, tuesday))
        assertEquals(LocalDate.of(2027, 1, 1), DateRules.nextOccurrence(DayOfWeek.FRIDAY, LocalDate.of(2026, 12, 30)))
    }

    @Test
    fun weekHelpers() {
        val tuesday = LocalDate.of(2026, 10, 20)
        assertEquals(LocalDate.of(2026, 10, 19), DateRules.weekStart(tuesday))
        assertEquals(LocalDate.of(2026, 10, 19), DateRules.weekStart(LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 10, 12), DateRules.mondayForOffset(tuesday, -1))
        assertEquals(LocalDate.of(2026, 11, 9), DateRules.mondayForOffset(tuesday, 3))
        assertEquals("Last Week", DateRules.weekTitle(-1))
        assertEquals("This Week", DateRules.weekTitle(0))
        assertEquals("Next Week", DateRules.weekTitle(1))
        assertEquals("3 Weeks Out", DateRules.weekTitle(3))
        assertEquals("Oct 19 – Oct 25, 2026", DateRules.weekRangeText(LocalDate.of(2026, 10, 19)))
        assertEquals("Dec 28 – Jan 3, 2027", DateRules.weekRangeText(LocalDate.of(2026, 12, 28)))
        assertEquals(7, DateRules.weekDates(LocalDate.of(2026, 10, 19)).size)
        assertEquals(LocalDate.of(2026, 10, 25), DateRules.dateInWeek(LocalDate.of(2026, 10, 19), DayOfWeek.SUNDAY))
    }

    @Test
    fun dateFormats() {
        val date = LocalDate.of(2026, 10, 20)
        assertEquals("Tuesday, October 20, 2026", DateRules.fullDate(date))
        assertEquals("10/20", DateRules.monthDay(date))
        assertEquals("10/20/2026", DateRules.usDate(date))
        assertEquals("Tue", DateRules.shortWeekday(date))
        assertEquals("2026-10", DateRules.monthKey(date))
        assertEquals("2026-10-20", DateRules.iso(date))
    }
}
