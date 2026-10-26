package com.android.mysystemapp.feature.vehiclecontrol

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps navigation feedback separate from the expensive destination composition. */
@Stable
internal class VehicleGroupTransition {
    var target by mutableStateOf<ControlGroup?>(null)
        private set

    val isLoading: Boolean get() = target != null

    fun request(group: ControlGroup) {
        // Check state at event delivery too: native/Compose callbacks may still hold old lambdas.
        if (!isLoading) target = group
    }

    suspend fun complete(
        awaitFrame: suspend () -> Unit,
        changeGroup: (ControlGroup) -> Unit,
    ) {
        val destination = target ?: return
        try {
            // A frame callback precedes drawing. Cross two boundaries so selection/loading is
            // actually presented before native FocusItems for the new destination are created.
            awaitFrame()
            awaitFrame()
            changeGroup(destination)
            // Keep navigation locked through destination composition, native layout and drawing.
            awaitFrame()
            awaitFrame()
        } finally {
            target = null
        }
    }
}
