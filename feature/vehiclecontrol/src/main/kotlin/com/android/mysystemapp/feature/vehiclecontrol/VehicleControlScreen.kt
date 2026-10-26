package com.android.mysystemapp.feature.vehiclecontrol

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EventSeat
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.mysystemapp.core.ui.FocusArea
import com.android.mysystemapp.core.ui.FocusItem
import com.android.mysystemapp.core.ui.RotaryDestination
import com.android.mysystemapp.core.ui.SettingsCardDialog
import com.android.mysystemapp.core.ui.VehicleControlUiMetadata
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.android.mysystemapp.core.ui.toUiControls
import com.android.mysystemapp.core.ui.vehicleBThinScrollbar
import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleUxPolicyState
import com.android.mysystemapp.core.vehiclemodel.CameraPose
import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.VehicleCamera
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import com.android.mysystemapp.core.vehiclemodel.VehicleSceneState
import com.android.mysystemapp.core.vehiclemodel.VehicleViewport
import com.android.mysystemapp.feature.doorcontrol.presentation.workspaceDoorControls
import com.android.mysystemapp.feature.driverassistance.presentation.workspaceAssistanceControls
import com.android.mysystemapp.feature.hvac.presentation.workspaceClimateControls
import com.android.mysystemapp.feature.seatcontrol.presentation.workspaceSeatControls
import com.android.mysystemapp.feature.vehiclelighting.presentation.workspaceLightingControls
import com.b231001.bmaterial.ccp.rotaryfocus.FocusAreaId
import com.b231001.bmaterial.ccp.rotaryfocus.FocusAreaLayout
import com.b231001.bmaterial.ccp.rotaryfocus.FocusAreaOrientation
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemId
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemLayout
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemSemantics
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemTouchBehavior

