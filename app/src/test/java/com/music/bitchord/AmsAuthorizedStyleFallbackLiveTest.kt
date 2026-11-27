package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Diagnostic of the existing ordered canonical fallback, not AMS coverage.
 * The AMS miss is the established live observation, not a new AMS request.
 * No cross-provider identity claim, no extra provider in production. */
class AmsAuthorizedStyleFallbackLiveTest {
    @Test fun exactTagMusicBrainzFallbackForEmptyAmsStyles(): Unit = runBlocking {
        assumeTrue(System.getenv("AMS_FALLBACK_LIVE") == "1")
        val mb = BuiltInProviderKeys.MUSICBRAINZ
        val ams = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(mb,setOf(editorial),ProviderReadiness.READY)),
            mapOf(editorial to listOf(ProviderChoice(mb,true,0))),
        ))
        val source = MusicBrainzCatalogSearch(MusicBrainzClient(admission = { gate }),
            capability = editorial)
        val date = LocalDate.now(ZoneOffset.UTC)
        val gaps = mutableListOf<String>()
        for (tag in listOf("art pop","boom bap","ambient techno")) {
            val historical = (withTimeout(120_000) { source.searchReleaseGroupsForTag(tag,60) }
                as? MusicBrainzCatalogSearchResult.Success)?.page
            val recent = (withTimeout(120_000) {
                source.searchRecentReleaseGroupsForTag(tag,date.minusDays(180).toString(),date.toString(),40)
            } as? MusicBrainzCatalogSearchResult.Success)?.page
            val hits = (historical?.items.orEmpty() + recent?.items.orEmpty()).distinctBy { it.musicBrainzId }
            var rows = canonicalPublicGenreRows(hits,date.toString())
            if (rows.none { canonicalEditorialReleaseType(it) == "Album" }) {
                val albums = (withTimeout(120_000) { source.searchAlbumReleaseGroupsForTag(tag,24) }
                    as? MusicBrainzCatalogSearchResult.Success)?.page
                val extra = canonicalPublicGenreRows(hits + albums?.items.orEmpty(),date.toString())
                if (extra.any { canonicalEditorialReleaseType(it) == "Album" }) rows = extra
            }
            val amsMiss = mapOf(tag to UiState.Success(emptyList<HomeShelf>()))
            val state = UiState.Success(rows)
            val dispatch = shouldDispatchCanonicalGenreSource(tag,mb,listOf(ams,mb),
                tag,state,emptyMap(),emptyMap(),amsMiss)
            assertTrue(dispatch)
            assertFalse(shouldDispatchCanonicalGenreSource(tag,mb,listOf(ams),
                tag,state,emptyMap(),emptyMap(),amsMiss))
            fun project(order: List<ProviderKey>, resultTag: String = tag) =
                projectCanonicalGenreState(tag,emptyList(),"Playlists",emptyMap(),state,order,
                    "Lançamentos",musicBrainzResultTag=resultTag,
                    todayIsoDate=date.toString(),amsByTag=amsMiss)
            val fallback = (project(listOf(ams,mb)) as? UiState.Success)?.data.orEmpty()
            val cards = fallback.flatMap { it.items }.distinctBy { it.browseId }
            assertTrue(cards.all { it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") && it.videoId == null })
            assertTrue((project(listOf(ams)) as? UiState.Success)?.data.orEmpty().isEmpty())
            assertEquals(UiState.Loading,project(listOf(ams,mb),"rock"))
            val albums = rows.filter { canonicalEditorialReleaseType(it) == "Album" }
                .map { "catalog:RELEASE_GROUP:" + it.musicBrainzId }.toSet()
            val visibleAlbums = cards.count { it.browseId in albums }
            println("AMS_FALLBACK|tag=$tag|historical=${historical?.items?.size ?: -1}|recent=${recent?.items?.size ?: -1}|visible=${cards.size}|albums=$visibleAlbums|nativeAms=0")
            if (visibleAlbums < 6) gaps += tag
        }
        assertTrue("Insufficient authorized MusicBrainz fallback: " + gaps.joinToString(),gaps.isEmpty())
    }
}
