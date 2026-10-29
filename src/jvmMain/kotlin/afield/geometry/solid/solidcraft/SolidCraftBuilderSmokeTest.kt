package afield.geometry.solid.solidcraft

import afield.geometry.path.FLine
import afield.geometry.mesh.weld
import afield.geometry.mesh.genus
import afield.geometry.mesh.isWatertight
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Run with ./gradlew smokeSolidCraft: builds one design per construction feature and checks its geometry. */
object SolidCraftBuilderSmokeTest {
    private val report = StringBuilder()

    private fun body(design: SolidCraftDesign, index: Int = 0): JSONObject = design.summary().getJSONArray("bodies").getJSONObject(index)

    private fun case(label: String, builder: SolidCraftBuilder, check: (SolidCraftDesign) -> Unit) {
        try {
            builder.open().use { design ->
                check(design)
                val mesh = design.mesh(0, 0.1)
                check(mesh.indices.isNotEmpty()) { "$label: empty mesh" }
                report.append("  $label: ${mesh.faceIds.size} triangles\n")
            }
        } catch (t: Throwable) {
            throw IllegalStateException("$label failed: ${t.message}\n${builder.json()}", t)
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

        // Sweep along a 3D wire made from a Field FLine: a closed trefoil knot. The profile is drawn
        // on the plane square to the knot's start, so the tube is true: volume = πr² × length.
        val knot = FLine().moveTo(0f, -8f, 0f).also { l ->
            for (i in 1..240) {
                val t = i / 240.0 * 2 * PI
                l.lineTo(8 * (sin(t) + 2 * sin(2 * t)), 8 * (cos(t) - 2 * cos(2 * t)), -8 * sin(3 * t))
            }
        }
        val knotLength = knot.cursor().lengthD().toDouble()
        case("sweep along FLine trefoil", solidCraft {
            val section = sketch(planeSquareTo(knot)) { circle(0.0, 0.0, 2.5) }
            sweep(section, sketch("XY") { wire(knot, spacing = 0.5f) })
        }) { d -> near("trefoil tube volume", body(d).getDouble("volume"), PI * 2.5 * 2.5 * knotLength, 3e-2) }

        // A sweep caps both ends even when its path closes, so a ring is two half sweeps joined:
        // the join removes the touching caps, leaving exactly a torus's area, genus 1. (The same join
        // on the trefoil's freeform halves fails in SolidCraft v0.15.0 after minutes: "no result".)
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

        // A boundary patch bridging two curves (v0.15.0 builds two boundaries; three or four
        // are SolidCraft issue #763), with a crown lifting its middle.
        case("surface boundary", solidCraft {
            val s = sketch("XY") {
                spline(0.0, 0.0, 10.0, -3.0, 20.0, 0.0)
                spline(0.0, 20.0, 10.0, 23.0, 20.0, 20.0)
            }
            surfaceBoundary(s, crown = 0.5)
        }) { d -> check(body(d).getDouble("area") > 400.0) { "boundary patch ${d.summary()}" } }

        // A failing feature reports SolidCraft's reason instead of producing a model.
        val bad = runCatching { solidCraft { extrude(sketch { line(0.0, 0.0, 10.0, 0.0) }, "5") }.open() }.exceptionOrNull()
        check(bad != null) { "an extrude of an open curve unexpectedly evaluated" }

        print("SolidCraft builder smoke passed:\n$report")
    }
}
