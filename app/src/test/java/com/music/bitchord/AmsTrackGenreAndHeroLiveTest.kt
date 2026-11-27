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
    @Test fun nativeChartHeroReturnsActualImage(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val ref = requireNotNull(resolveAppleMusicWebDailyChart(catalog.dailyChartDirectory(),"br"))
        val chart = (withTimeout(120_000) { catalog.dailyChart("br",ref) } as AppleMusicWebOutcome.Complete).value
        val url = requireNotNull(chart.playlist.editorialTallHeroUrl)
        val uri = java.net.URI(url)
        assertTrue(uri.scheme == "https" && uri.host.endsWith(".mzstatic.com"))
        val conn = uri.toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            assertEquals(200,conn.responseCode)
            assertTrue(conn.contentType.orEmpty().startsWith("image/"))
            val bytes = conn.inputStream.use { it.readNBytes(12) }
            assertTrue(bytes.size >= 12 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 255) == 216)
            println("AMS_HERO|scope=native_top100_br|http=200|jpeg=true")
        } finally { conn.disconnect() }
    }

    @Test fun artistWebMotionEvidence(): Unit = runBlocking {
        assumeTrue(System.getenv("AMS_TRACK_HERO_LIVE") == "1")
        val response = withTimeout(60_000) {
            KtorAppleMusicWebExchange().get(AppleMusicWebHost.MUSIC,
                "/br/artist/illit/1734551937",emptyMap(),
                mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"),
                8_000_000)
        }
        assertEquals(200,response.status)
        val html = response.body
        val marker = html.indexOf("id=\"serialized-server-data\"")
        assertTrue("Missing artist hydration",marker >= 0)
        val start = html.indexOf('>',marker) + 1
        val end = html.indexOf("</script>",start)
        assertTrue(start > 0 && end > start)
        val payload = Json.parseToJsonElement(html.substring(start,end))
        fun header(value: JsonElement): JsonObject? = when(value) {
            is JsonObject -> if ("artistLogo" in value) value else value.values.firstNotNullOfOrNull(::header)
            is JsonArray -> value.firstNotNullOfOrNull(::header)
            else -> null
        }
        val artist = requireNotNull(header(payload))
        val dictionary = (artist["videoArtwork"] as? JsonObject)?.get("dictionary") as? JsonObject
        val keys = dictionary?.keys.orEmpty()
        val motion = (dictionary?.get("motionArtistSquare1x1") as? JsonObject)?.get("video") as? JsonPrimitive
        val uri = motion?.contentOrNull?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val url = com.music.bitchord.data.canvas.appleEditorialMotionUrl(motion?.contentOrNull)
        val fields = (dictionary?.get("motionArtistSquare1x1") as? JsonObject)?.keys.orEmpty()
        println("AMS_HERO|scope=illit_web|motionKeys=${keys.sorted().joinToString(",")}|clipFields=${fields.sorted().joinToString(",")}|host=${uri?.host}|extension=${uri?.path?.substringAfterLast('.')}|nativeMotion=${url != null}")
        assertNotNull("No compatible official artist motion",url)
        val conn = java.net.URI(requireNotNull(url)).toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            assertEquals(200,conn.responseCode)
            val prefix = conn.inputStream.use { it.readNBytes(128) }
            val hls = String(prefix,Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
            val mp4 = prefix.size >= 8 && String(prefix.copyOfRange(4,8),Charsets.US_ASCII) == "ftyp"
            assertTrue("Invalid official motion response",hls || mp4)
            println("AMS_HERO|scope=illit_motion_bytes|http=200|hls=$hls|mp4=$mp4")
        } finally { conn.disconnect() }
    }

    private fun assertMotionBytes(url: String) {
        assertEquals(url,com.music.bitchord.data.canvas.appleEditorialMotionUrl(url))
        val conn = java.net.URI(url).toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            assertEquals(200,conn.responseCode)
            val bytes = conn.inputStream.use { it.readNBytes(128) }
            val hls = String(bytes,Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
            val mp4 = bytes.size >= 8 && String(bytes.copyOfRange(4,8),Charsets.US_ASCII) == "ftyp"
            assertTrue("Invalid editorial motion payload",hls || mp4)
        } finally { conn.disconnect() }
    }

    @Test fun exactAlbumMotionFromCatalog(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val samples = listOf("SUPER REAL ME" to "ILLIT", "HIT ME HARD AND SOFT" to "Billie Eilish",
            "Midnights" to "Taylor Swift")
        var verified = 0
        for ((title,artist) in samples) {
            val hits = withTimeout(120_000) { catalog.search(AppleMusicWebKind.ALBUM,"$artist $title","br") }
            val exact = hits.items.filter { it.title.equals(title,true) && it.artistName.equals(artist,true) &&
                it.storefront == "br" && it.kind == AppleMusicWebKind.ALBUM }.distinctBy { it.id }.singleOrNull()
            if (exact == null) {
                println("AMS_HERO|scope=album|title=$title|exactIdentity=false")
                continue
            }
            val album = when(val outcome = withTimeout(120_000) { catalog.albumOutcome(exact.id,"br") }) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                is AppleMusicWebOutcome.Partial -> outcome.value
                else -> continue
            }
            assertEquals(exact.scopedId,album.identity.scopedId)
            val url = album.identity.editorialMotionUrl
            if (url != null) {
                assertMotionBytes(url)
                verified++
            }
            println("AMS_HERO|scope=album|title=$title|id=${exact.id}|motion=${url != null}|tall=${album.identity.editorialTallHeroUrl != null}")
        }
        assertTrue("No exact album with valid editorial motion in sample",verified > 0)
    }

    @Test fun playlistMotionFieldInventory(): Unit = runBlocking {
        val transport = transport()
        val catalog = AppleMusicWebCatalog(transport)
        val chart = requireNotNull(resolveAppleMusicWebDailyChart(catalog.dailyChartDirectory(),"br"))
        val raw = withTimeout(120_000) { transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
            "/v1/catalog/br/playlists/${chart.id}",
            mapOf("include" to "tracks","extend" to "editorialArtwork,editorialVideo")) }
        val identity = Json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray?.singleOrNull()?.jsonObject
            ?: error("Missing exact playlist")
        assertEquals(chart.id,identity["id"]?.jsonPrimitive?.content)
        assertEquals("playlists",identity["type"]?.jsonPrimitive?.content)
        val attr = identity["attributes"]?.jsonObject ?: error("Missing attributes")
        val video = attr["editorialVideo"] as? JsonObject
        println("AMS_HERO|scope=playlist_motion_api|id=${chart.id}|fields=${video?.keys.orEmpty().sorted().joinToString(",")}")
        val url = appleMusicWebAlbumMotion(video,true)?.first
        if (url != null) assertMotionBytes(url)
        println("AMS_HERO|scope=playlist_motion_api|motion=${url != null}")
    }

}
