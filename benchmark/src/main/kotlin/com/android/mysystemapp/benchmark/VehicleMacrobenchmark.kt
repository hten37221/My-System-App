package com.android.mysystemapp.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.android.mysystemapp"

/**
 * Hardware-independent critical-user-journey measurements. Results from an
 * emulator are recorded as regression signals only; they are never presented
 * as representative driver-facing performance numbers.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class VehicleMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() =
        runOnSupportedImage {
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(StartupTimingMetric()),
                compilationMode = CompilationMode.None(),
                iterations = 3,
                startupMode = StartupMode.COLD,
                setupBlock = { pressHome() },
            ) {
                startActivityAndWait(workspaceIntent())
            }
        }

    @Test
    fun warmStartup() =
        runOnSupportedImage {
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(StartupTimingMetric()),
                compilationMode = CompilationMode.None(),
                iterations = 3,
                startupMode = StartupMode.WARM,
                setupBlock = {
                    startActivityAndWait(workspaceIntent())
                    pressHome()
                },
            ) {
                startActivityAndWait(workspaceIntent())
            }
        }

    @Test
    @OptIn(ExperimentalMacrobenchmarkApi::class)
    fun coldStartupWithBaselineProfile() =
        runOnSupportedImage {
            prepareEmbeddedBaselineProfile()
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(StartupTimingMetric()),
                // ProfileInstaller 1.4.x writes to ART user 0. AAOS runs the visible driver on a
                // secondary user, so CompilationMode.Partial would invoke the receiver as that
                // user and fail with RESULT_NOT_WRITABLE. prepareEmbeddedBaselineProfile installs
                // the exact embedded profile through system user 0 and verifies speed-profile
                // compilation first; Ignore preserves that deliberately prepared ART state.
                compilationMode = CompilationMode.Ignore(),
                iterations = 3,
                startupMode = StartupMode.COLD,
                setupBlock = { pressHome() },
            ) {
                startActivityAndWait(workspaceIntent())
            }
        }

    private fun prepareEmbeddedBaselineProfile() {
        val component = "$TARGET_PACKAGE/androidx.profileinstaller.ProfileInstallReceiver"
        val installOutput =
            device.executeShellCommand(
                "am broadcast --user 0 " +
                    "-a androidx.profileinstaller.action.INSTALL_PROFILE -n $component",
            )
        check(installOutput.contains("result=1")) {
            "Embedded Baseline Profile was not installed through AAOS system user 0: $installOutput"
        }

        val compileOutput =
            device.executeShellCommand("cmd package compile -f -m speed-profile $TARGET_PACKAGE")
        check(compileOutput.contains("Success", ignoreCase = true)) {
            "ART rejected speed-profile compilation: $compileOutput"
        }

        val dexoptOutput =
            device.executeShellCommand(
                "dumpsys package dexopt | " +
                    "grep -A 3 -F '[$TARGET_PACKAGE]'",
            )
        check(dexoptOutput.contains("status=speed-profile")) {
            "ART did not retain speed-profile compilation for $TARGET_PACKAGE: $dexoptOutput"
        }
        device.executeShellCommand("am force-stop --user 0 $TARGET_PACKAGE")
    }

    @Test
    fun vehicleControlsNavigationAndScroll() =
        runOnSupportedImage {
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                // The stock API 37 Automotive image cannot install a baseline profile from the
                // benchmark shell user. None still captures frame timing without that dependency.
                compilationMode = CompilationMode.None(),
                iterations = 3,
                startupMode = StartupMode.WARM,
                setupBlock = { pressHome() },
            ) {
                openWorkspaceGroup(WorkspaceGroup.ASSISTANCE)
                val modelBounds = checkNotNull(device.findObject(By.desc("Vehicle model"))).visibleBounds
                val controls =
                    device.findObjects(By.scrollable(true))
                        .filter { it.visibleBounds.left >= modelBounds.right || it.visibleBounds.top >= modelBounds.bottom }
                        .maxByOrNull { it.visibleBounds.height() }
                checkNotNull(controls) { "Scrollable workspace controls were not found" }
                controls.apply {
                    setGestureMargin(visibleBounds.width() / 8)
                    fling(Direction.DOWN)
                    fling(Direction.UP)
                }
                // Include the model itself so frame timing covers its camera/geometry path.
                device.swipe(
                    modelBounds.left + modelBounds.width() / 3,
                    modelBounds.centerY(),
                    modelBounds.left + modelBounds.width() * 2 / 3,
                    modelBounds.centerY(),
                    20,
                )
            }
        }

    @Test
    fun seatDoorAndRotaryInteraction() =
        runOnSupportedImage {
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.None(),
                iterations = 3,
                startupMode = StartupMode.WARM,
                setupBlock = { pressHome() },
            ) {
                openWorkspaceGroup(WorkspaceGroup.SEATS)
                device.waitForIdle()
                repeat(6) { device.pressDPadDown() }
                repeat(4) { device.pressDPadRight() }
                openWorkspaceGroup(WorkspaceGroup.DOORS)
                device.waitForIdle()
                repeat(4) { device.pressDPadDown() }
                repeat(3) { device.pressDPadRight() }
            }
        }

    @Test
    fun rotaryFocusAcrossWorkspace() =
        runOnSupportedImage {
            benchmarkRule.measureRepeated(
                packageName = TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.None(),
                iterations = 3,
                startupMode = StartupMode.WARM,
                setupBlock = { pressHome() },
            ) {
                openWorkspaceGroup(WorkspaceGroup.SEATS)
                // Traverse focus between the rail, camera and control regions. Property writes
                // and direct manipulation are covered by instrumented functional tests; this
                // journey measures focus rendering without changing a vehicle setting.
                repeat(32) {
                    device.pressDPadRight()
                    if (it % 8 == 7) device.pressDPadDown()
                }
            }
        }

    /**
     * Some stock AAOS images expose `cmd package` but reject the background-dexopt cancellation
     * issued by Macrobenchmark before an iteration. Treat that as an explicit environment skip;
     * never turn a missing metric into a false performance pass or a product-test failure.
     */
    private inline fun runOnSupportedImage(crossinline benchmark: () -> Unit) {
        try {
            benchmark()
        } catch (error: IllegalStateException) {
            if (error.message?.contains("background dexopt", ignoreCase = true) == true) {
                assumeTrue(
                    "Macrobenchmark unavailable: image rejected background-dexopt cancellation",
                    false,
                )
            }
            throw error
        }
    }

    private fun MacrobenchmarkScope.openWorkspaceGroup(group: WorkspaceGroup) {
        startActivityAndWait(workspaceIntent())
        device.selectWorkspaceGroup(group)
    }

    private val device
        get() = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}
