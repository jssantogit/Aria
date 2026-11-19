package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalArtistOfficialReleaseCaptionRow
import com.music.bitchord.data.catalog.CanonicalRecordingReleaseTitleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Artist pages display contextual metadata, never the same credit twice. */
class CanonicalArtistReleaseSubtitleTest {
    private fun link(recording: String, release: String, name: String) =
        CanonicalRecordingReleaseTitleRow(recording, release, name)

    private fun proof(id: String, date: String?) =
        CanonicalArtistReleaseEvidence(id, primaryType = "Album", firstReleaseDate = date)

    @Test fun albumCardsUseOnlyValidatedReleaseYear() {
        assertEquals("2026", canonicalArtistReleaseYearLabel("2026-11-19"))
        assertEquals("2024", canonicalArtistReleaseYearLabel(" 2024 "))
        assertNull(canonicalArtistReleaseYearLabel(null))
        assertNull(canonicalArtistReleaseYearLabel(""))
        assertNull(canonicalArtistReleaseYearLabel("2026-02-30"))
        assertNull(canonicalArtistReleaseYearLabel("2026-11"))
        assertNull(canonicalArtistReleaseYearLabel("KISS N TELL - 2026"))
        assertNull(canonicalArtistReleaseYearLabel("metadata scanned 2026"))
    }

    @Test fun artistTrackUsesExactRecordingToReleaseLinkAndOptionalYear() {
        val labels = canonicalArtistTrackReleaseLabels(
            links = listOf(
                link("recording-a", "release-a", "KISS N TELL - EP"),
                link("recording-b", "release-b", "LEMONADE - The 2nd Album"),
            ),
            eligibleReleaseIds = setOf("release-a", "release-b"),
            evidence = mapOf(
                "release-a" to proof("release-a", "2026-11-19"),
                "release-b" to proof("release-b", "2026-01-05"),
            ),
        )
        assertEquals("KISS N TELL - EP · 2026", labels["recording-a"])
        assertEquals("LEMONADE - The 2nd Album · 2026", labels["recording-b"])
    }

    @Test fun recordingWithAmbiguousEditionsNeverGetsInventedAlbum() {
        val releases = listOf(
            link("recording-a", "edition-1", "Original"),
            link("recording-a", "edition-2", "Deluxe"),
            link("recording-b", "edition-1", "Original"),
            link("recording-b", "edition-1", "Original"), // repeated DB projection
            link("recording-c", "external", "Other server album"),
            link("recording-d", "edition-3", ""),
        )
        val labels = canonicalArtistTrackReleaseLabels(
            releases,
            eligibleReleaseIds = setOf("edition-1", "edition-2", "edition-3"),
            evidence = mapOf("edition-1" to proof("edition-1", null)),
        )
        assertFalse("recording-a" in labels)
        assertEquals("Original", labels["recording-b"])
        assertFalse("recording-c" in labels)
        assertFalse("recording-d" in labels)
        assertEquals(1, labels.size)
    }

    @Test fun verifiedPublicEditionCaptionRequiresRealTitleButNotInventedYear() {
        val linked = CanonicalArtistOfficialReleaseCaptionRow(
            "official-release", "Whiplash - The 5th Mini Album - EP", "2024-10-21",
        )
        assertEquals(
            "Whiplash - The 5th Mini Album - EP · 2024",
            canonicalArtistOfficialReleaseCaption(linked),
        )
        assertEquals("Whiplash - The 5th Mini Album - EP",
            canonicalArtistOfficialReleaseCaption(linked.copy(releaseDate = null)))
        assertNull(canonicalArtistOfficialReleaseCaption(linked.copy(releaseTitle = " ")))
    }

    @Test fun artistLegacyCardsOnlyReadStandaloneYearMetadataSegment() {
        assertEquals("2026", canonicalArtistShelfYearFromSubtitle("EP · aespa · 2026"))
        assertEquals("2025", canonicalArtistShelfYearFromSubtitle("2025"))
        assertEquals("2024", canonicalArtistShelfYearFromSubtitle("Album • 2024"))
        assertNull(canonicalArtistShelfYearFromSubtitle("aespa"))
        assertNull(canonicalArtistShelfYearFromSubtitle("A Summer 2026 Remix"))
        assertNull(canonicalArtistShelfYearFromSubtitle("2024-09-30"))
    }
}
