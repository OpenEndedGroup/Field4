package afield.examples

import afield.lang.remotejs.KotlinJsHeadProfiles
import afield.lang.remotejs.KotlinJsSourceMap
import afield.lang.remotejs.KotlinToJsCompiler
import afield.media.image.util.NodeTool
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/*
FIELD_TEST
id: remotejs.kotlinjs-box-surface
runner: jvm-main
entrypoint: afield.examples.KotlinJsBoxSurfaceSmokeTest
tags: [smoke, remotejs, kotlinjs]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsBoxSurfaceSmokeTest ok"
*/

/**
 * The Kotlin→JS half of `dev_docs/REMOTEJS_BOX_PARITY.md`: a `// {{ remoteAR }}` box compiles
 * against `display`, `slider`, `checkbox` and `setTimeout` (bound to the head's field.js globals),
 * and an exception thrown inside the Kotlin stdlib maps, through the module's source map, to the
 * editor line of the box's own code rather than to the generated JavaScript line (an inline stdlib function marks the line that called
 * the user function it was expanded into). The compiled
 * module runs in Node with stand-ins for the head's globals and a real stack.
 */
object KotlinJsBoxSurfaceSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = """
            // {{ remoteAR }}
            display("main", "<b>hello</b>")
            slider("frequency", 1.0, 10.0, 0.1, 3.0) { v -> display("main", "f=" + v) }
            checkbox("square", true) { v -> println("square " + v) }
            setTimeout({ println("later") }, 10)
            fun mean(values: List<Double>): Double {
                return values.reduce { a, b -> a + b } / values.size
            }
            println(mean(emptyList()))
        """.trimIndent()
        val selection = KotlinJsHeadProfiles.resolve(source)
        val moduleName = "box_surface_remotear"
        val prepared = selection.profile.prepareSourceMapped(selection.source, stateKey = moduleName)
        val result = KotlinToJsCompiler.compile(prepared.text, moduleName = moduleName, wrapImplicitMain = false)
        require(result.success && result.js != null) { "box surface sample did not compile:\n${result.rawLog.takeLast(1800)}" }
        val mapText = requireNotNull(result.sourceMap) { "the compiler wrote no source map" }
        val map = KotlinJsSourceMap(mapText, "$moduleName.kt", prepared::userLineFor)

        val node = NodeTool.resolveNode() ?: run {
            println("skipping head execution: ${NodeTool.unavailableMessage()}")
            println("KotlinJsBoxSurfaceSmokeTest ok")
            return
        }
        val codeName = "box_surface|uid"
        val nodeSource = """
            const calls = [];
            globalThis.display = (channel, html) => calls.push(["display", channel, html]);
            globalThis.slider = (label, cb, o) => { calls.push(["slider", label, o.min, o.max, o.step, o.value, o.channel]); cb(4.5); return o.value; };
            globalThis.checkbox = (label, cb, o) => { calls.push(["checkbox", label, o.value, o.channel]); return o.value; };
            globalThis.print = (message, line) => calls.push(["print", String(message), line]);
            globalThis.__fieldKotlinStateRestore = (name, init) => init();
            let stack = "";
            try {
                require("vm").runInThisContext(${JSONObject.quote(result.js)}, {filename: ${JSONObject.quote(codeName)}});
            } catch (error) {
                stack = String(error.stack);
                calls.push(["error", error.message]);
            }
            setTimeout(() => console.log(JSON.stringify({calls, stack})), 50);
        """.trimIndent()
        val script = Files.createTempFile("field-kotlinjs-surface", ".js").toFile()
        val output = try {
            script.writeText(nodeSource)
            val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            process.inputStream.bufferedReader().readText().trim().also {
                require(finished && process.exitValue() == 0) { "node failed:\n$it" }
            }
        } finally {
            script.delete()
        }
        val report = JSONObject(output.lines().last { it.startsWith("{") })
        val calls = report.getJSONArray("calls").toString()
        println("calls: $calls")
        require("""["display","main","<b>hello<\/b>"]""" in calls || """["display","main","<b>hello</b>"]""" in calls) { "display missing: $calls" }
        require("""["slider","frequency",1,10,0.1,3,"controls"]""" in calls) { "slider spec wrong: $calls" }
        require("""["display","main","f=4.5"]""" in calls) { "slider callback did not reach display: $calls" }
        require("""["checkbox","square",true,"controls"]""" in calls) { "checkbox spec wrong: $calls" }
        require("""["print","later",""" in calls) { "setTimeout callback did not print: $calls" }
        require("""["error",""" in calls) { "mean(emptyList()) did not throw: $calls" }

        val stack = report.getString("stack")
        val line = map.editorLineForStack(stack, codeName)
        println("error stack maps to editor line $line")
        // reduce is inline: its throw is stdlib code expanded inside mean, so the innermost frame of
        // the box's own code is the call on line 9 (the generated line was 856).
        require(line == 9) { "runtime error mapped to line $line, expected 9 (the call of mean):\n$stack" }
        println("KotlinJsBoxSurfaceSmokeTest ok")
    }
}
