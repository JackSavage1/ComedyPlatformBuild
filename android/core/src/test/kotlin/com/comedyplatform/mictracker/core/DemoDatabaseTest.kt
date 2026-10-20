package com.comedyplatform.mictracker.core

import com.comedyplatform.mictracker.core.demo.DemoDatabase
import com.comedyplatform.mictracker.core.demo.DemoDatabaseException
import com.comedyplatform.mictracker.core.model.MicDraft
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.SetDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Spec 8.2: every demo mutation behaves like the real table / RPC (spec 3.4). */
class DemoDatabaseTest {

    private val today = LocalDate.of(2026, 10, 20) // Tuesday
    private val nextWed = LocalDate.of(2026, 10, 21)

    private fun db(): DemoDatabase = DemoDatabase.fromJson(
        """
        {
          "mics": [
            {"id": 1, "name": "Mon Mic", "venue": "A", "day_of_week": "Monday", "start_time": "19:00", "is_active": true},
            {"id": 7, "name": "Wed Mic", "venue": "B", "day_of_week": "Wednesday", "start_time": "20:00", "is_active": true}
          ],
          "sets": [
            {"id": 3, "open_mic_id": 1, "weeks_ago": 1, "set_rating": 8},
            {"id": 4, "open_mic_id": 7, "weeks_ago": 0}
          ],
          "plans": [
            {"id": 2, "open_mic_id": 7, "status": "going", "when": "past", "weeks_ago": 0},
            {"id": 5, "open_mic_id": 1, "status": "going", "when": "upcoming", "weeks_ahead": 0}
          ],
          "scraped_mics": [
            {"id": 101, "source": "badslava", "name": "Pete's", "venue": "Pete's Candy Store",
             "day_of_week": "Tuesday", "start_time": "19:30", "cost": "Free", "status": "pending",
             "payload": {"name": "Pete's", "venue": "Pete's Candy Store", "day_of_week": "Tuesday",
                         "start_time": "19:30", "borough": "Brooklyn", "set_length_min": "5.0",
                         "is_biweekly": "yes", "latitude": "40.71", "source": "badslava", "notes": "  "}},
            {"id": 102, "source": "firemics", "name": "No Time", "venue": "X", "day_of_week": "Friday",
             "status": "pending", "payload": {}},
            {"id": 103, "source": "badslava", "name": "Old", "venue": "Y", "day_of_week": "Monday",
             "start_time": "18:00", "status": "dismissed"}
          ],
          "scrape_log": []
        }
        """.trimIndent(),
        today,
    )

    private fun expectError(block: () -> Unit): String {
        try {
            block()
        } catch (e: DemoDatabaseException) {
            return e.message ?: ""
        }
        fail("expected DemoDatabaseException")
        return ""
    }

    private val wholeWindow = LocalDate.of(2026, 1, 1)..LocalDate.of(2027, 1, 1)

    private fun allPlans(db: DemoDatabase) = db.plans(wholeWindow.start, wholeWindow.endInclusive)

    // ------------------------------------------------------------ plans

    @Test
    fun goingCreatesPlanAndOneSkeletalSet() {
        val db = db()
        db.setPlanStatus(7, nextWed, PlanStatus.GOING.raw)

        val plan = allPlans(db).single { it.openMicId == 7 && it.planDate == nextWed }
        assertEquals(6, plan.id) // continues from the max id (5)
        assertEquals(PlanStatus.GOING, plan.planStatus)
        val skeletal = db.sets().single { it.openMicId == 7 && it.datePerformed == nextWed }
        assertEquals(5, skeletal.id)
        assertTrue(skeletal.isPlanned)
        assertEquals(true, skeletal.wouldReturn) // column default

        // Going again doesn't add a second set; switching to cancelled updates the same plan.
        db.setPlanStatus(7, nextWed, PlanStatus.GOING.raw)
        db.setPlanStatus(7, nextWed, PlanStatus.CANCELLED.raw)
        assertEquals(1, db.sets().count { it.openMicId == 7 && it.datePerformed == nextWed })
        val updated = allPlans(db).single { it.openMicId == 7 && it.planDate == nextWed }
        assertEquals(6, updated.id)
        assertEquals(PlanStatus.CANCELLED, updated.planStatus)
    }

    @Test
    fun cancelledDoesNotCreateASet() {
        val db = db()
        db.setPlanStatus(1, today.plusDays(6), PlanStatus.CANCELLED.raw)
        assertEquals(2, db.sets().size)
    }

    @Test
    fun invalidStatusOrMicIsRejected() {
        val db = db()
        assertTrue(expectError { db.setPlanStatus(1, today, "maybe") }.contains("Invalid plan status"))
        expectError { db.setPlanStatus(999, today, PlanStatus.GOING.raw) }
    }

    @Test
    fun clearPlanKeepsTheSkeletalSet() {
        val db = db()
        db.setPlanStatus(7, nextWed, PlanStatus.GOING.raw)
        db.clearPlan(7, nextWed)
        assertTrue(allPlans(db).none { it.openMicId == 7 && it.planDate == nextWed })
        assertEquals(1, db.sets().count { it.openMicId == 7 && it.datePerformed == nextWed })
    }

