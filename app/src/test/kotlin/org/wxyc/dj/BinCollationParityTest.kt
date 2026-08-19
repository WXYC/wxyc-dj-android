package org.wxyc.dj

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.wxyc.dj.api.BinEntry
import org.wxyc.dj.api.BinSorting

/**
 * Parity check for issue #5 invariant 15: `:api:test`'s
 * `BinEntryTest#sorting is stable across the diacritic-bearing pool` runs
 * `java.text.Collator` on the desktop JVM, which does **not** delegate to
 * `android.icu`. This is the same assertion run under Robolectric, which
 * loads the real platform `Collator`/`android.icu` — so a divergence between
 * the host JVM's collation tables and Android's would show up here, not on a
 * DJ's phone. `@Config(sdk = 34)` pins a level that runs under the repo's
 * JDK 17 toolchain; Robolectric's SDK 36 shadows require JDK 21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BinCollationParityTest {
    @Test
    fun `sorting is stable across the diacritic-bearing pool from wxyc-example-data`() {
        val names = listOf(
            "Aşıq Altay",
            "Csillagrablók",
            "GIDEÖN",
            "Hermanos Gutiérrez",
            "Nilüfer Yanya",
        )
        val entries = names.mapIndexed { index, name ->
            BinEntry(
                albumId = index,
                albumTitle = "Album $index",
                artistName = name,
                alphabeticalName = name,
            )
        }.shuffled(kotlin.random.Random(42))

        val sorted = BinSorting.sorted(entries).map { it.sortName }

        assertEquals(names, sorted)
    }
}
