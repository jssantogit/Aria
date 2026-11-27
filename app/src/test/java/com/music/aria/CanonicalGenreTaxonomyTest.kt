package com.music.aria.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalGenreTaxonomyTest {
    @Test fun genrePreviewRunsTextSearchRatherThanJumpingToCategory() {
        val kpop = canonicalExploreGenreSearchHits("k-pop").first()
        val submitted = mutableListOf<String>()
        com.music.aria.ui.screens.submitCanonicalGenrePreview(kpop, submitted::add)
        assertEquals(listOf("K-pop"), submitted)
        val metal = canonicalExploreGenreSearchHits("metal").first()
        com.music.aria.ui.screens.submitCanonicalGenrePreview(metal, submitted::add)
        assertEquals(listOf("K-pop", "Metal"), submitted)
        assertTrue(metal.browseId?.startsWith("catalog:GENRE:") == true)
    }

    @Test fun exploreIndexRemainsGenreOnlyWhileSubgenresHaveUniqueCanonicalIds() {
        assertEquals(12, canonicalExploreGenres.size)
        assertTrue(canonicalExploreGenres.all { it.parentTag == null })
        assertFalse(canonicalExploreGenres.any { it.tag == "death metal" })
        assertEquals(
            canonicalAllExploreGenres.size,
            canonicalAllExploreGenres.map(::canonicalGenreBrowseId).distinct().size,
        )
        assertEquals("metal", canonicalExploreGenreForBrowseId("catalog:GENRE:death metal")?.parentTag)
        assertNull(canonicalExploreGenreForBrowseId("catalog:GENRE:not-a-genre"))
    }

    @Test fun aGenreShowsSquareNavigationCardsAndAChildShowsOtherStyles() {
        val metal = canonicalExploreGenreForBrowseId("catalog:GENRE:metal")!!
        val death = canonicalExploreGenreForBrowseId("catalog:GENRE:death metal")!!
        val children = canonicalExploreGenreRelationsShelf(metal)!!
        val siblings = canonicalExploreGenreRelationsShelf(death)!!
        assertEquals("Subgêneros", children.title)
        assertEquals("Estilos relacionados", siblings.title)
        assertTrue(children.items.any { it.browseId == canonicalGenreBrowseId(death) })
        assertTrue(siblings.items.none { it.browseId == canonicalGenreBrowseId(death) })
        assertTrue(siblings.items.any { it.title == "Black Metal" })
        assertTrue(children.items.all { it.videoId == null && it.thumbnailUrl == null })
    }

    @Test fun searchIsAccentInsensitiveAndCategoriesAreNotAudio() {
        assertEquals("catalog:GENRE:metal", canonicalExploreGenreSearchHits("metal").first().browseId)
        val death = canonicalExploreGenreSearchHits("death metal").single()
        assertEquals("Death Metal", death.title)
        assertEquals("Categoria", death.subtitle)
        assertTrue(canonicalExploreGenreSearchHits("metal").all { it.subtitle == "Categoria" })
        assertNull(death.videoId)
        assertTrue(canonicalExploreGenreSearchHits("camara").any { it.title == "Música de câmara" })
        assertTrue(canonicalExploreGenreSearchHits("x").isEmpty())
        assertTrue(canonicalExploreGenreSearchHits("fabricated-tag").isEmpty())
    }

    @Test fun relatedEdgesDoNotLeaveTheKnownParent() {
        canonicalExploreSubgenres.forEach { subgenre ->
            assertTrue(canonicalExploreGenres.any { it.tag == subgenre.parentTag })
            val related = canonicalExploreGenreRelationsShelf(subgenre).orEmptyItems()
            assertTrue(related.all { item ->
                val other = canonicalExploreGenreForBrowseId(item.browseId)
                other != null && other.parentTag == subgenre.parentTag &&
                    other.tag != subgenre.tag
            })
        }
    }

    private fun com.music.aria.data.model.HomeShelf?.orEmptyItems() =
        this?.items.orEmpty()

    @Test fun frozenParityMatrixHasExplicitTypedMappingsAndCanonicalRelations() {
        assertEquals(9, com.music.aria.ui.idmParityMainGenres.distinct().size)
        assertEquals(6, com.music.aria.ui.idmParitySubgenres.distinct().size)
        val matrix = com.music.aria.ui.idmParityMainGenres +
            com.music.aria.ui.idmParitySubgenres
        for (tag in matrix) {
            val category = com.music.aria.ui.canonicalAllExploreGenres.single { it.tag == tag }
            assertTrue(com.music.aria.data.catalog.metadata.AppleExploreGenrePolicy.supports(tag))
            assertTrue(com.music.aria.ui.discogsEditorialQueriesForTag(tag).isNotEmpty())
            if (tag in com.music.aria.ui.idmParitySubgenres) {
                assertNotNull(category.parentTag)
                val parent = com.music.aria.ui.canonicalAllExploreGenres.single {
                    it.tag == category.parentTag
                }
                assertTrue(com.music.aria.ui.canonicalExploreGenreRelationsShelf(parent)
                    ?.items.orEmpty().any { it.browseId == "catalog:GENRE:$tag" })
                assertFalse(com.music.aria.ui.canonicalExploreGenreRelationsShelf(category)
                    ?.items.orEmpty().any { it.browseId == "catalog:GENRE:$tag" })
            }
        }
    }

}
