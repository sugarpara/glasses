package com.example.glasses.depth

import android.graphics.Bitmap
import com.example.glasses.ground.GroundFilterFrame

/**
 * One inference result. [metricDepth] is the authoritative model output; [bitmap] and
 * [classificationBitmap] are optional visualizations owned by the caller and are never used as
 * input to downstream depth processing.
 * Percentiles are approximated from at most 4096 evenly spaced valid depth samples.
 */
data class DepthFrame(
    val metricDepth: MetricDepthFrame,
    val groundFilter: GroundFilterFrame,
    val bitmap: Bitmap?,
    val accelerator: String,
    val minDepth: Float,
    val maxDepth: Float,
    val finitePositiveFraction: Double,
    val p10Depth: Float,
    val p50Depth: Float,
    val p90Depth: Float,
    val preProcessMs: Double,
    val inferenceMs: Double,
    val groundFilterMs: Double,
    val renderMs: Double,
    val classificationBitmap: Bitmap? = null,
) {
    val postProcessMs: Double
        get() = groundFilterMs + renderMs

    val totalMs: Double
        get() = preProcessMs + inferenceMs + groundFilterMs + renderMs
}
