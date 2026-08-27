package com.example.glasses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.glasses.ui.theme.GlassesTheme

import android.util.Log
import com.example.glasses.camera.DepthCameraController

class MainActivity : ComponentActivity() {
    private var cameraController: DepthCameraController? = null

    private fun startCamera() {
        cameraController = DepthCameraController(
            context = this,
            lifecycleOwner = this,
            processFrame = { bitmap ->
                // 当前运行在后台单线程
                Log.d(
                    "DepthCamera",
                    "frame=${bitmap.width}x${bitmap.height}"
                )

                // 下一阶段在这里调用：
                // val depthFrame = depthEstimator.estimate(bitmap)
            },
            onError = { error ->
                Log.e("DepthCamera", "Camera error", error)
            }
        )

        cameraController?.start()
    }

    override fun onDestroy() {
        cameraController?.close()
        cameraController = null
        super.onDestroy()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GlassesTheme {
        Greeting("Android")
    }
}