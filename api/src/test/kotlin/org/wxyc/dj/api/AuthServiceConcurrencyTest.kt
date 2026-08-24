package org.wxyc.dj.api

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.wxyc.dj.api.support.Fixtures
import org.wxyc.dj.api.support.GatedAuthSession

/**
 * Issue #16: [AuthService] must be safe under genuinely concurrent
 * dispatchers, not merely under [kotlinx.coroutines.test.runTest]'s single
 * [kotlinx.coroutines.test.TestDispatcher]. [AuthServiceTest]'s "sign-out
 * during in-flight refresh is not resurrected by rotation header" pins the
 * same *scenario*, but per the issue's own measurement (a mutation dropped
 * into the review of #18: wrapping only the mutating `invalidateJwt()` call
 * in `withContext(Dispatchers.IO)` stayed green against the full suite), a
 * suite built entirely on `runTest` cannot tell "correct because atomic"
 * from "correct because sequential and single-threaded" — it reproduces on
 * one dispatcher exactly the confinement production would have lost. This
 * suite drives the identical race over real OS threads
 * (`Dispatchers.Default`), where a `signOut()` and an in-flight
 * `currentJwt()`'s refresh genuinely execute in parallel with no
 * synchronization the test itself injects between them — an explicit
 * `.await()` sequencing "sign-out finishes" before "release the parked
 * refresh" would itself be a synchronizing action masking the very bug this
 * exists to catch, so the two run as two independently-scheduled real
 * threads instead.
 *
 * A single run is not a reliable falsifier of a memory-visibility race —
 * the window between a suspended refresh resuming and its epoch check is
 * nanoseconds wide, and whether two real threads land in it is a function
 * of OS scheduling and cache-coherence timing, not anything a test
 * controls. `many races never resurrect a cleared session under real
 * parallel dispatchers` therefore repeats the race many times and fails if
 * *any* iteration resurrects a cleared session, rather than asserting on a
 * single attempt. See the issue-#16 report for the measured pass/fail
 * counts against both the pre-fix and post-fix implementations, and for why
 * the counts are expected to be non-deterministic.
 */
class AuthServiceConcurrencyTest {
    private val config = Configuration.localDevelopment

    /**
     * One race: a refresh gets as far as capturing (token, epoch) and
     * parking in the network call, a concurrent [AuthService.signOut] is
     * launched on its own real thread, and only then is the parked call
     * released — also from this coroutine, with no join awaiting
     * [AuthService.signOut]'s completion first. Returns `true` if the
     * session was resurrected: [AuthState.SignedOut] is current, but
     * storage (or the in-memory session it mirrors) still holds a live
     * token that a cold-launch [AuthService.restoreSession] would revive.
     */
    private suspend fun CoroutineScope.raceOnce(): Boolean {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-1", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.restoreSession()
        service.invalidateJwt()

        session.armGate(
            returning = GatedAuthSession.Stub(
                statusCode = 200,
                headers = mapOf("set-auth-token" to "session-2"),
                body = """{"token":"${Fixtures.jwt()}"}""",
            ),
        )

        val refresh = async(Dispatchers.Default) { runCatching { service.currentJwt() } }
        session.waitForGatedArrival()

        // Launch signOut on its own real thread and release the parked
        // network call right after, WITHOUT awaiting signOut first — that
        // asymmetry is load-bearing (see the class doc).
        val signOutJob = async(Dispatchers.Default) { service.signOut() }
        session.releaseGate()

        refresh.await()
        signOutJob.await()

        val signedOut = service.state.value == AuthState.SignedOut
        val leftoverSession = storage.load(TokenSlot.SESSION_TOKEN) != null || storage.load(TokenSlot.JWT) != null
        return signedOut && leftoverSession
    }

    @Test
    fun `many races never resurrect a cleared session under real parallel dispatchers`() = runBlocking {
        val iterations = 500
        var resurrections = 0
        withTimeout(60.seconds) {
            repeat(iterations) {
                if (raceOnce()) resurrections++
            }
        }
        assertEquals(0, resurrections, "$resurrections/$iterations races resurrected a cleared session")
    }

    /**
     * Proves the reentrant path [AuthService.currentJwt] -> `refreshJwt`
     * (private) does not deadlock under real, heavy concurrent contention
     * on a single shared [AuthService]. [SessionState]
     * guards its fields with a plain JVM monitor precisely so that this
     * call chain never needs to hold a lock across the other's execution —
     * see [SessionState]'s KDoc. A naive fix wrapping the whole of
     * [AuthService.currentJwt] (or [AuthService.refreshJwt]) in a single
     * [kotlinx.coroutines.sync.Mutex.withLock] would deadlock the first time
     * this ran, since `Mutex` is not reentrant; this test would hang past
     * its timeout against that design instead of completing.
     */
    @Test
    fun `currentJwt calling refreshJwt does not deadlock under heavy real concurrency`() = runBlocking {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)

        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-1"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.signIn("dj", "pw")
        assertTrue(service.isSignedIn)

        val concurrency = 200
        // Invalidated once, from this coroutine, before the storm — not
        // once per launched coroutine. A per-coroutine invalidate would
        // race an unrelated peer's just-committed cachedJwt (any coroutine
        // can null the *shared* cache), which is a real but orthogonal
        // property of invalidateJwt() shared by the pre-#16 code, and would
        // muddy this test's one claim with spurious failures that have
        // nothing to do with reentrancy. Every coroutine below still forces
        // its own currentJwt -> refreshJwt round trip on its first
        // attempt (nothing has populated the cache yet), so this is an
        // upper bound on the number of /auth/token calls made.
        repeat(concurrency) {
            session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        }
        service.invalidateJwt()

        withTimeout(30.seconds) {
            val jobs = (1..concurrency).map {
                async(Dispatchers.Default) { runCatching { service.currentJwt() } }
            }
            val results = jobs.awaitAll()
            assertTrue(
                results.all { it.isSuccess },
                "unexpected failures: ${results.mapNotNull { it.exceptionOrNull() }}",
            )
        }
    }
}
