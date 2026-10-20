package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.demo.DemoData
import com.comedyplatform.mictracker.core.demo.DemoDatabase
import com.comedyplatform.mictracker.core.demo.DemoDates
import com.comedyplatform.mictracker.core.json.JsonValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** Spec 8.2: turning the demo file's relative dates into real ones. */
class DemoDataTest {

    private val tuesday = LocalDate.of(2026, 10, 20)

    // ------------------------------------------------------------ date rules

    @Test
    fun pastDateIsStrictlyBeforeToday() {
        // Same weekday as today → last week's, never today.
        assertEquals(LocalDate.of(2026, 10, 13), DemoDates.pastDate(DayOfWeek.TUESDAY, tuesday, 0))
        assertEquals(LocalDate.of(2026, 10, 19), DemoDates.pastDate(DayOfWeek.MONDAY, tuesday, 0))
        assertEquals(LocalDate.of(2026, 10, 14), DemoDates.pastDate(DayOfWeek.WEDNESDAY, tuesday, 0))
        assertEquals(LocalDate.of(2026, 10, 18), DemoDates.pastDate(DayOfWeek.SUNDAY, tuesday, 0))
    }

    @Test
    fun pastDateGoesBackSevenDaysPerWeek() {
        assertEquals(LocalDate.of(2026, 10, 5), DemoDates.pastDate(DayOfWeek.MONDAY, tuesday, 2))
        assertEquals(LocalDate.of(2026, 8, 19), DemoDates.pastDate(DayOfWeek.WEDNESDAY, tuesday, 8))
    }

    @Test
    fun upcomingDateIsOnOrAfterToday() {
        assertEquals(tuesday, DemoDates.upcomingDate(DayOfWeek.TUESDAY, tuesday, 0))
        assertEquals(LocalDate.of(2026, 10, 21), DemoDates.upcomingDate(DayOfWeek.WEDNESDAY, tuesday, 0))
        assertEquals(LocalDate.of(2026, 10, 26), DemoDates.upcomingDate(DayOfWeek.MONDAY, tuesday, 0))
        assertEquals(LocalDate.of(2026, 10, 31), DemoDates.upcomingDate(DayOfWeek.SATURDAY, tuesday, 1))
    }

    @Test
    fun scrapeTimestampIsLocalTimeOnTheRightDay() {
        assertEquals("2026-10-20T14:07:00", DemoDates.scrapeTimestamp(tuesday, 0, 14, 7))
        assertEquals("2026-10-19T09:05:00", DemoDates.scrapeTimestamp(tuesday, 1, 9, 5))
        assertEquals("2026-10-20T23:59:00", DemoDates.scrapeTimestamp(tuesday, 0, 99, 99)) // clamped
    }

    // ------------------------------------------------------------- resolver

    private val sample = """
        {
          "_comment": "test",
          "mics": [
            {"id": 1, "name": "Mon Mic", "venue": "A", "day_of_week": "Monday", "start_time": "19:00"},
            {"id": 2, "name": "Tue Mic", "venue": "B", "day_of_week": "Tuesday", "start_time": "20:00"},
            {"id": 3, "name": "No Day", "venue": "C", "day_of_week": "Someday", "start_time": "20:00"}
          ],
          "sets": [
            {"id": 1, "open_mic_id": 1, "weeks_ago": 2, "set_rating": 7},
            {"id": 2, "open_mic_id": 2, "weeks_ago": 0},
            {"id": 3, "open_mic_id": 3, "weeks_ago": 0, "set_rating": 5},
            {"id": 4, "open_mic_id": 99, "weeks_ago": 0, "set_rating": 5},
            {"id": 5, "open_mic_id": 1, "date_performed": "2026-01-05", "set_rating": 6}
          ],
          "plans": [
            {"id": 1, "open_mic_id": 2, "status": "going", "when": "past", "weeks_ago": 0},
            {"id": 2, "open_mic_id": 2, "status": "going", "when": "upcoming", "weeks_ahead": 0},
            {"id": 3, "open_mic_id": 1, "status": "cancelled", "when": "upcoming", "weeks_ahead": 1}
          ],
          "scraped_mics": [{"id": 101, "source": "badslava", "name": "S", "status": "pending"}],
          "scrape_log": [
            {"id": 1, "source": "badslava", "days_ago": 1, "hour": 14, "minute": 7, "status": "success"},
            {"id": 2, "source": "firemics", "last_scraped": "2026-10-01T08:00:00", "status": "error"}
          ]
        }
    """.trimIndent()

