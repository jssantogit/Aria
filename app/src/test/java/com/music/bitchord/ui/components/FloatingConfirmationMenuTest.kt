package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Regression contract for the expandable-card-based, centered confirmations. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FloatingConfirmationMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun destructiveConfirmationUsesSharedNeutralMenuWithStackedActions() {
        var confirms = 0
        var dismisses = 0
        compose.setContent {
            MaterialTheme {
                ConfirmationAlert(
                    hazeState = null,
                    title = "Excluir condição?",
                    description = "Esta exclusão altera a lógica da playlist.",
                    confirmLabel = "Excluir",
                    destructive = true,
                    onConfirm = { confirms++ },
                    onDismiss = { dismisses++ },
                )
            }
        }
        // Exactly the same rounded, neutral frosted plate as expandable menus;
        // no separate UIKit-style UIAlertController geometry.
        assertEquals(
            nativeContextPlateCornerRadiusDp(NativeActionCardTreatment.FROSTED),
            nativeContextPlateCornerRadiusDp(FLOATING_CONFIRMATION_TREATMENT),
        )
        compose.onNodeWithTag("floating-confirmation-menu").assertExists()
        compose.waitForIdle() // Settle the fade/scale before comparing pixel bounds.
        val heading = compose.onNodeWithText("Excluir condição?").getUnclippedBoundsInRoot()
        val message = compose.onNodeWithText("Esta exclusão altera a lógica da playlist.")
            .getUnclippedBoundsInRoot()
        val destructive = compose.onNodeWithTag("floating-confirmation-confirm")
            .getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("floating-confirmation-dismiss")
            .getUnclippedBoundsInRoot()
        assertTrue(message.top >= heading.top)
        assertTrue("message=$message, confirm=$destructive", destructive.top >= message.bottom - 1.dp)
        assertTrue("confirm=$destructive, cancel=$cancel", cancel.top >= destructive.bottom - 1.dp)
        compose.onNodeWithTag("floating-confirmation-confirm").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, confirms)
            assertEquals(0, dismisses)
        }
    }

    @Test fun cancelPreservesDraftAndDoesNotInvokeDestructiveAction() {
        var confirms = 0
        var dismisses = 0
        compose.setContent {
            MaterialTheme {
                ConfirmationAlert(
                    hazeState = null,
                    title = "Descartar alterações?",
                    description = "O rascunho desta playlist não será salvo.",
                    confirmLabel = "Descartar",
                    dismissLabel = "Continuar editando",
                    destructive = true,
                    onConfirm = { confirms++ },
                    onDismiss = { dismisses++ },
                )
            }
        }
        compose.onNodeWithText("Descartar alterações?").assertExists()
        compose.onNodeWithText("Continuar editando").assertExists()
        compose.onNodeWithTag("floating-confirmation-dismiss").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, dismisses)
        }
    }

    @Test fun nonDestructiveConfirmationRetainsStackedChoiceOrder() {
        compose.setContent {
            MaterialTheme {
                ConfirmationAlert(
                    hazeState = null,
                    title = "Aplicar alterações?",
                    description = "",
                    confirmLabel = "Aplicar",
                    destructive = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Aplicar").assertExists()
        compose.onNodeWithText(RuntimeEnvironment.getApplication<Application>()
            .getString(R.string.cancel)).assertExists()
        compose.onNodeWithText("O rascunho desta playlist não será salvo.").assertDoesNotExist()
    }
}
