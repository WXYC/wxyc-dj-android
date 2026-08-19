package org.wxyc.dj.api

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single client-side answer to "is this album in rotation?", shared by
 * every row type that carries a rotation record (today, [AlbumInfo.Rotation];
 * a future on-device catalog clone would be the other). It lives in one place
 * because those are **alternatives, not complements** — a DJ sees one answer
 * or the other for the same album on the same screen, so any divergence
 * between them reads as the app contradicting itself. Mirrors
 * `RotationPredicate.swift` (iOS issue #93).
 */
object RotationPredicate {
    /**
     * Mirrors the server's published predicate — `rotation_bin != null &&
     * (kill_date == null || kill_date > today)` — evaluated against the
     * client's own calendar day.
     *
     * **Any** non-null [bin] counts as in rotation, including one outside the
     * current H/M/L/S cohorts: that forward-compatibility hedge is why a
     * bin is carried as a raw string rather than a closed enum wherever this
     * predicate is fed from. An empty-string bin is *not* one of those —
     * callers normalize `""` to `null` before reaching here, so it arrives as
     * "no assignment". The kill-date compare is **strict**, matching
     * `kill_date > CURRENT_DATE`: a record expiring *today* is already out.
     *
     * [killDay] and [today] MUST both be zero-padded `YYYY-MM-DD` days (the
     * output of [localDay]). The lexicographic compare is equivalent to a
     * chronological one only for that fixed-width form.
     *
     * A [killDay] this cannot read is treated as **expired, not
     * un-expiring**. Both row types hold this value as the raw wire string,
     * so a malformed one reaches here intact rather than being rejected at
     * decode — and a bare `killDay > today` on garbage is worse than useless:
     * `"not-a-date"` sorts above every real `"YYYY-MM-DD"`, so a corrupt value
     * would read as in rotation *forever*. Failing closed costs at most a
     * badge on a record whose expiry is unreadable; failing open leaves dead
     * records on the shelf indefinitely with nothing to surface it.
     */
    fun isInRotation(bin: String?, killDay: String?, today: String): Boolean {
        if (bin == null) return false
        if (killDay == null) return true
        val day = calendarDay(killDay) ?: return false
        return day > today
    }

    /**
     * The leading `YYYY-MM-DD` of an ISO-8601 date or date-time, or `null`
     * when [raw] isn't one. Taking the prefix rather than demanding an
     * exact-width match is what keeps a full timestamp comparable against a
     * bare day without reinterpreting it through a time zone. Digits are
     * checked against ASCII `0`-`9` specifically: `Char.isDigit()` also
     * accepts other Unicode digit forms, which would pass the shape check and
     * then sort arbitrarily against an ASCII day.
     */
    fun calendarDay(raw: String): String? {
        if (raw.length < 10) return null
        val day = raw.substring(0, 10)
        for ((offset, character) in day.withIndex()) {
            if (offset == 4 || offset == 7) {
                if (character != '-') return null
            } else {
                if (character !in '0'..'9') return null
            }
        }
        return day
    }

    /**
     * The calendar day of [now] in [zone] as a zero-padded `YYYY-MM-DD`
     * string. Converts *the client's clock* to a day, and nothing else — a
     * rotation kill date is held raw and handed to [isInRotation] untouched,
     * so no caller passes a rotation date through here.
     */
    fun localDay(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        return LocalDate.ofInstant(now, zone).toString()
    }
}
