package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat

/** Independent Apple Web source evidence. The shared display enum and title
 * syntax do not convert the native Apple Web identity into an iTunes record. */
internal enum class AppleMusicWebFormatEvidence {
    SOURCE_TITLE, SOURCE_SINGLE_FLAG, SOURCE_ALBUM_SHELF, SOURCE_SHORT_SHELF_UNSPECIFIED,
    COMMERCIAL_AUDIO_RULE, UNRESOLVED_CONFLICT, UNCLASSIFIED,
}

internal enum class AppleMusicWebEditorialReleaseGroup { ALBUMS, SINGLES_AND_EPS, UNKNOWN, CONFLICT }

/** Positive membership only, witnessed by the exact native artist view.
 * Not finding an album in a bounded page is not negative evidence. */
internal data class AppleMusicWebReleaseShelfEvidence(
    val albumScopedId: String,
    val artistScopedId: String,
    val shelf: AppleMusicWebArtistShelf,
)

internal data class AppleMusicWebReleaseClassification(
    val explicitTitle: ItunesEditorialReleaseFormat,
    val singleFlag: Boolean?,
    val commercialAudio: ItunesEditorialReleaseFormat,
    val display: ItunesEditorialReleaseFormat,
    val evidence: AppleMusicWebFormatEvidence,
    val explicitConflict: Boolean,
    val commercialDisagreement: Boolean,
    val editorialGroup: AppleMusicWebEditorialReleaseGroup,
    val sourceShelfGroup: AppleMusicWebEditorialReleaseGroup,
)

/** Only evaluate the actual requested edition. A false isSingle is not an
 * album declaration. Do not derive a short format from a partial tracklist,
 * missing durations, duplicated positions or a count of non-audio resources.
 * Source labels remain authoritative over a calculated commercial rule.
 */
internal fun classifyAppleMusicWebRelease(
    album: AppleMusicWebAlbum, complete: Boolean,
    shelfEvidence: Collection<AppleMusicWebReleaseShelfEvidence> = emptyList(),
): AppleMusicWebReleaseClassification {
    val unknown = ItunesEditorialReleaseFormat.UNCLASSIFIED
    val title = ItunesEditorialCatalog.formatFromAppleTitle(album.identity.title)
    val flag = album.identity.isSingle
    val count = album.identity.trackCount
    val rows = album.tracks
    val songs = rows.filter { it.kind == AppleMusicWebKind.SONG }
    val positions = songs.mapNotNull { row ->
        row.discNumber?.takeIf { it > 0 }?.let { disc ->
            row.trackNumber?.takeIf { it > 0 }?.let { track -> disc to track }
        }
    }
    val clean = album.identity.kind == AppleMusicWebKind.ALBUM &&
        count != null && count in 1..500 && rows.size in 1..count &&
        songs.isNotEmpty() && rows.all {
            it.storefront == album.identity.storefront &&
                it.id.matches(Regex("[0-9]{1,12}")) &&
                it.kind in setOf(AppleMusicWebKind.SONG, AppleMusicWebKind.MUSIC_VIDEO)
        } && rows.distinctBy { it.scopedId }.size == rows.size &&
        positions.size == songs.size && positions.distinct().size == songs.size
    val durations = songs.mapNotNull { it.durationMillis }
    val exact = clean && complete && !album.possiblyTruncated && rows.size == count &&
        durations.size == songs.size && durations.all { it in 1L..86_400_000L }
    val commercial = when {
        !clean -> unknown
        songs.size >= 7 -> ItunesEditorialReleaseFormat.ALBUM
        !exact -> unknown
        songs.size <= 3 && durations.all { it < 600_000L } -> ItunesEditorialReleaseFormat.SINGLE
        songs.size <= 6 && durations.sum() <= 1_800_000L -> ItunesEditorialReleaseFormat.EP
        else -> ItunesEditorialReleaseFormat.ALBUM
    }
    val artistIds = album.artists.filter {
        it.kind == AppleMusicWebKind.ARTIST && it.storefront == album.identity.storefront
    }.map { it.scopedId }.toSet()
    val shelves = shelfEvidence.filter {
        it.albumScopedId == album.identity.scopedId && it.artistScopedId in artistIds
    }.map { it.shelf }.toSet()
    val inAlbums = AppleMusicWebArtistShelf.STUDIO_ALBUMS in shelves
    val inShort = AppleMusicWebArtistShelf.SINGLES_AND_EPS in shelves
    val group = when {
        inAlbums && inShort -> AppleMusicWebEditorialReleaseGroup.CONFLICT
        inAlbums -> AppleMusicWebEditorialReleaseGroup.ALBUMS
        inShort -> AppleMusicWebEditorialReleaseGroup.SINGLES_AND_EPS
        else -> AppleMusicWebEditorialReleaseGroup.UNKNOWN
    }
    val explicitShort = title in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP) || flag == true
    val explicitConflict = title == ItunesEditorialReleaseFormat.EP && flag == true ||
        group == AppleMusicWebEditorialReleaseGroup.CONFLICT ||
        (explicitShort && inAlbums)
    val (display, evidence) = when {
        explicitConflict -> unknown to AppleMusicWebFormatEvidence.UNRESOLVED_CONFLICT
        title != unknown -> title to AppleMusicWebFormatEvidence.SOURCE_TITLE
        flag == true -> ItunesEditorialReleaseFormat.SINGLE to AppleMusicWebFormatEvidence.SOURCE_SINGLE_FLAG
        inAlbums -> ItunesEditorialReleaseFormat.ALBUM to AppleMusicWebFormatEvidence.SOURCE_ALBUM_SHELF
        inShort -> {
            // The native combined shelf establishes only the group. A
            // calculated short format may refine it, but a long tracklist
            // must not turn the shelf's release into an album or invented EP.
            if (commercial in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP))
                commercial to AppleMusicWebFormatEvidence.COMMERCIAL_AUDIO_RULE
            else unknown to AppleMusicWebFormatEvidence.SOURCE_SHORT_SHELF_UNSPECIFIED
        }
        commercial != unknown -> commercial to AppleMusicWebFormatEvidence.COMMERCIAL_AUDIO_RULE
        else -> unknown to AppleMusicWebFormatEvidence.UNCLASSIFIED
    }
    val editorialGroup = when {
        explicitConflict -> AppleMusicWebEditorialReleaseGroup.CONFLICT
        group != AppleMusicWebEditorialReleaseGroup.UNKNOWN -> group
        display == ItunesEditorialReleaseFormat.ALBUM -> AppleMusicWebEditorialReleaseGroup.ALBUMS
        display in setOf(ItunesEditorialReleaseFormat.SINGLE, ItunesEditorialReleaseFormat.EP) ->
            AppleMusicWebEditorialReleaseGroup.SINGLES_AND_EPS
        else -> AppleMusicWebEditorialReleaseGroup.UNKNOWN
    }
    return AppleMusicWebReleaseClassification(
        title, flag, commercial, display, evidence, explicitConflict,
        (display != unknown && commercial != unknown && display != commercial) ||
            (inShort && commercial == ItunesEditorialReleaseFormat.ALBUM),
        editorialGroup, group,
    )
}
