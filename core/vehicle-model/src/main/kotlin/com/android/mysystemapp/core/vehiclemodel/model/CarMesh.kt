package com.android.mysystemapp.core.vehiclemodel.model

import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.Vector3D
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection

/**
 * Đa giác 3D (tam giác hoặc tứ giác) đại diện cho một mảng bề mặt của xe ô tô
 */
data class Polygon3D(
    val vertices: List<Vector3D>,
    val normal: Vector3D,
    val part: CarPart,
    val baseColor: Long,
    val isTransparent: Boolean = false,
    val alpha: Float = 1.0f,
    val isEmissive: Boolean = false,
    val emissiveColor: Long = baseColor,
    val isDoubleSided: Boolean = false,
    val selection: VehiclePartSelection? = null,
    val hingeType: HingeType = HingeType.NONE,
)

enum class HingeType {
    NONE,
    TRUNK_LID, // Nắp cốp sau mở lật lên trên
    DRIVER_DOOR, // Cửa người lái mở bung ra ngoài
    PASSENGER_DOOR, // Cửa phụ mở bung ra ngoài
    HOOD, // Nắp ca-pô
}

data class ProjectedPolygon(
    val screenPoints: List<ScreenPoint>,
    val averageDepth: Float,
    val color: Long,
    val alpha: Float,
    val part: CarPart,
    val isEmissive: Boolean,
    val isSelected: Boolean,
    val selection: VehiclePartSelection? = null,
    val observationKnown: Boolean = true,
)

data class ScreenPoint(
    val x: Float,
    val y: Float,
    val z: Float,
)
