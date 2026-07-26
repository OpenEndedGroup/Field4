package afield.graphics

import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * See a Live Metal box's `geometry { … }` on your desk, without a headset.
 *
 * # What this proves, and what it does not
 *
 * A `// {{ visionmetal }}` box has three parts, and only one of them can be checked away from the
 * device today. This previews **the geometry part, and only that**:
 *
 *  - The mesh is built by the *real* builder. [capture] resolves the box source through the real
 *    [KotlinJsHeadProfiles.visionMetal] profile, compiles it with the real [KotlinToJsCompiler], and
 *    runs the emitted JavaScript under `node` against [hostShim] — a mirror of
 *    `LiveMetalJavaScript.swift`. What gets captured is the byte-identical packed array the headset's
 *    Metal vertex buffer would receive. Nothing here reimplements `MeshBuilder`; if the shared
 *    [VisionMetalKotlinJs] builder source changes, this changes with it.
 *  - The **shader is a stand-in**. The box's MSL is not compiled, not translated and not simulated.
 *    [render] shades with a fixed two-light lambert over the vertex colours the builder wrote. So a
 *    preview answers *"is my geometry the shape I meant?"* — the other half of the question the
 *    **Vision Geometry: Built-in Cube** command answers. It cannot tell you your shader is right.
 *
 * That boundary is deliberate and is repeated in the caption burnt into every rendered frame.
 *
 * # Typical use
 *
 * ```kotlin
 * val capture = VisionGeometryPreview.capture(box.code)
 * val png = VisionGeometryPreview.renderPng(capture.mesh())
 * display(png)
 * ```
 *
 * or, from a Live Metal box's own commands, the **Preview Vision Geometry** action.
 */
object VisionGeometryPreview {

    // ---------------------------------------------------------------- captured meshes

    /**
     * One `__fieldSetGeometry` upload, exactly as the head received it.
     *
     * [packed] is a whole number of [VISION_METAL_VERTEX_FLOATS]-float vertices in the
     * `position / normal / color / uv+extra` layout `LiveMetalVertex` declares; [indices] are
     * triangle indices into it.
     */
    class CapturedMesh(val packed: FloatArray, val indices: IntArray, val label: String = "mesh") {

        val vertexCount: Int get() = packed.size / VISION_METAL_VERTEX_FLOATS
        val triangleCount: Int get() = indices.size / 3
        val isEmpty: Boolean get() = triangleCount == 0

        private fun at(vertex: Int, slot: Int): Float = packed[vertex * VISION_METAL_VERTEX_FLOATS + slot]

        fun positionX(i: Int): Float = at(i, 0)
        fun positionY(i: Int): Float = at(i, 1)
        fun positionZ(i: Int): Float = at(i, 2)
        fun positionW(i: Int): Float = at(i, 3)
        fun normalX(i: Int): Float = at(i, 4)
        fun normalY(i: Int): Float = at(i, 5)
        fun normalZ(i: Int): Float = at(i, 6)
        fun normalW(i: Int): Float = at(i, 7)
        fun red(i: Int): Float = at(i, 8)
        fun green(i: Int): Float = at(i, 9)
        fun blue(i: Int): Float = at(i, 10)
        fun alpha(i: Int): Float = at(i, 11)
        fun u(i: Int): Float = at(i, 12)
        fun v(i: Int): Float = at(i, 13)
        fun extra0(i: Int): Float = at(i, 14)
        fun extra1(i: Int): Float = at(i, 15)

