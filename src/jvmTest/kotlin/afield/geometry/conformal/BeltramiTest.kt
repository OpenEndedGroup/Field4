package afield.geometry.conformal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeltramiTest {
    /** The affine map z ↦ z + k·z̄ has Beltrami coefficient k on every triangle. */
    @Test
    fun anAffineMapHasItsOwnCoefficientEverywhere() {
        val mesh = PlanarMesh.grid(6)
        val kr = 0.3
        val ki = -0.2
        val u = DoubleArray(mesh.vertexCount) { mesh.x[it] + kr * mesh.x[it] + ki * mesh.y[it] }
        val v = DoubleArray(mesh.vertexCount) { mesh.y[it] + ki * mesh.x[it] - kr * mesh.y[it] }
        val mu = beltrami(mesh, u, v)
        for (t in 0 until mesh.triangleCount) {
            assertEquals(kr, mu[2 * t], 1e-12)
            assertEquals(ki, mu[2 * t + 1], 1e-12)
        }
    }

    /** z ↦ z² is conformal away from 0, so its piecewise-linear version has small |μ| on a fine mesh. */
    @Test
    fun aConformalMapHasNearlyZeroCoefficient() {
        val mesh = PlanarMesh.grid(20, 1.0, 1.0, 1.0)
        val u = DoubleArray(mesh.vertexCount) { mesh.x[it] * mesh.x[it] - mesh.y[it] * mesh.y[it] }
        val v = DoubleArray(mesh.vertexCount) { 2 * mesh.x[it] * mesh.y[it] }
        assertTrue(modulus(beltrami(mesh, u, v)).max() < 0.03)
    }

    /** Given a smooth map's own μ and its boundary values, the solver gives back the map inside. */
    @Test
    fun theSolverRecoversAMapFromItsCoefficient() {
        val mesh = PlanarMesh.grid(24)
        fun f(x: Double, y: Double) = doubleArrayOf(x + 0.15 * sin(PI * x) * sin(PI * y) + 0.1 * y * y, y + 0.12 * sin(2 * PI * x) * y * (1 - y))
        val u = DoubleArray(mesh.vertexCount) { f(mesh.x[it], mesh.y[it])[0] }
        val v = DoubleArray(mesh.vertexCount) { f(mesh.x[it], mesh.y[it])[1] }
        val mu = beltrami(mesh, u, v)
        val fixed = mesh.boundary.associateWith { doubleArrayOf(u[it], v[it]) }
        val (su, sv) = LinearBeltramiSolver(mesh).solve(mu, fixed)
        val error = (0 until mesh.vertexCount).maxOf { hypot(su[it] - u[it], sv[it] - v[it]) }
        assertTrue(error < 2e-3, "error $error")
        // The harmonic map with the same boundary is a different map.
        val h = harmonicMap(mesh, fixed)
        assertTrue((0 until mesh.vertexCount).maxOf { hypot(h.u[it] - u[it], h.v[it] - v[it]) } > 10 * error)
    }

    /**
     * Dragging an interior landmark: the Teichmüller map spreads its distortion evenly, so its |μ|
     * varies far less than the harmonic map's and its largest |μ| is smaller.
     */
    @Test
    fun theTeichmullerMapHasNearlyUniformAndSmallerDistortion() {
        val mesh = PlanarMesh.grid(20)
        val fixed = mesh.boundaryIdentity() + (mesh.nearest(0.35, 0.5) to doubleArrayOf(0.62, 0.55))
        val h = harmonicMap(mesh, fixed)
        val t = teichmullerMap(mesh, fixed, iterations = 120)
        assertEquals(0, t.flipped)
        val spread = { m: QuasiConformalMap -> m.maxModulus - m.minModulus }
        assertTrue(t.maxModulus < h.maxModulus, "T ${t.maxModulus} vs harmonic ${h.maxModulus}")
        assertTrue(spread(t) < 0.5 * spread(h), "spread T ${spread(t)} vs harmonic ${spread(h)}")
    }

    /** Stretching a square into a rectangle with its boundary fixed to the affine stretch: the extremal map is the stretch, K = the ratio. */
    @Test
    fun theExtremalStretchOfASquareIsAffine() {
        val mesh = PlanarMesh.grid(12)
        val fixed = mesh.boundary.associateWith { doubleArrayOf(2 * mesh.x[it], mesh.y[it]) }
        val t = teichmullerMap(mesh, fixed)
        assertEquals(2.0, t.dilatation, 1e-6)
        assertEquals(0.5 * kotlin.math.ln(2.0), t.distance, 1e-6)
    }

    /** A star mesh covers its outline, every triangle counter-clockwise, and a shape compared with itself is at distance zero. */
    @Test
    fun aShapeIsAtDistanceZeroFromItself() {
        val r = { a: Double -> 1.0 + 0.3 * cos(2 * a) }
        val mesh = PlanarMesh.star(r, rings = 8, sectors = 48)
        assertEquals(PI * (1 + 0.3 * 0.3 / 2), mesh.areas.sum(), 0.03)
        val outline = 400
        val tx = DoubleArray(outline) { r(2 * PI * it / outline) * cos(2 * PI * it / outline) }
        val ty = DoubleArray(outline) { r(2 * PI * it / outline) * sin(2 * PI * it / outline) }
        val b = mesh.boundary
        val landmarks = intArrayOf(b[0], b[b.size / 4], b[b.size / 2], b[3 * b.size / 4])
        val fixed = boundaryCorrespondence(mesh, landmarks, tx, ty, intArrayOf(0, outline / 4, outline / 2, 3 * outline / 4))
        val t = teichmullerMap(mesh, fixed)
        assertTrue(abs(t.distance) < 0.02, "distance ${t.distance}")
    }
}
