@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.audio

import java.util.concurrent.ForkJoinPool

class ResonatorBankArray(
    frequencies: FloatArray,
    alphas: FloatArray,
    betas: FloatArray = alphas,
    private val sampleRate: Float
) {
    val resonators: Array<Resonator> = Array(frequencies.size) { i ->
        Resonator(frequencies[i], alphas[i], betas[i], sampleRate)
    }

    val size: Int
        get() = resonators.size

    init {
        require(frequencies.size == alphas.size) { "frequencies and alphas must have same size" }
        require(alphas.size == betas.size) { "alphas and betas must have same size" }
        require(sampleRate > 0.0f) { "sampleRate must be > 0" }
    }

    fun update(sample: Float) {
        for (resonator in resonators) {
            resonator.update(sample)
        }
    }

    fun update(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset, stride: Int = 1) {
        for (resonator in resonators) {
            resonator.update(samples, offset, length, stride)
        }
    }

    fun updateConcurrent(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
        stride: Int = 1,
        taskCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ) {
        updateConcurrentResonators(resonators, taskCount) { resonator ->
            resonator.update(samples, offset, length, stride)
        }
    }

    fun getComplex(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size * 2) { "Buffer passed to getComplex() is not large enough" }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].real
            dest[offset + size + i] = resonators[i].imag
        }
    }

    fun getPowers(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size) { "Buffer passed to getPowers() is not large enough" }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].power
        }
    }

    fun getAmplitudes(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size) { "Buffer passed to getAmplitudes() is not large enough" }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].amplitude
        }
    }
}

class TrackingResonatorBankArray(
    naturalFrequencies: FloatArray,
    alphas: FloatArray,
    betas: FloatArray = alphas,
    private val sampleRate: Float,
    gammas: FloatArray = trackingResonatorGammaHeuristics(naturalFrequencies, sampleRate)
) {
    val resonators: Array<TrackingResonator> = Array(naturalFrequencies.size) { i ->
        TrackingResonator(naturalFrequencies[i], alphas[i], betas[i], sampleRate, gammas[i])
    }

    val size: Int
        get() = resonators.size

    init {
        require(naturalFrequencies.size == alphas.size) { "frequencies and alphas must have same size" }
        require(alphas.size == betas.size) { "alphas and betas must have same size" }
        require(betas.size == gammas.size) { "betas and gammas must have same size" }
        require(sampleRate > 0.0f) { "sampleRate must be > 0" }
    }

    fun update(sample: Float) {
        for (resonator in resonators) {
            resonator.update(sample)
        }
    }

    fun update(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset, stride: Int = 1) {
        for (resonator in resonators) {
            resonator.update(samples, offset, length, stride)
        }
    }

    fun updateConcurrent(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
        stride: Int = 1,
        taskCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ) {
        updateConcurrentResonators(resonators, taskCount) { resonator ->
            resonator.update(samples, offset, length, stride)
        }
    }

    fun setPowerTimeConstant(tau: Float = 1.0f) {
        for (resonator in resonators) {
            resonator.setPowerTimeConstant(tau)
        }
    }

    fun getComplex(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size * 2) { "Buffer passed to getComplex() is not large enough" }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].real
            dest[offset + size + i] = resonators[i].imag
        }
    }

    fun getPowers(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size) { "Buffer passed to getPowers() is not large enough" }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].power
        }
    }

    fun getAmplitudes(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size) { "Buffer passed to getAmplitudes() is not large enough" }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].amplitude
        }
    }

    fun getResonantFrequencies(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= size) {
            "Buffer passed to getResonantFrequencies() is not large enough"
        }
        for (i in resonators.indices) {
            dest[offset + i] = resonators[i].resonantFrequency
        }
    }
}

private inline fun <T> updateConcurrentResonators(
    resonators: Array<T>,
    taskCount: Int,
    crossinline update: (T) -> Unit
) {
    require(taskCount > 0) { "taskCount must be > 0" }
    if (resonators.isEmpty()) return

    val chunkSize = ((resonators.size + taskCount - 1) / taskCount).coerceAtLeast(1)
    val tasks = ArrayList<java.util.concurrent.ForkJoinTask<*>>()
    var start = 0
    while (start < resonators.size) {
        val chunkStart = start
        val chunkEnd = (chunkStart + chunkSize).coerceAtMost(resonators.size)
        tasks += ForkJoinPool.commonPool().submit {
            for (i in chunkStart until chunkEnd) {
                update(resonators[i])
            }
        }
        start = chunkEnd
    }
    tasks.forEach { it.join() }
}
