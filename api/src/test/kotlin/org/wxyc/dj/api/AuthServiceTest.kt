package org.wxyc.dj.api

import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.wxyc.dj.api.support.Fixtures
import org.wxyc.dj.api.support.GatedAuthSession

/**
 * Drives [AuthService] against a stubbed wire ([GatedAuthSession]): sign-in
 * success grabs the session token from the `set-auth-token` header, the JWT
 * exchange decodes claims, 401 on sign-in maps to [AuthError.InvalidCredentials],
 * and [AuthService.restoreSession] brings a stored token back to
 * [AuthState.SignedIn]. Pins the six issue-#3 invariants: the transient/
 * terminal JWT split, the session-generation guard, the token-identity
 * guard, the offline grace window, `@`-based identifier routing, and status
 * mapping. Ported from `AuthServiceTests.swift`, trimmed to what this issue
 * scopes (the connectivity-outcome-hook and OTP-route tests are out of
 * scope — issues #71/#106 and #4 respectively).
 *
 * Every test runs inside [runTest], whose single [kotlinx.coroutines.test.TestDispatcher]
 * gives the same single-confined-dispatcher guarantee [AuthService]'s KDoc
 * requires — the concurrency tests below rely on it exactly as production
 * code relies on a ViewModel's `Dispatchers.Main.immediate`.
 */
class AuthServiceTest {
    private val config = Configuration.localDevelopment

