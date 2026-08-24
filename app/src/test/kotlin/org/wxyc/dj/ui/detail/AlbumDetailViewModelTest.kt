@file:OptIn(ExperimentalCoroutinesApi::class)

package org.wxyc.dj.ui.detail

import java.net.UnknownHostException
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClientFactory
import org.wxyc.dj.api.InMemoryTokenStorage
import org.wxyc.dj.api.TokenSlot
import org.wxyc.dj.ui.nav.AlbumRouteFallbackStore
import org.wxyc.dj.testing.MainDispatcherRule

/**
 * Pins [AlbumDetailViewModel]: invariant 18's fan-out (with a fallback, LML
 * runs concurrently with `/library/info`; without one, `/library/info` is
 * awaited first and LML is keyed off its result), the best-effort LML
 * framing, the once-scoped-to-this-ViewModel [AlbumRouteFallbackStore] read,
 * the issue-#86 artwork-failure ledger's classify-before-record wiring, and
 * the `addToBin` `viewModelScope` survival the issue's own trap warning
 * calls out. Drives a real [AuthService]/[ApiClient] against [MockWebServer],
 * the same pattern `LoginViewModelTest` and `:api`'s `ApiClientTest` already
 * use.
 *
 * **Responses are dispatched by request path ([respondByPath]), not by
 * enqueue order.** The "with a fallback" branch fires `/library/info` and
 * `/proxy/metadata/album` genuinely concurrently on OkHttp's own thread
 * pool, so [MockWebServer]'s default FIFO queue cannot be trusted to hand
 * each request the response meant for it -- two requests racing against an
 * enqueue-ordered queue can and did swap responses under load. A path-keyed
 * [Dispatcher] removes the race by construction.
 *
 * **`@RunWith(RobolectricTestRunner::class)`, not a plain JUnit4 test.**
 * [AlbumDetailViewModel]'s failure legs call `android.util.Log.w` (mirroring
 * `TokenStorageModule`'s own precedent for a platform-facing diagnostic
 * line), and AGP's default unit-test stub for every `android.*` method
 * throws `RuntimeException: ... not mocked` the instant it's actually
 * called -- a plain unit test can never legitimately exercise this class's
 * failure paths (a 404, a decode failure) without either Robolectric's
 * shadow or never calling `Log`. Mirrors `BinCollationParityTest`'s
 * identical `@Config(sdk = [34])` pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        AlbumRouteFallbackStore.clearForTesting()
    }

    private fun configuration() = Configuration(
        authBaseUrl = server.url("/auth"),
        apiBaseUrl = server.url("/"),
    )

    /**
     * Installs a [Dispatcher] that answers by [RecordedRequest.path]
     * (path only, query stripped) rather than FIFO enqueue order --
     * see the class KDoc for why that matters once two requests can be
     * genuinely concurrent. A path with no entry gets a 404, so an
     * unexpected request fails fast and visibly rather than hanging.
     */
    private fun respondByPath(vararg responses: Pair<String, MockResponse>) {
        val byPath = responses.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath
                return byPath[path] ?: MockResponse().setResponseCode(404)
            }
        }
    }

    /**
     * A signed-in [ApiClient], consuming exactly one `/auth/token` request
     * against [server] before returning. Runs on the default [QueueDispatcher]
     * -- ordering is unambiguous here since this is the only request in
     * flight at this point -- so a later [respondByPath] call is always safe
     * to install afterwards.
     */
    private suspend fun signedInClient(): ApiClient {
        val configuration = configuration()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        val callFactory = CookielessHttpClientFactory.create(configuration)
        val auth = AuthService(configuration, storage, callFactory)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"${jwt()}"}"""))
        auth.restoreSession()
        return ApiClient(configuration, callFactory, auth)
    }

    private fun dogaFallback() = AlbumSearchResult(
        id = 100,
        albumTitle = "DOGA",
        artistName = "Juana Molina",
        label = "Sonamos",
    )

    private val infoJson = """
        {
          "id": 100,
          "album_title": "DOGA",
          "artist_name": "Juana Molina",
          "code_letters": "MOL",
          "code_number": 12,
          "code_artist_number": 1,
          "format_name": "CD",
          "genre_name": "Rock",
          "label": "Sonamos"
        }
    """.trimIndent()

    private fun okJson(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // MARK: - Invariant 18: the fan-out

    @Test
    fun `with a fallback, library-info and metadata are fetched concurrently off the fallback's artist and title`() =
        runTest(mainDispatcherRule.testDispatcher) {
            AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
            val apiClient = signedInClient()
            respondByPath(
                "/library/info" to okJson(infoJson),
                "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
            )

            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.info != null && viewModel.uiState.value.metadata != null })

            assertEquals("DOGA", viewModel.uiState.value.info?.albumTitle)
            assertEquals("Sonamos", viewModel.uiState.value.metadata?.label)
        }

    @Test
    fun `without a fallback, library-info is awaited first and metadata is keyed off its result`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Nothing stashed for this id -- the Spotlight-deep-link-style case.
            val apiClient = signedInClient()
            respondByPath(
                "/library/info" to okJson(infoJson),
                "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
            )

            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.metadata != null })

            // Sequential in this branch, so request order IS meaningful here
            // (unlike the concurrent "with a fallback" case above): the
            // second request received must be the metadata one, proving it
            // waited for /library/info rather than firing with nothing to
            // key on. server.takeRequest() reflects arrival order regardless
            // of which Dispatcher answered each request.
            server.takeRequest() // the JWT exchange
            val infoRequest = server.takeRequest()
            assertEquals("/library/info", infoRequest.requestUrl!!.encodedPath)
            val metadataRequest = server.takeRequest()
            assertEquals("/proxy/metadata/album", metadataRequest.requestUrl!!.encodedPath)
            assertEquals("Juana Molina", metadataRequest.requestUrl!!.queryParameter("artistName"))
        }

    @Test
    fun `with no fallback and a failed library-info, metadata is never attempted`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val apiClient = signedInClient()
            respondByPath("/library/info" to MockResponse().setResponseCode(404))

            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.infoFailed })

            // No artist name to key LML on -- loadMetadata short-circuits
            // without ever reaching the server. 2 requests total: the JWT
            // exchange signedInClient() consumed, then the failed
            // /library/info -- never a third for /proxy/metadata/album.
            assertEquals("no artist name available", viewModel.uiState.value.metadataError)
            assertEquals(2, server.requestCount)
        }

    // MARK: - LML is best-effort

    @Test
    fun `a metadata failure leaves the catalog row rendering and surfaces a footer note only`() =
        runTest(mainDispatcherRule.testDispatcher) {
            AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
            val apiClient = signedInClient()
            respondByPath(
                "/library/info" to okJson(infoJson),
                "/proxy/metadata/album" to MockResponse().setResponseCode(404),
            )

            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.info != null && viewModel.uiState.value.metadataError != null })

            assertEquals("DOGA", viewModel.uiState.value.info?.albumTitle)
            assertNull(viewModel.uiState.value.metadata)
        }

    // MARK: - AlbumRouteFallbackStore is read exactly once, scoped to this ViewModel

    @Test
    fun `the stashed fallback row is reflected in state immediately`() = runTest(mainDispatcherRule.testDispatcher) {
        val fallback = dogaFallback()
        AlbumRouteFallbackStore.stash(id = 100, fallback = fallback)
        val apiClient = signedInClient()
        respondByPath(
            "/library/info" to okJson(infoJson),
            "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
        )

        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)

        assertEquals(fallback, viewModel.uiState.value.fallback)
        // Consumed -- a second read for the same id sees nothing left.
        assertNull(AlbumRouteFallbackStore.take(id = 100))
    }

    @Test
    fun `no stashed row leaves fallback null without failing`() = runTest(mainDispatcherRule.testDispatcher) {
        val apiClient = signedInClient()
        respondByPath("/library/info" to okJson(infoJson))

        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)

        assertNull(viewModel.uiState.value.fallback)
    }

    // MARK: - Issue #86: classify before recording

    @Test
    fun `a connectivity-class failure is not recorded`() = runTest(mainDispatcherRule.testDispatcher) {
        AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
        val apiClient = signedInClient()
        respondByPath(
            "/library/info" to okJson(infoJson),
            "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
        )
        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
        assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })

        viewModel.recordArtworkFailure("https://cdn.example/dead.jpg", UnknownHostException("Unable to resolve host"))

        assertTrue(viewModel.uiState.value.failedArtworkUrls.isEmpty())
    }

    @Test
    fun `a resource-level failure is recorded by URL`() = runTest(mainDispatcherRule.testDispatcher) {
        AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
        val apiClient = signedInClient()
        respondByPath(
            "/library/info" to okJson(infoJson),
            "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
        )
        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
        assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })

        viewModel.recordArtworkFailure("https://cdn.example/dead.jpg", IllegalStateException("BitmapFactory returned a null bitmap."))

        assertEquals(setOf("https://cdn.example/dead.jpg"), viewModel.uiState.value.failedArtworkUrls)
    }

    @Test
    fun `a failure recorded against one URL never suppresses a different, healthy URL`() =
        runTest(mainDispatcherRule.testDispatcher) {
            AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
            val apiClient = signedInClient()
            respondByPath(
                "/library/info" to okJson(infoJson),
                "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
            )
            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })

            viewModel.recordArtworkFailure("https://cdn.example/dead.jpg", IllegalStateException("decode failed"))

            assertFalse(viewModel.uiState.value.failedArtworkUrls.contains("https://cdn.example/healthy.jpg"))
        }

    // MARK: - addToBin: the viewModelScope trap (issue #10's own warning)

    @Test
    fun `addToBin marks addedToBin on success`() = runTest(mainDispatcherRule.testDispatcher) {
        AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
        val apiClient = signedInClient()
        respondByPath(
            "/library/info" to okJson(infoJson),
            "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
            "/djs/bin" to MockResponse().setResponseCode(201).setBody("""{"id":1,"dj_id":9,"album_id":100}"""),
        )
        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
        assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })

        viewModel.addToBin()

        assertTrue(viewModel.uiState.value.addedToBin)
        assertFalse(viewModel.uiState.value.addInFlight)
    }

    /**
     * A failed add must clear `addInFlight`, not just record the error. The
     * button is gated on that flag, so leaving it set turns one transient
     * failure into a permanently dead control with no retry -- the same
     * stuck-flag shape issue #8's composition-scope defect produced, reached
     * here through the error path instead of a cancellation. Dropping
     * `addInFlight = false` from the failure arm left every other test in
     * this file green.
     */
    @Test
    fun `a failed addToBin clears the in-flight flag so the DJ can retry`() =
        runTest(mainDispatcherRule.testDispatcher) {
            AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
            val apiClient = signedInClient()
            respondByPath(
                "/library/info" to okJson(infoJson),
                "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
                "/djs/bin" to MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""),
            )
            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })

            viewModel.addToBin()

            assertFalse("a failed add must not leave the button disabled forever", viewModel.uiState.value.addInFlight)
            assertFalse(viewModel.uiState.value.addedToBin)
            assertNotNull(viewModel.uiState.value.addError)
        }

    /**
     * Once a release is in the bin, tapping again is a no-op rather than a
     * duplicate `POST /djs/bin`. Distinct from the in-flight guard above:
     * that one covers two taps racing, this one covers a tap long after the
     * first has settled, which the in-flight flag no longer says anything
     * about.
     */
    @Test
    fun `addToBin is a no-op once the release is already in the bin`() = runTest(mainDispatcherRule.testDispatcher) {
        AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
        val apiClient = signedInClient()
        respondByPath(
            "/library/info" to okJson(infoJson),
            "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
            "/djs/bin" to MockResponse().setResponseCode(201).setBody("""{"id":1,"dj_id":9,"album_id":100}"""),
        )
        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
        assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })
        viewModel.addToBin()
        assertTrue(viewModel.uiState.value.addedToBin)
        val requestsAfterFirstAdd = server.requestCount

        viewModel.addToBin()
        advanceUntilIdle()

        assertEquals(requestsAfterFirstAdd, server.requestCount)
    }

    @Test
    fun `a second addToBin while the first is in flight is a no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
        val apiClient = signedInClient()
        respondByPath(
            "/library/info" to okJson(infoJson),
            "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
            "/djs/bin" to MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )
        val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
        assertTrue(drainUntil { viewModel.uiState.value.infoLoaded && viewModel.uiState.value.metadata != null })
        val requestsBeforeAdd = server.requestCount

        val firstCall = launch { viewModel.addToBin() }
        assertTrue(drainUntil { viewModel.uiState.value.addInFlight })
        // Wait for the first request to genuinely land on the server, not
        // just for the coroutine to have issued it -- addInFlight flips
        // synchronously before the network call is even dispatched, but
        // MockWebServer's request count only updates once the request has
        // actually arrived over the socket.
        assertTrue(drainUntil { server.requestCount == requestsBeforeAdd + 1 })

        viewModel.addToBin()

        // Still exactly one add-to-bin request -- the gate held.
        assertEquals(requestsBeforeAdd + 1, server.requestCount)
        firstCall.cancel()
    }

    /**
     * The reproduced defect this pattern exists to fix: a `rememberCoroutineScope()`
     * composition scope is cancelled by a configuration change, but the
     * `ViewModel` survives it. If `addToBin`'s network call ran directly in
     * that scope instead of `viewModelScope`, the cancellation below would
     * abandon the request mid-flight and strand `addInFlight` `true` forever.
     */
    @Test
    fun `addToBin survives the composition scope that started it being torn down`() =
        runTest(mainDispatcherRule.testDispatcher) {
            AlbumRouteFallbackStore.stash(id = 100, fallback = dogaFallback())
            val apiClient = signedInClient()
            respondByPath(
                "/library/info" to okJson(infoJson),
                "/proxy/metadata/album" to okJson("""{"label":"Sonamos"}"""),
                "/djs/bin" to MockResponse().setResponseCode(201).setBody("""{"id":1,"dj_id":9,"album_id":100}"""),
            )
            val viewModel = AlbumDetailViewModel(albumId = 100, apiClient = apiClient)
            assertTrue(drainUntil { viewModel.uiState.value.infoLoaded })

            val compositionScope = CoroutineScope(mainDispatcherRule.testDispatcher)
            compositionScope.launch { viewModel.addToBin() }
            advanceUntilIdle()
            assertTrue("precondition: the add is in flight", viewModel.uiState.value.addInFlight)

            compositionScope.cancel()

            assertTrue(
                "the add-to-bin call must still complete -- a rotation must not abandon it mid-flight",
                drainUntil { viewModel.uiState.value.addedToBin },
            )
            assertFalse(viewModel.uiState.value.addInFlight)
        }

    /**
     * Drains the test scheduler repeatedly, with short real sleeps in
     * between, until [predicate] holds or [timeoutMillis] of real time
     * elapses. Needed whenever a state change depends on real `MockWebServer`
     * I/O settling on OkHttp's own thread and being posted back onto the
     * `TestDispatcher` from outside anything the test body is itself
     * awaiting -- `advanceUntilIdle()` alone does not wait for that I/O, and
     * a real sleep alone does not run the continuations it queues. Mirrors
     * `LoginViewModelTest`'s identically-named helper.
     */
    private fun TestScope.drainUntil(timeoutMillis: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (predicate()) return true
            Thread.sleep(10)
        }
        advanceUntilIdle()
        return predicate()
    }
}

/** JWT with payload `{"sub":"9","email":"juana@wxyc.org","role":"dj","exp":<now+600s>}`. Signature is a placeholder; [org.wxyc.dj.api.JwtDecoder] does not verify it. */
private fun jwt(): String {
    val header = """{"alg":"HS256","typ":"JWT"}"""
    val exp = Instant.now().epochSecond + 600
    val payload = """{"sub":"9","email":"juana@wxyc.org","role":"dj","exp":$exp}"""
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val encodedHeader = encoder.encodeToString(header.toByteArray())
    val encodedPayload = encoder.encodeToString(payload.toByteArray())
    return "$encodedHeader.$encodedPayload.sig"
}
