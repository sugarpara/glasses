package com.example.glasses.pipeline

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.glasses.ground.GroundFilterFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DepthAudioCoordinatorInstrumentedTest {
    @Test
    fun syntheticOccupancyStartsRealSoundscapeAndImmediateAlert() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = DepthAudioCoordinator(context)
        try {
            coordinator.warmUp()
            coordinator.start()
            val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(10L)
            while (
                SystemClock.elapsedRealtime() < deadline &&
                (
                    coordinator.state.value.soundscapeRenderCount < 1L ||
                        coordinator.state.value.immediateAlertCount < 1L
                    )
            ) {
                assertTrue(coordinator.submit(obstacleFrame(SystemClock.elapsedRealtime())))
                Thread.sleep(100L)
            }

            val state = coordinator.state.value
            assertEquals(DepthAudioCoordinatorStatus.ACTIVE, state.status)
            assertTrue(state.soundscapeRenderCount >= 1L)
            assertTrue(state.immediateAlertCount >= 1L)
            assertTrue(state.lastSoundscapeRenderMs > 0.0)
        } finally {
            coordinator.close()
        }
    }

    private fun obstacleFrame(timestampMs: Long): GroundFilterFrame {
        val occupancy = FloatArray(64 * 64)
        occupancy[40 * 64 + 32] = 1.0f
        return GroundFilterFrame(
            classMap = null,
            obstacleOccupancy = occupancy,
            width = 640,
            height = 640,
            timestampMs = timestampMs,
            fitSucceeded = true,
            groundFraction = 0.85f,
            obstacleFraction = 0.05f,
            unknownFraction = 0.10f,
            processingMs = 10.0,
        )
    }
}