    private fun signInHandshake(session: GatedAuthSession, sessionToken: String = "session-abc") {
        session.enqueueInstant(
            GatedAuthSession.Stub(
                statusCode = 200,
                headers = mapOf("set-auth-token" to sessionToken),
                body = "{}",
            ),
        )
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""),
        )
    }

    // MARK: - Core sign-in

    @Test
    fun `sign-in success reaches signed in`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        signInHandshake(session)

        service.signIn("dj", "pw")

        val state = service.state.value as? AuthState.SignedIn ?: fail("expected SignedIn, got ${service.state.value}")
        // The happy path carries a real payload (not the issue-#53 pending nil).
        assertEquals("dj", state.payload?.role)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `isSignedIn tracks state`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)

        assertFalse(service.isSignedIn) // Unknown at construction

        signInHandshake(session)
        service.signIn("dj", "pw")
        assertTrue(service.isSignedIn)

        service.signOut()
        assertFalse(service.isSignedIn)
    }

    @Test
    fun `sign-in failure surfaces invalid credentials`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 401, body = """{"message":"nope"}"""))

        service.signIn("dj", "wrong")

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals(AuthError.InvalidCredentials, service.lastError.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
    }

    // MARK: - Invariant 6: identifier routing (issue #97)

    @Test
    fun `sign-in routes the identifier to the endpoint that accepts it`() = runTest {
        for ((identifier, expectedPath, expectedKey) in listOf(
            Triple("juana", "/auth/sign-in/username", "username"),
            Triple("juana@wxyc.org", "/auth/sign-in/email", "email"),
        )) {
            val session = GatedAuthSession()
            val storage = InMemoryTokenStorage()
            val service = AuthService(config, storage, session)
            signInHandshake(session)

            service.signIn(identifier, "pw")

            val signInRequest = session.recordedRequests.first()
            assertEquals(expectedPath, signInRequest.url.encodedPath)
            val bodyJson = Json.parseToJsonElement(bodyAsString(signInRequest)).jsonObject
            assertEquals(identifier, bodyJson[expectedKey]?.jsonPrimitive?.content)
            assertEquals("pw", bodyJson["password"]?.jsonPrimitive?.content)

            val state = service.state.value as? AuthState.SignedIn ?: fail("expected SignedIn")
            assertEquals("dj", state.payload?.role)
            assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
        }
    }

    // MARK: - Status mapping

    @Test
    fun `a named refusal surfaces its own reason`() = runTest {
        for ((status, message, identifier) in listOf(
            Triple(403, "Email not verified", "juana@wxyc.org"),
            Triple(400, "Invalid email", "juana@wxyc"),
        )) {
            val session = GatedAuthSession()
            val storage = InMemoryTokenStorage()
            val service = AuthService(config, storage, session)
            session.enqueueInstant(GatedAuthSession.Stub(statusCode = status, body = """{"message":"$message"}"""))

            service.signIn(identifier, "pw")

            assertEquals(AuthState.SignedOut, service.state.value)
            assertEquals(AuthError.Rejected(message), service.lastError.value)
            assertEquals("$message.", service.lastError.value?.message)
            assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        }
    }

    @Test
    fun `rate limited sign-in says so rather than reading as a server fault`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        // The Express limiter's body is {error: ...}, which the better-auth
        // {message, code} decoder can't decode — the copy must not depend on it.
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 429, body = """{"error":"Too many requests, please try again later."}"""),
        )

        service.signIn("juana", "pw")

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals(AuthError.RateLimited, service.lastError.value)
        assertEquals("Too many attempts. Wait a few minutes and try again.", service.lastError.value?.message)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
    }

    // MARK: - Invariant 2: transient/terminal JWT split (issue #53)

    @Test
    fun `sign-in with failed JWT exchange leaves no stored token`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 401))

        service.signIn("dj", "pw")

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals(AuthError.NotSignedIn, service.lastError.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
    }

    @Test
    fun `sign-in with undecodable JWT body enters pending and keeps session`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-rotated"), body = "not json"),
        )

        service.signIn("dj", "pw")

        assertEquals(AuthState.SignedIn(null), service.state.value)
        assertNull(service.lastError.value)
        assertEquals("session-rotated", storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
    }

    @Test
    fun `sign-in with transient server error enters pending`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503))

        service.signIn("dj", "pw")

        assertEquals(AuthState.SignedIn(null), service.state.value)
        assertNull(service.lastError.value)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `sign-in with network error on JWT exchange enters pending`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        // Only leg 1 is stubbed; /auth/token throws (no more stubs).
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )

        service.signIn("dj", "pw")

        assertEquals(AuthState.SignedIn(null), service.state.value)
        assertNull(service.lastError.value)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `pending sign-in resolves via lazy JWT refresh`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503))
        service.signIn("dj", "pw")
        assertEquals(AuthState.SignedIn(null), service.state.value)
        val requestsBeforeRetry = session.recordedRequests.size

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        val token = service.currentJwt()

        assertTrue(token.isNotEmpty())
        assertTrue(service.isSignedIn)
        assertEquals(requestsBeforeRetry + 1, session.recordedRequests.size)
    }

    @Test
    fun `pending sign-in demotes to signed out on lazy 401`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503))
        service.signIn("dj", "pw")
        assertEquals(AuthState.SignedIn(null), service.state.value)

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 401))
        val thrown = runCatching { service.currentJwt() }.exceptionOrNull()
        assertEquals(AuthError.NotSignedIn, thrown)

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals(AuthError.NotSignedIn, service.lastError.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
    }

    @Test
    fun `signed-in session demotes when lazy refresh hits 401`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        signInHandshake(session)
        service.signIn("dj", "pw")
        val signedIn = service.state.value as? AuthState.SignedIn ?: fail("expected SignedIn")
        assertNotNull(signedIn.payload)

        service.invalidateJwt()
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 401))
        val thrown = runCatching { service.currentJwt() }.exceptionOrNull()
        assertEquals(AuthError.NotSignedIn, thrown)

        assertEquals(AuthState.SignedOut, service.state.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
    }

    @Test
    fun `currentJwt transient failure leaves state signed in`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503))
        service.signIn("dj", "pw")
        assertEquals(AuthState.SignedIn(null), service.state.value)

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503))
        val thrown = runCatching { service.currentJwt() }.exceptionOrNull()
        assertEquals(AuthError.ServerFailure(503, null), thrown)

        assertEquals(AuthState.SignedIn(null), service.state.value)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    // MARK: - Invariant 4: token-identity guard (issue #53's lazy-401 concurrency race)

    @Test
    fun `lazy refresh 401 for a superseded bearer does not clobber a new session`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-1", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.restoreSession()
        service.invalidateJwt()

        session.armGate(returning = GatedAuthSession.Stub(statusCode = 401))
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-2"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))

        val staleRefresh = async { runCatching { service.currentJwt() } }
        session.waitForGatedArrival()

        service.signIn("dj", "pw")
        assertTrue(service.isSignedIn)
        assertEquals("session-2", storage.load(TokenSlot.SESSION_TOKEN))

        session.releaseGate()
        staleRefresh.await()

        val state = service.state.value as? AuthState.SignedIn ?: fail("expected still SignedIn, got ${service.state.value}")
        assertNotNull(state.payload)
        assertEquals("session-2", storage.load(TokenSlot.SESSION_TOKEN))
        assertNotNull(storage.load(TokenSlot.JWT))
    }

    // MARK: - Invariant 3: session-generation guard (issue #66)

    @Test
    fun `sign-out during in-flight refresh is not resurrected by rotation header`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-1", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.restoreSession()
        service.invalidateJwt()

        // Gate the refresh's /auth/token; it returns a 2xx carrying BOTH a
        // rotation header and a valid JWT.
        session.armGate(
            returning = GatedAuthSession.Stub(
                statusCode = 200,
                headers = mapOf("set-auth-token" to "session-2"),
                body = """{"token":"${Fixtures.jwt()}"}""",
            ),
        )

        val refresh = async { runCatching { service.currentJwt() } }
        session.waitForGatedArrival()

        service.signOut()
        assertEquals(AuthState.SignedOut, service.state.value)

        session.releaseGate()
        refresh.await()

        // Load-bearing: the signed-out session stays signed out with EMPTY
        // storage — the rotation header must not resurrect it.
        assertEquals(AuthState.SignedOut, service.state.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
    }

    @Test
    fun `benign concurrent double refresh does not spuriously fail`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-1", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.restoreSession()
        service.invalidateJwt()

        // Refresh A parks at the gate (bound to session-1); it resumes
        // LAST, after refresh B has already rotated the live session.
        session.armGate(returning = GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        session.enqueueInstant(
            GatedAuthSession.Stub(
                statusCode = 200,
                headers = mapOf("set-auth-token" to "session-2"),
                body = """{"token":"${Fixtures.jwt()}"}""",
            ),
        )

        val refreshA = async { service.currentJwt() }
        session.waitForGatedArrival()

        val bToken = service.currentJwt()
        assertTrue(bToken.isNotEmpty())
        assertEquals("session-2", storage.load(TokenSlot.SESSION_TOKEN))

        session.releaseGate()
        val aToken = refreshA.await() // rethrows if A spuriously failed
        assertTrue(aToken.isNotEmpty())
        assertTrue(service.isSignedIn)
        assertEquals("session-2", storage.load(TokenSlot.SESSION_TOKEN))
    }

    // MARK: - restoreSession / signOut / rotation

    @Test
    fun `restoreSession pulls token from storage`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-existing", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))

        service.restoreSession()

        val state = service.state.value as? AuthState.SignedIn ?: fail("expected SignedIn")
        assertEquals("juana@wxyc.org", state.payload?.email)
    }

    @Test
    fun `signOut clears local state even when network call fails`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        storage.save("jwt-old", TokenSlot.JWT)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.restoreSession()

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 500))
        service.signOut()

        assertEquals(AuthState.SignedOut, service.state.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
    }

    @Test
    fun `signOut clears state and storage`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        storage.save("jwt-old", TokenSlot.JWT)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.restoreSession()

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200))
        service.signOut()

        assertEquals(AuthState.SignedOut, service.state.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))

        val signOutRequest = session.recordedRequests.last()
        assertEquals("POST", signOutRequest.method)
        assertEquals("/auth/sign-out", signOutRequest.url.encodedPath)
        assertEquals("Bearer session-abc", signOutRequest.header("Authorization"))
    }

    @Test
    fun `refreshJwt captures rotated session token from header`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-old", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(
                statusCode = 200,
                headers = mapOf("set-auth-token" to "session-new"),
                body = """{"token":"${Fixtures.jwt()}"}""",
            ),
        )

        service.restoreSession()

        assertTrue(service.state.value is AuthState.SignedIn)
        assertEquals("session-new", storage.load(TokenSlot.SESSION_TOKEN))

        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200))
        service.signOut()
        assertEquals("Bearer session-new", session.recordedRequests.last().header("Authorization"))
    }

    @Test
    fun `refreshJwt leaves session token alone when header absent`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-stable", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))

        service.restoreSession()

        assertEquals("session-stable", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `refreshJwt ignores empty rotation header`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-stable", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to ""), body = """{"token":"${Fixtures.jwt()}"}"""),
        )

        service.restoreSession()

        assertEquals("session-stable", storage.load(TokenSlot.SESSION_TOKEN))
    }

    // MARK: - Invariant 5: offline cold-launch grace window (issue #57)

    @Test
    fun `restoreSession transient without grace anchor signs out but keeps token`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-stored", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503))

        service.restoreSession()

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals("session-stored", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `restoreSession offline within window keeps cached identity`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        signInHandshake(session)
        val first = AuthService(config, storage, session)
        first.signIn("dj", "pw")
        assertTrue(first.isSignedIn)
        assertNotNull(storage.load(TokenSlot.PAYLOAD))
        assertNotNull(storage.load(TokenSlot.LAST_VALIDATED_AT))

        // Cold launch (fresh AuthService, state Unknown). No stub enqueued
        // -> the /auth/token exchange throws a transport error.
        val second = AuthService(config, storage, session)
        second.restoreSession()

        val state = second.state.value as? AuthState.SignedIn ?: fail("expected SignedIn, got ${second.state.value}")
        val payload = state.payload ?: fail("expected a cached payload, not the pending nil")
        assertEquals("42", payload.sub)
        assertEquals("juana@wxyc.org", payload.email)
        assertEquals("dj", payload.role)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
        assertNotNull(storage.load(TokenSlot.PAYLOAD))
    }

    @Test
    fun `restoreSession offline beyond window signs out`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        signInHandshake(session)
        val first = AuthService(config, storage, session)
        first.signIn("dj", "pw")

        // Backdate the anchor just past the window.
        val stale = Instant.now().epochSecond - (OfflineSessionPolicy.DEFAULT_WINDOW_SECONDS.toLong() + 60)
        storage.save(stale.toDouble().toString(), TokenSlot.LAST_VALIDATED_AT)

        val second = AuthService(config, storage, session)
        second.restoreSession()

        assertEquals(AuthState.SignedOut, second.state.value)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `restoreSession with 401 clears stored token and grace anchors`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-revoked", TokenSlot.SESSION_TOKEN)
        val payload = JwtPayload("42", "juana@wxyc.org", "dj", Instant.now().plusSeconds(600))
        storage.save(persistedPayloadJsonForTest(payload), TokenSlot.PAYLOAD)
        storage.save(Instant.now().epochSecond.toString(), TokenSlot.LAST_VALIDATED_AT)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 401))

        service.restoreSession()

        assertEquals(AuthState.SignedOut, service.state.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertNull(storage.load(TokenSlot.JWT))
        assertNull(storage.load(TokenSlot.LAST_VALIDATED_AT))
        assertNull(storage.load(TokenSlot.PAYLOAD))
    }

    @Test
    fun `restoreSession offline with corrupted anchor signs out`() = runTest {
        for (corrupt in listOf("not-a-number", "inf", "infinity", "", "Infinity", "NaN")) {
            val session = GatedAuthSession()
            val storage = InMemoryTokenStorage()
            storage.save("session-abc", TokenSlot.SESSION_TOKEN)
            val payload = JwtPayload("42", "juana@wxyc.org", "dj", Instant.now().plusSeconds(600))
            storage.save(persistedPayloadJsonForTest(payload), TokenSlot.PAYLOAD)
            storage.save(corrupt, TokenSlot.LAST_VALIDATED_AT)
            val service = AuthService(config, storage, session)

            service.restoreSession()

            assertEquals(AuthState.SignedOut, service.state.value, "corrupt anchor \"$corrupt\" should fail closed")
            assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
        }
    }

    @Test
    fun `restoreSession offline with corrupted payload signs out`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        storage.save("{ this is not valid json", TokenSlot.PAYLOAD)
        storage.save(Instant.now().epochSecond.toString(), TokenSlot.LAST_VALIDATED_AT)
        val service = AuthService(config, storage, session)

        service.restoreSession()

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `sign-in persists grace anchors and refresh updates them`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val clockMillis = LongArray(1) { 1_900_000_000_000L }
        val service = AuthService(config, storage, session, clock = { Instant.ofEpochMilli(clockMillis[0]) })
        signInHandshake(session)

        service.signIn("dj", "pw")

        val t1 = storage.load(TokenSlot.LAST_VALIDATED_AT)?.toDouble() ?: fail("expected an anchor")
        val payloadJson = storage.load(TokenSlot.PAYLOAD) ?: fail("expected a durable payload")
        assertTrue(payloadJson.contains("juana@wxyc.org"))
        assertTrue(payloadJson.contains("\"dj\""))

        // Advance the injected clock (never a wall-clock sleep) and force a
        // later refresh; the anchor must advance past t1.
        clockMillis[0] += 20_000
        service.invalidateJwt()
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
        service.currentJwt()

        val t2 = storage.load(TokenSlot.LAST_VALIDATED_AT)?.toDouble() ?: fail("expected an anchor")
        assertTrue(t2 > t1, "expected the grace window to reset on refresh: t1=$t1 t2=$t2")
    }

    @Test
    fun `sign-in clears a stale identity's grace anchors on entry`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)

        // DJ A: full sign-in seeds anchors through the production path.
        signInHandshake(session, sessionToken = "session-A")
        service.signIn("dj-a", "pw")
        assertNotNull(storage.load(TokenSlot.PAYLOAD))
        assertNotNull(storage.load(TokenSlot.LAST_VALIDATED_AT))

        // DJ B: leg 1 establishes a new session, leg 2 fails transiently
        // (only one stub, so /auth/token throws).
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-B"), body = "{}"),
        )
        service.signIn("dj-b", "pw")

        assertEquals("session-B", storage.load(TokenSlot.SESSION_TOKEN))
        assertEquals(AuthState.SignedIn(null), service.state.value)
        assertNull(storage.load(TokenSlot.PAYLOAD))
        assertNull(storage.load(TokenSlot.LAST_VALIDATED_AT))
    }

    @Test
    fun `invalidateJwt leaves durable payload intact`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        val service = AuthService(config, storage, session)
        signInHandshake(session)
        service.signIn("dj", "pw")
        assertNotNull(storage.load(TokenSlot.JWT))
        assertNotNull(storage.load(TokenSlot.PAYLOAD))

        service.invalidateJwt()

        assertNull(storage.load(TokenSlot.JWT))
        assertNotNull(storage.load(TokenSlot.PAYLOAD))
    }

    @Test
    fun `currentJwt reuses cached token when fresh`() = runTest {
        val session = GatedAuthSession()
        val storage = InMemoryTokenStorage()
        storage.save("session-abc", TokenSlot.SESSION_TOKEN)
        val service = AuthService(config, storage, session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt(expiresInSeconds = 3600)}"}"""),
        )
        service.restoreSession()

        // No more stubs queued — a second currentJwt() must reuse the cache.
        service.currentJwt()
        service.currentJwt()

        assertEquals(1, session.recordedRequests.size)
    }

    private fun bodyAsString(request: okhttp3.Request): String {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun persistedPayloadJsonForTest(payload: JwtPayload): String {
        val exp = payload.exp.epochSecond + payload.exp.nano / 1_000_000_000.0
        return """{"sub":"${payload.sub}","email":"${payload.email}","role":"${payload.role}","exp":$exp}"""
    }
}
