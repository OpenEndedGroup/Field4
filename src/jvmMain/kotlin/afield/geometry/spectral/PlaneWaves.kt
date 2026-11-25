@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.bvh
import afield.geometry.mesh.marchingCubes
import afield.geometry.mesh.topology
import afield.geometry.spatial.AxisAlignedBoundingBox3D
import afield.math.linalg.Matrix3f
import afield.math.linalg.Matrix3fc
import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class PlaneWave(wavevector: Vec3, val a: Double, val b: Double) {
    internal val kx = wavevector.x.toDouble()
    internal val ky = wavevector.y.toDouble()
    internal val kz = wavevector.z.toDouble()
    val wavevector: Vec3 get() = Vec3(kx.toFloat(), ky.toFloat(), kz.toFloat())
    val frequency: Double = sqrt(kx * kx + ky * ky + kz * kz)
    val amplitude: Double get() = hypot(a, b)
    val phase: Double get() = atan2(-b, a)
    val wavelength: Double get() = TAU / frequency

    companion object {
        fun polar(wavevector: Vec3, amplitude: Double, phase: Double = 0.0): PlaneWave {
            require(amplitude >= 0)
            return PlaneWave(wavevector, amplitude * cos(phase), -amplitude * sin(phase))
        }
    }

    init {
        require(frequency > 0 && frequency.isFinite() && a.isFinite() && b.isFinite())
    }

    internal fun angle(point: Vec3): Double = kx * point.x + ky * point.y + kz * point.z

    /** Adds [radians] to the argument of this wave. */
    fun shiftPhase(radians: Double): PlaneWave {
        val c = cos(radians)
        val s = sin(radians)
        return PlaneWave(wavevector, a * c + b * s, b * c - a * s)
    }
}

data class FieldSample(val point: Vec3, val value: Double, val weight: Double = 1.0)

/** A bounded-use implicit field. Values are generally not signed distances. */
class PlaneWaveField(val offset: Double, waves: List<PlaneWave>) {
    val waves = waves.toList()
    private val domain = this.waves.map { Triple(it.kx, it.ky, it.kz) }
    val spectrum = Spectrum(1, waves.map { SpectralMode(it.frequency, 0.5, 0.5) },
        DoubleArray(waves.size * 2) { if (it % 2 == 0) waves[it / 2].a else waves[it / 2].b }, domain)

    init {
        require(offset.isFinite())
    }

    fun withSpectrum(edited: Spectrum): PlaneWaveField {
        require(edited.channels == 1 && edited.domain == domain)
        return PlaneWaveField(offset, waves.mapIndexed { i, wave ->
            PlaneWave(wave.wavevector, edited.primary(i, 0), edited.quadrature(i, 0))
        })
    }

    fun evaluate(point: Vec3): Double {
        var result = offset
        for (wave in waves) {
            val angle = wave.angle(point)
            result += wave.a * cos(angle) + wave.b * sin(angle)
        }
        return result
    }

    fun gradient(point: Vec3): Vec3 {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (wave in waves) {
            val angle = wave.angle(point)
            val rate = -wave.a * sin(angle) + wave.b * cos(angle)
            x += wave.kx * rate
            y += wave.ky * rate
            z += wave.kz * rate
        }
        return Vec3(x.toFloat(), y.toFloat(), z.toFloat())
    }

    /** Symmetric spatial Hessian in row-major order. */
    fun hessian(point: Vec3): DoubleArray {
        val matrix = DoubleArray(9)
        for (wave in waves) {
            val angle = wave.angle(point)
            val curvature = -wave.a * cos(angle) - wave.b * sin(angle)
            val k = doubleArrayOf(wave.kx, wave.ky, wave.kz)
            for (row in 0..2) for (column in 0..2) matrix[3 * row + column] += curvature * k[row] * k[column]
        }
        return matrix
    }

    fun laplacian(point: Vec3): Double = waves.sumOf {
        val angle = it.angle(point)
        -it.frequency * it.frequency * (it.a * cos(angle) + it.b * sin(angle))
    }

    /** Gradient in xyz, field value in w. Uses the same sine/cosine basis as scalar evaluation. */
    fun valueGradientSpectrum(): Spectrum {
        val data = DoubleArray(waves.size * 8)
        for ((index, wave) in waves.withIndex()) {
            val start = index * 8
            val wavevector = wave.wavevector
            for (axis in 0..2) {
                data[start + axis] = wavevector[axis] * wave.b
                data[start + 4 + axis] = -wavevector[axis] * wave.a
            }
            data[start + 3] = wave.a
            data[start + 7] = wave.b
        }
        return Spectrum(4, spectrum.modes, data, domain)
    }

