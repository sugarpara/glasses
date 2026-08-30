package com.example.glasses.pipeline

import android.content.Context
import android.os.SystemClock
import com.example.glasses.audio.GLASSES64_INPUT_TIMEOUT_MS
import com.example.glasses.audio.Glasses64AudioEngine
import com.example.glasses.audio.Glasses64ColumnRequest
import com.example.glasses.ground.GroundFilterFrame
import com.example.glasses.obstacle.Glasses64ImmediateAlertTarget
import com.example.glasses.obstacle.ObstacleGridFrame
import com.example.glasses.obstacle.ObstacleGridProcessor
import com.example.glasses.obstacle.ObstacleGridProcessorStats
import com.example.glasses.obstacle.ProcessedObstacleGridFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class DepthAudioCoordinatorStatus {
    STOPPED,
    WAITING_FOR_FRAME,
    ACTIVE,
    DEPTH_ONLY,
    STALE,
    ERROR,
}

internal data class DepthAudioCoordinatorState(
    val status: DepthAudioCoordinatorStatus = DepthAudioCoordinatorStatus.STOPPED,
    val latestInputTimestampMs: Long = 0L,
    val fitSucceeded: Boolean? = null,
    val activeObstacleCount: Int = 0,
    val lastGridProcessingMs: Double = 0.0,
    val lastSoundscapeRenderMs: Double = 0.0,
    val lastImmediateAlertLatencyMs: Double = 0.0,
    val soundscapeRenderCount: Long = 0L,
    val immediateAlertCount: Long = 0L,
    val errorMessage: String? = null,
)

internal interface DepthAudioOutput : AutoCloseable {
    fun warmUp()

    fun playSoundscape(
        requests: List<Glasses64ColumnRequest>,
        continuePlayback: () -> Boolean,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        onRendered: (Double) -> Unit,
    )

    fun playImmediateObstacleAlert(
        targets: List<Glasses64ImmediateAlertTarget>,
        inputTimestampMs: Long,
        continuePlayback: () -> Boolean,
        onStarted: (Double, Double, Double) -> Unit,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
    )

    fun stop()
}

private class Glasses64DepthAudioOutput(
    context: Context,
) : DepthAudioOutput {
    private val engine = Glasses64AudioEngine(context)

    override fun warmUp() = engine.warmUp()

    override fun playSoundscape(
        requests: List<Glasses64ColumnRequest>,
        continuePlayback: () -> Boolean,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        onRendered: (Double) -> Unit,
    ) {
        engine.playSoundscape(
            requests = requests,
            continuePlayback = continuePlayback,
            onPrepared = {},
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError,
            onRendered = onRendered,
        )
    }

    override fun playImmediateObstacleAlert(
        targets: List<Glasses64ImmediateAlertTarget>,
        inputTimestampMs: Long,
        continuePlayback: () -> Boolean,
        onStarted: (Double, Double, Double) -> Unit,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
    ) {
        engine.playImmediateObstacleAlert(
            targets = targets,
            inputTimestampMs = inputTimestampMs,
            continuePlayback = continuePlayback,
            onStarted = onStarted,
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError,
        )
    }

    override fun stop() = engine.stop()

    override fun close() = engine.close()
}

/**
 * Connects ground-filter occupancy to stable HRTF playback without blocking vision processing.
 */
