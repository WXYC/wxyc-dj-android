package org.wxyc.dj.api

/**
 * Failure modes [AuthService] can surface. Distinguishes causes that read
 * differently to a DJ even when they share an HTTP status family: [Rejected]
 * (400/403 — the server named a reason, e.g. an unverified address or a
 * malformed email) is not [InvalidCredentials] (401 — the password was
 * wrong), and neither is [ServerFailure] (every other non-2xx, rendered
 * behind a "Server error" prefix that both of the above deliberately avoid,
 * since the DJ mistyped or their account needs attention rather than the
 * backend being at fault). [RateLimited] is carried separately for the same
 * reason: a 429 calls for waiting, not retyping a password. Mirrors
 * `AuthError` in `AuthService.swift`, trimmed to what this module ports —
 * the offline/network transport split (iOS issue #106) is a Sentry-message
 * sanitization concern this module has no consumer for yet, so it collapses
 * to a single [NetworkFailure].
 *
 * Message formatting lives on [Throwable.message] rather than a separate
 * `localizedMessage` property, since [Exception] already carries one.
 */
sealed class AuthError(message: String) : Exception(message) {
    object InvalidCredentials : AuthError("Incorrect username or email, or password.")

    data class NetworkFailure(val detail: String) : AuthError("Network error: $detail")

    object MissingSessionToken : AuthError("Sign-in did not return a session token.")

    data class ServerFailure(val status: Int, val serverMessage: String?) :
        AuthError("Server error ($status)" + (serverMessage?.let { ": $it" } ?: "") + ".")

    /**
     * The server understood the request and refused it for a stated reason
     * the DJ can act on — a malformed email, an unverified address, a
     * rejected origin. Distinct from [InvalidCredentials] (asserts the
     * password itself was wrong) and from [ServerFailure] (reads as a
     * backend fault); folding either in would have a DJ retyping a correct
     * password against a problem retyping cannot fix, or hiding a message
     * server actually meant them to read.
     */
    data class Rejected(val serverMessage: String?) :
        AuthError(serverMessage?.takeIf { it.isNotEmpty() }?.let { "$it." } ?: "Sign-in was rejected.")

    object RateLimited : AuthError("Too many attempts. Wait a few minutes and try again.")

    object NotSignedIn : AuthError("You are signed out.")
}
