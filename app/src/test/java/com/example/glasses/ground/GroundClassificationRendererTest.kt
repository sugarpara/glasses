package com.example.glasses.ground

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundClassificationRendererTest {
    @Test
    fun mapsEveryClassificationCodeToFixedArgbColor() {
        val pixels = GroundClassificationRenderer.renderArgb(
            classMap = byteArrayOf(
                GROUND_CLASS_INVALID,
                GROUND_CLASS_GROUND,
                GROUND_CLASS_OBSTACLE,
                GROUND_CLASS_UNKNOWN,
            ),
            width = 2,
            height = 2,
        )

        assertArrayEquals(
            intArrayOf(
                GROUND_CLASSIFICATION_INVALID_ARGB,
                GROUND_CLASSIFICATION_GROUND_ARGB,
                GROUND_CLASSIFICATION_OBSTACLE_ARGB,
                GROUND_CLASSIFICATION_UNKNOWN_ARGB,
            ),
            pixels,
        )
    }

    @Test
    fun reusesCallerOwnedPixelBuffer() {
        val destination = IntArray(4)

        val result = GroundClassificationRenderer.renderArgb(
            classMap = byteArrayOf(0, 1, 2, 3),
            width = 2,
            height = 2,
            destination = destination,
        )

        assertSame(destination, result)
    }

    @Test
    fun rejectsInvalidDimensionsBuffersAndClassificationCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            GroundClassificationRenderer.renderArgb(ByteArray(4), width = 0, height = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundClassificationRenderer.renderArgb(ByteArray(3), width = 2, height = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundClassificationRenderer.renderArgb(
                ByteArray(4),
                width = 2,
                height = 2,
                destination = IntArray(3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundClassificationRenderer.renderArgb(
                byteArrayOf(0, 1, 2, 4),
                width = 2,
                height = 2,
            )
        }
    }

    @Test
    fun classificationRefreshIsLimitedToFourFramesPerSecond() {
        val throttle = ClassificationRenderThrottle(intervalMs = 250L)

        assertTrue(throttle.shouldRender(enabled = true, nowMs = 1_000L))
        assertFalse(throttle.shouldRender(enabled = true, nowMs = 1_249L))
        assertTrue(throttle.shouldRender(enabled = true, nowMs = 1_250L))
        assertFalse(throttle.shouldRender(enabled = false, nowMs = 1_300L))
        assertTrue(throttle.shouldRender(enabled = true, nowMs = 1_301L))
    }
}
