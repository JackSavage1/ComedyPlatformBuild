package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.model.CostKind
import com.comedyplatform.mictracker.core.model.CrowdSize
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.SignupMethod
import com.comedyplatform.mictracker.core.model.Urgency
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.GeoRules
import com.comedyplatform.mictracker.core.rules.MicRules
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class MicRulesTest {

    @Test
    fun rule1TimeDisplay() {
        assertEquals("7:00 PM", MicRules.timeText("7:00 PM", "19:00"))
        assertEquals("19:00", MicRules.timeText("  ", "19:00"))
        assertEquals("19:00", MicRules.timeText(null, "19:00"))
        assertEquals("", MicRules.timeText(null, null))
    }

    @Test
    fun rule2CostDisplayAndBadge() {
        assertEquals("$5", MicRules.costText("$5"))
        assertEquals("Free/TBD", MicRules.costText(null))
        assertEquals("Free/TBD", MicRules.costText("   "))

        assertEquals(CostKind.FREE, CostKind.of(null))
        assertEquals(CostKind.FREE, CostKind.of("FREE!"))
        assertEquals(CostKind.FREE, CostKind.of("Free w/ drink"))
        assertEquals(CostKind.DRINK_MIN, CostKind.of("1 drink min"))
        assertEquals(CostKind.PAID, CostKind.of("$5"))
        assertEquals("🍺", CostKind.DRINK_MIN.emoji)
        assertEquals("Drink min", CostKind.DRINK_MIN.label)
    }

    @Test
    fun rule3Urgency() {
        assertEquals(Urgency.HIGH, Urgency.from("high"))
        assertEquals(Urgency.MEDIUM, Urgency.from(" Medium "))
        assertEquals(Urgency.NORMAL, Urgency.from(null))
        assertEquals(Urgency.NORMAL, Urgency.from("super"))
        assertEquals("🔴", Urgency.HIGH.dot)
        assertEquals("Signs up fast — book ahead!", Urgency.HIGH.defaultNote)
        assertEquals("Book a few days early", Urgency.MEDIUM.defaultNote)
        assertNull(Urgency.NORMAL.defaultNote)

        val withNote = Mic(id = 1, urgency = "high", urgencyNote = "List opens at noon")
        assertEquals("List opens at noon", withNote.urgencyMessage)
        val withoutNote = Mic(id = 1, urgency = "medium")
        assertEquals("Book a few days early", withoutNote.urgencyMessage)
        assertNull(Mic(id = 1).urgencyMessage)
    }

    @Test
    fun rule10SetLength() {
        assertEquals("5 min sets", MicRules.setLengthText(5))
        assertEquals("Set length: N/A", MicRules.setLengthText(0))
        assertEquals("Set length: N/A", MicRules.setLengthText(null))
    }

    @Test
    fun rule19DisplayTimeFromPicker() {
        assertEquals("19:00", MicRules.startTimeString(19, 0))
        assertEquals("07:05", MicRules.startTimeString(7, 5))
        assertEquals("7:00 PM", MicRules.displayTimeString(19, 0))
        assertEquals("12:05 AM", MicRules.displayTimeString(0, 5))
        assertEquals("12:30 PM", MicRules.displayTimeString(12, 30))
        assertEquals("11:59 PM", MicRules.displayTimeString(23, 59))
        assertEquals(Pair(19, 30), MicRules.hourAndMinute("19:30"))
        assertNull(MicRules.hourAndMinute("late"))
    }

    @Test
    fun startTimeParsing() {
        assertEquals(19 * 60, MicRules.minutesSinceMidnight("19:00"))
        assertEquals(7 * 60 + 30, MicRules.minutesSinceMidnight("7:30"))
        assertEquals(19 * 60, MicRules.minutesSinceMidnight("19:00:00"))
        assertEquals(19 * 60 + 30, MicRules.minutesSinceMidnight("7:30 PM"))
        assertEquals(19 * 60, MicRules.minutesSinceMidnight("7pm"))
        assertEquals(0, MicRules.minutesSinceMidnight("12:00 AM"))
        assertEquals(12 * 60, MicRules.minutesSinceMidnight("12:00 pm"))
        assertNull(MicRules.minutesSinceMidnight("19"))
        assertNull(MicRules.minutesSinceMidnight("25:00"))
        assertNull(MicRules.minutesSinceMidnight("7:5"))
        assertNull(MicRules.minutesSinceMidnight("TBD"))
        assertNull(MicRules.minutesSinceMidnight(null))
    }

    @Test
    fun sortingByStartTimeThenName() {
        val mics = listOf(
            Mic(id = 1, name = "Zeta", startTime = "19:00"),
            Mic(id = 2, name = "alpha", startTime = "19:00"),
            Mic(id = 3, name = "Broken", startTime = "late"),
            Mic(id = 4, name = "Early", startTime = "6:30 PM"),
            Mic(id = 5, name = "Aardvark", startTime = null),
            Mic(id = 6, name = "Morning", startTime = "09:00"),
        )
        val sorted = MicRules.sortedByStartTime(mics).map { it.id }
        assertEquals(listOf(6, 4, 2, 1, 5, 3), sorted)
    }

    @Test
    fun sortingByDayThenTime() {
        val mics = listOf(
            Mic(id = 1, dayOfWeek = "Sunday", startTime = "18:00"),
            Mic(id = 2, dayOfWeek = "Monday", startTime = "21:00"),
            Mic(id = 3, dayOfWeek = "Someday", startTime = "18:00"),
            Mic(id = 4, dayOfWeek = "Monday", startTime = "18:00"),
        )
        assertEquals(listOf(4, 2, 1, 3), mics.sortedWith(MicRules.byDayThenTime).map { it.id })
    }

    @Test
    fun weekdayParsing() {
        assertEquals(DayOfWeek.MONDAY, Weekdays.parse("Monday"))
        assertEquals(DayOfWeek.MONDAY, Weekdays.parse(" monday "))
        assertEquals(DayOfWeek.TUESDAY, Weekdays.parse("Tue"))
        assertEquals(DayOfWeek.SUNDAY, Weekdays.parse("Sundays"))
        assertNull(Weekdays.parse("Funday"))
        assertEquals("Wednesday", Weekdays.fullName(DayOfWeek.WEDNESDAY))
        assertEquals("Thu", Weekdays.shortName(DayOfWeek.THURSDAY))
        assertEquals(7, Weekdays.fullNames.size)
    }

    @Test
    fun signupMethodsAndPrettify() {
        assertEquals("In Person", SignupMethod.displayText("in_person"))
        assertEquals("Instagram DM", SignupMethod.displayText("instagram_dm"))
        assertEquals("Walk Up List", SignupMethod.displayText("walk_up_list"))
        assertNull(SignupMethod.displayText("  "))
        assertEquals(SignupMethod.OTHER, SignupMethod.from("carrier pigeon"))
        assertEquals("In Person", TextRules.prettify("in_person"))
        assertEquals("", TextRules.prettify(null))
        assertEquals("Decent", CrowdSize.displayText("decent"))
        assertEquals(35, CrowdSize.bubbleSize("decent"))
        assertEquals(20, CrowdSize.bubbleSize("huge"))
        assertEquals(20, CrowdSize.bubbleSize(null))
    }

    @Test
    fun micComputedText() {
        val mic = Mic(
            id = 1, name = "Mic", dayOfWeek = "Monday", displayTime = "7:00 PM",
            neighborhood = "East Village", borough = "Manhattan",
        )
        assertEquals("Mondays at 7:00 PM", mic.scheduleText)
        assertEquals("East Village, Manhattan", mic.locationText)
        assertEquals("Mon", mic.shortDayText)
        assertEquals("Brooklyn", Mic(id = 2, borough = "Brooklyn").locationText)
    }

    @Test
    fun rule17BoroughColors() {
        assertEquals(0xFFFF6347, Boroughs.colorArgb("Manhattan"))
        assertEquals(0xFF1E90FF, Boroughs.colorArgb("brooklyn"))
        assertEquals(0xFF32CD32, Boroughs.colorArgb("Queens"))
        assertEquals(0xFFFFA500, Boroughs.colorArgb("Bronx"))
        assertEquals(0xFF9370DB, Boroughs.colorArgb("Staten Island"))
        assertEquals(0xFF808080, Boroughs.colorArgb("Hoboken"))
        assertEquals(0xFF808080, Boroughs.colorArgb(null))
    }

    @Test
    fun mostCommonBorough() {
        assertEquals("N/A", Boroughs.mostCommon(emptyList()))
        assertEquals("Brooklyn", Boroughs.mostCommon(listOf("Brooklyn", "Manhattan", "Brooklyn", null, " ")))
        assertEquals("Manhattan", Boroughs.mostCommon(listOf("Brooklyn", "Manhattan")))
    }

    @Test
    fun rule18NycBounds() {
        assertTrue(GeoRules.isInNyc(40.7128, -74.0060))
        assertFalse(GeoRules.isInNyc(41.2, -73.9))
        assertFalse(GeoRules.isInNyc(40.7, -74.5))
        assertFalse(GeoRules.isInNyc(40.4, -74.0)) // bounds are exclusive
        assertEquals(
            listOf("1 Main St, Brooklyn, New York, NY", "1 Main St, New York, NY"),
            GeoRules.geocodeQueries(" 1 Main St ", "Brooklyn"),
        )
        assertEquals(listOf("1 Main St, New York, NY"), GeoRules.geocodeQueries("1 Main St", null))
        assertTrue(GeoRules.geocodeQueries("  ", "Queens").isEmpty())
    }
}
