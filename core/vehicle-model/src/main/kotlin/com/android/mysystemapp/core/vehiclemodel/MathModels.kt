package com.android.mysystemapp.core.vehiclemodel

import kotlin.math.sqrt

data class Vector3D(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
) {
    operator fun plus(other: Vector3D) = Vector3D(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector3D) = Vector3D(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Float) = Vector3D(x * scalar, y * scalar, z * scalar)

    operator fun div(scalar: Float) = Vector3D(x / scalar, y / scalar, z / scalar)

    fun dot(other: Vector3D): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3D): Vector3D =
        Vector3D(
            x = y * other.z - z * other.y,
            y = z * other.x - x * other.z,
            z = x * other.y - y * other.x,
        )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vector3D {
        val len = length()
        return if (len > 0.00001f) this / len else Vector3D(0f, 0f, 1f)
    }
}

data class CameraPose(
    val azimuth: Float = 45f,
    val elevation: Float = 18f,
    val distance: Float = 5.2f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val targetZ: Float = 0f,
)
