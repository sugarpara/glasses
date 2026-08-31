package com.example.glasses.pipeline

import com.example.glasses.audio.Glasses64ColumnRequest
import com.example.glasses.ground.GroundFilterFrame
import com.example.glasses.obstacle.Glasses64ImmediateAlertTarget
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class DepthAudioCoordinatorTest {
    @Test
    fun soundscapeFinishesThenUsesLatestProcessedSnapshot() {
        val clock = AtomicLong(1_000L)
        val output = FakeDepthAudioOutput()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val coordinator = DepthAudioCoordinator(
            audioOutput = output,
            clock = clock::get,
            processorDispatcher = dispatcher,
            coordinatorDispatcher = dispatcher,
        )
        try {
            coordinator.warmUp()
            coordinator.start()
            coordinator.submit(frame(timestampMs = 1_000L, row = 10, column = 5, value = 1f))

            await { output.soundscapeCalls.size == 1 }
            assertEquals(1, output.warmUpCount)
            assertTrue(output.immediateCalls.isEmpty())
            assertEquals(1.0f, output.soundscapeCalls[0].requests[5].activeCells.single().strength, 0f)

            clock.set(1_100L)
            coordinator.submit(frame(timestampMs = 1_100L, value = 0f))
            await { coordinator.state.value.latestInputTimestampMs == 1_100L }
            assertEquals(1, output.soundscapeCalls.size)

            output.finishSoundscape(0)

            await { output.soundscapeCalls.size == 2 }
            assertEquals(0.70f, output.soundscapeCalls[1].requests[5].activeCells.single().strength, 1e-6f)
            assertEquals(DepthAudioCoordinatorStatus.ACTIVE, coordinator.state.value.status)
        } finally {
            coordinator.close()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedGroundFitUsesUnifiedSoundscapeWithoutAnExtraThreeFrameGate() {
        val clock = AtomicLong(2_000L)
        val output = FakeDepthAudioOutput()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val coordinator = DepthAudioCoordinator(
            audioOutput = output,
            clock = clock::get,
            processorDispatcher = dispatcher,
            coordinatorDispatcher = dispatcher,
        )
        try {
            coordinator.start()
            coordinator.submit(frame(timestampMs = 2_000L, fitSucceeded = false, value = 1f))
            await { output.soundscapeCalls.size == 1 }

            assertEquals(DepthAudioCoordinatorStatus.DEPTH_ONLY, coordinator.state.value.status)
            assertEquals(1, output.soundscapeCalls.single().requests.sumOf { it.activeCells.size })
            assertTrue(output.immediateCalls.isEmpty())
        } finally {
            coordinator.close()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun staleInputStopsAudioAndFreshFrameRestartsIt() {
        val clock = AtomicLong(3_000L)
        val output = FakeDepthAudioOutput()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val coordinator = DepthAudioCoordinator(
            audioOutput = output,
            clock = clock::get,
            processorDispatcher = dispatcher,
            coordinatorDispatcher = dispatcher,
            staleCheckIntervalMs = 10L,
        )
        try {
            coordinator.start()
            coordinator.submit(frame(timestampMs = 3_000L, value = 1f))
            await { output.soundscapeCalls.size == 1 }

            clock.set(3_351L)
            await { coordinator.state.value.status == DepthAudioCoordinatorStatus.STALE }
            assertTrue(output.stopCount > 0)

            coordinator.submit(frame(timestampMs = 3_351L, row = 30, column = 40, value = 1f))
            await { output.soundscapeCalls.size == 2 }
            assertEquals(DepthAudioCoordinatorStatus.ACTIVE, coordinator.state.value.status)
        } finally {
            coordinator.close()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun stopAndCloseReleaseAudioAndRejectFurtherFrames() {
        val output = FakeDepthAudioOutput()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val coordinator = DepthAudioCoordinator(
            audioOutput = output,
            clock = { 4_000L },
            processorDispatcher = dispatcher,
            coordinatorDispatcher = dispatcher,
        )
        try {
            coordinator.start()
            coordinator.stop()
            assertEquals(DepthAudioCoordinatorStatus.STOPPED, coordinator.state.value.status)
            assertTrue(output.stopCount > 0)

            coordinator.close()

            assertEquals(1, output.closeCount)
            assertFalse(coordinator.submit(frame(timestampMs = 4_000L, value = 0f)))
        } finally {
            coordinator.close()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun preparedStereoPcmPublishesIndependentLeftAndRightWaveforms() {
        val output = FakeDepthAudioOutput()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val coordinator = DepthAudioCoordinator(
            audioOutput = output,
            clock = { 5_000L },
            processorDispatcher = dispatcher,
            coordinatorDispatcher = dispatcher,
        )
        try {
            coordinator.start()
            coordinator.submit(frame(timestampMs = 5_000L, value = 1f))
            await { output.soundscapeCalls.size == 1 }

            val sampleRate = 48_000
            val pcm = ShortArray(4_096 * 2)
            repeat(pcm.size / 2) { frameIndex ->
                val time = frameIndex.toDouble() / sampleRate
                pcm[frameIndex * 2] = (sin(2.0 * PI * 750.0 * time) * 24_000)
                    .roundToInt()
                    .toShort()
                pcm[frameIndex * 2 + 1] = (sin(2.0 * PI * 6_000.0 * time) * 4_000)
                    .roundToInt()
                    .toShort()
            }
            output.prepareSoundscape(0, pcm, sampleRate)

            await {
                coordinator.state.value.leftWaveform.isNotEmpty() &&
                    coordinator.state.value.leftSpectrum.isNotEmpty()
            }
            val state = coordinator.state.value
            assertEquals(state.leftWaveform.size, state.rightWaveform.size)
            assertTrue(state.leftWaveform.size in 1..80)
            val leftPeak = state.leftWaveform.maxOf { maxOf(abs(it.minimum), abs(it.maximum)) }
            val rightPeak = state.rightWaveform.maxOf { maxOf(abs(it.minimum), abs(it.maximum)) }
            assertTrue(leftPeak > rightPeak * 5f)
            val leftFrequency = state.leftSpectrum.maxBy { it.level }.centerFrequencyHz
            val rightFrequency = state.rightSpectrum.maxBy { it.level }.centerFrequencyHz
            assertTrue(leftFrequency in 650f..1_000f)
            assertTrue(rightFrequency in 5_800f..6_400f)
        } finally {
            coordinator.close()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun frame(
        timestampMs: Long,
        fitSucceeded: Boolean = true,
        row: Int = 0,
        column: Int = 0,
        value: Float,
    ): GroundFilterFrame {
        val occupancy = FloatArray(64 * 64)
        occupancy[row * 64 + column] = value
        val distance = FloatArray(64 * 64)
        if (value > 0.0f) distance[row * 64 + column] = 1.5f
        return GroundFilterFrame(
            classMap = null,
            obstacleOccupancy = occupancy,
            obstacleDistanceMeters = distance,
            width = 640,
            height = 640,
            timestampMs = timestampMs,
            fitSucceeded = fitSucceeded,
            groundFraction = if (fitSucceeded) 0.8f else 0f,
            obstacleFraction = if (value > 0f) 0.1f else 0f,
            unknownFraction = if (fitSucceeded) 0.1f else 1f,
            processingMs = 10.0,
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5L)
        assertTrue("Condition was not met before timeout", condition())
    }
}

private class FakeDepthAudioOutput : DepthAudioOutput {
    data class SoundscapeCall(
        val requests: List<Glasses64ColumnRequest>,
        val onFinished: () -> Unit,
        val onStopped: () -> Unit,
        val onPrepared: (ShortArray, Int) -> Unit,
    )

    data class ImmediateCall(
        val targets: List<Glasses64ImmediateAlertTarget>,
        val inputTimestampMs: Long,
    )

    val soundscapeCalls = CopyOnWriteArrayList<SoundscapeCall>()
    val immediateCalls = CopyOnWriteArrayList<ImmediateCall>()

    @Volatile var warmUpCount = 0
    @Volatile var stopCount = 0
    @Volatile var closeCount = 0

    override fun warmUp() {
        warmUpCount++
    }

    override fun playSoundscape(
        requests: List<Glasses64ColumnRequest>,
        continuePlayback: () -> Boolean,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        onRendered: (Double) -> Unit,
        onPrepared: (ShortArray, Int) -> Unit,
    ) {
        soundscapeCalls += SoundscapeCall(requests, onFinished, onStopped, onPrepared)
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
        immediateCalls += ImmediateCall(targets, inputTimestampMs)
    }

    fun finishSoundscape(index: Int) {
        soundscapeCalls[index].onFinished()
    }

    fun prepareSoundscape(index: Int, pcm: ShortArray, sampleRate: Int) {
        soundscapeCalls[index].onPrepared(pcm, sampleRate)
    }

    override fun stop() {
        stopCount++
    }

    override fun close() {
        closeCount++
    }
}
