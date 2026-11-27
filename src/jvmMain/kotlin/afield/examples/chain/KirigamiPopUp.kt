package afield.examples.chain

import afield.geometry.mesh.TriMesh
import afield.simulation.physics.HingedPlates
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/*
FIELD_TEST
id: chain.kirigami-popup
runner: jvm-main
entrypoint: afield.examples.chain.KirigamiPopUp
tags: [smoke, chain, kirigami, physics, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "kirigami-popup chain passed"
*/
/**
 * The cut sheet of [KirigamiSolidChain], stretched until it buckles out of its plane: buckling-induced
 * kirigami, after Rafsanjani & Bertoldi, "Buckling-Induced Kirigami", Phys. Rev. Lett. 118, 084301
 * (2017).
 *
 * ```kotlin
 * val sheet = KirigamiPopUp.sheet(thickness = 0.06)
 * val states = sheet.sweep((0..24).map { it * 0.0025 })
 * println(KirigamiPopUp.threshold(states))   // the strain at which the tiles start to tilt
 * ```
 *
 * Every tile of the rotating-squares sheet is a rigid plate; every hinge is the bridge of
 * [KirigamiSolidChain.bridgedSection], a ligament `2 * gap` long from tile to tile, `bridge` wide
 * along the cut and `thickness` thick, given its beam stiffnesses by
 * [HingedPlates.Stiffness.ligament]. The left column of tiles is clamped and the right column is
 * pulled along `x`. A thin ligament folds and twists far more easily than it bends in the plane
 * (the ratio is `(thickness / bridge)²`), so past a critical strain the sheet stops opening in its
 * plane and the tiles tilt out of it instead. Each hinge is cut with a random kink of
 * [Sheet.imperfection] radians, the small out-of-plane imperfection that picks which way each tile
 * tilts.
 *
 * The `kirigami_popup` transcript plots the sweep, path-traces the popped sheet and has a stretch
 * slider; the FIELD_TEST `chain.kirigami-popup` checks the threshold and its scaling headless.
 */
object KirigamiPopUp {

    /**
     * A sheet ready to stretch.
     *
     * @param n tiles per side.
     * @param gap half a slit's width; each ligament is `2 * gap` long.
     * @param bridge how far each ligament runs along its cut: its width.
     * @param thickness the sheet's thickness.
     * @param imperfection the standard deviation of each hinge's rest kink, radians.
     * @param plates the plates and hinges, in tile order `i + n * j`, sheet in the `xy` plane.
     */
    class Sheet(
        val n: Int,
        val gap: Double,
        val bridge: Double,
        val thickness: Double,
        val imperfection: Double,
        val plates: HingedPlates,
    ) {
        /** Rest x of every plate, for prescribing the pulled column. */
        private val restX = DoubleArray(plates.count) { plates.position[3 * it] }

        /** The applied strain: the pulled column's displacement over the clamps' rest distance `n − 1`. */
        var strain = 0.0
            private set

        /** Pulls the right column to [strain] and relaxes the sheet from wherever it last was. */
        fun stretch(strain: Double): State {
            this.strain = strain
            for (k in 0 until plates.count) if (k % n == n - 1) plates.position[3 * k] = restX[k] + strain * (n - 1)
            val relaxed = plates.relax()
            return measure(relaxed)
        }

        /** Stretches through each of [strains] in turn, each starting from the last. */
        fun sweep(strains: List<Double>): List<State> = strains.map { stretch(it) }

        private fun measure(relaxed: HingedPlates.Relaxed): State {
            val free = (0 until plates.count).filter { !plates.fixed[it] }
            val tilt = free.map { Math.toDegrees(acos(plates.normal(it)[2].coerceIn(-1.0, 1.0))) }
            val turn = free.map { k -> Math.toDegrees(abs(atan2(plates.rotation[9 * k + 3], plates.rotation[9 * k]))) }
            val g = plates.gradient()
            val force = (0 until plates.count).filter { it % n == n - 1 }.sumOf { g[6 * it] }
            return State(
                strain = strain,
                meanTilt = tilt.average(),
                maxTilt = tilt.max(),
                meanTurn = turn.average(),
                stress = force / (n * thickness),
                energy = relaxed.energy,
                iterations = relaxed.iterations,
                gradient = relaxed.gradient,
                lift = free.maxOf { abs(plates.position[3 * it + 2]) },
            )
        }

        /** The sheet's current pose as a closed mesh, `y` up, centred on the origin, with each triangle's [Part]. */
        fun mesh(): PoseMesh = poseMesh(this)
    }

