package org.wxyc.dj.ui.bin

import org.junit.Assert.assertThrows
import org.junit.Test
import org.wxyc.dj.api.BinEntry

/**
 * Pins the structural half of issue #11's empty/error split: an empty
 * [BinUiState.Populated] cannot be constructed at all, so "authoritative
 * empty bin" and "populated bin" cannot be confused by construction, not
 * merely by a `when` branch a future edit could get wrong. See
 * [BinUiState]'s KDoc.
 */
class BinUiStateTest {

    @Test
    fun `Populated rejects an empty entries list`() {
        assertThrows(IllegalArgumentException::class.java) {
            BinUiState.Populated(emptyList())
        }
    }

    @Test
    fun `Populated accepts a non-empty entries list`() {
        BinUiState.Populated(listOf(BinEntry(albumId = 1, albumTitle = "DOGA", artistName = "Juana Molina")))
    }
}