@Composable
fun VehicleControlRoute(viewModel: VehicleControlViewModel) {
    val doors by viewModel.doors.state.collectAsStateWithLifecycle()
    val seats by viewModel.seats.state.collectAsStateWithLifecycle()
    val lights by viewModel.lights.state.collectAsStateWithLifecycle()
    val assistance by viewModel.assistance.state.collectAsStateWithLifecycle()
    val modes by viewModel.modes.state.collectAsStateWithLifecycle()
    val climate by viewModel.hvac.collectAsStateWithLifecycle(
        initialValue =
            com.android.mysystemapp.feature.hvac.domain
                .ClimateState(),
    )
    val ux by viewModel.uxPolicy.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val all =
        mapOf(
            ControlGroup.DOORS to workspaceDoorControls(doors),
            ControlGroup.SEATS to workspaceSeatControls(seats),
            ControlGroup.LIGHTS to workspaceLightingControls(lights),
            ControlGroup.ASSISTANCE to workspaceAssistanceControls(assistance),
            ControlGroup.CLIMATE to workspaceClimateControls(climate),
            ControlGroup.MODES to
                modes.toUiControls(
                    listOf(
                        VehicleControlUiMetadata(
                            "VALET",
                            section = stringResource(R.string.modes),
                            title = stringResource(R.string.valet),
                            summary = stringResource(R.string.valet_description),
                            info = stringResource(R.string.valet_description),
                            limitations = "",
                            dependencies = "",
                            editor = VehicleEditorUiKind.SWITCH,
                        ),
                    ),
                ) {
                    it.description
                },
        )
    val scene = remember(doors, seats, lights, climate) { bindVehicleScene(listOf(doors, seats, lights), climate) }
    val connected = doors.connection is VehicleConnectionState.Connected
    VehicleControlScreen(all, scene, connected, ux !is VehicleUxPolicyState.Unrestricted, viewModel, message)
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // Declarative layout; commands live in the ViewModel.
private fun VehicleControlScreen(
    all: Map<ControlGroup, List<VehicleControlUiModel>>,
    scene: VehicleSceneState,
    connected: Boolean,
    restricted: Boolean,
    viewModel: VehicleControlViewModel,
    message: String?,
) {
    var groupName by rememberSaveable { mutableStateOf(ControlGroup.DOORS.name) }
    var partName by rememberSaveable { mutableStateOf<String?>(null) }
    var areaName by rememberSaveable { mutableStateOf(SceneArea.GLOBAL.name) }
    var areaId by rememberSaveable { mutableIntStateOf(0) }
    var yaw by rememberSaveable { mutableFloatStateOf(VehicleCamera.exterior.azimuth) }
    var elevation by rememberSaveable { mutableFloatStateOf(VehicleCamera.exterior.elevation) }
    var distance by rememberSaveable { mutableFloatStateOf(VehicleCamera.exterior.distance) }
    var cabin by rememberSaveable { mutableStateOf(false) }
    var activeCameraTitle by rememberSaveable { mutableStateOf<Int?>(R.string.exterior) }
    var info by remember { mutableStateOf<VehicleControlUiModel?>(null) }
    val groupTransition = remember { VehicleGroupTransition() }
    val group = ControlGroup.valueOf(groupName)
    val selection = partName?.let { VehiclePartSelection(CarPart.valueOf(it), SceneArea.valueOf(areaName), areaId) }
    val controls = all[group].orEmpty()
    val locale = LocalConfiguration.current.locales[0].language
    val vi = locale == "vi"

    fun setCamera(pose: CameraPose) {
        yaw = VehicleCamera.shortestAzimuth(yaw, pose.azimuth)
        elevation = pose.elevation
        distance = pose.distance
    }

    fun select(part: VehiclePartSelection) {
        if (groupTransition.isLoading) return
        viewModel.stopMovement()
        groupName = groupFor(part).name
        partName = part.part.name
        areaName = part.areaType.name
        areaId = part.areaId
        cabin = part.part in listOf(CarPart.SEATS, CarPart.HVAC) || part.areaType == SceneArea.MIRROR && part.areaId == 4
        val focusPose = VehicleCamera.focus(part)
        activeCameraTitle =
            when (focusPose) {
                VehicleCamera.cabin -> R.string.cabin
                VehicleCamera.left -> R.string.left
                VehicleCamera.right -> R.string.right
                VehicleCamera.exterior -> R.string.exterior
                else -> null
            }
        setCamera(focusPose)
    }

    fun changeGroup(next: ControlGroup) {
        viewModel.stopMovement()
        groupName = next.name
        partName = null
        areaId = 0
        areaName = SceneArea.GLOBAL.name
        cabin = next in listOf(ControlGroup.SEATS, ControlGroup.CLIMATE)
        activeCameraTitle = if (cabin) R.string.cabin else R.string.exterior
        setCamera(if (cabin) VehicleCamera.cabin else VehicleCamera.exterior)
    }
    LaunchedEffect(groupTransition.target) {
        groupTransition.complete(awaitFrame = { withFrameNanos { } }, changeGroup = ::changeGroup)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.stopMovement() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.stopMovement()
        }
    }
    BackHandler(selection != null && !groupTransition.isLoading) {
        viewModel.stopMovement()
        partName = null
        activeCameraTitle = R.string.exterior
        setCamera(VehicleCamera.exterior)
        cabin = false
    }
    val zones = controls.filter { it.supported }.map { it.areaType to it.areaId }.distinct()
    val filtered = controls.filter { it.matchesSelection(group, selection) }
    val dark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val currentScene = scene.copy(xrayMode = cabin, nightMode = dark)
    val palette = MaterialTheme.colorScheme
    RotaryDestination("vehicle-workspace") {
        Surface(color = palette.background, contentColor = palette.onBackground) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(palette.background)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .testTag("vehicle-workspace"),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DirectionsCar, null, Modifier.size(32.dp), tint = palette.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(if (connected)R.string.live_connection else R.string.disconnected),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.onSurfaceVariant,
                        )
                    }
                    FocusArea(FocusAreaId("workspace-refresh"), modifier = Modifier.width(148.dp).height(56.dp)) {
                        Action("refresh", stringResource(R.string.refresh), Icons.Outlined.Refresh, onClick = viewModel::refresh)
                    }
                }
                if (restricted) {
                    Text(
                        stringResource(R.string.uxr),
                        Modifier.fillMaxWidth().background(palette.errorContainer).padding(12.dp),
                        color = palette.onErrorContainer,
                    )
                }
                message?.let { TextButton(onClick = viewModel::clearMessage) { Text(it, color = palette.error) } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 1280.dp
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(if (wide)158.dp else 116.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            FocusArea(
                                FocusAreaId("workspace-groups"),
                                layout = FocusAreaLayout(itemSpacing = 8.dp),
                                nextFocusArea = FocusAreaId("workspace-camera"),
                            ) {
                                ControlGroup.entries.forEach { next ->
                                    Action(
                                        "group-${next.name}",
                                        groupLabel(next),
                                        groupIcon(next),
                                        selected = next == (groupTransition.target ?: group),
                                        loading = next == groupTransition.target,
                                        enabled = !groupTransition.isLoading,
                                        onClick = { groupTransition.request(next) },
                                        vertical = true,
                                    )
                                }
                            }
                        }
                        val model: @Composable (Modifier) -> Unit = { modifier ->
                            Column(modifier) {
                                FocusArea(
                                    FocusAreaId("workspace-camera"),
                                    modifier = Modifier.fillMaxWidth().height(60.dp),
                                    layout = FocusAreaLayout(FocusAreaOrientation.Horizontal, 6.dp),
                                    nextFocusArea = FocusAreaId("workspace-zones"),
                                ) {
                                    listOf(
                                        R.string.exterior to VehicleCamera.exterior,
                                        R.string.left to VehicleCamera.left,
                                        R.string.right to VehicleCamera.right,
                                        R.string.cabin to VehicleCamera.cabin,
                                    ).forEach { (title, pose) ->
                                        Action(
                                            "camera-$title",
                                            stringResource(title),
                                            null,
                                            selected = activeCameraTitle == title,
                                            onClick = {
                                                viewModel.stopMovement()
                                                setCamera(pose)
                                                activeCameraTitle = title
                                                cabin = title == R.string.cabin
                                            },
                                            enabled = !restricted,
                                            compact = true,
                                        )
                                    }
                                }
                                VehicleViewport(
                                    currentScene,
                                    selection ?: selectionFor(
                                        group,
                                        SceneArea.GLOBAL,
                                        0,
                                    ),
                                    CameraPose(yaw, elevation, distance),
                                    { pose ->
                                        setCamera(pose)
                                        activeCameraTitle =
                                            when {
                                                cabin || pose.elevation >= 55f -> R.string.cabin
                                                kotlin.math.abs(pose.azimuth - VehicleCamera.left.azimuth) < 15f -> R.string.left
                                                kotlin.math.abs(pose.azimuth - VehicleCamera.right.azimuth) < 15f -> R.string.right
                                                kotlin.math.abs(pose.azimuth - VehicleCamera.exterior.azimuth) < 20f -> R.string.exterior
                                                else -> null
                                            }
                                    },
                                    ::select,
                                    {
                                        partLabel(it, vi)
                                    },
                                    Modifier.weight(1f).fillMaxWidth(),
                                    enabled = !restricted && !groupTransition.isLoading,
                                    onToggleDoor = { doorPart ->
                                        val doorControl =
                                            controls.firstOrNull {
                                                (it.key == "DOOR_LOCK" || it.key == "DOOR_MOVE" || it.key == "DOOR_POSITION") &&
                                                    it.areaId == doorPart.areaId
                                            }
                                        if (doorControl != null) {
                                            if (doorControl.key == "DOOR_LOCK") {
                                                viewModel.boolean(ControlGroup.DOORS, doorControl, !(doorControl.booleanValue ?: false))
                                            } else {
                                                viewModel.move(doorControl, 1)
                                            }
                                        }
                                        select(doorPart)
                                    },
                                )
                            }
                        }
                        val panel: @Composable (Modifier) -> Unit = { modifier ->
                            Column(
                                modifier
                                    .testTag("group-content-${group.name}")
                                    .background(palette.surface, RoundedCornerShape(20.dp))
                                    .padding(16.dp),
                            ) {
                                Text(groupLabel(group), style = MaterialTheme.typography.titleLarge)
                                val selectedZoneLabel = selection?.let { partLabel(it, vi) } ?: stringResource(R.string.all_areas)
                                Text(
                                    selectedZoneLabel,
                                    modifier = Modifier.testTag("selected-area"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.primary,
                                )
                                var zoneDropdownExpanded by remember { mutableStateOf(false) }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                ) {
                                    FocusArea(
                                        FocusAreaId("workspace-zones"),
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        layout = FocusAreaLayout(itemSpacing = 4.dp),
                                        nextFocusArea = FocusAreaId("workspace-controls"),
                                    ) {
                                        FocusItem(
                                            FocusItemId("zone-dropdown-trigger"),
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .testTag("zone-dropdown-trigger"),
                                            onClick = { zoneDropdownExpanded = !zoneDropdownExpanded },
                                            semantics = FocusItemSemantics(selectedZoneLabel),
                                            layout = FocusItemLayout(minHeight = 48.dp),
                                        ) { state ->
                                            Surface(
                                                onClick = { zoneDropdownExpanded = !zoneDropdownExpanded },
                                                shape = RoundedCornerShape(12.dp),
                                                color =
                                                    if (state.isFocused) {
                                                        palette.secondaryContainer
                                                    } else {
                                                        palette.surfaceContainerHighest
                                                    },
                                                border = BorderStroke(1.dp, palette.outlineVariant),
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(48.dp),
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(
                                                        text = selectedZoneLabel,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = palette.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Filled.ArrowDropDown,
                                                        contentDescription = "Select area",
                                                        tint = palette.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = zoneDropdownExpanded,
                                        onDismissRequest = { zoneDropdownExpanded = false },
                                        modifier =
                                            Modifier
                                                .width(360.dp)
                                                .background(palette.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.all_areas),
                                                    fontWeight = if (selection == null) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (selection == null) palette.primary else palette.onSurface,
                                                )
                                            },
                                            onClick = {
                                                partName = null
                                                viewModel.stopMovement()
                                                zoneDropdownExpanded = false
                                            },
                                            modifier = Modifier.testTag("zone-all"),
                                        )
                                        zones.forEach { (type, id) ->
                                            val zone = selectionFor(group, type.sceneArea() ?: SceneArea.GLOBAL, id)
                                            val isCurrent = selection == zone
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        partLabel(zone, vi),
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isCurrent) palette.primary else palette.onSurface,
                                                    )
                                                },
                                                onClick = {
                                                    select(zone)
                                                    zoneDropdownExpanded = false
                                                },
                                                modifier = Modifier.testTag("zone-${type.name}-$id"),
                                            )
                                        }
                                    }
                                }
                                val scroll = rememberScrollState()
                                LaunchedEffect(group, selection) { scroll.scrollTo(0) }
                                Column(Modifier.weight(1f).testTag("controls-list")) {
                                    FocusArea(
                                        FocusAreaId("workspace-controls"),
                                        modifier = Modifier.fillMaxSize(),
                                        layout = FocusAreaLayout(itemSpacing = 10.dp),
                                        previousFocusArea = FocusAreaId("workspace-zones"),
                                    ) {
                                        Column(
                                            Modifier
                                                .fillMaxSize()
                                                .vehicleBThinScrollbar(
                                                    scrollState = scroll,
                                                    autoHideEnabled = false,
                                                ).verticalScroll(scroll)
                                                .padding(end = 14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            filtered.forEach { control ->
                                                key(group, control.key, control.areaType, control.areaId) {
                                                    ControlEditor(
                                                        control,
                                                        connected,
                                                        restricted,
                                                        group,
                                                        viewModel,
                                                        onInfo = { info = control },
                                                        zone =
                                                            partLabel(
                                                                selectionFor(
                                                                    group,
                                                                    control.areaType.sceneArea() ?: SceneArea.GLOBAL,
                                                                    control.areaId,
                                                                ),
                                                                vi,
                                                            ),
                                                    )
                                                }
                                            }
                                            if (group == ControlGroup.MODES) {
                                                UnsupportedMode(stringResource(R.string.drive))
                                                UnsupportedMode(stringResource(R.string.pet))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (wide) {
                            model(Modifier.weight(1f).fillMaxHeight())
                            panel(Modifier.width(456.dp).fillMaxHeight())
                        } else {
                            Column(Modifier.weight(1f)) {
                                model(Modifier.fillMaxWidth().weight(.43f))
                                panel(Modifier.fillMaxWidth().weight(.57f))
                            }
                        }
                    }
                }
            }
        }
    }
    info?.let { control ->
        SettingsCardDialog(
            dialogKey = "vehicle-info-${control.key}-${control.areaId}",
            title = control.title,
            message = listOf(control.info, control.limitations, control.dependencies).filter { it.isNotBlank() }.joinToString("\n\n"),
            onDismissRequest = { info = null },
            confirmLabel = stringResource(R.string.close),
        )
    }
}

