package org.wxyc.dj.api

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Pins [ArtworkFailureClassification.indictsUrl] (invariant 17, iOS issue
 * #86): a connectivity-class failure leaves the URL in the running, while a
 * resource-level failure -- including a decode failure -- retires it.
 * Mirrors `ArtworkFailureClassificationTests.swift`, adapted to the raw
 * [Throwable] seam this module classifies over (see the type's KDoc for
 * why -- Coil, the image loader, is Android-only and cannot be imported
 * here).
 */
class ArtworkFailureClassificationTest {

    // Retiring a URL is a one-way door for the caller (AlbumDetailViewModel's
    // set is permanent for the screen's life), so a connectivity blip must
    // never retire a healthy cover: doing so would hand the header to LML's
    // art the moment the network came back, with no self-recovery.

    @ParameterizedTest
    @MethodSource("connectivityFailures")
    fun `a connectivity-class failure does not retire the URL`(error: Throwable) {
        assertFalse(ArtworkFailureClassification.indictsUrl(error))
    }

    @ParameterizedTest
    @MethodSource("resourceLevelFailures")
    fun `a resource-level failure retires the URL`(error: Throwable) {
        assertTrue(ArtworkFailureClassification.indictsUrl(error))
    }

    @Test
    fun `a bare unrecognized IOException retires the URL rather than being treated as transient`() {
        // The trap this type's own KDoc warns against: blanket-treating every
        // IOException as connectivity would swallow this case and silently
        // disable the whole fallthrough.
        assertTrue(ArtworkFailureClassification.indictsUrl(IOException("unexpected")))
    }

    @Test
    fun `coil3's HttpException for a non-2xx response retires the URL`() {
        // Verified via javap against the pinned coil3 3.3.0 jar: HttpException
        // is a plain RuntimeException, so it is not classified as connectivity
        // here regardless -- this pins that a plain RuntimeException (what an
        // expired-signature or purged-asset CDN 403/404 surfaces as) retires.
        assertTrue(ArtworkFailureClassification.indictsUrl(RuntimeException("HTTP 403")))
    }

    @Test
    fun `a bitmap decode failure retires the URL`() {
        // What BitmapFactoryDecoder throws (verified via javap) when the
        // downloaded bytes aren't a valid image -- the expired-signature /
        // purged-asset case this classification exists to recover from.
        assertTrue(ArtworkFailureClassification.indictsUrl(IllegalStateException("BitmapFactory returned a null bitmap.")))
    }

    @Test
    fun `a connectivity failure still counts as one when a layer has wrapped it`() {
        // OkHttp's real shape for a connection reset: a bare IOException whose
        // message says nothing useful, carrying the actual SocketException as
        // its cause. Matched against the top frame alone this falls into the
        // "unrecognized" bucket and permanently retires a healthy cover --
        // the exact defect this classification exists to prevent, arrived at
        // from the one direction the type's own doc calls fail-safe.
        val wrapped = IOException("unexpected end of stream on Connection{...}", SocketException("Connection reset"))
        assertFalse(ArtworkFailureClassification.indictsUrl(wrapped))
    }

    @Test
    fun `a wrapped resource-level failure still retires the URL`() {
        // The converse: walking the cause chain must not turn a genuinely dead
        // URL into a "keep trying". Nothing in the URL-indicting set carries a
        // connectivity cause, so the walk is safe in only one direction by
        // construction -- pinned rather than argued.
        val wrapped = IOException("decode failed", IllegalStateException("Failed to create bitmap"))
        assertTrue(ArtworkFailureClassification.indictsUrl(wrapped))
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        // A Throwable can legally be its own cause; an unbounded walk would
        // spin here rather than answer.
        val looping = object : IOException("looping") {
            override val cause: Throwable get() = this
        }
        assertTrue(ArtworkFailureClassification.indictsUrl(looping))
    }

    companion object {
        @JvmStatic
        fun connectivityFailures(): List<Throwable> = listOf(
            UnknownHostException("Unable to resolve host"),
            ConnectException("Connection refused"),
            NoRouteToHostException("No route to host"),
            SocketException("Connection reset"),
            SocketTimeoutException("timeout"),
            SSLHandshakeException("handshake failed"),
            CancellationException("cancelled"),
        )

        @JvmStatic
        fun resourceLevelFailures(): List<Throwable> = listOf(
            IllegalStateException("BitmapFactory returned a null bitmap."),
            RuntimeException("HTTP 404"),
        )
    }
}
