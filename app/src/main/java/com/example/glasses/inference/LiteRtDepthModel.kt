// Derived from Ultralytics yolo-flutter-app LiteRtModel.kt, AGPL-3.0.
package com.example.glasses.inference

import android.content.Context
import android.util.Log
import com.example.glasses.depth.DepthTensorShape
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

class LiteRtDepthModel(
    private val context: Context,
    modelFile: File,
    preferGpu: Boolean,
) : Closeable {
    private data class Prepared(
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
        val inputWidth: Int,
        val inputHeight: Int,
        val inputUsesNchw: Boolean,
        val outputShape: DepthTensorShape,
    )

    private val model: CompiledModel
    private val inputs: List<TensorBuffer>
    private val outputs: List<TensorBuffer>

    val accelerator: String
    val inputWidth: Int
    val inputHeight: Int
    val inputUsesNchw: Boolean
    val outputShape: DepthTensorShape

    init {
        var prepared: Prepared? = null
        var resolvedAccelerator = "CPU"
        if (preferGpu) {
            try {
                prepared = prepare(modelFile, Accelerator.GPU)
                resolvedAccelerator = "GPU"
            } catch (error: Throwable) {
                Log.w(TAG, "GPU failed; falling back to CPU", error)
            }
        }
        if (prepared == null) {
            prepared = prepare(modelFile, Accelerator.CPU)
        }

        val finalPrepared = requireNotNull(prepared)
        model = finalPrepared.model
        inputs = finalPrepared.inputs
        outputs = finalPrepared.outputs
        inputWidth = finalPrepared.inputWidth
        inputHeight = finalPrepared.inputHeight
        inputUsesNchw = finalPrepared.inputUsesNchw
        outputShape = finalPrepared.outputShape
        accelerator = resolvedAccelerator

        Log.i(
            TAG,
            "LiteRT accelerator=$accelerator input=${inputWidth}x$inputHeight output=$outputShape",
        )
    }

    private fun prepare(file: File, accelerator: Accelerator): Prepared {
        val options = CompiledModel.Options(accelerator)
        if (accelerator == Accelerator.GPU) {
            options.gpuOptions = CompiledModel.GpuOptions(
                serializationDir = context.codeCacheDir.absolutePath,
                modelCacheKey = "${file.name}_${file.length()}",
                serializeProgramCache = true,
            )
        } else {
            options.cpuOptions = CompiledModel.CpuOptions(
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        }

        val compiled = CompiledModel.create(file.absolutePath, options)
        val inputBuffers = try {
            compiled.createInputBuffers()
        } catch (error: Throwable) {
            compiled.close()
            throw error
        }
        val outputBuffers = try {
            compiled.createOutputBuffers()
        } catch (error: Throwable) {
            inputBuffers.forEach { runCatching { it.close() } }
            compiled.close()
            throw error
        }

        try {
            val nativeInputDims = sequenceOf("args_0", "images", "input", "input_1")
                .firstNotNullOfOrNull { name ->
                    runCatching {
                        compiled.getInputTensorType(inputName = name)
                            .layout
                            ?.dimensions
                            ?.toIntArray()
                            ?.takeIf { it.isNotEmpty() }
                    }.getOrNull()
                } ?: inferSquareInputShape(inputBuffers.first().readFloat().size)

            val nchw = nativeInputDims.size == 4 && nativeInputDims[1] == 3
            val inputHeight = if (nchw) nativeInputDims[2] else nativeInputDims[1]
            val inputWidth = if (nchw) nativeInputDims[3] else nativeInputDims[2]
            require(inputWidth > 0 && inputHeight > 0) { "Invalid model input shape" }

            inputBuffers.first().writeFloat(FloatArray(inputWidth * inputHeight * 3))
            compiled.run(inputBuffers, outputBuffers)

            val outputValues = outputBuffers.first().readFloat()
            val outputDims = sequenceOf("output_0", "Identity")
                .firstNotNullOfOrNull { name ->
                    runCatching {
                        compiled.getOutputTensorType(outputName = name)
                            .layout
                            ?.dimensions
                            ?.toIntArray()
                            ?.takeIf { it.isNotEmpty() }
                    }.getOrNull()
                } ?: inferSquareDepthShape(outputValues.size)

            return Prepared(
                model = compiled,
                inputs = inputBuffers,
                outputs = outputBuffers,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                inputUsesNchw = nchw,
                outputShape = DepthTensorShape.parse(outputDims, outputValues.size),
            )
        } catch (error: Throwable) {
            inputBuffers.forEach { runCatching { it.close() } }
            outputBuffers.forEach { runCatching { it.close() } }
            runCatching { compiled.close() }
            throw error
        }
    }

    private fun inferSquareInputShape(elementCount: Int): IntArray {
        val side = sqrt(elementCount / 3.0).roundToInt()
        require(side * side * 3 == elementCount) {
            "Cannot infer square RGB input from $elementCount values"
        }
        return intArrayOf(1, side, side, 3)
    }

    private fun inferSquareDepthShape(elementCount: Int): IntArray {
        val side = sqrt(elementCount.toDouble()).roundToInt()
        require(side * side == elementCount) {
            "Cannot infer square depth output from $elementCount values"
        }
        return intArrayOf(1, side, side)
    }

    fun run(input: FloatArray): FloatArray {
        require(input.size == inputWidth * inputHeight * 3) {
            "Unexpected model input size ${input.size}"
        }
        inputs.first().writeFloat(input)
        model.run(inputs, outputs)
        return outputs.first().readFloat()
    }

    override fun close() {
        inputs.forEach { runCatching { it.close() } }
        outputs.forEach { runCatching { it.close() } }
        runCatching { model.close() }
    }

    companion object {
        private const val TAG = "LiteRtDepthModel"
    }
}
