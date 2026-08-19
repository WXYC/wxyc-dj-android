package org.wxyc.dj.api

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    /**
     * A second tripwire, alongside the Android one above: `:api` runs on
     * `useJUnitPlatform()` with jupiter and no vintage engine, so a test
     * method carrying JUnit 4's `@org.junit.Test` compiles cleanly, produces
     * no result XML, and leaves `./gradlew :api:test` green while running
     * nothing at all — one autocomplete away for anyone moving between this
     * module (JUnit 5) and `:app` (JUnit 4).
     *
     * This can't be caught by asserting `org.junit.Test` is unresolvable:
     * `okhttp3.mockwebserver.MockWebServer` directly `extends
     * org.junit.rules.ExternalResource` (verified via `javap` against the
     * resolved 4.12.0 jar), so `junit:junit` is structurally required on
     * this module's test compile *and* runtime classpath as long as
     * MockWebServer is used here — excluding it breaks
     * `:api:compileTestKotlin` outright. So `org.junit.Test` will always
     * resolve; the guard instead inspects this module's own *compiled test
     * classes* for a method actually carrying that annotation, which is the
     * real failure mode a stray JUnit 4 test produces.
     */
    @Test
    fun `no compiled test class carries a JUnit 4 @Test annotation`() {
        val testClassesRoot = File(
            PureJvmModuleTest::class.java.protectionDomain.codeSource.location.toURI(),
        )

        val offenders = testClassesRoot.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { file ->
                file.relativeTo(testClassesRoot).path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }
            .mapNotNull { className ->
                runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()
            }
            .filter { candidate ->
                candidate.declaredMethods.any { method ->
                    method.annotations.any { it.annotationClass.qualifiedName == "org.junit.Test" }
                }
            }
            .map { it.name }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "JUnit 4 @org.junit.Test found on: $offenders — :api runs useJUnitPlatform() " +
                "with no vintage engine, so these methods compile but silently never run. " +
                "Use org.junit.jupiter.api.Test instead.",
        )
    }
}
