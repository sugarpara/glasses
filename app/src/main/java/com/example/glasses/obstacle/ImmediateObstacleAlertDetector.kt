package com.example.glasses.obstacle

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val IMMEDIATE_OBSTACLE_ALERT_COOLDOWN_MS = 180L
internal const val IMMEDIATE_OBSTACLE_ALERT_MAX_TARGETS = 3

internal data class Glasses64ImmediateAlertTarget(
    val row: Int,
    val column: Int,
    val strength: Float,
    val cellCount: Int
) {
    init {
        require(row in 0 until OBSTACLE_GRID_ROWS)
        require(column in 0 until OBSTACLE_GRID_COLUMNS)
        require(strength.isFinite() && strength in 0.0f..1.0f)
        require(cellCount > 0)
    }
}

internal data class ImmediateObstacleAlertEvent(
    val targets: List<Glasses64ImmediateAlertTarget>,
    val inputTimestampMs: Long,
    val sourceTimestampMs: Long?,
    val activeObstacleCount: Int,
    val newlyActiveCount: Int
)

/**
 * Finds newly appearing stable obstacle components without retaining frames or audio jobs.
 */
internal class ImmediateObstacleAlertDetector(
    private val cooldownMs: Long = IMMEDIATE_OBSTACLE_ALERT_COOLDOWN_MS
) {
    private var previousActive = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
    private var lastAlertTimeMs = Long.MIN_VALUE

    @Synchronized
    fun detect(
        frame: ProcessedObstacleGridFrame,
        nowMs: Long
    ): ImmediateObstacleAlertEvent? = detect(
        activeMask = frame.activeMask,
        smoothedOccupancy = frame.smoothedOccupancy,
        inputTimestampMs = frame.timestampMs,
        sourceTimestampMs = frame.timestampMs,
        nowMs = nowMs
    )

    @Synchronized
    internal fun detect(
        activeMask: BooleanArray,
        smoothedOccupancy: FloatArray,
        inputTimestampMs: Long,
        sourceTimestampMs: Long?,
        nowMs: Long
    ): ImmediateObstacleAlertEvent? {
        require(activeMask.size == OBSTACLE_GRID_CELL_COUNT)
        require(smoothedOccupancy.size == OBSTACLE_GRID_CELL_COUNT)

        for (index in previousActive.indices) {
            if (!activeMask[index]) previousActive[index] = false
        }

        val cooldownElapsed = lastAlertTimeMs == Long.MIN_VALUE ||
            nowMs - lastAlertTimeMs >= cooldownMs
        if (!cooldownElapsed) return null

        val visited = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
        val newComponents = mutableListOf<Component>()
        var activeObstacleCount = 0
        for (isActive in activeMask) if (isActive) activeObstacleCount++

        for (startIndex in activeMask.indices) {
            if (!activeMask[startIndex] || visited[startIndex]) continue
            val component = collectComponent(
                startIndex = startIndex,
                activeMask = activeMask,
                smoothedOccupancy = smoothedOccupancy,
                visited = visited
            )
            if (!component.overlapsPrevious) newComponents += component
        }

        previousActive = activeMask.copyOf()
        if (newComponents.isEmpty()) return null

        lastAlertTimeMs = nowMs
        val targets = newComponents
            .sortedByDescending { it.priority }
            .take(IMMEDIATE_OBSTACLE_ALERT_MAX_TARGETS)
            .map { it.toTarget() }
        return ImmediateObstacleAlertEvent(
            targets = targets,
            inputTimestampMs = inputTimestampMs,
            sourceTimestampMs = sourceTimestampMs,
            activeObstacleCount = activeObstacleCount,
            newlyActiveCount = newComponents.sumOf { it.cellCount }
        )
    }

    @Synchronized
    fun reset() {
        previousActive = BooleanArray(OBSTACLE_GRID_CELL_COUNT)
        lastAlertTimeMs = Long.MIN_VALUE
    }

    private fun collectComponent(
        startIndex: Int,
        activeMask: BooleanArray,
        smoothedOccupancy: FloatArray,
        visited: BooleanArray
    ): Component {
        val pending = IntArray(OBSTACLE_GRID_CELL_COUNT)
        var readIndex = 0
        var writeIndex = 0
        pending[writeIndex++] = startIndex
        visited[startIndex] = true

        var overlapsPrevious = false
        var cellCount = 0
        var strengthSum = 0.0
        var weightedRowSum = 0.0
        var weightedColumnSum = 0.0

        while (readIndex < writeIndex) {
            val index = pending[readIndex++]
            val row = index / OBSTACLE_GRID_COLUMNS
            val column = index % OBSTACLE_GRID_COLUMNS
            val strength = smoothedOccupancy[index].coerceIn(0f, 1f).toDouble()
            overlapsPrevious = overlapsPrevious || previousActive[index]
            cellCount++
            strengthSum += strength
            weightedRowSum += row.toDouble() * strength
            weightedColumnSum += column.toDouble() * strength

            for (rowOffset in -1..1) {
                for (columnOffset in -1..1) {
                    if (rowOffset == 0 && columnOffset == 0) continue
                    val neighborRow = row + rowOffset
                    val neighborColumn = column + columnOffset
                    if (
                        neighborRow !in 0 until OBSTACLE_GRID_ROWS ||
                        neighborColumn !in 0 until OBSTACLE_GRID_COLUMNS
                    ) {
                        continue
                    }
                    val neighborIndex = neighborRow * OBSTACLE_GRID_COLUMNS + neighborColumn
                    if (activeMask[neighborIndex] && !visited[neighborIndex]) {
                        visited[neighborIndex] = true
                        pending[writeIndex++] = neighborIndex
                    }
                }
            }
        }

        return Component(
            overlapsPrevious = overlapsPrevious,
            cellCount = cellCount,
            strengthSum = strengthSum,
            weightedRowSum = weightedRowSum,
            weightedColumnSum = weightedColumnSum
        )
    }

    private data class Component(
        val overlapsPrevious: Boolean,
        val cellCount: Int,
        val strengthSum: Double,
        val weightedRowSum: Double,
        val weightedColumnSum: Double
    ) {
        private val averageStrength: Double
            get() = strengthSum / cellCount.coerceAtLeast(1).toDouble()

        private val centroidRow: Int
            get() = weightedCentroid(weightedRowSum, OBSTACLE_GRID_ROWS)

        private val centroidColumn: Int
            get() = weightedCentroid(weightedColumnSum, OBSTACLE_GRID_COLUMNS)

        val priority: Double
            get() {
                val lowerPriority = 1.0 + 0.45 *
                    centroidRow.toDouble() / (OBSTACLE_GRID_ROWS - 1).toDouble()
                return sqrt(cellCount.toDouble()) * averageStrength * lowerPriority
            }

        fun toTarget(): Glasses64ImmediateAlertTarget = Glasses64ImmediateAlertTarget(
            row = centroidRow,
            column = centroidColumn,
            strength = averageStrength.toFloat().coerceIn(0f, 1f),
            cellCount = cellCount
        )

        private fun weightedCentroid(weightedSum: Double, dimension: Int): Int {
            if (strengthSum <= 0.0) return dimension / 2
            return (weightedSum / strengthSum).roundToInt().coerceIn(0, dimension - 1)
        }
    }
}
