package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat

/** Independent Apple Web source evidence. The shared display enum and title
 * syntax do not convert the native Apple Web identity into an iTunes record. */
internal enum class AppleMusicWebFormatEvidence {
    SOURCE_TITLE, SOURCE_SINGLE_FLAG, COMMERCIAL_AUDIO_RULE, UNRESOLVED_CONFLICT, UNCLASSIFIED,
}

internal data class AppleMusicWebReleaseClassification(
    val explicitTitle: ItunesEditorialReleaseFormat,
    val singleFlag: Boolean?,
    val commercialAudio: ItunesEditorialReleaseFormat,
    val display: ItunesEditorialReleaseFormat,
    val evidence: AppleMusicWebFormatEvidence,
    val explicitConflict: Boolean,
    val commercialDisagreement: Boolean,
)

/** Only evaluate the actual requested edition. A false isSingle is not an
 * album declaration. Do not derive a short format from a partial tracklist,
 * missing durations, duplicated positions or a count of non-audio resources.
 * Source labels remain authoritative over a calculated commercial rule.
 */
internal fun classifyAppleMusicWebRelease(
    album: AppleMusicWebAlbum, complete: Boolean,
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
    val explicitConflict = title == ItunesEditorialReleaseFormat.EP && flag == true
    val (display, evidence) = when {
        explicitConflict -> unknown to AppleMusicWebFormatEvidence.UNRESOLVED_CONFLICT
        title != unknown -> title to AppleMusicWebFormatEvidence.SOURCE_TITLE
        flag == true -> ItunesEditorialReleaseFormat.SINGLE to AppleMusicWebFormatEvidence.SOURCE_SINGLE_FLAG
        commercial != unknown -> commercial to AppleMusicWebFormatEvidence.COMMERCIAL_AUDIO_RULE
        else -> unknown to AppleMusicWebFormatEvidence.UNCLASSIFIED
    }
    return AppleMusicWebReleaseClassification(
        title, flag, commercial, display, evidence, explicitConflict,
        display != unknown && commercial != unknown && display != commercial,
    )
}