    @Test
    fun deletePlannedSetRemovesSetAndPlanButNeverALoggedSet() {
        val db = db()
        val planned = db.sets().single { it.id == 4 }
        assertTrue(allPlans(db).any { it.openMicId == 7 && it.planDate == planned.datePerformed })

        assertFalse(db.deletePlannedSet(3)) // logged: kept
        assertTrue(db.sets().any { it.id == 3 })

        assertTrue(db.deletePlannedSet(4))
        assertTrue(db.sets().none { it.id == 4 })
        assertTrue(allPlans(db).none { it.openMicId == 7 && it.planDate == planned.datePerformed })
        assertFalse(db.deletePlannedSet(4)) // already gone
    }

    // ------------------------------------------------------------ mics

    @Test
    fun insertMicAppliesDefaultsAndContinuesIds() {
        val db = db()
        val draft = MicDraft(name = "New", venue = "Spot", dayOfWeek = "Friday", startTime = "21:00", cost = "Free")
        val mic = db.insertMic(draft.toInsertPayload())
        assertEquals(8, mic.id)
        assertEquals("Free", mic.cost)
        assertEquals("normal", mic.urgency)
        assertTrue(mic.isActiveMic)
        assertTrue(db.mics().any { it.id == 8 })
    }

    @Test
    fun insertMicWithoutARequiredColumnFails() {
        val db = db()
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive("Only a name"))
        }
        assertTrue(expectError { db.insertMic(payload) }.contains("not-null"))
    }

    @Test
    fun updateMicClearsExplicitNullsAndKeepsTheRest() {
        val db = db()
        db.updateMic(1, MicDraft.fromMic(db.mics().first { it.id == 1 }).copy(cost = "\$5").toUpdatePayload())
        val withCost = db.mics().first { it.id == 1 }
        assertEquals("\$5", withCost.cost)

        val cleared = db.updateMic(1, MicDraft.fromMic(withCost).copy(cost = "  ").toUpdatePayload())!!
        assertNull(cleared.cost)
        assertEquals("Mon Mic", cleared.name)
        assertNull(db.updateMic(999, MicDraft(name = "x").toUpdatePayload()))
    }

    @Test
    fun deleteMicIsSoftAndRemovesItsPlans() {
        val db = db()
        db.deleteMic(1)
        val mic = db.mics().first { it.id == 1 }
        assertFalse(mic.isActiveMic)
        assertTrue(allPlans(db).none { it.openMicId == 1 })
        assertTrue(db.sets().any { it.openMicId == 1 }) // history kept
    }

    // ------------------------------------------------------------ sets

    @Test
    fun setInsertUpdateDelete() {
        val db = db()
        val created = db.insertSet(SetDraft(openMicId = 1, datePerformed = today, setRating = 9, tags = listOf("killed")).toInsertPayload())
        assertEquals(5, created.id)
        assertEquals(listOf("killed"), created.tagList)
        assertEquals(5, db.sets().first().id) // newest first

        val updated = db.updateSet(5, SetDraft(openMicId = 1, datePerformed = today, setRating = 4, notes = null).toUpdatePayload())!!
        assertEquals(4, updated.setRating)
        assertNull(updated.tags) // explicit null cleared it

        assertTrue(db.deleteSet(5))
        assertFalse(db.deleteSet(5))
        assertNull(db.updateSet(5, SetDraft(openMicId = 1, datePerformed = today).toUpdatePayload()))
    }

    // ------------------------------------------------------------ import queue

    @Test
    fun pendingScrapedListsOnlyPendingRows() {
        assertEquals(listOf(101L, 102L), db().pendingScraped().map { it.id })
    }

    @Test
    fun importCopiesThePayloadIntoTheMicList() {
        val db = db()
        val newId = db.importScrapedMic(101)
        assertEquals(8, newId)

        val mic = db.mics().first { it.id == newId }
        assertEquals("Pete's", mic.name)
        assertEquals("Brooklyn", mic.borough) // from the payload
        assertEquals("Free", mic.cost) // from the row's own column
        assertEquals(5, mic.setLengthMin) // "5.0" coerced
        assertEquals(true, mic.isBiweekly) // "yes" coerced
        assertEquals(40.71, mic.latitude!!, 1e-9)
        assertNull(mic.notes) // blank → left out
        assertEquals("normal", mic.urgency) // default
        assertTrue(mic.isActiveMic)

        assertTrue(db.pendingScraped().none { it.id == 101L })
        assertTrue(expectError { db.importScrapedMic(101) }.contains("already added"))
    }

    @Test
    fun importFailsForMissingRowsOrRequiredColumns() {
        val db = db()
        assertTrue(expectError { db.importScrapedMic(555) }.contains("not found"))
        assertTrue(expectError { db.importScrapedMic(102) }.contains("has no start_time"))
        assertTrue(db.pendingScraped().any { it.id == 102L }) // still pending
    }

    @Test
    fun dismissHidesTheRow() {
        val db = db()
        db.dismissScrapedMic(102)
        assertEquals(listOf(101L), db.pendingScraped().map { it.id })
    }
}
