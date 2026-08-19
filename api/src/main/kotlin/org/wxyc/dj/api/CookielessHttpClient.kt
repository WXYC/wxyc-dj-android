package org.wxyc.dj.api

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
 * The constructor is `internal` and there is no exposed builder, so no
 * consumer of this module can obtain an instance whose `CookieJar` was ever
 * set to anything else. That structural point is the whole reason the iOS
 * package reworked its own version of this in issue #99 — the policy had
 * been a flag two transport functions each set, and a third consumer was
 * added over a raw session with cookie handling at its default and nothing
 * caught it. Enforcing it here by construction, rather than by convention,
 * closes that gap regardless of which platform's default turns out to be
 * the safer one.
 *
 * **Scope: requests issued through [okHttpClient].** This is not an app-wide
 * guarantee. Coil builds its own `OkHttpClient` unless handed one, so once
 * cover art is fetched, `:app`'s `ImageLoader` must be constructed over
 * [okHttpClient] (or given `CookieJar.NO_COOKIES` directly) — the same hole
 * the iOS source notes for `AsyncImage` on `URLSession.shared`. Latent today,
 * since cover URLs are third-party CDN hosts; fatal the day art is proxied
 * through `api.wxyc.org`.
 */
class CookielessHttpClient internal constructor(val okHttpClient: OkHttpClient)

/**
 * The only way to obtain a [CookielessHttpClient]. `:api` exposes this plain
 * factory rather than a Hilt module because Hilt is Android-only; `:app`'s
 * `@Module`/`@Provides` (added with issue #7) calls this factory rather than
 * restating the cookie policy itself — a bare `@Provides` in `:app` alone
 * would still leave an `:api` consumer free to construct a raw client.
 */
object CookielessHttpClientFactory {
    fun create(configuration: Configuration): CookielessHttpClient {
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(configuration.timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return CookielessHttpClient(client)
    }
}
