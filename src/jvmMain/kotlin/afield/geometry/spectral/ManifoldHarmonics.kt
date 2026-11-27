@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.barycentricVertexAreas
import afield.geometry.mesh.cotangentLaplacianMatrix
import afield.geometry.mesh.topology
import afield.geometry.mesh.withVertexNormals
import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import org.ejml.data.DMatrixRMaj
import org.ejml.data.DMatrixSparseCSC
import org.ejml.dense.row.factory.DecompositionFactory_DDRM
import org.ejml.ops.DConvertMatrixStruct
import org.ejml.sparse.FillReducing
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC
import afield.math.linalg.Vector3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Natural Neumann FEM stiffness and lumped vertex areas on a welded triangle mesh: the shared
 * [cotangentLaplacianMatrix] and [barycentricVertexAreas] from `afield.geometry.mesh`, checked for
 * degenerate faces and isolated vertices, with the generalized eigenproblem `Lϕ = λMϕ` on top.
 */
class CotangentLaplacian(source: TriMesh) {
    private val mesh = TriMesh(source.positions.copyOf(), source.indices.copyOf(),
        uvs = source.uvs?.copyOf(), colors = source.colors?.copyOf())
    private val topology = mesh.topology()
    internal val mass = DoubleArray(mesh.vertexCount)
    internal val stiffness: DMatrixSparseCSC
    val vertexCount: Int get() = mass.size
    val vertexMasses: DoubleArray get() = mass.copyOf()
    val componentCount: Int get() = topology.componentCount

    init {
        require(!mesh.isEmpty && mesh.positions.all { it.isFinite() }) { "Manifold harmonics need finite triangles" }
        require(topology.isManifold) { "Manifold harmonics need a manifold triangle mesh" }
        for (face in 0 until mesh.triangleCount) {
            val a = position(mesh.ia(face)); val b = position(mesh.ib(face)); val c = position(mesh.ic(face))
            val ab = Vector3d(b).sub(a)
            val ac = Vector3d(c).sub(a)
            val bc = Vector3d(c).sub(b)
            val twiceArea = ab.cross(ac, Vector3d()).length()
            val longest2 = maxOf(ab.lengthSquared(), ac.lengthSquared(), bc.lengthSquared())
            require(twiceArea > 1e-12 * longest2) { "Degenerate or extremely skinny face $face" }
        }
        mesh.barycentricVertexAreas().copyInto(mass)
        require(mass.all { it > 0 }) { "Remove isolated vertices before computing a manifold basis" }
        stiffness = topology.cotangentLaplacianMatrix()
    }

    internal fun position(index: Int): Vector3d =
        Vector3d(mesh.x(index).toDouble(), mesh.y(index).toDouble(), mesh.z(index).toDouble())

    fun apply(signal: DoubleArray): DoubleArray {
        require(signal.size == vertexCount)
        return stiffness.multiply(signal)
    }

    /** Solves Lϕ = λMϕ. Includes all component constants and completes the eigenspace at the cutoff. */
    fun basis(requestedModes: Int, tolerance: Double = 1e-7): ManifoldBasis {
        require(requestedModes in 1..vertexCount)
        require(tolerance > 0 && tolerance.isFinite())
        val count = maxOf(requestedModes, componentCount)
        val roots = DoubleArray(vertexCount) { sqrt(mass[it]) }
        val normal = stiffness.copy()
        for (column in roots.indices) for (p in normal.col_idx[column] until normal.col_idx[column + 1]) {
            normal.nz_values[p] /= roots[column] * roots[normal.nz_rows[p]]
        }
        val componentAreas = topology.components.map { vertices -> vertices.sumOf { mass[it] } }
        val constants = topology.components.mapIndexed { c, vertices ->
            val shape = DoubleArray(vertexCount)
            val scale = 1 / sqrt(componentAreas[c])
            for (v in vertices) shape[v] = scale
            shape
        }
        if (count == componentCount) return ManifoldBasis(mesh, mass, DoubleArray(count), constants.toTypedArray())

        val positiveCount = count - componentCount
        val pairs = if (vertexCount <= 128) {
            val dense = DConvertMatrixStruct.convert(normal, null as DMatrixRMaj?)
            symmetricEigenpairs(dense).drop(componentCount)
        } else if (count == vertexCount) {
            error("Request a partial manifold basis for a mesh with more than 128 vertices")
        } else {
            val componentOf = IntArray(vertexCount)
            topology.components.forEachIndexed { component, vertices ->
                for (v in vertices) componentOf[v] = component
            }
            val nullScale = DoubleArray(vertexCount) { v -> roots[v] / sqrt(componentAreas[componentOf[v]]) }
            val solver = ShiftInvertBasis(normal, componentOf, nullScale, 1 / componentAreas.average())
            var wanted = minOf(vertexCount - componentCount, positiveCount + 1)
            var eigenpairs = solver.solve(wanted, tolerance)
            while (wanted < vertexCount - componentCount &&
                sameEigenspace(eigenpairs[positiveCount - 1].value, eigenpairs.last().value)) {
                wanted = minOf(vertexCount - componentCount, wanted + 8)
                eigenpairs = solver.solve(wanted, tolerance)
            }
            eigenpairs
        }
        val edge = pairs[positiveCount - 1].value
        val kept = pairs.take(positiveCount) + pairs.drop(positiveCount).takeWhile { sameEigenspace(edge, it.value) }
        val values = DoubleArray(componentCount + kept.size)
        val modes = (constants + kept.mapIndexed { index, pair ->
            values[componentCount + index] = pair.value.coerceAtLeast(0.0)
            DoubleArray(vertexCount) { pair.vector[it] / roots[it] }
        }).toTypedArray()
        return ManifoldBasis(mesh, mass, values, modes)
    }
}

