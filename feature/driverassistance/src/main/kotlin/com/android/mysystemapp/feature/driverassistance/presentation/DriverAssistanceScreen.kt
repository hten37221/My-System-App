package com.android.mysystemapp.feature.driverassistance.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.ui.VehicleEnumOption
import com.android.mysystemapp.core.vehicle.VehicleFeatureAreaState
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.core.vehicle.VehiclePropertyAccess
import com.android.mysystemapp.core.vehicle.VehiclePropertyArea
import com.android.mysystemapp.core.vehicle.VehiclePropertyError
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.feature.driverassistance.R
import com.android.mysystemapp.feature.driverassistance.domain.DRIVER_ASSISTANCE_DEFINITIONS
import com.android.mysystemapp.feature.driverassistance.domain.DriverAssistanceControlKind
import com.android.mysystemapp.feature.driverassistance.domain.DriverAssistanceDefinition
import com.android.mysystemapp.feature.driverassistance.domain.DriverAssistanceId
import kotlin.math.roundToInt

/** Pure domain-to-presentation mapping kept outside the Compose route. */
internal fun mapDriverAssistanceControls(
    state: VehicleFeatureState,
    titles: Array<String>,
    descriptions: Array<String>,
    sections: Array<String>,
    levelLabels: Array<String>,
    laneModeLabels: Array<String>,
    profileLabels: Array<String>,
    limitations: String,
    dependencies: String,
    unavailable: String,
): List<VehicleControlUiModel> =
    state.controls.flatMap { control ->
        val id = DriverAssistanceId.valueOf(control.definition.key)
        val definition = DRIVER_ASSISTANCE_DEFINITIONS.first { it.id == id }
        val areas =
            control.areas.ifEmpty {
                listOf(
                    VehicleFeatureAreaState(
                        area = VehiclePropertyArea.GLOBAL,
                        access = VehiclePropertyAccess.NONE,
                        error = control.error,
                    ),
                )
            }
        areas.map { area ->
            val value = area.value
            val numeric = (value as? Number)?.toFloat()
            val min = (area.minValue as? Number)?.toFloat()
            val max = (area.maxValue as? Number)?.toFloat()
            val enumValues = area.supportedEnumValues.mapNotNull { (it as? Number)?.toInt() }
            VehicleControlUiModel(
                key = control.definition.key,
                propertyId = definition.propertyId,
                areaId = area.area.areaId,
                section = sections[definition.section.ordinal],
                categoryKey = definition.section.name,
                title = titles[id.ordinal],
                summary = descriptions[id.ordinal],
                info = descriptions[id.ordinal],
                limitations = limitations,
                dependencies = dependencies,
                illustrationRes = null,
                editor = definition.controlKind.toEditor(),
                readable = area.access.canRead,
                writable = area.access.canWrite,
                supported = control.supported,
                available =
                    control.supported &&
                        area.status == VehiclePropertyStatus.AVAILABLE &&
                        value != null &&
                        (
                            definition.controlKind != DriverAssistanceControlKind.INTEGER_RANGE ||
                                (min != null && max != null && max > min)
                        ) &&
                        (
                            definition.controlKind != DriverAssistanceControlKind.INTEGER_ENUM ||
                                enumValues.isNotEmpty()
                        ),
                pending = area.pending,
                booleanValue = value as? Boolean,
                numericValue = numeric,
                valueLabel = numeric?.roundToInt()?.let { definition.valueLabel(it) }.orEmpty(),
                range = if (min != null && max != null && max > min) min..max else 0f..1f,
                steps =
                    if (min != null && max != null) {
                        (max - min).roundToInt().minus(1).coerceAtLeast(0)
                    } else {
                        0
                    },
                selectedEnumKey = numeric?.roundToInt()?.toString(),
                enumOptions =
                    enumValues.map { enumValue ->
                        VehicleEnumOption(
                            key = enumValue.toString(),
                            label = enumLabel(id, enumValue, levelLabels, laneModeLabels, profileLabels),
                        )
                    },
                errorMessage = (area.error ?: control.error)?.toMessage(unavailable),
                requiresUnrestrictedUx = control.definition.requiresUnrestrictedUx,
            )
        }
    }

