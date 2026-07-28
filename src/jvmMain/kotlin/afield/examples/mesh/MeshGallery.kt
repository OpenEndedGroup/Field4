package afield.examples.mesh

import afield.stdlib.Img
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.bounds
import afield.stdlib.bvh
import afield.stdlib.boundsAround
import afield.stdlib.boundsOf
import afield.stdlib.boxMesh
import afield.stdlib.capsuleMesh
import afield.stdlib.coloredByScalar
import afield.stdlib.coneMesh
import afield.stdlib.convexHullMesh
import afield.stdlib.cylinderMesh
import afield.stdlib.decimate
import afield.stdlib.deviationFrom
import afield.stdlib.dodecahedronMesh
import afield.stdlib.extrudeMesh
import afield.stdlib.flatShaded
import afield.stdlib.gaussianCurvature
import afield.stdlib.heightfieldMesh
import afield.stdlib.icosahedronMesh
import afield.stdlib.icosphereMesh
import afield.stdlib.laplacianSmooth
import afield.stdlib.loopSubdivide
import afield.stdlib.marchingCubes
import afield.stdlib.meanCurvature
import afield.stdlib.objToTriMesh
import afield.stdlib.octahedronMesh
import afield.stdlib.renderPixels
import afield.stdlib.revolveMesh
import afield.stdlib.sdfBox
import afield.stdlib.sdfCylinder
import afield.stdlib.sdfGyroid
import afield.stdlib.sdfMetaballs
import afield.stdlib.sdfSmoothUnion
import afield.stdlib.sdfSphere
import afield.stdlib.sdfSubtract
import afield.stdlib.sdfTorus
import afield.stdlib.shadedNormals
import afield.stdlib.shell
import afield.stdlib.signedVolume
import afield.stdlib.stlToTriMesh
import afield.stdlib.summary
import afield.stdlib.taubinSmooth
import afield.stdlib.tetrahedronMesh
import afield.stdlib.toHeightfieldMesh
import afield.stdlib.toObj
import afield.stdlib.toStlBinary
import afield.stdlib.toTriMesh
import afield.stdlib.topology
import afield.stdlib.torusMesh
import afield.stdlib.tubeMesh
import afield.stdlib.twisted
import afield.stdlib.uvSphereMesh
import afield.stdlib.voronoi3d
import afield.stdlib.weld
import afield.stdlib.withVertexNormals
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess

/*
FIELD_TEST
id: mesh.gallery
runner: jvm-main
entrypoint: afield.examples.mesh.MeshGallery
tags: [smoke, examples, mesh, geometry, capture, cpu]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "mesh gallery rendered"
*/

/**
 * Headless **capture gallery** for the `afield.stdlib` triangle-mesh family — the honest visual proof
 * that the geometry comes out right, in the pattern
 * [afield.examples.imaging.ImagingGallery] and [afield.examples.VisionGeometryPreviewGallery]
 * established.
 *
 * Rendering path: real [TriMesh] pipelines → [TriMesh.renderPixels] (the CPU rasterizer in
 * `afield.graphics.VisionGeometryPreview`: perspective, z-buffer, perspective-correct attributes,
 * near-plane clip, two-light lambert, depth-tested wireframe) → AWT compositing → `ImageIO` PNG,
 * under `java.awt.headless=true`. No browser, no GPU, no Vulkan, no Field window. Every triangle in
 * every panel is the output of a real stdlib call; AWT only draws the frames, captions and the
 * measurement strips.
 *
 * As a FIELD_TEST it renders to a temp dir and asserts **correctness, not plausibility**: each panel
 * declares invariants (Euler characteristic, watertightness, volume against the analytic answer,
 * decimation deviation, hull containment) and the run fails if any of them breaks. It also checks the
 * *pixels* — every rendered cell must have a plausible painted fraction and a real spread of colour,
 * because a mesh can be geometrically perfect and still render black. It writes NO committed assets.
 *
 * Pass an output dir as `args[0]` (or set `MESH_GALLERY_DIR`) to regenerate the committed docs
 * captures under `dev_docs/assets/mesh_gallery/`.
 *
 * Adding a panel: append one [Panel] to [PANELS]. Everything else — layout, captions, the pixel
 * guards, the PNG write — is shared.
 */
object MeshGallery {

    // Dark-mode palette shared with the other docs figures.
    private val BG = Color(20, 22, 28)
    private val PANEL_BG = Color(16, 19, 24)
    private val FRAME = Color(58, 63, 75)
    private val TEXT = Color(206, 210, 220)
    private val DIM = Color(138, 144, 160)
    private val ACCENT = Color(96, 202, 228)

    private const val CELL_W = 340
    private const val CELL_H = 260

    /** One rendered cell: a mesh, how to look at it, and the caption printed under it. */
    class Cell(
        val caption: String,
        val mesh: TriMesh,
        val view: MeshView = MeshView(width = CELL_W, height = CELL_H),
        /** Extra facts printed on a second caption line; usually the mesh's own measurements. */
        val facts: String? = null,
        /**
         * Draw *this* image instead of rasterising [mesh] with [view].
         *
         * Only the inline-visualizer panel uses it, and for a reason that matters: that panel shows the
         * PNG the value visualizer's own card embeds, decoded straight back out of its HTML, rather than
         * a lookalike rendered beside it.
         */
        val image: BufferedImage? = null,
    )

    /** A built figure: the cells to lay out, plus the one-line factual summary printed to stdout. */
    class Figure(
        val cells: List<Cell>,
        val summary: String,
        val columns: Int = 4,
        /**
         * How many lines of [Cell.facts] to print under each cell. One is enough for a measurement or
         * two; the inline-visualizer panel needs several, because it prints a whole card's fact table.
         */
        val factLines: Int = 1,
    )