    /**
     * One relaxed state of a stretched sheet.
     *
     * @param strain the applied strain.
     * @param meanTilt the mean angle, degrees, between a free tile's normal and the sheet's.
     * @param maxTilt the largest such angle.
     * @param meanTurn the mean in-plane turn of a free tile, degrees: the rotating-squares opening.
     * @param stress the clamp's reaction over the sheet's cross-section `n * thickness`, in units of Young's modulus.
     * @param energy the elastic energy stored in the hinges.
     * @param iterations Levenberg–Marquardt steps taken to relax.
     * @param gradient the largest gradient left on a free plate.
     * @param lift the largest out-of-plane displacement of a tile centre.
     */
    data class State(
        val strain: Double,
        val meanTilt: Double,
        val maxTilt: Double,
        val meanTurn: Double,
        val stress: Double,
        val energy: Double,
        val iterations: Int,
        val gradient: Double,
        val lift: Double,
    )

    /**
     * Builds an `n × n` sheet with the hinge layout of [KirigamiSolidChain.hinges], closed and flat.
     * [seed] draws the rest kinks.
     */
    fun sheet(n: Int = 8, thickness: Double = 0.06, gap: Double = 0.04, bridge: Double = 0.12, imperfection: Double = 1e-3, seed: Long = 1): Sheet {
        val plates = HingedPlates((0 until n * n).map { doubleArrayOf(it % n + 0.5, it / n + 0.5, 0.0) })
        val stiffness = HingedPlates.Stiffness.ligament(width = bridge, length = 2 * gap, thickness = thickness)
        val random = Random(seed)
        for (h in KirigamiSolidChain.hinges(n)) {
            val at = doubleArrayOf(h.node.first.toDouble(), h.node.second.toDouble(), 0.0)
            val along = doubleArrayOf((h.along.first - h.node.first).toDouble(), (h.along.second - h.node.second).toDouble(), 0.0)
            val kink = doubleArrayOf(random.nextGaussian() * imperfection, random.nextGaussian() * imperfection, 0.0)
            plates.hinge(h.a, h.b, at, along, stiffness, kink = kink)
        }
        for (k in 0 until n * n) if (k % n == 0 || k % n == n - 1) plates.fixed[k] = true
        return Sheet(n, gap, bridge, thickness, imperfection, plates)
    }

    /**
     * The first strain at which the mean tilt passes [degrees], interpolated between the two states
     * either side; `NaN` if it never does.
     */
    fun threshold(states: List<State>, degrees: Double = 0.5): Double {
        for (i in 1 until states.size) {
            val (a, b) = states[i - 1] to states[i]
            if (a.meanTilt < degrees && b.meanTilt >= degrees) {
                return a.strain + (degrees - a.meanTilt) / (b.meanTilt - a.meanTilt) * (b.strain - a.strain)
            }
        }
        return Double.NaN
    }

    /** What a triangle of a [PoseMesh] belongs to. */
    enum class Part { EVEN_TILE, ODD_TILE, LIGAMENT }

    /**
     * A posed sheet as triangles.
     *
     * @param mesh the triangles, `y` up, without normals (flat facets).
     * @param parts each triangle's [Part].
     */
    class PoseMesh(val mesh: TriMesh, val parts: List<Part>)

