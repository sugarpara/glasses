package com.example.glasses.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glasses.depth.DepthEstimator
import com.example.glasses.inference.LiteRtDepthModel
import com.example.glasses.inference.ModelFileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var estimator: DepthEstimator? = null
    private var lastFrameTimeNanos = 0L
    private var smoothedFps = 0.0

    fun initialize() {
        if (estimator != null || !initializing.compareAndSet(false, true)) return
        mutableState.value = DepthCameraUiState.LoadingModel
        viewModelScope.launch(Dispatchers.Default) {
            var createdEstimator: DepthEstimator? = null
            try {
                val context = getApplication<Application>()
                val file = ModelFileProvider.prepare(context, MODEL_ASSET)
                createdEstimator = DepthEstimator(
                    LiteRtDepthModel(context, file, preferGpu = true),
                )
                currentCoroutineContext().ensureActive()
                estimator = createdEstimator
                createdEstimator = null
                mutableState.value = DepthCameraUiState.WaitingForCamera
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = DepthCameraUiState.Error(error.toDisplayMessage())
            } finally {
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
                val frame = activeEstimator.predict(bitmap)
                unpublishedBitmap = frame.bitmap
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

                val image = withContext(Dispatchers.Main) {
                    frame.bitmap.asImageBitmap()
                }
                mutableState.value = DepthCameraUiState.Running(
                    image = image,
                    accelerator = frame.accelerator,
                    fps = smoothedFps,
                    inferenceMs = frame.inferenceMs,
                    minDepth = frame.minDepth,
                    maxDepth = frame.maxDepth,
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
        mutableState.value = DepthCameraUiState.Error(error.toDisplayMessage())
    }

    override fun onCleared() {
        estimator?.close()
        estimator = null
        super.onCleared()
    }

    private fun Throwable.toDisplayMessage(): String = message ?: javaClass.simpleName

    companion object {
        private const val MODEL_ASSET = "yolo26n-depth_w8a32.tflite"
        private const val FPS_HISTORY_WEIGHT = 0.9
        private const val FPS_SAMPLE_WEIGHT = 0.1
    }
}
