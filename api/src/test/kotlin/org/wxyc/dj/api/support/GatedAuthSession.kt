package org.wxyc.dj.api.support

import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout

/**
 * A [Call.Factory] that serves most requests instantly from a FIFO of stubs
 * but can GATE the next `…/token` request — parking that one call until
 * released, so a test can interleave a concurrent re-sign-in or sign-out
 * while a stale JWT refresh is suspended mid-flight. Built for the issue-#3
 * invariant-3/4 concurrency guards: [org.wxyc.dj.api.AuthService.currentJwt]
 * must not clobber a session that was replaced while its refresh was
 * awaiting, and a success-path refresh must not resurrect a session torn
 * down by a concurrent sign-out.
 *
 * The gated call's callback fires from a background [Thread], not from the
 * calling coroutine — [Call.enqueue] is a plain synchronous method (OkHttp
 * itself dispatches asynchronously from a worker pool), so parking it for a
 * coroutine-driven release requires a real thread hand-off, exactly like
 * OkHttp's own dispatcher would do. Whichever thread eventually calls
 * [Callback.onResponse] is safe: the production [Call.await] suspend bridge
 * resumes through the calling coroutine's own dispatcher, not the calling
 * thread. Mirrors WXYCAPITests' `GatedAuthSession.swift`.
 */
class GatedAuthSession : Call.Factory {
    data class Stub(
        val statusCode: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    private val lock = Any()
    private val instant = ArrayDeque<Stub>()
    private var armed = false
    private var gatedStub = Stub(401)
    private var arrived = false
    private val arrivalWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var released = false
    private var pendingLatch: CountDownLatch? = null

    private val _recordedRequests = mutableListOf<Request>()
    val recordedRequests: List<Request> get() = synchronized(lock) { _recordedRequests.toList() }

    /** Queue a response served instantly, FIFO, to any request that isn't the currently-gated token fetch. */
    fun enqueueInstant(stub: Stub) {
        synchronized(lock) { instant.addLast(stub) }
    }

    /**
     * Arm the gate: the next `…/token` request parks until [releaseGate],
     * then returns [returning]. One-shot — once that request arrives the
     * gate disarms, so later token fetches are instant.
     */
    fun armGate(returning: Stub) {
        synchronized(lock) {
            armed = true
            gatedStub = returning
            released = false
            arrived = false
        }
    }

    /** Suspends until the gated token fetch has arrived and parked. */
    suspend fun waitForGatedArrival() {
        val waiter = CompletableDeferred<Unit>()
        val alreadyArrived = synchronized(lock) {
            if (arrived) true else { arrivalWaiters.add(waiter); false }
        }
        if (alreadyArrived) return
        waiter.await()
    }

    /**
     * Release the parked gated fetch so it returns its armed response. Safe
     * to call before the fetch parks — the `released` flag lets the later
     * park resume immediately.
     */
    fun releaseGate() {
        val latch = synchronized(lock) {
            released = true
            val l = pendingLatch
            pendingLatch = null
            l
        }
        latch?.countDown()
    }

    override fun newCall(request: Request): Call = FakeCall(request)

    private sealed class Action {
        data class Instant(val stub: Stub) : Action()
        data class Gated(val stub: Stub) : Action()
        data object NoStub : Action()
    }

    private inner class FakeCall(private val request: Request) : Call {
        private var canceled = false

        override fun request(): Request = request

        override fun execute(): Response = throw UnsupportedOperationException("GatedAuthSession is enqueue-only")

        override fun enqueue(responseCallback: Callback) {
            val isToken = request.url.encodedPath.endsWith("/token")

            val (action, waiters) = synchronized(lock) {
                _recordedRequests += request
                when {
                    isToken && armed -> {
                        armed = false
                        arrived = true
                        val waiters = arrivalWaiters.toList()
                        arrivalWaiters.clear()
                        Action.Gated(gatedStub) to waiters
                    }
                    instant.isNotEmpty() -> Action.Instant(instant.removeFirst()) to emptyList()
                    else -> Action.NoStub to emptyList()
                }
            }
            waiters.forEach { it.complete(Unit) }

            when (val a = action) {
                is Action.NoStub -> responseCallback.onFailure(this, IOException("GatedAuthSession: no more stubs"))
                is Action.Instant -> responseCallback.onResponse(this, buildResponse(a.stub))
                is Action.Gated -> {
                    val latch = synchronized(lock) {
                        if (released) null else CountDownLatch(1).also { pendingLatch = it }
                    }
                    if (latch == null) {
                        responseCallback.onResponse(this, buildResponse(a.stub))
                    } else {
                        Thread {
                            latch.await()
                            responseCallback.onResponse(this, buildResponse(a.stub))
                        }.start()
                    }
                }
            }
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request)

        private fun buildResponse(stub: Stub): Response {
            val headers = headersOf(*stub.headers.flatMap { (k, v) -> listOf(k, v) }.toTypedArray())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(stub.statusCode)
                .message("stub")
                .headers(headers)
                .body(stub.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
