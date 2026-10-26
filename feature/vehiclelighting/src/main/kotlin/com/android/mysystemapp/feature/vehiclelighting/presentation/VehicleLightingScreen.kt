package com.android.mysystemapp.feature.vehiclelighting.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.mysystemapp.core.common.VehicleLightSetting
import com.android.mysystemapp.core.ui.VehicleControlUiMetadata
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.ui.toUiControls
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.feature.vehiclelighting.R
import com.android.mysystemapp.feature.vehiclelighting.domain.VEHICLE_LIGHTING_DEFINITIONS
import com.android.mysystemapp.feature.vehiclelighting.domain.VehicleLightingId

/** Resource order follows the standard enum; unknown vendor values are displayed numerically. */
internal fun resolveLightingModeLabels(modes: Array<String>): Map<Int, String> =
    VehicleLightSetting.entries.zip(modes.asList()).associate { (setting, label) -> setting.value to label }

/** Pure metadata construction kept out of the recomposing route body. */
internal fun buildVehicleLightingMetadata(
    titles: Array<String>,
    descriptions: Array<String>,
    sections: Array<String>,
    modeLabels: Map<Int, String>,
    limitations: String,
    dependencies: String,
): List<VehicleControlUiMetadata> =
    VEHICLE_LIGHTING_DEFINITIONS.map { definition ->
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
            // Every definition is an AAOS `*_SWITCH` INT32 enum (VehicleLightSwitch):
            // OFF / ON / DAYTIME_RUNNING / AUTOMATIC. Enum options come from VHAL-discovered
            // supported values, so vendors exposing a subset render only that subset.
            editor = VehicleEditorUiKind.ENUM,
            enumLabels = modeLabels,
            valueLabel = { value -> modeLabels[(value as? Number)?.toInt()] ?: value.toString() },
        )
    }

@Composable
fun workspaceLightingControls(
    state: VehicleFeatureState,
): List<VehicleControlUiModel> {
    val resources = LocalContext.current.resources
    val titles = resources.getStringArray(R.array.vehicle_lighting_titles)
    val descriptions = resources.getStringArray(R.array.vehicle_lighting_descriptions)
    val sections = resources.getStringArray(R.array.vehicle_lighting_sections)
    val modes = resources.getStringArray(R.array.vehicle_lighting_modes)
    val limitations = stringResource(R.string.vehicle_lighting_limitations)
    val dependencies = stringResource(R.string.vehicle_lighting_dependencies)
    val unavailable = stringResource(R.string.vehicle_control_unavailable_message)
    val modeLabels = remember(modes.contentHashCode()) { resolveLightingModeLabels(modes) }
    val metadata =
        remember(
            titles.contentHashCode(),
            descriptions.contentHashCode(),
            sections.contentHashCode(),
            modeLabels,
            limitations,
            dependencies,
        ) {
            buildVehicleLightingMetadata(
                titles = titles,
                descriptions = descriptions,
                sections = sections,
                modeLabels = modeLabels,
                limitations = limitations,
                dependencies = dependencies,
            )
        }
    val controls = remember(state, metadata, unavailable) { state.toUiControls(metadata) { unavailable } }
    return controls
}
