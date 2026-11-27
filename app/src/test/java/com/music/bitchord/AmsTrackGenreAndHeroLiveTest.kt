package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.amsStyleSongAlbumId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Discovery only: measure exact track labels, never infer a genre from search ranking.
 * Artwork inventory logs field names and counts, not credentials or signed URLs. */
class AmsTrackGenreAndHeroLiveTest {
    private fun transport(): AppleMusicWebPolicyTransport {
        assumeTrue(System.getenv("AMS_TRACK_HERO_LIVE") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val caps = setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY, ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.CHART_MOST_PLAYED)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(key,true,0)) },
        ))
        return AppleMusicWebPolicyTransport(admission=RateLimitedProviderTransport { gate })
    }

    @Test fun allRegisteredStylesFromSongMetadata(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val styles = amsEditorialStyleParents.keys.toList()
        assertTrue(styles.isNotEmpty())
        val gaps = mutableListOf<String>()
        for (tag in styles) {
            val first = withTimeout(120_000) { catalog.search(AppleMusicWebKind.SONG,tag,"br",limit=25) }
            val second = first.nextCursor?.let { cursor ->
                withTimeout(120_000) { catalog.search(AppleMusicWebKind.SONG,tag,"br",page=2,limit=25,continuation=cursor) }
            }
            val songs = (first.items + second?.items.orEmpty()).distinctBy { it.scopedId }
            val exact = songs.filter { it.kind == AppleMusicWebKind.SONG && it.storefront == "br" &&
                it.genreNames.any { genre -> appleMusicWebGenreNameMatches(genre,tag) } }
            val ids = exact.mapNotNull { amsStyleSongAlbumId(it,"br") }.distinct()
            val albums = ids.chunked(25).flatMap { batch ->
                withTimeout(120_000) { catalog.linkedAlbumMetadata(batch,"br") }
            }.filter { it.kind == AppleMusicWebKind.ALBUM && it.storefront == "br" && it.id in ids }
                .distinctBy { it.id }
            val labels = songs.flatMap { it.genreNames }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)
            println("AMS_TRACK_GENRE|tag=$tag|songs=${songs.size}|exact=${exact.size}|links=${ids.size}|albums=${albums.size}|labels=${labels.joinToString { it.key + ":" + it.value }}")
            if (albums.size < 6) gaps += tag
        }
        println("AMS_TRACK_GENRE|scope=summary|styles=${styles.size}|gaps=${gaps.size}")
        assertTrue("Insufficient track-label coverage: " + gaps.joinToString(),gaps.isEmpty())
    }

    private fun inventory(raw: String, id: String, type: String, label: String) {
        val root = Json.parseToJsonElement(raw).jsonObject
        val data = root["data"]?.jsonArray?.singleOrNull()?.jsonObject
            ?: error("Missing exact editorial resource")
        assertEquals(id,data["id"]?.jsonPrimitive?.content)
        assertEquals(type,data["type"]?.jsonPrimitive?.content)
        val attr = data["attributes"]?.jsonObject ?: error("Missing attributes")
        val art = attr["editorialArtwork"]
        fun fields(value: JsonElement?, path: String): List<String> = when(value) {
            is JsonObject -> value.entries.flatMap { (key,child) -> fields(child,"$path.$key") }
            is JsonArray -> value.flatMap { fields(it,"$path[]") }
            is JsonPrimitive -> listOf(path)
            else -> emptyList()
        }
        val keys = fields(art,"editorialArtwork").distinct().sorted()
        println("AMS_HERO|scope=$label|attributes=${attr.keys.sorted().joinToString(",")}|editorialFields=${keys.joinToString(",")}")
    }

    @Test fun officialEditorialArtworkFields(): Unit = runBlocking {
        val transport = transport()
        // Public BR artist identity verified at music.apple.com/br/artist/illit/1734551937.
        val artistId = "1734551937"
        inventory(withTimeout(120_000) { transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/catalog/br/artists/$artistId",mapOf("extend" to "editorialArtwork")) },
            artistId,"artists","illit")
    }

    @Test fun officialPlaylistArtworkFields(): Unit = runBlocking {
        val transport = transport()
        val catalog = AppleMusicWebCatalog(transport)
        val chart = requireNotNull(resolveAppleMusicWebDailyChart(catalog.dailyChartDirectory(),"br"))
        inventory(withTimeout(120_000) { transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/catalog/br/playlists/${chart.id}",mapOf("include" to "tracks","extend" to "editorialArtwork")) },
            chart.id,"playlists","top100_br")
    }
}