        /** `[minX, minY, minZ, maxX, maxY, maxZ]`, or a unit box when the mesh is empty. */
        fun bounds(): DoubleArray {
            if (vertexCount == 0) return doubleArrayOf(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
            val b = doubleArrayOf(
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
            )
            for (i in 0 until vertexCount) {
                val x = positionX(i).toDouble(); val y = positionY(i).toDouble(); val z = positionZ(i).toDouble()
                b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = min(b[2], z)
                b[3] = max(b[3], x); b[4] = max(b[4], y); b[5] = max(b[5], z)
            }
            return b
        }

        /**
         * Everything that must be true of a mesh before Metal will draw it sanely. Returns the
         * problems it found rather than throwing, so a caller can report all of them at once.
         *
         * This is the regression net the geometry part lacked: the head silently draws nothing, or
         * draws garbage, for every one of these.
         */
        fun invariantProblems(): List<String> = buildList {
            // `indices` inside buildList would resolve to the list's own IntRange, so bind it here.
            val triangles = this@CapturedMesh.indices
            if (packed.size % VISION_METAL_VERTEX_FLOATS != 0) {
                add("$label: ${packed.size} floats is not a whole number of $VISION_METAL_VERTEX_FLOATS-float vertices")
                return@buildList
            }
            if (triangles.size % 3 != 0) add("$label: ${triangles.size} indices is not whole triangles")
            triangles.forEachIndexed { at, index ->
                if (index < 0 || index >= vertexCount) {
                    add("$label: index[$at] = $index is outside the $vertexCount vertices")
                }
            }
            // Everything below indexes the packed array; if the indices are already wrong, stop here
            // rather than reporting a cascade of derived nonsense.
            if (isNotEmpty()) return@buildList
            for (i in 0 until vertexCount) {
                if (positionW(i) != 1f) add("$label: vertex $i position.w is ${positionW(i)}, must be 1")
                if (normalW(i) != 0f) add("$label: vertex $i normal.w is ${normalW(i)}, must be 0")
                val finite = (0 until VISION_METAL_VERTEX_FLOATS).all {
                    packed[i * VISION_METAL_VERTEX_FLOATS + it].isFinite()
                }
                if (!finite) add("$label: vertex $i carries a non-finite float")
                val n = sqrt(
                    normalX(i).toDouble() * normalX(i) + normalY(i).toDouble() * normalY(i) +
                        normalZ(i).toDouble() * normalZ(i),
                )
                // A builder is allowed to leave a normal at zero, but a *nearly* unit normal is
                // almost always an un-normalised cross product, which shades wrong on the device.
                if (n > 1e-6 && abs(n - 1.0) > 1e-3) {
                    add("$label: vertex $i normal length is $n, expected 1 (or exactly 0 for 'unset')")
                }
            }
        }

        /**
         * Triangles with no area — a repeated index, or three positions on a line.
         *
         * Not automatically a fault: a UV [sphere] legitimately produces `2 * segments` of them where
         * its quad lattice collapses at the two poles, and Metal discards them for free. It is only a
         * fault when the *count* is a surprise, so this is reported rather than asserted.
         */
        fun degenerateTriangles(): List<Int> = (0 until triangleCount).filter { t ->
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            a == b || b == c || a == c || triangleArea(a, b, c) < 1e-12
        }

        /**
         * The fraction of triangles whose **winding agrees with their own declared normals**.
         *
         * A triangle's winding implies an outward direction (the right-hand rule over `b-a`, `c-a`);
         * the builder separately declares a `normal()` per vertex. If those two disagree, the mesh
         * contradicts itself, and the head — which culls back faces — will draw the surface the
         * author did not mean. That makes this the single most useful device-independent check there
         * is: it needs no knowledge of Metal's winding convention, only that the mesh be consistent.
         *
         * 1.0 is a clean mesh. 0.0 is a mesh wound entirely inside-out. Anything in between is a mesh
         * that will have holes. Triangles with no declared normal, and degenerate ones, are skipped.
         */
        fun windingAgreement(): Double {
            var agree = 0
            var counted = 0
            for (t in 0 until triangleCount) {
                val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
                if (a == b || b == c || a == c) continue
                if (a >= vertexCount || b >= vertexCount || c >= vertexCount || a < 0 || b < 0 || c < 0) continue
                val ux = (positionX(b) - positionX(a)).toDouble()
                val uy = (positionY(b) - positionY(a)).toDouble()
                val uz = (positionZ(b) - positionZ(a)).toDouble()
                val vx = (positionX(c) - positionX(a)).toDouble()
                val vy = (positionY(c) - positionY(a)).toDouble()
                val vz = (positionZ(c) - positionZ(a)).toDouble()
                val gx = uy * vz - uz * vy
                val gy = uz * vx - ux * vz
                val gz = ux * vy - uy * vx
                if (gx * gx + gy * gy + gz * gz < 1e-24) continue
                val nx = (normalX(a) + normalX(b) + normalX(c)).toDouble()
                val ny = (normalY(a) + normalY(b) + normalY(c)).toDouble()
                val nz = (normalZ(a) + normalZ(b) + normalZ(c)).toDouble()
                if (nx * nx + ny * ny + nz * nz < 1e-12) continue
                counted++
                if (gx * nx + gy * ny + gz * nz > 0) agree++
            }
            return if (counted == 0) 1.0 else agree.toDouble() / counted
        }

        private fun triangleArea(a: Int, b: Int, c: Int): Double {
            val ux = (positionX(b) - positionX(a)).toDouble()
            val uy = (positionY(b) - positionY(a)).toDouble()
            val uz = (positionZ(b) - positionZ(a)).toDouble()
            val vx = (positionX(c) - positionX(a)).toDouble()
            val vy = (positionY(c) - positionY(a)).toDouble()
            val vz = (positionZ(c) - positionZ(a)).toDouble()
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            return 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
        }

        /** Throws with every problem at once, for a smoke that wants one clear failure. */
        fun requireValid(): CapturedMesh {
            val problems = invariantProblems()
            require(problems.isEmpty()) {
                "Vision geometry mesh failed ${problems.size} invariant(s):\n" + problems.joinToString("\n") { "  - $it" }
            }
            return this
        }

        override fun toString(): String =
            "CapturedMesh($label: $vertexCount vertices, $triangleCount triangles)"
    }

    /** One `uniform(name, …)` write seen by the shim, in order. */
    data class CapturedUniform(val name: String, val x: Double, val y: Double, val z: Double, val w: Double)

    /** Everything one headless run of a Live Metal box's script produced. */
    class Capture(
        val meshes: List<CapturedMesh>,
        val uniforms: List<CapturedUniform>,
        val log: List<String>,
        val javascriptChars: Int,
        val installedFrameCallback: Boolean,
    ) {
        /** The mesh the head would be showing at the end of the run — the last upload wins. */
        fun mesh(): CapturedMesh = meshes.lastOrNull()
            ?: error("this Live Metal script never called geometry { … }, so the head would draw nothing")

        fun uniform(name: String): CapturedUniform? = uniforms.lastOrNull { it.name == name }

        override fun toString(): String = "VisionGeometryPreview.Capture(${meshes.size} upload(s), " +
            "${uniforms.size} uniform write(s), ${log.size} log line(s))"
    }

    // ---------------------------------------------------------------- capture

    /** How the preview drives `onFrame` after `onStart` has run. */
    data class FrameTick(val time: Double, val deltaTime: Double = 1.0 / 90.0, val frameNumber: Int = 0) {
        val predictedDisplayTime: Double get() = time + deltaTime
    }

