package com.example.glasses.ui

import com.example.glasses.audio.GLASSES64_COLUMNS
import com.example.glasses.audio.GLASSES64_ROWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundscapeTestScreenTest {
    @Test
    fun fullTestSoundscapeContainsOneRealCellInEveryColumn() {
        val row = 27
        val requests = createSoundscapeTestRequests(row)

        assertEquals(GLASSES64_COLUMNS, requests.size)
        requests.forEachIndexed { column, request ->
            assertEquals(column, request.column)
            assertEquals(row, request.activeCells.single().row)
            assertEquals(row, request.regions.single().representativeRow)
            assertTrue(request.activeCells.single().strength > 0f)
            assertTrue(request.activeCells.single().distanceMeters > 0f)
        }
    }

    @Test
    fun testSoundscapeRowIsClampedToThe64By64Grid() {
        assertEquals(0, createSoundscapeTestRequests(-1).first().activeCells.single().row)
        assertEquals(
            GLASSES64_ROWS - 1,
            createSoundscapeTestRequests(GLASSES64_ROWS).first().activeCells.single().row,
        )
    }
}
