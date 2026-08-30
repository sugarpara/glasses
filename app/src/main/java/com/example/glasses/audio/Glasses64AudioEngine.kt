package com.example.glasses.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.glasses.obstacle.Glasses64ImmediateAlertTarget
import com.example.glasses.obstacle.IMMEDIATE_OBSTACLE_ALERT_MAX_TARGETS
import com.example.glasses.obstacle.OBSTACLE_EMERGENCY_DISTANCE_METERS
import com.example.glasses.obstacle.OBSTACLE_ENTER_DISTANCE_METERS
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal const val HRTF64_PREFS_NAME = "hrtf_calibration_v2"
internal const val HRTF64_VERTICAL_SOUND_MODE_KEY = "vertical_sound_mode"
internal const val HRTF64_PERSONAL_VERTICAL_ENABLED_KEY = "personal_vertical_enabled"
internal const val HRTF64_PERSONAL_MIDDLE_ROW_KEY = "personal_vertical_middle_hrtf_row"
internal const val HRTF64_PERSONAL_LOWER_ROW_KEY = "personal_vertical_lower_hrtf_row"
internal const val HRTF64_DEFAULT_MIDDLE_HRTF_ROW = 36
internal const val HRTF64_DEFAULT_LOWER_HRTF_ROW = 63
internal const val HRTF64_VISUAL_MIDDLE_ANCHOR_ROW = 32
internal const val HRTF64_VISUAL_LOWER_ANCHOR_ROW = 54
internal const val HRTF64_LOG_PITCH_TOP_HZ = 5_000.0
internal const val HRTF64_LOG_PITCH_BOTTOM_HZ = 400.0
internal const val HRTF64_ENHANCED_MIDDLE_TOP_HZ = 1_800.0
internal const val HRTF64_ENHANCED_MIDDLE_BOTTOM_HZ = 1_200.0
internal const val HRTF64_ENHANCED_LOWER_TOP_HZ = 800.0
internal const val HRTF64_ENHANCED_LOWER_BOTTOM_HZ = 500.0
internal const val HRTF64_ENHANCED_TOP_END_ROW = 20
internal const val HRTF64_ENHANCED_MIDDLE_END_ROW = 43
internal const val HRTF64_CATEGORICAL_TOP_HZ = 4_200.0
internal const val HRTF64_CATEGORICAL_UPPER_BOTTOM_HZ = 3_000.0
internal const val HRTF64_CATEGORICAL_MIDDLE_TOP_HZ = 1_700.0
internal const val HRTF64_CATEGORICAL_MIDDLE_BOTTOM_HZ = 1_100.0
internal const val HRTF64_CATEGORICAL_LOWER_TOP_HZ = 650.0
internal const val HRTF64_CATEGORICAL_BOTTOM_HZ = 320.0
private const val HRTF64_OUTPUT_EDGE_FADE_SECONDS = 0.001
private const val HRTF64_SOURCE_FADE_SECONDS = 0.002
private const val HRTF64_CELL_PEAK = 0.56f
private const val HRTF64_MIX_PEAK_LIMIT = 0.92f
private const val HRTF64_ENHANCED_CARRIER_LOW_HZ = 2_500.0
private const val HRTF64_ENHANCED_CARRIER_HIGH_HZ = 6_000.0
private const val HRTF64_CATEGORICAL_CARRIER_HIGH_HZ = 5_000.0
private const val HRTF64_UPPER_AUDIBILITY_GAIN = 1.55f
private const val HRTF64_ENHANCED_LOWER_GAIN = 1.26f
private const val HRTF64_CATEGORICAL_MIDDLE_GAIN = 1.12f
private const val HRTF64_CATEGORICAL_LOWER_GAIN = 1.32f
private const val HRTF64_CALIBRATION_PROBE_SECONDS = 0.62
private const val HRTF64_CALIBRATION_GAP_SECONDS = 0.20
private const val HRTF64_CALIBRATION_FADE_SECONDS = 0.025
private const val HRTF64_CALIBRATION_PROBE_PEAK = 0.46f
private const val HRTF64_CALIBRATION_COLUMN = 32
private const val HRTF64_CALIBRATION_LOW_HZ = 1_000.0
private const val HRTF64_CALIBRATION_HIGH_HZ = 7_000.0
private const val HRTF64_IMMEDIATE_ALERT_SECONDS = 0.18
private const val HRTF64_IMMEDIATE_ALERT_PEAK = 0.46f
private const val HRTF64_BACKDROP_DUCK_VOLUME = 0.32f

internal enum class Glasses64VerticalSoundMode(
    val shortLabel: String,
    val uiLabel: String
) {
    LEGACY_SIX_BAND("A", "A 原6档"),
    LOG_64_ROW("B", "B 区域频率"),
    LOG_EACH_CELL("C", "C 白格频率"),
    REGION_ENHANCED("D", "D 分区增强"),
    REGION_CATEGORICAL("E", "E 分类声音");

    companion object {
        fun fromPreference(value: String?): Glasses64VerticalSoundMode =
            entries.firstOrNull { it.name == value } ?: LEGACY_SIX_BAND
    }
}

internal fun glasses64LogPitchHz(row: Int): Double {
    val position = row.coerceIn(0, GLASSES64_ROWS - 1).toDouble() /
        (GLASSES64_ROWS - 1).toDouble()
    return HRTF64_LOG_PITCH_TOP_HZ *
        (HRTF64_LOG_PITCH_BOTTOM_HZ / HRTF64_LOG_PITCH_TOP_HZ).pow(position)
}

internal fun glasses64EnhancedPitchHz(row: Int): Double {
    val clampedRow = row.coerceIn(0, GLASSES64_ROWS - 1)
    return when {
        clampedRow <= HRTF64_ENHANCED_TOP_END_ROW -> logInterpolatePitch(
            row = clampedRow,
            startRow = 0,
            endRow = HRTF64_ENHANCED_TOP_END_ROW,
            startHz = HRTF64_LOG_PITCH_TOP_HZ,
            endHz = 2_600.0
        )
        clampedRow <= HRTF64_ENHANCED_MIDDLE_END_ROW -> logInterpolatePitch(
            row = clampedRow,
            startRow = HRTF64_ENHANCED_TOP_END_ROW + 1,
            endRow = HRTF64_ENHANCED_MIDDLE_END_ROW,
            startHz = HRTF64_ENHANCED_MIDDLE_TOP_HZ,
            endHz = HRTF64_ENHANCED_MIDDLE_BOTTOM_HZ
        )
        else -> logInterpolatePitch(
            row = clampedRow,
            startRow = HRTF64_ENHANCED_MIDDLE_END_ROW + 1,
            endRow = GLASSES64_ROWS - 1,
            startHz = HRTF64_ENHANCED_LOWER_TOP_HZ,
            endHz = HRTF64_ENHANCED_LOWER_BOTTOM_HZ
        )
    }
}

private fun logInterpolatePitch(
    row: Int,
    startRow: Int,
    endRow: Int,
    startHz: Double,
    endHz: Double
): Double {
    val position = if (endRow == startRow) {
        0.0
    } else {
        (row - startRow).toDouble() / (endRow - startRow).toDouble()
    }.coerceIn(0.0, 1.0)
    return startHz * (endHz / startHz).pow(position)
}

internal fun glasses64EnhancedCarrierRatio(row: Int): Float = when {
    row <= HRTF64_ENHANCED_TOP_END_ROW -> 0.25f
    row <= HRTF64_ENHANCED_MIDDLE_END_ROW -> 0.30f
    else -> 0.40f
}

