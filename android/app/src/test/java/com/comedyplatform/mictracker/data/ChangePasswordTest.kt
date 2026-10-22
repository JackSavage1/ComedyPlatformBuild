package com.comedyplatform.mictracker.data

import com.comedyplatform.mictracker.core.rules.PasswordRules
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** [AuthRepository.changePassword] against a fake GoTrue (spec section 9). */
class ChangePasswordTest {

    private val server = TestServer()
    private val now = 1_000_000L
    private val signedIn = AuthSession("a1", "r1", now + 3600, "user-1", "me@example.com")

    @After
    fun tearDown() = server.shutdown()

    private fun tokenBody(access: String, refresh: String) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"expires_at":${now + 3600},
            "token_type":"bearer","user":{"id":"user-1","email":"me@example.com"}}"""

    /** Calls changePassword and returns the ApiException it must throw. */
    private suspend fun AuthRepository.changePasswordFailure(current: String, new: String): ApiException =
        try {
            changePassword(current, new)
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            e
        }

    @Test
    fun successSignsInAgainThenUpdatesThePassword() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") { server.json(tokenBody("a2", "r2")) }
        server.on("PUT", "/auth/v1/user") { server.json("""{"id":"user-1","email":"me@example.com"}""") }
        val store = InMemorySessionStore(signedIn)
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })

        auth.changePassword("old-password", "new-password")

        // Exactly two calls, in order: re-auth, then the update.
        assertEquals(listOf("POST /auth/v1/token", "PUT /auth/v1/user"), server.requests.map { "${it.method} ${it.path?.substringBefore('?')}" })
        val (reauth, update) = server.requests

        assertEquals("password", reauth.requestUrl!!.queryParameter("grant_type"))
        assertEquals(TestServer.ANON_KEY, reauth.getHeader("apikey"))
        assertNull(reauth.getHeader("Authorization"))
        assertEquals("""{"email":"me@example.com","password":"old-password"}""", reauth.body.readUtf8())

        assertEquals(TestServer.ANON_KEY, update.getHeader("apikey"))
        assertEquals("Bearer a2", update.getHeader("Authorization")) // the FRESH token
        assertTrue(update.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"password":"new-password"}""", update.body.readUtf8())

        // The fresh session is stored like after a normal sign-in; still signed in.
        val session = auth.session.value!!
        assertEquals("a2", session.accessToken)
        assertEquals("r2", session.refreshToken)
        assertEquals("me@example.com", session.email)
        assertEquals(session, store.load())
    }

    @Test
    fun wrongCurrentPasswordSaysSoAndKeepsTheSession() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") {
            server.json("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""", code = 400)
        }
        val store = InMemorySessionStore(signedIn)
        val auth = AuthRepository(server.config, server.http, store, nowEpochSeconds = { now })

        val error = auth.changePasswordFailure("wrong-password", "new-password")

        assertEquals("Your current password is incorrect.", error.message)
        assertEquals(400, error.statusCode)
        assertEquals("invalid_credentials", error.code)
        // NOT signed out, and the password was never sent to /user.
        assertEquals(signedIn, auth.session.value)
        assertEquals(signedIn, store.load())
        assertEquals(0, server.requestsTo("/auth/v1/user").size)
    }

    @Test
    fun weakPasswordShowsTheServerMessage() = runBlocking<Unit> {
        val message = "Password should contain at least one character of each: abcdefghijklmnopqrstuvwxyz, 0123456789."
        server.on("POST", "/auth/v1/token") { server.json(tokenBody("a2", "r2")) }
        server.on("PUT", "/auth/v1/user") {
            server.json(
                """{"code":422,"error_code":"weak_password","msg":"$message","weak_password":{"reasons":["characters"]}}""",
                code = 422,
            )
        }
        val auth = AuthRepository(server.config, server.http, InMemorySessionStore(signedIn), nowEpochSeconds = { now })

        val error = auth.changePasswordFailure("old-password", "newpassword")

        assertEquals(message, error.message)
        assertEquals(422, error.statusCode)
        assertEquals("weak_password", error.code)
        // The re-auth worked, so the user stays signed in with the fresh session.
        assertEquals("a2", auth.session.value?.accessToken)
    }

    @Test
    fun samePasswordShowsTheServerMessage() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") { server.json(tokenBody("a2", "r2")) }
        server.on("PUT", "/auth/v1/user") {
            server.json(
                """{"code":422,"error_code":"same_password","msg":"New password should be different from the old password."}""",
                code = 422,
            )
        }
        val auth = AuthRepository(server.config, server.http, InMemorySessionStore(signedIn), nowEpochSeconds = { now })

        val error = auth.changePasswordFailure("old-password", "new-password")

        assertEquals("New password should be different from the old password.", error.message)
        assertEquals("same_password", error.code)
    }

    @Test
    fun failureWithoutAServerMessageUsesTheGenericMessage() = runBlocking<Unit> {
        server.on("POST", "/auth/v1/token") { server.json(tokenBody("a2", "r2")) }
        server.on("PUT", "/auth/v1/user") { server.json("<html>Bad gateway</html>", code = 502) }
        val auth = AuthRepository(server.config, server.http, InMemorySessionStore(signedIn), nowEpochSeconds = { now })

        val error = auth.changePasswordFailure("old-password", "new-password")

        assertEquals("Couldn't change your password. Check your connection and try again.", error.message)
        assertEquals(502, error.statusCode)
    }

    @Test
    fun offlineUsesTheGenericMessageAndKeepsTheSession() = runBlocking<Unit> {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val store = InMemorySessionStore(signedIn)
        val auth = AuthRepository(server.config, offline, store, nowEpochSeconds = { now })

        val error = auth.changePasswordFailure("old-password", "new-password")

        assertEquals(PasswordRules.GENERIC_FAILURE_MESSAGE, error.message)
        assertEquals(signedIn, auth.session.value)
        assertEquals(signedIn, store.load())
    }

    @Test
    fun signedOutFailsWithoutCallingTheServer() = runBlocking<Unit> {
        val auth = AuthRepository(server.config, server.http, InMemorySessionStore(), nowEpochSeconds = { now })

        val error = auth.changePasswordFailure("old-password", "new-password")

        assertEquals(ApiException.SESSION_EXPIRED_MESSAGE, error.message)
        assertEquals(0, server.requests.size)
    }
}
