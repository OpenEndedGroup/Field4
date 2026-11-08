package afield.graphics.scene.reading

import kotlin.math.*

/** Cached text masks are local to the word, never a previous-eye image. */
data class ReadingTextBlurConfig(
    val width: Int = 2048,
    val height: Int = 1024,
    val minimumSize: Int = 128,
) {
    init {
        require(width >= minimumSize && height >= minimumSize && minimumSize >= 32)
        require(width <= 8192 && height <= 8192)
    }
}

/**
 * Variance-matched interpolation between Gaussian masks. The narrowest interval uses analytic Slug.
 */
internal object ReadingBlurLevels {
    const val count = 16

    data class Mix(val low: Int, val high: Int, val fraction: Float, val cached: Float)

    fun sigma(level: Int): Float {
        require(level in 0 until count)
        return if (level == 0) 0f else 2f.pow((level - (count - 1)) * .5f)
    }

    fun mix(radius: Float, capacity: Float): Mix {
        require(radius.isFinite() && radius >= 0f && capacity.isFinite() && capacity > 0f)
        require(radius <= capacity * 1.00001f) {
            "Text blur $radius exceeds prepared capacity $capacity; rebuild Drawing"
        }
        val r = (radius / capacity).coerceIn(0f, 1f)
        if (r == 0f) return Mix(1, 1, 0f, 0f)
        if (r < sigma(1)) return Mix(1, 1, 0f, (r / sigma(1)).pow(2))
        val low = (count - 1 + 2 * log2(r)).toInt().coerceIn(1, count - 1)
        val high = (low + 1).coerceAtMost(count - 1)
        val a = sigma(low).pow(2)
        val b = sigma(high).pow(2)
        return Mix(low, high, if (b > a) ((r * r - a) / (b - a)).coerceIn(0f, 1f) else 0f, 1f)
    }

    fun size(base: Int, level: Int, minimum: Int) = max(minimum, base / (1 shl ((level + 1) / 2)))

    fun increment(level: Int) = sqrt(max(0f, sigma(level).pow(2) - sigma(level - 1).pow(2)))
}