internal fun glasses64CategoricalPitchHz(row: Int): Double {
    val clampedRow = row.coerceIn(0, GLASSES64_ROWS - 1)
    return when {
        clampedRow <= HRTF64_ENHANCED_TOP_END_ROW -> logInterpolatePitch(
            row = clampedRow,
            startRow = 0,
            endRow = HRTF64_ENHANCED_TOP_END_ROW,
            startHz = HRTF64_CATEGORICAL_TOP_HZ,
            endHz = HRTF64_CATEGORICAL_UPPER_BOTTOM_HZ
        )
        clampedRow <= HRTF64_ENHANCED_MIDDLE_END_ROW -> logInterpolatePitch(
            row = clampedRow,
            startRow = HRTF64_ENHANCED_TOP_END_ROW + 1,
            endRow = HRTF64_ENHANCED_MIDDLE_END_ROW,
            startHz = HRTF64_CATEGORICAL_MIDDLE_TOP_HZ,
            endHz = HRTF64_CATEGORICAL_MIDDLE_BOTTOM_HZ
        )
        else -> logInterpolatePitch(
            row = clampedRow,
            startRow = HRTF64_ENHANCED_MIDDLE_END_ROW + 1,
            endRow = GLASSES64_ROWS - 1,
            startHz = HRTF64_CATEGORICAL_LOWER_TOP_HZ,
            endHz = HRTF64_CATEGORICAL_BOTTOM_HZ
        )
    }
}

internal fun glasses64CategoricalCarrierRatio(row: Int): Float = when {
    row <= HRTF64_ENHANCED_TOP_END_ROW -> 0.20f
    row <= HRTF64_ENHANCED_MIDDLE_END_ROW -> 0.24f
    else -> 0.20f
}

internal fun glasses64ModeOutputGain(
    mode: Glasses64VerticalSoundMode,
    row: Int
): Float = when (mode) {
    Glasses64VerticalSoundMode.LOG_EACH_CELL,
    Glasses64VerticalSoundMode.REGION_ENHANCED -> when {
        row <= HRTF64_ENHANCED_TOP_END_ROW -> HRTF64_UPPER_AUDIBILITY_GAIN
        mode == Glasses64VerticalSoundMode.REGION_ENHANCED &&
            row > HRTF64_ENHANCED_MIDDLE_END_ROW -> HRTF64_ENHANCED_LOWER_GAIN
        else -> 1f
    }
    Glasses64VerticalSoundMode.REGION_CATEGORICAL -> when {
        row <= HRTF64_ENHANCED_TOP_END_ROW -> HRTF64_UPPER_AUDIBILITY_GAIN
        row <= HRTF64_ENHANCED_MIDDLE_END_ROW -> HRTF64_CATEGORICAL_MIDDLE_GAIN
        else -> HRTF64_CATEGORICAL_LOWER_GAIN
    }
    else -> 1f
}

internal data class Hrtf64CalibrationSettings(
    val swapChannels: Boolean,
    val upperClear: Boolean,
    val lowerClear: Boolean,
    val pitchPreset: String,
    val verticalSoundMode: Glasses64VerticalSoundMode,
    val personalVerticalEnabled: Boolean = true,
    val personalMiddleHrtfRow: Int = HRTF64_DEFAULT_MIDDLE_HRTF_ROW,
    val personalLowerHrtfRow: Int = HRTF64_DEFAULT_LOWER_HRTF_ROW
)

internal fun glasses64MapVisualRowToPersonalHrtfRow(
    visualRow: Int,
    middleHrtfRow: Int,
    lowerHrtfRow: Int
): Int {
    val visual = visualRow.coerceIn(0, GLASSES64_ROWS - 1)
    val middle = middleHrtfRow.coerceIn(1, GLASSES64_ROWS - 2)
    val lower = lowerHrtfRow
        .coerceIn(middle + 1, GLASSES64_ROWS - 1)

    return when {
        visual <= HRTF64_VISUAL_MIDDLE_ANCHOR_ROW -> interpolateHrtfRow(
            value = visual,
            inputStart = 0,
            inputEnd = HRTF64_VISUAL_MIDDLE_ANCHOR_ROW,
            outputStart = 0,
            outputEnd = middle
        )
        visual <= HRTF64_VISUAL_LOWER_ANCHOR_ROW -> interpolateHrtfRow(
            value = visual,
            inputStart = HRTF64_VISUAL_MIDDLE_ANCHOR_ROW,
            inputEnd = HRTF64_VISUAL_LOWER_ANCHOR_ROW,
            outputStart = middle,
            outputEnd = lower
        )
        else -> interpolateHrtfRow(
            value = visual,
            inputStart = HRTF64_VISUAL_LOWER_ANCHOR_ROW,
            inputEnd = GLASSES64_ROWS - 1,
            outputStart = lower,
            outputEnd = GLASSES64_ROWS - 1
        )
    }.coerceIn(0, GLASSES64_ROWS - 1)
}

private fun interpolateHrtfRow(
    value: Int,
    inputStart: Int,
    inputEnd: Int,
    outputStart: Int,
    outputEnd: Int
): Int {
    if (inputStart == inputEnd) return outputEnd
    val position = (value - inputStart).toDouble() / (inputEnd - inputStart).toDouble()
    return (outputStart + (outputEnd - outputStart) * position)
        .roundToInt()
}

internal data class RenderedGlasses64Soundscape(
    /** Final interleaved stereo PCM16, exactly as passed to AudioTrack. */
    val pcm: ShortArray,
    val sampleRate: Int,
    val scanUnitCount: Int,
    val framesPerScanUnit: Int
) {
    val durationSeconds: Double
        get() = pcm.size.toDouble() / 2.0 / sampleRate.toDouble()
}

internal data class Glasses64CalibrationProbe(
    val hrtfRow: Int,
    val hrtfColumn: Int,
    val pitchHz: Double,
    val toneRatio: Float,
    val durationSeconds: Double
)

/**
 * Renders all 64 spatial columns in one second. Each column is 750 frames
 * (15.625 ms) at 48 kHz. A 256-sample HRIR is about 5.33 ms, so the full
 * convolution tail fits inside the scan unit. Adjacent units are forced to
 * zero with a short output fade to avoid boundary clicks.
 */
