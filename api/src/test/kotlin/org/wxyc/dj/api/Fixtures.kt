package org.wxyc.dj.api

/**
 * WXYC-representative test data — Juana Molina, Jessica Pratt,
 * Chuquimamani-Condori — pulled from
 * `wxyc-shared/src/test-utils/wxyc-example-data.json`. No mainstream
 * substitutes, per the org-level `CLAUDE.md` rule. Mirrors iOS's
 * `Fixtures.swift`.
 */
object Fixtures {
    val juanaMolinaSearchResult = """
        {
          "id": 100,
          "add_date": "2025-10-12T00:00:00.000Z",
          "album_title": "DOGA",
          "artist_name": "Juana Molina",
          "code_letters": "MOL",
          "code_number": 12,
          "code_artist_number": 1,
          "format_name": "CD",
          "genre_name": "Rock",
          "label": "Sonamos",
          "rotation_bin": "H"
        }
    """.trimIndent()

    val albumInfoJSON = """
        {
          "id": 100,
          "artist_id": 555,
          "album_title": "DOGA",
          "code_number": 12,
          "code_letters": "MOL",
          "artist_name": "Juana Molina",
          "format_name": "CD",
          "genre_name": "Rock",
          "label": "Sonamos",
          "add_date": "2025-10-12T00:00:00.000Z",
          "rotation": {
            "id": 9,
            "rotation_bin": "H",
            "add_date": "2025-10-15",
            "kill_date": null
          }
        }
    """.trimIndent()

    /**
     * Wire body for `GET /djs/bin` — a bare array of the denormalized library
     * join `djs.service.getBinFromDB` projects. No envelope, no
     * `bins.id`/`dj_id`/added-at: this is the shape the server actually
     * emits. Order is Pratt then Molina, which is what makes it useful for
     * testing the shelf sort.
     */
    val binResponseJSON = """
        [
          {
            "album_id": 200,
            "album_title": "On Your Own Love Again",
            "artist_name": "Jessica Pratt",
            "alphabetical_name": "Pratt, Jessica",
            "label": "Drag City",
            "code_letters": "PRA",
            "code_artist_number": 1,
            "code_number": 5,
            "format_name": "LP",
            "genre_name": "Rock",
            "legacy_release_id": 88221
          },
          {
            "album_id": 100,
            "album_title": "DOGA",
            "artist_name": "Juana Molina",
            "alphabetical_name": "Molina, Juana",
            "label": "Sonamos",
            "code_letters": "MOL",
            "code_artist_number": 1,
            "code_number": 12,
            "format_name": "CD",
            "genre_name": "Rock",
            "legacy_release_id": 55123
          }
        ]
    """.trimIndent()

    fun binEntries(): List<BinEntry> = BinResponse.decode(binResponseJSON)

    /**
     * The diacritic-bearing entries from `wxyc-example-data.json`'s
     * `canonicalArtistNames` pool, which the three headline fixtures above
     * don't supply. Used to pin [BinSorting] against Android's
     * `java.text.Collator`-via-`android.icu` divergence risk (issue #5,
     * invariant 15).
     */
    fun diacriticBearingArtists(): List<String> = listOf(
        "Aşıq Altay",
        "Csillagrablók",
        "GIDEÖN",
        "Hermanos Gutiérrez",
        "Nilüfer Yanya",
    )
}
