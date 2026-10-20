package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.rules.CostFilter
import com.comedyplatform.mictracker.core.rules.FilterRules
import com.comedyplatform.mictracker.core.rules.InstagramRules
import com.comedyplatform.mictracker.core.rules.MicFilters
import com.comedyplatform.mictracker.core.rules.SignupDeadline
import com.comedyplatform.mictracker.core.rules.SignupFilter
import com.comedyplatform.mictracker.core.rules.SignupRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SignupAndFilterRulesTest {

    // ---------------------------------------------------------------- rule 4

    @Test
    fun costFilterFreeOnly() {
        assertTrue(CostFilter.FREE_ONLY.matches(null))
        assertTrue(CostFilter.FREE_ONLY.matches("Free"))
        assertTrue(CostFilter.FREE_ONLY.matches("FREE w/ 1 drink"))
        assertFalse(CostFilter.FREE_ONLY.matches("$5"))
        assertFalse(CostFilter.FREE_ONLY.matches("1 drink min"))
    }

    @Test
    fun costFilterUnderSixFixesTheTenDollarBug() {
        assertTrue(CostFilter.UNDER_6.matches(null))
        assertTrue(CostFilter.UNDER_6.matches("   "))
        assertTrue(CostFilter.UNDER_6.matches("Free"))
        assertTrue(CostFilter.UNDER_6.matches("$5"))
        assertTrue(CostFilter.UNDER_6.matches("$5.50 + 1 drink"))
        assertTrue(CostFilter.UNDER_6.matches("$0"))
        // FIX: Streamlit's regex `\$[1-5]` matched "$10" and "$15".
        assertFalse(CostFilter.UNDER_6.matches("$10"))
        assertFalse(CostFilter.UNDER_6.matches("$15 or 2 drinks"))
        assertFalse(CostFilter.UNDER_6.matches("$6"))
        assertFalse(CostFilter.UNDER_6.matches("$6.00"))
        // First amount wins.
        assertFalse(CostFilter.UNDER_6.matches("$10 ($5 with student id)"))
        assertTrue(CostFilter.UNDER_6.matches("$5 ($10 at the door)"))
        // No dollar amount, not free → not under $6.
        assertFalse(CostFilter.UNDER_6.matches("1 drink min"))
        assertFalse(CostFilter.UNDER_6.matches("TBD"))
    }

    @Test
    fun costFilterDrinkAndAll() {
        assertTrue(CostFilter.DRINK_MIN_ONLY.matches("1 Drink Min"))
        assertFalse(CostFilter.DRINK_MIN_ONLY.matches(null))
        assertFalse(CostFilter.DRINK_MIN_ONLY.matches("$5"))
        assertTrue(CostFilter.ALL.matches(null))
        assertTrue(CostFilter.ALL.matches("$100"))
        assertEquals("Under $6", CostFilter.UNDER_6.label)
    }

    @Test
    fun firstDollarAmount() {
        assertEquals(5.5, FilterRules.firstDollarAmount("cost: $5.50")!!, 0.0001)
        assertEquals(10.0, FilterRules.firstDollarAmount("$10 or $5")!!, 0.0001)
        assertNull(FilterRules.firstDollarAmount("five dollars"))
    }

    // ---------------------------------------------------------------- rule 5

    @Test
    fun signupFilter() {
        assertTrue(SignupFilter.ALL.matches(null))
        assertTrue(SignupFilter.ONLINE.matches("online"))
        assertTrue(SignupFilter.IN_PERSON.matches(" in_person "))
        assertTrue(SignupFilter.INSTAGRAM_DM.matches("instagram_dm"))
        assertFalse(SignupFilter.EMAIL.matches("online"))
        assertFalse(SignupFilter.EMAIL.matches(null))
    }

    @Test
    fun micFiltersCombine() {
        val mics = listOf(
            Mic(id = 1, borough = "Manhattan", neighborhood = "East Village", cost = "Free", signupMethod = "online"),
            Mic(id = 2, borough = "Brooklyn", neighborhood = "Bushwick", cost = "$10", signupMethod = "in_person"),
            Mic(id = 3, borough = "Brooklyn", neighborhood = "Williamsburg", cost = null, signupMethod = "online"),
        )
        val visited = setOf(1)
        val going = setOf(3)

        assertEquals(listOf(1, 2, 3), MicFilters().apply(mics, visited, going).map { it.id })
        assertEquals(listOf(2, 3), MicFilters(boroughs = setOf("Brooklyn")).apply(mics, visited, going).map { it.id })
        assertEquals(listOf(1, 3), MicFilters(cost = CostFilter.UNDER_6).apply(mics, visited, going).map { it.id })
        assertEquals(listOf(2, 3), MicFilters(onlyUnvisited = true).apply(mics, visited, going).map { it.id })
        assertEquals(listOf(3), MicFilters(onlyGoing = true).apply(mics, visited, going).map { it.id })
        assertEquals(
            listOf(3),
            MicFilters(signup = SignupFilter.ONLINE, neighborhoods = setOf("Williamsburg")).apply(mics, visited, going).map { it.id },
        )
        assertEquals(3, MicFilters(boroughs = setOf("Bronx"), cost = CostFilter.FREE_ONLY, onlyGoing = true).activeCount)
        assertEquals(listOf("Bushwick", "Williamsburg"), MicFilters.neighborhoodOptions(mics, setOf("Brooklyn")))
        assertEquals(3, MicFilters.neighborhoodOptions(mics, emptySet()).size)
    }

    // ---------------------------------------------------------------- rule 7

    private val today = LocalDate.of(2026, 10, 20) // a Tuesday

    @Test
    fun deadlineOnlyWithAdvanceDays() {
        assertNull(SignupDeadline.compute(null, today.plusDays(5), today))
        assertNull(SignupDeadline.compute(0, today.plusDays(5), today))
    }

    @Test
    fun deadlinePassedTodayTomorrowUpcoming() {
        // signupBy = micDate − advanceDays
        assertEquals(SignupDeadline.Passed, SignupDeadline.compute(3, today.plusDays(2), today))
        assertEquals(SignupDeadline.Today, SignupDeadline.compute(3, today.plusDays(3), today))
        assertEquals(SignupDeadline.Tomorrow, SignupDeadline.compute(3, today.plusDays(4), today))
        val upcoming = SignupDeadline.compute(2, LocalDate.of(2026, 10, 25), today)
        assertEquals(SignupDeadline.Upcoming(LocalDate.of(2026, 10, 23), 3), upcoming)
        assertEquals("📅 Sign up by Fri 10/23 (3 days)", upcoming!!.text)
        assertEquals("⚠️ Signup deadline passed!", SignupDeadline.Passed.text)
        assertEquals("🔴 Sign up TODAY!", SignupDeadline.Today.text)
        assertEquals("🟡 Sign up by TOMORROW!", SignupDeadline.Tomorrow.text)
        assertTrue(SignupDeadline.Today.isUrgent)
        assertFalse(SignupDeadline.Passed.isUrgent)
    }

    @Test
    fun deadlineAcrossYearBoundary() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        val deadline = SignupDeadline.compute(7, LocalDate.of(2027, 1, 10), newYearsEve)
        assertEquals(SignupDeadline.Upcoming(LocalDate.of(2027, 1, 3), 3), deadline)
    }

    // ---------------------------------------------------------------- rule 8

    @Test
    fun signupUrlNormalization() {
        assertEquals("https://example.com/signup", SignupRules.signupUrl("  https://example.com/signup "))
        assertEquals("http://example.com", SignupRules.signupUrl("http://example.com"))
        assertEquals("HTTPS://Example.com", SignupRules.signupUrl("HTTPS://Example.com"))
        assertEquals("https://forms.gle/abc123", SignupRules.signupUrl("forms.gle/abc123"))
        assertEquals("https://www.eventbrite.com", SignupRules.signupUrl("www.eventbrite.com"))
        assertNull(SignupRules.signupUrl("https://"))
        assertNull(SignupRules.signupUrl("Sign up at the bar"))
        assertNull(SignupRules.signupUrl("DM the host."))
        assertNull(SignupRules.signupUrl("in person"))
        assertNull(SignupRules.signupUrl(null))
        assertNull(SignupRules.signupUrl("   "))
        assertEquals("Sign up at the bar", SignupRules.signupNote(" Sign up at the bar "))
        assertNull(SignupRules.signupNote("forms.gle/abc"))
    }

    // ---------------------------------------------------------------- rule 9

    @Test
    fun instagramHandles() {
        assertEquals("comedycellar", InstagramRules.handle("@comedycellar"))
        assertEquals("comedycellar", InstagramRules.handle("comedycellar"))
        assertEquals("comedy.cellar", InstagramRules.handle("https://www.instagram.com/comedy.cellar/?hl=en"))
        assertEquals("the_pit", InstagramRules.handle("instagram.com/the_pit"))
        assertEquals("the_pit", InstagramRules.handle("Instagram.com/@the_pit/"))
        assertEquals("handle", InstagramRules.handle("@handle?igshid=123"))
        assertEquals("handle", InstagramRules.handle("handle/"))
        assertNull(InstagramRules.handle("DM @host on IG"))
        assertNull(InstagramRules.handle("https://www.instagram.com/"))
        assertNull(InstagramRules.handle("way_too_long_handle_over_thirty_chars"))
        assertNull(InstagramRules.handle("@"))
        assertNull(InstagramRules.handle(null))
        assertEquals("https://instagram.com/abc", InstagramRules.profileUrl("abc"))
    }
}
