@file:OptIn(ExperimentalCoroutinesApi::class)

package org.wxyc.dj.ui.login

import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClientFactory
import org.wxyc.dj.api.InMemoryTokenStorage
import org.wxyc.dj.api.isSignedIn
import org.wxyc.dj.api.LoginCodeDestination

/**
 * Pins [LoginViewModel]: the three-stage machine and its error-clearing on
 * every transition, the single error surface every credential route shares,
 * the resend cooldown's observable-state/starts-on-failure/survives-a-stage-
 * change trio, the send-in-flight gate blocking code submission, and the
 * trim/normalize logic on both text fields. Ported from
 * `WXYCDJTests/Auth/LoginViewModelTests.swift`, trimmed to what this repo's
 * `:api` actually has: no issue-#106 error-reporting seam exists here yet
 * (out of scope per this repo's `CLAUDE.md` -- "Analytics or crash reporting
 * of any kind ... until the phase-2 telemetry issue lands"), so this suite
 * does not port the iOS `shouldReport`/pending-JWT-report tests.
 *
 * Drives a real [AuthService] against [MockWebServer] -- the same pattern
 * `:api`'s `ApiClientTest`/`OTPSignInTest` already use -- rather than a
 * fake, so a wire-shape regression in `:api` would fail here too, not just
 * mask behind a hand-rolled stub. [MainDispatcherRule] routes
 * `Dispatchers.Main` (and so [LoginViewModel]'s `viewModelScope`) through
 * the same [kotlinx.coroutines.test.TestDispatcher] `runTest` uses, so the
 * resend-cooldown coroutine can be driven with [advanceUntilIdle] instead of
 * a real wait; the cooldown's own delay is a [ManualSleeper] gate (a
 * single-slot [Channel]) rather than a real `delay()``, mirroring iOS's
 * `ManualSleeper` -- tolerant of `elapse()` arriving before the view model's
 * coroutine reaches the sleep, so no test depends on winning a race with a
 * task it just started.
 */
class LoginViewModelTest {

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

    /** A view model with an instant (real-delay-free) cooldown sleeper, for tests that don't drive the cooldown themselves. */
    private fun makeViewModel(): Pair<LoginViewModel, AuthService> {
        val configuration = configuration()
        val authService = AuthService(configuration, InMemoryTokenStorage(), CookielessHttpClientFactory.create(configuration))
        return LoginViewModel(authService) { /* no-op: nothing awaits this in tests that ignore the cooldown */ } to authService
    }

    private fun makeViewModel(sleeper: ManualSleeper): Pair<LoginViewModel, AuthService> {
        val configuration = configuration()
        val authService = AuthService(configuration, InMemoryTokenStorage(), CookielessHttpClientFactory.create(configuration))
        return LoginViewModel(authService, sleeper::sleep) to authService
    }

