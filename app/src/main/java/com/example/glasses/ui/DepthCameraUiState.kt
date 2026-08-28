package com.example.glasses.ui

import androidx.compose.ui.graphics.ImageBitmap

sealed interface DepthCameraUiState {
    data object LoadingModel : DepthCameraUiState
    data object WaitingForCamera : DepthCameraUiState

    data class Running(
        val image: ImageBitmap,
        val accelerator: String,
        val fps: Double,
        val inferenceMs: Double,
        val minDepth: Float,
        val maxDepth: Float,
    ) : DepthCameraUiState

    data class Error(
        val message: String,
    ) : DepthCameraUiState
}
