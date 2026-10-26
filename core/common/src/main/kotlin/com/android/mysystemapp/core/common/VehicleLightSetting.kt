package com.android.mysystemapp.core.common

/** Standard AAOS VehicleLightSwitch settings; vendor values keep their numeric identity. */
enum class VehicleLightSetting(
    val value: Int,
) {
    OFF(0),
    ON(1),
    DAYTIME_RUNNING(2),
    AUTOMATIC(0x100),
}
