package com.sightline.app.detection

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Samples the centre half of a frame (what the user is pointing at) and
 * returns the dominant colour name plus overall brightness. Two questions in
 * one: "what colour is this?" and "is the light on?". Pure pixel math, no ML.
 */
object ColorLightDetector {

    private const val STRIDE = 4

    data class Result(
        val colorName: String,
        val isBright: Boolean,
        val isDark: Boolean,
    )

    fun detect(bitmap: Bitmap): Result {
        val w = bitmap.width
        val h = bitmap.height
        val left = w / 4
        val top = h / 4
        val right = w * 3 / 4
        val bottom = h * 3 / 4

        val hueBands = IntArray(6) // ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK
        var saturated = 0
        var sampled = 0
        var sumValue = 0f

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
                sumValue += value
                sampled++
                if (sat > 0.25f && value > 0.3f) {
                    saturated++
                    when {
                        hue < 35f || hue >= 348f -> hueBands[0]++
                        hue < 62f -> hueBands[1]++
                        hue < 150f -> hueBands[2]++
                        hue < 258f -> hueBands[3]++
                        hue < 295f -> hueBands[4]++
                        else -> hueBands[5]++
                    }
                }
                y += STRIDE
            }
            x += STRIDE
        }

        if (sampled == 0) return Result("gray", isBright = false, isDark = true)

        val meanValue = sumValue / sampled
        val isBright = meanValue > 0.45f
        val isDark = meanValue < 0.22f
        val saturationRatio = saturated.toFloat() / sampled

        val colorName = when {
            saturationRatio < 0.04f -> when {
                meanValue > 0.78f -> "white"
                meanValue < 0.18f -> "black"
                else -> "gray"
            }
            else -> {
                val best = hueBands.indices.maxByOrNull { hueBands[it] } ?: 0
                when (best) {
                    0 -> "orange"
                    1 -> "yellow"
                    2 -> "green"
                    3 -> "blue"
                    4 -> "purple"
                    else -> "pink"
                }
            }
        }

        return Result(colorName, isBright, isDark)
    }
}