package com.example.glasses.depth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.example.glasses.ground.GroundFilterConfig
import com.example.glasses.ground.GroundFilterFrame
import com.example.glasses.ground.GroundClassificationRenderer
import com.example.glasses.ground.NATIVE_GROUND_FILTER_GROUND_FRACTION_INDEX
import com.example.glasses.ground.NATIVE_GROUND_FILTER_METRIC_COUNT
import com.example.glasses.ground.NATIVE_GROUND_FILTER_OBSTACLE_FRACTION_INDEX
import com.example.glasses.ground.NATIVE_GROUND_FILTER_PROCESSING_MS_INDEX
import com.example.glasses.ground.NATIVE_GROUND_FILTER_UNKNOWN_FRACTION_INDEX
import com.example.glasses.ground.NativeGroundFilter
import com.example.glasses.inference.LiteRtDepthModel
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT
import java.io.Closeable
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

class DepthEstimator(
    private val model: LiteRtDepthModel,
    groundFilterConfig: GroundFilterConfig = GroundFilterConfig(),
) : Closeable {
    private val inputPixels = IntArray(model.inputWidth * model.inputHeight)
    private val inputFloats = FloatArray(model.inputWidth * model.inputHeight * 3)
    private val previewWidth = minOf(model.outputShape.width, MAX_PREVIEW_DIMENSION)
    private val previewHeight = minOf(model.outputShape.height, MAX_PREVIEW_DIMENSION)
    private var outputPixels: IntArray? = null
    private val percentileSamples = FloatArray(
        minOf(MAX_PERCENTILE_SAMPLES, model.outputShape.width * model.outputShape.height),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val obstacleOccupancy = FloatArray(OBSTACLE_GRID_CELL_COUNT)
    private val obstacleDistanceMeters = FloatArray(OBSTACLE_GRID_CELL_COUNT)
    private val nativeMetrics = DoubleArray(NATIVE_GROUND_FILTER_METRIC_COUNT)
    private var classificationMap: ByteArray? = null
    private var classificationPixels: IntArray? = null
    private val modelBitmap = Bitmap.createBitmap(
        model.inputWidth,
        model.inputHeight,
        Bitmap.Config.ARGB_8888,
    )
    private val groundFilter = try {
        NativeGroundFilter(groundFilterConfig)
    } catch (error: Throwable) {
        modelBitmap.recycle()
        model.close()
        throw error
    }
    private val closed = AtomicBoolean(false)
    private val processLock = Any()

    fun predict(
        source: Bitmap,
        renderDepthBitmap: Boolean = true,
        renderClassificationBitmap: Boolean = false,
    ): DepthFrame {
        check(!closed.get()) { "Depth estimator is closed" }
        return synchronized(processLock) {
            check(!closed.get()) { "Depth estimator is closed" }
            predictLocked(source, renderDepthBitmap, renderClassificationBitmap)
        }
    }

    private fun predictLocked(
        source: Bitmap,
        renderDepthBitmap: Boolean,
        renderClassificationBitmap: Boolean,
    ): DepthFrame {
        val start = System.nanoTime()
        Canvas(modelBitmap).drawBitmap(
            source,
            null,
            Rect(0, 0, model.inputWidth, model.inputHeight),
            paint,
        )
        modelBitmap.getPixels(
            inputPixels,
            0,
            model.inputWidth,
            0,
            0,
            model.inputWidth,
            model.inputHeight,
        )
        writeNormalizedRgb(inputPixels, inputFloats, model.inputUsesNchw)
        val afterPre = System.nanoTime()

        val output = model.run(inputFloats)
        val afterInference = System.nanoTime()

        val metricDepth = MetricDepthFrame(
            values = output,
            width = model.outputShape.width,
            height = model.outputShape.height,
            timestampMs = SystemClock.elapsedRealtime(),
        )
        val requestedClassMap = if (renderClassificationBitmap) {
            classificationMap
                ?.takeIf { it.size == output.size }
                ?: ByteArray(output.size).also { classificationMap = it }
        } else {
            null
        }
        val fitSucceeded = groundFilter.process(
            frame = metricDepth,
            obstacleOccupancy = obstacleOccupancy,
            obstacleDistanceMeters = obstacleDistanceMeters,
            classMap = requestedClassMap,
            metrics = nativeMetrics,
        )
        val groundFilterFrame = GroundFilterFrame(
            classMap = null,
            obstacleOccupancy = obstacleOccupancy.copyOf(),
            obstacleDistanceMeters = obstacleDistanceMeters.copyOf(),
            width = metricDepth.width,
            height = metricDepth.height,
            timestampMs = metricDepth.timestampMs,
            fitSucceeded = fitSucceeded,
            groundFraction = nativeMetrics[NATIVE_GROUND_FILTER_GROUND_FRACTION_INDEX].toFloat(),
            obstacleFraction = nativeMetrics[NATIVE_GROUND_FILTER_OBSTACLE_FRACTION_INDEX].toFloat(),
            unknownFraction = nativeMetrics[NATIVE_GROUND_FILTER_UNKNOWN_FRACTION_INDEX].toFloat(),
            processingMs = nativeMetrics[NATIVE_GROUND_FILTER_PROCESSING_MS_INDEX],
        )
        val afterGroundFilter = System.nanoTime()

        var depthBitmap: Bitmap? = null
        try {
            val statistics = summarizeMetricDepth(output)
            depthBitmap = when {
                renderClassificationBitmap -> createClassificationBitmap(
                    classMap = checkNotNull(requestedClassMap),
                    width = metricDepth.width,
                    height = metricDepth.height,
                )
                renderDepthBitmap -> createDepthBitmap(
                    values = output,
                    range = DepthRange(statistics.min, statistics.max),
                )
                else -> null
            }
            val afterRender = System.nanoTime()

            val frame = DepthFrame(
                metricDepth = metricDepth,
                groundFilter = groundFilterFrame,
                bitmap = depthBitmap,
                accelerator = model.accelerator,
                minDepth = statistics.min,
                maxDepth = statistics.max,
                finitePositiveFraction = statistics.finitePositiveFraction,
                p10Depth = statistics.p10,
                p50Depth = statistics.p50,
                p90Depth = statistics.p90,
                preProcessMs = nanosToMs(afterPre - start),
                inferenceMs = nanosToMs(afterInference - afterPre),
                groundFilterMs = nanosToMs(afterGroundFilter - afterInference),
                renderMs = nanosToMs(afterRender - afterGroundFilter),
            )
            depthBitmap = null
            return frame
        } finally {
            depthBitmap?.recycle()
        }
    }

    private fun createDepthBitmap(values: FloatArray, range: DepthRange): Bitmap {
        val pixelCount = previewWidth * previewHeight
        val pixels = outputPixels ?: IntArray(pixelCount).also { outputPixels = it }
        DepthColorizer.colorizeResampled(
            values = values,
            sourceWidth = model.outputShape.width,
            sourceHeight = model.outputShape.height,
            output = pixels,
            outputWidth = previewWidth,
            outputHeight = previewHeight,
            range = range,
        )
        return Bitmap.createBitmap(
            pixels,
            previewWidth,
            previewHeight,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun createClassificationBitmap(
        classMap: ByteArray,
        width: Int,
        height: Int,
    ): Bitmap {
        val pixels = classificationPixels
            ?.takeIf { it.size == classMap.size }
            ?: IntArray(classMap.size).also { classificationPixels = it }
        GroundClassificationRenderer.renderArgb(
            classMap = classMap,
            width = width,
            height = height,
            destination = pixels,
        )
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun summarizeMetricDepth(values: FloatArray): MetricDepthStatistics {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var finitePositiveCount = 0
        var sampleCount = 0
        var visitedSampleCount = 0
        val sampleStride = ((values.size + percentileSamples.size - 1) / percentileSamples.size)
            .coerceAtLeast(1)

        for (index in values.indices step sampleStride) {
            val value = values[index]
            val valid = value.isFinite() && value > 0f
            visitedSampleCount++
            if (valid) {
                finitePositiveCount++
                if (value < min) min = value
                if (value > max) max = value
                percentileSamples[sampleCount++] = value
            }
        }

        if (finitePositiveCount == 0) {
            return MetricDepthStatistics(
                min = Float.NaN,
                max = Float.NaN,
                finitePositiveFraction = 0.0,
                p10 = Float.NaN,
                p50 = Float.NaN,
                p90 = Float.NaN,
            )
        }

        if (sampleCount == 0) {
            percentileSamples[0] = min
            sampleCount = 1
        }
        Arrays.sort(percentileSamples, 0, sampleCount)
        return MetricDepthStatistics(
            min = min,
            max = max,
            finitePositiveFraction = finitePositiveCount.toDouble() / visitedSampleCount,
            p10 = percentile(percentileSamples, sampleCount, 0.10),
            p50 = percentile(percentileSamples, sampleCount, 0.50),
            p90 = percentile(percentileSamples, sampleCount, 0.90),
        )
    }

    private fun percentile(values: FloatArray, size: Int, quantile: Double): Float {
        val index = ((size - 1) * quantile).toInt().coerceIn(0, size - 1)
        return values[index]
    }

    private fun writeNormalizedRgb(
        pixels: IntArray,
        output: FloatArray,
        nchw: Boolean,
    ) {
        val planeSize = pixels.size
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 0xFF) / 255f
            val green = ((pixel shr 8) and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            if (nchw) {
                output[index] = red
                output[planeSize + index] = green
                output[2 * planeSize + index] = blue
            } else {
                val offset = index * 3
                output[offset] = red
                output[offset + 1] = green
                output[offset + 2] = blue
            }
        }
    }

    private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(processLock) {
            try {
                groundFilter.close()
            } finally {
                try {
                    modelBitmap.recycle()
                } finally {
                    model.close()
                }
            }
        }
    }

    private data class MetricDepthStatistics(
        val min: Float,
        val max: Float,
        val finitePositiveFraction: Double,
        val p10: Float,
        val p50: Float,
        val p90: Float,
    )

    companion object {
        private const val MAX_PERCENTILE_SAMPLES = 4_096
        private const val MAX_PREVIEW_DIMENSION = 256
    }
}
