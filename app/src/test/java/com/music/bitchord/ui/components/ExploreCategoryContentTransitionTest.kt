package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The Explore genre grid and each genre must keep separate frames during a 150ms fade. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExploreCategoryContentTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rootToGenreAndBackCrossfadeWithoutReplacingTheOutgoingPage() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent {
            ExploreCategoryContentTransition(
                category = selected.value,
                reduceMotion = false,
            ) { category ->
                Box(Modifier.size(200.dp).testTag(
                    if (category == null) "explore-grid" else "explore-genre-$category",
                ))
            }
        }
        compose.onNodeWithTag("explore-grid").assertExists()
        compose.mainClock.autoAdvance = false

        compose.runOnIdle { selected.value = "K-pop" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("explore-grid", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("explore-genre-K-pop", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(200L)
        compose.onNodeWithTag("explore-grid", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { selected.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("explore-genre-K-pop", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("explore-grid", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(200L)
        compose.onNodeWithTag("explore-genre-K-pop", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun reducedMotionReplacesCategoryImmediately() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent {
            ExploreCategoryContentTransition(category = selected.value, reduceMotion = true) { category ->
                Box(Modifier.size(200.dp).testTag(
                    if (category == null) "explore-grid" else "explore-genre-$category",
                ))
            }
        }
        compose.onNodeWithTag("explore-grid").assertExists()
        compose.runOnIdle { selected.value = "Jazz" }
        compose.onNodeWithTag("explore-genre-Jazz").assertExists()
        compose.onNodeWithTag("explore-grid").assertDoesNotExist()
    }
}
