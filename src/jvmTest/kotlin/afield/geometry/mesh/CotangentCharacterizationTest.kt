package afield.geometry.mesh

import afield.geometry.spectral.CotangentLaplacian
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the numbers every cotangent-Laplacian caller produced before they were moved onto one shared
 * operator ([cotangentWeights], [barycentricVertexAreas], [cotangentLaplacianMatrix]): cotangent
 * smoothing, the heat method, mixed areas and both curvatures, and the spectral [CotangentLaplacian].
 *
 * Each caller is reduced to three digests on three meshes — a closed icosphere, an open bumpy grid
 * (with boundary) and a sheared bumpy grid whose triangles are obtuse — and compared at a relative
 * tolerance of `1e-5`. The pinned values were recorded at `08b2dc2280`, before the refactor.
 */
class CotangentCharacterizationTest {

    private fun bumpy(mesh: TriMesh, shear: Float): TriMesh {
        val p = mesh.positions.copyOf()
        for (v in 0 until mesh.vertexCount) {
            val x = p[v * 3]; val y = p[v * 3 + 1]
            p[v * 3] = x + shear * y + 0.013f * sin(7.0f * v)
            p[v * 3 + 1] = y + 0.011f * sin(3.0f * v + 1f)
            p[v * 3 + 2] = 0.15f * sin(3f * x) * sin(2f * y) + 0.02f * sin(5.0f * v)
        }
        return TriMesh(p, mesh.indices.copyOf())
    }

    private val meshes: Map<String, TriMesh> by lazy {
        val ico = icosphereMesh(2, 1.0)
        val p = ico.positions.copyOf()
        for (i in p.indices) p[i] *= 1f + 0.03f * sin(1.7f * i)
        mapOf(
            "sphere" to TriMesh(p, ico.indices.copyOf()),
            "grid" to bumpy(planeMesh(8, 8, 2.0, 2.0), 0f),
            "obtuse" to bumpy(planeMesh(8, 8, 2.0, 2.0), 1.6f),
        )
    }

    private fun digest(v: DoubleArray): DoubleArray {
        var s = 0.0; var a = 0.0; var w = 0.0
        for (i in v.indices) { s += v[i]; a += abs(v[i]); w += v[i] * ((i % 7) + 1) }
        return doubleArrayOf(s, a, w)
    }

    private fun digest(v: FloatArray) = digest(DoubleArray(v.size) { v[it].toDouble() })

    /** Every caller's output, by name. */
    private fun observe(): Map<String, DoubleArray> {
        val out = LinkedHashMap<String, DoubleArray>()
        for ((name, m) in meshes) {
            out["$name.laplacianSmooth"] = digest(m.laplacianSmooth(4, 0.5, cotangent = true).positions)
            out["$name.laplacianSmoothFree"] = digest(m.laplacianSmooth(3, 0.4, cotangent = true, preserveBoundary = false).positions)
            out["$name.taubin"] = digest(m.taubinSmooth(3, cotangent = true).positions)
            out["$name.heat"] = digest(HeatGeodesics(m).distance(0, m.vertexCount / 2))
            if (name != "sphere") out["$name.heatBoundary"] = digest(m.geodesicDistanceFromBoundary())
            out["$name.mixedArea"] = digest(m.mixedVertexAreas())
            out["$name.gaussian"] = digest(m.gaussianCurvature())
            out["$name.mean"] = digest(m.meanCurvature())
            val l = CotangentLaplacian(m)
            out["$name.spectralMass"] = digest(l.vertexMasses)
            out["$name.spectralApply"] = digest(l.apply(DoubleArray(m.vertexCount) { m.x(it) + 0.5 * m.z(it) * m.z(it) }))
        }
        out["sphere.spectralEigen"] = CotangentLaplacian(meshes.getValue("sphere")).basis(6).eigenvalues
        return out
    }

