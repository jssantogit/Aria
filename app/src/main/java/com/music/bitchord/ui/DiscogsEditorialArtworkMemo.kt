package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.DiscogsVerifiedAppleArtwork
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord

/** A small process-memory optimization, NOT an independent catalog cache.
 * The caller must retrieve CURRENT authorized Discogs evidence before reading.
 * Equality of the entire record and the original capability leases are required.
 * No negative results, credentials, disk storage or freshness extension. */
internal class DiscogsEditorialArtworkMemo(private val capacity: Int = 80) {
    init { require(capacity in 1..80) }

    private data class Entry(
        val evidence: DiscogsRecord,
        val proof: DiscogsVerifiedAppleArtwork,
        val stillAuthorized: () -> Boolean,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    @Synchronized
    fun read(currentEvidence: DiscogsRecord): DiscogsVerifiedAppleArtwork? {
        val id = currentEvidence.link.id
        val entry = entries[id] ?: return null
        if (entry.evidence != currentEvidence || !entry.stillAuthorized()) {
            entries.remove(id)
            return null
        }
        // Maintain LRU order without extending source evidence freshness.
        entries.remove(id)
        entries[id] = entry
        return entry.proof
    }

    @Synchronized
    fun remember(
        evidence: DiscogsRecord,
        proof: DiscogsVerifiedAppleArtwork,
        stillAuthorized: () -> Boolean,
    ) {
        if (proof.discogsReleaseId != evidence.link.id || !stillAuthorized()) return
        entries.remove(evidence.link.id)
        entries[evidence.link.id] = Entry(evidence, proof, stillAuthorized)
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun clear() { entries.clear() }
}
