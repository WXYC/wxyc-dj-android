package org.wxyc.dj.ui.nav

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.WxycJson

/**
 * Carries an optional [AlbumSearchResult] through [AlbumRoute]'s type-safe
 * Navigation Compose route. Navigation's reflection-based argument
 * resolution only auto-derives a [NavType] for primitives, enums, and
 * [android.os.Parcelable]/[java.io.Serializable] types; [AlbumSearchResult]
 * is none of those, so `composable<AlbumRoute>` needs this supplied via its
 * `typeMap` (see `MainScaffold.kt`) or the graph fails to build.
 *
 * [put]/[get] store the value as a single JSON [Bundle] string via `:api`'s
 * shared [WxycJson] codec -- a `Bundle` extra needs no URL-safe encoding.
 * [parseValue]/[serializeAsValue] additionally exist because a type-safe
 * route's *encoded route string* embeds this value inline (for deep linking
 * and route comparison).
 *
 * **[serializeAsValue] encodes exactly once, and [parseValue] does not decode
 * at all.** Navigation Compose interposes exactly one [Uri.decode] of its own
 * between the two -- `NavDeepLink.parseInputParams`/argument matching calls
 * [android.net.Uri.decode] on each path segment before handing it to
 * [parseValue] -- so [serializeAsValue] uses [Uri.encode] (percent-encoding,
 * `%20` for a space) to pair with that single decode. An earlier version used
 * [java.net.URLEncoder]/[java.net.URLDecoder] here (form encoding, `+` for a
 * space), which is wrong on both ends: [parseValue] decoding *again* is a
 * second, unpaired decode on top of Navigation's own, and `URLEncoder`'s `+`
 * is not a [Uri.decode] escape at all, so a value containing a literal `+`
 * round-tripped as a space and a value containing a literal `%` frequently
 * made [java.net.URLDecoder] throw -- caught by `NavDeepLink.parseInputParams`
 * and discarded, silently falling back to the argument's default. See
 * `AlbumSearchResultNavTypeTest` for the reproduction matrix, driven through a
 * real [androidx.navigation.NavHostController.navigate] rather than these two
 * methods called back to back (which cancels the bug out).
 */
object AlbumSearchResultNavType : NavType<AlbumSearchResult?>(isNullableAllowed = true) {
    private val json get() = WxycJson.json
    private val serializer get() = AlbumSearchResult.serializer()

    override fun put(bundle: Bundle, key: String, value: AlbumSearchResult?) {
        bundle.putString(key, value?.let { json.encodeToString(serializer, it) })
    }

    override fun get(bundle: Bundle, key: String): AlbumSearchResult? =
        bundle.getString(key)?.let { json.decodeFromString(serializer, it) }

    override fun parseValue(value: String): AlbumSearchResult? =
        if (value == "null") null else json.decodeFromString(serializer, value)

    override fun serializeAsValue(value: AlbumSearchResult?): String =
        value?.let { Uri.encode(json.encodeToString(serializer, it)) } ?: "null"
}
