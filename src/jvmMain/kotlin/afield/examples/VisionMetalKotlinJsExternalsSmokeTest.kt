package afield.examples

import afield.graphics.VISION_METAL_VERTEX_FLOATS
import afield.graphics.VisionMetalGeometryOrigin
import afield.graphics.VisionMetalKotlinJs
import afield.graphics.VisionMetalProgramPayload
import afield.graphics.VisionMetalScriptPart
import afield.graphics.VisionMetalShaderPart
import afield.graphics.visionMetalWireJson
import afield.remotejs.KotlinToJsCompiler
import java.io.File
import java.nio.file.Files
import afield.util.NodeTool

/*
FIELD_TEST
id: visionmetal.kotlinjs-externals
runner: jvm-main
entrypoint: afield.examples.VisionMetalKotlinJsExternalsSmokeTest
tags: [smoke, visionos, kotlinjs]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "VisionMetalKotlinJsExternalsSmokeTest ok"
*/

/**
 * Compiles the typed surface and, when Node is present, links it against a faithful host shim.
 *
 * The geometry assertions matter more than the compile does: the builder writes the packed layout the
 * Metal vertex buffer expects, so this checks the real float count, winding-independent index range,
 * `position.w`/`normal.w`, and the actual arithmetic of `cube`/`plane`/`sphere`/`tube` — not just that
 * the names bind.
 */
object VisionMetalKotlinJsExternalsSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val userSource = """
            fun main() {
                onStart {
                    geometry {
                        color(1.0, 0.35, 0.15)
                        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
                    }
                    uniform("amount", 0.025)
                    uniform("tint", 0.1, 0.8, 1.0, 0.35)
                    onFrame { frame ->
                        uniform("phase", frame.time * 3.0 + frame.deltaTime)
                        log("frame " + frame.predictedDisplayTime)
                    }
                    onStop { log("stopped") }
                }
            }
        """.trimIndent()

        val result = KotlinToJsCompiler.compile(
            VisionMetalKotlinJs.appendTo(userSource),
            moduleName = "vision_metal_externals",
        )
        require(result.success && result.js != null) {
            "Vision Metal Kotlin/JS surface did not compile:\n${result.rawLog.takeLast(1600)}"
        }
        require(result.diagnostics.none { it.severity == "error" }) {
            "Unexpected diagnostics: ${result.diagnostics}"
        }

        verifyWirePartsAreIndependent()
        verifyBuilderShapesCompile()

        val js = result.js
        require(js.contains("amount") && js.contains("phase")) {
            "Compiled module does not contain the expected uniform writes"
        }

        val node = NodeTool.resolveNode()
        if (node != null) {
            runNode(node, hostShim + "\n" + js + "\n" + hostProbe, "vision-metal-host-ok")
            val shapesJs = compile(shapeSource, "vision_metal_shapes")
            runNode(node, hostShim + "\n" + shapesJs + "\n" + shapeProbe, "vision-metal-shapes-ok")
        } else {
            println("skipping host-link execution: ${NodeTool.unavailableMessage()}; compile proof passed")
        }

        println("VisionMetalKotlinJsExternalsSmokeTest ok")
    }

    /**
     * Wire v2's whole point: a shader reload is a payload carrying the shader and nothing else, so
     * the head leaves the resident script, uniforms and geometry alone.
     */
    private fun verifyWirePartsAreIndependent() {
        val shaderOnly = visionMetalWireJson.encodeToString(
            VisionMetalProgramPayload(
                uid = "smoke",
                revision = 7,
                shader = VisionMetalShaderPart(revision = 7, source = "// msl"),
            ),
        )
        require("\"channel\":\"__visionmetal__\"" in shaderOnly) { "wire payload lost its channel: $shaderOnly" }
        require("\"version\":2" in shaderOnly) { "Vision Metal wire version regressed: $shaderOnly" }
        require("\"shader\":{" in shaderOnly) { "shader part missing from a shader reload: $shaderOnly" }
        for (absent in listOf("script", "uniforms", "geometry")) {
            require("\"$absent\":null" in shaderOnly) {
                "a shader-only reload must leave '$absent' unchanged (null), got: $shaderOnly"
            }
        }

        // Execute (as opposed to Launch) still has to reach the head as an explicitly inert script.
        val executePayload = visionMetalWireJson.encodeToString(
            VisionMetalProgramPayload(
                uid = "smoke",
                revision = 8,
                script = VisionMetalScriptPart(revision = 8, source = "", launchable = false),
            ),
        )
        require("\"launchable\":false" in executePayload) {
            "Vision Metal wire payload lost Execute/Launch intent: $executePayload"
        }

        // The Swift enum decodes these exact lowercase strings.
        val origins = VisionMetalGeometryOrigin.entries.map {
            visionMetalWireJson.encodeToString(it).trim('"')
        }
        require(origins.containsAll(listOf("wire", "builtin", "script"))) {
            "geometry origins must serialise as the lowercase names the Swift enum decodes, got $origins"
        }
    }

    /** The completion mirror has to stay compilable Kotlin, since the sidecar parses it as source. */
    private fun verifyBuilderShapesCompile() {
        require(VisionMetalKotlinJs.completionDeclarations.contains("class MeshBuilder")) {
            "the completion mirror lost the geometry builder"
        }
        require(!VisionMetalKotlinJs.completionDeclarations.contains("external")) {
            "the completion mirror must stay free of Kotlin/JS-only syntax"
        }
        require(!VisionMetalKotlinJs.completionDeclarations.contains("@kotlin.js.JsName")) {
            "the completion mirror must stay free of @JsName"
        }
    }

    private val shapeSource = """
        fun main() {
            onStart {
                geometry { plane(1.0, 2.0, cols = 2, rows = 3) }
                geometry { sphere(0.5, segments = 6, rings = 4) }
                geometry { tube(listOf(p(0.0, 0.0, 0.0), p(0.0, 1.0, 0.0), p(0.0, 2.0, 0.0)), 0.1, sides = 4) }
                geometry { }
            }
        }
    """.trimIndent()

    private fun compile(source: String, module: String): String {
        val result = KotlinToJsCompiler.compile(
            VisionMetalKotlinJs.appendTo(source),
            moduleName = module,
        )
        require(result.success && result.js != null) {
            "$module did not compile:\n${result.rawLog.takeLast(1600)}"
        }
        return result.js
    }

    private fun runNode(node: String, source: String, expected: String) {
        val script = Files.createTempFile("field-vision-metal-kotlinjs", ".js").toFile()
        try {
            script.writeText(source)
            val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            require(process.exitValue() == 0) { "Host-link run failed:\n$output" }
            require(output.contains(expected)) { "Host probe did not pass:\n$output" }
            println(output)
        } finally {
            script.delete()
        }
    }

    /**
     * Mirrors `LiveMetalJavaScript.swift`: `__fieldSetGeometry` coerces to typed arrays before the
     * bridge sees them, so the probe can assert on the exact buffers Metal would receive.
     */
    private val hostShim = """
        const __visionMetalWrites = [];
        const __visionMetalMeshes = [];
        let __visionMetalFrame = null;
        let __visionMetalStop = null;
        const VERTEX_FLOATS = $VISION_METAL_VERTEX_FLOATS;
        function uniform(name, x, y = 0, z = 0, w = 0) {
            __visionMetalWrites.push([String(name), Number(x), Number(y), Number(z), Number(w)]);
        }
        function __fieldSetGeometry(vertices, indices) {
            if (!(vertices instanceof Float32Array)) {
                throw new Error("geometry vertices reached the bridge as " + Object.prototype.toString.call(vertices));
            }
            if (!(indices instanceof Int32Array)) {
                throw new Error("geometry indices reached the bridge as " + Object.prototype.toString.call(indices));
            }
            __visionMetalMeshes.push({ vertices, indices });
        }
        function __fieldOnStart(callback) { callback(); }
        function __fieldOnStop(callback) { __visionMetalStop = callback; }
        function __fieldOnFrame(callback) {
            if (typeof callback !== "function") throw new TypeError("onFrame expects a function");
            __visionMetalFrame = callback;
        }
        function __fieldKeepAlive() {}
        function print(value) {
            console.log("vision-metal-log:" + value);
        }
        function vertexOf(mesh, i) {
            const o = i * VERTEX_FLOATS;
            return {
                position: [mesh.vertices[o], mesh.vertices[o+1], mesh.vertices[o+2], mesh.vertices[o+3]],
                normal:   [mesh.vertices[o+4], mesh.vertices[o+5], mesh.vertices[o+6], mesh.vertices[o+7]],
                color:    [mesh.vertices[o+8], mesh.vertices[o+9], mesh.vertices[o+10], mesh.vertices[o+11]],
                uv:       [mesh.vertices[o+12], mesh.vertices[o+13], mesh.vertices[o+14], mesh.vertices[o+15]],
            };
        }
        function checkMesh(mesh, label) {
            if (mesh.vertices.length % VERTEX_FLOATS !== 0) {
                throw new Error(label + ": " + mesh.vertices.length + " floats is not a whole number of vertices");
            }
            const count = mesh.vertices.length / VERTEX_FLOATS;
            if (mesh.indices.length % 3 !== 0) throw new Error(label + ": indices are not whole triangles");
            for (const index of mesh.indices) {
                if (index < 0 || index >= count) throw new Error(label + ": index " + index + " outside " + count + " vertices");
            }
            for (let i = 0; i < count; i++) {
                const v = vertexOf(mesh, i);
                if (v.position[3] !== 1) throw new Error(label + ": position.w must be 1, got " + v.position[3]);
                if (v.normal[3] !== 0) throw new Error(label + ": normal.w must be 0, got " + v.normal[3]);
            }
            return count;
        }
    """.trimIndent()

    private val hostProbe = """
        if (__visionMetalMeshes.length !== 1) {
            throw new Error("expected exactly one geometry upload, got " + __visionMetalMeshes.length);
        }
        const cube = __visionMetalMeshes[0];
        const cubeCount = checkMesh(cube, "cube");
        // Six faces, four own vertices each so facet normals survive; two triangles per face.
        if (cubeCount !== 24) throw new Error("cube should have 24 vertices, got " + cubeCount);
        if (cube.indices.length !== 36) throw new Error("cube should have 36 indices, got " + cube.indices.length);

        // cube(0.18, cx = 0.45, cz = -1.2) must land exactly on that centre and half-extent.
        let lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
        const normals = new Set(), colors = new Set();
        for (let i = 0; i < cubeCount; i++) {
            const v = vertexOf(cube, i);
            for (let a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], v.position[a]);
                hi[a] = Math.max(hi[a], v.position[a]);
            }
            normals.add(v.normal.slice(0, 3).map(n => n.toFixed(3)).join(","));
            colors.add(v.color.map(c => c.toFixed(3)).join(","));
        }
        const near = (a, b) => Math.abs(a - b) < 1e-5;
        if (!near(lo[0], 0.27) || !near(hi[0], 0.63)) throw new Error("cube x extent " + lo[0] + ".." + hi[0]);
        if (!near(lo[1], -0.18) || !near(hi[1], 0.18)) throw new Error("cube y extent " + lo[1] + ".." + hi[1]);
        if (!near(lo[2], -1.38) || !near(hi[2], -1.02)) throw new Error("cube z extent " + lo[2] + ".." + hi[2]);
        // Six axis-aligned facet normals, and tintFaces gave each face its own colour.
        if (normals.size !== 6) throw new Error("expected 6 facet normals, got " + normals.size + ": " + [...normals]);
        if (colors.size !== 6) throw new Error("tintFaces should give 6 colours, got " + colors.size);

        if (typeof __visionMetalFrame !== "function") throw new Error("Kotlin did not install onFrame");
        __visionMetalFrame(2.0, 0.25, 7, 2.5);
        const phase = __visionMetalWrites.find(entry => entry[0] === "phase");
        if (!phase || Math.abs(phase[1] - 6.25) > 1e-9) {
            throw new Error("unexpected phase write: " + JSON.stringify(__visionMetalWrites));
        }
        const tint = __visionMetalWrites.find(entry => entry[0] === "tint");
        if (!tint || Math.abs(tint[4] - 0.35) > 1e-9) throw new Error("float4 uniform lost its w: " + JSON.stringify(tint));
        if (typeof __visionMetalStop !== "function") throw new Error("Kotlin did not install onStop");
        __visionMetalStop();
        console.log("vision-metal-host-ok:" + cubeCount + " vertices, " + (cube.indices.length / 3) + " triangles");
    """.trimIndent()

    private val shapeProbe = """
        if (__visionMetalMeshes.length !== 4) {
            throw new Error("expected four geometry uploads, got " + __visionMetalMeshes.length);
        }
        const [plane, sphere, tube, empty] = __visionMetalMeshes;

        // plane(cols=2, rows=3): a (cols+1) x (rows+1) vertex lattice, two triangles per cell.
        if (checkMesh(plane, "plane") !== 12) throw new Error("plane vertices: " + plane.vertices.length / VERTEX_FLOATS);
        if (plane.indices.length !== 36) throw new Error("plane indices: " + plane.indices.length);
        for (let i = 0; i < 12; i++) {
            const v = vertexOf(plane, i);
            if (Math.abs(v.normal[2] - 1) > 1e-6) throw new Error("plane normals should face +Z");
            if (Math.abs(v.position[0]) > 0.5 + 1e-6) throw new Error("plane width should be 1.0, got x=" + v.position[0]);
            if (Math.abs(v.position[1]) > 1.0 + 1e-6) throw new Error("plane height should be 2.0, got y=" + v.position[1]);
        }

        // sphere(segments=6, rings=4): (segments+1) x (rings+1) vertices; every one at |r| from centre.
        if (checkMesh(sphere, "sphere") !== 35) throw new Error("sphere vertices: " + sphere.vertices.length / VERTEX_FLOATS);
        if (sphere.indices.length !== 144) throw new Error("sphere indices: " + sphere.indices.length);
        for (let i = 0; i < 35; i++) {
            const v = vertexOf(sphere, i);
            const radius = Math.hypot(v.position[0], v.position[1], v.position[2]);
            if (Math.abs(radius - 0.5) > 1e-5) throw new Error("sphere radius " + radius);
            const nLen = Math.hypot(v.normal[0], v.normal[1], v.normal[2]);
            if (Math.abs(nLen - 1) > 1e-5) throw new Error("sphere normal length " + nLen);
        }

        // tube(3 points, sides=4): points x (sides+1) vertices, (points-1) x sides quads.
        if (checkMesh(tube, "tube") !== 15) throw new Error("tube vertices: " + tube.vertices.length / VERTEX_FLOATS);
        if (tube.indices.length !== 48) throw new Error("tube indices: " + tube.indices.length);
        for (let i = 0; i < 15; i++) {
            const v = vertexOf(tube, i);
            // The polyline runs up +Y, so every ring vertex sits 0.1 from the axis in the XZ plane.
            const offset = Math.hypot(v.position[0], v.position[2]);
            if (Math.abs(offset - 0.1) > 1e-5) throw new Error("tube radius " + offset + " at vertex " + i);
            const nLen = Math.hypot(v.normal[0], v.normal[1], v.normal[2]);
            if (Math.abs(nLen - 1) > 1e-5) throw new Error("tube normal length " + nLen);
        }

        // An empty builder is a legitimate way to draw nothing, and must still reach the bridge.
        if (empty.vertices.length !== 0 || empty.indices.length !== 0) {
            throw new Error("an empty geometry { } should upload empty arrays");
        }
        console.log("vision-metal-shapes-ok");
    """.trimIndent()
}
