package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.isUnresolvedSpotify
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

/** Preserve the tapped occurrence rather than using Song equality. */
internal fun selectPlayableQueueOccurrence(songs: List<Song>, index: Int): Pair<List<Song>,Int>? {
    val selected = songs.getOrNull(index) ?: return null
    if (selected.isUnresolvedSpotify) return null
    return songs.filterNot { it.isUnresolvedSpotify } to songs.take(index).count { !it.isUnresolvedSpotify }
}

internal fun amsSearchPreviewSong(row: AppleEditorialTrackSearchRow): Song? {
    val route = AmsPreviewRoute.fromScopedId(row.key) ?: return null
    if (!row.hasAudioPreview || row.title.isBlank()) return null
    return Song(videoId=route.scopedId,title=row.title,artist=row.artistName,thumbnailUrl=row.artworkUri,
        albumId=row.albumBrowseId?.takeIf {
            AppleMusicWebNativeRoute.parse(it)?.let { album ->
                album.storefront == route.storefront &&
                    album.kind == com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ALBUM
            } == true
        },isAudioPreview=true)
}

internal fun amsHeldPreviewSong(track: NativeMetadataTrack, parent: String?): Song? {
    val route = AmsPreviewRoute.fromScopedId(track.key) ?: return null
    val page = AppleMusicWebNativeRoute.parse(parent) ?: return null
    if (!track.hasAudioPreview || track.title.isBlank() || page.storefront != route.storefront) return null
    return Song(videoId=route.scopedId,title=track.title,artist=track.credit.orEmpty(),
        thumbnailUrl=track.artworkUri,albumId=page.browseId.takeIf {
            page.kind == com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ALBUM
        },isAudioPreview=true)
}

/** Editorial card membership is revalidated by the caller before use. */
internal fun amsShelfPreviewSong(card: ShelfItem): Song? {
    val route = AmsPreviewRoute.fromScopedId(card.browseId) ?: return null
    if (!card.hasAudioPreview || card.videoId != null || card.title.isBlank() ||
        appleMusicWebVerifiedRouteUrl(card.appleStoreUrl, route.scopedId) == null) return null
    return Song(videoId=route.scopedId,title=card.title,artist=card.subtitle,
        thumbnailUrl=card.thumbnailUrl,isAudioPreview=true)
}

/** Collection actions reuse the same ordered sample subset as the detail hero.
 * Artists and unrelated/stale page identities are not collection queues. */
internal fun amsCollectionPreviewSongs(expectedId: String, page: DetailPage, tracks: List<NativeMetadataTrack>): List<Song> {
    if (page.browseId != expectedId) return emptyList()
    val route = AppleMusicWebNativeRoute.parse(expectedId) ?: return emptyList()
    if ((route.kind != com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ALBUM || page.type != BrowseType.ALBUM) &&
        (route.kind != com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.PLAYLIST || page.type != BrowseType.PLAYLIST)) return emptyList()
    return amsDetailPreviewSongs(page,tracks).values.toList()
}

internal enum class AmsCollectionPlaybackAction { PLAY, SHUFFLE, NEXT, QUEUE }
internal typealias AmsCardCollectionPlayback = (String, String, AmsCollectionPlaybackAction, () -> Boolean) -> Unit

internal fun amsCardCollectionAction(id: String?, title: String, action: AmsCollectionPlaybackAction,
    enabled: Boolean, callback: AmsCardCollectionPlayback?, isCurrent: () -> Boolean): (() -> Unit)? {
    val route = AppleMusicWebNativeRoute.parse(id) ?: return null
    if (!enabled || callback == null || route.kind !in setOf(
            com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.ALBUM,
            com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind.PLAYLIST)) return null
    return { if (isCurrent()) callback(route.browseId,title,action,isCurrent) }
}

/** A preview may be rated by exact source-owned metadata, not a fabricated MediaAsset. */
internal fun amsPreviewRatingLabel(song: Song): CatalogMetadataRecordingLabel? {
    if (!song.isAudioPreview || AmsPreviewRoute.fromScopedId(song.videoId) == null) return null
    return CatalogMetadataRecordingLabel(song.videoId,song.title,song.artist)
        .takeIf(::validCatalogMetadataRecordingLabel)
}

/** The provider exposes a sample URL, not its offset in the full recording.
 * Retain the lyrics for reading/sharing, but never claim full-song timestamps
 * correspond to the excerpt. Explicit sample alignment can later replace this
 * policy only when there is positive timing evidence. */
internal fun amsPreviewSafeLyrics(
    preview: Boolean, lines: List<com.music.bitchord.data.lyrics.LyricLine>?,
): List<com.music.bitchord.data.lyrics.LyricLine>? =
    if (!preview) lines else lines?.filterNot { it.isGap }?.map { line ->
        com.music.bitchord.data.lyrics.LyricLine(0L,line.text,
            alignment = line.alignment,
            background=line.background?.takeUnless { it.isGap }?.let {
                com.music.bitchord.data.lyrics.LyricLine(0L,it.text,alignment=it.alignment)
            })
    }
