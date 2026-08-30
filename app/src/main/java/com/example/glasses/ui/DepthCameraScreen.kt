package com.example.glasses.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glasses.camera.DepthCameraController
import java.util.Locale

@Composable
fun DepthCameraScreen(
    viewModel: DepthCameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val modelReady = state is DepthCameraUiState.WaitingForCamera ||
        state is DepthCameraUiState.Running
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val controller = remember {
        DepthCameraController(context.applicationContext)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startAudio()
                Lifecycle.Event.ON_STOP -> viewModel.stopAudio()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startAudio()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAudio()
        }
    }

    DisposableEffect(permissionGranted, modelReady, lifecycleOwner) {
        if (permissionGranted && modelReady) {
            controller.start(
                lifecycleOwner = lifecycleOwner,
                onBitmap = viewModel::process,
                onError = viewModel::reportCameraError,
            )
        }
        onDispose { controller.stop() }
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (state is DepthCameraUiState.Error) {
            val error = state as DepthCameraUiState.Error
            StatusText(
                text = "运行失败\n${error.message}",
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!permissionGranted && state !is DepthCameraUiState.LoadingModel) {
            PermissionRequired(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
            )
        } else {
            when (val current = state) {
                DepthCameraUiState.LoadingModel -> StatusText(
                    text = "正在加载 YOLO26 depth 模型...",
                )

                DepthCameraUiState.WaitingForCamera -> StatusText(
                    text = "正在启动摄像头...",
                )

                is DepthCameraUiState.Error -> Unit

                is DepthCameraUiState.Running -> RunningDepthFrame(
                    state = current,
                    onClassificationDisplayChanged = viewModel::setClassificationDisplayEnabled,
                    onFrameDisplayed = viewModel::reportUiFrameDisplayed,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.RunningDepthFrame(
    state: DepthCameraUiState.Running,
    onClassificationDisplayChanged: (Boolean) -> Unit,
    onFrameDisplayed: (Long) -> Unit,
) {
    LaunchedEffect(state.performanceFrameSequence) {
        onFrameDisplayed(state.performancePublishedAtNanos)
    }
    Image(
        bitmap = state.image,
        contentDescription = "实时深度图",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "YOLO26 Depth",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "分类显示",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = state.classificationDisplayEnabled,
                    onCheckedChange = onClassificationDisplayChanged,
                )
            }
        }
        Text(
            text = String.format(
                Locale.US,
                "%s | %.1f FPS | %.1f ms",
                state.accelerator,
                state.fps,
                state.inferenceMs,
            ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = String.format(
                Locale.US,
                "range %.3f .. %.3f",
                state.minDepth,
                state.maxDepth,
            ),
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = String.format(
                Locale.US,
                "%s | MLE %.1f ms",
                if (state.groundFitSucceeded) "GROUND FILTER" else "DEPTH ONLY",
                state.groundFilterMs,
            ),
            color = if (state.groundFitSucceeded) FIT_SUCCEEDED_COLOR else FIT_FAILED_COLOR,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = String.format(
                Locale.US,
                "ground %.1f%% | obstacle %.1f%% | unknown %.1f%%",
                state.groundFraction * 100.0f,
                state.obstacleFraction * 100.0f,
                state.unknownFraction * 100.0f,
            ),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = String.format(
                Locale.US,
                "obstacle grid %d/4096 | max %.1f%%",
                state.activeObstacleCells,
                state.maxObstacleOccupancy * 100.0f,
            ),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionRequired(
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "需要摄像头权限才能生成实时深度图",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequestPermission) {
            Text("授予摄像头权限")
        }
    }
}

@Composable
private fun StatusText(
    text: String,
    color: Color = Color.White,
) {
    Text(
        text = text,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

private val FIT_SUCCEEDED_COLOR = Color(0xFF66E38D)
private val FIT_FAILED_COLOR = Color(0xFFFFC857)
