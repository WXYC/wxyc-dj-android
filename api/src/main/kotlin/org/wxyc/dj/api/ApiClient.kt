package org.wxyc.dj.api

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Failure modes [ApiClient] can surface. [Unauthorized] is a `401` that
 * survived [ApiClient]'s single invalidate-and-retry (see that class's
 * KDoc) — the session itself is dead, not merely a stale cached JWT.
 * [NotSignedIn] never touches the network: [ApiClient] resolves the bearer
 * before building a request, so a signed-out client fails here instead of
 * firing an unauthenticated call. [Http] is every other non-2xx, carrying
 * the server's decoded `message` when the body decodes as one (Backend
 * errors are `{ message, code?, details? }` — see `WxycError`). [Decoding]
 * and [Network] are what let a caller tell a malformed/unexpected response
 * body apart from a transport failure — issue #10's best-effort LML
 * metadata handling depends on that distinction to degrade a decode failure
 * quietly rather than surfacing it as a hard error.
 *
 * There is deliberately no offline/genuine-defect split the way iOS's
 * `APIError.offline` vs `.network` has: this module has no connectivity
 * monitor to feed (that is phase 2 — see the port plan), so [Network]
 * covers every transport failure undifferentiated. A **cancelled** request
 * is not one of these cases at all — [ApiClient]'s `fire` lets
 * [CancellationException] propagate unwrapped rather than catching and
 * classifying it, so cancelling a search never surfaces as [Network]. See
 * `fire`'s doc comment for why that carve-out has to survive even with
 * nothing downstream to protect yet. Mirrors `APIError` in
 * `APIClient.swift`, trimmed to what this module ports.
 */
sealed class ApiError(message: String) : Exception(message) {
    object Unauthorized : ApiError("Your session expired. Please sign in again.")

    object NotSignedIn : ApiError("Please sign in.")

    data class Http(val status: Int, val serverMessage: String?) :
        ApiError("Server error ($status)" + (serverMessage?.let { ": $it" } ?: "") + ".")

    /** The response body did not decode into the shape the caller expected. */
    data class Decoding(val detail: String) : ApiError("The server returned an unexpected response: $detail")

    /** A transport-level failure: no route to the server, DNS, timeout, connection refused. */
    data class Network(val detail: String) : ApiError("Network error: $detail")
}

/** Backend-Service's `WxycError` wire body: `{ message, code?, details? }`, minus the fields this module never reads. */
@Serializable
private data class ApiErrorResponseDto(val message: String? = null)

@Serializable
private data class AddToBinRequestDto(
    @SerialName("album_id") val albumId: Int,
    // No default-value hazard here even though this module's Json doesn't
    // set encodeDefaults: albumId above has NO default (always encoded
    // regardless), and omitting trackTitle when null is the correct wire
    // shape (djs.controller.addToBin treats an absent key exactly like an
    // explicit null: `track_title: req.body.track_title === undefined ?
    // null : req.body.track_title`), matching iOS's Codable synthesis for
    // an Optional property. There is deliberately no `djId` field at all —
    // see [ApiClient.addToBin].
    @SerialName("track_title") val trackTitle: String? = null,
)

private val jsonRequestBodyMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Suspend bridge from [Call.enqueue] to structured concurrency. Currently
 * duplicated from [AuthWireClient]'s private copy of the same function;
 * consolidating the two is tracked separately and deliberately left out of
 * this change. No added dependency: this is the standard
 * callback-to-coroutine pattern, not a substitute for
 * `okhttp3:okhttp-coroutines`, which this module does not depend on.
 *
 * This is also the mechanism that keeps [ApiClient] off a dispatcher hop:
 * [Call.enqueue] runs the request on OkHttp's own dispatcher pool, and
 * [kotlinx.coroutines.CancellableContinuation.resume] redispatches through
 * the calling coroutine's own context — so a callback firing on OkHttp's
 * thread never hands control back on the wrong one, and nothing here ever
 * needs `withContext(Dispatchers.IO)` around the call into [AuthService].
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
}

