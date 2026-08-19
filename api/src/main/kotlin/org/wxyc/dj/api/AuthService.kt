package org.wxyc.dj.api

import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call

@Serializable
private data class PersistedJwtPayload(
    val sub: String?,
    val email: String?,
    val role: String?,
    val exp: Double,
)

private fun JwtPayload.toPersisted() = PersistedJwtPayload(sub, email, role, exp.epochSecond + exp.nano / 1_000_000_000.0)

private fun PersistedJwtPayload.toJwtPayload() = JwtPayload(sub, email, role, epochSecondsToInstant(exp))

private fun epochSecondsToInstant(seconds: Double): Instant =
    Instant.ofEpochMilli((seconds * 1000).toLong())

private val persistedPayloadJson = Json { ignoreUnknownKeys = true }

/**
 * Owns the better-auth session lifecycle for a single signed-in DJ: takes an
 * identifier + password, calls whichever sign-in route the identifier
 * belongs to ([SignInIdentifier] picks `/auth/sign-in/email` or
 * `/auth/sign-in/username`), exchanges the session for a short-lived JWT via
 * `GET /auth/token`, and refreshes the JWT before it expires.
 *
 * **Concurrency contract**: this class holds mutable state ([sessionToken],
 * [cachedJwt], [sessionEpoch], [state]) with no internal lock. It mirrors
 * iOS's `@MainActor`-isolated `AuthService` — correct only when every call
 * is made from a single confined coroutine dispatcher (e.g. a ViewModel's
 * `viewModelScope`, backed by `Dispatchers.Main.immediate`), so that two
 * concurrent callers interleave *only* at suspension points (an awaited
 * network call), never mid-mutation. That is exactly the interleaving
 * [refreshJwt]'s session-epoch guard and [currentJwt]'s token-identity guard
 * exist for: without single-dispatcher confinement those guards would not
 * be sufficient on their own, and with it a lock would be redundant. `:api`
 * test suites reproduce the same confinement with a single [kotlinx.coroutines.test.TestDispatcher]
 * via `runTest`.
 *
 * The central design fact — the session token is the credential, the JWT is
 * a derived, re-mintable artifact — is what makes sign-in and restore each
 * *two legs* (establish the session, then exchange it for a JWT) that fail
 * differently. This class directly holds the transient/terminal JWT split,
 * the session-generation guard, the token-identity guard, and the offline
 * grace window (see the per-method docs below and [OfflineSessionPolicy]);
 * `@`-based identifier routing lives in [SignInIdentifier] and status
 * mapping in [AuthWireClient]. Mirrors `AuthService.swift`.
 */
