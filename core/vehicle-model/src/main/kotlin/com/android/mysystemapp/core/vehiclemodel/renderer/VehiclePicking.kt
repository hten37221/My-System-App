package com.android.mysystemapp.core.vehiclemodel.renderer

import com.android.mysystemapp.core.vehiclemodel.CarPart
import com.android.mysystemapp.core.vehiclemodel.VehiclePartSelection
import com.android.mysystemapp.core.vehiclemodel.model.ProjectedPolygon
import com.android.mysystemapp.core.vehiclemodel.model.ScreenPoint
import kotlin.math.abs

object VehiclePicking {
    /** Interpolate depth inside a triangle rather than choosing by polygon average depth. */
    fun depthAt(
        polygon: ProjectedPolygon,
        x: Float,
        y: Float,
    ): Float? {
        val p = polygon.screenPoints
        for (i in 1 until p.lastIndex) {
            val a = p[0]
            val b = p[i]
            val c = p[i + 1]
            val d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
            if (abs(d) < .0001f) continue
            val u = ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / d
            val v = ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / d
            val w = 1f - u - v
            if (u >= -.0001f && v >= -.0001f && w >= -.0001f) return u * a.z + v * b.z + w * c.z
        }
        return null
    }

    fun pick(
        snapshot: List<ProjectedPolygon>,
        x: Float,
        y: Float,
    ): VehiclePartSelection? =
        snapshot
            .asSequence()
            .filter { it.alpha >= .15f }
            .mapNotNull { poly -> depthAt(poly, x, y)?.let { poly to it } }
            .minByOrNull { it.second }
            ?.first
            ?.selection

    /** Visible component centroids used as 48dp labelled selection targets. */
    fun anchors(
        snapshot: List<ProjectedPolygon>,
        candidatePart: CarPart? = null,
    ): List<Pair<VehiclePartSelection, ScreenPoint>> =
        snapshot
            .filter { it.selection != null && it.alpha >= .15f }
            .filter { candidatePart == null || it.selection?.part == candidatePart }
            .groupBy { it.selection!! }
            .mapNotNull { (selection, polys) ->
                polys.sortedBy { it.averageDepth }.firstNotNullOfOrNull { poly ->
                    val point =
                        ScreenPoint(
                            poly.screenPoints
                                .map { it.x }
                                .average()
                                .toFloat(),
                            poly.screenPoints
                                .map { it.y }
                                .average()
                                .toFloat(),
                            poly.averageDepth,
                        )
                    if (pick(snapshot, point.x, point.y) == selection) selection to point else null
                }
            }
}
