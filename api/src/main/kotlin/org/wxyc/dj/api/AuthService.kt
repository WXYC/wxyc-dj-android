package org.wxyc.dj.api

import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The single home of every read and write to [AuthService]'s session
 * identity: the session token, the cached JWT, and the session-*generation*
 * epoch (issue #16). Nothing outside this class ever touches those three
 * values directly — every access is one of the methods below, and every one
 * of them is a `synchronized` block on [lock]: a plain JVM monitor, not
 * [kotlinx.coroutines.sync.Mutex].
 *
 * That choice is deliberate, not incidental, and it resolves two separate
 * traps in one move:
 *
 * **Reentrancy.** [AuthService.currentJwt] calls [AuthService.refreshJwt],
 * and [kotlinx.coroutines.sync.Mutex] is explicitly *not* reentrant — a
 * `withLock` acquired around the whole of one and held into a `withLock`
 * around the whole of the other would deadlock the very first time a lazy
 * refresh ran. This class never has that shape: no method here calls
 * another method here while still holding the lock, and no method here is
 * ever held open across [AuthService.refreshJwt]'s network await (that
 * await happens entirely *between* two independent, short-lived lock
 * acquisitions — see [readForRefresh] and [commitRefresh]). A JVM monitor
 * is reentrant by construction, so even if some future change did nest two
 * calls on the same thread, it would nest safely rather than deadlock —
 * but the actual design here needs no reentrancy at all, because nothing
 * suspends while the lock is held.
 *
 * **Visibility.** A `synchronized` block does more than serialize access:
 * per JLS 17.4.5, releasing a monitor happens-before a later thread
 * acquiring the *same* monitor. That is the specific guarantee the old,
 * lock-free field design lacked and issue #16 is about: a coroutine
 * resuming on one dispatcher thread after [AuthService.refreshJwt]'s
 * network await is not otherwise guaranteed to observe a [clear] a
 * *different* thread completed while that await was in flight, and could
 * silently act on a stale epoch. Every method below both reads and, where
 * relevant, decides-and-writes inside one critical section, so a
 * check-then-act guard (the epoch check, the token-identity check) can
 * never be split across a window another thread's mutation lands in.
 * [InMemoryTokenStorage] guards its backing map with the identical pattern
 * for the identical reason.
 *
 * None of this requires [AuthService] to run on a confined dispatcher any
 * more — see its class doc.
 */
private class SessionState {
    private val lock = Any()
    private var sessionToken: String? = null
    private var cachedJwt: Pair<String, JwtPayload>? = null

    /**
     * Monotonic session-*generation* counter (invariant 3, iOS issue #66).
     * Bumped by [clear], [clearIfTokenMatches] (when it actually clears),
     * and [beginSession] — never by a `set-auth-token` rotation, which
     * re-issues the bearer *within* the same generation. [readForRefresh]
     * captures it before [AuthService.refreshJwt] awaits `/auth/token`,
     * and [commitRefresh] re-checks it — atomically, alongside the write it
     * gates — after that await returns, so a sign-out (or re-sign-in)
     * landing during an in-flight refresh can't be resurrected by the
     * success path re-persisting a stale rotation/JWT. Rotation
     * deliberately keeps the same generation, so a benign overlapping
     * double-refresh — where only the bearer rotated — still resolves a
     * JWT for both callers.
     */
    private var sessionEpoch: Long = 0L

    /** [token] and [epoch] read together as one consistent generation snapshot. */
    data class TokenAndEpoch(val token: String, val epoch: Long)

    fun currentToken(): String? = synchronized(lock) { sessionToken }

    fun latestJwt(): String? = synchronized(lock) { cachedJwt?.first }

    /** The cached JWT if it has more than [leeway] left before expiry as of [now], else `null`. */
    fun freshJwt(now: Instant, leeway: Duration): String? = synchronized(lock) {
        cachedJwt?.takeIf { (_, payload) -> Duration.between(now, payload.expiration) > leeway }?.first
    }

