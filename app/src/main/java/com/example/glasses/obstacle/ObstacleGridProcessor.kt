package com.example.glasses.obstacle

import com.example.glasses.audio.GLASSES64_MAX_REGIONS_PER_COLUMN
import com.example.glasses.audio.Glasses64ActiveCell
import com.example.glasses.audio.Glasses64ColumnRequest
import com.example.glasses.audio.Glasses64VerticalRegion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val CURRENT_OCCUPANCY_WEIGHT = 0.30f
private const val PREVIOUS_OCCUPANCY_WEIGHT = 0.70f
private const val HYSTERESIS_ON_THRESHOLD = 0.55f
private const val HYSTERESIS_OFF_THRESHOLD = 0.35f

internal data class ProcessedObstacleGridFrame(
    val smoothedOccupancy: FloatArray,
    val smoothedDistanceMeters: FloatArray,
    val activeMask: BooleanArray,
    val columnRequests: List<Glasses64ColumnRequest>,
    val timestampMs: Long,
    val fitSucceeded: Boolean,
    val processingMs: Double,
    val processedFrameCount: Long,
) {
    init {
        requireValidObstacleOccupancy(smoothedOccupancy)
        requireValidObstacleDistance(smoothedDistanceMeters)
        require(activeMask.size == OBSTACLE_GRID_CELL_COUNT)
        require(columnRequests.size == OBSTACLE_GRID_COLUMNS)
        require(timestampMs >= 0L)
        require(processingMs.isFinite() && processingMs >= 0.0)
        require(processedFrameCount >= 0L)
    }
}

internal data class ObstacleGridProcessorStats(
    val latestInputTimestampMs: Long = 0L,
    val submittedFrameCount: Long = 0L,
    val processedFrameCount: Long = 0L,
    val droppedFrameCount: Long = 0L,
    val lastProcessingMs: Double = 0.0,
    val averageProcessingMs: Double = 0.0,
)

/**
 * Converts the native 64x64 occupancy grid directly into stable audio requests.
 */
internal class ObstacleGridTransform {
    private var previousOccupancy: FloatArray? = null
    private var previousDistanceMeters: FloatArray? = null
    private var activeStates = BooleanArray(OBSTACLE_GRID_CELL_COUNT)

    @Synchronized
    fun process(frame: ObstacleGridFrame): ProcessedObstacleGridFrame {
        val smoothed = smooth(frame.occupancy)
        val smoothedDistance = smoothDistance(frame.distanceMeters)
        val active = updateHysteresis(frame.occupancy, smoothed)
        applyEmergencyOverrides(frame, smoothed, smoothedDistance, active)
        clearInactiveDistances(smoothedDistance, active)
        return ProcessedObstacleGridFrame(
            smoothedOccupancy = smoothed,
            smoothedDistanceMeters = smoothedDistance,
            activeMask = active,
            columnRequests = buildColumnRequests(smoothed, smoothedDistance, active),
            timestampMs = frame.timestampMs,
            fitSucceeded = frame.fitSucceeded,
            processingMs = 0.0,
            processedFrameCount = 0L,
        )
    }

    @Synchronized
    fun reset() {
        previousOccupancy = null
        previousDistanceMeters = null
        activeStates = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
    }

    private fun smooth(current: FloatArray): FloatArray {
        val previous = previousOccupancy
        if (previous == null) {
            return current.copyOf().also { previousOccupancy = it.copyOf() }
        }

        val output = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        for (index in current.indices) {
            val value = CURRENT_OCCUPANCY_WEIGHT * current[index] +
                PREVIOUS_OCCUPANCY_WEIGHT * previous[index]
            output[index] = value
            previous[index] = value
        }
        return output
    }

    private fun updateHysteresis(
        current: FloatArray,
        smoothed: FloatArray,
    ): BooleanArray {
        for (index in smoothed.indices) {
            activeStates[index] = if (activeStates[index]) {
                smoothed[index] > HYSTERESIS_OFF_THRESHOLD
            } else {
                current[index] >= HYSTERESIS_ON_THRESHOLD
            }
        }
        return activeStates.copyOf()
    }

    private fun applyEmergencyOverrides(
        frame: ObstacleGridFrame,
        smoothedOccupancy: FloatArray,
        smoothedDistanceMeters: FloatArray,
        active: BooleanArray,
    ) {
        for (index in active.indices) {
            val distance = frame.distanceMeters[index]
            if (
                frame.occupancy[index] > 0.0f &&
                distance > 0.0f &&
                distance <= OBSTACLE_EMERGENCY_DISTANCE_METERS
            ) {
                active[index] = true
                smoothedOccupancy[index] =
                    maxOf(smoothedOccupancy[index], frame.occupancy[index])
                smoothedDistanceMeters[index] = distance
                checkNotNull(previousDistanceMeters)[index] = distance
            }
        }
    }

