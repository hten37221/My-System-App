package com.android.mysystemapp.core.vehiclemodel

import com.android.mysystemapp.core.common.VehicleLightSetting

enum class CarPart { BODY, WHEELS, HEADLIGHTS, MIRRORS, TRUNK, DOORS, GLASS_WINDOWS, SEATS, HVAC, ADAS, MODES }

enum class SceneArea { GLOBAL, DOOR, WINDOW, SEAT, MIRROR }

enum class HeadlightMode { OFF, LOW_BEAM }

data class VehiclePartSelection(
    val part: CarPart,
    val areaType: SceneArea = SceneArea.GLOBAL,
    val areaId: Int = 0,
) {
    /** Area bits are meaningful only within the same component and VHAL area domain. */
    fun overlaps(other: VehiclePartSelection?): Boolean {
        if (other == null || part != other.part || areaType != other.areaType) return false
        return if (areaType == SceneArea.GLOBAL) areaId == 0 && other.areaId == 0 else areaId and other.areaId != 0
    }
}

data class SceneKey(
    val propertyId: Int,
    val areaType: SceneArea,
    val areaId: Int,
)

data class ObservedValue(
    val value: Any,
    val min: Float? = null,
    val max: Float? = null,
) {
    fun normalized(): Float? {
        val number = (value as? Number)?.toFloat()
        val low = min
        val high = max
        if (number == null || low == null || high == null)return null
        return if (number.isFinite() && high > low)((number - low) / (high - low)).coerceIn(0f, 1f) else null
    }
}

/** Only confirmed AVAILABLE observations enter this snapshot. Camera/reveal never write VHAL. */
data class VehicleSceneState(
    val observations: Map<SceneKey, ObservedValue> = emptyMap(),
    val xrayMode: Boolean = false,
    val nightMode: Boolean = true,
) {
    fun observed(
        property: Int,
        type: SceneArea = SceneArea.GLOBAL,
        area: Int = 0,
    ): ObservedValue? =
        observations[SceneKey(property, type, area)] ?: observations.entries
            .firstOrNull { (key, _) ->
                key.propertyId == property && key.areaType == type && area != 0 && key.areaId and area == area
            }?.value

    fun normalized(
        property: Int,
        type: SceneArea,
        area: Int,
    ): Float? = observed(property, type, area)?.normalized()

    fun bool(
        property: Int,
        type: SceneArea = SceneArea.GLOBAL,
        area: Int = 0,
    ): Boolean? = observed(property, type, area)?.value as? Boolean

    /** Unknown geometry uses a neutral pose; it must never look like an observed closed/off state. */
    fun hasObservedState(selection: VehiclePartSelection?): Boolean {
        if (selection == null) return true
        val properties =
            when (selection.part) {
                CarPart.DOORS, CarPart.TRUNK -> listOf(SceneProperties.DOOR_POS)
                CarPart.GLASS_WINDOWS ->
                    if (selection.areaId in listOf(1, 2)) listOf(SceneProperties.HVAC_DEFROSTER) else listOf(SceneProperties.WINDOW_POS)
                CarPart.MIRRORS -> listOf(SceneProperties.MIRROR_Y_POS, SceneProperties.MIRROR_Z_POS)
                CarPart.SEATS ->
                    if (selection.areaType == SceneArea.GLOBAL) {
                        listOf(SceneProperties.STEERING_WHEEL_DEPTH_POS, SceneProperties.STEERING_WHEEL_HEIGHT_POS)
                    } else {
                        listOf(SceneProperties.SEAT_FORE_AFT_POS, SceneProperties.SEAT_HEIGHT_POS)
                    }
                CarPart.HVAC -> listOf(SceneProperties.HVAC_POWER_ON)
                CarPart.HEADLIGHTS ->
                    if (selection.areaType == SceneArea.SEAT) {
                        listOf(SceneProperties.READING_LIGHTS_SWITCH, SceneProperties.SEAT_FOOTWELL_LIGHTS_SWITCH)
                    } else {
                        listOf(SceneProperties.HEADLIGHTS_SWITCH, SceneProperties.CABIN_LIGHTS_SWITCH)
                    }
                else -> emptyList()
            }
        return properties.isEmpty() || properties.any { observed(it, selection.areaType, selection.areaId) != null }
    }

    fun windowOpening(area: Int): Float {
        val current = observed(SceneProperties.WINDOW_POS, SceneArea.WINDOW, area)
        val number = (current?.value as? Number)?.toFloat()
        return if (number != null)(number / (current.max ?: 1f).coerceAtLeast(1f)).coerceIn(0f, 1f) else 0f
    }

    fun lightEnabled(
        property: Int,
        type: SceneArea = SceneArea.GLOBAL,
        area: Int = 0,
    ): Boolean = (observed(property, type, area)?.value as? Number)?.toInt() == VehicleLightSetting.ON.value

    val trunkMaxHeightPercent: Int get() = 100
    val trunkOpenPercent: Float get() = normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, 0x20000000) ?: 0f
    val driverDoorOpenPercent: Float get() = 0f // Per-door transforms are in ZonedGeometry.
    val headlightMode: HeadlightMode get() =
        if (lightEnabled(SceneProperties.HEADLIGHTS_SWITCH) ||
            lightEnabled(SceneProperties.HIGH_BEAM_LIGHTS_SWITCH)
        ) {
            HeadlightMode.LOW_BEAM
        } else {
            HeadlightMode.OFF
        }
    val drlEnabled: Boolean get() =
        (observed(SceneProperties.HEADLIGHTS_SWITCH)?.value as? Number)?.toInt() == VehicleLightSetting.DAYTIME_RUNNING.value
    val fogLightsEnabled: Boolean get() = lightEnabled(SceneProperties.FOG_LIGHTS_SWITCH)
}

object VehicleCamera {
    val exterior = CameraPose(azimuth = 325f, elevation = 25f, distance = 6f)
    val left = CameraPose(azimuth = 270f, elevation = 22f, distance = 6.8f)
    val right = CameraPose(azimuth = 90f, elevation = 22f, distance = 6.8f)
    val cabin = CameraPose(azimuth = 325f, elevation = 65f, distance = 5.5f)

    fun focus(selection: VehiclePartSelection): CameraPose =
        when (selection.part) {
            CarPart.SEATS, CarPart.HVAC -> cabin
            CarPart.TRUNK -> CameraPose(azimuth = 200f, elevation = 27f, distance = 6.2f)
            CarPart.DOORS, CarPart.GLASS_WINDOWS -> if (selection.areaId in listOf(4, 0x40, 0x400)) right else left
            CarPart.MIRRORS ->
                when (selection.areaId) {
                    2 -> right
                    4 -> cabin
                    else -> left
                }
            CarPart.HEADLIGHTS -> CameraPose(azimuth = 325f, elevation = 20f, distance = 6.4f)
            else -> exterior
        }

    fun shortestAzimuth(
        current: Float,
        target: Float,
    ): Float = current + ((target - current + 180f) % 360f + 360f) % 360f - 180f

    fun orbit(
        pose: CameraPose,
        dx: Float,
        dy: Float,
        zoom: Float = 1f,
    ): CameraPose =
        pose.copy(
            azimuth = ((pose.azimuth - dx * .32f) % 360f + 360f) % 360f,
            elevation = (pose.elevation + dy * .22f).coerceIn(8f, 78f),
            distance = (pose.distance / zoom.coerceIn(.5f, 2f)).coerceIn(4f, 10f),
        )
}
