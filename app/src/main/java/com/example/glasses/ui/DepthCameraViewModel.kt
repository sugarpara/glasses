package com.example.glasses.ui

import android.app.Application
import android.content.pm.ApplicationInfo
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
import com.example.glasses.pipeline.PipelinePerformanceMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    private val depthRenderThrottle = ClassificationRenderThrottle()
    private val classificationRenderThrottle = ClassificationRenderThrottle()
    private val performanceMonitor = PipelinePerformanceMonitor(
        enabled = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
    private var estimator: DepthEstimator? = null
    private var audioCoordinator: DepthAudioCoordinator? = null
    private var lastFrameTimeNanos = 0L
    private var smoothedFps = 0.0
    private var performanceFrameSequence = 0L

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
                observePerformance(createdAudioCoordinator)
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

    fun process(bitmap: Bitmap, cameraConversionMs: Double) {
        performanceMonitor.recordCamera(cameraConversionMs)
        val activeEstimator = estimator
        if (activeEstimator == null || !processing.compareAndSet(false, true)) {
            performanceMonitor.recordDroppedFrame()
            bitmap.recycle()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            var unpublishedBitmap: Bitmap? = null
            try {
                val classificationEnabled = classificationDisplayEnabled.get()
                val nowMs = SystemClock.elapsedRealtime()
                val renderDepth = depthRenderThrottle.shouldRender(
                    enabled = !classificationEnabled,
                    nowMs = nowMs,
                )
                val renderClassification = classificationRenderThrottle.shouldRender(
                    enabled = classificationEnabled,
                    nowMs = nowMs,
                )
                val frame = activeEstimator.predict(
                    source = bitmap,
                    renderDepthBitmap = renderDepth,
                    renderClassificationBitmap = renderClassification,
                )
                performanceMonitor.recordProcessedFrame(frame)
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
                val publishedAtNanos = System.nanoTime()
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
                    performanceFrameSequence = ++performanceFrameSequence,
                    performancePublishedAtNanos = publishedAtNanos,
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

    fun reportUiFrameDisplayed(publishedAtNanos: Long) {
        val deliveryMs = (System.nanoTime() - publishedAtNanos).coerceAtLeast(0L) / 1_000_000.0
        performanceMonitor.recordUi(deliveryMs)
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
        performanceMonitor.logCurrentSnapshot()
        audioCoordinator?.close()
        audioCoordinator = null
        estimator?.close()
        estimator = null
        super.onCleared()
    }

    private fun Throwable.toDisplayMessage(): String = message ?: javaClass.simpleName

    private fun observePerformance(coordinator: DepthAudioCoordinator) {
        viewModelScope.launch(Dispatchers.Default) {
            var previousProcessed = 0L
            var previousDropped = 0L
            coordinator.processorStats.collect { stats ->
                if (stats.processedFrameCount < previousProcessed) previousProcessed = 0L
                if (stats.droppedFrameCount < previousDropped) previousDropped = 0L
                val processedDelta = stats.processedFrameCount - previousProcessed
                val droppedDelta = stats.droppedFrameCount - previousDropped
                if (processedDelta > 0L || droppedDelta > 0L) {
                    performanceMonitor.recordGrid(
                        processingMs = stats.lastProcessingMs,
                        processedDelta = processedDelta,
                        droppedDelta = droppedDelta,
                    )
                }
                previousProcessed = stats.processedFrameCount
                previousDropped = stats.droppedFrameCount
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            var previousSoundscapes = 0L
            var previousAlerts = 0L
            coordinator.state.collect { state ->
                if (state.soundscapeRenderCount < previousSoundscapes) previousSoundscapes = 0L
                if (state.immediateAlertCount < previousAlerts) previousAlerts = 0L
                val soundscapeDelta = state.soundscapeRenderCount - previousSoundscapes
                val alertDelta = state.immediateAlertCount - previousAlerts
                if (soundscapeDelta > 0L) {
                    performanceMonitor.recordSoundscape(
                        renderMs = state.lastSoundscapeRenderMs,
                        countDelta = soundscapeDelta,
                    )
                }
                if (alertDelta > 0L) performanceMonitor.recordImmediateAlerts(alertDelta)
                previousSoundscapes = state.soundscapeRenderCount
                previousAlerts = state.immediateAlertCount
            }
        }
    }

    companion object {
        private const val MODEL_ASSET = "yolo26n-depth_w8a32.tflite"
        private const val FPS_HISTORY_WEIGHT = 0.9
        private const val FPS_SAMPLE_WEIGHT = 0.1
        private const val ACTIVE_OBSTACLE_CELL_THRESHOLD = 0.01f
    }
}
