package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics

/** Terminal stage per candidate examination, not a count of unique albums.
 * Never export source IDs, barcodes, names, credentials, URLs or query text. */
internal enum class DiscogsArtworkOutcome {
    NO_CURRENT_EVIDENCE,
    INELIGIBLE_EDITION,
    POLICY_DENIED,
    EVIDENCE_CHANGED,
    LOOKUP_TIMEOUT,
    NO_EXACT_APPLE_PRODUCT,
    NO_PROMINENT_IMAGE,
    VERIFIED_PROMINENT,
    ERROR,
}

internal class DiscogsArtworkBatchReport {
    private val counts = mutableMapOf<DiscogsArtworkOutcome, Int>()

    fun record(outcome: DiscogsArtworkOutcome) {
        counts[outcome] = (counts[outcome] ?: 0) + 1
    }

    fun metadata(): Map<String, String> = buildMap {
        put("scope", "candidate_examinations_not_unique_albums")
        for (outcome in DiscogsArtworkOutcome.entries)
            put(outcome.name.lowercase(java.util.Locale.ROOT),
                (counts[outcome] ?: 0).toString())
    }

    fun emit(durationNanos: Long) {
        if (counts.isEmpty()) return
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "discogs_editorial_artwork_batch_v1",
                metadata = metadata(),
                durationNanos = durationNanos.coerceAtLeast(0),
            )
        }
    }
}
