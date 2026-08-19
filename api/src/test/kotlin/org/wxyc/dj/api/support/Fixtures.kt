package org.wxyc.dj.api.support

import java.time.Instant
import java.util.Base64

/**
 * Shared fixture builders for `:api` tests that exercise [org.wxyc.dj.api.AuthService]
 * against a stubbed wire. Mirrors WXYCAPITests' `Fixtures.jwt(expiresIn:)`.
 */
object Fixtures {
    /**
     * JWT with payload `{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":<now+expiresIn>}`.
     * Signature is a placeholder ("sig"); [org.wxyc.dj.api.JwtDecoder] does not verify it.
     */
    fun jwt(expiresInSeconds: Long = 600): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val exp = Instant.now().epochSecond + expiresInSeconds
        val payload = """{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":$exp}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "$encodedHeader.$encodedPayload.sig"
    }
}
