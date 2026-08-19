package org.wxyc.dj.api

/**
 * [AuthService]'s state, observed as a `StateFlow<AuthState>`. [Unknown] is
 * the pre-restore state and matters as its own case, not a synonym for
 * [SignedOut]: an app that cannot yet tell signed-in from signed-out must
 * render neither screen while `restoreSession()` is still in flight.
 *
 * [SignedIn.payload] is the decoded JWT when one is in hand, or `null` for
 * "session established, JWT pending" (issue #53) — a real session token was
 * issued but the `/auth/token` exchange failed transiently. The session
 * token is the credential; the JWT is a derived, re-mintable artifact, so a
 * pending window is still signed in. Nothing reads the payload off the state
 * ([isSignedIn] keys on the case), so `null` is a benign sentinel and the
 * JWT is obtained lazily on first use by `currentJwt()`.
 *
 * Mirrors iOS's `AuthService.State`.
 */
sealed interface AuthState {
    data object Unknown : AuthState

    data object SignedOut : AuthState

    data object SigningIn : AuthState

    data class SignedIn(val payload: JwtPayload?) : AuthState
}

/** Whether this state represents an authenticated DJ. */
val AuthState.isSignedIn: Boolean get() = this is AuthState.SignedIn
