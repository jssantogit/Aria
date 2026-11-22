package com.music.bitchord

import com.music.bitchord.data.providers.maskedProviderCredentialHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCredentialDisplayHintTest {
    @Test fun onlyLastFourCharactersOfLongTokensAreVisible() {
        val original = "my-personal-account-token-Xy42"
        val hint = maskedProviderCredentialHint(original)
        assertEquals("••••••••Xy42", hint)
        assertFalse(hint!!.contains("my-personal-account"))
        assertFalse(hint.contains(original))
    }

    @Test fun shortOrBlankCredentialsCannotExposeSuffix() {
        assertNull(maskedProviderCredentialHint(""))
        assertNull(maskedProviderCredentialHint("   "))
        assertEquals("••••••••", maskedProviderCredentialHint("abcd"))
        assertEquals("••••••••", maskedProviderCredentialHint("1234567"))
    }

    @Test fun unsafeControlCharactersInTheSuffixAreMasked() {
        assertEquals("••••••••ab••", maskedProviderCredentialHint("secure-1234-ab\\n\\t".replace("\\n", "\n").replace("\\t", "\t")))
    }
}
