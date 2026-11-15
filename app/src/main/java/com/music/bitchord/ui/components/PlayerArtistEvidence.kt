package com.music.bitchord.ui.components

import android.content.Context
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalArtistCreditRow
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.androidCanonicalQueueResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Source provenance is part of artist navigation, not inferred from a name. */
internal data class SourceOwnedArtistEvidence(
    val artistId: String,
    val artistName: String,
    val providerKey: String,
)

internal fun verifiedSourceOwnedPlayerArtist(
    recordingId: String,
    providerKey: String,
    credits: List<CanonicalArtistCreditRow>,
    availableArtists: List<ArtistEntity>,
): SourceOwnedArtistEvidence? {
    if (recordingId.isBlank() || providerKey.isBlank()) return null
    val lead = credits.filter { it.position == 0 && it.artistId.isNotBlank() }
        .distinctBy { it.artistId }.singleOrNull() ?: return null
    val actual = availableArtists.filter { it.id == lead.artistId }.singleOrNull()
        ?: return null
    return SourceOwnedArtistEvidence(actual.id, actual.name, providerKey)
}

/** Exact Apple route format used by the iTunes catalog; no display-name search. */
internal fun verifiedAppleArtistBrowseRoute(raw: String?): String? =
    raw?.takeIf { Regex("^itunes:[a-z]{2}:artist:[1-9][0-9]*$").matches(it) }

/** A provider-native artist key is not a legacy YouTube artist URL. */
internal fun verifiedLegacyPlayerArtistBrowseId(value: String?): String? =
    value?.takeIf { it.startsWith("UC") && it.length >= 20 }
        ?: value?.takeIf {
            Regex("^catalog:ARTIST:[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(it)
        }

/**
 * Only a queue identity that resolves to one current MediaAsset may open an
 * artist inside the same provider. Avoid crossing local files and Navidrome.
 */
internal suspend fun verifiedPlayerSourceOwnedArtist(
    context: Context,
    song: Song,
): SourceOwnedArtistEvidence? = withContext(Dispatchers.IO) {
    val resolver = androidCanonicalQueueResolver(context.applicationContext)
    val identity = resolver.identifyAll(listOf(song)).singleOrNull()
        ?: return@withContext null
    val source = identity.providerKey?.takeIf(String::isNotBlank)
        ?: return@withContext null
    if (resolver.resolveAll(listOf(identity)).singleOrNull()?.identity != identity) {
        return@withContext null
    }
    val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
    try {
        verifiedSourceOwnedPlayerArtist(
            recordingId = identity.recordingId,
            providerKey = source,
            credits = dao.recordingArtistCredits(identity.recordingId),
            availableArtists = dao.providerScopedArtists(source),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
