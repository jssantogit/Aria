package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.DiscogsVerifiedAppleArtwork
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind

/** A small process-memory optimization, NOT an independent catalog cache.
 * The caller must retrieve CURRENT authorized Discogs evidence before reading.
 * Equality of the entire record and the original capability leases are required.
 * No negative results, credentials, disk storage or freshness extension. */
internal class DiscogsEditorialArtworkMemo(
    private val capacity: Int = 80,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(capacity in 1..80) }

    private data class Entry(
        val evidence: DiscogsRecord,
        val proof: DiscogsVerifiedAppleArtwork,
        val stillAuthorized: () -> Boolean,
        val verifiedAtMs: Long,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    private fun discardExpired(now: Long) {
        entries.entries.removeAll { (_, entry) ->
            now - entry.verifiedAtMs !in 0L..120_000L
        }
    }

    @Synchronized
    fun read(currentEvidence: DiscogsRecord): DiscogsVerifiedAppleArtwork? {
        discardExpired(clockMs())
        if (currentEvidence.link.kind != DiscogsKind.RELEASE) return null
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
        val now = clockMs()
        discardExpired(now)
        if (evidence.link.kind != DiscogsKind.RELEASE ||
            proof.discogsReleaseId != evidence.link.id || !stillAuthorized()
        ) return
        entries.remove(evidence.link.id)
        entries[evidence.link.id] = Entry(evidence, proof, stillAuthorized, now)
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun clear() { entries.clear() }
}