    /** The (token, epoch) snapshot [AuthService.refreshJwt] needs before its network await, or `null` when signed out. */
    fun readForRefresh(): TokenAndEpoch? = synchronized(lock) {
        sessionToken?.let { TokenAndEpoch(it, sessionEpoch) }
    }

    /** Install a token without touching the generation — the initial read in [AuthService.restoreSession]. */
    fun installToken(token: String) = synchronized(lock) {
        sessionToken = token
    }

    /** Install a brand-new session token AND bump the generation (issue #66 invariant 3) — a sign-in, not a restore. */
    fun beginSession(token: String) = synchronized(lock) {
        sessionToken = token
        sessionEpoch++
    }

    fun invalidateJwt() = synchronized(lock) {
        cachedJwt = null
    }

    /** Forget everything and bump the generation, so a stale in-flight refresh's [commitRefresh] can detect it. */
    fun clear() = synchronized(lock) {
        sessionToken = null
        cachedJwt = null
        sessionEpoch++
    }

    /**
     * [clear], but only if the current token still equals [expected] — the
     * issue-#53 token-identity guard behind [AuthService.currentJwt]'s 401
     * demotion. Checking and clearing as one atomic step is what stops a
     * concurrent re-sign-in's (or rotation's) token from being clobbered by
     * a 401 that was actually for a *different*, already-superseded bearer.
     * Returns whether it cleared.
     */
    fun clearIfTokenMatches(expected: String): Boolean = synchronized(lock) {
        if (sessionToken != expected) return@synchronized false
        sessionToken = null
        cachedJwt = null
        sessionEpoch++
        true
    }

    /**
     * The atomic commit at the tail of [AuthService.refreshJwt] (invariant
     * 3, iOS issue #66): applies [rotatedToken] (if the server rotated the
     * bearer) and/or [freshJwt] (if the JWT decoded) in one step, checked
     * against [epochAtRefresh] — the generation captured by [readForRefresh]
     * before the network await. Returns `false`, applying **neither**
     * mutation, when the generation has moved: a [clear],
     * [clearIfTokenMatches], or [beginSession] landed on another thread
     * while the network call was in flight, and the whole refresh must be
     * treated as stale. This is the fix issue #16 is about: with the check
     * and the write as two separate steps, a concurrent clear landing in
     * the gap between them was silently undone by a refresh that had
     * already validated *before* the clear happened.
     *
     * This method only ever moves in-memory state. [AuthService.refreshJwt]
     * additionally holds [AuthService.sessionMutex] for the duration of
     * this call *and* every storage write it authorizes — see that field's
     * KDoc for why the in-memory guard alone is not sufficient once a
     * persisted copy is involved.
     */
    fun commitRefresh(epochAtRefresh: Long, rotatedToken: String?, freshJwt: Pair<String, JwtPayload>?): Boolean =
        synchronized(lock) {
            if (sessionEpoch != epochAtRefresh) return@synchronized false
            if (rotatedToken != null) sessionToken = rotatedToken
            if (freshJwt != null) cachedJwt = freshJwt
            true
        }
}

