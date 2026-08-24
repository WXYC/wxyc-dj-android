package org.wxyc.dj.ui.login

/**
 * Everything [LoginScreen] needs to render one frame, and the gates that
 * decide which controls are enabled (issue #8, port of iOS's computed
 * properties on `LoginViewModel`). A single immutable snapshot rather than
 * several independent `StateFlow`s, so [LoginViewModel] can update several
 * related fields (e.g. clearing [code] the moment a code is sent) in one
 * atomic `copy()` with no window where a Compose recomposition could see
 * them half-updated.
 *
 * [isSigningIn] is tracked locally by [LoginViewModel] rather than derived
 * from `AuthService.state == SigningIn` -- the two are equivalent for this
 * screen's own sign-in attempts (nothing else in the app calls
 * `AuthService.signIn`/`signInWithCode` while this screen is showing), and
 * tracking it locally means the gate updates the instant a button is
 * tapped rather than waiting on a `StateFlow` collection to observe
 * `AuthService`'s own state flip.
 */
data class LoginUiState(
    val stage: LoginStage = LoginStage.Identifier,
    /** The DJ's login identifier: a username **or** an email address. */
    val identifier: String = "",
    val password: String = "",
    /** The typed one-time code. Normalized to digits and capped at 6 by [LoginViewModel.onCodeChanged]. */
    val code: String = "",
    /**
     * True while `sendLoginCode`/`resendLoginCode` is in flight. Distinct
     * from [isSigningIn] because that call establishes no session -- it
     * never reaches `AuthService.state == SigningIn`, so this screen cannot
     * borrow that state to drive its own spinner or disable its own button.
     */
    val isSendingCode: Boolean = false,
    /**
     * Whether the 30s resend cooldown is currently running. **Observable
     * state a coroutine flips, never a clock read** -- see
     * [LoginViewModel.startResendCooldown] for why a computed
     * `now() - sentAt >= cooldown` is the wrong shape under Compose too,
     * even though Compose (unlike SwiftUI) re-renders on a `StateFlow`
     * emission rather than needing an unrelated invalidation to notice time
     * passing: nothing would ever *emit* the recomputed value, so the
     * button would still latch disabled until some unrelated state change
     * happened to trigger a recomposition.
     */
    val isResendOnCooldown: Boolean = false,
    /** True while `signIn`/`signInWithCode` is in flight. */
    val isSigningIn: Boolean = false,
    /**
     * The one error the screen shows, or `null`. Sourced from
     * `AuthService.lastError` at every point this view model's own actions
     * settle -- see [LoginViewModel] for why that is one surface rather
     * than one per credential.
     */
    val errorMessage: String? = null,
) {
    private val trimmedIdentifier: String
        get() = identifier.trim()

    /**
     * Gates on the *trimmed* identifier, matching what [LoginViewModel.requestCode]
     * actually sends -- an all-whitespace field would otherwise post an
     * empty identifier and come back as a verdict on a field the DJ never
     * filled in.
     *
     * Also gated on [isResendOnCooldown], which is not obvious: this is the
     * button on the *first* stage, and "Send login code -> Use a different
     * account -> Send login code" would otherwise loop unlimited sends
     * straight past it. The budget the cooldown protects is per-IP, so
     * switching accounts does not refill it -- the gate has to follow the
     * request, not the identifier.
     */
    val canRequestCode: Boolean
        get() = trimmedIdentifier.isNotEmpty() && !isSendingCode && !isResendOnCooldown

    val canSubmit: Boolean
        get() = trimmedIdentifier.isNotEmpty() && password.isNotEmpty() && !isSigningIn

    /**
     * Blocked while a send is in flight, because a resend **replaces** the
     * stored code rather than reusing it: Backend-Service configures no
     * `resendStrategy`, so verifying the first mail's code mid-resend races
     * that write, comes back `INVALID_OTP` for a code the DJ read
     * correctly, and burns one of the 5 allowed attempts.
     */
    val canSubmitCode: Boolean
        get() = code.length == 6 && !isSigningIn && !isSendingCode

    /** Whether a fresh code may be requested yet. */
    val canResendCode: Boolean
        get() = !isSendingCode && !isResendOnCooldown
}
