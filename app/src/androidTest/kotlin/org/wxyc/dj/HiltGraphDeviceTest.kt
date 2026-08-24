package org.wxyc.dj

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.wxyc.dj.di.AppEntryPoint

/**
 * Issue #7's "the Hilt graph actually constructs on device" acceptance
 * criterion. `./gradlew :app:testDebugUnitTest` only proves this app's Hilt
 * modules *compile* -- Robolectric cannot stand in for the real
 * `dagger.hilt.android.internal.managers.ApplicationComponentManager` that
 * assembles on a genuine `Application.onCreate()`, and a Keystore- or
 * DataStore-backed provider (`di/TokenStorageModule.kt`) failing only on a
 * real Android runtime is exactly the shape of gap this repo's `CLAUDE.md`
 * ("The instrumented tier, and why it is not optional") names.
 *
 * This resolves [AppEntryPoint] against the **real, installed app's**
 * `SingletonComponent` -- there is no test-only Hilt component substituted
 * here (no `@HiltAndroidTest`, no `HiltTestApplication`), because the point
 * is to prove the production graph, not a test double of it. Each accessor
 * call below exercises a real `@Provides` function transitively: reaching
 * [AppEntryPoint.authService] alone already resolves `Configuration`,
 * `CookielessHttpClient`, and the encrypted `TokenStorage` (Keystore + Aead
 * + DataStore) behind it, but every top-level binding is asserted
 * individually so a future provider that Hilt can satisfy structurally
 * (e.g. by binding to `null`-returning code, which Kotlin's non-null types
 * would already forbid, or one nobody currently reaches from an injection
 * site) still gets exercised here.
 */
@RunWith(AndroidJUnit4::class)
class HiltGraphDeviceTest {
    @Test
    fun theProductionHiltGraphResolvesEveryTopLevelBindingOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entryPoint = EntryPointAccessors.fromApplication(context, AppEntryPoint::class.java)

        assertNotNull(entryPoint.configuration())
        assertNotNull(entryPoint.cookielessHttpClient())
        assertNotNull(entryPoint.tokenStorage())
        assertNotNull(entryPoint.authService())
        assertNotNull(entryPoint.apiClient())
        assertNotNull(entryPoint.imageLoader())
    }
}
