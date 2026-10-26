package com.android.mysystemapp.core.vehiclemodel.geometry

import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.HeadlightMode
import com.android.mysystemapp.core.vehiclemodel.Vector3D
import com.android.mysystemapp.core.vehiclemodel.VehicleSceneState
import com.android.mysystemapp.core.vehiclemodel.model.HingeType
import com.android.mysystemapp.core.vehiclemodel.model.Polygon3D
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bộ tạo dựng mô hình hình học 3D của siêu xe điện thể thao (Sports EV Sedan)
 * Đã sửa toàn bộ thứ tự winding và chuẩn hóa pháp tuyến outward (F01, F02, F10)
 */
object CarGeometryBuilder {
    /** Detailed fixed exterior from Automotive-App, plus area-aware articulated components. */
    fun buildCarModel(config: VehicleSceneState): List<Polygon3D> {
        val polygons = mutableListOf<Polygon3D>()
        val bodyColor = 0xFFA6BBC8
        buildBodyShell(polygons, bodyColor, 0xFF718996, config.xrayMode)
        buildTrunkLid(polygons, bodyColor, config.xrayMode)
        buildLighting(polygons, config)
        buildWheels(polygons)
        return (polygons + ZonedGeometry.build(config)).map { polygon ->
            if (config.xrayMode && polygon.part == CarPart.BODY) polygon.copy(alpha = 0.09f, isTransparent = true) else polygon
        }
    }

    private fun addQuad(
        list: MutableList<Polygon3D>,
        v1: Vector3D,
        v2: Vector3D,
        v3: Vector3D,
        v4: Vector3D,
        part: CarPart,
        color: Long,
        alpha: Float = 1.0f,
        isTransparent: Boolean = false,
        isEmissive: Boolean = false,
        emissiveColor: Long = color,
        isDoubleSided: Boolean = false,
        hingeType: HingeType = HingeType.NONE,
    ) {
        val normal1 = (v2 - v1).cross(v3 - v1).normalized()
        list.add(
            Polygon3D(
                vertices = listOf(v1, v2, v3, v4),
                normal = normal1,
                part = part,
                baseColor = color,
                isTransparent = isTransparent,
                alpha = alpha,
                isEmissive = isEmissive,
                emissiveColor = emissiveColor,
                isDoubleSided = isDoubleSided,
                hingeType = hingeType,
            ),
        )
    }

    private fun addTriangle(
        list: MutableList<Polygon3D>,
        v1: Vector3D,
        v2: Vector3D,
        v3: Vector3D,
        part: CarPart,
        color: Long,
        alpha: Float = 1.0f,
        isTransparent: Boolean = false,
        isEmissive: Boolean = false,
        emissiveColor: Long = color,
        isDoubleSided: Boolean = false,
        hingeType: HingeType = HingeType.NONE,
    ) {
        val normal = (v2 - v1).cross(v3 - v1).normalized()
        list.add(
            Polygon3D(
                vertices = listOf(v1, v2, v3),
                normal = normal,
                part = part,
                baseColor = color,
                isTransparent = isTransparent,
                alpha = alpha,
                isEmissive = isEmissive,
                emissiveColor = emissiveColor,
                isDoubleSided = isDoubleSided,
                hingeType = hingeType,
            ),
        )
    }

