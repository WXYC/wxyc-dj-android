package org.wxyc.dj.api

import java.time.Instant
import java.util.Base64

/**
 * The claims Backend-Service puts in a JWT that this module reads: subject,
 * email, role, and expiry. Mirrors `JWTPayload.swift`.
 */
data class JwtPayload(
    val sub: String?,
    val email: String?,
    val role: String?,
    val exp: Instant,
) {
    val expiration: Instant get() = exp
}

/** Mirrors iOS's `JWTDecodeError`. */
sealed class JwtDecodeError(message: String) : Exception(message) {
    object Malformed : JwtDecodeError("token did not have three dot-separated segments")
    object Base64DecodeFailed : JwtDecodeError("payload segment was not valid base64url")
    object PayloadDecodeFailed : JwtDecodeError("payload segment was not a decodable JWT payload")
}

/**
 * Minimal client-side JWT decoder. Reads the payload claims Backend-Service
 * puts on the wire without verifying the signature — the server validates
 * that against JWKS on every request, so re-deriving trust here would be
 * security theater on a token this module did not mint. Mirrors
 * `JWTPayload.swift`'s `JWTDecoder`.
 */
object JwtDecoder {
    fun decode(token: String): JwtPayload {
        val segments = token.split(".")
        if (segments.size != 3) throw JwtDecodeError.Malformed

        val payloadJson = base64UrlDecode(segments[1])
            ?.toString(Charsets.UTF_8)
            ?: throw JwtDecodeError.Base64DecodeFailed

        return try {
            parsePayload(payloadJson)
        } catch (e: JwtDecodeError) {
            throw e
        } catch (e: Exception) {
            throw JwtDecodeError.PayloadDecodeFailed
        }
    }

    private fun base64UrlDecode(segment: String): ByteArray? {
        val standard = segment.replace('-', '+').replace('_', '/')
        val padding = (4 - standard.length % 4) % 4
        val padded = standard + "=".repeat(padding)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun parsePayload(json: String): JwtPayload {
        val fields = JsonObjectParser.parse(json)
        val exp = (fields["exp"] as? Double) ?: throw JwtDecodeError.PayloadDecodeFailed
        return JwtPayload(
            sub = fields["sub"] as? String,
            email = fields["email"] as? String,
            role = fields["role"] as? String,
            exp = Instant.ofEpochMilli((exp * 1000).toLong()),
        )
    }
}

/**
 * A hand-rolled parser for the `{"key": value}` shape a JWT payload takes, so
 * decoding a token doesn't require a JSON dependency this module doesn't
 * otherwise need. This module only reads `sub`/`email`/`role`/`exp`, but a
 * real Backend-Service token also carries structured claims this module does
 * not — `capabilities: []`, `image: null`, and others — so every value shape
 * (including nested arrays and objects) is parsed and *discarded* if unread,
 * mirroring the tolerance `JSONDecoder` gives iOS's keyed-container decode of
 * `JWTPayload.swift` for free. Only the claims this module actually reads are
 * kept in the returned map.
 */
private object JsonObjectParser {
    fun parse(json: String): Map<String, Any?> {
        val cursor = Cursor(json)
        cursor.skipWhitespace()
        val value = cursor.parseValue()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw IllegalArgumentException("payload was not a JSON object")
    }

    private class Cursor(private val json: String) {
        private var index = 0

        fun peek(): Char {
            if (index >= json.length) throw IllegalArgumentException("unexpected end of JSON")
            return json[index]
        }

        fun advance() {
            index++
        }

        fun expect(char: Char) {
            if (peek() != char) throw IllegalArgumentException("expected '$char'")
            advance()
        }

        fun skipWhitespace() {
            while (index < json.length && json[index].isWhitespace()) index++
        }

        fun parseValue(): Any? = when {
            peek() == '"' -> parseString()
            peek() == '{' -> parseObject()
            peek() == '[' -> parseArray()
            json.startsWith("null", index) -> { index += 4; null }
            json.startsWith("true", index) -> { index += 4; true }
            json.startsWith("false", index) -> { index += 5; false }
            else -> parseNumber()
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val fields = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                advance()
                return fields
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        advance()
                        continue
                    }
                    '}' -> {
                        advance()
                        break
                    }
                    else -> throw IllegalArgumentException("malformed JSON object")
                }
            }
            return fields
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val elements = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                advance()
                return elements
            }
            while (true) {
                skipWhitespace()
                elements.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        advance()
                        continue
                    }
                    ']' -> {
                        advance()
                        break
                    }
                    else -> throw IllegalArgumentException("malformed JSON array")
                }
            }
            return elements
        }

        fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (peek() != '"') {
                val char = peek()
                if (char == '\\') {
                    advance()
                    when (val escaped = peek()) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('')
                        'u' -> {
                            val hex = json.substring(index + 1, index + 5)
                            builder.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("invalid escape sequence")
                    }
                    advance()
                } else {
                    builder.append(char)
                    advance()
                }
            }
            advance()
            return builder.toString()
        }

        fun parseNumber(): Double {
            val start = index
            while (index < json.length && (json[index].isDigit() || json[index] in "-+.eE")) {
                index++
            }
            if (index == start) throw IllegalArgumentException("expected a value")
            return json.substring(start, index).toDouble()
        }
    }
}
