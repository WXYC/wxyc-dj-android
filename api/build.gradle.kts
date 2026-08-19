// :api is a PURE JVM module — `org.jetbrains.kotlin.jvm`, never
// `com.android.library`. This is the structural decision the whole repo is
// arranged around: it is the analogue of the iOS split that lets
// `swift test --package-path Packages/WXYCAPI` run the auth state machine, the
// DTO decoding, the identifier router and the rotation predicate on the host
// with no simulator. Here the alternative is an *emulator*, so the payoff is
// larger: these are the suites that run hundreds of times a day and they must
// run in milliseconds.
//
// The cost is the rule that keeps it true: nothing touching the Android SDK may
// live here. That means Hilt (`com.google.dagger.hilt.android`) lives entirely
// in :app — :api exposes plain factories and constructors for :app to wire —
// platform-backed implementations of an :api interface (encrypted token
// storage, connectivity) live in :app beside their `@Module`, and :api stays
// SDK-free: no Sentry, no PostHog, no analytics of any kind.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    // api, not implementation — but NOT because the client is exposed: the
    // constructor and the wrapped OkHttpClient are both internal, and keeping
    // them that way is what makes the cookie policy hold by construction. Do
    // not "reconcile" this comment by re-publishing the client.
    //
    // Two things genuinely need OkHttp on :app's compile classpath:
    // CookielessHttpClient's `okhttp3.Call.Factory` supertype, and
    // OkHttpClient.Builder as derive()'s closure receiver. Downgrading to
    // implementation breaks :app with "Cannot access 'Call.Factory' which is
    // a supertype of 'CookielessHttpClient'".
    api(libs.okhttp)

    // implementation, not api: JwtDecoder's use of kotlinx.serialization is
    // an internal implementation detail — JwtPayload itself stays a plain
    // data class with no @Serializable on its public shape, so no consumer
    // needs this on its own compile classpath.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // MockWebServer 4.x's own class directly `extends
    // org.junit.rules.ExternalResource` (verified via javap against the
    // resolved jar), so junit:junit cannot be excluded here — Kotlin's
    // compiler requires every supertype in a referenced class's hierarchy to
    // be resolvable, and the JVM verifier requires the same at class-load
    // time. `exclude(group = "junit", module = "junit")` was tried and
    // confirmed to break `:api:compileTestKotlin` outright wherever
    // MockWebServer is referenced. Because junit:junit therefore always
    // stays on this classpath, `org.junit.Test` will always resolve here —
    // PureJvmModuleTest's tripwire scans compiled test *classes* for a
    // JUnit 4 `@Test` annotation instead of asserting the class is absent
    // from the classpath, since that would be a permanently-failing
    // assertion under this constraint.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