private fun DriverAssistanceControlKind.toEditor(): VehicleEditorUiKind =
    when (this) {
        DriverAssistanceControlKind.SWITCH -> VehicleEditorUiKind.SWITCH
        DriverAssistanceControlKind.INTEGER_RANGE -> VehicleEditorUiKind.SLIDER
        DriverAssistanceControlKind.INTEGER_ENUM -> VehicleEditorUiKind.ENUM
    }

private fun DriverAssistanceDefinition.valueLabel(value: Int): String =
    when (id) {
        DriverAssistanceId.SPEED_LIMIT_OFFSET -> if (value > 0) "+$value km/h" else "$value km/h"
        else -> value.toString()
    }

private fun enumLabel(
    id: DriverAssistanceId,
    value: Int,
    levelLabels: Array<String>,
    laneModeLabels: Array<String>,
    profileLabels: Array<String>,
): String =
    when (id) {
        DriverAssistanceId.LANE_ALERT_MODE -> laneModeLabels.getOrNull(value)
        DriverAssistanceId.DRIVER_ASSISTANCE_PROFILE -> profileLabels.getOrNull(value)
        else -> levelLabels.getOrNull(value)
    } ?: value.toString()

private fun VehiclePropertyError.toMessage(fallback: String): String =
    when (this) {
        is VehiclePropertyError.Unsupported -> fallback
        is VehiclePropertyError.Unavailable -> fallback
        is VehiclePropertyError.PermissionDenied -> description
        is VehiclePropertyError.ServiceUnavailable -> description
        is VehiclePropertyError.Timeout -> description
        is VehiclePropertyError.InvalidArea -> description
        is VehiclePropertyError.TypeMismatch -> description
        is VehiclePropertyError.InvalidValue -> description
        is VehiclePropertyError.WriteNotAllowed -> description
        is VehiclePropertyError.UxRestricted -> description
        is VehiclePropertyError.WriteRejected -> description
    }

@Composable
fun workspaceAssistanceControls(
    state: com.android.mysystemapp.core.vehicle.VehicleFeatureState,
): List<com.android.mysystemapp.core.ui.VehicleControlUiModel> {
    val resources = LocalContext.current.resources
    val titles = resources.getStringArray(R.array.driver_assistance_titles)
    val descriptions = resources.getStringArray(R.array.driver_assistance_descriptions)
    val sections = resources.getStringArray(R.array.driver_assistance_sections)
    val levelLabels = resources.getStringArray(R.array.driver_assistance_level_labels)
    val laneModeLabels = resources.getStringArray(R.array.driver_assistance_lane_mode_labels)
    val profileLabels = resources.getStringArray(R.array.driver_assistance_profile_labels)
    val limitations = stringResource(R.string.driver_assistance_limitations)
    val dependencies = stringResource(R.string.driver_assistance_dependencies)
    val unavailable = stringResource(R.string.driver_assistance_unavailable)

    val controls =
        remember(
            state,
            titles.contentHashCode(),
            descriptions.contentHashCode(),
            sections.contentHashCode(),
            levelLabels.contentHashCode(),
            laneModeLabels.contentHashCode(),
            profileLabels.contentHashCode(),
            limitations,
            dependencies,
            unavailable,
        ) {
            mapDriverAssistanceControls(
                state = state,
                titles = titles,
                descriptions = descriptions,
                sections = sections,
                levelLabels = levelLabels,
                laneModeLabels = laneModeLabels,
                profileLabels = profileLabels,
                limitations = limitations,
                dependencies = dependencies,
                unavailable = unavailable,
            )
        }

    return controls
}