@Composable
private fun Action(
    id: String,
    label: String,
    icon: ImageVector?,
    selected: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    vertical: Boolean = false,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isComposePressed by interactionSource.collectIsPressedAsState()
    FocusItem(
        FocusItemId(id),
        Modifier.testTag(id).semantics { this.selected = selected },
        isEnabled = enabled,
        onClick = onClick,
        semantics = FocusItemSemantics(label, stateDescription = if (loading) stringResource(R.string.loading_tab) else null),
        touchBehavior = FocusItemTouchBehavior.ComposeContent,
        layout = FocusItemLayout(minHeight = 52.dp, width = if (compact) 116.dp else androidx.compose.ui.unit.Dp.Unspecified),
    ) { state ->
        val isPressed = enabled && (state.isPressed || isComposePressed)
        val isFocused = enabled && state.isFocused
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.94f else 1f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            label = "action-scale",
        )
        val backgroundColor =
            when {
                isPressed -> if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                selected || isFocused -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
        val borderStroke =
            when {
                isFocused -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                isPressed -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                else -> null
            }
        val contentColor =
            when {
                selected || isFocused || isPressed -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
        val mod =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(14.dp))
                .then(
                    if (borderStroke != null) {
                        Modifier.border(borderStroke, RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                ).background(backgroundColor, RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = MaterialTheme.colorScheme.primary),
                    enabled = enabled,
                    onClick = onClick,
                ).padding(horizontal = 12.dp, vertical = 12.dp)
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (vertical) {
                Column(mod, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionIcon(id, icon, loading, 26.dp)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            } else {
                Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionIcon(id, icon, loading, 22.dp)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(
    id: String,
    icon: ImageVector?,
    loading: Boolean,
    size: androidx.compose.ui.unit.Dp,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(size).testTag("$id-loading"),
            color = LocalContentColor.current,
            strokeWidth = 2.dp,
        )
    } else {
        icon?.let { Icon(it, null, Modifier.size(size)) }
    }
}

@Composable
private fun UnsupportedMode(title: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.no_mode_provider),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun groupLabel(group: ControlGroup) =
    stringResource(
        when (group) {
            ControlGroup.MODES -> R.string.modes
            ControlGroup.DOORS -> R.string.doors
            ControlGroup.SEATS -> R.string.seats
            ControlGroup.CLIMATE -> R.string.climate
            ControlGroup.LIGHTS -> R.string.lights
            ControlGroup.ASSISTANCE -> R.string.assistance
        },
    )

private fun groupIcon(group: ControlGroup) =
    when (group) {
        ControlGroup.MODES -> Icons.Outlined.Tune
        ControlGroup.DOORS -> Icons.Outlined.Lock
        ControlGroup.SEATS -> Icons.Outlined.EventSeat
        ControlGroup.CLIMATE -> Icons.Outlined.Air
        ControlGroup.LIGHTS -> Icons.Outlined.LightMode
        ControlGroup.ASSISTANCE -> Icons.Outlined.Shield
    }

fun partLabel(
    selection: VehiclePartSelection,
    vi: Boolean,
): String {
    fun text(
        english: String,
        vietnamese: String,
    ) = if (vi)vietnamese else english
    val fixedLabel =
        when {
            selection.areaType == SceneArea.GLOBAL -> text("Whole vehicle", "Toàn xe")
            selection.areaType == SceneArea.DOOR && selection.areaId == 0x20000000 -> text("Trunk", "Cốp")
            else -> null
        }
    if (fixedLabel != null)return fixedLabel
    val names =
        when (selection.areaType) {
            SceneArea.DOOR, SceneArea.SEAT ->
                listOf(
                    1 to text("front left", "trước trái"),
                    4 to text("front right", "trước phải"),
                    16 to text("rear left", "sau trái"),
                    32 to text("rear center", "sau giữa"),
                    64 to text("rear right", "sau phải"),
                    0x20000000 to text("trunk", "cốp"),
                )
            SceneArea.WINDOW ->
                listOf(
                    1 to text("windshield", "chắn gió trước"),
                    2 to text("rear windshield", "chắn gió sau"),
                    16 to text("front left", "trước trái"),
                    64 to text("front right", "trước phải"),
                    256 to text("rear left", "sau trái"),
                    1024 to text("rear right", "sau phải"),
                    65536 to text("roof", "mái"),
                )
            SceneArea.MIRROR -> listOf(1 to text("left", "trái"), 2 to text("right", "phải"), 4 to text("center", "giữa"))
            else -> emptyList()
        }
    val members = names.filter { (mask, _) -> selection.areaId and mask == mask }
    val knownMask = members.fold(0) { value, pair -> value or pair.first }
    val suffix =
        if (members.isNotEmpty() &&
            knownMask == selection.areaId
        ) {
            members.joinToString(", ") { it.second }
        } else {
            "0x${selection.areaId.toString(16)}"
        }
    val prefix =
        when (selection.areaType) {
            SceneArea.DOOR -> text("Door", "Cửa")
            SceneArea.WINDOW -> text("Window", "Kính")
            SceneArea.MIRROR -> text("Mirror", "Gương")
            SceneArea.SEAT ->
                if (selection.part ==
                    CarPart.SEATS
                ) {
                    text("Seat", "Ghế")
                } else {
                    text("Zone", "Vùng")
                }
            ;else -> ""
        }
    return "$prefix $suffix"
}