    @Test
    fun resolvesSetDatesAndDropsHelperKeys() {
        val tables = DemoData.resolve(sample, tuesday)

        val byId = tables.sets.associateBy { JsonValues.int(it["id"]) }
        assertEquals("2026-10-05", JsonValues.text(byId[1]!!["date_performed"]))
        assertEquals("2026-10-13", JsonValues.text(byId[2]!!["date_performed"])) // strictly before today
        assertEquals("2026-01-05", JsonValues.text(byId[5]!!["date_performed"])) // already a real date
        // Unknown weekday / unknown mic: no date can be worked out, so the row is left out.
        assertNull(byId[3])
        assertNull(byId[4])
        for (row in tables.sets + tables.plans + tables.scrapeLog + tables.mics) {
            assertTrue("helper keys left in $row", DemoData.HELPER_KEYS.none { it in row })
        }
    }

    @Test
    fun resolvesPlanDates() {
        val plans = DemoData.resolve(sample, tuesday).plans.associateBy { JsonValues.int(it["id"]) }
        assertEquals("2026-10-13", JsonValues.text(plans[1]!!["plan_date"])) // past
        assertEquals("2026-10-20", JsonValues.text(plans[2]!!["plan_date"])) // upcoming = today
        assertEquals("2026-11-02", JsonValues.text(plans[3]!!["plan_date"])) // next Monday + 1 week
        assertEquals("going", JsonValues.text(plans[1]!!["status"]))
    }

    @Test
    fun resolvesScrapeLogTimes() {
        val log = DemoData.resolve(sample, tuesday).scrapeLog.associateBy { JsonValues.int(it["id"]) }
        assertEquals("2026-10-19T14:07:00", JsonValues.text(log[1]!!["last_scraped"]))
        assertEquals("2026-10-01T08:00:00", JsonValues.text(log[2]!!["last_scraped"])) // untouched
    }

    @Test
    fun resolvedRowsDecodeIntoTheAppModels() {
        val db = DemoDatabase.fromJson(sample, tuesday)
        assertEquals(listOf(1, 2, 3), db.mics().map { it.id })
        val sets = db.sets()
        assertEquals(listOf(2, 1, 5), sets.map { it.id }) // newest first
        assertTrue(sets.first { it.id == 2 }.isPlanned)
        assertEquals(listOf(1, 2), db.plans(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25)).map { it.id })
        assertEquals("2026-10-19 14:07", db.scrapeLog().first().lastScrapedText)
    }

    // ------------------------------------------------------ the real file

    /**
     * The bundled file itself (generated by scripts/build_demo_data.py).
     * Gradle runs tests from the module folder, so the app's assets are one
     * level up; skipped if the file isn't there.
     */
    @Test
    fun bundledDemoFileResolvesCompletely() {
        val file = File("../app/src/main/assets/demo_data.json")
        assumeTrue("demo_data.json not found from ${File(".").absolutePath}", file.isFile)

        val text = file.readText()
        val tables = DemoData.resolve(text, tuesday)
        val db = DemoDatabase(tables)

        // Nothing was dropped: every set and plan got a date.
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals(JsonValues.array(raw["sets"]).size, db.sets().size)
        assertEquals(JsonValues.array(raw["plans"]).size, tables.plans.size)
        assertTrue(db.sets().all { it.datePerformed != null && it.datePerformed!! < tuesday })

        // Spec 8.3 needs: mic 4 exists, a planned set, logged sets, pending scraped mics.
        assertTrue(db.mics().any { it.id == 4 })
        assertTrue(db.sets().any { it.isPlanned })
        assertTrue(db.sets().count { it.isLogged } >= 2)
        assertTrue(db.pendingScraped().isNotEmpty())
        assertTrue(db.mics().any { it.hasCoordinates })

        // The planned set and its "past" plan land on the same date, so
        // delete_planned_set can remove both.
        val planned = db.sets().first { it.isPlanned }
        val window = db.plans(planned.datePerformed!!, planned.datePerformed!!)
        assertTrue(window.any { it.openMicId == planned.openMicId })
        assertTrue(db.deletePlannedSet(planned.id))
        assertFalse(db.plans(planned.datePerformed!!, planned.datePerformed!!).any { it.openMicId == planned.openMicId })
    }
}
