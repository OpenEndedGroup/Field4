package afield.examples

import afield.graphics.VISION_METAL_VERTEX_FLOATS
import afield.graphics.VisionGeometryPreview
import afield.graphics.VisionGeometryPreview.CapturedMesh
import afield.graphics.VisionMetalProgramCheck
import org.yaml.snakeyaml.Yaml
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: vision.geometry-preview
runner: jvm-main
entrypoint: afield.examples.VisionGeometryPreviewGallery
tags: [smoke, visionos, kotlinjs, geometry, capture, cpu]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "vision geometry preview gallery rendered"
*/

/**
 * Headless **geometry-preview gallery** for the visionOS Live Metal head: takes a box's real
 * `geometry { … }` program all the way to pixels on the Mac.
 *
 * Every mesh here is built by the *real* shared builder — [VisionGeometryPreview.capture] compiles
 * the box source with the real `KotlinToJsCompiler` and runs the emitted JavaScript under `node`
 * against a mirror of `LiveMetalJavaScript.swift`. Nothing in this file reimplements `MeshBuilder`,
 * and four of the seven entries take their Kotlin verbatim out of the shipped agent transcripts, so
 * the gallery cannot drift away from what a user actually loads.
 *
 * What it does *not* do is run the box's Metal shader: the rasterizer's lambert shade is a stand-in,
 * and every rendered frame says so in its caption. This answers "is my **geometry** the shape I
 * meant?", which is the half of the author's question — *is it my shader or my geometry?* — that had
 * no answer away from the device.
 *
 * As a FIELD_TEST it renders to a temp dir and asserts real correctness: the mesh invariants
 * ([CapturedMesh.invariantProblems]), the analytic vertex/triangle counts of each shape primitive,
 * the packed stride against both [VISION_METAL_VERTEX_FLOATS] and the Swift `LiveMetalVertex`
 * declaration, and image-level facts about the raster (painted fraction, colour spread, and for the
 * sphere the roundness and area of its silhouette). It writes NO committed assets. Pass an output
 * dir as `args[0]` (or set `VISION_GEOMETRY_PREVIEW_DIR`) to regenerate the committed docs captures
 * under `dev_docs/assets/vision_geometry_preview/`.
 *
 * When regenerating, render to a **temp dir and copy the files in afterwards**. Writing them
 * straight into the working tree while `run_field_tests.py`'s Field server is watching it takes the
 * jvm-main child down mid-run (exit 130), for no reason the output ever explains.
 */
object VisionGeometryPreviewGallery {

    /** One gallery entry: a Live Metal box source, how to drive it, and what must be true of it. */
    class Preview(
        val name: String,
        val title: String,
        /** Whole box code, marker and all. */
        val source: String,
        /** `onFrame` ticks to pump after `onStart`; empty for a static mesh. */
        val frames: List<VisionGeometryPreview.FrameTick> = emptyList(),
        val camera: VisionGeometryPreview.Camera = VisionGeometryPreview.Camera(),
        /** Analytic expectations, when the shape's parameters pin them down. */
        val expectVertices: Int? = null,
        val expectTriangles: Int? = null,
        /**
         * Triangles the builder legitimately collapses. A UV sphere has `2 * segments` of them where
         * its lattice pinches at the poles; every other shape here has none. Spelled out per entry so
         * a *new* degenerate triangle is a failure rather than a shrug.
         */
        val expectDegenerate: Int = 0,
        /**
         * Expected [CapturedMesh.windingAgreement]. 1.0 for everything that should draw; the
         * deliberately-inverted entry is here to prove the detector fires, so it expects 0.0.
         */
        val expectWinding: Double = 1.0,
        /** Per-entry style tweak, for the one entry that must be drawn with culling off to be seen. */
        val restyle: (VisionGeometryPreview.Style) -> VisionGeometryPreview.Style = { it },
        /** Extra per-entry assertions against the captured mesh. */
        val check: (CapturedMesh) -> Unit = {},
    )

    private const val MARKER = "// {{ visionmetal uniforms=phase }}"

