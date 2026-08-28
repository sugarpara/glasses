package com.example.glasses.inference

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtDepthModelTest {
    @Test
    fun loadsModelAndRunsOneInference() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = ModelFileProvider.prepare(context, "yolo26n-depth_w8a32.tflite")

        LiteRtDepthModel(context, file, preferGpu = true).use { model ->
            val output = model.run(FloatArray(model.inputWidth * model.inputHeight * 3))

            assertEquals(model.outputShape.width * model.outputShape.height, output.size)
            assertTrue(model.accelerator == "GPU" || model.accelerator == "CPU")
            assertTrue(output.all { it.isFinite() })
        }
    }
}
