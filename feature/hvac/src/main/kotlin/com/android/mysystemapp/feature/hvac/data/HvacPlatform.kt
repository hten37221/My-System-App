package com.android.mysystemapp.feature.hvac.data

import com.android.mysystemapp.core.common.ActionResult
import com.android.mysystemapp.feature.hvac.domain.ClimateState
import kotlinx.coroutines.flow.StateFlow

internal interface HvacPlatform {
    val state: StateFlow<ClimateState>

    suspend fun refresh(): ActionResult

    suspend fun setBoolean(
        key: String,
        value: Boolean,
    ): ActionResult

    suspend fun setInt(
        key: String,
        value: Int,
    ): ActionResult

    suspend fun setFloat(
        key: String,
        value: Float,
    ): ActionResult
}
