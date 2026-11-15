package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ApplePublicSearchPageLoad
import com.music.bitchord.data.catalog.metadata.ApplePublicSearchPager
import com.music.bitchord.data.catalog.metadata.ApplePublicSearchSnapshot
import com.music.bitchord.data.catalog.metadata.ApplePublicSearchSource
import com.music.bitchord.data.catalog.metadata.ItunesCatalogEntity
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
import com.music.bitchord.data.catalog.metadata.ItunesPublicSearchSource
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/**
 * Three typed storefront pagers behind PR212's existing Search UI.
 * The public Search API has no offset. This reuses the PR216 verified local
 * pagination and bounded alternate-attribute probes, never invents a cursor.
 */
internal data class ApplePr212SearchPage(
    val rows: List<CanonicalCatalogSearchRow>,
    val trackAlbums: Map<String, ApplePr212CatalogRoute>,
    val hasMore: Boolean,
    val coverageUncertain: Boolean,
)

internal class ApplePr212SearchSession(
    val query: String,
    val storefront: String = "br",
    source: ApplePublicSearchSource = ItunesPublicSearchSource(),
    pageSize: Int = 24,
) {
    private val kinds = listOf(
        ItunesCatalogEntity.ARTIST,
        ItunesCatalogEntity.ALBUM,
        ItunesCatalogEntity.SONG,
    )
    private val pagers = kinds.associateWith { kind ->
        ApplePublicSearchPager(query, kind, storefront, pageSize, source)
    }
    private val successKinds = mutableSetOf<ItunesCatalogEntity>()

    private fun raw(kind: ItunesCatalogEntity): List<ItunesCatalogItem> =
        pagers.getValue(kind).snapshot().visible

    fun snapshot(): ApplePr212SearchPage {
        val artists = raw(ItunesCatalogEntity.ARTIST)
            .mapNotNull(ApplePr212CatalogRows::artist)
        val albums = ItunesEditorialCatalog.assemble(
            storefront, raw(ItunesCatalogEntity.ALBUM),
        ).mapNotNull(ApplePr212CatalogRows::album)
        val trackAlbums = linkedMapOf<String, ApplePr212CatalogRoute>()
        val tracks = raw(ItunesCatalogEntity.SONG).mapNotNull { product ->
            val row = ApplePr212CatalogRows.track(product)
                ?: return@mapNotNull null
            val collection = product.collectionId ?: return@mapNotNull null
            val route = ApplePr212CatalogRoute.parse(
                "itunes:$storefront:album:$collection",
            ) ?: return@mapNotNull null
            if (route.kind != ItunesCatalogItemType.ALBUM) return@mapNotNull null
            trackAlbums[row.musicBrainzId] = route
            row
        }
        val pages = kinds.map { pagers.getValue(it).snapshot() }
        return ApplePr212SearchPage(
            rows = (artists + albums + tracks)
                .distinctBy { it.kind to it.musicBrainzId },
            trackAlbums = trackAlbums,
            hasMore = pages.any { it.hasMore } ||
                kinds.any { it !in successKinds },
            coverageUncertain = pages.any { it.coverageUncertain },
        )
    }

    /**
     * One progress delivery per successful upstream source. A failed class
     * cannot replace other successful artist/album/track results with MB rows.
     */
    suspend fun start(
        onProgress: suspend (ApplePr212SearchPage) -> Unit = {},
    ): ApplePr212SearchPage? {
        for (kind in kinds) {
            val result = pagers.getValue(kind).start()
            if (result is ApplePublicSearchPageLoad.Success) {
                successKinds.add(kind)
                onProgress(snapshot())
            }
        }
        return snapshot().takeIf { successKinds.isNotEmpty() }
    }

    /**
     * Increment each section at most one local page. Once a 200-item result
     * is exhausted, the source's alternate-attribute probe is the only
     * additional network discovery; no pretend offset is generated.
     */
    suspend fun loadMore(
        onProgress: suspend (ApplePr212SearchPage) -> Unit = {},
    ): ApplePr212SearchPage? {
        for (kind in kinds) {
            val pager = pagers.getValue(kind)
            val attempt = when {
                kind !in successKinds -> pager.start()
                pager.snapshot().hasMore -> pager.loadMore()
                else -> continue
            }
            when (attempt) {
                is ApplePublicSearchPageLoad.Success -> {
                    successKinds.add(kind)
                    onProgress(snapshot())
                }
                is ApplePublicSearchPageLoad.Failure -> Unit
            }
        }
        return snapshot().takeIf { successKinds.isNotEmpty() }
    }
}