internal class Glasses64AudioEngine(
    context: Context
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Hrtf64Repository(appContext)
    }
    private val currentAudioTrack = AtomicReference<AudioTrack?>(null)
    private val currentWorker = AtomicReference<Thread?>(null)
    private val currentAlertAudioTrack = AtomicReference<AudioTrack?>(null)
    private val currentAlertWorker = AtomicReference<Thread?>(null)

    @Volatile
    private var closed = false

    fun warmUp() {
        check(!closed) { "64x64 HRTF audio engine is closed" }
        repository.metadata
    }

    fun loadCalibrationSettings(): Hrtf64CalibrationSettings {
        val preferences = appContext.getSharedPreferences(HRTF64_PREFS_NAME, Context.MODE_PRIVATE)
        return Hrtf64CalibrationSettings(
            swapChannels = preferences.getBoolean("swap_channels", true),
            upperClear = preferences.getBoolean("upper_clear", true),
            lowerClear = preferences.getBoolean("lower_clear", false),
            pitchPreset = preferences.getString("pitch_preset", "MEDIUM") ?: "MEDIUM",
            verticalSoundMode = Glasses64VerticalSoundMode.fromPreference(
                preferences.getString(HRTF64_VERTICAL_SOUND_MODE_KEY, null)
            ),
            personalVerticalEnabled = preferences.getBoolean(
                HRTF64_PERSONAL_VERTICAL_ENABLED_KEY,
                true
            ),
            personalMiddleHrtfRow = preferences.getInt(
                HRTF64_PERSONAL_MIDDLE_ROW_KEY,
                HRTF64_DEFAULT_MIDDLE_HRTF_ROW
            ).coerceIn(1, GLASSES64_ROWS - 2),
            personalLowerHrtfRow = preferences.getInt(
                HRTF64_PERSONAL_LOWER_ROW_KEY,
                HRTF64_DEFAULT_LOWER_HRTF_ROW
            ).coerceIn(2, GLASSES64_ROWS - 1)
        )
    }

    fun playVerticalCalibrationProbe(
        hrtfRow: Int,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        val settings = loadCalibrationSettings()
        startWorker(
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError
        ) {
            val pcm = renderVerticalCalibrationProbe(
                hrtfRow = hrtfRow,
                settings = settings
            )
            playPcmBlocking(pcm) { true }
        }
    }

    fun playVerticalCalibrationComparison(
        middleHrtfRow: Int,
        lowerHrtfRow: Int,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        val settings = loadCalibrationSettings()
        startWorker(
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError
        ) {
            val middle = renderVerticalCalibrationProbe(middleHrtfRow, settings)
            val lower = renderVerticalCalibrationProbe(lowerHrtfRow, settings)
            val gap = ShortArray(
                (GLASSES64_SAMPLE_RATE * HRTF64_CALIBRATION_GAP_SECONDS).toInt() * 2
            )
            val pcm = ShortArray(middle.size + gap.size + lower.size)
            middle.copyInto(pcm)
            gap.copyInto(pcm, middle.size)
            lower.copyInto(pcm, middle.size + gap.size)
            playPcmBlocking(pcm) { true }
        }
    }

    fun playCalibrationSequence(
        probes: List<Glasses64CalibrationProbe>,
        gapSeconds: Double = 0.15,
        swapChannelsOverride: Boolean? = null,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        require(probes.isNotEmpty()) { "Calibration sequence must not be empty" }
        val snapshot = probes.map { probe ->
            require(probe.hrtfRow in 0 until GLASSES64_ROWS) {
                "HRTF row must be in 0..${GLASSES64_ROWS - 1}"
            }
            require(probe.hrtfColumn in 0 until GLASSES64_COLUMNS) {
                "HRTF column must be in 0..${GLASSES64_COLUMNS - 1}"
            }
            require(probe.pitchHz > 0.0) { "Calibration pitch must be positive" }
            require(probe.toneRatio in 0f..1f) { "Calibration tone ratio must be in 0..1" }
            require(probe.durationSeconds in 0.1..2.0) {
                "Calibration duration must be in 0.1..2.0 seconds"
            }
            probe.copy()
        }
        val loadedSettings = loadCalibrationSettings()
        val settings = if (swapChannelsOverride == null) {
            loadedSettings
        } else {
            loadedSettings.copy(swapChannels = swapChannelsOverride)
        }
        val safeGapSeconds = gapSeconds.coerceIn(0.0, 1.0)

        startWorker(
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError
        ) {
            val rendered = snapshot.map { probe ->
                renderCalibrationProbe(probe, settings)
            }
            val gap = ShortArray(
                (GLASSES64_SAMPLE_RATE * safeGapSeconds).toInt() * 2
            )
            val totalShortCount = rendered.sumOf { it.size } +
                gap.size * (rendered.size - 1).coerceAtLeast(0)
            val pcm = ShortArray(totalShortCount)
            var offset = 0
            rendered.forEachIndexed { index, probePcm ->
                probePcm.copyInto(pcm, destinationOffset = offset)
                offset += probePcm.size
                if (index < rendered.lastIndex) offset += gap.size
            }
            playPcmBlocking(pcm) { true }
        }
    }

    fun playSoundscape(
        requests: List<Glasses64ColumnRequest>,
        continuePlayback: () -> Boolean,
        onPrepared: (RenderedGlasses64Soundscape) -> Unit,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        onRendered: (Double) -> Unit = {}
    ) {
        val snapshot = requests.map { request ->
            request.copy(
                regions = request.regions.map { it.copy() },
                activeCells = request.activeCells.map { it.copy() }
            )
        }
        val settings = loadCalibrationSettings()

        startWorker(
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError
        ) {
            val renderStartNs = SystemClock.elapsedRealtimeNanos()
            val rendered = renderSoundscape(snapshot, settings)
            val renderTimeMs =
                (SystemClock.elapsedRealtimeNanos() - renderStartNs) / 1_000_000.0
            mainHandler.post { onRendered(renderTimeMs) }
            mainHandler.post { onPrepared(rendered) }
            playPcmBlocking(rendered.pcm, continuePlayback)
        }
    }

    fun playImmediateObstacleAlert(
        targets: List<Glasses64ImmediateAlertTarget>,
        inputTimestampMs: Long,
        continuePlayback: () -> Boolean,
        onStarted: (renderTimeMs: Double, totalLatencyMs: Double, durationSeconds: Double) -> Unit,
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (targets.isEmpty()) return
        val snapshot = targets
            .take(IMMEDIATE_OBSTACLE_ALERT_MAX_TARGETS)
            .map { it.copy() }
        val settings = loadCalibrationSettings()

        startImmediateAlertWorker(
            onFinished = onFinished,
            onStopped = onStopped,
            onError = onError
        ) {
            val renderStartNs = SystemClock.elapsedRealtimeNanos()
            val pcm = renderImmediateObstacleAlert(snapshot, settings)
            val renderTimeMs =
                (SystemClock.elapsedRealtimeNanos() - renderStartNs) / 1_000_000.0
            playImmediatePcmBlocking(
                pcm = pcm,
                continuePlayback = continuePlayback,
                onPlaybackStarted = {
                    val totalLatencyMs =
                        (SystemClock.elapsedRealtime() - inputTimestampMs).coerceAtLeast(0L)
                            .toDouble()
                    mainHandler.post {
                        onStarted(
                            renderTimeMs,
                            totalLatencyMs,
                            pcm.size.toDouble() / 2.0 / GLASSES64_SAMPLE_RATE.toDouble()
                        )
                    }
                }
            )
        }
    }

    private fun renderImmediateObstacleAlert(
        targets: List<Glasses64ImmediateAlertTarget>,
        settings: Hrtf64CalibrationSettings
    ): ShortArray {
        check(!closed) { "64x64 HRTF audio engine is closed" }
        val frameCount = (GLASSES64_SAMPLE_RATE * HRTF64_IMMEDIATE_ALERT_SECONDS)
            .roundToInt()
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        val targetGain = 1f / sqrt(targets.size.coerceAtLeast(1).toFloat())

        for (target in targets) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val pair = renderCategoricalRegion(
                region = Glasses64VerticalRegion(
                    startRow = target.row,
                    endRow = target.row,
                    representativeRow = target.row,
                    strength = target.strength,
                    distanceMeters = OBSTACLE_EMERGENCY_DISTANCE_METERS,
                ),
                column = target.column,
                settings = settings,
                frameCount = frameCount
            )
            for (frame in 0 until frameCount) {
                left[frame] += pair.first[frame] * targetGain
                right[frame] += pair.second[frame] * targetGain
            }
        }

        applyBoundaryFade(left, right)
        normalizeStereoPairInPlace(left, right, HRTF64_IMMEDIATE_ALERT_PEAK)
        return floatsToStereoPcm(left, right)
    }

    internal fun renderSoundscape(
        requests: List<Glasses64ColumnRequest>,
        settings: Hrtf64CalibrationSettings = loadCalibrationSettings()
    ): RenderedGlasses64Soundscape {
        check(!closed) { "64x64 HRTF audio engine is closed" }
        val framesPerUnit =
            (GLASSES64_SAMPLE_RATE * GLASSES64_SCAN_UNIT_DURATION_SECONDS).toInt()
        check(framesPerUnit * GLASSES64_SCAN_UNIT_COUNT ==
            (GLASSES64_SAMPLE_RATE * TOTAL_SCAN_DURATION_SECONDS).toInt()) {
            "The one-second scan must divide into exact 64-column frame blocks"
        }

        val left = FloatArray(framesPerUnit * GLASSES64_SCAN_UNIT_COUNT)
        val right = FloatArray(left.size)
        val byColumn = requests.associateBy { it.column }

        for (column in 0 until GLASSES64_COLUMNS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val request = byColumn[column] ?: continue
            val columnPair = when (settings.verticalSoundMode) {
                Glasses64VerticalSoundMode.LOG_EACH_CELL -> {
                    if (request.activeCells.isEmpty()) continue
                    renderActiveCellColumn(
                        activeCells = request.activeCells,
                        column = column,
                        settings = settings,
                        frameCount = framesPerUnit
                    )
                }
                Glasses64VerticalSoundMode.REGION_ENHANCED -> {
                    val regions = request.regions.take(GLASSES64_MAX_REGIONS_PER_COLUMN)
                    if (regions.isEmpty()) continue
                    renderEnhancedRegionColumn(
                        request = request,
                        regions = regions,
                        column = column,
                        settings = settings,
                        frameCount = framesPerUnit
                    )
                }
                Glasses64VerticalSoundMode.REGION_CATEGORICAL -> {
                    val regions = request.regions.take(GLASSES64_MAX_REGIONS_PER_COLUMN)
                    if (regions.isEmpty()) continue
                    renderCategoricalRegionColumn(
                        regions = regions,
                        column = column,
                        settings = settings,
                        frameCount = framesPerUnit
                    )
                }
                else -> {
                    val regions = request.regions.take(GLASSES64_MAX_REGIONS_PER_COLUMN)
                    if (regions.isEmpty()) continue
                    val columnLeft = FloatArray(framesPerUnit)
                    val columnRight = FloatArray(framesPerUnit)
                    val regionGain = 1f / sqrt(regions.size.toFloat())

                    for (region in regions) {
                        val pair = renderRegion(
                            row = region.representativeRow,
                            column = column,
                            strength = region.strength,
                            distanceMeters = region.distanceMeters,
                            settings = settings,
                            frameCount = framesPerUnit
                        )
                        for (frame in 0 until framesPerUnit) {
                            columnLeft[frame] += pair.first[frame] * regionGain
                            columnRight[frame] += pair.second[frame] * regionGain
                        }
                    }
                    columnLeft to columnRight
                }
            }

            applyBoundaryFade(columnPair.first, columnPair.second)
            val destination = column * framesPerUnit
            columnPair.first.copyInto(left, destination)
            columnPair.second.copyInto(right, destination)
        }

        limitStereoPairInPlace(left, right, HRTF64_MIX_PEAK_LIMIT)
        return RenderedGlasses64Soundscape(
            pcm = floatsToStereoPcm(left, right),
            sampleRate = GLASSES64_SAMPLE_RATE,
            scanUnitCount = GLASSES64_SCAN_UNIT_COUNT,
            framesPerScanUnit = framesPerUnit
        )
    }

    private fun personalHrtfRow(
        visualRow: Int,
        settings: Hrtf64CalibrationSettings
    ): Int {
        if (!settings.personalVerticalEnabled) {
            return visualRow.coerceIn(0, GLASSES64_ROWS - 1)
        }
        return glasses64MapVisualRowToPersonalHrtfRow(
            visualRow = visualRow,
            middleHrtfRow = settings.personalMiddleHrtfRow,
            lowerHrtfRow = settings.personalLowerHrtfRow
        )
    }

    private fun renderVerticalCalibrationProbe(
        hrtfRow: Int,
        settings: Hrtf64CalibrationSettings
    ): ShortArray {
        val row = hrtfRow.coerceIn(0, GLASSES64_ROWS - 1)
        val frameCount = (GLASSES64_SAMPLE_RATE * HRTF64_CALIBRATION_PROBE_SECONDS).toInt()
        val sourceFrameCount = (frameCount - repository.metadata.hrirLength + 1)
            .coerceAtLeast(1)
        val original = repository.getOriginalReceiverPair(row, HRTF64_CALIBRATION_COLUMN)
        val leftHrir = if (settings.swapChannels) original.receiver1 else original.receiver0
        val rightHrir = if (settings.swapChannels) original.receiver0 else original.receiver1

        // Every probe uses the same source. Only the HRTF row changes, so pitch cannot bias
        // the user's middle/lower judgement.
        val source = generateBandLimitedCarrier(
            row = 0,
            column = 0,
            sampleCount = sourceFrameCount,
            lowHz = HRTF64_CALIBRATION_LOW_HZ,
            highHz = HRTF64_CALIBRATION_HIGH_HZ
        )
        val left = convolveToLength(source, leftHrir, frameCount)
        val right = convolveToLength(source, rightHrir, frameCount)
        normalizeStereoPairInPlace(left, right, HRTF64_CALIBRATION_PROBE_PEAK)
        applyCalibrationFade(left, right)
        return floatsToStereoPcm(left, right)
    }

    private fun renderCalibrationProbe(
        probe: Glasses64CalibrationProbe,
        settings: Hrtf64CalibrationSettings
    ): ShortArray {
        val frameCount = (GLASSES64_SAMPLE_RATE * probe.durationSeconds).toInt()
        val sourceFrameCount = (frameCount - repository.metadata.hrirLength + 1)
            .coerceAtLeast(1)
        val original = repository.getOriginalReceiverPair(probe.hrtfRow, probe.hrtfColumn)
        val leftHrir = if (settings.swapChannels) original.receiver1 else original.receiver0
        val rightHrir = if (settings.swapChannels) original.receiver0 else original.receiver1
        val carrier = generateBandLimitedCarrier(
            row = probe.hrtfRow,
            column = probe.hrtfColumn,
            sampleCount = sourceFrameCount,
            lowHz = HRTF64_CALIBRATION_LOW_HZ,
            highHz = HRTF64_CALIBRATION_HIGH_HZ
        )
        val toneRatio = probe.toneRatio.coerceIn(0f, 1f)
        val noiseRatio = 1f - toneRatio
        val source = FloatArray(sourceFrameCount) { index ->
            val time = index.toDouble() / GLASSES64_SAMPLE_RATE.toDouble()
            val tone = sin(2.0 * PI * probe.pitchHz * time).toFloat()
            carrier[index] * noiseRatio + tone * toneRatio
        }
        val left = convolveToLength(source, leftHrir, frameCount)
        val right = convolveToLength(source, rightHrir, frameCount)
        normalizeStereoPairInPlace(left, right, HRTF64_CALIBRATION_PROBE_PEAK)
        applyCalibrationFade(left, right)
        return floatsToStereoPcm(left, right)
    }

    private fun applyCalibrationFade(left: FloatArray, right: FloatArray) {
        val fadeFrames = (GLASSES64_SAMPLE_RATE * HRTF64_CALIBRATION_FADE_SECONDS)
            .toInt()
            .coerceAtMost(left.size / 2)
            .coerceAtLeast(1)
        for (index in 0 until fadeFrames) {
            val phase = index.toDouble() / (fadeFrames - 1).coerceAtLeast(1).toDouble()
            val gain = (0.5 - 0.5 * kotlin.math.cos(PI * phase)).toFloat()
            val endIndex = left.lastIndex - index
            left[index] *= gain
            right[index] *= gain
            left[endIndex] *= gain
            right[endIndex] *= gain
        }
    }

    private fun renderRegion(
        row: Int,
        column: Int,
        strength: Float,
        distanceMeters: Float,
        settings: Hrtf64CalibrationSettings,
        frameCount: Int
    ): Pair<FloatArray, FloatArray> {
        require(row in 0 until GLASSES64_ROWS)
        require(column in 0 until GLASSES64_COLUMNS)
        val hrtfRow = personalHrtfRow(row, settings)
        val original = repository.getOriginalReceiverPair(hrtfRow, column)
        val leftHrir = if (settings.swapChannels) original.receiver1 else original.receiver0
        val rightHrir = if (settings.swapChannels) original.receiver0 else original.receiver1
        val sourceFrameCount = (frameCount - repository.metadata.hrirLength + 1).coerceAtLeast(1)
        val source = generateSource(
            row = row,
            column = column,
            settings = settings,
            sampleCount = sourceFrameCount
        )
        val left = convolveToLength(source, leftHrir, frameCount)
        val right = convolveToLength(source, rightHrir, frameCount)
        val strengthGain = 0.65f + 0.35f * strength.coerceIn(0f, 1f)
        val distanceGain = glasses64DistanceGain(distanceMeters)
        normalizeStereoPairInPlace(left, right, HRTF64_CELL_PEAK * strengthGain * distanceGain)
        return left to right
    }

    private fun renderActiveCellColumn(
        activeCells: List<Glasses64ActiveCell>,
        column: Int,
        settings: Hrtf64CalibrationSettings,
        frameCount: Int
    ): Pair<FloatArray, FloatArray> {
        val visualRow = activeCellHrtfRow(activeCells)
        val hrtfRow = personalHrtfRow(visualRow, settings)
        val original = repository.getOriginalReceiverPair(hrtfRow, column)
        val leftHrir = if (settings.swapChannels) original.receiver1 else original.receiver0
        val rightHrir = if (settings.swapChannels) original.receiver0 else original.receiver1
        val sourceFrameCount = (frameCount - repository.metadata.hrirLength + 1).coerceAtLeast(1)
        val source = generateActiveCellSource(
            activeCells = activeCells,
            column = column,
            sampleCount = sourceFrameCount
        )
        val left = convolveToLength(source, leftHrir, frameCount)
        val right = convolveToLength(source, rightHrir, frameCount)
        val averageStrength = activeCells.map { it.strength }.average().toFloat()
        val strengthGain = 0.65f + 0.35f * averageStrength.coerceIn(0f, 1f)
        val distanceGain = averageDistanceGain(activeCells)
        val verticalGain = glasses64ModeOutputGain(
            Glasses64VerticalSoundMode.LOG_EACH_CELL,
            visualRow
        )
        normalizeStereoPairInPlace(
            left,
            right,
            HRTF64_CELL_PEAK * strengthGain * distanceGain * verticalGain
        )
        return left to right
    }

    private fun renderEnhancedRegionColumn(
        request: Glasses64ColumnRequest,
        regions: List<Glasses64VerticalRegion>,
        column: Int,
        settings: Hrtf64CalibrationSettings,
        frameCount: Int
    ): Pair<FloatArray, FloatArray> {
        val columnLeft = FloatArray(frameCount)
        val columnRight = FloatArray(frameCount)
        val populatedRegions = regions.mapNotNull { region ->
            val cells = request.activeCells.filter { it.row in region.startRow..region.endRow }
            if (cells.isEmpty()) null else region to cells
        }
        if (populatedRegions.isEmpty()) return columnLeft to columnRight

        val regionGain = 1f / sqrt(populatedRegions.size.toFloat())
        for ((region, activeCells) in populatedRegions) {
            val pair = renderEnhancedRegion(
                activeCells = activeCells,
                representativeRow = region.representativeRow,
                column = column,
                settings = settings,
                frameCount = frameCount
            )
            for (frame in 0 until frameCount) {
                columnLeft[frame] += pair.first[frame] * regionGain
                columnRight[frame] += pair.second[frame] * regionGain
            }
        }
        return columnLeft to columnRight
    }

    private fun renderEnhancedRegion(
        activeCells: List<Glasses64ActiveCell>,
        representativeRow: Int,
        column: Int,
        settings: Hrtf64CalibrationSettings,
        frameCount: Int
    ): Pair<FloatArray, FloatArray> {
        val visualRow = representativeRow.coerceIn(0, GLASSES64_ROWS - 1)
        val hrtfRow = personalHrtfRow(visualRow, settings)
        val original = repository.getOriginalReceiverPair(hrtfRow, column)
        val leftHrir = if (settings.swapChannels) original.receiver1 else original.receiver0
        val rightHrir = if (settings.swapChannels) original.receiver0 else original.receiver1
        val sourceFrameCount = (frameCount - repository.metadata.hrirLength + 1).coerceAtLeast(1)
        val source = generateEnhancedRegionSource(
            activeCells = activeCells,
            representativeRow = visualRow,
            column = column,
            sampleCount = sourceFrameCount
        )
        val left = convolveToLength(source, leftHrir, frameCount)
        val right = convolveToLength(source, rightHrir, frameCount)
        val averageStrength = activeCells.map { it.strength }.average().toFloat()
        val strengthGain = 0.65f + 0.35f * averageStrength.coerceIn(0f, 1f)
        val distanceGain = averageDistanceGain(activeCells)
        val verticalGain = glasses64ModeOutputGain(
            Glasses64VerticalSoundMode.REGION_ENHANCED,
            visualRow
        )
        normalizeStereoPairInPlace(
            left,
            right,
            HRTF64_CELL_PEAK * strengthGain * distanceGain * verticalGain
        )
        return left to right
    }

    private fun renderCategoricalRegionColumn(
        regions: List<Glasses64VerticalRegion>,
        column: Int,
        settings: Hrtf64CalibrationSettings,
        frameCount: Int
    ): Pair<FloatArray, FloatArray> {
        val columnLeft = FloatArray(frameCount)
        val columnRight = FloatArray(frameCount)
        val regionGain = 1f / sqrt(regions.size.toFloat())

        for (region in regions) {
            val pair = renderCategoricalRegion(
                region = region,
                column = column,
                settings = settings,
                frameCount = frameCount
            )
            for (frame in 0 until frameCount) {
                columnLeft[frame] += pair.first[frame] * regionGain
                columnRight[frame] += pair.second[frame] * regionGain
            }
        }
        return columnLeft to columnRight
    }

    private fun renderCategoricalRegion(
        region: Glasses64VerticalRegion,
        column: Int,
        settings: Hrtf64CalibrationSettings,
        frameCount: Int
    ): Pair<FloatArray, FloatArray> {
        val visualRow = region.representativeRow.coerceIn(0, GLASSES64_ROWS - 1)
        val hrtfRow = personalHrtfRow(visualRow, settings)
        val original = repository.getOriginalReceiverPair(hrtfRow, column)
        val leftHrir = if (settings.swapChannels) original.receiver1 else original.receiver0
        val rightHrir = if (settings.swapChannels) original.receiver0 else original.receiver1
        val sourceFrameCount = (frameCount - repository.metadata.hrirLength + 1).coerceAtLeast(1)
        val source = generateCategoricalRegionSource(
            row = visualRow,
            column = column,
            sampleCount = sourceFrameCount
        )
        val left = convolveToLength(source, leftHrir, frameCount)
        val right = convolveToLength(source, rightHrir, frameCount)
        val strengthGain = 0.65f + 0.35f * region.strength.coerceIn(0f, 1f)
        val distanceGain = glasses64DistanceGain(region.distanceMeters)
        val heightRatio = (region.endRow - region.startRow + 1).toFloat() / GLASSES64_ROWS
        val sizeGain = 0.90f + 0.10f * sqrt(heightRatio.coerceIn(0f, 1f))
        val verticalGain = glasses64ModeOutputGain(
            Glasses64VerticalSoundMode.REGION_CATEGORICAL,
            visualRow
        )
        normalizeStereoPairInPlace(
            left,
            right,
            HRTF64_CELL_PEAK * strengthGain * distanceGain * sizeGain * verticalGain
        )
        return left to right
    }

    private fun activeCellHrtfRow(activeCells: List<Glasses64ActiveCell>): Int {
        var strengthSum = 0.0
        var weightedRowSum = 0.0
        for (cell in activeCells) {
            val strength = cell.strength.coerceIn(0f, 1f).toDouble()
            strengthSum += strength
            weightedRowSum += cell.row.toDouble() * strength
        }
        if (strengthSum <= 0.0) return GLASSES64_ROWS / 2
        return (weightedRowSum / strengthSum).roundToInt().coerceIn(0, GLASSES64_ROWS - 1)
    }

    private fun averageDistanceGain(activeCells: List<Glasses64ActiveCell>): Float {
        var strengthSum = 0.0
        var weightedGainSum = 0.0
        for (cell in activeCells) {
            val strength = cell.strength.coerceIn(0f, 1f).toDouble()
            strengthSum += strength
            weightedGainSum += glasses64DistanceGain(cell.distanceMeters).toDouble() * strength
        }
        if (strengthSum <= 0.0) return glasses64DistanceGain(OBSTACLE_ENTER_DISTANCE_METERS)
        return (weightedGainSum / strengthSum).toFloat()
    }

    private fun generateActiveCellSource(
        activeCells: List<Glasses64ActiveCell>,
        column: Int,
        sampleCount: Int
    ): FloatArray {
        val count = activeCells.size
        val strengths = DoubleArray(count)
        val sinPhase = DoubleArray(count)
        val cosPhase = DoubleArray(count)
        val sinStep = DoubleArray(count)
        val cosStep = DoubleArray(count)
        var energySum = 0.0

        activeCells.forEachIndexed { index, cell ->
            val strength = cell.strength.coerceIn(0f, 1f).toDouble()
            val phase = 2.0 * PI * ((cell.row * 37 + column * 17) % 64).toDouble() / 64.0
            val step = 2.0 * PI * glasses64LogPitchHz(cell.row) /
                GLASSES64_SAMPLE_RATE.toDouble()
            strengths[index] = strength
            sinPhase[index] = sin(phase)
            cosPhase[index] = kotlin.math.cos(phase)
            sinStep[index] = sin(step)
            cosStep[index] = kotlin.math.cos(step)
            energySum += strength * strength
        }

        val energyScale = 1.0 / sqrt(energySum.coerceAtLeast(1.0e-9))
        val random = Random(20_260_826 + column * 31)
        val fadeSamples = (GLASSES64_SAMPLE_RATE * HRTF64_SOURCE_FADE_SECONDS)
            .toInt()
            .coerceAtMost(sampleCount / 2)
            .coerceAtLeast(1)

        return FloatArray(sampleCount) { sampleIndex ->
            var tone = 0.0
            for (cellIndex in 0 until count) {
                tone += sinPhase[cellIndex] * strengths[cellIndex]
                val nextSin = sinPhase[cellIndex] * cosStep[cellIndex] +
                    cosPhase[cellIndex] * sinStep[cellIndex]
                val nextCos = cosPhase[cellIndex] * cosStep[cellIndex] -
                    sinPhase[cellIndex] * sinStep[cellIndex]
                sinPhase[cellIndex] = nextSin
                cosPhase[cellIndex] = nextCos
            }
            val noise = random.nextFloat() * 2f - 1f
            val envelope = when {
                sampleIndex < fadeSamples -> sampleIndex.toFloat() / fadeSamples.toFloat()
                sampleIndex >= sampleCount - fadeSamples ->
                    (sampleCount - sampleIndex - 1).toFloat() / fadeSamples.toFloat()
                else -> 1f
            }.coerceIn(0f, 1f)
            ((tone * energyScale * 0.88 + noise * 0.12) * envelope * 0.28).toFloat()
        }
    }

    private fun generateEnhancedRegionSource(
        activeCells: List<Glasses64ActiveCell>,
        representativeRow: Int,
        column: Int,
        sampleCount: Int
    ): FloatArray {
        val count = activeCells.size
        val strengths = DoubleArray(count)
        val sinPhase = DoubleArray(count)
        val cosPhase = DoubleArray(count)
        val sinStep = DoubleArray(count)
        val cosStep = DoubleArray(count)
        var energySum = 0.0

        activeCells.forEachIndexed { index, cell ->
            val strength = cell.strength.coerceIn(0f, 1f).toDouble()
            val phase = 2.0 * PI * ((cell.row * 37 + column * 17) % 64).toDouble() / 64.0
            val step = 2.0 * PI * glasses64EnhancedPitchHz(cell.row) /
                GLASSES64_SAMPLE_RATE.toDouble()
            strengths[index] = strength
            sinPhase[index] = sin(phase)
            cosPhase[index] = kotlin.math.cos(phase)
            sinStep[index] = sin(step)
            cosStep[index] = kotlin.math.cos(step)
            energySum += strength * strength
        }

        val energyScale = 1.0 / sqrt(energySum.coerceAtLeast(1.0e-9))
        val carrier = generateBandLimitedCarrier(
            row = representativeRow,
            column = column,
            sampleCount = sampleCount
        )
        val carrierRatio = glasses64EnhancedCarrierRatio(representativeRow).toDouble()
        val toneRatio = 1.0 - carrierRatio
        val fadeSamples = (GLASSES64_SAMPLE_RATE * HRTF64_SOURCE_FADE_SECONDS)
            .toInt()
            .coerceAtMost(sampleCount / 2)
            .coerceAtLeast(1)

        return FloatArray(sampleCount) { sampleIndex ->
            var tone = 0.0
            for (cellIndex in 0 until count) {
                tone += sinPhase[cellIndex] * strengths[cellIndex]
                val nextSin = sinPhase[cellIndex] * cosStep[cellIndex] +
                    cosPhase[cellIndex] * sinStep[cellIndex]
                val nextCos = cosPhase[cellIndex] * cosStep[cellIndex] -
                    sinPhase[cellIndex] * sinStep[cellIndex]
                sinPhase[cellIndex] = nextSin
                cosPhase[cellIndex] = nextCos
            }
            val envelope = when {
                sampleIndex < fadeSamples -> sampleIndex.toFloat() / fadeSamples.toFloat()
                sampleIndex >= sampleCount - fadeSamples ->
                    (sampleCount - sampleIndex - 1).toFloat() / fadeSamples.toFloat()
                else -> 1f
            }.coerceIn(0f, 1f)
            (
                (tone * energyScale * toneRatio + carrier[sampleIndex] * carrierRatio) *
                    envelope * 0.30
                ).toFloat()
        }
    }

    private fun generateBandLimitedCarrier(
        row: Int,
        column: Int,
        sampleCount: Int,
        lowHz: Double = HRTF64_ENHANCED_CARRIER_LOW_HZ,
        highHz: Double = HRTF64_ENHANCED_CARRIER_HIGH_HZ
    ): FloatArray {
        val random = Random(20_260_827 + row * 257 + column * 31)
        val lowPassDecay = exp(
            -2.0 * PI * highHz /
                GLASSES64_SAMPLE_RATE.toDouble()
        )
        val highPassDecay = exp(
            -2.0 * PI * lowHz /
                GLASSES64_SAMPLE_RATE.toDouble()
        )
        val output = FloatArray(sampleCount)
        var lowPass = 0.0
        var previousLowPass = 0.0
        var highPass = 0.0
        var energy = 0.0

        for (index in output.indices) {
            val white = random.nextDouble() * 2.0 - 1.0
            lowPass = (1.0 - lowPassDecay) * white + lowPassDecay * lowPass
            highPass = highPassDecay * (highPass + lowPass - previousLowPass)
            previousLowPass = lowPass
            output[index] = highPass.toFloat()
            energy += highPass * highPass
        }

        val rms = sqrt(energy / sampleCount.coerceAtLeast(1).toDouble()).coerceAtLeast(1.0e-6)
        val scale = (0.55 / rms).coerceAtMost(8.0)
        for (index in output.indices) output[index] = (output[index] * scale).toFloat()
        return output
    }

    private fun generateCategoricalRegionSource(
        row: Int,
        column: Int,
        sampleCount: Int
    ): FloatArray {
        val pitchHz = glasses64CategoricalPitchHz(row)
        val harmonicRatio = when {
            row <= HRTF64_ENHANCED_TOP_END_ROW -> 0.08
            row <= HRTF64_ENHANCED_MIDDLE_END_ROW -> 0.16
            else -> 0.24
        }
        val carrierRatio = glasses64CategoricalCarrierRatio(row).toDouble()
        val toneRatio = 1.0 - carrierRatio
        val carrier = generateBandLimitedCarrier(
            row = row,
            column = column,
            sampleCount = sampleCount,
            highHz = HRTF64_CATEGORICAL_CARRIER_HIGH_HZ
        )
        val phaseOffset = 2.0 * PI * ((row * 37 + column * 17) % 64) / 64.0
        val fadeSamples = (GLASSES64_SAMPLE_RATE * HRTF64_SOURCE_FADE_SECONDS)
            .toInt()
            .coerceAtMost(sampleCount / 2)
            .coerceAtLeast(1)

        return FloatArray(sampleCount) { index ->
            val time = index.toDouble() / GLASSES64_SAMPLE_RATE.toDouble()
            val fundamental = sin(2.0 * PI * pitchHz * time + phaseOffset)
            val harmonicHz = minOf(pitchHz * 2.0, HRTF64_CATEGORICAL_CARRIER_HIGH_HZ)
            val harmonic = sin(2.0 * PI * harmonicHz * time + phaseOffset * 0.5)
            val tone = fundamental * (1.0 - harmonicRatio) + harmonic * harmonicRatio
            val envelope = when {
                index < fadeSamples -> index.toFloat() / fadeSamples.toFloat()
                index >= sampleCount - fadeSamples ->
                    (sampleCount - index - 1).toFloat() / fadeSamples.toFloat()
                else -> 1f
            }.coerceIn(0f, 1f)
            ((tone * toneRatio + carrier[index] * carrierRatio) * envelope * 0.31).toFloat()
        }
    }

    private fun generateSource(
        row: Int,
        column: Int,
        settings: Hrtf64CalibrationSettings,
        sampleCount: Int
    ): FloatArray {
        val legacyBand = (row * 6 / GLASSES64_ROWS).coerceIn(0, 5)
        val pitchHz = when (settings.verticalSoundMode) {
            Glasses64VerticalSoundMode.LEGACY_SIX_BAND ->
                pitchHz(legacyBand, settings.pitchPreset)
            Glasses64VerticalSoundMode.LOG_64_ROW,
            Glasses64VerticalSoundMode.LOG_EACH_CELL -> glasses64LogPitchHz(row)
            Glasses64VerticalSoundMode.REGION_ENHANCED -> glasses64EnhancedPitchHz(row)
            Glasses64VerticalSoundMode.REGION_CATEGORICAL -> glasses64CategoricalPitchHz(row)
        }
        val toneRatio = when (settings.verticalSoundMode) {
            Glasses64VerticalSoundMode.LEGACY_SIX_BAND -> toneRatio(
                legacyBand = legacyBand,
                upperClear = settings.upperClear,
                lowerClear = settings.lowerClear
            )
            Glasses64VerticalSoundMode.LOG_64_ROW,
            Glasses64VerticalSoundMode.LOG_EACH_CELL -> logPitchToneRatio(row)
            Glasses64VerticalSoundMode.REGION_ENHANCED ->
                1f - glasses64EnhancedCarrierRatio(row)
            Glasses64VerticalSoundMode.REGION_CATEGORICAL ->
                1f - glasses64CategoricalCarrierRatio(row)
        }
        val noiseRatio = 1f - toneRatio
        val random = Random(20_260_826 + row * 257 + column * 31)
        val fadeSamples = (GLASSES64_SAMPLE_RATE * HRTF64_SOURCE_FADE_SECONDS)
            .toInt()
            .coerceAtMost(sampleCount / 2)
            .coerceAtLeast(1)

        return FloatArray(sampleCount) { index ->
            val time = index.toDouble() / GLASSES64_SAMPLE_RATE.toDouble()
            val noise = random.nextFloat() * 2f - 1f
            val tone = sin(2.0 * PI * pitchHz * time).toFloat()
            val envelope = when {
                index < fadeSamples -> index.toFloat() / fadeSamples.toFloat()
                index >= sampleCount - fadeSamples ->
                    (sampleCount - index - 1).toFloat() / fadeSamples.toFloat()
                else -> 1f
            }.coerceIn(0f, 1f)
            (noise * noiseRatio + tone * toneRatio) * envelope * 0.32f
        }
    }

    private fun pitchHz(legacyBand: Int, preset: String): Double {
        if (legacyBand <= 2) {
            return when (legacyBand) {
                0 -> 1350.0
                1 -> 1200.0
                else -> 1050.0
            }
        }
        return when (preset) {
            "LIGHT" -> when (legacyBand) {
                3 -> 900.0
                4 -> 700.0
                else -> 500.0
            }
            "STRONG" -> when (legacyBand) {
                3 -> 1200.0
                4 -> 600.0
                else -> 250.0
            }
            else -> when (legacyBand) {
                3 -> 1000.0
                4 -> 650.0
                else -> 350.0
            }
        }
    }

    private fun toneRatio(legacyBand: Int, upperClear: Boolean, lowerClear: Boolean): Float {
        if (legacyBand <= 2) {
            return if (upperClear) {
                when (legacyBand) {
                    0 -> 0.06f
                    1 -> 0.08f
                    else -> 0.10f
                }
            } else {
                when (legacyBand) {
                    0 -> 0.14f
                    1 -> 0.17f
                    else -> 0.20f
                }
            }
        }
        return if (lowerClear) 0.18f else 0.35f
    }

    private fun logPitchToneRatio(row: Int): Float {
        val position = row.coerceIn(0, GLASSES64_ROWS - 1).toFloat() /
            (GLASSES64_ROWS - 1).toFloat()
        return 0.24f + 0.14f * position
    }

    private fun convolveToLength(
        signal: FloatArray,
        impulseResponse: FloatArray,
        outputSize: Int
    ): FloatArray {
        val output = FloatArray(outputSize)
        for (signalIndex in signal.indices) {
            val value = signal[signalIndex]
            val maxImpulseIndex = minOf(
                impulseResponse.lastIndex,
                output.lastIndex - signalIndex
            )
            for (impulseIndex in 0..maxImpulseIndex) {
                output[signalIndex + impulseIndex] += value * impulseResponse[impulseIndex]
            }
        }
        return output
    }

    private fun applyBoundaryFade(left: FloatArray, right: FloatArray) {
        val fadeFrames = (GLASSES64_SAMPLE_RATE * HRTF64_OUTPUT_EDGE_FADE_SECONDS)
            .toInt()
            .coerceAtMost(left.size / 2)
            .coerceAtLeast(1)
        for (index in 0 until fadeFrames) {
            val phase = index.toDouble() / (fadeFrames - 1).coerceAtLeast(1).toDouble()
            val gainIn = (0.5 - 0.5 * kotlin.math.cos(PI * phase)).toFloat()
            val endIndex = left.lastIndex - index
            left[index] *= gainIn
            right[index] *= gainIn
            left[endIndex] *= gainIn
            right[endIndex] *= gainIn
        }
    }

    private fun normalizeStereoPairInPlace(left: FloatArray, right: FloatArray, targetPeak: Float) {
        val peak = stereoPeak(left, right)
        if (peak <= 0f) return
        val gain = targetPeak / peak
        for (index in left.indices) left[index] *= gain
        for (index in right.indices) right[index] *= gain
    }

    private fun limitStereoPairInPlace(left: FloatArray, right: FloatArray, peakLimit: Float) {
        val peak = stereoPeak(left, right)
        if (peak <= peakLimit || peak <= 0f) return
        val gain = peakLimit / peak
        for (index in left.indices) left[index] *= gain
        for (index in right.indices) right[index] *= gain
    }

    private fun stereoPeak(left: FloatArray, right: FloatArray): Float {
        var peak = 0f
        for (value in left) peak = maxOf(peak, abs(value))
        for (value in right) peak = maxOf(peak, abs(value))
        return peak
    }

    private fun floatsToStereoPcm(left: FloatArray, right: FloatArray): ShortArray {
        val pcm = ShortArray(left.size * 2)
        for (frame in left.indices) {
            pcm[frame * 2] = floatToPcm16(left[frame])
            pcm[frame * 2 + 1] = floatToPcm16(right[frame])
        }
        return pcm
    }

    private fun floatToPcm16(value: Float): Short {
        return (value.coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat())
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun startWorker(
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        work: () -> Boolean
    ) {
        check(!closed) { "64x64 HRTF audio engine is closed" }
        stopMainSoundscape()
        val worker = Thread {
            try {
                val completed = work()
                mainHandler.post(if (completed) onFinished else onStopped)
            } catch (_: InterruptedException) {
                mainHandler.post(onStopped)
            } catch (error: Exception) {
                Log.e("Glasses64Audio", "64x64 soundscape playback failed", error)
                mainHandler.post { onError(error.message ?: "未知错误") }
            } finally {
                currentWorker.compareAndSet(Thread.currentThread(), null)
            }
        }.apply { name = "Glasses64SoundscapeThread" }
        currentWorker.set(worker)
        worker.start()
    }

    private fun startImmediateAlertWorker(
        onFinished: () -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        work: () -> Boolean
    ) {
        check(!closed) { "64x64 HRTF audio engine is closed" }
        stopImmediateAlert()
        val worker = Thread {
            try {
                val completed = work()
                mainHandler.post(if (completed) onFinished else onStopped)
            } catch (_: InterruptedException) {
                mainHandler.post(onStopped)
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) {
                    mainHandler.post(onStopped)
                } else {
                    Log.e("Glasses64Audio", "Immediate obstacle alert failed", error)
                    mainHandler.post { onError(error.message ?: "未知错误") }
                }
            } finally {
                if (currentAlertWorker.compareAndSet(Thread.currentThread(), null)) {
                    updateMainSoundscapeVolume()
                }
            }
        }.apply { name = "Glasses64ImmediateAlertThread" }
        currentAlertWorker.set(worker)
        updateMainSoundscapeVolume()
        worker.start()
    }

    private fun playPcmBlocking(
        pcm: ShortArray,
        continuePlayback: () -> Boolean
    ): Boolean {
        if (!continuePlayback()) return false
        val minBuffer = AudioTrack.getMinBufferSize(
            GLASSES64_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "AudioTrack min buffer error: $minBuffer" }

        val blockShortCount = (GLASSES64_SAMPLE_RATE * 0.020).toInt() * 2
        val bufferSizeBytes = maxOf(minBuffer, blockShortCount * Short.SIZE_BYTES * 2)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(GLASSES64_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        currentAudioTrack.set(audioTrack)
        updateMainSoundscapeVolume()

        try {
            audioTrack.play()
            var offset = 0
            while (offset < pcm.size) {
                if (Thread.currentThread().isInterrupted || !continuePlayback()) {
                    pauseAndFlush(audioTrack)
                    return false
                }
                val count = minOf(blockShortCount, pcm.size - offset)
                val written = audioTrack.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "AudioTrack write failed: $written" }
                offset += written
            }

            val totalFrames = pcm.size / 2
            val timeoutMs =
                (totalFrames.toDouble() / GLASSES64_SAMPLE_RATE * 1000.0).toLong() + 800L
            val startMs = System.currentTimeMillis()
            while (
                audioTrack.playbackHeadPosition < totalFrames &&
                System.currentTimeMillis() - startMs < timeoutMs
            ) {
                if (Thread.currentThread().isInterrupted || !continuePlayback()) {
                    pauseAndFlush(audioTrack)
                    return false
                }
                Thread.sleep(5)
            }
            return true
        } finally {
            currentAudioTrack.compareAndSet(audioTrack, null)
            stopAndReleaseTrack(audioTrack)
        }
    }

    private fun playImmediatePcmBlocking(
        pcm: ShortArray,
        continuePlayback: () -> Boolean,
        onPlaybackStarted: () -> Unit
    ): Boolean {
        if (Thread.currentThread().isInterrupted || !continuePlayback()) return false
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(GLASSES64_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        currentAlertAudioTrack.set(audioTrack)

        try {
            val written = audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            check(written == pcm.size) { "Immediate AudioTrack write failed: $written" }
            if (Thread.currentThread().isInterrupted || !continuePlayback()) return false

            audioTrack.play()
            onPlaybackStarted()
            val totalFrames = pcm.size / 2
            val timeoutMs =
                (totalFrames.toDouble() / GLASSES64_SAMPLE_RATE * 1000.0).toLong() + 300L
            val startMs = SystemClock.elapsedRealtime()
            while (
                audioTrack.playbackHeadPosition < totalFrames &&
                SystemClock.elapsedRealtime() - startMs < timeoutMs
            ) {
                if (Thread.currentThread().isInterrupted || !continuePlayback()) {
                    pauseAndFlush(audioTrack)
                    return false
                }
                Thread.sleep(4L)
            }
            return true
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted || !continuePlayback()) return false
            throw error
        } finally {
            currentAlertAudioTrack.compareAndSet(audioTrack, null)
            stopAndReleaseTrack(audioTrack)
        }
    }

    private fun updateMainSoundscapeVolume() {
        val volume = if (currentAlertWorker.get() == null) 1f else HRTF64_BACKDROP_DUCK_VOLUME
        try {
            currentAudioTrack.get()?.setVolume(volume)
        } catch (_: Exception) {
        }
    }

    private fun stopMainSoundscape() {
        currentWorker.getAndSet(null)?.interrupt()
        stopAndReleaseTrack(currentAudioTrack.getAndSet(null))
    }

    @Synchronized
    fun stopImmediateAlert() {
        currentAlertWorker.getAndSet(null)?.interrupt()
        stopAndReleaseTrack(currentAlertAudioTrack.getAndSet(null))
        updateMainSoundscapeVolume()
    }

    @Synchronized
    fun stop() {
        stopMainSoundscape()
        stopImmediateAlert()
    }

    override fun close() {
        closed = true
        stop()
    }

    private fun pauseAndFlush(audioTrack: AudioTrack) {
        try {
            audioTrack.pause()
        } catch (_: Exception) {
        }
        try {
            audioTrack.flush()
        } catch (_: Exception) {
        }
    }

    private fun stopAndReleaseTrack(audioTrack: AudioTrack?) {
        if (audioTrack == null) return
        try {
            audioTrack.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack.flush()
        } catch (_: Exception) {
        }
        try {
            audioTrack.release()
        } catch (_: Exception) {
        }
    }
}
