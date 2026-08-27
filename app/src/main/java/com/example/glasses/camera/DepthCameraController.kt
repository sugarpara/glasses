package com.example.glasses.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DepthCameraController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val processFrame: (Bitmap) -> Unit,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analyzerExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var closed = false

    fun start() {
        if (closed) return

        val providerFuture = ProcessCameraProvider.getInstance(appContext)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )
                    .build()

                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    analyze(imageProxy)
                }

                imageAnalysis = analysis

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
            } catch (error: Throwable) {
                onError(error)
            }
        }, mainExecutor)
    }

    private fun analyze(imageProxy: ImageProxy) {
        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null

        try {
            bitmap = imageProxy.toRgbaBitmap()

            rotatedBitmap = bitmap.rotate(
                imageProxy.imageInfo.rotationDegrees
            )

            /*
             * 此回调在 analyzerExecutor 中同步执行。
             * 后续在这里调用 DepthEstimator，不要切到主线程。
             */
            processFrame(rotatedBitmap)
        } catch (error: Throwable) {
            onError(error)
        } finally {
            if (rotatedBitmap !== bitmap) {
                rotatedBitmap?.recycle()
            }
            bitmap?.recycle()

            // 无论成功还是失败都必须关闭
            imageProxy.close()
        }
    }

    fun stop() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null

        mainExecutor.execute {
            cameraProvider?.unbindAll()
            cameraProvider = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true

        stop()
        analyzerExecutor.shutdown()

        try {
            analyzerExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private fun ImageProxy.toRgbaBitmap(): Bitmap {
    require(format == android.graphics.PixelFormat.RGBA_8888) {
        "期望 RGBA_8888，实际 format=$format"
    }

    val plane = planes.first()
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride

    require(pixelStride == 4) {
        "不支持的 RGBA pixelStride=$pixelStride"
    }

    val paddedWidth = rowStride / pixelStride
    val paddedBitmap = Bitmap.createBitmap(
        paddedWidth,
        height,
        Bitmap.Config.ARGB_8888
    )

    buffer.rewind()
    paddedBitmap.copyPixelsFromBuffer(buffer)

    if (paddedWidth == width) {
        return paddedBitmap
    }

    return Bitmap.createBitmap(
        paddedBitmap,
        0,
        0,
        width,
        height
    ).also {
        paddedBitmap.recycle()
    }
}

private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return this

    val matrix = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
    }

    return Bitmap.createBitmap(
        this,
        0,
        0,
        width,
        height,
        matrix,
        true
    )
}