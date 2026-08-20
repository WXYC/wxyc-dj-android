package org.wxyc.dj.api

/**
 * Where a mailed one-time sign-in code went, and how much of that the DJ
 * may be told (issue #4).
 *
 * Two fields rather than one, because they answer different questions and
 * conflating them would leak. [email] keys the verify call and may be an
 * address the DJ never typed — resolved from their username by
 * `POST /auth/wxyc/lookup-email`, a route Backend-Service itself describes
 * as "a mild enumeration vector", accepted only because it is rate-limited.
 * Echoing that answer back on screen would promote a rate-limited
 * server-side vector into a displayed one, so only [typedEmail] is
 * renderable, and it is `null` in exactly the case where rendering would
 * leak. dj-site draws the same line in `LoginFormSwitcher.tsx`.
 *
 * The constructor is deliberately `internal`: a public one would let any
 * caller pass the resolved address as [typedEmail] — precisely the leak
 * this type exists to prevent — leaving the guarantee resting on
 * [AuthService.sendLoginCode] happening to be the only constructor rather
 * than being unrepresentable. Internal, within this module, is as narrow as
 * Kotlin visibility gets while still letting `:api`'s own tests construct
 * one directly.
 *
 * `data class`, guarded by [ConsistentCopyVisibility]: an *un*guarded `data
 * class` over a non-public primary constructor still synthesizes a `copy()`
 * that historically remained `public` regardless (KT-11914) — precisely the
 * loophole that would let an outside caller reconstruct a fabricated
 * [typedEmail] through `copy()` even with the constructor locked down.
 * [ConsistentCopyVisibility] closes exactly that hole — the generated
 * `copy()` is mangled to match the constructor's own (`internal`)
 * visibility, verified with `javap -p` — while still letting `equals`/
 * `hashCode`/`toString`/`component1`/`component2` be synthesized instead of
 * hand-written and liable to drift out of sync with the fields. **Do not
 * remove this annotation as noise**: without it, the class still compiles
 * (with a warning) but `copy()` reopens the leak this type exists to
 * prevent.
 *
 * Mirrors iOS's `LoginCodeDestination`.
 */
@ConsistentCopyVisibility
data class LoginCodeDestination internal constructor(
    /** Keys the verify call. May be an address the DJ never typed. */
    val email: String,
    /**
     * The address the DJ typed, or `null` when it was resolved from a
     * username and so must not be shown. Carries the fact; the wording of
     * the `null` case belongs to whichever surface renders it.
     */
    val typedEmail: String?,
)
