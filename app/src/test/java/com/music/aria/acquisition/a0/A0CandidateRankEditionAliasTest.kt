package com.music.aria.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0CandidateRankEditionAliasTest {
    private fun match(query:String,name:String):A0CandidateRank.Ranking =
        A0CandidateRank.rank(
            searchPhrase=query,
            name=name,
            declaredBitrateKbps=900,
            remotePath="Shared\\BTS\\Proof\\$name",
            format="flac",
            declaredDurationSeconds=220,
            expectedDurationSeconds=220,
            expectedAlbum="Proof",
            sizeBytes=27_000_000L
        )

    @Test fun demoVerAndDemoVersionShareExactCanonicalEditionTerms() {
        for ((requested,file) in listOf(
            "BTS - Epiphany (Jin Demo Ver.)" to
                "BTS - Epiphany (Jin Demo Version).flac",
            "BTS - Epiphany (Jin Demo Version)" to
                "BTS - Epiphany (Jin Demo Ver.).flac"
        )) {
            val result=match(requested,file)
            assertEquals(A0CandidateRank.Match.ALL_TERMS,result.match)
            assertTrue(result.score>=65)
            assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings)
            assertFalse(A0CandidateRank.Warning.VERSION_MISMATCH in result.warnings)
        }
    }

    @Test fun acapellaAndACappellaMatchWithoutRelaxingTitleOrVersion() {
        val result=match("BTS - Still With You (Acapella)",
            "BTS - Still With You (A Cappella).flac")
        assertEquals(A0CandidateRank.Match.ALL_TERMS,result.match)
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in result.warnings)
        val inverse=match("BTS - Still With You (A Cappella)",
            "BTS - Still With You (Acapella).flac")
        assertEquals(A0CandidateRank.Match.ALL_TERMS,inverse.match)
        assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in inverse.warnings)
    }

    @Test fun missingEditionWordsOrUnrequestedDemoStillFailClosed() {
        val missing=match("BTS - Epiphany (Jin Demo Ver.)",
            "BTS - Epiphany.flac")
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,missing.match)
        val extra=match("BTS - Epiphany",
            "BTS - Epiphany (Demo Version).flac")
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in extra.warnings)
    }

    @Test fun ordinaryTitleWordVerIsNotAContextFreeVersionAlias() {
        val mismatched=match("BTS - Quiero Ver","BTS - Quiero Version.flac")
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,mismatched.match)
        val ambiguous=match("BTS - Epiphany (Jin Demo Ver.)",
            "BTS - Epiphany (Demo Version).flac")
        assertNotEquals(A0CandidateRank.Match.ALL_TERMS,ambiguous.match)
    }

    private fun albumVersionWord(folder:String,file:String="01 - Signal.flac",
        album:String="Go Live"):A0CandidateRank.Ranking =
        A0CandidateRank.rank(
            searchPhrase="Example Artist - Signal",
            name=file,declaredBitrateKbps=900,
            remotePath="Shared\\Example Artist\\$folder\\$file",
            format="flac",declaredDurationSeconds=180,
            expectedDurationSeconds=180,expectedAlbum=album,
            sizeBytes=20_250_000L)

    @Test fun exactCanonicalAlbumNameDoesNotDeclareAnAlternateVersion() {
        for(folder in listOf("Go Live","Example Artist - Go Live")) {
            val rank=albumVersionWord(folder)
            assertEquals(A0CandidateRank.Match.ALL_TERMS,rank.match)
            assertFalse(A0CandidateRank.Warning.VERSION_MISMATCH in rank.warnings)
            assertFalse(A0CandidateRank.Warning.IDENTITY_AMBIGUOUS in rank.warnings)
        }
        // Generalize beyond one particular release or artist.
        val other=albumVersionWord("Radio Days",album="Radio Days")
        assertFalse(A0CandidateRank.Warning.VERSION_MISMATCH in other.warnings)
    }

    @Test fun canonicalAlbumDoesNotExcuseExplicitVersionInFilenameOrExtraFolderWords() {
        val explicit=albumVersionWord("Go Live","01 - Signal (Live).flac")
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in explicit.warnings)
        for(folder in listOf("Go Live (Live)","Go Live - Remix","Live Go",
            "Example Artist - Go Live 2025 Remaster")) {
            assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in
                albumVersionWord(folder).warnings)
        }
        assertTrue(A0CandidateRank.Warning.VERSION_MISMATCH in
            albumVersionWord("Go Live",album="Different Album").warnings)
    }

}
