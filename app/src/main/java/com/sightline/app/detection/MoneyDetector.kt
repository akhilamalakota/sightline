package com.sightline.app.detection

import android.content.Context
import android.graphics.Bitmap
import com.sightline.app.DetectedObject

/**
 * Indian banknote detector for MONEY mode. Wraps the shared YOLOv8 TFLite
 * pipeline with the 7-class currency model.
 *
 * IMPORTANT: label order must EXACTLY match the exported model's class ids —
 * the model was trained with {0:'10', 1:'100', 2:'20', 3:'200', 4:'2000',
 * 5:'50', 6:'500'} (verified from yolov8n_currency_best.pt via model.names).
 */
class MoneyDetector(context: Context) {

    private val detector = ObjectDetector(
        context = context,
        modelPath = "currency.tflite",
        inputSize = 320,
        confidenceThreshold = 0.35f,
        labels = listOf("10", "100", "20", "200", "2000", "50", "500"),
    )

    fun init(): Boolean = detector.init()

    fun detect(bitmap: Bitmap): List<DetectedObject> = detector.detect(bitmap)
}