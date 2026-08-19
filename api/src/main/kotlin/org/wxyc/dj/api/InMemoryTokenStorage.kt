package org.wxyc.dj.api

/**
 * In-memory [TokenStorage] for tests. A `synchronized` block guards the
 * backing map so parallel tests do not clobber each other; mirrors
 * `InMemoryTokenStorage.swift`, which uses `OSAllocatedUnfairLock` for the
 * same reason.
 */
class InMemoryTokenStorage : TokenStorage {
    private val lock = Any()
    private val state = mutableMapOf<TokenSlot, String>()

    override suspend fun save(token: String, slot: TokenSlot) {
        synchronized(lock) { state[slot] = token }
    }

    override suspend fun load(slot: TokenSlot): String? =
        synchronized(lock) { state[slot] }

    override suspend fun clear(slot: TokenSlot) {
        synchronized(lock) { state.remove(slot) }
    }

    override suspend fun clearAll() {
        synchronized(lock) { state.clear() }
    }
}
