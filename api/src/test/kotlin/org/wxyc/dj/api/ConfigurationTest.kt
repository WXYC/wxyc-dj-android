package org.wxyc.dj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigurationTest {

    @Test
    fun `production matches the iOS preset`() {
        val configuration = Configuration.production

        assertEquals("https://api.wxyc.org/auth", configuration.authBaseUrl)
        assertEquals("https://api.wxyc.org", configuration.apiBaseUrl)
        assertEquals(15L, configuration.timeoutSeconds)
    }

    @Test
    fun `localDevelopment matches the iOS preset`() {
        val configuration = Configuration.localDevelopment

        assertEquals("http://localhost:8082/auth", configuration.authBaseUrl)
        assertEquals("http://localhost:8080", configuration.apiBaseUrl)
        assertEquals(15L, configuration.timeoutSeconds)
    }
}
