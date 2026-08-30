package com.example.glasses.pipeline

import com.example.glasses.depth.DepthFrame
import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.ground.GroundFilterFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelinePerformanceMonitorTest {
    @Test
    fun ignoresWarmUpAndReportsStableWindowPercentilesAndCounts() {
        var nowMs = 0L
        val logs = mutableListOf<String>()
        val monitor = PipelinePerformanceMonitor(
            enabled = true,
            warmUpMs = 100L,
            measurementMs = 200L,
            progressIntervalMs = 1_000L,
            clockMs = { nowMs },
            logger = logs::add,
        )

        monitor.recordCamera(99.0)
        nowMs = 100L
        monitor.recordCamera(4.0)
        monitor.recordProcessedFrame(frame(mleMs = 10.0, fitSucceeded = true))
        monitor.recordGrid(processingMs = 1.0, processedDelta = 1L, droppedDelta = 2L)
        nowMs = 200L
        monitor.recordCamera(6.0)
        monitor.recordProcessedFrame(frame(mleMs = 30.0, fitSucceeded = false))
        monitor.recordSoundscape(renderMs = 8.0, countDelta = 1L)
        monitor.recordImmediateAlerts(countDelta = 1L)
        monitor.recordUi(2.0)
        nowMs = 300L
        monitor.recordUi(3.0)

        assertEquals(1, logs.size)
        val summary = logs.single()
        assertTrue(summary.startsWith("PERF_COMPLETE"))
        assertTrue(summary.contains("fps=10.000"))
        assertTrue(summary.contains("processed=2"))
        assertTrue(summary.contains("dropped=0"))
        assertTrue(summary.contains("fitFailed=1"))
        assertTrue(summary.contains("gridDropped=2"))
        assertTrue(summary.contains("soundscapes=1"))
        assertTrue(summary.contains("alerts=1"))
        assertTrue(summary.contains("camera={n=2,avg=5.000,p50=4.000,p90=6.000,p95=6.000,max=6.000}"))
        assertTrue(summary.contains("mle={n=2,avg=20.000,p50=10.000,p90=30.000,p95=30.000,max=30.000}"))
    }

    @Test
    fun disabledMonitorDoesNotLog() {
        val logs = mutableListOf<String>()
        val monitor = PipelinePerformanceMonitor(
            enabled = false,
            warmUpMs = 0L,
            measurementMs = 1L,
            progressIntervalMs = 1L,
            clockMs = { 10L },
            logger = logs::add,
        )

        monitor.recordCamera(1.0)
        monitor.recordProcessedFrame(frame(mleMs = 1.0, fitSucceeded = true))
        monitor.logCurrentSnapshot()

        assertTrue(logs.isEmpty())
    }

    private fun frame(mleMs: Double, fitSucceeded: Boolean): DepthFrame = DepthFrame(
        metricDepth = MetricDepthFrame(FloatArray(4) { 1.0f }, 2, 2, 1L),
        groundFilter = GroundFilterFrame(
            classMap = null,
            obstacleOccupancy = FloatArray(64 * 64),
            width = 2,
            height = 2,
            timestampMs = 1L,
            fitSucceeded = fitSucceeded,
            groundFraction = if (fitSucceeded) 1.0f else 0.0f,
            obstacleFraction = 0.0f,
            unknownFraction = if (fitSucceeded) 0.0f else 1.0f,
            processingMs = mleMs,
        ),
        bitmap = null,
        accelerator = "GPU",
        minDepth = 1.0f,
        maxDepth = 1.0f,
        finitePositiveFraction = 1.0,
        p10Depth = 1.0f,
        p50Depth = 1.0f,
        p90Depth = 1.0f,
        preProcessMs = 2.0,
        inferenceMs = 3.0,
        groundFilterMs = mleMs,
        renderMs = 4.0,
    )
}
