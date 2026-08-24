package org.wxyc.dj.api

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * Classifies a failed album-cover load as indicting the URL (permanently
 * retire it from `AlbumDetailViewModel`'s artwork precedence chain --
 * invariant 17, iOS issues #83/#86) or merely indicting the link (a passing
 * condition; the URL stays eligible for a later attempt). Recording is a
 * one-way door for the caller -- the detail screen
 * accumulates a permanent, per-screen set of retired URLs -- so a
 * connectivity blip must never retire a URL that was never actually broken:
 * doing so would drop a *healthy* catalog cover and hand the header to
 * LML's art (which can resolve to a label-level image instead of the cover)
 * the moment connectivity returned, with no self-recovery. The split
 * therefore fails safe: a connectivity-class failure is not recorded, and
 * everything else is, including a decode failure -- a CDN's 403/404 error
 * page is exactly what an expired pre-signed URL or a purged asset looks
 * like once it reaches an image decoder, and those are the cases this
 * exists to recover from. **Do not simplify this to "any network exception
 * is transient" -- that swallows the decode case and silently disables the
 * whole fallthrough.**
 *
 * Mirrors iOS's `ArtworkFailureClassification` + `ConnectivityErrorClassification`
 * (`Packages/WXYCAPI/Sources/WXYCAPI/Catalog/ArtworkFailureClassification.swift`),
 * with one structural difference: iOS classifies over `URLError`, a single
 * closed type `URLSession` always throws. The JVM/Android image loader
 * (Coil) has no equivalent -- and Coil itself is Android-only, so it cannot
 * be imported into this module (`:api` stays SDK-free; see this repo's
 * `CLAUDE.md`). The seam here is therefore a raw [Throwable], classified over
 * the standard `java.net`/`javax.net.ssl` exception hierarchy that Coil's
 * OkHttp-backed network fetcher throws or lets propagate unwrapped, rather
 * than over a Coil-specific type -- `:app` passes it the [Throwable] it reads
 * off Coil's `AsyncImagePainter.State.Error` / `ErrorResult`.
 *
 * That seam was verified, not assumed, by inspecting the compiled classes of
 * this repo's pinned coil3 3.3.0 (`coil-network-core`, `coil-network-okhttp`,
 * `coil-core`): a non-2xx HTTP response (an expired-signature or purged-asset
 * CDN reply -- the case this classification exists to recover from) throws
 * `coil3.network.HttpException`, a plain `RuntimeException`; a bitmap decode
 * failure throws `java.lang.IllegalStateException`; neither is a
 * `java.net`/`javax.net.ssl` connectivity type, so both correctly fall
 * through to "indicts the URL" below. A genuine transport failure (DNS,
 * connection refused, timeout, TLS handshake) propagates through OkHttp's
 * callback bridge as its ordinary unwrapped `java.io.IOException` subtype.
 */
object ArtworkFailureClassification {
    /**
     * `true` when [error] indicts the URL itself and the caller should stop
     * offering it; `false` for a connectivity-class failure, which indicts
     * only the link and leaves the URL eligible for a later attempt.
     */
    fun indictsUrl(error: Throwable): Boolean = !isConnectivityFailure(error)

    /**
     * Connectivity-class failures: the *link* failed, so the error says
     * nothing about the resource or request itself. Each entry is the
     * JVM/Android analogue of one of iOS's `ConnectivityErrorClassification`
     * codes:
     *
     * - [UnknownHostException] -- DNS lookup failed / no route to a host by
     *   name (`.dnsLookupFailed` / `.cannotFindHost`).
     * - [SocketException] and its subtypes ([java.net.ConnectException]
     *   connection refused, [java.net.NoRouteToHostException] no route) --
     *   the link itself (`.cannotConnectToHost` / `.networkConnectionLost`).
     * - [SocketTimeoutException] -- `.timedOut`. Does **not** extend
     *   [SocketException] (it extends `InterruptedIOException`), so it needs
     *   its own branch.
     * - [SSLException] and its subtypes (handshake failure, untrusted
     *   certificate, protocol mismatch) -- `.secureConnectionFailed`, the
     *   captive-portal-adjacent case.
     * - [CancellationException] -- `.cancelled`: a deliberate cancellation
     *   (the DJ leaves the screen before a load finishes) indicts neither
     *   the URL nor genuinely the link, but is grouped here rather than as a
     *   third case for the same reason iOS's list includes `.cancelled`
     *   verbatim -- it must never retire a URL that was simply never given
     *   the chance to finish.
     *
     * Deliberately excluded, matching iOS's explicit exclusions: a bare,
     * unrecognized [java.io.IOException] is **not** classified as
     * connectivity here -- iOS excludes `.cannotDecodeContentData` /
     * `.badServerResponse` for the identical reason (what a CDN's error page
     * looks like by the time it reaches a decoder), and defaulting every
     * unrecognized transport exception to "transient" would reintroduce
     * exactly the "any network exception is transient" trap this type's
     * KDoc warns against. Only the specific, well-understood link-level
     * types above are treated as connectivity; everything else -- including
     * `coil3.network.HttpException`, a bitmap decode failure, and any other
     * `IOException` this list doesn't name -- retires the URL.
     */
    internal fun isConnectivityFailure(error: Throwable): Boolean =
        causeChain(error).any { link ->
            when (link) {
                is UnknownHostException -> true
                is SocketException -> true
                is SocketTimeoutException -> true
                is SSLException -> true
                is CancellationException -> true
                else -> false
            }
        }

    /**
     * [error] and its `cause` chain, so a connectivity failure still counts
     * as one when some layer has wrapped it.
     *
     * This is the safe direction of the asymmetry the class KDoc describes,
     * and it is not defensive padding. OkHttp really does report a connection
     * reset as a bare `IOException("unexpected end of stream on ...")`
     * carrying a [SocketException] cause; matched against the top frame
     * alone, that lands in the `else` branch and **retires a healthy cover
     * permanently**, which is the exact defect this classification exists to
     * prevent. The converse mistake is not symmetric: none of the
     * URL-indicting cases (`coil3.network.HttpException` for a non-2xx, an
     * `IllegalStateException` from a bitmap decode) carries a connectivity
     * cause, so walking the chain cannot turn a genuine dead URL into a
     * "keep trying".
     *
     * Bounded rather than a plain `generateSequence`: a `Throwable` whose
     * cause chain is self-referential is legal and would otherwise spin
     * forever. The depth is far past anything a real wrapper stack reaches.
     */
    private fun causeChain(error: Throwable): Sequence<Throwable> = sequence {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            yield(current)
            val next = current.cause
            if (next === current) return@sequence
            current = next
            depth++
        }
    }

    private const val MAX_CAUSE_DEPTH = 16
}
