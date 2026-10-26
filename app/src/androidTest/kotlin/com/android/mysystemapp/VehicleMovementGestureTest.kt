package com.android.mysystemapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.android.mysystemapp.feature.vehiclecontrol.VehicleMovementButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VehicleMovementGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun releaseAndCancelAlwaysSendStop() {
        var starts = 0
        var stops = 0
        compose.setContent {
            MaterialTheme { VehicleMovementButton("Hold", true, { starts++ }, { stops++ }, Modifier.testTag("hold")) }
        }
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, stops)
        }
        compose.onNodeWithTag("hold").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithTag("hold").performTouchInput {
            down(center)
            moveBy(Offset(10f, 0f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(2, starts)
            assertEquals(2, stops)
        }
    }

    @Test fun disabledOrRemovedWhilePressedAlwaysSendsStop() {
        val enabled = mutableStateOf(true)
        val visible = mutableStateOf(true)
        var stops = 0
        compose.setContent {
            MaterialTheme { if (visible.value) VehicleMovementButton("Hold", enabled.value, {}, { stops++ }, Modifier.testTag("hold")) }
        }
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.runOnIdle { enabled.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithTag("hold").performTouchInput { cancel() }
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, stops) }
    }
}
