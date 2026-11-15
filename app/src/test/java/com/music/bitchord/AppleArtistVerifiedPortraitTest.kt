package com.music.bitchord

import com.music.bitchord.data.canvas.appleArtistStorefrontPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleArtistVerifiedPortraitTest {
    @Test fun heroIsQueriedByExactStorefrontScopedArtistProductNotTitle() {
        assertEquals(
            "https://music.apple.com/br/artist/123456789",
            appleArtistStorefrontPage("br", 123456789L),
        )
        assertNull(appleArtistStorefrontPage("BR", 123456789L))
        assertNull(appleArtistStorefrontPage("br", 0L))
        assertNull(appleArtistStorefrontPage("../us", 123456789L))
        assertNull(appleArtistStorefrontPage("br", -9L))
    }
}
