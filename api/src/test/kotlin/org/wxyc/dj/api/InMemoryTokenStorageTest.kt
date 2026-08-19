package org.wxyc.dj.api

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pins the in-memory [TokenStorage] implementation's contract: slots
 * ([TokenSlot.SESSION_TOKEN] / [TokenSlot.JWT]) are independent, an unset
 * slot reads as `null`, and — the invariant every terminal-cleanup path in
 * `AuthService` (issue #53's rollback, #66's generation guard, sign-out)
 * will come to depend on — [InMemoryTokenStorage.clearAll] leaves no slot
 * behind, checked by iterating [TokenSlot.entries] rather than naming each
 * slot so a newly-added slot can't silently survive it.
 */
class InMemoryTokenStorageTest {

    @Test
    fun `save and load round trip`() = runTest {
        val storage = InMemoryTokenStorage()

        storage.save("abc", TokenSlot.SESSION_TOKEN)

        assertEquals("abc", storage.load(TokenSlot.SESSION_TOKEN))
    }

    @Test
    fun `slots are independent`() = runTest {
        val storage = InMemoryTokenStorage()

        storage.save("session", TokenSlot.SESSION_TOKEN)
        storage.save("jwt", TokenSlot.JWT)

        assertEquals("session", storage.load(TokenSlot.SESSION_TOKEN))
        assertEquals("jwt", storage.load(TokenSlot.JWT))
    }

    @Test
    fun `clearing one slot leaves others`() = runTest {
        val storage = InMemoryTokenStorage()
        storage.save("s", TokenSlot.SESSION_TOKEN)
        storage.save("j", TokenSlot.JWT)

        storage.clear(TokenSlot.SESSION_TOKEN)

        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
        assertEquals("j", storage.load(TokenSlot.JWT))
    }

    @Test
    fun `clearAll empties every slot`() = runTest {
        // Seed EVERY slot (via entries) so a newly-added TokenSlot can't
        // silently survive clearAll — the leave-no-trace contract the
        // 401/sign-out paths depend on.
        val storage = InMemoryTokenStorage()
        for (slot in TokenSlot.entries) {
            storage.save("value-$slot", slot)
        }

        storage.clearAll()

        for (slot in TokenSlot.entries) {
            assertNull(storage.load(slot), "clearAll left $slot behind")
        }
    }

    @Test
    fun `loading an unset slot returns null`() = runTest {
        val storage = InMemoryTokenStorage()

        assertNull(storage.load(TokenSlot.SESSION_TOKEN))
    }
}
