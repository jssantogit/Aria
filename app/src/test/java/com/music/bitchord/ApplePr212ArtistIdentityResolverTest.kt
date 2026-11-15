package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ApplePr212ArtistIdentityCandidate
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.applePr212VerifiedArtistMbid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplePr212ArtistIdentityResolverTest {
    private val artistA = "11111111-1111-4111-8111-111111111111"
    private val artistB = "22222222-2222-4222-8222-222222222222"

    private fun apple(id: Long, title: String, year: String = "2024") =
        ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:$id", appleCollectionId = id,
            storefront = "br", title = title, artistName = "NewJeans",
            artistId = 77, releaseDate = "$year-05-24", genre = "K-Pop",
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
            artworkUrl = null, trackCount = null, tracks = emptyList(),
            musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
            musicBrainzRecordingIds = emptyMap(),
        )

    private fun group(id: String, title: String, year: String = "2024") =
        MusicBrainzReleaseGroupHit(id, title, "Single", "$year-05-24", "NewJeans")

    private fun candidate(id: String, name: String = "NewJeans",
                          groups: List<MusicBrainzReleaseGroupHit>) =
        ApplePr212ArtistIdentityCandidate(
            MusicBrainzArtistHit(id, name, null, "KR"), groups,
        )

    @Test fun twoIndependentExactTitlesAndYearsProveOnlyOneArtistMbid() {
        val apple = listOf(
            apple(101, "How Sweet - EP"),
            apple(102, "Supernatural - Single"),
            apple(103, "NJWMX", "2023"),
            apple(104, "NJWMX", "2023"),
        )
        val mb = listOf(candidate(artistA, groups = listOf(
            group("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet"),
            group("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Supernatural"),
        )))
        assertEquals(artistA, applePr212VerifiedArtistMbid("NewJeans", 77, "br", apple, mb))
    }

    @Test fun aSingleMatchingTitleAnUnrelatedArtistOrWrongYearIsNeverProof() {
        val apple = listOf(apple(101, "How Sweet - EP"), apple(102, "Supernatural - Single"))
        assertNull(applePr212VerifiedArtistMbid("NewJeans", 77, "br", apple,
            listOf(candidate(artistA, groups = listOf(
                group("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet"),
            )))))
        assertNull(applePr212VerifiedArtistMbid("NewJeans", 77, "br", apple,
            listOf(candidate(artistA, name = "Other Artist", groups = listOf(
                group("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet"),
                group("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Supernatural"),
            )))))
        assertNull(applePr212VerifiedArtistMbid("NewJeans", 77, "br", apple,
            listOf(candidate(artistA, groups = listOf(
                group("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet", "2021"),
                group("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Supernatural", "2021"),
            )))))
    }

    @Test fun twoValidCandidateArtistsAreAmbiguousAndDoNotEnableForeignShelves() {
        val releases = listOf(apple(101, "How Sweet - EP"), apple(102, "Supernatural - Single"))
        val groups = listOf(
            group("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet"),
            group("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Supernatural"),
        )
        assertNull(applePr212VerifiedArtistMbid("NewJeans", 77, "br", releases,
            listOf(candidate(artistA, groups = groups), candidate(artistB, groups = groups))))
    }
}
