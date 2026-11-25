package com.music.bitchord

import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.ui.canonicalCatalogSearchPagingLease
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
        val groupKind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE_GROUP
        val editionKind = com.music.bitchord.ui.screens.CanonicalSearchKind.RELEASE
        val artistKind = com.music.bitchord.ui.screens.CanonicalSearchKind.ARTIST
        val group = com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit(
            "11111111-1111-4111-8111-111111111111", "Other work", "Album", null, "Artist",
        )
        val edition = com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit(
            "22222222-2222-4222-8222-222222222222", "Exact work", "GB", null, "Artist",
        )
        val groupRow = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            group.musicBrainzId, groupKind, group.title, group.artistDisplay,
        )
        val editionRow = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            edition.musicBrainzId, editionKind, "Display edition label", "Artist · GB",
        )
        val artistRow = com.music.bitchord.ui.screens.CanonicalCatalogSearchRow(
            "33333333-3333-4333-8333-333333333333", artistKind, "Artist", null,
        )
        val rows = listOf(groupRow, artistRow, editionRow)
        org.junit.Assert.assertEquals(listOf(editionRow, artistRow, groupRow),
            com.music.bitchord.ui.rankMusicBrainzInitialAlbumRows(
                "Exact work Artist", rows, listOf(group), listOf(edition),
            ))
        org.junit.Assert.assertEquals(rows, com.music.bitchord.ui.rankMusicBrainzInitialAlbumRows(
            "Unrelated term", rows, listOf(group), listOf(edition),
        ))
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
