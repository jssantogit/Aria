package com.music.bitchord.data.catalog.metadata

/** Candidate names only; never authoritative Apple IDs, metadata or album artwork. */
internal fun interface AppleArtistTitleHintSource {
    suspend fun suggest(artistName: String, verifiedMbArtistId: String?): List<String>
}
