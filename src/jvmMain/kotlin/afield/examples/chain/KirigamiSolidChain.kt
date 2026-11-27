package afield.examples.chain

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
timeoutSec: 300
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
     * The fabricable 2D region: tiles shrunk by [gap] on every side, plus one bar per hinge of width
     * [bridge] reaching from one tile's shrunk corner to the other's along their cut edges.
     */
    fun section(n: Int, angle: Double, gap: Double = 0.04, bridge: Double = 0.12): Section {
        val tiles = deploy(n, angle)
        fun centre(k: Int): DoubleArray {
            val c = tiles[k].values
            return doubleArrayOf(c.sumOf { it[0] } / 4, c.sumOf { it[1] } / 4)
        }
        val shrink = 1 - 2 * gap
        fun inset(k: Int, node: Pair<Int, Int>): DoubleArray {
            val c = centre(k)
            val p = tiles[k].getValue(node)
            return doubleArrayOf(c[0] + (p[0] - c[0]) * shrink, c[1] + (p[1] - c[1]) * shrink)
        }
        val parts = mutableListOf<Section>()
        for (k in tiles.indices) {
            val i = k % n
            val j = k / n
            parts += Section.ofDoublePolygon(cornerNodes(i, j).map { inset(k, it).let { p -> p[0] to p[1] } })
        }
        for (h in hinges(n)) {
            val ends = mutableListOf<List<Pair<Double, Double>>>()
            for (k in listOf(h.a, h.b)) {
                val p = inset(k, h.node)
                val q = inset(k, h.along)
                val len = hypot(q[0] - p[0], q[1] - p[1])
                val ux = (q[0] - p[0]) / len
                val uy = (q[1] - p[1]) / len
                // Start a little inside the tile so the bar overlaps it rather than touching an edge.
                val c = centre(k)
                val cl = hypot(c[0] - p[0], c[1] - p[1])
                val ex = (c[0] - p[0]) / cl * gap * 0.5
                val ey = (c[1] - p[1]) / cl * gap * 0.5
                ends += listOf((p[0] + ex) to (p[1] + ey), (p[0] + ex + ux * bridge) to (p[1] + ey + uy * bridge))
            }
            // Walk A's end out and B's end back, so the quad is simple; NON_ZERO fills it either way round.
            val quad = listOf(ends[0][0], ends[0][1], ends[1][1], ends[1][0])
            parts += Section.ofDoublePolygon(quad, FillRule.NON_ZERO).hull()
        }
        return Section.union(parts)
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

    /** Builds the solid at [angle] (radians), [thickness] thick, and measures it. */
    fun measure(n: Int = 8, angle: Double, thickness: Double = 0.1, gap: Double = 0.04, bridge: Double = 0.12): Measured {
        val region = section(n, angle, gap, bridge)
        val solid: Solid = region.extruded(thickness)
        val mesh = solid.toTriMesh()
        return Measured(
            angle = angle,
            hinges = hinges(n).size,
            tiles = n * n,
            sectionArea = region.area,
            hullArea = region.hull().area,
            volume = solid.volume,
            meshVolume = mesh.volume(),
            genus = solid.genus,
            parts = solid.decompose().size,
            watertight = mesh.topology().isWatertight,
            hingeGap = hingeGap(n, angle),
            mesh = mesh,
        )
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
        if (args.isNotEmpty()) {
            val out = File(args[0]).apply { mkdirs() }
            File(out, "kirigami_solid.png").writeBytes(measure(angle = Math.toRadians(30.0)).mesh.renderPng(MeshView()))
        }
        check("all checks", failures == 0, "$failures failure(s)")
        if (failures == 0) println("kirigami-solid chain passed") else kotlin.system.exitProcess(1)
    }
}