/**
 * The typed HTTP surface every screen calls: library search, release info,
 * LML metadata, and the three bin operations. **No raw-send escape hatch is
 * exposed** — every endpoint below is a typed method, which is what keeps
 * status handling and decoding from being re-invented per call site (the
 * convention `APIClient.swift` establishes on iOS).
 *
 * Every method attaches `Authorization: Bearer <jwt>` from
 * [AuthService.currentJwt], resolved in [resolveBearer] **before** a
 * request is built — a signed-out client throws [ApiError.NotSignedIn]
 * without ever touching the network — and on a `401` [perform] invalidates
 * the cached JWT and retries the same request exactly once (`isRetry`),
 * never in a loop.
 *
 * **401 handling lives here, in [perform], not in an OkHttp
 * [okhttp3.Interceptor] or [okhttp3.Authenticator].** Two reasons: the
 * acceptable status range is a decision each typed method's caller makes —
 * every method here requires 2xx via [send], and a future conditional-GET
 * leg (phase 2's catalog export, out of scope here) would additionally
 * accept `304` exactly as iOS's `catalog(ifModifiedSince:)` does — an
 * interceptor that owns status handling would have to be unwound to add
 * that. And an `Authenticator` would need [AuthService] to mint a fresh
 * bearer, which itself needs an [okhttp3.Call.Factory] to make its own
 * requests — wiring one into this client's `Authenticator` is a dependency
 * cycle the cookieless factory (#2) stays credential-unaware specifically
 * to avoid. [fire] is the shared transport core: it fires a built request
 * and hands back the raw [okhttp3.Response] with no status policy of its
 * own; [send] (used by every typed method) is what imposes 2xx.
 *
 * Takes the client as a [CookielessHttpClient], not a bare
 * [okhttp3.Call.Factory] — unlike [AuthService], which relaxes that to
 * `Call.Factory` for testability. There is no equivalent relaxation here:
 * every test in `ApiClientTest` drives this class through a real
 * [CookielessHttpClient] over `MockWebServer`, which is what lets this
 * type stay the one that can never carry a cookie jar (see
 * [CookielessHttpClient]'s KDoc).
 *
 * Mirrors `APIClient.swift`, with the phase-2 catalog leg
 * (`catalog(ifModifiedSince:)`) and its connectivity-monitor outcome hook
 * both out of scope for v1 — see [ApiError]'s KDoc for how that trims this
 * module's error set relative to iOS's.
 */
