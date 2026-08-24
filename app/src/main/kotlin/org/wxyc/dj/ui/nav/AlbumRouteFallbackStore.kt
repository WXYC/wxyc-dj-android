package org.wxyc.dj.ui.nav

import org.wxyc.dj.api.AlbumSearchResult

/**
 * The out-of-band hand-off for [AlbumRoute]'s payload (issue #23). Since
 * [AlbumRoute] carries only an [AlbumRoute.id], a screen that already has a
 * matching row in hand -- a search result, eventually a bin entry -- has no
 * way to carry it *through* the route. It stashes the row here immediately
 * before navigating, and `AlbumDetailScreen` reads it back exactly once, so
 * the header still renders instantly instead of waiting on `/library/info`.
 *
 * **Why a plain holder instead of `SavedStateHandle`.** The issue sketched
 * two options: this one, or writing the row onto the previous back-stack
 * entry's `SavedStateHandle` and reading it via
 * `navController.previousBackStackEntry` from the destination side.
 * `SavedStateHandle` was rejected for two concrete reasons, not just
 * "it's more idiomatic but tighter coupling": first, it validates that a
 * stored value is one of a fixed set of Bundle-savable types, and
 * [AlbumSearchResult] -- a plain kotlinx-serialization `data class`, not
 * `Parcelable`/`Serializable` -- is not one of them, which would have meant
 * either making it `Parcelable` for no reason but this hand-off or wrapping
 * it in yet another JSON-string encode/decode, reintroducing exactly the
 * class of bug [AlbumRoute]'s KDoc describes `AlbumSearchResultNavType` as
 * the source of. Second, reading via `previousBackStackEntry` would require
 * `MainScaffold.kt`'s `composable<AlbumRoute>` block to reach back across
 * the graph to whichever entry happens to sit below it -- coupling the
 * detail destination to its caller's position on the stack -- where this
 * store lets the caller and the reader agree on nothing but the album [id].
 * A plain, dependency-free holder needs neither.
 *
 * **Shape: one slot, last write wins, single-use.** [stash] overwrites
 * whatever was pending; [take] returns the pending row only when its id
 * matches the one asked for, and clears the slot on that match so a second
 * `take` for the same id (a recomposition that re-runs the read without
 * re-keying, for instance) sees `null` rather than replaying a stale value.
 * A mismatched or absent id -- a cold-launch deep link that never had a row
 * to stash, for instance -- returns `null` without disturbing whatever else
 * is pending; there is nothing useful to reconcile it against, and the next
 * real [stash] overwrites it regardless. This is deliberately **not** a
 * map keyed by every in-flight id: the only production consumer reads
 * immediately after the navigation that produced its row, so there is never
 * more than one row worth remembering at a time. See `AlbumDetailScreen.kt`
 * for the read side and `AlbumRouteNavigationTest` for the real
 * `navigate()`-driven proof that two differently-sourced stashes for the
 * same id still resolve to one back-stack entry.
 *
 * A plain Kotlin `object`, not a Hilt `@Singleton`: it has no collaborators
 * to inject (no storage, no service), so Hilt's graph would add an
 * `@EntryPoint`/`@Module` detour for zero benefit, and `MainScaffold.kt`
 * (where the read is wired) has no other Hilt dependency of its own to
 * justify introducing one. [stash]/[take] are `@Synchronized` as cheap
 * insurance -- every real caller is on the main thread (a tap handler
 * immediately followed by `navController.navigate(...)`, and Compose
 * recomposition) -- rather than because concurrent access is expected.
 */
object AlbumRouteFallbackStore {
    private var pending: Entry? = null

    /** Stashes [fallback] for [id], overwriting any previously-pending, unconsumed entry. */
    @Synchronized
    fun stash(id: Int, fallback: AlbumSearchResult) {
        pending = Entry(id, fallback)
    }

    /**
     * Returns and clears the pending fallback for [id] if one is stashed and
     * its id matches; otherwise returns `null` without touching whatever
     * (mismatched) entry is pending.
     */
    @Synchronized
    fun take(id: Int): AlbumSearchResult? {
        val current = pending ?: return null
        if (current.id != id) return null
        pending = null
        return current.fallback
    }

    /**
     * Test-only reset. Robolectric isolates statics **per test class**, not
     * per test method, so an `object`'s state can otherwise leak between
     * `@Test` methods that share a class.
     */
    @Synchronized
    internal fun clearForTesting() {
        pending = null
    }

    private data class Entry(val id: Int, val fallback: AlbumSearchResult)
}
