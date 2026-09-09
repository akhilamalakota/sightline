package com.sightline.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Bridges CameraX ImageProxy → Bitmap for the detection pipeline.
 *
 * ImageProxy gives us a YUV_420_888 frame. We need RGB Bitmap for TFLite.
 * This conversion runs on the ImageAnalysis background executor.
 */
object FrameConverter {

    /**
     * Convert a CameraX ImageProxy to a Bitmap suitable for inference.
     * Handles rotation so the bitmap matches the device's current orientation.
     */
    fun toBitmap(image: ImageProxy): Bitmap? {
        return try {
            val nv21 = yuv420888ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val bytes = out.toByteArray()

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // Rotate to match display orientation
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Convert YUV_420_888 ImageProxy to NV21 byte array for YuvImage compression.
     * This is the fastest path on Android — no external library needed.
     */
    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave V and U planes for NV21 format
        val uvPixelStride = image.planes[1].pixelStride
        val uvRowStride = image.planes[1].rowStride
        val uvWidth = image.width / 2
        val uvHeight = image.height / 2

        var pos = ySize
        if (uvPixelStride == 2) {
            // UV planes are interleaved (common on most devices)
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uIdx = row * uvRowStride + col * uvPixelStride
                    val vIdx = uIdx + 1
                    if (uIdx < vBuffer.capacity()) nv21[pos++] = vBuffer.get(uIdx)
                    if (vIdx < uBuffer.capacity()) nv21[pos++] = uBuffer.get(vIdx)
                }
            }
        } else {
            // UV planes are separate
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uIdx = row * uvRowStride + col
                    val vIdx = row * uvRowStride + col
                    if (uIdx < uBuffer.capacity()) nv21[pos++] = vBuffer.get(uIdx)
                    if (vIdx < vBuffer.capacity()) nv21[pos++] = uBuffer.get(vIdx)
                }
            }
        }

        return nv21
    }
}
