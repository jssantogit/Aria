package com.music.aria

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Fails early if the pinned framework artifacts cannot initialize. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34, 35], application = android.app.Application::class)
class RobolectricFrameworkAvailabilityTest {
    @Test fun pinnedFrameworkInitializes() {
        assertTrue(RuntimeEnvironment.getApiLevel() in setOf(34, 35))
        assertNotNull(RuntimeEnvironment.getApplication())
    }
}
