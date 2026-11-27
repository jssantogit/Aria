package com.music.aria.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.music.aria.data.catalog.provider.opensubsonic.OpenSubsonicAuthMode
import com.music.aria.ui.components.NativeSettingsPrimaryAction
import com.music.aria.ui.theme.AriaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProviderInlineFormsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editableSearchStyleFieldsEnableLoginAfterEnteringCredentials() {
        val user = mutableStateOf("")
        val password = mutableStateOf("")
        compose.setContent {
            AriaTheme {
                Column {
                    ProviderInlineField("Usuário", user.value, { user.value = it }, enabled = true)
                    ProviderInlineField("Senha", password.value, { password.value = it },
                        enabled = true, secret = true)
                    NativeSettingsPrimaryAction(
                        label = "Entrar",
                        onClick = {},
                        enabled = user.value.isNotBlank() && password.value.isNotBlank(),
                        tag = "provider-login-ready",
                    )
                }
            }
        }
        compose.onNodeWithTag("provider-login-ready").assertIsNotEnabled()
        compose.onNodeWithTag("providers-inline-field-usuário").performTextInput("listener")
        compose.onNodeWithTag("provider-login-ready").assertIsNotEnabled()
        compose.onNodeWithTag("providers-inline-field-senha").performTextInput("password-example")
        compose.onNodeWithTag("provider-login-ready").assertIsEnabled()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("listener", user.value)
            org.junit.Assert.assertEquals("password-example", password.value)
        }
    }

    @Test fun navidromeLoginRequiresFieldsButReusesStoredCredentialWhenUnchanged() {
        val key = OpenSubsonicAuthMode.API_KEY
        val pass = OpenSubsonicAuthMode.PASSWORD
        assertFalse(navidromeAccountReady("", key, "", "secret", false))
        assertFalse(navidromeAccountReady("https://music.example", key, "", "", false))
        assertTrue(navidromeAccountReady("https://music.example", key, "", "secret", false))
        assertTrue(navidromeAccountReady("https://music.example", key, "", "", true))
        assertFalse(navidromeAccountReady("https://music.example", pass, "", "secret", false))
        assertTrue(navidromeAccountReady("https://music.example", pass, "user", "secret", false))
        assertTrue(navidromeAccountReady("https://music.example", pass, "user", "", true))
    }
}