    private fun smoothDistance(current: FloatArray): FloatArray {
        val previous = previousDistanceMeters
            ?: FloatArray(OBSTACLE_GRID_CELL_COUNT).also { previousDistanceMeters = it }
        val output = FloatArray(OBSTACLE_GRID_CELL_COUNT)
        for (index in current.indices) {
            val currentValue = current[index]
            val previousValue = previous[index]
            val value = when {
                currentValue <= 0.0f -> previousValue
                previousValue <= 0.0f -> currentValue
                else -> CURRENT_OCCUPANCY_WEIGHT * currentValue +
                    PREVIOUS_OCCUPANCY_WEIGHT * previousValue
            }
            output[index] = value
            previous[index] = value
        }
        return output
    }

    private fun clearInactiveDistances(
        smoothedDistanceMeters: FloatArray,
        activeMask: BooleanArray,
    ) {
        val previous = checkNotNull(previousDistanceMeters)
        for (index in activeMask.indices) {
            if (!activeMask[index]) {
                smoothedDistanceMeters[index] = 0.0f
                previous[index] = 0.0f
            }
        }
    }

    private fun buildColumnRequests(
        smoothedOccupancy: FloatArray,
        smoothedDistanceMeters: FloatArray,
        activeMask: BooleanArray,
    ): List<Glasses64ColumnRequest> = List(OBSTACLE_GRID_COLUMNS) { column ->
        val selectedRegions = mergeColumnRegions(
            column,
            smoothedOccupancy,
            smoothedDistanceMeters,
            activeMask,
        )
            .sortedByDescending { it.strength }
            .take(GLASSES64_MAX_REGIONS_PER_COLUMN)
            .sortedBy { it.representativeRow }
        Glasses64ColumnRequest(
            column = column,
            regions = selectedRegions,
            activeCells = collectColumnActiveCells(
                column,
                smoothedOccupancy,
                smoothedDistanceMeters,
                activeMask,
            ),
        )
    }

    private fun collectColumnActiveCells(
        column: Int,
        smoothedOccupancy: FloatArray,
        smoothedDistanceMeters: FloatArray,
        activeMask: BooleanArray,
    ): List<Glasses64ActiveCell> = buildList {
        for (row in 0 until OBSTACLE_GRID_ROWS) {
            val index = cellIndex(row, column)
            if (activeMask[index]) {
                add(
                    Glasses64ActiveCell(
                        row = row,
                        strength = smoothedOccupancy[index].coerceIn(0f, 1f),
                        distanceMeters = smoothedDistanceMeters[index],
                    ),
                )
            }
        }
    }

    private fun mergeColumnRegions(
        column: Int,
        smoothedOccupancy: FloatArray,
        smoothedDistanceMeters: FloatArray,
        activeMask: BooleanArray,
    ): List<Glasses64VerticalRegion> {
        val output = mutableListOf<Glasses64VerticalRegion>()
        var startRow = -1
        var strengthSum = 0.0
        var weightedRowSum = 0.0
        var weightedDistanceSum = 0.0
        var cellCount = 0

        fun finishRegion(endRow: Int) {
            if (startRow < 0 || cellCount == 0) return
            val representativeRow = if (strengthSum > 0.0) {
                (weightedRowSum / strengthSum).roundToInt()
            } else {
                (startRow + endRow) / 2
            }.coerceIn(startRow, endRow)
            output += Glasses64VerticalRegion(
                startRow = startRow,
                endRow = endRow,
                representativeRow = representativeRow,
                strength = (strengthSum / cellCount.toDouble()).toFloat(),
                distanceMeters = if (strengthSum > 0.0) {
                    (weightedDistanceSum / strengthSum).toFloat()
                } else {
                    0.0f
                },
            )
            startRow = -1
            strengthSum = 0.0
            weightedRowSum = 0.0
            weightedDistanceSum = 0.0
            cellCount = 0
        }

        for (row in 0 until OBSTACLE_GRID_ROWS) {
            val index = cellIndex(row, column)
            if (activeMask[index]) {
                if (startRow < 0) startRow = row
                val strength = smoothedOccupancy[index].toDouble()
                strengthSum += strength
                weightedRowSum += row.toDouble() * strength
                weightedDistanceSum += smoothedDistanceMeters[index].toDouble() * strength
                cellCount++
            } else if (startRow >= 0) {
                finishRegion(row - 1)
            }
        }
        if (startRow >= 0) finishRegion(OBSTACLE_GRID_ROWS - 1)
        return output
    }

