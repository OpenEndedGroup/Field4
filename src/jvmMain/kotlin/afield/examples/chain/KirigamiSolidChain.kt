package afield.examples.chain

import afield.geometry.conformal.KirigamiInverseDesign
import afield.geometry.mesh.MeshView
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.renderPng
import afield.geometry.mesh.topology
import afield.geometry.mesh.volume
import afield.geometry.solid.manifold.FillRule
import afield.geometry.solid.manifold.Section
import afield.geometry.solid.manifold.Solid
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
FIELD_TEST
id: chain.kirigami-solid
runner: jvm-main
entrypoint: afield.examples.chain.KirigamiSolidChain
tags: [smoke, chain, kirigami, manifold, mesh, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "kirigami-solid chain passed"
*/
/**
 * A rotating-squares kirigami sheet becomes a solid you could cut or print, at any opening angle.
 *
 * The [kirigami page](https://openendedgroup.github.io/Field4/kirigami/) treats a hinge as one shared
 * point, which is exact for the mechanism and impossible to make. This object gives each hinge a
 * width: every square tile is shrunk about its centre so the cuts become slits of width `2 * gap`,
 * and each hinge becomes a short bar of material across the slit, joining only the two tiles the
 * hinge joins. The 2D region goes to Manifold as a [Section] and is extruded into a [Solid].
 *
 * The pattern and hinge layout are those of the `kirigami_choi` transcript's first box (Choi's unit
 * cell): at grid node `(a, b)` the four meeting corners pair left-right when `a + b` is even and
 * up-down when it is odd. With `n * n` tiles there are `2n(n - 1)` hinges, so the tile graph's cycle
 * rank, and therefore the genus of a solid made this way, is `2n(n - 1) - n^2 + 1 = (n - 1)^2`: one
 * through-hole per interior grid node, at every opening angle. [measure] reports Manifold's own genus
 * so the two can be compared.
 */
object KirigamiSolidChain {

    /** A hinge: tiles `a` and `b` (as `i + n * j`), the grid node they share, and the other node of the cut edge. */
    data class Hinge(val a: Int, val b: Int, val node: Pair<Int, Int>, val along: Pair<Int, Int>)

    /** Every hinge of an `n x n` rotating-squares sheet, `2n(n - 1)` of them. */
    fun hinges(n: Int): List<Hinge> {
        val out = mutableListOf<Hinge>()
        for (j in 0 until n) for (i in 0 until n - 1) {
            // vertical cut at x = i + 1 between tiles (i, j) and (i + 1, j)
            val row = if ((i + 1 + j) % 2 == 0) j else j + 1
            out += Hinge(i + n * j, i + 1 + n * j, i + 1 to row, i + 1 to (if (row == j) j + 1 else j))
        }
        for (j in 0 until n - 1) for (i in 0 until n) {
            // horizontal cut at y = j + 1 between tiles (i, j) and (i, j + 1)
            val col = if ((i + j + 1) % 2 == 1) i else i + 1
            out += Hinge(i + n * j, i + n * (j + 1), col to j + 1, (if (col == i) i + 1 else i) to j + 1)
        }
        return out
    }

    private fun cornerNodes(i: Int, j: Int) = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1)

    /**
     * Corner positions of every unit tile at opening angle [angle] (radians), keyed by grid node,
     * indexed by tile `i + n * j`. Tile `(i, j)` turns by `-angle` when `i + j` is even and `+angle`
     * when odd; centres are placed on the lattice of spacing `cos(angle) + sin(angle)`, which is where
     * the hinge walk in the `kirigami_choi` transcript puts them. [hingeGap] checks that.
     */
    fun deploy(n: Int, angle: Double): List<Map<Pair<Int, Int>, DoubleArray>> {
        val s = cos(angle) + sin(angle)
        return (0 until n * n).map { k ->
            val i = k % n
            val j = k / n
            val turn = if ((i + j) % 2 == 0) -angle else angle
            val cx = (i + 0.5) * s
            val cy = (j + 0.5) * s
            cornerNodes(i, j).associateWith { (a, b) ->
                val dx = a - (i + 0.5)
                val dy = b - (j + 0.5)
                doubleArrayOf(cx + dx * cos(turn) - dy * sin(turn), cy + dx * sin(turn) + dy * cos(turn))
            }
        }
    }

    /** The largest distance between the two corners a hinge joins; `~1e-15` when [deploy] is a true mechanism. */
    fun hingeGap(n: Int, angle: Double): Double {
        val tiles = deploy(n, angle)
        return hinges(n).maxOf { h ->
            val p = tiles[h.a].getValue(h.node)
            val q = tiles[h.b].getValue(h.node)
            hypot(p[0] - q[0], p[1] - q[1])
        }
    }

    /**
     * A hinge to bridge, by corner index within each tile: tile [tileA]'s corner [hingeA] is joined to
     * tile [tileB]'s corner [hingeB], and [endA] / [endB] are the far corners of the cut edge each side.
     */
    data class Bridge(val tileA: Int, val hingeA: Int, val endA: Int, val tileB: Int, val hingeB: Int, val endB: Int)

    /** The rotating-squares hinges as [Bridge]s, for [bridgedSection]. */
    fun bridges(n: Int): List<Bridge> = hinges(n).map { h ->
        val (ia, ja) = h.a % n to h.a / n
        val (ib, jb) = h.b % n to h.b / n
        val ca = cornerNodes(ia, ja)
        val cb = cornerNodes(ib, jb)
        Bridge(h.a, ca.indexOf(h.node), ca.indexOf(h.along), h.b, cb.indexOf(h.node), cb.indexOf(h.along))
    }

    /** [polygon] with every edge moved inward by [gap]; exact for a convex polygon of either winding. */
    fun inset(polygon: List<DoubleArray>, gap: Double): List<DoubleArray> {
        val m = polygon.size
        val area = (0 until m).sumOf { polygon[it][0] * polygon[(it + 1) % m][1] - polygon[(it + 1) % m][0] * polygon[it][1] }
        val side = if (area > 0) 1.0 else -1.0
        fun inward(i: Int): DoubleArray {
            val p = polygon[i]
            val q = polygon[(i + 1) % m]
            val l = hypot(q[0] - p[0], q[1] - p[1])
            return doubleArrayOf(-side * (q[1] - p[1]) / l, side * (q[0] - p[0]) / l)
        }
        return (0 until m).map { i ->
            val n1 = inward((i + m - 1) % m)
            val n2 = inward(i)
            val k = gap / (1 + n1[0] * n2[0] + n1[1] * n2[1])
            doubleArrayOf(polygon[i][0] + k * (n1[0] + n2[0]), polygon[i][1] + k * (n1[1] + n2[1]))
        }
    }

    /**
     * The fabricable 2D region for any quad sheet: every tile of [tiles] inset by [gap], so each cut is
     * a slit `2 * gap` wide, plus one bar per [Bridge] reaching [bridge] along the cut from one tile's
     * inset hinge corner to the other's. The bar joins those two tiles and no others.
     */
    fun bridgedSection(tiles: List<List<DoubleArray>>, bridges: List<Bridge>, gap: Double = 0.04, bridge: Double = 0.12): Section {
        val insets = tiles.map { inset(it, gap) }
        val parts = insets.map { t -> Section.ofDoublePolygon(t.map { it[0] to it[1] }, FillRule.NON_ZERO) }.toMutableList()
        for (b in bridges) {
            val ends = listOf(Triple(b.tileA, b.hingeA, b.endA), Triple(b.tileB, b.hingeB, b.endB)).map { (k, h, e) ->
                val p = insets[k][h]
                val q = insets[k][e]
                val len = hypot(q[0] - p[0], q[1] - p[1])
                val ux = (q[0] - p[0]) / len
                val uy = (q[1] - p[1]) / len
                // Start a little inside the tile so the bar overlaps it rather than touching an edge.
                val c = doubleArrayOf(insets[k].sumOf { it[0] } / insets[k].size, insets[k].sumOf { it[1] } / insets[k].size)
                val cl = hypot(c[0] - p[0], c[1] - p[1])
                val ex = (c[0] - p[0]) / cl * gap * 0.5
                val ey = (c[1] - p[1]) / cl * gap * 0.5
                listOf((p[0] + ex) to (p[1] + ey), (p[0] + ex + ux * bridge) to (p[1] + ey + uy * bridge))
            }
            // Walk A's end out and B's end back, so the quad is simple; NON_ZERO fills it either way round.
            val quad = listOf(ends[0][0], ends[0][1], ends[1][1], ends[1][0])
            parts += Section.ofDoublePolygon(quad, FillRule.NON_ZERO).hull()
        }
        return Section.union(parts)
    }

    /** The rotating-squares region at opening [angle] (radians): [bridgedSection] over [deploy]. */
    fun section(n: Int, angle: Double, gap: Double = 0.04, bridge: Double = 0.12): Section {
        val tiles = deploy(n, angle).mapIndexed { k, corners -> cornerNodes(k % n, k / n).map { corners.getValue(it) } }
        return bridgedSection(tiles, bridges(n), gap, bridge)
    }

    /** The inverse-designed sheet's tiles at [state] (its opened or closed points) and its cuts as [Bridge]s. */
    fun designSheet(design: KirigamiInverseDesign.Design, state: DoubleArray): Pair<List<List<DoubleArray>>, List<Bridge>> {
        val tiles = design.problem.tiles
        val bridges = design.problem.cuts.map { c ->
            Bridge(c.tileA, tiles[c.tileA].indexOf(c.hinge), tiles[c.tileA].indexOf(c.endA), c.tileB, tiles[c.tileB].indexOf(c.hinge), tiles[c.tileB].indexOf(c.endB))
        }
        return design.tileCorners(state) to bridges
    }

    /** What the solid measures. */
    data class Measured(
        val angle: Double,
        val hinges: Int,
        val tiles: Int,
        val sectionArea: Double,
        val hullArea: Double,
        val volume: Double,
        val meshVolume: Double,
        val genus: Int,
        val parts: Int,
        val watertight: Boolean,
        val hingeGap: Double,
        val mesh: TriMesh,
    ) {
        /** The genus the tile graph predicts: hinges - tiles + 1. */
        val predictedGenus: Int get() = hinges - tiles + 1
    }

    /** Builds the rotating-squares solid at [angle] (radians), [thickness] thick, and measures it. */
    fun measure(n: Int = 8, angle: Double, thickness: Double = 0.1, gap: Double = 0.04, bridge: Double = 0.12): Measured =
        measureRegion(section(n, angle, gap, bridge), angle, thickness, hinges(n).size, n * n, hingeGap(n, angle))

    /** Extrudes [region] by [thickness] and measures the solid. */
    fun measureRegion(region: Section, angle: Double, thickness: Double, hinges: Int, tiles: Int, hingeGap: Double): Measured {
        // Lay the sheet flat, thickness along +y, so a camera above it is above it.
        val solid: Solid = region.extruded(thickness).rotated(-90.0, 0.0, 0.0)
        val mesh = solid.toTriMesh()
        return Measured(
            angle = angle,
            hinges = hinges,
            tiles = tiles,
            sectionArea = region.area,
            hullArea = region.hull().area,
            volume = solid.volume,
            meshVolume = mesh.volume(),
            genus = solid.genus,
            parts = solid.decompose().size,
            watertight = mesh.topology().isWatertight,
            hingeGap = hingeGap,
            mesh = mesh,
        )
    }

    /**
     * The inverse-designed circle sheet, closed and opened, as bridged solids. Tile sizes vary, so the
     * hinge gap is 0 by construction (a hinge is one shared point of the design).
     */
    fun measureDesign(design: KirigamiInverseDesign.Design, thickness: Double = 0.1, gap: Double = 0.01, bridge: Double = 0.12): Pair<Measured, Measured> {
        fun at(state: DoubleArray, angle: Double): Measured {
            val (tiles, bridges) = designSheet(design, state)
            return measureRegion(bridgedSection(tiles, bridges, gap, bridge), angle, thickness, bridges.size, tiles.size, 0.0)
        }
        return at(design.closed, 0.0) to at(design.opened, Double.NaN)
    }

    private var failures = 0

    private fun check(what: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $what: $detail")
        if (!ok) failures++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("kirigami-solid chain")
        val closed = measure(angle = 0.0)
        for (deg in listOf(0.0, 15.0, 30.0, 45.0)) {
            val m = measure(angle = Math.toRadians(deg))
            check("hinge gap at $deg deg", m.hingeGap < 1e-12, "%.1e".format(m.hingeGap))
            check("genus at $deg deg", m.genus == m.predictedGenus, "Manifold ${m.genus}, hinges - tiles + 1 = ${m.predictedGenus}")
            check("one piece at $deg deg", m.parts == 1, "${m.parts} part(s)")
            check("watertight at $deg deg", m.watertight, "${m.watertight}")
            check(
                "volume = area x thickness at $deg deg", abs(m.volume - m.sectionArea * 0.1) < 1e-9 && abs(m.meshVolume - m.volume) < 1e-6,
                "Manifold %.6f, area x t %.6f, mesh %.6f".format(m.volume, m.sectionArea * 0.1, m.meshVolume),
            )
            val ratio = m.hullArea / closed.hullArea
            val s = cos(m.angle) + sin(m.angle)
            println("    hull area ratio %.4f (lattice (cos+sin)^2 = %.4f)".format(ratio, s * s))
        }
        val design = KirigamiInverseDesign(n = 8).solve()
        check("circle design residual", design.residual < 1e-8, "%.1e after %d iterations".format(design.residual, design.iterations))
        val (shut, open) = measureDesign(design, gap = 0.01)
        for ((label, m) in listOf("closed" to shut, "opened" to open)) {
            check("circle $label genus", m.genus == m.predictedGenus, "Manifold ${m.genus}, hinges - tiles + 1 = ${m.predictedGenus}")
            check("circle $label one piece", m.parts == 1, "${m.parts} part(s)")
            check("circle $label watertight", m.watertight, "${m.watertight}")
            check("circle $label volume = area x thickness", abs(m.volume - m.sectionArea * 0.1) < 1e-9, "%.6f".format(m.volume))
        }
        println("    circle hull area ratio %.4f".format(open.hullArea / shut.hullArea))
        if (args.isNotEmpty()) {
            val out = File(args[0]).apply { mkdirs() }
            File(out, "kirigami_solid.png").writeBytes(measure(angle = Math.toRadians(30.0)).mesh.renderPng(MeshView()))
        }
        check("all checks", failures == 0, "$failures failure(s)")
        if (failures == 0) println("kirigami-solid chain passed") else kotlin.system.exitProcess(1)
    }
}
