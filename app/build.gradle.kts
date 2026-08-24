import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Issue #7: the nav skeleton's routes (AlbumRoute, SearchRoute, BinRoute)
    // are @Serializable data types consumed by Navigation Compose's
    // type-safe routing — the compiler plugin generates their KSerializers.
    // Applied directly here rather than declared "apply false" in the root
    // build file, matching :api's existing precedent for this same plugin.
    alias(libs.plugins.kotlin.serialization)
    // KSP, not kapt (issue #7's explicit requirement) -- WXYC-Android is
    // still on kapt; this repo starts clean on the successor.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Release signing is configured from a `keystore.properties` that is gitignored
// and never present on a fresh clone or in CI, so its absence must not break the
// build — only `assembleRelease` needs it. See docs/signing.md; the upload key is
// identified by SHA-256 fingerprint, never by keystore filename.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "org.wxyc.dj"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.wxyc.dj"
        // Not a capability floor — nothing in the stack needs past API 21 — but a
        // security one: API 24/25 devices receive no security patches, and this
        // app holds a live station credential. A DJ on such a phone still has
        // dj.wxyc.org, so the exclusion costs a login rather than their show.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        // Deliberately NOT enabling buildConfig. This app has no secrets to
        // route through it: its base URLs are public hostnames and its only
        // credential is the DJ's own session, so Configuration carries plain
        // presets. See CLAUDE.md — no secrets.properties, and none should be
        // added.
        buildConfig = false
    }

    lint {
        // The gate is only real if lint can fail the build.
        abortOnError = true
        checkReleaseBuilds = false
        // AGP writes this file and ABORTS THE RUN when `baseline` points at a
        // path that does not exist, so the empty `<issues format="6"/>` stub is
        // committed rather than left to be created on first run.
        baseline = file("lint-baseline.xml")
        // Findings must reach the CI console; the HTML report exists only in
        // the uploaded artifact.
        textReport = true
        warningsAsErrors = false
        disable += setOf("MissingTranslation")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }

        // Compose's UI-test clock idles on animations; leaving them on is the
        // standard source of instrumented flake. ATD images ship with no
        // window animations anyway, so this is here for the day a physical
        // device or a non-ATD image runs the same suite.
        animationsDisabled = true

        // A Gradle Managed Device, not a third-party emulator action. AGP
        // provisions the image and the AVD itself, so `./gradlew
        // :app:atdApi30DebugAndroidTest` is the same command locally and in
        // CI — which matters more here than usual, because the entire reason
        // this tier exists is to reproduce device-only behavior a maintainer
        // then has to debug.
        //
        // `aosp-atd` (Automated Test Device) is the headless, UI-stripped
        // image Google publishes for exactly this: no Play services, no
        // launcher apps, materially faster boot. It is published for both
        // x86_64 and arm64-v8a at API 30-36, so one declaration serves the
        // ubuntu-latest runner and an Apple-silicon laptop.
        //
        // API 30 is the FLOOR the ATD program reaches, and the floor is the
        // point: this tier's whole value is catching runtime behavior the
        // desktop JVM and Robolectric get wrong, and that divergence is
        // likeliest at the oldest runtime the app supports (minSdk 26). It is
        // deliberately NOT targetSdk 36 — a second device at the top of the
        // range is the right answer once Compose surfaces land and
        // targetSdk-36 behavior changes (edge-to-edge, predictive back)
        // become the divergence that matters, and adding one is a five-line
        // `create(...)` block plus a matching CI task name.
        //
        // `testedAbi` is deliberately left unset, with a dated caveat: AGP
        // warns that it presently defaults to "x86" and that AGP 9.0 will
        // change that default to "arm64-v8a". Today's default is what the
        // ubuntu runner needs (it resolves the API 30 ATD x86 image and runs),
        // and pinning "x86" here would break this device on an Apple-silicon
        // laptop, which is half the point of choosing an image published for
        // both. Revisit on the AGP 9 upgrade — API 31+ ATD publishes no 32-bit
        // x86 at all, so moving the floor up is the likely resolution.
        managedDevices {
            localDevices {
                create("atdApi30") {
                    device = "Pixel 6"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

// Works around a real Hilt Gradle plugin bug (google/dagger#4976, #4048): its
// opt-in cross-module "aggregating task" (:app:hiltAggregateDepsDebug) fails
// with `NoSuchMethodError: ClassName.canonicalName()` -- a JavaPoet version
// mismatch on that task's own worker classpath, reproduced against Hilt 2.58
// (the newest release whose Gradle plugin still supports AGP 8.x -- 2.59+
// requires AGP 9) on this repo's AGP 8.13.2 + Gradle 9.0 combination. The
// aggregating task exists to discover @Module/@EntryPoint types published
// from a *separate* library module's AAR; this repo has exactly one Hilt
// consumer (:app itself -- :api is a pure JVM module that can't use Hilt at
// all, per this repo's CLAUDE.md), so disabling it costs nothing here.
hilt {
    enableAggregatingTask = false
}

// Pin the JDK Gradle compiles and runs tests on, not just the bytecode level:
// compileOptions governs -target, while unit tests run on the daemon's JDK — a
// machine with a newer JAVA_HOME otherwise diverges from CI's Temurin 17.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Backs collectAsStateWithLifecycle() -- the repo-wide convention
    // (CLAUDE.md: "StateFlow + collectAsStateWithLifecycle (never
    // LiveData)") for observing AuthService.state from AuthGate.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Issue #7: navigation skeleton (type-safe @Serializable routes).
    implementation(libs.androidx.navigation.compose)
    // No kotlinx-serialization-JSON on the *production* classpath, deliberately.
    // :app once needed one so AlbumSearchResultNavType could round-trip
    // AlbumRoute's fallback row through :api's WxycJson codec; issue #23
    // deleted that NavType (the route is id-only now, and Int needs no custom
    // NavType), so no main-source file names the Json type any more. The
    // @Serializable routes still compile: their generated serializers need
    // serialization-*core*, which arrives transitively with navigation-compose.
    // It IS a testImplementation below -- LoginViewModelTest parses request
    // bodies it captured off MockWebServer -- which is the whole point of the
    // split: a test-only parser must not put a JSON codec in the shipped APK.

    // Issue #7: Hilt graph, KSP not kapt.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Issue #7: encrypted token storage -- DataStore holds the ciphertext,
    // Tink (over a Keystore-wrapped master key) does the encrypting. See
    // token/EncryptedTokenStorage.kt for why not EncryptedSharedPreferences.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)

    // Issue #7: Coil's ImageLoader must be built over CookielessHttpClient
    // (di/NetworkModule.kt) rather than a default OkHttpClient Coil would
    // otherwise construct itself -- see CookielessHttpClient's KDoc on why a
    // second, cookie-armed client anywhere in the app is a real regression
    // risk once cover art is proxied through api.wxyc.org.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit4)
    // Issue #8: LoginViewModel drives a real AuthService against MockWebServer,
    // the same pattern :api's ApiClientTest already uses -- and
    // kotlinx-coroutines-test's TestDispatcher is what lets its test-only
    // MainDispatcherRule (app/src/test/.../ui/login/) run the resend
    // cooldown's viewModelScope coroutine without a real 30s wait.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // Test-only, and note the scope: issue #23 removed the `implementation`
    // one when it deleted the last production consumer (see above). This
    // parses request bodies captured off MockWebServer, so it belongs on the
    // test classpath and must not drift back onto the production one.
    testImplementation(libs.kotlinx.serialization.json)
    // The one place the pure-JVM :api/:app split can produce false confidence
    // (see BinSorting's KDoc): Android's java.text.Collator delegates to
    // android.icu, and the desktop JVM's does not, so a green :api:test alone
    // doesn't prove the bin sort order holds on device. Robolectric cannot
    // load the real java.text.Collator façade either — its
    // SdkSandboxClassLoader only sandboxes android.*, so java.text.Collator
    // still resolves to the bootstrap JDK's RuleBasedCollator under this
    // runner — but android.icu.text.Collator, the class that façade actually
    // delegates to on device, *is* loadable here. BinCollationParityTest
    // builds one directly and checks it against BinSorting's ordering.
    testImplementation(libs.robolectric)

    // The instrumented tier. It exists because Robolectric demonstrably
    // cannot answer questions about `java.*` platform behavior — its
    // SdkSandboxClassLoader only sandboxes `android.*` — so a suite that
    // believes it is testing Android's `java.text.Collator` is in fact
    // testing the host JDK's. See BinCollationDeviceTest, and
    // InstrumentedTierTest for the guard that keeps this tier honest.
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
