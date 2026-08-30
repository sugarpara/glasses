package com.example.glasses.obstacle

const val OBSTACLE_GRID_ROWS = 64
const val OBSTACLE_GRID_COLUMNS = 64
const val OBSTACLE_GRID_CELL_COUNT = OBSTACLE_GRID_ROWS * OBSTACLE_GRID_COLUMNS
const val OBSTACLE_ENTER_DISTANCE_METERS = 3.0f
const val OBSTACLE_EXIT_DISTANCE_METERS = 3.3f
const val OBSTACLE_EMERGENCY_DISTANCE_METERS = 0.8f

/**
 * Row-major obstacle occupancy consumed by temporal processing and audio rendering.
 *
 * This value object contains no rendered image and has no image resource ownership responsibilities.
 */
data class ObstacleGridFrame(
    val occupancy: FloatArray,
    val distanceMeters: FloatArray,
    val timestampMs: Long,
    val fitSucceeded: Boolean,
) {
    init {
        requireValidObstacleOccupancy(occupancy)
        requireValidObstacleDistance(distanceMeters)
        requireMatchingObstacleDistance(occupancy, distanceMeters)
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

internal fun requireValidObstacleDistance(distanceMeters: FloatArray) {
    require(distanceMeters.size == OBSTACLE_GRID_CELL_COUNT) {
        "Obstacle distance must contain exactly $OBSTACLE_GRID_CELL_COUNT values"
    }
    require(distanceMeters.all { it.isFinite() && it >= 0.0f }) {
        "Obstacle distance values must be finite and non-negative"
    }
}

internal fun requireMatchingObstacleDistance(
    occupancy: FloatArray,
    distanceMeters: FloatArray,
) {
    require(occupancy.indices.all { index ->
        (occupancy[index] > 0.0f) == (distanceMeters[index] > 0.0f)
    }) {
        "Obstacle occupancy and distance must identify the same non-empty cells"
    }
}
