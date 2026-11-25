package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.boundsAround
import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.marchingCubes
import afield.geometry.mesh.sdfSphere
import afield.geometry.mesh.sdfSubtract
import afield.geometry.mesh.sdfTorus
import afield.geometry.mesh.sdfUnion
import afield.geometry.mesh.topology
import afield.lang.stacksl.compileStackSL
import afield.lang.stacksl.cpu.AdValue
import afield.lang.stacksl.cpu.GlslAdScalars
import afield.lang.stacksl.cpu.GlslType
import afield.lang.stacksl.cpu.newMachine
import afield.lang.stacksl.cpu.parseGlslShader
import afield.math.autodiff.ADTape
import afield.math.linalg.Matrix3f
import afield.math.linalg.Vec3
import afield.math.linalg.quat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpectralGeometryTest {
    @Test
    fun torusFitClocksAndRotation() {
        val torus = EpicycleGeometry.torus(1.2, 0.3)
        val fitted = EpicycleGeometry.fit(intArrayOf(16, 8), minEnergy = 1e-10) {
            val (u, v) = it
            Vec3(((1.2 + 0.3 * cos(v)) * cos(u)).toFloat(),
                ((1.2 + 0.3 * cos(v)) * sin(u)).toFloat(), (0.3 * sin(v)).toFloat())
        }
        assertEquals(4, fitted.harmonics.size)
        val rotation = quat(1.26, Vec3(0.4f, 0.9f, -0.1f).normalize())
        val irrational = PhaseClock(doubleArrayOf(1.0, sqrt(2.0)))
        val rational = PhaseClock(doubleArrayOf(1.0, 1.5))
        val period = 4 * PI
        assertTrue(torus.evaluate(0.0, rational).distance(torus.evaluate(period, rational)) < 1e-6)
        assertTrue(torus.evaluate(0.0, irrational).distance(torus.evaluate(period, irrational)) > 0.1)
        for (i in 0..40) {
            val phase = irrational.phase(i * 0.743)
            val point = torus.evaluate(phase)
            assertTrue(point.distance(fitted.evaluate(phase)) < 1e-6)
            assertTrue(point.distance(torus.partialSums(phase).last()) < 1e-6)
            assertTrue(rotation.transform(Vec3(point)).distance(torus.rotate(rotation).evaluate(phase)) < 1e-6)
        }
        val topology = torus.sampleSurface(16, 8).topology()
        assertTrue(topology.isWatertight)
        assertEquals(0, topology.eulerCharacteristic)
        assertEquals(0.0, torus.harmonics[1].radii().second)
    }

    @Test
    fun nyquistAndThreePhaseVolume() {
        val nyquist = EpicycleGeometry.fit(intArrayOf(8, 4)) { phase ->
            Vec3(cos(4 * phase[0] + 2 * phase[1]).toFloat(), 0f, 0f)
        }
        assertEquals(1, nyquist.harmonics.size)
        assertEquals(1.0, nyquist.evaluate(doubleArrayOf(0.0, 0.0)).x.toDouble(), 1e-6)
        val volume = EpicycleGeometry(3, Vec3(), List(3) { axis ->
            EllipseHarmonic(IntArray(3) { if (it == axis) 1 else 0 },
                Vec3().also { it[axis] = 1f }, Vec3())
        })
        val phase = DoubleArray(3) { PI / 2 }
        for (axis in phase.indices) {
            val next = phase.copyOf().also { it[axis] += 1e-3 }
            val derivative = volume.evaluate(next).sub(volume.evaluate(phase)).mul(1000f)
            assertTrue(derivative.distance(Vec3().also { it[axis] = -1f }) < 1e-4)
        }
    }

    @Test
    fun planeWaveFitsDerivativesAndRigidMotion() {
        val bounds = boundsAround(Vec3(0.37f, -1.2f, 0.6f), 1.0)
        val known = PlaneWaveField(0.31, listOf(
            PlaneWave(Vec3(0f, PI.toFloat(), 0f), 0.4, -0.8),
            PlaneWave(Vec3(PI.toFloat(), PI.toFloat(), -PI.toFloat()), -0.5, 0.2),
        ))
        val points = List(47) { i -> Vec3(sin(i * 1.19).toFloat(), cos(i * 1.77).toFloat(), sin(i * 2.21).toFloat()) }
        val cartesian = PlaneWaveField.fitCartesian(bounds, intArrayOf(8, 8, 8), 2, known::evaluate)
        val directional = PlaneWaveField.fitDirectional(points.map { FieldSample(it, known.evaluate(it)) },
            known.waves.map { it.wavevector })
        val rotation = quat(0.72, Vec3(1f, 2f, 3f).normalize())
        val displacement = Vec3(0.2f, 0.4f, -0.1f)
        val rotated = known.rotate(rotation).translate(displacement)
        val linear = Matrix3f().rotation(rotation).scale(0.8f, 1.4f, -1.25f)
        val affine = known.transform(linear, displacement)
        for (p in points) {
            val expected = known.evaluate(p)
            assertEquals(expected, cartesian.evaluate(p), 2e-6)
            assertEquals(expected, directional.evaluate(p), 2e-6)
            assertEquals(expected, rotated.evaluate(rotation.transform(Vec3(p)).add(displacement)), 2e-6)
            assertEquals(expected, affine.evaluate(linear.transform(Vec3(p)).add(displacement)), 2e-6)
            val gradient = known.gradient(p)
            for (axis in 0..2) {
                val step = Vec3().also { it[axis] = 0.001f }
                val finiteDifference = (known.evaluate(Vec3(p).add(step)) - known.evaluate(Vec3(p).sub(step))) / 0.002
                assertEquals(finiteDifference, gradient[axis].toDouble(), 2e-4)
            }
        }
    }

    @Test
    fun implicitReconstructionsAndTopologyChange() {
        val bounds = boundsAround(radius = 1.9)
        for ((source, genus) in listOf(sdfSphere(0.83) to 0, sdfTorus(0.91, 0.32) to 1)) {
            val field = PlaneWaveField.fitCartesian(bounds, intArrayOf(16, 16, 16), 120) {
                source(it).toDouble().coerceIn(-0.4, 0.4)
            }
            val body = field.extractSurface(bounds, resolution = 26)
            val topology = body.topology()
            assertTrue(topology.isWatertight)
            assertEquals(genus, topology.genus)
            assertTrue((0 until body.vertexCount).map { abs(source(body.position(it))) }.average() < 0.05)
        }
        val open = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
        assertFailsWith<IllegalArgumentException> { PlaneWaveField.meshTarget(open, 0.5) }

        val lobes = PlaneWaveField(0.0, listOf(
            PlaneWave(Vec3(2f, 0f, 0f), -1.0, 0.0),
            PlaneWave(Vec3(0f, 1f, 0f), 1.0, 0.0),
            PlaneWave(Vec3(0f, 0f, 1f), 1.0, 0.0),
        ))
        val domain = boundsAround(radius = PI)
        assertEquals(2, lobes.extractSurface(domain, 40, 1.2, false).topology().componentCount)
        assertEquals(1, lobes.extractSurface(domain, 40, 0.8, false).topology().componentCount)
    }

    @Test
    fun adaptiveDiscoveryAndIsotropicSynthesis() {
        val known = PlaneWaveField(0.35, listOf(
            PlaneWave(Vec3(1.33f, -0.29f, 0.38f), 0.9, -0.1),
            PlaneWave(Vec3(0.22f, 2.21f, 0.71f), -0.3, 0.52),
        ))
        val random = Random(112)
        fun point() = Vec3(random.nextDouble(-2.0, 2.0).toFloat(),
            random.nextDouble(-2.0, 2.0).toFloat(), random.nextDouble(-2.0, 2.0).toFloat())
        val samples = List(280) { point().let { FieldSample(it, known.evaluate(it)) } }
        val candidate = listOf(Vec3(1.4f, -0.38f, 0.5f), Vec3(0.27f, 2.1f, 0.62f), Vec3(0f, 0f, 4f))
        val steps = mutableListOf<Int>()
        val discovered = discoverPlaneWaves(samples, candidate, 2, refinementRadius = 0.36,
            refinementIterations = 65) { steps += it.waves.size }
        val fixed = PlaneWaveField.fitDirectional(samples, candidate.take(2))
        val heldOut = List(80) { point() }
        fun error(field: PlaneWaveField) = sqrt(heldOut.map {
            val d = field.evaluate(it) - known.evaluate(it)
            d * d
        }.average())
        assertEquals(listOf(1, 2), steps)
        assertTrue(error(discovered) < error(fixed) / 20, "adaptive error ${error(discovered)}, fixed error ${error(fixed)}")
        for (p in heldOut) assertEquals(known.evaluate(p), known.scale(1.8).evaluate(Vec3(p).mul(1.8f)), 1e-6)
        val singleWave = PlaneWaveField(0.0, listOf(PlaneWave(Vec3(1f, 0f, 0f), 1.0, 0.0)))
        val projected = checkNotNull(singleWave.projectToSurface(Vec3(1.37f, 2f, 3f)))
        assertEquals(PI / 2, projected.x.toDouble(), 1e-5)
        assertEquals(null, singleWave.projectToSurface(Vec3()))

        val shell = PlaneWaveField.random(203, seed = 47, variance = 0.6, radialFrequency = { 1.7 })
        assertEquals(0.6, shell.waves.sumOf { it.amplitude * it.amplitude / 2 }, 1e-12)
        for (p in heldOut) assertEquals(-1.7 * 1.7 * shell.evaluate(p), shell.laplacian(p), 2e-6)
        val statistics = (0..2).map { axis -> shell.waves.map { wave ->
            val component = wave.wavevector[axis] / wave.frequency
            component * component
        }.average() }
        assertTrue(statistics.all { abs(it - 1.0 / 3) < 0.07 })
    }

    @Test
    fun fitsAConcaveMeshWithSeveralComponents() {
        val bounds = boundsAround(radius = 2.0)
        val source = sdfUnion(
            sdfSubtract(sdfSphere(0.8, Vec3(-0.45f, 0f, 0f)), sdfSphere(0.54, Vec3(-0.83f, 0.24f, 0.48f))),
            sdfSphere(0.38, Vec3(1.00f, 0f, 0f)),
        )
        val mesh = marchingCubes(source, bounds, 40)
        val target = PlaneWaveField.meshTarget(mesh, 0.3)
        val fitted = PlaneWaveField.fitCartesian(bounds, intArrayOf(32, 32, 32), 280, target)
        val topology = fitted.extractSurface(bounds, 36).topology()
        assertTrue(topology.isWatertight)
        assertEquals(2, topology.componentCount)
        assertTrue(fitted.evaluate(Vec3(-0.45f, 0f, 0f)) < 0.0)
        assertTrue(fitted.evaluate(Vec3(-0.83f, 0.24f, 0.48f)) > 0.0)
        assertTrue(fitted.evaluate(Vec3(1f, 0f, 0f)) < 0.0)
    }

    @Test
    fun stackSLMatchesTheFieldAndItsDifferential() {
        val field = PlaneWaveField(0.35, listOf(
            PlaneWave(Vec3(1.33f, -0.29f, 0.38f), 0.9, -0.1),
            PlaneWave(Vec3(0.22f, 2.21f, 0.71f), -0.3, 0.52),
        ))
        val compiled = compileStackSL(field.toStackSL())
        assertTrue(compiled.ok, compiled.diagnostics.joinToString())
        val shader = parseGlslShader(compiled.glsl)
        val point = Vec3(0.37f, -0.4f, 0.6f)
        val tape = ADTape()
        val scalars = GlslAdScalars(tape)
        val position = Array(3) { scalars.variable(point[it].toDouble()) }
        val input = AdValue(GlslType.VEC4, position + scalars.constant(17.0))
        val result = shader.newMachine(scalars).call("stacksl", listOf(input)).c[0]
        assertEquals(field.evaluate(point), result.value, 1e-7)
        val derivatives = tape.backward(result)
        val expected = field.gradient(point)
        for (axis in 0..2) assertEquals(expected[axis].toDouble(), derivatives[position[axis].index], 2e-7)
    }

    @Test
    fun sparseSphereEigenspacesAndHeat() {
        val mesh = icosphereMesh(subdivisions = 2)
        val laplacian = CotangentLaplacian(mesh)
        val masses = laplacian.vertexMasses
        val basis = laplacian.basis(6)
        val eigenvalues = basis.eigenvalues
        assertEquals(9, basis.modeCount)
        assertEquals(0.0, eigenvalues[0])
        assertTrue(eigenvalues.slice(1..3).all { abs(it - 2) < 0.02 })
        assertTrue(eigenvalues.slice(4..8).all { abs(it - 6) < 0.25 })
        val functions = Array(basis.modeCount, basis::mode)
        for (i in eigenvalues.indices) {
            val applied = laplacian.apply(functions[i])
            for (v in masses.indices) assertEquals(eigenvalues[i] * masses[v] * functions[i][v], applied[v], 2e-7)
            for (j in eigenvalues.indices) {
                assertEquals(if (i == j) 1.0 else 0.0,
                    masses.indices.sumOf { v -> functions[i][v] * functions[j][v] * masses[v] }, 1e-7)
            }
        }
        val input = DoubleArray(mesh.positions.size) { mesh.positions[it].toDouble() }
        assertTrue(basis.reconstructionError(input, basis.truncate(basis.positions(), 2)) < 0.01)
        val signal = DoubleArray(mesh.vertexCount) { 2.0 + mesh.x(it) }
        val filtered = basis.reconstruct(basis.diffuse(basis.analyze(signal), 8.0))
        assertTrue(filtered.all { abs(it - 2.0) < 1e-6 })
        val rotation = quat(0.86, Vec3(0.1f, -0.8f, 0.4f).normalize())
        val rotated = FloatArray(input.size)
        for (vertex in 0 until mesh.vertexCount) {
            val p = rotation.transform(mesh.position(vertex))
            for (axis in 0..2) rotated[vertex * 3 + axis] = p[axis]
        }
        val rotatedBasis = CotangentLaplacian(TriMesh(rotated, mesh.indices)).basis(4)
        val projected = rotatedBasis.reconstructMesh(rotatedBasis.positions())
        val original = basis.reconstructMesh(basis.truncate(basis.positions(), 4))
        for (vertex in 0 until mesh.vertexCount) {
            assertTrue(projected.position(vertex).distance(rotation.transform(original.position(vertex))) < 2e-5)
        }
    }

    @Test
    fun openMeshContoursAndSurfaceFlow() {
        val mesh = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f),
            intArrayOf(0, 1, 2, 0, 2, 3))
        val laplacian = CotangentLaplacian(mesh)
        assertEquals(1, laplacian.componentCount)
        assertTrue(laplacian.apply(DoubleArray(4) { 1.0 }).all { abs(it) < 1e-12 })
        val basis = laplacian.basis(4)
        val signal = doubleArrayOf(0.0, 1.0, 1.0, 0.0)
        assertEquals(4, basis.contours(signal, 0.5).nodes.size)
        val flow = basis.flow(signal)
        val start = flow.start(Vec3(0.2f, 0.4f, 0.2f))
        val end = flow.position(flow.step(start, 0.6))
        assertTrue(end.distance(Vec3(0.8f, 0.4f, 0f)) < 1e-6)
        assertEquals(1.0, flow.position(flow.step(start, 2.0)).x.toDouble(), 1e-6)
        val otherBasis = CotangentLaplacian(mesh).basis(4)
        assertFailsWith<IllegalArgumentException> { otherBasis.reconstruct(basis.analyze(signal)) }
    }
}
