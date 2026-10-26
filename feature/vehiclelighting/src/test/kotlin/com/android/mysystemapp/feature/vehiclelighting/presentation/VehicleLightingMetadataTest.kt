package com.android.mysystemapp.feature.vehiclelighting.presentation

import android.car.hardware.property.VehicleLightSwitch
import com.android.mysystemapp.core.common.VehicleLightSetting
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VehicleLightingMetadataTest {
    @Test fun `shared enum agrees with the compiled platform SDK`() {
        assertThat(VehicleLightSetting.entries.map { it.value })
            .containsExactly(
                VehicleLightSwitch.STATE_OFF,
                VehicleLightSwitch.STATE_ON,
                VehicleLightSwitch.STATE_DAYTIME_RUNNING,
                VehicleLightSwitch.STATE_AUTOMATIC,
            ).inOrder()
    }

    @Test
    fun `all AAOS lighting switch definitions map to enum rows`() {
        val modeLabels = resolveLightingModeLabels(arrayOf("Off", "On", "Daytime", "Automatic"))
        val metadata =
            buildVehicleLightingMetadata(
                titles = Array(8) { "title-$it" },
                descriptions = Array(8) { "description-$it" },
                sections = Array(3) { "section-$it" },
                modeLabels = modeLabels,
                limitations = "limitations",
                dependencies = "dependencies",
            )

        assertThat(metadata).hasSize(8)
        assertThat(metadata.map { it.editor }).containsExactlyElementsIn(List(8) { VehicleEditorUiKind.ENUM })
        assertThat(metadata.mapNotNull { it.illustrationRes }).isEmpty()
    }

    @Test
    fun `value labels resolve VehicleLightSwitch values and fall back for unknown vendor values`() {
        val modeLabels = resolveLightingModeLabels(arrayOf("Off", "On", "Daytime", "Automatic"))
        val metadata =
            buildVehicleLightingMetadata(
                titles = Array(8) { "title-$it" },
                descriptions = Array(8) { "description-$it" },
                sections = Array(3) { "section-$it" },
                modeLabels = modeLabels,
                limitations = "limitations",
                dependencies = "dependencies",
            )

        val row = metadata.first()
        assertThat(row.valueLabel(0)).isEqualTo("Off")
        assertThat(row.valueLabel(1)).isEqualTo("On")
        assertThat(row.valueLabel(2)).isEqualTo("Daytime")
        assertThat(row.valueLabel(256)).isEqualTo("Automatic")
        assertThat(row.valueLabel(3)).isEqualTo("3")
        assertThat(row.valueLabel(999)).isEqualTo("999")
    }
}
