package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicWebNativeCatalogSessionTest {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val search = ProviderCapability.CATALOG_SEARCH
    private fun gate(vararg caps: ProviderCapability) = ProviderPolicyGate(
        ProviderCapabilityPolicy(listOf(ProviderDescriptor(provider,setOf(editorial,search),ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(provider,true,0)) }),
    )

    @Test fun nativeRouteNeverReinterpretsItunesOrMusicBrainz() {
        val id = "apple_music_web:br:album:123"
        assertEquals(id,AppleMusicWebNativeRoute.parse(id)?.browseId)
        for (bad in listOf("itunes:br:album:123","catalog:RELEASE:123",
            "apple_music_web:br:song:123","apple_music_web:BR:album:123",
            "apple_music_web:br:album:0","apple_music_web:br:album:123:extra")) {
            assertNull(AppleMusicWebNativeRoute.parse(bad))
        }
    }

    @Test fun blockedRuntimeDoesNotConstructReaderOrBorrowOtherCapability(): Unit = runBlocking {
        var factories = 0
        var current: ProviderPolicyGate? = null
        val session = AppleMusicWebNativeCatalogSession({ current }) {
            factories++
            error("No reader may be constructed")
        }
        assertEquals(ProviderCallResult.Denied,session.genre("soul","br"))
        current = gate(search)
        assertEquals(ProviderCallResult.Denied,session.genre("soul","br"))
        assertEquals(ProviderCallResult.Denied,session.detail("apple_music_web:br:artist:123"))
        current = gate(editorial)
        assertEquals(ProviderCallResult.Denied,session.search(CatalogSearchRequest(CatalogSearchKind.ARTIST,"A")))
        assertEquals(0,factories)
    }

    @Test fun genreUsesCanonicalShelfAndNativeAlbumRouteWithoutPlayback(): Unit = runBlocking {
        val g = gate(editorial)
        var calls = 0
        val session = AppleMusicWebNativeCatalogSession({ g }) { policy ->
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebTransport { _,path,_ ->
                calls++
                if (path.endsWith("/976439585")) """{"data":[{"id":"976439585","type":"apple-curators","attributes":{"name":"Apple Music Soul e funk"},
                  "relationships":{"grouping":{"data":[{"relationships":{"tabs":{"data":[{"relationships":{"children":{"data":[
                  {"attributes":{"name":"Lançamentos recentes"},"relationships":{"room":{"data":[{"id":"456","type":"rooms"}]}}}
                  ]}}}]}}}]}}}]}"""
                else """{"data":[
                    {"id":"123","type":"albums","attributes":{"name":"Accepted","artistName":"Artist","genreNames":["R&B/Soul"]}},
                    {"id":"124","type":"albums","attributes":{"name":"Rejected","genreNames":["Funk"]}}
                    ]}"""
            }),policy)
        }
        val outcome = (session.genre("soul","br") as ProviderCallResult.Success).value as AppleMusicWebOutcome.Complete
        val shelves = outcome.value
        val item = shelves.single().items.single()
        assertEquals("apple_music_web:br:album:123",item.browseId)
        assertEquals("Artist",item.subtitle)
        assertNull(item.videoId)
        assertEquals(2,calls)
        val state = projectCanonicalGenreState(
            tag="soul",genrePlaylists=emptyList(),playlistHeading="Playlists",
            appleByTag=emptyMap(),musicBrainzRows=UiState.Loading,
            ordered=listOf(provider),releasesHeading="Lançamentos",
            amsByTag=mapOf("soul" to UiState.Success(shelves)),
        )
        assertEquals(shelves,(state as UiState.Success).data)
        val other = BuiltInProviderKeys.DISCOGS
        assertFalse(shouldDispatchCanonicalGenreSource("soul",other,listOf(provider,other),
            null,UiState.Loading,emptyMap(),emptyMap(),mapOf("soul" to UiState.Success(shelves))))
        assertTrue(shouldDispatchCanonicalGenreSource("soul",other,listOf(provider,other),
            null,UiState.Loading,emptyMap(),emptyMap(),mapOf("soul" to UiState.Error("unavailable"))))
        assertTrue(shouldDispatchCanonicalGenreSource("unknown",other,listOf(provider,other),
            null,UiState.Loading,emptyMap(),emptyMap()))
        val disabled = projectCanonicalGenreState(
            tag="soul",genrePlaylists=emptyList(),playlistHeading="Playlists",
            appleByTag=emptyMap(),musicBrainzRows=UiState.Loading,
            ordered=emptyList(),releasesHeading="Lançamentos",
            amsByTag=mapOf("soul" to UiState.Success(shelves)),
        )
        assertFalse(disabled is UiState.Success && disabled.data.isNotEmpty())
    }

    @Test fun genreCoverUsesOnlyAdmittedTypedAmsCardsAndProviderOrder() {
        val art = "https://is1-ssl.mzstatic.com/image/thumb/album/600x600bb.jpg"
        val good = com.music.bitchord.data.model.ShelfItem("Release","Artist",art,null,"apple_music_web:br:album:123")
        fun state(item: com.music.bitchord.data.model.ShelfItem) =
            com.music.bitchord.data.model.UiState.Success(listOf(com.music.bitchord.data.model.HomeShelf("Lançamentos",listOf(item))))
        val covers = com.music.bitchord.ui.amsExploreGenreCoverMap(mapOf(
            "soul" to state(good),
            "metal" to state(good.copy(browseId="apple_music_web:us:album:123")),
            "reggae" to state(good.copy(browseId="itunes:br:album:123")),
            "blues" to state(good.copy(thumbnailUrl="https://evil.example/cover.jpg")),
            "unknown" to state(good),
        ))
        assertEquals(mapOf("soul" to art),covers)
        val ams = com.music.bitchord.data.providers.BuiltInProviderKeys.APPLE_MUSIC_WEB
        val itunes = com.music.bitchord.data.providers.BuiltInProviderKeys.ITUNES
        val alternate = mapOf("soul" to "other-cover","metal" to "verified-metal")
        assertEquals(art,com.music.bitchord.ui.orderedAppleExploreGenreCovers(
            listOf(ams,itunes),alternate,covers)["soul"])
        assertEquals("other-cover",com.music.bitchord.ui.orderedAppleExploreGenreCovers(
            listOf(itunes,ams),alternate,covers)["soul"])
        assertTrue(com.music.bitchord.ui.orderedAppleExploreGenreCovers(
            emptyList(),alternate,covers).isEmpty())
    }

}
