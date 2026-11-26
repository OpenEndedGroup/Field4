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

    private fun heart(t: Double): Double {
        val a = 0.75 * (1 - 0.15 * sin(t))
        val b = 0.9
        val oval = a * b / kotlin.math.sqrt((b * cos(t)) * (b * cos(t)) + (a * sin(t)) * (a * sin(t)))
        return oval * (1 + 0.2 * Math.pow(kotlin.math.max(0.0, sin(t)), 8.0)) * (1 - 0.45 * kotlin.math.exp(-(1 + sin(t)) / 0.024))
    }

    private fun outline(r: (Double) -> Double, n: Int = 400) =
        DoubleArray(n) { r(2 * PI * it / n) * cos(2 * PI * it / n) } to DoubleArray(n) { r(2 * PI * it / n) * sin(2 * PI * it / n) }

    /** The outline mesher gives a heart-shaped leaf, notch and all, triangles with no angle under 20°. */
    @Test
    fun anOutlineMeshHasNoThinTriangles() {
        val (x, y) = outline(::heart)
        val mesh = PlanarMesh.outline(x, y, 0.06)
        var smallest = PI
        for (t in 0 until mesh.triangleCount) for (k in 0..2) {
            val a = mesh.triangles[3 * t + k]
            val b = mesh.triangles[3 * t + (k + 1) % 3]
            val c = mesh.triangles[3 * t + (k + 2) % 3]
            val ux = mesh.x[b] - mesh.x[a]
            val uy = mesh.y[b] - mesh.y[a]
            val vx = mesh.x[c] - mesh.x[a]
            val vy = mesh.y[c] - mesh.y[a]
            smallest = kotlin.math.min(smallest, kotlin.math.acos((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))))
        }
        assertTrue(smallest > 20 * PI / 180, "smallest angle ${smallest * 180 / PI}°")
        // The mesh fills the outline: its area matches the polygon's.
        val polygon = 0.5 * x.indices.sumOf { x[it] * y[(it + 1) % x.size] - x[(it + 1) % x.size] * y[it] }
        assertEquals(polygon, mesh.areas.sum(), 0.01 * polygon)
    }

    private fun ellipse(t: Double) = 0.5 / kotlin.math.sqrt(cos(t) * cos(t) + 0.25 * sin(t) * sin(t))

    private fun quad(r: (Double) -> Double, h: Double = 0.06): Pair<PlanarMesh, IntArray> {
        val (x, y) = outline(r)
        val mesh = PlanarMesh.outline(x, y, h)
        return mesh to IntArray(4) { mesh.nearest(x[100 * it], y[100 * it]) }
    }

    /** A square with its corners marked has modulus 1, measured either way. */
    @Test
    fun aSquareHasModulusOne() {
        val n = 16
        val mesh = PlanarMesh.grid(n)
        val corners = intArrayOf(0, n, (n + 1) * (n + 1) - 1, n * (n + 1))
        val r = RectangleMap(mesh, corners)
        assertEquals(1.0, r.modulus, 1e-9)
        assertEquals(1.0, r.dualModulus, 1e-9)
    }

    /** An ellipse with its landmarks off the axes, and a circle with four evenly spaced ones: a real distance. */
    private fun lopsidedPair(): List<Any> {
        val (sx, sy) = outline(::ellipse)
        val a = PlanarMesh.outline(sx, sy, 0.05)
        val la = intArrayOf(0, 60, 200, 300).map { a.nearest(sx[it], sy[it]) }.toIntArray()
        val (tx, ty) = outline({ 1.0 })
        val b = PlanarMesh.outline(tx, ty, 0.08)
        val lb = intArrayOf(0, 100, 200, 300).map { b.nearest(tx[it], ty[it]) }.toIntArray()
        return listOf(a, la, b, lb, tx, ty)
    }

    /** The map through rectangles has |μ| = tanh(d) over most of the shape, and turns nothing over. */
    @Test
    fun theQuadrilateralMapHasUniformDistortion() {
        val (a, la, b, lb) = lopsidedPair()
        val (map, exact) = quadrilateralTeichmullerMap(a as PlanarMesh, la as IntArray, b as PlanarMesh, lb as IntArray)
        val k = kotlin.math.tanh(exact)
        assertTrue(exact > 0.03, "exact $exact")
        assertEquals(0, map.flipped)
        assertEquals(k, map.medianModulus, 0.04)
        assertTrue(map.areaNear(k, 0.1) > 0.75, "area near k: ${map.areaNear(k, 0.1)}")
    }

    /**
     * Two mirror-symmetric leaves with tip, base and two pairs of margin landmarks: the map has
     * |μ| = tanh(d) over most of the leaf, turns nothing over, and holding the whole outline at an
     * arc-length guess gives a larger distance.
     */
    @Test
    fun mirrorLeavesHaveUniformDistortionAndALowerDistance() {
        val c = MirrorShapeComparison(::ellipse, ::heart)
        val k = kotlin.math.tanh(c.distance)
        assertTrue(c.distance > 0.05, "distance ${c.distance}")
        assertEquals(0, c.map.flipped)
        assertEquals(k, c.map.medianModulus, 0.04)
        assertTrue(c.map.areaNear(k, 0.05) > 0.8, "area near k: ${c.map.areaNear(k, 0.05)}")
        // The same leaves with every outline vertex held at an arc-length guess.
        val (sx, sy) = outline(::ellipse)
        val (tx, ty) = outline(::heart)
        val mesh = PlanarMesh.outline(sx, sy, 0.05)
        val marks = intArrayOf(0, 100, 200, 300)
        val held = teichmullerMap(mesh, boundaryCorrespondence(mesh, marks.map { mesh.nearestBoundary(sx[it], sy[it]) }.toIntArray(), tx, ty, marks))
        assertTrue(held.distance > c.distance, "held ${held.distance} vs ${c.distance}")
    }

    /** A leaf compared with itself is at distance zero, and so is a leaf twice the size. */
    @Test
    fun aLeafIsAtDistanceZeroFromItselfAtAnySize() {
        assertEquals(0.0, MirrorShapeComparison(::heart, ::heart).distance, 1e-9)
        // The larger leaf is meshed relatively finer, which shifts the distance a little.
        assertEquals(0.0, MirrorShapeComparison(::heart, { 2 * heart(it) }).distance, 0.03)
    }

    /** The distortion colours run from blue at zero to near-white at the top of the scale, and a mesh paints its pixels with them. */
    @Test
    fun distortionColoursPaintAMesh() {
        val low = MeshPicture.distortionColor(0.0)
        val high = MeshPicture.distortionColor(0.6, top = 0.6)
        assertTrue((low and 0xFF) > ((low shr 16) and 0xFF), "zero is blue")
        assertTrue(((high shr 16) and 0xFF) > 0xF0, "the top is near white")
        assertEquals(high, MeshPicture.distortionColor(5.0, top = 0.6), "beyond the top stays at the top")
        val mesh = PlanarMesh.grid(4)
        val picture = MeshPicture(40, 40, 0.0, 0.0, 1.0, 1.0)
        picture.mesh(mesh, colors = IntArray(mesh.triangleCount) { low }, edgeAlpha = 0.0)
        assertEquals(low, picture.pixels[20 * 40 + 20])
    }

    /** Growing a shape is conformal: a leaf and the same leaf twice the size are at distance zero. */
    @Test
    fun sizeCostsNothing() {
        val (a, la) = quad(::heart)
        val (b, lb) = quad({ 2 * heart(it) }, 0.12)
        assertEquals(0.0, quadrilateralTeichmullerMap(a, la, b, lb).second, 0.01)
    }

}
