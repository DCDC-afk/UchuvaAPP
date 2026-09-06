package com.example.uchuvatwinapp

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class Point2(val x: Double, val y: Double)
data class PlanarPosition(val xCm: Double, val yCm: Double)

class PlanarMapping private constructor(private val h: DoubleArray, private val origin: Point2, private val scale: Double) {
    fun project(point: Point2): PlanarPosition? {
        val x = (point.x - origin.x) / scale
        val y = (point.y - origin.y) / scale
        val denominator = h[6] * x + h[7] * y + 1.0
        if (!denominator.isFinite() || denominator < 1e-8) return null
        val px = (h[0] * x + h[1] * y + h[2]) / denominator
        val py = (h[3] * x + h[4] * y + h[5]) / denominator
        return if (px.isFinite() && py.isFinite()) PlanarPosition(px, py) else null
    }

    companion object {
        fun fromMarker(corners: List<Point2>, sideCm: Double): PlanarMapping? {
            if (corners.size != 4 || !sideCm.isFinite() || sideCm <= 0 || corners.any { !it.x.isFinite() || !it.y.isFinite() }) return null
            val span = max(corners.maxOf { it.x } - corners.minOf { it.x }, corners.maxOf { it.y } - corners.minOf { it.y })
            val origin = Point2(corners.map { it.x }.average(), corners.map { it.y }.average())
            val half = sideCm / 2
            val target = listOf(Point2(-half, half), Point2(half, half), Point2(half, -half), Point2(-half, -half))
            val rows = Array(8) { DoubleArray(9) }

            corners.forEachIndexed { i, p ->
                val x = (p.x - origin.x) / span; val y = (p.y - origin.y) / span
                val u = target[i].x; val v = target[i].y
                rows[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                rows[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }

            for (col in 0..7) {
                val pivot = (col..7).maxByOrNull { abs(rows[it][col]) } ?: col
                if (abs(rows[pivot][col]) < 1e-10) return null
                val swap = rows[col]; rows[col] = rows[pivot]; rows[pivot] = swap
                val divisor = rows[col][col]
                for (j in col..8) rows[col][j] /= divisor
                for (i in 0..7) if (i != col) {
                    val factor = rows[i][col]
                    for (j in col..8) rows[i][j] -= factor * rows[col][j]
                }
            }
            return PlanarMapping(DoubleArray(8) { rows[it][8] }, origin, span)
        }
    }
}