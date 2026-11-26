package com.music.bitchord.data.catalog.provider.applemusicweb

/** Exact source album, with its independently verified editorial evidence.
 * Combined-label evidence is not a global genre or identity assertion. */
@kotlinx.serialization.Serializable
internal data class AppleMusicWebCuratedAlbum(
    val item: AppleMusicWebItem,
    val evidence: AppleMusicWebCuratedAlbumEvidence,
    val room: AppleMusicWebRoomRef,
)

@kotlinx.serialization.Serializable
internal data class AppleMusicWebCuratorLanding(
    val tag: String,
    val curator: AppleMusicWebCurator,
    val albums: List<AppleMusicWebCuratedAlbum>,
    val shared: Boolean,
    val provenance: String,
    val searchCandidates: Int,
    val searchReads: Int,
    val searchReason: String,
    val sourceRows: Int,
    val distinctCandidates: Int,
    val rejectedDistinct: Int,
    val rejectedObserved: Int,
    val roomReads: Int,
    val roomFailures: Int,
    /** True if more rooms/pages existed beyond this intentional landing budget.
     * Complete means the bounded read succeeded, not the entire catalog. */
    val bounded: Boolean,
) {
    val exactLabels: Int get() = albums.count { it.evidence == AppleMusicWebCuratedAlbumEvidence.EXACT_GENRE_LABEL }
    val combinedLabels: Int get() = albums.count { it.evidence == AppleMusicWebCuratedAlbumEvidence.SOUL_COMBINED_LABEL }
}
