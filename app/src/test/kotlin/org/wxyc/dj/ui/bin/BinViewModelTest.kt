@file:OptIn(ExperimentalCoroutinesApi::class)

package org.wxyc.dj.ui.bin

import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.BinEntry
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClientFactory
import org.wxyc.dj.api.InMemoryTokenStorage
import org.wxyc.dj.api.TokenSlot
import org.wxyc.dj.testing.MainDispatcherRule

/**
 * Pins [BinViewModel]'s full lifecycle: the four [BinUiState]s and that
 * empty never collapses into error, the shelf sort and album-id dedupe
 * every fetch goes through, optimistic swipe-to-remove with restore-on-
 * failure, and that a refresh failure after a successful load keeps the
 * DJ's bin on screen instead of blowing it away. Ported from
 * `WXYCDJTests/Bin/BinViewModelTests.swift`, trimmed to what this issue's
 * notes put in scope for v1: no offline `BinStore` snapshot (phase 2, per
 * the issue) and no issue-#106-equivalent error-reporting seam (not part
 * of this repo yet, mirroring `LoginViewModelTest`'s identical trim).
 *
 * Drives a real [AuthService] + [ApiClient] against [MockWebServer], the
 * same pattern `:api`'s `ApiClientTest` and this package's sibling
 * `LoginViewModelTest` already use, rather than a fake -- a wire-shape
 * regression in `:api` fails here too.
 */
