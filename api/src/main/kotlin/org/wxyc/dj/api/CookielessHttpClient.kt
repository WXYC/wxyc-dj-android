package org.wxyc.dj.api

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The sole owner of this module's no-cookie policy — invariant 1, and the
 * load-bearing part of this module.
 *
 * better-auth's `bearer()` after-hook adds a `set-auth-token` response header
 * *without* stripping the `Set-Cookie` it rides alongside. A client that
 * stores that cookie replays it to the host forever — including on the
 * *next* sign-in. better-auth registers `originCheckMiddleware` globally on
 * every non-GET route, and `validateOrigin` enforces the `Origin` header
 * *only when a cookie is present*. A native client sends no `Origin`, so a
 * cookie-bearing sign-in is refused `403 MISSING_OR_NULL_ORIGIN` before any
 * credential check — which reads to the DJ as a wrong password. Verified
 * against production on both sign-in routes.
 *
 * OkHttp's own default is already `CookieJar.NO_COOKIES` — unlike iOS's
 * `URLSession`, a bare `OkHttpClient.Builder().build()` does not arm this
 * failure today. The risk this factory forecloses is a *future* one: wiring
 * a `CookieJar` onto this client is common OkHttp boilerplate for
 * session-based APIs (a `java.net.CookieManager`-backed `JavaNetCookieJar` is
 * the usual copy-pasted answer), and that is exactly the shape the failure
 * needs. A clean sign-out self-heals, because better-auth expires the
 * cookie; the paths that clear tokens *without* a network sign-out do not —
 * a 401 demotion, a restore-time 401, a sign-in rollback, or a sign-out
 * whose call fails all leave the cookie behind, and the next sign-in 403s.
 *
 * The constructor is `internal` **and** the wrapped [okHttpClient] property is
 * `internal`, so no consumer outside this module can reach a raw
 * `OkHttpClient` at all — not by constructing one, and not by reading it off
 * an instance the factory handed out. A public `val` here would have left
 * exactly the gap the constructor alone closes: `create(cfg).okHttpClient
 * .newBuilder().cookieJar(...)` compiles fine and defeats the policy from
 * outside `:api`. That structural point is the whole reason the iOS package
 * reworked its own version of this in issue #99 — the policy had been a flag
 * two transport functions each set, and a third consumer was added over a raw
 * session with cookie handling at its default and nothing caught it.
 * Enforcing it here by construction, rather than by convention, closes that
 * gap regardless of which platform's default turns out to be the safer one.
 *
 * `:app` gets exactly two ways to use this client: as an [okhttp3.Call.Factory]
 * (Coil 3 accepts one directly, so its `ImageLoader` can be handed this
 * wrapper without ever touching an `OkHttpClient`), and via [newBuilder] for
 * anything that needs to derive a customized client — [newBuilder] re-applies
 * [CookieJar.NO_COOKIES] itself, so a derived client can't silently drop the
 * policy the way a bare `newBuilder()` on a raw client could.
 *
 * **Scope: requests issued through this client.** This is not an app-wide
 * guarantee — the same hole the iOS source notes for `AsyncImage` on
 * `URLSession.shared`. Latent today, since cover URLs are third-party CDN
 * hosts; fatal the day art is proxied through `api.wxyc.org`.
 */
class CookielessHttpClient internal constructor(
    internal val okHttpClient: OkHttpClient,
) : Call.Factory by okHttpClient {

    /**
     * Derives a new client builder from this one, re-applying
     * [CookieJar.NO_COOKIES] so the policy survives the derivation. This is
     * the only sanctioned route to an [OkHttpClient.Builder] from outside the
     * module — there is no way to reach the underlying raw client to build
     * from it directly.
     */
    fun newBuilder(): OkHttpClient.Builder =
        okHttpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES)
}

/**
 * The only way to obtain a [CookielessHttpClient]. `:api` exposes this plain
 * factory rather than a Hilt module because Hilt is Android-only; `:app`'s
 * `@Module`/`@Provides` (added with issue #7) calls this factory rather than
 * restating the cookie policy itself — a bare `@Provides` in `:app` alone
 * would still leave an `:api` consumer free to construct a raw client.
 */
object CookielessHttpClientFactory {
    fun create(configuration: Configuration): CookielessHttpClient {
        // iOS's Configuration.timeout is URLRequest(timeoutInterval:), an
        // idle/stall timeout: it resets on every byte received. OkHttp's
        // callTimeout is the opposite shape — a hard cap on the whole call,
        // including body transfer — so setting only callTimeout left the
        // configured value operative in neither sense (connect/read/write sat
        // at OkHttp's 10s defaults instead). Setting read/connect/write here
        // is what actually mirrors the idle-timeout semantics, and matters
        // concretely for phase 2's gzipped-NDJSON catalog export, which a
        // hard whole-call cap would kill on a slow connection while bytes are
        // still arriving.
        val timeout = configuration.timeoutSeconds
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .build()
        return CookielessHttpClient(client)
    }
}
