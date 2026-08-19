package org.wxyc.dj.api

/**
 * Pure decision for the offline cold-launch restore (issue #57). When the
 * JWT exchange fails transiently (network / 5xx / undecodable body) on a
 * cold launch, [AuthService] consults this policy to decide whether a
 * returning DJ stays signed in on the cached identity within a bounded grace
 * window, or is dropped to the login screen. The window is anchored on the
 * last confirmed server contact (`lastValidatedAt`), never the JWT `exp`.
 *
 * The anchor and "now" are epoch-second [Double]s rather than [java.time.Instant]:
 * a tampered or corrupted stored value can parse to a non-finite double
 * (`"Infinity"`, `"NaN"` both parse successfully via [String.toDoubleOrNull]),
 * and [java.time.Instant] cannot represent that at all — constructing one from
 * an out-of-range epoch value throws rather than round-tripping. Keeping the
 * anchor as a raw double, exactly as it comes off storage, is what lets this
 * function's own fail-closed guard (below) be the thing a test exercises
 * directly, mirroring `OfflineSessionPolicy.swift`'s use of `Date`/`TimeInterval`
 * (which can hold IEEE-754 infinity) for the same reason.
 *
 * Mirrors `OfflineSessionPolicy.swift`.
 */
object OfflineSessionPolicy {
    /** How long a returning DJ may stay signed in offline. 30 days. */
    const val DEFAULT_WINDOW_SECONDS: Double = 30.0 * 24 * 60 * 60

    sealed class Decision {
        data class SignedIn(val payload: JwtPayload) : Decision()
        data object SignedOut : Decision()
    }

    /**
     * Decide the offline cold-launch restore outcome.
     *
     * Returns [Decision.SignedIn] iff a stored session exists **and** a
     * cached payload exists **and** [lastValidatedAtEpochSeconds] is
     * non-null **and** the elapsed time since it is **finite** and strictly
     * less than [windowSeconds]. Otherwise [Decision.SignedOut].
     *
     * The window is **exclusive** at exactly [windowSeconds]: `elapsed <
     * windowSeconds` is in, `elapsed == windowSeconds` is out. An anchor in
     * the *finite* future (mild clock skew) reads as in-window (negative
     * elapsed) — the lenient choice. A **non-finite** anchor (a
     * corrupted/tampered value that parsed to ±infinity) fails closed to
     * [Decision.SignedOut] — without this guard a `+infinity` anchor would
     * give `elapsed == -infinity < windowSeconds` and grant grace forever.
     */
    fun decide(
        hasStoredSession: Boolean,
        cachedPayload: JwtPayload?,
        lastValidatedAtEpochSeconds: Double?,
        nowEpochSeconds: Double,
        windowSeconds: Double = DEFAULT_WINDOW_SECONDS,
    ): Decision {
        if (!hasStoredSession || cachedPayload == null || lastValidatedAtEpochSeconds == null) {
            return Decision.SignedOut
        }
        val elapsed = nowEpochSeconds - lastValidatedAtEpochSeconds
        if (!elapsed.isFinite() || elapsed >= windowSeconds) {
            return Decision.SignedOut
        }
        return Decision.SignedIn(cachedPayload)
    }
}
