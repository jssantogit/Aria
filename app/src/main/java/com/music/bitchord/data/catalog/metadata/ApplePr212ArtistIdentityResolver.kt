package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** MusicBrainz IDs never replace Apple product IDs; this is optional evidence. */
internal data class ApplePr212ArtistIdentityCandidate(
    val artist: MusicBrainzArtistHit,
    val releaseGroups: List<MusicBrainzReleaseGroupHit>,
)

private val applePr212MbidPattern =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private fun applePr212NormalizedText(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()

private val applePr212NumberedEpTitle = Regex(
    """^(.+?)\s+[1-9][0-9]*(?:st|nd|rd|th)\s+ep\s+['‘“](.+?)['’”]$""",
)

/**
 * Apple may display e.g. "NewJeans 2nd EP 'Get Up'" where MusicBrainz
 * names the independently credited release group simply "Get Up".
 * Only remove the artist-prefixed numbered EP wrapper when its prefix
 * equals the EXACT Apple artist name; unrelated quoted titles stay intact.
 * This is a comparison key, never a displayed Apple title rewrite.
 */
private fun applePr212NormalizedReleaseTitle(
    text: String,
    artistName: String,
): String {
    val normalized = applePr212NormalizedText(text)
    val numbered = applePr212NumberedEpTitle.matchEntire(normalized)
    if (numbered != null &&
        numbered.groupValues[1] == applePr212NormalizedText(artistName)
    ) return numbered.groupValues[2].trim()
    return normalized.replace(
        Regex("""\s[-–—]\s(?:ep|single)$"""), "",
    ).trim()
}

private fun applePr212ReleaseYear(date: String?): String? =
    date?.take(4)?.takeIf {
        it.length == 4 && it.all(Char::isDigit) && it.toInt() in 1900..2100
    }

/**
 * An exact artist name alone is not a crosswalk. Require at least TWO
 * independent Apple products with different names AND two MusicBrainz
 * release-groups with the same normalized titles and first-release years,
 * each explicitly credited to the candidate. A second candidate passing
 * the same evidence makes the mapping ambiguous and therefore unavailable.
 *
 * A single MusicBrainz group covered by several Apple edition IDs counts once.
 */
internal fun applePr212VerifiedArtistMbid(
    appleArtistName: String,
    appleArtistId: Long,
    storefront: String,
    appleReleases: List<ItunesEditorialRelease>,
    candidates: List<ApplePr212ArtistIdentityCandidate>,
): String? {
    if (appleArtistId <= 0 || !Regex("[a-z]{2}").matches(storefront) ||
        appleArtistName.isBlank()) return null
    val name = applePr212NormalizedText(appleArtistName)
    val appleProof = appleReleases.asSequence()
        .filter { it.artistId == appleArtistId && it.storefront == storefront &&
            it.artistName?.let(::applePr212NormalizedText) == name &&
            it.appleScopedId == "itunes:$storefront:album:${it.appleCollectionId}" &&
            it.appleCollectionId > 0L
        }
        .mapNotNull { release ->
            val date = applePr212ReleaseYear(release.releaseDate) ?: return@mapNotNull null
            val title = applePr212NormalizedReleaseTitle(release.title, appleArtistName)
            if (title.isBlank()) null else title to date
        }
        .toSet()
    if (appleProof.map { it.first }.distinct().size < 2) return null

    val matched = candidates.asSequence()
        .filter { candidate ->
            applePr212MbidPattern.matches(candidate.artist.musicBrainzId.lowercase(Locale.ROOT)) &&
                applePr212NormalizedText(candidate.artist.name) == name
        }
        .filter { candidate ->
            val mbProof = candidate.releaseGroups.asSequence()
                .filter(::isCuratedMusicBrainzReleaseGroup)
                .filter { applePr212NormalizedText(it.artistDisplay.orEmpty()) == name }
                .mapNotNull { hit ->
                    val date = applePr212ReleaseYear(hit.firstReleaseDate)
                        ?: return@mapNotNull null
                    val title = applePr212NormalizedReleaseTitle(hit.title, appleArtistName)
                    if (title.isBlank()) null else title to date
                }
                .toSet()
            (appleProof intersect mbProof).map { it.first }.distinct().size >= 2
        }
        .map { it.artist.musicBrainzId.lowercase(Locale.ROOT) }
        .distinct()
        .take(2)
        .toList()
    return matched.singleOrNull()
}

/** All requests are read-only; a failure simply hides optional MB features. */
internal class ApplePr212ArtistIdentityResolver(
    private val search: MusicBrainzCatalogSearch = MusicBrainzCatalogSearch(),
) {
    suspend fun resolve(
        name: String,
        appleArtistId: Long,
        storefront: String,
        releases: List<ItunesEditorialRelease>,
    ): String? {
        if (releases.size < 2 || name.isBlank()) return null
        val artists = try {
            (search.searchArtists(name, limit = 12) as?
                MusicBrainzCatalogSearchResult.Success)?.page?.items.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return null }
        val exactName = applePr212NormalizedText(name)
        val exact = artists.asSequence()
            .filter { applePr212NormalizedText(it.name) == exactName }
            .distinctBy { it.musicBrainzId }
            .take(3).toList()
        val candidates = mutableListOf<ApplePr212ArtistIdentityCandidate>()
        for (artist in exact) {
            val groups = try {
                (search.searchReleaseGroupsForArtistMbid(
                    artist.musicBrainzId, limit = 100,
                ) as? MusicBrainzCatalogSearchResult.Success)?.page?.items
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            if (groups != null) candidates.add(
                ApplePr212ArtistIdentityCandidate(artist, groups),
            )
        }
        return applePr212VerifiedArtistMbid(
            name, appleArtistId, storefront, releases, candidates,
        )
    }
}