class AuthService(
    private val configuration: Configuration,
    private val tokenStorage: TokenStorage,
    callFactory: Call.Factory,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val wire = AuthWireClient(configuration, callFactory)

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<AuthError?>(null)
    val lastError: StateFlow<AuthError?> = _lastError.asStateFlow()

    /** Whether a DJ is currently signed in. */
    val isSignedIn: Boolean get() = state.value.isSignedIn

    private var sessionToken: String? = null
    private var cachedJwt: Pair<String, JwtPayload>? = null

    /**
     * Monotonic session-*generation* counter (invariant 3, iOS issue #66).
     * Bumped whenever the session is cleared ([clearLocalSession]) or a
     * brand-new one is established ([completeSignIn]'s leg 1), but **not**
     * on a `set-auth-token` rotation, which re-issues the bearer *within*
     * the same generation. [refreshJwt] captures it before awaiting
     * `/auth/token` and re-checks it after, so a sign-out (or re-sign-in)
     * landing during an in-flight refresh can't be resurrected by the
     * success path re-persisting a stale rotation/JWT. Rotation
     * deliberately keeps the same generation, so a benign overlapping
     * double-refresh — where only the bearer rotated — still resolves a
     * JWT for both callers.
     */
    private var sessionEpoch: Long = 0L

    suspend fun restoreSession() {
        if (_state.value != AuthState.Unknown) return
        val stored = try {
            tokenStorage.load(TokenSlot.SESSION_TOKEN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Couldn't even read storage — treat as no usable session.
            _state.value = AuthState.SignedOut
            return
        }
        if (stored == null) {
            _state.value = AuthState.SignedOut
            return
        }
        sessionToken = stored
        try {
            val payload = refreshJwt()
            _state.value = AuthState.SignedIn(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError.NotSignedIn) {
            // Terminal: the stored session bearer was rejected (401). The
            // token is dead — clear it so it doesn't linger and 401 on
            // every launch.
            clearLocalSession()
            _state.value = AuthState.SignedOut
        } catch (e: Exception) {
            // Transient (5xx / network / undecodable body): the session may
            // still be good, but we couldn't confirm it. Consult the
            // offline grace policy (issue #57): within the window, restore
            // the cached identity; otherwise fall through to the login
            // screen. Either way tokens are retained — a transient failure
            // never clears them, so a later online launch can still recover
            // the session (success) or terminally clear it (401).
            val decision = OfflineSessionPolicy.decide(
                hasStoredSession = sessionToken != null,
                cachedPayload = loadPersistedPayload(),
                lastValidatedAtEpochSeconds = loadLastValidatedAt(),
                nowEpochSeconds = epochSeconds(clock()),
            )
            _state.value = when (decision) {
                is OfflineSessionPolicy.Decision.SignedIn -> AuthState.SignedIn(decision.payload)
                OfflineSessionPolicy.Decision.SignedOut -> AuthState.SignedOut
            }
        }
    }

    /**
     * Sign a DJ in with the credentials they use on dj.wxyc.org.
     *
     * @param identifier A username **or** an email address — [SignInIdentifier]
     *   routes it to the endpoint that can accept it. Expected pre-trimmed.
     * @param password Passed through verbatim; whitespace is significant.
     */
    suspend fun signIn(identifier: String, password: String) {
        completeSignIn {
            val parsed = SignInIdentifier(identifier)
            wire.establishSession(parsed.path, parsed.encodedBody(password))
        }
    }

    /**
     * The credential-agnostic half of signing in: everything from
     * [AuthState.SigningIn] through to a settled signed-in/signed-out state,
     * parameterized only by how leg 1 obtains a session token. A future
     * credential route (issue #4's OTP sign-in) supplies its own
     * `establishing` closure and inherits every invariant below rather than
     * restating — possibly wrongly — the transient/terminal split (2), the
     * generation bump (3), and the leave-no-trace rollback.
     */
    private suspend fun completeSignIn(establishing: suspend () -> String) {
        _state.value = AuthState.SigningIn
        _lastError.value = null

        // Drop any prior DJ's grace anchors before establishing a new
        // session (issue #57). They belong to the *previous* identity; if
        // this sign-in's JWT leg fails transiently (leg 2 below), it enters
        // the pending window WITHOUT persisting fresh anchors, so a stale
        // payload left here would later let an offline restore pair this
        // session's bearer with the old DJ's cached identity.
        clearGraceAnchors()

        // Leg 1 — establish the session. Any failure here is terminal:
        // there is no session to keep, so roll back and stop before the JWT
        // exchange.
        val token: String
        try {
            token = establishing()
            tokenStorage.save(token, TokenSlot.SESSION_TOKEN)
            sessionToken = token
            sessionEpoch++
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError) {
            clearLocalSession()
            _lastError.value = e
            _state.value = AuthState.SignedOut
            return
        } catch (e: Exception) {
            clearLocalSession()
            _lastError.value = AuthError.NetworkFailure(e.message ?: e.toString())
            _state.value = AuthState.SignedOut
            return
        }

        // Leg 2 — exchange the session for a JWT (invariant 2, iOS issue
        // #53). A transient failure here is NOT terminal: the session is
        // real, so keep it and obtain the JWT lazily on first use. Only a
        // 401 (session bearer rejected) is terminal and rolls back.
        try {
            val payload = refreshJwt()
            _state.value = AuthState.SignedIn(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError.NotSignedIn) {
            clearLocalSession()
            _lastError.value = AuthError.NotSignedIn
            _state.value = AuthState.SignedOut
        } catch (e: Exception) {
            // Transient: keep the session token (incl. any value rotated
            // and persisted by refreshJwt) and enter the pending window.
            // lastError was already cleared above, so pending carries no
            // error — same as restoreSession's transient arm.
            _state.value = AuthState.SignedIn(payload = null)
        }
    }

    suspend fun signOut() {
        sessionToken?.let { token -> bestEffort { wire.signOut(token) } }
        clearLocalSession()
        _state.value = AuthState.SignedOut
        _lastError.value = null
    }

    /**
     * The shared lazy-refresh chokepoint for every authed caller — a
     * cached, non-expiring-soon JWT returns instantly; otherwise this
     * refreshes and, on success, leaves [state] untouched (the payload is
     * never read off it, so promoting a pending sign-in has no consumer and
     * would only reintroduce state churn on every roughly-hourly refresh).
     *
     * A 401 here demotes to [AuthState.SignedOut] — **but only if the
     * rejected bearer is still the current session token** (invariant 4,
     * iOS issue #53's token-identity guard). Two callers can share this
     * service and overlap a refresh, each bound to its own bearer at the
     * top of the call; if a concurrent re-sign-in (or a rotation captured
     * by the other in-flight refresh) replaced [sessionToken] while this
     * one awaited, the 401 is for a superseded bearer, and clobbering would
     * erase a valid new session. Report this stale attempt as
     * unauthenticated instead and let the caller retry the live session.
     */
    suspend fun currentJwt(): String {
        cachedJwt?.let { (token, payload) ->
            if (Duration.between(clock(), payload.expiration) > REFRESH_LEEWAY) return token
        }
        val tokenAtRefresh = sessionToken ?: throw AuthError.NotSignedIn
        try {
            refreshJwt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError.NotSignedIn) {
            if (sessionToken == tokenAtRefresh) {
                clearLocalSession()
                _lastError.value = AuthError.NotSignedIn
                _state.value = AuthState.SignedOut
            }
            throw e
        }
        // A transient throw above propagates unchanged: state stays
        // signed-in and the caller sees its normal retryable error.
        return cachedJwt?.first ?: throw AuthError.NotSignedIn
    }

    suspend fun invalidateJwt() {
        cachedJwt = null
        bestEffort { tokenStorage.clear(TokenSlot.JWT) }
    }

    /** Drop the last sign-in error without touching the session. */
    fun clearLastError() {
        _lastError.value = null
    }

    private suspend fun refreshJwt(): JwtPayload {
        val token = sessionToken ?: throw AuthError.NotSignedIn
        val epochAtRefresh = sessionEpoch
        val exchange = wire.fetchToken(token)
        if (exchange.statusCode !in 200..299) {
            if (exchange.statusCode == 401) throw AuthError.NotSignedIn
            throw AuthError.ServerFailure(exchange.statusCode, serverMessage = null)
        }
        // Bail before persisting anything if the session was cleared or
        // replaced (signOut / re-sign-in) while this awaited (invariant 3).
        // Otherwise this 2xx resurrects a torn-down session: the rotated
        // bearer and the new JWT would be written back after sign-out
        // cleared them, leaving state == SignedOut but a live token that
        // the next cold launch silently signs back in. A rotation captured
        // by a concurrent refresh keeps the same generation, so a benign
        // overlapping double-refresh still persists for both callers.
        if (sessionEpoch != epochAtRefresh) throw AuthError.NotSignedIn
        exchange.rotatedSessionToken?.let { rotated ->
            sessionToken = rotated
            bestEffort { tokenStorage.save(rotated, TokenSlot.SESSION_TOKEN) }
        }
        val jwtToken = exchange.jwtToken ?: throw AuthError.NetworkFailure("undecodable /auth/token body")
        val payload = try {
            JwtDecoder.decode(jwtToken)
        } catch (e: JwtDecodeError) {
            throw AuthError.NetworkFailure("undecodable JWT payload")
        }
        cachedJwt = jwtToken to payload
        bestEffort { tokenStorage.save(jwtToken, TokenSlot.JWT) }
        // A successful exchange is a confirmed server contact: reset the
        // offline grace window and refresh the durable payload (issue #57).
        // This is the single chokepoint for sign-in, cold-launch restore,
        // and the lazy refresh — every path that proves the session is
        // live flows here.
        persistValidationAnchors(payload)
        return payload
    }

    /**
     * Forget every local trace of the session: the in-memory bearer, the
     * cached JWT, and everything in storage. Used by every terminal path
     * that must leave nothing behind — a failed sign-in, a 401 on restore,
     * a lazy-refresh 401 demotion, and sign-out — so a revoked or
     * intentionally-cleared session can never be silently revived by the
     * next cold-launch restore. `restoreSession`'s *transient* arm
     * deliberately does not call this: an offline blip keeps a
     * previously-good token so it can retry.
     */
    private suspend fun clearLocalSession() {
        sessionToken = null
        cachedJwt = null
        sessionEpoch++
        bestEffort { tokenStorage.clearAll() }
    }

    private suspend fun persistValidationAnchors(payload: JwtPayload) {
        val now = epochSeconds(clock())
        bestEffort { tokenStorage.save(now.toString(), TokenSlot.LAST_VALIDATED_AT) }
        val json = runCatching {
            persistedPayloadJson.encodeToString(PersistedJwtPayload.serializer(), payload.toPersisted())
        }.getOrNull()
        if (json != null) {
            bestEffort { tokenStorage.save(json, TokenSlot.PAYLOAD) }
        }
    }

    /**
     * Drop the offline grace anchors without touching the session/JWT
     * slots. Used at sign-in entry so a new session can't inherit the
     * previous DJ's cached identity.
     */
    private suspend fun clearGraceAnchors() {
        bestEffort { tokenStorage.clear(TokenSlot.LAST_VALIDATED_AT) }
        bestEffort { tokenStorage.clear(TokenSlot.PAYLOAD) }
    }

    private suspend fun loadLastValidatedAt(): Double? {
        val raw = try {
            tokenStorage.load(TokenSlot.LAST_VALIDATED_AT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return raw.toDoubleOrNull()
    }

    private suspend fun loadPersistedPayload(): JwtPayload? {
        val json = try {
            tokenStorage.load(TokenSlot.PAYLOAD)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return runCatching {
            persistedPayloadJson.decodeFromString(PersistedJwtPayload.serializer(), json).toJwtPayload()
        }.getOrNull()
    }

    companion object {
        private val REFRESH_LEEWAY: Duration = Duration.ofSeconds(60)
    }
}

private fun epochSeconds(instant: Instant): Double = instant.epochSecond + instant.nano / 1_000_000_000.0

/**
 * Run [block] for its side effect only, swallowing any failure — the
 * pattern every best-effort storage write/clear in this file uses.
 * [CancellationException] is a subtype of [Exception] on the JVM, so a bare
 * `catch (e: Exception)` would swallow it too; that would break structured
 * concurrency (a cancelled caller must see the cancellation propagate, not
 * have it silently absorbed here), so it is caught and rethrown first.
 */
private suspend fun bestEffort(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Best-effort: a storage failure degrades gracefully.
    }
}
