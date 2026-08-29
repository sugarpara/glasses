package com.example.glasses.ground

import com.example.glasses.depth.MetricDepthFrame
import com.example.glasses.obstacle.OBSTACLE_GRID_CELL_COUNT

const val NATIVE_GROUND_FILTER_GROUND_FRACTION_INDEX = 0
const val NATIVE_GROUND_FILTER_OBSTACLE_FRACTION_INDEX = 1
const val NATIVE_GROUND_FILTER_UNKNOWN_FRACTION_INDEX = 2
const val NATIVE_GROUND_FILTER_PROCESSING_MS_INDEX = 3
const val NATIVE_GROUND_FILTER_METRIC_COUNT = 4

/**
 * Owns one native ground-filter context. Input and output arrays remain owned by the caller.
 */
class NativeGroundFilter(
    config: GroundFilterConfig = GroundFilterConfig(),
) : AutoCloseable {
    private var nativeHandle = nativeCreate(
        fitRoiTop = config.fitRoiTop,
        classificationRoiTop = config.classificationRoiTop,
        obstacleMaxDepthMeters = config.obstacleMaxDepthMeters,
        fitMaxDepthMeters = config.fitMaxDepthMeters,
        sampleStep = config.sampleStep,
        maxIterations = config.maxIterations,
    ).also { handle ->
        check(handle != 0L) { "Native ground filter creation failed" }
    }

    @Synchronized
    fun process(
        frame: MetricDepthFrame,
        obstacleOccupancy: FloatArray,
        classMap: ByteArray?,
        metrics: DoubleArray,
    ): Boolean {
        val handle = requireOpenHandle()
        require(obstacleOccupancy.size == OBSTACLE_GRID_CELL_COUNT) {
            "Obstacle occupancy must contain exactly $OBSTACLE_GRID_CELL_COUNT values"
        }
        require(classMap == null || classMap.size.toLong() == frame.width.toLong() * frame.height) {
            "Class map size must match the metric depth dimensions"
        }
        require(metrics.size == NATIVE_GROUND_FILTER_METRIC_COUNT) {
            "Native metrics must contain exactly $NATIVE_GROUND_FILTER_METRIC_COUNT values"
        }

        return nativeProcess(
            handle = handle,
            depthValues = frame.values,
            width = frame.width,
            height = frame.height,
            obstacleOccupancy = obstacleOccupancy,
            classMap = classMap,
            metrics = metrics,
        )
    }

    @Synchronized
    fun reset() {
        nativeReset(requireOpenHandle())
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        nativeDestroy(handle)
    }

    private fun requireOpenHandle(): Long {
        check(nativeHandle != 0L) { "Native ground filter is closed" }
        return nativeHandle
    }

    private external fun nativeCreate(
        fitRoiTop: Float,
        classificationRoiTop: Float,
        obstacleMaxDepthMeters: Float,
        fitMaxDepthMeters: Float,
        sampleStep: Int,
        maxIterations: Int,
    ): Long

    private external fun nativeProcess(
        handle: Long,
        depthValues: FloatArray,
        width: Int,
        height: Int,
        obstacleOccupancy: FloatArray,
        classMap: ByteArray?,
        metrics: DoubleArray,
    ): Boolean

    private external fun nativeReset(handle: Long)

    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("ground_filter")
        }
    }
}
