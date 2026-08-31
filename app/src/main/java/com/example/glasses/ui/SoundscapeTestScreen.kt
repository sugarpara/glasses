package com.example.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.glasses.audio.GLASSES64_COLUMNS
import com.example.glasses.audio.GLASSES64_ROWS
import com.example.glasses.audio.Glasses64ActiveCell
import com.example.glasses.audio.Glasses64AudioEngine
import com.example.glasses.audio.Glasses64ColumnRequest
import com.example.glasses.audio.Glasses64VerticalRegion
import com.example.glasses.ui.theme.AppBlue
import com.example.glasses.ui.theme.AppGreen
import com.example.glasses.ui.theme.AppMutedText
import com.example.glasses.ui.theme.AppOutline
import com.example.glasses.ui.theme.AppRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private enum class SoundscapeTestStatus {
    LOADING,
    READY,
    RENDERING,
    PLAYING,
    ERROR,
}

@Composable
internal fun SoundscapeTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember(context) {
        Glasses64AudioEngine(
            context = context.applicationContext,
            preferLocalOutput = true,
        )
    }
    val playbackActive = remember { AtomicBoolean(false) }
    val playbackGeneration = remember { AtomicLong(0L) }
    var selectedRow by remember { mutableIntStateOf(GLASSES64_ROWS / 2) }
    var selectedColumn by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(SoundscapeTestStatus.LOADING) }
    var renderTimeMs by remember { mutableStateOf<Double?>(null) }
    var pcmPeak by remember { mutableStateOf<Float?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun stopPlayback(showReady: Boolean = true) {
        playbackGeneration.incrementAndGet()
        playbackActive.set(false)
        engine.stop()
        if (
            showReady &&
            status != SoundscapeTestStatus.LOADING &&
            status != SoundscapeTestStatus.ERROR
        ) {
            status = SoundscapeTestStatus.READY
        }
    }

    fun startFullSoundscape() {
        if (status == SoundscapeTestStatus.LOADING) return
        val generation = playbackGeneration.incrementAndGet()
        playbackActive.set(true)
        selectedColumn = 0
        renderTimeMs = null
        pcmPeak = null
        errorMessage = null
        status = SoundscapeTestStatus.RENDERING
        try {
            engine.playSoundscape(
                requests = createSoundscapeTestRequests(selectedRow),
                continuePlayback = {
                    playbackActive.get() && generation == playbackGeneration.get()
                },
                onPrepared = { rendered ->
                    if (generation == playbackGeneration.get()) {
                        val peak = rendered.pcm.maxOfOrNull { sample -> abs(sample.toInt()) } ?: 0
                        pcmPeak = peak / 32_768f
                        status = SoundscapeTestStatus.PLAYING
                    }
                },
                onFinished = {
                    if (generation == playbackGeneration.get()) {
                        playbackActive.set(false)
                        selectedColumn = GLASSES64_COLUMNS - 1
                        status = SoundscapeTestStatus.READY
                    }
                },
                onStopped = {
                    if (generation == playbackGeneration.get()) {
                        playbackActive.set(false)
                        status = SoundscapeTestStatus.READY
                    }
                },
                onError = { message ->
                    if (generation == playbackGeneration.get()) {
                        playbackActive.set(false)
                        errorMessage = message
                        status = SoundscapeTestStatus.ERROR
                    }
                },
                onRendered = { elapsedMs ->
                    if (generation == playbackGeneration.get()) renderTimeMs = elapsedMs
                },
            )
        } catch (error: Throwable) {
            playbackActive.set(false)
            errorMessage = error.message ?: error.javaClass.simpleName
            status = SoundscapeTestStatus.ERROR
        }
    }

    fun leaveScreen() {
        stopPlayback(showReady = false)
        onBack()
    }

    LaunchedEffect(engine) {
        try {
            withContext(Dispatchers.IO) { engine.warmUp() }
            status = SoundscapeTestStatus.READY
        } catch (error: Throwable) {
            errorMessage = error.message ?: error.javaClass.simpleName
            status = SoundscapeTestStatus.ERROR
        }
    }

    LaunchedEffect(status) {
        if (status == SoundscapeTestStatus.PLAYING) {
            for (column in 0 until GLASSES64_COLUMNS) {
                if (status != SoundscapeTestStatus.PLAYING) break
                selectedColumn = column
                delay(SCAN_COLUMN_VISUAL_DELAY_MS)
            }
        }
    }

    DisposableEffect(engine, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopPlayback()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playbackGeneration.incrementAndGet()
            playbackActive.set(false)
            engine.close()
        }
    }

    BackHandler(onBack = ::leaveScreen)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        SoundscapeTopBar(onBack = ::leaveScreen)
        androidx.compose.material3.HorizontalDivider(color = AppOutline)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("测试声景", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "64 × 64 空间网格 · 选择高度后由左向右扫描",
                color = AppMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )

            SoundscapeGrid(
                selectedRow = selectedRow,
                selectedColumn = selectedColumn,
                playing = status == SoundscapeTestStatus.PLAYING,
                onCellSelected = { row, column ->
                    stopPlayback()
                    selectedRow = row
                    selectedColumn = column
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("64 × 64 网格", style = MaterialTheme.typography.labelMedium)
                Text(
                    "行 ${selectedRow + 1} · 列 ${selectedColumn + 1}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("上方", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
                Text("下方", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = selectedRow.toFloat(),
                onValueChange = {
                    stopPlayback()
                    selectedRow = it.toInt().coerceIn(0, GLASSES64_ROWS - 1)
                },
                valueRange = 0f..(GLASSES64_ROWS - 1).toFloat(),
                steps = GLASSES64_ROWS - 2,
                modifier = Modifier.fillMaxWidth(),
            )

            PlaybackStatusPanel(
                status = status,
                renderTimeMs = renderTimeMs,
                pcmPeak = pcmPeak,
                errorMessage = errorMessage,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val busy = status == SoundscapeTestStatus.RENDERING ||
                    status == SoundscapeTestStatus.PLAYING
                Button(
                    onClick = ::startFullSoundscape,
                    enabled = status != SoundscapeTestStatus.LOADING && !busy,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier
                        .weight(1.7f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(
                        text = when (status) {
                            SoundscapeTestStatus.RENDERING -> "生成中"
                            SoundscapeTestStatus.PLAYING -> "播放中"
                            else -> "播放完整声景"
                        },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = { stopPlayback() },
                    enabled = busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppRed),
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("停止", modifier = Modifier.padding(start = 4.dp))
                }
            }

            androidx.compose.material3.HorizontalDivider(
                color = AppOutline,
                modifier = Modifier.padding(vertical = 18.dp),
            )
            Text("声音映射", style = MaterialTheme.typography.titleMedium)
            MappingLegend()

            androidx.compose.material3.HorizontalDivider(
                color = AppOutline,
                modifier = Modifier.padding(vertical = 18.dp),
            )
            Text("时间方向", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("左", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .height(2.dp)
                        .background(AppMutedText),
                )
                Text("右", color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "使用与实时辅助相同的 HRTF 渲染、双声道 PCM 和播放链路。",
                color = AppMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
        }
    }
}

internal fun createSoundscapeTestRequests(row: Int): List<Glasses64ColumnRequest> {
    val safeRow = row.coerceIn(0, GLASSES64_ROWS - 1)
    return List(GLASSES64_COLUMNS) { column ->
        Glasses64ColumnRequest(
            column = column,
            regions = listOf(
                Glasses64VerticalRegion(
                    startRow = safeRow,
                    endRow = safeRow,
                    representativeRow = safeRow,
                    strength = TEST_SOUND_STRENGTH,
                    distanceMeters = TEST_SOUND_DISTANCE_METERS,
                ),
            ),
            activeCells = listOf(
                Glasses64ActiveCell(
                    row = safeRow,
                    strength = TEST_SOUND_STRENGTH,
                    distanceMeters = TEST_SOUND_DISTANCE_METERS,
                ),
            ),
        )
    }
}

@Composable
private fun SoundscapeTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "声景测试",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun SoundscapeGrid(
    selectedRow: Int,
    selectedColumn: Int,
    playing: Boolean,
    onCellSelected: (row: Int, column: Int) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF10141A))
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    val row = (position.y / size.height * GLASSES64_ROWS)
                        .toInt()
                        .coerceIn(0, GLASSES64_ROWS - 1)
                    val column = (position.x / size.width * GLASSES64_COLUMNS)
                        .toInt()
                        .coerceIn(0, GLASSES64_COLUMNS - 1)
                    onCellSelected(row, column)
                }
            },
    ) {
        val cellWidth = size.width / GLASSES64_COLUMNS
        val cellHeight = size.height / GLASSES64_ROWS
        for (column in 0 until GLASSES64_COLUMNS) {
            drawRect(
                color = AppRed.copy(alpha = 0.34f),
                topLeft = Offset(column * cellWidth, selectedRow * cellHeight),
                size = Size(cellWidth, cellHeight),
            )
        }
        drawRect(
            color = if (playing) AppGreen else Color.White,
            topLeft = Offset(selectedColumn * cellWidth, selectedRow * cellHeight),
            size = Size(cellWidth, cellHeight),
        )
        val gridColor = Color.White.copy(alpha = 0.16f)
        for (index in 1 until GLASSES64_COLUMNS) {
            val x = size.width * index / GLASSES64_COLUMNS
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.6f)
        }
        for (index in 1 until GLASSES64_ROWS) {
            val y = size.height * index / GLASSES64_ROWS
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.6f)
        }
    }
}