class BinViewModelTest {

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
     * Stands up a [BinViewModel] over an [ApiClient] already pinned to
     * `SignedIn` with a fresh JWT -- consuming exactly one `/auth/token`
     * request against [server], drained here so every test's own
     * `server.enqueue`/`server.takeRequest()` calls line up 1:1 with the
     * request its own action makes.
     */
    private suspend fun makeViewModel(): BinViewModel {
        val configuration = configuration()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        val callFactory = CookielessHttpClientFactory.create(configuration)
        val auth = AuthService(configuration, storage, callFactory)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"${jwt()}"}"""))
        auth.restoreSession()
        server.takeRequest() // the restoreSession JWT exchange
        return BinViewModel(ApiClient(configuration, callFactory, auth))
    }

    // MARK: - refresh: shelf order and dedupe

    @Test
    fun `refresh loads entries sorted into shelf order`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        // Wire order is Pratt then Molina; the filing sort must put Molina first.
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertTrue("expected Populated, got $state", state is BinUiState.Populated)
        assertEquals(listOf("DOGA", "On Your Own Love Again"), (state as BinUiState.Populated).entries.map { it.albumTitle })
    }

    @Test
    fun `refresh collapses duplicate album rows into one`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(Wire.duplicated(albumId = 100, title = "DOGA", artist = "Juana Molina", alphabeticalName = "Molina, Juana")),
        )

        viewModel.refresh()

        val state = viewModel.uiState.value as BinUiState.Populated
        assertEquals(1, state.entries.size)
    }

    // MARK: - The empty/error split issue #11 is explicit must never collapse

    @Test
    fun `an empty bin renders Empty, never Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertTrue("expected BinUiState.Empty, got $state", state is BinUiState.Empty)
    }

    @Test
    fun `a first-load failure renders Error, never Empty`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertTrue("expected BinUiState.Error, got $state", state is BinUiState.Error)
        assertEquals("Server error (500): boom.", (state as BinUiState.Error).message)
    }

    @Test
    fun `a refresh failure after a successful load keeps the list instead of regressing to Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
            viewModel.refresh()
            assertTrue(viewModel.uiState.value is BinUiState.Populated)

            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
            viewModel.refresh()

            val state = viewModel.uiState.value
            assertTrue("a bin that loaded once must not regress to Error on a later failure, got $state", state is BinUiState.Populated)
            state as BinUiState.Populated
            assertEquals(2, state.entries.size)
            assertFalse(state.isRefreshing)
            assertEquals("Server error (500): boom.", state.message)
        }

    // MARK: - refresh: in-flight gating (the composition-scope trap, and plain double-tap)

    @Test
    fun `a second refresh while one is in flight is a no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val firstRefresh = launch { viewModel.refresh() }
        advanceUntilIdle()
        assertEquals(BinUiState.Loading, viewModel.uiState.value)
        assertNotNull("expected the first refresh's request to reach the server", server.takeRequest(2, TimeUnit.SECONDS))

        viewModel.refresh() // synchronous no-op: the gate is checked before any launch

        assertNull("a second refresh must not send a second request", server.takeRequest(200, TimeUnit.MILLISECONDS))
        firstRefresh.cancel()
    }

    @Test
    fun `a refresh survives the composition scope that started it being torn down`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))

            // Stands in for BinScreen's rememberCoroutineScope().
            val compositionScope = CoroutineScope(mainDispatcherRule.testDispatcher)
            compositionScope.launch { viewModel.refresh() }
            advanceUntilIdle()
            assertEquals(
                "precondition: the fetch is in flight, its response not yet processed",
                BinUiState.Loading,
                viewModel.uiState.value,
            )

            // The configuration change: the composition, and its scope, go away.
            compositionScope.cancel()

            assertTrue(
                "the fetch must still land -- a rotation must not abandon a refresh mid-flight",
                drainUntil { viewModel.uiState.value is BinUiState.Populated },
            )
            assertEquals(2, (viewModel.uiState.value as BinUiState.Populated).entries.size)
        }

    /**
     * A pull-to-refresh keeps the loaded list on screen with
     * [BinUiState.Populated.isRefreshing] on, and turns it back off when the
     * fetch lands. Both halves matter and neither was pinned before: the
     * "keeps the list" half is what stops a routine pull from flashing the
     * whole bin away to a full-screen spinner, and the `isRefreshing` flag
     * itself is the *only* thing `BinScreen`'s `PullToRefreshBox` reads to
     * decide whether to show its indicator at all -- pinning it permanently
     * `false` (a pull that silently does nothing visible) left every other
     * test in this file green.
     */
    @Test
    fun `a pull-to-refresh keeps the loaded list up and flags itself as refreshing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
            viewModel.refresh()
            server.takeRequest()
            val loaded = (viewModel.uiState.value as BinUiState.Populated).entries

            // A second refresh whose response is withheld, so the in-flight
            // frame is observable rather than raced past.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(Wire.twoEntries)
                    .setHeadersDelay(500, TimeUnit.MILLISECONDS),
            )
            val pull = launch { viewModel.refresh() }
            advanceUntilIdle()

            val inFlight = viewModel.uiState.value
            assertTrue(
                "a pull-to-refresh must not blow the loaded list away to a spinner, got $inFlight",
                inFlight is BinUiState.Populated,
            )
            assertEquals(loaded, (inFlight as BinUiState.Populated).entries)
            assertTrue("PullToRefreshBox reads this flag and nothing else", inFlight.isRefreshing)

            assertTrue(
                drainUntil { (viewModel.uiState.value as? BinUiState.Populated)?.isRefreshing == false },
            )
            assertEquals(loaded, (viewModel.uiState.value as BinUiState.Populated).entries)
            pull.cancel()
        }

    // MARK: - remove: optimistic, targets the album, restores on failure

    @Test
    fun `remove takes the row off screen before the network call resolves`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
        viewModel.refresh()
        server.takeRequest() // drain the refresh's GET so the assertion below can only see the DELETE
        val target = (viewModel.uiState.value as BinUiState.Populated).entries.first()

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val removal = launch { viewModel.remove(target) }
        advanceUntilIdle()

        val state = viewModel.uiState.value as BinUiState.Populated
        assertEquals(1, state.entries.size)
        assertFalse(state.entries.any { it.albumId == target.albumId })
        assertNotNull("expected the DELETE to have actually reached the server", server.takeRequest(2, TimeUnit.SECONDS))
        removal.cancel()
    }

    @Test
    fun `remove success leaves the row gone with no message`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
        viewModel.refresh()
        server.takeRequest() // drain the refresh's GET so the DELETE assertion below sees the right request
        val target = (viewModel.uiState.value as BinUiState.Populated).entries.first()

        server.enqueue(MockResponse().setResponseCode(200))
        viewModel.remove(target)

        val state = viewModel.uiState.value as BinUiState.Populated
        assertEquals(1, state.entries.size)
        assertNull(state.message)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals(target.albumId.toString(), request.requestUrl!!.queryParameter("album_id"))
    }

    @Test
    fun `remove failure restores the row and explains why`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
        viewModel.refresh()
        val before = (viewModel.uiState.value as BinUiState.Populated).entries
        val target = before.first()

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        viewModel.remove(target)

        val state = viewModel.uiState.value as BinUiState.Populated
        assertEquals(before.map { it.albumId }.toSet(), state.entries.map { it.albumId }.toSet())
        assertEquals(before.size, state.entries.size)
        // Restored back into shelf order (Molina before Pratt), not appended
        // wherever the dedupedAndSorted(entries + entry) call happens to put
        // it -- the fixture's wire order (Pratt then Molina) would fail this
        // if restoring skipped the sort.
        assertEquals(listOf("DOGA", "On Your Own Love Again"), state.entries.map { it.albumTitle })
        assertNotNull(state.message)
        assertTrue(state.message!!.contains(target.albumTitle))
        assertTrue(state.message!!.contains("Server error (500): boom."))
    }

    @Test
    fun `removing the only row shows Empty on success`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.single))
        viewModel.refresh()
        val target = (viewModel.uiState.value as BinUiState.Populated).entries.single()

        server.enqueue(MockResponse().setResponseCode(200))
        viewModel.remove(target)

        assertTrue(viewModel.uiState.value is BinUiState.Empty)
    }

    @Test
    fun `removing the only row and failing restores it to Populated, not Empty`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.single))
            viewModel.refresh()
            val target = (viewModel.uiState.value as BinUiState.Populated).entries.single()

            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
            viewModel.remove(target)

            val state = viewModel.uiState.value
            assertTrue("expected the row restored into Populated, got $state", state is BinUiState.Populated)
            assertEquals(1, (state as BinUiState.Populated).entries.size)
            assertNotNull(state.message)
        }

    @Test
    fun `remove is a no-op for an album not currently shown`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
        viewModel.refresh()
        val before = viewModel.uiState.value
        val baseline = server.requestCount

        viewModel.remove(BinEntry(albumId = 999, albumTitle = "Ghost", artistName = "Nobody"))

        assertEquals(before, viewModel.uiState.value)
        assertEquals("a phantom remove must not touch the network", baseline, server.requestCount)
    }

    // MARK: - clearMessage

    @Test
    fun `clearMessage drops a Populated message without touching the entries`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
        viewModel.refresh()
        val target = (viewModel.uiState.value as BinUiState.Populated).entries.first()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        viewModel.remove(target)
        val withMessage = viewModel.uiState.value as BinUiState.Populated
        assertNotNull(withMessage.message)

        viewModel.clearMessage()

        val cleared = viewModel.uiState.value as BinUiState.Populated
        assertNull(cleared.message)
        assertEquals(withMessage.entries, cleared.entries)
    }

    @Test
    fun `clearMessage drops an Empty message`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = makeViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.single))
        viewModel.refresh()
        val target = (viewModel.uiState.value as BinUiState.Populated).entries.single()
        server.enqueue(MockResponse().setResponseCode(200))
        viewModel.remove(target) // empties the bin
        assertTrue(viewModel.uiState.value is BinUiState.Empty)
        // A refresh failure against an already-loaded (now empty) bin
        // attaches a message onto Empty rather than regressing to Error --
        // this is what actually exercises clearMessage()'s Empty branch
        // with a non-null message to clear.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        viewModel.refresh()
        val withMessage = viewModel.uiState.value as BinUiState.Empty
        assertNotNull(withMessage.message)

        viewModel.clearMessage()

        val cleared = viewModel.uiState.value
        assertTrue(cleared is BinUiState.Empty)
        assertNull((cleared as BinUiState.Empty).message)
    }

    @Test
    fun `a remove survives the composition scope that started it being torn down`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = makeViewModel()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Wire.twoEntries))
            viewModel.refresh()
            val target = (viewModel.uiState.value as BinUiState.Populated).entries.first()

            server.enqueue(MockResponse().setResponseCode(200))
            val baseline = server.requestCount
            val compositionScope = CoroutineScope(mainDispatcherRule.testDispatcher)
            compositionScope.launch { viewModel.remove(target) }
            advanceUntilIdle()
            // Precondition: the optimistic removal already applied -- it's
            // synchronous, before any suspension, so it doesn't need the
            // network leg to survive at all. What DOES need to survive is
            // the DELETE actually reaching the server below.
            assertEquals(1, (viewModel.uiState.value as BinUiState.Populated).entries.size)

            compositionScope.cancel()

            assertTrue(
                "the DELETE must still be sent -- a rotation must not abandon it mid-flight",
                drainUntil { server.requestCount > baseline },
            )
        }

    /**
     * Drains the test scheduler repeatedly, with short real sleeps in
     * between, until [predicate] holds or [timeoutMillis] of real time
     * elapses. Needed for the two composition-scope tests above: their
     * discriminating assertion depends on real `MockWebServer` I/O
     * completing on OkHttp's own threads *after* the point the test cancels
     * the calling scope, and `advanceUntilIdle()` alone does not wait for
     * that. Duplicated from `LoginViewModelTest`'s identical helper -- see
     * `MainDispatcherRule`'s KDoc for why this package doesn't import
     * across screen packages.
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

/** WXYC-representative wire fixtures -- Juana Molina / DOGA, Jessica Pratt / On Your Own Love Again. */
private object Wire {
    /** Wire order is Pratt then Molina; only the shelf sort puts Molina first. */
    val twoEntries = """
        [
          {
            "album_id": 200,
            "album_title": "On Your Own Love Again",
            "artist_name": "Jessica Pratt",
            "alphabetical_name": "Pratt, Jessica",
            "label": "Drag City",
            "code_letters": "PRA",
            "code_artist_number": 1,
            "code_number": 5,
            "format_name": "LP",
            "genre_name": "Rock"
          },
          {
            "album_id": 100,
            "album_title": "DOGA",
            "artist_name": "Juana Molina",
            "alphabetical_name": "Molina, Juana",
            "label": "Sonamos",
            "code_letters": "MOL",
            "code_artist_number": 1,
            "code_number": 12,
            "format_name": "CD",
            "genre_name": "Rock"
          }
        ]
    """.trimIndent()

    val single = """
        [
          {
            "album_id": 100,
            "album_title": "DOGA",
            "artist_name": "Juana Molina",
            "alphabetical_name": "Molina, Juana",
            "label": "Sonamos",
            "code_letters": "MOL",
            "code_artist_number": 1,
            "code_number": 12,
            "format_name": "CD",
            "genre_name": "Rock"
          }
        ]
    """.trimIndent()

    fun duplicated(albumId: Int, title: String, artist: String, alphabeticalName: String): String {
        val entry = """
            {"album_id": $albumId, "album_title": "$title", "artist_name": "$artist", "alphabetical_name": "$alphabeticalName"}
        """.trimIndent()
        return "[$entry, $entry]"
    }
}

/** JWT with payload `{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":<now+600s>}`. Signature is a placeholder; [org.wxyc.dj.api.JwtDecoder] does not verify it. */
private fun jwt(): String {
    val header = """{"alg":"HS256","typ":"JWT"}"""
    val exp = Instant.now().epochSecond + 600
    val payload = """{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":$exp}"""
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val encodedHeader = encoder.encodeToString(header.toByteArray())
    val encodedPayload = encoder.encodeToString(payload.toByteArray())
    return "$encodedHeader.$encodedPayload.sig"
}