    private val cubeSource = """
        $MARKER
        onStart {
            geometry {
                color(1.0, 0.42, 0.18)
                cube(0.18, cz = -1.2, tintFaces = true)
            }
        }
    """.trimIndent()

    private val planeSource = """
        $MARKER
        onStart {
            geometry {
                color(0.95, 0.85, 0.25)
                plane(0.4, 0.3, cols = 8, rows = 6, cz = -1.2)
            }
        }
    """.trimIndent()

    private val sphereSource = """
        $MARKER
        onStart {
            geometry {
                color(0.25, 0.75, 1.0)
                sphere(0.2, segments = 32, rings = 20, cz = -1.2)
            }
        }
    """.trimIndent()

    private val tubeSource = """
        $MARKER
        import kotlin.math.cos
        import kotlin.math.sin

        onStart {
            geometry {
                color(0.62, 0.42, 1.0)
                tube(
                    (0..64).map { i ->
                        val t = i / 64.0
                        val a = t * 6.2831853 * 2.0
                        p(0.16 * sin(a), -0.2 + t * 0.4, -1.2 + 0.16 * cos(a))
                    },
                    radius = 0.022,
                    sides = 10,
                )
            }
        }
    """.trimIndent()

    /** The gallery. Four primitives, then three real shipped transcripts. */
    private fun previews(transcripts: File): List<Preview> = listOf(
        Preview(
            name = "cube",
            title = "cube(0.18, tintFaces = true)",
            source = cubeSource,
            // Six faces, each with its own four vertices so facet normals survive; two triangles each.
            expectVertices = 24,
            expectTriangles = 12,
            check = { mesh ->
                val normals = (0 until mesh.vertexCount)
                    .map { "%.3f,%.3f,%.3f".format(mesh.normalX(it), mesh.normalY(it), mesh.normalZ(it)) }
                    .toSet()
                require(normals.size == 6) { "cube should have six facet normals, got ${normals.size}" }
                val colors = (0 until mesh.vertexCount)
                    .map { "%.3f,%.3f,%.3f".format(mesh.red(it), mesh.green(it), mesh.blue(it)) }
                    .toSet()
                require(colors.size == 6) { "tintFaces should give six colours, got ${colors.size}" }
                val b = mesh.bounds()
                require(abs((b[3] - b[0]) - 0.36) < 1e-5) { "cube edge should be 0.36m, got ${b[3] - b[0]}" }
            },
        ),
        Preview(
            name = "plane",
            title = "plane(0.4, 0.3, cols = 8, rows = 6)",
            source = planeSource,
            camera = VisionGeometryPreview.Camera(yawDegrees = 34.0, pitchDegrees = 26.0),
            // A (cols+1) x (rows+1) lattice, two triangles per cell.
            expectVertices = 9 * 7,
            expectTriangles = 8 * 6 * 2,
            check = { mesh ->
                for (i in 0 until mesh.vertexCount) {
                    require(abs(mesh.normalZ(i) - 1f) < 1e-6f) { "plane normals must face +Z" }
                }
                // uv must span the whole sheet, corner to corner — that is what a shader samples.
                val us = (0 until mesh.vertexCount).map { mesh.u(it) }
                val vs = (0 until mesh.vertexCount).map { mesh.v(it) }
                require(us.min() == 0f && us.max() == 1f) { "plane u should span 0..1, got ${us.min()}..${us.max()}" }
                require(vs.min() == 0f && vs.max() == 1f) { "plane v should span 0..1, got ${vs.min()}..${vs.max()}" }
            },
        ),
        Preview(
            name = "sphere",
            title = "sphere(0.2, segments = 32, rings = 20)",
            source = sphereSource,
            expectVertices = 33 * 21,
            expectTriangles = 32 * 20 * 2,
            expectDegenerate = 2 * 32,
            check = { mesh ->
                for (i in 0 until mesh.vertexCount) {
                    val x = mesh.positionX(i).toDouble()
                    val y = mesh.positionY(i).toDouble()
                    val z = mesh.positionZ(i) + 1.2
                    val r = kotlin.math.sqrt(x * x + y * y + z * z)
                    require(abs(r - 0.2) < 1e-5) { "sphere vertex $i is $r from the centre, expected 0.2" }
                }
            },
        ),
        Preview(
            name = "tube",
            title = "tube(65-point helix, radius = 0.022, sides = 10)",
            source = tubeSource,
            // points x (sides+1) vertices; (points-1) x sides quads.
            expectVertices = 65 * 11,
            expectTriangles = 64 * 10 * 2,
        ),
        Preview(
            name = "shapes",
            title = "transcript: vision_geometry_shapes",
            source = transcriptCode(transcripts, "vision_geometry_shapes"),
            camera = VisionGeometryPreview.Camera(yawDegrees = 16.0, pitchDegrees = 12.0, distance = 2.4),
            expectDegenerate = 2 * 32,
            check = { mesh ->
                // One mesh holding all four primitives: 24 cube + 693 sphere + 81 plane + 539 tube.
                require(mesh.vertexCount == 24 + 33 * 21 + 9 * 9 + 49 * 11) {
                    "combined shapes mesh has ${mesh.vertexCount} vertices"
                }
                val b = mesh.bounds()
                require(b[3] - b[0] > 0.9) { "the four shapes should span ~1m of desk, got ${b[3] - b[0]}" }
            },
        ),
        Preview(
            name = "heightfield",
            title = "transcript: vision_geometry_heightfield",
            source = transcriptCode(transcripts, "vision_geometry_heightfield"),
            camera = VisionGeometryPreview.Camera(yawDegrees = 22.0, pitchDegrees = 34.0, distance = 2.3),
            expectVertices = 49 * 49,
            expectTriangles = 48 * 48 * 2,
            check = { mesh ->
                val b = mesh.bounds()
                // The ripple is 0.055m tall at the origin and decays; the sheet is 0.52m across.
                require(abs((b[3] - b[0]) - 0.52) < 1e-4) { "height field should be 0.52m across, got ${b[3] - b[0]}" }
                require(b[4] - b[1] in 0.02..0.12) { "ripple relief should be a few cm, got ${b[4] - b[1]}" }
                // Colour is driven by height, so a flat palette would mean the height function died.
                val reds = (0 until mesh.vertexCount).map { mesh.red(it) }
                require(reds.max() - reds.min() > 0.2) { "height field colour barely varies — is height() flat?" }
            },
        ),
        Preview(
            name = "animated",
            title = "transcript: vision_geometry_animated (t = 1.5s)",
            source = transcriptCode(transcripts, "vision_geometry_animated"),
            frames = VisionGeometryPreview.ticks(frameCount = 24, duration = 4.0).let { it.take(10) },
            camera = VisionGeometryPreview.Camera(yawDegrees = 18.0, pitchDegrees = 8.0, distance = 2.5),
            expectVertices = 73 * 9,
            expectTriangles = 72 * 8 * 2,
        ),
        Preview(
            name = "lantern",
            title = "transcript: vision_geometry_desk_preview (box 1)",
            source = transcriptEmbeddedProgram(transcripts, "vision_geometry_desk_preview", 0),
            camera = VisionGeometryPreview.Camera(yawDegrees = 24.0, pitchDegrees = 18.0, distance = 2.6),
            expectDegenerate = 2 * 28,
            check = { mesh ->
                // Sphere + tube + eight hand-stitched uprights, so all three levels of the builder
                // are exercised by the demo the user actually loads.
                require(mesh.vertexCount == 29 * 19 + 41 * 9 + 8 * 4) {
                    "lantern has ${mesh.vertexCount} vertices — has the demo transcript drifted?"
                }
            },
        ),
        Preview(
            name = "lantern-inverted",
            title = "transcript: vision_geometry_desk_preview (box 2 — wound backwards)",
            source = transcriptEmbeddedProgram(transcripts, "vision_geometry_desk_preview", 1),
            camera = VisionGeometryPreview.Camera(yawDegrees = 24.0, pitchDegrees = 18.0, distance = 2.6),
            // The point of this one: every normal is outward, every quad is wound the other way.
            expectWinding = 0.0,
            // Drawn with culling off, because with culling on there is nothing to photograph — which
            // is precisely what the headset would show you.
            restyle = { it.copy(cullBackFaces = false) },
            expectVertices = 24 * 4,
            expectTriangles = 24 * 2,
        ),
    )

