package org.wxyc.dj

import android.icu.text.Collator as IcuCollator
import java.text.Collator
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
 * this test builds one directly, with its strength and decomposition
 * *derived* from [BinSorting.newCollator] (translated through
 * [javaToIcuStrength]/[javaToIcuDecomposition] rather than restated as
 * separate literals — see those functions for why a plain int copy is
 * wrong), and asserts it orders the fixture pool identically to
 * [BinSorting.sorted].
 *
 * **What this pool actually exercises, precisely — a prior version of this
 * KDoc overclaimed here too, which is why this paragraph is explicit about
 * the boundary.** The pool (`Fixtures`-equivalent, inlined below because
 * `:app` cannot see `:api`'s test sources) is two real WXYC artists, each as
 * an (unaccented, accented, ASCII-later-sentinel) triple. Under primary-
 * strength collation the accented and unaccented spellings of one name are
 * primary-equal (the accent is ignored) and both sort ahead of that artist's
 * sentinel; under plain `String.compareTo` the accented character's high
 * code point instead pushes it *after* the sentinel, splitting the pair.
 * That is the one thing this test can currently detect: it would catch a
 * regression that dropped collation for code-point order, or a `strength`/
 * `decomposition` change that stopped folding the accent. It says nothing
 * about whether Android's ICU tables and the host JDK's `RuleBasedCollator`
 * agree on any *other* ordering question — today, on this pool, they agree,
 * which is exactly what makes this a tripwire for future divergence rather
 * than proof there is none.
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
        // Mirrors api/src/test/.../Fixtures.diacriticBearingArtists() — kept
        // as a separate literal because :app cannot see :api's test sources.
        // Two real WXYC artists, each as an (unaccented, accented,
        // ASCII-later-sentinel) triple; see the class KDoc for why that
        // shape — and not a plain diacritic-bearing name list — is what
        // gives this pool discriminating power.
        val names = listOf(
            "Hermanos Gutierrez",
            "Hermanos Gutiérrez",
            "Hermanos Gutizz",
            "Nilufer Yanya",
            "Nilüfer Yanya",
            "Nilzz Yanya",
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

        // Same locale as BinSorting.newCollator(), built against
        // android.icu.text.Collator directly rather than through the
        // unreachable java.text.Collator façade. Strength/decomposition are
        // *derived* from newCollator()'s own configured java.text.Collator
        // below, not restated here, so a future change to production
        // strength can't silently leave this test parity-checking against
        // stale config.
        val reference = BinSorting.newCollator()
        val icuCollator: IcuCollator = IcuCollator.getInstance(Locale.US).apply {
            strength = javaToIcuStrength(reference.strength)
            decomposition = javaToIcuDecomposition(reference.decomposition)
        }
        val icuOrder = names.sortedWith(Comparator { a, b -> icuCollator.compare(a, b) })

        assertEquals(icuOrder, binSortingOrder)
    }
}

/**
 * `java.text.Collator` and `android.icu.text.Collator` (`:app`'s
 * `IcuCollator`) declare constants of the same *name* for strength and
 * decomposition, but not the same underlying `Int`. Confirmed empirically
 * against icu4j 74.2 — the library `android.icu` is a shaded copy of — via
 * `javap -p -constants`: `PRIMARY`/`SECONDARY`/`TERTIARY` happen to coincide
 * with `java.text.Collator`'s (0/1/2), but the decomposition modes do not
 * (`NO_DECOMPOSITION`/`CANONICAL_DECOMPOSITION`/`FULL_DECOMPOSITION` are
 * 16/17/15 in icu4j versus 0/1/2 in `java.text.Collator`). So
 * `icuCollator.strength = reference.strength` would work today by
 * coincidence and `icuCollator.decomposition = reference.decomposition`
 * would not work at all — copying the raw `Int` out of
 * [BinSorting.newCollator]'s `java.text.Collator` is not a safe way to
 * configure the ICU side. Mapping by constant identity, as these two
 * functions do, is safe regardless of what either side's underlying `Int`
 * values are.
 */
private fun javaToIcuStrength(strength: Int): Int = when (strength) {
    Collator.PRIMARY -> IcuCollator.PRIMARY
    Collator.SECONDARY -> IcuCollator.SECONDARY
    Collator.TERTIARY -> IcuCollator.TERTIARY
    Collator.IDENTICAL -> IcuCollator.IDENTICAL
    else -> error("Unmapped java.text.Collator strength: $strength")
}

/** See [javaToIcuStrength] — the decomposition-side half of the same translation. */
private fun javaToIcuDecomposition(decomposition: Int): Int = when (decomposition) {
    Collator.NO_DECOMPOSITION -> IcuCollator.NO_DECOMPOSITION
    Collator.CANONICAL_DECOMPOSITION -> IcuCollator.CANONICAL_DECOMPOSITION
    Collator.FULL_DECOMPOSITION -> IcuCollator.FULL_DECOMPOSITION
    else -> error("Unmapped java.text.Collator decomposition: $decomposition")
}
