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

class MicRepositoryTest {

    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val today = LocalDate.of(2026, 10, 20) // Tuesday

    private fun repository(): MicRepository =
        MicRepository(ApiClient(server.config, server.http, FakeTokens()), scope, today = { today })

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun serveBasics() {
        server.on("GET", "/rest/v1/open_mics") {
            server.json(
                """[{"id": 1, "name": "Late", "day_of_week": "Tuesday", "start_time": "21:00", "is_active": true},
                    {"id": 2, "name": "Early", "day_of_week": "Tuesday", "start_time": "18:00", "is_active": null},
                    {"id": 3, "name": "Gone", "day_of_week": "Monday", "start_time": "19:00", "is_active": false}]""",
            )
        }
        server.on("GET", "/rest/v1/my_sets") {
            server.json(
                """[{"id": 10, "open_mic_id": 3, "date_performed": "2026-10-13", "set_rating": 8},
                    {"id": 11, "open_mic_id": 1, "date_performed": "2026-10-21"}]""",
            )
        }
        server.on("GET", "/rest/v1/mic_plans") {
            server.json("""[{"id": 20, "open_mic_id": 1, "plan_date": "2026-10-21", "status": "going"}]""")
        }
    }

    @Test
    fun refreshAllLoadsEverything() = runBlocking<Unit> {
        serveBasics()
        val repo = repository()

        assertTrue(repo.refreshAll())

        assertEquals(listOf(3, 2, 1), repo.mics.value.map { it.id }) // Monday first, then by time
        val active = withTimeout(5_000) { repo.activeMics.first { it.isNotEmpty() } }
        assertEquals(listOf(2, 1), active.map { it.id })
        val lookup = withTimeout(5_000) { repo.planLookup.first { it.isNotEmpty() } }
        assertEquals(PlanStatus.GOING, lookup[PlanKey(1, LocalDate.of(2026, 10, 21))])
        val visits = withTimeout(5_000) { repo.loggedVisitCounts.first { it.isNotEmpty() } }
        assertEquals(mapOf(3 to 1), visits) // the planned row doesn't count
        val joined = withTimeout(5_000) { repo.setsWithMics.first { it.size == 2 } }
        assertEquals("Gone", joined.first { it.set.id == 10 }.micName) // inactive mic still resolves
        assertTrue(repo.hasLoaded.value)
        assertNull(repo.lastError.value)

        // Plans window: Monday of last week … Sunday three weeks ahead.
        val plansRequest = server.requestsTo("/rest/v1/mic_plans").first()
        assertEquals(
            listOf("gte.2026-10-12", "lte.2026-11-15"),
            plansRequest.requestUrl!!.queryParameterValues("plan_date"),
        )
        assertEquals("date_performed.desc,id.desc", server.requestsTo("/rest/v1/my_sets").first().requestUrl!!.queryParameter("order"))
    }

    @Test
    fun refreshAllReportsErrorsWithoutThrowing() = runBlocking<Unit> {
        server.on("GET", "/rest/v1/open_mics") { server.json("""{"message":"permission denied for table open_mics"}""", code = 403) }
        server.on("GET", "/rest/v1/my_sets") { server.json("[]") }
        server.on("GET", "/rest/v1/mic_plans") { server.json("[]") }
        val repo = repository()

        assertFalse(repo.refreshAll())
        assertEquals("permission denied for table open_mics", repo.lastError.value)
        assertFalse(repo.isRefreshing.value)
    }

    @Test
    fun setPlanStatusCallsRpcAndReloads() = runBlocking<Unit> {
        serveBasics()
        server.on("POST", "/rest/v1/rpc/set_plan_status") { MockResponseFactory.noContent() }
        val repo = repository()
        val date = LocalDate.of(2026, 10, 27)

        repo.setPlanStatus(2, date, PlanStatus.CANCELLED)

        val rpc = server.requestsTo("/rest/v1/rpc/set_plan_status").single()
        assertEquals("""{"p_mic_id":2,"p_plan_date":"2026-10-27","p_status":"cancelled"}""", rpc.body.readUtf8())
        // The plan window for that single date was reloaded.
        val reload = server.requestsTo("/rest/v1/mic_plans").last()
        assertEquals(listOf("gte.2026-10-27", "lte.2026-10-27"), reload.requestUrl!!.queryParameterValues("plan_date"))
        // "Cancelled" doesn't create a skeletal set, so sets weren't reloaded.
        assertTrue(server.requestsTo("/rest/v1/my_sets").isEmpty())
    }

