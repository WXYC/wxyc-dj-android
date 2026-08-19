package org.wxyc.dj.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Auth and API base URLs plus the shared request timeout. [production] and
 * [localDevelopment] are hardcoded presets, not a shortcut: this app's base
 * URLs are public hostnames and its only credential is the DJ's own session,
 * so there is deliberately no secrets mechanism backing this — see the
 * repo's `CLAUDE.md` before adding one.
 *
 * Base URLs are [HttpUrl], not `String`. OkHttp is already an `api`
 * dependency here (see [CookielessHttpClient]), and the type steers a later
 * PR's endpoint construction toward [HttpUrl.newBuilder] +
 * `addPathSegments(...)` (or [HttpUrl.resolve]) instead of `String`
 * interpolation — which [HttpUrl] does **not** make safe on its own.
 * [apiBaseUrl]'s literal has no path segment, so it round-trips through
 * `toString()` *with* a trailing slash (`"https://api.wxyc.org/"`);
 * [authBaseUrl]'s literal already has one (`/auth`), so it round-trips
 * *without* one. `"$apiBaseUrl/library/info"` would therefore double a
 * slash while the parallel `"$authBaseUrl/sign-in/username"` would not —
 * see `ConfigurationTest` for both round-trips pinned. Building the request
 * path through the `HttpUrl` API rather than its `toString()` is what
 * actually removes the hazard; holding an `HttpUrl` instead of a `String`
 * only makes that the easy path to reach for.
 *
 * [localDevelopment] needs a debug-only cleartext-traffic allowance wired
 * into the manifest before it is reachable end-to-end: `targetSdk 36` blocks
 * plain `http://` by default. That config is not this file's job — it
 * belongs with the `:app` shell (issue #7) — this is only the note so the
 * requirement isn't rediscovered the hard way when that PR lands.
 *
 * Mirrors `Packages/WXYCAPI/Sources/WXYCAPI/Configuration.swift` on iOS.
 */
data class Configuration(
    val authBaseUrl: HttpUrl,
    val apiBaseUrl: HttpUrl,
    val timeoutSeconds: Long = 15,
) {
    companion object {
        val production = Configuration(
            authBaseUrl = "https://api.wxyc.org/auth".toHttpUrl(),
            apiBaseUrl = "https://api.wxyc.org".toHttpUrl(),
        )

        val localDevelopment = Configuration(
            authBaseUrl = "http://localhost:8082/auth".toHttpUrl(),
            apiBaseUrl = "http://localhost:8080".toHttpUrl(),
        )
    }
}
