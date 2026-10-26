package com.android.mysystemapp.core.vehiclemodel.geometry

import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.SceneProperties
import com.android.mysystemapp.core.vehiclemodel.Vector3D
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import com.android.mysystemapp.core.vehiclemodel.VehicleSceneState
import com.android.mysystemapp.core.vehiclemodel.model.Polygon3D
import kotlin.math.cos
import kotlin.math.sin

/** Articulated geometry for each actual AAOS area; the exterior comes from Automotive-App. */
object ZonedGeometry {
    fun build(scene: VehicleSceneState): List<Polygon3D> =
        buildList {
            addDoors(scene)
            addRoof(scene)
            addSeats(scene)
            addMirrors(scene)
            addLightingAndSteering(scene)
            addClimate(scene)
            addWindshields(scene)
        }

    private fun MutableList<Polygon3D>.addDoors(scene: VehicleSceneState) {
        val shellAlpha = if (scene.xrayMode) .08f else 1f
        for ((doorArea, side, front) in listOf(
            Triple(1, -1f, true),
            Triple(4, 1f, true),
            Triple(16, -1f, false),
            Triple(64, 1f, false),
        )) {
            val windowArea =
                when (doorArea) {
                    1 -> 16
                    4 -> 64
                    16 -> 256
                    else -> 1024
                }
            val zFront = if (front) .69f else -.15f
            val zRear = if (front) -.15f else -.98f
            val pivot = Vector3D(side * .89f, 0f, zFront)
            val opening = scene.normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, doorArea) ?: 0f
            val door = VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, doorArea)
            val glass = VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, windowArea)

            fun hinged(v: Vector3D): Vector3D {
                val r = Math.toRadians((-side * opening * 62f).toDouble()).toFloat()
                val d = v - pivot
                return Vector3D(pivot.x + d.x * cos(r) + d.z * sin(r), v.y, pivot.z - d.x * sin(r) + d.z * cos(r))
            }
            quad(
                listOf(
                    Vector3D(side * .9f, .2f, zFront),
                    Vector3D(side * .9f, .2f, zRear),
                    Vector3D(side * .88f, -.28f, zRear),
                    Vector3D(side * .88f, -.28f, zFront),
                ).map(::hinged),
                door,
                0xFF93ABB9,
                shellAlpha,
            )
            // A thin seam and inset handle retain the four-door silhouette.
            quad(
                listOf(
                    Vector3D(side * .91f, .09f, zRear + .14f),
                    Vector3D(side * .91f, .09f, zRear + .30f),
                    Vector3D(side * .91f, .115f, zRear + .30f),
                    Vector3D(
                        side * .91f,
                        .115f,
                        zRear + .14f,
                    ),
                ).map(::hinged),
                door,
                0xFF263B49,
                shellAlpha,
            )
            val closed = scene.windowOpening(windowArea)
            val topY = .2f + .43f * (1f - closed)
            quad(
                listOf(
                    Vector3D(side * .88f, .21f, zFront),
                    Vector3D(side * .88f, .21f, zRear),
                    Vector3D(side * .68f, topY, if (front) -.15f else -.75f),
                    Vector3D(
                        side * .68f,
                        topY,
                        if (front) .05f else -.15f,
                    ),
                ).map(::hinged),
                glass,
                0xFF244654,
                if (scene.xrayMode) .04f else .65f,
            )
        }
    }

    private fun MutableList<Polygon3D>.addRoof(scene: VehicleSceneState) {
        val roofOffset = scene.windowOpening(0x10000) * .70f
        quad(
            listOf(
                Vector3D(-.60f, .66f, .02f - roofOffset),
                Vector3D(.60f, .66f, .02f - roofOffset),
                Vector3D(.60f, .64f, -.72f - roofOffset),
                Vector3D(
                    -.60f,
                    .64f,
                    -.72f - roofOffset,
                ),
            ),
            VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, 0x10000),
            0xFF254B5C,
            if (scene.xrayMode).04f else .8f,
        )
    }

    private fun MutableList<Polygon3D>.addSeats(scene: VehicleSceneState) {
        for ((area, front) in listOf(1 to true, 4 to true, 16 to false, 32 to false, 64 to false)) {
            val x =
                when (area) {
                    1, 16 -> -.40f
                    4, 64 -> .40f
                    else -> 0f
                }
            val slide = scene.normalized(SceneProperties.SEAT_FORE_AFT_POS, SceneArea.SEAT, area) ?: .5f
            val height = scene.normalized(SceneProperties.SEAT_HEIGHT_POS, SceneArea.SEAT, area) ?: .5f
            val back = scene.normalized(SceneProperties.SEAT_BACKREST_ANGLE_1_POS, SceneArea.SEAT, area) ?: .5f

            fun position(property: Int) = scene.normalized(property, SceneArea.SEAT, area) ?: .5f
            val depth = position(SceneProperties.SEAT_DEPTH_POS)
            val tilt = (position(SceneProperties.SEAT_TILT_POS) - .5f) * .09f
            val headrestHeight = position(SceneProperties.SEAT_HEADREST_HEIGHT_POS_V2) * .12f
            val headrestAngle = (position(SceneProperties.SEAT_HEADREST_ANGLE_POS) - .5f) * .06f
            val headrestForward = (position(SceneProperties.SEAT_HEADREST_FORE_AFT_POS) - .5f) * .08f
            val lumbar = position(SceneProperties.SEAT_LUMBAR_FORE_AFT_POS) * .06f
            val lumbarY = position(SceneProperties.SEAT_LUMBAR_VERTICAL_POS) * .14f
            val cushionWidth = .17f + (position(SceneProperties.SEAT_CUSHION_SIDE_SUPPORT_POS) - .5f) * .05f
            val backWidth = .16f + (position(SceneProperties.SEAT_LUMBAR_SIDE_SUPPORT_POS) - .5f) * .05f
            val secondary = (position(SceneProperties.SEAT_BACKREST_ANGLE_2_POS) - .5f) * .08f
            val y = -.10f + (height - .5f) * .16f
            val z = (if (front).12f else -.70f) + (slide - .5f) * .24f
            val selection = VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, area)
            val heat = (scene.observed(SceneProperties.HVAC_SEAT_TEMPERATURE, SceneArea.SEAT, area)?.value as? Number)?.toInt() ?: 0
            val color =
                when {
                    heat > 0 -> 0xFF8D5748
                    heat < 0 -> 0xFF386F8F
                    else -> 0xFF526677
                }
            box(
                Vector3D(x - cushionWidth, y - .04f - tilt, z - .20f),
                Vector3D(x + cushionWidth, y + .04f + tilt, z + .15f + depth * .12f),
                selection,
                color,
            )
            val recline = .04f + back * .14f + secondary
            quad(
                listOf(
                    Vector3D(x - .17f, y, z - .18f),
                    Vector3D(x + .17f, y, z - .18f),
                    Vector3D(x + backWidth, y + .41f, z - .18f - recline),
                    Vector3D(
                        x - backWidth,
                        y + .41f,
                        z - .18f - recline,
                    ),
                ),
                selection,
                color,
            )
            box(
                Vector3D(x - .11f, y + .41f + headrestHeight, z - .24f - recline + headrestForward),
                Vector3D(
                    x + .11f,
                    y + .52f + headrestHeight,
                    z - .18f - recline + headrestForward + headrestAngle,
                ),
                selection,
                color,
            )
            box(
                Vector3D(x - .12f, y + .10f + lumbarY, z - .19f),
                Vector3D(x + .12f, y + .18f + lumbarY, z - .16f + lumbar),
                selection,
                color,
            )
        }
    }

    private fun MutableList<Polygon3D>.addMirrors(scene: VehicleSceneState) {
        for ((area, side) in listOf(1 to -1f, 2 to 1f, 4 to 0f)) {
            val folded = scene.bool(SceneProperties.MIRROR_FOLD) == true
            val x = if (side == 0f) 0f else side * (if (folded).90f else 1.04f)
            val pitch = (scene.normalized(SceneProperties.MIRROR_Z_POS, SceneArea.MIRROR, area) ?: .5f) - .5f
            val yaw = (scene.normalized(SceneProperties.MIRROR_Y_POS, SceneArea.MIRROR, area) ?: .5f) - .5f
            box(
                Vector3D(x - .09f, .22f + pitch * .06f, .43f + yaw * .06f),
                Vector3D(x + .09f, .30f + pitch * .06f, .50f + yaw * .06f),
                VehiclePartSelection(CarPart.MIRRORS, SceneArea.MIRROR, area),
                0xFF9DB7C6,
            )
        }
    }

    private fun MutableList<Polygon3D>.addLightingAndSteering(scene: VehicleSceneState) {
        val steeringDepth = (scene.normalized(SceneProperties.STEERING_WHEEL_DEPTH_POS, SceneArea.GLOBAL, 0) ?: .5f) * .10f
        val steeringHeight = (scene.normalized(SceneProperties.STEERING_WHEEL_HEIGHT_POS, SceneArea.GLOBAL, 0) ?: .5f) * .10f
        box(
            Vector3D(-.51f, .16f + steeringHeight, .35f - steeringDepth),
            Vector3D(-.25f, .20f + steeringHeight, .43f - steeringDepth),
            VehiclePartSelection(CarPart.SEATS),
            if (scene.lightEnabled(SceneProperties.STEERING_WHEEL_LIGHTS_SWITCH))0xFF7CD1EF else 0xFF263948,
        )
        if (scene.lightEnabled(SceneProperties.HAZARD_LIGHTS_SWITCH)) {
            for (side in listOf(
                -1f,
                1f,
            )) {
                box(
                    Vector3D(side * .74f - .06f, .04f, 1.97f),
                    Vector3D(side * .74f + .06f, .10f, 2.03f),
                    VehiclePartSelection(CarPart.HEADLIGHTS),
                    0xFFFFB448,
                )
            }
        }
        for (area in listOf(1, 4, 16, 32, 64)) {
            val x =
                when (area) {
                    1, 16 -> -.4f
                    4, 64 -> .4f
                    else -> 0f
                }
            val z = if (area < 16).1f else -.7f
            if (scene.lightEnabled(SceneProperties.READING_LIGHTS_SWITCH, SceneArea.SEAT, area)) {
                box(
                    Vector3D(x - .03f, .58f, z - .03f),
                    Vector3D(
                        x + .03f,
                        .60f,
                        z + .03f,
                    ),
                    VehiclePartSelection(CarPart.HEADLIGHTS, SceneArea.SEAT, area),
                    0xFFFFE1A0,
                )
            }
            if (scene.lightEnabled(SceneProperties.SEAT_FOOTWELL_LIGHTS_SWITCH, SceneArea.SEAT, area)) {
                box(
                    Vector3D(x - .1f, -.25f, z + .15f),
                    Vector3D(
                        x + .1f,
                        -.23f,
                        z + .20f,
                    ),
                    VehiclePartSelection(CarPart.HEADLIGHTS, SceneArea.SEAT, area),
                    0xFF75C8E5,
                )
            }
        }
        if (scene.lightEnabled(
                SceneProperties.CABIN_LIGHTS_SWITCH,
            )
        ) {
            box(Vector3D(-.06f, .60f, -.3f), Vector3D(.06f, .62f, -.15f), VehiclePartSelection(CarPart.HEADLIGHTS), 0xFFFFE1A0)
        }
    }

    private fun MutableList<Polygon3D>.addClimate(scene: VehicleSceneState) {
        val hvac = VehiclePartSelection(CarPart.HVAC, SceneArea.SEAT, 1)
        box(Vector3D(-.66f, .08f, .48f), Vector3D(.66f, .20f, .63f), hvac, 0xFF243747)
        for (area in listOf(1, 4, 16, 32, 64)) {
            if (scene.bool(SceneProperties.HVAC_POWER_ON, SceneArea.SEAT, area) != true) continue
            val x =
                when (area) {
                    1, 16 -> -.40f
                    4, 64 -> .40f
                    else -> 0f
                }
            val z = if (area < 16).40f else -.35f
            quad(
                listOf(
                    Vector3D(x - .04f, .17f, z),
                    Vector3D(x + .04f, .17f, z),
                    Vector3D(x + .10f, .05f, z - .30f),
                    Vector3D(x - .10f, .05f, z - .30f),
                ),
                VehiclePartSelection(CarPart.HVAC, SceneArea.SEAT, area),
                0xFF67C9EF,
                .6f,
                true,
            )
        }
    }

    private fun MutableList<Polygon3D>.addWindshields(scene: VehicleSceneState) {
        for (area in listOf(1, 2)) {
            val vertices =
                if (area == 1) {
                    listOf(
                        Vector3D(-.82f, .25f, .68f),
                        Vector3D(.82f, .25f, .68f),
                        Vector3D(.62f, .65f, .05f),
                        Vector3D(-.62f, .65f, .05f),
                    )
                } else {
                    listOf(
                        Vector3D(-.62f, .63f, -.75f),
                        Vector3D(.62f, .63f, -.75f),
                        Vector3D(.75f, .35f, -1.45f),
                        Vector3D(-.75f, .35f, -1.45f),
                    )
                }
            quad(
                vertices,
                VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, area),
                if (scene.bool(SceneProperties.HVAC_DEFROSTER, SceneArea.WINDOW, area) ==
                    true
                ) {
                    0xFFBD8660
                } else {
                    0xFF244654
                },
                if (scene.xrayMode).04f else .55f,
            )
        }
    }

    private fun MutableList<Polygon3D>.quad(
        vertices: List<Vector3D>,
        selection: VehiclePartSelection,
        color: Long,
        alpha: Float = 1f,
        emissive: Boolean = false,
    ) {
        add(
            Polygon3D(
                vertices = vertices,
                normal = (vertices[1] - vertices[0]).cross(vertices[2] - vertices[0]).normalized(),
                part = selection.part,
                baseColor = color,
                isTransparent = alpha < 1f,
                alpha = alpha,
                isEmissive = emissive,
                isDoubleSided = true,
                selection = selection,
            ),
        )
    }

    private fun MutableList<Polygon3D>.box(
        a: Vector3D,
        b: Vector3D,
        selection: VehiclePartSelection,
        color: Long,
    ) {
        val p =
            listOf(
                Vector3D(a.x, a.y, a.z),
                Vector3D(b.x, a.y, a.z),
                Vector3D(b.x, b.y, a.z),
                Vector3D(a.x, b.y, a.z),
                Vector3D(a.x, a.y, b.z),
                Vector3D(b.x, a.y, b.z),
                Vector3D(b.x, b.y, b.z),
                Vector3D(a.x, b.y, b.z),
            )
        for (face in listOf(
            listOf(0, 1, 2, 3),
            listOf(4, 7, 6, 5),
            listOf(0, 4, 5, 1),
            listOf(3, 2, 6, 7),
            listOf(0, 3, 7, 4),
            listOf(1, 5, 6, 2),
        )) {
            quad(
                face.map {
                    p[it]
                },
                selection,
                color,
            )
        }
    }
}
