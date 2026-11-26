package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.appleChartTerritories
import com.music.bitchord.data.catalog.provider.applemusicweb.*
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.*
import com.music.bitchord.ui.*
import com.music.bitchord.ui.screens.AppleSharedDetail
import com.music.bitchord.ui.screens.appleMusicWebVerifiedRouteUrl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercise the actual native UI adapters, not just the underlying API.
 * The opt-in gate is local to this test. No runtime provider is made READY. */
class AppleMusicWebCanonicalLiveTest {
    private fun session(): AppleMusicWebNativeCatalogSession {
        assumeTrue("Dedicated AMS canonical live workflow only",
            System.getenv("AMS_CANONICAL_LIVE") == "1")
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,ProviderCapability.CHART_MOST_PLAYED)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(ProviderDescriptor(key,caps,ProviderReadiness.READY)),
            caps.associateWith { listOf(ProviderChoice(key,true,0)) },
        ))
        return AppleMusicWebNativeCatalogSession({gate}) { owner ->
            AppleMusicWebEditorialGateway(AppleMusicWebCatalog(AppleMusicWebPolicyTransport(
                admission=RateLimitedProviderTransport { gate })),owner)
        }
    }

    private fun <T> content(result: ProviderCallResult<AppleMusicWebOutcome<T>>?): T? =
        when (val outcome = (result as? ProviderCallResult.Success)?.value) {
            is AppleMusicWebOutcome.Complete -> outcome.value
            is AppleMusicWebOutcome.Partial -> outcome.value
            else -> null
        }

    private fun validDetail(detail: AppleSharedDetail?, id: String, kind: BrowseType): Boolean =
        detail != null && detail.page.browseId == id && detail.page.type == kind &&
            detail.page.title.isNotBlank() && (detail.page.songs as? UiState.Success)?.data?.isEmpty() == true &&
            detail.page.sections.flatMap { it.items }.all { it.videoId == null } &&
            detail.metadataTracks.all { amsSongStorefront(it.key) == "br" }

    @Test fun homeAndSourceOwnedContinuation(): Unit = runBlocking {
        val native = session()
        val page = content(withTimeoutOrNull(150_000) { native.home("br") })
        val shelves = page?.shelves.orEmpty()
        val albums = shelves.flatMap { it.items }.filter {
            AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.ALBUM
        }
        val songs = shelves.filter { it.title == "Músicas em alta" }.flatMap { it.items }
        val songsValid = songs.isNotEmpty() && songs.all {
            amsSongStorefront(it.browseId) == "br" &&
                appleMusicWebVerifiedRouteUrl(it.appleStoreUrl,it.browseId) != null
        }
        println("AMS_UI_LIVE|scope=home|albums=${albums.size}|trending=${songs.size}|valid_trending=$songsValid|pending=${page?.pending?.size ?: 0}")
        val failures = mutableListOf<String>()
        if (albums.isEmpty() || shelves.flatMap { it.items }.any { it.videoId != null })
            failures += "home_albums"
        if (!songsValid) failures += "home_trending"
        val initial = shelves.flatMap { it.items }.mapNotNull { it.browseId }.toSet()
        for (request in page?.pending.orEmpty().take(3)) {
            val more = content(withTimeoutOrNull(90_000) { native.nextEditorial(request) })
            val cards = more?.shelves.orEmpty().flatMap { it.items }
            val kind = (request as? AmsEditorialRequest.Room)?.resourceKind
            val ok = more != null && cards.all {
                it.videoId == null && if (kind == AppleMusicWebKind.SONG)
                    amsSongStorefront(it.browseId) == "br"
                else AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.ALBUM
            }
            println("AMS_UI_LIVE|scope=home_next|kind=$kind|rows=${cards.size}|new=${cards.count { it.browseId !in initial }}|ok=$ok")
            if (!ok) failures += "home_next_$kind"
        }
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }

    @Test fun curatorGenreAndPlaylistProjection(): Unit = runBlocking {
        val native = session()
        val failures = mutableListOf<String>()
        var playlists = 0
        var opened = 0
        for (tag in listOf("metal","rock","soul")) {
            val page = content(withTimeoutOrNull(180_000) { native.genre(tag,"br") })
            val rows = page?.shelves.orEmpty()
            val releases = rows.filter { it.title == "Lançamentos" }.flatMap { it.items }
            val lists = rows.filter { it.title == "Playlists" }.flatMap { it.items }
            playlists += lists.size
            val valid = releases.isNotEmpty() && releases.all {
                AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.ALBUM && it.videoId == null
            } && lists.all { AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.PLAYLIST && it.videoId == null }
            println("AMS_UI_LIVE|scope=genre|tag=$tag|albums=${releases.size}|playlists=${lists.size}|ok=$valid")
            if (!valid) failures += tag
            val request = page?.pending?.firstOrNull()
            if (request != null) {
                val next = content(withTimeoutOrNull(90_000) { native.nextEditorial(request) })
                println("AMS_UI_LIVE|scope=genre_next|tag=$tag|rows=${next?.shelves.orEmpty().sumOf { it.items.size }}|ok=${next != null}")
                if (next == null) failures += "next_$tag"
            }
            val id = lists.firstOrNull()?.browseId
            if (id != null && opened == 0) {
                val detail = content(withTimeoutOrNull(120_000) { native.sharedDetail(id) })
                val ok = validDetail(detail,id,BrowseType.PLAYLIST) && detail?.metadataTracks?.isNotEmpty() == true
                println("AMS_UI_LIVE|scope=genre_playlist|tracks=${detail?.metadataTracks?.size ?: 0}|ok=$ok")
                if (ok) opened++ else failures += "genre_playlist"
            }
        }
        if (playlists == 0) failures += "no_curator_playlist_observed"
        if (opened == 0) failures += "no_curator_playlist_opened"
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }

    @Test fun nativeArtistAlbumPlaylistAndSongRelationship(): Unit = runBlocking {
        val native = session()
        val failures = mutableListOf<String>()
        var playlistsOpened = 0
        var relationshipAlbums = 0
        for ((index,name) in listOf("Taylor Swift","Beyoncé","Gilberto Gil").withIndex()) {
            val search = withTimeoutOrNull(90_000) {
                native.search(CatalogSearchRequest(CatalogSearchKind.ARTIST,name,"br",limit=24))
            }
            val item = (search as? ProviderCallResult.Success)?.value?.items?.firstOrNull {
                it.title.equals(name,ignoreCase=true) && it.identity.storefront == "br"
            }
            val id = item?.identity?.nativeId?.let { "apple_music_web:br:artist:$it" }
            val detail = id?.let { content(withTimeoutOrNull(180_000) { native.sharedDetail(it) }) }
            val sections = detail?.page?.sections.orEmpty()
            val albums = sections.flatMap { it.items }.filter {
                AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.ALBUM
            }
            val songs = detail?.metadataTracks.orEmpty()
            val lists = sections.flatMap { it.items }.filter {
                AppleMusicWebNativeRoute.parse(it.browseId)?.kind == AppleMusicWebKind.PLAYLIST
            }
            val ok = id != null && validDetail(detail,id,BrowseType.ARTIST) &&
                albums.isNotEmpty() && songs.isNotEmpty()
            println("AMS_UI_LIVE|scope=artist|case=${index+1}|albums=${albums.size}|tracks=${songs.size}|playlists=${lists.size}|ok=$ok")
            if (!ok) { failures += "artist_${index+1}"; continue }
            val albumId = albums.first().browseId!!
            val album = content(withTimeoutOrNull(150_000) { native.sharedDetail(albumId) })
            val albumOk = validDetail(album,albumId,BrowseType.ALBUM) && album?.metadataTracks?.isNotEmpty() == true
            println("AMS_UI_LIVE|scope=album|case=${index+1}|tracks=${album?.metadataTracks?.size ?: 0}|ok=$albumOk")
            if (!albumOk) failures += "album_${index+1}"
            val paging = detail?.amsArtistPaging
            if (paging?.hasMore == true) {
                val next = content(withTimeoutOrNull(120_000) { native.nextArtistDetail(requireNotNull(detail)) })
                val nextOk = next != null && next.page.browseId == id &&
                    next.amsArtistPaging?.pages == paging.pages+1
                println("AMS_UI_LIVE|scope=artist_next|case=${index+1}|ok=$nextOk")
                if (!nextOk) failures += "artist_next_${index+1}"
            }
            var resolved: String? = null
            for (track in songs.take(3)) {
                val route = withTimeoutOrNull(60_000) { native.songAlbumRoute(track.key) }
                if (route != null) { resolved = route; break }
            }
            val route = AppleMusicWebNativeRoute.parse(resolved)
            if (route?.kind == AppleMusicWebKind.ALBUM && route.storefront == "br") relationshipAlbums++
            println("AMS_UI_LIVE|scope=track_album|case=${index+1}|resolved=${route != null}")
            val playlistId = lists.firstOrNull()?.browseId
            if (playlistId != null && playlistsOpened == 0) {
                val playlist = content(withTimeoutOrNull(150_000) { native.sharedDetail(playlistId) })
                val playlistOk = validDetail(playlist,playlistId,BrowseType.PLAYLIST) &&
                    playlist?.metadataTracks?.isNotEmpty() == true
                println("AMS_UI_LIVE|scope=artist_playlist|tracks=${playlist?.metadataTracks?.size ?: 0}|ok=$playlistOk")
                if (playlistOk) playlistsOpened++ else failures += "artist_playlist"
            }
        }
        if (playlistsOpened == 0) failures += "no_artist_playlist_opened"
        if (relationshipAlbums == 0) failures += "no_song_album_relationship"
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }

    @Test fun nativeCountryRankings(): Unit = runBlocking {
        val native = session()
        val failures = mutableListOf<String>()
        for (territory in appleChartTerritories) for (albums in listOf(false,true)) {
            val read = withTimeoutOrNull(180_000) { native.countryChart(territory.code,albums) }
            val result = (read as? ProviderCallResult.Success)?.value
            val positions = when(result) {
                is AmsCountryChartResult.Songs -> result.chart.songs.map { it.position }
                is AmsCountryChartResult.Albums -> result.chart.albums.map { it.position }
                null -> emptyList()
            }
            val correctType = if (albums) result is AmsCountryChartResult.Albums else result is AmsCountryChartResult.Songs
            val ids = when(result) {
                is AmsCountryChartResult.Songs -> result.chart.songs.map { it.songBrowseId }
                is AmsCountryChartResult.Albums -> result.chart.albums.map { it.albumBrowseId }
                null -> emptyList()
            }
            val prefix = "apple_music_web:${territory.code}:" + if (albums) "album:" else "song:"
            val valid = correctType && positions.isNotEmpty() && positions == positions.distinct().sorted() &&
                positions.all { it in 1..100 } && ids.all { it.startsWith(prefix) }
            println("AMS_UI_LIVE|scope=chart|market=${territory.code}|albums=$albums|rows=${positions.size}|ok=$valid")
            if (!valid) failures += "${territory.code}:$albums"
        }
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