    /** `frameCount` evenly spaced ticks covering `[0, duration)`, the usual animation sweep. */
    fun ticks(frameCount: Int, duration: Double): List<FrameTick> =
        (0 until frameCount).map { i ->
            val dt = duration / frameCount
            FrameTick(time = i * dt, deltaTime = dt, frameNumber = i)
        }

    class NodeUnavailable(message: String) : IllegalStateException(message)

    /**
     * Compile [source] the way a live box does and run it under `node`, capturing every geometry
     * upload.
     *
     * [source] is whole box code, `// {{ visionmetal … }}` marker and all — the marker is consumed by
     * the real [KotlinJsHeadProfiles.resolve], so the *same* string you would paste into a box works
     * here. `onStart` runs immediately (as it does on the head once the immersive scene is up); each
     * entry in [frames] then calls the installed `onFrame`, so a script that rebuilds its mesh every
     * frame yields one [CapturedMesh] per tick.
     *
     * Throws [NodeUnavailable] when `node` is not on `PATH`, and [IllegalArgumentException] with the
     * compiler's own diagnostics when the box does not compile.
     */
    fun capture(
        source: String,
        moduleName: String = "vision_geometry_preview",
        frames: List<FrameTick> = emptyList(),
        timeoutSeconds: Long = 60,
    ): Capture {
        val node = which("node")
            ?: throw NodeUnavailable(
                "node is not on PATH; the geometry preview runs the box's real compiled Kotlin/JS, " +
                    "so there is nothing honest to draw without it",
            )

        val selection = KotlinJsHeadProfiles.resolve(source)
        require(selection.profile === KotlinJsHeadProfiles.visionMetal || !selection.explicit) {
            "Vision geometry preview needs a Live Metal box; this one asks for the " +
                "'${selection.profile.id}' head"
        }
        val prepared = KotlinJsHeadProfiles.visionMetal.prepareSource(selection.source)
        val compiled = KotlinToJsCompiler.compile(prepared, moduleName = moduleName, wrapImplicitMain = false)
        val errors = compiled.diagnostics.filter { it.severity == "error" }
        require(errors.isEmpty()) {
            "Live Metal box does not compile:\n" + errors.joinToString("\n") { "  line ${it.line}: ${it.message}" }
        }
        val js = compiled.js
        require(compiled.success && js != null) {
            "Live Metal box did not produce JavaScript:\n${compiled.rawLog.takeLast(1600)}"
        }

        return runCaptured(node, js, frames, timeoutSeconds)
    }

    private fun runCaptured(node: String, js: String, frames: List<FrameTick>, timeoutSeconds: Long): Capture {
        val ticks = frames.joinToString(",\n") {
            "  [${it.time}, ${it.deltaTime}, ${it.frameNumber}, ${it.predictedDisplayTime}]"
        }
        val program = buildString {
            append(hostShim)
            append("\n")
            append(js)
            append("\n")
            append("const __fieldTicks = [\n$ticks\n];\n")
            append(captureEpilogue)
        }
        val script = Files.createTempFile("field-vision-geometry-preview", ".js").toFile()
        try {
            script.writeText(program)
            val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Live Metal script did not finish within ${timeoutSeconds}s:\n${output.takeLast(2000)}")
            }
            require(process.exitValue() == 0) {
                "Live Metal script threw while building geometry:\n${output.takeLast(2000)}"
            }
            val line = output.lineSequence().lastOrNull { it.startsWith(CAPTURE_PREFIX) }
                ?: error("geometry capture produced no payload; node said:\n${output.takeLast(2000)}")
            val payload = captureJson.decodeFromString<CapturePayload>(line.removePrefix(CAPTURE_PREFIX))
            return Capture(
                meshes = payload.meshes.mapIndexed { i, m ->
                    CapturedMesh(m.vertices, m.indices, label = "upload ${i + 1}")
                },
                uniforms = payload.uniformValues.map { CapturedUniform(it.name, it.x, it.y, it.z, it.w) },
                log = payload.log,
                javascriptChars = js.length,
                installedFrameCallback = payload.hasFrameCallback,
            )
        } finally {
            script.delete()
        }
    }

    private const val CAPTURE_PREFIX = "__field_vision_geometry__:"
    private val captureJson = Json { ignoreUnknownKeys = true }

    @Serializable
    private class CaptureMeshPayload(val vertices: FloatArray, val indices: IntArray)

    @Serializable
    private class CaptureUniformPayload(
        val name: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val w: Double,
    )

    @Serializable
    private class CapturePayload(
        val meshes: List<CaptureMeshPayload> = emptyList(),
        val uniformValues: List<CaptureUniformPayload> = emptyList(),
        val log: List<String> = emptyList(),
        val hasFrameCallback: Boolean = false,
    )

    /**
     * The host side of the JavaScriptCore bridge, mirroring `LiveMetalJavaScript.swift`.
     *
     * Faithfulness matters twice: `__fieldSetGeometry` really is handed typed arrays on the device
     * (the Kotlin/JS `FloatArray`/`IntArray` bridge to `Float32Array`/`Int32Array`), and `onStart` on
     * the head really does run its callback immediately once the immersive scene is up. Anything the
     * shim gets wrong is a bug the preview would hide.
     */
    private val hostShim = """
        const __fieldMeshes = [];
        const __fieldUniforms = [];
        const __fieldLog = [];
        let __fieldFrame = null;
        let __fieldStop = null;
        function uniform(name, x, y = 0, z = 0, w = 0) {
            __fieldUniforms.push({ name: String(name), x: Number(x), y: Number(y), z: Number(z), w: Number(w) });
        }
        function __fieldSetGeometry(vertices, indices) {
            if (!(vertices instanceof Float32Array)) {
                throw new Error("geometry vertices reached the bridge as " + Object.prototype.toString.call(vertices));
            }
            if (!(indices instanceof Int32Array)) {
                throw new Error("geometry indices reached the bridge as " + Object.prototype.toString.call(indices));
            }
            __fieldMeshes.push({ vertices: Array.from(vertices), indices: Array.from(indices) });
        }
        function __fieldOnStart(callback) { callback(); }
        function __fieldOnStop(callback) { __fieldStop = callback; }
        function __fieldOnFrame(callback) {
            if (typeof callback !== "function") throw new TypeError("onFrame expects a function");
            __fieldFrame = callback;
        }
        function __fieldKeepAlive() {}
        function print(value) { __fieldLog.push(String(value)); }
    """.trimIndent()

