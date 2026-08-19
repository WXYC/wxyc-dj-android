package org.wxyc.dj.api

import java.time.Instant
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [JwtDecoder]'s client-side-only contract: it reads `sub`/`email`/
 * `role`/`exp` off a JWT's payload segment without verifying the signature,
 * tolerates unread structured claims of any shape (a real Backend-Service
 * token carries several this module doesn't read), and maps every failure
 * mode — wrong segment count, undecodable base64url, undecodable JSON — to a
 * typed [JwtDecodeError] rather than letting a raw parse exception escape.
 */
class JwtDecoderTest {

    @Test
    fun `decodes payload claims`() {
        val token = jwtFixture(expiresIn = 3600)

        val payload = JwtDecoder.decode(token)

        assertEquals("42", payload.sub)
        assertEquals("juana@wxyc.org", payload.email)
        assertEquals("dj", payload.role)
        assertTrue(payload.expiration.epochSecond - Instant.now().epochSecond > 3500)
    }

    /**
     * A real Backend-Service token carries structured claims this module
     * doesn't read — `capabilities` is always present, even as `[]` — and
     * iOS's `JSONDecoder` keyed-container decode of `JWTPayload.swift`
     * silently ignores whatever shape they take. This hand-rolled decoder
     * must do the same rather than treat an array or nested object as
     * undecodable, or every real token fails to decode.
     */
    @Test
    fun `tolerates unread structured claims of any shape`() {
        val token = jwtFixture(
            expiresIn = 3600,
            extraClaimsJson = """
                ,"capabilities":["bin:read","bin:write"],
                "emailVerified":true,
                "image":null,
                "createdAt":1755500000,
                "session":{"impersonatedBy":null,"nested":{"deep":[1,2,3]}}
            """.trimIndent(),
        )

        val payload = JwtDecoder.decode(token)

        assertEquals("42", payload.sub)
        assertEquals("juana@wxyc.org", payload.email)
        assertEquals("dj", payload.role)
    }

    @Test
    fun `rejects a token with the wrong segment count`() {
        assertThrows(JwtDecodeError.Malformed::class.java) {
            JwtDecoder.decode("just-one-segment")
        }
    }

    @Test
    fun `rejects a token with an undecodable payload`() {
        val bad = "eyJhbGciOiJIUzI1NiJ9.@@@@.sig"

        // The specific arm, not the sealed parent: asserting the parent leaves
        // Base64DecodeFailed the one branch of the three-way contract no test
        // names, so a refactor that folded it into PayloadDecodeFailed would
        // pass. AuthService keys on these arms differently.
        assertThrows(JwtDecodeError.Base64DecodeFailed::class.java) {
            JwtDecoder.decode(bad)
        }
    }

    @Test
    fun `rejects a payload segment that is valid base64 but not a JSON object`() {
        val notJson = Base64.getUrlEncoder().withoutPadding().encodeToString("not json".toByteArray())
        val bad = "eyJhbGciOiJIUzI1NiJ9.$notJson.sig"

        assertThrows(JwtDecodeError.PayloadDecodeFailed::class.java) {
            JwtDecoder.decode(bad)
        }
    }

    // No test attempts to reproduce a StackOverflowError via a deeply nested
    // unread claim here — verified, not assumed: probed up to 5,000,000
    // levels of both object and array nesting under ignoreUnknownKeys (and
    // as a fully materialized JsonElement tree), and none overflowed the
    // stack. kotlinx.serialization's skip/parse path appears to walk nested
    // structures iteratively rather than via JVM call-stack recursion, so
    // the StackOverflowError decode()'s catch clause guards against is not
    // reproducible against the current dependency version. The catch stays
    // as defense-in-depth against a future kotlinx.serialization version, a
    // different runtime's stack behavior, or a differently-shaped payload —
    // see JwtPayload.kt's decode() for the reasoning — but a test asserting
    // it fires would have to fabricate the failure rather than trigger it,
    // which is worse than no test at all.

    private fun jwtFixture(expiresIn: Long, extraClaimsJson: String = ""): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val exp = Instant.now().epochSecond + expiresIn
        val payload =
            """{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":$exp$extraClaimsJson}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "$encodedHeader.$encodedPayload.signature"
    }
}
