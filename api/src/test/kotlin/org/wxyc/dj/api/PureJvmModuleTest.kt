package org.wxyc.dj.api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Tripwire for the structural decision the whole repo is arranged around: :api
 * is a pure JVM module, so its suites run on the host in milliseconds rather
 * than in an emulator.
 *
 * A scaffold test with a real job. Converting :api to `com.android.library`, or
 * adding a dependency that drags the SDK in transitively, would otherwise
 * compile and pass — the cost (every :api suite now needs a device) only shows
 * up later, as slowness nobody traces back to the change that caused it.
 */
class PureJvmModuleTest {

    @Test
    fun `has no Android SDK on the classpath`() {
        val androidPresent = runCatching {
            Class.forName("android.content.Context", false, javaClass.classLoader)
        }.isSuccess

        assertFalse(
            androidPresent,
            "android.content.Context resolved from :api — this module is no longer pure JVM. " +
                "Platform-backed code belongs in :app behind an interface declared here.",
        )
    }
}
