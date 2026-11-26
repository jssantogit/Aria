package com.music.bitchord

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.canonicalCuratedHomeShelves
import com.music.bitchord.ui.canonicalPublicPopularityShelf
import com.music.bitchord.ui.canonicalEditorialRowForCard
import com.music.bitchord.ui.canonicalPublicPopularityCandidates
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.ui.canonicalExploreShelves
import com.music.bitchord.ui.canonicalExploreWithCommunityShelves
import com.music.bitchord.ui.canonicalEditorialPlaybackSource
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.ui.canonicalCoverLookupTarget
import com.music.bitchord.ui.canonicalCoverLookupCandidates
import com.music.bitchord.ui.canonicalArtistArtworkCandidate
import com.music.bitchord.ui.canonicalLocalPickCardId
import com.music.bitchord.ui.canonicalRemotePickCardId
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical #178: YT-off feeds must use real data, old Home cards and no fake charts. */
class CanonicalCuratedHomeFeedTest {
    private val release = CanonicalCatalogSearchRow(
        "00000000-0000-0000-0000-000000000001", CanonicalSearchKind.RELEASE_GROUP,
        "SUPER REAL ME", "ILLIT", fromOfflineCache = true,
        releaseDate = "2024-03-25", releaseKind = "EP",
    )
    private val local = Song("local-123", "Magnetic", "ILLIT", null, localUri = "content://media/audio/123")
    private val playable = CanonicalCatalogSearchRow(
        "00000000-0000-0000-0000-000000000002", CanonicalSearchKind.RECORDING,
        "Magnetic", "ILLIT", hasCatalogedAsset = true, fromOfflineCache = true,
    )
    private fun feed(metadata: List<CanonicalCatalogSearchRow>, picks: List<Song> = emptyList(),
                     trending: List<String> = emptyList()) = canonicalCuratedHomeShelves(
        savedReleases = emptyList(), metadata = metadata,
        localPicks = picks, trendingRecordingMbids = trending,
        releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
        trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
    )


    @Test fun albumRelatedRailIsBoundToExactArtistAndCanonicalGroupIds() {
        val artist = "12345678-1234-4234-8234-123456789abc"
        val current = "11111111-1111-4111-8111-111111111111"
        fun group(id: String, title: String, type: String) =
            com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit(
                musicBrainzId = id, title = title, primaryType = type,
                firstReleaseDate = "2025-09-02", artistDisplay = "ILLIT",
            )
        val other = group("22222222-2222-4222-8222-222222222222",
            "Verified second album", "Album")
        val untyped = group("33333333-3333-4333-8333-333333333333",
            "Unclassified", "Other")
        val rows = com.music.bitchord.ui.canonicalAlbumArtistRelatedRows(
            artist, current, listOf(group(current, "Opened", "Album"),
                other, other, untyped),
        )
        assertEquals(listOf(other.musicBrainzId), rows.map { it.musicBrainzId })
        assertEquals(artist, rows.single().artistScopeMbid)
        val shelf = com.music.bitchord.ui.canonicalAlbumArtistRelatedShelf(rows, "ILLIT")!!
        assertEquals("Mais de ILLIT", shelf.title)
        assertEquals("catalog:RELEASE_GROUP:${other.musicBrainzId}",
            shelf.items.single().browseId)
        assertEquals(null, shelf.items.single().videoId)
        assertTrue(com.music.bitchord.ui.canonicalAlbumArtistRelatedRows(
            null, current, listOf(other),
        ).isEmpty())
        assertTrue(com.music.bitchord.ui.canonicalAlbumArtistRelatedRows(
            "not-a-valid-id", current, listOf(other),
        ).isEmpty())
        assertEquals("Mais do artista",
            com.music.bitchord.ui.canonicalAlbumArtistRelatedShelf(rows, "Various Artists")?.title)
    }


    @Test fun editionAndWorkResolveExactlyOneCanonicalGroupWithoutTitleGuessing() {
        val group = "11111111-1111-4111-8111-111111111111"
        val release = "22222222-2222-4222-8222-222222222222"
        assertEquals(group, com.music.bitchord.ui.canonicalAlbumGroupMbid(
            CanonicalSearchKind.RELEASE_GROUP, group,
        ))
        assertEquals(group, com.music.bitchord.ui.canonicalAlbumGroupMbid(
            CanonicalSearchKind.RELEASE, release, group.uppercase(),
        ))
        assertEquals(null, com.music.bitchord.ui.canonicalAlbumGroupMbid(
            CanonicalSearchKind.RELEASE, release,
        ))
        assertEquals(null, com.music.bitchord.ui.canonicalAlbumGroupMbid(
            CanonicalSearchKind.RELEASE, release, "Album title in a catalogue",
        ))
        assertEquals(null, com.music.bitchord.ui.canonicalAlbumGroupMbid(
            CanonicalSearchKind.RECORDING, release, group,
        ))
        assertEquals(null, com.music.bitchord.ui.canonicalAlbumGroupMbid(
            CanonicalSearchKind.RELEASE_GROUP, "invalid release group",
        ))
    }

