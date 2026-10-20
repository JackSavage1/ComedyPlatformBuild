package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.model.PlanStatus
import com.comedyplatform.mictracker.core.model.Rpc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class ApiClientTest {

    private val server = TestServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun selectSendsHeadersAndFilters() = runBlocking<Unit> {
        server.on("GET", "/rest/v1/open_mics") { server.json("""[{"id": 1, "name": "A"}, {"name": "no id"}]""") }
        val client = ApiClient(server.config, server.http, FakeTokens())

        val mics = client.selectList("open_mics", Mic.serializer(), listOf("id" to "eq.1"))

        assertEquals(listOf(1), mics.map { it.id })
        val request = server.requestsTo("/rest/v1/open_mics").single()
        assertEquals(TestServer.ANON_KEY, request.getHeader("apikey"))
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertEquals("*", request.requestUrl!!.queryParameter("select"))
        assertEquals("eq.1", request.requestUrl!!.queryParameter("id"))
    }

    @Test
    fun noUserTokenMeansNoAuthorizationHeader() = runBlocking<Unit> {
        server.on("GET", "/rest/v1/open_mics") { server.json("[]") }
        val client = ApiClient(server.config, server.http, FakeTokens(token = null))
        client.select("open_mics")
        assertNull(server.requests.single().getHeader("Authorization"))
    }

    @Test
    fun unauthorizedRefreshesOnceAndRetries() = runBlocking<Unit> {
        server.on("GET", "/rest/v1/my_sets") { request ->
            if (request.getHeader("Authorization") == "Bearer token-1") {
                server.json("""{"code":"PGRST301","message":"JWT expired"}""", code = 401)
            } else {
                server.json("""[{"id": 5, "date_performed": "2026-10-20"}]""")
            }
        }
        val tokens = FakeTokens()
        val client = ApiClient(server.config, server.http, tokens)

        val result = client.select("my_sets")

        assertEquals(1, tokens.refreshCalls)
        assertEquals(2, server.requests.size)
        assertEquals("Bearer token-2", server.requests[1].getHeader("Authorization"))
        assertTrue(result.toString().contains("2026-10-20"))
    }

    @Test
    fun unauthorizedWithFailedRefreshReportsExpiredSession() = runBlocking<Unit> {
        server.on("GET", "/rest/v1/my_sets") { server.json("""{"message":"JWT expired"}""", code = 401) }
        val client = ApiClient(server.config, server.http, FakeTokens(refreshedToken = null))
        try {
            client.select("my_sets")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.SESSION_EXPIRED_MESSAGE, e.message)
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun serverMessageIsSurfaced() = runBlocking<Unit> {
        server.on("POST", "/rest/v1/rpc/set_plan_status") {
            server.json("""{"code":"42501","message":"Not authorized: this account is not an app admin.","details":null,"hint":null}""", code = 403)
        }
        val client = ApiClient(server.config, server.http, FakeTokens())
        try {
            client.rpc(Rpc.SET_PLAN_STATUS, Rpc.setPlanStatusArgs(3, LocalDate.of(2026, 10, 21), PlanStatus.GOING))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("Not authorized: this account is not an app admin.", e.message)
            assertEquals("42501", e.code)
            assertEquals(403, e.statusCode)
        }
    }

    @Test
    fun rpcSendsNamedArgsAndHandlesEmptyBody() = runBlocking<Unit> {
        server.on("POST", "/rest/v1/rpc/clear_plan") { MockResponseFactory.noContent() }
        server.on("POST", "/rest/v1/rpc/is_app_admin") { server.json("true") }
        val client = ApiClient(server.config, server.http, FakeTokens())

        val voidResult = client.rpc(Rpc.CLEAR_PLAN, Rpc.clearPlanArgs(7, LocalDate.of(2026, 1, 2)))
        val admin = client.rpc(Rpc.IS_APP_ADMIN)

        assertEquals(JsonNull, voidResult)
        assertEquals(JsonPrimitive(true), admin)
        val body = server.requestsTo("/rest/v1/rpc/clear_plan").single().body.readUtf8()
        assertEquals("""{"p_mic_id":7,"p_plan_date":"2026-01-02"}""", body)
    }

    @Test
    fun updateAsksForRepresentation() = runBlocking<Unit> {
        server.on("PATCH", "/rest/v1/open_mics") { server.json("""[{"id": 4, "mic_rating": 7.5}]""") }
        val client = ApiClient(server.config, server.http, FakeTokens())
        client.update("open_mics", listOf("id" to "eq.4"), com.comedyplatform.mictracker.core.model.Payloads.micRating(7.5))
        val request = server.requests.single()
        assertEquals("return=representation", request.getHeader("Prefer"))
        assertEquals("eq.4", request.requestUrl!!.queryParameter("id"))
        assertEquals("""{"mic_rating":7.5}""", request.body.readUtf8())
    }

    @Test
    fun notConfiguredFailsFast() = runBlocking<Unit> {
        val client = ApiClient(SupabaseConfig(url = "", anonKey = ""), server.http, FakeTokens())
        try {
            client.select("open_mics")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.NOT_CONFIGURED_MESSAGE, e.message)
        }
    }
}

/** Small helpers for responses without a JSON body. */
object MockResponseFactory {
    fun noContent(): okhttp3.mockwebserver.MockResponse = okhttp3.mockwebserver.MockResponse().setResponseCode(204)
}
