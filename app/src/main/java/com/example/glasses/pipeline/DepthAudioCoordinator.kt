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
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class DepthAudioCoordinatorStatus {
    STOPPED,
    WAITING_FOR_FRAME,
    ACTIVE,
    DEPTH_ONLY,
    STALE,
    ERROR,
}

internal data class AudioWaveformBar(
    val minimum: Float,
    val maximum: Float,
)

internal data class AudioSpectrumBar(
    val centerFrequencyHz: Float,
    val level: Float,
)

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
    val leftWaveform: List<AudioWaveformBar> = emptyList(),
    val rightWaveform: List<AudioWaveformBar> = emptyList(),
    val leftSpectrum: List<AudioSpectrumBar> = emptyList(),
    val rightSpectrum: List<AudioSpectrumBar> = emptyList(),
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
        onPrepared: (ShortArray, Int) -> Unit,
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
        onPrepared: (ShortArray, Int) -> Unit,
    ) {
        engine.playSoundscape(
            requests = requests,
            continuePlayback = continuePlayback,
            onPrepared = { rendered -> onPrepared(rendered.pcm, rendered.sampleRate) },
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
            mutableState.update {
                it.copy(
                    leftWaveform = emptyList(),
                    rightWaveform = emptyList(),
                    leftSpectrum = emptyList(),
                    rightSpectrum = emptyList(),
                )
            }
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
                onPrepared = { pcm, sampleRate ->
                    scope.launch {
                        if (generation != audioGeneration.get() || !canPlay(latestFrame.get())) {
                            return@launch
                        }
                        val visualization = createAudioVisualization(pcm, sampleRate)
                        if (generation != audioGeneration.get() || !canPlay(latestFrame.get())) {
                            return@launch
                        }
                        mutableState.update {
                            it.copy(
                                leftWaveform = visualization.leftWaveform,
                                rightWaveform = visualization.rightWaveform,
                                leftSpectrum = visualization.leftSpectrum,
                                rightSpectrum = visualization.rightSpectrum,
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
                leftWaveform = emptyList(),
                rightWaveform = emptyList(),
                leftSpectrum = emptyList(),
                rightSpectrum = emptyList(),
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
                leftWaveform = emptyList(),
                rightWaveform = emptyList(),
                leftSpectrum = emptyList(),
                rightSpectrum = emptyList(),
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

    private data class AudioVisualization(
        val leftWaveform: List<AudioWaveformBar>,
        val rightWaveform: List<AudioWaveformBar>,
        val leftSpectrum: List<AudioSpectrumBar>,
        val rightSpectrum: List<AudioSpectrumBar>,
    )

    private fun createAudioVisualization(
        pcm: ShortArray,
        sampleRate: Int,
    ): AudioVisualization {
        val (leftWaveform, rightWaveform) = createStereoWaveforms(pcm)
        return AudioVisualization(
            leftWaveform = leftWaveform,
            rightWaveform = rightWaveform,
            leftSpectrum = createSpectrum(pcm, channel = 0, sampleRate = sampleRate),
            rightSpectrum = createSpectrum(pcm, channel = 1, sampleRate = sampleRate),
        )
    }

    private fun createStereoWaveforms(
        pcm: ShortArray,
        requestedBarCount: Int = WAVEFORM_BAR_COUNT,
    ): Pair<List<AudioWaveformBar>, List<AudioWaveformBar>> {
        val stereoFrameCount = pcm.size / 2
        if (stereoFrameCount == 0) return emptyList<AudioWaveformBar>() to emptyList()

        val barCount = requestedBarCount.coerceIn(1, stereoFrameCount)
        val left = ArrayList<AudioWaveformBar>(barCount)
        val right = ArrayList<AudioWaveformBar>(barCount)
        for (barIndex in 0 until barCount) {
            val startFrame = barIndex * stereoFrameCount / barCount
            val endFrame = ((barIndex + 1) * stereoFrameCount / barCount)
                .coerceAtLeast(startFrame + 1)
            var leftMinimum = Short.MAX_VALUE.toInt()
            var leftMaximum = Short.MIN_VALUE.toInt()
            var rightMinimum = Short.MAX_VALUE.toInt()
            var rightMaximum = Short.MIN_VALUE.toInt()
            for (frameIndex in startFrame until endFrame) {
                val leftSample = pcm[frameIndex * 2].toInt()
                val rightSample = pcm[frameIndex * 2 + 1].toInt()
                if (leftSample < leftMinimum) leftMinimum = leftSample
                if (leftSample > leftMaximum) leftMaximum = leftSample
                if (rightSample < rightMinimum) rightMinimum = rightSample
                if (rightSample > rightMaximum) rightMaximum = rightSample
            }
            left += AudioWaveformBar(
                minimum = leftMinimum / PCM_NORMALIZATION,
                maximum = leftMaximum / PCM_NORMALIZATION,
            )
            right += AudioWaveformBar(
                minimum = rightMinimum / PCM_NORMALIZATION,
                maximum = rightMaximum / PCM_NORMALIZATION,
            )
        }
        return left to right
    }

    private fun createSpectrum(
        pcm: ShortArray,
        channel: Int,
        sampleRate: Int,
    ): List<AudioSpectrumBar> {
        val stereoFrameCount = pcm.size / 2
        if (stereoFrameCount == 0 || sampleRate <= 0) return emptyList()

        val accumulatedPower = DoubleArray(FFT_SIZE / 2 + 1)
        val maximumOffset = (stereoFrameCount - FFT_SIZE).coerceAtLeast(0)
        val windowCount = if (maximumOffset == 0) 1 else FFT_WINDOW_COUNT
        repeat(windowCount) { windowIndex ->
            val offset = if (windowCount == 1) {
                0
            } else {
                windowIndex * maximumOffset / (windowCount - 1)
            }
            val real = DoubleArray(FFT_SIZE)
            val imaginary = DoubleArray(FFT_SIZE)
            for (sampleIndex in 0 until FFT_SIZE) {
                val frameIndex = offset + sampleIndex
                if (frameIndex >= stereoFrameCount) break
                val window = 0.5 - 0.5 * cos(2.0 * PI * sampleIndex / (FFT_SIZE - 1))
                real[sampleIndex] = pcm[frameIndex * 2 + channel] / PCM_NORMALIZATION * window
            }
            fftInPlace(real, imaginary)
            for (bin in 1..FFT_SIZE / 2) {
                accumulatedPower[bin] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }
        }

        val maximumFrequency = minOf(SPECTRUM_MAX_FREQUENCY_HZ, sampleRate / 2f)
        return List(SPECTRUM_BAR_COUNT) { barIndex ->
            val lowerFrequency = maximumFrequency * barIndex / SPECTRUM_BAR_COUNT
            val upperFrequency = maximumFrequency * (barIndex + 1) / SPECTRUM_BAR_COUNT
            val firstBin = max(
                1,
                (lowerFrequency * FFT_SIZE / sampleRate).toInt(),
            ).coerceAtMost(FFT_SIZE / 2)
            val endBinExclusive = ceil(upperFrequency * FFT_SIZE / sampleRate)
                .toInt()
                .coerceIn(firstBin + 1, FFT_SIZE / 2 + 1)
            var peakPower = 0.0
            for (bin in firstBin until endBinExclusive) {
                peakPower = max(peakPower, accumulatedPower[bin] / windowCount)
            }
            val amplitude = 4.0 * sqrt(peakPower) / FFT_SIZE
            val decibels = 20.0 * log10(max(amplitude, MIN_SPECTRUM_AMPLITUDE))
            AudioSpectrumBar(
                centerFrequencyHz = (lowerFrequency + upperFrequency) / 2f,
                level = ((decibels - MIN_SPECTRUM_DECIBELS) / -MIN_SPECTRUM_DECIBELS)
                    .toFloat()
                    .coerceIn(0f, 1f),
            )
        }
    }

    private fun fftInPlace(real: DoubleArray, imaginary: DoubleArray) {
        var reversedIndex = 0
        for (index in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (reversedIndex and bit != 0) {
                reversedIndex = reversedIndex xor bit
                bit = bit shr 1
            }
            reversedIndex = reversedIndex xor bit
            if (index < reversedIndex) {
                val realValue = real[index]
                real[index] = real[reversedIndex]
                real[reversedIndex] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversedIndex]
                imaginary[reversedIndex] = imaginaryValue
            }
        }

        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var blockStart = 0
            while (blockStart < FFT_SIZE) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val evenIndex = blockStart + offset
                    val oddIndex = evenIndex + length / 2
                    val oddReal = real[oddIndex] * twiddleReal -
                        imaginary[oddIndex] * twiddleImaginary
                    val oddImaginary = real[oddIndex] * twiddleImaginary +
                        imaginary[oddIndex] * twiddleReal
                    real[oddIndex] = real[evenIndex] - oddReal
                    imaginary[oddIndex] = imaginary[evenIndex] - oddImaginary
                    real[evenIndex] += oddReal
                    imaginary[evenIndex] += oddImaginary
                    val nextTwiddleReal = twiddleReal * stepReal -
                        twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary +
                        twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
                blockStart += length
            }
            length = length shl 1
        }
    }

    private companion object {
        const val WAVEFORM_BAR_COUNT = 80
        const val PCM_NORMALIZATION = 32_768f
        const val FFT_SIZE = 2_048
        const val FFT_WINDOW_COUNT = 4
        const val SPECTRUM_BAR_COUNT = 48
        const val SPECTRUM_MAX_FREQUENCY_HZ = 16_000f
        const val MIN_SPECTRUM_DECIBELS = -72.0
        const val MIN_SPECTRUM_AMPLITUDE = 1e-9
    }
}
