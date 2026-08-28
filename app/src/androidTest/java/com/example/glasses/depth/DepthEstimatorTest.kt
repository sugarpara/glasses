package com.example.glasses.depth

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.glasses.inference.LiteRtDepthModel
import com.example.glasses.inference.ModelFileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepthEstimatorTest {
    @Test
    fun convertsBitmapIntoDepthFrame() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = ModelFileProvider.prepare(context, "yolo26n-depth_w8a32.tflite")
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val value = x * 255 / (source.width - 1)
                source.setPixel(x, y, Color.rgb(value, value, value))
            }
        }

        DepthEstimator(LiteRtDepthModel(context, file, preferGpu = true)).use { estimator ->
            val frame = estimator.predict(source)

            assertTrue(frame.bitmap.width > 0)
            assertTrue(frame.bitmap.height > 0)
            assertTrue(frame.minDepth.isFinite())
            assertTrue(frame.maxDepth.isFinite())
            assertTrue(frame.accelerator == "GPU" || frame.accelerator == "CPU")
            assertTrue(frame.preProcessMs >= 0.0)
            assertTrue(frame.inferenceMs >= 0.0)
            assertTrue(frame.postProcessMs >= 0.0)
            assertEquals(
                frame.preProcessMs + frame.inferenceMs + frame.postProcessMs,
                frame.totalMs,
                0.0,
            )
            assertEquals(frame.bitmap.width * frame.bitmap.height, frame.bitmap.byteCount / 4)
            frame.bitmap.recycle()
        }
        source.recycle()
    }
}