    private val captureEpilogue = """
        for (const tick of __fieldTicks) {
            if (typeof __fieldFrame !== "function") break;
            __fieldFrame(tick[0], tick[1], tick[2], tick[3]);
        }
        if (typeof __fieldStop === "function") __fieldStop();
        console.log("$CAPTURE_PREFIX" + JSON.stringify({
            meshes: __fieldMeshes,
            uniformValues: __fieldUniforms,
            log: __fieldLog,
            hasFrameCallback: typeof __fieldFrame === "function",
        }));
    """.trimIndent()

    private fun which(executable: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .map { File(it, executable) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath

    // ---------------------------------------------------------------- camera and style

    /**
     * Where the desk-side camera stands. [yawDegrees] orbits around `+y`, [pitchDegrees] lifts above
     * the horizon, and [distance] is in multiples of the mesh's bounding-sphere radius — so the same
     * camera frames a 2cm mesh and a 2m one.
     *
     * The head puts you at the origin looking down `-z`; yaw 0 / pitch 0 reproduces that view.
     */
    data class Camera(
        val yawDegrees: Double = 26.0,
        val pitchDegrees: Double = 16.0,
        val distance: Double = 2.7,
        val fieldOfViewDegrees: Double = 42.0,
    ) {
        companion object {
            /** What you would see standing where the headset puts you. */
            val headOn = Camera(yawDegrees = 0.0, pitchDegrees = 0.0)

            /** Four evenly spaced orbits, for a turntable contact sheet. */
            fun turntable(count: Int, pitchDegrees: Double = 16.0): List<Camera> =
                (0 until count).map { Camera(yawDegrees = 360.0 * it / count, pitchDegrees = pitchDegrees) }
        }
    }

    /**
     * How the preview draws.
     *
     * [caption] and [title] are text, so only the AWT path ([render], [renderContactSheet]) burns
     * them into the pixels; [renderFigureHtml] puts them in the markup instead. Either way the frame
     * carries the honesty note. Passing `caption = ""` drops it and reclaims the bottom band — for a
     * frame going into a video, where the note belongs in the page around it.
     */
    data class Style(
        val width: Int = 720,
        val height: Int = 540,
        val supersample: Int = 2,
        val wireframe: Boolean = true,
        val cullBackFaces: Boolean = true,
        val background: Int = 0xFF101318.toInt(),
        val caption: String? = null,
        val title: String? = null,
    ) {
        /** Bottom pixels the caption strip and the stats lines will occupy, so the model clears them. */
        internal val reservedBottom: Int get() = if (caption == "" && title == null) 0 else CAPTION_HEIGHT + 26
    }

    /**
     * A finished raster: ARGB pixels, no `java.awt` anywhere near it.
     *
     * That matters more than it sounds. Touching AWT inside the Field server's JVM on macOS
     * initialises the AppKit toolkit in a process that already owns its main thread for GLFW, and the
     * process dies on the spot — no exception, just an exit. So the rasterizer produces plain ints,
     * [renderPng] encodes them with [afield.util.PngEncoder], and the `BufferedImage` path is kept
     * for the headless gallery only.
     */
    class Raster(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        /** Triangles that reached the z-buffer, and those the back-face cull removed. */
        val drawn: Int,
        val culled: Int,
    )

    // ---------------------------------------------------------------- rasterizer

    /**
     * Rasterise [mesh]: perspective projection, a z-buffer, perspective-correct attributes, a
     * near-plane clip, a depth-tested wireframe and a **stand-in** shader. No AWT — see [Raster].
     *
     * Back faces are culled by default because the head culls them too — a face wound the wrong way
     * is missing here exactly as it would be missing in the headset, which is the single most common
     * "why is my mesh full of holes" cause and worth reproducing rather than papering over.
     */
    fun renderPixels(
        mesh: CapturedMesh,
        camera: Camera = Camera(),
        style: Style = Style(),
    ): Raster {
        val scale = max(1, style.supersample)
        val w = style.width * scale
        val h = style.height * scale
        val pixels = IntArray(w * h) { style.background }
        val depth = DoubleArray(w * h) { Double.MAX_VALUE }

        val bounds = mesh.bounds()
        val cx = (bounds[0] + bounds[3]) / 2
        val cy = (bounds[1] + bounds[4]) / 2
        val cz = (bounds[2] + bounds[5]) / 2
        val radius = max(
            1e-4,
            0.5 * sqrt(
                (bounds[3] - bounds[0]) * (bounds[3] - bounds[0]) +
                    (bounds[4] - bounds[1]) * (bounds[4] - bounds[1]) +
                    (bounds[5] - bounds[2]) * (bounds[5] - bounds[2]),
            ),
        )

        val yaw = Math.toRadians(camera.yawDegrees)
        val pitch = Math.toRadians(camera.pitchDegrees)
        val eyeDistance = radius * camera.distance
        val ex = cx + eyeDistance * cos(pitch) * sin(yaw)
        val ey = cy + eyeDistance * sin(pitch)
        val ez = cz + eyeDistance * cos(pitch) * cos(yaw)

        // Right-handed look-at: forward is -z in view space, as in Metal and in the head.
        val fx0 = cx - ex; val fy0 = cy - ey; val fz0 = cz - ez
        val fl = sqrt(fx0 * fx0 + fy0 * fy0 + fz0 * fz0)
        val fx = fx0 / fl; val fy = fy0 / fl; val fz = fz0 / fl
        // right = forward x worldUp
        var rx = fy * 0.0 - fz * 1.0
        var ry = fz * 0.0 - fx * 0.0
        var rz = fx * 1.0 - fy * 0.0
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        if (rl < 1e-9) { rx = 1.0; ry = 0.0; rz = 0.0 } else { rx /= rl; ry /= rl; rz /= rl }
        val ux = ry * fz - rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy - ry * fx

        // The eye stands `distance * radius` away, so a near plane at a twentieth of the radius is
        // always well in front of the mesh and keeps the depth buffer's precision where the mesh is.
        val near = max(1e-5, radius * 0.05)
        val focal = 1.0 / tan(Math.toRadians(camera.fieldOfViewDegrees) / 2)
        // The caption strip and the stats lines sit over the bottom of the frame, so the model is
        // framed in the band above them rather than in the whole image — otherwise a mesh that fills
        // its frame (a cube does) gets its near corner clipped by the text.
        val contentH = (h - style.reservedBottom * scale).coerceAtLeast(h / 3)
        val aspect = w.toDouble() / contentH

        // Key light over the viewer's left shoulder, plus a cool fill from below-right, both in view
        // space so the shading reads the same from every camera angle.
        val keyX = -0.45; val keyY = 0.68; val keyZ = 0.58
        val fillX = 0.62; val fillY = -0.35; val fillZ = 0.70

        fun toView(x: Double, y: Double, z: Double): DoubleArray {
            val dx = x - ex; val dy = y - ey; val dz = z - ez
            return doubleArrayOf(
                dx * rx + dy * ry + dz * rz,
                dx * ux + dy * uy + dz * uz,
                dx * fx + dy * fy + dz * fz, // +ve in front of the eye; we negate at projection
            )
        }

        // Each vertex becomes view position, view normal, and colour; the raster stage only ever
        // deals in view space, which makes the near-plane clip a single comparison.
        val vx = DoubleArray(mesh.vertexCount)
        val vy = DoubleArray(mesh.vertexCount)
        val vz = DoubleArray(mesh.vertexCount)
        val nvx = DoubleArray(mesh.vertexCount)
        val nvy = DoubleArray(mesh.vertexCount)
        val nvz = DoubleArray(mesh.vertexCount)
        for (i in 0 until mesh.vertexCount) {
            val p = toView(mesh.positionX(i).toDouble(), mesh.positionY(i).toDouble(), mesh.positionZ(i).toDouble())
            vx[i] = p[0]; vy[i] = p[1]; vz[i] = p[2]
            val nx = mesh.normalX(i).toDouble(); val ny = mesh.normalY(i).toDouble(); val nz = mesh.normalZ(i).toDouble()
            nvx[i] = nx * rx + ny * ry + nz * rz
            nvy[i] = nx * ux + ny * uy + nz * uz
            // Negated so the conventional "+z points back at the viewer" holds for a front face —
            // `forward` runs away from the eye, and lighting is easier to reason about the other way.
            nvz[i] = -(nx * fx + ny * fy + nz * fz)
        }

        class Corner(
            val x: Double, val y: Double, val z: Double,
            val nx: Double, val ny: Double, val nz: Double,
            val r: Double, val g: Double, val b: Double,
        )

        fun corner(i: Int) = Corner(
            vx[i], vy[i], vz[i], nvx[i], nvy[i], nvz[i],
            mesh.red(i).toDouble(), mesh.green(i).toDouble(), mesh.blue(i).toDouble(),
        )

        fun lerp(a: Corner, b: Corner, t: Double) = Corner(
            a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
            a.nx + (b.nx - a.nx) * t, a.ny + (b.ny - a.ny) * t, a.nz + (b.nz - a.nz) * t,
            a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t,
        )

        fun project(c: Corner): DoubleArray {
            val invZ = 1.0 / c.z
            val sx = (c.x * focal * invZ / aspect * 0.5 + 0.5) * w
            val sy = (0.5 - c.y * focal * invZ * 0.5) * contentH
            return doubleArrayOf(sx, sy, c.z)
        }

        fun shade(nx: Double, ny: Double, nz: Double, r: Double, g: Double, b: Double, facing: Double): IntArray {
            var lx = nx; var ly = ny; var lz = nz
            val len = sqrt(lx * lx + ly * ly + lz * lz)
            if (len < 1e-6) {
                // The builder left this normal unset; fall back to the face normal the caller passed
                // in as `facing` so the surface still reads as a surface.
                lx = 0.0; ly = 0.0; lz = 1.0
            } else {
                lx /= len; ly /= len; lz /= len
            }
            // Vertex normals point outward; the camera looks down -z in view space, so a front face
            // has nz > 0. Flip an inward normal rather than dropping the face to black.
            if (lz < 0 && facing > 0) { lx = -lx; ly = -ly; lz = -lz }
            val key = max(0.0, lx * keyX + ly * keyY + lz * keyZ)
            val fill = max(0.0, lx * fillX + ly * fillY + lz * fillZ)
            val rim = Math.pow(1.0 - min(1.0, abs(lz)), 3.0)
            val lit = 0.11 + 0.92 * key + 0.22 * fill
            return intArrayOf(
                channel(r * lit + rim * 0.20),
                channel(g * lit + rim * 0.24),
                channel(b * lit + rim * 0.32),
            )
        }

        var drawn = 0
        var culled = 0
        val nearZ = near

        fun rasterize(a: Corner, b: Corner, c: Corner) {
            val pa = project(a); val pb = project(b); val pc = project(c)
            val area = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0])
            if (abs(area) < 1e-12) return
            // Screen y runs downward, so counter-clockwise in world space comes out clockwise here:
            // a front face has a *negative* signed area.
            if (style.cullBackFaces && area > 0) { culled++; return }
            drawn++
            val facing = if (area < 0) 1.0 else -1.0

            val minX = max(0, floor(minOf(pa[0], pb[0], pc[0])).toInt())
            val maxX = min(w - 1, ceil(maxOf(pa[0], pb[0], pc[0])).toInt())
            val minY = max(0, floor(minOf(pa[1], pb[1], pc[1])).toInt())
            val maxY = min(h - 1, ceil(maxOf(pa[1], pb[1], pc[1])).toInt())
            if (minX > maxX || minY > maxY) return

            val invZa = 1.0 / a.z; val invZb = 1.0 / b.z; val invZc = 1.0 / c.z
            for (py in minY..maxY) {
                val sy = py + 0.5
                for (px in minX..maxX) {
                    val sx = px + 0.5
                    var w0 = (pb[0] - pa[0]) * (sy - pa[1]) - (pb[1] - pa[1]) * (sx - pa[0])
                    var w1 = (pc[0] - pb[0]) * (sy - pb[1]) - (pc[1] - pb[1]) * (sx - pb[0])
                    var w2 = (pa[0] - pc[0]) * (sy - pc[1]) - (pa[1] - pc[1]) * (sx - pc[0])
                    if (area > 0) { w0 = -w0; w1 = -w1; w2 = -w2 }
                    if (w0 > 0 || w1 > 0 || w2 > 0) continue
                    val total = w0 + w1 + w2
                    if (abs(total) < 1e-15) continue
                    // w1 opposes a, w2 opposes b, w0 opposes c.
                    val la = w1 / total; val lb = w2 / total; val lc = w0 / total
                    val invZ = la * invZa + lb * invZb + lc * invZc
                    if (invZ <= 0) continue
                    val z = 1.0 / invZ
                    val o = py * w + px
                    if (z >= depth[o]) continue
                    // Perspective-correct: interpolate attribute/z, then divide by the interpolated 1/z.
                    val pa2 = la * invZa; val pb2 = lb * invZb; val pc2 = lc * invZc
                    val nx = (a.nx * pa2 + b.nx * pb2 + c.nx * pc2) / invZ
                    val ny = (a.ny * pa2 + b.ny * pb2 + c.ny * pc2) / invZ
                    val nz = (a.nz * pa2 + b.nz * pb2 + c.nz * pc2) / invZ
                    val r = (a.r * pa2 + b.r * pb2 + c.r * pc2) / invZ
                    val g = (a.g * pa2 + b.g * pb2 + c.g * pc2) / invZ
                    val bl = (a.b * pa2 + b.b * pb2 + c.b * pc2) / invZ
                    val rgb = shade(nx, ny, nz, r, g, bl, facing)
                    depth[o] = z
                    pixels[o] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
                }
            }
        }

        /** Clip a triangle against the near plane, then rasterise the 1 or 2 pieces that survive. */
        fun clipAndRasterize(a: Corner, b: Corner, c: Corner) {
            val polygon = mutableListOf(a, b, c)
            val out = mutableListOf<Corner>()
            for (i in polygon.indices) {
                val cur = polygon[i]
                val next = polygon[(i + 1) % polygon.size]
                val curIn = cur.z >= nearZ
                val nextIn = next.z >= nearZ
                if (curIn) out += cur
                if (curIn != nextIn) {
                    val t = (nearZ - cur.z) / (next.z - cur.z)
                    out += lerp(cur, next, t)
                }
            }
            if (out.size < 3) return
            for (i in 1 until out.size - 1) rasterize(out[0], out[i], out[i + 1])
        }

        for (t in 0 until mesh.triangleCount) {
            val ia = mesh.indices[t * 3]; val ib = mesh.indices[t * 3 + 1]; val ic = mesh.indices[t * 3 + 2]
            if (ia !in 0 until mesh.vertexCount || ib !in 0 until mesh.vertexCount || ic !in 0 until mesh.vertexCount) {
                continue
            }
            clipAndRasterize(corner(ia), corner(ib), corner(ic))
        }

        if (style.wireframe) {
            drawWireframe(mesh, pixels, depth, w, h, contentH, ::toView, focal, aspect, nearZ, style.cullBackFaces)
        }

        return Raster(downsample(pixels, w, h, style.width, style.height, scale), style.width, style.height, drawn, culled)
    }

