package com.music.aria

import com.music.aria.data.providers.BuiltInProviderDescriptors
import com.music.aria.data.providers.BuiltInProviderKeys
import com.music.aria.data.providers.ProviderCapability
import com.music.aria.data.providers.ProviderCapabilityPolicy
import com.music.aria.data.providers.ProviderChoice
import com.music.aria.data.providers.ProviderPolicyGate
import com.music.aria.data.providers.ProviderReadiness
import com.music.aria.ui.canonicalCatalogSearchPagingLease
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCatalogSearchPagingBoundaryTest {
    private val key = BuiltInProviderKeys.MUSICBRAINZ
    private fun policy(search: Boolean, editorial: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(BuiltInProviderDescriptors.musicBrainz(ProviderReadiness.READY)),
        choices = mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(key, search, 0)),
            ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(
                ProviderChoice(key, editorial, 0)),
        ),
    )

    @Test fun initialAlbumRankingUsesCombinedSourceEvidenceWithoutMovingOtherKinds() {
        val groupKind = com.music.aria.ui.screens.CanonicalSearchKind.RELEASE_GROUP
        val editionKind = com.music.aria.ui.screens.CanonicalSearchKind.RELEASE
        val artistKind = com.music.aria.ui.screens.CanonicalSearchKind.ARTIST
        val group = com.music.aria.data.catalog.metadata.MusicBrainzReleaseGroupHit(
            "11111111-1111-4111-8111-111111111111", "Other work", "Album", null, "Artist",
        )
        val edition = com.music.aria.data.catalog.metadata.MusicBrainzReleaseHit(
            "22222222-2222-4222-8222-222222222222", "Exact work", "GB", null, "Artist",
        )
        val groupRow = com.music.aria.ui.screens.CanonicalCatalogSearchRow(
            group.musicBrainzId, groupKind, group.title, group.artistDisplay,
        )
        val editionRow = com.music.aria.ui.screens.CanonicalCatalogSearchRow(
            edition.musicBrainzId, editionKind, "Display edition label", "Artist · GB",
        )
        val artistRow = com.music.aria.ui.screens.CanonicalCatalogSearchRow(
            "33333333-3333-4333-8333-333333333333", artistKind, "Artist", null,
        )
        val rows = listOf(groupRow, artistRow, editionRow)
        org.junit.Assert.assertEquals(listOf(editionRow, artistRow, groupRow),
            com.music.aria.ui.rankMusicBrainzInitialAlbumRows(
                "Exact work Artist", rows, listOf(group), listOf(edition),
            ))
        org.junit.Assert.assertEquals(rows, com.music.aria.ui.rankMusicBrainzInitialAlbumRows(
            "Unrelated term", rows, listOf(group), listOf(edition),
        ))
    }

    @Test fun exactAlbumTitleTiesUseOnlyDistinctLinkedEditionEvidence() {
        val groupKind = com.music.aria.ui.screens.CanonicalSearchKind.RELEASE_GROUP
        val a = com.music.aria.data.catalog.metadata.MusicBrainzReleaseGroupHit(
            "11111111-1111-4111-8111-111111111111", "Same title", "Album", null, "First",
        )
        val b = a.copy(musicBrainzId = "22222222-2222-4222-8222-222222222222", artistDisplay = "Second")
        val unrelated = a.copy(musicBrainzId = "33333333-3333-4333-8333-333333333333", title = "Other title")
        val linked = "44444444-4444-4444-8444-444444444444"
        val another = "55555555-5555-4555-8555-555555555555"
        a.searchLinkedReleaseMbids = setOf(linked)
        b.searchLinkedReleaseMbids = setOf(linked, another)
        unrelated.searchLinkedReleaseMbids = (1..10).map { it.toString() }.toSet()
        val bEdition = com.music.aria.data.catalog.metadata.MusicBrainzReleaseHit(
            another, "Same title", "GB", null, "Second", status = "Official", releaseGroupMbid = b.musicBrainzId,
        )
        val rows = listOf(a, unrelated, b).map {
            com.music.aria.ui.screens.CanonicalCatalogSearchRow(
                it.musicBrainzId, groupKind, it.title, it.artistDisplay,
            )
        }
        val ranked = com.music.aria.ui.rankMusicBrainzInitialAlbumRows(
            "Same title", rows, listOf(a, b, unrelated), listOf(bEdition, bEdition),
        )
        org.junit.Assert.assertEquals(listOf(b.musicBrainzId, a.musicBrainzId, unrelated.musicBrainzId),
            ranked.map { it.musicBrainzId })
        val explicit = com.music.aria.ui.rankMusicBrainzInitialAlbumRows(
            "First Same title", rows, listOf(a, b, unrelated), listOf(bEdition),
        )
        org.junit.Assert.assertEquals(a.musicBrainzId, explicit.first().musicBrainzId)
    }

    @Test fun searchPagesDoNotBorrowAnEditorialOnlyGrant() {
        val gate = ProviderPolicyGate(policy(search = false, editorial = true))
        assertNull(canonicalCatalogSearchPagingLease(gate))
        gate.replace(policy(search = true, editorial = false))
        assertNotNull(canonicalCatalogSearchPagingLease(gate))
        assertNull(canonicalCatalogSearchPagingLease(null))
    }

    @Test fun revocationCancelsPagingWithoutCancellingAuthorizedDiscography() {
        val gate = ProviderPolicyGate(policy(search = true, editorial = true))
        val lease = canonicalCatalogSearchPagingLease(gate)!!
        val pageJob = Job()
        val unregister = lease.cancelJobOnRevoke(pageJob)
        gate.replace(policy(search = true, editorial = false))
        assertTrue(lease.isCurrent())
        assertTrue(pageJob.isActive)
        gate.replace(policy(search = false, editorial = false))
        assertFalse(lease.isCurrent())
        assertTrue(pageJob.isCancelled)
        unregister()
    }
}
