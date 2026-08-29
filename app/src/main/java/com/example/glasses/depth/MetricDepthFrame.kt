package com.example.glasses.depth

/**
 * A row-major metric depth image whose orientation has already been corrected.
 *
 * Non-finite and non-positive values are allowed and represent invalid depth. This value object
 * does not own a camera image or Bitmap and therefore has no image resource to recycle.
 */
data class MetricDepthFrame(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
) {
    init {
        require(values.isNotEmpty()) { "Metric depth values must not be empty" }
        require(width > 0 && height > 0) { "Metric depth width and height must be positive" }
        require(width.toLong() * height.toLong() == values.size.toLong()) {
            "Metric depth dimensions ${width}x$height do not match ${values.size} values"
        }
        require(timestampMs >= 0L) { "Metric depth timestamp must be non-negative" }
    }
}