    // ---------------------------------------------------------------- transcript sources

    /** Lift the `code` property of the one kotlinjs box in a shipped transcript, verbatim. */
    @Suppress("UNCHECKED_CAST")
    private fun transcriptCode(dir: File, name: String): String {
        val file = File(dir, "$name.yaml")
        require(file.isFile) { "transcript $name.yaml not found in ${dir.absolutePath}" }
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        val ops = root["operations"] as? List<Map<String, Any?>>
            ?: error("$name.yaml has no operations list")
        val codes = ops.mapNotNull { op ->
            val body = op["create"] as? Map<String, Any?> ?: return@mapNotNull null
            val properties = body["properties"] as? Map<String, Any?> ?: return@mapNotNull null
            properties["code"]?.toString()?.takeIf { it.isNotBlank() }
        }
        require(codes.size == 1) { "$name.yaml has ${codes.size} code boxes; expected exactly one" }
        return codes.single()
    }

    /**
     * The [index]th triple-quoted Live Metal program embedded in a *JVM Kotlin* transcript's boxes.
     *
     * `vision_geometry_desk_preview` is the one demo whose box bodies are JVM Kotlin that carry the
     * headset program as a string, so its meshes cannot be lifted the way the `// {{ visionmetal }}`
     * transcripts' can. Pulling them out here rather than restating them keeps the gallery and the
     * loadable demo from drifting: change one and this fails.
     */
    @Suppress("UNCHECKED_CAST")
    private fun transcriptEmbeddedProgram(dir: File, name: String, index: Int): String {
        val file = File(dir, "$name.yaml")
        require(file.isFile) { "transcript $name.yaml not found in ${dir.absolutePath}" }
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        val ops = root["operations"] as? List<Map<String, Any?>> ?: error("$name.yaml has no operations")
        val blocks = ops.flatMap { op ->
            val properties = (op["create"] as? Map<String, Any?>)?.get("properties") as? Map<String, Any?>
                ?: return@flatMap emptyList()
            val code = properties["code"]?.toString() ?: return@flatMap emptyList()
            Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).findAll(code)
                .map { it.groupValues[1].trimIndent().trim('\n') }
                .toList()
        }
        require(index in blocks.indices) {
            "$name.yaml has ${blocks.size} embedded program(s); asked for #$index"
        }
        return blocks[index]
    }

    /** Every `code` + `msl` pair in a transcript, for the uniform-wiring check. */
    @Suppress("UNCHECKED_CAST")
    private fun transcriptPrograms(dir: File, name: String): List<Pair<String, String?>> {
        val file = File(dir, "$name.yaml")
        require(file.isFile) { "transcript $name.yaml not found in ${dir.absolutePath}" }
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        val ops = root["operations"] as? List<Map<String, Any?>> ?: error("$name.yaml has no operations")
        return ops.mapNotNull { op ->
            val properties = (op["create"] as? Map<String, Any?>)?.get("properties") as? Map<String, Any?>
                ?: return@mapNotNull null
            val code = properties["code"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            code to properties["msl"]?.toString()
        }
    }

    /**
     * A checker that never fires proves nothing, and every shipped program is (now) clean — so put a
     * deliberately broken one through it and require each fault to come back.
     */
    private fun verifyUniformCheckFires(failures: MutableList<String>) {
        val broken = """
            // {{ visionmetal uniforms=spin,unused }}
            onStart {
                geometry { cube(0.1) }
                uniform("spin", 1.0)
                uniform("undeclared", 2.0)
            }
        """.trimIndent()
        val msl = """
            // u_commented is inside a comment and must NOT count as a reference
            vertex FieldVarying field_vertex() { return (FieldVarying)u_spin; }
        """.trimIndent()
        val problems = VisionMetalProgramCheck.problems(broken, msl, listOf("spin", "undeclared"))
        val expectations = listOf(
            "field_fragment" to true,
            "'undeclared' is not declared" to true,
            "'unused' is declared but never read" to false,
        )
        for ((fragment, fatal) in expectations) {
            val hit = problems.firstOrNull { fragment in it.message }
            when {
                hit == null -> failures += "uniform check missed '$fragment': got ${problems.map { it.message }}"
                hit.fatal != fatal -> failures += "uniform check reported '$fragment' as fatal=${hit.fatal}"
            }
        }
        if (problems.any { "u_commented" in it.message }) {
            failures += "uniform check read `u_commented` out of a comment"
        }
        if (problems.none { "u_spin" in it.message } && problems.none { it.message.contains("field_fragment") }) {
            failures += "uniform check found nothing at all in a deliberately broken program"
        }
        println("  uniform check self-test: ${problems.size} problem(s) on a deliberately broken program " +
            "(${problems.count { it.fatal }} fatal)")
    }

    /**
     * The shipped Live Metal transcripts, checked for uniform wiring — the failure mode a geometry
     * preview cannot see, because it happens in Metal's compiler on the device.
     */
    private fun checkShippedUniformWiring(transcripts: File, failures: MutableList<String>) {
        verifyUniformCheckFires(failures)
        for (name in listOf(
            "visionos_start", "vision_geometry_shapes", "vision_geometry_heightfield",
            "vision_geometry_animated",
        )) {
            for ((code, msl) in transcriptPrograms(transcripts, name)) {
                val declared = VisionMetalProgramCheck.declaredUniforms(code)
                val problems = VisionMetalProgramCheck.problems(code, msl)
                problems.filter { it.fatal }.forEach { failures += "$name: ${it.message}" }
                val advisory = problems.count { !it.fatal }
                println(
                    "  uniform wiring ${name.padEnd(28)} declared=${declared.joinToString(",").ifEmpty { "-" }} " +
                        "fatal=${problems.count { it.fatal }} advisory=$advisory",
                )
                problems.filter { !it.fatal }.forEach { println("      note: ${it.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- layout guard

    /**
     * The packed stride is an ABI shared by three languages. The Kotlin builder writes it, the wire
     * constant names it, and Swift reinterprets the array as `LiveMetalVertex` — so check all three
     * agree rather than trusting a comment.
     */
    private fun checkVertexLayout(repoRoot: File) {
        require(VISION_METAL_VERTEX_FLOATS == 16) {
            "VISION_METAL_VERTEX_FLOATS is $VISION_METAL_VERTEX_FLOATS; LiveMetalVertex is four float4s"
        }
        val swift = File(repoRoot, "native/field-visionos/Field4Vision/LiveMetal/LiveMetalWire.swift")
        if (!swift.isFile) {
            println("LiveMetalWire.swift not found; skipped the cross-language vertex-layout check")
            return
        }
        val body = Regex("""struct LiveMetalVertex \{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(swift.readText())?.groupValues?.get(1)
            ?: error("could not find `struct LiveMetalVertex` in ${swift.name}")
        val fields = Regex("""var\s+(\w+)\s*:\s*SIMD4<Float>""").findAll(body).map { it.groupValues[1] }.toList()
        require(fields == listOf("position", "normal", "color", "uv")) {
            "LiveMetalVertex declares $fields; the builder packs position/normal/color/uv in that order"
        }
        require(fields.size * 4 == VISION_METAL_VERTEX_FLOATS) {
            "LiveMetalVertex is ${fields.size * 4} floats but VISION_METAL_VERTEX_FLOATS is $VISION_METAL_VERTEX_FLOATS"
        }
        println("vertex layout: ${fields.size} float4s == $VISION_METAL_VERTEX_FLOATS floats, " +
            "Kotlin builder and Swift LiveMetalVertex agree")
    }

    // ---------------------------------------------------------------- image assertions

    /** Fraction of sampled pixels that differ from the background — "did anything draw?". */
    private fun paintedFraction(image: java.awt.image.BufferedImage, background: Int): Double {
        var painted = 0
        var seen = 0
        // Skip the caption strip, which is painted for every image and would flatter the number.
        var y = 0
        while (y < image.height - 56) {
            var x = 0
            while (x < image.width) {
                if ((image.getRGB(x, y) and 0xFFFFFF) != (background and 0xFFFFFF)) painted++
                seen++
                x += 2
            }
            y += 2
        }
        return painted.toDouble() / seen.coerceAtLeast(1)
    }

    /** Bounding box of everything that is not background, plus its fill ratio. */
    private fun silhouette(image: java.awt.image.BufferedImage, background: Int, skipBottom: Int = 0): DoubleArray {
        var minX = image.width; var maxX = -1; var minY = image.height; var maxY = -1
        var painted = 0
        for (y in 0 until image.height - skipBottom) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) and 0xFFFFFF) == (background and 0xFFFFFF)) continue
                painted++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return doubleArrayOf(0.0, 0.0, 0.0)
        val w = (maxX - minX + 1).toDouble()
        val h = (maxY - minY + 1).toDouble()
        return doubleArrayOf(w, h, painted / (w * h))
    }

    /** Mean luminance of the painted pixels — catches "rendered, but entirely black". */
    private fun meanPaintedLuminance(image: java.awt.image.BufferedImage, background: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until image.height - 56 step 2) {
            for (x in 0 until image.width step 2) {
                val p = image.getRGB(x, y)
                if ((p and 0xFFFFFF) == (background and 0xFFFFFF)) continue
                sum += 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    // ---------------------------------------------------------------- main

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val repoRoot = locateRepoRoot()
        val transcripts = File(repoRoot, "dev_docs/agents/transcripts")
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("VISION_GEOMETRY_PREVIEW_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "vision-geometry-preview").path,
        )
        outDir.mkdirs()
        println("VisionGeometryPreviewGallery: repo = ${repoRoot.absolutePath}")
        println("VisionGeometryPreviewGallery: out  = ${outDir.absolutePath}")

        checkVertexLayout(repoRoot)

        val style = VisionGeometryPreview.Style()
        val failures = mutableListOf<String>()
        checkShippedUniformWiring(transcripts, failures)
        var rendered = 0

        for (preview in previews(transcripts)) {
            val started = System.nanoTime()
            val capture = try {
                VisionGeometryPreview.capture(
                    preview.source,
                    moduleName = "vision_preview_${preview.name}",
                    frames = preview.frames,
                )
            } catch (e: VisionGeometryPreview.NodeUnavailable) {
                // Without node there is no honest mesh to draw, and drawing a dishonest one is worse
                // than failing: fail loudly rather than shipping a gallery of invented shapes.
                System.err.println("FATAL: ${e.message}")
                exitProcess(2)
            }
            val mesh = capture.mesh()
            val problems = mesh.invariantProblems()
            if (problems.isNotEmpty()) {
                failures += "${preview.name}: ${problems.size} invariant failure(s): ${problems.take(3)}"
            }
            preview.expectVertices?.let {
                if (mesh.vertexCount != it) {
                    failures += "${preview.name}: expected $it vertices, built ${mesh.vertexCount}"
                }
            }
            preview.expectTriangles?.let {
                if (mesh.triangleCount != it) {
                    failures += "${preview.name}: expected $it triangles, built ${mesh.triangleCount}"
                }
            }
            val degenerate = mesh.degenerateTriangles()
            if (degenerate.size != preview.expectDegenerate) {
                failures += "${preview.name}: ${degenerate.size} degenerate triangle(s), " +
                    "expected ${preview.expectDegenerate} (first: ${degenerate.take(3)})"
            }
            // A mesh whose winding disagrees with its own declared normals draws inside-out on a
            // head that culls back faces. This needs no device to detect and no Metal convention to
            // interpret — the mesh simply contradicts itself.
            val winding = mesh.windingAgreement()
            if (abs(winding - preview.expectWinding) > 0.001) {
                failures += "${preview.name}: ${"%.1f".format(winding * 100)}% of triangles are wound " +
                    "to match their own normals, expected ${"%.1f".format(preview.expectWinding * 100)}%"
            }
            runCatching { preview.check(mesh) }.onFailure { failures += "${preview.name}: ${it.message}" }
            if (preview.frames.isNotEmpty()) {
                if (!capture.installedFrameCallback) {
                    failures += "${preview.name}: declares frames but never installed onFrame"
                }
                if (capture.meshes.size < preview.frames.size) {
                    failures += "${preview.name}: ${preview.frames.size} ticks produced only " +
                        "${capture.meshes.size} uploads — is the rebuild really inside onFrame?"
                }
                // A per-frame rebuild that yields the identical mesh every tick is not animation.
                val first = capture.meshes.first()
                val moved = (0 until minOf(first.vertexCount, mesh.vertexCount)).any {
                    abs(first.positionX(it) - mesh.positionX(it)) > 1e-4f
                }
                if (!moved) failures += "${preview.name}: the mesh is identical on every frame"
            }

            val entryStyle = preview.restyle(style.copy(title = preview.title))
            val image = VisionGeometryPreview.render(mesh, preview.camera, entryStyle)
            val file = File(outDir, "vision-geometry-${preview.name}.png")
            ImageIO.write(image, "png", file)
            rendered++

            val painted = paintedFraction(image, style.background)
            val luminance = meanPaintedLuminance(image, style.background)
            if (painted < 0.04) failures += "${preview.name}: rasterized near-empty (painted=$painted)"
            if (painted > 0.92) failures += "${preview.name}: rasterized as a full-frame smear (painted=$painted)"
            if (luminance < 24.0) failures += "${preview.name}: painted pixels are almost black (mean=$luminance)"

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println(
                "  ${preview.name.padEnd(12)} ${mesh.vertexCount} verts, ${mesh.triangleCount} tris, " +
                    "${capture.meshes.size} upload(s), ${capture.uniforms.size} uniform write(s) — " +
                    "painted=${"%.3f".format(painted)} luma=${"%.0f".format(luminance)} " +
                    "winding=${"%.0f".format(winding * 100)}% degenerate=${degenerate.size} " +
                    "(${elapsedMs}ms) -> ${file.name}",
            )
            capture.log.take(2).forEach { println("      log: $it") }

            // The sphere is the one shape whose silhouette is analytically knowable, so it earns a
            // real "does it look like a sphere" test rather than just "something drew".
            if (preview.name == "sphere") {
                // Measured on a bare render: the title and the stats lines are painted pixels too,
                // and would drag the silhouette's bounding box out to the width of the text.
                val bare = VisionGeometryPreview.render(mesh, preview.camera, style.copy(caption = "", title = null))
                val (w, h, fill) = silhouette(bare, style.background).let { Triple(it[0], it[1], it[2]) }
                val aspect = w / h
                if (abs(aspect - 1.0) > 0.06) failures += "sphere silhouette is $aspect wide/tall, expected ~1"
                // A filled disc covers pi/4 of its bounding box; a cube would cover ~1.
                if (abs(fill - 0.785) > 0.06) failures += "sphere silhouette fills $fill of its box, expected ~0.785"
                println("      silhouette ${w.toInt()}x${h.toInt()} px, fill=${"%.3f".format(fill)} (disc is 0.785)")
            }

            // A turntable proves the mesh is a solid from every side, not a lucky projection.
            if (preview.name in setOf("cube", "sphere", "heightfield")) {
                val sheet = VisionGeometryPreview.renderContactSheet(
                    mesh,
                    VisionGeometryPreview.Camera.turntable(4, pitchDegrees = preview.camera.pitchDegrees),
                    style.copy(width = 420, height = 320, title = preview.title),
                )
                val sheetFile = File(outDir, "vision-geometry-${preview.name}-turntable.png")
                ImageIO.write(sheet, "png", sheetFile)
                rendered++
                println("      turntable -> ${sheetFile.name} (${sheet.width}x${sheet.height})")
            }
        }

        renderAnimatedFrames(transcripts, outDir, style)?.let { rendered += it }

        if (failures.isNotEmpty()) {
            System.err.println("VisionGeometryPreviewGallery FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("vision geometry preview gallery rendered to ${outDir.absolutePath} ($rendered image(s))")
    }

    /**
     * The per-frame rebuild, as a filmstrip and (if ffmpeg is around) an mp4 — the only way to show
     * that `geometry { }` inside `onFrame` really produces a *different* mesh each tick.
     */
    private fun renderAnimatedFrames(
        transcripts: File,
        outDir: File,
        style: VisionGeometryPreview.Style,
    ): Int? {
        val source = transcriptCode(transcripts, "vision_geometry_animated")
        val ticks = VisionGeometryPreview.ticks(frameCount = 90, duration = 6.0)
        val capture = VisionGeometryPreview.capture(source, moduleName = "vision_preview_anim", frames = ticks)
        require(capture.meshes.size == ticks.size) {
            "animated transcript produced ${capture.meshes.size} meshes for ${ticks.size} ticks"
        }
        val camera = VisionGeometryPreview.Camera(yawDegrees = 18.0, pitchDegrees = 8.0, distance = 2.5)
        val frameStyle = style.copy(width = 480, height = 360, supersample = 2, caption = "")

        val framesDir = File(outDir, "animated-frames").apply { deleteRecursively(); mkdirs() }
        capture.meshes.forEachIndexed { i, mesh ->
            ImageIO.write(
                VisionGeometryPreview.render(mesh, camera, frameStyle),
                "png",
                File(framesDir, "frame_%05d.png".format(i)),
            )
        }

        // A four-up filmstrip is the committed still; it survives with no video player.
        val strip = java.awt.image.BufferedImage(4 * 480 + 5 * 8, 360 + 16 + 46, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = strip.createGraphics()
        g.color = java.awt.Color(0x0A, 0x0C, 0x10)
        g.fillRect(0, 0, strip.width, strip.height)
        listOf(0, 22, 45, 67).forEachIndexed { slot, frame ->
            val mesh = capture.meshes[frame]
            g.drawImage(VisionGeometryPreview.render(mesh, camera, frameStyle), 8 + slot * 488, 8, null)
            g.color = java.awt.Color(0x7A, 0x8A, 0xA0)
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12)
            g.drawString("t = %.2fs".format(ticks[frame].time), 16 + slot * 488, 26)
        }
        g.color = java.awt.Color(0x08, 0x0A, 0x0E)
        g.fillRect(0, 360 + 16, strip.width, 46)
        g.color = java.awt.Color(0xE6, 0xEC, 0xF4)
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 14)
        g.drawString("vision_geometry_animated — the mesh really is rebuilt every frame", 14, 360 + 38)
        g.color = java.awt.Color(0x93, 0xA4, 0xBA)
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12)
        g.drawString(VisionGeometryPreview.DEFAULT_CAPTION, 14, 360 + 56)
        g.dispose()
        val stripFile = File(outDir, "vision-geometry-animated-filmstrip.png")
        ImageIO.write(strip, "png", stripFile)
        println("      filmstrip -> ${stripFile.name} (${strip.width}x${strip.height})")

        var written = 1
        val ffmpeg = resolveFfmpeg()
        if (ffmpeg == null) {
            println("      mp4 skipped: ffmpeg not found (the filmstrip still ships)")
        } else {
            val mp4 = File(outDir, "vision-geometry-animated.mp4")
            mp4.delete()
            val process = ProcessBuilder(
                ffmpeg, "-y",
                "-framerate", "30",
                "-i", "frame_%05d.png",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
                "-movflags", "+faststart",
                mp4.absolutePath,
            ).directory(framesDir).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (mp4.length() > 0L) {
                written++
                println("      mp4 -> ${mp4.name} (${ticks.size} frames, ${mp4.length() / 1024}kB)")
            } else {
                println("      mp4 skipped: ffmpeg wrote nothing\n${log.takeLast(400)}")
            }
        }
        framesDir.deleteRecursively()
        return written
    }

    private fun resolveFfmpeg(): String? = listOfNotNull(
        System.getenv("FFMPEG"), "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg",
    ).firstOrNull { File(it).canExecute() }

    private fun locateRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "dev_docs/agents/transcripts").isDirectory) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
