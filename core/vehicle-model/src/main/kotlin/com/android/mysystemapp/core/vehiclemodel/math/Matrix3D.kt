package com.android.mysystemapp.core.vehiclemodel.math

import com.android.mysystemapp.core.vehiclemodel.Vector3D
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Ma trận 4x4 phục vụ các phép biến đổi 3D: Quay, Tịnh tiến, Thu phóng, và Phép chiếu phối cảnh (Perspective Projection).
 */
class Matrix3D {
    val m = FloatArray(16)

    init {
        identity()
    }

    fun identity(): Matrix3D {
        for (i in 0 until 16) {
            m[i] = if (i % 5 == 0) 1f else 0f
        }
        return this
    }

    fun multiply(other: Matrix3D): Matrix3D {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += this.m[row * 4 + k] * other.m[k * 4 + col]
                }
                result[row * 4 + col] = sum
            }
        }
        System.arraycopy(result, 0, this.m, 0, 16)
        return this
    }

    fun translate(
        tx: Float,
        ty: Float,
        tz: Float,
    ): Matrix3D {
        val t = Matrix3D()
        t.m[3] = tx
        t.m[7] = ty
        t.m[11] = tz
        return multiply(t)
    }

    fun scale(
        sx: Float,
        sy: Float,
        sz: Float,
    ): Matrix3D {
        val s = Matrix3D()
        s.m[0] = sx
        s.m[5] = sy
        s.m[10] = sz
        return multiply(s)
    }

    fun rotateX(degrees: Float): Matrix3D {
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val r = Matrix3D()
        r.m[5] = c
        r.m[6] = -s
        r.m[9] = s
        r.m[10] = c
        return multiply(r)
    }

    fun rotateY(degrees: Float): Matrix3D {
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val r = Matrix3D()
        r.m[0] = c
        r.m[2] = s
        r.m[8] = -s
        r.m[10] = c
        return multiply(r)
    }

    fun rotateZ(degrees: Float): Matrix3D {
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val r = Matrix3D()
        r.m[0] = c
        r.m[1] = -s
        r.m[4] = s
        r.m[5] = c
        return multiply(r)
    }

    /**
     * Biến đổi một điểm Vector3D bằng ma trận 4x4
     */
    fun transform(v: Vector3D): Vector3D {
        val x = m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3]
        val y = m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7]
        val z = m[8] * v.x + m[9] * v.y + m[10] * v.z + m[11]
        val w = m[12] * v.x + m[13] * v.y + m[14] * v.z + m[15]
        return if (w != 0f && w != 1f) {
            Vector3D(x / w, y / w, z / w)
        } else {
            Vector3D(x, y, z)
        }
    }

    /**
     * Biến đổi vector pháp tuyến (chỉ xoay/co giãn, không tịnh tiến)
     */
    fun transformNormal(n: Vector3D): Vector3D {
        val x = m[0] * n.x + m[1] * n.y + m[2] * n.z
        val y = m[4] * n.x + m[5] * n.y + m[6] * n.z
        val z = m[8] * n.x + m[9] * n.y + m[10] * n.z
        return Vector3D(x, y, z).normalized()
    }

    companion object {
        /**
         * Tạo ma trận phối cảnh (Perspective projection matrix)
         */
        fun perspective(
            fovYDegrees: Float,
            aspect: Float,
            near: Float,
            far: Float,
        ): Matrix3D {
            val matrix = Matrix3D()
            val theta = Math.toRadians((fovYDegrees / 2.0)).toFloat()
            val f = 1.0f / tan(theta)
            val range = near - far

            matrix.m[0] = f / aspect
            matrix.m[1] = 0f
            matrix.m[2] = 0f
            matrix.m[3] = 0f

            matrix.m[4] = 0f
            matrix.m[5] = f
            matrix.m[6] = 0f
            matrix.m[7] = 0f

            matrix.m[8] = 0f
            matrix.m[9] = 0f
            matrix.m[10] = (far + near) / range
            matrix.m[11] = (2.0f * far * near) / range

            matrix.m[12] = 0f
            matrix.m[13] = 0f
            matrix.m[14] = -1.0f
            matrix.m[15] = 0f

            return matrix
        }

        /**
         * Tạo ma trận Camera LookAt
         */
        fun lookAt(
            eye: Vector3D,
            center: Vector3D,
            up: Vector3D,
        ): Matrix3D {
            val forward = (center - eye).normalized()
            val right = forward.cross(up.normalized()).normalized()
            val trueUp = right.cross(forward).normalized()

            val matrix = Matrix3D()
            matrix.m[0] = right.x
            matrix.m[1] = right.y
            matrix.m[2] = right.z
            matrix.m[3] = -right.dot(eye)

            matrix.m[4] = trueUp.x
            matrix.m[5] = trueUp.y
            matrix.m[6] = trueUp.z
            matrix.m[7] = -trueUp.dot(eye)

            matrix.m[8] = -forward.x
            matrix.m[9] = -forward.y
            matrix.m[10] = -forward.z
            matrix.m[11] = forward.dot(eye)

            matrix.m[12] = 0f
            matrix.m[13] = 0f
            matrix.m[14] = 0f
            matrix.m[15] = 1f

            return matrix
        }
    }
}
