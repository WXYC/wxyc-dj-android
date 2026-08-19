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
 * dependency here (see [CookielessHttpClient]), and `HttpUrl` normalizes the
 * trailing-slash question at parse time instead of leaving it latent for
 * whichever later PR concatenates a request path onto [apiBaseUrl] — a plain
 * `String` would let `"$base/library/info"` and `"$base" + "/library/info"`
 * silently disagree depending on whether `base` happened to end in `/`.
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