    /** One gallery figure: a [name] (its PNG filename), a [title], a builder, and its invariants. */
    class Panel(val name: String, val title: String, val subtitle: String, val build: () -> Figure)

    // ==========================================================================================
    // Shared inputs
    // ==========================================================================================

    private fun helix(turns: Double = 2.0, samples: Int = 140, radius: Double = 0.55, rise: Double = 1.4): List<Vec3> =
        (0 until samples).map { i ->
            val t = i.toDouble() / (samples - 1)
            val a = 2.0 * PI * turns * t
            Vec3((cos(a) * radius).toFloat(), ((t - 0.5) * rise).toFloat(), (sin(a) * radius).toFloat())
        }

    /** An L-shaped polygon: concave, so it exercises the ear-clipping triangulation. */
    private val lShape = listOf(
        Vec2(0f, 0f), Vec2(1.2f, 0f), Vec2(1.2f, 0.5f), Vec2(0.5f, 0.5f), Vec2(0.5f, 1.2f), Vec2(0f, 1.2f),
    )

    /** A deterministic test image: a disc, a bar and a soft gradient. Heightfield source. */
    private fun testCard(w: Int = 200, h: Int = 200): Img = Img.generate(w, h) { x, y ->
        var v = 0.15f + 0.25f * (y / h.toFloat())
        if (kotlin.math.hypot(x - w * 0.34, y - h * 0.36) <= w * 0.18) v = 0.85f
        if (y in (h * 0.7).toInt()..(h * 0.78).toInt() && x in (w * 0.1).toInt()..(w * 0.9).toInt()) v = 1.0f
        v
    }

    private fun deterministicSites(count: Int, seed: Long): List<Vec3> {
        val random = kotlin.random.Random(seed)
        return List(count) {
            Vec3(random.nextFloat() * 1.6f - 0.8f, random.nextFloat() * 1.6f - 0.8f, random.nextFloat() * 1.6f - 0.8f)
        }
    }

    /** Concatenate the given Voronoi cells, each shrunk toward its own site. */
    private fun interiorMesh(cells: List<afield.stdlib.VoronoiCell3>, shrink: Double): TriMesh {
        val builder = afield.stdlib.TriMeshBuilder()
        for (cell in cells) builder.add(cell.toTriMesh(shrink))
        return builder.build()
    }

    /** A short measurement line for a mesh: counts, chi, genus, volume. */
    private fun facts(mesh: TriMesh): String {
        val topo = mesh.topology()
        return buildString {
            append("${mesh.triangleCount} tris  χ=${topo.eulerCharacteristic}")
            topo.genus?.let { append(" g=$it") }
            if (topo.isWatertight) append("  V=%.4f".format(mesh.signedVolume()))
            else append("  open(${topo.boundaryLoops.size})")
        }
    }

    // ==========================================================================================
    // Panels
    // ==========================================================================================

