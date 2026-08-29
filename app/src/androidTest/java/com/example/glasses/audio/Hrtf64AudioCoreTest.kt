package com.example.glasses.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Hrtf64AudioCoreTest {
    @Test
    fun repositoryValidatesProductionMetadataAndBinary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = Hrtf64Repository(context)

        assertEquals(64, repository.metadata.rows)
        assertEquals(64, repository.metadata.columns)
        assertEquals(2, repository.metadata.receivers)
        assertEquals(256, repository.metadata.hrirLength)
        assertEquals(48_000, repository.metadata.sampleRateHz)
        assertEquals("P0020_FreeFieldComp_48kHz.sofa", repository.metadata.sourceSofa)

        val pair = repository.getOriginalReceiverPair(row = 32, column = 32)
        assertEquals(256, pair.receiver0.size)
        assertEquals(256, pair.receiver1.size)
        assertTrue(pair.receiver0.all(Float::isFinite))
        assertTrue(pair.receiver1.all(Float::isFinite))
        assertTrue(pair.receiver0.any { it != 0.0f })
        assertTrue(pair.receiver1.any { it != 0.0f })
    }

    @Test
    fun engineRendersOneSecondStereoPcmWithoutPlayingIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = Hrtf64CalibrationSettings(
            swapChannels = false,
            upperClear = true,
            lowerClear = false,
            pitchPreset = "MEDIUM",
            verticalSoundMode = Glasses64VerticalSoundMode.LEGACY_SIX_BAND,
            personalVerticalEnabled = false,
        )
        val request = Glasses64ColumnRequest(
            column = 32,
            regions = listOf(
                Glasses64VerticalRegion(
                    startRow = 28,
                    endRow = 36,
                    representativeRow = 32,
                    strength = 0.8f,
                ),
            ),
            activeCells = listOf(Glasses64ActiveCell(row = 32, strength = 0.8f)),
        )

        Glasses64AudioEngine(context).use { engine ->
            engine.warmUp()
            val rendered = engine.renderSoundscape(listOf(request), settings)

            assertEquals(48_000, rendered.sampleRate)
            assertEquals(64, rendered.scanUnitCount)
            assertEquals(750, rendered.framesPerScanUnit)
            assertEquals(48_000 * 2, rendered.pcm.size)
            assertEquals(1.0, rendered.durationSeconds, 1.0e-9)
            assertTrue(rendered.pcm.any { it.toInt() != 0 })
        }
    }
}
