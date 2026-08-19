package org.wxyc.dj.api

import java.time.Instant
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    @Test
    fun `rejects a token with the wrong segment count`() {
        assertThrows(JwtDecodeError.Malformed::class.java) {
            JwtDecoder.decode("just-one-segment")
        }
    }

    @Test
    fun `rejects a token with an undecodable payload`() {
        val bad = "eyJhbGciOiJIUzI1NiJ9.@@@@.sig"

        assertThrows(JwtDecodeError::class.java) {
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

    private fun jwtFixture(expiresIn: Long): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val exp = Instant.now().epochSecond + expiresIn
        val payload = """{"sub":"42","email":"juana@wxyc.org","role":"dj","exp":$exp}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "$encodedHeader.$encodedPayload.signature"
    }
}