    private val PANELS: List<Panel> = listOf(

        Panel(
            "01-primitives",
            "Generators — every primitive, welded and watertight",
            "Each of these is the minimal closed mesh for its shape: a cube is 8 vertices, not 24. " +
                "χ = 2 for every genus-0 solid and 0 for the torus, which is what catches a duplicated " +
                "seam or a missing cap.",
        ) {
            val meshes = listOf(
                "boxMesh()" to boxMesh(),
                "uvSphereMesh(24, 12)" to uvSphereMesh(24, 12, 0.6),
                "icosphereMesh(2)" to icosphereMesh(2, 0.6),
                "torusMesh(40, 20)" to torusMesh(40, 20, 0.55, 0.22),
                "cylinderMesh(24)" to cylinderMesh(24, 0.4, 0.9),
                "coneMesh(24)" to coneMesh(24, 0.45, 0.9),
                "capsuleMesh(24, 6)" to capsuleMesh(24, 6, 0.3, 0.5),
                "tetrahedronMesh()" to tetrahedronMesh(0.6),
                "octahedronMesh()" to octahedronMesh(0.6),
                "icosahedronMesh()" to icosahedronMesh(0.6),
                "dodecahedronMesh()" to dodecahedronMesh(0.6),
                "extrudeMesh(L, 0.6)" to extrudeMesh(lShape, 0.6).let { it.translated(-0.6, -0.6, -0.3) },
            )
            for ((label, mesh) in meshes) {
                check(mesh.topology().isWatertight) { "$label should be watertight: ${mesh.summary()}" }
                check(mesh.signedVolume() > 0.0) { "$label should be wound outward: ${mesh.summary()}" }
            }
            check(torusMesh(40, 20).topology().genus == 1) { "the torus should be genus 1" }
            Figure(
                meshes.map { (label, mesh) -> Cell(label, mesh, facts = facts(mesh)) },
                "12 primitives, all watertight, all wound outward; torus genus 1",
            )
        },

        Panel(
            "02-marching-cubes",
            "Marching cubes — a watertight surface from a scalar field",
            "The centrepiece. Shared edge vertices and the asymptotic decider on ambiguous faces, so " +
                "the output is closed (χ = 2) at every resolution and its volume converges on the " +
                "analytic 4/3 π. The same marcher handles a genus-1 torus and a CSG solid.",
        ) {
            val exact = 4.0 / 3.0 * PI
            val cells = mutableListOf<Cell>()
            val errors = mutableListOf<Double>()
            for (resolution in listOf(10, 20, 40)) {
                val mesh = marchingCubes(sdfSphere(0.7), boundsAround(radius = 1.0), resolution)
                val scaled = mesh.signedVolume() / (0.7 * 0.7 * 0.7)
                val error = kotlin.math.abs(scaled - exact) / exact
                errors.add(error)
                check(mesh.topology().isWatertight) { "res $resolution should be watertight: ${mesh.summary()}" }
                check(mesh.topology().eulerCharacteristic == 2) { "res $resolution χ: ${mesh.summary()}" }
                cells.add(
                    Cell(
                        "sdfSphere, resolution $resolution",
                        mesh,
                        facts = "${mesh.triangleCount} tris  χ=2  volume error %.3f%%".format(error * 100),
                    )
                )
            }
            check(errors[0] > errors[1] && errors[1] > errors[2]) {
                "the volume error must fall with resolution, got $errors"
            }
            val torus = marchingCubes(sdfTorus(0.62, 0.22), boundsAround(radius = 0.95), 48)
            check(torus.topology().genus == 1) { "a marched torus should be genus 1: ${torus.summary()}" }
            val drilled = marchingCubes(
                sdfSubtract(sdfSphere(0.72), sdfCylinder(0.22, 3.0)), boundsAround(radius = 0.9), 64,
            )
            check(drilled.topology().isWatertight) { "the drilled sphere should be closed: ${drilled.summary()}" }
            check(drilled.topology().genus == 1) { "drilling through adds a handle: ${drilled.summary()}" }
            cells.add(Cell("sdfTorus, resolution 48", torus, facts = facts(torus)))
            cells.add(
                Cell(
                    "sdfSubtract(sphere, cylinder)", drilled,
                    // Looked at from well above, so the drilled hole is *visible* rather than merely
                    // asserted by the genus in the caption.
                    MeshView(width = CELL_W, height = CELL_H, pitchDegrees = 58.0, distance = 2.4),
                    facts = facts(drilled),
                )
            )
            val metaballs = marchingCubes(
                sdfMetaballs(
                    listOf(Vec3(-0.32f, 0f, 0f), Vec3(0.3f, 0.12f, 0.05f), Vec3(0f, -0.3f, 0.25f)),
                    radius = 0.62, iso = 0.45,
                ),
                boundsAround(radius = 1.0), 64,
            )
            check(metaballs.topology().isWatertight) { "metaballs should close: ${metaballs.summary()}" }
            cells.add(Cell("sdfMetaballs, 3 blobs", metaballs, facts = facts(metaballs)))
            Figure(
                cells,
                "marched sphere volume error %.3f%% / %.3f%% / %.3f%% at resolution 10 / 20 / 40; ".format(
                    errors[0] * 100, errors[1] * 100, errors[2] * 100,
                ) + "torus genus 1, drilled sphere genus 1, all watertight",
                columns = 3,
            )
        },

        Panel(
            "03-sdf-modelling",
            "SDF modelling — shapes as expressions",
            "Primitives and combinators compose into something you can actually model with. The " +
                "smooth union is the operator that makes it feel like clay rather than CSG; the gyroid " +
                "is here because a triply-periodic minimal surface is the hardest thing to get " +
                "topologically right.",
        ) {
            val bar = sdfBox(Vec3(1.3f, 0.24f, 0.24f))
            val ball = sdfSphere(0.55)
            val sharp = marchingCubes(afield.stdlib.sdfUnion(ball, bar), boundsAround(radius = 0.9), 72)
            val smooth = marchingCubes(sdfSmoothUnion(ball, bar, 0.22), boundsAround(radius = 0.9), 72)
            // A shell looked at from outside is indistinguishable from the solid, so cut it in half:
            // the point of the panel is the wall, and the wall is inside.
            val hollow = marchingCubes(
                afield.stdlib.sdfIntersect(
                    sdfSmoothUnion(ball, bar, 0.22).shell(0.06),
                    afield.stdlib.sdfPlane(Vec3(0f, 0f, -1f), 0.0),
                ),
                boundsAround(radius = 0.9), 96,
            )
            val twisted = marchingCubes(
                sdfBox(Vec3(0.9f, 1.3f, 0.3f)).twisted(0.35), boundsAround(radius = 0.95), 88,
            )
            val gyroid = marchingCubes(sdfGyroid(4.0, 0.35), boundsAround(radius = 0.8), 72)
            val gyroidTopo = gyroid.topology()
            check(sharp.topology().isWatertight && smooth.topology().isWatertight) { "unions should close" }
            check(smooth.signedVolume() > sharp.signedVolume()) {
                "the fillet only adds material: ${smooth.signedVolume()} vs ${sharp.signedVolume()}"
            }
            check(hollow.signedVolume() < smooth.signedVolume() * 0.4) {
                "half a shell should enclose far less than the solid: ${hollow.summary()}"
            }
            check(gyroidTopo.eulerCharacteristic < 0) {
                "a gyroid has many handles, χ was ${gyroidTopo.eulerCharacteristic}"
            }
            Figure(
                listOf(
                    Cell("sdfUnion(sphere, box)", sharp, facts = facts(sharp)),
                    Cell("sdfSmoothUnion(…, k = 0.22)", smooth, facts = facts(smooth)),
                    Cell(
                        ".shell(0.06), cut open", hollow,
                        MeshView(width = CELL_W, height = CELL_H, yawDegrees = 200.0, pitchDegrees = 22.0),
                        facts = facts(hollow),
                    ),
                    Cell("sdfBox(…).twisted(0.35)", twisted, facts = facts(twisted)),
                    Cell("sdfGyroid(4.0, 0.35)", gyroid, facts = facts(gyroid)),
                ),
                "smooth union %.4f > sharp union %.4f; half shell %.4f; gyroid χ=%d".format(
                    smooth.signedVolume(), sharp.signedVolume(), hollow.signedVolume(),
                    gyroidTopo.eulerCharacteristic,
                ),
                columns = 3,
            )
        },

        Panel(
            "04-subdivision",
            "Loop subdivision — four triangles for one, and the same topology",
            "Every round quadruples the triangle count and preserves the Euler characteristic " +
                "exactly, so a watertight input stays watertight at the same genus. The volume falls " +
                "monotonically toward the Loop limit surface, in geometrically shrinking steps.",
        ) {
            val cells = mutableListOf<Cell>()
            var mesh = icosahedronMesh(0.7)
            val volumes = mutableListOf(mesh.signedVolume())
            cells.add(Cell("icosahedronMesh() — control mesh", mesh, facts = facts(mesh)))
            for (round in 1..3) {
                mesh = mesh.loopSubdivide(1)
                volumes.add(mesh.signedVolume())
                check(mesh.topology().eulerCharacteristic == 2) { "round $round χ: ${mesh.summary()}" }
                cells.add(Cell("loopSubdivide($round)", mesh, facts = facts(mesh)))
            }
            val deltas = (1 until volumes.size).map { volumes[it] - volumes[it - 1] }
            check(deltas.all { it < 0.0 }) { "the volume must fall monotonically: $volumes" }
            for (i in 1 until deltas.size) {
                check(kotlin.math.abs(deltas[i]) < kotlin.math.abs(deltas[i - 1])) {
                    "the steps must shrink: $deltas"
                }
            }
            // A torus, to show the genus really is preserved.
            val torus = torusMesh(12, 8, 0.55, 0.22)
            val fineTorus = torus.loopSubdivide(2)
            check(fineTorus.topology().genus == 1) { "subdivision must preserve genus: ${fineTorus.summary()}" }
            cells.add(Cell("torusMesh(12, 8)", torus, facts = facts(torus)))
            cells.add(Cell("… .loopSubdivide(2) — still genus 1", fineTorus, facts = facts(fineTorus)))
            Figure(cells, "icosahedron volumes ${volumes.map { "%.4f".format(it) }}; deltas ${deltas.map { "%.4f".format(it) }}", columns = 3)
        },

        Panel(
            "05-smoothing",
            "Smoothing — Laplacian shrinks, Taubin does not",
            "Both remove the same noise. A Laplacian pass is a diffusion, so it deflates the shape; " +
                "Taubin's λ/μ pair alternates a shrink with an inflate and holds the volume. The " +
                "numbers under each cell are the point of the panel.",
        ) {
            val random = kotlin.random.Random(7)
            val clean = icosphereMesh(3, 0.7)
            val noisy = clean.transformed { p ->
                val s = 1f + (random.nextFloat() - 0.5f) * 0.16f
                Vec3(p.x * s, p.y * s, p.z * s)
            }.withVertexNormals()
            val laplacian = noisy.laplacianSmooth(iterations = 30, lambda = 0.6)
            val taubin = noisy.taubinSmooth(iterations = 30, lambda = 0.6, mu = -0.63)
            check(laplacian.signedVolume() < noisy.signedVolume() * 0.9) {
                "30 Laplacian passes should visibly deflate: ${noisy.signedVolume()} -> ${laplacian.signedVolume()}"
            }
            check(kotlin.math.abs(taubin.signedVolume() - noisy.signedVolume()) < noisy.signedVolume() * 0.05) {
                "Taubin should hold the volume: ${noisy.signedVolume()} -> ${taubin.signedVolume()}"
            }
            fun spread(mesh: TriMesh): Double {
                var worst = 0.0
                for (v in 0 until mesh.vertexCount) {
                    worst = kotlin.math.max(worst, kotlin.math.abs(mesh.position(v).length() - 0.7f).toDouble())
                }
                return worst
            }
            check(spread(taubin) < spread(noisy) * 0.6) {
                "Taubin should cut the radial spread: ${spread(noisy)} -> ${spread(taubin)}"
            }
            Figure(
                listOf(
                    Cell("icosphereMesh(3) — clean", clean, facts = "V=%.4f".format(clean.signedVolume())),
                    Cell("+ 8% radial noise", noisy, facts = "V=%.4f  spread %.4f".format(noisy.signedVolume(), spread(noisy))),
                    Cell("laplacianSmooth(30, λ=0.6)", laplacian, facts = "V=%.4f — deflated".format(laplacian.signedVolume())),
                    Cell("taubinSmooth(30, λ=0.6, μ=−0.63)", taubin, facts = "V=%.4f  spread %.4f".format(taubin.signedVolume(), spread(taubin))),
                    // Every other cell is framed by its own bounding sphere, which hides exactly the
                    // thing this panel is about. Putting both results in ONE frame makes the
                    // deflation impossible to miss.
                    Cell(
                        "same frame: laplacian (left) vs taubin (right)",
                        laplacian.translated(-0.8, 0, 0) + taubin.translated(0.8, 0, 0),
                        // Same cell size as everything else — the layout grid is uniform on purpose.
                        MeshView(width = CELL_W, height = CELL_H, distance = 2.5, yawDegrees = 8.0),
                        facts = "the Laplacian ball really is smaller — %.4f against %.4f".format(
                            laplacian.signedVolume(), taubin.signedVolume(),
                        ),
                    ),
                ),
                "noisy V=%.4f -> laplacian %.4f (shrunk), taubin %.4f (held); spread %.4f -> %.4f".format(
                    noisy.signedVolume(), laplacian.signedVolume(), taubin.signedVolume(),
                    spread(noisy), spread(taubin),
                ),
            )
        },

        Panel(
            "06-decimation",
            "QEM decimation — a triangle budget, and the shape survives",
            "Quadric error metric edge collapse with the link condition, a normal-flip veto and a " +
                "shape veto. The deviation figure is the largest distance either mesh's vertices sit " +
                "from the other's surface: 5120 triangles down to 200 costs 3% of the radius.",
        ) {
            val fine = icosphereMesh(4, 0.7)
            val cells = mutableListOf(Cell("icosphereMesh(4) — 5120 tris", fine, facts = facts(fine)))
            val report = mutableListOf<String>()
            for (target in listOf(2000, 800, 200)) {
                val coarse = fine.decimate(target)
                val deviation = kotlin.math.max(fine.deviationFrom(coarse), coarse.deviationFrom(fine))
                check(coarse.triangleCount <= target) { "budget exceeded: ${coarse.triangleCount} > $target" }
                check(coarse.topology().isWatertight) { "decimation must stay closed: ${coarse.summary()}" }
                check(deviation < 0.05) { "target $target deviated $deviation" }
                report.add("$target: dev %.4f".format(deviation))
                cells.add(
                    Cell(
                        "decimate($target)", coarse,
                        facts = "${coarse.triangleCount} tris  χ=2  deviation %.4f".format(deviation),
                    )
                )
            }
            // And the same on a marched CSG solid, where the topology is not trivial.
            val solid = marchingCubes(
                sdfSubtract(sdfSmoothUnion(sdfSphere(0.6), sdfBox(Vec3(1.2f, 0.22f, 0.22f)), 0.2), sdfCylinder(0.18, 3.0)),
                boundsAround(radius = 0.85), 80,
            )
            val slim = solid.decimate(1500)
            check(slim.topology().isWatertight) { "a decimated CSG solid must stay closed: ${slim.summary()}" }
            check(slim.topology().genus == solid.topology().genus) {
                "and keep its genus: ${solid.topology().genus} -> ${slim.topology().genus}"
            }
            cells.add(Cell("marched CSG solid", solid, facts = facts(solid)))
            cells.add(Cell("… .decimate(1500) — genus kept", slim, facts = facts(slim)))
            Figure(cells, "deviation ${report.joinToString(", ")}; CSG genus kept at ${slim.topology().genus}", columns = 3)
        },

        Panel(
            "07-derived-generators",
            "Derived generators — a mesh out of something you already have",
            "Extrusion of a concave polygon (real ear clipping, not a fan), a lathe, a " +
                "rotation-minimising tube along a 3D path, and heightfields from a lambda and from an " +
                "Img — the bridge from the imaging family into the mesh family.",
        ) {
            val prism = extrudeMesh(lShape, 0.7).translated(-0.6, -0.6, -0.35)
            val goblet = revolveMesh(
                listOf(
                    Vec2(0f, -0.7f), Vec2(0.42f, -0.7f), Vec2(0.12f, -0.55f), Vec2(0.1f, 0.1f),
                    Vec2(0.42f, 0.28f), Vec2(0.46f, 0.7f), Vec2(0f, 0.7f),
                ),
                64,
            )
            val coil = tubeMesh(helix(), radius = 0.11, sides = 16, caps = true)
            val dunes = heightfieldMesh(72, 72, 1.6, 1.6) { x, z ->
                (0.22 * sin(x * 4.0) * cos(z * 3.2) + 0.1 * sin((x + z) * 6.5)).toFloat()
            }
            val card = testCard()
            val relief = card.toHeightfieldMesh(scale = 0.45, width = 1.6)
            check(prism.topology().isWatertight) { "the prism should be closed: ${prism.summary()}" }
            // The L is 1.2 x 0.5 plus 0.5 x 0.7 = 0.95 of area, extruded by 0.7.
            check(kotlin.math.abs(prism.signedVolume() - 0.95 * 0.7) < 0.005) {
                "the L has area 0.95, so the prism should be 0.665: ${prism.signedVolume()}"
            }
            check(goblet.topology().isWatertight) { "the lathe should be closed: ${goblet.summary()}" }
            check(coil.topology().isWatertight) { "the capped tube should be closed: ${coil.summary()}" }
            check(coil.isFinite()) { "a helical tube must not produce NaN frames" }
            check(dunes.topology().boundaryLoops.size == 1) { "a heightfield is a disc: ${dunes.summary()}" }
            check(relief.bounds().max.y > 0.4f) { "the relief should reach the bright bar: ${relief.bounds()}" }
            Figure(
                listOf(
                    Cell("extrudeMesh(concave L, 0.7)", prism, facts = facts(prism)),
                    Cell("revolveMesh(profile, 64)", goblet, facts = facts(goblet)),
                    Cell("tubeMesh(helix, sides = 16)", coil, facts = facts(coil)),
                    Cell("heightfieldMesh(72, 72) { … }", dunes, MeshView(width = CELL_W, height = CELL_H, pitchDegrees = 34.0), facts = facts(dunes)),
                    Cell("Img.toHeightfieldMesh()", relief, MeshView(width = CELL_W, height = CELL_H, pitchDegrees = 42.0), facts = facts(relief)),
                ),
                "prism V=%.4f (expected 0.665), lathe V=%.4f, tube V=%.4f, dunes %d tris".format(
                    prism.signedVolume(), goblet.signedVolume(), coil.signedVolume(), dunes.triangleCount,
                ),
                columns = 3,
            )
        },

        Panel(
            "08-voronoi-bridge",
            "3D Voronoi → mesh — the bridge to voronoi.kt",
            "Field already computed a real 3D Voronoi diagram by half-space clipping; it just could " +
                "not turn it into geometry. Now each polygonal cell triangulates into a closed solid, " +
                "and the cell volumes sum to the clip box exactly.",
        ) {
            // voronoi3d's default clipPadding is 8x the point-cloud scale, which closes the hull cells
            // against a box 40 units across — correct, and useless as a picture. A tight box plus the
            // `bounded` cells (the ones surrounded entirely by neighbours) is the readable version.
            val sites = deterministicSites(72, 101)
            val lattice = afield.stdlib.Voronoi3d.build(sites, clipPadding = 0.12f)
            val interior = lattice.cells.filter { it.bounded }
            check(interior.size >= 12) { "expected a good number of interior cells, got ${interior.size}" }
            val packed = lattice.toTriMesh(shrink = 1.0)
            val exploded = interiorMesh(interior, 0.62)
            // No extra weld: toTriMesh already welds at a cell-relative tolerance, and welding a second
            // time at a coarser absolute one merges genuinely distinct corners into a non-manifold mess.
            val single = interior.minByOrNull { it.site.length() }!!.toTriMesh(shrink = 1.0)
            var totalVolume = 0.0
            var closedCells = 0
            for (cell in lattice.cells) {
                val one = cell.toTriMesh(shrink = 1.0)
                totalVolume += kotlin.math.abs(one.signedVolume())
                if (one.topology().isWatertight) closedCells++
            }
            val box = lattice.clipBounds
            val boxVolume = (box.max.x - box.min.x).toDouble() * (box.max.y - box.min.y) * (box.max.z - box.min.z)
            // The volumes tiling the clip box is the strong claim and is checked first. Per-cell
            // watertightness is the weaker one: voronoi.kt marks some hull cells' faces `truncated`
            // where the clip box cuts them, and those corner coordinates do not always coincide to
            // within a weld tolerance, so a few cells legitimately come out with a hairline crack.
            check(kotlin.math.abs(totalVolume - boxVolume) < boxVolume * 1e-3) {
                "the cells must tile the clip box: $totalVolume vs $boxVolume"
            }
            check(closedCells == lattice.cells.size) {
                "every cell must come out closed: $closedCells of ${lattice.cells.size}"
            }
            check(single.topology().isWatertight) { "an interior cell should be a closed solid: ${single.summary()}" }
            Figure(
                listOf(
                    Cell("one interior cell, shrink = 1.0", single, facts = facts(single)),
                    Cell("all 72 cells, shrink = 1.0 — they tile the clip box", packed, facts = "${packed.triangleCount} tris"),
                    Cell(
                        "${interior.size} interior cells, shrink = 0.62", exploded,
                        facts = "${exploded.triangleCount} tris — the seams are the cell walls",
                    ),
                ),
                ("72 cells, all %d closed, volumes sum to %.5f against a clip box of %.5f " +
                    "(%.1e relative); %d interior").format(
                    closedCells, totalVolume, boxVolume,
                    kotlin.math.abs(totalVolume - boxVolume) / boxVolume, interior.size,
                ),
                columns = 3,
            )
        },

        Panel(
            "09-measure-and-query",
            "Measurement and query — curvature, hulls, colour by scalar",
            "Discrete Gaussian and mean curvature, coloured through the same colormap tables RectSL " +
                "uses. A torus is the shape that has all three signs of Gaussian curvature. The hull " +
                "is an incremental convex hull, no dependencies.",
        ) {
            val torus = torusMesh(72, 36, 0.6, 0.24)
            val gauss = torus.gaussianCurvature()
            val mean = torus.meanCurvature()
            // On a torus of radii R, r the Gaussian curvature is cos(v) / (r (R + r cos v)), so it is
            // positive outside, zero on the top and bottom circles, and negative in the hole.
            check(gauss.min() < -1.0 && gauss.max() > 1.0) {
                "a torus should have both signs of Gaussian curvature: ${gauss.min()} .. ${gauss.max()}"
            }
            val ball = icosphereMesh(4, 0.7)
            val ballGauss = ball.gaussianCurvature()
            val expected = 1.0 / (0.7 * 0.7)
            check(kotlin.math.abs(ballGauss.average() - expected) < expected * 0.03) {
                "a sphere of radius 0.7 has K = ${expected}, measured ${ballGauss.average()}"
            }
            val cloud = deterministicSites(160, 5).map { p ->
                // Push the cloud onto a squashed ellipsoid so the hull has an obvious silhouette.
                val n = Vec3(p).normalize()
                Vec3(n.x * 0.75f, n.y * 0.45f, n.z * 0.6f)
            }
            val hull = convexHullMesh(cloud)
            check(hull.topology().isWatertight) { "a hull must be closed: ${hull.summary()}" }
            check(hull.topology().eulerCharacteristic == 2) { "a hull is genus 0: ${hull.summary()}" }
            val hullTree = hull.bvh()
            for (p in cloud) {
                val d = hullTree.closestPoint(p)?.distance ?: error("empty hull")
                check(hullTree.contains(p) || d < 1e-3) { "point $p was left outside the hull by $d" }
            }
            val blob = marchingCubes(
                sdfMetaballs(listOf(Vec3(-0.3f, 0f, 0f), Vec3(0.28f, 0.16f, 0f)), radius = 0.6, iso = 0.4),
                boundsAround(radius = 0.95), 72,
            )
            // Marching cubes legitimately emits sliver triangles wherever a crossing lands near a grid
            // corner, and a discrete curvature operator divides by those slivers' areas. A few Taubin
            // passes regularise the triangles without moving the surface, which is the honest fix — and
            // saying so is better than showing a speckled figure with no explanation.
            val smoothedBlob = blob.taubinSmooth(iterations = 8, lambda = 0.5, mu = -0.53)
            check(kotlin.math.abs(smoothedBlob.signedVolume() - blob.signedVolume()) < blob.signedVolume() * 0.05) {
                "smoothing must not change the blob's volume: ${blob.signedVolume()} -> ${smoothedBlob.signedVolume()}"
            }
            Figure(
                listOf(
                    Cell(
                        "gaussianCurvature() on a torus",
                        torus.coloredByScalar(gauss, "coolwarm"),
                        MeshView(width = CELL_W, height = CELL_H, wireframe = false, pitchDegrees = 46.0),
                        facts = "K from %.2f to %.2f".format(gauss.min(), gauss.max()),
                    ),
                    Cell(
                        "meanCurvature() on a torus",
                        torus.coloredByScalar(mean, "viridis"),
                        MeshView(width = CELL_W, height = CELL_H, wireframe = false, pitchDegrees = 46.0),
                        facts = "H from %.2f to %.2f".format(mean.min(), mean.max()),
                    ),
                    Cell(
                        "meanCurvature() on a marched blob, taubinSmooth(8) first",
                        smoothedBlob.coloredByScalar(smoothedBlob.meanCurvature(), "magma"),
                        MeshView(width = CELL_W, height = CELL_H, wireframe = false),
                        facts = "raw marching-cubes slivers make discrete curvature noisy",
                    ),
                    Cell(
                        "convexHullMesh(160 points)",
                        hull.withColor(0.85, 0.62, 0.32),
                        facts = facts(hull),
                    ),
                ),
            "torus K in [%.2f, %.2f]; sphere K %.4f against the analytic %.4f; hull contains all 160 points".format(
                    gauss.min(), gauss.max(), ballGauss.average(), expected,
                ),
            )
        },

        Panel(
            "10-shading-and-io",
            "Shading, and a round trip through OBJ and STL",
            "A generator ships without normals when its vertices sit on a crease, and you choose: " +
                "flat, crease-aware, or fully smooth. On the right, the same mesh after a round trip " +
                "through each file format — OBJ keeps the index structure, STL is triangle soup and " +
                "needs a weld.",
        ) {
            val tube = cylinderMesh(12, 0.4, 0.9)
            val flat = tube.flatShaded()
            val creased = tube.shadedNormals(35.0)
            val smooth = tube.withVertexNormals()
            check(flat.vertexCount == tube.triangleCount * 3) { "flatShaded shares nothing" }
            check(creased.vertexCount > tube.vertexCount && creased.vertexCount < flat.vertexCount) {
                "crease shading should split only the rim: ${creased.vertexCount}"
            }
            val source = icosphereMesh(3, 0.65)
            val viaObj = objToTriMesh(source.toObj())
            val viaStl = stlToTriMesh(source.toStlBinary()).weld(1e-5)
            check(viaObj.vertexCount == source.vertexCount) { "OBJ is indexed: ${viaObj.summary()}" }
            check(viaObj.topology().isWatertight) { "and round-trips watertight: ${viaObj.summary()}" }
            check(kotlin.math.abs(viaObj.signedVolume() - source.signedVolume()) < 1e-4) { "OBJ volume" }
            check(kotlin.math.abs(viaStl.signedVolume() - source.signedVolume()) < 1e-4) { "STL volume" }
            Figure(
                listOf(
                    Cell("cylinderMesh(12).flatShaded()", flat, facts = "${flat.vertexCount} verts — every edge hard"),
                    Cell(".shadedNormals(35°)", creased, facts = "${creased.vertexCount} verts — smooth side, hard rim"),
                    Cell(".withVertexNormals()", smooth, facts = "${smooth.vertexCount} verts — rim rounded off"),
                    Cell("→ OBJ → back", viaObj, facts = "${viaObj.vertexCount} verts, V=%.5f".format(viaObj.signedVolume())),
                    Cell("→ binary STL → weld() → back", viaStl, facts = "${viaStl.vertexCount} verts, V=%.5f".format(viaStl.signedVolume())),
                    Cell("the source", source, facts = "${source.vertexCount} verts, V=%.5f".format(source.signedVolume())),
                ),
                "flat ${flat.vertexCount} / creased ${creased.vertexCount} / smooth ${smooth.vertexCount} verts; " +
                    "OBJ and STL both recover V=%.5f".format(source.signedVolume()),
                columns = 3,
            )
        },

        Panel(
            "11-inline-visualizer",
            "The inline visualizer — what a TriMesh looks like in a box",
            "Every image here is the PNG that TriMesh.toHTML() embeds, decoded back out of the card's " +
                "own HTML, and every caption line is that card's own fact rows. Nothing is a lookalike " +
                "rendered alongside. This is what you see when a box evaluates to a mesh: the shape from " +
                "a fixed three-quarter view, and the numbers that decide whether it is usable.",
        ) {
            val subjects = listOf(
                "icosphereMesh(3)" to icosphereMesh(3, 0.7),
                "torusMesh(40, 20)" to torusMesh(40, 20, 0.55, 0.22),
                "marchingCubes(gyroid)" to marchingCubes(sdfGyroid(2.6, 0.35), boundsAround(radius = 0.95), 44),
                "planeMesh(6, 6) — open" to afield.stdlib.planeMesh(6, 6),
            )
            val cells = subjects.map { (label, mesh) ->
                val html = mesh.toHTML()
                val image = decodeCardPng(html)
                check(image.width == 300 && image.height == 225) {
                    "$label: the card's PNG should be 300x225, got ${image.width}x${image.height}"
                }
                Cell(label, mesh, view = MeshView(width = 300, height = 225), facts = cardFacts(html), image = image)
            }
            // The card must be telling the truth about each of these, which is the same claim its pixel
            // tests make; asserting it here too means a regression cannot land a pretty, wrong picture.
            check(subjects[0].second.toHTML().contains("watertight")) { "the icosphere card should say watertight" }
            check(subjects[1].second.toHTML().let { it.contains("χ=0") && it.contains("genus 1") }) {
                "the torus card should report chi 0 and genus 1"
            }
            check(subjects[3].second.toHTML().contains("boundary loop")) { "the patch card should report a boundary" }
            check(!subjects[3].second.toHTML().contains("volume")) { "and no volume" }
            Figure(
                cells,
                "4 inline cards, decoded from their own HTML; 300x225 each",
                columns = 4,
                factLines = 6,
            )
        },
    )

