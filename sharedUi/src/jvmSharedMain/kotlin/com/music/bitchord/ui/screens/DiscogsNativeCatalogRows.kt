package com.music.bitchord.ui.screens

/**
 * Provider-scoped metadata in the common native search compositor.
 * Discogs ids must never be cast to MusicBrainz, Apple, Song or video IDs.
 */
data class DiscogsNativeArtistSearchRow(
    val artistId: Long,
    val name: String,
) {
    val browseId: String get() = "discogs:artist:$artistId"
}

/** Typed Release search hit. Its title is not used to infer another provider's ID. */
data class DiscogsNativeReleaseSearchRow(
    val releaseId: Long,
    val title: String,
) {
    val browseId: String get() = "discogs:release:$releaseId"
}