    /**
     * [renderPixels], encoded straight to PNG bytes with [afield.util.PngEncoder].
     *
     * AWT-free, so this is the one that is safe to call from inside a Field box. The title, stats and
     * caption are *not* burnt in — [renderFigureHtml] carries them in markup instead.
     */
    fun renderPng(
        mesh: CapturedMesh,
        camera: Camera = Camera(),
        style: Style = Style(),
    ): ByteArray {
        val raster = renderPixels(mesh, camera, style)
        return afield.util.PngEncoder.encode(raster.pixels, raster.width, raster.height)
    }

    /**
     * A complete `<figure>` — the mesh as an inline PNG, its measurements, and the honesty note as a
     * caption — for `display(…)` in a box output.
     *
     * AWT-free by construction, which is why the note is markup rather than pixels here.
     */
    fun renderFigureHtml(
        mesh: CapturedMesh,
        camera: Camera = Camera(),
        style: Style = Style(),
        title: String? = style.title,
    ): String {
        val raster = renderPixels(mesh, camera, style)
        val png = afield.util.PngEncoder.encode(raster.pixels, raster.width, raster.height)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        val bounds = mesh.bounds()
        val winding = mesh.windingAgreement()
        val stats = "%d verts · %d tris · %d drawn · %d back-facing · extent %.3f × %.3f × %.3f m".format(
            mesh.vertexCount, mesh.triangleCount, raster.drawn, raster.culled,
            bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2],
        )
        val windingNote = if (winding < 0.999) {
            "<div style='color:#e08a50'>winding: only ${"%.0f".format(winding * 100)}% of triangles " +
                "match their own normals — this mesh renders inside-out</div>"
        } else {
            ""
        }
        return buildString {
            append("<figure style='margin:0'>")
            title?.let { append("<div style='color:#e6ecf4;font-weight:600;padding-bottom:4px'>${escape(it)}</div>") }
            append("<img src='data:image/png;base64,$b64' style='max-width:900px;height:auto;border-radius:6px;display:block'/>")
            append("<figcaption style='color:#93a4ba;font-size:12px;padding-top:6px;line-height:1.5'>")
            append("<div>${escape(stats)}</div>")
            append(windingNote)
            append("<div>${escape(style.caption ?: DEFAULT_CAPTION)}</div>")
            append("</figcaption></figure>")
        }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * [renderPixels] as an AWT `BufferedImage`, with the title, stats and caption drawn on.
     *
     * **Headless callers only.** Touching AWT from inside the Field server's JVM on macOS
     * initialises AppKit in a process that already owns its main thread for GLFW and takes the whole
     * process down without an exception. Boxes and box commands must use [renderPng] or
     * [renderFigureHtml]; this exists for the docs gallery, which runs in its own JVM under
     * `java.awt.headless=true`.
     */
    fun render(
        mesh: CapturedMesh,
        camera: Camera = Camera(),
        style: Style = Style(),
    ): java.awt.image.BufferedImage {
        val raster = renderPixels(mesh, camera, style)
        val image = java.awt.image.BufferedImage(raster.width, raster.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, raster.width, raster.height, raster.pixels, 0, raster.width)
        annotate(image, mesh, style, camera, raster.drawn, raster.culled)
        return image
    }

