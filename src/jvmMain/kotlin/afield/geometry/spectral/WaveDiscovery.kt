@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.math.linalg.Vec3
import org.ejml.data.DMatrixRMaj
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM
import smile.math.BFGS
import smile.util.function.DifferentiableMultivariateFunction
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adds terms from a candidate dictionary by weighted residual correlation, refits every coefficient,
 * and optionally refines the selected wavevectors by variable projection. Calling [publish] after
 * each addition makes intermediate fields available to a background box job.
 */
fun discoverPlaneWaves(
    samples: List<FieldSample>,
    candidates: List<Vec3>,
    maxWaves: Int,
    refinementRadius: Double = 0.0,
    refinementIterations: Int = 24,
    ridge: Double = 1e-5,
    publish: (PlaneWaveField) -> Unit = {},
): PlaneWaveField {
    require(maxWaves >= 0 && refinementRadius.isFinite() && refinementRadius >= 0 && refinementIterations > 0)
    val regression = WaveRegression(samples, ridge)
    val remaining = candidates.map { PlaneWave(it, 0.0, 0.0) }.toMutableList()
    var field = regression.fit(emptyList())
    repeat(minOf(maxWaves, remaining.size)) {
        checkFittingCancelled()
        val residual = DoubleArray(samples.size) { i -> samples[i].weight * (samples[i].value - field.evaluate(samples[i].point)) }
        val best = remaining.indices.maxBy { i ->
            checkFittingCancelled()
            var cosine = 0.0
            var sine = 0.0
            val wave = remaining[i]
            for (j in residual.indices) {
                val phase = wave.angle(samples[j].point)
                cosine += residual[j] * cos(phase)
                sine += residual[j] * sin(phase)
            }
            cosine * cosine + sine * sine
        }
        val proposed = field.waves.map { it.wavevector } + remaining.removeAt(best).wavevector
        field = if (refinementRadius == 0.0) {
            regression.fit(proposed)
        } else {
            regression.refine(proposed, refinementRadius, refinementIterations)
        }
        publish(field)
    }
    return field
}

/** Refines wavevectors that already have a useful initialization. Each step solves the coefficients by QR. */
fun refinePlaneWaves(
    samples: List<FieldSample>,
    initial: List<Vec3>,
    radius: Double,
    iterations: Int = 40,
    ridge: Double = 1e-5,
): PlaneWaveField = WaveRegression(samples, ridge).refine(initial, radius, iterations)

internal class WaveRegression(samples: List<FieldSample>, private val ridge: Double) {
    private val samples = samples.map { FieldSample(Vec3(it.point), it.value, it.weight) }
    private val totalWeight = samples.sumOf { it.weight }

    init {
        require(samples.isNotEmpty() && ridge.isFinite() && ridge > 0)
        require(totalWeight.isFinite() && totalWeight > 0)
        require(samples.all { it.value.isFinite() && it.weight > 0 && it.weight.isFinite() && it.point.isFinite() })
    }

    fun fit(vectors: List<Vec3>, penalty: (Double) -> Double = { 1.0 }): PlaneWaveField {
        val modes = vectors.map { PlaneWave(it, 0.0, 0.0) }
        val coordinates = DoubleArray(modes.size * 3) { vectors[it / 3][it % 3].toDouble() }
        val penalties = DoubleArray(modes.size) { penalty(modes[it].frequency) }
        require(penalties.all { it > 0 && it.isFinite() })
        return makeField(vectors, coefficients(coordinates, penalties))
    }