/**
 * Owns the better-auth session lifecycle for a single signed-in DJ: takes an
 * identifier + password, calls whichever sign-in route the identifier
 * belongs to ([SignInIdentifier] picks `/auth/sign-in/email` or
 * `/auth/sign-in/username`), exchanges the session for a short-lived JWT via
 * `GET /auth/token`, and refreshes the JWT before it expires.
 *
 * **Concurrency contract (issue #16).** Every public method here is safe to
 * call from any number of coroutines on any dispatchers, including
 * genuinely parallel ones — a ViewModel on `Dispatchers.Main.immediate`
 * racing a `withContext(Dispatchers.IO) { apiClient.searchLibrary(...) }`
 * call that lazily refreshes the JWT, for instance. An earlier version of
 * this class's KDoc claimed the opposite: that it was correct only under
 * single-confined-dispatcher discipline, mirroring iOS's `@MainActor`. That
 * claim held only by accident — it was true of the one caller that existed
 * at the time ([kotlinx.coroutines.test.TestDispatcher] in tests, a
 * ViewModel's `Dispatchers.Main.immediate` in the app) — and stopped being
 * true the moment a second, genuinely parallel dispatcher could reach this
 * class. Read that as *safety* — no interleaving can corrupt the session or
 * resurrect a cleared one — and not as idempotence: [restoreSession]'s
 * `state != Unknown` early-out is a plain read-then-act, so two genuinely
 * concurrent cold-launch restores would each spend a `/auth/token`
 * exchange and each settle on the same answer. That is wasteful rather
 * than wrong, and there is one call site, so it is left as-is deliberately
 * rather than overlooked.
 *
 * What actually holds the invariant now is two layers, one for each
 * kind of state this class touches:
 *
 * - **In-memory session identity** — the session token, the cached JWT, the
 *   session-generation epoch — lives in [SessionState], not as bare fields
 *   here. Every read, write, or check-then-act on them goes through one of
 *   its `synchronized` methods; see that class's KDoc for exactly how that
 *   closes the gap, and how it avoids [kotlinx.coroutines.sync.Mutex]'s
 *   non-reentrancy given [currentJwt] calls [refreshJwt].
 * - **The memory-plus-storage compound transactions** — "commit a refresh
 *   in memory, *then* persist it", "clear the session in memory, *then*
 *   wipe storage" — additionally go through [sessionMutex]. A
 *   [SessionState] commit and its own [tokenStorage] write are two
 *   independent suspending operations with no ordering relationship of
 *   their own; without [sessionMutex] serializing the whole pair, a
 *   refresh whose in-memory commit legitimately won a race against a
 *   concurrent clear could still have its *storage* write reordered to
 *   land after that clear's storage wipe (measured while building the
 *   issue-#16 regression suite, in
 *   [AuthServiceConcurrencyTest] — a real, reproducible gap distinct from,
 *   and narrower than, the in-memory race the issue itself describes).
 *   [sessionMutex] is never held across [refreshJwt]'s network await and no
 *   guarded block ever calls into another one, so — exactly like
 *   [SessionState]'s monitor — nothing here needs [Mutex]'s reentrancy
 *   either.
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

    /** See [SessionState]'s KDoc: the sole owner of the session token, the cached JWT, and the session-generation epoch. */
    private val sessionState = SessionState()

    /**
     * Serializes each "mutate [sessionState], then persist to
     * [tokenStorage]" compound transaction against every other one — see
     * the class KDoc's second bullet. Never held across a suspending
     * network call, and no block guarded by it ever enters another one, so
     * it needs no reentrancy: [completeSignIn]'s leg-1 commit-and-persist,
     * [refreshJwt]'s commit-and-persist, [clearLocalSession], and
     * [currentJwt]'s token-identity clear-and-persist each acquire and
     * release it once, never nested inside each other.
     */
    private val sessionMutex = Mutex()

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
        sessionState.installToken(stored)
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
                hasStoredSession = sessionState.currentToken() != null,
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
     * parameterized only by how leg 1 obtains a session token. [signIn] and
     * [signInWithCode] (issue #4's OTP sign-in) each supply their own
     * `establishing` closure and inherit every invariant below rather than
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
            sessionMutex.withLock {
                tokenStorage.save(token, TokenSlot.SESSION_TOKEN)
                sessionState.beginSession(token)
            }
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

    /**
     * Mail a one-time sign-in code to the DJ behind [identifier] (issue #4).
     *
     * Two legs, the first often skipped: `POST /auth/wxyc/lookup-email`
     * resolves a username to the address the code goes to — an identifier
     * containing `@` skips it entirely, since [SignInIdentifier] classifies
     * it as an email and the resolver's own first line would just echo it
     * back unchanged. Then `POST /auth/email-otp/send-verification-otp`
     * mails a 6-digit code valid for five minutes.
     *
     * Deliberately does **not** touch [state]. No session exists yet, so
     * entering [AuthState.SigningIn] would claim one is being established
     * and strand a caller in a spinner state on failure — the caller (the
     * login screen's view model) owns this step's in-flight indicator
     * instead. [lastError] is cleared on entry, exactly as [signIn] clears
     * it, and any failure is recorded there before rethrowing — the same
     * one-surface convention [signIn] and [signInWithCode] use, via
     * [recordingFailure], so a failed resend can't go both unrendered and
     * erase a message already on screen.
     *
     * @return The address the code went to, plus how much of it may be
     *   shown — see [LoginCodeDestination].
     * @throws AuthError.Rejected When no account matches a username
     *   identifier.
     * @throws AuthError.RateLimited On a 429 from either leg.
     */
    suspend fun sendLoginCode(identifier: String): LoginCodeDestination {
        _lastError.value = null
        return recordingFailure {
            val classified = SignInIdentifier(identifier)
            val email = when (classified) {
                is SignInIdentifier.Email -> classified.raw
                is SignInIdentifier.Username -> wire.lookupEmail(classified.raw)
                    // `{"email": null}` (or empty) is the one failure this flow
                    // can name precisely. Says "username", never "username or
                    // email", because an identifier holding an `@` never
                    // reaches the lookup at all.
                    ?: throw AuthError.Rejected("No account matches that username")
            }
            wire.sendVerificationOtp(email)
            LoginCodeDestination(email = email, typedEmail = classified.typedEmail)
        }
    }

    /**
     * Mail another code to an address [sendLoginCode] already resolved
     * (issue #4).
     *
     * Skips the lookup by construction, which is the point: routing a
     * resend back through [sendLoginCode] would re-POST
     * `/auth/wxyc/lookup-email` for a username — an answer that cannot have
     * changed, since the identifier field isn't even on screen by then —
     * and spend **two** slots of Backend-Service's per-IP budget where one
     * would do, in precisely the situation (slow mail, an expired code)
     * where a DJ is most likely to tap again.
     */
    suspend fun resendLoginCode(to: String) {
        _lastError.value = null
        recordingFailure {
            wire.sendVerificationOtp(to)
        }
    }

    /**
     * Sign a DJ in with a code mailed by [sendLoginCode] (issue #4).
     *
     * `POST /auth/sign-in/email-otp` is a peer of the two password
     * routes — same `setSessionCookie`, same global `bearer()` plugin, so
     * the same `set-auth-token` header — which is why this is a single call
     * into [completeSignIn] and [AuthWireClient.verifyOtp] rather than a
     * second state machine. Everything after the token is obtained is
     * shared with the password path by construction.
     *
     * One server-side asymmetry worth knowing, since it is invisible from
     * here: Backend-Service sets `requireEmailVerification: true`, so an
     * unverified account is refused at password sign-in — but this route
     * instead *marks it verified* and proceeds, on the reasoning that
     * possession of the mailbox is the very proof the verification email
     * would have demanded.
     *
     * @param email [LoginCodeDestination.email], not the DJ's typed
     *   identifier.
     * @param otp The DJ's typed code; normalized to digits before it is
     *   sent.
     */
    suspend fun signInWithCode(email: String, otp: String) {
        completeSignIn {
            // The server's alphabet is provably 0-9 (better-auth's
            // defaultOTPGenerator is generateRandomString(otpLength ?? 6,
            // "0-9"), and Backend-Service overrides otpLength but not
            // generateOTP), so discarding non-digits can only ever drop
            // characters a real code cannot contain — and it makes a code
            // pasted as "123 456" work.
            val digits = otp.filter { it.isDigit() }
            wire.verifyOtp(email, digits, rejectionMessage = OTPRejection::copyFor)
        }
    }

    suspend fun signOut() {
        sessionState.currentToken()?.let { token -> bestEffort { wire.signOut(token) } }
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
     * iOS issue #53's token-identity guard, enforced atomically by
     * [SessionState.clearIfTokenMatches]). Two callers can share this
     * service and overlap a refresh, each bound to its own bearer at the
     * top of the call; if a concurrent re-sign-in (or a rotation captured
     * by the other in-flight refresh) replaced the session token while this
     * one awaited, the 401 is for a superseded bearer, and clobbering would
     * erase a valid new session. Report this stale attempt as
     * unauthenticated instead and let the caller retry the live session.
     */
    suspend fun currentJwt(): String {
        sessionState.freshJwt(clock(), REFRESH_LEEWAY)?.let { return it }
        val tokenAtRefresh = sessionState.currentToken() ?: throw AuthError.NotSignedIn
        try {
            refreshJwt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError.NotSignedIn) {
            val cleared = sessionMutex.withLock {
                sessionState.clearIfTokenMatches(tokenAtRefresh).also { didClear ->
                    if (didClear) bestEffort { tokenStorage.clearAll() }
                }
            }
            if (cleared) {
                _lastError.value = AuthError.NotSignedIn
                _state.value = AuthState.SignedOut
            }
            throw e
        }
        // A transient throw above propagates unchanged: state stays
        // signed-in and the caller sees its normal retryable error.
        return sessionState.latestJwt() ?: throw AuthError.NotSignedIn
    }

    /**
     * Drops the cached JWT so the next [currentJwt] re-mints one — what
     * `ApiClient` calls on a 401 before its single retry.
     *
     * Guarded by [sessionMutex] like every other memory-plus-storage
     * compound here, and deliberately so even though its storage half is
     * currently unobservable: [TokenSlot.JWT] is written and cleared but
     * **never loaded back** (a cold launch re-mints via [restoreSession] →
     * [refreshJwt]), so today an interleaving that left storage and memory
     * disagreeing about the JWT would have no visible effect. The rule is
     * uniform anyway, for two reasons: this method has exactly the
     * mutate-then-persist shape whose *unserialized* form was the narrower
     * second gap found while building the issue-#16 suite, and a future
     * reader of that slot — an offline path reusing a still-valid JWT, say,
     * next to the issue-#57 grace window — would turn a benign divergence
     * into a real one with nothing here to flag it. One exempt method is
     * also how the class KDoc's rule stops being true.
     */
    suspend fun invalidateJwt() {
        sessionMutex.withLock {
            sessionState.invalidateJwt()
            bestEffort { tokenStorage.clear(TokenSlot.JWT) }
        }
    }

    /** Drop the last sign-in error without touching the session. */
    fun clearLastError() {
        _lastError.value = null
    }

    /**
     * Run [block], recording any failure into [lastError] before rethrowing
     * the **original** exception untouched — the pre-session-leg
     * counterpart to [completeSignIn]'s leg-1 catch, so [sendLoginCode] and
     * [resendLoginCode] report through the same field [signIn] and
     * [signInWithCode] do. An earlier design that reported only by throwing
     * forced a caller to keep a second error store and coalesce the two,
     * which is exactly the hazard this exists to close: a failed resend
     * that went both unrendered and — because the entry-point clears
     * [lastError] — erased the message already on screen.
     *
     * Rethrows the original [Exception] rather than the classified
     * [AuthError.NetworkFailure] it records, mirroring [completeSignIn]'s
     * leg-1 catch: [lastError] is the one place this module decides how to
     * *classify* a failure, and a caller that wants to pattern-match the
     * underlying exception type still can.
     */
    private suspend fun <T> recordingFailure(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError) {
            _lastError.value = e
            throw e
        } catch (e: Exception) {
            _lastError.value = AuthError.NetworkFailure(e.message ?: e.toString())
            throw e
        }
    }

    /**
     * Fetches a fresh JWT for the current session and reconciles the result
     * against [sessionState] and [tokenStorage] as one atomic transaction
     * (see [SessionState.commitRefresh] and [sessionMutex]).
     *
     * The read of (token, epoch) and the eventual commit-and-persist are
     * two *independent* short lock acquisitions with the suspending network
     * call in between — never one critical section spanning the await,
     * which is exactly what would make [currentJwt] calling this function
     * deadlock-prone under a non-reentrant lock. JWT decoding happens
     * between those two points too, deliberately outside any lock: it
     * touches no shared state, so there is nothing to protect, and it must
     * still run — and its result must still be committable — even when a
     * `set-auth-token` rotation arrived with no usable JWT body (see the
     * throws below, which fire only *after* the transaction, so a rotated
     * bearer survives an undecodable JWT exactly as before).
     */
    private suspend fun refreshJwt(): JwtPayload {
        val (token, epochAtRefresh) = sessionState.readForRefresh() ?: throw AuthError.NotSignedIn
        val exchange = wire.fetchToken(token)
        if (exchange.statusCode !in 200..299) {
            if (exchange.statusCode == 401) throw AuthError.NotSignedIn
            throw AuthError.ServerFailure(exchange.statusCode, serverMessage = null)
        }

        val jwtToken = exchange.jwtToken
        val payload = jwtToken?.let { raw ->
            try {
                JwtDecoder.decode(raw)
            } catch (e: JwtDecodeError) {
                null
            }
        }

        // Bail before persisting anything if the session was cleared or
        // replaced (signOut / re-sign-in) while this awaited (invariant 3).
        // Otherwise this 2xx resurrects a torn-down session: the rotated
        // bearer and the new JWT would be written back after sign-out
        // cleared them, leaving state == SignedOut but a live token that
        // the next cold launch silently signs back in. A rotation captured
        // by a concurrent refresh keeps the same generation, so a benign
        // overlapping double-refresh still persists for both callers.
        //
        // The in-memory commit AND every storage write it authorizes run
        // inside the SAME [sessionMutex] critical section as
        // [clearLocalSession]'s clear-and-wipe: without that, the commit
        // above could legitimately win (epoch still matched at the instant
        // it ran) and then have its OWN storage write reordered, by
        // ordinary scheduling, to land after a concurrent clear's storage
        // wipe — resurrecting the session in storage even though memory
        // ended up correctly cleared. See the class KDoc's second bullet.
        val applied = sessionMutex.withLock {
            val committed = sessionState.commitRefresh(
                epochAtRefresh = epochAtRefresh,
                rotatedToken = exchange.rotatedSessionToken,
                freshJwt = if (jwtToken != null && payload != null) jwtToken to payload else null,
            )
            if (committed) {
                exchange.rotatedSessionToken?.let { rotated ->
                    bestEffort { tokenStorage.save(rotated, TokenSlot.SESSION_TOKEN) }
                }
                if (jwtToken != null && payload != null) {
                    bestEffort { tokenStorage.save(jwtToken, TokenSlot.JWT) }
                    // A successful exchange is a confirmed server contact:
                    // reset the offline grace window and refresh the
                    // durable payload (issue #57). This is the single
                    // chokepoint for sign-in, cold-launch restore, and the
                    // lazy refresh — every path that proves the session is
                    // live flows here.
                    persistValidationAnchors(payload)
                }
            }
            committed
        }
        if (!applied) throw AuthError.NotSignedIn

        if (jwtToken == null) throw AuthError.NetworkFailure("undecodable /auth/token body")
        if (payload == null) throw AuthError.NetworkFailure("undecodable JWT payload")
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
        sessionMutex.withLock {
            sessionState.clear()
            bestEffort { tokenStorage.clearAll() }
        }
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
