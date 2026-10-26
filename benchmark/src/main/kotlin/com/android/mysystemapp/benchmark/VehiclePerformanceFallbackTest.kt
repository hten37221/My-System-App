package com.android.mysystemapp.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val PERFORMANCE_TARGET_PACKAGE = "com.android.mysystemapp"

/**
 * Device-side performance signal for Automotive images that reject Macrobenchmark's shell
 * background-dexopt preparation. It intentionally measures only elapsed launch/route readiness;
 * it never pretends to be a FrameTimingMetric replacement.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class VehiclePerformanceFallbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test
    fun startupAndVehicleRoutesProduceUsableTimingSignal() {
        val startupSamples = List(3) { launchAndMeasure(group = null) }
        val routeSamples =
            WorkspaceGroup.entries.flatMap { group -> List(2) { launchAndMeasure(group) } }
        val startupMedian = startupSamples.sorted()[startupSamples.size / 2]
        val routeMedian = routeSamples.sorted()[routeSamples.size / 2]

        Log.i(
            "VehiclePerformanceFallback",
            "startupMs=${startupSamples.joinToString()} startupMedianMs=$startupMedian " +
                "routeMs=${routeSamples.joinToString()} routeMedianMs=$routeMedian " +
                "metric=elapsed-device-signal-only",
        )
        assertTrue("startup timing was not recorded", startupSamples.all { it > 0L })
        assertTrue("route timing was not recorded", routeSamples.all { it > 0L })
    }

    private fun launchAndMeasure(group: WorkspaceGroup?): Long {
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am force-stop --user current $PERFORMANCE_TARGET_PACKAGE")
        val startedAt = SystemClock.elapsedRealtime()
        val launchOutput = device.executeShellCommand(
            "am start --user current -W -n ${workspaceIntent().component!!.flattenToShortString()}",
        )
        check(launchOutput.contains("Status: ok", ignoreCase = true)) {
            "target app launch failed: $launchOutput"
        }
        check(waitForTargetActivity()) {
            "target app did not become resumed: $launchOutput"
        }
        if (group != null) device.selectWorkspaceGroup(group)
        device.waitForIdle()
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
    }

    private fun waitForTargetActivity(): Boolean {
        repeat(30) {
            if (
                device.executeShellCommand("dumpsys activity activities")
                    .lineSequence().any {
                        it.contains("topResumedActivity=") && it.contains("$PERFORMANCE_TARGET_PACKAGE/.MainActivity")
                    }
            ) {
                return true
            }
            SystemClock.sleep(500L)
        }
        return false
    }
}
