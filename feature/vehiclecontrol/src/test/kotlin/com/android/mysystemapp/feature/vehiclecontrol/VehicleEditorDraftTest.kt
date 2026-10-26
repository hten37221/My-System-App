package com.android.mysystemapp.feature.vehiclecontrol

import com.b231001.bmaterial.ccp.rotaryfocus.RotaryNudgeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleEditorDraftTest {
    @Test fun dpadDirectionsFollowSliderConventions() {
        assertEquals(1f, nudgeSteps(RotaryNudgeDirection.Right))
        assertEquals(1f, nudgeSteps(RotaryNudgeDirection.Up))
        assertEquals(-1f, nudgeSteps(RotaryNudgeDirection.Left))
        assertEquals(-1f, nudgeSteps(RotaryNudgeDirection.Down))
    }

    @Test fun boundedDraftCommitsOnceAtTheEndOfAdjustment() {
        val draft = VehicleEditorDraft(0f)
        val writes = mutableListOf<Float>()
        draft.edit(1f, -1f..1f)
        draft.edit(2f, -1f..1f)
        assertEquals(1f, draft.value)
        assertEquals(emptyList<Float>(), writes)
        draft.commit(writes::add)
        draft.commit(writes::add)
        assertEquals(listOf(1f), writes)
        assertFalse(draft.editing)
    }

    @Test fun cancelledOrDisabledDraftReturnsToTheConfirmedValue() {
        val draft = VehicleEditorDraft(0f)
        draft.edit(.5f, 0f..1f)
        draft.reset(0f)
        val writes = mutableListOf<Float>()
        draft.commit(writes::add)
        assertEquals(0f, draft.value)
        assertEquals(emptyList<Float>(), writes)
    }
}
