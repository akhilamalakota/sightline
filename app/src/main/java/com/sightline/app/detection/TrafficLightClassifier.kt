package com.sightline.app.detection

import android.graphics.Bitmap
import android.graphics.Color
import com.sightline.app.TrafficLightColor

/**
 * Classifies the dominant lit color of a traffic-light bbox crop by counting
 * bright, saturated pixels in hue bands for RED / YELLOW / GREEN. The dark
 * housing pixels never qualify (low value), so the lit lamp wins.
 */
object TrafficLightClassifier {

    private const val STRIDE = 2

    fun classify(bitmap: Bitmap, bbox: FloatArray): TrafficLightColor {
        val w = bitmap.width
        val h = bitmap.height

        val left = ((bbox[0] * w).toInt()).coerceIn(0, w - 1)
        val top = ((bbox[1] * h).toInt()).coerceIn(0, h - 1)
        val right = ((bbox[2] * w).toInt()).coerceIn(left + 1, w)
        val bottom = ((bbox[3] * h).toInt()).coerceIn(top + 1, h)

        val regionW = right - left
        val regionH = bottom - top
        if (regionW < 3 || regionH < 3) return TrafficLightColor.UNKNOWN

        var red = 0
        var yellow = 0
        var green = 0

        val hsv = FloatArray(3)
        var x = left
        while (x < right) {
            var y = top
            while (y < bottom) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (sat > 0.5f && value > 0.35f) {
                    when {
                        hue < 12f || hue >= 348f -> red++
                        hue < 40f -> red++          // amber/orange reads as red for pedestrians
                        hue < 75f -> yellow++
                        hue < 160f -> green++
                    }
                }
                y += STRIDE
            }
            x += STRIDE
        }

        val total = red + yellow + green
        if (total < 8) return TrafficLightColor.UNKNOWN

        return when (maxOf(red, yellow, green)) {
            red -> TrafficLightColor.RED
            yellow -> TrafficLightColor.YELLOW
            else -> TrafficLightColor.GREEN
        }
    }
}