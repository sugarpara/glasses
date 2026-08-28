package com.example.glasses

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.glasses.camera.DepthCameraController
import com.example.glasses.ui.theme.GlassesTheme

class MainActivity : ComponentActivity() {
    private var cameraController: DepthCameraController? = null

    private fun startCamera() {
        cameraController = DepthCameraController(this)
        cameraController?.start(
            lifecycleOwner = this,
            onBitmap = { bitmap ->
                // 当前运行在后台单线程
                try {
                    Log.d("DepthCamera", "frame=${bitmap.width}x${bitmap.height}")
                } finally {
                    bitmap.recycle()
                }
            },
            onError = { error ->
                Log.e("DepthCamera", "Camera error", error)
            },
        )
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
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GlassesTheme {
        Greeting("Android")
    }
}
