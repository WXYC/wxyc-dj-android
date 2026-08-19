package org.wxyc.dj.api

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Exhaustive table tests for the pure offline cold-launch restore policy
 * (issue #3 invariant 5): the 30-day grace window's boundary (exclusive at
 * exactly the window), and every fail-closed input (missing payload /
 * anchor / stored session). Also pins that a within-window decision passes
 * the cached payload through intact (diacritic- and role-bearing, from
 * canonical WXYC data). Ported near-verbatim from `OfflineSessionPolicyTests.swift`.
 */
class OfflineSessionPolicyTest {
    private val now: Double = Instant.parse("2030-03-08T23:06:40Z").epochSecond.toDouble() // 1_900_000_000
    private val window = OfflineSessionPolicy.DEFAULT_WINDOW_SECONDS

    /** A role- and diacritic-bearing payload, exercising the Unicode path through the decision. */
    private fun payload(expiresInSeconds: Double = 600.0) = JwtPayload(
        sub = "Nilüfer",
        email = "nilufer@wxyc.org",
        role = "dj",
        exp = Instant.ofEpochMilli(((now + expiresInSeconds) * 1000).toLong()),
    )

    @Test
    fun `within window signs in with cached payload`() {
        val payload = payload()
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload,
            lastValidatedAtEpochSeconds = now - (window - 1),
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedIn(payload), decision)
    }

    @Test
    fun `exactly at window signs out`() {
        // Boundary is exclusive: elapsed == window is out.
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload(),
            lastValidatedAtEpochSeconds = now - window,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `beyond window signs out`() {
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload(),
            lastValidatedAtEpochSeconds = now - (window + 1),
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `missing payload signs out`() {
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = null,
            lastValidatedAtEpochSeconds = now - 60,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `missing lastValidatedAt signs out`() {
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload(),
            lastValidatedAtEpochSeconds = null,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `no stored session signs out`() {
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = false,
            cachedPayload = payload(),
            lastValidatedAtEpochSeconds = now - 60,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `future anchor reads as in window`() {
        // Documented leniency: a lastValidatedAt in the future (clock skew,
        // negative elapsed) reads as in-window. Pins the behavior the doc
        // comment promises so a later "harden against skew" can't silently
        // flip it to a sign-out.
        val payload = payload()
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload,
            lastValidatedAtEpochSeconds = now + 60,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedIn(payload), decision)
    }

    @Test
    fun `non-finite anchor signs out`() {
        // A corrupted/tampered anchor that parsed to a non-finite double
        // must NOT pin the DJ to signed-in forever: `now - (+infinity)` is
        // `-infinity`, which is `< window`, so without a finite-elapsed
        // guard the bounded window is defeated. Fail closed.
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload(),
            lastValidatedAtEpochSeconds = Double.POSITIVE_INFINITY,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `NaN elapsed signs out`() {
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload(),
            lastValidatedAtEpochSeconds = Double.NaN,
            nowEpochSeconds = now,
            windowSeconds = window,
        )
        assertEquals(OfflineSessionPolicy.Decision.SignedOut, decision)
    }

    @Test
    fun `default window is thirty days`() {
        assertEquals(30.0 * 24 * 60 * 60, OfflineSessionPolicy.DEFAULT_WINDOW_SECONDS)
    }

    @Test
    fun `diacritic role-bearing payload round-trips through decision`() {
        val payload = JwtPayload(
            sub = "Hermanos Gutiérrez",
            email = "hermanos@wxyc.org",
            role = "md",
            exp = Instant.ofEpochMilli(((now + 3600) * 1000).toLong()),
        )
        val decision = OfflineSessionPolicy.decide(
            hasStoredSession = true,
            cachedPayload = payload,
            lastValidatedAtEpochSeconds = now - 1000,
            nowEpochSeconds = now,
        )
        val signedIn = decision as? OfflineSessionPolicy.Decision.SignedIn
            ?: error("expected SignedIn, got $decision")
        assertEquals(payload, signedIn.payload)
        assertEquals("Hermanos Gutiérrez", signedIn.payload.sub)
        assertEquals("md", signedIn.payload.role)
    }
}