    /** The PNG a `toHTML()` card embeds, decoded — the card's real pixels, not a re-render. */
    private fun decodeCardPng(html: String): BufferedImage {
        val marker = "data:image/png;base64,"
        val start = html.indexOf(marker)
        check(start >= 0) { "the card embeds no inline PNG" }
        val from = start + marker.length
        // The card quotes its attributes with `"`; accept `'` too rather than depend on that.
        val quote = html.indexOf('"', from)
        val apostrophe = html.indexOf('\'', from)
        val end = when {
            quote < 0 -> apostrophe
            apostrophe < 0 -> quote
            else -> kotlin.math.min(quote, apostrophe)
        }
        check(end > from) { "the card's base64 payload is not delimited" }
        val bytes = java.util.Base64.getDecoder().decode(html.substring(from, end))
        return checkNotNull(ImageIO.read(java.io.ByteArrayInputStream(bytes))) {
            "the card's PNG (${bytes.size} bytes) did not decode"
        }
    }

    /** The card's own fact rows, flattened onto one line. */
    private fun cardFacts(html: String): String =
        Regex("<td[^>]*>([^<]*)</td><td[^>]*>([^<]*)</td>").findAll(html)
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .joinToString("  ·  ")

    // ==========================================================================================
    // Rendering
    // ==========================================================================================

