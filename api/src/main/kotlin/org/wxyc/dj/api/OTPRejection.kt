package org.wxyc.dj.api

/**
 * The refusals `POST /auth/sign-in/email-otp` can answer with, and what to
 * tell the DJ about each (issue #4).
 *
 * better-auth's own messages ("Invalid OTP", "OTP expired", "Too many
 * attempts") are accurate but describe the *server's* finding rather than
 * the DJ's next action, and the three call for genuinely different actions:
 * a wrong code should be retyped, an expired one must be re-requested, and
 * a lockout can only be cleared by requesting a fresh code.
 *
 * [copyFor] matches the wire `code` against [entries] by string rather than
 * decoding it into this enum up front, so a code shipped server-side ahead
 * of this app degrades to the server's own message instead of throwing —
 * the same forward-compatible posture [SignInIdentifier] takes on an
 * unrecognized identifier shape.
 *
 * [copy] is deliberately **unpunctuated**: it reaches the DJ through
 * [AuthError.Rejected], whose message appends a period unconditionally with
 * no doubling guard. Ending here exactly where better-auth's own messages
 * end keeps that contract true rather than widening it for one caller.
 *
 * Mirrors iOS's `OTPRejection`.
 */
enum class OTPRejection(val code: String, val copy: String) {
    /**
     * `400` — the code doesn't match. Also what an unknown *account* gets,
     * since `disableSignUp: true` makes the server refuse to distinguish
     * the two.
     */
    INVALID_OTP("INVALID_OTP", "That code isn't right. Check it and try again"),

    /** `400` — past the 5-minute `expiresIn` window. */
    OTP_EXPIRED("OTP_EXPIRED", "That code has expired. Request a new one"),

    /**
     * `403`, not `400` — `atomicVerifyOTP` raises this one via
     * `APIError.from("FORBIDDEN", …)` while the other two are
     * `"BAD_REQUEST"`. Both statuses land in the same refusal arm in
     * [AuthWireClient.establishSession], so nothing here branches on the
     * difference, but a test that stubs it must use 403.
     */
    TOO_MANY_ATTEMPTS("TOO_MANY_ATTEMPTS", "Too many incorrect attempts. Request a new code"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /**
         * The [AuthWireClient.establishSession] `rejectionMessage` hook for
         * the OTP verify route: friendlier wording when the code is one
         * this app recognizes, otherwise [fallback] (the server's own
         * message) untouched.
         */
        fun copyFor(code: String?, fallback: String?): String? =
            code?.let { byCode[it] }?.copy ?: fallback
    }
}
