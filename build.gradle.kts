// Root build file. Plugins are declared here with `apply false` and applied per
// module, so the version catalog stays the single place a version is written.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
