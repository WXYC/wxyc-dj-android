package org.wxyc.dj.api

import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.wxyc.dj.api.support.Fixtures as WireFixtures

/**
 * Drives [ApiClient] against a real [CookielessHttpClient] over
 * [MockWebServer] — every test needs the real OkHttp stack because
 * [ApiClient] takes a [CookielessHttpClient], not a bare `Call.Factory`
 * (see that class's KDoc), and [CookielessHttpClient]'s constructor only
 * wraps a real [okhttp3.OkHttpClient]. Ported from `APIClientTests.swift`,
 * trimmed to the six v1 typed methods (the phase-2 catalog leg is out of
 * scope). Every request-shaping test asserts on the request MockWebServer
 * actually received — path, query, headers, JSON body — not just the
 * decoded return value, since a round-trip test alone cannot see a field
 * silently dropped by kotlinx.serialization's `encodeDefaults = false`
 * default.
 */
class ApiClientTest {

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

    private fun configuration() = Configuration(
        authBaseUrl = server.url("/auth"),
        apiBaseUrl = server.url("/"),
    )

    /**
     * Stands up an [AuthService] already pinned to `SignedIn` with a fresh
     * JWT (consuming exactly one `/auth/token` request against [server]),
     * plus the [ApiClient] under test wired to the same
     * [CookielessHttpClient]. Callers that care about request ordering
     * should consume that first request via `server.takeRequest()` before
     * enqueuing their own stubs.
     */
    private suspend fun signedInClient(
        sessionToken: String = "session-abc",
        initialJwt: String = WireFixtures.jwt(),
    ): Pair<ApiClient, InMemoryTokenStorage> {
        val configuration = configuration()
        val storage = InMemoryTokenStorage()
        storage.save(sessionToken, TokenSlot.SESSION_TOKEN)
        val callFactory = CookielessHttpClientFactory.create(configuration)
        val auth = AuthService(configuration, storage, callFactory)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"$initialJwt"}"""))
        auth.restoreSession()
        return ApiClient(configuration, callFactory, auth) to storage
    }

    // MARK: - searchLibrary

    @Test
    fun `searchLibrary composes the query and attaches the bearer`() = runTest {
        val jwt = WireFixtures.jwt()
        val (client, _) = signedInClient(initialJwt = jwt)
        server.takeRequest() // the restoreSession JWT exchange

        server.enqueue(MockResponse().setResponseCode(200).setBody("[${Fixtures.juanaMolinaSearchResult}]"))

        val results = client.searchLibrary(artist = "Juana", title = null, limit = 10)

        assertEquals(1, results.size)
        assertEquals("Juana Molina", results.first().artistName)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/library/", url.encodedPath)
        assertEquals("Juana", url.queryParameter("artist_name"))
        assertEquals("10", url.queryParameter("n"))
        assertNull(url.queryParameter("album_title"))
        // The exact token, not merely the "Bearer " prefix: AuthService holds a
        // session token *and* a JWT, so attaching the wrong one is the natural
        // confusion, and a prefix-only assertion cannot tell them apart.
        assertEquals("Bearer $jwt", request.getHeader("Authorization"))
    }

    @Test
    fun `searchLibrary omits empty artist and title from the query`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        client.searchLibrary(artist = "", title = "")

        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("artist_name"))
        assertNull(url.queryParameter("album_title"))
        assertEquals("25", url.queryParameter("n"))
    }

    // MARK: - albumInfo

    @Test
    fun `albumInfo sends the album_id query`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Fixtures.albumInfoJSON))

        val info = client.albumInfo(albumId = 100)

        assertEquals(100, info.id)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/library/info", url.encodedPath)
        assertEquals("100", url.queryParameter("album_id"))
    }

    // MARK: - albumMetadata

    @Test
    fun `albumMetadata sends artistName, releaseTitle and trackTitle`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"label":"Sonamos","releaseYear":2025}""",
            ),
        )

        val metadata = client.albumMetadata(artistName = "Juana Molina", releaseTitle = "DOGA", trackTitle = "la paradoja")

        assertEquals("Sonamos", metadata.label)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/proxy/metadata/album", url.encodedPath)
        assertEquals("Juana Molina", url.queryParameter("artistName"))
        assertEquals("DOGA", url.queryParameter("releaseTitle"))
        assertEquals("la paradoja", url.queryParameter("trackTitle"))
    }

    @Test
    fun `albumMetadata omits releaseTitle and trackTitle when absent`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.albumMetadata(artistName = "Juana Molina")

        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("releaseTitle"))
        assertNull(url.queryParameter("trackTitle"))
    }

    // MARK: - getBin

    @Test
    fun `getBin decodes the bare array response`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Fixtures.binResponseJSON))

        val entries = client.getBin()

        assertEquals(2, entries.size)
        assertEquals(setOf(100, 200), entries.map { it.albumId }.toSet())
        assertEquals("/djs/bin", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `getBin decodes an empty array as an empty bin`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertTrue(client.getBin().isEmpty())
    }

    /**
     * A `null` body must not read as an empty bin — issue #60's
     * written-empty vs. never-written distinction, ported from iOS's
     * `getBinRejectsANullBodyRatherThanReadingItAsEmpty`.
     */
    @Test
    fun `getBin rejects a null body rather than reading it as empty`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("null"))

        val thrown = runCatching { client.getBin() }.exceptionOrNull()

        assertTrue(thrown is ApiError.Decoding, "expected ApiError.Decoding, got $thrown")
    }

    // MARK: - addToBin

    @Test
    fun `addToBin posts album_id and track_title but never dj_id`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        // The 201 body is the raw inserted `bins` row -- enqueued to prove
        // this shape can't fail the call, since it's never decoded.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":9,"dj_id":"42","album_id":200,"track_title":null}"""))

        client.addToBin(albumId = 200, trackTitle = "la paradoja")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/djs/bin", request.requestUrl!!.encodedPath)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"album_id\":200"), "body was: $body")
        assertTrue(body.contains("\"track_title\":\"la paradoja\""), "body was: $body")
        assertFalse(body.contains("dj_id"), "addToBin must never send dj_id -- the server derives it from the session: $body")
    }

    @Test
    fun `addToBin omits track_title when not given`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":9,"dj_id":"42","album_id":200,"track_title":null}"""))

        client.addToBin(albumId = 200)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"album_id\":200"), "body was: $body")
        assertFalse(body.contains("track_title"), "body was: $body")
    }

    // MARK: - removeFromBin

    @Test
    fun `removeFromBin sends DELETE with album_id and no track_title when absent`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200))

        client.removeFromBin(albumId = 200)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        val url = request.requestUrl!!
        assertEquals("200", url.queryParameter("album_id"))
        assertNull(url.queryParameter("track_title"))
    }

    @Test
    fun `removeFromBin includes track_title when provided`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200))

        client.removeFromBin(albumId = 200, trackTitle = "la paradoja")

        val url = server.takeRequest().requestUrl!!
        assertEquals("200", url.queryParameter("album_id"))
        assertEquals("la paradoja", url.queryParameter("track_title"))
    }

    // MARK: - Invariant 19: 401 -> invalidate -> single retry

    @Test
    fun `a 401 invalidates the JWT and retries exactly once`() = runTest {
        // Two distinguishable tokens, so the assertions below can tell which
        // one each attempt carried. A differing `exp` is enough to make the
        // encoded payloads differ.
        val staleJwt = WireFixtures.jwt(expiresInSeconds = 600)
        val refreshedJwt = WireFixtures.jwt(expiresInSeconds = 1800)
        val (client, _) = signedInClient(initialJwt = staleJwt)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"$refreshedJwt"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${Fixtures.juanaMolinaSearchResult}]"))

        val results = client.searchLibrary(artist = "Juana", title = null)

        assertEquals(1, results.size)
        val firstAttempt = server.takeRequest()
        assertEquals("/library/", firstAttempt.requestUrl!!.encodedPath)
        assertEquals("Bearer $staleJwt", firstAttempt.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/token", refresh.requestUrl!!.encodedPath)
        val retry = server.takeRequest()
        assertEquals("/library/", retry.requestUrl!!.encodedPath)
        // The *refreshed* token specifically, which also pins that
        // invalidateJwt() took effect: a retry that re-sent the rejected
        // bearer would 401 again rather than recover, and a prefix-only
        // assertion could not tell the two apart.
        assertEquals("Bearer $refreshedJwt", retry.getHeader("Authorization"))
    }

    /**
     * The invariant-19 pin: a second, consecutive `401` (i.e. the retry
     * also fails) must not trigger a third attempt. Bounded by [Timeout] so
     * a regression that turns this into an actual retry loop fails the
     * build instead of hanging it — MockWebServer blocks a client request
     * against an empty response queue rather than failing it outright, so
     * an un-bounded loop here would otherwise wait forever for a fourth
     * stub that is deliberately never enqueued.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `a second consecutive 401 is not retried a third time`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(401)) // first attempt
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"${WireFixtures.jwt()}"}""")) // invalidate -> refresh
        server.enqueue(MockResponse().setResponseCode(401)) // retry -- still unauthorized

        val thrown = runCatching { client.searchLibrary(artist = "Juana", title = null) }.exceptionOrNull()

        assertEquals(ApiError.Unauthorized, thrown)
        // Exactly three requests beyond the restoreSession exchange already
        // consumed above: the failing attempt, the refresh, and the single
        // retry -- nothing further was ever sent.
        assertEquals(4, server.requestCount)
    }

    // MARK: - Bearer resolution

    @Test
    fun `resolves the bearer before touching the network`() = runTest {
        val configuration = configuration()
        val storage = InMemoryTokenStorage()
        val callFactory = CookielessHttpClientFactory.create(configuration)
        val auth = AuthService(configuration, storage, callFactory)
        val client = ApiClient(configuration, callFactory, auth)

        val thrown = runCatching { client.searchLibrary(artist = "Juana", title = null) }.exceptionOrNull()

        assertEquals(ApiError.NotSignedIn, thrown)
        assertEquals(0, server.requestCount)
    }

    // MARK: - Status / decoding classification

    @Test
    fun `a non-2xx status surfaces as Http with the decoded server message`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        val thrown = runCatching { client.searchLibrary(artist = "Juana", title = null) }.exceptionOrNull()

        assertEquals(ApiError.Http(500, "boom"), thrown)
    }

    @Test
    fun `a malformed response body surfaces as Decoding rather than a raw exception`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val thrown = runCatching { client.searchLibrary(artist = "Juana", title = null) }.exceptionOrNull()

        assertTrue(thrown is ApiError.Decoding, "expected ApiError.Decoding, got $thrown")
    }

    // MARK: - Cancellation carve-out

    /**
     * A cancelled request must propagate cancellation itself, never
     * [ApiError.Network] -- see [ApiClient]'s `fire` for the full
     * rationale (mirrors iOS's cancellation carve-out ahead of a
     * connectivity monitor this module doesn't have yet). No response is
     * ever enqueued for the search request below: MockWebServer parks the
     * real in-flight OkHttp call waiting on one, which is what lets this
     * test cancel a request that is genuinely still outstanding rather than
     * racing a fast in-memory stub.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `a cancelled request propagates cancellation instead of becoming a network error`() = runTest {
        val (client, _) = signedInClient()
        server.takeRequest()

        val deferred = async { client.searchLibrary(artist = "Juana", title = null) }
        runCurrent()
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded, "expected the search request to reach the server before cancelling")

        deferred.cancel()
        val thrown = runCatching { deferred.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException, "expected CancellationException, got $thrown")
    }

    /**
     * The second cancellation carve-out, on the leg the test above cannot
     * reach. [ApiClient] catches [CancellationException] in two places:
     * around the request itself, and around the bearer resolve. The test
     * above only exercises the first, because [signedInClient] warms the JWT
     * cache via `restoreSession()`, so `currentJwt()` returns without ever
     * suspending.
     *
     * This one drops the cached JWT first, so the bearer resolve has to
     * re-mint it and suspends on a `/auth/token` exchange that is
     * deliberately never answered — putting the cancellation inside
     * `resolveBearer` rather than inside the request.
     *
     * Without the carve-out there, `resolveBearer`'s catch-all would convert
     * the cancellation into a fabricated network error: the parent's
     * cancellation would be swallowed (breaking structured concurrency), and
     * once the phase-2 connectivity monitor lands, a keystroke arriving
     * during the roughly-hourly lazy refresh would latch the app offline —
     * exactly what the debounce carve-out exists to prevent.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `a cancellation during the lazy JWT refresh propagates instead of becoming a network error`() = runTest {
        val configuration = configuration()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        val callFactory = CookielessHttpClientFactory.create(configuration)
        val auth = AuthService(configuration, storage, callFactory)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"${WireFixtures.jwt()}"}"""))
        auth.restoreSession()
        server.takeRequest() // the restoreSession JWT exchange
        val client = ApiClient(configuration, callFactory, auth)

        // Force the next call to re-mint, and enqueue nothing for that
        // exchange so it is still in flight when we cancel.
        auth.invalidateJwt()

        val deferred = async { client.searchLibrary(artist = "Juana", title = null) }
        runCurrent()
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded, "expected the /auth/token refresh to reach the server before cancelling")
        assertEquals("/auth/token", recorded!!.requestUrl!!.encodedPath)

        deferred.cancel()
        val thrown = runCatching { deferred.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException, "expected CancellationException, got $thrown")
    }
}