internal class DepthAudioCoordinator internal constructor(
    private val audioOutput: DepthAudioOutput,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    processorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    coordinatorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val inputTimeoutMs: Long = GLASSES64_INPUT_TIMEOUT_MS,
    private val staleCheckIntervalMs: Long = 50L,
) : AutoCloseable {
    constructor(context: Context) : this(
        audioOutput = Glasses64DepthAudioOutput(context.applicationContext),
    )

    private val processor = ObstacleGridProcessor(processorDispatcher)
    private val scope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mainPlaybackActive = AtomicBoolean(false)
    private val audioGeneration = AtomicLong(0L)
    private val latestFrame = AtomicReference<ProcessedObstacleGridFrame?>(null)

    private val mutableState = MutableStateFlow(DepthAudioCoordinatorState())
    val state: StateFlow<DepthAudioCoordinatorState> = mutableState.asStateFlow()
    val processorStats: StateFlow<ObstacleGridProcessorStats> = processor.stats

    init {
        require(inputTimeoutMs > 0L)
        require(staleCheckIntervalMs > 0L)
        scope.launch {
            processor.latest.filterNotNull().collect(::handleProcessedFrame)
        }
        scope.launch {
            while (true) {
                delay(staleCheckIntervalMs)
                val frame = latestFrame.get()
                if (
                    running.get() &&
                    frame != null &&
                    !isFresh(frame) &&
                    mutableState.value.status != DepthAudioCoordinatorStatus.STALE
                ) {
                    invalidateAudio(DepthAudioCoordinatorStatus.STALE)
                }
            }
        }
    }

    fun warmUp() {
        check(!closed.get()) { "Depth audio coordinator is closed" }
        audioOutput.warmUp()
    }

    fun start() {
        check(!closed.get()) { "Depth audio coordinator is closed" }
        if (!running.compareAndSet(false, true)) return
        audioGeneration.incrementAndGet()
        mainPlaybackActive.set(false)
        latestFrame.set(null)
        processor.reset()
        mutableState.value = DepthAudioCoordinatorState(
            status = DepthAudioCoordinatorStatus.WAITING_FOR_FRAME,
        )
    }

    fun submit(frame: GroundFilterFrame): Boolean {
        if (!running.get() || closed.get()) return false
        return processor.submit(
            ObstacleGridFrame(
                occupancy = frame.obstacleOccupancy,
                distanceMeters = frame.obstacleDistanceMeters,
                timestampMs = frame.timestampMs,
                fitSucceeded = frame.fitSucceeded,
            ),
        )
    }

    private fun handleProcessedFrame(frame: ProcessedObstacleGridFrame) {
        if (!running.get() || closed.get()) return
        latestFrame.set(frame)
        val activeObstacleCount = frame.activeMask.count { it }

        val modeStatus = if (frame.fitSucceeded) {
            DepthAudioCoordinatorStatus.ACTIVE
        } else {
            DepthAudioCoordinatorStatus.DEPTH_ONLY
        }
        mutableState.update {
            it.copy(
                status = modeStatus,
                latestInputTimestampMs = frame.timestampMs,
                fitSucceeded = frame.fitSucceeded,
                activeObstacleCount = activeObstacleCount,
                lastGridProcessingMs = frame.processingMs,
                errorMessage = null,
            )
        }

        if (activeObstacleCount == 0) {
            if (mainPlaybackActive.get()) invalidateAudio(modeStatus)
            return
        }

        // Appearance-based immediate alerts stay disabled until distance thresholds replace them.
        startLatestSoundscapeIfNeeded()
    }

    private fun startLatestSoundscapeIfNeeded() {
        val frame = latestFrame.get() ?: return
        if (!canPlay(frame) || !mainPlaybackActive.compareAndSet(false, true)) return
        val generation = audioGeneration.get()

        try {
            audioOutput.playSoundscape(
                requests = frame.columnRequests,
                continuePlayback = {
                    generation == audioGeneration.get() && canPlay(latestFrame.get())
                },
                onFinished = { finishSoundscapeCycle(generation) },
                onStopped = { finishSoundscapeCycle(generation) },
                onError = { message -> handleAudioError(generation, message) },
                onRendered = { renderMs ->
                    if (generation == audioGeneration.get()) {
                        mutableState.update {
                            it.copy(
                                lastSoundscapeRenderMs = renderMs,
                                soundscapeRenderCount = it.soundscapeRenderCount + 1L,
                            )
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            handleAudioError(generation, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun finishSoundscapeCycle(generation: Long) {
        if (generation != audioGeneration.get()) return
        mainPlaybackActive.set(false)
        startLatestSoundscapeIfNeeded()
    }

    private fun handleAudioError(generation: Long, message: String) {
        if (generation != audioGeneration.get()) return
        mainPlaybackActive.set(false)
        mutableState.update {
            it.copy(
                status = DepthAudioCoordinatorStatus.ERROR,
                errorMessage = message,
            )
        }
    }

    private fun canPlay(frame: ProcessedObstacleGridFrame?): Boolean =
        running.get() && !closed.get() && frame != null && frame.activeMask.any() && isFresh(frame)

    private fun isFresh(frame: ProcessedObstacleGridFrame): Boolean =
        (clock() - frame.timestampMs).coerceAtLeast(0L) <= inputTimeoutMs

    private fun invalidateAudio(status: DepthAudioCoordinatorStatus) {
        audioGeneration.incrementAndGet()
        mainPlaybackActive.set(false)
        audioOutput.stop()
        mutableState.update {
            it.copy(
                status = status,
                errorMessage = null,
            )
        }
    }

    fun stop() {
        running.set(false)
        audioGeneration.incrementAndGet()
        mainPlaybackActive.set(false)
        latestFrame.set(null)
        processor.reset()
        audioOutput.stop()
        mutableState.value = DepthAudioCoordinatorState(
            status = DepthAudioCoordinatorStatus.STOPPED,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scope.cancel()
        processor.close()
        audioOutput.close()
    }
}
