package org.wxyc.dj.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SignInRequestDto(val username: String, val password: String)

@Serializable
private data class EmailSignInRequestDto(val email: String, val password: String)

/**
 * Which better-auth sign-in endpoint a DJ's typed identifier belongs to.
 *
 * The login form takes one field, but better-auth exposes two credential
 * routes and they are **not** interchangeable. `/sign-in/username` validates
 * the identifier's *shape* before it looks up any user — Backend-Service
 * registers the plugin with no `usernameValidator` override, so better-auth's
 * default `/^[a-zA-Z0-9_.]+$/` applies and an `@` is rejected outright with
 * `422 INVALID_USERNAME` ("Username is invalid"), before any credential
 * check. Every identifier posted there means an email can never sign in at
 * any password (iOS issue #97).
 *
 * ## Why the predicate is `@`, and not a full email regex
 *
 * 1. `@` is the exact complement of the failure: it is the one character the
 *    username validator forbids, so no account can hold an `@` in its
 *    username and no identifier is plausible on both routes.
 * 2. It gives a **typo'd** email a better error. `dj@wxyc` is "not an email"
 *    to a regex, which sends it back to the username route for the same
 *    misleading 422. Routed on `@` it reaches `/sign-in/email`, which
 *    validates with `z.email()` and answers `400 INVALID_EMAIL` — a message
 *    that describes what the DJ actually got wrong.
 * 3. dj-site's `isValidEmail` lives in `@wxyc/shared`, which has no Kotlin
 *    distribution; porting its regex here would fork a shared validation
 *    source of truth into a copy free to drift silently.
 *
 * The converse is deliberate too: `dj-name` (a hyphen is outside the
 * username character class but says nothing about email) stays on the
 * username route for an honest 422 rather than being mistranslated into
 * "Invalid email".
 *
 * Owns both the path and the body key, so the two cannot drift apart —
 * better-auth reads `ctx.body.username` on one route and `ctx.body.email` on
 * the other. Mirrors iOS's `SignInIdentifier`.
 */
sealed class SignInIdentifier {
    abstract val raw: String

    data class Username(override val raw: String) : SignInIdentifier()

    data class Email(override val raw: String) : SignInIdentifier()

    /** Path component appended to the auth base URL. */
    val path: String
        get() = when (this) {
            is Username -> "sign-in/username"
            is Email -> "sign-in/email"
        }

    /**
     * The JSON body for this route. The password is passed through
     * untouched — whitespace in a password is significant, so nothing on
     * this path may normalize it.
     */
    internal fun encodedBody(password: String): String = when (this) {
        is Username -> Json.encodeToString(SignInRequestDto.serializer(), SignInRequestDto(raw, password))
        is Email -> Json.encodeToString(EmailSignInRequestDto.serializer(), EmailSignInRequestDto(raw, password))
    }

    companion object {
        /**
         * Classify a DJ's typed identifier. Expects the caller to have
         * trimmed it already; total for every input, including the empty
         * string.
         */
        operator fun invoke(raw: String): SignInIdentifier =
            if (raw.contains("@")) Email(raw) else Username(raw)
    }
}
