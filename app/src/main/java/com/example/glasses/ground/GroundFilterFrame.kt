package com.example.glasses.ground

import com.example.glasses.obstacle.requireValidObstacleOccupancy

const val GROUND_CLASS_INVALID: Byte = 0
const val GROUND_CLASS_GROUND: Byte = 1
const val GROUND_CLASS_OBSTACLE: Byte = 2
const val GROUND_CLASS_UNKNOWN: Byte = 3

/**
 * Ground-filter output passed entirely in memory.
 *
 * [classMap] is optional debug data. This value object does not own a Bitmap and does not recycle
 * display resources. Production obstacle and audio processing consume [obstacleOccupancy].
 */
data class GroundFilterFrame(
    val classMap: ByteArray?,
    val obstacleOccupancy: FloatArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val fitSucceeded: Boolean,
    val groundFraction: Float,
    val obstacleFraction: Float,
    val unknownFraction: Float,
    val processingMs: Double,
) {
    init {
        require(width > 0 && height > 0) { "Ground filter width and height must be positive" }
        require(timestampMs >= 0L) { "Ground filter timestamp must be non-negative" }
        requireValidObstacleOccupancy(obstacleOccupancy)
        requireValidFraction("groundFraction", groundFraction)
        requireValidFraction("obstacleFraction", obstacleFraction)
        requireValidFraction("unknownFraction", unknownFraction)
        require(processingMs.isFinite() && processingMs >= 0.0) {
            "processingMs must be finite and non-negative"
        }

        classMap?.let { classifications ->
            require(width.toLong() * height.toLong() == classifications.size.toLong()) {
                "Class map dimensions ${width}x$height do not match ${classifications.size} values"
            }
            require(classifications.all(::isValidClassification)) {
                "Class map contains an unsupported classification code"
            }
        }
    }

    private fun requireValidFraction(name: String, value: Float) {
        require(value.isFinite() && value in 0.0f..1.0f) {
            "$name must be finite and in [0.0, 1.0]"
        }
    }

    private fun isValidClassification(value: Byte): Boolean =
        value in GROUND_CLASS_INVALID..GROUND_CLASS_UNKNOWN
}
