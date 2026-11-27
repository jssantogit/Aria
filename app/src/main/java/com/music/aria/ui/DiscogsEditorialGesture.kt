package com.music.aria.ui

import com.music.aria.data.catalog.metadata.discogs.*
import kotlinx.coroutines.CancellationException

/** The same bounded gesture drives the Android session and CI observations. */
internal data class DiscogsEditorialGesture<T>(
    val state: T,
    val consumedCandidates: Int,
    val chunksAttempted: Int,
    val transportException: Boolean,
)

private fun consumedDiscogsPositions(
    read: DiscogsGranularRead, cursor: DiscogsEditorialCursor,
): Int = when (read) {
    is DiscogsGranularRead.Page -> read.consumedCandidates
    is DiscogsGranularRead.Interrupted -> if (read.retryCursor.page == cursor.page)
        (read.retryCursor.candidatePosition - cursor.candidatePosition).coerceAtLeast(0)
    else 0
}

internal suspend fun scanDiscogsHomeGesture(
    initial: DiscogsHomePageSnapshot,
    queries: List<DiscogsEditorialQuery>,
    scanner: DiscogsNativeGenreEditorial,
    stillCurrent: () -> Boolean = { true },
    onVerified: suspend (DiscogsEditorialCandidate) -> Unit = {},
): DiscogsEditorialGesture<DiscogsHomePageSnapshot>? {
    if (queries.size != initial.cursors.size || !stillCurrent()) return null
    var state = initial
    var consumed = 0
    var chunks = 0
    var transportException = false
    for (batch in 1..3) {
        if (!stillCurrent()) return null
        val remainingTarget = DISCOGS_SCROLL_VERIFIED_TARGET -
            (state.releases.size - initial.releases.size)
        if (remainingTarget <= 0) break
        val index = state.nextQueryIndex ?: break
        val cursor = state.cursors.getOrNull(index) ?: break
        val query = queries.getOrNull(index) ?: break
        val read = try {
            scanner.discoverGranular(
                query = query, cursor = cursor,
                knownMasterIds = state.knownMasterIds,
                maxCandidates = 4,
                maxVerifiedReleases = minOf(4, remainingTarget),
                onVerified = { if (stillCurrent()) onVerified(it) },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              transportException = true
              DiscogsGranularRead.Interrupted(
                  emptyList(), cursor, DiscogsEditorialResult.Unavailable,
              )
          }
        if (!stillCurrent()) return null
        chunks++
        consumed += consumedDiscogsPositions(read, cursor)
        state = advanceDiscogsHomePage(state, index, read)
            ?: state.copy(loading = false, failed = true)
        if (state.failed || !state.hasMore ||
            state.releases.size - initial.releases.size >= DISCOGS_SCROLL_VERIFIED_TARGET
        ) break
    }
    return DiscogsEditorialGesture(
        state.copy(loading = false), consumed, chunks, transportException,
    )
}

internal suspend fun scanDiscogsGenreGesture(
    initial: DiscogsGenrePageSnapshot,
    tag: String,
    queries: List<DiscogsEditorialQuery>,
    scanner: DiscogsNativeGenreEditorial,
    stillCurrent: () -> Boolean = { true },
    onVerified: suspend (DiscogsEditorialCandidate) -> Unit = {},
): DiscogsEditorialGesture<DiscogsGenrePageSnapshot>? {
    if (initial.tag != tag || !stillCurrent()) return null
    var state = initial
    var consumed = 0
    var chunks = 0
    var transportException = false
    for (batch in 1..3) {
        if (!stillCurrent()) return null
        val remaining = 12 - consumed
        val remainingTarget = DISCOGS_SCROLL_VERIFIED_TARGET -
            (state.releases.size - initial.releases.size)
        if (remaining <= 0 || remainingTarget <= 0) break
        val cursor = state.cursor ?: break
        val query = queries.getOrNull(state.queryIndex) ?: break
        val read = try {
            scanner.discoverGranular(
                query = query, cursor = cursor,
                knownMasterIds = state.knownMasterIds,
                maxCandidates = remaining,
                maxVerifiedReleases = minOf(remaining, remainingTarget),
                onVerified = { if (stillCurrent()) onVerified(it) },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              transportException = true
              DiscogsGranularRead.Interrupted(
                  emptyList(), cursor, DiscogsEditorialResult.Unavailable,
              )
          }
        if (!stillCurrent()) return null
        chunks++
        val progressed = consumedDiscogsPositions(read, cursor)
        consumed += progressed
        val previousQueryIndex = state.queryIndex
        state = advanceDiscogsGenrePage(state, tag, queries.size, read)
            ?: state.copy(loading = false, failed = true)
        if (state.failed || !state.hasMore ||
            state.releases.size - initial.releases.size >= DISCOGS_SCROLL_VERIFIED_TARGET ||
            consumed >= 12 ||
            (progressed == 0 && state.cursor == cursor &&
                state.queryIndex == previousQueryIndex)
        ) break
    }
    return DiscogsEditorialGesture(
        state.copy(loading = false), consumed, chunks, transportException,
    )
}

/** The exact initial Discogs genre sampler, shared with the parity matrix. */
internal data class DiscogsGenreLanding(
    val releases: List<DiscogsEditorialCandidate>,
    val checkpoints: Map<Int, DiscogsEditorialCursor>,
    val failure: DiscogsEditorialResult?,
    val queriesAttempted: Int,
) {
    fun paging(tag: String) = DiscogsGenrePageSnapshot(
        tag = tag, cursor = checkpoints[0] ?: DiscogsEditorialCursor(),
        knownMasterIds = releases.map { it.masterId }.toSet(),
        pendingLandingCursors = checkpoints,
    )
}

internal suspend fun scanDiscogsGenreLanding(
    queries: List<DiscogsEditorialQuery>,
    scanner: DiscogsNativeGenreEditorial,
    stillCurrent: () -> Boolean = { true },
    onProgress: suspend (List<DiscogsEditorialCandidate>) -> Unit = {},
): DiscogsGenreLanding? {
    if (!stillCurrent()) return null
    val verified = linkedMapOf<Long, DiscogsEditorialCandidate>()
    val checkpoints = mutableMapOf<Int, DiscogsEditorialCursor>()
    var failure: DiscogsEditorialResult? = null
    var attempted = 0
    for ((index, query) in queries.withIndex()) {
        if (!stillCurrent()) return null
        if (verified.size >= 6) break
        val budget = discogsMultiStylePageBudget(verified.size, queries.size - index - 1)
        attempted++
        val result = try {
            scanner.discoverDistinct(
                query, maxReleases = budget, knownMasterIds = verified.keys.toSet(),
                onCheckpoint = { checkpoints[index] = it },
                onVerified = { candidate ->
                    if (stillCurrent() && verified.putIfAbsent(candidate.masterId, candidate) == null)
                        onProgress(verified.values.toList())
                },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { DiscogsEditorialResult.Unavailable }
        if (!stillCurrent()) return null
        when (result) {
            is DiscogsEditorialResult.Verified -> {
                result.releases.forEach { verified.putIfAbsent(it.masterId, it) }
                // Early partial responses intentionally lack a signed checkpoint.
                // Do not start more predicates immediately after that interruption.
                if (index !in checkpoints) {
                    failure = DiscogsEditorialResult.Unavailable
                    break
                }
            }
            DiscogsEditorialResult.Unauthorized, DiscogsEditorialResult.Disabled,
            is DiscogsEditorialResult.Throttled -> {
                failure = result
                break
            }
            else -> failure = result
        }
    }
    return DiscogsGenreLanding(verified.values.toList(), checkpoints.toMap(), failure, attempted)
}
