package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.*
import org.junit.Test

class AmsVisualParityPolicyTest {
    @Test fun sourceNativeArtistDoesNotUseNameBasedPortraitOrSignature() {
        assertFalse(nativeArtistAllowsNameBasedAuxiliaryVisuals("apple_music_web:br:artist:10"))
        assertFalse(nativeArtistAllowsNameBasedAuxiliaryVisuals("discogs:artist:10"))
        assertTrue(nativeArtistAllowsNameBasedAuxiliaryVisuals("catalog:ARTIST:example"))
    }
    @Test fun onlyExactTypedAmsArtistGetsPortraitCard() {
        assertTrue(nativeShelfCardIsArtist("apple_music_web:br:artist:10"))
        assertFalse(nativeShelfCardIsArtist("apple_music_web:br:album:10"))
        assertFalse(nativeShelfCardIsArtist("apple_music_web:br:artist:0"))
        assertFalse(nativeShelfCardIsArtist(null))
    }
    @Test fun heldActionRequiresOriginalSnapshotToRemainAdmitted() {
        val card = ShelfItem("Release", "2026", null, null, "apple_music_web:br:album:10")
        val target = AmsShelfHeldTarget(AmsShelfHoldScope(0, null, null), card=card)
        var enabled = true
        var visible = target
        fun allowed(item: AmsShelfHeldTarget) = enabled && item == visible
        assertEquals(target, admittedHeldAction(target, ::allowed))
        visible = target.copy(scope=target.scope.copy(tab=1))
        assertNull(admittedHeldAction(target, ::allowed))
        visible = target
        enabled = false
        assertNull(admittedHeldAction(target, ::allowed))
        assertNull(admittedHeldAction<AmsShelfHeldTarget>(null, ::allowed))
    }
    @Test fun unknownFeaturedReleaseFormatStaysGeneric() {
        val card = ShelfItem("Release","2026",null,null,"apple_music_web:br:album:10")
        assertTrue(appleSourceRecentReleaseNeedsGenericLabel(card))
        assertTrue(appleSourceRecentReleaseNeedsGenericLabel(card.copy(subtitle="")))
        assertFalse(appleSourceRecentReleaseNeedsGenericLabel(card.copy(subtitle="Single · 2026")))
    }
}
