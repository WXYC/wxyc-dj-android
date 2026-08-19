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
}
