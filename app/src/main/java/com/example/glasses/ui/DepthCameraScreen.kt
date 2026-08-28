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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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

                is DepthCameraUiState.Running -> RunningDepthFrame(current)
            }
        }
    }
}

@Composable
private fun BoxScope.RunningDepthFrame(state: DepthCameraUiState.Running) {
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
        Text(
            text = "YOLO26 Depth",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
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
