package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AmsCountryChartIntegrationTest {
    private val ams = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val apple = BuiltInProviderKeys.ITUNES
    private val charts = ProviderCapability.CHART_MOST_PLAYED
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private fun policy(vararg order: ProviderKey) = ProviderCapabilityPolicy(
        listOf(ProviderDescriptor(ams,setOf(charts,editorial),ProviderReadiness.READY),
            ProviderDescriptor(apple,setOf(charts),ProviderReadiness.READY)),
        mapOf(charts to order.mapIndexed { i,key -> ProviderChoice(key,true,i) }),
    )
    private val territory = knownAppleChartTerritory("br")!!
    private val song = AppleMusicWebItem("1",AppleMusicWebKind.SONG,"br","Song",
        artistName="Artist",storeUrl="https://music.apple.com/br/song/song/1")

    @Test fun sourceRankGapsAndNativeIdentityArePreserved() {
        val invalid = song.copy(id="2",storeUrl="https://music.apple.com/us/song/song/2")
        val third = song.copy(id="3",storeUrl="https://music.apple.com/br/song/song/3")
        val result = projectAmsCountryChart(territory,false,listOf(song,invalid,third),123L)
            as AmsCountryChartResult.Songs
        assertEquals(listOf(1,3),result.chart.songs.map { it.position })
        assertEquals(listOf(song.scopedId,third.scopedId),result.chart.songs.map { it.songBrowseId })
        assertEquals(3,result.chart.sourceRowCount)
        assertNull(projectAmsCountryChart(territory,false,listOf(song,song),123L))
        assertNull(projectAmsCountryChart(territory,false,listOf(song.copy(storefront="us")),123L))
        val album = song.copy(kind=AppleMusicWebKind.ALBUM,
            storeUrl="https://music.apple.com/br/album/album/1")
        val albums = projectAmsCountryChart(territory,true,listOf(album),123L) as AmsCountryChartResult.Albums
        assertEquals(album.scopedId,albums.chart.albums.single().albumBrowseId)
    }

    @Test fun providerPriorityAndRouteDoNotAliasItunes() {
        val gate = ProviderPolicyGate(policy(ams,apple))
        assertEquals(ams,preferredAppleChartProvider(gate))
        val shelf = canonicalCountryChartShelf(true,provider=ams)!!
        assertEquals(20,shelf.items.size)
        assertEquals(territory,parseCanonicalAppleChartRoute(shelf.items[0].browseId,ams,false))
        assertNull(parseCanonicalAppleChartRoute(shelf.items[0].browseId,apple,false))
        assertNull(parseCanonicalAppleChartRoute(shelf.items[1].browseId,ams,false))
        gate.replace(policy(apple,ams))
        assertEquals(apple,preferredAppleChartProvider(gate))
        gate.replace(policy())
        assertNull(preferredAppleChartProvider(gate))
    }

    @Test fun chartSessionFollowsUpstreamCursorAndRequiresOwnCapability(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(ams))
        val requests = mutableListOf<String?>()
        val native = AppleMusicWebNativeCatalogSession({gate}) { g ->
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebTransport { capability,path,args ->
                assertEquals(charts,capability)
                assertEquals("/v1/catalog/br/charts",path)
                requests += args["offset"]
                val id = if (args["offset"] == "7") "3" else "1"
                val next = if (id == "1") ",\"next\":\"/v1/catalog/br/charts?types=songs&chart=most-played&offset=7\"" else ""
                """{"results":{"songs":[{"chart":"most-played","data":[{"id":"$id","type":"songs",
                  "attributes":{"name":"Song $id","artistName":"Artist","url":"https://music.apple.com/br/song/song/$id"}}]$next}]}}"""
            }),g)
        }
        val result = (native.countryChart("br",false) as ProviderCallResult.Success).value as AmsCountryChartResult.Songs
        assertEquals(listOf(null,"7"),requests)
        assertEquals(listOf(1,2),result.chart.songs.map { it.position })
        assertEquals(listOf(1L,3L),result.chart.songs.map { it.songId })
        gate.replace(policy())
        assertEquals(ProviderCallResult.Denied,native.countryChart("br",false))
        assertEquals(2,requests.size)
    }

    @Test fun controllerDoesNotPublishRevokedChartSnapshots(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(ams))
        val result = projectAmsCountryChart(territory,false,listOf(song),123L)!!
        val controller = AmsCountryChartController(this,{gate},{ _,_ -> ProviderCallResult.Success(result) })
        val key = AmsCountryChartKey("br",false)
        controller.load("br",false); yield()
        assertTrue(controller.snapshots.value[key] is UiState.Success)
        assertTrue(controller.isCurrent(key))
        gate.replace(policy())
        assertFalse(controller.isCurrent(key))
        controller.purgeRevoked()
        assertTrue(controller.snapshots.value.isEmpty())
    }
}
