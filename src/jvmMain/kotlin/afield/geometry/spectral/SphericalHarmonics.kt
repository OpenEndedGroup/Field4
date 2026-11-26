@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real spherical harmonics for a three-coordinate map defined on the unit sphere.
 * Modes are ordered by degree and then by order from -degree to +degree. Negative
 * orders use sine and positive orders use cosine. The basis is orthonormal for
 * the sphere's area measure divided by its total area.
 */
class SphericalHarmonics(val maxDegree: Int) {
    init {
        require(maxDegree in 0..32)
    }

    private val modes = (0..maxDegree).flatMap { degree ->
        List(2 * degree + 1) { SpectralMode(sqrt(degree * (degree + 1.0))) }
    }
    private val normalization = Array(maxDegree + 1) { degree ->
        DoubleArray(degree + 1).apply {
            this[0] = sqrt(2.0 * degree + 1)
            if (degree > 0) this[1] = this[0] * sqrt(2.0 / (degree * (degree + 1.0)))
            for (order in 2..degree) {
                this[order] = this[order - 1] / sqrt((degree - order + 1.0) * (degree + order))
            }
        }
    }

    /**
     * Project a spherical parameterization using Gauss–Legendre latitude weights and a uniform
     * azimuth grid. Increase [latitudes] to resolve finer detail in the input before truncation.
     */
    fun fit(latitudes: Int = maxOf(16, 2 * (maxDegree + 1)), sample: (Vec3) -> Vec3): Spectrum {
        require(latitudes > maxDegree && latitudes <= Int.MAX_VALUE / 2)
        val azimuths = 2 * latitudes
        val coefficients = DoubleArray(modes.size * 6)
        val basis = DoubleArray(modes.size)
        for ((z, weight) in gaussLegendre(latitudes)) {
            val radius = sqrt(1 - z * z)
            for (i in 0 until azimuths) {
                val phi = TAU * i / azimuths
                val point = sample(Vec3((radius * cos(phi)).toFloat(), (radius * sin(phi)).toFloat(), z.toFloat()))
                require(point.isFinite()) { "The spherical parameterization returned a non-finite position" }
                fillBasis(z, phi, basis)
                for (mode in basis.indices) {
                    val weighted = basis[mode] * weight / (2 * azimuths)
                    coefficients[6 * mode] += point.x * weighted
                    coefficients[6 * mode + 1] += point.y * weighted
                    coefficients[6 * mode + 2] += point.z * weighted
                }
            }
        }
        return Spectrum(3, modes, coefficients, this)
    }

    fun evaluate(spectrum: Spectrum, direction: Vec3, throughDegree: Int = maxDegree): Vec3 {
        require(spectrum.domain === this && throughDegree in 0..maxDegree)
        val x = direction.x.toDouble()
        val y = direction.y.toDouble()
        val z = direction.z.toDouble()
        val length = sqrt(x * x + y * y + z * z)
        require(length.isFinite() && length > 0)
        val basis = DoubleArray(modes.size)
        fillBasis(z / length, atan2(y, x), basis)
        val sum = DoubleArray(3)
        for (mode in 0 until (throughDegree + 1) * (throughDegree + 1)) {
            spectrum.accumulate(mode, basis[mode], 0.0, sum)
        }
        return Vec3(sum[0].toFloat(), sum[1].toFloat(), sum[2].toFloat())
    }

    private fun fillBasis(z: Double, phi: Double, basis: DoubleArray) {
        val radius = sqrt((1 - z * z).coerceAtLeast(0.0))
        var diagonal = 1.0
        for (order in 0..maxDegree) {
            if (order > 0) diagonal *= -(2 * order - 1) * radius
            val cosine = cos(order * phi)
            val sine = sin(order * phi)
            var previous = 0.0
            var current = diagonal
            for (degree in order..maxDegree) {
                val center = degree * (degree + 1)
                val value = current * normalization[degree][order]
                basis[center + order] = value * cosine
                if (order > 0) basis[center - order] = value * sine
                val next = ((2 * degree + 1) * z * current - (degree + order) * previous) / (degree - order + 1)
                previous = current
                current = next
            }
        }
    }
}

private fun gaussLegendre(count: Int): List<Pair<Double, Double>> {
    val nodes = MutableList(count) { 0.0 to 0.0 }
    fun polynomial(z: Double): Pair<Double, Double> {
        var previous = 0.0
        var current = 1.0
        for (i in 1..count) {
            val next = ((2 * i - 1) * z * current - (i - 1) * previous) / i
            previous = current
            current = next
        }
        val derivative = count * (previous - z * current) / (1 - z * z)
        return current to derivative
    }
    for (i in 0 until (count + 1) / 2) {
        var z = cos(PI * (i + 0.75) / (count + 0.5))
        var converged = false
        for (iteration in 0 until 20) {
            val (value, derivative) = polynomial(z)
            val delta = value / derivative
            z -= delta
            if (abs(delta) < 1e-15) {
                converged = true
                break
            }
        }
        check(converged) { "Gauss–Legendre node did not converge" }
        val derivative = polynomial(z).second
        val weight = 2 / ((1 - z * z) * derivative * derivative)
        nodes[i] = -z to weight
        nodes[count - 1 - i] = z to weight
    }
    return nodes
}
