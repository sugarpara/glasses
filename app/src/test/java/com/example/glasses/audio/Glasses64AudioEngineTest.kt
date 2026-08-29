package com.example.glasses.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Glasses64AudioEngineTest {

    @Test
    fun logarithmicPitchUsesComfortRangeEndpoints() {
        assertEquals(HRTF64_LOG_PITCH_TOP_HZ, glasses64LogPitchHz(0), 1.0e-9)
        assertEquals(
            HRTF64_LOG_PITCH_BOTTOM_HZ,
            glasses64LogPitchHz(GLASSES64_ROWS - 1),
            1.0e-9
        )
    }

    @Test
    fun logarithmicPitchFallsContinuouslyFromTopToBottom() {
        var previous = glasses64LogPitchHz(0)
        for (row in 1 until GLASSES64_ROWS) {
            val current = glasses64LogPitchHz(row)
            assertTrue(current < previous)
            previous = current
        }
    }

    @Test
    fun unknownSavedModeFallsBackToLegacy() {
        assertEquals(
            Glasses64VerticalSoundMode.LEGACY_SIX_BAND,
            Glasses64VerticalSoundMode.fromPreference("UNKNOWN")
        )
    }

    @Test
    fun strictCellModeCanBeRestoredFromPreferences() {
        assertEquals(
            Glasses64VerticalSoundMode.LOG_EACH_CELL,
            Glasses64VerticalSoundMode.fromPreference("LOG_EACH_CELL")
        )
    }

    @Test
    fun enhancedRegionModeCanBeRestoredFromPreferences() {
        assertEquals(
            Glasses64VerticalSoundMode.REGION_ENHANCED,
            Glasses64VerticalSoundMode.fromPreference("REGION_ENHANCED")
        )
    }

    @Test
    fun enhancedPitchUsesSeparatedComfortBands() {
        assertEquals(HRTF64_LOG_PITCH_TOP_HZ, glasses64EnhancedPitchHz(0), 1.0e-9)
        assertEquals(2_600.0, glasses64EnhancedPitchHz(HRTF64_ENHANCED_TOP_END_ROW), 1.0e-9)
        assertEquals(
            HRTF64_ENHANCED_MIDDLE_TOP_HZ,
            glasses64EnhancedPitchHz(HRTF64_ENHANCED_TOP_END_ROW + 1),
            1.0e-9
        )
        assertEquals(
            HRTF64_ENHANCED_MIDDLE_BOTTOM_HZ,
            glasses64EnhancedPitchHz(HRTF64_ENHANCED_MIDDLE_END_ROW),
            1.0e-9
        )
        assertEquals(
            HRTF64_ENHANCED_LOWER_TOP_HZ,
            glasses64EnhancedPitchHz(HRTF64_ENHANCED_MIDDLE_END_ROW + 1),
            1.0e-9
        )
        assertEquals(
            HRTF64_ENHANCED_LOWER_BOTTOM_HZ,
            glasses64EnhancedPitchHz(GLASSES64_ROWS - 1),
            1.0e-9
        )
    }

    @Test
    fun enhancedPitchFallsStrictlyFromTopToBottom() {
        var previous = glasses64EnhancedPitchHz(0)
        for (row in 1 until GLASSES64_ROWS) {
            val current = glasses64EnhancedPitchHz(row)
            assertTrue("row $row should be below the previous row", current < previous)
            previous = current
        }
    }

    @Test
    fun enhancedCarrierEmphasizesLowerLocalization() {
        assertEquals(0.25f, glasses64EnhancedCarrierRatio(0), 0f)
        assertEquals(0.30f, glasses64EnhancedCarrierRatio(HRTF64_ENHANCED_TOP_END_ROW + 1), 0f)
        assertEquals(0.40f, glasses64EnhancedCarrierRatio(GLASSES64_ROWS - 1), 0f)
    }

    @Test
    fun categoricalRegionModeCanBeRestoredFromPreferences() {
        assertEquals(
            Glasses64VerticalSoundMode.REGION_CATEGORICAL,
            Glasses64VerticalSoundMode.fromPreference("REGION_CATEGORICAL")
        )
    }

    @Test
    fun categoricalPitchUsesComfortableSeparatedBands() {
        assertEquals(HRTF64_CATEGORICAL_TOP_HZ, glasses64CategoricalPitchHz(0), 1.0e-9)
        assertEquals(
            HRTF64_CATEGORICAL_UPPER_BOTTOM_HZ,
            glasses64CategoricalPitchHz(HRTF64_ENHANCED_TOP_END_ROW),
            1.0e-9
        )
        assertEquals(
            HRTF64_CATEGORICAL_MIDDLE_TOP_HZ,
            glasses64CategoricalPitchHz(HRTF64_ENHANCED_TOP_END_ROW + 1),
            1.0e-9
        )
        assertEquals(
            HRTF64_CATEGORICAL_MIDDLE_BOTTOM_HZ,
            glasses64CategoricalPitchHz(HRTF64_ENHANCED_MIDDLE_END_ROW),
            1.0e-9
        )
        assertEquals(
            HRTF64_CATEGORICAL_LOWER_TOP_HZ,
            glasses64CategoricalPitchHz(HRTF64_ENHANCED_MIDDLE_END_ROW + 1),
            1.0e-9
        )
        assertEquals(
            HRTF64_CATEGORICAL_BOTTOM_HZ,
            glasses64CategoricalPitchHz(GLASSES64_ROWS - 1),
            1.0e-9
        )
        assertTrue(HRTF64_CATEGORICAL_TOP_HZ <= 5_000.0)
        assertTrue(HRTF64_CATEGORICAL_BOTTOM_HZ >= 300.0)
        assertTrue(
            HRTF64_CATEGORICAL_UPPER_BOTTOM_HZ /
                HRTF64_CATEGORICAL_MIDDLE_TOP_HZ >= 1.7
        )
        assertTrue(
            HRTF64_CATEGORICAL_MIDDLE_BOTTOM_HZ /
                HRTF64_CATEGORICAL_LOWER_TOP_HZ >= 1.65
        )
    }

    @Test
    fun categoricalUpperAndLowerPitchRemainProminent() {
        assertEquals(0.20f, glasses64CategoricalCarrierRatio(0), 0f)
        assertEquals(
            0.24f,
            glasses64CategoricalCarrierRatio(HRTF64_ENHANCED_TOP_END_ROW + 1),
            0f
        )
        assertEquals(0.20f, glasses64CategoricalCarrierRatio(GLASSES64_ROWS - 1), 0f)
    }

    @Test
    fun categoricalPitchFallsStrictlyFromTopToBottom() {
        var previous = glasses64CategoricalPitchHz(0)
        for (row in 1 until GLASSES64_ROWS) {
            val current = glasses64CategoricalPitchHz(row)
            assertTrue("row $row should be below the previous row", current < previous)
            previous = current
        }
    }

    @Test
    fun cellAndEnhancedModesBoostUpperAudibility() {
        val upperRow = HRTF64_ENHANCED_TOP_END_ROW
        val middleRow = HRTF64_ENHANCED_TOP_END_ROW + 1
        assertTrue(
            glasses64ModeOutputGain(Glasses64VerticalSoundMode.LOG_EACH_CELL, upperRow) > 1f
        )
        assertTrue(
            glasses64ModeOutputGain(Glasses64VerticalSoundMode.REGION_ENHANCED, upperRow) > 1f
        )
        assertEquals(
            1f,
            glasses64ModeOutputGain(Glasses64VerticalSoundMode.LOG_EACH_CELL, middleRow),
            0f
        )
    }

    @Test
    fun personalVerticalMappingUsesSelectedPerceptualAnchors() {
        assertEquals(
            HRTF64_DEFAULT_MIDDLE_HRTF_ROW,
            glasses64MapVisualRowToPersonalHrtfRow(
                visualRow = HRTF64_VISUAL_MIDDLE_ANCHOR_ROW,
                middleHrtfRow = HRTF64_DEFAULT_MIDDLE_HRTF_ROW,
                lowerHrtfRow = HRTF64_DEFAULT_LOWER_HRTF_ROW
            )
        )
        assertEquals(
            HRTF64_DEFAULT_LOWER_HRTF_ROW,
            glasses64MapVisualRowToPersonalHrtfRow(
                visualRow = HRTF64_VISUAL_LOWER_ANCHOR_ROW,
                middleHrtfRow = HRTF64_DEFAULT_MIDDLE_HRTF_ROW,
                lowerHrtfRow = HRTF64_DEFAULT_LOWER_HRTF_ROW
            )
        )
    }

    @Test
    fun personalVerticalMappingIsBoundedAndMonotonic() {
        var previous = -1
        for (visualRow in 0 until GLASSES64_ROWS) {
            val mapped = glasses64MapVisualRowToPersonalHrtfRow(
                visualRow = visualRow,
                middleHrtfRow = 40,
                lowerHrtfRow = 59
            )
            assertTrue(mapped in 0 until GLASSES64_ROWS)
            assertTrue("row $visualRow must not move upward in the HRTF grid", mapped >= previous)
            previous = mapped
        }
    }

    @Test
    fun defaultPersonalMappingSeparatesMiddleAndLowerVisualBands() {
        val middle = glasses64MapVisualRowToPersonalHrtfRow(
            visualRow = HRTF64_VISUAL_MIDDLE_ANCHOR_ROW,
            middleHrtfRow = HRTF64_DEFAULT_MIDDLE_HRTF_ROW,
            lowerHrtfRow = HRTF64_DEFAULT_LOWER_HRTF_ROW
        )
        val lowerStart = glasses64MapVisualRowToPersonalHrtfRow(
            visualRow = HRTF64_ENHANCED_MIDDLE_END_ROW + 1,
            middleHrtfRow = HRTF64_DEFAULT_MIDDLE_HRTF_ROW,
            lowerHrtfRow = HRTF64_DEFAULT_LOWER_HRTF_ROW
        )
        assertTrue(lowerStart - middle >= 10)
    }
}
