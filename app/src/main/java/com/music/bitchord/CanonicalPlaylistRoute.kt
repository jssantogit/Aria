package com.music.bitchord

/**
 * Library→playlist entry is a pushed route, not an independent tab.
 * A route-hosted wizard would take priority over the parent playlist list,
 * but the Android full-screen creator is Dialog-owned, not a second page.
 * Its current host must leave the parent page composed behind that window.
 * Keep the route logic shared with the Android regression tests so a
 * branch reconciliation cannot silently bring back the legacy list.
 */
/** Preserve a painted page underneath the full-screen native Dialog.
 * From the root Library this returns null (use the current tab); from the
 * Playlist collection it returns that same collection route. The editor
 * and Library selection dialogs continue to use one shared editor UI. */
internal fun canonicalPlaylistModalBackgroundRoute(showLibrary: Boolean): String? =
    canonicalPlaylistRoute(showBuilder = false, showLibrary = showLibrary)

internal fun canonicalPlaylistRoute(
    showBuilder: Boolean,
    showLibrary: Boolean,
): String? = when {
    showBuilder -> "smart_playlist_builder"
    showLibrary -> "canonical_playlist_library"
    else -> null
}
