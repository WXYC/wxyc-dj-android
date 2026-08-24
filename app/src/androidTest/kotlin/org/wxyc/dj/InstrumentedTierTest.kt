package org.wxyc.dj

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // Order matters. The fingerprint check is the SPECIFIC one and runs
        // first, so a Robolectric run fails with a message naming Robolectric;
        // put the general assertion first and it throws before this ever
        // executes, making the specific message unreachable in the one
        // situation it was written for.
        assertNotEquals("robolectric", Build.FINGERPRINT)

        // The general one. ART reports "Dalvik" for compatibility on every
        // API level (AOSP hardcodes it in initUnchangeableSystemProperties);
        // every desktop JVM reports some "… VM" string. So any host-JVM-hosted
        // impostor fails here — a future shadowing framework, or files that
        // landed in src/test instead of src/androidTest — not just the one
        // this repo tripped over.
        assertEquals("Dalvik", System.getProperty("java.vm.name"))
    }

    @Test
    fun theInstrumentationIsTargetingThisApp() {
        // Narrow on purpose: this detects a wrong or renamed applicationId,
        // nothing more. It cannot tell a stale install of org.wxyc.dj from a
        // fresh one — same package name either way — so do not read it as a
        // freshness check.
        assertEquals(
            "org.wxyc.dj",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }
}