    /** A contact sheet of the same mesh from [cameras], side by side with a shared caption. */
    fun renderContactSheet(
        mesh: CapturedMesh,
        cameras: List<Camera> = Camera.turntable(4),
        style: Style = Style(),
    ): java.awt.image.BufferedImage {
        require(cameras.isNotEmpty()) { "a contact sheet needs at least one camera" }
        val cellStyle = style.copy(caption = "", title = null)
        val cells = cameras.map { render(mesh, it, cellStyle) }
        val columns = min(cameras.size, 2)
        val rows = (cameras.size + columns - 1) / columns
        val gap = 8
        val sheetW = columns * style.width + (columns + 1) * gap
        val cellsH = rows * style.height + (rows + 1) * gap
        val stripH = if (style.caption == "") 0 else CAPTION_HEIGHT
        val sheet = java.awt.image.BufferedImage(sheetW, cellsH + stripH, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color(0x0A0C10)
        g.fillRect(0, 0, sheet.width, sheet.height)
        cells.forEachIndexed { i, cell ->
            val col = i % columns
            val row = i / columns
            g.drawImage(cell, gap + col * (style.width + gap), gap + row * (style.height + gap), null)
            g.color = java.awt.Color(0x7A8AA0)
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12)
            g.drawString(
                "yaw ${cameras[i].yawDegrees.toInt()}°  pitch ${cameras[i].pitchDegrees.toInt()}°",
                gap + col * (style.width + gap) + 8,
                gap + row * (style.height + gap) + 18,
            )
        }
        if (stripH > 0) {
            paintCaption(g, 0, cellsH, sheetW, stripH, style.title, style.caption ?: DEFAULT_CAPTION)
        }
        g.dispose()
        return sheet
    }

