package com.example.glasses.depth

import android.graphics.Bitmap

data class DepthFrame(
    val bitmap: Bitmap,
    val accelerator: String,
    val minDepth: Float,
    val maxDepth: Float,
    val preProcessMs: Double,
    val inferenceMs: Double,
    val postProcessMs: Double,
) {
    val totalMs: Double
        get() = preProcessMs + inferenceMs + postProcessMs
}
