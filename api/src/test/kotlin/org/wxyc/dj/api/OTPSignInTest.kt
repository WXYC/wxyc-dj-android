package org.wxyc.dj.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.wxyc.dj.api.support.Fixtures
import org.wxyc.dj.api.support.GatedAuthSession

/**
 * Drives the email one-time-code sign-in path (issue #4): identifier ->
 * optional lookup -> mailed code -> verify. Pins the wire shapes, the two
 * distinct error vocabularies the three endpoints speak, and that a code
 * sign-in lands in exactly the state a password sign-in lands in — because
 * both run the same [AuthService.completeSignIn] orchestration behind the
 * same [AuthWireClient.establishSession] wire handling. Ported from
 * `OTPSignInTests.swift`, trimmed to what this module has: there is no
 * offline/network transport split here (that's an iOS issue-#106 Sentry
 * concern `AuthError` explicitly collapses to a single [AuthError.NetworkFailure]),
 * and there is no per-request cookie flag to assert on (OkHttp's cookie
 * suppression is a client-level `CookieJar.NO_COOKIES`, already pinned by
 * `CookielessHttpClientFactoryTest`).
 */
class OTPSignInTest {
    private val config = Configuration.localDevelopment

    private fun makeService(session: GatedAuthSession): Pair<AuthService, InMemoryTokenStorage> {
        val storage = InMemoryTokenStorage()
        return AuthService(config, storage, session) to storage
    }