class ApiClient(
    private val configuration: Configuration,
    private val callFactory: CookielessHttpClient,
    private val authService: AuthService,
) {
    private val json = WxycJson.json

    /** `GET /library/` — the classic catalog search dj-site and the DJ apps share. */
    suspend fun searchLibrary(artist: String? = null, title: String? = null, limit: Int = 25): List<AlbumSearchResult> {
        val query = buildList {
            if (!artist.isNullOrEmpty()) add("artist_name" to artist)
            if (!title.isNullOrEmpty()) add("album_title" to title)
            add("n" to limit.toString())
        }
        val text = send(path = "library/", method = "GET", query = query)
        return decode(ListSerializer(AlbumSearchResult.serializer()), text)
    }

    /** `GET /library/info` — the shelf source of truth for a single release. */
    suspend fun albumInfo(albumId: Int): AlbumInfo {
        val text = send(path = "library/info", method = "GET", query = listOf("album_id" to albumId.toString()))
        return decode(AlbumInfo.serializer(), text)
    }

    /**
     * `GET /proxy/metadata/album` — LML-enriched release record: year,
     * label, genres/styles, streaming URLs, tracklist, Discogs/Wikipedia
     * URLs. Best-effort by convention at the call site (issue #10); this
     * method itself always either returns a decoded [AlbumMetadata] or
     * throws — the caller decides how to degrade.
     */
    suspend fun albumMetadata(artistName: String, releaseTitle: String? = null, trackTitle: String? = null): AlbumMetadata {
        val query = buildList {
            add("artistName" to artistName)
            if (!releaseTitle.isNullOrEmpty()) add("releaseTitle" to releaseTitle)
            if (!trackTitle.isNullOrEmpty()) add("trackTitle" to trackTitle)
        }
        val text = send(path = "proxy/metadata/album", method = "GET", query = query)
        return decode(AlbumMetadata.serializer(), text)
    }

    /**
     * `GET /djs/bin` — the DJ's bin, newest server truth. The response is a
     * **bare array** (api.yaml's `{dj_id, entries: [...]}` envelope is
     * fiction no handler emits — see the iOS `CLAUDE.md`'s "Bin wire shape"
     * bullet), so decoding goes through [BinResponse.decode] rather than
     * the generic [decode] path: `[]` is a real, authoritative empty bin,
     * but a JSON `null` body is not a bin at all and must throw rather than
     * coerce to empty (issue #60's written-empty vs. never-written
     * distinction; dj-site coerces null, this must not).
     */
    suspend fun getBin(): List<BinEntry> {
        val text = send(path = "djs/bin", method = "GET")
        return try {
            BinResponse.decode(text)
        } catch (e: SerializationException) {
            throw ApiError.Decoding(e.message ?: "GET /djs/bin returned an unexpected body")
        }
    }

    /**
     * `POST /djs/bin` — add a release to the signed-in DJ's bin.
     * Deliberately sends **no `dj_id`**, even though api.yaml's inline
     * request schema marks it required: `djs.controller.addToBin` derives
     * it from `req.auth!.id!` server-side and never reads `req.body.dj_id`
     * at all, so sending one would be dead weight the server ignores. The
     * `201` body is the raw inserted `bins` row (`id`/`dj_id`/`album_id`/
     * `track_title`) — not a bin entry, and not something any caller
     * needs — so it is deliberately left undecoded; the 2xx is the
     * acknowledgement.
     */
    suspend fun addToBin(albumId: Int, trackTitle: String? = null) {
        val bodyJson = json.encodeToString(AddToBinRequestDto.serializer(), AddToBinRequestDto(albumId, trackTitle))
        send(path = "djs/bin", method = "POST", body = bodyJson.toRequestBody(jsonRequestBodyMediaType))
    }

    /**
     * `DELETE /djs/bin` — removes **every** row for the `(dj, album)` pair;
     * there is no `bins.id` on the wire to target a single row (see
     * [BinEntry]'s KDoc). `trackTitle` is sent when given, matching iOS,
     * even though `djs.controller.deleteFromBin` only ever reads
     * `req.query.album_id` today and ignores it server-side — kept for
     * wire symmetry with [addToBin] rather than because the server acts on
     * it.
     */
    suspend fun removeFromBin(albumId: Int, trackTitle: String? = null) {
        val query = buildList {
            add("album_id" to albumId.toString())
            if (!trackTitle.isNullOrEmpty()) add("track_title" to trackTitle)
        }
        send(path = "djs/bin", method = "DELETE", query = query)
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T {
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw ApiError.Decoding(e.message ?: "decoding failed")
        } catch (e: IllegalArgumentException) {
            throw ApiError.Decoding(e.message ?: "decoding failed")
        }
    }

    /**
     * 2xx-only transport: returns the decoded response body text, or
     * throws [ApiError.Unauthorized]/[ApiError.Http]. Thin status-policy
     * layer over [perform] — the one place every typed method funnels
     * through, which is what keeps that policy from being re-decided per
     * call site.
     */
    private suspend fun send(
        path: String,
        method: String,
        query: List<Pair<String, String>> = emptyList(),
        body: RequestBody? = null,
    ): String {
        val response = perform(path, method, query, body)
        return response.use {
            val text = it.body?.string().orEmpty()
            if (it.code !in 200..299) throw httpError(it.code, text)
            text
        }
    }

    /**
     * Transport core shared by every typed method: resolves the bearer,
     * builds and fires the request, and applies the one-shot `401` →
     * [AuthService.invalidateJwt] → retry (invariant 19). Returns the raw
     * [Response] **without** imposing a status-code policy — [send] is what
     * requires 2xx. `isRetry` is set only on the recursive call this method
     * makes after invalidating; it is never itself retried, so a second
     * consecutive `401` is returned to [send] as-is rather than looping.
     */
    private suspend fun perform(
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        isRetry: Boolean = false,
    ): Response {
        val token = resolveBearer()
        val request = buildRequest(path, method, query, body, token)
        val response = fire(request)
        if (response.code == 401 && !isRetry) {
            response.close()
            authService.invalidateJwt()
            return perform(path, method, query, body, isRetry = true)
        }
        return response
    }

    private fun buildRequest(
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        token: String,
    ): Request {
        val urlBuilder = configuration.apiBaseUrl.newBuilder().addPathSegments(path)
        for ((name, value) in query) {
            urlBuilder.addQueryParameter(name, value)
        }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body)
            .build()
    }

    /**
     * Fires a built request over [callFactory] and classifies the outcome.
     * A thrown [IOException] (no route, DNS, timeout, connection refused)
     * becomes [ApiError.Network].
     *
     * A [CancellationException] is deliberately **not** caught here — it
     * propagates unchanged rather than being classified as
     * [ApiError.Network]. iOS's `fire(_:)` carries the identical carve-out
     * for a concrete reason: the issue-#58 search debounce cancels the
     * in-flight request on every keystroke, and a connectivity monitor
     * that latched offline on a cancellation would wrongly route every
     * later search to a local clone while the device is genuinely online,
     * with no self-recovery (a local search issues no request, so nothing
     * would ever restore the flag). This module has no connectivity
     * monitor yet (that is phase 2), so nothing downstream reads this
     * distinction today — but the carve-out has to be *preserved* now,
     * because it is far harder to retrofit once a monitor exists and every
     * call site already treats cancellation as an ordinary failure.
     */
    private suspend fun fire(request: Request): Response {
        return try {
            callFactory.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiError.Network(e.message ?: e.toString())
        }
    }

    /**
     * Resolves the bearer for [perform], mapping [AuthService.currentJwt]'s
     * two failure shapes onto [ApiError]. [AuthError.NotSignedIn] — thrown
     * directly when no session token exists, or after a 401 demotes an
     * expired one — becomes [ApiError.NotSignedIn]. Anything else
     * (including a raw, unwrapped transport exception: `currentJwt`'s lazy
     * refresh leg can itself fail offline one layer below this client's own
     * [fire], and `AuthService` does not wrap that leg in an [AuthError] —
     * see its KDoc) becomes [ApiError.Network], mirroring iOS's
     * `APIClient.currentJWT()`, which classifies its catch-all the same
     * way. [CancellationException] propagates unchanged, same rationale as
     * [fire].
     */
    private suspend fun resolveBearer(): String {
        return try {
            authService.currentJwt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError.NotSignedIn) {
            throw ApiError.NotSignedIn
        } catch (e: Exception) {
            throw ApiError.Network(e.message ?: e.toString())
        }
    }

    private fun httpError(status: Int, body: String): ApiError {
        if (status == 401) return ApiError.Unauthorized
        val message = runCatching { json.decodeFromString(ApiErrorResponseDto.serializer(), body) }.getOrNull()?.message
        return ApiError.Http(status, message)
    }
}
