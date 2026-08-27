package com.example.glasses.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DepthTensorShapeTest {
    @Test
    fun parsesNchwShape() {
        val shape = DepthTensorShape.parse(intArrayOf(1, 1, 160, 160), 25_600)
        assertEquals(160, shape.width)
        assertEquals(160, shape.height)
    }

    @Test
    fun parsesNhwcShape() {
        val shape = DepthTensorShape.parse(intArrayOf(1, 160, 160, 1), 25_600)
        assertEquals(160, shape.width)
        assertEquals(160, shape.height)
    }

    @Test
    fun parsesThreeDimensionalShape() {
        val shape = DepthTensorShape.parse(intArrayOf(1, 160, 160), 25_600)
        assertEquals(160, shape.width)
        assertEquals(160, shape.height)
    }

    @Test
    fun rejectsMismatchedElementCount() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthTensorShape.parse(intArrayOf(1, 1, 160, 160), 100)
        }
    }

    @Test
    fun rejectsMultiChannelOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthTensorShape.parse(intArrayOf(1, 3, 160, 160), 76_800)
        }
    }
}