    // -------------------------------------------------------------
    // 1. THÂN VỎ XE (BODY SHELL) - Chuẩn hóa Outward Normals
    // -------------------------------------------------------------
    @Suppress("LongMethod") // Contiguous authored mesh coordinates from Automotive-App.
    private fun buildBodyShell(
        polygons: MutableList<Polygon3D>,
        primaryColor: Long,
        secondaryColor: Long,
        isXray: Boolean,
    ) {
        val bodyColor = if (isXray) 0xFF37474F else primaryColor
        val bodyAlpha = if (isXray) 0.35f else 1.0f
        val trimColor = 0xFF14171A

        // Nắp ca-pô (Front Hood) - Outward normal hướng lên trên (+Y)
        val hoodFL = Vector3D(-0.75f, 0.22f, 0.70f)
        val hoodFR = Vector3D(0.75f, 0.22f, 0.70f)
        val hoodCenterL = Vector3D(-0.68f, 0.18f, 1.45f)
        val hoodCenterR = Vector3D(0.68f, 0.18f, 1.45f)
        val noseL = Vector3D(-0.55f, 0.08f, 2.05f)
        val noseR = Vector3D(0.55f, 0.08f, 2.05f)

        // Sửa winding cho nắp ca-pô: (+Z) x (+X) = +Y (hướng lên trên)
        addQuad(polygons, hoodFL, hoodCenterL, hoodCenterR, hoodFR, CarPart.BODY, bodyColor, bodyAlpha, isTransparent = isXray)
        addQuad(polygons, hoodCenterL, noseL, noseR, hoodCenterR, CarPart.BODY, bodyColor, bodyAlpha, isTransparent = isXray)

        // Cản trước và lưới tản nhiệt khí động học (Front Bumper & Nose) - Hướng về phía trước (+Z)
        val bumperBottomL = Vector3D(-0.80f, -0.32f, 2.05f)
        val bumperBottomR = Vector3D(0.80f, -0.32f, 2.05f)
        val noseCenterL = Vector3D(-0.82f, -0.05f, 2.08f)
        val noseCenterR = Vector3D(0.82f, -0.05f, 2.08f)

        // Winding (+X) x (+Y) = +Z (hướng về phía trước)
        addQuad(polygons, noseCenterL, noseCenterR, noseR, noseL, CarPart.BODY, bodyColor, bodyAlpha, isTransparent = isXray)
        addQuad(polygons, bumperBottomL, bumperBottomR, noseCenterR, noseCenterL, CarPart.BODY, trimColor)

        // Hốc gió cản trước (Front Air Curtains)
        addQuad(
            polygons,
            Vector3D(-0.50f, -0.28f, 2.06f),
            Vector3D(0.50f, -0.28f, 2.06f),
            Vector3D(0.45f, -0.15f, 2.06f),
            Vector3D(-0.45f, -0.15f, 2.06f),
            CarPart.BODY,
            0xFF0D0F11,
        )

        // Vè chắn bùn trước (Front Fenders)
        // Vè trái: Hướng ra ngoài bên trái (-X)
        addQuad(
            polygons,
            Vector3D(-0.90f, 0.18f, 0.70f),
            Vector3D(-0.92f, 0.12f, 1.45f),
            hoodCenterL,
            hoodFL,
            CarPart.BODY,
            secondaryColor,
            bodyAlpha,
            isTransparent = isXray,
        )
        addQuad(
            polygons,
            Vector3D(-0.92f, 0.12f, 1.45f),
            Vector3D(-0.82f, -0.05f, 2.08f),
            noseL,
            hoodCenterL,
            CarPart.BODY,
            secondaryColor,
            bodyAlpha,
            isTransparent = isXray,
        )
        // Vè phải: Hướng ra ngoài bên phải (+X)
        addQuad(
            polygons,
            Vector3D(0.92f, 0.12f, 1.45f),
            Vector3D(0.90f, 0.18f, 0.70f),
            hoodFR,
            hoodCenterR,
            CarPart.BODY,
            secondaryColor,
            bodyAlpha,
            isTransparent = isXray,
        )
        addQuad(
            polygons,
            Vector3D(0.82f, -0.05f, 2.08f),
            Vector3D(0.92f, 0.12f, 1.45f),
            hoodCenterR,
            noseR,
            CarPart.BODY,
            secondaryColor,
            bodyAlpha,
            isTransparent = isXray,
        )

        // Cột A và Khung nóc xe (A-Pillars & Roof Rails)
        val roofFrontL = Vector3D(-0.62f, 0.65f, 0.05f)
        val roofFrontR = Vector3D(0.62f, 0.65f, 0.05f)
        val roofRearL = Vector3D(-0.60f, 0.62f, -0.75f)
        val roofRearR = Vector3D(0.60f, 0.62f, -0.75f)

        // Cột A trái & phải
        addQuad(polygons, Vector3D(-0.85f, 0.24f, 0.68f), Vector3D(-0.68f, 0.64f, 0.05f), roofFrontL, hoodFL, CarPart.BODY, trimColor)
        addQuad(polygons, hoodFR, roofFrontR, Vector3D(0.68f, 0.64f, 0.05f), Vector3D(0.85f, 0.24f, 0.68f), CarPart.BODY, trimColor)

        // Ray nóc xe (Roof rails)
        addQuad(polygons, Vector3D(-0.68f, 0.64f, 0.05f), Vector3D(-0.66f, 0.61f, -0.75f), roofRearL, roofFrontL, CarPart.BODY, trimColor)
        addQuad(polygons, roofFrontR, roofRearR, Vector3D(0.66f, 0.61f, -0.75f), Vector3D(0.68f, 0.64f, 0.05f), CarPart.BODY, trimColor)

        // Cột C dốc phong cách Fastback Coupe
        val trunkBaseL = Vector3D(-0.75f, 0.35f, -1.45f)
        val trunkBaseR = Vector3D(0.75f, 0.35f, -1.45f)
        addQuad(polygons, Vector3D(-0.66f, 0.61f, -0.75f), Vector3D(-0.85f, 0.32f, -1.45f), trunkBaseL, roofRearL, CarPart.BODY, trimColor)
        addQuad(polygons, roofRearR, trunkBaseR, Vector3D(0.85f, 0.32f, -1.45f), Vector3D(0.66f, 0.61f, -0.75f), CarPart.BODY, trimColor)

        // Hông sau xe & Vè bánh sau (Rear Quarter Panels & Haunches)
        val rearFenderL = Vector3D(-0.95f, 0.28f, -1.25f)
        val rearFenderR = Vector3D(0.95f, 0.28f, -1.25f)
        val rearBumperTopL = Vector3D(-0.82f, 0.20f, -1.98f)
        val rearBumperTopR = Vector3D(0.82f, 0.20f, -1.98f)
        val rearBumperBotL = Vector3D(-0.78f, -0.32f, -1.95f)
        val rearBumperBotR = Vector3D(0.78f, -0.32f, -1.95f)

        // Hông sau trái (-X)
        addQuad(
            polygons,
            Vector3D(-0.85f, 0.32f, -1.45f),
            Vector3D(-0.75f, 0.32f, -1.95f),
            rearBumperTopL,
            trunkBaseL,
            CarPart.BODY,
            bodyColor,
            bodyAlpha,
            isTransparent = isXray,
        )
        addQuad(
            polygons,
            rearFenderL,
            Vector3D(-0.90f, 0.05f, -1.95f),
            rearBumperTopL,
            Vector3D(-0.85f, 0.32f, -1.45f),
            CarPart.BODY,
            secondaryColor,
            bodyAlpha,
            isTransparent = isXray,
        )

        // Hông sau phải (+X)
        addQuad(
            polygons,
            trunkBaseR,
            rearBumperTopR,
            Vector3D(0.75f, 0.32f, -1.95f),
            Vector3D(0.85f, 0.32f, -1.45f),
            CarPart.BODY,
            bodyColor,
            bodyAlpha,
            isTransparent = isXray,
        )
        addQuad(
            polygons,
            Vector3D(0.85f, 0.32f, -1.45f),
            rearBumperTopR,
            Vector3D(0.90f, 0.05f, -1.95f),
            rearFenderR,
            CarPart.BODY,
            secondaryColor,
            bodyAlpha,
            isTransparent = isXray,
        )

        // Cản sau & Bộ khuếch tán gió gầm (Rear Diffuser & Fins) - Hướng về phía sau (-Z)
        addQuad(polygons, rearBumperTopL, rearBumperTopR, rearBumperBotR, rearBumperBotL, CarPart.BODY, trimColor)
        // Aerodynamic Diffuser fins (Vây khuếch tán gió)
        addQuad(
            polygons,
            Vector3D(-0.35f, -0.32f, -1.75f),
            Vector3D(-0.35f, -0.18f, -1.75f),
            Vector3D(-0.35f, -0.18f, -1.96f),
            Vector3D(-0.35f, -0.32f, -1.96f),
            CarPart.BODY,
            0xFF00E5FF,
        )
        addQuad(
            polygons,
            Vector3D(0.35f, -0.32f, -1.96f),
            Vector3D(0.35f, -0.18f, -1.96f),
            Vector3D(0.35f, -0.18f, -1.75f),
            Vector3D(0.35f, -0.32f, -1.75f),
            CarPart.BODY,
            0xFF00E5FF,
        )

        // Door panels are generated separately per VHAL area.
        val passSillFront = Vector3D(0.88f, -0.28f, 0.70f)
        val passSillRear = Vector3D(0.88f, -0.28f, -0.70f)
        val passBeltFront = Vector3D(0.90f, 0.20f, 0.70f)
        val passBeltRear = Vector3D(0.90f, 0.22f, -0.70f)
    }

