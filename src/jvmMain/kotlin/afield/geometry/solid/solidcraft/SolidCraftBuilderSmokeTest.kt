package afield.geometry.solid.solidcraft

import afield.geometry.path.FLine
import afield.geometry.mesh.weld
import afield.geometry.mesh.diagnose
import afield.geometry.mesh.genus
import afield.geometry.mesh.isWatertight
import afield.geometry.solid.manifold.Solid
import afield.geometry.solid.manifold.insetFaces
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Run with ./gradlew smokeSolidCraft: builds one design per construction feature and checks its geometry. */
object SolidCraftBuilderSmokeTest {
    private val report = StringBuilder()
    private val failures = mutableListOf<IllegalStateException>()

    private fun body(design: SolidCraftDesign, index: Int = 0): JSONObject = design.summary().getJSONArray("bodies").getJSONObject(index)

    private fun case(label: String, builder: SolidCraftBuilder, check: (SolidCraftDesign) -> Unit) {
        try {
            builder.open().use { design ->
                val details = report.length
                check(design)
                val mesh = design.mesh(0, 0.1)
                check(mesh.indices.isNotEmpty()) { "$label: empty mesh" }
                report.insert(details, "  $label: ${mesh.faceIds.size} triangles\n")
            }
        } catch (t: Throwable) {
            // Every case runs, so one rev shows everything it broke; main fails at the end.
            failures += IllegalStateException("$label failed: ${t.message}\n${builder.json()}", t)
        }
    }

