package com.example.glasses.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glasses.depth.DepthEstimator
import com.example.glasses.ground.ClassificationRenderThrottle
import com.example.glasses.inference.LiteRtDepthModel
import com.example.glasses.inference.ModelFileProvider
import com.example.glasses.pipeline.DepthAudioCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class DepthCameraViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<DepthCameraUiState>(
        DepthCameraUiState.LoadingModel,
    )
    val state: StateFlow<DepthCameraUiState> = mutableState.asStateFlow()

    private val initializing = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val audioRequested = AtomicBoolean(false)
    private val classificationDisplayEnabled = AtomicBoolean(false)
    private val classificationRenderThrottle = ClassificationRenderThrottle()
    private var estimator: DepthEstimator? = null
    private var audioCoordinator: DepthAudioCoordinator? = null
    private var lastFrameTimeNanos = 0L
    private var smoothedFps = 0.0

    fun initialize() {
        if (estimator != null || !initializing.compareAndSet(false, true)) return
        mutableState.value = DepthCameraUiState.LoadingModel
        viewModelScope.launch(Dispatchers.Default) {
            var createdEstimator: DepthEstimator? = null
            var createdAudioCoordinator: DepthAudioCoordinator? = null
            try {
                val context = getApplication<Application>()
                val file = ModelFileProvider.prepare(context, MODEL_ASSET)
                createdEstimator = DepthEstimator(
                    LiteRtDepthModel(context, file, preferGpu = true),
                )
                createdAudioCoordinator = DepthAudioCoordinator(context).also { it.warmUp() }
                currentCoroutineContext().ensureActive()
                if (audioRequested.get()) createdAudioCoordinator.start()
                estimator = createdEstimator
                audioCoordinator = createdAudioCoordinator
                createdEstimator = null
                createdAudioCoordinator = null
                mutableState.value = DepthCameraUiState.WaitingForCamera
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = DepthCameraUiState.Error(error.toDisplayMessage())
            } finally {
                createdAudioCoordinator?.close()
                createdEstimator?.close()
                initializing.set(false)
            }
        }
    }

    fun process(bitmap: Bitmap) {
        val activeEstimator = estimator
        if (activeEstimator == null || !processing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            var unpublishedBitmap: Bitmap? = null
            try {
                val classificationEnabled = classificationDisplayEnabled.get()
                val renderClassification = classificationRenderThrottle.shouldRender(
                    enabled = classificationEnabled,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                val frame = activeEstimator.predict(
                    source = bitmap,
                    renderDepthBitmap = !classificationEnabled,
                    renderClassificationBitmap = renderClassification,
                )
                audioCoordinator?.submit(frame.groundFilter)
                val displayBitmap = frame.bitmap
                unpublishedBitmap = displayBitmap
                val now = System.nanoTime()
                val instantFps = if (lastFrameTimeNanos == 0L) {
                    0.0
                } else {
                    1_000_000_000.0 / (now - lastFrameTimeNanos)
                }
                lastFrameTimeNanos = now
                smoothedFps = if (smoothedFps == 0.0) {
                    instantFps
                } else {
                    smoothedFps * FPS_HISTORY_WEIGHT + instantFps * FPS_SAMPLE_WEIGHT
                }

                val image = if (displayBitmap != null) {
                    withContext(Dispatchers.Main) { displayBitmap.asImageBitmap() }
                } else {
                    checkNotNull((mutableState.value as? DepthCameraUiState.Running)?.image) {
                        "The first visible frame must render a display bitmap"
                    }
                }
                val groundFilter = frame.groundFilter
                var activeObstacleCells = 0
                var maxObstacleOccupancy = 0.0f
                for (occupancy in groundFilter.obstacleOccupancy) {
                    if (occupancy >= ACTIVE_OBSTACLE_CELL_THRESHOLD) {
                        activeObstacleCells++
                    }
                    if (occupancy > maxObstacleOccupancy) {
                        maxObstacleOccupancy = occupancy
                    }
                }
                mutableState.value = DepthCameraUiState.Running(
                    image = image,
                    classificationDisplayEnabled = classificationDisplayEnabled.get(),
                    accelerator = frame.accelerator,
                    fps = smoothedFps,
                    inferenceMs = frame.inferenceMs,
                    groundFilterMs = frame.groundFilterMs,
                    minDepth = frame.minDepth,
                    maxDepth = frame.maxDepth,
                    groundFitSucceeded = groundFilter.fitSucceeded,
                    groundFraction = groundFilter.groundFraction,
                    obstacleFraction = groundFilter.obstacleFraction,
                    unknownFraction = groundFilter.unknownFraction,
                    activeObstacleCells = activeObstacleCells,
                    maxObstacleOccupancy = maxObstacleOccupancy,
                )
                unpublishedBitmap = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = DepthCameraUiState.Error(error.toDisplayMessage())
            } finally {
                unpublishedBitmap?.recycle()
                bitmap.recycle()
                processing.set(false)
            }
        }
    }

    fun reportCameraError(error: Throwable) {
        audioCoordinator?.stop()
        mutableState.value = DepthCameraUiState.Error(error.toDisplayMessage())
    }

    fun startAudio() {
        audioRequested.set(true)
        audioCoordinator?.start()
    }

    fun stopAudio() {
        audioRequested.set(false)
        audioCoordinator?.stop()
    }

    fun setClassificationDisplayEnabled(enabled: Boolean) {
        classificationDisplayEnabled.set(enabled)
        mutableState.update { state ->
            if (state is DepthCameraUiState.Running) {
                state.copy(classificationDisplayEnabled = enabled)
            } else {
                state
            }
        }
    }

    override fun onCleared() {
        audioCoordinator?.close()
        audioCoordinator = null
        estimator?.close()
        estimator = null
        super.onCleared()
    }

    private fun Throwable.toDisplayMessage(): String = message ?: javaClass.simpleName

    companion object {
        private const val MODEL_ASSET = "yolo26n-depth_w8a32.tflite"
        private const val FPS_HISTORY_WEIGHT = 0.9
        private const val FPS_SAMPLE_WEIGHT = 0.1
        private const val ACTIVE_OBSTACLE_CELL_THRESHOLD = 0.01f
    }
}
