package com.example.glasses.ui

import androidx.compose.ui.graphics.ImageBitmap

sealed interface DepthCameraUiState {
    data object LoadingModel : DepthCameraUiState
    data object WaitingForCamera : DepthCameraUiState

    data class Running(
        val image: ImageBitmap,
        val classificationDisplayEnabled: Boolean,
        val accelerator: String,
        val fps: Double,
        val inferenceMs: Double,
        val groundFilterMs: Double,
        val minDepth: Float,
        val maxDepth: Float,
        val groundFitSucceeded: Boolean,
        val groundFraction: Float,
        val obstacleFraction: Float,
        val unknownFraction: Float,
        val activeObstacleCells: Int,
        val maxObstacleOccupancy: Float,
    ) : DepthCameraUiState

    data class Error(
        val message: String,
    ) : DepthCameraUiState
}
