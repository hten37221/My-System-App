package com.android.mysystemapp.feature.seatcontrol.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.mysystemapp.core.ui.VehicleControlUiMetadata
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.ui.toUiControls
import com.android.mysystemapp.core.vehicle.VehicleFeatureState
import com.android.mysystemapp.feature.seatcontrol.R
import com.android.mysystemapp.feature.seatcontrol.domain.SEAT_CONTROL_DEFINITIONS
import com.android.mysystemapp.feature.seatcontrol.domain.SeatControlId
import com.android.mysystemapp.feature.seatcontrol.domain.SeatControlKind

/** Pure metadata construction kept out of the recomposing route body. */
internal fun buildSeatControlMetadata(
    titles: Array<String>,
    descriptions: Array<String>,
    sections: Array<String>,
    occupancyLabels: Array<String>,
    limitations: String,
    dependencies: String,
    buckled: String,
    unbuckled: String,
    slotFormat: String,
    chooseSlot: String,
): List<VehicleControlUiMetadata> =
    SEAT_CONTROL_DEFINITIONS.map { definition ->
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
                    SeatControlKind.SWITCH -> VehicleEditorUiKind.SWITCH
                    SeatControlKind.RANGE -> VehicleEditorUiKind.SLIDER
                    SeatControlKind.SLOT_ACTION -> VehicleEditorUiKind.ENUM
                    SeatControlKind.STATUS -> VehicleEditorUiKind.STATUS
                },
            enumLabels =
                if (definition.kind == SeatControlKind.SLOT_ACTION) {
                    (0..3).associateWith { slot -> slotFormat.replace("%1\$d", slot.toString()) }
                } else {
                    emptyMap()
                },
            emptyValueLabel = if (definition.kind == SeatControlKind.SLOT_ACTION) chooseSlot else "",
            valueLabel = { value ->
                when (definition.id) {
                    SeatControlId.OCCUPANCY ->
                        occupancyLabels.getOrNull((value as Number).toInt()) ?: value.toString()
                    SeatControlId.SEAT_BELT -> if (value == true) buckled else unbuckled
                    SeatControlId.MEMORY_RECALL, SeatControlId.MEMORY_SAVE ->
                        slotFormat.replace("%1\$d", (value as Number).toInt().toString())
                    else -> (value as? Number)?.toInt()?.toString() ?: value.toString()
                }
            },
        )
    }

@Composable
fun workspaceSeatControls(
    state: VehicleFeatureState,
): List<VehicleControlUiModel> {
    val resources = LocalContext.current.resources
    val titles = resources.getStringArray(R.array.seat_control_titles)
    val descriptions = resources.getStringArray(R.array.seat_control_descriptions)
    val sections = resources.getStringArray(R.array.seat_control_sections)
    val occupancyLabels = resources.getStringArray(R.array.seat_occupancy_labels)
    val limitations = stringResource(R.string.seat_control_limitations)
    val dependencies = stringResource(R.string.seat_control_dependencies)
    val unavailable = stringResource(R.string.vehicle_control_unavailable_message)
    val buckled = stringResource(R.string.seat_belt_buckled)
    val unbuckled = stringResource(R.string.seat_belt_unbuckled)
    val slotFormat = stringResource(R.string.seat_memory_slot_format)
    val chooseSlot = stringResource(R.string.seat_memory_choose_slot)

    val metadata =
        remember(
            titles.contentHashCode(),
            descriptions.contentHashCode(),
            sections.contentHashCode(),
            occupancyLabels.contentHashCode(),
            limitations,
            dependencies,
            buckled,
            unbuckled,
            slotFormat,
            chooseSlot,
        ) {
            buildSeatControlMetadata(
                titles = titles,
                descriptions = descriptions,
                sections = sections,
                occupancyLabels = occupancyLabels,
                limitations = limitations,
                dependencies = dependencies,
                buckled = buckled,
                unbuckled = unbuckled,
                slotFormat = slotFormat,
                chooseSlot = chooseSlot,
            )
        }
    val controls = remember(state, metadata, unavailable) { state.toUiControls(metadata) { unavailable } }
    return controls
}