    private const val CAPTION_HEIGHT = 46

    /** The honesty note. Burnt into the image so it cannot be separated from the picture. */
    const val DEFAULT_CAPTION: String =
        "Geometry preview — the real Kotlin/JS builder, a stand-in shader. Your MSL is not simulated."

    private fun channel(value: Double): Int = (255.0 * min(1.0, max(0.0, value))).toInt()

    private fun downsample(pixels: IntArray, w: Int, h: Int, outW: Int, outH: Int, scale: Int): IntArray {
        if (scale == 1) return pixels
        val out = IntArray(outW * outH)
        val n = scale * scale
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until scale) {
                    val row = (y * scale + dy) * w
                    for (dx in 0 until scale) {
                        val p = pixels[row + x * scale + dx]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                    }
                }
                out[y * outW + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    private fun drawWireframe(
        mesh: CapturedMesh,
        pixels: IntArray,
        depth: DoubleArray,
        w: Int,
        h: Int,
        contentH: Int,
        toView: (Double, Double, Double) -> DoubleArray,
        focal: Double,
        aspect: Double,
        nearZ: Double,
        cullBackFaces: Boolean,
    ) {
        // The wireframe is what turns "a smooth blob" into "a mesh you can count the quads in", so it
        // is on by default. Drawn with the same depth buffer plus a small bias, so hidden edges stay
        // hidden — a wireframe that shows through is unreadable on anything but a cube.
        val seen = HashSet<Long>(mesh.indices.size)
        fun screen(i: Int): DoubleArray? {
            val p = toView(mesh.positionX(i).toDouble(), mesh.positionY(i).toDouble(), mesh.positionZ(i).toDouble())
            if (p[2] < nearZ) return null
            val invZ = 1.0 / p[2]
            return doubleArrayOf(
                (p[0] * focal * invZ / aspect * 0.5 + 0.5) * w,
                (0.5 - p[1] * focal * invZ * 0.5) * contentH,
                p[2],
            )
        }
        for (t in 0 until mesh.triangleCount) {
            val ia = mesh.indices[t * 3]; val ib = mesh.indices[t * 3 + 1]; val ic = mesh.indices[t * 3 + 2]
            if (ia !in 0 until mesh.vertexCount || ib !in 0 until mesh.vertexCount || ic !in 0 until mesh.vertexCount) {
                continue
            }
            val sa = screen(ia) ?: continue
            val sb = screen(ib) ?: continue
            val sc = screen(ic) ?: continue
            if (cullBackFaces) {
                val area = (sb[0] - sa[0]) * (sc[1] - sa[1]) - (sb[1] - sa[1]) * (sc[0] - sa[0])
                if (area > 0) continue
            }
            fun edge(p: DoubleArray, q: DoubleArray, i: Int, j: Int) {
                val key = if (i < j) i.toLong() * 1_000_003L + j else j.toLong() * 1_000_003L + i
                if (!seen.add(key)) return
                drawDepthLine(pixels, depth, w, h, p, q)
            }
            edge(sa, sb, ia, ib)
            edge(sb, sc, ib, ic)
            edge(sc, sa, ic, ia)
        }
    }

    private fun drawDepthLine(
        pixels: IntArray,
        depth: DoubleArray,
        w: Int,
        h: Int,
        a: DoubleArray,
        b: DoubleArray,
    ) {
        val steps = max(1, ceil(max(abs(b[0] - a[0]), abs(b[1] - a[1]))).toInt())
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val x = (a[0] + (b[0] - a[0]) * t).toInt()
            val y = (a[1] + (b[1] - a[1]) * t).toInt()
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val z = a[2] + (b[2] - a[2]) * t
            val o = y * w + x
            if (z > depth[o] * 1.004 + 1e-6) continue
            val p = pixels[o]
            // Darken toward the edge colour rather than replacing it, so the wireframe reads as a
            // crease in the surface instead of a decal floating on top.
            val r = ((p shr 16) and 0xFF) * 55 / 100 + 26
            val g = ((p shr 8) and 0xFF) * 55 / 100 + 28
            val bl = (p and 0xFF) * 55 / 100 + 32
            pixels[o] = (0xFF shl 24) or (min(255, r) shl 16) or (min(255, g) shl 8) or min(255, bl)
        }
    }

