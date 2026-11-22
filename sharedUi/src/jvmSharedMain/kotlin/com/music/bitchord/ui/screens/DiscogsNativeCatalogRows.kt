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
