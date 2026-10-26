package com.android.mysystemapp.feature.vehiclecontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.mysystemapp.core.ui.FocusItem
import com.android.mysystemapp.core.ui.VehicleControlUiModel
import com.android.mysystemapp.core.ui.VehicleEditorUiKind
import com.b231001.bmaterial.ccp.rotaryfocus.DirectManipulationConfig
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemId
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemLayout
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemSemantics
import com.b231001.bmaterial.ccp.rotaryfocus.FocusItemTouchBehavior
import com.b231001.bmaterial.ccp.rotaryfocus.RotaryNudgeDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The native focus tree has separate edit, details and STOP targets; none is nested in another item. */
@Composable
internal fun ControlEditor(
    c: VehicleControlUiModel,
    connected: Boolean,
    restricted: Boolean,
    group: ControlGroup,
    vm: VehicleControlViewModel,
    onInfo: () -> Unit,
    zone: String,
) {
    val enabled = connected && c.supported && c.available && c.writable && !restricted
    val moving = c.key in setOf("DOOR_MOVEMENT", "WINDOW_MOVEMENT")
    val identity = "control-${group.name}-${c.key}-${c.areaType.name}-${c.areaId}"
    val input = rememberEditorInput(c, enabled, moving, vm, group)
    val status = controlStatus(c, connected, restricted)
    DisposableEffect(identity) { onDispose { if (moving) vm.stopMovement(c) } }
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusItem(
                FocusItemId(identity),
                Modifier.weight(1f).testTag(identity),
                nextFocusItem = FocusItemId("$identity-info"),
                onClick = {
                    when {
                        !enabled -> onInfo()
                        c.editor == VehicleEditorUiKind.SWITCH -> vm.boolean(group, c, c.booleanValue != true)
                        c.editor == VehicleEditorUiKind.ENUM && !moving -> nextEnumValue(c)?.let { vm.number(group, c, it) }
                    }
                },
                directManipulation = input.directManipulation,
                semantics = FocusItemSemantics("${c.title} · $zone", status ?: c.valueLabel),
                touchBehavior = FocusItemTouchBehavior.ComposeContent,
                layout = FocusItemLayout(minHeight = 52.dp),
            ) { focus ->
                val outline =
                    if (focus.isFocused) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(10.dp),
                        )
                    } else {
                        Modifier
                    }
                Column(Modifier.fillMaxWidth().then(outline).padding(4.dp)) {
                    ControlHeader(c, zone, status, enabled) { vm.boolean(group, c, it) }
                    if (c.editor != VehicleEditorUiKind.SWITCH) {
                        Spacer(Modifier.height(8.dp))
                        ControlValue(
                            c,
                            identity,
                            enabled,
                            moving,
                            input.draft,
                            { input.commit() },
                            { vm.number(group, c, it) },
                            { vm.move(c, it) },
                            { vm.stopMovement(c) },
                        )
                    }
                }
            }
            EditorDetails(identity, c.title, zone, onInfo, moving)
        }
        if (moving) {
            EditorStop(identity, connected && c.supported && c.writable, c.title, zone) { vm.requestStop(c) }
        }
    }
}

private fun nextEnumValue(c: VehicleControlUiModel): Float? =
    c.enumOptions.takeIf { it.isNotEmpty() }?.let { options ->
        options[(options.indexOfFirst { it.key == c.selectedEnumKey } + 1) % options.size].key.toFloat()
    }

@Composable
private fun controlStatus(
    c: VehicleControlUiModel,
    connected: Boolean,
    restricted: Boolean,
): String? =
    when {
        !c.supported -> stringResource(R.string.unsupported)
        !connected -> stringResource(R.string.disconnected)
        c.errorMessage != null -> c.errorMessage
        c.pending -> stringResource(R.string.pending)
        !c.available -> stringResource(R.string.unavailable)
        !c.writable -> stringResource(R.string.read_only)
        restricted -> stringResource(R.string.park_to_adjust)
        else -> null
    }

@Composable
private fun ControlHeader(
    c: VehicleControlUiModel,
    zone: String,
    status: String?,
    enabled: Boolean,
    onBoolean: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium)
            Text(zone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (c.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (c.editor == VehicleEditorUiKind.SWITCH) Switch(checked = c.booleanValue == true, onCheckedChange = onBoolean, enabled = enabled)
    }
}

@Composable
private fun RowScope.EditorDetails(
    identity: String,
    title: String,
    zone: String,
    onInfo: () -> Unit,
    moving: Boolean,
) {
    FocusItem(
        FocusItemId("$identity-info"),
        Modifier
            .size(48.dp)
            .align(Alignment.CenterVertically)
            .testTag("$identity-info"),
        previousFocusItem = FocusItemId(identity),
        nextFocusItem = if (moving) FocusItemId("$identity-stop") else null,
        onClick = onInfo,
        semantics = FocusItemSemantics("${stringResource(R.string.info)} · $title · $zone"),
        layout = FocusItemLayout(width = 48.dp, height = 48.dp),
    ) { focus ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (focus.isFocused) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    RoundedCornerShape(10.dp),
                ).clickable(onClick = onInfo),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Info, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EditorStop(
    identity: String,
    enabled: Boolean,
    title: String,
    zone: String,
    onStop: () -> Unit,
) {
    val label = stringResource(R.string.stop)
    FocusItem(
        FocusItemId("$identity-stop"),
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("$identity-stop"),
        isEnabled = enabled,
        previousFocusItem = FocusItemId("$identity-info"),
        onClick = onStop,
        semantics = FocusItemSemantics("$label · $title · $zone"),
        layout = FocusItemLayout(minHeight = 48.dp),
    ) { focus ->
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    if (focus.isFocused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(10.dp),
                ).clickable(enabled = enabled, onClick = onStop),
            contentAlignment = Alignment.Center,
        ) { Text(label) }
    }
}

