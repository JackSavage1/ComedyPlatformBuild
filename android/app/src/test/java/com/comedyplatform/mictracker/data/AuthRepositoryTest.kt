package com.comedyplatform.mictracker.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AuthRepositoryTest {

    private val server = TestServer()
    private val now = 1_000_000L

    @After
    fun tearDown() = server.shutdown()

    private fun tokenBody(access: String, refresh: String, expiresAt: Long) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"expires_at":$expiresAt,
            "token_type":"bearer","user":{"id":"user-1","email":"me@example.com"}}"""

    @Test
    fun signInStoresTheSession() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") { server.json(tokenBody("a1", "r1", now + 3600)) }
        val store = InMemorySessionStore()
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })

        val session = auth.signIn(" me@example.com ", "pw")

        assertEquals("a1", session.accessToken)
        assertEquals("user-1", session.userId)
        assertEquals("me@example.com", session.email)
        assertEquals(session, store.load())
        assertEquals(session, auth.session.value)
        val request = server.requests.single()
        assertEquals("password", request.requestUrl!!.queryParameter("grant_type"))
        assertEquals(TestServer.ANON_KEY, request.getHeader("apikey"))
        assertEquals("""{"email":"me@example.com","password":"pw"}""", request.body.readUtf8())
    }

    @Test
    fun signInErrorUsesGoTrueMessage() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") {
            server.json("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""", code = 400)
        }
        val auth = AuthRepository(server.config, server.http, InMemorySessionStore(), nowEpochSeconds = { now })
        try {
            auth.signIn("me@example.com", "wrong")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("Invalid login credentials", e.message)
        }
        assertNull(auth.session.value)
    }

    @Test
    fun validTokenIsReturnedWithoutRefreshing() = runBlocking<Unit> {
        val store = InMemorySessionStore(AuthSession("a1", "r1", now + 600, "user-1", "me@example.com"))
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })
        assertEquals("a1", auth.accessToken())
        assertEquals(0, server.requests.size)
    }

    @Test
    fun tokenExpiringWithinAMinuteIsRefreshedProactively() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") { server.json(tokenBody("a2", "r2", now + 3600)) }
        val store = InMemorySessionStore(AuthSession("a1", "r1", now + 30, "user-1", "me@example.com"))
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })

        assertEquals("a2", auth.accessToken())
        val request = server.requests.single()
        assertEquals("refresh_token", request.requestUrl!!.queryParameter("grant_type"))
        assertEquals("""{"refresh_token":"r1"}""", request.body.readUtf8())
        assertEquals("r2", store.load()!!.refreshToken)
    }

    @Test
    fun rejectedRefreshSignsOut() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") {
            server.json("""{"code":400,"msg":"Invalid Refresh Token: Already Used"}""", code = 400)
        }
        val store = InMemorySessionStore(AuthSession("a1", "r1", now + 3600, "user-1", "me@example.com"))
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })

        assertNull(auth.refreshAfterUnauthorized("a1"))
        assertNull(auth.session.value)
        assertNull(store.load())
    }

    @Test
    fun refreshAfterUnauthorizedReusesANewerToken() = runBlocking<Unit> {
        val store = InMemorySessionStore(AuthSession("a2", "r2", now + 3600, "user-1", "me@example.com"))
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })
        // Someone already refreshed: the rejected token is an old one.
        assertEquals("a2", auth.refreshAfterUnauthorized("a1"))
        assertEquals(0, server.requests.size)
    }

    @Test
    fun signOutClearsEvenWhenTheServerFails() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/logout") { server.json("""{"msg":"boom"}""", code = 500) }
        val store = InMemorySessionStore(AuthSession("a1", "r1", now + 3600, "user-1", "me@example.com"))
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })

        auth.signOut()

        assertNull(auth.session.value)
        assertNull(store.load())
        assertNotNull(server.requestsTo("/auth/v1/logout").single().getHeader("Authorization"))
    }
}
