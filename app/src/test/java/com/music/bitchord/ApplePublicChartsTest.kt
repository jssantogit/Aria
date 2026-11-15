package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApplePublicChartsTest {
    private val appleCover =
        "https://is1-ssl.mzstatic.com/image/thumb/Music211/test/100x100bb.jpg"

    private fun song(
        id: Long,
        name: String,
        artist: String = "NewJeans",
        artistId: Long = 77,
    ): String = """
        {"id":"$id","name":"$name","kind":"songs",
         "artistName":"$artist","artistUrl":"https://music.apple.com/br/artist/newjeans/$artistId",
         "artworkUrl100":"$appleCover","releaseDate":"2024-04-01",
         "genres":[{"name":"K-Pop"}],
         "url":"https://music.apple.com/br/album/ditto/9000?i=$id"}
    """.trimIndent()
    private fun feed(vararg rows: String, market: String = "br"): String =
        """{"feed":{"country":"$market","results":[${rows.joinToString(",")}]}}"""

    @Test fun publishedAppleRssHostIsPrimaryAndOldAddressOnlyFallback() {
        val urls = applePublicChartFeedUrls("br", ApplePublicChartKind.SONGS)
        assertEquals(2, urls.size)
        assertEquals(
            "https://rss.marketingtools.apple.com/api/v2/br/music/most-played/100/songs.json",
            urls.first(),
        )
        assertEquals(
            "https://rss.applemarketingtools.com/api/v2/br/music/most-played/100/songs.json",
            urls.last(),
        )
        assertTrue(applePublicChartFeedUrls("bR", ApplePublicChartKind.ALBUMS).isEmpty())
        assertTrue(applePublicChartFeedUrls("../", ApplePublicChartKind.ALBUMS).isEmpty())
    }

    @Test fun canonicalMarketingRssPayloadParsesRealShapeWithArtistIdLinkProof() {
        // Sample reflects published feed fields, including artistId, artistUrl,
        // Music as the first genre and Apple's path-specific album identity.
        val sample = """{"feed":{"title":"Top Albums","country":"us","results":[
          {"artistName":"Adele","id":"1590035691","name":"30",
           "kind":"albums","artistId":"262836961",
           "artistUrl":"https://music.apple.com/us/artist/adele/262836961",
           "artworkUrl100":"$appleCover",
           "genres":[{"name":"Music"},{"name":"Pop"}],
           "url":"https://music.apple.com/us/album/30/1590035691"}]}}"""
        val releases = ApplePublicChartParser.parse(
            sample, "us", ApplePublicChartKind.ALBUMS,
        )!!
        assertEquals(1, releases.size)
        assertEquals(262836961L, releases.single().artistId)
        assertEquals("Pop", releases.single().primaryGenre)
        assertEquals(1590035691L, releases.single().id)
        assertEquals("itunes:us:album:1590035691", releases.single().scopedId)
        val contradictory = sample.replace(
            "\"artistId\":\"262836961\"", "\"artistId\":\"111111111\"",
        )
        assertNull(ApplePublicChartParser.parse(
            contradictory, "us", ApplePublicChartKind.ALBUMS,
        )!!.single().artistId)
    }

    @Test fun parserRetainsRankAndExactAppleSongArtistIdsWithoutPlayback() {
        val items = ApplePublicChartParser.parse(
            feed(song(100, "Ditto"), song(101, "OMG")),
            "br", ApplePublicChartKind.SONGS,
        )
        assertNotNull(items)
        assertEquals(listOf(1, 2), items!!.map { it.rank })
        assertEquals(listOf("itunes:br:track:100", "itunes:br:track:101"),
            items.map { it.scopedId })
        assertEquals(listOf(77L, 77L), items.map { it.artistId })
        assertTrue(items.all { it.primaryGenre == "K-Pop" && it.artwork == appleCover })
    }

    @Test fun mismatchedTrackLinksAndForeignStorefrontNeverBecomeHomeCards() {
        val mismatched = song(100, "Ditto")
            .replace("?i=100", "?i=101")
        val foreign = song(101, "OMG")
            .replace("/br/album/", "/us/album/")
        val forgedArtwork = song(102, "Cookie")
            .replace("is1-ssl.mzstatic.com", "malicious.invalid")
        val result = ApplePublicChartParser.parse(
            feed(mismatched, foreign, forgedArtwork),
            "br", ApplePublicChartKind.SONGS,
        )!!
        assertEquals(listOf(102L), result.map { it.id })
        assertNull(result.single().artwork)
        assertNull(ApplePublicChartParser.parse(
            feed(song(100, "Ditto"), market = "us"),
            "br", ApplePublicChartKind.SONGS,
        ))
    }

    @Test fun chartAlbumRequiresRealAppleAlbumIdentity() {
        val albums = feed(
            """{"id":"333","kind":"albums","name":"How Sweet - EP",
                "artistName":"NewJeans",
                "url":"https://music.apple.com/br/album/how-sweet/333",
                "artworkUrl100":"$appleCover"}""",
            """{"id":"334","kind":"albums","name":"Unrelated",
                "artistName":"Other",
                "url":"https://music.apple.com/br/album/unrelated/335"}""",
        )
        val result = ApplePublicChartParser.parse(
            albums, "br", ApplePublicChartKind.ALBUMS,
        )!!
        assertEquals(1, result.size)
        assertEquals("itunes:br:album:333", result.single().scopedId)
    }

    @Test fun conflictingDuplicateAppleProductIsDiscardedRatherThanFirstWins() {
        val result = ApplePublicChartParser.parse(
            feed(song(100, "Ditto"), song(100, "Different"), song(101, "OMG")),
            "br", ApplePublicChartKind.SONGS,
        )!!
        assertEquals(listOf(101L), result.map { it.id })
        assertEquals(3, result.single().rank)
    }

    @Test fun malformedOrOversizedFeedsDoNotPublishInventedPopularity() {
        assertNull(ApplePublicChartParser.parse("garbage", "br", ApplePublicChartKind.SONGS))
        assertNull(ApplePublicChartParser.parse(
            """{"feed":{"results":{}}}""", "br", ApplePublicChartKind.SONGS,
        ))
        assertNull(ApplePublicChartParser.parse(
            "x".repeat(900_001), "br", ApplePublicChartKind.SONGS,
        ))
    }

    @Test fun oneUnavailableMarketDoesNotImpersonateAChartSuccess() = runBlocking {
        val client = ApplePublicChartsClient(ApplePublicChartTransport { market, _ ->
            if (market == "us") error("unavailable")
            feed(song(100, "Ditto"))
        })
        assertNull(client.load("us", ApplePublicChartKind.SONGS))
        assertEquals(1, client.load("br", ApplePublicChartKind.SONGS)!!.size)
        assertNull(client.load("BRA", ApplePublicChartKind.ALBUMS))
    }
}