    /** The enclosed volume of the tessellation, independent of SolidCraft's own measure. */
    private fun meshVolume(design: SolidCraftDesign): Double {
        val m = design.mesh(0, 0.01)
        var v = 0.0
        for (t in m.indices.indices step 3) {
            val (a, b, c) = Triple(3 * m.indices[t], 3 * m.indices[t + 1], 3 * m.indices[t + 2])
            val p = m.positions
            v += p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])
        }
        return v / 6.0
    }

    private fun near(label: String, actual: Double, expected: Double, tolerance: Double) =
        check(abs(actual - expected) <= tolerance * abs(expected)) { "$label: $actual, expected $expected" }

    /** A 20 × 10 × 10 block from the origin. */
    private fun SolidCraftBuilder.block() = extrude(sketch { rectangle(0.0, 0.0, 20.0, 10.0) }, "10")

    /** A square pyramid: base ±5 on z = 0, apex (0, 0, 10). */
    private fun SolidCraftBuilder.pyramid() =
        loft(listOf(sketch("XY") { rectangle(-5.0, -5.0, 5.0, 5.0) }), apex = sketch(offsetPlane("XY", "10")) { point(0.0, 0.0) })

    /** The pyramid's side faces by name: outward unit normal, and a point on the face. */
    private val sides = run {
        val k = 1.0 / kotlin.math.sqrt(5.0)
        mapOf(
            "+x" to (doubleArrayOf(2 * k, 0.0, k) to doubleArrayOf(10.0 / 3, 0.0, 10.0 / 3)),
            "-x" to (doubleArrayOf(-2 * k, 0.0, k) to doubleArrayOf(-10.0 / 3, 0.0, 10.0 / 3)),
            "+y" to (doubleArrayOf(0.0, 2 * k, k) to doubleArrayOf(0.0, 10.0 / 3, 10.0 / 3)),
            "-y" to (doubleArrayOf(0.0, -2 * k, k) to doubleArrayOf(0.0, -10.0 / 3, 10.0 / 3)),
        )
    }

    /**
     * The volume of the solid where n · x <= c for every (n, c), from Manifold: the exact answer
     * for moving faces of a convex part, whatever happens to its vertices.
     */
    private fun halfSpaces(planes: List<Pair<DoubleArray, Double>>): Double {
        var solid = Solid.cube(200.0, center = true)
        for ((n, c) in planes) solid = solid.trimByPlane(-n[0], -n[1], -n[2], -c).also { solid.close() }
        return solid.volume.also { solid.close() }
    }

    /** The pyramid's planes, the `moved` sides pushed out by `distance` (negative: in). */
    private fun pyramidPlanes(moved: Set<String>, distance: Double): List<Pair<DoubleArray, Double>> =
        sides.map { (name, s) -> s.first to 10.0 / kotlin.math.sqrt(5.0) + if (name in moved) distance else 0.0 } + (doubleArrayOf(0.0, 0.0, -1.0) to 0.0)

    /** The same pyramid in Manifold, its `moved` sides inset by `distance` with [insetFaces]. */
    private fun manifoldInset(moved: Set<String>, distance: Double): Double {
        val normals = moved.map { sides.getValue(it).first }
        val apexAndBase = doubleArrayOf(-5.0, -5.0, 0.0, 5.0, -5.0, 0.0, 5.0, 5.0, 0.0, -5.0, 5.0, 0.0, 0.0, 0.0, 10.0)
        Solid.hullOfPointsD(apexAndBase).use { pyramid ->
            pyramid.insetFaces(distance) { plane -> normals.any { n -> n[0] * plane[0] + n[1] * plane[1] + n[2] * plane[2] > 1 - 1e-9 } }
                .use { return it.volume }
        }
    }

    private fun chamfersAndOffsets() {
        // Chamfers on the block: each closed form is the volume cut off.
        val front = doubleArrayOf(10.0, 0.0, 10.0)
        case("chamfer one edge", solidCraft { block(); chamfer("1", front) }) { d -> near("chamfer", body(d).getDouble("volume"), 1990.0, 1e-9) }
        case("chamfer two distances", solidCraft { block(); chamfer("1", front, distance2 = "2") }) { d -> near("chamfer 1 × 2", body(d).getDouble("volume"), 1980.0, 1e-9) }
        case("chamfer distance and angle", solidCraft { block(); chamfer("1", front, angle = "30 deg") }) { d ->
            near("chamfer 1 at 30°", body(d).getDouble("volume"), 2000.0 - 10.0 * kotlin.math.tan(PI / 6), 1e-9)
        }
        // Round the top rim the four chamfers mitre at the corners: at depth s below the top the
        // section is the rectangle shrunk by s, so the cut is d²(L + W) - 4d³/3.
        case("chamfer around a face", solidCraft {
            block(); chamfer("1", front, doubleArrayOf(20.0, 5.0, 10.0), doubleArrayOf(10.0, 10.0, 10.0), doubleArrayOf(0.0, 5.0, 10.0))
        }) { d -> near("rim chamfer", body(d).getDouble("volume"), 2000.0 - (30.0 - 4.0 / 3), 1e-9) }
        // A base edge of the pyramid meets its side at atan(2), not 90°: each leg is 1 along its
        // own face, and the cut is the plane through the two legs' ends.
        case("chamfer a sloped edge", solidCraft { pyramid(); chamfer("1", doubleArrayOf(5.0, 0.0, 0.0)) }) { d ->
            // In x, z: the leg ends are (4, 0) on the base and (5, 0) + (-5, 10)/√125 on the side;
            // the cut is the plane through both, its normal pointing at the corner it removes.
            val (dx, dz) = (1.0 - 5.0 / kotlin.math.sqrt(125.0)) to 10.0 / kotlin.math.sqrt(125.0)
            val l = kotlin.math.hypot(dx, dz)
            val n = doubleArrayOf(dz / l, 0.0, -dx / l)
            val cut = n[0] * 4.0
            near("sloped chamfer", body(d).getDouble("volume"), halfSpaces(pyramidPlanes(emptySet(), 0.0) + (n to cut)), 1e-6)
        }

        // Every edge, from the mesh: a block's twelve, all outside corners, 4 × (20 + 10 + 10) long.
        case("edges of a block", solidCraft { block() }) { d ->
            val edges = d.mesh().edges()
            check(edges.size == 12 && edges.all { it.convex }) { "block edges: ${edges.size}, convex ${edges.count { it.convex }}" }
            near("edge length", edges.sumOf { it.length }, 160.0, 1e-9)
        }
        // A pocket adds eight inside edges at its floor and walls. Chamfering all 24 at once fails
        // where outside and inside edges meet (SMLib "Vertex Computation Failure" at v0.44.1);
        // outside then inside, as two chamfers, builds. The one-chamfer outcome is reported, not
        // required, so a rev that fixes it shows up here.
        val pocketed: SolidCraftBuilder.() -> Unit = {
            block()
            extrude(sketch(offsetPlane("XY", "6")) { rectangle(5.0, 3.0, 15.0, 7.0) }, "10", SolidCraftOperation.CUT)
        }
        val pocketEdges = solidCraft { pocketed() }.open().use { it.mesh().edges() }
        case("chamfer every edge, outside then inside", solidCraft {
            pocketed()
            chamfer("0.5", *pocketEdges.filter { it.convex }.map { it.pick }.toTypedArray())
            chamfer("0.5", *pocketEdges.filter { !it.convex }.map { it.pick }.toTypedArray())
        }) { d ->
            check(pocketEdges.count { it.convex } == 16 && pocketEdges.count { !it.convex } == 8) { "pocket edges ${pocketEdges.size}" }
            val v = body(d).getDouble("volume")
            check(v < 2000.0 - 160.0 && v > 1800.0) { "chamfered pocket volume $v" }
            val once = runCatching { solidCraft { pocketed(); chamfer("0.5", *pocketEdges.map { it.pick }.toTypedArray()) }.open().close() }
            report.append("    one chamfer of all ${pocketEdges.size}: ${once.exceptionOrNull()?.message?.substringBefore("\n")?.takeLast(60) ?: "builds"}\n")
        }

        // Offsets on the block: out on the top, in on a side.
        case("offset a face out", solidCraft { block(); offsetFaces("2", doubleArrayOf(10.0, 5.0, 10.0)) }) { d -> near("top out 2", body(d).getDouble("volume"), 2400.0, 1e-9) }
        case("offset a face in", solidCraft { block(); offsetFaces("-2", doubleArrayOf(20.0, 5.0, 5.0)) }) { d -> near("side in 2", body(d).getDouble("volume"), 1800.0, 1e-9) }

        // The pyramid's apex is four planes at one point. Move one side, or two opposite ones, and
        // they no longer meet at one, so the apex opens into a ridge: six vertices, not five. Two
        // adjacent sides moved alike still meet the other two at one point, on the diagonal x = y,
        // so the apex only slides along it. Manifold's insetFaces, which moves vertices rather
        // than faces, is reported beside each inward case for comparison.
        for (distance in listOf(-1.0, 1.0)) for (moved in listOf(setOf("+x"), setOf("+x", "+y"), setOf("+x", "-x"), sides.keys)) {
            val label = "pyramid ${moved.joinToString("")} by $distance"
            case(label, solidCraft { pyramid(); offsetFaces(distance.toString(), *moved.map { sides.getValue(it).second }.toTypedArray()) }) { d ->
                val b = body(d)
                val exact = halfSpaces(pyramidPlanes(moved, distance))
                near(label, b.getDouble("volume"), exact, 1e-6)
                val vertices = b.getInt("vertices")
                val concurrent = moved.size == 4 || moved == setOf("+x", "+y")
                check(vertices == if (concurrent) 5 else 6) { "$label: $vertices vertices, ${b}" }
                report.append("    volume ${"%.6f".format(b.getDouble("volume"))} (half-spaces ${"%.6f".format(exact)}), $vertices vertices")
                if (distance < 0) report.append(", insetFaces ${"%.6f".format(manifoldInset(moved, -distance))}")
                report.append("\n")
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        check(SolidCraftLibrary.isAvailable) { SolidCraftLibrary.describe() }

        // Extrude, with a parameter that stays editable after construction.
        case("extrude", solidCraft { param("h", "10 mm"); extrude(sketch { rectangle(0.0, 0.0, 20.0, 10.0) }, "h") }) { d ->
            near("extrude volume", body(d).getDouble("volume"), 2000.0, 1e-6)
            d.setParameter("h", "25 mm")
            near("edited extrude volume", body(d).getDouble("volume"), 5000.0, 1e-6)
            check(String(d.exportStep(), Charsets.UTF_8).contains("ISO-10303-21"))
            SolidCraftDesign.open(d.saveDesign()).use { re -> near("reopened", body(re).getDouble("volume"), 5000.0, 1e-6) }
        }

        // Revolve: a 5 × 10 rectangle at radius 10..15 about the sketch y axis makes a ring.
        case("revolve", solidCraft { revolve(sketch("XY") { rectangle(10.0, 0.0, 15.0, 10.0) }, "y") }) { d ->
            near("ring volume", body(d).getDouble("volume"), PI * (15.0 * 15.0 - 10.0 * 10.0) * 10.0, 1e-3)
        }

        // Sweep along a straight path, plain and twisted: twist keeps the area, so the volume.
        for (twist in listOf(null, "90 deg")) {
            case("sweep twist=$twist", solidCraft {
                val section = sketch("XY") { rectangle(-4.0, -2.0, 4.0, 2.0) }
                val path = sketch("XZ") { line(0.0, 0.0, 0.0, 60.0) }
                sweep(section, path, twist = twist)
            }) { d -> near("swept volume", body(d).getDouble("volume"), 8.0 * 4.0 * 60.0, 2e-2) }
        }

        // Sweep along a curved spline path. The path leaves the profile about 27° off square, and
        // the sweep keeps the profile at that angle to the path, so the section square to the path
        // is the disc's area times cos(27°): the volume is that times the length, which the side
        // area gives (side = 2πr·length; the two caps are the drawn discs).
        case("sweep spline path", solidCraft {
            val section = sketch("XY") { circle(0.0, 0.0, 2.0) }
            val path = sketch("XZ") { spline(0.0, 0.0, 10.0, 20.0, 0.0, 40.0, -10.0, 60.0) }
            sweep(section, path)
        }) { d ->
            val b = body(d)
            val length = (b.getDouble("area") - 2 * PI * 4.0) / (2 * PI * 2.0)
            check(length > 60.0) { "spline path length $length" }
            val squareSection = b.getDouble("volume") / length / (PI * 4.0)
            check(squareSection in 0.8..0.95) { "section/disc $squareSection, mesh volume ${meshVolume(d)}" }
        }

        // Sweep with a guide rail: the rail widens from 4 to 12 away from the path, so the
        // section's far side follows it and the body is bigger than the plain sweep.
        case("sweep with rail", solidCraft {
            val section = sketch("XY") { rectangle(-4.0, -4.0, 4.0, 4.0) }
            val path = sketch("XZ") { line(0.0, 0.0, 0.0, 60.0) }
            val rail = sketch("XZ") { line(4.0, 0.0, 12.0, 60.0) }
            sweep(section, path, rails = listOf(rail))
        }) { d ->
            val v = body(d).getDouble("volume")
            check(v > 8.0 * 8.0 * 60.0 * 1.2) { "rail did not widen the sweep: $v" }
        }

        // Sweep along a 3D wire made from a Field FLine: a trefoil knot stopped one step short of its
        // start. Since v0.30 a closed path is sewn shut, which needs the profile to come back unturned,
        // and a knot turns it (about 127°); the open knot is a capped tube. The profile is drawn on the
        // plane square to the knot's start, so the tube is true: volume = πr² × length. The wire is
        // sampled at its default 1 mm: since v0.41 sweeps go through SMLib, which refuses this tube
        // (status 1001) at 0.5 mm and builds it at 1, 1.5 and 2 mm, within 0.1% of the closed form.
        val knot = FLine().moveTo(0f, -8f, 0f).also { l ->
            for (i in 1..239) {
                val t = i / 240.0 * 2 * PI
                l.lineTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t))
            }
        }
        val knotLength = knot.cursor().lengthD().toDouble()
        case("sweep along FLine trefoil", solidCraft {
            val section = sketch(planeSquareTo(knot)) { circle(0.0, 0.0, 2.5) }
            sweep(section, sketch("XY") { wire(knot) })
        }) { d -> near("trefoil tube volume", body(d).getDouble("volume"), PI * 2.5 * 2.5 * knotLength, 1e-3) }

        // A sweep round a closed path joins its end to its start with no caps (since v0.41): a circle
        // path gives exactly a torus's area, genus 1.
        case("ring from one sweep round a circle", solidCraft {
            sweep(sketch("XZ") { circle(20.0, 0.0, 2.5) }, sketch("XY") { circle(0.0, 0.0, 20.0) })
        }) { d ->
            near("torus area", body(d).getDouble("area"), 4 * PI * PI * 20.0 * 2.5, 1e-3)
            val m = d.mesh(0, 0.05).toTriMesh().weld()
            check(m.isWatertight() && m.genus() == 1) { "ring is not a closed genus-1 solid: genus ${m.genus()}" }
        }

        // Two half sweeps joined make the same ring: the join removes the touching caps. (Refused on the
        // trefoil's freeform halves at v0.41 and v0.44; built since v0.46, checked below.)
        case("ring from two joined half sweeps", solidCraft {
            sweep(sketch("XZ") { circle(20.0, 0.0, 2.5) }, sketch("XY") { arc(0.0, 0.0, 20.0, 0.0, -20.0, 0.0) })
            sweep(sketch("XZ") { circle(-20.0, 0.0, 2.5) }, sketch("XY") { arc(0.0, 0.0, -20.0, 0.0, 20.0, 0.0) }, operation = SolidCraftOperation.JOIN)
        }) { d ->
            near("torus area", body(d).getDouble("area"), 4 * PI * PI * 20.0 * 2.5, 1e-3)
            val m = d.mesh(0, 0.05).toTriMesh().weld()
            check(m.isWatertight() && m.genus() == 1) { "ring is not a closed genus-1 solid: genus ${m.genus()}" }
        }

        case("loft", solidCraft {
            val a = sketch("XY") { rectangle(-10.0, -10.0, 10.0, 10.0) }
            val b = sketch(offsetPlane("XY", "30")) { circle(0.0, 0.0, 6.0) }
            loft(listOf(a, b))
        }) { d -> val v = body(d).getDouble("volume"); check(v > PI * 36.0 * 30.0 && v < 400.0 * 30.0) { "loft volume $v" } }

        case("pipe", solidCraft { pipe(sketch("XY") { line(0.0, 0.0, 50.0, 0.0) }, diameter = "6", wall = "1") }) { d ->
            near("pipe volume", body(d).getDouble("volume"), PI * (9.0 - 4.0) * 50.0, 1e-2)
        }

        // Surfaces: open sheet bodies.
        case("surface sweep", solidCraft {
            val profile = sketch("XY") { arc(0.0, 0.0, 10.0, 0.0, -10.0, 0.0) }
            val path = sketch("XZ") { line(0.0, 0.0, 0.0, 40.0) }
            surfaceSweep(profile, path)
        }) { d -> near("half-cylinder area", body(d).getDouble("area"), PI * 10.0 * 40.0, 2e-2) }

        case("surface sweep with rail", solidCraft {
            val profile = sketch("XY") { line(-10.0, 0.0, 10.0, 0.0) }
            val path = sketch("XZ") { line(0.0, 0.0, 0.0, 40.0) }
            val rail = sketch("XZ") { spline(10.0, 0.0, 16.0, 20.0, 10.0, 40.0) }
            surfaceSweep(profile, path, rails = listOf(rail))
        }) { d ->
            val maxX = body(d).getJSONObject("bounds").getJSONArray("max").getDouble(0)
            check(maxX > 14.0) { "surface did not follow the rail out: max x $maxX, ${d.summary()}" }
        }

        case("surface loft G1", solidCraft {
            val a = sketch("XY") { spline(-20.0, 0.0, 0.0, 6.0, 20.0, 0.0) }
            val b = sketch(offsetPlane("XY", "20")) { spline(-20.0, 0.0, 0.0, -6.0, 20.0, 0.0) }
            val c = sketch(offsetPlane("XY", "40")) { spline(-20.0, 0.0, 0.0, 6.0, 20.0, 0.0) }
            surfaceLoft(listOf(a, b, c))
        }) { d -> check(body(d).getDouble("area") > 40.0 * 40.0) }

        case("surface revolve", solidCraft { surfaceRevolve(sketch("XY") { spline(10.0, 0.0, 14.0, 10.0, 8.0, 20.0) }, "y") }) { d ->
            check(body(d).getDouble("area") > 2 * PI * 8.0 * 20.0)
        }

        // A boundary patch bridging two curves, with a crown lifting its middle.
        case("surface boundary", solidCraft {
            val s = sketch("XY") {
                spline(0.0, 0.0, 10.0, -3.0, 20.0, 0.0)
                spline(0.0, 20.0, 10.0, 23.0, 20.0, 20.0)
            }
            surfaceBoundary(s, crown = 0.5)
        }) { d -> check(body(d).getDouble("area") > 400.0) { "boundary patch ${d.summary()}" } }

        // Three curves in loop order (each ends where the next begins) give a flat-sided patch;
        // a crown is refused there.
        case("surface boundary of three curves", solidCraft {
            val s = sketch("XY") {
                spline(0.0, 0.0, 10.0, -3.0, 20.0, 0.0)
                spline(20.0, 0.0, 13.0, 10.0, 10.0, 17.0)
                spline(10.0, 17.0, 3.0, 10.0, 0.0, 0.0)
            }
            surfaceBoundary(s)
        }) { d -> check(body(d).getDouble("area") > 150.0) { "three-curve patch ${d.summary()}" } }

        // The same patch thickened by 2 is a closed solid, body 1 (body 0 is the sheet). Before v0.46
        // its welded mesh had two open boundary loops.
        fun patch(thickness: String? = null, sheet: String = "") = solidCraft("Sheet") {
            val s = sketch("XY") {
                spline(0.0, 0.0, 10.0, -3.0, 20.0, 0.0)
                spline(0.0, 20.0, 10.0, 23.0, 20.0, 20.0)
            }
            surfaceBoundary(s, crown = 0.5)
            if (thickness != null) thicken(listOf(sheet), thickness)
        }
        val sheetName = patch().open().use { body(it).getString("name") }
        case("thickened boundary patch", patch("2", sheetName)) { d ->
            val m = d.mesh(1, 0.05).toTriMesh().weld()
            check(m.isWatertight()) { "thickened sheet is open: ${m.diagnose().report()}" }
        }

        // Two freeform halves of the trefoil, the second joined: one closed genus-1 tube.
        val halfA = FLine().also { l -> for (i in 0..120) { val t = i / 240.0 * 2 * PI
            if (i == 0) l.moveTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t))
            else l.lineTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t)) } }
        val halfB = FLine().also { l -> for (i in 120..240) { val t = i / 240.0 * 2 * PI
            if (i == 120) l.moveTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t))
            else l.lineTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t)) } }
        case("trefoil from two joined freeform half sweeps", solidCraft {
            sweep(sketch(planeSquareTo(halfA)) { circle(0.0, 0.0, 2.5) }, sketch("XY") { wire(halfA) })
            sweep(sketch(planeSquareTo(halfB)) { circle(0.0, 0.0, 2.5) }, sketch("XY") { wire(halfB) }, operation = SolidCraftOperation.JOIN)
        }) { d ->
            val m = d.mesh(0, 0.05).toTriMesh().weld()
            check(m.isWatertight() && m.genus() == 1) { "joined trefoil is not a closed genus-1 tube: ${m.diagnose().report()}" }
        }

        chamfersAndOffsets()

        // A failing feature reports SolidCraft's reason instead of producing a model.
        val bad = runCatching { solidCraft { extrude(sketch { line(0.0, 0.0, 10.0, 0.0) }, "5") }.open() }.exceptionOrNull()
        check(bad != null) { "an extrude of an open curve unexpectedly evaluated" }

        if (failures.isNotEmpty()) {
            failures.forEach { System.err.println(it.message) }
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw IllegalStateException("${failures.size} SolidCraft builder case(s) failed; passed:\n$report", first)
        }
        print("SolidCraft builder smoke passed:\n$report")
    }
}
