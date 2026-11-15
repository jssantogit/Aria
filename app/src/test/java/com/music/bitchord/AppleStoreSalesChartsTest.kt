package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleStoreSalesChartsTest {
    private val cover =
        "https://is1-ssl.mzstatic.com/image/thumb/Music123/cover.jpg/170x170bb.jpg"
    private fun entry(track: Long = 777, artistId: Long = 222): String = """
      {"id":{"label":"https://itunes.apple.com/br/album/track/id999?i=$track&uo=4",
            "attributes":{"im:id":"$track"}},
       "im:name":{"label":"Verified Song"},
       "im:artist":{"label":"Real Artist",
         "attributes":{"href":"https://itunes.apple.com/br/artist/real-artist/id$artistId?uo=4"}},
       "category":{"attributes":{"term":"Pop"}},
       "im:releaseDate":{"label":"2026-11-01T00:00:00-03:00"},
       "im:image":[{"label":"$cover"}]}
    """.trimIndent()
    private fun payload(vararg entries: String): String =
        """{"feed":{"entry":[${entries.joinToString(",")}]}}"""

    @Test fun officialSalesSongsRemainRankedAppleTrackMetadataWithTheirProvenance() {
        val rows = AppleStoreSalesChartParser.parse(payload(entry()), "br")!!
        assertEquals(1, rows.size)
        assertEquals("itunes:br:track:777", rows.single().scopedId)
        assertEquals(101, rows.single().rank)
        assertEquals(222L, rows.single().artistId)
        assertEquals("Pop", rows.single().primaryGenre)
        assertEquals(cover, rows.single().artwork)
        assertEquals(ApplePublicChartSource.ITUNES_STORE_TOP_SALES, rows.single().source)
    }

    @Test fun foreignOrConflictingLinksCannotBecomePopularityEvidence() {
        val foreign = entry().replace("/br/album/", "/us/album/")
        val idMismatch = entry().replace("i=777", "i=778")
        val wrongProduct = entry().replace(
            "\"im:id\":\"777\"", "\"im:id\":\"123\"",
        )
        assertTrue(AppleStoreSalesChartParser.parse(
            payload(foreign, idMismatch, wrongProduct), "br",
        )!!.isEmpty())
        val malformed = entry().replace("is1-ssl.mzstatic.com", "attacker.invalid")
        val row = AppleStoreSalesChartParser.parse(payload(malformed), "br")!!.single()
        assertNull(row.artwork)
    }

    @Test fun publicStoreSalesFeedsCannotMasqueradeAsAppleMusicStreamRanking() = runBlocking {
        val client = AppleStoreSalesChartsClient(AppleStoreSalesChartTransport { market ->
            if (market == "us") error("Unavailable")
            payload(entry())
        })
        assertNull(client.load("us"))
        assertNull(client.load("../"))
        assertEquals(ApplePublicChartSource.ITUNES_STORE_TOP_SALES,
            client.load("br")!!.single().source)
    }

    @Test fun duplicateConflictingProductsAreRejectedRatherThanRandomlyChosen() {
        val rows = AppleStoreSalesChartParser.parse(payload(
            entry(777), entry(777).replace("Verified Song", "Wrong Song"),
            entry(778),
        ), "br")!!
        assertEquals(listOf(778L), rows.map { it.id })
    }
}
