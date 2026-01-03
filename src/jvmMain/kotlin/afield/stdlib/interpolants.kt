package afield.stdlib

import afield.four.buffers.HasUniform
import afield.four.buffers.ProgressingUniform
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f

interface NeedsStartingValue<T> {
    fun startAt(a: Any?)
}

private class SmoothInterpolant<T>(
    private val durationSeconds: Double?,
    private val steps: Int?,
    private val target: () -> T
) : NeedsStartingValue<T>, HasUniform<T>, ProgressingUniform {

    private var start: Any? = null
    private var startedAt: Double? = null
    private var stepIndex = 0
    private var completed = false

    override val isComplete: Boolean
        get() = completed

    override fun startAt(a: Any?) {
        start = a
        startedAt = null
        stepIndex = 0
        completed = false
    }

    override fun evaluateUniform(): T {
        if (completed) return target()

        val resolvedStart = resolveStart()
        val targetValue = target()

        if (resolvedStart == null) {
            completed = true
            return targetValue
        }

        val nowSeconds = System.nanoTime() / 1_000_000_000.0
        if (startedAt == null) startedAt = nowSeconds

        val alpha = progress(nowSeconds)
        val value = interpolate(resolvedStart, targetValue, alpha)

        if (alpha >= 1.0) completed = true
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun interpolate(startValue: Any?, endValue: T, alpha: Double): T {
        if (alpha <= 0.0) return (startValue ?: endValue) as T
        if (alpha >= 1.0) return endValue

        return when (endValue) {
            is Float -> lerpNumber(startValue, endValue.toDouble(), alpha).toFloat() as T
            is Double -> lerpNumber(startValue, endValue, alpha) as T
            is Int -> lerpNumber(startValue, endValue.toDouble(), alpha).toInt() as T
            is Long -> lerpNumber(startValue, endValue.toDouble(), alpha).toLong() as T
            is Vector2f -> interpolateVector(startValue, endValue, alpha, 2) as T
            is Vector3f -> interpolateVector(startValue, endValue, alpha, 3) as T
            is Vector4f -> interpolateVector(startValue, endValue, alpha, 4) as T
            else -> {
                completed = true
                endValue
            }
        }
    }

    private fun lerpNumber(startValue: Any?, end: Double, alpha: Double): Double {
        val s = (startValue as? Number)?.toDouble()
        if (s == null) {
            completed = true
            return end
        }
        return s + (end - s) * alpha
    }

    private fun interpolateVector(startValue: Any?, end: Any, alpha: Double, dim: Int): Any {
        val startComponents = toComponents(startValue, dim) ?: run {
            completed = true
            return end
        }
        val endComponents = toComponents(end, dim) ?: run {
            completed = true
            return end
        }

        val result = FloatArray(dim) { idx ->
            startComponents[idx] + (endComponents[idx] - startComponents[idx]) * alpha.toFloat()
        }

        return when (dim) {
            2 -> Vector2f(result[0], result[1])
            3 -> Vector3f(result[0], result[1], result[2])
            else -> Vector4f(result[0], result[1], result[2], result[3])
        }
    }

    private fun toComponents(value: Any?, dim: Int): FloatArray? {
        val base = when (value) {
            is Vector2f -> floatArrayOf(value.x, value.y)
            is Vector3f -> floatArrayOf(value.x, value.y, value.z)
            is Vector4f -> floatArrayOf(value.x, value.y, value.z, value.w)
            is Number -> floatArrayOf(value.toFloat())
            null -> return null
            else -> return null
        }
        if (base.size == dim) return base
        val out = FloatArray(dim)
        for (i in 0 until dim) {
            out[i] = if (i < base.size) base[i] else 0f
        }
        return out
    }

    private fun resolveStart(): Any? {
        val s = start
        return when (s) {
            is Function0<*> -> s()
            else -> s
        }
    }

    private fun progress(nowSeconds: Double): Double {
        durationSeconds?.let { duration ->
            if (duration <= 0.0) return 1.0
            val elapsed = nowSeconds - (startedAt ?: nowSeconds)
            return (elapsed / duration).coerceIn(0.0, 1.0)
        }

        val totalSteps = steps?.takeIf { it > 0 } ?: 1
        val fraction = (stepIndex.toDouble() / totalSteps.toDouble()).coerceIn(0.0, 1.0)
        if (stepIndex < totalSteps) stepIndex++
        return fraction
    }
}

fun <T> smoothTo(time: Double? = null, steps: Int? = 100, a: () -> T): () -> T {
    return SmoothInterpolant(time, steps, a)
}

// usage
// val q = smoothTo { vec(1,2,3,4) }
// but really more like:
// m.foo = smoothTo { vec(3,2,1,3 }
// where m is defined lie var Mesh.foo by BoundFromStructure<Vec4>
// and BoundFromStructure is aware of sourceTo and can call startAt
// with the current value
