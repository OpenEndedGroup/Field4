package afield.examples

import afield.graphics.VisionMetalKotlinJs
import afield.graphics.VisionMetalProgramPayload
import afield.remotejs.KotlinToJsCompiler
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/*
FIELD_TEST
id: visionmetal.kotlinjs-externals
runner: jvm-main
entrypoint: afield.examples.VisionMetalKotlinJsExternalsSmokeTest
tags: [smoke, visionos, kotlinjs]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "VisionMetalKotlinJsExternalsSmokeTest ok"
*/

/** Compiles and, when Node is present, links the typed surface against a faithful host shim. */
object VisionMetalKotlinJsExternalsSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val userSource = """
            fun main() {
                onStart {
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
        val executePayload = Json.encodeToString(
            VisionMetalProgramPayload(
                uid = "smoke",
                revision = 1,
                shader = "",
                launchable = false,
            ),
        )
        require("\"launchable\":false" in executePayload) {
            "Vision Metal wire payload lost Execute/Launch intent: $executePayload"
        }

        val js = result.js
        require(js.contains("amount") && js.contains("phase")) {
            "Compiled module does not contain the expected uniform writes"
        }

        val node = which("node")
        if (node != null) {
            val script = Files.createTempFile("field-vision-metal-kotlinjs", ".js").toFile()
            try {
                script.writeText(hostShim + "\n" + js + "\n" + hostProbe)
                val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                require(process.exitValue() == 0) { "Host-link run failed:\n$output" }
                require(output.contains("vision-metal-host-ok")) { "Host probe did not pass:\n$output" }
                println(output)
            } finally {
                script.delete()
            }
        } else {
            println("node not on PATH; compile proof passed, host-link execution skipped")
        }

        println("VisionMetalKotlinJsExternalsSmokeTest ok")
    }

    private val hostShim = """
        const __visionMetalWrites = [];
        let __visionMetalFrame = null;
        let __visionMetalStop = null;
        function uniform(name, x, y = 0, z = 0, w = 0) {
            __visionMetalWrites.push([String(name), Number(x), Number(y), Number(z), Number(w)]);
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
    """.trimIndent()

    private val hostProbe = """
        if (typeof __visionMetalFrame !== "function") throw new Error("Kotlin did not install onFrame");
        __visionMetalFrame(2.0, 0.25, 7, 2.5);
        const phase = __visionMetalWrites.find(entry => entry[0] === "phase");
        if (!phase || Math.abs(phase[1] - 6.25) > 1e-9) {
            throw new Error("unexpected phase write: " + JSON.stringify(__visionMetalWrites));
        }
        if (typeof __visionMetalStop !== "function") throw new Error("Kotlin did not install onStop");
        __visionMetalStop();
        console.log("vision-metal-host-ok:" + JSON.stringify(__visionMetalWrites));
    """.trimIndent()

    private fun which(executable: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .map { File(it, executable) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
}