private fun sameEigenspace(a: Double, b: Double): Boolean =
    abs(a - b) <= 2e-6 * maxOf(abs(a), abs(b))

private data class Eigenpair(val value: Double, val vector: DoubleArray)

private fun symmetricEigenpairs(matrix: DMatrixRMaj): List<Eigenpair> {
    val solver = DecompositionFactory_DDRM.eig(matrix.numRows, true, true)
    check(solver.decompose(matrix)) { "Symmetric eigendecomposition failed" }
    return List(matrix.numRows) { i ->
        Eigenpair(solver.getEigenvalue(i).real, solver.getEigenVector(i).data.copyOf())
    }.sortedBy { it.value }
}

private fun DMatrixSparseCSC.multiply(input: DoubleArray): DoubleArray {
    val result = DoubleArray(numRows)
    for (column in input.indices) for (p in col_idx[column] until col_idx[column + 1]) {
        result[nz_rows[p]] += nz_values[p] * input[column]
    }
    return result
}

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

/** Block inverse iteration using EJML's sparse Cholesky and symmetric Rayleigh–Ritz solve. */
private class ShiftInvertBasis(
    private val normal: DMatrixSparseCSC,
    private val componentOf: IntArray,
    private val nullScale: DoubleArray,
    private val shift: Double,
) {
    private val n = normal.numRows
    private val components = componentOf.max() + 1
    private val factor = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE)

    init {
        val shifted = normal.copy()
        for (i in 0 until n) shifted.set(i, i, shifted.get(i, i) + shift)
        check(factor.setA(shifted)) { "Shifted cotangent matrix did not factor" }
    }

    fun solve(wanted: Int, tolerance: Double): List<Eigenpair> {
        val width = minOf(n - components, wanted + maxOf(8, wanted / 3))
        val random = Random(31415)
        var block = Array(width) { DoubleArray(n) { random.nextDouble(-1.0, 1.0) } }
        orthonormalize(block)
        val input = DMatrixRMaj(n, 1)
        val output = DMatrixRMaj(n, 1)
        repeat(180) {
            for (vector in block) {
                vector.copyInto(input.data)
                factor.solve(input, output)
                output.data.copyInto(vector, endIndex = n)
            }
            orthonormalize(block)
            val applied = block.map { normal.multiply(it) }
            val projection = DMatrixRMaj(width, width)
            for (i in 0 until width) for (j in i until width) {
                val entry = dot(block[i], applied[j])
                projection[i, j] = entry
                projection[j, i] = entry
            }
            val ritz = symmetricEigenpairs(projection)
            block = Array(width) { index ->
                val result = DoubleArray(n)
                for (j in 0 until width) {
                    val gain = ritz[index].vector[j]
                    for (v in 0 until n) result[v] += gain * block[j][v]
                }
                result
            }
            val converged = (0 until wanted).all { i ->
                val value = ritz[i].value
                val residual = normal.multiply(block[i])
                for (v in 0 until n) residual[v] -= value * block[i][v]
                sqrt(dot(residual, residual)) <= tolerance * maxOf(shift, abs(value))
            }
            if (converged) return List(wanted) { Eigenpair(ritz[it].value, block[it]) }
        }
        error("Manifold eigensolver did not converge for $wanted positive modes")
    }

    private fun orthonormalize(block: Array<DoubleArray>) {
        val means = DoubleArray(components)
        for (i in block.indices) {
            val vector = block[i]
            repeat(2) {
                means.fill(0.0)
                for (v in 0 until n) means[componentOf[v]] += nullScale[v] * vector[v]
                for (v in 0 until n) vector[v] -= means[componentOf[v]] * nullScale[v]
                for (j in 0 until i) {
                    val coefficient = dot(vector, block[j])
                    for (v in 0 until n) vector[v] -= coefficient * block[j][v]
                }
            }
            val length = sqrt(dot(vector, vector))
            check(length > 0 && length.isFinite()) { "Manifold eigensolver lost rank" }
            for (v in 0 until n) vector[v] /= length
        }
    }
}

