package com.android.mysystemapp.core.vehiclemodel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.mysystemapp.core.vehiclemodel.geometry.CarGeometryBuilder
import com.android.mysystemapp.core.vehiclemodel.model.ProjectedPolygon
import com.android.mysystemapp.core.vehiclemodel.model.ScreenPoint
import com.android.mysystemapp.core.vehiclemodel.renderer.Car3DRenderer
import com.android.mysystemapp.core.vehiclemodel.renderer.VehiclePicking
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun VehicleViewport(
    scene: VehicleSceneState,
    selection: VehiclePartSelection?,
    camera: CameraPose,
    onCamera: (CameraPose) -> Unit,
    onSelect: (VehiclePartSelection) -> Unit,
    label: (VehiclePartSelection) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onToggleDoor: ((VehiclePartSelection) -> Unit)? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var interacting by remember { mutableStateOf(false) }
    val spec = if (interacting) snap<Float>() else tween<Float>(320)
    // Focus transitions are finite animations; an idle model has no frame loop.
    val azimuth by animateFloatAsState(camera.azimuth, spec, label = "camera-yaw")
    val elevation by animateFloatAsState(camera.elevation, spec, label = "camera-pitch")
    val distance by animateFloatAsState(camera.distance, spec, label = "camera-distance")
    val pose = camera.copy(azimuth = azimuth, elevation = elevation, distance = distance)
    val motionIds =
        setOf(
            SceneProperties.DOOR_POS,
            SceneProperties.WINDOW_POS,
            SceneProperties.SEAT_FORE_AFT_POS,
            SceneProperties.SEAT_HEIGHT_POS,
            SceneProperties.SEAT_BACKREST_ANGLE_1_POS,
            SceneProperties.MIRROR_Y_POS,
            SceneProperties.MIRROR_Z_POS,
        )
    val displayedValues =
        scene.observations.mapValues { (sceneKey, observed) ->
            if (sceneKey.propertyId in motionIds && observed.value is Number) {
                key(sceneKey) {
                    val value by animateFloatAsState(
                        observed.value.toFloat(),
                        tween(280),
                        label = "component-${sceneKey.propertyId}-${sceneKey.areaId}",
                    )
                    observed.copy(value = value)
                }
            } else {
                observed
            }
        }
    val displayScene = scene.copy(observations = displayedValues)
    val polygons = remember(displayScene) { CarGeometryBuilder.buildCarModel(displayScene) }
    val snapshot =
        remember(polygons, pose, size, selection) {
            if (size.width == 0 ||
                size.height == 0
            ) {
                emptyList()
            } else {
                Car3DRenderer.project(polygons, pose, displayScene, selection, size.width.toFloat(), size.height.toFloat())
            }
        }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentCamera by rememberUpdatedState(camera)
    val currentOnCamera by rememberUpdatedState(onCamera)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val activePart = selection?.part ?: CarPart.DOORS
    val anchors = remember(snapshot, activePart) { VehiclePicking.anchors(snapshot, activePart) }
    val palette = MaterialTheme.colorScheme
    Box(
        modifier.clipToBounds().background(Brush.radialGradient(listOf(palette.surfaceContainerHigh, palette.surface))).onSizeChanged {
            size =
                it
        },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("vehicle-model")
                .semantics {
                    contentDescription = "Vehicle model"
                }.vehicleInput(enabled, { currentCamera }, { currentSnapshot }, { currentOnCamera(it) }, { currentOnSelect(it) }, {
                    interacting =
                        it
                }),
        ) { Car3DRenderer.draw(this, snapshot) }
        // Label only a few non-overlapping visible surfaces. All areas also have a list selector.
        val targets =
            buildList<Pair<VehiclePartSelection, ScreenPoint>> {
                for (candidate in anchors.sortedBy { if (it.first == selection)0 else 1 }) {
                    val (_, point) = candidate
                    if (all { (_, placed) ->
                            abs(point.x - placed.x) >= with(density) { 176.dp.toPx() } ||
                                abs(point.y - placed.y) >= with(density) { 56.dp.toPx() }
                        }
                    ) {
                        add(candidate)
                    }
                    if (this.size == 4)break
                }
            }
        targets.forEach { (part, point) ->
            val isDoor = part.part == CarPart.DOORS || part.part == CarPart.TRUNK
            val doorOpen =
                if (part.part == CarPart.TRUNK) {
                    scene.trunkOpenPercent > 0.05f
                } else {
                    (scene.normalized(SceneProperties.DOOR_POS, SceneArea.DOOR, part.areaId) ?: 0f) > 0.05f
                }
            val x = point.x.roundToInt().coerceIn(0, (size.width - with(density) { 180.dp.toPx() }).roundToInt().coerceAtLeast(0))
            val y = point.y.roundToInt().coerceIn(0, (size.height - with(density) { 48.dp.toPx() }).roundToInt().coerceAtLeast(0))
            val isSelected = part == selection

            Surface(
                onClick = { onSelect(part) },
                enabled = enabled,
                shape = RoundedCornerShape(24.dp),
                color =
                    if (isSelected) {
                        palette.secondaryContainer.copy(alpha = 0.92f)
                    } else {
                        palette.surfaceContainerHighest.copy(alpha = 0.85f)
                    },
                border =
                    BorderStroke(
                        1.dp,
                        if (isSelected) palette.primary else palette.outlineVariant.copy(alpha = 0.5f),
                    ),
                shadowElevation = 3.dp,
                modifier =
                    Modifier
                        .offset { IntOffset(x, y) }
                        .heightIn(min = 44.dp)
                        .widthIn(min = 140.dp, max = 200.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isDoor) {
                        Surface(
                            onClick = { onToggleDoor?.invoke(part) ?: onSelect(part) },
                            enabled = enabled,
                            shape = CircleShape,
                            color =
                                if (doorOpen) {
                                    palette.errorContainer.copy(
                                        alpha = 0.9f,
                                    )
                                } else {
                                    palette.primaryContainer.copy(alpha = 0.9f)
                                },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = if (doorOpen) "Door open" else "Door closed",
                                    tint = if (doorOpen) palette.onErrorContainer else palette.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = label(part) + if (scene.hasObservedState(part)) "" else " · ?",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) palette.onSecondaryContainer else palette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (!enabled) Text("UXR", Modifier.align(Alignment.TopEnd).padding(16.dp), color = palette.onSurfaceVariant)
    }
}

private fun Modifier.vehicleInput(
    enabled: Boolean,
    camera: () -> CameraPose,
    snapshot: () -> List<ProjectedPolygon>,
    onCamera: (CameraPose) -> Unit,
    onSelect: (VehiclePartSelection) -> Unit,
    onInteraction: (Boolean) -> Unit,
): Modifier =
    pointerInput(enabled) {
        var lastTapTime = 0L
        var lastTap = Offset.Unspecified
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = down.position
            var moved = false
            var multiple = false
            try {
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size > 1) multiple = true
                    val pan = event.calculatePan()
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    if (primary != null && (primary.position - origin).getDistance() > viewConfiguration.touchSlop) moved = true
                    val transformed = moved || multiple
                    if (enabled && transformed && pressed.isNotEmpty()) {
                        onInteraction(true)
                        onCamera(VehicleCamera.orbit(camera(), pan.x, pan.y, event.calculateZoom()))
                        event.changes.forEach { it.consume() }
                    }
                    val completedTap = pressed.isEmpty() && !moved && !multiple
                    val acceptsTap = enabled && event.changes.none { it.isConsumed }
                    if (completedTap && acceptsTap) {
                        val upTime = event.changes.first().uptimeMillis
                        val samePosition =
                            lastTap != Offset.Unspecified && (down.position - lastTap).getDistance() < viewConfiguration.touchSlop * 2
                        if (upTime - lastTapTime in 1..300 && samePosition) {
                            onCamera(VehicleCamera.exterior)
                            lastTapTime = 0
                        } else {
                            VehiclePicking.pick(snapshot(), down.position.x, down.position.y)?.let(onSelect)
                            lastTapTime = upTime
                            lastTap = down.position
                        }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                onInteraction(false)
            }
        }
    }
