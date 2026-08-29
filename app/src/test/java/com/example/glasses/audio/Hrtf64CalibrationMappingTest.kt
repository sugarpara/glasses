package com.example.glasses.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hrtf64CalibrationMappingTest {

    @Test
    fun representativeGridSamplesTheReal64By64Bounds() {
        assertArrayEquals(
            intArrayOf(0, 14, 29, 43, 54, 63),
            HRTF64_CALIBRATION_REPRESENTATIVE_ROWS
        )
        assertArrayEquals(
            intArrayOf(0, 8, 16, 24, 39, 47, 55, 63),
            HRTF64_CALIBRATION_REPRESENTATIVE_COLUMNS
        )
        assertTrue(HRTF64_CALIBRATION_REPRESENTATIVE_ROWS.all { it in 0..63 })
        assertTrue(HRTF64_CALIBRATION_REPRESENTATIVE_COLUMNS.all { it in 0..63 })
        assertTrue(HRTF64_CALIBRATION_REPRESENTATIVE_ROWS.toList().zipWithNext().all {
            (first, second) -> first < second
        })
        assertTrue(HRTF64_CALIBRATION_REPRESENTATIVE_COLUMNS.toList().zipWithNext().all {
            (first, second) -> first < second
        })
    }

    @Test
    fun angleConversionMatchesThe64By64MetadataFormula() {
        assertEquals(-60f, hrtf64CalibrationAzimuthDegrees(0), 0.0001f)
        assertEquals(60f, hrtf64CalibrationAzimuthDegrees(63), 0.0001f)
        assertEquals(60f, hrtf64CalibrationElevationDegrees(0), 0.0001f)
        assertEquals(-45f, hrtf64CalibrationElevationDegrees(63), 0.0001f)
    }
}
