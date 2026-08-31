package com.example.glasses.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.example.glasses.pipeline.AudioSpectrumBar
import com.example.glasses.pipeline.AudioWaveformBar
import com.example.glasses.pipeline.DepthAudioCoordinatorStatus

internal data class DepthAudioUiState(
    val status: DepthAudioCoordinatorStatus = DepthAudioCoordinatorStatus.STOPPED,
    val activeObstacleCount: Int = 0,
    val soundscapeRenderCount: Long = 0L,
    val leftWaveform: List<AudioWaveformBar> = emptyList(),
    val rightWaveform: List<AudioWaveformBar> = emptyList(),
    val leftSpectrum: List<AudioSpectrumBar> = emptyList(),
    val rightSpectrum: List<AudioSpectrumBar> = emptyList(),
    val errorMessage: String? = null,
)

sealed interface DepthCameraUiState {
    data object LoadingModel : DepthCameraUiState
    data object WaitingForCamera : DepthCameraUiState

    data class Running(
        val image: ImageBitmap,
        val classificationImage: ImageBitmap,
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
        val performanceFrameSequence: Long,
        val performancePublishedAtNanos: Long,
    ) : DepthCameraUiState

    data class Error(
        val message: String,
    ) : DepthCameraUiState
}
