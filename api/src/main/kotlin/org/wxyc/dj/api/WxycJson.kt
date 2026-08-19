package org.wxyc.dj.api

import kotlinx.serialization.json.Json

/**
 * The one kotlinx.serialization [Json] instance every `:api` DTO decodes
 * through. `explicitNulls = false` mirrors `Codable`'s `decodeIfPresent`
 * default on iOS — a missing key and an explicit `null` both mean "absent" for
 * a field with a default. `ignoreUnknownKeys = true` is Backend-Service's own
 * forward-compatibility contract: a field this module doesn't model must never
 * fail the whole decode. `useAlternativeNames = true` (the kotlinx default,
 * named here so it isn't silently flipped) is what lets [AlbumInfo]'s
 * `record_label`/`label` dual-key fallback work via `@JsonNames` rather than a
 * hand-rolled decoder. Mirrors `JSONCoders.swift`.
 *
 * No global naming strategy: the wire mixes snake_case top-level fields with
 * camelCase in Drizzle-sourced nested types ([AlbumMetadata]), so every DTO
 * carries an explicit `@SerialName` per field instead of a blanket converter.
 */
object WxycJson {
    val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        useAlternativeNames = true
    }
}