    val lipschitzBound: Double get() = waves.sumOf { it.frequency * it.amplitude }

    fun rotate(rotation: Quat): PlaneWaveField =
        PlaneWaveField(offset, waves.map { PlaneWave(rotation.transform(it.wavevector), it.a, it.b) })

    fun translate(displacement: Vec3): PlaneWaveField =
        PlaneWaveField(offset, waves.map { it.shiftPhase(-it.angle(displacement)) })

    /** Dilates the represented surface about the origin, retaining field value units. */
    fun scale(factor: Double): PlaneWaveField {
        require(factor > 0 && factor.isFinite())
        return PlaneWaveField(offset, waves.map {
            PlaneWave(Vec3((it.kx / factor).toFloat(), (it.ky / factor).toFloat(), (it.kz / factor).toFloat()),
                it.a, it.b)
        })
    }

    /** Transforms the level set by x → linear·x + displacement. */
    fun transform(linear: Matrix3fc, displacement: Vec3 = Vec3()): PlaneWaveField {
        val determinant = linear.determinant()
        require(determinant != 0f && determinant.isFinite() && displacement.isFinite())
        val inverseTranspose = Matrix3f(linear).invert().transpose()
        return mapWavevectors(inverseTranspose::transform).translate(displacement)
    }

    fun mapWavevectors(transform: (Vec3) -> Vec3): PlaneWaveField =
        PlaneWaveField(offset, waves.map { PlaneWave(transform(it.wavevector), it.a, it.b) })

    fun pruneByCount(count: Int): PlaneWaveField {
        require(count >= 0)
        return PlaneWaveField(offset, waves.sortedByDescending { it.amplitude }.take(count))
    }

    fun pruneByAmplitude(minimum: Double): PlaneWaveField {
        require(minimum >= 0)
        return PlaneWaveField(offset, waves.filter { it.amplitude >= minimum })
    }

    /** The DC offset stays available as the field's baseline. */
    fun bandPass(minFrequency: Double, maxFrequency: Double): PlaneWaveField {
        require(minFrequency >= 0 && maxFrequency >= minFrequency)
        return PlaneWaveField(offset, waves.filter { it.frequency in minFrequency..maxFrequency })
    }

    fun lowPass(maxFrequency: Double): PlaneWaveField = bandPass(0.0, maxFrequency)

    fun highPass(minFrequency: Double): PlaneWaveField = bandPass(minFrequency, Double.POSITIVE_INFINITY)

    /** Locally projects to a regular point of the level set, or returns null on failure. */
    fun projectToSurface(point: Vec3, isoValue: Double = 0.0, maxStep: Double = 0.25,
                         tolerance: Double = 1e-5, iterations: Int = 24): Vec3? {
        require(point.isFinite() && isoValue.isFinite() && maxStep.isFinite() && maxStep > 0 &&
            tolerance.isFinite() && tolerance > 0 && iterations > 0)
        val current = Vec3(point)
        repeat(iterations) {
            val error = evaluate(current) - isoValue
            val normal = gradient(current)
            val length = normal.length().toDouble()
            if (!length.isFinite() || length <= 1e-12) return null
            if (kotlin.math.abs(error) <= tolerance) return current
            var distance = (-error / length).coerceIn(-maxStep, maxStep)
            normal.div(length.toFloat())
            val next = Vec3(current)
            var accepted = false
            for (attempt in 0 until 10) {
                next.set(current).fma(distance.toFloat(), normal)
                if (kotlin.math.abs(evaluate(next) - isoValue) < kotlin.math.abs(error)) {
                    accepted = true
                    break
                }
                distance *= 0.5
            }
            if (!accepted) return null
            current.set(next)
        }
        return current.takeIf { kotlin.math.abs(evaluate(it) - isoValue) <= tolerance }
    }

    fun extractSurface(bounds: AxisAlignedBoundingBox3D, resolution: Int = 48, isoValue: Double = 0.0,
                       positiveOutside: Boolean = true): TriMesh =
        marchingCubes({ evaluate(it).toFloat() }, bounds, resolution, isoValue, positiveOutside)

