package com.example.glasses.ground

data class GroundFilterConfig(
    val fitRoiTop: Float = 0.45f,
    val classificationRoiTop: Float = 0.0f,
    val obstacleMaxDepthMeters: Float = 5.0f,
    val fitMaxDepthMeters: Float = 30.0f,
    val sampleStep: Int = 4,
    val maxIterations: Int = 20,
) {
    init {
        requireValidRoi("fitRoiTop", fitRoiTop)
        requireValidRoi("classificationRoiTop", classificationRoiTop)
        requireValidDistance("obstacleMaxDepthMeters", obstacleMaxDepthMeters)
        requireValidDistance("fitMaxDepthMeters", fitMaxDepthMeters)
        require(fitMaxDepthMeters >= obstacleMaxDepthMeters) {
            "fitMaxDepthMeters must be greater than or equal to obstacleMaxDepthMeters"
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
