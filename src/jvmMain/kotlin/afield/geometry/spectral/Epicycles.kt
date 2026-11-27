@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import afield.math.linalg.Vector3d
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PhaseClock(frequencies: DoubleArray, initialPhase: DoubleArray = DoubleArray(frequencies.size)) {
    private val rates = frequencies.copyOf()
    private val initial = initialPhase.copyOf()
    val dimension: Int get() = rates.size

    init {
        require(rates.isNotEmpty() && rates.size == initial.size)
        require(rates.all { it.isFinite() } && initial.all { it.isFinite() })
    }

    /** Frequencies are radians per unit time. */
    fun phase(time: Double): DoubleArray {
        require(time.isFinite())
        return DoubleArray(dimension) { axis -> ((rates[axis] * time) % TAU + initial[axis] % TAU) % TAU }
    }

    fun frequency(mode: IntArray): Double {
        require(mode.size == dimension)
        return mode.indices.sumOf { mode[it] * rates[it] }
    }
}

/** The unit circle sent through the two columns [a] and [b]. Either column may be zero. */
class EllipseHarmonic(mode: IntArray, a: Vec3, b: Vec3) {
    internal val lattice = mode.copyOf()
    private val cosine = Vec3(a)
    private val sine = Vec3(b)

    val mode: IntArray get() = lattice.copyOf()
    val a: Vec3 get() = Vec3(cosine)
    val b: Vec3 get() = Vec3(sine)

    init {
        require(lattice.any { it != 0 } && cosine.isFinite() && sine.isFinite())
    }

    /** The singular values of the matrix with columns a and b. */
    fun radii(): Pair<Double, Double> {
        val a = Vector3d(cosine.x.toDouble(), cosine.y.toDouble(), cosine.z.toDouble())
        val b = Vector3d(sine.x.toDouble(), sine.y.toDouble(), sine.z.toDouble())
        val aa = a.lengthSquared()
        val bb = b.lengthSquared()
        val span = hypot(aa - bb, 2.0 * a.dot(b))
        val major = sqrt((aa + bb + span) / 2)
        val minor = if (major == 0.0) 0.0 else a.cross(b, Vector3d()).length() / major
        return major to minor
    }

    /** Two circles whose displacements sum to this ellipse at every phase. */
    fun counterRotatingCircles(): Pair<EllipseHarmonic, EllipseHarmonic> {
        val a = Vector3d(cosine.x.toDouble(), cosine.y.toDouble(), cosine.z.toDouble())
        val b = Vector3d(sine.x.toDouble(), sine.y.toDouble(), sine.z.toDouble())
        val normal = a.cross(b, Vector3d())
        if (normal.lengthSquared() == 0.0) {
            // A line has no preferred plane. Choose one through its direction.
            val direction = if (a.lengthSquared() > 0) a else b
            if (direction.lengthSquared() == 0.0) return this to this
            val axis = if (kotlin.math.abs(direction.x) <= direction.length() * 0.5) {
                Vector3d(1.0, 0.0, 0.0)
            } else {
                Vector3d(0.0, 1.0, 0.0)
            }
            direction.cross(axis, normal)
        }
        normal.normalize()
        val turnedB = normal.cross(b, Vector3d())
        val forward = Vector3d(a).sub(turnedB).mul(0.5)
        val reverse = Vector3d(a).add(turnedB).mul(0.5)
        fun point(v: Vector3d) = Vec3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
        return EllipseHarmonic(lattice, point(forward), point(normal.cross(forward, Vector3d()))) to
            EllipseHarmonic(lattice, point(reverse), point(normal.cross(reverse, Vector3d()).negate()))
    }

    fun at(angle: Double): Vec3 = Vec3(cosine).mul(cos(angle).toFloat()).fma(sin(angle).toFloat(), sine)

    internal fun angle(phase: DoubleArray): Double =
        lattice.indices.sumOf { lattice[it] * (phase[it] % TAU) } % TAU
}

