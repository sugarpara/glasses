package com.example.glasses.audio

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Hrtf64Metadata(
    val formatVersion: Int,
    val sourceSofa: String,
    val rows: Int,
    val columns: Int,
    val receivers: Int,
    val hrirLength: Int,
    val sampleRateHz: Int,
    val binaryLayout: String
)

internal data class Hrtf64Pair(
    /** SOFA receiver 0. This dataset describes receiver 0 as the left ear. */
    val receiver0: FloatArray,
    /** SOFA receiver 1. This dataset describes receiver 1 as the right ear. */
    val receiver1: FloatArray
)

/** Loads the complete 64x64 grid once and serves row/column HRIRs from memory. */
internal class Hrtf64Repository(
    context: Context,
    private val binaryAssetPath: String = "hrtf_grid64.bin",
    private val metadataAssetPath: String = "hrtf_grid64_meta.json"
) {

    private val assets = context.applicationContext.assets
    val metadata: Hrtf64Metadata = loadAndValidateMetadata()
    private val values: FloatArray = loadAndValidateBinary()

    fun getOriginalReceiverPair(row: Int, column: Int): Hrtf64Pair {
        require(row in 0 until metadata.rows) { "HRTF row must be in 0..${metadata.rows - 1}" }
        require(column in 0 until metadata.columns) {
            "HRTF column must be in 0..${metadata.columns - 1}"
        }

        return Hrtf64Pair(
            receiver0 = copyReceiver(row, column, 0),
            receiver1 = copyReceiver(row, column, 1)
        )
    }

    private fun copyReceiver(row: Int, column: Int, receiver: Int): FloatArray {
        val start = (((row * metadata.columns + column) * metadata.receivers + receiver) *
            metadata.hrirLength)
        return values.copyOfRange(start, start + metadata.hrirLength)
    }

    private fun loadAndValidateMetadata(): Hrtf64Metadata {
        val text = assets.open(metadataAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        val metadata = Hrtf64Metadata(
            formatVersion = json.getInt("format_version"),
            sourceSofa = json.getString("source_sofa"),
            rows = json.getInt("rows"),
            columns = json.getInt("columns"),
            receivers = json.getInt("receivers"),
            hrirLength = json.getInt("hrir_length"),
            sampleRateHz = json.getInt("sample_rate_hz"),
            binaryLayout = json.getString("binary_layout")
        )

        check(metadata.formatVersion == 1) {
            "Expected HRTF metadata format version 1, got ${metadata.formatVersion}"
        }
        check(metadata.sourceSofa.isNotBlank()) { "HRTF source_sofa must not be blank" }
        check(metadata.rows == GLASSES64_ROWS) { "Expected 64 HRTF rows, got ${metadata.rows}" }
        check(metadata.columns == GLASSES64_COLUMNS) {
            "Expected 64 HRTF columns, got ${metadata.columns}"
        }
        check(metadata.receivers == 2) { "Expected 2 HRTF receivers, got ${metadata.receivers}" }
        check(metadata.hrirLength == GLASSES64_HRIR_LENGTH) {
            "Expected HRIR length 256, got ${metadata.hrirLength}"
        }
        check(metadata.sampleRateHz == GLASSES64_SAMPLE_RATE) {
            "Expected 48000 Hz, got ${metadata.sampleRateHz}"
        }
        check(metadata.binaryLayout == "[row][column][receiver][sample]") {
            "Unexpected HRTF binary layout: ${metadata.binaryLayout}"
        }
        return metadata
    }

    private fun loadAndValidateBinary(): FloatArray {
        val expectedFloatCount = metadata.rows * metadata.columns * metadata.receivers *
            metadata.hrirLength
        val expectedByteCount = expectedFloatCount * Float.SIZE_BYTES
        val bytes = assets.open(binaryAssetPath).use { it.readBytes() }
        check(bytes.size == expectedByteCount) {
            "$binaryAssetPath has ${bytes.size} bytes; expected $expectedByteCount"
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = FloatArray(expectedFloatCount)
        for (index in output.indices) {
            val value = buffer.float
            check(value.isFinite()) { "$binaryAssetPath contains a non-finite value at $index" }
            output[index] = value
        }
        return output
    }
}