    private fun annotate(
        image: java.awt.image.BufferedImage,
        mesh: CapturedMesh,
        style: Style,
        camera: Camera,
        drawn: Int,
        culled: Int,
    ) {
        if (style.caption == "" && style.title == null) return
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        style.title?.let {
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 15)
            g.color = java.awt.Color(0xE6, 0xEC, 0xF4)
            g.drawString(it, 14, 24)
        }
        g.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
        g.color = java.awt.Color(0x8A, 0x9A, 0xB0)
        val bounds = mesh.bounds()
        val winding = mesh.windingAgreement()
        g.drawString(
            "%d verts  %d tris  %d drawn  %d back-facing".format(
                mesh.vertexCount, mesh.triangleCount, drawn, culled,
            ),
            14, image.height - 62,
        )
        // The winding number goes on the picture because it is the one fact the picture cannot show
        // you: a mesh wound inside-out still renders as a plausible solid.
        if (winding < 0.999) g.color = java.awt.Color(0xE0, 0x8A, 0x50)
        g.drawString(
            "extent %.3f x %.3f x %.3f m   yaw %.0f°  pitch %.0f°   winding %.0f%% agrees with normals".format(
                bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2],
                camera.yawDegrees, camera.pitchDegrees, winding * 100,
            ),
            14, image.height - 48,
        )
        g.color = java.awt.Color(0x8A, 0x9A, 0xB0)
        if (style.caption != "") {
            paintCaption(g, 0, image.height - CAPTION_HEIGHT + 12, image.width, CAPTION_HEIGHT - 12, null, style.caption ?: DEFAULT_CAPTION)
        }
        g.dispose()
    }

    private fun paintCaption(
        g: java.awt.Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        title: String?,
        caption: String,
    ) {
        g.color = java.awt.Color(0x08, 0x0A, 0x0E)
        g.fillRect(x, y, width, height)
        g.color = java.awt.Color(0x1E, 0x24, 0x2E)
        g.drawLine(x, y, x + width, y)
        var textY = y + height / 2 + 4
        title?.let {
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 14)
            g.color = java.awt.Color(0xE6, 0xEC, 0xF4)
            g.drawString(it, x + 14, y + 20)
            textY = y + 38
        }
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12)
        g.color = java.awt.Color(0x93, 0xA4, 0xBA)
        g.drawString(caption, x + 14, textY)
    }
}
