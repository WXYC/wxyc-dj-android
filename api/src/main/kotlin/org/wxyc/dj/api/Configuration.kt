package org.wxyc.dj.api

/**
 * Auth and API base URLs plus the shared request timeout. [production] and
 * [localDevelopment] are hardcoded presets, not a shortcut: this app's base
 * URLs are public hostnames and its only credential is the DJ's own session,
 * so there is deliberately no secrets mechanism backing this — see the
 * repo's `CLAUDE.md` before adding one.
 *
 * Mirrors `Packages/WXYCAPI/Sources/WXYCAPI/Configuration.swift` on iOS.
 */
data class Configuration(
    val authBaseUrl: String,
    val apiBaseUrl: String,
    val timeoutSeconds: Long = 15,
) {
    companion object {
        val production = Configuration(
            authBaseUrl = "https://api.wxyc.org/auth",
            apiBaseUrl = "https://api.wxyc.org",
        )

        val localDevelopment = Configuration(
            authBaseUrl = "http://localhost:8082/auth",
            apiBaseUrl = "http://localhost:8080",
        )
    }
}
