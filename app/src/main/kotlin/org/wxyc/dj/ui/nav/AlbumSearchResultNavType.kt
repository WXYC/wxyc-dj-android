package org.wxyc.dj.ui.nav

import android.os.Bundle
import androidx.navigation.NavType
import java.net.URLDecoder
import java.net.URLEncoder
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
 * and route comparison), so those two round-trip through
 * [URLEncoder]/[URLDecoder] instead -- the escape hatch Android's own
 * "custom types in Navigation Compose" guide documents for a non-primitive
 * argument.
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
        if (value == "null") null else json.decodeFromString(serializer, URLDecoder.decode(value, "UTF-8"))

    override fun serializeAsValue(value: AlbumSearchResult?): String =
        value?.let { URLEncoder.encode(json.encodeToString(serializer, it), "UTF-8") } ?: "null"
}
