package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.appleChartTerritories
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import com.music.bitchord.ui.screens.amsCollectionRecordingLabels
import com.music.bitchord.ui.screens.appleMusicWebVerifiedRouteUrl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live checks for the post-physical-smoke changes only. The earlier artist,
 * search and album chart market matrices are intentionally not replayed. */
class AmsPostSmokeCorrectionsLiveTest {
    private fun session(vararg caps: ProviderCapability): AppleMusicWebNativeCatalogSession {
        assumeTrue(System.getenv("AMS_POST_SMOKE_LIVE") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,caps.toSet(),ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(key,true,0)) },
        ))
        return AppleMusicWebNativeCatalogSession({gate}) { owner ->
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
                admission=RateLimitedProviderTransport { gate })),owner)
        }
    }

    private fun <T> result(value: ProviderCallResult<T>): T =
        (value as? ProviderCallResult.Success)?.value ?: error("Native provider read failed: ${value::class.simpleName}")

    private fun <T> content(outcome: AppleMusicWebOutcome<T>): T = when(outcome) {
        is AppleMusicWebOutcome.Complete -> outcome.value
        is AppleMusicWebOutcome.Partial -> outcome.value
        else -> error("Source outcome unavailable")
    }

    @Test fun dailyPlaylistDirectoryAndChartOnlyDetail(): Unit = runBlocking {
        val native = session(ProviderCapability.CHART_MOST_PLAYED)
        val tiles = result(withTimeout(180_000) { native.dailyChartTiles() })
        assertEquals(appleChartTerritories.map { it.code },tiles.map { it.country })
        assertTrue(tiles.all { it.card.thumbnailUrl != null && it.card.videoId == null })
        assertEquals(10,canonicalAmsChartShelf(tiles).items.size)
        println("AMS_POST_SMOKE|scope=directory|countries=${tiles.size}|covers=${tiles.count { it.card.thumbnailUrl != null }}")
        for (market in listOf("br","au")) {
            val tile = tiles.single { it.country == market }
            val outcome = result(withTimeout(150_000) { native.dailyChartSharedDetail(tile) })
            val detail = content(outcome)
            assertTrue(outcome is AppleMusicWebOutcome.Complete)
            assertEquals(BrowseType.PLAYLIST,detail.page.type)
            assertEquals(tile.card.browseId,detail.page.browseId)
            assertNotNull(detail.page.thumbnailUrl)
            assertEquals(100,detail.metadataTracks.size)
            assertTrue(detail.metadataTracks.all { amsSongStorefront(it.key) == market })
            val labels = amsCollectionRecordingLabels(detail.page.browseId,detail.metadataTracks,detail.notice == null)
            assertEquals(detail.metadataTracks.map { it.key }.distinct(),labels?.map { it.recordingId })
            println("AMS_POST_SMOKE|scope=playlist|market=$market|tracks=${detail.metadataTracks.size}|labels=${labels?.size}|cover=true")
        }
        val album = (result(withTimeout(120_000) { native.countryChart("br",true) }) as AmsCountryChartResult.Albums).chart
        val collection = requireNotNull(projectAmsAlbumChartCollection(album))
        assertEquals(album.albums.map { it.albumBrowseId },collection.shelf.items.map { it.browseId })
        assertEquals(album.albums.map { it.position },collection.shelf.items.map { collection.ranks[it.browseId] })
        assertTrue(collection.shelf.items.all { it.videoId == null })
        println("AMS_POST_SMOKE|scope=album_grid|market=br|rows=${collection.shelf.items.size}|covers=${collection.shelf.items.count { it.thumbnailUrl != null }}")
    }

    @Test fun homeAdditionalRailsUseExactTypes(): Unit = runBlocking {
        val native = session(ProviderCapability.EDITORIAL_DISCOGRAPHY)
        val start = System.nanoTime()
        val page = content(result(withTimeout(240_000) { native.home("br") }))
        val expected = mapOf("Playlists atualizadas" to AppleMusicWebKind.PLAYLIST,
            "Mais tocadas por cidade" to AppleMusicWebKind.PLAYLIST,
            "Agora em turnê" to AppleMusicWebKind.PLAYLIST,"Em breve" to AppleMusicWebKind.ALBUM)
        val extra = page.shelves.filter { it.title in expected }
        assertTrue("Insufficient additional Home rails",extra.size >= 2)
        for (shelf in extra) {
            assertTrue(shelf.items.isNotEmpty() && shelf.items.all {
                val route = AppleMusicWebNativeRoute.parse(it.browseId)
                route?.kind == expected[shelf.title] && route?.storefront == "br" && it.videoId == null
            })
            println("AMS_POST_SMOKE|scope=home_rail|heading=${shelf.title}|rows=${shelf.items.size}")
        }
        val request = page.pending.filterIsInstance<AmsEditorialRequest.Room>().firstOrNull { it.homeKind?.kind == AppleMusicWebKind.PLAYLIST }
        if (request != null) {
            val next = content(result(withTimeout(120_000) { native.nextEditorial(request) }))
            assertTrue(next.shelves.flatMap { it.items }.all {
                AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.PLAYLIST && it.videoId == null
            })
            println("AMS_POST_SMOKE|scope=home_playlist_next|rows=${next.shelves.sumOf { it.items.size }}")
        }
        println("AMS_POST_SMOKE|scope=home|rails=${page.shelves.size}|elapsed_ms=${(System.nanoTime()-start)/1_000_000}")
    }

    @Test fun dedicatedCuratorExtraRailsAndNativeAlbumDetail(): Unit = runBlocking {
        val native = session(ProviderCapability.EDITORIAL_DISCOGRAPHY,ProviderCapability.CHART_MOST_PLAYED)
        for (tag in listOf("k-pop","metal")) {
            val page = content(result(withTimeout(240_000) { native.genre(tag,"br") }))
            val extras = page.shelves.filter { it.title !in setOf("Lançamentos","Playlists") }
            assertTrue("No extra rail for $tag",extras.isNotEmpty())
            assertTrue(extras.flatMap { it.items }.all {
                AppleMusicWebNativeRoute.parse(it.browseId)?.storefront == "br" && it.videoId == null
            })
            println("AMS_POST_SMOKE|scope=genre|tag=$tag|rails=${page.shelves.size}|extra=${extras.size}")
        }
        val chart = (result(withTimeout(120_000) { native.countryChart("br",true) }) as AmsCountryChartResult.Albums).chart
        val card = requireNotNull(projectAmsAlbumChartCollection(chart)).shelf.items.first()
        val id = requireNotNull(card.browseId)
        val detail = content(result(withTimeout(180_000) { native.sharedDetail(id) }))
        assertEquals(id,detail.page.browseId)
        assertEquals(BrowseType.ALBUM,detail.page.type)
        assertTrue(detail.metadataTracks.isNotEmpty())
        assertTrue(detail.metadataTracks.all { amsSongStorefront(it.key) == "br" })
        println("AMS_POST_SMOKE|scope=album_detail|tracks=${detail.metadataTracks.size}")
    }
}