    // -------------------------------------------------------------
    // 2. KÍNH XE & CỬA SỔ TRỜI (GLASS & WINDOWS)
    // -------------------------------------------------------------

    // -------------------------------------------------------------
    // 3. CỐP XE ĐIỆN (TRUNK LID)
    // -------------------------------------------------------------
    private fun buildTrunkLid(
        polygons: MutableList<Polygon3D>,
        bodyColor: Long,
        isXray: Boolean,
    ) {
        val trunkBaseL = Vector3D(-0.75f, 0.35f, -1.45f)
        val trunkBaseR = Vector3D(0.75f, 0.35f, -1.45f)
        val trunkLipL = Vector3D(-0.75f, 0.33f, -1.95f)
        val trunkLipR = Vector3D(0.75f, 0.33f, -1.95f)
        val trunkBackL = Vector3D(-0.75f, 0.12f, -1.98f)
        val trunkBackR = Vector3D(0.75f, 0.12f, -1.98f)

        val alpha = if (isXray) 0.35f else 1.0f

        // Nắp trên của cốp (Trunk Top Deck)
        addQuad(
            polygons,
            trunkBaseL,
            trunkBaseR,
            trunkLipR,
            trunkLipL,
            CarPart.TRUNK,
            bodyColor,
            alpha,
            isTransparent = isXray,
            hingeType = HingeType.TRUNK_LID,
        )

        // Đuôi dựng của cốp (Trunk Vertical Tailgate with Spoiler)
        addQuad(
            polygons,
            trunkLipL,
            trunkLipR,
            trunkBackR,
            trunkBackL,
            CarPart.TRUNK,
            bodyColor,
            alpha,
            isTransparent = isXray,
            hingeType = HingeType.TRUNK_LID,
        )

        // Cánh lướt gió thể thao gắn trên mép cốp (Integrated Rear Lip Spoiler)
        val spoilerTopL = Vector3D(-0.76f, 0.37f, -1.97f)
        val spoilerTopR = Vector3D(0.76f, 0.37f, -1.97f)
        addQuad(polygons, trunkLipL, trunkLipR, spoilerTopR, spoilerTopL, CarPart.TRUNK, 0xFF0D0F11, 1.0f, hingeType = HingeType.TRUNK_LID)
    }

