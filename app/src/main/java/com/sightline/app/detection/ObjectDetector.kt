package com.sightline.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.sightline.app.DetectedObject
import com.sightline.app.Direction
import com.sightline.app.DistanceZone
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps a YOLOv8n TFLite model and runs inference on RGB bitmaps.
 *
 * Expected model I/O (standard YOLOv8 TFLite export):
 *   Input:  [1, 320, 320, 3]  float32 normalised 0-1
 *   Output: [1, 5+numClasses, 8400]  — raw detections (transposed YOLOv8 format)
 *
 * If your export gives a different shape, adjust processOutput() accordingly.
 */
class ObjectDetector(
    context: Context,
    private val modelPath: String = "yolov8n.tflite",
    private val inputSize: Int = 320,
    private val confidenceThreshold: Float = 0.45f,
) {
    private var interpreter: Interpreter? = null

    /** COCO class labels — YOLOv8n default. Replace if using custom model. */
    val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
        "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
        "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
        "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush"
    )

    fun init(): Boolean {
        return try {
            val opts = Interpreter.Options().apply {
                setNumThreads(4)
                // GPU delegate disabled — version mismatch causes crash on many devices.
                // CPU inference is fast enough for YOLOv8n at 320x320.
            }
            val model = loadModelFile(modelPath)
            interpreter = Interpreter(model, opts)
            android.util.Log.i("ObjectDetector", "TFLite interpreter initialized OK")
            true
        } catch (e: Exception) {
            android.util.Log.e("ObjectDetector", "Init failed", e)
            false
        }
    }

    /**
     * Run detection on a bitmap. Returns a list of DetectedObject with normalised bboxes.
     * This is the synchronous hot path — call from the ImageAnalysis executor.
     */
    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val interp = interpreter ?: return emptyList()

        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = bitmapToByteBuffer(resized)
        resized.recycle()

        // YOLOv8 TFLite output shape depends on input size:
        //   640 input → [1, 84, 8400]
        //   320 input → [1, 84, 2100]
        val numClasses = labels.size
        val numAnchors = (inputSize / 8) * (inputSize / 8) + (inputSize / 16) * (inputSize / 16) + (inputSize / 32) * (inputSize / 32)
        val outputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }

        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            android.util.Log.e("ObjectDetector", "Inference failed", e)
            return emptyList()
        }

        return processOutput(outputBuffer[0], numClasses, bitmap.width, bitmap.height)
    }

    /**
     * Parse YOLOv8 transposed output into DetectedObjects.
     *
     * YOLOv8 TFLite output shape: [5+nc, 8400]
     *   Row 0: x centre (normalised to input)
     *   Row 1: y centre
     *   Row 2: width
     *   Row 3: height
     *   Row 4: objectness score
     *   Row 5+: class scores
     */
    private fun processOutput(
        output: Array<FloatArray>,  // [4+nc, numAnchors]
        numClasses: Int,
        origWidth: Int,
        origHeight: Int,
    ): List<DetectedObject> {
        val detections = mutableListOf<DetectedObject>()
        val numAnchors = output[0].size

        for (i in 0 until numAnchors) {
            // YOLOv8 TFLite output: rows 0-3 are x,y,w,h (pixel coords), row 4+ are class scores
            // There is NO separate objectness score — class scores are direct
            var bestClass = 0
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = output[4 + c][i]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < confidenceThreshold) continue

            // Decode bbox: pixel coords → normalised 0-1
            val cx = output[0][i] / inputSize
            val cy = output[1][i] / inputSize
            val w = output[2][i] / inputSize
            val h = output[3][i] / inputSize

            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            val label = if (bestClass < labels.size) labels[bestClass] else "object"

            // Direction from bbox centre-x
            val direction = when {
                cx < 0.33f -> Direction.LEFT
                cx > 0.66f -> Direction.RIGHT
                else -> Direction.CENTER
            }

            // Distance zone from bbox height as fraction of frame
            val heightFraction = h
            val distanceZone = when {
                heightFraction > 0.35f -> DistanceZone.NEAR
                heightFraction > 0.15f -> DistanceZone.MID
                else -> DistanceZone.FAR
            }

            detections.add(
                DetectedObject(
                    label = label,
                    bbox = floatArrayOf(left, top, right, bottom),
                    confidence = bestScore,
                    distanceMeters = null,
                    distanceZone = distanceZone,
                    direction = direction,
                )
            )
        }

        // Non-maximum suppression
        return nms(detections, iouThreshold = 0.45f)
    }

    /** Simple NMS over all detections. */
    private fun nms(detections: List<DetectedObject>, iouThreshold: Float): List<DetectedObject> {
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<DetectedObject>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            result.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].label == sorted[j].label && iou(sorted[i].bbox, sorted[j].bbox) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return result
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = maxOf(a[0], b[0])
        val y1 = maxOf(a[1], b[1])
        val x2 = minOf(a[2], b[2])
        val y2 = minOf(a[3], b[3])
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)  // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)   // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)            // B
        }
        return buffer
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val assetFileDescriptor = appContext.assets.openFd(filename)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    private val appContext = context.applicationContext

    fun close() {
        interpreter?.close()
    }
}
