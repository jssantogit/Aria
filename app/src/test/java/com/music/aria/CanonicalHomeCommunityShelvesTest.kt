package com.music.aria.ui

import com.music.aria.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.aria.data.catalog.metadata.ListenBrainzSitewideRange
import org.junit.Assert.*
import org.junit.Test

class CanonicalHomeCommunityShelvesTest {
    @Test fun unknownAndExpiredDataNeverBecomeWeeklyTrending() {
        val state = CanonicalSitewideDiscoveryState(
            range = ListenBrainzSitewideRange.WEEK,
            fetchedAtMs = null,
        )
        assertFalse(canonicalSitewideDiscoveryIsFresh(state, System.currentTimeMillis()))
        assertTrue(state.recordings.isEmpty())
    }
}
