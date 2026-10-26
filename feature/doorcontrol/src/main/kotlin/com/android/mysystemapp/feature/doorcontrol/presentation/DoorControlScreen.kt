package com.android.mysystemapp.feature.doorcontrol.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.mysystemapp.core.ui.VehicleControlUiMetadata
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.ui.toUiControls
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.feature.doorcontrol.R
import com.android.mysystemapp.feature.doorcontrol.domain.DOOR_CONTROL_DEFINITIONS
import com.android.mysystemapp.feature.doorcontrol.domain.DoorControlKind

/** Pure metadata construction kept out of the recomposing route body. */
internal fun buildDoorControlMetadata(
    titles: Array<String>,
    descriptions: Array<String>,
    sections: Array<String>,
    movementLabels: Map<Int, String>,
    limitations: String,
    dependencies: String,
): List<VehicleControlUiMetadata> =
    DOOR_CONTROL_DEFINITIONS.map { definition ->
        VehicleControlUiMetadata(
            key = definition.id.name,
            categoryKey = definition.section.name,
            section = sections[definition.section.ordinal],
            title = titles[definition.id.ordinal],
            summary = descriptions[definition.id.ordinal],
            info = descriptions[definition.id.ordinal],
            limitations = limitations,
            dependencies = dependencies,
            illustrationRes = null,
            editor =
                when (definition.kind) {
                    DoorControlKind.SWITCH -> VehicleEditorUiKind.SWITCH
                    DoorControlKind.RANGE -> VehicleEditorUiKind.SLIDER
                    DoorControlKind.ENUM -> VehicleEditorUiKind.ENUM
                },
            enumLabels = movementLabels,
            valueLabel = { value -> doorValueLabel(definition.kind, movementLabels, value) },
        )
    }

internal fun doorValueLabel(
    kind: DoorControlKind,
    movementLabels: Map<Int, String>,
    value: Any,
): String =
    if (kind == DoorControlKind.ENUM) {
        movementLabels[(value as? Number)?.toInt()] ?: value.toString()
    } else {
        (value as? Number)?.toInt()?.toString() ?: value.toString()
    }

@Composable
fun workspaceDoorControls(
    state: VehicleFeatureState,
): List<VehicleControlUiModel> {
    val resources = LocalContext.current.resources
    val titles = resources.getStringArray(R.array.door_control_titles)
    val descriptions = resources.getStringArray(R.array.door_control_descriptions)
    val sections = resources.getStringArray(R.array.door_control_sections)
    val movement = resources.getStringArray(R.array.door_movement_labels)
    val limitations = stringResource(R.string.door_control_limitations)
    val dependencies = stringResource(R.string.door_control_dependencies)
    val unavailable = stringResource(R.string.vehicle_control_unavailable_message)
    val movementLabels = remember(movement.contentHashCode()) { mapOf(-1 to movement[0], 0 to movement[1], 1 to movement[2]) }

    val metadata =
        remember(
            titles.contentHashCode(),
            descriptions.contentHashCode(),
            sections.contentHashCode(),
            movementLabels,
            limitations,
            dependencies,
        ) {
            buildDoorControlMetadata(
                titles = titles,
                descriptions = descriptions,
                sections = sections,
                movementLabels = movementLabels,
                limitations = limitations,
                dependencies = dependencies,
            )
        }
    val controls = remember(state, metadata, unavailable) { state.toUiControls(metadata) { unavailable } }
    return controls
}
