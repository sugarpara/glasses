package com.example.glasses.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DepthCameraController(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    @Volatile
    private var startGeneration = 0
    @Volatile
    private var closed = false

    fun start(
        lifecycleOwner: LifecycleOwner,
        onBitmap: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(!closed) { "DepthCameraController is closed" }
        val generation = ++startGeneration
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                if (closed || generation != startGeneration) return@addListener
                try {
                    val provider = future.get()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { image ->
                        var bitmap: Bitmap? = null
                        try {
                            val uprightBitmap = ImageProxyBitmapConverter.toUprightBitmap(image)
                            bitmap = uprightBitmap
                            onBitmap(uprightBitmap)
                            bitmap = null
                        } catch (error: Throwable) {
                            bitmap?.recycle()
                            onError(error)
                        } finally {
                            image.close()
                        }
                    }

                    if (closed || generation != startGeneration) {
                        analysis.clearAnalyzer()
                        return@addListener
                    }
                    imageAnalysis?.clearAnalyzer()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis,
                    )
                    cameraProvider = provider
                    imageAnalysis = analysis
                } catch (error: Throwable) {
                    onError(error)
                }
            },
            mainExecutor,
        )
    }

    fun stop() {
        startGeneration++
        val releaseCamera = Runnable {
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            cameraProvider?.unbindAll()
            cameraProvider = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseCamera.run()
        } else {
            mainExecutor.execute(releaseCamera)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        analyzerExecutor.shutdownNow()
    }
}
