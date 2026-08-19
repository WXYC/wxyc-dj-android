import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    }
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
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    // The one place the pure-JVM :api/:app split can produce false confidence
    // (see BinSorting's KDoc): Android's java.text.Collator delegates to
    // android.icu, and the desktop JVM's does not, so a green :api:test alone
    // doesn't prove the bin sort order holds on device. Robolectric runs the
    // real platform Collator on the JVM without an emulator.
    testImplementation(libs.robolectric)
}
