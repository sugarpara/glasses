package com.example.glasses.audio

import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Glasses64AudioLifecycleTest {
    @Test
    fun stopReleasesActiveAudioTrackAndWorker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = Glasses64AudioEngine(context)
        val stopped = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        try {
            engine.warmUp()
            engine.playVerticalCalibrationComparison(
                middleHrtfRow = 24,
                lowerHrtfRow = 48,
                onFinished = {},
                onStopped = stopped::countDown,
                onError = error::set,
            )

            val trackReference = audioTrackReference(engine, "currentAudioTrack")
            val workerReference = workerReference(engine, "currentWorker")
            val activeTrack = awaitTrack(trackReference)
            assertEquals(AudioTrack.STATE_INITIALIZED, activeTrack.state)

            engine.stop()

            assertTrue(stopped.await(5L, TimeUnit.SECONDS))
            assertNull(error.get())
            assertNull(trackReference.get())
            assertNull(workerReference.get())
            assertEquals(AudioTrack.STATE_UNINITIALIZED, activeTrack.state)
        } finally {
            engine.close()
        }
    }

    private fun awaitTrack(reference: AtomicReference<AudioTrack?>): AudioTrack {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            reference.get()?.let { return it }
            Thread.sleep(20L)
        }
        throw AssertionError("AudioTrack did not start within 10 seconds")
    }

    @Suppress("UNCHECKED_CAST")
    private fun audioTrackReference(
        engine: Glasses64AudioEngine,
        fieldName: String,
    ): AtomicReference<AudioTrack?> {
        val field = Glasses64AudioEngine::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(engine) as AtomicReference<AudioTrack?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun workerReference(
        engine: Glasses64AudioEngine,
        fieldName: String,
    ): AtomicReference<Thread?> {
        val field = Glasses64AudioEngine::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(engine) as AtomicReference<Thread?>
    }
}