/** A periodic map from an integer phase lattice to 3D. A clock controls traversal independently. */
class EpicycleGeometry(
    val dimension: Int,
    center: Vec3,
    harmonics: List<EllipseHarmonic>,
) {
    private val origin = Vec3(center)
    val center: Vec3 get() = Vec3(origin)
    val harmonics = harmonics.toList()
    private val domain = this.harmonics.map { it.lattice.toList() }
    val spectrum = Spectrum(
        3,
        harmonics.map { harmonic ->
            SpectralMode(sqrt(harmonic.lattice.sumOf { it.toDouble() * it }), 0.5, 0.5)
        },
        DoubleArray(harmonics.size * 6).also { data ->
            for ((i, harmonic) in harmonics.withIndex()) {
                val a = harmonic.a
                val b = harmonic.b
                for (channel in 0..2) {
                    data[i * 6 + channel] = a[channel].toDouble()
                    data[i * 6 + 3 + channel] = b[channel].toDouble()
                }
            }
        },
        domain,
    )

    init {
        require(dimension > 0 && origin.isFinite() && harmonics.all { it.lattice.size == dimension })
    }

    fun withSpectrum(edited: Spectrum): EpicycleGeometry {
        require(edited.channels == 3 && edited.domain == domain)
        return EpicycleGeometry(dimension, origin, harmonics.mapIndexed { i, harmonic ->
            EllipseHarmonic(
                harmonic.lattice,
                Vec3(edited.primary(i, 0).toFloat(), edited.primary(i, 1).toFloat(), edited.primary(i, 2).toFloat()),
                Vec3(edited.quadrature(i, 0).toFloat(), edited.quadrature(i, 1).toFloat(), edited.quadrature(i, 2).toFloat()),
            )
        })
    }

    fun evaluate(phase: DoubleArray): Vec3 {
        checkPhase(phase)
        val sum = doubleArrayOf(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
        for ((i, harmonic) in harmonics.withIndex()) {
            val angle = harmonic.angle(phase)
            spectrum.accumulate(i, cos(angle), sin(angle), sum)
        }
        return Vec3(sum[0].toFloat(), sum[1].toFloat(), sum[2].toFloat())
    }

    fun evaluate(time: Double, clock: PhaseClock): Vec3 = evaluate(clock.phase(time))

    /**
     * The first point is the center. An optional per-mode angle transform can freeze, detune or
     * reverse a link without editing its ellipse. The transformed chain has its own traversal.
     */
    fun partialSums(phase: DoubleArray, angleEdit: (Int, Double) -> Double = { _, angle -> angle }): List<Vec3> {
        checkPhase(phase)
        val sum = center
        return buildList {
            add(Vec3(sum))
            for ((index, harmonic) in harmonics.withIndex()) {
                sum.add(harmonic.at(angleEdit(index, harmonic.angle(phase))))
                add(Vec3(sum))
            }
        }
    }

    fun chain(phase: DoubleArray): FLine = FLine().apply {
        val points = partialSums(phase)
        moveTo(points.first())
        for (i in 1 until points.size) lineTo(points[i])
    }

    fun orbits(phase: DoubleArray, segments: Int = 48): List<FLine> {
        require(segments >= 3)
        val centers = partialSums(phase)
        return harmonics.mapIndexed { index, harmonic ->
            FLine().apply {
                moveTo(harmonic.at(0.0).add(centers[index]))
                for (step in 1 until segments) {
                    lineTo(harmonic.at(TAU * step / segments).add(centers[index]))
                }
                lineTo(harmonic.at(0.0).add(centers[index]))
            }
        }
    }

    fun rotate(rotation: Quat): EpicycleGeometry = EpicycleGeometry(
        dimension,
        rotation.transform(center),
        harmonics.map { EllipseHarmonic(it.lattice, rotation.transform(it.a), rotation.transform(it.b)) },
    )

    fun truncate(maxDegree: Double): EpicycleGeometry {
        require(maxDegree >= 0)
        return EpicycleGeometry(dimension, origin, harmonics.filterIndexed { i, _ ->
            spectrum.modes[i].frequency <= maxDegree
        })
    }

    fun trace(clock: PhaseClock, start: Double, step: Double, count: Int): List<Vec3> {
        require(count >= 0 && clock.dimension == dimension && start.isFinite() && step.isFinite())
        return List(count) { evaluate(start + step * it, clock) }
    }

    /** Samples a periodic two-phase surface with welded seams. */
    fun sampleSurface(uCount: Int = 64, vCount: Int = 32): TriMesh {
        require(dimension == 2 && uCount >= 3 && vCount >= 3)
        val vertices = Math.multiplyExact(uCount, vCount)
        val positions = FloatArray(Math.multiplyExact(vertices, 3))
        val triangles = IntArray(Math.multiplyExact(vertices, 6))
        for (v in 0 until vCount) for (u in 0 until uCount) {
            val i = v * uCount + u
            val point = evaluate(doubleArrayOf(TAU * u / uCount, TAU * v / vCount))
            positions[3 * i] = point.x
            positions[3 * i + 1] = point.y
            positions[3 * i + 2] = point.z
            val right = v * uCount + (u + 1) % uCount
            val above = ((v + 1) % vCount) * uCount
            val diagonal = above + (u + 1) % uCount
            triangles[6 * i] = i
            triangles[6 * i + 1] = right
            triangles[6 * i + 2] = diagonal
            triangles[6 * i + 3] = i
            triangles[6 * i + 4] = diagonal
            triangles[6 * i + 5] = above + u
        }
        return TriMesh(positions, triangles)
    }

    private fun checkPhase(phase: DoubleArray) {
        require(phase.size == dimension && phase.all { it.isFinite() })
    }

    companion object {
        fun torus(major: Double = 1.0, minor: Double = 0.35): EpicycleGeometry {
            require(major > minor && minor > 0 && major.isFinite())
            val half = (minor / 2).toFloat()
            return EpicycleGeometry(2, Vec3(), listOf(
                EllipseHarmonic(intArrayOf(1, 0), Vec3(major.toFloat(), 0f, 0f), Vec3(0f, major.toFloat(), 0f)),
                EllipseHarmonic(intArrayOf(0, 1), Vec3(), Vec3(0f, 0f, minor.toFloat())),
                EllipseHarmonic(intArrayOf(1, 1), Vec3(half, 0f, 0f), Vec3(0f, half, 0f)),
                EllipseHarmonic(intArrayOf(1, -1), Vec3(half, 0f, 0f), Vec3(0f, half, 0f)),
            ))
        }

        /** Samples a given periodic map once per grid point, then fits every retained lattice mode. */
        fun fit(shape: IntArray, maxDegree: Double = Double.POSITIVE_INFINITY, minEnergy: Double = 1e-14,
                sample: (DoubleArray) -> Vec3): EpicycleGeometry {
            require(maxDegree >= 0 && minEnergy >= 0)
            val grid = PeriodicGrid(shape)
            val channels = Array(3) { FloatArray(grid.size) }
            for (i in 0 until grid.size) {
                val coordinates = grid.coordinates(i)
                val point = sample(DoubleArray(shape.size) { TAU * coordinates[it] / shape[it] })
                for (channel in 0..2) channels[channel][i] = point[channel]
            }
            val center = Vec3()
            val harmonics = ArrayList<EllipseHarmonic>()
            grid.decompose(channels) { mode, a, b, _ ->
                val cosine = Vec3(a[0].toFloat(), a[1].toFloat(), a[2].toFloat())
                val sine = Vec3(b[0].toFloat(), b[1].toFloat(), b[2].toFloat())
                val degree = sqrt(mode.sumOf { it.toDouble() * it })
                if (degree == 0.0) {
                    center.set(cosine)
                } else if (degree <= maxDegree && (cosine.lengthSquared() + sine.lengthSquared()) / 2 > minEnergy) {
                    harmonics += EllipseHarmonic(mode, cosine, sine)
                }
            }
            return EpicycleGeometry(shape.size, center, harmonics)
        }

        /** Seeded synthesis on the supplied lattice with amplitude falling as (1 + degree)^(-decay). */
        fun random(dimension: Int, modes: List<IntArray>, seed: Int, decay: Double = 2.0): EpicycleGeometry {
            require(decay.isFinite() && decay >= 0)
            val geometry = EpicycleGeometry(dimension, Vec3(), modes.map { EllipseHarmonic(it, Vec3(), Vec3()) })
            return geometry.withSpectrum(geometry.spectrum.randomize(seed) { (1 + it.frequency).pow(-decay) })
        }
    }
}
