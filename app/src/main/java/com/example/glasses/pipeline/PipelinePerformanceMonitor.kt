package com.example.glasses.pipeline

import android.os.SystemClock
import android.util.Log
import com.example.glasses.depth.DepthFrame
import java.util.Locale
import kotlin.math.ceil

internal class PipelinePerformanceMonitor(
    private val enabled: Boolean,
    private val warmUpMs: Long = DEFAULT_WARM_UP_MS,
    private val measurementMs: Long = DEFAULT_MEASUREMENT_MS,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val logger: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val metrics = linkedMapOf(
        "camera" to MetricAccumulator(),
        "preprocess" to MetricAccumulator(),
        "inference" to MetricAccumulator(),
        "mle" to MetricAccumulator(),
        "render" to MetricAccumulator(),
        "grid" to MetricAccumulator(),
        "audio" to MetricAccumulator(),
        "ui" to MetricAccumulator(),
    )

    private var startedAtMs = -1L
    private var nextProgressMs = progressIntervalMs
    private var completed = false
    private var cameraFrameCount = 0L
    private var processedFrameCount = 0L
    private var droppedFrameCount = 0L
    private var fitFailureCount = 0L
    private var gridProcessedFrameCount = 0L
    private var gridDroppedFrameCount = 0L
    private var soundscapeRenderCount = 0L
    private var immediateAlertCount = 0L

    init {
        require(warmUpMs >= 0L)
        require(measurementMs > 0L)
        require(progressIntervalMs > 0L)
    }

    @Synchronized
    fun recordCamera(conversionMs: Double) {
        recordMetric("camera", conversionMs)
        if (isStable(clockMs())) cameraFrameCount++
    }

    @Synchronized
    fun recordDroppedFrame() {
        val now = prepareNow()
        if (isStable(now)) droppedFrameCount++
        reportIfNeeded(now)
    }

    @Synchronized
    fun recordProcessedFrame(frame: DepthFrame) {
        val now = prepareNow()
        if (isStable(now) && !completed) {
            metrics.getValue("preprocess").record(frame.preProcessMs)
            metrics.getValue("inference").record(frame.inferenceMs)
            metrics.getValue("mle").record(frame.groundFilterMs)
            metrics.getValue("render").record(frame.renderMs)
            processedFrameCount++
            if (!frame.groundFilter.fitSucceeded) fitFailureCount++
        }
        reportIfNeeded(now)
    }

    @Synchronized
    fun recordGrid(processingMs: Double, processedDelta: Long, droppedDelta: Long) {
        val now = prepareNow()
        if (isStable(now) && !completed) {
            if (processedDelta > 0L) metrics.getValue("grid").record(processingMs)
            gridProcessedFrameCount += processedDelta.coerceAtLeast(0L)
            gridDroppedFrameCount += droppedDelta.coerceAtLeast(0L)
        }
        reportIfNeeded(now)
    }

    @Synchronized
    fun recordSoundscape(renderMs: Double, countDelta: Long) {
        val now = prepareNow()
        if (isStable(now) && !completed) {
            if (countDelta > 0L) metrics.getValue("audio").record(renderMs)
            soundscapeRenderCount += countDelta.coerceAtLeast(0L)
        }
        reportIfNeeded(now)
    }

    @Synchronized
    fun recordImmediateAlerts(countDelta: Long) {
        val now = prepareNow()
        if (isStable(now) && !completed) {
            immediateAlertCount += countDelta.coerceAtLeast(0L)
        }
        reportIfNeeded(now)
    }

    @Synchronized
    fun recordUi(deliveryMs: Double) {
        recordMetric("ui", deliveryMs)
    }

    @Synchronized
    fun logCurrentSnapshot() {
        if (!enabled || startedAtMs < 0L) return
        logger(buildSummary("PERF_SNAPSHOT", clockMs()))
    }

    private fun recordMetric(name: String, value: Double) {
        val now = prepareNow()
        if (isStable(now) && !completed) metrics.getValue(name).record(value)
        reportIfNeeded(now)
    }

    private fun prepareNow(): Long {
        val now = clockMs()
        if (!enabled || completed) return now
        if (startedAtMs < 0L) startedAtMs = now
        return now
    }

    private fun isStable(now: Long): Boolean =
        enabled && startedAtMs >= 0L && now - startedAtMs >= warmUpMs &&
            now - startedAtMs < warmUpMs + measurementMs

    private fun reportIfNeeded(now: Long) {
        if (!enabled || startedAtMs < 0L || completed) return
        val elapsedMs = (now - startedAtMs).coerceAtLeast(0L)
        if (elapsedMs >= warmUpMs + measurementMs) {
            logger(buildSummary("PERF_COMPLETE", now))
            completed = true
            return
        }
        if (elapsedMs >= nextProgressMs) {
            logger(buildSummary("PERF_PROGRESS", now))
            while (nextProgressMs <= elapsedMs) nextProgressMs += progressIntervalMs
        }
    }

    private fun buildSummary(prefix: String, now: Long): String {
        val elapsedMs = (now - startedAtMs).coerceAtLeast(0L)
        val stableElapsedMs = (elapsedMs - warmUpMs).coerceIn(0L, measurementMs)
        val fps = if (stableElapsedMs == 0L) {
            0.0
        } else {
            processedFrameCount * 1_000.0 / stableElapsedMs.toDouble()
        }
        val counts = "counts={camera=$cameraFrameCount,processed=$processedFrameCount," +
            "dropped=$droppedFrameCount,fitFailed=$fitFailureCount," +
            "gridProcessed=$gridProcessedFrameCount,gridDropped=$gridDroppedFrameCount," +
            "soundscapes=$soundscapeRenderCount,alerts=$immediateAlertCount}"
        val metricText = metrics.entries.joinToString(separator = " ") { (name, metric) ->
            "$name=${metric.summary()}"
        }
        return String.format(
            Locale.US,
            "%s elapsedMs=%d stableMs=%d fps=%.3f %s %s",
            prefix,
            elapsedMs,
            stableElapsedMs,
            fps,
            counts,
            metricText,
        )
    }

    private class MetricAccumulator {
        private val values = DoubleArray(MAX_SAMPLES)
        private var size = 0
        private var nextIndex = 0

        fun record(value: Double) {
            if (!value.isFinite() || value < 0.0) return
            values[nextIndex] = value
            nextIndex = (nextIndex + 1) % values.size
            if (size < values.size) size++
        }

        fun summary(): String {
            if (size == 0) return "{n=0,avg=0.000,p50=0.000,p90=0.000,p95=0.000,max=0.000}"
            val snapshot = DoubleArray(size)
            if (size < values.size) {
                values.copyInto(snapshot, endIndex = size)
            } else {
                val tailSize = values.size - nextIndex
                values.copyInto(snapshot, endIndex = values.size, startIndex = nextIndex)
                values.copyInto(snapshot, destinationOffset = tailSize, endIndex = nextIndex)
            }
            snapshot.sort()
            val average = snapshot.sum() / snapshot.size.toDouble()
            return String.format(
                Locale.US,
                "{n=%d,avg=%.3f,p50=%.3f,p90=%.3f,p95=%.3f,max=%.3f}",
                snapshot.size,
                average,
                percentile(snapshot, 0.50),
                percentile(snapshot, 0.90),
                percentile(snapshot, 0.95),
                snapshot.last(),
            )
        }

        private fun percentile(sorted: DoubleArray, percentile: Double): Double {
            val index = (ceil(percentile * sorted.size).toInt() - 1)
                .coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }

    companion object {
        private const val TAG = "GlassesPerf"
        private const val MAX_SAMPLES = 4_096
        private const val DEFAULT_WARM_UP_MS = 120_000L
        private const val DEFAULT_MEASUREMENT_MS = 180_000L
        private const val DEFAULT_PROGRESS_INTERVAL_MS = 30_000L
    }
}
