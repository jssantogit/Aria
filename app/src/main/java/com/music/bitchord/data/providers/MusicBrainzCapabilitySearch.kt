package com.music.bitchord.data.providers

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult

/**
 * First existing catalog entrypoint available through Provider Engine 2.0.
 *
 * The default lookup delegates to the real MusicBrainz Android catalog client,
 * keeping its validation, MusicBrainz rate limiting and typed, non-playable
 * results. A fake lookup can be injected for isolated tests.
 *
 * This adapter is not yet wired to the UI: the existing legacy catalog read
 * path must remain unchanged until settings are migrated and the new routing
 * is connected end-to-end. No implicit fallback is attempted here.
 */
internal class MusicBrainzCapabilitySearch(
    private val executor: ProviderQueryExecutor,
    private val lookupArtists: suspend (String, Int, Int) ->
        MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> =
        MusicBrainzCatalogSearch()::searchArtists,
) {
    suspend fun searchArtists(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): ProviderCallResult<MusicBrainzCatalogSearchResult<MusicBrainzArtistHit>> =
        executor.execute(ProviderCapability.CATALOG_SEARCH, PROVIDER_KEY) {
            lookupArtists(query, limit, offset)
        }

    companion object {
        val PROVIDER_KEY = ProviderKey("musicbrainz")
    }
}
