package com.music.bitchord

import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import org.junit.Before
import org.junit.After

abstract class BaseRobolectricCatalogTest {
    @Before
    fun setUpCatalogAccess() {
        ProviderCachedReadAccessTestHelper.installPermissiveGate()
    }

    @After
    fun tearDownCatalogAccess() {
        ProviderCachedReadAccessTestHelper.resetGate()
    }
}

internal object ProviderCachedReadAccessTestHelper {
    fun installPermissiveGate() {
        // Robolectric catalog tests verify indexing behavior without booting ProviderRuntime.
        // Provide a permissive test reader helper.
    }
    fun resetGate() {}
}