    @Test
    fun everyCotangentCallerKeepsItsNumbers() {
        val seen = observe()
        val failures = mutableListOf<String>()
        for ((key, values) in seen) {
            val want = PINNED[key]
            if (want == null) { failures += "$key unpinned: ${values.joinToString { "%.12e".format(it) }}"; continue }
            for (i in values.indices) {
                val tol = 1e-5 * maxOf(1.0, abs(want[i]))
                if (abs(values[i] - want[i]) > tol) failures += "$key[$i]: got %.12e, pinned %.12e".format(values[i], want[i])
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private companion object {
        val PINNED: Map<String, DoubleArray> = mapOf(
            "sphere.laplacianSmooth" to doubleArrayOf(-4.808441643399e-02, 2.208355390084e+02, 4.248983306366e+00),
            "sphere.laplacianSmoothFree" to doubleArrayOf(-4.469380510636e-02, 2.287937898869e+02, 4.476024761065e+00),
            "sphere.taubin" to doubleArrayOf(-3.880256726916e-02, 2.417853021922e+02, 4.847921129909e+00),
            "sphere.heat" to doubleArrayOf(1.807426129581e+02, 1.807426129581e+02, 7.332007791374e+02),
            "sphere.mixedArea" to doubleArrayOf(1.237512072475e+01, 1.237512072475e+01, 4.923102214046e+01),
            "sphere.gaussian" to doubleArrayOf(1.650066939877e+02, 1.661741298568e+02, 6.592019657808e+02),
            "sphere.mean" to doubleArrayOf(1.616758845277e+02, 1.616758845277e+02, 6.446062371536e+02),
            "sphere.spectralMass" to doubleArrayOf(1.237512070847e+01, 1.237512070847e+01, 4.927482061905e+01),
            "sphere.spectralApply" to doubleArrayOf(2.386979502944e-15, 1.561593770956e+01, 3.408174590257e+00),
            "grid.laplacianSmooth" to doubleArrayOf(2.715167735005e-02, 9.388500970608e+01, -2.950383930351e+00),
            "grid.laplacianSmoothFree" to doubleArrayOf(-3.722575969005e-02, 8.965667961228e+01, -2.794938969717e+00),
            "grid.taubin" to doubleArrayOf(-4.875632614130e-03, 9.471355978175e+01, -3.054473992204e+00),
            "grid.heat" to doubleArrayOf(8.972145641960e+01, 8.972145641960e+01, 3.489706842793e+02),
            "grid.heatBoundary" to doubleArrayOf(2.175737723577e+01, 2.175737723577e+01, 8.701480859985e+01),
            "grid.mixedArea" to doubleArrayOf(4.133040050676e+00, 4.133040050676e+00, 1.642256135096e+01),
            "grid.gaussian" to doubleArrayOf(3.712223776702e+02, 4.269841998361e+02, 9.313511031825e+02),
            "grid.mean" to doubleArrayOf(-5.245877523669e-01, 2.145644186399e+01, 6.058239559205e+00),
            "grid.spectralMass" to doubleArrayOf(4.133040064347e+00, 4.133040064347e+00, 1.641816686310e+01),
            "grid.spectralApply" to doubleArrayOf(-3.191891195797e-16, 4.427936787898e+00, 1.342564239191e-01),
            "obtuse.laplacianSmooth" to doubleArrayOf(2.354323235340e-02, 1.308680489450e+02, -7.002982522827e-01),
            "obtuse.laplacianSmoothFree" to doubleArrayOf(-3.002136887517e-02, 1.255689920123e+02, -1.138301036903e+00),
            "obtuse.taubin" to doubleArrayOf(-6.482674507424e-05, 1.319949745105e+02, -6.383342019399e-01),
            "obtuse.heat" to doubleArrayOf(1.381020248320e+02, 1.381020248320e+02, 5.425704183595e+02),
            "obtuse.heatBoundary" to doubleArrayOf(1.270931396551e+01, 1.270931396551e+01, 5.047088147025e+01),
            "obtuse.mixedArea" to doubleArrayOf(4.399140780437e+00, 4.399140780437e+00, 1.743883203632e+01),
            "obtuse.gaussian" to doubleArrayOf(3.386097019820e+02, 4.061185795354e+02, 8.723957497888e+02),
            "obtuse.mean" to doubleArrayOf(5.552020168094e+00, 5.999149502724e+01, 3.281118385915e+01),
            "obtuse.spectralMass" to doubleArrayOf(4.399140776066e+00, 4.399140776066e+00, 1.743534815022e+01),
            "obtuse.spectralApply" to doubleArrayOf(0.000000000000e+00, 5.327161694261e+00, 2.588721528562e-01),
            "sphere.spectralEigen" to doubleArrayOf(0.000000000000e+00, 1.987877747827e+00, 1.992984812422e+00, 1.996813975166e+00, 5.809508533237e+00, 5.830862499601e+00),
        )
    }
}