    private fun poseMesh(sheet: Sheet): PoseMesh {
        val n = sheet.n
        val plates = sheet.plates
        val t = sheet.thickness
        val closed = KirigamiSolidChain.deploy(n, 0.0).mapIndexed { k, corners -> KirigamiSolidChain.cornerNodes(k % n, k / n).map { corners.getValue(it) } }
        val insets = closed.map { KirigamiSolidChain.inset(it, sheet.gap) }
        val cx = (0 until plates.count).sumOf { plates.position[3 * it] } / plates.count
        val cy = (0 until plates.count).sumOf { plates.position[3 * it + 1] } / plates.count
        val positions = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val parts = ArrayList<Part>()
        fun vertex(plate: Int, x: Double, y: Double, z: Double): Int {
            val w = plates.world(plate, doubleArrayOf(x - (plate % n + 0.5), y - (plate / n + 0.5), z))
            // Sheet xy with z out of plane, turned so that z is up: (x, y, z) -> (x, z, -y).
            positions += (w[0] - cx).toFloat()
            positions += w[2].toFloat()
            positions += (-(w[1] - cy)).toFloat()
            return positions.size / 3 - 1
        }
        // A prism over a polygon whose corners belong to the given plates, counter-clockwise in the sheet.
        fun prism(corners: List<Pair<Int, DoubleArray>>, part: Part) {
            val area = corners.indices.sumOf { i ->
                val p = corners[i].second
                val q = corners[(i + 1) % corners.size].second
                p[0] * q[1] - q[0] * p[1]
            }
            val ring = if (area >= 0) corners else corners.reversed()
            val top = ring.map { (plate, p) -> vertex(plate, p[0], p[1], t / 2) }
            val bottom = ring.map { (plate, p) -> vertex(plate, p[0], p[1], -t / 2) }
            fun tri(a: Int, b: Int, c: Int) {
                indices += a
                indices += b
                indices += c
                parts += part
            }
            for (i in 1 until ring.size - 1) {
                tri(top[0], top[i], top[i + 1])
                tri(bottom[0], bottom[i + 1], bottom[i])
            }
            for (i in ring.indices) {
                val j = (i + 1) % ring.size
                tri(bottom[i], bottom[j], top[j])
                tri(bottom[i], top[j], top[i])
            }
        }
        for (k in 0 until n * n) prism(insets[k].map { k to it }, if ((k % n + k / n) % 2 == 0) Part.EVEN_TILE else Part.ODD_TILE)
        for (b in KirigamiSolidChain.bridges(n)) {
            val bar = KirigamiSolidChain.bridgeBar(insets, b, sheet.gap, sheet.bridge).map { doubleArrayOf(it.first, it.second) }
            prism(listOf(b.tileA to bar[0], b.tileA to bar[1], b.tileB to bar[2], b.tileB to bar[3]), Part.LIGAMENT)
        }
        return PoseMesh(TriMesh(positions.toFloatArray(), indices.toIntArray()), parts)
    }

    private var failures = 0

    private fun check(what: String, ok: Boolean, detail: String) {
        println("  ${if (ok) "ok  " else "FAIL"} $what: $detail")
        if (!ok) failures++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("kirigami-popup chain")
        val strains = (0..24).map { it * 0.0025 }
        val thicknesses = listOf(0.06, 0.08, 0.12)
        val thresholds = thicknesses.map { t ->
            val states = sheet(thickness = t).sweep(strains)
            val critical = threshold(states)
            val last = states.last()
            println(
                "  t = $t (t/bridge %.2f): threshold strain %.4f, at strain %.3f mean tilt %.2f deg, max %.2f deg, stress %.3e"
                    .format(t / 0.12, critical, last.strain, last.meanTilt, last.maxTilt, last.stress),
            )
            check(
                "t = $t relaxed", states.all { it.gradient < 1e-6 },
                "worst gradient %.1e, at most %d iterations a step".format(states.maxOf { it.gradient }, states.maxOf { it.iterations }),
            )
            check("t = $t buckles", critical.isFinite() && last.meanTilt > 5.0, "threshold %.4f, final mean tilt %.2f".format(critical, last.meanTilt))
            val flat = states.filter { it.strain < 0.5 * critical }
            check("t = $t flat below threshold", flat.isNotEmpty() && flat.all { it.meanTilt < 0.2 }, "largest tilt there %.3f deg".format(flat.maxOfOrNull { it.meanTilt } ?: Double.NaN))
            critical
        }
        // In-plane opening costs inPlane * strain², tilting costs fold * strain: the crossing goes as (t / bridge)².
        val scaled = thresholds.zip(thicknesses).map { (c, t) -> c / ((t / 0.12) * (t / 0.12)) }
        println("  threshold / (t/bridge)^2: ${scaled.joinToString { "%.4f".format(it) }}")
        check("threshold scales as (t/bridge)^2", scaled.max() / scaled.min() < 1.35, "spread %.2f".format(scaled.max() / scaled.min()))
        val popped = sheet(thickness = 0.06).also { it.sweep(strains) }.mesh()
        check("mesh", popped.mesh.triangleCount == popped.parts.size && popped.mesh.triangleCount > 0, "${popped.mesh.triangleCount} triangles")
        check("all checks", failures == 0, "$failures failure(s)")
        if (failures == 0) println("kirigami-popup chain passed") else kotlin.system.exitProcess(1)
    }
}