/** A fixed basis tied to the source connectivity and metric. Eigenfunctions are stored mode-major. */
class ManifoldBasis internal constructor(
    private val mesh: TriMesh,
    private val masses: DoubleArray,
    private val lambda: DoubleArray,
    private val eigenfunctions: Array<DoubleArray>,
) {
    private val metadata = lambda.map { SpectralMode(sqrt(it)) }
    private val domain = Any()

    internal fun checkSpectrum(spectrum: Spectrum) {
        require(spectrum.domain === domain) { "The spectrum belongs to a different manifold basis" }
    }
    val vertexCount: Int get() = masses.size
    val modeCount: Int get() = lambda.size
    val eigenvalues: DoubleArray get() = lambda.copyOf()
    val vertexMasses: DoubleArray get() = masses.copyOf()

    fun mode(index: Int): DoubleArray = eigenfunctions[index].copyOf()
    internal fun basisValue(mode: Int, vertex: Int): Double = eigenfunctions[mode][vertex]

    /** Input is vertex-major, with the channels interleaved at each vertex. */
    fun analyze(signal: DoubleArray, channels: Int = 1): Spectrum {
        require(channels > 0 && signal.size.toLong() == vertexCount.toLong() * channels)
        require(signal.all { it.isFinite() })
        val coefficients = DoubleArray(Math.multiplyExact(modeCount, Math.multiplyExact(2, channels)))
        for (mode in lambda.indices) for (vertex in 0 until vertexCount) {
            val scale = masses[vertex] * eigenfunctions[mode][vertex]
            for (channel in 0 until channels) {
                coefficients[(2 * mode) * channels + channel] += scale * signal[vertex * channels + channel]
            }
        }
        return Spectrum(channels, metadata, coefficients, domain)
    }

    fun positions(): Spectrum = analyze(DoubleArray(mesh.positions.size) { mesh.positions[it].toDouble() }, 3)

    fun reconstruct(spectrum: Spectrum): DoubleArray {
        checkSpectrum(spectrum)
        val channels = spectrum.channels
        val result = DoubleArray(Math.multiplyExact(vertexCount, channels))
        val row = DoubleArray(channels)
        for (vertex in 0 until vertexCount) {
            row.fill(0.0)
            for (mode in lambda.indices) spectrum.accumulate(mode, eigenfunctions[mode][vertex], 0.0, row)
            row.copyInto(result, vertex * channels)
        }
        return result
    }

    /** Rounds up to the end of a repeated-eigenvalue cluster. */
    fun truncate(spectrum: Spectrum, count: Int): Spectrum {
        checkSpectrum(spectrum)
        require(count in 0..modeCount)
        var end = count
        while (end in 1 until modeCount && sameEigenspace(lambda[count - 1], lambda[end])) end++
        return spectrum.reweight { index, _ -> if (index < end) 1.0 else 0.0 }
    }

    fun reconstructMesh(spectrum: Spectrum): TriMesh {
        require(spectrum.channels == 3)
        val signal = reconstruct(spectrum)
        return TriMesh(FloatArray(signal.size) { signal[it].toFloat() }, mesh.indices.copyOf(),
            uvs = mesh.uvs?.copyOf(), colors = mesh.colors?.copyOf()).withVertexNormals()
    }

    fun reconstructionError(signal: DoubleArray, spectrum: Spectrum): Double {
        val estimate = reconstruct(spectrum)
        require(signal.size == estimate.size)
        return sqrt(signal.indices.sumOf { i ->
            val residual = signal[i] - estimate[i]
            masses[i / spectrum.channels] * residual * residual
        } / masses.sum())
    }

    fun diffuse(spectrum: Spectrum, time: Double): Spectrum {
        checkSpectrum(spectrum)
        val weight = Spectrum.heat(time)
        return spectrum.reweight { _, mode -> weight(mode) }
    }

    fun vibrate(spectrum: Spectrum, time: Double, damping: Double = 0.0): Spectrum {
        checkSpectrum(spectrum)
        require(time.isFinite() && damping.isFinite() && damping >= 0)
        val weight = Spectrum.heat(abs(time) * damping)
        return spectrum.reweight { _, mode -> cos(time * mode.frequency) * weight(mode) }
    }

    fun randomField(seed: Int, deviation: (SpectralMode) -> Double): Spectrum =
        Spectrum(1, metadata, DoubleArray(2 * modeCount), domain).randomize(seed, deviation)

    /** Piecewise-linear level sets on the original surface. Flat triangles have no unique contour. */
    fun contours(signal: DoubleArray, level: Double = 0.0): FLine {
        require(signal.size == vertexCount && level.isFinite() && signal.all { it.isFinite() })
        val line = FLine()
        val flatEdges = HashSet<Long>()
        for (face in 0 until mesh.triangleCount) {
            val corners = intArrayOf(mesh.ia(face), mesh.ib(face), mesh.ic(face))
            val f = DoubleArray(3) { signal[corners[it]] - level }
            if (f.all { it == 0.0 }) continue
            val crossings = ArrayList<Vec3>(2)
            for (i in 0..2) {
                val j = (i + 1) % 3
                val a = corners[i]
                val b = corners[j]
                if (f[i] == 0.0 && f[j] == 0.0) {
                    val key = minOf(a, b).toLong() * vertexCount + maxOf(a, b)
                    if (flatEdges.add(key)) line.moveTo(mesh.position(a)).lineTo(mesh.position(b))
                }
                if (f[i] == 0.0) crossings += mesh.position(a)
                if (f[i] * f[j] < 0) {
                    crossings += mesh.position(a).lerp(mesh.position(b), (f[i] / (f[i] - f[j])).toFloat())
                }
            }
            if (crossings.size == 2 && f.count { it == 0.0 } < 2) line.moveTo(crossings[0]).lineTo(crossings[1])
        }
        return line
    }

    /** Area-weighted original normals and a scalar displacement, preserving the source topology. */
    fun displaceNormals(signal: DoubleArray, scale: Double = 1.0): TriMesh {
        require(signal.size == vertexCount && scale.isFinite() && signal.all { it.isFinite() })
        val normals = mesh.withVertexNormals().normals!!
        val positions = FloatArray(mesh.positions.size) { i ->
            (mesh.positions[i] + normals[i] * signal[i / 3] * scale).toFloat()
        }
        return TriMesh(positions, mesh.indices.copyOf(), uvs = mesh.uvs?.copyOf(), colors = mesh.colors?.copyOf())
            .withVertexNormals()
    }

    fun flow(signal: DoubleArray, perpendicular: Boolean = false): ManifoldFlow {
        require(signal.size == vertexCount && signal.all { it.isFinite() })
        return ManifoldFlow(mesh, Array(mesh.triangleCount) { gradient(it, signal) }, perpendicular)
    }

    /** Tangent gradient of a linear vertex signal on one triangle. */
    fun gradient(face: Int, signal: DoubleArray): Vec3 {
        require(signal.size == vertexCount)
        val a = mesh.ia(face)
        val b = mesh.ib(face)
        val c = mesh.ic(face)
        val (pa, pb, pc) = listOf(a, b, c).map { vertex ->
            Vector3d(mesh.x(vertex).toDouble(), mesh.y(vertex).toDouble(), mesh.z(vertex).toDouble())
        }
        val ab = pb.sub(pa)
        val ac = pc.sub(pa)
        val normal = ab.cross(ac, Vector3d())
        val area2 = normal.lengthSquared()
        val g = ac.cross(normal, Vector3d()).mul(signal[b] - signal[a])
            .fma(signal[c] - signal[a], normal.cross(ab, Vector3d())).div(area2)
        return Vec3(g.x.toFloat(), g.y.toFloat(), g.z.toFloat())
    }
}
