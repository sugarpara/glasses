package com.example.glasses.depth

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.glasses.inference.LiteRtDepthModel
import com.example.glasses.inference.ModelFileProvider
import com.example.glasses.ground.GROUND_CLASSIFICATION_GROUND_ARGB
import com.example.glasses.ground.GROUND_CLASSIFICATION_INVALID_ARGB
import com.example.glasses.ground.GROUND_CLASSIFICATION_OBSTACLE_ARGB
import com.example.glasses.ground.GROUND_CLASSIFICATION_UNKNOWN_ARGB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepthEstimatorTest {
    @Test
    fun convertsBitmapIntoDepthFrame() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = ModelFileProvider.prepare(context, "yolo26n-depth_w8a32.tflite")
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val value = x * 255 / (source.width - 1)
                source.setPixel(x, y, Color.rgb(value, value, value))
            }
        }

        DepthEstimator(LiteRtDepthModel(context, file, preferGpu = true)).use { estimator ->
            val frame = estimator.predict(source)
            assertNotNull(frame.bitmap)
            val bitmap = requireNotNull(frame.bitmap)

            assertEquals(frame.metricDepth.width, bitmap.width)
            assertEquals(frame.metricDepth.height, bitmap.height)
            assertTrue(frame.minDepth.isFinite())
            assertTrue(frame.maxDepth.isFinite())
            assertTrue(frame.accelerator == "GPU" || frame.accelerator == "CPU")
            assertTrue(frame.preProcessMs >= 0.0)
            assertTrue(frame.inferenceMs >= 0.0)
            assertTrue(frame.groundFilterMs >= 0.0)
            assertTrue(frame.renderMs >= 0.0)
            assertTrue(frame.postProcessMs >= 0.0)
            assertEquals(
                frame.preProcessMs + frame.inferenceMs + frame.groundFilterMs + frame.renderMs,
                frame.totalMs,
                0.0,
            )
            assertEquals(frame.groundFilterMs + frame.renderMs, frame.postProcessMs, 0.0)
            assertValidGroundFilterFrame(frame)
            assertEquals(bitmap.width * bitmap.height, bitmap.byteCount / 4)
            bitmap.recycle()
        }
        source.recycle()
    }

    @Test
    fun exposesMetricDepthWithoutRenderingBitmap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = ModelFileProvider.prepare(context, "yolo26n-depth_w8a32.tflite")
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }

        DepthEstimator(LiteRtDepthModel(context, file, preferGpu = true)).use { estimator ->
            val frame = estimator.predict(source, renderDepthBitmap = false)
            val metricDepth = frame.metricDepth
            val finitePositiveCount = metricDepth.values.count { it.isFinite() && it > 0f }

            assertNull(frame.bitmap)
            assertEquals(640, metricDepth.width)
            assertEquals(640, metricDepth.height)
            assertEquals(640 * 640, metricDepth.values.size)
            assertTrue(finitePositiveCount >= metricDepth.values.size * 0.999)
            assertEquals(
                finitePositiveCount.toDouble() / metricDepth.values.size,
                frame.finitePositiveFraction,
                1e-9,
            )
            assertTrue(frame.minDepth > 0f && frame.minDepth.isFinite())
            assertTrue(frame.maxDepth >= frame.minDepth && frame.maxDepth.isFinite())
            assertTrue(frame.p10Depth in frame.minDepth..frame.maxDepth)
            assertTrue(frame.p50Depth in frame.p10Depth..frame.p90Depth)
            assertTrue(frame.p90Depth in frame.p50Depth..frame.maxDepth)
            assertTrue(metricDepth.timestampMs >= 0L)
            assertValidGroundFilterFrame(frame)
            Log.i(
                TAG,
                "metricDepth=${metricDepth.width}x${metricDepth.height} " +
                    "positive=${frame.finitePositiveFraction} " +
                    "range=${frame.minDepth}..${frame.maxDepth} " +
                    "p10=${frame.p10Depth} p50=${frame.p50Depth} p90=${frame.p90Depth}",
            )
        }
        source.recycle()
    }

    @Test
    fun rendersClassificationBitmapOnlyWhenRequested() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = ModelFileProvider.prepare(context, "yolo26n-depth_w8a32.tflite")
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(112, 112, 112))
        }

        DepthEstimator(LiteRtDepthModel(context, file, preferGpu = true)).use { estimator ->
            val classified = estimator.predict(
                source = source,
                renderDepthBitmap = false,
                renderClassificationBitmap = true,
            )
            val bitmap = requireNotNull(classified.bitmap)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val allowedColors = setOf(
                GROUND_CLASSIFICATION_INVALID_ARGB,
                GROUND_CLASSIFICATION_GROUND_ARGB,
                GROUND_CLASSIFICATION_OBSTACLE_ARGB,
                GROUND_CLASSIFICATION_UNKNOWN_ARGB,
            )

            assertTrue(pixels.all(allowedColors::contains))
            assertNull(classified.groundFilter.classMap)
            bitmap.recycle()

            val hidden = estimator.predict(
                source = source,
                renderDepthBitmap = false,
                renderClassificationBitmap = false,
            )
            assertNull(hidden.bitmap)
            assertNull(hidden.groundFilter.classMap)
        }
        source.recycle()
    }

    @Test
    fun publishesIndependentOccupancyAndRejectsPredictionAfterClose() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = ModelFileProvider.prepare(context, "yolo26n-depth_w8a32.tflite")
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(96, 96, 96))
        }
        val estimator = DepthEstimator(LiteRtDepthModel(context, file, preferGpu = true))

        val first = estimator.predict(source, renderDepthBitmap = false)
        val second = estimator.predict(source, renderDepthBitmap = false)

        assertNotSame(first.groundFilter.obstacleOccupancy, second.groundFilter.obstacleOccupancy)
        estimator.close()
        estimator.close()
        assertThrows(IllegalStateException::class.java) {
            estimator.predict(source, renderDepthBitmap = false)
        }
        source.recycle()
    }

    private fun assertValidGroundFilterFrame(frame: DepthFrame) {
        val groundFilter = frame.groundFilter
        assertNull(groundFilter.classMap)
        assertEquals(frame.metricDepth.width, groundFilter.width)
        assertEquals(frame.metricDepth.height, groundFilter.height)
        assertEquals(frame.metricDepth.timestampMs, groundFilter.timestampMs)
        assertEquals(64 * 64, groundFilter.obstacleOccupancy.size)
        assertTrue(groundFilter.obstacleOccupancy.all { it.isFinite() && it in 0.0f..1.0f })
        assertTrue(groundFilter.groundFraction.isFinite())
        assertTrue(groundFilter.groundFraction in 0.0f..1.0f)
        assertTrue(groundFilter.obstacleFraction.isFinite())
        assertTrue(groundFilter.obstacleFraction in 0.0f..1.0f)
        assertTrue(groundFilter.unknownFraction.isFinite())
        assertTrue(groundFilter.unknownFraction in 0.0f..1.0f)
        assertTrue(groundFilter.processingMs >= 0.0)
        assertFalse(groundFilter.obstacleOccupancy.any { !it.isFinite() })
    }

    companion object {
        private const val TAG = "DepthEstimatorTest"
    }
}