    companion object {
        /** Samples inside the FFT cell, excluding its repeated upper faces. Pad the source within this cell. */
        fun fitCartesian(bounds: AxisAlignedBoundingBox3D, shape: IntArray, maxWaves: Int,
                         sample: (Vec3) -> Double): PlaneWaveField {
            require(shape.size == 3 && maxWaves >= 0)
            val extent = bounds.size
            require(extent.isFinite() && extent.x > 0 && extent.y > 0 && extent.z > 0)
            val grid = PeriodicGrid(shape)
            val values = FloatArray(grid.size)
            for (i in values.indices) {
                val coord = grid.coordinates(i)
                val point = Vec3(
                    bounds.min.x + extent.x * coord[0] / shape[0],
                    bounds.min.y + extent.y * coord[1] / shape[1],
                    bounds.min.z + extent.z * coord[2] / shape[2],
                )
                values[i] = sample(point).toFloat()
            }
            var offset = 0.0
            val waves = ArrayList<Pair<Double, PlaneWave>>()
            grid.decompose(arrayOf(values)) { mode, a, b, nyquist ->
                if (mode.all { it == 0 }) {
                    offset = a[0]
                } else if (a[0] != 0.0 || b[0] != 0.0) {
                    val k = Vec3((TAU * mode[0] / extent.x).toFloat(),
                        (TAU * mode[1] / extent.y).toFloat(), (TAU * mode[2] / extent.z).toFloat())
                    val wave = PlaneWave(k, a[0], b[0])
                    val shifted = wave.shiftPhase(-wave.angle(bounds.min))
                    val energy = (a[0] * a[0] + b[0] * b[0]) * if (nyquist) 1.0 else 0.5
                    waves += energy to shifted
                }
            }
            return PlaneWaveField(offset, waves.sortedByDescending { it.first }.take(maxWaves).map { it.second })
        }

        /** Hemisphere samples avoid the redundant pair k and -k, since each wave has two coefficients. */
        fun frequencyShells(radii: DoubleArray, directionsPerShell: Int): List<Vec3> {
            require(directionsPerShell > 0 && radii.all { it > 0 && it.isFinite() })
            val goldenAngle = PI * (3 - sqrt(5.0))
            return radii.flatMapIndexed { shell, radius ->
                List(directionsPerShell) { i ->
                    val z = (i + 0.5) / directionsPerShell
                    val ring = sqrt(1 - z * z)
                    val angle = goldenAngle * (i + shell * 0.5)
                    Vec3((radius * ring * cos(angle)).toFloat(), (radius * ring * sin(angle)).toFloat(),
                        (radius * z).toFloat())
                }
            }
        }

        /** Weighted QR ridge fit. [penalty] controls the regularization by spatial frequency. */
        fun fitDirectional(samples: List<FieldSample>, wavevectors: List<Vec3>, ridge: Double = 1e-5,
                           penalty: (Double) -> Double = { 1.0 }): PlaneWaveField =
            WaveRegression(samples, ridge).fit(wavevectors, penalty)

        /** Isotropic in distribution, with phase-averaged variance [variance]. */
        fun random(waveCount: Int, seed: Int, variance: Double = 1.0,
                   radialFrequency: (Random) -> Double,
                   power: (Double) -> Double = { 1.0 }): PlaneWaveField {
            require(waveCount > 0 && variance.isFinite() && variance >= 0)
            val random = Random(seed)
            val unscaled = List(waveCount) {
                val rho = radialFrequency(random)
                val weight = power(rho)
                require(rho.isFinite() && rho > 0 && weight.isFinite() && weight >= 0)
                val z = random.nextDouble(-1.0, 1.0)
                val angle = random.nextDouble(TAU)
                val ring = sqrt(1 - z * z)
                val k = Vec3((rho * ring * cos(angle)).toFloat(), (rho * ring * sin(angle)).toFloat(),
                    (rho * z).toFloat())
                PlaneWave.polar(k, sqrt(weight), random.nextDouble(TAU))
            }
            val powerSum = unscaled.sumOf { it.amplitude * it.amplitude }
            require(powerSum.isFinite() && powerSum > 0)
            val gain = sqrt(2 * variance / powerSum)
            return PlaneWaveField(0.0, unscaled.map { PlaneWave(it.wavevector, gain * it.a, gain * it.b) })
        }

        /** Keeps the sign convention of a closed mesh, and limits the distance used far from its surface. */
        fun meshTarget(mesh: TriMesh, truncation: Double): (Vec3) -> Double {
            require(!mesh.isEmpty && mesh.topology().isWatertight) { "A signed mesh target needs a watertight mesh" }
            require(truncation > 0 && truncation.isFinite())
            val bvh = mesh.bvh()
            return { bvh.signedDistance(it).coerceIn(-truncation, truncation) }
        }
    }
}
