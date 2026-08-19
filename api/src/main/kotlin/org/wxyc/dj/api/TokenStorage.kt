package org.wxyc.dj.api

/**
 * Named slots in [TokenStorage]. Two for now — the JWT (short-lived,
 * cacheable) evicted independently of the session token (long-lived, the
 * source of truth). Mirrors iOS's `TokenSlot`; its offline-grace slots
 * (issue #57 — last confirmed server contact, durable payload copy) are out
 * of scope until the sign-in state machine (issue #3) needs them.
 */
enum class TokenSlot {
    SESSION_TOKEN,
    JWT,
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
