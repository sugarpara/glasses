package com.example.glasses.depth

import kotlin.math.roundToInt

data class DepthRange(
    val min: Float,
    val max: Float,
)

object DepthColorizer {
    private val stops = arrayOf(
        intArrayOf(48, 18, 59),
        intArrayOf(50, 100, 200),
        intArrayOf(40, 190, 140),
        intArrayOf(245, 210, 60),
        intArrayOf(180, 20, 40),
    )

    private val palette = IntArray(256) { index ->
        val position = index.toFloat() / 255f * (stops.size - 1)
        val lower = position.toInt().coerceAtMost(stops.size - 2)
        val fraction = position - lower
        val first = stops[lower]
        val second = stops[lower + 1]
        val red = (first[0] + (second[0] - first[0]) * fraction).roundToInt()
        val green = (first[1] + (second[1] - first[1]) * fraction).roundToInt()
        val blue = (first[2] + (second[2] - first[2]) * fraction).roundToInt()
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun colorize(values: FloatArray, output: IntArray): DepthRange {
        require(values.isNotEmpty()) { "Depth values are empty" }
        require(output.size == values.size) {
            "Output pixel count must match depth value count"
        }

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (value in values) {
            if (!value.isFinite()) continue
            if (value < min) min = value
            if (value > max) max = value
        }
        require(min.isFinite() && max.isFinite()) {
            "Depth output contains no finite values"
        }

        val span = max - min
        for (index in values.indices) {
            val value = values[index]
            val paletteIndex = if (!value.isFinite() || span <= 0f) {
                0
            } else {
                (((value - min) / span) * 255f).roundToInt().coerceIn(0, 255)
            }
            output[index] = palette[paletteIndex]
        }
        return DepthRange(min = min, max = max)
    }
}
