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

    @Test fun nativeChartMotionReachesSharedDetail(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val ref = requireNotNull(resolveAppleMusicWebDailyChart(catalog.dailyChartDirectory(),"br"))
        val chart = (withTimeout(120_000) { catalog.dailyChart("br",ref) } as AppleMusicWebOutcome.Complete).value
        val identity = chart.playlist
        val detail = requireNotNull(com.music.bitchord.ui.screens.appleMusicWebAsSharedDetail(
            identity.scopedId,com.music.bitchord.ui.AppleMusicWebNativeDetail.Playlist(
                AppleMusicWebPlaylist(identity,chart.songs))))
        val url = requireNotNull(detail.releaseMotion?.url)
        assertMotionBytes(url)
        assertNotNull(detail.releaseHeroUrl)
        println("AMS_HERO|scope=chart_native_motion|id=${identity.id}|hero=true|motion=true")
    }

    @Test fun remainingStyleTaxonomyAndEditorialInventory(): Unit = runBlocking {
        val catalog = AppleMusicWebCatalog(transport())
        val tags = listOf("alternative rock","trap","ambient techno","drill","art pop",
            "progressive rock","boom bap","dark ambient","post-punk","post-rock","drone")
        val genres = withTimeout(120_000) { catalog.genres("br") }
        assertTrue(genres.isNotEmpty())
        for (tag in tags) {
            val exact = genres.filter { appleMusicWebGenreNameMatches(it.title,tag) }
            println("AMS_STYLE_DISCOVERY|tag=$tag|native=${exact.joinToString { it.id + ":" + it.title }}")
            for (query in amsStyleDiscoveryQueries(tag)) {
                val page = withTimeout(120_000) { catalog.search(AppleMusicWebKind.PLAYLIST,query,"br",limit=50) }
                val rows = page.items.filter { it.playlistType == "editorial" && it.storefront == "br" }
                println("AMS_STYLE_DISCOVERY|tag=$tag|query=$query|editorial=${rows.size}")
                for (row in rows.take(20)) {
                    println("AMS_STYLE_DISCOVERY|tag=$tag|id=${row.id}|title=${row.title}|credit=${row.artistName}|accepted=${amsVerifiedSearchedStylePlaylist(row,tag,"br")}")
                }
            }
        }
    }

    /** Public BR album pages are exact fixtures; identically titled editions
     * remain separate IDs. Distinguish absent data from an unsupported field
     * shape or a failed aggregate extension request. */
    @Test fun exactAlbumMotionFieldInventory(): Unit = runBlocking {
        val transport = transport()
        val catalog = AppleMusicWebCatalog(transport)
        val samples = listOf(
            Triple("1734500886","SUPER REAL ME - EP","ILLIT"),
            Triple("1645937249","Midnights","Taylor Swift"),
            Triple("1645937456","Midnights","Taylor Swift"),
            Triple("1739659134","HIT ME HARD AND SOFT","Billie Eilish"),
        )
        var verified = 0
        for ((id,title,artist) in samples) {
            val raw = withTimeout(120_000) {
                transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,
                    "/v1/catalog/br/albums/$id",mapOf("extend" to "editorialVideo"))
            }
            val resource = Json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray?.singleOrNull()?.jsonObject
                ?: error("Missing exact album $id")
            assertEquals(id,resource["id"]?.jsonPrimitive?.content)
            assertEquals("albums",resource["type"]?.jsonPrimitive?.content)
            val attr = resource["attributes"]?.jsonObject ?: error("Missing attributes")
            assertEquals(title,attr["name"]?.jsonPrimitive?.content)
            assertEquals(artist,attr["artistName"]?.jsonPrimitive?.content)
            val video = attr["editorialVideo"] as? JsonObject
            val fields = video?.entries.orEmpty().joinToString(",") { (key,value) ->
                key + ":" + (value as? JsonObject)?.keys.orEmpty().sorted().joinToString("+")
            }
            val direct = appleMusicWebAlbumMotion(video,false)?.first
            val album = when(val outcome = withTimeout(120_000) { catalog.albumOutcome(id,"br") }) {
                is AppleMusicWebOutcome.Complete -> outcome.value
                is AppleMusicWebOutcome.Partial -> outcome.value
                else -> error("Exact album unavailable $id")
            }
            assertEquals(id,album.identity.id)
            assertEquals("br",album.identity.storefront)
            val aggregate = album.identity.editorialMotionUrl
            if (direct != null) assertMotionBytes(direct)
            if (aggregate != null) assertMotionBytes(aggregate)
            if (direct != null || aggregate != null) verified++
            println("AMS_HERO|scope=album_inventory|id=$id|title=$title|fields=$fields|direct=${direct != null}|aggregate=${aggregate != null}|hero=${album.identity.editorialTallHeroUrl != null}")
        }
        println("AMS_HERO|scope=album_inventory_summary|verified=$verified|sample=${samples.size}")
        assertTrue("No exact BR album motion verified",verified > 0)
    }

    /** Diagnostic only. Search terms and textual mentions do not admit a style.
     * Exact US public examples are probes for BR availability, not runtime seeds.
     * A known negative (It Was Written) mentions an earlier boom-bap record. */
    @Test fun editorialStyleNotesAvailability(): Unit = runBlocking {
        val transport = transport()
        val catalog = AppleMusicWebCatalog(transport)
        val probes = mapOf(
            "art pop" to listOf("6773848999","1814969020"),
            "ambient techno" to listOf("1753966586","1679051624","1614357726"),
            "boom bap" to listOf("1485466984","505493917","1396290965","211507540","1724856762")
        )
        fun noteText(value: JsonElement?): String = when (value) {
            is JsonObject -> value.values.joinToString(" ") { noteText(it) }
            is JsonPrimitive -> if (value.isString) value.content else ""
            else -> ""
        }
        for ((tag, fixtures) in probes) {
            val search = withTimeout(120_000) {
                catalog.search(AppleMusicWebKind.ALBUM,tag,"br",limit=25)
            }
            val ids = (fixtures + search.items.filter { it.kind == AppleMusicWebKind.ALBUM &&
                it.storefront == "br" }.map { it.id }).distinct()
            val phrase = Regex(tag.split(" ").joinToString("[-\\\\s]+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
            var returned = 0
            for (batch in ids.chunked(25)) {
                val raw = withTimeout(120_000) {
                    transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,"/v1/catalog/br/albums",
                        mapOf("ids" to batch.joinToString(","),"extend" to "plainEditorialNotes"))
                }
                val root = Json.parseToJsonElement(raw).jsonObject
                assertFalse("Catalog error envelope", "errors" in root)
                val rows = root["data"]?.jsonArray ?: error("Missing album batch")
                val seen = hashSetOf<String>()
                for (entry in rows) {
                    val row = entry.jsonObject
                    val id = requireNotNull(row["id"]?.jsonPrimitive?.contentOrNull)
                    assertTrue(id in batch && seen.add(id))
                    assertEquals("albums",row["type"]?.jsonPrimitive?.content)
                    val attr = row["attributes"]?.jsonObject ?: error("Missing attributes")
                    val notes = noteText(attr["plainEditorialNotes"]) + " " + noteText(attr["editorialNotes"])
                    val labels = (attr["genreNames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val exact = labels.any { appleMusicWebGenreNameMatches(it,tag) }
                    println("AMS_STYLE_NOTES|tag=$tag|id=$id|fixture=${id in fixtures}|plain=${attr["plainEditorialNotes"] != null}|editorial=${attr["editorialNotes"] != null}|mention=${phrase.containsMatchIn(notes)}|exactLabel=$exact")
                    returned++
                }
                println("AMS_STYLE_NOTES|tag=$tag|requested=${batch.size}|returned=${rows.size}|missingFixtures=${fixtures.filter { it in batch && it !in seen }.joinToString(",")}")
            }
            assertTrue("No BR album rows for $tag",returned > 0)
        }
    }

    /** Compare materialized genre relationships against the album's own labels.
     * The fixed examples are diagnostic controls, never production candidates.
     * Search ranking, bare relationship IDs and parent labels are not evidence. */
    @Test fun structuredStyleRelationshipInventory(): Unit = runBlocking {
        val transport = transport()
        val catalog = AppleMusicWebCatalog(transport)
        val probes = linkedMapOf(
            "art pop" to listOf("6773848999","1814969020"),
            "ambient techno" to listOf("1753966586","1679051624","1614357726"),
            "boom bap" to listOf("1485466984","505493917","1396290965","211507540","1724856762"),
            "jazz fusion" to emptyList<String>()
        )
        val nativeGenres = withTimeout(120_000) { catalog.genres("br") }
        assertTrue(nativeGenres.isNotEmpty())
        val nativeById = nativeGenres.associateBy { it.id }
        for ((tag, fixtures) in probes) {
            val search = withTimeout(120_000) {
                catalog.search(AppleMusicWebKind.ALBUM,tag,"br",limit=25)
            }
            val ids = (fixtures + search.items.filter { it.kind == AppleMusicWebKind.ALBUM &&
                it.storefront == "br" }.map { it.id }).distinct().take(25)
            assertTrue(ids.isNotEmpty())
            val raw = withTimeout(120_000) {
                transport.get(ProviderCapability.EDITORIAL_DISCOGRAPHY,"/v1/catalog/br/albums",
                    mapOf("ids" to ids.joinToString(","),"include" to "genres"))
            }
            val root = Json.parseToJsonElement(raw).jsonObject
            assertFalse("Catalog error envelope", "errors" in root)
            val rows = root["data"]?.jsonArray ?: error("Missing album batch")
            assertTrue("No rows for $tag",rows.isNotEmpty())
            val seen = hashSetOf<String>()
            var exactAttributes = 0
            var exactRelationships = 0
            var additional = 0
            var unresolved = 0
            for (entry in rows) {
                val row = entry.jsonObject
                val id = requireNotNull(row["id"]?.jsonPrimitive?.contentOrNull)
                assertTrue(id in ids && seen.add(id))
                assertEquals("albums",row["type"]?.jsonPrimitive?.content)
                val attr = row["attributes"]?.jsonObject ?: error("Missing attributes")
                val labels = (attr["genreNames"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val block = ((row["relationships"] as? JsonObject)?.get("genres") as? JsonObject)
                val links = (block?.get("data") as? JsonArray).orEmpty()
                val names = mutableListOf<String>()
                val linkedIds = hashSetOf<String>()
                for (link in links) {
                    val genre = link.jsonObject
                    val genreId = requireNotNull(genre["id"]?.jsonPrimitive?.contentOrNull)
                    assertTrue(genreId.matches(Regex("[0-9]{1,12}")) && linkedIds.add(genreId))
                    assertEquals("genres",genre["type"]?.jsonPrimitive?.content)
                    val name = (genre["attributes"] as? JsonObject)?.get("name")
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (name == null) unresolved++
                    else names += name
                }
                val attrExact = labels.any { appleMusicWebGenreNameMatches(it,tag) }
                val relExact = names.any { appleMusicWebGenreNameMatches(it,tag) }
                if (attrExact) exactAttributes++
                if (relExact) exactRelationships++
                if (relExact && !attrExact) additional++
                val taxonomyExact = linkedIds.any { genreId ->
                    nativeById[genreId]?.let { appleMusicWebGenreNameMatches(it.title,tag) } == true
                }
                println("AMS_STYLE_REL|tag=$tag|id=$id|fixture=${id in fixtures}|labels=${labels.joinToString(",")}|linked=${linkedIds.size}|materialized=${names.joinToString(",")}|attrExact=$attrExact|relExact=$relExact|taxonomyExact=$taxonomyExact")
            }
            println("AMS_STYLE_REL|tag=$tag|scope=batch|requested=${ids.size}|returned=${rows.size}|attrExact=$exactAttributes|relExact=$exactRelationships|additional=$additional|unresolved=$unresolved")
            // Compare exact detail on a bounded subset including the controls.
            for (id in (fixtures + ids.take(3)).distinct().filter { it in seen }.take(6)) {
                val detail = withTimeout(120_000) { catalog.albumGenres(id,"br") }
                assertTrue(detail.all { it.storefront == "br" })
                println("AMS_STYLE_REL|tag=$tag|scope=detail|id=$id|names=${detail.joinToString(",") { it.title }}|exact=${detail.any { appleMusicWebGenreNameMatches(it.title,tag) }}")
            }
        }
    }

}
