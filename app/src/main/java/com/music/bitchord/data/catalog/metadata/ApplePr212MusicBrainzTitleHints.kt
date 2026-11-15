package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * MB supplies candidate *titles* only. An unverified artist name is not an
 * identity crosswalk. The Apple pager requires a matching product artist ID
 * before any candidate is displayed; no MB title, artwork or ID is copied.
 */
internal class ApplePr212MusicBrainzTitleHints(
    private val source: MusicBrainzCatalogSearch = MusicBrainzCatalogSearch(),
) : AppleArtistTitleHintSource {
    override suspend fun suggest(artistName: String, verifiedMbArtistId: String?): List<String> {
        if (artistName.isBlank()) return emptyList()
        val answer = try {
            if (verifiedMbArtistId == null) {
                source.searchReleaseGroups(artistName, limit = 100)
            } else {
                source.searchReleaseGroupsForArtistMbid(verifiedMbArtistId, limit = 100)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return emptyList() }
        val groups = (answer as? MusicBrainzCatalogSearchResult.Success)
            ?.page?.items.orEmpty()
        val key = artistName.trim().lowercase(Locale.ROOT)
        return groups.asSequence()
            .filter { it.artistDisplay?.trim()?.lowercase(Locale.ROOT) == key }
            .filter(::isCuratedMusicBrainzReleaseGroup)
            .map { it.title.trim() }
            .filter { it.length in 2..120 && it.none(Char::isISOControl) }
            .distinct()
            .take(32).toList()
    }
}
