package com.music.bitchord

import com.music.bitchord.ui.applePr212RowsWithVerifiedCover
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** An asynchronous Apple CDN upgrade may only patch its own still-current card. */
class ApplePr212ArtworkPatchTest {
    private val old = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/ab/source.jpg/100x100bb.jpg"
    private val large = old.replace("100x100bb.jpg", "1024x1024bb.jpg")
    private fun release(id: String, artwork: String? = old) = CanonicalCatalogSearchRow(
        musicBrainzId = id,
        kind = CanonicalSearchKind.RELEASE,
        title = "HOW SWEET",
        subtitle = "NewJeans",
        artworkUri = artwork,
    )

    @Test fun upgradeChangesOnlyMatchingStorefrontProductNeverAdjacentTitle() {
        val rows = listOf(
            release("itunes:br:album:12"),
            release("itunes:br:album:13"),
        )
        val patched = applePr212RowsWithVerifiedCover(rows, "itunes:br:album:12", old, large)
        assertEquals(large, patched[0].artworkUri)
        assertSame(rows[1], patched[1])
    }

    @Test fun lateProbeNeverReplacesNewerArtworkOrNonAppleRelease() {
        val newer = release("itunes:br:album:12", old.replace("ab/source", "zz/changed"))
        val rows = listOf(newer, release("catalog:RELEASE:12"))
        assertSame(rows, applePr212RowsWithVerifiedCover(rows, "itunes:br:album:12", old, large))
        assertSame(rows, applePr212RowsWithVerifiedCover(rows, "catalog:RELEASE:12", old, large))
    }

    @Test fun missingDisputedOrUnchangedProofDoesNotRebuildList() {
        val duplicate = listOf(release("itunes:br:album:12"), release("itunes:br:album:12"))
        assertSame(duplicate,
            applePr212RowsWithVerifiedCover(duplicate, "itunes:br:album:12", old, large))
        val single = listOf(release("itunes:br:album:12"))
        assertSame(single,
            applePr212RowsWithVerifiedCover(single, "itunes:br:album:12", old, old))
    }
}
