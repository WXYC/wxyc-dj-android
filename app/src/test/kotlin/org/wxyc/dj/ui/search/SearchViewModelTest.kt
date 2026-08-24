@file:OptIn(ExperimentalCoroutinesApi::class)

package org.wxyc.dj.ui.search

import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClientFactory
import org.wxyc.dj.api.InMemoryTokenStorage
import org.wxyc.dj.api.TokenSlot
import org.wxyc.dj.api.TrackMatchHint
import org.wxyc.dj.api.TrackMatchSource
import org.wxyc.dj.testing.MainDispatcherRule

/**
 * Pins [SearchViewModel]: the debounced idle/searching/results/empty state
 * machine, "newest query wins" (issue #9's explicit acceptance criterion --
 * two queries whose network requests both actually reach the server, with
 * the *first*'s response arriving *after* the second's, still leaves the
 * second's results on screen), a failed search's fallback to
 * [SearchState.Empty] with the collector surviving to serve the next query,
 * and the inline add-to-bin action -- its track-title forwarding, its
 * per-row failure surface, and its synchronous double-tap guard. Ported
 * from `WXYCDJTests/Search/SearchViewModelTests.swift`, trimmed to what
 * `docs/port-plan.md` scopes for v1: no on-device catalog clone exists yet
 * (the implementer notes on issue #9 are explicit -- "call the client
 * directly"), so there is no `.local`/`.server` source distinction to pin
 * and a failed request degrades straight to [SearchState.Empty].
 *
 * Drives a real [AuthService] + [ApiClient] against [MockWebServer], the
 * same pattern `LoginViewModelTest` and `:api`'s `ApiClientTest` both use,
 * rather than a hand-rolled fake -- a wire-shape regression in `:api` fails
 * here too. [MainDispatcherRule] (the shared one in `org.wxyc.dj.testing`)
 * routes `Dispatchers.Main`, and so [SearchViewModel]'s `viewModelScope`,
 * through `runTest`'s own [kotlinx.coroutines.test.TestDispatcher], so the
 * 300ms debounce fast-forwards under virtual time. The tests that need a
 * *real* MockWebServer round-trip to actually land bridge virtual and real
 * time via [drainUntil] (an [advanceUntilIdle] / short real-sleep loop,
 * copied from `LoginViewModelTest`'s identical helper for the identical
 * reason: OkHttp answers on its own real thread pool, which the virtual
 * scheduler cannot fast-forward through).
 */
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun configuration() = Configuration(
        authBaseUrl = server.url("/auth"),
        apiBaseUrl = server.url("/"),
    )

    /**
     * A signed-in [ApiClient] over a fresh [AuthService], with its one JWT
     * exchange already consumed from [server]'s request queue -- mirrors
     * `:api`'s `ApiClientTest.signedInClient`, so every test's own
     * `server.takeRequest()`/`requestCount` reasoning starts clean rather
     * than needing to account for this leg's request.
     */
    private suspend fun signedInApiClient(): ApiClient {
        val configuration = configuration()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        val callFactory = CookielessHttpClientFactory.create(configuration)
        val authService = AuthService(configuration, storage, callFactory)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"${jwt()}"}"""))
        authService.restoreSession()
        server.takeRequest()
        return ApiClient(configuration, callFactory, authService)
    }

    private suspend fun makeViewModel(): SearchViewModel = SearchViewModel(signedInApiClient())

    private fun searchResult(
        id: Int,
        albumTitle: String,
        artistName: String,
        matchedTrackTitles: List<String> = emptyList(),
    ): AlbumSearchResult = AlbumSearchResult(
        id = id,
        albumTitle = albumTitle,
        artistName = artistName,
        matchedVia = matchedTrackTitles.map { TrackMatchHint(title = it, source = TrackMatchSource.Cta) },
    )

    // MARK: - Idle / minimum length

    @Test
    fun `empty query stays idle and issues no request`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        val baseline = server.requestCount

        viewModel.onQueryChanged("")
        advanceUntilIdle()

        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertEquals(baseline, server.requestCount)
    }

    @Test
    fun `a single character query stays idle and issues no request`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        val baseline = server.requestCount

        viewModel.onQueryChanged("j")
        advanceUntilIdle()

        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertEquals(baseline, server.requestCount)
    }

    // MARK: - Server round-trips

    @Test
    fun `a hit transitions to the results state`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$juanaMolinaSearchResultJson]"))

        viewModel.onQueryChanged("ju")

        assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Results })
        assertEquals(1, viewModel.uiState.value.results.size)
        assertEquals("Juana Molina", viewModel.uiState.value.results.first().artistName)
    }

    @Test
    fun `an empty response transitions to the empty state`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        viewModel.onQueryChanged("zzz")

        assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Empty })
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    @Test
    fun `a server error transitions to the empty state and the pipeline survives it`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

            viewModel.onQueryChanged("ju")

            assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Empty })
            assertTrue(viewModel.uiState.value.results.isEmpty())

            // The debounce/flatMapLatest collector in `init` is one long-lived
            // coroutine for this view model's whole life -- an uncaught throw
            // out of the failed search above would have killed it and silently
            // disabled every later query. Prove it didn't.
            server.enqueue(MockResponse().setResponseCode(200).setBody("[$juanaMolinaSearchResultJson]"))
            viewModel.onQueryChanged("mo")

            assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Results })
            assertEquals("Juana Molina", viewModel.uiState.value.results.first().artistName)
        }

    /**
     * Pins the 300ms debounce itself, distinct from every test above and
     * below it -- those all reach the network through `drainUntil`/
     * `advanceUntilIdle`, which don't care whether a delay sat in front of
     * the request, only that one eventually landed. A `SearchViewModel`
     * with `.debounce(DEBOUNCE_MILLIS)` deleted from its pipeline would
     * still pass every other test in this file (`flatMapLatest`'s
     * cancellation behavior doesn't depend on debounce being present) --
     * this is the one that actually requires the wait.
     *
     * The negative assertion is a bounded **real** wait
     * (`takeRequest(300, MILLISECONDS)`), not `runCurrent()` followed by an
     * immediate `requestCount` read -- `runCurrent()` only drains the
     * virtual-time queue, and `OkHttp`'s `Call.enqueue()` hands the actual
     * socket write to its own real thread pool and returns immediately, so
     * a same-tick read races that background thread and can read "nothing
     * yet" even once the request is already in flight (which is exactly
     * what let a first version of this test pass against a debounce-free
     * implementation for the wrong reason). A real 300ms wait gives that
     * background thread ample time to actually land the request if one was
     * ever dispatched, while costing nothing against the correct
     * implementation: virtual time here is fully decoupled from real time,
     * so debounce's `delay(300)` cannot elapse no matter how long this
     * thread blocks without an explicit `advanceUntilIdle()`.
     */
    @Test
    fun `the search waits out the debounce window before hitting the server`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody("[$juanaMolinaSearchResultJson]"))

            viewModel.onQueryChanged("ju")
            runCurrent()
            assertNull(
                "no request should reach the server before the debounce window elapses",
                server.takeRequest(300, TimeUnit.MILLISECONDS),
            )

            advanceUntilIdle()
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }

    // MARK: - Newest query wins (flatMapLatest)

    @Test
    fun `shortening the query below the minimum cancels an in-flight search`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            // A real, non-trivial delay so "ju"'s request is still
            // outstanding when the query drops below the minimum length.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("[$jessicaPrattSearchResultJson]")
                    .setHeadersDelay(500, TimeUnit.MILLISECONDS),
            )

            viewModel.onQueryChanged("ju")
            advanceUntilIdle()
            assertNotNull(
                "the debounced request for \"ju\" must reach the server",
                server.takeRequest(2, TimeUnit.SECONDS),
            )

            viewModel.onQueryChanged("j")
            // Synchronous -- onQueryChanged flips this before the debounced
            // pipeline even reconsiders the query, no wait needed.
            assertEquals(SearchState.Idle, viewModel.uiState.value.state)

            // Let the stale "ju" response (500ms real delay) actually arrive,
            // then pump the scheduler -- a broken (non-cancelling)
            // implementation would have this land and flip state back to
            // .Results with Pratt's row.
            Thread.sleep(600)
            advanceUntilIdle()

            assertEquals(SearchState.Idle, viewModel.uiState.value.state)
            assertTrue(viewModel.uiState.value.results.isEmpty())
        }

    /**
     * The acceptance criterion, proven end to end: both requests genuinely
     * reach the server (this is not debounce merely coalescing two
     * keystrokes into one emission), the *first*'s response is the slower
     * of the two, and yet the *second*'s results are what's on screen once
     * both have had a chance to land. A `flatMapMerge` (or any
     * non-cancelling per-emission `launch`) swap in `SearchViewModel` turns
     * this red -- see the mutation table in the issue #9 report.
     */
    @Test
    fun `the newest query wins over a slower, stale response`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        val baseline = server.requestCount
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("[$jessicaPrattSearchResultJson]")
                .setHeadersDelay(600, TimeUnit.MILLISECONDS),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$juanaMolinaSearchResultJson]"))

        viewModel.onQueryChanged("pra")
        advanceUntilIdle()
        assertNotNull("Pratt's request must reach the server", server.takeRequest(2, TimeUnit.SECONDS))

        viewModel.onQueryChanged("juana")
        advanceUntilIdle()
        assertNotNull("Molina's request must reach the server", server.takeRequest(2, TimeUnit.SECONDS))

        assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Results })
        assertEquals(listOf(100), viewModel.uiState.value.results.map { it.id })

        // Let Pratt's slow response (600ms real delay) finish arriving, and
        // pump the scheduler once more.
        Thread.sleep(700)
        advanceUntilIdle()

        assertEquals(SearchState.Results, viewModel.uiState.value.state)
        assertEquals(listOf(100), viewModel.uiState.value.results.map { it.id })
        assertEquals(2, server.requestCount - baseline)
    }

    // MARK: - Inline add-to-bin

    @Test
    fun `add to bin forwards the first matched track title`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        val row = searchResult(
            id = 100,
            albumTitle = "Duke Ellington & John Coltrane",
            artistName = "Duke Ellington & John Coltrane",
            matchedTrackTitles = listOf("In a Sentimental Mood"),
        )
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        viewModel.addToBin(row)
        assertTrue(drainUntil { viewModel.uiState.value.addToBinStatus[row.id] != AddToBinStatus.InFlight })

        assertEquals(AddToBinStatus.Added, viewModel.uiState.value.addToBinStatus[row.id])
        val posted = server.takeRequest()
        assertEquals("POST", posted.method)
        val body = Json.parseToJsonElement(posted.body.readUtf8()).jsonObject
        assertEquals(100, body.getValue("album_id").jsonPrimitive.int)
        assertEquals("In a Sentimental Mood", body.getValue("track_title").jsonPrimitive.content)
    }

    @Test
    fun `add to bin omits the track title when not track matched`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        val row = searchResult(id = 100, albumTitle = "DOGA", artistName = "Juana Molina")
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        viewModel.addToBin(row)
        assertTrue(drainUntil { viewModel.uiState.value.addToBinStatus[row.id] != AddToBinStatus.InFlight })

        assertEquals(AddToBinStatus.Added, viewModel.uiState.value.addToBinStatus[row.id])
        val posted = server.takeRequest()
        val body = Json.parseToJsonElement(posted.body.readUtf8()).jsonObject
        // explicitNulls = false (WxycJson) omits a null trackTitle entirely
        // rather than encoding an explicit `"track_title":null`.
        assertFalse(body.containsKey("track_title"))
    }

    @Test
    fun `add to bin surfaces a failure without disturbing the rest of the state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody("[$juanaMolinaSearchResultJson]"))
            viewModel.onQueryChanged("ju")
            assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Results })
            val row = viewModel.uiState.value.results.first()

            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
            viewModel.addToBin(row)
            assertTrue(drainUntil { viewModel.uiState.value.addToBinStatus[row.id] != AddToBinStatus.InFlight })

            assertEquals(AddToBinStatus.Failed, viewModel.uiState.value.addToBinStatus[row.id])
            // Scoped to this one row's status -- the result list (and so
            // scroll position) is untouched by the failure.
            assertEquals(SearchState.Results, viewModel.uiState.value.state)
            assertEquals(1, viewModel.uiState.value.results.size)
        }

    /**
     * Mirrors `LoginViewModelTest`'s `second submit while signing in is a
     * no-op`: the request is left hanging (no response enqueued) so the
     * first `addToBin` is genuinely still in flight when the second tap
     * arrives.
     */
    @Test
    fun `a second tap while adding is a no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        val row = searchResult(id = 100, albumTitle = "DOGA", artistName = "Juana Molina")
        val baseline = server.requestCount
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        viewModel.addToBin(row)
        advanceUntilIdle()
        assertEquals(AddToBinStatus.InFlight, viewModel.uiState.value.addToBinStatus[row.id])
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        viewModel.addToBin(row)
        advanceUntilIdle()

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount - baseline)
    }


    /**
     * A repeated, identical query is a no-op -- and specifically **not** a
     * spinner that never clears.
     *
     * `onQueryChanged` flips [SearchState.Searching] on synchronously, and
     * the only thing that ever flips it back off is an emission out of the
     * view model's internal `queryChanges` stream. That stream is a
     * `MutableStateFlow`, which drops an assignment of a value equal to the
     * one it already holds -- so without the unchanged-text guard, calling
     * this method twice with the same string leaves the UI in
     * [SearchState.Searching] permanently, with no request in flight to
     * ever answer it. Verified: removing the guard strands this at
     * `Searching` through a full real-time drain rather than merely
     * delaying it.
     *
     * Compose's `String`-valued `BasicTextField` filters unchanged text
     * before invoking `onValueChange`, so `SearchScreen` -- the one caller
     * today -- cannot trigger this. That makes the guard a contract this
     * public method keeps on its own behalf rather than one it borrows from
     * its current caller, which is the same lesson issue #8's
     * composition-scope defect taught: a view model API that only works
     * because of how it happens to be called is one new call site away from
     * a user-visible strand.
     */
    @Test
    fun `repeating the same query is a no-op, not a stuck spinner`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$juanaMolinaSearchResultJson]"))
        viewModel.onQueryChanged("ju")
        assertTrue(drainUntil { viewModel.uiState.value.state == SearchState.Results })
        // Drain that first search out of the server's queue, so the
        // `takeRequest` below can only ever see a *new* request.
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val baseline = server.requestCount

        viewModel.onQueryChanged("ju")

        // Still on the results it already had -- never parked on a spinner.
        assertEquals(
            "a repeated query must not strand the UI mid-search",
            SearchState.Results,
            viewModel.uiState.value.state,
        )
        assertEquals(listOf(100), viewModel.uiState.value.results.map { it.id })

        // And it is a genuine no-op: no second request went out for text
        // that never changed.
        advanceUntilIdle()
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(baseline, server.requestCount)
    }

    /**
     * Drains the test scheduler repeatedly, with short real sleeps in
     * between, until [predicate] holds or [timeoutMillis] of real time
     * elapses. Copied from `LoginViewModelTest`'s identical helper: neither
     * `advanceUntilIdle()` (doesn't wait for real I/O) nor a plain real
     * sleep (doesn't run the continuations that I/O queued) suffices alone
     * for a test that has to observe the far side of a real MockWebServer
     * round-trip.
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

    private companion object {
        val juanaMolinaSearchResultJson = """
            {
              "id": 100,
              "album_title": "DOGA",
              "artist_name": "Juana Molina",
              "code_letters": "MOL",
              "code_number": 12,
              "code_artist_number": 1,
              "format_name": "CD",
              "genre_name": "Rock",
              "label": "Sonamos",
              "rotation_bin": "H"
            }
        """.trimIndent()

        val jessicaPrattSearchResultJson = """
            {
              "id": 200,
              "album_title": "On Your Own Love Again",
              "artist_name": "Jessica Pratt",
              "code_letters": "PRA",
              "code_number": 5,
              "code_artist_number": 1,
              "format_name": "LP",
              "genre_name": "Rock",
              "label": "Drag City"
            }
        """.trimIndent()
    }
}

/** JWT with payload `{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":<now+600s>}`. Signature is a placeholder; [org.wxyc.dj.api.JwtDecoder] does not verify it. Copied from `LoginViewModelTest`'s identical helper. */
private fun jwt(): String {
    val header = """{"alg":"HS256","typ":"JWT"}"""
    val exp = Instant.now().epochSecond + 600
    val payload = """{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":$exp}"""
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val encodedHeader = encoder.encodeToString(header.toByteArray())
    val encodedPayload = encoder.encodeToString(payload.toByteArray())
    return "$encodedHeader.$encodedPayload.sig"
}
