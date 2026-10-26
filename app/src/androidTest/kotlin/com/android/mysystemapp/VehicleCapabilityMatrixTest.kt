package com.android.mysystemapp

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.mysystemapp.core.vehicle.VehicleConnectionState
import com.android.mysystemapp.core.vehicle.VehicleFeatureAreaState
import com.android.mysystemapp.core.vehicle.VehicleFeatureControlState
import com.android.mysystemapp.core.vehicle.VehicleFeatureDefinition
import com.android.mysystemapp.core.vehicle.VehiclePropertyResult
import com.android.mysystemapp.core.vehicle.VehiclePropertySpec
import com.android.mysystemapp.core.vehicle.VehiclePropertyStatus
import com.android.mysystemapp.core.vehicle.VehiclePropertyValueType
import com.android.mysystemapp.core.vehicle.VehiclePropertyWriteResult
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Full live capability/area matrix. Values are restored in finally, including on assertion failure. */
@RunWith(AndroidJUnit4::class)
class VehicleCapabilityMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry get() = EntryPointAccessors.fromApplication(instrumentation.targetContext, VehicleControlEntryPoint::class.java)

    @Test fun everySupportedPropertyAreaCanReadWriteObserveAndRestore() =
        runBlocking {
            val fixture =
                JSONArray(
                    instrumentation.context.assets
                        .open("vehicle-properties.json")
                        .bufferedReader()
                        .readText(),
                )
            val definitions =
                (0 until fixture.length()).map { index ->
                    val p = fixture.getJSONObject(index)
                    val spec =
                        when (p.getString("type").lowercase()) {
                            "boolean" -> VehiclePropertySpec.boolean(p.getInt("id"))
                            "float" -> VehiclePropertySpec.float(p.getInt("id"))
                            else -> VehiclePropertySpec.int(p.getInt("id"))
                        }
                    VehicleFeatureDefinition(p.getString("key"), spec, requiresUnrestrictedUx = true)
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = entry.factory().create(scope, definitions)
            val rows = JSONArray()
            val failures = mutableListOf<String>()
            val finalRestoration = JSONArray()
            val originals = mutableListOf<Triple<VehicleFeatureControlState, VehicleFeatureAreaState, Any>>()
            try {
                val initial =
                    withTimeout(45000) { controller.state.first { !it.loading && it.connection is VehicleConnectionState.Connected } }
                assertEquals(
                    "Unexpected missing capabilities",
                    listOf("FOG_LIGHTS_SWITCH"),
                    initial.controls
                        .filter {
                            !it.supported
                        }.map { it.definition.key },
                )
                // Read the complete snapshot before the first mutation, not a partially loaded callback state.
                initial.controls.filter { it.supported }.forEach { control ->
                    control.areas.filter { it.access.canRead && it.access.canWrite }.forEach { area ->
                        originals += Triple(control, area, read(control, area))
                    }
                }
                val beforeFolder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
                File(beforeFolder, "property-matrix-before.json").writeText(
                    JSONArray()
                        .apply {
                            originals.forEach { (control, area, value) ->
                                put(
                                    JSONObject()
                                        .put(
                                            "propertyId",
                                            control.definition.spec.propertyId,
                                        ).put("areaId", area.area.areaId)
                                        .put("value", value),
                                )
                            }
                        }.toString(2),
                )
                for (control in initial.controls) {
                    if (!control.supported) {
                        rows.put(JSONObject().put("property", control.definition.key).put("status", "UNSUPPORTED"))
                        continue
                    }
                    for (area in control.areas) {
                        val row =
                            JSONObject()
                                .put(
                                    "property",
                                    control.definition.key,
                                ).put("propertyId", control.definition.spec.propertyId)
                                .put("areaId", area.area.areaId)
                                .put("access", area.access.name)
                        rows.put(row)
                        var original: Any? = null
                        try {
                            if (area.access.canRead) {
                                original = read(control, area)
                                row.put("read", "PASS").put("original", original)
                                if (!area.access.canWrite) {
                                    row.put("status", "READ_ONLY_PASS")
                                    continue
                                }
                            }
                            if (!area.access.canWrite) {
                                row.put("status", "READ_ONLY_PASS")
                                continue
                            }
                            val target = alternate(control, area, original)
                            row.put("target", target)
                            if (!area.access.canRead) {
                                set(control, area, target)
                                row.put("write", "ACKNOWLEDGED").put("status", "WRITE_ONLY_ACTION_PASS")
                                continue
                            }
                            when (control.definition.spec.valueType) {
                                VehiclePropertyValueType.BOOLEAN ->
                                    controller.setBoolean(
                                        control.definition.key,
                                        area.area.areaId,
                                        target as Boolean,
                                    )
                                VehiclePropertyValueType.INT ->
                                    controller.setInt(
                                        control.definition.key,
                                        area.area.areaId,
                                        (target as Number).toInt(),
                                    )
                                VehiclePropertyValueType.FLOAT ->
                                    controller.setFloat(
                                        control.definition.key,
                                        area.area.areaId,
                                        (target as Number).toFloat(),
                                    )
                            }
                            withTimeout(6500) {
                                controller.state.first { state ->
                                    state.control(control.definition.key)?.area(area.area.areaId)?.let {
                                        !it.pending &&
                                            same(it.confirmedValue, target) &&
                                            it.error == null
                                    } ==
                                        true
                                }
                            }
                            assertTrue("Readback mismatch", same(target, read(control, area)))
                            row.put("write", "PASS").put("readback", "PASS")
                            // An external write proves the subscription is not just optimistic UI state.
                            external(control, area, original!!)
                            withTimeout(6500) {
                                controller.state.first { state ->
                                    same(state.control(control.definition.key)?.area(area.area.areaId)?.confirmedValue, original)
                                }
                            }
                            row.put("external_event", "PASS").put("status", "PASS")
                        } catch (error: Throwable) {
                            row.put("status", "FAIL").put("error", error.toString())
                            failures += "${control.definition.key}/${area.area.areaId}: $error"
                        } finally {
                            if (original != null && area.access.canWrite) {
                                try {
                                    external(control, area, original)
                                    assertTrue(same(original, read(control, area)))
                                    row.put("restore", "PASS")
                                } catch (error: Throwable) {
                                    row.put("restore", "FAIL")
                                    failures +=
                                        "restore ${control.definition.key}/${area.area.areaId}: $error"
                                }
                            }
                            instrumentation.sendStatus(0, Bundle().apply { putString("matrix", row.toString()) })
                        }
                    }
                }
            } finally {
                // Seat-memory recall and HVAC dependencies may affect other values: restore the full snapshot.
                val power = originals.filter { it.first.definition.spec.propertyId == 0x15200510 }
                power.forEach { (control, area, _) -> runCatching { set(control, area, true) } }
                for ((control, area, value) in originals.asReversed().filter {
                    it.first.definition.spec.propertyId != 0x15200510
                } + power) {
                    runCatching { set(control, area, value) }.onFailure {
                        failures += "final restore ${control.definition.key}/${area.area.areaId}: $it"
                    }
                }
                // A later dependency setter can alter values restored earlier (for example
                // synchronized temperatures). Prove the complete end state independently.
                for ((control, area, expected) in originals) {
                    val result =
                        JSONObject()
                            .put("property", control.definition.key)
                            .put("propertyId", control.definition.spec.propertyId)
                            .put("areaId", area.area.areaId)
                            .put("expected", expected)
                    finalRestoration.put(result)
                    try {
                        val actual = read(control, area) // Also requires AVAILABLE status.
                        result.put("actual", actual)
                        assertTrue(
                            "Final readback ${control.definition.key}/${area.area.areaId}: expected=$expected actual=$actual",
                            same(expected, actual),
                        )
                        result.put("status", "PASS")
                    } catch (error: Throwable) {
                        result.put("status", "FAIL").put("error", error.toString())
                        failures += "final readback ${control.definition.key}/${area.area.areaId}: $error"
                    }
                }
                scope.cancel()
                val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
                File(folder, "property-matrix.json").writeText(
                    JSONObject()
                        .put("rows", rows)
                        .put("failures", JSONArray(failures))
                        .put("final_restore", finalRestoration)
                        .put(
                            "initial_snapshot",
                            JSONArray().apply {
                                originals.forEach { (control, area, value) ->
                                    put(
                                        JSONObject()
                                            .put(
                                                "propertyId",
                                                control.definition.spec.propertyId,
                                            ).put("areaId", area.area.areaId)
                                            .put("value", value),
                                    )
                                }
                            },
                        ).toString(2),
                )
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun read(
        c: VehicleFeatureControlState,
        a: VehicleFeatureAreaState,
    ): Any {
        val result = entry.client().get(c.definition.spec as VehiclePropertySpec<Any>, a.area)
        assertTrue("GET ${c.definition.key}: $result", result is VehiclePropertyResult.Success)
        val value = (result as VehiclePropertyResult.Success).value
        assertEquals(VehiclePropertyStatus.AVAILABLE, value.status)
        return checkNotNull(value.value)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun set(
        c: VehicleFeatureControlState,
        a: VehicleFeatureAreaState,
        value: Any,
    ) {
        val terminal =
            withTimeout(6500) {
                entry
                    .client()
                    .set(
                        c.definition.spec as VehiclePropertySpec<Any>,
                        a.area,
                        value,
                    ).first { it !is VehiclePropertyWriteResult.Pending }
            }
        assertTrue("SET ${c.definition.key}: $terminal", terminal is VehiclePropertyWriteResult.Confirmed)
    }

    private fun alternate(
        c: VehicleFeatureControlState,
        a: VehicleFeatureAreaState,
        current: Any?,
    ): Any =
        when (c.definition.spec.valueType) {
            VehiclePropertyValueType.BOOLEAN -> current != true
            VehiclePropertyValueType.INT ->
                a.supportedEnumValues.firstOrNull {
                    !same(it, current) &&
                        (c.definition.key != "HVAC_FAN_DIRECTION" || (it as? Number)?.toInt() != 0)
                }
                    ?: run {
                        val min =
                            ((a.minValue as? Number)?.toInt() ?: 0).coerceAtLeast(
                                if (c.definition.key ==
                                    "HVAC_FAN_DIRECTION"
                                ) {
                                    1
                                } else {
                                    Int.MIN_VALUE
                                },
                            )
                        val max = (a.maxValue as? Number)?.toInt() ?: min
                        if ((current as? Number)?.toInt() != min)min else max
                    }
            VehiclePropertyValueType.FLOAT -> {
                val min =
                    (a.minValue as? Number)?.toFloat() ?: 0f
                val max = (a.maxValue as? Number)?.toFloat() ?: min
                if (!same(current, min))min else max
            }
        }

    private fun external(
        c: VehicleFeatureControlState,
        a: VehicleFeatureAreaState,
        value: Any,
    ) {
        val data = value.toString()
        val cmd = "cmd car_service set-property-value ${c.definition.spec.propertyId} ${a.area.areaId} $data"
        val output =
            instrumentation.uiAutomation.executeShellCommand(cmd).let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        check(!output.contains("Exception") && !output.contains("Error")) { output }
    }

    private fun same(
        a: Any?,
        b: Any?,
    ) = if (a is Number && b is Number)kotlin.math.abs(a.toDouble() - b.toDouble()) < .001 else a == b
}