@Composable
private fun PlaybackStatusPanel(
    status: SoundscapeTestStatus,
    renderTimeMs: Double?,
    pcmPeak: Float?,
    errorMessage: String?,
) {
    val color = when (status) {
        SoundscapeTestStatus.READY -> AppGreen
        SoundscapeTestStatus.ERROR -> AppRed
        else -> AppBlue
    }
    val title = when (status) {
        SoundscapeTestStatus.LOADING -> "正在加载 HRTF 数据"
        SoundscapeTestStatus.READY -> "音频引擎已就绪"
        SoundscapeTestStatus.RENDERING -> "正在生成 64 × 64 声景"
        SoundscapeTestStatus.PLAYING -> "正在播放真实双声道声景"
        SoundscapeTestStatus.ERROR -> "声景播放失败"
    }
    val detail = when {
        status == SoundscapeTestStatus.ERROR -> errorMessage ?: "未知音频错误"
        renderTimeMs != null && pcmPeak != null -> String.format(
            Locale.US,
            "PCM 峰值 %.0f%% · 渲染 %.1f ms",
            pcmPeak * 100f,
            renderTimeMs,
        )
        status == SoundscapeTestStatus.READY -> "媒体音量开启后即可播放"
        else -> "请稍候"
    }
    Surface(
        color = color.copy(alpha = 0.09f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = color)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(detail, color = AppMutedText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MappingLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            LegendItem(AppBlue, "左侧声场")
            LegendItem(AppGreen, "当前扫描位置")
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            LegendItem(AppRed, "选中高度")
            LegendItem(Color.White, "选中单元")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private const val TEST_SOUND_STRENGTH = 1f
private const val TEST_SOUND_DISTANCE_METERS = 1.5f
private const val SCAN_COLUMN_VISUAL_DELAY_MS = 16L
