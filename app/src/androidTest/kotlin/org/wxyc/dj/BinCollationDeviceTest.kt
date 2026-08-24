package org.wxyc.dj

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.wxyc.dj.api.BinEntry
import org.wxyc.dj.api.BinSorting

/**
 * Exercises [BinSorting] against the **real** `java.text.Collator` façade, on
 * a real Android runtime — the one thing no other suite in this repo can do.
 *
 * `BinCollationParityTest`'s KDoc states the gap this closes verbatim: "the
 * `java.text.Collator` façade `BinSorting.newCollator` actually calls is
 * unreachable under Robolectric … so no test in this repo exercises *that*
 * façade's delegation to `android.icu`". Here it is reachable by
 * construction — on device, `java.text.Collator` **is** the platform's,
 * ICU-backed one — so no assertion has to reach for it. Calling
 * [BinSorting.sorted] is the coverage.
 *
 * That gap was not theoretical. `Collator.FULL_DECOMPOSITION` shipped to
 * `main` behind a green `:api:test` and a green Robolectric parity test,
 * because on the host JVM `RuleBasedCollator` accepts it and on Android the
 * façade's private `decompositionMode_Java_ICU(int)` converter throws
 * `IllegalArgumentException("Bad mode: 2")` for every value that is not
 * `CANONICAL_DECOMPOSITION` or `NO_DECOMPOSITION`. The first Bin-tab load on
 * any phone would have crashed before comparing a single name.
 *
 * **What these cases prove, and what they do not.** The ordering case
 * discriminates two things at once: collation from code-point order — under
 * `String.compareTo` the accented spelling's high code point (`é` = U+00E9,
 * `ü` = U+00FC) sorts it *after* each artist's `zz` sentinel (`z` = U+007A),
 * splitting the pair collation keeps adjacent — and `PRIMARY` from any
 * stronger setting, via album titles picked so the accent-fold tie is what
 * hands the ordering decision to the secondary key. It does **not** prove
 * Android's ICU tables and the host JDK's `RuleBasedCollator` agree on any
 * other ordering question; on this pool they do, which is what makes it a
 * tripwire for future divergence rather than proof there is none.
 *
 * **Exactly one case here is device-only**, and it is worth naming precisely
 * rather than letting the suite's location imply it of all four:
 * [theRealCollatorFacadeRejectsFullDecomposition], which the host JDK fails
 * because `RuleBasedCollator` accepts `FULL_DECOMPOSITION` happily. The other
 * three assert things a correctly-configured host collator also satisfies —
 * they earn their place by asserting them against the *real* façade, and by
 * being the backstop if the host pins in `BinEntryTest` are ever weakened,
 * not by being unreachable elsewhere. [InstrumentedTierTest] independently
 * pins that this is a real Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class BinCollationDeviceTest {
    @Test
    fun theRealCollatorFacadeAcceptsWhatBinSortingConfigures() {
        // The whole mutation target in one line: on Android this throws for a
        // decomposition mode the façade's Java-to-ICU converter rejects, so
        // constructing it at all is the assertion. The readback then pins that
        // both knobs are set explicitly rather than left at a platform default
        // that is not guaranteed to match the host JVM's.
        val collator = BinSorting.newCollator()

        assertEquals(Collator.PRIMARY, collator.strength)
        assertEquals(Collator.CANONICAL_DECOMPOSITION, collator.decomposition)
    }

    @Test
    fun theRealCollatorFacadeRejectsFullDecomposition() {
        // Pins the platform fact BinSorting's KDoc asserts, on the platform.
        // This case is also the tier's self-check: the desktop JVM's
        // RuleBasedCollator accepts FULL_DECOMPOSITION happily, so a green
        // result here is only reachable on an Android runtime.
        val collator = Collator.getInstance(Locale.US)

        assertThrows(IllegalArgumentException::class.java) {
            collator.decomposition = Collator.FULL_DECOMPOSITION
        }
    }

    @Test
    fun bothUnicodeFormsOfANameFileAsOne() {
        // The wire can carry either Unicode form for the same visual name, and
        // BinSorting exists so both file in one place. Asserted against the
        // configured collator rather than against `equals`, which they are
        // not: these two strings differ by a code point.
        //
        // Scope, measured rather than assumed: this holds for every
        // strength/decomposition pair BinSorting could plausibly use, and for
        // an unconfigured collator too — ICU and the JDK both map a
        // precomposed character to the same collation elements as its
        // decomposition, so canonical equivalence folds regardless of the
        // decomposition knob. It therefore pins the REQUIREMENT (either form
        // files in one place) and not the configuration that delivers it; the
        // configuration is pinned by the two cases above and by BinEntryTest.
        // Do not read a green here as evidence that `decomposition` is set.
        val precomposed = "Nil\u00FCfer Yanya"
        val decomposed = "Nilu\u0308fer Yanya"

        assertEquals(0, BinSorting.newCollator().compare(precomposed, decomposed))
    }

    @Test
    fun sortedFoldsDiacriticsOnThePlatformCollator() {
        // Two real WXYC artists, each as an (unaccented, accented,
        // ASCII-later-sentinel) triple, handed in already out of order so a
        // no-op sort cannot pass.
        //
        // The album titles are chosen to make `strength` load-bearing, not
        // just decorative. At PRIMARY the accented and unaccented spellings
        // are equal, so BinSorting's documented secondary key — album title —
        // decides, putting "Aire" (the accented row) ahead of "Bosque" (the
        // unaccented one). At any higher strength the two names stop being
        // equal and the accent alone orders unaccented first, flipping that
        // pair. So [2, 1, ...] is reachable only with the accent actually
        // folded; a strength regression yields [1, 2, ...] and fails here.
        val entries = listOf(
            entry(id = 3, artist = "Hermanos Gutizz", album = "Cielo"),
            entry(id = 6, artist = "Nilzz Yanya", album = "Cielo"),
            entry(id = 2, artist = "Hermanos Gutiérrez", album = "Aire"),
            entry(id = 4, artist = "Nilufer Yanya", album = "Bosque"),
            entry(id = 1, artist = "Hermanos Gutierrez", album = "Bosque"),
            entry(id = 5, artist = "Nilüfer Yanya", album = "Aire"),
        )

        val sorted = BinSorting.sorted(entries)

        assertEquals(listOf(2, 1, 3, 5, 4, 6), sorted.map { it.albumId })
    }

    private fun entry(id: Int, artist: String, album: String) = BinEntry(
        albumId = id,
        albumTitle = album,
        artistName = artist,
        alphabeticalName = artist,
    )
}
