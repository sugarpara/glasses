package com.example.glasses.depth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DepthColorizerTest {
    @Test
    fun reportsFiniteRangeAndWritesOpaquePixels() {
        val output = IntArray(3)
        val range = DepthColorizer.colorize(floatArrayOf(1f, 2f, 3f), output)

        assertEquals(1f, range.min, 0f)
        assertEquals(3f, range.max, 0f)
        output.forEach { pixel ->
            assertEquals(0xFF000000.toInt(), pixel and 0xFF000000.toInt())
        }
        assertNotEquals(output[0], output[2])
    }

    @Test
    fun ignoresNonFiniteValuesWhenFindingRange() {
        val output = IntArray(4)
        val range = DepthColorizer.colorize(
            floatArrayOf(Float.NaN, 2f, Float.POSITIVE_INFINITY, 4f),
            output,
        )

        assertEquals(2f, range.min, 0f)
        assertEquals(4f, range.max, 0f)
    }

    @Test
    fun supportsAFlatDepthMap() {
        val output = IntArray(2)
        val range = DepthColorizer.colorize(floatArrayOf(5f, 5f), output)

        assertEquals(5f, range.min, 0f)
        assertEquals(5f, range.max, 0f)
        assertEquals(output[0], output[1])
    }

    @Test
    fun rejectsOutputWithWrongSize() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthColorizer.colorize(floatArrayOf(1f, 2f), IntArray(1))
        }
    }

    @Test
    fun doesNotModifyMetricDepthValues() {
        val values = floatArrayOf(1f, 2f, 3f, Float.NaN)
        val original = values.copyOf()

        DepthColorizer.colorize(values, IntArray(values.size))

        assertArrayEquals(original, values, 0f)
    }

    @Test
    fun resamplesPreviewWithoutChangingTheProvidedRange() {
        val values = FloatArray(16) { index -> index.toFloat() }
        val fullPixels = IntArray(values.size)
        val range = DepthColorizer.colorize(values, fullPixels)
        val previewPixels = IntArray(4)

        DepthColorizer.colorizeResampled(
            values = values,
            sourceWidth = 4,
            sourceHeight = 4,
            output = previewPixels,
            outputWidth = 2,
            outputHeight = 2,
            range = range,
        )

        assertArrayEquals(
            intArrayOf(fullPixels[0], fullPixels[2], fullPixels[8], fullPixels[10]),
            previewPixels,
        )
    }
}