    fun refine(initial: List<Vec3>, radius: Double, iterations: Int): PlaneWaveField {
        require(radius > 0 && radius.isFinite() && iterations > 0)
        require(initial.all { it.isFinite() && it.lengthSquared() > 0 })
        if (initial.isEmpty()) return fit(initial)
        val coordinates = DoubleArray(initial.size * 3) { initial[it / 3][it % 3].toDouble() }
        val lower = DoubleArray(coordinates.size) { coordinates[it] - radius }
        val upper = DoubleArray(coordinates.size) { coordinates[it] + radius }
        val penalty = DoubleArray(initial.size) { 1.0 }
        var bestValue = Double.POSITIVE_INFINITY
        var best = coordinates.copyOf()
        val objective = object : DifferentiableMultivariateFunction {
            override fun f(x: DoubleArray): Double = g(x, DoubleArray(x.size))

            override fun g(x: DoubleArray, gradient: DoubleArray): Double {
                val w = coefficients(x, penalty)
                gradient.fill(0.0)
                var loss = ridge * (1 until w.size).sumOf { w[it] * w[it] }
                val angles = DoubleArray(initial.size)
                for (sample in samples) {
                    var predicted = w[0]
                    for (i in initial.indices) {
                        val angle = angle(x, i, sample.point)
                        angles[i] = angle
                        predicted += w[2 * i + 1] * cos(angle) + w[2 * i + 2] * sin(angle)
                    }
                    val residual = predicted - sample.value
                    loss += sample.weight * residual * residual
                    for (i in initial.indices) {
                        val angle = angles[i]
                        val derivative = sample.weight * residual *
                            (-w[2 * i + 1] * sin(angle) + w[2 * i + 2] * cos(angle)) / totalWeight
                        // The linear solve is stationary in w, so its derivative drops out of d(loss)/dk.
                        for (axis in 0..2) gradient[3 * i + axis] += derivative * sample.point[axis]
                    }
                }
                val value = 0.5 * loss / totalWeight
                if (value < bestValue) {
                    bestValue = value
                    best = x.copyOf()
                }
                return value
            }
        }
        BFGS.minimize(objective, minOf(5, coordinates.size), coordinates, lower, upper, 1e-8, iterations)
        val fitted = initial.indices.map { i ->
            val k = Vec3(best[3 * i].toFloat(), best[3 * i + 1].toFloat(), best[3 * i + 2].toFloat())
            if (k.lengthSquared() > 0) k else initial[i]
        }
        return fit(fitted)
    }

    private fun makeField(vectors: List<Vec3>, w: DoubleArray): PlaneWaveField =
        PlaneWaveField(w[0], vectors.mapIndexed { i, k -> PlaneWave(k, w[2 * i + 1], w[2 * i + 2]) })

    private fun coefficients(wavevectors: DoubleArray, penalty: DoubleArray): DoubleArray {
        checkFittingCancelled()
        val modes = penalty.size
        val columns = Math.addExact(1, Math.multiplyExact(2, modes))
        val rows = Math.addExact(samples.size, columns - 1)
        val design = DMatrixRMaj(rows, columns)
        val target = DMatrixRMaj(rows, 1)
        for ((row, sample) in samples.withIndex()) {
            val weight = sqrt(sample.weight)
            design[row, 0] = weight
            target[row, 0] = sample.value * weight
            for (i in 0 until modes) {
                val angle = angle(wavevectors, i, sample.point)
                design[row, 2 * i + 1] = weight * cos(angle)
                design[row, 2 * i + 2] = weight * sin(angle)
            }
        }
        for (i in 0 until modes) {
            val diagonal = sqrt(ridge) * penalty[i]
            for (lane in 0..1) {
                val column = 2 * i + lane + 1
                design[samples.size + column - 1, column] = diagonal
            }
        }
        val solver = LinearSolverFactory_DDRM.qr(rows, columns)
        require(solver.setA(design)) { "Directional Fourier fit is numerically singular" }
        val solution = DMatrixRMaj(columns, 1)
        solver.solve(target, solution)
        return solution.data
    }

    private fun angle(k: DoubleArray, index: Int, p: Vec3): Double =
        k[3 * index] * p.x + k[3 * index + 1] * p.y + k[3 * index + 2] * p.z
}

private fun checkFittingCancelled() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Spectral fitting interrupted")
}