    private fun enqueueSignInHandshake(sessionToken: String = "session-abc") {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("set-auth-token", sessionToken).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"${jwt()}"}"""))
    }

    // MARK: - Password-path gating and trimming

    @Test
    fun `canSubmit is false when identifier is empty`() {
        val (viewModel, _) = makeViewModel()
        viewModel.onPasswordChanged("hunter2")

        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `canSubmit is false when password is empty`() {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("juana")

        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `canSubmit is false when the identifier is only whitespace`() {
        // submit() trims, so a whitespace-only identifier would post an
        // empty one and come back "Incorrect username or email, or
        // password" -- a credential verdict on a field the DJ never filled
        // in. Gate on the trimmed value so the button stays disabled
        // instead.
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("   \n ")
        viewModel.onPasswordChanged("hunter2")

        assertFalse(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `canSubmit is true when both fields are populated`() {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("juana")
        viewModel.onPasswordChanged("hunter2")

        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `submit trims the identifier but preserves the password for a username`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("  juana \n")
        viewModel.onPasswordChanged("  hunter2 ")
        enqueueSignInHandshake()

        viewModel.submit()

        val request = server.takeRequest()
        assertEquals("/auth/sign-in/username", request.requestUrl!!.encodedPath)
        val body = bodyAsJson(request.body.readUtf8())
        assertEquals("juana", body["username"])
        assertEquals("  hunter2 ", body["password"])
    }

    @Test
    fun `submit trims the identifier but preserves the password for an email`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged(" juana@wxyc.org ")
        viewModel.onPasswordChanged("  hunter2 ")
        enqueueSignInHandshake()

        viewModel.submit()

        val request = server.takeRequest()
        assertEquals("/auth/sign-in/email", request.requestUrl!!.encodedPath)
        val body = bodyAsJson(request.body.readUtf8())
        assertEquals("juana@wxyc.org", body["email"])
        assertEquals("  hunter2 ", body["password"])
    }

    @Test
    fun `submit with empty fields is a no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()

        viewModel.submit()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `second submit while signing in is a no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("juana")
        viewModel.onPasswordChanged("hunter2")
        // No response enqueued: the request will hang against MockWebServer
        // until this test ends, which is exactly the "still in flight" state
        // this test needs -- mirrors iOS's HangingRequestSession technique
        // without a bespoke fake.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val firstSubmit = launch { viewModel.submit() }
        advanceUntilIdle()
        // The first submit has reached MockWebServer (isSigningIn flipped
        // synchronously before the network await), so a second call must
        // see the gate closed and do nothing.
        assertTrue(viewModel.uiState.value.isSigningIn)
        assertFalse(viewModel.uiState.value.canSubmit)
        // Block (briefly, with a real bound) for the first request to
        // actually land on MockWebServer's socket -- OkHttp's own thread
        // pool delivers it independently of the TestDispatcher's virtual
        // time, so reading server.requestCount immediately after
        // advanceUntilIdle() would race it.
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        viewModel.submit()

        // No second request within a short real bound -- the guard held.
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        firstSubmit.cancel()
    }

    // MARK: - Code sign-in: the path the screen starts on

    @Test
    fun `the screen starts on the identifier stage`() {
        val (viewModel, _) = makeViewModel()

        assertEquals(LoginStage.Identifier, viewModel.uiState.value.stage)
    }

    @Test
    fun `a username advances to the code step without disclosing the resolved address`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val (viewModel, _) = makeViewModel()
            viewModel.onIdentifierChanged("  juana \n")
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"juana@wxyc.org"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

            viewModel.requestCode()

            val stage = viewModel.uiState.value.stage as LoginStage.AwaitingCode
            assertEquals("juana@wxyc.org", stage.destination.email)
            assertNull(stage.destination.typedEmail)
            assertNull(viewModel.uiState.value.errorMessage)
            val lookup = server.takeRequest()
            assertEquals("juana", bodyAsJson(lookup.body.readUtf8())["identifier"])
        }

    @Test
    fun `a typed email is echoed back to the DJ`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        viewModel.requestCode()

        val stage = viewModel.uiState.value.stage as LoginStage.AwaitingCode
        assertEquals("juana@wxyc.org", stage.destination.email)
        assertEquals("juana@wxyc.org", stage.destination.typedEmail)
        assertEquals(1, server.requestCount) // no lookup for an email
    }

    @Test
    fun `a failed request stays on the identifier step with a reason`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("nobody")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":null}"""))

        viewModel.requestCode()

        assertEquals(LoginStage.Identifier, viewModel.uiState.value.stage)
        assertEquals("No account matches that username.", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSendingCode)
    }

    @Test
    fun `a transport failure surfaces rather than silently failing`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.shutdown() // every request against this server now fails at the transport layer

        viewModel.requestCode()

        assertEquals(LoginStage.Identifier, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.errorMessage != null)
    }

    // MARK: - The code field

    @Test
    fun `the code field normalizes as it is typed`() {
        val (viewModel, _) = makeViewModel()

        viewModel.onCodeChanged("12 34 56")
        assertEquals("123456", viewModel.uiState.value.code)

        viewModel.onCodeChanged("123-456")
        assertEquals("123456", viewModel.uiState.value.code)

        viewModel.onCodeChanged("12345678")
        assertEquals("123456", viewModel.uiState.value.code)

        viewModel.onCodeChanged("abc123")
        assertEquals("123", viewModel.uiState.value.code)
    }

    @Test
    fun `submitting is gated on a six digit code`() {
        val (viewModel, _) = makeViewModel()

        viewModel.onCodeChanged("123")
        assertFalse(viewModel.uiState.value.canSubmitCode)

        viewModel.onCodeChanged("123456")
        assertTrue(viewModel.uiState.value.canSubmitCode)
    }

    // MARK: - The resend cooldown (issue #8 invariant 12)

    /**
     * A 429 is the case most worth throttling -- leaving the button live
     * after one invites spending the rest of a shared per-IP budget on
     * requests that cannot succeed. Unconditional: this pins the cooldown
     * starting on a **failure**, not just a success.
     */
    @Test
    fun `the cooldown starts even when the send fails`() = runTest(mainDispatcherRule.testDispatcher) {
        // A ManualSleeper that is never elapsed, so the window stays open for
        // the assertions. The no-op sleeper this once used closed the window
        // the moment the cooldown coroutine was scheduled, which made the test
        // pass only while nothing happened to run it first -- it asserted the
        // scheduler's ordering, not the cooldown.
        val (viewModel, _) = makeViewModel(ManualSleeper())
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many requests"}"""))

        viewModel.requestCode()

        assertTrue(viewModel.uiState.value.errorMessage != null)
        assertFalse(viewModel.uiState.value.canRequestCode)
        assertFalse(viewModel.uiState.value.canResendCode)
    }

    @Test
    fun `the resend window closes then reopens on its own`() = runTest(mainDispatcherRule.testDispatcher) {
        val sleeper = ManualSleeper()
        val (viewModel, _) = makeViewModel(sleeper)
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        assertTrue(viewModel.uiState.value.canResendCode) // nothing sent yet
        viewModel.requestCode()
        assertFalse(viewModel.uiState.value.canResendCode)

        sleeper.elapse()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canResendCode)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.resendCode()
        assertEquals(2, server.requestCount)
    }

    /**
     * The single-error-surface pin. The failure mode: the DJ mistypes a
     * code, sees "That code isn't right.", taps "Send a new code", and the
     * send 429s. `AuthService.sendLoginCode` clears `lastError` on entry, so
     * if the failure did not land back in the same field the DJ would watch
     * the existing error *disappear* with nothing taking its place.
     */
    @Test
    fun `a failed resend replaces the error on screen rather than erasing it`() = runTest(mainDispatcherRule.testDispatcher) {
        val sleeper = ManualSleeper()
        val (viewModel, _) = makeViewModel(sleeper)
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.requestCode()

        // A wrong code first.
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"message":"Invalid OTP","code":"INVALID_OTP"}"""),
        )
        viewModel.onCodeChanged("000000")
        viewModel.submitCode()
        assertEquals("That code isn't right. Check it and try again.", viewModel.uiState.value.errorMessage)

        // Then a resend that is rate-limited.
        sleeper.elapse()
        advanceUntilIdle()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many requests"}"""))
        viewModel.resendCode()

        assertEquals(
            "Too many attempts. Wait a few minutes and try again.",
            viewModel.uiState.value.errorMessage,
        )
    }

    /**
     * The cooldown protects a **per-IP** allowance the control room shares,
     * so "Send login code -> Use a different account -> Send login code"
     * must not refill it.
     */
    @Test
    fun `changing the identifier does not bypass the cooldown`() = runTest(mainDispatcherRule.testDispatcher) {
        val sleeper = ManualSleeper()
        val (viewModel, _) = makeViewModel(sleeper)
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.requestCode()

        viewModel.changeIdentifier()
        viewModel.onIdentifierChanged("jessica@wxyc.org")

        assertEquals(LoginStage.Identifier, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.canRequestCode) // still inside the window

        viewModel.requestCode()
        assertEquals(1, server.requestCount) // guarded -- no second send
    }

    /**
     * No `resendStrategy` server-side means a resend **replaces** the
     * stored OTP; verifying the first mail's code mid-resend would come
     * back `INVALID_OTP` for a code the DJ read correctly and burn one of
     * the 5 allowed attempts.
     */
    @Test
    fun `sign-in is blocked while a resend is in flight`() = runTest(mainDispatcherRule.testDispatcher) {
        val sleeper = ManualSleeper()
        val (viewModel, _) = makeViewModel(sleeper)
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.requestCode()
        viewModel.onCodeChanged("123456")
        assertTrue(viewModel.uiState.value.canSubmitCode)

        sleeper.elapse()
        advanceUntilIdle()

        // Park the resend mid-flight: no response enqueued, so
        // AuthService.resendLoginCode suspends on the network call.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val inFlight = launch { viewModel.resendCode() }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSendingCode)
        assertFalse(viewModel.uiState.value.canSubmitCode)

        inFlight.cancel()
    }

    /** A session that died elsewhere (e.g. `currentJwt()`'s 401 demotion) must be explained on this, the first stage the DJ sees. */
    @Test
    fun `the first stage still explains a session that died elsewhere`() = runTest(mainDispatcherRule.testDispatcher) {
        val configuration = configuration()
        val authService = AuthService(configuration, InMemoryTokenStorage(), CookielessHttpClientFactory.create(configuration))
        // A sign-in failure is the reachable way to set lastError before
        // constructing the view model -- the 401 demotion sets the same
        // field by the same route.
        server.enqueue(MockResponse().setResponseCode(401))
        authService.signIn("juana", "wrong")
        advanceUntilIdle()

        val viewModel = LoginViewModel(authService) { }

        assertEquals(LoginStage.Identifier, viewModel.uiState.value.stage)
        assertEquals(authService.lastError.value?.message, viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage != null)
    }

    /**
     * The defect this guards: without an explicit clear, a failed code
     * verify would still be set when the DJ taps "Sign in with password
     * instead" -- rendering "That code isn't right" under a form that
     * never produced a code.
     */
    @Test
    fun `switching to the password form retires the code error`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, authService) = makeViewModel()
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.requestCode()

        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"message":"Invalid OTP","code":"INVALID_OTP"}"""),
        )
        viewModel.onCodeChanged("000000")
        viewModel.submitCode()
        assertTrue(authService.lastError.value != null)

        viewModel.usePassword()

        assertEquals(LoginStage.Password, viewModel.uiState.value.stage)
        assertNull(authService.lastError.value)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    /**
     * A resend must not re-run the username lookup: the resolved address
     * cannot have changed, and re-asking would spend two slots of a per-IP
     * budget the control room shares.
     */
    @Test
    fun `resending skips the lookup it already paid for`() = runTest(mainDispatcherRule.testDispatcher) {
        val sleeper = ManualSleeper()
        val (viewModel, _) = makeViewModel(sleeper)
        viewModel.onIdentifierChanged("juana")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"juana@wxyc.org"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.requestCode()
        assertEquals(2, server.requestCount) // lookup + send

        sleeper.elapse()
        advanceUntilIdle()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.resendCode()

        assertEquals(3, server.requestCount) // one send, no second lookup
        server.takeRequest() // the lookup
        server.takeRequest() // the first send
        val resend = server.takeRequest()
        assertEquals("/auth/email-otp/send-verification-otp", resend.requestUrl!!.encodedPath)
        // And it still goes to the resolved address, which was never rendered.
        assertEquals("juana@wxyc.org", bodyAsJson(resend.body.readUtf8())["email"])
    }

    @Test
    fun `changing the identifier returns to the start and drops the code`() = runTest(mainDispatcherRule.testDispatcher) {
        val (viewModel, _) = makeViewModel()
        viewModel.onIdentifierChanged("juana@wxyc.org")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        viewModel.requestCode()
        viewModel.onCodeChanged("123456")

        viewModel.changeIdentifier()

        assertEquals(LoginStage.Identifier, viewModel.uiState.value.stage)
        assertEquals("", viewModel.uiState.value.code)
    }

    // MARK: - Invariant 11: a looked-up email is never displayed

    @Test
    fun `displayTarget renders the typed email when one was typed`() {
        val destination = destinationFor(email = "juana@wxyc.org", typedEmail = "juana@wxyc.org")

        assertEquals("juana@wxyc.org", displayTarget(destination, "your registered email"))
    }

    @Test
    fun `displayTarget renders the generic wording when the email was looked up`() {
        val destination = destinationFor(email = "juana@wxyc.org", typedEmail = null)

        assertEquals("your registered email", displayTarget(destination, "your registered email"))
    }

    /** Drives a real lookup through [AuthService] to obtain a [LoginCodeDestination] -- its constructor is `internal` to `:api` and cannot be fabricated from this module. */
    // MARK: - Surviving the composition that started the work

    /**
     * `LoginScreen` starts every one of these calls from a
     * `rememberCoroutineScope()`, which belongs to the **composition** and is
     * cancelled the moment the screen leaves it — a configuration change, most
     * commonly. The view model outlives that, so any in-flight flag it set is
     * still set when the screen recomposes against the same instance.
     *
     * That makes the failure mode permanent rather than transient: with
     * `isSigningIn` stuck true, [LoginUiState.canSubmit] is false forever and
     * the sign-in button never re-enables until the process dies. Rotating the
     * phone mid-sign-in would brick the login screen.
     *
     * The fix is for the work to run in `viewModelScope`, whose lifetime is the
     * view model's own — so a rotation doesn't cancel the sign-in at all, and
     * it settles normally underneath the recomposed screen.
     */
    @Test
    fun `a sign-in survives the composition scope that started it being torn down`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val (viewModel, _) = makeViewModel()
            viewModel.onIdentifierChanged("juana")
            viewModel.onPasswordChanged("hunter2")
            // Two legs: establish the session, then exchange it for a JWT.
            // Leg 2 is the discriminator -- it can only be reached by a
            // coroutine that survived the cancellation below.
            enqueueSignInHandshake()

            // Stands in for LoginScreen's rememberCoroutineScope().
            val compositionScope = CoroutineScope(mainDispatcherRule.testDispatcher)
            compositionScope.launch { viewModel.submit() }
            advanceUntilIdle()
            assertTrue("precondition: leg 1 is in flight", viewModel.uiState.value.isSigningIn)

            // The configuration change: the composition, and its scope, go away.
            compositionScope.cancel()

            assertTrue(
                "the JWT exchange must still happen -- a rotation must not abandon " +
                    "a sign-in mid-flight, nor strand isSigningIn true forever",
                drainUntil { server.requestCount >= 2 },
            )
            assertTrue(drainUntil { !viewModel.uiState.value.isSigningIn })
        }

    /** The same property for the code-request leg, whose stuck flag disables both submit and resend. */
    @Test
    fun `a code request survives the composition scope that started it being torn down`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val (viewModel, _) = makeViewModel()
            // A username, so this is also a two-leg call: resolve the address,
            // then mail the code. Leg 2 is again the discriminator.
            viewModel.onIdentifierChanged("juana")
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"juana@wxyc.org"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

            val compositionScope = CoroutineScope(mainDispatcherRule.testDispatcher)
            compositionScope.launch { viewModel.requestCode() }
            advanceUntilIdle()
            assertTrue("precondition: the lookup is in flight", viewModel.uiState.value.isSendingCode)

            compositionScope.cancel()

            assertTrue(
                "the code must still be sent -- a rotation must not abandon the " +
                    "request after it has already spent a lookup against a per-IP budget",
                drainUntil { server.requestCount >= 2 },
            )
            assertTrue(drainUntil { viewModel.uiState.value.stage is LoginStage.AwaitingCode })
            assertFalse(viewModel.uiState.value.isSendingCode)
        }

    /**
     * Drains the test scheduler repeatedly, with short real sleeps in between,
     * until [predicate] holds or [timeoutMillis] of real time elapses.
     *
     * The two tests above are the only ones here that have to span real
     * network I/O *after* the point they take their measurement:
     * `MockWebServer` answers on OkHttp's own threads, and the resumed
     * continuation lands back on the virtual-time test dispatcher. Neither
     * half suffices alone -- `advanceUntilIdle()` does not wait for real I/O,
     * and a real sleep does not run the continuations that I/O queued -- so
     * this alternates the two. Returns whether [predicate] ever held, so the
     * caller asserts on it rather than on a timeout.
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

    private fun destinationFor(email: String, typedEmail: String?): LoginCodeDestination {
        val configuration = configuration()
        val authService = AuthService(configuration, InMemoryTokenStorage(), CookielessHttpClientFactory.create(configuration))
        return if (typedEmail == null) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"$email"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
            runBlocking { authService.sendLoginCode("someusername") }
        } else {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
            runBlocking { authService.sendLoginCode(typedEmail) }
        }
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

/** Parses a JSON object body into a flat `Map<String, String?>` for simple field assertions -- every wire body this suite sends is flat, and a JSON `null` reads back as a Kotlin `null`. */
private fun bodyAsJson(raw: String): Map<String, String?> {
    val element = Json.parseToJsonElement(raw).jsonObject
    return element.mapValues { (_, value) ->
        when (value) {
            is JsonNull -> null
            is JsonPrimitive -> value.content
            else -> null
        }
    }
}

/**
 * Hand-driven stand-in for a real delay, so the resend-cooldown tests
 * assert the window opening and closing without waiting 30 real seconds.
 * Mirrors iOS's `ManualSleeper`: a single-slot [Channel] rather than a
 * latch-and-continuation pair, but with the same tolerance for [elapse]
 * arriving before [sleep] is ever called -- `trySend` buffers into the
 * channel's capacity-1 slot regardless of whether a receiver is parked yet.
 */
class ManualSleeper {
    private val channel = Channel<Unit>(capacity = 1)

    suspend fun sleep(@Suppress("UNUSED_PARAMETER") millis: Long) {
        channel.receive()
    }

    /** Let the pending cooldown finish. */
    fun elapse() {
        channel.trySend(Unit)
    }
}