    private fun bodyAsJson(request: okhttp3.Request): Map<String, kotlinx.serialization.json.JsonElement> {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun enqueueLookup(session: GatedAuthSession, email: String?) {
        val value = email?.let { "\"$it\"" } ?: "null"
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"email":$value}"""))
    }

    private fun enqueueSendOk(session: GatedAuthSession) {
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"success":true}"""))
    }

    private fun enqueueVerifyHandshake(session: GatedAuthSession, sessionToken: String = "session-abc") {
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to sessionToken), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 200, body = """{"token":"${Fixtures.jwt()}"}"""))
    }

    // MARK: - Resolving the identifier

    /**
     * An email identifier costs **one** round-trip, not two. Backend-Service's
     * lookup resolver opens with `if (identifier.includes('@')) return identifier;`
     * — it echoes an email-shaped identifier back unchanged — so asking it is
     * provably a no-op. [SignInIdentifier] classifies on the same `@`.
     */
    @Test
    fun `an email identifier skips the lookup entirely`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        enqueueSendOk(session)

        val destination = service.sendLoginCode("juana@wxyc.org")

        assertEquals(1, session.recordedRequests.size)
        assertTrue(session.recordedRequests[0].url.encodedPath.endsWith("/auth/email-otp/send-verification-otp"))
        assertEquals("juana@wxyc.org", destination.email)
    }

    /**
     * A username needs the resolver, and the address it returns is what the
     * code is mailed to — not the username the DJ typed.
     */
    @Test
    fun `a username is resolved before the code is sent`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        enqueueLookup(session, email = "juana@wxyc.org")
        enqueueSendOk(session)

        val destination = service.sendLoginCode("juana")

        assertEquals(2, session.recordedRequests.size)
        assertTrue(session.recordedRequests[0].url.encodedPath.endsWith("/auth/wxyc/lookup-email"))
        assertEquals("juana", bodyAsJson(session.recordedRequests[0])["identifier"]?.jsonPrimitive?.content)
        assertTrue(session.recordedRequests[1].url.encodedPath.endsWith("/auth/email-otp/send-verification-otp"))
        val sendBody = bodyAsJson(session.recordedRequests[1])
        assertEquals("juana@wxyc.org", sendBody["email"]?.jsonPrimitive?.content)
        assertEquals("sign-in", sendBody["type"]?.jsonPrimitive?.content)
        assertEquals("juana@wxyc.org", destination.email)
    }

    /**
     * `{"email": null}` is the one failure this flow can name precisely, and
     * it must stop before mailing anything. The copy says "username", not
     * "username or email": an identifier holding an `@` never reaches the
     * lookup at all.
     */
    @Test
    fun `an unknown username fails without sending a code`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        enqueueLookup(session, email = null)

        val thrown = runCatching { service.sendLoginCode("nobody") }.exceptionOrNull()

        assertEquals(AuthError.Rejected("No account matches that username"), thrown)
        assertEquals(1, session.recordedRequests.size) // no send followed
    }

    /**
     * The lookup speaks `{error: …}`, which the better-auth `{message, code}`
     * decoder cannot decode at all — so its failures must be mapped by
     * status, never by reaching for a body message that will always be null.
     */
    @Test
    fun `a rate limited lookup says rate limited not rejected`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 429, body = """{"error":"Too many requests, please try again later."}"""),
        )

        val thrown = runCatching { service.sendLoginCode("juana") }.exceptionOrNull()

        assertEquals(AuthError.RateLimited, thrown)
    }

    /**
     * The send leg is a better-auth route, so unlike the lookup it carries a
     * readable `{message, code}` — and its 400 must render that message
     * rather than hide behind "Server error (400)". This is the payoff
     * [SignInIdentifier] exists for: `dj@wxyc` holds an `@`, so it classifies
     * as an email, skips the lookup, and reaches better-auth's `z.email()`.
     */
    @Test
    fun `a typo'd email gets the server's own reason not a server error prefix`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 400, body = """{"message":"Invalid email","code":"INVALID_EMAIL"}"""),
        )

        val thrown = runCatching { service.sendLoginCode("dj@wxyc") }.exceptionOrNull()

        assertEquals(AuthError.Rejected("Invalid email"), thrown)
        assertEquals(1, session.recordedRequests.size) // lookup skipped on the @
    }

    // MARK: - Verifying the code

    /**
     * The code path lands in exactly the state the password path lands in,
     * because it runs the same [AuthService.completeSignIn] orchestration
     * behind the same [AuthWireClient.establishSession] wire handling.
     */
    @Test
    fun `verifying a code reaches signed in`() = runTest {
        val session = GatedAuthSession()
        val (service, storage) = makeService(session)
        enqueueVerifyHandshake(session)

        service.signInWithCode("juana@wxyc.org", "123456")

        val state = service.state.value as? AuthState.SignedIn ?: fail("expected SignedIn, got ${service.state.value}")
        assertEquals("dj", state.payload?.role)
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))

        val verify = session.recordedRequests[0]
        assertTrue(verify.url.encodedPath.endsWith("/auth/sign-in/email-otp"))
        val verifyBody = bodyAsJson(verify)
        assertEquals("juana@wxyc.org", verifyBody["email"]?.jsonPrimitive?.content)
        assertEquals("123456", verifyBody["otp"]?.jsonPrimitive?.content)
    }

    /**
     * Separators survive a paste from a mail client. The server's alphabet is
     * provably `0-9`, so discarding non-digits can only ever drop characters
     * a real code cannot contain.
     */
    @Test
    fun `a pasted code is normalized to digits`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        enqueueVerifyHandshake(session)

        service.signInWithCode("juana@wxyc.org", " 12 34-56 ")

        assertEquals("123456", bodyAsJson(session.recordedRequests[0])["otp"]?.jsonPrimitive?.content)
    }

    /**
     * Each documented code gets copy naming its own recovery, and the three
     * read differently. `TOO_MANY_ATTEMPTS` is stubbed at 403:
     * `atomicVerifyOTP` raises it as `FORBIDDEN` while the other two are
     * `BAD_REQUEST`.
     */
    @Test
    fun `each rejection code names its own recovery`() = runTest {
        data class Case(val status: Int, val code: String, val serverMessage: String, val expected: String)
        val cases = listOf(
            Case(400, "INVALID_OTP", "Invalid OTP", "That code isn't right. Check it and try again."),
            Case(400, "OTP_EXPIRED", "OTP expired", "That code has expired. Request a new one."),
            Case(403, "TOO_MANY_ATTEMPTS", "Too many attempts", "Too many incorrect attempts. Request a new code."),
        )
        for (case in cases) {
            val session = GatedAuthSession()
            val (service, storage) = makeService(session)
            session.enqueueInstant(
                GatedAuthSession.Stub(
                    statusCode = case.status,
                    body = """{"message":"${case.serverMessage}","code":"${case.code}"}""",
                ),
            )

            service.signInWithCode("juana@wxyc.org", "000000")

            assertEquals(AuthState.SignedOut, service.state.value)
            assertEquals(case.expected, service.lastError.value?.message)
            assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        }
    }

    /**
     * A code this app has never heard of degrades to the server's own words
     * rather than throwing or rendering a generic failure — the same
     * forward-compatible posture [SignInIdentifier] takes.
     */
    @Test
    fun `an unrecognized code falls back to the server's message`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 400, body = """{"message":"Account suspended","code":"SOME_FUTURE_CODE"}"""),
        )

        service.signInWithCode("juana@wxyc.org", "123456")

        assertEquals(AuthError.Rejected("Account suspended"), service.lastError.value)
    }

    /** The JWT leg is deliberately unchanged and still renders a 429 as a server failure — this is the verify leg's own 429, not that one. */
    @Test
    fun `a rate limited verify says rate limited`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 429, body = """{"error":"Too many requests"}"""))

        service.signInWithCode("juana@wxyc.org", "123456")

        assertEquals(AuthState.SignedOut, service.state.value)
        assertEquals(AuthError.RateLimited, service.lastError.value)
    }

    // MARK: - The JWT leg behaves identically on both credentials

    /**
     * Issue #3's transient/terminal split is orchestration, not wire
     * handling, so it must hold for a code sign-in exactly as for a
     * password one. This is the assertion that would catch
     * [AuthService.completeSignIn] being re-implemented per route rather
     * than shared.
     */
    @Test
    fun `a transient JWT failure after a code enters the pending window`() = runTest {
        val session = GatedAuthSession()
        val (service, storage) = makeService(session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 503, body = "{}"))

        service.signInWithCode("juana@wxyc.org", "123456")

        assertEquals(AuthState.SignedIn(null), service.state.value) // pending, session kept
        assertEquals("session-abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    /** ...and a 401 on the JWT leg stays terminal, leaving nothing behind. */
    @Test
    fun `a 401 on the JWT leg after a code is terminal`() = runTest {
        val session = GatedAuthSession()
        val (service, storage) = makeService(session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 200, headers = mapOf("set-auth-token" to "session-abc"), body = "{}"),
        )
        session.enqueueInstant(GatedAuthSession.Stub(statusCode = 401, body = "{}"))

        service.signInWithCode("juana@wxyc.org", "123456")

        assertEquals(AuthState.SignedOut, service.state.value)
        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
    }

    // MARK: - Error lifecycle

    /**
     * [AuthService.lastError] is cleared at [AuthService.signIn]'s entry and
     * in [AuthService.signOut], and nowhere else — so without an explicit
     * clear, a failed code verify would still be set when a caller switches
     * to the password form, showing OTP copy under a form that never
     * produced it.
     */
    @Test
    fun `clearLastError lets the caller retire a stale message`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        session.enqueueInstant(
            GatedAuthSession.Stub(statusCode = 400, body = """{"message":"Invalid OTP","code":"INVALID_OTP"}"""),
        )

        service.signInWithCode("juana@wxyc.org", "000000")
        assertTrue(service.lastError.value != null)

        service.clearLastError()
        assertNull(service.lastError.value)
    }

    /** sendLoginCode must not touch [AuthState] — no session exists yet. */
    @Test
    fun `sendLoginCode does not enter the signing-in state`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        enqueueSendOk(session)

        service.sendLoginCode("juana@wxyc.org")

        assertEquals(AuthState.Unknown, service.state.value)
    }

    // MARK: - Transport classification

    /**
     * `recordingFailure`'s catch-all — [AuthService.sendLoginCode] /
     * [AuthService.resendLoginCode]'s own flatten site — must record the
     * same [AuthError.NetworkFailure] classification [AuthService.completeSignIn]'s
     * leg 1 does, and rethrow the *original* exception untouched (never
     * wrapped in an `AuthError`). Driven with no stub queued, which makes
     * [GatedAuthSession] itself throw an `IOException` — a real transport
     * failure, not a stubbed HTTP response.
     */
    @Test
    fun `sendLoginCode transport failure records a network failure and rethrows the original`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        // No stub queued: the send-verification-otp request fails at the
        // transport layer (an email identifier skips the lookup entirely).

        val thrown = runCatching { service.sendLoginCode("juana@wxyc.org") }.exceptionOrNull()

        assertTrue(thrown is java.io.IOException, "expected the original IOException, got $thrown")
        val recorded = service.lastError.value
        assertTrue(recorded is AuthError.NetworkFailure, "expected NetworkFailure, got $recorded")
    }

    /** The complement, driven through [AuthService.resendLoginCode] so both callers of `recordingFailure` are covered. */
    @Test
    fun `resendLoginCode transport failure records a network failure and rethrows the original`() = runTest {
        val session = GatedAuthSession()
        val (service, _) = makeService(session)
        // No stub queued.

        val thrown = runCatching { service.resendLoginCode("juana@wxyc.org") }.exceptionOrNull()

        assertTrue(thrown is java.io.IOException, "expected the original IOException, got $thrown")
        val recorded = service.lastError.value
        assertTrue(recorded is AuthError.NetworkFailure, "expected NetworkFailure, got $recorded")
    }
}
