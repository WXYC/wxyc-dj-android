package org.wxyc.dj

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumented tier's own tripwire — `PureJvmModuleTest`'s counterpart at
 * the other end of the split.
 *
 * `:api`'s tripwire guards the property that its suites need **no** Android
 * runtime. This one guards the converse: that a suite claiming to test
 * Android's runtime is actually running on one. The repo has already been
 * burned by the difference. `BinCollationParityTest` was written to check
 * Android's `java.text.Collator`, and could not: Robolectric's
 * `SdkSandboxClassLoader` sandboxes only the `android.*` package tree and
 * cannot define classes in `java.*`, so `java.text.Collator.getInstance(...)`
 * under that runner resolves to the **bootstrap JDK's** `RuleBasedCollator`.
 * The suite was green, the assertion was real, and the thing it named was not
 * under test — which is how `Collator.FULL_DECOMPOSITION` reached `main` as a
 * guaranteed first-Bin-load crash on every real phone.
 *
 * So the test that matters here is a *negative* one, and it is deliberately
 * about the VM rather than about Robolectric specifically: Robolectric runs
 * its shadows on the host JVM, so any host-JVM-hosted impostor — this runner,
 * a future one, or a misconfigured source set that compiled these files into
 * `src/test` by mistake — fails the same assertion for the same reason. Naming
 * only `Build.FINGERPRINT == "robolectric"` would guard against one library's
 * current implementation detail instead of against the class of mistake.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedTierTest {
    @Test
    fun theSuiteRunsOnAnAndroidRuntimeAndNotOnTheHostJvm() {
        // ART reports "Dalvik" for compatibility; every desktop JVM reports
        // some "… VM" string (HotSpot: "OpenJDK 64-Bit Server VM"). This is
        // the assertion Robolectric cannot satisfy, because Robolectric IS
        // the host JVM with android.* swapped underneath it.
        assertEquals("Dalvik", System.getProperty("java.vm.name"))

        // Belt to the above's braces: names the specific impostor this repo
        // actually tripped over, so a failure reads as "you are on
        // Robolectric" rather than only "you are not on ART".
        assertNotEquals("robolectric", Build.FINGERPRINT)
    }

    @Test
    fun theRuntimeIsAtOrAboveTheAppsDeclaredMinSdk() {
        // Mirrors `minSdk = 26` in app/build.gradle.kts. An emulator below
        // the floor would be testing a configuration the app refuses to
        // install on, which is worse than not testing at all: it reports
        // failures nobody has to fix and hides the ones they do.
        assertTrue(
            "Instrumented tests ran on API ${Build.VERSION.SDK_INT}, below the app's minSdk of 26",
            Build.VERSION.SDK_INT >= 26,
        )
    }

    @Test
    fun theInstrumentationIsTargetingThisApp() {
        // Proves the APK under test is the one this module builds, not a
        // stale install left by an earlier run on the same device.
        assertEquals(
            "org.wxyc.dj",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }
}
