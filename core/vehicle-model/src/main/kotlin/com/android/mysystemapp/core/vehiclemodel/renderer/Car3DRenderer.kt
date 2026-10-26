package com.android.mysystemapp.core.vehiclemodel.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.android.mysystemapp.core.vehiclemodel.CameraPose
import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.SceneArea
import com.android.mysystemapp.core.vehiclemodel.Vector3D
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import com.android.mysystemapp.core.vehiclemodel.VehicleSceneState
import com.android.mysystemapp.core.vehiclemodel.math.Matrix3D
import com.android.mysystemapp.core.vehiclemodel.model.HingeType
import com.android.mysystemapp.core.vehiclemodel.model.Polygon3D
import com.android.mysystemapp.core.vehiclemodel.model.ProjectedPolygon
import com.android.mysystemapp.core.vehiclemodel.model.ScreenPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object Car3DRenderer {
    private val keyLightDir = Vector3D(-0.4f, 1.2f, 0.7f).normalized()
    private val fillLightDir = Vector3D(0.8f, 0.5f, -0.6f).normalized()

    private data class ClipVertex(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float,
    )

    /**
     * Biến đổi và chiếu hình học 3D của xe lên mặt phẳng 2D
     */
    @Suppress("LoopWithTooManyJumpStatements")
    fun project(
        polygons: List<Polygon3D>,
        cameraPose: CameraPose,
        config: VehicleSceneState,
        selectedPart: VehiclePartSelection?,
        viewWidth: Float,
        viewHeight: Float,
    ): List<ProjectedPolygon> {
        val aspect = viewWidth / max(1f, viewHeight)

        // 1. Tính toán tọa độ vị trí Camera (Eye Position)
        val azRad = Math.toRadians(cameraPose.azimuth.toDouble())
        val elRad = Math.toRadians(cameraPose.elevation.toDouble())
        val eyeX = (cameraPose.distance * cos(elRad) * sin(azRad)).toFloat() + cameraPose.targetX
        val eyeY = (cameraPose.distance * sin(elRad)).toFloat() + cameraPose.targetY
        val eyeZ = (cameraPose.distance * cos(elRad) * cos(azRad)).toFloat() + cameraPose.targetZ

        val eye = Vector3D(eyeX, eyeY, eyeZ)
        val center = Vector3D(cameraPose.targetX, cameraPose.targetY, cameraPose.targetZ)
        val up = Vector3D(0f, 1f, 0f)

        // Ma trận View & Projection
        val viewMatrix = Matrix3D.lookAt(eye, center, up)
        val projMatrix = Matrix3D.perspective(fovYDegrees = 42f, aspect = aspect, near = 0.4f, far = 50f)
        val vpMatrix = projMatrix.multiply(viewMatrix)

        // 2. Vẽ bối cảnh Studio: Nền sàn phản chiếu, bóng xe và chùm sáng đèn pha

        // 3. Biến đổi các mặt đa giác với Hinge Rotation (Cốp mở, Cửa mở)
        val projectedPolys = mutableListOf<ProjectedPolygon>()

        val trunkHingePivot = Vector3D(0f, 0.35f, -1.45f)
        val maxTrunkAngle = 55f * (config.trunkMaxHeightPercent / 100f).coerceIn(0.5f, 1.0f)
        val trunkOpenAngle = config.trunkOpenPercent * maxTrunkAngle

        val doorHingePivot = Vector3D(-0.88f, 0f, 0.70f)
        // Cửa tài xế (bên trái x < 0) mở bung ra ngoài (hướng dương theta)
        val doorOpenAngle = config.driverDoorOpenPercent * 45f

        for (poly in polygons) {
            val transformedVertices =
                poly.vertices.map { v ->
                    when (poly.hingeType) {
                        HingeType.TRUNK_LID -> rotateAroundAxisX(v, trunkHingePivot, trunkOpenAngle)
                        HingeType.DRIVER_DOOR -> rotateAroundAxisY(v, doorHingePivot, doorOpenAngle)
                        else -> v
                    }
                }

            // Tính pháp tuyến bề mặt sau biến đổi
            val v0 = transformedVertices[0]
            val v1 = transformedVertices[1]
            val v2 = transformedVertices[2]
            val normal = (v1 - v0).cross(v2 - v0).normalized()

            // Vector từ bề mặt tới camera
            val viewDir = (eye - v0).normalized()
            val dotView = normal.dot(viewDir)

            // Backface culling: nếu không phải 2 mặt và quay lưng về camera thì bỏ qua
            if (!poly.isDoubleSided && dotView <= -0.01f) {
                continue
            }

            // Cắt đa giác với near-plane (Sutherland-Hodgman) để chống vỡ hình học khi zoom sát
            val clippedVertices = clipPolygonNearPlane(transformedVertices, vpMatrix, nearW = 0.15f)
            if (clippedVertices.size < 3) continue

            val screenPoints = mutableListOf<ScreenPoint>()
            var totalDepth = 0f
            var valid = true

            for (cv in clippedVertices) {
                val invW = 1f / cv.w
                val ndcX = cv.x * invW
                val ndcY = cv.y * invW
                val ndcZ = cv.z * invW

                if (!ndcX.isFinite() || !ndcY.isFinite() || !ndcZ.isFinite()) {
                    valid = false
                    break
                }

                val screenX = (ndcX + 1f) * 0.5f * viewWidth
                val screenY = (1f - (ndcY + 1f) * 0.5f) * viewHeight
                screenPoints.add(ScreenPoint(screenX, screenY, ndcZ))
                totalDepth += ndcZ
            }

            if (!valid || screenPoints.size < 3) continue

            val avgDepth = totalDepth / screenPoints.size

            // Tính toán màu sắc với ánh sáng Directional + Specular
            val selection =
                poly.selection ?: when (poly.part) {
                    CarPart.HEADLIGHTS -> VehiclePartSelection(CarPart.HEADLIGHTS)
                    CarPart.TRUNK -> VehiclePartSelection(CarPart.TRUNK, SceneArea.DOOR, 0x20000000)
                    else -> null
                }
            val isSelected = selectedPart?.overlaps(selection) == true
            val litColor =
                if (poly.isEmissive) {
                    poly.emissiveColor
                } else {
                    computeLighting(poly, normal, viewDir, config, isSelected)
                }

            projectedPolys.add(
                ProjectedPolygon(
                    screenPoints = screenPoints,
                    averageDepth = avgDepth,
                    color = litColor,
                    alpha = poly.alpha,
                    part = poly.part,
                    isEmissive = poly.isEmissive,
                    isSelected = isSelected,
                    selection = selection,
                    observationKnown = config.hasObservedState(selection),
                ),
            )
        }

        // 4. Sắp xếp đa giác theo chiều sâu (Painter's Algorithm Z-sorting)
        projectedPolys.sortByDescending { it.averageDepth }

        return projectedPolys
    }

    fun draw(
        drawScope: DrawScope,
        snapshot: List<ProjectedPolygon>,
    ) {
        snapshot.forEach { drawPolygon(drawScope, it) }
    }

    private fun computeLighting(
        poly: Polygon3D,
        normal: Vector3D,
        viewDir: Vector3D,
        config: VehicleSceneState,
        isSelected: Boolean,
    ): Long {
        val ambientBase = if (config.nightMode) 0.28f else 0.45f

        val diffKey = max(0f, normal.dot(keyLightDir))
        val diffFill = max(0f, normal.dot(fillLightDir)) * 0.4f
        val diffuse = ambientBase + diffKey * 0.55f + diffFill

        // Specular highlight (Phản xạ ánh kim)
        val reflectKey = (normal * (2f * normal.dot(keyLightDir)) - keyLightDir).normalized()
        val specFactor = max(0f, reflectKey.dot(viewDir)).pow(36f)
        val specular = specFactor * 0.65f * 0.45f

        val baseColor = poly.baseColor
        val rBase = ((baseColor shr 16) and 0xFF) / 255f
        val gBase = ((baseColor shr 8) and 0xFF) / 255f
        val bBase = (baseColor and 0xFF) / 255f

        var r = min(1f, rBase * diffuse + specular)
        var g = min(1f, gBase * diffuse + specular)
        var b = min(1f, bBase * diffuse + specular)

        // Nếu bộ phận đang được chọn: tăng cường độ sáng và phủ ánh sắc cyan nổi bật
        if (isSelected) {
            r = min(1f, r * 1.15f + 0.1f)
            g = min(1f, g * 1.25f + 0.2f)
            b = min(1f, b * 1.30f + 0.3f)
        }

        val rInt = (r * 255).toInt().coerceIn(0, 255)
        val gInt = (g * 255).toInt().coerceIn(0, 255)
        val bInt = (b * 255).toInt().coerceIn(0, 255)

        return (0xFFL shl 24) or (rInt.toLong() shl 16) or (gInt.toLong() shl 8) or bInt.toLong()
    }

    private fun drawPolygon(
        drawScope: DrawScope,
        poly: ProjectedPolygon,
    ) {
        val path = Path()
        val pts = poly.screenPoints
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
        }
        path.close()

        val observedColor = Color(poly.color)
        val neutral = (observedColor.red + observedColor.green + observedColor.blue) / 3f
        val color =
            if (poly.observationKnown) {
                observedColor.copy(alpha = poly.alpha)
            } else {
                Color(neutral, neutral, neutral, poly.alpha * .72f)
            }
        drawScope.drawPath(path = path, color = color)

        // Vẽ viền sáng khi được chọn
        if (poly.isSelected) {
            drawScope.drawPath(
                path = path,
                color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                style =
                    Stroke(
                        width = 2.5f,
                        pathEffect = if (poly.observationKnown) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    ),
            )
        }
    }

    private fun clipPolygonNearPlane(
        vertices: List<Vector3D>,
        vpMatrix: Matrix3D,
        nearW: Float = 0.15f,
    ): List<ClipVertex> {
        val m = vpMatrix.m
        val clipVertices =
            vertices.map { v ->
                ClipVertex(
                    m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3],
                    m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7],
                    m[8] * v.x + m[9] * v.y + m[10] * v.z + m[11],
                    m[12] * v.x + m[13] * v.y + m[14] * v.z + m[15],
                )
            }
        if (clipVertices.isEmpty() || clipVertices.any { !listOf(it.x, it.y, it.z, it.w).all(Float::isFinite) })return emptyList()

        val outputList = ArrayList<ClipVertex>()
        var prevVertex = clipVertices.last()
        var prevInside = prevVertex.w >= nearW

        for (currVertex in clipVertices) {
            val currInside = currVertex.w >= nearW
            if (currInside) {
                if (!prevInside) {
                    val t = (nearW - prevVertex.w) / (currVertex.w - prevVertex.w)
                    outputList.add(
                        ClipVertex(
                            x = prevVertex.x + t * (currVertex.x - prevVertex.x),
                            y = prevVertex.y + t * (currVertex.y - prevVertex.y),
                            z = prevVertex.z + t * (currVertex.z - prevVertex.z),
                            w = nearW,
                        ),
                    )
                }
                outputList.add(currVertex)
            } else if (prevInside) {
                val t = (nearW - prevVertex.w) / (currVertex.w - prevVertex.w)
                outputList.add(
                    ClipVertex(
                        x = prevVertex.x + t * (currVertex.x - prevVertex.x),
                        y = prevVertex.y + t * (currVertex.y - prevVertex.y),
                        z = prevVertex.z + t * (currVertex.z - prevVertex.z),
                        w = nearW,
                    ),
                )
            }
            prevVertex = currVertex
            prevInside = currInside
        }

        return outputList
    }

    private fun rotateAroundAxisX(
        v: Vector3D,
        pivot: Vector3D,
        degrees: Float,
    ): Vector3D {
        if (degrees == 0f) return v
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val relY = v.y - pivot.y
        val relZ = v.z - pivot.z
        val newY = relY * c - relZ * s + pivot.y
        val newZ = relY * s + relZ * c + pivot.z
        return Vector3D(v.x, newY, newZ)
    }

    private fun rotateAroundAxisY(
        v: Vector3D,
        pivot: Vector3D,
        degrees: Float,
    ): Vector3D {
        if (degrees == 0f) return v
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val relX = v.x - pivot.x
        val relZ = v.z - pivot.z
        val newX = relX * c + relZ * s + pivot.x
        val newZ = -relX * s + relZ * c + pivot.z
        return Vector3D(newX, v.y, newZ)
    }
}