    // -------------------------------------------------------------
    // 4. CỬA XE (DRIVER DOOR) - Chuẩn hóa Winding & Window Roll Down
    // -------------------------------------------------------------

    // -------------------------------------------------------------
    // 5. GƯƠNG CHIẾU HẬU (SIDE MIRRORS) - Tích hợp Mirror Tilt F10
    // -------------------------------------------------------------

    // -------------------------------------------------------------
    // 6. HỆ THỐNG ĐÈN XE (LIGHTING) - Winding Hướng Forward (+Z)
    // -------------------------------------------------------------
    private fun buildLighting(
        polygons: MutableList<Polygon3D>,
        config: VehicleSceneState,
    ) {
        val isHeadlightsOn = config.headlightMode != HeadlightMode.OFF
        val lightColor = if (isHeadlightsOn) 0xFFE0F7FA else 0xFF37474F
        val lightEmissive = isHeadlightsOn

        // Cụm đèn pha LED bên trái (Front Left Matrix LED) - Normal hướng +Z
        val hlL1 = Vector3D(-0.78f, 0.14f, 1.95f)
        val hlL2 = Vector3D(-0.54f, 0.11f, 2.05f)
        val hlL3 = Vector3D(-0.55f, 0.05f, 2.05f)
        val hlL4 = Vector3D(-0.76f, 0.07f, 1.96f)
        addQuad(polygons, hlL4, hlL3, hlL2, hlL1, CarPart.HEADLIGHTS, lightColor, isEmissive = lightEmissive)

        // Dải LED ban ngày DRL trái (Cyber Light Blade) - Normal hướng +Z
        val drlColor = if (config.drlEnabled) 0xFF00E5FF else 0xFF455A64
        addQuad(
            polygons,
            Vector3D(-0.79f, 0.14f, 1.94f),
            Vector3D(-0.53f, 0.11f, 2.06f),
            Vector3D(-0.52f, 0.13f, 2.06f),
            Vector3D(-0.80f, 0.16f, 1.93f),
            CarPart.HEADLIGHTS,
            drlColor,
            isEmissive = config.drlEnabled,
        )

        // Cụm đèn pha LED bên phải (Front Right Matrix LED) - Normal hướng +Z
        val hlR1 = Vector3D(0.54f, 0.11f, 2.05f)
        val hlR2 = Vector3D(0.78f, 0.14f, 1.95f)
        val hlR3 = Vector3D(0.76f, 0.07f, 1.96f)
        val hlR4 = Vector3D(0.55f, 0.05f, 2.05f)
        addQuad(polygons, hlR4, hlR3, hlR2, hlR1, CarPart.HEADLIGHTS, lightColor, isEmissive = lightEmissive)

        // Dải LED ban ngày DRL phải - Normal hướng +Z
        addQuad(
            polygons,
            Vector3D(0.53f, 0.11f, 2.06f),
            Vector3D(0.79f, 0.14f, 1.94f),
            Vector3D(0.80f, 0.16f, 1.93f),
            Vector3D(0.52f, 0.13f, 2.06f),
            CarPart.HEADLIGHTS,
            drlColor,
            isEmissive = config.drlEnabled,
        )

        // Đèn sương mù dưới (Fog lights) - Normal hướng +Z
        val fogColor = if (config.fogLightsEnabled) 0xFFFFEB3B else 0xFF263238
        addQuad(
            polygons,
            Vector3D(-0.75f, -0.26f, 2.04f),
            Vector3D(-0.62f, -0.26f, 2.04f),
            Vector3D(-0.62f, -0.22f, 2.04f),
            Vector3D(-0.75f, -0.22f, 2.04f),
            CarPart.HEADLIGHTS,
            fogColor,
            isEmissive = config.fogLightsEnabled,
        )
        addQuad(
            polygons,
            Vector3D(0.62f, -0.26f, 2.04f),
            Vector3D(0.75f, -0.26f, 2.04f),
            Vector3D(0.75f, -0.22f, 2.04f),
            Vector3D(0.62f, -0.22f, 2.04f),
            CarPart.HEADLIGHTS,
            fogColor,
            isEmissive = config.fogLightsEnabled,
        )

        // Dải đèn hậu OLED chạy dài toàn chiều ngang đuôi xe (Continuous Full-Width Rear Lightbar) - Normal hướng -Z
        val tailLightColor = if (isHeadlightsOn) 0xFFFF1744 else 0xFF592733
        val tailL1 = Vector3D(-0.80f, 0.22f, -1.97f)
        val tailR1 = Vector3D(0.80f, 0.22f, -1.97f)
        val tailR2 = Vector3D(0.80f, 0.17f, -1.97f)
        val tailL2 = Vector3D(-0.80f, 0.17f, -1.97f)
        addQuad(
            polygons,
            tailL1,
            tailR1,
            tailR2,
            tailL2,
            CarPart.HEADLIGHTS,
            tailLightColor,
            isEmissive = isHeadlightsOn,
            hingeType = HingeType.TRUNK_LID,
        )
    }

