package org.wxyc.dj.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
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

/**
 * A real, stateful [CookieJar] — stores whatever a response sets and replays
 * it on the next request for the same URL. Used only to put a client into a
 * genuinely cookie-bearing starting state; [okhttp3.CookieJar.NO_COOKIES] is
 * a singleton with no observable state to bias.
 */
private class StubStoringCookieJar : CookieJar {
    private val stored = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        stored += cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = stored.toList()
}

/**
 * Pins invariant 1 (the no-cookie policy) at the [CookielessHttpClient] /
 * [CookielessHttpClientFactory] boundary: that a client obtained through this
 * module never stores or replays a session cookie, and that the guarantee
 * survives derivation via [CookielessHttpClient.derive] even when the
 * derivation's own configuration closure tries to wire in a real cookie jar.
 */
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
     * OkHttp's own `Builder()` already defaults to `CookieJar.NO_COOKIES`, so
     * the assertion above passes whether or not `create()` ever calls
     * `.cookieJar(NO_COOKIES)` itself — deleting that line is a genuine no-op
     * on this platform, unlike iOS's `URLSession`, whose default really does
     * store cookies. That leaves the derivation path — [CookielessHttpClient
     * .derive] — as the one place this module's policy is actually at risk
     * of being silently dropped, since a derived builder has no
     * language-level reason to keep it.
     *
     * Mirrors the iOS guard at
     * `Packages/WXYCAPI/Tests/WXYCAPITests/CookielessSessionTests.swift:30`,
     * which sets `httpShouldHandleCookies = true` before wrapping so the
     * assertion is about suppression, not about a default. Here that means
     * starting from a client that would genuinely store and replay cookies —
     * built directly via the `internal` constructor test code has friend
     * access to — and proving [CookielessHttpClient.derive] undoes it.
     * Deleting the `.cookieJar(CookieJar.NO_COOKIES)` call inside
     * `derive()` makes this test fail; restoring it makes it pass again.
     */
    @Test
    fun `derive re-applies NO_COOKIES over a client that would otherwise store cookies`() {
        val cookieBearingClient = OkHttpClient.Builder()
            .cookieJar(StubStoringCookieJar())
            .build()
        val wrapper = CookielessHttpClient(cookieBearingClient)

        val derived = wrapper.derive()

        assertSame(CookieJar.NO_COOKIES, derived.okHttpClient.cookieJar)
    }

    /**
     * The gap an earlier version of `derive()` (then `newBuilder(): OkHttpClient
     * .Builder`) actually had: a caller with a legitimate reason to customize
     * the derived client — adding an interceptor, say — writes ordinary OkHttp
     * boilerplate and, deliberately or by copy-paste habit, ends up wiring in a
     * real `CookieJar` inside that same builder chain. A bare `newBuilder()`
     * return let that win, since the caller's `.cookieJar(...)` was the last
     * call before `.build()`. [CookielessHttpClient.derive] closes it
     * structurally: the configuration closure runs first, and
     * `.cookieJar(NO_COOKIES)` is applied *after*, unconditionally — so this
     * test's `configure` block setting a real, stateful jar must still lose.
     */
    @Test
    fun `derive suppresses a cookie jar the configure closure tries to set`() {
        val wrapper = CookielessHttpClientFactory.create(Configuration.production)

        val derived = wrapper.derive {
            cookieJar(StubStoringCookieJar())
        }

        assertSame(CookieJar.NO_COOKIES, derived.okHttpClient.cookieJar)
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
            authBaseUrl = server.url("/auth"),
            apiBaseUrl = server.url("/"),
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
