package org.wxyc.dj.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.wxyc.dj.api.AuthService

/**
 * Backs [LoginScreen] (issue #8, port of `WXYCDJ/Auth/LoginViewModel.swift`).
 * Owns the form fields for both credentials this screen offers -- a mailed
 * one-time code (the path it leads with, ADR 0006) and a password -- and the
 * [LoginStage] machine that switches between them, so the stage machine and
 * the trim/normalize logic are unit-testable without driving Compose.
 *
 * **One error surface, not one per credential.** [AuthService] records
 * every credential failure into `lastError` the same way, whichever of
 * [submit], [submitCode], [requestCode], or [resendCode] produced it, so
 * [LoginUiState.errorMessage] is refreshed from that single field after
 * each of those four calls settles -- never from a second, call-specific
 * store. An earlier iOS design had the code-request leg report *only* by
 * throwing, which forced a second store here and a coalesce between the
 * two; that produced a real defect where a failed resend went unrendered
 * **and** wiped the message already on screen, because [AuthService.sendLoginCode]
 * clears `lastError` on entry before [AuthService.resendLoginCode] could
 * even run. Reading the same field every time is what keeps a resend
 * failure from being able to erase a message it didn't produce.
 *
 * The initial [LoginUiState.errorMessage] is seeded from
 * `authService.lastError` at construction, not left `null` -- this screen
 * is the [org.wxyc.dj.api.AuthState.SignedOut] branch of the app's auth
 * gate, so by the time it appears a session may already have died
 * elsewhere (`currentJwt()`'s 401 demotion sets `lastError` on its way to
 * swapping the signed-in shell for this screen). A stage rendering only
 * failures produced by its own buttons would greet that revocation in
 * silence.
 *
 * [isSigningIn] and [isSendingCode] are tracked locally rather than derived
 * from `authService.state` -- see [LoginUiState]'s KDoc for why that is
 * equivalent for this screen's own calls and simpler to reason about.
 *
 * **Each of the four actions runs its work in [viewModelScope] and then
 * `join()`s it -- the scope decides the work's lifetime, the `join` only
 * decides how long the caller waits.** They were once plain `suspend fun`s
 * that ran directly in whatever scope called them, which left the lifetime
 * to the caller -- and [LoginScreen], reasonably enough, started them from a
 * `rememberCoroutineScope()`. That scope belongs to the *composition* and is
 * cancelled the moment the screen leaves it, which a configuration change
 * does routinely. Because this view model outlives the composition, the
 * in-flight flag it had set stayed set: `isSigningIn` stuck true means
 * [LoginUiState.canSubmit] is false forever, so **rotating the phone
 * mid-sign-in permanently disabled the login form** until the process died.
 * The same held for [isSendingCode], which gates both code submission and
 * resend, and the damage was worse than a dead button: a half-finished
 * sign-in had already spent a request against a per-IP budget the control
 * room shares.
 *
 * `launch(viewModelScope) { … }.join()` fixes that without giving up an
 * awaitable API. Cancelling the caller's scope cancels only the `join`; the
 * launched job is a child of [viewModelScope], so it runs to completion and
 * still writes its result. A rotation therefore no longer interrupts a
 * sign-in at all -- it settles underneath the recomposed screen. Tests keep
 * calling these as ordinary suspend functions and still observe a finished
 * call. Pinned by the two `survives the composition scope that started it`
 * tests, which cancel a stand-in composition scope mid-flight and assert
 * that the *second* leg of a two-leg call still reaches the server -- the
 * one observation that can tell "survived" from "abandoned".
 *
 * Each action's gate is checked, and its in-flight flag set, **synchronously
 * before** the `launch`. That is what makes the double-tap guard real: with
 * both inside the coroutine, two taps dispatched before either body ran
 * would each see the gate open and both fire.
 *
 * [sleep] is the resend-cooldown's delay, as a seam so tests can drive it
 * without waiting 30 real seconds (issue #8 invariant 12). Only the sleeper
 * is injectable, not the duration itself: nothing needs to vary the 30s
 * window, only to skip waiting through it. It is deliberately **not** a
 * parameter on the `@Inject` constructor -- Dagger resolves every
 * constructor parameter from the graph and does not fall back to a Kotlin
 * default value when no binding exists (confirmed empirically: a default
 * lambda there fails `:app:kspDebugKotlin` with a `MissingBinding` on
 * `Function2<Long, Continuation<Unit>, ?>`), so a second, `internal`
 * constructor is the seam instead. Hilt only ever sees the single
 * `@Inject`-annotated one; the second exists purely for tests, which
 * construct this class directly rather than through the Hilt graph anyway.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authService: AuthService,
) : ViewModel() {

    private var sleep: suspend (Long) -> Unit = { millis -> delay(millis) }

    /** Test-only seam -- see the class KDoc for why this isn't a primary-constructor parameter. */
    internal constructor(authService: AuthService, sleep: suspend (Long) -> Unit) : this(authService) {
        this.sleep = sleep
    }

    private val _uiState = MutableStateFlow(
        LoginUiState(errorMessage = authService.lastError.value?.message),
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** Cancelled and replaced on every call to [startResendCooldown], so only the latest window can ever close it. */
    private var cooldownJob: Job? = null

    fun onIdentifierChanged(value: String) {
        _uiState.update { it.copy(identifier = value) }
    }

    /** Password is stored untouched -- whitespace in a password is significant. */
    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    /**
     * Normalizes as the DJ types: non-digits dropped, length capped at 6,
     * matching dj-site's `OTPCodeForm`. Safe because the server's alphabet
     * is provably `0-9` (better-auth's default OTP generator is
     * `generateRandomString(6, "0-9")`, and Backend-Service overrides the
     * length but not the generator), so this can only ever discard
     * characters a real code cannot contain -- which is what lets a code
     * pasted as "123 456" work.
     */
    fun onCodeChanged(value: String) {
        val normalized = value.filter(Char::isDigit).take(MAX_CODE_LENGTH)
        _uiState.update { it.copy(code = normalized) }
    }

    /**
     * Ask for a code and advance to [LoginStage.AwaitingCode]. Stays on
     * [LoginStage.Identifier] if anything fails, with the reason in
     * [LoginUiState.errorMessage].
     */
    suspend fun requestCode() {
        val current = _uiState.value
        if (!current.canRequestCode) return
        val trimmedIdentifier = current.identifier.trim()
        _uiState.update { it.copy(isSendingCode = true) }

        viewModelScope.launch {
            val destination = try {
                authService.sendLoginCode(trimmedIdentifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            _uiState.update {
                it.copy(
                    isSendingCode = false,
                    errorMessage = authService.lastError.value?.message,
                    stage = if (destination != null) LoginStage.AwaitingCode(destination) else it.stage,
                    code = if (destination != null) "" else it.code,
                )
            }
            // Unconditional -- on failure as much as success, since the
            // failure most worth throttling is the one saying the DJ is
            // already over the limit. Leaving the button live after a 429
            // invites spending the rest of a shared per-IP budget on
            // requests that cannot succeed.
            startResendCooldown()
        }.join()
    }

    /**
     * Mail another code to the address [requestCode] already resolved,
     * subject to the cooldown. Goes through [AuthService.resendLoginCode]
     * rather than re-running [requestCode]: a username identifier would
     * otherwise re-run `POST /auth/wxyc/lookup-email` for an answer that
     * cannot have changed -- the identifier field isn't even on screen by
     * then -- spending two slots of a shared per-IP budget where one would
     * do, in exactly the moment (slow mail, an expired code) a DJ taps
     * most.
     */
    suspend fun resendCode() {
        val current = _uiState.value
        val destination = (current.stage as? LoginStage.AwaitingCode)?.destination ?: return
        if (!current.canResendCode) return
        _uiState.update { it.copy(isSendingCode = true) }

        viewModelScope.launch {
            try {
                authService.resendLoginCode(destination.email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Swallowed deliberately -- there is no caller left to react
                // to the throw. AuthService has already recorded the failure
                // into lastError, read immediately below, so it still reaches
                // the screen rather than being silently lost.
            }

            _uiState.update { it.copy(isSendingCode = false, errorMessage = authService.lastError.value?.message) }
            startResendCooldown()
        }.join()
    }

    /**
     * Redeem the typed code. Lands in exactly the state a password sign-in
     * lands in -- same session, same JWT exchange, same state machine,
     * since [AuthService.signInWithCode] and [AuthService.signIn] both run
     * `completeSignIn`.
     */
    suspend fun submitCode() {
        val current = _uiState.value
        val destination = (current.stage as? LoginStage.AwaitingCode)?.destination ?: return
        if (!current.canSubmitCode) return
        val code = current.code
        _uiState.update { it.copy(isSigningIn = true) }

        viewModelScope.launch {
            authService.signInWithCode(destination.email, code)
            _uiState.update { it.copy(isSigningIn = false, errorMessage = authService.lastError.value?.message) }
        }.join()
    }

    suspend fun submit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        // Trim whitespace on the identifier only -- keyboards (and password
        // managers) routinely emit a trailing space on autofill and the
        // server would 401. Password intentionally untrimmed; whitespace in
        // a password is significant.
        val trimmedIdentifier = current.identifier.trim()
        val password = current.password
        _uiState.update { it.copy(isSigningIn = true) }

        viewModelScope.launch {
            authService.signIn(trimmedIdentifier, password)
            _uiState.update { it.copy(isSigningIn = false, errorMessage = authService.lastError.value?.message) }
        }.join()
    }

    /**
     * Close the resend window, and schedule the reopen that flips
     * [LoginUiState.isResendOnCooldown] back -- the observable-state
     * mechanism issue #8 invariant 12 requires, in place of a computed
     * `now() - sentAt >= cooldown`. [viewModelScope] outlives any single
     * call to [requestCode]/[resendCode] and is cancelled automatically
     * when this view model is cleared, so the window closes on its own
     * even if the DJ has since left this screen.
     */
    private fun startResendCooldown() {
        cooldownJob?.cancel()
        _uiState.update { it.copy(isResendOnCooldown = true) }
        cooldownJob = viewModelScope.launch {
            sleep(RESEND_COOLDOWN_MILLIS)
            _uiState.update { it.copy(isResendOnCooldown = false) }
        }
        // Deliberately does NOT reset on a stage change -- see usePassword/
        // useCode/changeIdentifier below. The cooldown protects a per-IP
        // budget the control room shares, so switching accounts must not
        // refill it.
    }

    /** Reached from the identifier stage's "Sign in with password instead". */
    fun usePassword() = enterStage(LoginStage.Password)

    /** Reached from the password stage's "Email me a code instead". */
    fun useCode() = enterStage(LoginStage.Identifier)

    /**
     * Back out of the code step to correct a mistyped identifier -- the
     * only recourse when the DJ typo'd an *email*, since the server cannot
     * report that (an unknown address still answers "code sent").
     */
    fun changeIdentifier() {
        _uiState.update { it.copy(code = "") }
        enterStage(LoginStage.Identifier)
    }

    /**
     * Every stage change clears the error. `authService.lastError` is
     * otherwise cleared only when a sign-in starts or a sign-out completes,
     * so without this a failed code verify would still be set when the DJ
     * switches to the password form -- showing "That code isn't right"
     * under a form that never produced a code. The cooldown deliberately
     * does *not* reset here -- see [LoginUiState.canRequestCode]'s doc.
     */
    private fun enterStage(next: LoginStage) {
        authService.clearLastError()
        _uiState.update { it.copy(stage = next, errorMessage = null) }
    }

    private companion object {
        /** Digits only, matching dj-site's `OTPCodeForm` and the server's provably `0-9` alphabet. */
        const val MAX_CODE_LENGTH = 6

        /**
         * better-auth allows 3 sends per 60s on the send route, so 30s
         * leaves headroom (2/min) while still letting a DJ whose mail is
         * slow try again without feeling stuck. It also protects the
         * Express limiter's 10 per 15 minutes, keyed per-**IP** -- a budget
         * the control room shares.
         */
        const val RESEND_COOLDOWN_MILLIS = 30_000L
    }
}
