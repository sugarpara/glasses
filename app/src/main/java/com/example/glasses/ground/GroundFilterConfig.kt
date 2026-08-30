package com.example.glasses.ground

import com.example.glasses.obstacle.OBSTACLE_EMERGENCY_DISTANCE_METERS
import com.example.glasses.obstacle.OBSTACLE_ENTER_DISTANCE_METERS
import com.example.glasses.obstacle.OBSTACLE_EXIT_DISTANCE_METERS

data class GroundFilterConfig(
    val fitRoiTop: Float = 0.45f,
    val classificationRoiTop: Float = 0.0f,
    val obstacleEnterDepthMeters: Float = OBSTACLE_ENTER_DISTANCE_METERS,
    val obstacleExitDepthMeters: Float = OBSTACLE_EXIT_DISTANCE_METERS,
    val emergencyDepthMeters: Float = OBSTACLE_EMERGENCY_DISTANCE_METERS,
    val fitMaxDepthMeters: Float = 30.0f,
    val sampleStep: Int = 8,
    val maxIterations: Int = 20,
) {
    init {
        requireValidRoi("fitRoiTop", fitRoiTop)
        requireValidRoi("classificationRoiTop", classificationRoiTop)
        requireValidDistance("obstacleEnterDepthMeters", obstacleEnterDepthMeters)
        requireValidDistance("obstacleExitDepthMeters", obstacleExitDepthMeters)
        requireValidDistance("emergencyDepthMeters", emergencyDepthMeters)
        requireValidDistance("fitMaxDepthMeters", fitMaxDepthMeters)
        require(emergencyDepthMeters < obstacleEnterDepthMeters) {
            "emergencyDepthMeters must be less than obstacleEnterDepthMeters"
        }
        require(obstacleEnterDepthMeters < obstacleExitDepthMeters) {
            "obstacleEnterDepthMeters must be less than obstacleExitDepthMeters"
        }
        require(fitMaxDepthMeters >= obstacleExitDepthMeters) {
            "fitMaxDepthMeters must be greater than or equal to obstacleExitDepthMeters"
        }
        require(sampleStep > 0) { "sampleStep must be positive" }
        require(maxIterations > 0) { "maxIterations must be positive" }
    }

    private fun requireValidRoi(name: String, value: Float) {
        require(value.isFinite() && value >= 0.0f && value < 1.0f) {
            "$name must be finite and in [0.0, 1.0)"
        }
    }

    private fun requireValidDistance(name: String, value: Float) {
        require(value.isFinite() && value > 0.0f) { "$name must be finite and positive" }
    }
}
