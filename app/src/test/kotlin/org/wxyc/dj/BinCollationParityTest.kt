package org.wxyc.dj

import android.icu.text.Collator as IcuCollator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.wxyc.dj.api.BinEntry
import org.wxyc.dj.api.BinSorting

/**
 * Parity check for issue #5 invariant 15, against `android.icu.text.Collator`
 * directly — **not** a check of `java.text.Collator`'s Android behavior.
 *
 * Robolectric's `SdkSandboxClassLoader` only sandboxes the `android.*`
 * package tree; it cannot define classes in `java.*`, so a plain
 * `java.text.Collator.getInstance(...)` call under this test runner still
 * resolves to the bootstrap JDK's `java.text.RuleBasedCollator` — the exact
 * same implementation `:api:test`'s `BinEntryTest` already exercises on the
 * desktop JVM, with the same input and the same assertion. A prior version of
 * this test claimed to load "the real platform `Collator`/`android.icu`" via
 * that call; it did not, which was confirmed by probing the live instance
 * inside this source set (`impl=java.text.RuleBasedCollator`,
 * `collatorClassLoader=null` — bootstrap — against
 * `binEntryLoader=…SdkSandboxClassLoader@…`), so the assertion below proved
 * nothing about Android's collation tables.
 *
 * What *is* loadable in the Robolectric sandbox — confirmed by the same
 * probe — is `android.icu.text.Collator` itself, constructed explicitly. So
 * this test builds one directly, configured with the same locale/strength/
 * decomposition [BinSorting.newCollator] pins, and asserts it orders the
 * diacritic-bearing fixture pool identically to [BinSorting.sorted]. That is
 * a genuine parity assertion: it would catch Android's ICU collation tables
 * ordering these names differently than the host JDK's `RuleBasedCollator`
 * does.
 *
 * **What this still does not cover:** the `java.text.Collator` façade
 * `BinSorting.newCollator` actually calls is unreachable under Robolectric
 * (see above), so no test in this repo exercises *that* façade's delegation
 * to `android.icu` — only the two implementations it can bridge to
 * (`RuleBasedCollator` here in `:api:test`, `android.icu.text.Collator` here)
 * independently. `BinEntryTest`'s
 * `newCollator uses a decomposition mode Android's Collator actually accepts`
 * closes the one gap that combination could still miss (a decomposition
 * value the façade's Java→ICU converter rejects outright) by asserting
 * against the converter's accepted values directly, on the host, with no
 * simulator or Robolectric sandbox needed.
 *
 * `@Config(sdk = 34)` pins a level that runs under the repo's JDK 17
 * toolchain; Robolectric's SDK 36 shadows require JDK 21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BinCollationParityTest {
    @Test
    fun `android icu's Collator orders the diacritic-bearing pool the same as BinSorting`() {
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

        val binSortingOrder = BinSorting.sorted(entries).map { it.sortName }

        // Same locale/strength/decomposition as BinSorting.newCollator(),
        // built against android.icu.text.Collator directly rather than
        // through the unreachable java.text.Collator façade.
        val icuCollator: IcuCollator = IcuCollator.getInstance(Locale.US).apply {
            strength = IcuCollator.PRIMARY
            decomposition = IcuCollator.CANONICAL_DECOMPOSITION
        }
        val icuOrder = names.sortedWith(Comparator { a, b -> icuCollator.compare(a, b) })

        assertEquals(icuOrder, binSortingOrder)
    }
}
