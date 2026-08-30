package com.example.glasses.obstacle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ObstacleGridProcessorTest {
    @Test
    fun firstFrameUsesOccupancyDirectlyAndAppliesOnThreshold() {
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        occupancy[cellIndex(10, 12)] = 0.55f
        occupancy[cellIndex(10, 13)] = 0.54f

        val result = ObstacleGridTransform().process(
            ObstacleGridFrame(
                occupancy,
                distanceGrid(occupancy),
                timestampMs = 100L,
                fitSucceeded = true,
            ),
        )

        assertEquals(0.55f, result.smoothedOccupancy[cellIndex(10, 12)], 0.0f)
        assertTrue(result.activeMask[cellIndex(10, 12)])
        assertFalse(result.activeMask[cellIndex(10, 13)])
        assertEquals(100L, result.timestampMs)
        assertTrue(result.fitSucceeded)
    }

    @Test
    fun smoothingAndHysteresisMatchLegacyWeightsAndThresholds() {
        val transform = ObstacleGridTransform()
        val index = cellIndex(20, 30)

        fun process(value: Float): ProcessedObstacleGridFrame {
            val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
            occupancy[index] = value
            return transform.process(
                ObstacleGridFrame(
                    occupancy,
                    distanceGrid(occupancy),
                    timestampMs = 1L,
                    fitSucceeded = true,
                ),
            )
        }

        val first = process(1.0f)
        val second = process(0.0f)
        val third = process(0.0f)
        val fourth = process(0.0f)

        assertEquals(1.0f, first.smoothedOccupancy[index], 1.0e-6f)
        assertEquals(0.70f, second.smoothedOccupancy[index], 1.0e-6f)
        assertEquals(0.49f, third.smoothedOccupancy[index], 1.0e-6f)
        assertEquals(0.343f, fourth.smoothedOccupancy[index], 1.0e-6f)
        assertTrue(first.activeMask[index])
        assertTrue(second.activeMask[index])
        assertTrue(third.activeMask[index])
        assertFalse(fourth.activeMask[index])
    }

    @Test
    fun fitStatusChangeKeepsUnifiedAudioState() {
        val transform = ObstacleGridTransform()
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val index = cellIndex(20, 30)
        occupancy[index] = 1.0f
        val first = transform.process(
            ObstacleGridFrame(
                occupancy,
                distanceGrid(occupancy),
                timestampMs = 1L,
                fitSucceeded = true,
            ),
        )
        occupancy[index] = 0.4f
        val changedMode = transform.process(
            ObstacleGridFrame(
                occupancy,
                distanceGrid(occupancy),
                timestampMs = 2L,
                fitSucceeded = false,
            ),
        )

        assertTrue(first.activeMask[index])
        assertEquals(0.82f, changedMode.smoothedOccupancy[index], 1.0e-6f)
        assertTrue(changedMode.activeMask[index])
        assertFalse(changedMode.fitSucceeded)
    }

    @Test
    fun emergencyDistanceBypassesNormalOccupancyThresholdWithoutAddingAlertType() {
        val transform = ObstacleGridTransform()
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        val emergencyIndex = cellIndex(12, 8)
        val regularIndex = cellIndex(12, 9)
        occupancy[emergencyIndex] = 0.10f
        occupancy[regularIndex] = 0.10f
        distance[emergencyIndex] = 0.7f
        distance[regularIndex] = 1.0f

        val result = transform.process(
            ObstacleGridFrame(
                occupancy,
                distance,
                timestampMs = 1L,
                fitSucceeded = false,
            ),
        )

        assertTrue(result.activeMask[emergencyIndex])
        assertFalse(result.activeMask[regularIndex])
        assertEquals(0.7f, result.smoothedDistanceMeters[emergencyIndex], 0.0f)
    }

    @Test
    fun columnRequestsKeepStrongestThreeRegionsAndAllActiveCells() {
        val column = 7
        val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        setRegion(occupancy, column, 2..3, 0.6f)
        setRegion(occupancy, column, 10..11, 0.9f)
        setRegion(occupancy, column, 20..21, 0.7f)
        setRegion(occupancy, column, 30..31, 0.8f)

        val result = ObstacleGridTransform().process(
            ObstacleGridFrame(
                occupancy,
                distanceGrid(occupancy, distanceMeters = 1.2f),
                timestampMs = 10L,
                fitSucceeded = true,
            ),
        )
        val request = result.columnRequests[column]

        assertEquals(listOf(11, 21, 31), request.regions.map { it.representativeRow })
        assertEquals(listOf(0.9f, 0.7f, 0.8f), request.regions.map { it.strength })
        assertEquals(8, request.activeCells.size)
        assertEquals((2..3).toList() + (10..11) + (20..21) + (30..31), request.activeCells.map { it.row })
        assertTrue(request.activeCells.all { it.distanceMeters == 1.2f })
        assertTrue(request.regions.all { it.distanceMeters == 1.2f })
    }

    @Test
    fun coordinateEndpointsMatchSharedGridContract() {
        assertEquals(-60.0, ObstacleGridTransform.azimuthDegrees(0), 1.0e-9)
        assertEquals(60.0, ObstacleGridTransform.azimuthDegrees(63), 1.0e-9)
        assertEquals(60.0, ObstacleGridTransform.elevationDegrees(0), 1.0e-9)
        assertEquals(-45.0, ObstacleGridTransform.elevationDegrees(63), 1.0e-9)
    }

    @Test
    fun conflatedProcessorPublishesLatestFrameAndCountsOverwrittenInputs() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val blocker = CoroutineScope(dispatcher).launch {
            blockerStarted.countDown()
            releaseBlocker.await()
        }
        assertTrue(blockerStarted.await(2L, TimeUnit.SECONDS))

        val processor = ObstacleGridProcessor(dispatcher)
        try {
            repeat(200) { index ->
                val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
                occupancy[index % occupancy.size] = 1.0f
                assertTrue(
                    processor.submit(
                        ObstacleGridFrame(
                            occupancy = occupancy,
                            distanceMeters = distanceGrid(occupancy),
                            timestampMs = index.toLong(),
                            fitSucceeded = index % 2 == 0,
                        ),
                    ),
                )
            }
            releaseBlocker.countDown()

            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            while (
                processor.getLatestProcessedFrame()?.timestampMs != 199L &&
                System.nanoTime() < deadlineNanos
            ) {
                Thread.sleep(5L)
            }

            val latest = processor.getLatestProcessedFrame()
            val stats = processor.stats.value
            assertNotNull(latest)
            assertEquals(199L, latest?.timestampMs)
            assertFalse(requireNotNull(latest).fitSucceeded)
            assertTrue(stats.droppedFrameCount > 0L)
            assertEquals(200L, stats.submittedFrameCount)
            assertEquals(
                stats.submittedFrameCount,
                stats.processedFrameCount + stats.droppedFrameCount,
            )
        } finally {
            releaseBlocker.countDown()
            processor.close()
            blocker.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun setRegion(
        occupancy: FloatArray,
        column: Int,
        rows: IntRange,
        strength: Float,
    ) {
        for (row in rows) occupancy[cellIndex(row, column)] = strength
    }

    private fun distanceGrid(
        occupancy: FloatArray,
        distanceMeters: Float = 1.5f,
    ): FloatArray = FloatArray(OBSTACLE_GRID_CELL_COUNT) { index ->
        if (occupancy[index] > 0.0f) distanceMeters else 0.0f
    }

    private fun cellIndex(row: Int, column: Int): Int = row * OBSTACLE_GRID_COLUMNS + column
}
