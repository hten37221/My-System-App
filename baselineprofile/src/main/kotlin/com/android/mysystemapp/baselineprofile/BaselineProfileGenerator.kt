package com.android.mysystemapp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures a narrow startup profile and broader baseline-only vehicle-control journeys. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() {
        collectProfile(includeInStartupProfile = true) {
            startActivityAndWait(vehicleProfileIntent())
        }
    }

    @Test
    fun vehicleControls() {
        collectProfile(includeInStartupProfile = false) {
            startActivityAndWait(vehicleProfileIntent())
            visitVehicleWorkspace(device)
        }
    }

    private fun collectProfile(
        includeInStartupProfile: Boolean,
        journey: MacrobenchmarkScope.() -> Unit,
    ) {
        try {
            baselineProfileRule.collect(
                packageName = PROFILE_TARGET_PACKAGE,
                includeInStartupProfile = includeInStartupProfile,
                profileBlock = journey,
            )
        } catch (error: IllegalStateException) {
            // The stock Automotive image can reject the benchmark runner's package-compile
            // command while the same command succeeds from an adb shell. Do not turn that
            // infrastructure limitation into a product failure, but only skip this exact empty
            // compile response; profile collection/runtime failures still fail the test.
            val compileRejectedByImage =
                error.message?.contains("Failed to compile (out=)", ignoreCase = true) == true
            assumeTrue(
                "Baseline Profile unavailable: connected image rejected benchmark package compile",
                !compileRejectedByImage,
            )
            throw error
        }
    }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}
