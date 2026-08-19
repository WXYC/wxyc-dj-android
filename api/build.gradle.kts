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
    alias(libs.plugins.kotlin.jvm)
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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