    @Test
    fun goingAlsoReloadsSets() = runBlocking<Unit> {
        serveBasics()
        server.on("POST", "/rest/v1/rpc/set_plan_status") { MockResponseFactory.noContent() }
        val repo = repository()
        repo.setPlanStatus(2, LocalDate.of(2026, 10, 27), PlanStatus.GOING)
        assertEquals(1, server.requestsTo("/rest/v1/my_sets").size)
    }

    @Test
    fun deleteMicCallsRpc() = runBlocking<Unit> {
        serveBasics()
        server.on("POST", "/rest/v1/rpc/delete_mic") { MockResponseFactory.noContent() }
        val repo = repository()
        repo.refreshAll()

        repo.deleteMic(1)

        assertEquals("""{"p_mic_id":1}""", server.requestsTo("/rest/v1/rpc/delete_mic").single().body.readUtf8())
    }

    @Test
    fun updateMicThatMatchesNothingThrows() = runBlocking<Unit> {
        server.on("PATCH", "/rest/v1/open_mics") { server.json("[]") }
        val repo = repository()
        try {
            repo.updateMic(99, MicDraft(name = "N", venue = "V", dayOfWeek = "Monday", startTime = "19:00"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.userMessage.contains("no longer exists"))
        }
    }

    @Test
    fun deletePlannedSetReturnsServerAnswer() = runBlocking<Unit> {
        serveBasics()
        server.on("POST", "/rest/v1/rpc/delete_planned_set") { server.json("true") }
        val repo = repository()
        repo.refreshAll()

        assertTrue(repo.deletePlannedSet(11))
        assertEquals("""{"p_set_id":11}""", server.requestsTo("/rest/v1/rpc/delete_planned_set").single().body.readUtf8())
    }

    @Test
    fun logSetNeverDuplicatesARowARefreshAlreadyLoaded() = runBlocking<Unit> {
        serveBasics()
        // The server answers the insert with row 10, which refreshAll() already loaded
        // (as if a refresh raced the insert). A duplicate id would crash keyed lists.
        server.on("POST", "/rest/v1/my_sets") {
            server.json("""[{"id": 10, "open_mic_id": 3, "date_performed": "2026-10-13", "set_rating": 8}]""", code = 201)
        }
        val repo = repository()
        repo.refreshAll()
        // Make the follow-up reload fail, so what we check is logSet's own local update.
        server.on("GET", "/rest/v1/my_sets") { server.json("""{"message":"boom"}""", code = 500) }

        repo.logSet(com.comedyplatform.mictracker.core.model.SetDraft(openMicId = 3, datePerformed = LocalDate.of(2026, 10, 13)))

        assertEquals(1, repo.sets.value.count { it.id == 10 })
        assertEquals("boom", repo.lastError.value)
    }

    @Test
    fun importScrapedReturnsNewId() = runBlocking<Unit> {
        serveBasics()
        server.on("POST", "/rest/v1/rpc/import_scraped_mic") { server.json("42") }
        server.on("GET", "/rest/v1/scraped_mics") { server.json("[]") }
        val repo = repository()

        assertEquals(42, repo.importScraped(9_000_000_000L))
        assertEquals("""{"p_id":9000000000}""", server.requestsTo("/rest/v1/rpc/import_scraped_mic").single().body.readUtf8())
        assertEquals("eq.pending", server.requestsTo("/rest/v1/scraped_mics").single().requestUrl!!.queryParameter("status"))
    }
}
