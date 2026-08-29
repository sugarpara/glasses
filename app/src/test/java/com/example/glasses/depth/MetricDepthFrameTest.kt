package com.example.glasses.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetricDepthFrameTest {
    @Test
    fun acceptsMetricDepthValuesAndMetadata() {
        val values = floatArrayOf(1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)

        val frame = MetricDepthFrame(
            values = values,
            width = 2,
            height = 2,
            timestampMs = 123L,
        )

        assertEquals(values, frame.values)
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(123L, frame.timestampMs)
    }

    @Test
    fun rejectsEmptyValues() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricDepthFrame(FloatArray(0), width = 0, height = 0, timestampMs = 0L)
        }
    }

    @Test
    fun rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricDepthFrame(floatArrayOf(1f), width = 0, height = 1, timestampMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MetricDepthFrame(floatArrayOf(1f), width = 1, height = -1, timestampMs = 0L)
        }
    }

    @Test
    fun rejectsMismatchedValueCount() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricDepthFrame(FloatArray(3), width = 2, height = 2, timestampMs = 0L)
        }
    }

    @Test
    fun rejectsNegativeTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricDepthFrame(floatArrayOf(1f), width = 1, height = 1, timestampMs = -1L)
        }
    }
}
