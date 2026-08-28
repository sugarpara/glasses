package com.example.glasses.inference

import android.content.Context
import java.io.File

object ModelFileProvider {
    fun prepare(context: Context, assetName: String): File {
        require(assetName.endsWith(".tflite")) { "Expected a .tflite model asset" }

        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(directory, assetName)
        val assetLength = context.assets.openFd(assetName).use { it.length }
        if (!target.exists() || target.length() != assetLength) {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        check(target.length() > 0L) {
            "Prepared model is empty: ${target.absolutePath}"
        }
        return target
    }
}
