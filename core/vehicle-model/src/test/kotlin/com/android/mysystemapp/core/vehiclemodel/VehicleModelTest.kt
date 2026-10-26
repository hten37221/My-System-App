package com.android.mysystemapp.core.vehiclemodel

import com.android.mysystemapp.core.vehiclemodel.geometry.CarGeometryBuilder
import com.android.mysystemapp.core.vehiclemodel.model.Polygon3D
import com.android.mysystemapp.core.vehiclemodel.model.ProjectedPolygon
import com.android.mysystemapp.core.vehiclemodel.model.ScreenPoint
import com.android.mysystemapp.core.vehiclemodel.renderer.Car3DRenderer
import com.android.mysystemapp.core.vehiclemodel.renderer.VehiclePicking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleModelTest {
    @Test fun standardOnSettingLightsTheModelAndDaytimeUsesOnlyDrls() {
        fun scene(value: Int) =
            VehicleSceneState(
                mapOf(
                    SceneKey(SceneProperties.HEADLIGHTS_SWITCH, SceneArea.GLOBAL, 0) to ObservedValue(value),
                ),
            )
        val off = scene(0)
        val on = scene(1)
        val daytime = scene(2)
        assertEquals(HeadlightMode.LOW_BEAM, on.headlightMode)
        assertFalse(on.drlEnabled)
        assertEquals(HeadlightMode.OFF, daytime.headlightMode)
        assertTrue(daytime.drlEnabled)
        assertTrue(
            CarGeometryBuilder.buildCarModel(on).count { it.isEmissive } > CarGeometryBuilder.buildCarModel(off).count { it.isEmissive },
        )
        for (value in listOf(0, 3, 256, 999)) {
            assertFalse(scene(value).lightEnabled(SceneProperties.HEADLIGHTS_SWITCH))
        }
    }

    @Test fun allZonedAndGlobalLightsUseTheSameOnValue() {
        val properties =
            listOf(
                SceneProperties.CABIN_LIGHTS_SWITCH,
                SceneProperties.HAZARD_LIGHTS_SWITCH,
                SceneProperties.STEERING_WHEEL_LIGHTS_SWITCH,
                SceneProperties.FOG_LIGHTS_SWITCH,
            )
        val scene =
            VehicleSceneState(
                properties.associate { SceneKey(it, SceneArea.GLOBAL, 0) to ObservedValue(1) } +
                    (SceneKey(SceneProperties.READING_LIGHTS_SWITCH, SceneArea.SEAT, 5) to ObservedValue(1)),
            )
        properties.forEach { assertTrue(scene.lightEnabled(it)) }
        assertTrue(scene.fogLightsEnabled)
        assertTrue(scene.lightEnabled(SceneProperties.READING_LIGHTS_SWITCH, SceneArea.SEAT, 1))
        assertTrue(scene.lightEnabled(SceneProperties.READING_LIGHTS_SWITCH, SceneArea.SEAT, 4))
        assertFalse(scene.lightEnabled(SceneProperties.READING_LIGHTS_SWITCH, SceneArea.SEAT, 16))
    }

    @Test fun compositeSelectionOverlapsOnlyItsMembersInTheSameComponentAndDomain() {
        val left = VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, 1)
        val front = VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, 5)
        assertTrue(front.overlaps(left))
        assertTrue(left.overlaps(front))
        assertFalse(front.overlaps(left.copy(areaId = 16)))
        assertFalse(front.overlaps(left.copy(part = CarPart.HVAC)))
        assertFalse(front.overlaps(left.copy(areaType = SceneArea.DOOR)))
        assertFalse(front.overlaps(VehiclePartSelection(CarPart.SEATS)))
        assertFalse(front.overlaps(null))
        assertTrue(VehiclePartSelection(CarPart.SEATS).overlaps(VehiclePartSelection(CarPart.SEATS)))
    }

    @Test fun rendererHighlightsBothMembersOfACompositeSelection() {
        val scene = VehicleSceneState(xrayMode = true)
        val snapshot =
            Car3DRenderer.project(
                CarGeometryBuilder.buildCarModel(scene),
                VehicleCamera.cabin,
                scene,
                VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, 5),
                900f,
                700f,
            )
        val selected = snapshot.filter { it.isSelected }.map { it.selection }.toSet()
        assertEquals(
            setOf(
                VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, 1),
                VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, 4),
            ),
            selected,
        )
    }

    private fun face(
        selection: VehiclePartSelection?,
        depth: Float,
    ) = ProjectedPolygon(
        listOf(ScreenPoint(0f, 0f, depth), ScreenPoint(100f, 0f, depth), ScreenPoint(100f, 100f, depth), ScreenPoint(0f, 100f, depth)),
        depth,
        0xFF000000,
        1f,
        CarPart.DOORS,
        false,
        false,
        selection,
    )

    @Test fun nearestVisibleSurfaceWinsAndBodyOccludesHiddenParts() {
        val left = VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 1)
        val right = VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 4)
        assertEquals(left, VehiclePicking.pick(listOf(face(right, .8f), face(left, .2f)), 50f, 50f))
        assertNull(VehiclePicking.pick(listOf(face(right, .8f), face(null, .1f)), 50f, 50f))
        assertNull(VehiclePicking.pick(listOf(face(left, .2f)), 150f, 50f))
    }

    @Test fun requestedPartLimitsAnchorCandidatesButOtherPartsRemainOccluders() {
        val door = VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 1)
        val seat = VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, 1)
        val doorFace = face(door, .6f)
        val seatFace = face(seat, .1f).copy(part = CarPart.SEATS)
        val separateSeat = seatFace.copy(screenPoints = seatFace.screenPoints.map { it.copy(x = it.x + 150f) })
        val snapshot = listOf(doorFace, separateSeat)
        assertEquals(listOf(door), VehiclePicking.anchors(snapshot, CarPart.DOORS).map { it.first })
        assertEquals(listOf(seat), VehiclePicking.anchors(snapshot, CarPart.SEATS).map { it.first })
        assertTrue(VehiclePicking.anchors(listOf(doorFace, seatFace), CarPart.DOORS).isEmpty())
    }

    @Test fun windowAndDoorBitsCannotCrossContaminate() {
        val scene =
            VehicleSceneState(
                mapOf(
                    SceneKey(SceneProperties.DOOR_POS, SceneArea.DOOR, 16) to ObservedValue(1, 0f, 1f),
                    SceneKey(SceneProperties.WINDOW_POS, SceneArea.WINDOW, 16) to ObservedValue(0, 0f, 10f),
                ),
            )
        assertEquals(1f, scene.normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, 16))
        assertEquals(0f, scene.normalized(SceneProperties.WINDOW_POS, SceneArea.WINDOW, 16))
        assertNull(scene.normalized(SceneProperties.WINDOW_POS, SceneArea.DOOR, 16))
    }

    @Test fun groupedAreasApplyOnlyToTheirMembers() {
        val scene = VehicleSceneState(mapOf(SceneKey(SceneProperties.HVAC_POWER_ON, SceneArea.SEAT, 5) to ObservedValue(true)))
        assertEquals(true, scene.bool(SceneProperties.HVAC_POWER_ON, SceneArea.SEAT, 1))
        assertEquals(true, scene.bool(SceneProperties.HVAC_POWER_ON, SceneArea.SEAT, 4))
        assertNull(scene.bool(SceneProperties.HVAC_POWER_ON, SceneArea.SEAT, 16))
    }

    @Test fun geometryHasAllDoorsWindowsMirrorsAndFiveSeats() {
        val selections = CarGeometryBuilder.buildCarModel(VehicleSceneState()).mapNotNull { it.selection }.toSet()
        for (area in listOf(1, 4, 16, 64))assertTrue(selections.contains(VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, area)))
        for (area in listOf(1, 4, 16, 32, 64))assertTrue(selections.contains(VehiclePartSelection(CarPart.SEATS, SceneArea.SEAT, area)))
        for (area in listOf(1, 2, 4))assertTrue(selections.contains(VehiclePartSelection(CarPart.MIRRORS, SceneArea.MIRROR, area)))
        assertTrue(selections.contains(VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, 65536)))
    }

    @Test fun doorActuationOnlyMovesTheSelectedDoor() {
        val baseline = CarGeometryBuilder.buildCarModel(VehicleSceneState())
        val changed =
            CarGeometryBuilder.buildCarModel(
                VehicleSceneState(
                    mapOf(
                        SceneKey(SceneProperties.DOOR_POS, SceneArea.DOOR, 1) to ObservedValue(1, 0f, 1f),
                    ),
                ),
            )

        fun vertices(
            polys: List<Polygon3D>,
            area: Int,
        ) = polys
            .filter {
                it.selection == VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, area)
            }.flatMap { it.vertices }
        assertNotEquals(vertices(baseline, 1), vertices(changed, 1))
        assertEquals(vertices(baseline, 4), vertices(changed, 4))
    }

    @Test fun cameraBoundsHoldForExtremeGestures() {
        val result = VehicleCamera.orbit(VehicleCamera.exterior, 100000f, 100000f, .001f)
        assertTrue(result.azimuth >= 0f && result.azimuth < 360f)
        assertEquals(78f, result.elevation)
        assertTrue(result.distance in 4f..10f)
        assertEquals(VehicleCamera.cabin, VehicleCamera.focus(VehiclePartSelection(CarPart.MIRRORS, SceneArea.MIRROR, 4)))
    }

    @Test fun clippingAndProjectionStayFiniteAtEveryPreset() {
        val scene = VehicleSceneState()
        val geometry = CarGeometryBuilder.buildCarModel(scene)
        for (camera in listOf(VehicleCamera.exterior, VehicleCamera.left, VehicleCamera.right, VehicleCamera.cabin)) {
            val projection = Car3DRenderer.project(geometry, camera, scene, null, 900f, 700f)
            assertTrue(projection.isNotEmpty())
            assertTrue(projection.flatMap { it.screenPoints }.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        }
    }

    @Test fun cameraFocusCrossesZeroByTheShortestArc() {
        assertEquals(370f, VehicleCamera.shortestAzimuth(350f, 10f))
        assertEquals(-10f, VehicleCamera.shortestAzimuth(10f, 350f))
    }

    @Test fun missingObservationIsDistinctFromAConfirmedClosedDoorInTheRenderSnapshot() {
        val door = VehiclePartSelection(CarPart.DOORS, SceneArea.DOOR, 1)
        val unknown = VehicleSceneState()
        val closed = VehicleSceneState(mapOf(SceneKey(SceneProperties.DOOR_POS, SceneArea.DOOR, 1) to ObservedValue(0, 0f, 1f)))
        assertTrue(!unknown.hasObservedState(door))
        assertTrue(closed.hasObservedState(door))
        for (scene in listOf(unknown, closed)) {
            val polygons = Car3DRenderer.project(CarGeometryBuilder.buildCarModel(scene), VehicleCamera.left, scene, door, 900f, 700f)
            val selected = polygons.filter { it.selection == door }
            assertTrue(selected.isNotEmpty())
            assertTrue(selected.all { it.observationKnown == (scene == closed) })
        }
        assertTrue(!closed.hasObservedState(VehiclePartSelection(CarPart.GLASS_WINDOWS, SceneArea.WINDOW, 1)))
    }
}
