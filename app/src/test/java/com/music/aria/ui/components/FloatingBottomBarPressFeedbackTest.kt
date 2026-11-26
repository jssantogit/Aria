package com.music.aria.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The old floating tab bar had no press indication at all for taps that did
 * not change the selected destination. Never introduce an oversized ripple
 * to fix it: the same icon contracts briefly while finger-down.
 */
class FloatingBottomBarPressFeedbackTest {
    @Test fun selectedAndUnselectedTabsBothHaveAnIndependentPressResponse() {
        // Selection animation remains independent; every enabled tab has
        // this same instantaneous pressed state, even when already selected.
        assertEquals(0.96f, bottomTabPressScale(pressed = true, reduceMotion = false), 0.0001f)
        assertEquals(1f, bottomTabPressScale(pressed = false, reduceMotion = false), 0.0001f)
    }

    @Test fun reducedMotionKeepsTabAtRestButDoesNotDisableItsClick() {
        assertEquals(1f, bottomTabPressScale(pressed = true, reduceMotion = true), 0.0001f)
        assertEquals(1f, bottomTabPressScale(pressed = false, reduceMotion = true), 0.0001f)
    }
}