    private fun renderCell(cell: Cell): BufferedImage {
        cell.image?.let { return it }
        val raster = cell.mesh.renderPixels(cell.view)
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, raster.width, raster.height, raster.pixels, 0, raster.width)
        return image
    }

    /** Fraction of pixels that differ from the panel background — the "did anything render" floor. */
    private fun paintedFraction(image: BufferedImage, background: Int): Double {
        var painted = 0
        var seen = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                seen++
                if ((image.getRGB(x, y) and 0xFFFFFF) != (background and 0xFFFFFF)) painted++
                x += 2
            }
            y += 2
        }
        return painted.toDouble() / seen.coerceAtLeast(1)
    }

    /** Number of distinct quantised colours — a black or flat render has almost none. */
    private fun colourSpread(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val p = image.getRGB(x, y)
                seen.add(((p shr 20) and 0xF shl 8) or ((p shr 12) and 0xF shl 4) or ((p shr 4) and 0xF))
                x += 3
            }
            y += 3
        }
        return seen.size
    }

    private fun renderPanel(panel: Panel, figure: Figure): BufferedImage {
        val gap = 12
        val captionH = 21 + 13 * figure.factLines
        val columns = figure.columns
        val rows = (figure.cells.size + columns - 1) / columns
        val headerH = 74
        val cellW = figure.cells.first().view.width
        val cellH = figure.cells.first().view.height
        val width = columns * cellW + (columns + 1) * gap
        val height = headerH + rows * (cellH + captionH + gap) + gap
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BG
        g.fillRect(0, 0, width, height)

        g.color = TEXT
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        g.drawString(panel.title, gap + 2, 26)
        g.color = DIM
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        wrap(panel.subtitle, width - 2 * gap - 4, g).forEachIndexed { i, line ->
            g.drawString(line, gap + 2, 46 + i * 15)
        }

        figure.cells.forEachIndexed { i, cell ->
            val col = i % columns
            val row = i / columns
            val x = gap + col * (cellW + gap)
            val y = headerH + row * (cellH + captionH + gap)
            val rendered = renderCell(cell)
            val painted = paintedFraction(rendered, cell.view.background)
            val spread = colourSpread(rendered)
            check(painted > 0.04) { "${panel.name} / ${cell.caption} rendered nearly empty (painted=$painted)" }
            // A flat-faced solid legitimately renders in very few colours — a cube from one angle is
            // three constant face shades plus the background and the wireframe — so this is only a
            // "did it render as a black rectangle" floor, not a richness check.
            check(spread > 10) { "${panel.name} / ${cell.caption} rendered flat ($spread distinct colours)" }
            g.color = PANEL_BG
            g.fillRect(x, y, cellW, cellH)
            g.drawImage(rendered, x, y, null)
            g.color = FRAME
            g.drawRect(x, y, cellW - 1, cellH - 1)
            g.color = TEXT
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            g.drawString(clip(cell.caption, cellW - 8, g), x + 3, y + cellH + 14)
            cell.facts?.let {
                g.color = ACCENT
                g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                wrap(it, cellW - 8, g).take(figure.factLines).forEachIndexed { line, text ->
                    g.drawString(clip(text, cellW - 8, g), x + 3, y + cellH + 27 + line * 13)
                }
            }
        }
        g.dispose()
        return sheet
    }

    private fun wrap(text: String, maxWidth: Int, g: java.awt.Graphics2D): List<String> {
        val metrics = g.fontMetrics
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (metrics.stringWidth(candidate) > maxWidth && line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    private fun clip(text: String, maxWidth: Int, g: java.awt.Graphics2D): String {
        val metrics = g.fontMetrics
        if (metrics.stringWidth(text) <= maxWidth) return text
        var cut = text
        while (cut.length > 4 && metrics.stringWidth("$cut…") > maxWidth) cut = cut.dropLast(1)
        return "$cut…"
    }

    // ==========================================================================================
    // Entry point
    // ==========================================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val outDir = File(
            args.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?: System.getenv("MESH_GALLERY_DIR")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("java.io.tmpdir"), "mesh-gallery").path
        )
        outDir.mkdirs()
        var failures = 0
        for (panel in PANELS) {
            try {
                val started = System.nanoTime()
                val figure = panel.build()
                check(figure.cells.isNotEmpty()) { "${panel.name} produced no cells" }
                for (cell in figure.cells) {
                    check(cell.mesh.triangleCount > 0) { "${panel.name} / ${cell.caption} is an empty mesh" }
                    check(cell.mesh.isFinite()) { "${panel.name} / ${cell.caption} has non-finite floats" }
                }
                val png = renderPanel(panel, figure)
                val file = File(outDir, "${panel.name}.png")
                ImageIO.write(png, "png", file)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                println(
                    "wrote ${file.absolutePath} (${png.width}x${png.height}, ${figure.cells.size} cells, ${elapsed}ms) — ${figure.summary}"
                )
            } catch (t: Throwable) {
                failures++
                System.err.println("[error] panel ${panel.name} failed: ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace(System.err)
            }
        }
        if (failures > 0) {
            System.err.println("MeshGallery FAILED: $failures of ${PANELS.size} panels")
            exitProcess(1)
        }
        println("mesh gallery rendered to ${outDir.absolutePath} (${PANELS.size} panels)")
    }
}
