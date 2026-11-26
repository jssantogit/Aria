package com.music.bitchord.data.catalog.provider.applemusicweb

import java.text.Normalizer
import java.util.Locale

internal data class AppleMusicWebRoomRef(
    val storefront: String, val genreId: String?, val id: String, val title: String,
    val curatorId: String? = null,
)
internal data class AppleMusicWebRoomPage(
    val room: AppleMusicWebRoomRef, val kind: AppleMusicWebKind,
    val items: List<AppleMusicWebItem>, val sourceRows: Int, val excludedRows: Int,
    val unresolvedReferences: Int, val nextCursor: String?,
)

/** Localized semantic aliases observed by the Python oracle. No guessed room ID. */
internal enum class AppleMusicWebHomeShelf(val labels: Set<String>, val kind: AppleMusicWebKind) {
    NEW_THIS_WEEK(setOf("New This Week", "Novidades da semana", "Nouveautés de la semaine"), AppleMusicWebKind.ALBUM),
    RECENT_RELEASES(setOf("Recent Releases", "Lançamentos recentes", "Sorties récentes"), AppleMusicWebKind.ALBUM),
    TRENDING_SONGS(setOf("Trending Songs", "Músicas em alta", "Titres tendances"), AppleMusicWebKind.SONG),
    BEST_NEW_SONGS(setOf("Best New Songs", "Melhores músicas novas", "Titres du moment"), AppleMusicWebKind.SONG),
}

internal fun resolveAppleMusicWebHomeRoom(
    rooms: List<AppleMusicWebRoomRef>, storefront: String, shelf: AppleMusicWebHomeShelf,
): AppleMusicWebRoomRef? {
    fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val keys = shelf.labels.map(::fold).toSet()
    return rooms.filter { it.storefront == storefront && it.genreId == null && it.curatorId == null && fold(it.title) in keys }
        .distinctBy { it.id }.singleOrNull()
}
