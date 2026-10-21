package com.example.whatsapp.data.media

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure sizing math, kept separate from Bitmap code so it is unit-testable on the JVM. */
object ImageSizing {

    /**
     * Largest power-of-two subsample that keeps the long edge >= [maxDimension]. Decoding with it
     * avoids allocating a full 12MP (48MB ARGB) bitmap just to downscale it.
     */
    fun inSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        val longEdge = max(width, height)
        while (longEdge / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    /** Final size with the long edge clamped to [maxDimension], aspect ratio preserved. Never upscales. */
    fun scaledSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= maxDimension) return width to height
        val scale = maxDimension.toFloat() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}
