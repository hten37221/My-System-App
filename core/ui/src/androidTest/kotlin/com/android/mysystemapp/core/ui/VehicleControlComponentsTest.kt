package com.android.mysystemapp.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.b231001.bmaterial.ccp.rotaryfocus.FocusAreaId
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VehicleControlComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchRow_editableRowTogglesWholeTouchTarget() {
        var requestedValue: Boolean? = null
        composeRule.setContent {
            MySystemTheme {
                VehicleSwitchRow(
                    title = "Auto high beam",
                    checked = false,
                    onCheckedChange = { requestedValue = it },
                    modifier = Modifier.testTag("switch-row"),
                )
            }
        }

        composeRule
            .onNodeWithTag("switch-row")
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, requestedValue) }
    }

    @Test
    fun switchRow_pendingKeepsControlMountedEnabledAndWritable() {
        var requestedValue: Boolean? = null
        composeRule.setContent {
            MySystemTheme {
                VehicleSwitchRow(
                    title = "Lane centering",
                    checked = true,
                    pending = true,
                    pendingDescription = "Applying lane centering",
                    onCheckedChange = { requestedValue = it },
                    modifier = Modifier.testTag("pending-row"),
                )
            }
        }

        composeRule
            .onNodeWithTag("pending-row")
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithContentDescription("Applying lane centering").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(false, requestedValue) }
    }

    @Test
    fun switchRow_uxRestrictedIsDisabledAndExplainsWhy() {
        var writeRequested = false
        composeRule.setContent {
            MySystemTheme {
                VehicleSwitchRow(
                    title = "Lane intervention",
                    checked = true,
                    enabled = false,
                    disabledReason = "Park the vehicle to change this setting.",
                    onCheckedChange = { writeRequested = true },
                    modifier = Modifier.testTag("ux-restricted-row"),
                )
            }
        }

        val restrictedNode = composeRule.onNodeWithTag("ux-restricted-row").fetchSemanticsNode()
        assertTrue(restrictedNode.config.contains(SemanticsProperties.Disabled))
        composeRule
            .onNodeWithText("Park the vehicle to change this setting.")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(writeRequested) }
    }

    @Test
    fun switchRow_readOnlyExposesValueWithoutClickAction() {
        composeRule.setContent {
            MySystemTheme {
                VehicleSwitchRow(
                    title = "Passenger occupied",
                    checked = true,
                    readOnly = true,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("read-only-row"),
                )
            }
        }

        composeRule.onNodeWithTag("read-only-row").assertHasNoClickAction()
        composeRule.onNodeWithText("On").assertIsDisplayed()
    }

    @Test
    fun switchRow_keepsInformationActionAccessible() {
        composeRule.setContent {
            MySystemTheme {
                VehicleSwitchRow(
                    title = "Forward collision warning",
                    checked = true,
                    onCheckedChange = {},
                    infoContent = {
                        VehicleInfoButton(
                            title = "Forward collision warning",
                            body = "Warns about a possible collision ahead.",
                            contentDescription = "Forward collision warning information",
                        )
                    },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Forward collision warning information")
            .assertHasClickAction()
            .performClick()
        // ModalBottomSheet is a scroll container on the automotive-sized test
        // display; ensure the caller-provided body is brought into the
        // viewport before asserting visibility.
        composeRule
            .onNodeWithText("Warns about a possible collision ahead.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun infoFocusItem_hostlessFallbackRemainsTouchAccessible() {
        var clicked = false
        composeRule.setContent {
            MySystemTheme {
                FocusArea(id = FocusAreaId("hostless-info-area")) {
                    VehicleInfoFocusItem(
                        focusId = FocusItemId("hostless-info-item"),
                        title = "Collision warning",
                        onClick = { clicked = true },
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription("Information about Collision warning")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, clicked) }
    }

    @Test
    fun zoneSelectorKeepsAreaSelectionExplicit() {
        var selectedArea: Int? = null
        composeRule.setContent {
            MySystemTheme {
                VehicleZoneSelector(
                    label = "Seat zone",
                    zones =
                        listOf(
                            VehicleZoneOption(1, "Driver"),
                            VehicleZoneOption(2, "Passenger"),
                        ),
                    selectedAreaId = 1,
                    onZoneSelected = { selectedArea = it.areaId },
                )
            }
        }

        composeRule.onNodeWithText("Driver").assertIsSelected()
        composeRule.onNodeWithText("Passenger").performClick()
        composeRule.runOnIdle { assertEquals(2, selectedArea) }
    }

    @Test
    fun infoBottomSheetHasFeatureSpecificContentAndCloseAction() {
        var dismissed = false
        composeRule.setContent {
            MySystemTheme {
                VehicleInfoBottomSheet(
                    title = "Blind spot warning",
                    body = "Warns when a vehicle is detected beside the car.",
                    limitations = "Detection depends on sensor visibility.",
                    dependencies = "Requires the side radar sensors.",
                    closeContentDescription = "Close blind spot information",
                    onDismissRequest = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Blind spot warning").assertIsDisplayed()
        composeRule.onNodeWithText("Detection depends on sensor visibility.").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Close blind spot information")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun errorBanner_exposesRetryAndDismissAsTouchAndRotaryActions() {
        var retried = false
        var dismissed = false
        composeRule.setContent {
            MySystemTheme {
                VehicleErrorBanner(
                    message = "Vehicle service timed out",
                    retryLabel = "Retry vehicle service",
                    dismissContentDescription = "Dismiss vehicle error",
                    onRetry = { retried = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule
            .onNodeWithText("Retry vehicle service")
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithContentDescription("Dismiss vehicle error")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(true, retried)
            assertEquals(true, dismissed)
        }
    }
}

