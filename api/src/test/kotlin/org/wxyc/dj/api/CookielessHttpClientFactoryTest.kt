package org.wxyc.dj.api

import okhttp3.CookieJar
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CookielessHttpClientFactoryTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `create applies CookieJar NO_COOKIES`() {
        val client = CookielessHttpClientFactory.create(Configuration.production).okHttpClient

        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }

    /**
     * The invariant-1 regression test. Pins the outcome directly rather than
     * the mechanism — OkHttp's own default is already cookie-free, so this
     * guards against a future `CookieJar` being wired onto the client, not
     * against today's `OkHttpClient.Builder().build()`. Against the real
     * backend, a replayed session cookie is what turns a clean sign-in into a
     * spurious 403 on the *next* sign-in. See [CookielessHttpClient]'s KDoc
     * for the full failure this pins.
     */
    @Test
    fun `signing in twice sends no Cookie header on the second request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "better-auth.session_token=abc123; Path=/; HttpOnly")
                .setBody("{}"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}"),
        )

        val configuration = Configuration(
            authBaseUrl = server.url("/auth").toString(),
            apiBaseUrl = server.url("/").toString(),
        )
        val client = CookielessHttpClientFactory.create(configuration).okHttpClient
        val signInUrl = server.url("/auth/sign-in/username")

        repeat(2) {
            val request = Request.Builder()
                .url(signInUrl)
                .post("{}".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }
        }

        server.takeRequest()
        val secondRequest = server.takeRequest()
        assertNull(secondRequest.headers["Cookie"], "second sign-in replayed a stored cookie")
    }
}
