package org.wxyc.dj.api

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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
    // Every subclass below is an `object` — a singleton constructed once,
    // lazily, the first time it's referenced. Exception's default
    // fillInStackTrace() runs at construction, so an unmodified singleton's
    // stack trace always points at that first-touch <clinit> frame rather
    // than wherever it was actually thrown from. Overriding it to a no-op
    // makes that omission deliberate — this decoder's errors are a typed
    // signal to branch on, not a trace to debug from — instead of a trap for
    // whoever next reads a report from this decoder and wonders why every
    // one points at the same place.
    override fun fillInStackTrace(): Throwable = this

    object Malformed : JwtDecodeError("token did not have three dot-separated segments")
    object Base64DecodeFailed : JwtDecodeError("payload segment was not valid base64url")
    object PayloadDecodeFailed : JwtDecodeError("payload segment was not a decodable JWT payload")
}

/**
 * The wire shape of a JWT payload, decoded structurally rather than by hand.
 * `ignoreUnknownKeys` is load-bearing: a real Backend-Service token also
 * carries claims this module doesn't read (`capabilities`, `emailVerified`,
 * `image`, `createdAt`, a nested `session` object, and others), and every one
 * of them must decode-and-discard rather than fail the whole payload —
 * mirroring the tolerance `JSONDecoder` gives iOS's keyed-container decode of
 * `JWTPayload.swift` for free. `exp` is `Double` because the claim is a Unix
 * timestamp in seconds, not milliseconds.
 */
@Serializable
private data class RawJwtPayload(
    val sub: String? = null,
    val email: String? = null,
    val role: String? = null,
    val exp: Double,
)

/**
 * Minimal client-side JWT decoder. Reads the payload claims Backend-Service
 * puts on the wire without verifying the signature — the server validates
 * that against JWKS on every request, so re-deriving trust here would be
 * security theater on a token this module did not mint. Mirrors
 * `JWTPayload.swift`'s `JWTDecoder`.
 *
 * Payload decoding goes through kotlinx.serialization rather than a
 * hand-rolled parser: the earlier hand-rolled recursive-descent parser was
 * the confirmed source of a `StackOverflowError` on the cold-launch path.
 * `decode` still catches `StackOverflowError` explicitly below, as
 * defense-in-depth — checked, not assumed: probing kotlinx.serialization's
 * reader up to 5,000,000 levels of nested unread claims (both object and
 * array nesting, under `ignoreUnknownKeys`, and as a fully materialized
 * `JsonElement` tree) never overflowed the stack, so unlike the parser this
 * replaces, its skip/parse path does not appear to recurse per nesting level
 * on the JVM. The catch stays anyway: `StackOverflowError` is an `Error`, so
 * an uncaught one would still crash the caller outright rather than surface
 * as a typed [JwtDecodeError], and that guarantee is cheap to keep against a
 * future kotlinx.serialization version or a differently-shaped payload,
 * even with no reproducible case backing it today. Depending on
 * kotlinx.serialization here rather than avoiding the dependency is
 * deliberate regardless: the port plan already commits `:api` to it for the
 * DTOs that land next, so this is that dependency arriving one PR earlier
 * alongside its first real use, per the repo's own "entries are added by the
 * PR that first uses them" rule.
 */
object JwtDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(token: String): JwtPayload {
        val segments = token.split(".")
        if (segments.size != 3) throw JwtDecodeError.Malformed

        val payloadJson = base64UrlDecode(segments[1])
            ?.toString(Charsets.UTF_8)
            ?: throw JwtDecodeError.Base64DecodeFailed

        val raw = try {
            json.decodeFromString(RawJwtPayload.serializer(), payloadJson)
        } catch (e: SerializationException) {
            throw JwtDecodeError.PayloadDecodeFailed
        } catch (e: IllegalArgumentException) {
            throw JwtDecodeError.PayloadDecodeFailed
        } catch (e: StackOverflowError) {
            // Defense-in-depth, not a reproduced failure against the current
            // kotlinx.serialization version — see this object's KDoc.
            // StackOverflowError is an Error, so it is not caught by either
            // clause above; if some future payload shape or dependency
            // version ever does overflow here, this is what keeps it a
            // typed JwtDecodeError instead of a crash.
            throw JwtDecodeError.PayloadDecodeFailed
        }

        return JwtPayload(
            sub = raw.sub,
            email = raw.email,
            role = raw.role,
            exp = Instant.ofEpochMilli((raw.exp * 1000).toLong()),
        )
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
}
