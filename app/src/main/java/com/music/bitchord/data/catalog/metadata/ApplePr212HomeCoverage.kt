package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException

/** Fill the unchanged PR212 Home only with independently verified Apple
 * releases. A second bounded candidate batch runs only when the first leaves
 * the editorial shelves too sparse. MusicBrainz supplies hints, never cards. */
internal class ApplePr212HomeCoverage(
    private val discovery: AppleHomeEditorialDiscovery = AppleHomeEditorialDiscovery(),
) {
    suspend fun discover(
        seeds: List<AppleHomeEditorialSeed>,
        storefront: String,
        onProgress: suspend (List<ItunesEditorialRelease>) -> Unit = {},
    ): AppleEditorialLoad {
        if (!Regex("[a-z]{2}").matches(storefront))
            return AppleEditorialLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        val planned = AppleEditorialSeedPlanner.select(seeds, 24)
        if (planned.isEmpty()) return AppleEditorialLoad.Success(
            emptyList(), AppleEditorialReadState.NOT_IN_STOREFRONT,
        )
        val proven = linkedMapOf<String, ItunesEditorialRelease>()
        val disputed = mutableSetOf<String>()
        var anySuccess = false
        var incomplete = false
        var scanned = 0

        suspend fun append(batch: List<ItunesEditorialRelease>) {
            var changed = false
            for (release in batch) {
                val id = release.appleScopedId
                if (id in disputed || release.storefront != storefront ||
                    id != "itunes:$storefront:album:${release.appleCollectionId}" ||
                    release.appleCollectionId <= 0L
                ) continue
                val old = proven[id]
                when {
                    old == null -> {
                        proven[id] = release
                        changed = true
                    }
                    old != release -> {
                        proven.remove(id)
                        disputed += id
                        incomplete = true
                        changed = true
                    }
                }
            }
            if (changed) onProgress(proven.values.toList())
        }
        for (batch in planned.chunked(12)) {
            if (proven.size >= 8) break
            scanned += batch.size
            val result = try {
                discovery.discover(
                    seeds = batch, storefront = storefront, maxProbes = batch.size,
                    onProgress = ::append,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { AppleEditorialLoad.Failure(
                  ItunesCatalogFailure.UNAVAILABLE,
              ) }
            when (result) {
                is AppleEditorialLoad.Success -> {
                    anySuccess = true
                    append(result.releases)
                    if (result.state != AppleEditorialReadState.COMPLETE)
                        incomplete = true
                }
                is AppleEditorialLoad.Failure -> incomplete = true
            }
        }
        if (!anySuccess) return AppleEditorialLoad.Failure(
            ItunesCatalogFailure.UNAVAILABLE,
        )
        return AppleEditorialLoad.Success(
            releases = proven.values.toList(),
            state = when {
                incomplete || scanned < planned.size -> AppleEditorialReadState.POSSIBLY_TRUNCATED
                proven.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                else -> AppleEditorialReadState.COMPLETE
            },
        )
    }
}
