package com.example.glasses.ground

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundFilterGoldenParityTest {
    @Test
    fun realJniMatchesPythonGoldenFixtures() {
        FIXTURES.forEach { fixtureName ->
            val fixture = loadFixture(fixtureName)
            val occupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
            val distance = FloatArray(OBSTACLE_GRID_CELL_COUNT)
            val classMap = ByteArray(fixture.depth.size)
            val metrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)
            val actualFitSucceeded = NativeGroundFilter(
                GroundFilterConfig(
                    fitRoiTop = fixture.fitRoiTop,
                    classificationRoiTop = fixture.classificationRoiTop,
                    obstacleEnterDepthMeters = fixture.obstacleMaxDepth,
                    obstacleExitDepthMeters = fixture.obstacleMaxDepth + 0.3f,
                    fitMaxDepthMeters = fixture.fitMaxDepth,
                    sampleStep = fixture.sampleStep,
                    maxIterations = fixture.maxIterations,
                ),
            ).use { filter ->
                val frame = MetricDepthFrame(
                    fixture.depth,
                    fixture.width,
                    fixture.height,
                    timestampMs = 1L,
                )
                filter.process(
                    frame,
                    occupancy,
                    distance,
                    classMap,
                    metrics,
                )
                filter.process(
                    frame,
                    occupancy,
                    distance,
                    classMap,
                    metrics,
                )
            }

            assertEquals(
                "$fixtureName fit status",
                fixture.fitSucceeded,
                actualFitSucceeded,
            )
            assertMaskIou(fixtureName, "ground", GROUND_CLASS_GROUND, fixture.classMap, classMap)
            assertMaskIou(
                fixtureName,
                "obstacle",
                GROUND_CLASS_OBSTACLE,
                fixture.classMap,
                classMap,
            )
            assertMaskIou(fixtureName, "unknown", GROUND_CLASS_UNKNOWN, fixture.classMap, classMap)
            val maxOccupancyError = fixture.occupancy.indices.maxOf { index ->
                abs(fixture.occupancy[index] - occupancy[index])
            }
            assertTrue(
                "$fixtureName occupancy max error was $maxOccupancyError",
                maxOccupancyError <= OCCUPANCY_TOLERANCE,
            )
        }
    }

    private fun assertMaskIou(
        fixtureName: String,
        label: String,
        classification: Byte,
        expected: ByteArray,
        actual: ByteArray,
    ) {
        var intersection = 0
        var union = 0
        for (index in expected.indices) {
            val expectedMatch = expected[index] == classification
            val actualMatch = actual[index] == classification
            if (expectedMatch && actualMatch) ++intersection
            if (expectedMatch || actualMatch) ++union
        }
        val iou = if (union == 0) 1.0 else intersection.toDouble() / union
        assertTrue("$fixtureName $label IoU was $iou", iou >= MINIMUM_IOU)
    }

    private fun loadFixture(name: String): GoldenFixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("ground_filter_golden/$name.gff").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buffer.get(magic)
        assertTrue("$name magic", magic.contentEquals(MAGIC))
        assertEquals("$name version", 1, buffer.int)
        val width = buffer.int
        val height = buffer.int
        val fitRoiTop = buffer.float
        val classificationRoiTop = buffer.float
        val obstacleMaxDepth = buffer.float
        val fitMaxDepth = buffer.float
        val sampleStep = buffer.int
        val maxIterations = buffer.int
        val fitSucceeded = buffer.int != 0
        val pixelCount = buffer.int
        val gridCount = buffer.int
        assertEquals("$name pixel count", width.toLong() * height, pixelCount.toLong())
        assertEquals("$name grid count", OBSTACLE_GRID_CELL_COUNT, gridCount)
        val depth = FloatArray(pixelCount) { buffer.float }
        val classMap = ByteArray(pixelCount)
        buffer.get(classMap)
        val occupancy = FloatArray(gridCount) { buffer.float }
        assertFalse("$name trailing bytes", buffer.hasRemaining())
        return GoldenFixture(
            width,
            height,
            fitRoiTop,
            classificationRoiTop,
            obstacleMaxDepth,
            fitMaxDepth,
            sampleStep,
            maxIterations,
            fitSucceeded,
            depth,
            classMap,
            occupancy,
        )
    }

    private data class GoldenFixture(
        val width: Int,
        val height: Int,
        val fitRoiTop: Float,
        val classificationRoiTop: Float,
        val obstacleMaxDepth: Float,
        val fitMaxDepth: Float,
        val sampleStep: Int,
        val maxIterations: Int,
        val fitSucceeded: Boolean,
        val depth: FloatArray,
        val classMap: ByteArray,
        val occupancy: FloatArray,
    )

    companion object {
        private const val MINIMUM_IOU = 0.98
        private const val OCCUPANCY_TOLERANCE = 1e-6f

        private val MAGIC = byteArrayOf(
            'G'.code.toByte(),
            'F'.code.toByte(),
            'F'.code.toByte(),
            '1'.code.toByte(),
        )

        private val FIXTURES = listOf(
            "clean_ground_120x160",
            "near_obstacle_120x160",
            "top_obstacle_120x160",
            "invalid_depth_120x160",
            "competing_planes_120x160",
            "fit_failure_80x100",
            "narrow_obstacle_640x640",
        )
    }
}