    @Test fun directlyOpenedEditionCanKeepIndependentNativeVersionsAndArtistSections() {
        val group = "11111111-1111-4111-8111-111111111111"
        val artist = "12345678-1234-4234-8234-123456789abc"
        val current = "22222222-2222-4222-8222-222222222222"
        val sibling = com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
            musicBrainzId = "33333333-3333-4333-8333-333333333333",
            title = "Other verified edition", country = "JP",
            releaseDate = "2025-07-11", artistDisplay = "Verified artist",
            status = "Official", releaseGroupMbid = group,
        )
        val editions = com.music.bitchord.ui.canonicalReleaseGroupEditionRows(
            group,
            listOf(sibling.copy(musicBrainzId = current), sibling,
                sibling.copy(musicBrainzId = "44444444-4444-4444-8444-444444444444",
                    releaseGroupMbid = "55555555-5555-4555-8555-555555555555")),
            current,
        )
        val versionSections = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.RELEASE_GROUP, editions, "Outras versões",
        )
        val related = com.music.bitchord.ui.canonicalAlbumArtistRelatedRows(
            artist, group,
            listOf(
                com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit(
                    group, "Current work", primaryType = "Album",
                    firstReleaseDate = "2025-01-01", artistDisplay = "Verified artist",
                ),
                com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit(
                    "66666666-6666-4666-8666-666666666666", "Other artist work",
                    primaryType = "EP", firstReleaseDate = "2025-04-01",
                    artistDisplay = "Verified artist",
                ),
            ),
        )
        val shelves = versionSections + listOfNotNull(
            com.music.bitchord.ui.canonicalAlbumArtistRelatedShelf(related, "Verified artist"),
        )
        assertEquals(listOf("Outras versões", "Mais de Verified artist"),
            shelves.map { it.title })
        assertEquals(2, shelves.flatMap { it.items }.size)
        assertTrue(shelves.flatMap { it.items }.all { it.videoId == null })
        assertTrue(shelves.flatMap { it.items }.all { it.browseId.orEmpty().startsWith("catalog:") })
        assertTrue(shelves.flatMap { it.items }.none {
            it.browseId == "catalog:RELEASE:$current" ||
                it.browseId == "catalog:RELEASE_GROUP:$group"
        })
    }

    @Test fun albumVersionsUseProvenGroupLinksAndAreNeverPlayableSongs() {
        val group = "11111111-1111-4111-8111-111111111111"
        val selected = com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
            musicBrainzId = "22222222-2222-4222-8222-222222222222",
            title = "Current edition", country = "BR",
            releaseDate = "2026-11-01", artistDisplay = "Artist",
            status = "Official", releaseGroupMbid = group,
        )
        val jp = selected.copy(
            musicBrainzId = "33333333-3333-4333-8333-333333333333",
            title = "Japanese edition", country = "JP",
        )
        val unrelated = jp.copy(
            musicBrainzId = "44444444-4444-4444-8444-444444444444",
            releaseGroupMbid = "55555555-5555-4555-8555-555555555555",
        )
        val unofficial = jp.copy(
            musicBrainzId = "66666666-6666-4666-8666-666666666666",
            status = "Unofficial",
        )
        val editions = com.music.bitchord.ui.canonicalReleaseGroupEditionRows(
            group, listOf(selected, jp, jp, unrelated, unofficial),
            displayedReleaseMbid = selected.musicBrainzId,
        )
        assertEquals(listOf(jp.musicBrainzId), editions.map { it.musicBrainzId })
        assertEquals(CanonicalSearchKind.RELEASE, editions.single().kind)
        assertEquals(null, editions.single().artworkUri)
        assertTrue(editions.single().subtitle.orEmpty().contains("JP"))
        val section = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.RELEASE_GROUP, editions, "Outras versões",
        )
        assertEquals(listOf("Outras versões"), section.map { it.title })
        assertEquals("catalog:RELEASE:${jp.musicBrainzId}",
            section.single().items.single().browseId)
        assertEquals(null, section.single().items.single().videoId)
        assertTrue(com.music.bitchord.ui.canonicalReleaseGroupEditionRows(
            group, listOf(selected), selected.musicBrainzId,
        ).isEmpty())
    }

    @Test fun largerRealMusicBrainzFeedDistributesDistinctTypedReleasesAcrossShelves() {
        val base = release.copy(releaseKind = "Album")
        val rows = (1..12).map { i ->
            base.copy(
                musicBrainzId = "00000000-0000-0000-0000-${i.toString().padStart(12, '0')}",
                title = "Release $i",
                releaseKind = if (i % 3 == 0) "Single" else "Album",
                releaseDate = "2026-10-${i.toString().padStart(2, '0')}",
            )
        }
        val shelves = feed(rows)
        assertEquals(listOf("Lançamentos", "Álbuns para descobrir", "Singles e EPs"),
            shelves.map { it.title })
        assertEquals(12, shelves.flatMap { it.items }.map { it.browseId }.distinct().size)
        assertTrue(shelves.flatMap { it.items }.all {
            it.videoId == null && it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:")
        })
        assertEquals(8, shelves.first().items.size)
    }

    @Test fun cachedHomeHeroUsesSourceOwnedCoverBeforePlaceholderWhenBothRecent() {
        val newest = release.copy(
            musicBrainzId = "60000000-0000-4000-8000-000000000001",
            title = "No cover yet", releaseKind = "Album",
            releaseDate = "2026-10-21", artworkUri = null,
        )
        val withArt = release.copy(
            musicBrainzId = "60000000-0000-4000-8000-000000000002",
            title = "Verified cover", releaseKind = "Album",
            releaseDate = "2026-10-11",
            artworkUri = "https://coverartarchive.org/release-group/60000000-0000-4000-8000-000000000002/front-500",
        )
        val shelves = feed(listOf(newest, withArt))
        assertEquals("Verified cover",
            shelves.first { it.title == "Lançamentos" }.items.first().title)
        assertTrue(shelves.none { it.title == "Em alta" })
    }

    @Test fun finiteCachedHomeSampleReservesAlbumBehindThirtyRecentSingles() {
        val singles = (1..36).map { index ->
            release.copy(
                musicBrainzId = "90000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                title = "Single ${index}", releaseKind = "Single",
                releaseDate = "2026-10-06",
            )
        }
        val archivedAlbum = release.copy(
            musicBrainzId = "90000000-0000-4000-8000-000000000099",
            title = "Archived album", releaseKind = "Album",
            releaseDate = "2020-01-01",
        )
        val shelves = feed(singles + archivedAlbum)
        assertEquals("Archived album", shelves.first {
            it.title == "Álbuns para descobrir"
        }.items.single().title)
        assertTrue(shelves.any { it.title == "Singles e EPs" })
        assertTrue(shelves.first { it.title == "Lançamentos" }.items.all {
            it.title.startsWith("Single ")
        })
        assertTrue(shelves.flatMap { it.items }.map { it.browseId }.distinct().size ==
            shelves.flatMap { it.items }.size)
    }

    @Test fun finiteCachedHomeSampleReservesSingleBehindThirtyRecentAlbums() {
        val albums = (1..36).map { index ->
            release.copy(
                musicBrainzId = "80000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                title = "Album ${index}", releaseKind = "Album",
                releaseDate = "2026-10-06",
            )
        }
        val archivedSingle = release.copy(
            musicBrainzId = "80000000-0000-4000-8000-000000000099",
            title = "Archived single", releaseKind = "Single",
            releaseDate = "2019-07-01",
        )
        val shelves = feed(albums + archivedSingle)
        assertEquals("Archived single", shelves.first {
            it.title == "Singles e EPs"
        }.items.single().title)
        assertTrue(shelves.any { it.title == "Álbuns para descobrir" })
        assertTrue(shelves.first { it.title == "Lançamentos" }.items.all {
            it.title.startsWith("Album ")
        })
        val ids = shelves.flatMap { it.items }.mapNotNull { it.browseId }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun communityPopularityRanksOnlyVerifiedCachedRecordingIdentities() {
        val known = playable.copy(hasCatalogedAsset = false)
        val other = playable.copy(musicBrainzId = "00000000-0000-0000-0000-000000000003",
            title = "Lucky Girl Syndrome", hasCatalogedAsset = true)
        val evidence = listOf(
            ListenBrainzPopularityEvidence.Unknown(other.musicBrainzId),
            ListenBrainzPopularityEvidence.Known(known.musicBrainzId, 1200, 80),
            ListenBrainzPopularityEvidence.Known("00000000-0000-0000-0000-000000000099", 8000, 1000),
        )
        val shelf = canonicalPublicPopularityShelf(
            rows = listOf(release, known, other), evidence = evidence,
            heading = "Popular na comunidade",
        )
        assertEquals("Popular na comunidade", shelf?.title)
        assertEquals(listOf("Magnetic"), shelf?.items?.map { it.title })
        assertEquals("catalog:RECORDING:${known.musicBrainzId}", shelf?.items?.single()?.browseId)
        assertEquals(null, shelf?.items?.single()?.videoId)
        assertFalse(known.hasCatalogedAsset) // metadata-only stays metadata-only
        assertEquals(known, canonicalEditorialRowForCard(listOf(release, known),
            shelf?.items?.single()?.browseId))
        assertEquals(null, canonicalEditorialRowForCard(listOf(release, known), null))
        assertEquals(null, canonicalEditorialRowForCard(listOf(release, known), "local:pick:123"))
        assertEquals(null, canonicalPublicPopularityShelf(
            rows = listOf(known, other),
            evidence = listOf(ListenBrainzPopularityEvidence.Unknown(known.musicBrainzId)),
            heading = "Popular na comunidade",
        ))
    }

    @Test fun publicPopularityPreservesExactRecordingIdentityAcrossMbidCaseVariations() {
        val mbid = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
        val row = playable.copy(musicBrainzId = mbid.uppercase(java.util.Locale.ROOT),
            hasCatalogedAsset = false)
        val evidence = listOf(ListenBrainzPopularityEvidence.Known(mbid, 12, 3))
        assertEquals(listOf(mbid),
            canonicalPublicPopularityCandidates(listOf(row, row.copy(musicBrainzId = mbid))))
        val shelf = canonicalPublicPopularityShelf(
            rows = listOf(row), evidence = evidence, heading = "Popular na comunidade",
        )
        assertEquals("catalog:RECORDING:$mbid", shelf?.items?.single()?.browseId)
        assertEquals(null, shelf?.items?.single()?.videoId)
        assertEquals(row, canonicalEditorialRowForCard(listOf(row), shelf?.items?.single()?.browseId))
    }

    @Test fun weeklySitewideRankUsesOnlyCachedMusicBrainzRecordingRows() {
        val firstId = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
        val otherId = "22ad712e-ce73-9f0c-4e97-9f0e53144411"
        val known = playable.copy(musicBrainzId = firstId.uppercase(java.util.Locale.ROOT),
            hasCatalogedAsset = false)
        val another = playable.copy(musicBrainzId = otherId, title = "Album Title",
            kind = CanonicalSearchKind.RELEASE)
        val ranks = listOf(
            com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording(otherId, 900),
            com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording(firstId, 30),
        )
        val shelf = com.music.bitchord.ui.canonicalSitewideCommunityShelf(
            rows = listOf(known, another), rankings = ranks,
            heading = "Popular na comunidade",
        )
        assertEquals(listOf(known.title), shelf?.items?.map { it.title })
        assertEquals("catalog:RECORDING:$firstId", shelf?.items?.single()?.browseId)
        assertEquals(null, shelf?.items?.single()?.videoId)
        assertEquals(null, com.music.bitchord.ui.canonicalSitewideCommunityShelf(
            rows = listOf(another), rankings = ranks, heading = "Popular na comunidade",
        ))
    }

    @Test fun publicCommunityRecordingsAlwaysNavigateToMetadataOnExplore() {
        val id = "catalog:RECORDING:13dd61c7-ce73-4e97-9f0c-9f0e53144411"
        assertTrue(com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.EXPLORE, "Popular na comunidade", "Popular na comunidade", id,
        ))
        assertFalse(com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.HOME, "Popular na comunidade", "Popular na comunidade", id,
        ))
        assertTrue(com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.HOME, "Para você", "Para você", id, allowHome = true,
        ))
        assertFalse(com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.HOME, "Para você", "Para você", "catalog:ARTIST:bad",
            allowHome = true,
        ))
        assertFalse(com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.EXPLORE, "Lançamentos", "Popular na comunidade", id,
        ))
        assertFalse(com.music.bitchord.ui.canonicalCommunityCardIsMetadataOnly(
            PlaybackSourceType.EXPLORE, "Popular na comunidade", "Popular na comunidade",
            "local:pick:1",
        ))
    }

    @Test fun communityPopularityNeverInventsAlbumOrAudioFromCounts() {
        val known = playable.copy(hasCatalogedAsset = false)
        val listens = listOf(ListenBrainzPopularityEvidence.Known(known.musicBrainzId, 20, 4))
        val shelf = canonicalPublicPopularityShelf(
            rows = listOf(release.copy(musicBrainzId = known.musicBrainzId)),
            evidence = listens, heading = "Popular na comunidade",
        )
        assertEquals(null, shelf)
        assertEquals(null, canonicalPublicPopularityShelf(
            rows = listOf(known),
            evidence = listOf(ListenBrainzPopularityEvidence.Known(known.musicBrainzId, 0, 0)),
            heading = "Popular na comunidade",
        ))
        assertEquals(listOf(known.musicBrainzId),
            canonicalPublicPopularityCandidates(listOf(release, known, known), limit = 40))
    }

    @Test fun communityShelfCannotPretendHistoricalEpIsFreshLaunch() {
        val original = feed(listOf(release, playable), listOf(local), listOf(playable.musicBrainzId))
        val public = com.music.bitchord.data.model.HomeShelf(
            "Popular na comunidade",
            listOf(ShelfItem("Public recording", "", null, null,
                "catalog:RECORDING:11111111-1111-4111-8111-111111111111")),
        )
        val explore = canonicalExploreWithCommunityShelves(
            homeShelves = original, releasesHeading = "Lançamentos",
            trendingHeading = "Em alta", community = public,
        )
        assertEquals(listOf("Popular na comunidade"), explore.map { it.title })
        assertEquals(listOf("Singles e EPs", "Escolha a dedo", "Em alta"),
            original.map { it.title })
        assertEquals("SUPER REAL ME", original.first().items.single().title)
    }

    @Test fun communityDiscoveryPrecedesPersonalFallbackWhenNoPublicReleaseExists() {
        val localOnly = feed(emptyList(), listOf(local))
        val public = com.music.bitchord.data.model.HomeShelf(
            "Popular na comunidade",
            listOf(ShelfItem("Public recording", "", null, null,
                "catalog:RECORDING:11111111-1111-4111-8111-111111111111")),
        )
        assertEquals(listOf("Popular na comunidade"),
            canonicalExploreWithCommunityShelves(localOnly, "Lançamentos", "Em alta", public)
                .map { it.title })
        assertEquals(listOf("Escolha a dedo"),
            canonicalExploreWithCommunityShelves(localOnly, "Lançamentos", "Em alta", null)
                .map { it.title })
    }

    @Test fun publicPopularityDoesNotFlickerOrRefetchForFreshMatchingMbidSet() {
        val ids = listOf("13dd61c7-ce73-4e97-9f0c-9f0e53144411")
        val at = 100_000L
        assertTrue(com.music.bitchord.ui.canonicalPublicPopularityCacheIsFresh(
            requested = ids, previous = ids, fetchedAtMs = at,
            nowMs = at + 60_000L,
        ))
        assertFalse(com.music.bitchord.ui.canonicalPublicPopularityCacheIsFresh(
            requested = ids, previous = ids.reversed() + "another", fetchedAtMs = at,
            nowMs = at + 60_000L,
        ))
        assertFalse(com.music.bitchord.ui.canonicalPublicPopularityCacheIsFresh(
            requested = ids, previous = ids, fetchedAtMs = at,
            nowMs = at + 6 * 60 * 60 * 1_000L,
        ))
        assertFalse(com.music.bitchord.ui.canonicalPublicPopularityCacheIsFresh(
            requested = ids, previous = ids, fetchedAtMs = at,
            nowMs = at - 1,
        ))
        assertFalse(com.music.bitchord.ui.canonicalPublicPopularityCacheIsFresh(
            requested = emptyList(), previous = emptyList(), fetchedAtMs = at,
            nowMs = at,
        ))
    }

    @Test fun editorialPlaybackProvenanceTracksTheActualHomeOrExploreTab() {
        assertEquals(PlaybackSourceType.HOME, canonicalEditorialPlaybackSource(explore = false))
        assertEquals(PlaybackSourceType.EXPLORE, canonicalEditorialPlaybackSource(explore = true))
    }

    @Test fun explorePrefersVerifiedTrendingWhenHomeOnlyHasHistoricalReleases() {
        val home = feed(listOf(release, playable), listOf(local), listOf(playable.musicBrainzId))
        val explore = canonicalExploreShelves(home, releasesHeading = "Lançamentos",
            trendingHeading = "Em alta")
        assertEquals(listOf("Singles e EPs", "Escolha a dedo", "Em alta"),
            home.map { it.title })
        assertEquals(listOf("Em alta"), explore.map { it.title })
        assertEquals(home.last().items, explore.single().items)
        assertTrue(home.none { it.title == "Lançamentos" })
        assertFalse(explore.any { it.title == "Escolha a dedo" })
    }

    @Test fun exploreNeverAppearsEmptyWhenOfflineLibraryHasOnlyVerifiedPicks() {
        val home = feed(emptyList(), listOf(local))
        val explore = canonicalExploreShelves(home, releasesHeading = "Lançamentos",
            trendingHeading = "Em alta")
        assertEquals(listOf("Escolha a dedo"), explore.map { it.title })
        assertEquals(canonicalLocalPickCardId(local), explore.single().items.single().browseId)
    }

    @Test fun exploreDoesNotPresentSavedDownloadAsPublicRelease() {
        val saved = ShelfItem(
            title = "Saved album", subtitle = "Downloaded",
            thumbnailUrl = null, videoId = null, browseId = "local:download:album-1",
        )
        val home = listOf(
            com.music.bitchord.data.model.HomeShelf("Lançamentos", listOf(saved)),
            com.music.bitchord.data.model.HomeShelf(
                "Escolha a dedo",
                listOf(ShelfItem("Local track", "Artist", null, null, "local:pick:valid")),
            ),
        )
        val explore = canonicalExploreShelves(home, "Lançamentos", "Em alta")
        assertEquals(listOf("Escolha a dedo"), explore.map { it.title })
        assertFalse(explore.flatMap { it.items }.any { it.browseId == saved.browseId })
        val onlySaved = canonicalExploreShelves(home.take(1), "Lançamentos", "Em alta")
        assertTrue(onlySaved.isEmpty())
    }

    @Test fun exploreFiltersMixedReleaseShelfInsteadOfLeakingSavedCollections() {
        val downloaded = ShelfItem("Saved", "Downloaded", null, null, "local:download:42")
        val verified = ShelfItem("Official", "EP", null, null,
            "catalog:RELEASE_GROUP:11111111-1111-4111-8111-111111111111")
        val home = listOf(
            com.music.bitchord.data.model.HomeShelf("Lançamentos", listOf(downloaded, verified)),
        )
        val explore = canonicalExploreShelves(home, "Lançamentos", "Em alta")
        assertEquals(listOf(verified), explore.single().items)
        assertEquals(listOf(downloaded, verified), home.single().items)
    }

    @Test fun exploreFallsBackToEvidenceBasedTrendingWhenNoReleaseIsKnown() {
        val home = feed(listOf(playable), trending = listOf(playable.musicBrainzId))
        val explore = canonicalExploreShelves(home, releasesHeading = "Lançamentos",
            trendingHeading = "Em alta")
        assertEquals(listOf("Em alta"), explore.map { it.title })
    }

    @Test fun showsNativeReleasePickAndEvidenceBasedTrendingShelvesInEditorialOrder() {
        val shelves = feed(listOf(release.copy(releaseDate = "2026-10-02"), playable),
            listOf(local), listOf(playable.musicBrainzId))
        assertEquals(listOf("Lançamentos", "Escolha a dedo", "Em alta"), shelves.map { it.title })
        assertEquals("catalog:RELEASE_GROUP:${release.musicBrainzId}", shelves[0].items.single().browseId)
        assertEquals(null, shelves[0].items.single().videoId)
        assertEquals(canonicalLocalPickCardId(local), shelves[1].items.single().browseId)
        assertEquals(null, shelves[1].items.single().videoId)
        assertEquals("catalog:RECORDING:${playable.musicBrainzId}", shelves[2].items.single().browseId)
    }

    @Test fun unclassifiedReleaseGroupsAndUntypedEditionsCannotPretendToBeNewAlbums() {
        val unknownGroup = release.copy(
            musicBrainzId = "right-now-other",
            title = "Right Now", releaseKind = "Other",
        )
        val unknownEdition = release.copy(
            musicBrainzId = "right-now-edition",
            kind = CanonicalSearchKind.RELEASE, releaseKind = null,
        )
        val recording = playable.copy(title = "Hype Boy")
        val knownSingle = release.copy(
            musicBrainzId = "certified-single", title = "Super Shy", releaseKind = "Single", releaseDate = "2026-10-05",
        )
        val shelves = feed(listOf(unknownGroup, unknownEdition, recording, release, knownSingle))
        assertEquals(listOf("Lançamentos", "Singles e EPs"), shelves.map { it.title })
        assertEquals("Super Shy", shelves[0].items.single().title)
        assertEquals("SUPER REAL ME", shelves[1].items.single().title)
        assertTrue(shelves.flatMap { it.items }.all {
            it.browseId!!.contains("RELEASE_GROUP")
        })
    }

    @Test fun cannotClaimChartsWithoutListeningEvidenceOrAudioAsset() {
        val shelves = feed(listOf(release.copy(releaseDate = "2026-10-02"),
            playable.copy(hasCatalogedAsset = false)),
            listOf(local.copy(localUri = null)), listOf(playable.musicBrainzId))
        assertEquals(listOf("Lançamentos"), shelves.map { it.title })
        assertFalse(shelves.any { it.title == "Em alta" })
    }

    @Test fun knownFutureEditionsAreNotPromotedAndReleaseGroupTakesPrecedence() {
        val future = release.copy(musicBrainzId = "future", title = "Album 2027", releaseDate = "2027-01-01")
        val older = release.copy(musicBrainzId = "older", title = "Older", releaseDate = "2020-01-01")
        val edition = release.copy(kind = CanonicalSearchKind.RELEASE, musicBrainzId = "edition")
        val shelves = feed(listOf(
            older, future, edition, release.copy(releaseDate = "2026-10-04"),
        ))
        assertEquals("SUPER REAL ME", shelves.first().items.single().title)
        assertTrue(shelves.first().items.none { it.title == "Older" })
        assertTrue(shelves.flatMap { it.items }.any { it.title == "Older" })
        assertTrue(shelves.flatMap { it.items }.none { it.title == "Album 2027" })
    }

    @Test fun existingReleasedEditionIsVisibleWhenEveryCachedGroupIsFuture() {
        val futureGroup = release.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000009",
            title = "Future release", releaseDate = "2027-01-01",
        )
        val releasedEdition = release.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000010",
            kind = CanonicalSearchKind.RELEASE, title = "Existing EP",
            releaseDate = "2024-03-25",
        )
        val shelves = feed(listOf(futureGroup, releasedEdition))
        assertEquals(listOf("Singles e EPs"), shelves.map { it.title })
        assertEquals("catalog:RELEASE:${releasedEdition.musicBrainzId}",
            shelves.single().items.single().browseId)
    }

    @Test fun cachedInvalidDatesCannotEnterHistoricalAlbumAndSingleShelves() {
        val invalid = listOf(
            release.copy(musicBrainzId = "11111111-1111-4111-8111-111111111111",
                title = "Impossible day", releaseKind = "Album",
                releaseDate = "2026-02-30"),
            release.copy(musicBrainzId = "22222222-2222-4222-8222-222222222222",
                title = "Impossible month", releaseKind = "Single",
                releaseDate = "2025-99"),
            release.copy(musicBrainzId = "33333333-3333-4333-8333-333333333333",
                title = "Known future", releaseKind = "Album",
                releaseDate = "2027-01-01"),
        )
        val partial = release.copy(
            musicBrainzId = "44444444-4444-4444-8444-444444444444",
            title = "Known year only", releaseKind = "Album", releaseDate = "2025",
        )
        val rows = feed(invalid + partial)
        assertEquals(listOf("Álbuns para descobrir"), rows.map { it.title })
        assertEquals("Known year only", rows.single().items.single().title)
        assertTrue(rows.none { it.title == "Lançamentos" })
    }

    @Test fun locallyRepeatedSongsCanTrendWithoutAnyMusicBrainzMatch() {
        val shelves = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = emptyList(),
            localPicks = listOf(local), trendingRecordingMbids = emptyList(),
            trendingLocalPicks = listOf(local),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
        )
        assertEquals(listOf("Escolha a dedo", "Em alta"), shelves.map { it.title })
        assertEquals(canonicalLocalPickCardId(local), shelves.last().items.single().browseId)
    }

    @Test fun distinctFilesWithTheSameLegacySongIdKeepDistinctPrivateCardKeys() {
        val second = local.copy(
            title = "Magnetic (Live)", localUri = "content://media/audio/456",
        )
        val firstCardId = canonicalLocalPickCardId(local)
        val secondCardId = canonicalLocalPickCardId(second)
        assertFalse(firstCardId == secondCardId)
        assertTrue(firstCardId.startsWith("local:pick:"))
        assertFalse(firstCardId.contains("content://"))
        assertFalse(firstCardId.contains(local.videoId))
        val items = feed(emptyList(), listOf(local, second)).single().items
        assertEquals(2, items.size)
        assertEquals(2, items.map { it.browseId }.distinct().size)
    }

    @Test fun picksIncludeVerifiedServerAssetsWhenDeviceHasNoLocalFiles() {
        val remote = CanonicalServerSearchRow(
            recordingId = "recording-nav-1", mediaAssetId = "asset-nav-1",
            providerKey = "opensubsonic:test-account", title = "Magnetic", artistDisplay = "ILLIT",
            artworkUri = "https://example.com/cover.png",
        )
        val result = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = emptyList(), localPicks = emptyList(),
            trendingRecordingMbids = emptyList(),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
            remotePicks = listOf(remote),
        )
        assertEquals(listOf("Escolha a dedo"), result.map { it.title })
        val card = result.single().items.single()
        assertEquals("Magnetic", card.title)
        assertEquals(remote.artworkUri, card.thumbnailUrl)
        assertEquals(canonicalRemotePickCardId(remote), card.browseId)
        assertEquals(null, card.videoId)
        assertFalse(card.browseId!!.contains(remote.mediaAssetId))
    }

    @Test fun twoServerAssetsForSameRecordingRetainIndependentNavigationKeys() {
        val one = CanonicalServerSearchRow(
            recordingId = "recording-1", mediaAssetId = "asset-1",
            providerKey = "opensubsonic:account", title = "OMG", artistDisplay = "NewJeans",
        )
        val two = one.copy(mediaAssetId = "asset-2")
        assertFalse(canonicalRemotePickCardId(one) == canonicalRemotePickCardId(two))
        val cards = canonicalCuratedHomeShelves(
            savedReleases = emptyList(), metadata = emptyList(), localPicks = emptyList(),
            trendingRecordingMbids = emptyList(), remotePicks = listOf(one, two),
            releasesHeading = "Lançamentos", picksHeading = "Escolha a dedo",
            trendingHeading = "Em alta", todayIsoDate = "2026-10-25",
        ).single().items
        assertEquals(2, cards.map { it.browseId }.distinct().size)
    }

    @Test fun onlyTrustedLocalUrisCanEnterPickCarousel() {
        val candidates = listOf(
            local,
            local.copy(videoId = "remote", localUri = "https://invalid/path"),
            local.copy(videoId = "legacy-yt", localUri = null),
            local.copy(videoId = "another", localUri = "file:///Music/another.mp3"),
        )
        val shelf = feed(emptyList(), candidates).single()
        assertEquals("Escolha a dedo", shelf.title)
        assertEquals(2, shelf.items.size)
        assertTrue(shelf.items.all { it.videoId == null && it.browseId!!.startsWith("local:pick:") })
    }

    @Test fun editionCannotBorrowPotentiallyWrongGroupArtworkWhenItsOwnFrontIsMissing() {
        val edition = release.copy(
            kind = CanonicalSearchKind.RELEASE,
            musicBrainzId = "33333333-3333-4333-8333-333333333333",
            coverGroupMbid = "44444444-4444-4444-8444-444444444444",
        )
        // The group's CAA Front may be a video poster from a different
        // edition; a specific Release must show its own sleeve or none.
        assertEquals(
            edition.musicBrainzId to null,
            canonicalCoverLookupTarget(edition),
        )
        assertEquals(
            edition.musicBrainzId to null,
            canonicalCoverLookupTarget(edition.copy(coverGroupMbid = null)),
        )
    }

    @Test fun onlyOneExactArtistGetsDecorativeSearchPortrait() {
        val first = release.copy(kind = CanonicalSearchKind.ARTIST,
            musicBrainzId = "artist-main", title = "ILLIT", artworkUri = null)
        val namesake = first.copy(musicBrainzId = "artist-homonym")
        val unrelated = first.copy(musicBrainzId = "other", title = "ILLIT Remix")
        assertEquals(first, canonicalArtistArtworkCandidate(
            listOf(unrelated, first, namesake), "illit",
        ))
        assertEquals(null, canonicalArtistArtworkCandidate(
            listOf(unrelated), "illit",
        ))
        assertEquals(null, canonicalArtistArtworkCandidate(
            listOf(first.copy(artworkUri = "https://example.com/artist.jpg")), "illit",
        ))
    }

    @Test fun artworkEnrichmentOnlyRequestsIdentitySafeReleaseOrGroupCovers() {
        val edition = release.copy(kind = CanonicalSearchKind.RELEASE,
            musicBrainzId = "00000000-0000-0000-0000-000000000003")
        val artist = release.copy(kind = CanonicalSearchKind.ARTIST,
            musicBrainzId = "00000000-0000-0000-0000-000000000004")
        val recording = release.copy(kind = CanonicalSearchKind.RECORDING,
            musicBrainzId = "00000000-0000-0000-0000-000000000005")
        assertEquals(null to release.musicBrainzId, canonicalCoverLookupTarget(release))
        assertEquals(edition.musicBrainzId to null, canonicalCoverLookupTarget(edition))
        assertEquals(null, canonicalCoverLookupTarget(artist))
        assertEquals(null, canonicalCoverLookupTarget(recording))
        // A recording is permitted a cover only from a MusicBrainz Release
        // explicitly associated with it, never a title-only assumption.
        val linkedRelease = "22222222-2222-4222-8222-222222222222"
        assertEquals(linkedRelease to null, canonicalCoverLookupTarget(
            recording.copy(coverReleaseMbid = linkedRelease),
        ))
        val candidates = canonicalCoverLookupCandidates(
            listOf(artist, release, release, recording, edition),
            limit = 2,
        )
        // Never fan out to MB for unverified recording editions: this could
        // fetch performance-video stills and stall every thumbnail.
        assertEquals(listOf(release, edition), candidates)
        assertEquals(listOf(recording.copy(coverReleaseMbid = linkedRelease)),
            canonicalCoverLookupCandidates(
                listOf(recording.copy(coverReleaseMbid = linkedRelease)), limit = 2))

    }
    @Test fun legacyCaaFront250WithoutJpgIsStillRecognizedAsSmallArtwork() {
        assertTrue(com.music.bitchord.ui.canonicalNeedsCoverRefresh(
            "https://coverartarchive.org/release/00000000-0000-0000-0000-000000000001/front-250"))
        assertTrue(com.music.bitchord.ui.canonicalNeedsCoverRefresh(
            "https://archive.org/download/cover/front-250.jpg"))
        assertFalse(com.music.bitchord.ui.canonicalNeedsCoverRefresh(
            "https://coverartarchive.org/release/00000000-0000-0000-0000-000000000001/front-1200"))
    }


    @Test fun artistAlbumThumb250CanUpgradeWithoutBlankingOrDowngradingVerifiedCover() {
        val id = release.musicBrainzId
        val low = "https://coverartarchive.org/release/$id/front-250"
        val high = "https://coverartarchive.org/release/$id/front-500"
        val artistShelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST,
            listOf(release.copy(artworkUri = low)),
            heading = "EPs",
        )
        val upgraded = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            artistShelves, mapOf(id to (high to "SUPER REAL ME")),
        )
        assertEquals(high, upgraded.single().items.single().thumbnailUrl)
        assertEquals(artistShelves.single().items.single().browseId,
            upgraded.single().items.single().browseId)
        val discardedDowngrade = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            upgraded, mapOf(id to (low to "SUPER REAL ME")),
        )
        assertTrue("Already displayed 500px art must retain its shelf object",
            upgraded === discardedDowngrade)
        val missing = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST,
            listOf(release.copy(artworkUri = null)),
            heading = "EPs",
        )
        val temporary = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            missing, mapOf(id to (low to "SUPER REAL ME")),
        )
        assertEquals(low, temporary.single().items.single().thumbnailUrl)
    }

    @Test fun artistArtworkRefreshEligibilityRequiresAnActualImageUpgrade() {
        val id = release.musicBrainzId
        val low = "https://coverartarchive.org/release/$id/front-250"
        val high = "https://coverartarchive.org/release/$id/front-500"
        assertTrue(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(null, low))
        assertTrue(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(low, high))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(high, low))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(low, low))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptArtworkUpdate(high, high))
    }

    @Test fun laterValidatedOfficialFrontCanCorrectStaleEqualResolutionArtistSleeve() {
        val id = release.musicBrainzId
        val stale = "https://archive.org/earlier-incorrect-front-500.jpg"
        val verified = "https://archive.org/current-official-front-500.jpg"
        val shelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST,
            listOf(release.copy(artworkUri = stale)),
            heading = "EPs",
        )
        val corrected = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            shelves, mapOf(id to (verified to "SUPER REAL ME")),
        )
        assertEquals(verified, corrected.single().items.single().thumbnailUrl)
        assertTrue(corrected === com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            corrected, mapOf(id to (verified to "SUPER REAL ME")),
        ))
        val low = "https://coverartarchive.org/release/$id/front-250"
        assertTrue(corrected === com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            corrected, mapOf(id to (low to "SUPER REAL ME")),
        ))
    }

    @Test fun artistWarmCacheRevalidatesFullSizeGroupSleevesWithoutPaintingOtherOrForeignGroups() {
        val stale500 = "https://archive.org/older-edition-front-500.jpg"
        val official500 = "https://archive.org/verified-official-front-500.jpg"
        val otherId = "00000000-0000-0000-0000-000000000013"
        val foreignId = "00000000-0000-0000-0000-000000000014"
        val rows = listOf(
            release.copy(artworkUri = stale500),
            release.copy(musicBrainzId = otherId, title = "Video release",
                releaseKind = "Other", artworkUri = stale500),
        )
        assertEquals(listOf(release.musicBrainzId),
            com.music.bitchord.ui.canonicalWarmGroupArtworkCandidateMbids(rows, 400))
        val updates = com.music.bitchord.ui.canonicalArtistVerifiedGroupUpdates(
            rows,
            mapOf(release.musicBrainzId to official500,
                otherId to "https://archive.org/video-front-500.jpg",
                foreignId to "https://archive.org/foreign-front-500.jpg"),
        )
        assertEquals(mapOf(release.musicBrainzId to (official500 to release.title)), updates)
        val corrected = com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            rows, updates.mapValues { it.value.first },
        )
        assertEquals(official500, corrected[0].artworkUri)
        assertEquals(stale500, corrected[1].artworkUri)
        assertEquals(corrected, com.music.bitchord.ui.canonicalRowsWithVerifiedGroupCoverBatch(
            corrected, updates.mapValues { it.value.first },
        ))
        val shelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST, rows, heading = "EPs",
        )
        val painted = com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            shelves, updates,
        )
        assertEquals(official500, painted.single().items.single().thumbnailUrl)
    }

    @Test fun verifiedItunesCardCoverReplacesPreviouslyCachedCaa500() {
        val caa500 = "https://coverartarchive.org/release/123/front-500"
        val apple100 = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val old = release.copy(artworkUri = caa500)
        val patched = com.music.bitchord.ui.canonicalArtistRowsWithOfficialCoverUpdates(
            listOf(old), mapOf(old.musicBrainzId to (apple100 to old.title)),
        )
        assertEquals(apple100, patched.single().artworkUri)
        val shelf = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST, listOf(old), heading = "EPs",
        )
        assertEquals(apple100,
            com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
                shelf, mapOf(old.musicBrainzId to (apple100 to old.title)),
            ).single().items.single().thumbnailUrl)
    }

    @Test fun appleAlbumArtworkCanNeverBeReplacedByLateArchiveCoverUpdates() {
        val apple = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
        val archive = "https://coverartarchive.org/release/release/front-500"
        val withApple = release.copy(artworkUri = apple)
        val archivePatch = mapOf(release.musicBrainzId to (archive to release.title))
        assertFalse(com.music.bitchord.ui.canonicalShouldAcceptVerifiedGroupArtwork(
            apple, archive,
        ))
        assertEquals(listOf(withApple),
            com.music.bitchord.ui.canonicalArtistRowsWithOfficialCoverUpdates(
                listOf(withApple), archivePatch,
            ))
        val shelves = com.music.bitchord.ui.canonicalNativeSections(
            CanonicalSearchKind.ARTIST, listOf(withApple), heading = "EPs",
        )
        assertEquals(shelves, com.music.bitchord.ui.canonicalArtistShelvesWithCoverUpdates(
            shelves, archivePatch,
        ))
    }

    @Test fun verifiedArtistNetworkCoverAlsoCorrectsCanonicalRowsAtEqualResolution() {
        val old500 = "https://archive.org/previous-official-front-500.jpg"
        val new500 = "https://archive.org/current-official-front-500.jpg"
        val low250 = "https://coverartarchive.org/release/00000000-0000-0000-0000-000000000001/front-250"
        val video = release.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000023",
            title = "Unrelated performance", releaseKind = "Other", artworkUri = old500,
        )
        val rows = listOf(release.copy(artworkUri = old500), video)
        val official = mapOf(
            release.musicBrainzId to (new500 to "SUPER REAL ME"),
            video.musicBrainzId to ("https://archive.org/performance-500.jpg" to "Performance"),
        )
        val corrected = com.music.bitchord.ui.canonicalArtistRowsWithOfficialCoverUpdates(
            rows, official,
        )
        assertEquals(new500, corrected[0].artworkUri)
        assertEquals(video, corrected[1])
        assertEquals(corrected, com.music.bitchord.ui.canonicalArtistRowsWithOfficialCoverUpdates(
            corrected, official,
        ))
        assertEquals(corrected, com.music.bitchord.ui.canonicalArtistRowsWithOfficialCoverUpdates(
            corrected, mapOf(release.musicBrainzId to (low250 to "Old name")),
        ))
    }

    @Test fun artistLiveAndCompilationRailsNeedRealMusicBrainzSecondaryTypeEvidence() {
        val base = release.copy(
            releaseKind = "Album", secondaryTypes = null,
            musicBrainzId = "00000000-0000-0000-0000-000000000031",
        )
        val live = base.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000032",
            title = "Recorded in Concert", secondaryTypes = "Live",
        )
        val compilation = base.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000033",
            title = "Collected Works", secondaryTypes = "Compilation",
        )
        val ambiguous = base.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000034",
            title = "Two secondary types", secondaryTypes = "Live|Compilation",
        )
        val ep = base.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000035",
            title = "EP with live tracks", releaseKind = "EP", secondaryTypes = "Live",
        )
        val untyped = base.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000036",
            title = "Unclassified live", releaseKind = "Other", secondaryTypes = "Live",
        )
        val titleOnly = base.copy(
            musicBrainzId = "00000000-0000-0000-0000-000000000037",
            title = "Greatest Hits Live", secondaryTypes = null,
        )
        val shelves = com.music.bitchord.ui.canonicalNativeSections(
            parentKind = CanonicalSearchKind.ARTIST,
            rows = listOf(base, live, compilation, ambiguous, ep, untyped, titleOnly),
            heading = "Lançamentos", albumsHeading = "Álbuns",
            singlesHeading = "Singles e EPs",
        )
        assertEquals(listOf("Álbuns", "Singles e EPs", "Álbuns ao vivo", "Coletâneas"),
            shelves.map { it.title })
        assertEquals(setOf(base.musicBrainzId, ambiguous.musicBrainzId, titleOnly.musicBrainzId),
            shelves[0].items.mapNotNull { it.browseId?.removePrefix("catalog:RELEASE_GROUP:") }.toSet())
        assertEquals(listOf(ep.musicBrainzId),
            shelves[1].items.mapNotNull { it.browseId?.removePrefix("catalog:RELEASE_GROUP:") })
        assertEquals(listOf(live.musicBrainzId),
            shelves[2].items.mapNotNull { it.browseId?.removePrefix("catalog:RELEASE_GROUP:") })
        assertEquals(listOf(compilation.musicBrainzId),
            shelves[3].items.mapNotNull { it.browseId?.removePrefix("catalog:RELEASE_GROUP:") })
        val cards = shelves.flatMap { it.items }
        assertEquals(6, cards.size)
        assertEquals(cards.size, cards.map { it.browseId }.distinct().size)
        assertTrue(cards.all { it.videoId == null })
        assertTrue(cards.none { it.browseId?.contains(untyped.musicBrainzId) == true })
        assertTrue(shelves[0].items.any {
            it.title == "Two secondary types" &&
                it.subtitle.contains("Live") && it.subtitle.contains("Compilation")
        })
        assertEquals(
            com.music.bitchord.ui.CanonicalArtistAlbumShelfKind.ALBUM,
            com.music.bitchord.ui.canonicalArtistAlbumShelfKind(untyped),
        )
        assertEquals(
            com.music.bitchord.ui.CanonicalArtistAlbumShelfKind.ALBUM,
            com.music.bitchord.ui.canonicalArtistAlbumShelfKind(ep),
        )
    }
}
