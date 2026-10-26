package com.android.mysystemapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import com.android.mysystemapp.core.ui.MySystemTheme
import com.android.mysystemapp.core.ui.setSafeRotaryContent
import com.android.mysystemapp.feature.vehiclecontrol.VehicleControlRoute
import com.b231001.bmaterial.ccp.rotaryfocus.RotaryFocusController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Instrumentation requests focus through the same controller, including its pending-entry handling.
    internal val rotary = RotaryFocusController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (rotary.isDirectManipulationActive) {
                        rotary.exitDirectManipulation()
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
        enableEdgeToEdge()
        setSafeRotaryContent("vehicle-control", rotary) {
            MySystemTheme(darkTheme = isSystemInDarkTheme()) { VehicleControlRoute(hiltViewModel()) }
        }
    }
}