    companion object {
        fun azimuthDegrees(column: Int): Double {
            require(column in 0 until OBSTACLE_GRID_COLUMNS)
            return -60.0 + column * (120.0 / (OBSTACLE_GRID_COLUMNS - 1).toDouble())
        }

        fun elevationDegrees(row: Int): Double {
            require(row in 0 until OBSTACLE_GRID_ROWS)
            return 60.0 - row * (105.0 / (OBSTACLE_GRID_ROWS - 1).toDouble())
        }

        private fun cellIndex(row: Int, column: Int): Int =
            row * OBSTACLE_GRID_COLUMNS + column
    }
}

/**
 * Single-consumer latest-only wrapper around [ObstacleGridTransform].
 */
internal class ObstacleGridProcessor(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private data class QueuedFrame(val generation: Long, val frame: ObstacleGridFrame)

    private val transform = ObstacleGridTransform()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generation = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val statisticsLock = Any()
    private val latestReference = AtomicReference<ProcessedObstacleGridFrame?>(null)

    private val mutableLatest = MutableStateFlow<ProcessedObstacleGridFrame?>(null)
    val latest: StateFlow<ProcessedObstacleGridFrame?> = mutableLatest.asStateFlow()

    private val mutableStats = MutableStateFlow(ObstacleGridProcessorStats())
    val stats: StateFlow<ObstacleGridProcessorStats> = mutableStats.asStateFlow()

    private var latestInputTimestampMs = 0L
    private var submittedFrameCount = 0L
    private var processedFrameCount = 0L
    private var droppedFrameCount = 0L
    private var lastProcessingMs = 0.0
    private var totalProcessingMs = 0.0

    private val frameChannel = Channel<QueuedFrame>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = { queued ->
            if (queued.generation == generation.get()) recordDroppedFrame()
        },
    )

    init {
        scope.launch {
            for (queued in frameChannel) processQueuedFrame(queued)
        }
    }

    fun submit(frame: ObstacleGridFrame): Boolean {
        if (closed.get()) return false
        val frameGeneration = generation.get()
        val result = frameChannel.trySend(QueuedFrame(frameGeneration, frame))
        if (!result.isSuccess || frameGeneration != generation.get()) return false
        synchronized(statisticsLock) {
            latestInputTimestampMs = frame.timestampMs
            submittedFrameCount++
            publishStatsLocked()
        }
        return true
    }

    fun getLatestProcessedFrame(): ProcessedObstacleGridFrame? = latestReference.get()

    fun reset() {
        generation.incrementAndGet()
        while (frameChannel.tryReceive().isSuccess) {
            // Pending inputs belong to the previous processing generation.
        }
        transform.reset()
        latestReference.set(null)
        mutableLatest.value = null
        synchronized(statisticsLock) {
            latestInputTimestampMs = 0L
            submittedFrameCount = 0L
            processedFrameCount = 0L
            droppedFrameCount = 0L
            lastProcessingMs = 0.0
            totalProcessingMs = 0.0
            publishStatsLocked()
        }
    }

    private fun processQueuedFrame(queued: QueuedFrame) {
        if (queued.generation != generation.get()) return
        val startNs = System.nanoTime()
        val transformed = transform.process(queued.frame)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        if (queued.generation != generation.get()) return

        val processedCount: Long
        synchronized(statisticsLock) {
            processedFrameCount++
            processedCount = processedFrameCount
            lastProcessingMs = elapsedMs
            totalProcessingMs += elapsedMs
            publishStatsLocked()
        }
        val processed = transformed.copy(
            processingMs = elapsedMs,
            processedFrameCount = processedCount,
        )
        latestReference.set(processed)
        mutableLatest.value = processed
    }

    private fun recordDroppedFrame() {
        synchronized(statisticsLock) {
            droppedFrameCount++
            publishStatsLocked()
        }
    }

    private fun publishStatsLocked() {
        mutableStats.value = ObstacleGridProcessorStats(
            latestInputTimestampMs = latestInputTimestampMs,
            submittedFrameCount = submittedFrameCount,
            processedFrameCount = processedFrameCount,
            droppedFrameCount = droppedFrameCount,
            lastProcessingMs = lastProcessingMs,
            averageProcessingMs = if (processedFrameCount == 0L) {
                0.0
            } else {
                totalProcessingMs / processedFrameCount.toDouble()
            },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        frameChannel.cancel()
        scope.cancel()
        latestReference.set(null)
        mutableLatest.value = null
    }
}
