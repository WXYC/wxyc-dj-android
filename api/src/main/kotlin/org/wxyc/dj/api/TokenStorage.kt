package org.wxyc.dj.api

/**
 * Named slots in [TokenStorage]. The JWT (short-lived, cacheable) is evicted
 * independently of the session token (long-lived, the source of truth).
 * [LAST_VALIDATED_AT] and [PAYLOAD] anchor the offline grace window (issue
 * #57, [OfflineSessionPolicy]): the wall-clock of the last confirmed server
 * contact and a durable copy of the last [JwtPayload], so a cold launch
 * offline can restore the cached identity without a network round-trip.
 * [PAYLOAD] lives in its own slot so a transient [AuthService.invalidateJwt]
 * (which clears only [JWT]) never erases it. Mirrors iOS's `TokenSlot`.
 */
enum class TokenSlot {
    SESSION_TOKEN,
    JWT,
    LAST_VALIDATED_AT,
    PAYLOAD,
}

/**
 * Persistent home for the better-auth session token and its derived JWT.
 * Platform-backed in `:app` (DataStore + Tink, added with issue #7);
 * [InMemoryTokenStorage] is the test double used here and by every `:api`
 * suite that needs a [TokenStorage]. Suspending because the real DataStore
 * implementation is async-only. Mirrors `TokenStorage.swift` +
 * `InMemoryTokenStorage.swift`.
 */
interface TokenStorage {
    suspend fun save(token: String, slot: TokenSlot)
    suspend fun load(slot: TokenSlot): String?
    suspend fun clear(slot: TokenSlot)
    suspend fun clearAll()
}
