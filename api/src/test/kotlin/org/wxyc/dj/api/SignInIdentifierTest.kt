package org.wxyc.dj.api

import java.util.stream.Stream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Pins the issue-#97 sign-in routing predicate (issue #3 invariant 6): an
 * identifier bearing an `@` is an email (and takes better-auth's
 * `/sign-in/email` route with an `email` body key), anything else stays a
 * username on the pre-#97 path. Also pins that the username body is
 * byte-for-byte what it always was. Ported from `SignInIdentifierTests.swift`.
 */
class SignInIdentifierTest {
    @ParameterizedTest
    @MethodSource("routingCases")
    fun `routes on the at sign`(raw: String, expected: SignInIdentifier) {
        assertEquals(expected, SignInIdentifier(raw))
    }

    /**
     * The path and the body key are the pair that must never drift apart —
     * better-auth reads `ctx.body.username` on one route and
     * `ctx.body.email` on the other, so a body posted to the wrong endpoint
     * is unreadable. The username row is also the pre-#97 regression pin:
     * that request is byte-for-byte what it always was.
     */
    @ParameterizedTest
    @MethodSource("routeCarriesBodyKeyCases")
    fun `route carries the body key its endpoint reads`(
        raw: String,
        expectedPath: String,
        expectedKey: String,
    ) {
        val identifier = SignInIdentifier(raw)

        assertEquals(expectedPath, identifier.path)

        val body = Json.parseToJsonElement(identifier.encodedBody("hunter2")).jsonObject
        assertEquals(JsonPrimitive(raw), body[expectedKey])
        assertEquals(JsonPrimitive("hunter2"), body["password"])
        assertEquals(2, body.size)
    }

    @ParameterizedTest
    @MethodSource("passwordIdentifiers")
    fun `password is passed through untouched`(raw: String) {
        val password = "  hunter2 \n"
        val identifier = SignInIdentifier(raw)

        val body = Json.parseToJsonElement(identifier.encodedBody(password)).jsonObject

        assertEquals(JsonPrimitive(password), body["password"])
    }

    /**
     * The same `@` predicate decides a second thing (issue #4): whether the
     * app already knows the DJ's email, or had to ask the server for it.
     *
     * A username is resolved through `POST /auth/wxyc/lookup-email`, which
     * Backend-Service documents as "a mild enumeration vector" it accepts
     * because the route is rate-limited. Displaying that answer would take
     * a vector bounded by request rate and put it on screen for anyone
     * holding the phone, so a looked-up address is never shown — only one
     * the DJ typed. An unresolved *email*, by contrast, is safe to echo and
     * is the only way a DJ can catch their own typo, since
     * `disableSignUp: true` means the send step reports success for an
     * address that matches no account.
     *
     * Pinned here rather than only at [AuthService] because this type is
     * what decides it. Ported from iOS's `onlyATypedEmailIsEverDisclosed`.
     */
    @ParameterizedTest
    @MethodSource("typedEmailCases")
    fun `only a typed email is ever disclosed`(raw: String, expected: String?) {
        assertEquals(expected, SignInIdentifier(raw).typedEmail)
    }

    companion object {
        @JvmStatic
        fun routingCases(): Stream<Arguments> = Stream.of(
            // Plain usernames — every character better-auth's default
            // validator (`/^[a-zA-Z0-9_.]+$/`) permits, so these stay on
            // the username route.
            Arguments.of("juana", SignInIdentifier.Username("juana")),
            Arguments.of("juana.molina", SignInIdentifier.Username("juana.molina")),
            Arguments.of("dj_chuquimamani", SignInIdentifier.Username("dj_chuquimamani")),
            Arguments.of("JessicaPratt89", SignInIdentifier.Username("JessicaPratt89")),
            // Emails — the case that 422'd before #97.
            Arguments.of("juana@wxyc.org", SignInIdentifier.Email("juana@wxyc.org")),
            Arguments.of("jessica.pratt@unc.edu", SignInIdentifier.Email("jessica.pratt@unc.edu")),
            // A malformed email still routes to the email endpoint — a full
            // email regex would call this "not an email" and send it back
            // to the username route, which answers with the very
            // "422 Username is invalid" #97 exists to stop showing.
            // /sign-in/email answers "400 Invalid email" instead.
            Arguments.of("juana@wxyc", SignInIdentifier.Email("juana@wxyc")),
            Arguments.of("@", SignInIdentifier.Email("@")),
            // A hyphen is outside the username character class, but it is
            // not an `@` — nothing about it says "email", so it stays a
            // username and earns an honest 422.
            Arguments.of("juana-molina", SignInIdentifier.Username("juana-molina")),
            // Empty never reaches this in practice, but the predicate must
            // still be total rather than trapping.
            Arguments.of("", SignInIdentifier.Username("")),
        )

        @JvmStatic
        fun routeCarriesBodyKeyCases(): Stream<Arguments> = Stream.of(
            Arguments.of("juana", "sign-in/username", "username"),
            Arguments.of("juana@wxyc.org", "sign-in/email", "email"),
        )

        @JvmStatic
        fun passwordIdentifiers(): Stream<Arguments> = Stream.of(
            Arguments.of("juana"),
            Arguments.of("juana@wxyc.org"),
        )

        @JvmStatic
        fun typedEmailCases(): Stream<Arguments> = Stream.of(
            Arguments.of("juana@wxyc.org", "juana@wxyc.org"),
            Arguments.of("jessica.pratt@unc.edu", "jessica.pratt@unc.edu"),
            // Typo'd but still email-shaped: echoing it back is exactly how
            // the DJ sees the mistake, since nothing downstream can report it.
            Arguments.of("juana@wxyc", "juana@wxyc"),
            // Usernames resolve to an address the DJ never typed — withheld.
            Arguments.of("juana", null),
            Arguments.of("dj_chuquimamani", null),
        )
    }
}