@Composable
private fun ControlValue(
    c: VehicleControlUiModel,
    identity: String,
    enabled: Boolean,
    moving: Boolean,
    draft: VehicleEditorDraft,
    onCommit: () -> Unit,
    onNumber: (Float) -> Unit,
    onMove: (Int) -> Unit,
    onRelease: () -> Unit,
) {
    when {
        moving ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                VehicleMovementButton(stringResource(R.string.decrease), enabled, { onMove(-1) }, onRelease)
                VehicleMovementButton(stringResource(R.string.increase), enabled, { onMove(1) }, onRelease)
            }
        c.editor == VehicleEditorUiKind.SLIDER -> {
            Text(
                if (draft.editing) "%.1f".format(draft.value) else c.valueLabel.ifBlank { "—" },
                style = MaterialTheme.typography.titleLarge,
            )
            Slider(
                draft.value,
                onValueChange = { draft.edit(it, c.range) },
                enabled = enabled,
                valueRange = c.range,
                steps = c.steps.coerceIn(0, 500),
                onValueChangeFinished = onCommit,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(c.range.start.toString(), style = MaterialTheme.typography.labelSmall)
                Text(c.range.endInclusive.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
        c.editor == VehicleEditorUiKind.ENUM -> ControlOptions(c, identity, enabled, onNumber)
        else -> Text(c.valueLabel.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ControlOptions(
    c: VehicleControlUiModel,
    identity: String,
    enabled: Boolean,
    onNumber: (Float) -> Unit,
) {
    val columns =
        when (c.enumOptions.size) {
            4 -> 2
            1, 2, 3 -> c.enumOptions.size
            else -> 3
        }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        c.enumOptions.chunked(columns).forEach { options ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    OutlinedButton(
                        onClick = { onNumber(option.key.toFloat()) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$identity-option-${option.key}"),
                        contentPadding = PaddingValues(6.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                containerColor =
                                    if (c.selectedEnumKey ==
                                        option.key
                                    ) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                            ),
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal fun nudgeSteps(direction: RotaryNudgeDirection): Float =
    when (direction) {
        RotaryNudgeDirection.Right, RotaryNudgeDirection.Up -> 1f
        RotaryNudgeDirection.Left, RotaryNudgeDirection.Down -> -1f
    }

internal class VehicleEditorDraft(
    initial: Float,
) {
    var value by mutableFloatStateOf(initial)
        private set
    var editing by mutableStateOf(false)
        private set

    fun edit(
        next: Float,
        range: ClosedFloatingPointRange<Float>,
    ) {
        val bounded = next.coerceIn(range)
        if (bounded != value) {
            value = bounded
            editing = true
        }
    }

    fun reset(confirmed: Float) {
        value = confirmed
        editing = false
    }

    fun commit(onNumber: (Float) -> Unit) {
        if (editing) {
            editing = false
            onNumber(value)
        }
    }
}

private data class EditorInput(
    val draft: VehicleEditorDraft,
    val directManipulation: DirectManipulationConfig?,
    val commit: () -> Unit,
)

@Composable
private fun rememberEditorInput(
    c: VehicleControlUiModel,
    enabled: Boolean,
    moving: Boolean,
    vm: VehicleControlViewModel,
    group: ControlGroup,
): EditorInput {
    val current by rememberUpdatedState(c)
    val canEdit by rememberUpdatedState(enabled)
    val draft = remember(c.instanceKey) { VehicleEditorDraft(c.numericValue ?: c.range.start) }
    val scope = rememberCoroutineScope()
    var pulse by remember(c.instanceKey) { mutableStateOf<Job?>(null) }
    LaunchedEffect(c.numericValue, c.pending, c.errorMessage, enabled) {
        if (!draft.editing || !enabled || c.errorMessage != null) draft.reset(c.numericValue ?: c.range.start)
    }
    val commit = {
        if (canEdit) {
            draft.commit { vm.number(group, current, if (current.usesFloatSlider) it else it.roundToInt().toFloat()) }
        } else {
            draft.reset(current.numericValue ?: current.range.start)
        }
    }

    fun adjust(steps: Float) {
        if (!canEdit || steps == 0f) return
        if (moving) {
            pulse?.cancel()
            vm.move(current, if (steps > 0) 1 else -1)
            pulse =
                scope.launch {
                    delay(180)
                    vm.stopMovement(current)
                }
        } else {
            val step = (current.range.endInclusive - current.range.start) / (current.steps + 1).coerceAtLeast(1)
            draft.edit(draft.value + steps * step, current.range)
        }
    }
    val direct =
        if (enabled && (moving || c.editor == VehicleEditorUiKind.SLIDER)) {
            DirectManipulationConfig(
                onRotary = { adjust(it.detents) },
                onNudge = { adjust(nudgeSteps(it.direction)) },
                onModeChanged = { active ->
                    if (!active) {
                        if (moving) {
                            pulse?.cancel()
                            vm.stopMovement(current)
                        } else {
                            commit()
                        }
                    }
                },
            )
        } else {
            null
        }
    return EditorInput(draft, direct, commit)
}

@Composable
fun VehicleMovementButton(
    label: String,
    enabled: Boolean,
    start: () -> Unit,
    stop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startNow by rememberUpdatedState(start)
    val stopNow by rememberUpdatedState(stop)
    Box(
        modifier
            .heightIn(min = 52.dp)
            .widthIn(min = 100.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (enabled) {
                        startNow()
                        try {
                            tryAwaitRelease()
                        } finally {
                            stopNow()
                        }
                    }
                })
            }.padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline) }
}
