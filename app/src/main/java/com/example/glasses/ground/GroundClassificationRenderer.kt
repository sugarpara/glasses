package com.example.glasses.ground

internal val GROUND_CLASSIFICATION_INVALID_ARGB: Int = 0xFF000000.toInt()
internal val GROUND_CLASSIFICATION_GROUND_ARGB: Int = 0xFF00C853.toInt()
internal val GROUND_CLASSIFICATION_OBSTACLE_ARGB: Int = 0xFFD50000.toInt()
internal val GROUND_CLASSIFICATION_UNKNOWN_ARGB: Int = 0xFFFFD600.toInt()

internal object GroundClassificationRenderer {
    fun renderArgb(
        classMap: ByteArray,
        width: Int,
        height: Int,
        destination: IntArray? = null,
    ): IntArray {
        require(width > 0 && height > 0)
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount == classMap.size.toLong()) {
            "Class map dimensions must match its value count"
        }
        val output = destination ?: IntArray(classMap.size)
        require(output.size == classMap.size) {
            "Classification pixel buffer must match the class map size"
        }

        for (index in classMap.indices) {
            output[index] = when (classMap[index]) {
                GROUND_CLASS_INVALID -> GROUND_CLASSIFICATION_INVALID_ARGB
                GROUND_CLASS_GROUND -> GROUND_CLASSIFICATION_GROUND_ARGB
                GROUND_CLASS_OBSTACLE -> GROUND_CLASSIFICATION_OBSTACLE_ARGB
                GROUND_CLASS_UNKNOWN -> GROUND_CLASSIFICATION_UNKNOWN_ARGB
                else -> throw IllegalArgumentException(
                    "Unsupported ground classification code ${classMap[index]}",
                )
            }
        }
        return output
    }
}

internal class ClassificationRenderThrottle(
    private val intervalMs: Long = 250L,
) {
    private var lastRenderTimestampMs = -1L

    init {
        require(intervalMs > 0L)
    }

    @Synchronized
    fun shouldRender(enabled: Boolean, nowMs: Long): Boolean {
        require(nowMs >= 0L)
        if (!enabled) {
            lastRenderTimestampMs = -1L
            return false
        }
        if (
            lastRenderTimestampMs < 0L ||
            nowMs - lastRenderTimestampMs >= intervalMs
        ) {
            lastRenderTimestampMs = nowMs
            return true
        }
        return false
    }
}
