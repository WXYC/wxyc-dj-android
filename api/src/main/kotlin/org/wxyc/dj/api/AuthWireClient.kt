package org.wxyc.dj.api

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
private data class JwtResponseDto(val token: String)

/** better-auth's `{message, code}` error body. */
@Serializable
private data class BetterAuthErrorDto(val message: String? = null, val code: String? = null)

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Suspend bridge from [Call.enqueue] to structured concurrency. No added
 * dependency: this is the standard callback-to-coroutine pattern, not a
 * substitute for `okhttp3:okhttp-coroutines`, which this module does not
 * depend on. Resuming from [Callback.onResponse]/[Callback.onFailure] can
 * happen on OkHttp's dispatcher thread; [kotlinx.coroutines.CancellableContinuation.resume]
 * redispatches through the calling coroutine's own context, so this never
 * hands control back on the wrong thread.
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
 * The counterpart to [AuthService]'s state machine: that owns the
 * transitions, this owns the wire — the `set-auth-token` header capture and
 * the status table every sign-in route shares (`bearer()` is a global
 * better-auth plugin, so every route answers with the same shape and the
 * same refusal vocabulary). Kept as one class rather than duplicated per
 * credential route so a route added later (issue #4's OTP leg) cannot
 * restate the table and drift from it. Mirrors the wire half of
 * `AuthService.swift`'s `postJSON`/`establishSession`/`refreshJWT`/
 * `serverError` — the status-classification and header-capture parts of it,
 * with JWT decoding and the session-epoch/token-identity guards (issue #3
 * invariants 3 and 4) left to the orchestrator, since those are about
 * [AuthService]'s own state rather than the wire.
 */
internal class AuthWireClient(
    private val configuration: Configuration,
    private val callFactory: Call.Factory,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * POST a credential body to a better-auth sign-in route and return the
     * session token it issues.
     *
     * @param rejectionMessage How to word a 400/403 refusal, given the
     *   server's `code` and `message`. Defaults to the server's own message
     *   verbatim, which is what the password route wants.
     */
    suspend fun establishSession(
        path: String,
        jsonBody: String,
        rejectionMessage: (code: String?, message: String?) -> String? = { _, message -> message },
    ): String {
        val response = postJson(path, jsonBody)
        return when (response.statusCode) {
            in 200..299 -> {
                val headerToken = response.headers["set-auth-token"]?.takeIf { it.isNotEmpty() }
                val bodyToken = headerToken ?: decodeJwtResponse(response.body)?.token?.takeIf { it.isNotEmpty() }
                bodyToken ?: throw AuthError.MissingSessionToken
            }
            401 -> throw AuthError.InvalidCredentials
            429 -> throw AuthError.RateLimited
            400, 403 -> {
                val error = decodeError(response.body)
                throw AuthError.Rejected(rejectionMessage(error?.code, error?.message))
            }
            else -> throw AuthError.ServerFailure(response.statusCode, decodeError(response.body)?.message)
        }
    }

    /** Everything the orchestrator needs off a `GET /auth/token` response. */
    data class TokenExchangeResult(
        val statusCode: Int,
        val rotatedSessionToken: String?,
        val jwtToken: String?,
    )

    /**
     * better-auth's bearer plugin emits `set-auth-token` on every response
     * where it re-issued the session cookie (sign-in, and rolling renewal
     * once per `session.updateAge`). [rotatedSessionToken] is captured on
     * every response, success or not, so a caller in lockstep with the
     * server's rotation schedule never has to ask twice.
     */
    suspend fun fetchToken(sessionToken: String): TokenExchangeResult {
        val url = configuration.authBaseUrl.newBuilder().addPathSegments("token").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val response = execute(request)
        val rotated = response.headers["set-auth-token"]?.takeIf { it.isNotEmpty() && it != sessionToken }
        if (response.statusCode !in 200..299) {
            return TokenExchangeResult(response.statusCode, rotated, jwtToken = null)
        }
        val token = decodeJwtResponse(response.body)?.token
        return TokenExchangeResult(response.statusCode, rotated, token)
    }

    /** Best-effort: the caller decides how to treat a failure. */
    suspend fun signOut(sessionToken: String) {
        val url = configuration.authBaseUrl.newBuilder().addPathSegments("sign-out").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $sessionToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        execute(request)
    }

    private suspend fun postJson(path: String, jsonBody: String): WireResponse {
        val url = configuration.authBaseUrl.newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): WireResponse {
        val response = callFactory.newCall(request).await()
        return response.use {
            WireResponse(statusCode = it.code, body = it.body?.string().orEmpty(), headers = it.headers)
        }
    }

    private fun decodeJwtResponse(body: String): JwtResponseDto? =
        runCatching { json.decodeFromString(JwtResponseDto.serializer(), body) }.getOrNull()

    private fun decodeError(body: String): BetterAuthErrorDto? =
        runCatching { json.decodeFromString(BetterAuthErrorDto.serializer(), body) }.getOrNull()

    private data class WireResponse(val statusCode: Int, val body: String, val headers: Headers)
}
