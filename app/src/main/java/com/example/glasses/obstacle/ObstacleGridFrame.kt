package com.example.glasses.obstacle

const val OBSTACLE_GRID_ROWS = 64
const val OBSTACLE_GRID_COLUMNS = 64
const val OBSTACLE_GRID_CELL_COUNT = OBSTACLE_GRID_ROWS * OBSTACLE_GRID_COLUMNS

/**
 * Row-major obstacle occupancy consumed by temporal processing and audio rendering.
 *
 * This value object contains no rendered image and has no image resource ownership responsibilities.
 */
data class ObstacleGridFrame(
    val occupancy: FloatArray,
    val timestampMs: Long,
    val fitSucceeded: Boolean,
) {
    init {
        requireValidObstacleOccupancy(occupancy)
        require(timestampMs >= 0L) { "Obstacle grid timestamp must be non-negative" }
    }
}

internal fun requireValidObstacleOccupancy(occupancy: FloatArray) {
    require(occupancy.size == OBSTACLE_GRID_CELL_COUNT) {
        "Obstacle occupancy must contain exactly $OBSTACLE_GRID_CELL_COUNT values"
    }
    require(occupancy.all { it.isFinite() && it in 0.0f..1.0f }) {
        "Obstacle occupancy values must be finite and in [0.0, 1.0]"
    }
}
