package com.sightline.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.min

/**
 * Wraps a YOLOv8n TFLite model and runs inference on RGB bitmaps using
 * aspect-preserving letterboxing (pad, don't stretch).
 *
 * Expected model I/O (standard YOLOv8 TFLite export):
 *   Input:  [1, 640, 640, 3]  float32 normalised 0-1
 *   Output: [1, 84, 8400]     raw detections (transposed YOLOv8 format)
 *
 * Coordinate convention is auto-detected at init(): newer onnx2tf export
 * stacks emit box coordinates as 0-1 fractions of the input image, older
 * stacks emit pixel coordinates (0..inputSize). Both are handled.
 */
class ObjectDetector(
    context: Context,
    private val modelPath: String = "yolov8n.tflite",
    private val inputSize: Int = 640,
    private val confidenceThreshold: Float = 0.30f,
) {
    private var interpreter: Interpreter? = null

    /** True = model emits box coords as 0-1 fractions; false = pixel coords (0..inputSize). */
    private var coordsNormalized = true

    // Reused buffers keep the per-frame hot path allocation-free.
    private var scratchScaled: Bitmap? = null
    private var scratchCanvas: Bitmap? = null
    private var pixelsScratch: IntArray? = null
    private val letterboxPaint = Paint().apply { isFilterBitmap = true }

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
                // CPU inference is fast enough for YOLOv8n at 640x640 on modern phones.
            }
            val model = loadModelFile(modelPath)
            interpreter = Interpreter(model, opts)
            coordsNormalized = detectCoordConvention()
            android.util.Log.i("ObjectDetector", "TFLite interpreter initialized OK (input ${inputSize}x$inputSize)")
            true
        } catch (e: Exception) {
            android.util.Log.e("ObjectDetector", "Init failed", e)
            false
        }
    }

    /**
     * Probe the exported model once at startup to learn whether it emits box
     * coordinates as 0-1 fractions or as pixels. The export stack changed
     * behaviour between versions, so we detect rather than assume.
     */
    private fun detectCoordConvention(): Boolean {
        val interp = interpreter ?: return true
        return try {
            val numAnchors = anchorCount(inputSize)
            val probe = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
            var seed = 123456789L
            repeat(inputSize * inputSize * 3) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
                probe.putFloat((seed % 1000) / 1000f)
            }
            val out = Array(1) { Array(4 + labels.size) { FloatArray(numAnchors) } }
            interp.run(probe, out)
            val maxCoord = out[0][0].maxOrNull() ?: 0f
            val normalized = maxCoord <= 2.0f
            android.util.Log.i(
                "ObjectDetector",
                "Output coord convention: ${if (normalized) "normalized 0-1" else "pixels (0..$inputSize)"} (max coord=$maxCoord)"
            )
            normalized
        } catch (e: Exception) {
            android.util.Log.e("ObjectDetector", "Coord convention probe failed", e)
            true
        }
    }

    /**
     * Run detection on a bitmap. Returns a list of DetectedObject with normalised bboxes
     * relative to the ORIGINAL (pre-letterbox) image. Synchronous hot path — call from
     * the ImageAnalysis executor.
     */
    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val interp = interpreter ?: return emptyList()
        val ow = bitmap.width
        val oh = bitmap.height
        val scale = min(inputSize.toFloat() / ow, inputSize.toFloat() / oh)
        val scaledW = (ow * scale).toInt().coerceAtLeast(1)
        val scaledH = (oh * scale).toInt().coerceAtLeast(1)
        val padX = ((inputSize - scaledW) / 2f).toInt()
        val padY = ((inputSize - scaledH) / 2f).toInt()

        // Aspect-preserving scale into a letterboxed square.
        val scaled: Bitmap
        val existing = scratchScaled
        if (existing != null && existing.width == scaledW && existing.height == scaledH) {
            Canvas(existing).drawBitmap(
                bitmap, null, RectF(0f, 0f, scaledW.toFloat(), scaledH.toFloat()), letterboxPaint
            )
            scaled = existing
        } else {
            scratchScaled?.recycle()
            scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
            scratchScaled = scaled
        }

        // Letterbox: pad the scaled image onto a gray square.
        val canvasBmp = scratchCanvas
            ?: Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888).also { scratchCanvas = it }
        val c = Canvas(canvasBmp)
        c.drawColor(Color.rgb(114, 114, 114)) // standard YOLO letterbox fill (0.447 after /255)
        c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), letterboxPaint)

        val inputBuffer = bitmapToByteBuffer(canvasBmp)

        val numClasses = labels.size
        val numAnchors = anchorCount(inputSize)
        val outputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }

        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            android.util.Log.e("ObjectDetector", "Inference failed", e)
            return emptyList()
        }

        return processOutput(
            outputBuffer[0], numClasses, ow, oh,
            scale = scale, padX = padX.toFloat(), padY = padY.toFloat(),
        )
    }

    private fun anchorCount(size: Int): Int =
        (size / 8) * (size / 8) + (size / 16) * (size / 16) + (size / 32) * (size / 32)

    /**
     * Parse YOLOv8 transposed output into DetectedObjects.
     *
     * YOLOv8 TFLite output shape: [4+nc, numAnchors]
     *   Row 0: x centre   Row 1: y centre   Row 2: width   Row 3: height
     *   Row 4+: class scores (no separate objectness — class scores are direct)
     *
     * Coordinates are mapped from letterboxed input space back to the original
     * image's normalised space.
     */
    private fun processOutput(
        output: Array<FloatArray>,  // [4+nc, numAnchors]
        numClasses: Int,
        origW: Int,
        origH: Int,
        scale: Float,
        padX: Float,
        padY: Float,
    ): List<DetectedObject> {
        val detections = mutableListOf<DetectedObject>()
        val numAnchors = output[0].size
        val scaleW = (scale * origW).coerceAtLeast(1f)
        val scaleH = (scale * origH).coerceAtLeast(1f)

        for (i in 0 until numAnchors) {
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

            // Decode bbox centre/size from the model's emitted convention.
            val cx = if (coordsNormalized) output[0][i] * inputSize else output[0][i]
            val cy = if (coordsNormalized) output[1][i] * inputSize else output[1][i]
            val w = if (coordsNormalized) output[2][i] * inputSize else output[2][i]
            val h = if (coordsNormalized) output[3][i] * inputSize else output[3][i]

            // Map from letterboxed input space back to the original image (normalised 0-1).
            val left = (((cx - w / 2f) - padX) / scaleW).coerceIn(0f, 1f)
            val top = (((cy - h / 2f) - padY) / scaleH).coerceIn(0f, 1f)
            val right = (((cx + w / 2f) - padX) / scaleW).coerceIn(0f, 1f)
            val bottom = (((cy + h / 2f) - padY) / scaleH).coerceIn(0f, 1f)

            val label = if (bestClass < labels.size) labels[bestClass] else "object"

            // Direction from bbox centre-x
            val centerX = (left + right) / 2f
            val direction = when {
                centerX < 0.33f -> Direction.LEFT
                centerX > 0.66f -> Direction.RIGHT
                else -> Direction.CENTER
            }

            // Distance zone from bbox height as fraction of frame
            val heightFraction = bottom - top
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
        val pixels = pixelsScratch
            ?: IntArray(inputSize * inputSize).also { pixelsScratch = it }
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