package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Validate the actual CDN bytes, not simply the existence of a URL.
 * Discover metal style coverage without treating the parent as the style. */
class AmsStyleArtworkLiveTest {
    private fun session(): AppleMusicWebNativeCatalogSession {
        assumeTrue(System.getenv("AMS_STYLE_ART_LIVE") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val caps = setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY,ProviderCapability.CHART_MOST_PLAYED,ProviderCapability.CATALOG_SEARCH)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(key,true,0)) },
        ))
        return AppleMusicWebNativeCatalogSession({gate}) { owner ->
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
                admission=RateLimitedProviderTransport { gate })),owner)
        }
    }
    private fun <T> content(result: ProviderCallResult<AppleMusicWebOutcome<T>>): T? =
        when(val outcome=(result as? ProviderCallResult.Success)?.value) {
            is AppleMusicWebOutcome.Complete -> outcome.value
            is AppleMusicWebOutcome.Partial -> outcome.value
            else -> null
        }

    private fun checkImage(scope: String, url: String?) {
        assertNotNull("Missing artwork for $scope",url)
        val uri=URI(requireNotNull(url))
        assertTrue(uri.scheme == "https" && uri.userInfo == null &&
            (uri.host == "mzstatic.com" || uri.host.endsWith(".mzstatic.com")))
        val conn=uri.toURL().openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects=false
            conn.connectTimeout=15_000
            conn.readTimeout=15_000
            val status=conn.responseCode
            val mime=conn.contentType.orEmpty()
            assertEquals("Artwork HTTP $scope",200,status)
            assertTrue("Artwork type $scope",mime.startsWith("image/"))
            val bytes=conn.inputStream.use { it.readNBytes(16) }
            assertTrue("Empty image $scope",bytes.size>=12)
            val jpg=bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 255 == 216
            val png=bytes[0].toInt() and 255 == 137 && String(bytes.copyOfRange(1,4))=="PNG"
            val webp=String(bytes.copyOfRange(0,4))=="RIFF" && String(bytes.copyOfRange(8,12))=="WEBP"
            assertTrue("Unsupported image signature $scope",jpg || png || webp)
            println("AMS_STYLE_ART|scope=$scope|http=$status|mime=$mime|signature=true")
        } finally { conn.disconnect() }
    }

    @Test fun editorialPlaylistCardImagesActuallyLoad(): Unit = runBlocking {
        val native=session()
        val tiles=(withTimeout(180_000) { native.dailyChartTiles() } as ProviderCallResult.Success).value
        checkImage("top100_br",tiles.single { it.country=="br" }.card.thumbnailUrl)
        val home=requireNotNull(content(withTimeout(180_000) { native.home("br") }))
        for (heading in listOf("Playlists atualizadas","Mais tocadas por cidade","Agora em turnê")) {
            checkImage(heading,home.shelves.first { it.title==heading }.items.first().thumbnailUrl)
        }
        val kpop=requireNotNull(content(withTimeout(240_000) { native.genre("k-pop","br") }))
        for (heading in listOf("Playlists","Playlists de artistas","Sucessos por ano")) {
            checkImage(heading,kpop.shelves.first { it.title==heading }.items.first().thumbnailUrl)
        }
    }

    @Test fun metalStyleCoverageFromActualCurators(): Unit = runBlocking {
        val native=session()
        val uncovered=mutableListOf<String>()
        for (tag in listOf("death metal","heavy metal","thrash metal","black metal","doom metal")) {
            val page=content(withTimeout(180_000) { native.genre(tag,"br") })
            val items=page?.shelves.orEmpty().flatMap { it.items }
            val albums=items.count { AppleMusicWebNativeRoute.parse(it.browseId)?.kind==AppleMusicWebKind.ALBUM }
            val playlists=items.count { AppleMusicWebNativeRoute.parse(it.browseId)?.kind==AppleMusicWebKind.PLAYLIST }
            val parent=page?.curator?.let { amsVerifiedStyleParent(it,tag) } == true
            assertTrue(items.all { it.videoId==null && AppleMusicWebNativeRoute.parse(it.browseId)?.storefront=="br" })
            println("AMS_STYLE_ART|scope=style|tag=$tag|parent=$parent|albums=$albums|playlists=$playlists|pending=${page?.pending?.size?:0}")
            if (albums < 6 || playlists < 1) uncovered+=tag
        }
        assertTrue("Uncovered metal styles: "+uncovered.joinToString(),uncovered.isEmpty())
    }
    @Test fun everyStylePageHasVerifiedContent(): Unit = runBlocking {
        val native = session()
        val shard = System.getenv("AMS_STYLE_SHARD")?.toIntOrNull() ?: 0
        require(shard in 0..3)
        val tags = amsEditorialStyleParents.keys.toList().filterIndexed { i,_ -> i % 4 == shard }
        val gaps = mutableListOf<String>()
        for (tag in tags) {
            val page = content(withTimeout(240_000) { native.genre(tag,"br") })
            val shelves = page?.shelves.orEmpty()
            val items = shelves.flatMap { it.items }.distinctBy { it.browseId }
            val albums = items.count { AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.ALBUM }
            val playlists = items.count { AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.PLAYLIST }
            val exact = shelves.filter { it.title == "Álbuns do estilo" }.sumOf { it.items.size }
            val curated = shelves.filter { it.title == "Álbuns das playlists" }.sumOf { it.items.size }
            assertTrue(items.all { it.videoId == null && AppleMusicWebNativeRoute.parse(it.browseId)?.storefront == "br" })
            println("AMS_STYLE_ART|scope=integrated|shard=$shard|tag=$tag|uniqueAlbums=$albums|playlists=$playlists|metadata=$exact|playlistAlbums=$curated|pending=${page?.pending?.size ?: 0}")
            if (albums < 6) gaps += tag
        }
        assertTrue("Insufficient actual style pages: " + gaps.joinToString(),gaps.isEmpty())
    }

    /** Explain rejected room candidates without relaxing identity or genre rules. */
    @Test fun curatorPlaylistDetailEvidenceInventory(): Unit = runBlocking {
        assumeTrue(System.getenv("AMS_STYLE_ART_LIVE") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val caps = setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY,ProviderCapability.CATALOG_SEARCH)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(key,true,0)) },
        ))
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
            admission=RateLimitedProviderTransport { gate })),gate)
        for (tag in listOf("hard rock","baroque")) {
            val landing = requireNotNull(content(withTimeout(240_000) {
                gateway.curatedGenreLanding(tag,"br").let { result ->
                    when (result) {
                        is ProviderCallResult.Success -> ProviderCallResult.Success(result.value.outcome)
                        is ProviderCallResult.Failed -> result
                        ProviderCallResult.Denied -> ProviderCallResult.Denied
                        ProviderCallResult.Stale -> ProviderCallResult.Stale
                    }
                }
            }))
            val curator = landing.curator.identity
            val cards = mutableListOf<com.music.bitchord.data.model.ShelfItem>()
            for (room in landing.curator.rooms.filter {
                isAppleMusicWebCuratorPlaylistRoom(it) && it.storefront == "br" &&
                    it.curatorId == curator.id && it.genreId == null
            }.take(2)) {
                var cursor: String? = null
                val visited = hashSetOf<String>()
                for (index in 1..3) {
                    val result = withTimeout(120_000) {
                        gateway.roomContents(room,AppleMusicWebKind.PLAYLIST,limit=25,cursor=cursor)
                    }
                    val outcome = (result as? ProviderCallResult.Success)?.value?.outcome
                    val page = when (outcome) {
                        is AppleMusicWebOutcome.Complete -> outcome.value
                        is AppleMusicWebOutcome.Partial -> outcome.value
                        else -> break
                    }
                    cards += projectAppleMusicWebGenrePlaylistRoom(curator,tag,page)?.items.orEmpty()
                    val next = page.nextCursor ?: break
                    if (page.unresolvedReferences != 0 || !visited.add(next)) break
                    cursor = next
                }
            }
            val candidates = cards.groupBy { it.browseId }.values.mapNotNull { rows ->
                rows.first().takeIf { first -> rows.all { it == first } }
            }.take(4)
            println("AMS_CURATOR_DETAIL|tag=$tag|scope=summary|candidates=${candidates.size}")
            assertTrue("No candidate playlists for $tag",candidates.isNotEmpty())
            for (card in candidates) {
                val id = requireNotNull(AppleMusicWebNativeRoute.parse(card.browseId)).id
                val response = withTimeout(180_000) { gateway.playlist(id,"br") }
                val outcome = (response as? ProviderCallResult.Success)?.value?.outcome
                val playlist = when (outcome) {
                    is AppleMusicWebOutcome.Complete -> outcome.value
                    is AppleMusicWebOutcome.Partial -> outcome.value
                    else -> {
                        println("AMS_CURATOR_DETAIL|tag=$tag|id=$id|detail=false")
                        continue
                    }
                }
                val item = playlist.identity
                val title = card.title.trim() == item.title.trim()
                val credit = card.subtitle.isBlank() || card.subtitle.trim() == item.artistName.orEmpty().trim()
                val url = com.music.bitchord.ui.screens.appleMusicWebVerifiedStoreUrl(item) != null
                val exactTitle = amsExactStylePlaylistTitle(item.title,tag)
                val label = item.genreNames.any { appleMusicWebGenreNameMatches(it,tag) }
                val admitted = amsStyleCuratorPlaylistDetailMatches(item,id,"br",tag,card)
                println("AMS_CURATOR_DETAIL|tag=$tag|id=$id|type=${item.playlistType}|titleMatch=$title|creditMatch=$credit|url=$url|exactTitle=$exactTitle|exactLabel=$label|accepted=$admitted|title=${item.title}|linkedAlbums=${amsStylePlaylistAlbumIds(playlist,"br").size}")
            }
        }
    }

}
