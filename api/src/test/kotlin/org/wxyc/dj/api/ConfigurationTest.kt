package org.wxyc.dj.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the two hardcoded [Configuration] presets against the values the iOS
 * `Configuration.swift` presets carry, including the trailing-slash
 * normalization [HttpUrl][okhttp3.HttpUrl] applies to a bare-host URL with
 * no path (`apiBaseUrl`) versus one that already has a path segment
 * (`authBaseUrl`).
 */
class ConfigurationTest {

    @Test
    fun `production matches the iOS preset`() {
        val configuration = Configuration.production

        assertEquals("https://api.wxyc.org/auth".toHttpUrl(), configuration.authBaseUrl)
        assertEquals("https://api.wxyc.org".toHttpUrl(), configuration.apiBaseUrl)
        assertEquals(15L, configuration.timeoutSeconds)
    }

    @Test
    fun `localDevelopment matches the iOS preset`() {
        val configuration = Configuration.localDevelopment

        assertEquals("http://localhost:8082/auth".toHttpUrl(), configuration.authBaseUrl)
        assertEquals("http://localhost:8080".toHttpUrl(), configuration.apiBaseUrl)
        assertEquals(15L, configuration.timeoutSeconds)
    }

    /**
     * Pins the asymmetry [Configuration]'s KDoc warns about: holding an
     * [okhttp3.HttpUrl] does not by itself make `"$base/path"` string
     * interpolation safe. [Configuration.apiBaseUrl]'s literal has no path
     * segment, so it round-trips through `toString()` *with* a trailing
     * slash; [Configuration.authBaseUrl]'s literal already has one (`/auth`)
     * and round-trips *without* one. Naive interpolation against the two
     * would therefore disagree on whether a slash doubles — the reason a
     * later PR must build request paths through the `HttpUrl` API
     * (`newBuilder().addPathSegments(...)` or `resolve()`) instead.
     */
    @Test
    fun `apiBaseUrl round-trips with a trailing slash, authBaseUrl does not`() {
        assertEquals("https://api.wxyc.org/", Configuration.production.apiBaseUrl.toString())
        assertEquals("https://api.wxyc.org/auth", Configuration.production.authBaseUrl.toString())
    }
}