    // -------------------------------------------------------------
    // 7. BÁNH XE & MÂM LỐP (WHEELS & TIRES)
    // -------------------------------------------------------------
    private fun buildWheels(polygons: MutableList<Polygon3D>) {
        val wheelPositions =
            listOf(
                Pair(Vector3D(-0.92f, -0.22f, 1.25f), true), // Front Left
                Pair(Vector3D(0.92f, -0.22f, 1.25f), false), // Front Right
                Pair(Vector3D(-0.92f, -0.22f, -1.25f), true), // Rear Left
                Pair(Vector3D(0.92f, -0.22f, -1.25f), false), // Rear Right
            )

        val tireRadius = 0.32f
        val rimRadius = 0.22f
        val tireWidth = 0.16f
        val tireColor = 0xFF1C1E20
        val rimColor = 0xFFECEFF1
        val rimSpokeColor = 0xFF37474F
        val caliperColor = 0xFF536875

        wheelPositions.forEach { (center, isLeft) ->
            val sign = if (isLeft) -1f else 1f
            val segments = 12

            // Vòng lốp xe đa giác (Tire Tread & Outer Face)
            for (i in 0 until segments) {
                val theta1 = Math.toRadians((i * (360.0 / segments))).toFloat()
                val theta2 = Math.toRadians(((i + 1) * (360.0 / segments))).toFloat()

                val y1 = center.y + tireRadius * sin(theta1)
                val z1 = center.z + tireRadius * cos(theta1)
                val y2 = center.y + tireRadius * sin(theta2)
                val z2 = center.z + tireRadius * cos(theta2)

                val xOuter = center.x + sign * (tireWidth / 2)
                val xInner = center.x - sign * (tireWidth / 2)

                // Bề mặt gai lốp tiếp đất (Tire tread outer band)
                val t1 = Vector3D(xOuter, y1, z1)
                val t2 = Vector3D(xInner, y1, z1)
                val t3 = Vector3D(xInner, y2, z2)
                val t4 = Vector3D(xOuter, y2, z2)
                if (isLeft) {
                    addQuad(polygons, t1, t4, t3, t2, CarPart.WHEELS, tireColor)
                } else {
                    addQuad(polygons, t1, t2, t3, t4, CarPart.WHEELS, tireColor)
                }

                // Mặt ngoài của lốp (Tire sidewall)
                val ry1 = center.y + rimRadius * sin(theta1)
                val rz1 = center.z + rimRadius * cos(theta1)
                val ry2 = center.y + rimRadius * sin(theta2)
                val rz2 = center.z + rimRadius * cos(theta2)

                val s1 = Vector3D(xOuter, y1, z1)
                val s2 = Vector3D(xOuter, y2, z2)
                val s3 = Vector3D(xOuter, ry2, rz2)
                val s4 = Vector3D(xOuter, ry1, rz1)
                if (isLeft) {
                    addQuad(polygons, s1, s2, s3, s4, CarPart.WHEELS, 0xFF24272A)
                } else {
                    addQuad(polygons, s1, s4, s3, s2, CarPart.WHEELS, 0xFF24272A)
                }
            }

            // Đĩa phanh & Cùm phanh thể thao bên trong mâm (Brake Rotor & Caliper)
            val discX = center.x + sign * 0.02f
            val discRadius = 0.18f
            for (i in 0 until 6) {
                val a1 = Math.toRadians((i * 60.0)).toFloat()
                val a2 = Math.toRadians(((i + 1) * 60.0)).toFloat()
                val vDiscCenter = Vector3D(discX, center.y, center.z)
                val vD1 = Vector3D(discX, center.y + discRadius * sin(a1), center.z + discRadius * cos(a1))
                val vD2 = Vector3D(discX, center.y + discRadius * sin(a2), center.z + discRadius * cos(a2))
                if (isLeft) {
                    addTriangle(polygons, vDiscCenter, vD1, vD2, CarPart.WHEELS, 0xFF78909C)
                } else {
                    addTriangle(polygons, vDiscCenter, vD2, vD1, CarPart.WHEELS, 0xFF78909C)
                }
            }

            // Kẹp phanh Brembo Caliper (Top-left quadrant of brake disc)
            val calX = center.x + sign * 0.05f
            val cal1 = Vector3D(calX, center.y + 0.12f, center.z + 0.08f)
            val cal2 = Vector3D(calX, center.y + 0.19f, center.z + 0.02f)
            val cal3 = Vector3D(calX, center.y + 0.14f, center.z - 0.09f)
            val cal4 = Vector3D(calX, center.y + 0.07f, center.z - 0.04f)
            if (isLeft) {
                addQuad(polygons, cal1, cal2, cal3, cal4, CarPart.WHEELS, caliperColor, isEmissive = true)
            } else {
                addQuad(polygons, cal1, cal4, cal3, cal2, CarPart.WHEELS, caliperColor, isEmissive = true)
            }

            // Nan hoa mâm xe (Wheel Rim Spokes)
            val rimFaceX = center.x + sign * (tireWidth / 2 + 0.01f)
            val numSpokes = 7
            for (s in 0 until numSpokes) {
                val angle = Math.toRadians((s * (360.0 / numSpokes))).toFloat()
                val spokeWidthAngle = Math.toRadians(8.0).toFloat()

                val spY1 = center.y + rimRadius * sin(angle - spokeWidthAngle)
                val spZ1 = center.z + rimRadius * cos(angle - spokeWidthAngle)
                val spY2 = center.y + rimRadius * sin(angle + spokeWidthAngle)
                val spZ2 = center.z + rimRadius * cos(angle + spokeWidthAngle)

                val hubRadius = 0.05f
                val hubY1 = center.y + hubRadius * sin(angle - spokeWidthAngle)
                val hubZ1 = center.z + hubRadius * cos(angle - spokeWidthAngle)
                val hubY2 = center.y + hubRadius * sin(angle + spokeWidthAngle)
                val hubZ2 = center.z + hubRadius * cos(angle + spokeWidthAngle)

                val sp1 = Vector3D(rimFaceX, spY1, spZ1)
                val sp2 = Vector3D(rimFaceX, spY2, spZ2)
                val sp3 = Vector3D(rimFaceX, hubY2, hubZ2)
                val sp4 = Vector3D(rimFaceX, hubY1, hubZ1)

                val spokeColor = if (s % 2 == 0) rimColor else rimSpokeColor
                if (isLeft) {
                    addQuad(polygons, sp1, sp2, sp3, sp4, CarPart.WHEELS, spokeColor)
                } else {
                    addQuad(polygons, sp1, sp4, sp3, sp2, CarPart.WHEELS, spokeColor)
                }
            }

            // Nắp chụp logo trục bánh xe (Hub Center Cap)
            val cap1 = Vector3D(rimFaceX + sign * 0.01f, center.y + 0.04f, center.z + 0.04f)
            val cap2 = Vector3D(rimFaceX + sign * 0.01f, center.y + 0.04f, center.z - 0.04f)
            val cap3 = Vector3D(rimFaceX + sign * 0.01f, center.y - 0.04f, center.z - 0.04f)
            val cap4 = Vector3D(rimFaceX + sign * 0.01f, center.y - 0.04f, center.z + 0.04f)
            if (isLeft) {
                addQuad(polygons, cap1, cap2, cap3, cap4, CarPart.WHEELS, 0xFF00E5FF, isEmissive = true)
            } else {
                addQuad(polygons, cap1, cap4, cap3, cap2, CarPart.WHEELS, 0xFF00E5FF, isEmissive = true)
            }
        }
    }

    // -------------------------------------------------------------
    // 8. GHẾ NGỒI & KHOANG NỘI THẤT (INTERIOR CABIN)
    // -------------------------------------------------------------

    // -------------------------------------------------------------
    // 9. KHUNG GẦM & BỘ PIN ĐIỆN EV (CHASSIS & BATTERY PACK)
    // -------------------------------------------------------------
}
