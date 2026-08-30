package com.example.glasses.audio

import com.example.glasses.obstacle.OBSTACLE_EMERGENCY_DISTANCE_METERS
import com.example.glasses.obstacle.OBSTACLE_ENTER_DISTANCE_METERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Glasses64AudioTypesTest {
    @Test
    fun distanceGainIsStrongestAtEmergencyRangeAndWeakestAtEntryRange() {
        val emergency = glasses64DistanceGain(OBSTACLE_EMERGENCY_DISTANCE_METERS)
        val middle = glasses64DistanceGain(1.9f)
        val entry = glasses64DistanceGain(OBSTACLE_ENTER_DISTANCE_METERS)

        assertEquals(1.0f, emergency, 0.0f)
        assertTrue(emergency > middle)
        assertTrue(middle > entry)
        assertEquals(entry, glasses64DistanceGain(3.3f), 0.0f)
    }
}
