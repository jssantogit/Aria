package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.providers.*
import com.music.bitchord.playback.AmsPreviewRoute
import com.music.bitchord.ui.AppleMusicWebNativeRoute

internal fun amsPreviewPlaybackAllowed(gate: ProviderPolicyGate?): Boolean =
    ProviderReadLease.acquire(gate,listOf(
        ProviderCapability.AUDIO_PLAYBACK to BuiltInProviderKeys.APPLE_MUSIC_WEB,
        ProviderCapability.EDITORIAL_DISCOGRAPHY to BuiltInProviderKeys.APPLE_MUSIC_WEB,
    ))?.isCurrent() == true

/** A playable queue entry is a sample, not a RecordingId or full asset.
 * Keep indices: playlists may intentionally repeat the same native song. */
internal fun amsDetailPreviewSongs(page: DetailPage, tracks: List<NativeMetadataTrack>): Map<Int,Song> {
    val parent = AppleMusicWebNativeRoute.parse(page.browseId) ?: return emptyMap()
    return tracks.mapIndexedNotNull { index, track ->
        val route = AmsPreviewRoute.fromScopedId(track.key)
            ?.takeIf { it.storefront == parent.storefront } ?: return@mapIndexedNotNull null
        if (!track.hasAudioPreview || track.title.isBlank()) return@mapIndexedNotNull null
        val credit = track.credit.orEmpty()
        index to Song(videoId=route.scopedId,title=track.title,artist=credit,
            thumbnailUrl=track.artworkUri ?: page.thumbnailUrl,
            albumId=page.browseId.takeIf { page.type == BrowseType.ALBUM },
            albumName=page.title.takeIf { page.type == BrowseType.ALBUM },
            isAudioPreview=true)
    }.toMap()
}
