package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.model.MicDraft
import com.comedyplatform.mictracker.core.model.PlanKey
import com.comedyplatform.mictracker.core.model.PlanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Demo mode (spec 8.2): the repository on the in-memory demo backend. */
class DemoMicBackendTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val today = LocalDate.of(2026, 10, 20) // Tuesday
    private var loads = 0

    private val demoJson = """
        {
          "mics": [
            {"id": 1, "name": "Mon Mic", "venue": "A", "day_of_week": "Monday", "start_time": "19:00", "is_active": true},
            {"id": 4, "name": "Tue Mic", "venue": "B", "day_of_week": "Tuesday", "start_time": "20:00", "is_active": true}
          ],
          "sets": [
            {"id": 1, "open_mic_id": 1, "weeks_ago": 0, "set_rating": 8, "tags": "killed"},
            {"id": 2, "open_mic_id": 4, "weeks_ago": 0}
          ],
          "plans": [
            {"id": 1, "open_mic_id": 4, "status": "going", "when": "past", "weeks_ago": 0},
            {"id": 2, "open_mic_id": 4, "status": "cancelled", "when": "upcoming", "weeks_ahead": 0}
          ],
          "scraped_mics": [
            {"id": 101, "source": "badslava", "name": "Pete's", "venue": "Pete's Candy Store",
             "day_of_week": "Tuesday", "start_time": "19:30", "status": "pending", "payload": {"borough": "Brooklyn"}}
          ],
          "scrape_log": [{"id": 1, "source": "badslava", "days_ago": 0, "hour": 14, "minute": 7, "status": "success"}]
        }
    """.trimIndent()

    private fun demoBackend(json: () -> String = { demoJson }) = DemoMicBackend(
        loadJson = {
            loads += 1
            json()
        },
        today = { today },
    )

    private fun repository(backend: MicBackend = demoBackend()) = MicRepository(backend, scope, today = { today })

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun refreshAllLoadsTheDemoDataWithRealDates() = runBlocking<Unit> {
        val repo = repository()

        assertTrue(repo.refreshAll())

        assertEquals(listOf(1, 4), repo.mics.value.map { it.id })
        assertEquals(LocalDate.of(2026, 10, 19), repo.sets.value.first { it.id == 1 }.datePerformed)
        val lookup = withTimeout(5_000) { repo.planLookup.first { it.size == 2 } }
        assertEquals(PlanStatus.GOING, lookup[PlanKey(4, LocalDate.of(2026, 10, 13))])
        assertEquals(PlanStatus.CANCELLED, lookup[PlanKey(4, today)])
        assertTrue(repo.hasLoaded.value)

        repo.loadScraped()
        repo.loadScrapeLog()
        assertEquals(listOf(101L), repo.scraped.value.map { it.id })
        assertEquals("2026-10-20 14:07", repo.scrapeLog.value.single().lastScrapedText)

        repo.refreshAll()
        assertEquals(1, loads) // the file is read once
    }

    @Test
    fun goingCreatesAPlannedSetAndDeletePlannedSetRemovesBoth() = runBlocking<Unit> {
        val repo = repository()
        repo.refreshAll()
        val wednesday = LocalDate.of(2026, 10, 21)

        repo.setPlanStatus(1, wednesday, PlanStatus.GOING)

        val planned = repo.sets.value.single { it.openMicId == 1 && it.datePerformed == wednesday }
        assertTrue(planned.isPlanned)
        assertEquals(3, planned.id) // continues from the max id
        // planLookup is derived on a background dispatcher, so wait for it.
        withTimeout(5_000) { repo.planLookup.first { it[PlanKey(1, wednesday)] == PlanStatus.GOING } }

        assertTrue(repo.deletePlannedSet(planned.id))
        assertTrue(repo.sets.value.none { it.id == planned.id })
        assertTrue(repo.plans.value.none { it.openMicId == 1 && it.planDate == wednesday })
    }

    @Test
    fun mutationsWorkInMemory() = runBlocking<Unit> {
        val repo = repository()
        repo.refreshAll()

        val added = repo.addMic(MicDraft(name = "New", venue = "Spot", dayOfWeek = "Friday", startTime = "21:00"))
        assertEquals(5, added.id)
        assertTrue(repo.mics.value.any { it.id == 5 })

        val rated = repo.updateMicRating(5, 7.5)
        assertEquals(7.5, rated.micRating!!, 1e-9)

        repo.deleteMic(4)
        assertFalse(repo.mics.value.first { it.id == 4 }.isActiveMic)
        assertTrue(repo.plans.value.none { it.openMicId == 4 })

        repo.loadScraped()
        assertEquals(6, repo.importScraped(101))
        assertEquals("Brooklyn", repo.mics.value.first { it.id == 6 }.borough)
        assertTrue(repo.scraped.value.isEmpty())
    }

    @Test
    fun demoErrorsBecomeApiExceptions() = runBlocking<Unit> {
        val repo = repository()
        repo.refreshAll()
        repo.importScraped(101)
        try {
            repo.importScraped(101)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.userMessage.contains("already added"))
        }
        try {
            repo.updateMic(99, MicDraft(name = "N", venue = "V", dayOfWeek = "Monday", startTime = "19:00"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.userMessage.contains("no longer exists"))
        }
    }

    @Test
    fun unreadableDemoFileIsReportedNotThrown() = runBlocking<Unit> {
        val repo = repository(demoBackend { "not json" })
        assertFalse(repo.refreshAll())
        assertEquals("Couldn't load the demo data.", repo.lastError.value)
    }

    @Test
    fun useBackendSwitchesAndForgetsOldData() = runBlocking<Unit> {
        val server = TestServer()
        try {
            server.on("GET", "/rest/v1/open_mics") { server.json("""[{"id": 77, "name": "Real", "day_of_week": "Monday", "start_time": "19:00"}]""") }
            server.on("GET", "/rest/v1/my_sets") { server.json("[]") }
            server.on("GET", "/rest/v1/mic_plans") { server.json("[]") }
            val remote = RemoteMicBackend(ApiClient(server.config, server.http, FakeTokens()))
            val repo = repository(remote)
            repo.refreshAll()
            assertEquals(listOf(77), repo.mics.value.map { it.id })

            // Enter demo: old rows are gone at once, demo rows after a refresh.
            repo.useBackend(demoBackend())
            assertTrue(repo.mics.value.isEmpty())
            assertFalse(repo.hasLoaded.value)
            repo.refreshAll()
            assertEquals(listOf(1, 4), repo.mics.value.map { it.id })

            // Exit demo: back to the server.
            repo.useBackend(remote)
            repo.refreshAll()
            assertEquals(listOf(77), repo.mics.value.map { it.id })
            assertNull(repo.lastError.value)
        } finally {
            server.shutdown()
        }
    }
}
