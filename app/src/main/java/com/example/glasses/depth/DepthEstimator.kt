package com.example.glasses.depth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.example.glasses.inference.LiteRtDepthModel
import java.io.Closeable

class DepthEstimator(
    private val model: LiteRtDepthModel,
) : Closeable {
    private val modelBitmap = Bitmap.createBitmap(
        model.inputWidth,
        model.inputHeight,
        Bitmap.Config.ARGB_8888,
    )
    private val inputPixels = IntArray(model.inputWidth * model.inputHeight)
    private val inputFloats = FloatArray(model.inputWidth * model.inputHeight * 3)
    private val outputPixels = IntArray(model.outputShape.width * model.outputShape.height)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun predict(source: Bitmap): DepthFrame {
        val start = System.nanoTime()
        Canvas(modelBitmap).drawBitmap(
            source,
            null,
            Rect(0, 0, model.inputWidth, model.inputHeight),
            paint,
        )
        modelBitmap.getPixels(
            inputPixels,
            0,
            model.inputWidth,
            0,
            0,
            model.inputWidth,
            model.inputHeight,
        )
        writeNormalizedRgb(inputPixels, inputFloats, model.inputUsesNchw)
        val afterPre = System.nanoTime()

        val output = model.run(inputFloats)
        val afterInference = System.nanoTime()

        val range = DepthColorizer.colorize(output, outputPixels)
        val depthBitmap = Bitmap.createBitmap(
            outputPixels,
            model.outputShape.width,
            model.outputShape.height,
            Bitmap.Config.ARGB_8888,
        )
        val afterPost = System.nanoTime()

        return DepthFrame(
            bitmap = depthBitmap,
            accelerator = model.accelerator,
            minDepth = range.min,
            maxDepth = range.max,
            preProcessMs = nanosToMs(afterPre - start),
            inferenceMs = nanosToMs(afterInference - afterPre),
            postProcessMs = nanosToMs(afterPost - afterInference),
        )
    }

    private fun writeNormalizedRgb(
        pixels: IntArray,
        output: FloatArray,
        nchw: Boolean,
    ) {
        val planeSize = pixels.size
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel shr 16) and 0xFF) / 255f
            val green = ((pixel shr 8) and 0xFF) / 255f
            val blue = (pixel and 0xFF) / 255f
            if (nchw) {
                output[index] = red
                output[planeSize + index] = green
                output[2 * planeSize + index] = blue
            } else {
                val offset = index * 3
                output[offset] = red
                output[offset + 1] = green
                output[offset + 2] = blue
            }
        }
    }

    private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

    override fun close() {
        modelBitmap.recycle()
        model.close()
    }
}
