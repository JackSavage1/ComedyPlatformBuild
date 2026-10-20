package com.comedyplatform.mictracker.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny fake Supabase for unit tests: register a handler per
 * "METHOD /path" and inspect the recorded requests afterwards.
 */
class TestServer {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val handlers = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()

    val config: SupabaseConfig get() = SupabaseConfig(url = server.url("/").toString(), anonKey = ANON_KEY)
    val http: OkHttpClient = OkHttpClient()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath ?: ""
                val key = "${request.method} $path"
                val handler = synchronized(handlers) { handlers[key] }
                return handler?.invoke(request) ?: MockResponse().setResponseCode(404)
                    .setBody("""{"message":"no handler for $key"}""")
            }
        }
        server.start()
    }

    fun on(method: String, path: String, handler: (RecordedRequest) -> MockResponse) {
        synchronized(handlers) { handlers["$method $path"] = handler }
    }

    fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.requestUrl?.encodedPath == path }

    fun shutdown() = server.shutdown()

    companion object {
        const val ANON_KEY = "anon-key"
    }
}

/** A token provider with a fixed token that "refreshes" to [refreshedToken]. */
class FakeTokens(var token: String? = "token-1", private val refreshedToken: String? = "token-2") : AccessTokenProvider {
    var refreshCalls = 0

    override suspend fun accessToken(): String? = token

    override suspend fun refreshAfterUnauthorized(rejectedToken: String): String? {
        refreshCalls += 1
        token = refreshedToken
        return refreshedToken
    }
}
