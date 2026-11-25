package com.music.aria

import com.music.aria.data.catalog.metadata.MetadataLookupResult
import com.music.aria.data.catalog.metadata.MusicBrainzClient
import com.music.aria.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.aria.data.catalog.metadata.MusicBrainzRateLimiter
import com.music.aria.data.catalog.metadata.MusicBrainzTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzOfficialArtistRecordingTest {
    private val recording = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val release = "11111111-1111-4111-8111-111111111111"
    private val another = "22222222-2222-4222-8222-222222222222"
    private val group = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private data class OfficialEvidence(
        val first: String?, val second: String?, val third: String?,
        val title: String?, val date: String?,
    )

    private suspend fun lookup(video: Boolean, releases: String): OfficialEvidence {
        var inc: String? = null
        val transport = object : MusicBrainzTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>, userAgent: String,
            ): String {
                inc = parameters["inc"]
                return """{"id":"$recording","title":"Official song","video":$video,
                  "artist-credit":[{"name":"Artist","artist":{"id":"33333333-3333-4333-8333-333333333333","name":"Artist"}}],
                  "releases":[$releases]}"""
            }
        }
        val provider = MusicBrainzMetadataEnrichmentProvider(
            MusicBrainzClient(transport = transport,
                rateLimiter = MusicBrainzRateLimiter(
                    nowMillis = { 1_000L }, delayMillis = {},
                ),
            ),
        )
        val result = provider.lookupOfficialAudioRecording(recording)
        assertTrue(result is MetadataLookupResult.Success)
        val verified = (result as MetadataLookupResult.Success).value
        return OfficialEvidence(
            verified.officialAudioReleaseMbid,
            verified.officialAudioReleaseGroupMbid,
            inc,
            verified.officialAudioReleaseTitle,
            verified.officialAudioReleaseDate,
        )
    }

    @Test fun retainsOnlyOfficialAlbumSingleOrEpEditionForArtistChart() = runBlocking {
        val unofficial = """{"id":"$another","status":"Bootleg",
          "release-group":{"primary-type":"Album"}}"""
        val official = """{"id":"$release","status":"Official",
          "release-group":{"id":"$group","primary-type":"Single"}}"""
        val (selected, confirmedGroup, inc) = lookup(false, "$unofficial,$official")
        assertEquals(group, confirmedGroup)
        assertEquals(release, selected)
        assertEquals("artist-credits+releases+release-groups", inc)
    }

    @Test fun officialRecordingProvidesExactEditionTitleAndDateWithoutAnotherLookup() = runBlocking {
        val bootleg = """{"id":"$another","title":"Wrong album",
          "date":"2010-01-01","status":"Bootleg",
          "release-group":{"primary-type":"Album"}}"""
        val official = """{"id":"$release","title":"Get Up",
          "date":"2023-07-21","status":"Official",
          "release-group":{"id":"$group","primary-type":"EP"}}"""
        val result = lookup(false, "$bootleg,$official")
        assertEquals(release, result.first)
        assertEquals("Get Up", result.title)
        assertEquals("2023-07-21", result.date)
        val rejected = lookup(true, official)
        assertNull(rejected.first)
        assertNull(rejected.title)
        assertNull(rejected.date)
    }

    @Test fun rejectsVideoRemixCompilationAndMissingReleaseProof() = runBlocking {
        val live = """{"id":"$release","status":"Official",
          "release-group":{"primary-type":"Album","secondary-types":["Live"]}}"""
        val compilation = """{"id":"$another","status":"Official",
          "release-group":{"primary-type":"Album","secondary-types":["Compilation"]}}"""
        assertNull(lookup(false, "$live,$compilation").first)
        assertNull(lookup(true, """{"id":"$release","status":"Official",
          "release-group":{"primary-type":"Album"}}""").first)
        assertNull(lookup(false, "").first)
    }
}
