package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.appleChartTerritories
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.projectAppleMusicWebGenrePlaylistRoom
import com.music.bitchord.ui.projectAppleMusicWebHomeRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Targeted physical-smoke findings only. Discover source-owned rooms and
 * validate missing artwork without replaying the approved artist/album matrix. */
class AmsPhysicalSmokeDiscoveryLiveTest {
    private val editorial = ProviderCapability.EDITORIAL_DISCOGRAPHY
    private val chart = ProviderCapability.CHART_MOST_PLAYED
    private fun transport(): AppleMusicWebTransport {
        assumeTrue(System.getenv("AMS_SMOKE_DISCOVERY") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val caps = setOf(editorial,chart)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(key,true,0)) },
        ))
        return AppleMusicWebPolicyTransport(admission=RateLimitedProviderTransport { gate })
    }
    private fun safeLabel(s: String) = s.replace(Regex("[\\r\\n|]")," ").take(120)
    private suspend fun sample(transport: AppleMusicWebTransport,scope: String,room: AppleMusicWebRoomRef) {
        val path = "/v1/editorial/${room.storefront}/rooms/${room.id}/contents"
        try {
            val body = Json.parseToJsonElement(transport.get(editorial,path,mapOf("limit" to "8"))).jsonObject
            val data = body["data"] as? JsonArray ?: error("No data")
            val kinds = data.mapNotNull {
                (it as? JsonObject)?.get("type")?.let { x -> (x as? JsonPrimitive)?.contentOrNull }
            }.groupingBy { it }.eachCount().toSortedMap()
            println("AMS_DISCOVERY|scope=$scope|room=${safeLabel(room.title)}|rows=${data.size}|kinds=${kinds.entries.joinToString(",") { "${safeLabel(it.key)}:${it.value}" }}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            println("AMS_DISCOVERY|scope=$scope|room=${safeLabel(room.title)}|status=unavailable")
        }
    }
    @Test fun sourceRoomInventory(): Unit = runBlocking {
        val transport = transport()
        val catalog = AppleMusicWebCatalog(transport)
        val home = catalog.browseRooms("br")
        assertTrue(home.isNotEmpty())
        for (room in home.distinctBy { it.id }.take(20)) sample(transport,"home",room)
        // Inspect four representative curators (including shared Soul/Funk).
        // This is a new inventory, not the already-approved release matrix.
        for (tag in listOf("k-pop","metal","pop","soul")) {
            val id = requireNotNull(appleMusicWebPublicCuratorReference("br",tag))
            val result = catalog.appleCurator(id,"br") as? AppleMusicWebOutcome.Complete
            val curator = result?.value
            assertNotNull(curator)
            assertTrue(appleMusicWebCuratorMatches(curator!!.identity,"br",tag))
            println("AMS_DISCOVERY|scope=curator|tag=$tag|rooms=${curator.rooms.size}")
            for (room in curator.rooms.distinctBy { it.id }.take(16)) sample(transport,"genre_$tag",room)
        }
        val newRoom = resolveAppleMusicWebHomeRoom(home,"br",AppleMusicWebHomeShelf.BEST_NEW_SONGS)
        assertNotNull("Missing exact Best New Songs room",newRoom)
        val newSongs = projectAppleMusicWebHomeRoom(newRoom!!,
            catalog.roomContents(newRoom,AppleMusicWebKind.SONG),"br",AppleMusicWebHomeShelf.BEST_NEW_SONGS)
        println("AMS_DISCOVERY|scope=home_new_songs|rows=${newSongs?.items?.size ?: 0}")
        assertTrue(newSongs?.items?.isNotEmpty() == true)
    }

    @Test fun kpopPlaylistArtwork(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val curator = (catalog.appleCurator(requireNotNull(appleMusicWebPublicCuratorReference("br","k-pop")),"br")
            as AppleMusicWebOutcome.Complete).value
        val rooms = curator.rooms.filter(::isAppleMusicWebCuratorPlaylistRoom)
        assertTrue(rooms.isNotEmpty())
        val cards = rooms.take(2).flatMap { room ->
            projectAppleMusicWebGenrePlaylistRoom(curator.identity,"k-pop",
                catalog.roomContents(room,AppleMusicWebKind.PLAYLIST))?.items.orEmpty()
        }.distinctBy { it.browseId }
        val withArt = cards.count { it.thumbnailUrl != null }
        println("AMS_DISCOVERY|scope=kpop_playlist_art|rows=${cards.size}|with_art=$withArt|missing=${cards.size-withArt}")
        assertTrue(cards.isNotEmpty())
        assertEquals("K-pop editorial playlists still missing artwork",cards.size,withArt)
    }

    @Test fun actualDailyPlaylistIdentityAndArtwork(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val directory = catalog.dailyChartDirectory()
        for (territory in appleChartTerritories) {
            val entry = resolveAppleMusicWebDailyChart(directory,territory.code)
            println("AMS_DISCOVERY|scope=daily_playlist|market=${territory.code}|mapped=${entry != null}|directory_art=${appleMusicWebArtworkUrl(entry?.artworkUrl) != null}")
        }
        // Existing live evidence covers other regions; sample the reported
        // Brazil chart and newly mapped Australia rather than replay all.
        for (market in listOf("br","au")) {
            val entry = resolveAppleMusicWebDailyChart(directory,market)
            if (entry == null) {
                println("AMS_DISCOVERY|scope=daily_detail|market=$market|status=unmapped")
                continue
            }
            val outcome = catalog.dailyChart(market,entry)
            val data = when(outcome) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                is AppleMusicWebOutcome.Partial -> outcome.value
                else -> null
            }
            println("AMS_DISCOVERY|scope=daily_detail|market=$market|rows=${data?.songs?.size ?: 0}|art=${appleMusicWebArtworkUrl(data?.playlist?.artworkUrl) != null}|complete=${outcome is AppleMusicWebOutcome.Complete}")
            assertNotNull(data)
        }
        assertNotNull(resolveAppleMusicWebDailyChart(directory,"br"))
    }
}
