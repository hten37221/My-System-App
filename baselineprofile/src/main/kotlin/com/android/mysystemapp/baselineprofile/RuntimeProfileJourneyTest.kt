package com.android.mysystemapp.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Test
import org.junit.runner.RunWith

internal const val PROFILE_TARGET_PACKAGE = "com.android.mysystemapp"

internal fun vehicleProfileIntent() =
    Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(PROFILE_TARGET_PACKAGE, "$PROFILE_TARGET_PACKAGE.MainActivity")
    }

/**
 * Journey only, with no compilation or profile synthesis. On images whose UiAutomation shell
 * rejects package compilation, the host resets ART, runs these journeys and exports ART's actual
 * `pm dump-profiles --dump-classes-and-methods` output. A passing journey alone is not a profile.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RuntimeProfileJourneyTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun startup() {
        launchWorkspace()
    }

    @Test
    fun vehicleControls() {
        launchWorkspace()
        visitVehicleWorkspace(device)
    }

    private fun launchWorkspace() {
        InstrumentationRegistry.getInstrumentation().context.startActivity(
            vehicleProfileIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        check(device.wait(Until.hasObject(By.pkg(PROFILE_TARGET_PACKAGE)), 15_000)) {
            "Vehicle workspace did not launch"
        }
        device.waitForIdle()
    }
}

internal fun visitVehicleWorkspace(device: UiDevice) {
    val groups =
        listOf(
            "Doors & glass" to "Cửa & kính",
            "Seats" to "Ghế ngồi",
            "Climate" to "Điều hòa",
            "Lighting" to "Đèn xe",
            "Assistance" to "Hỗ trợ lái",
            "Modes" to "Chế độ",
        )
    for ((english, vietnamese) in groups) {
        val selector = By.desc(Pattern.compile("^(?:${Pattern.quote(english)}|${Pattern.quote(vietnamese)})$"))
        check(device.wait(Until.hasObject(selector), 10_000)) { "Workspace group missing: $english" }
        device.findObject(selector).click()
        device.waitForIdle()
    }
    // Rendering, camera changes and focus traversal, without changing a vehicle property.
    device.swipe(
        device.displayWidth * 35 / 100,
        device.displayHeight / 2,
        device.displayWidth * 55 / 100,
        device.displayHeight * 55 / 100,
        20,
    )
    device.pressDPadDown()
    device.pressDPadRight()
}
