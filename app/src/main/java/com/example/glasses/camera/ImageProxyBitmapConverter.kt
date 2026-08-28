package com.example.glasses.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy

object ImageProxyBitmapConverter {
    fun toUprightBitmap(image: ImageProxy): Bitmap {
        require(image.format == PixelFormat.RGBA_8888) {
            "Expected RGBA_8888 ImageProxy, got ${image.format}"
        }

        val plane = image.planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride == RGBA_PIXEL_STRIDE) {
            "Expected RGBA pixel stride $RGBA_PIXEL_STRIDE, got $pixelStride"
        }
        require(rowStride >= pixelStride * image.width && rowStride % pixelStride == 0) {
            "Invalid RGBA row stride $rowStride for image width ${image.width}"
        }

        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        val buffer = plane.buffer.duplicate().apply { rewind() }
        padded.copyPixelsFromBuffer(buffer)

        val cropped = if (paddedWidth == image.width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                padded.recycle()
            }
        }

        val rotation = image.imageInfo.rotationDegrees
        require(rotation % 90 == 0) { "Unsupported image rotation: $rotation" }
        if (rotation == 0) return cropped

        return Bitmap.createBitmap(
            cropped,
            0,
            0,
            cropped.width,
            cropped.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        ).also { upright ->
            if (upright !== cropped) cropped.recycle()
        }
    }

    private const val RGBA_PIXEL_STRIDE = 4
}
