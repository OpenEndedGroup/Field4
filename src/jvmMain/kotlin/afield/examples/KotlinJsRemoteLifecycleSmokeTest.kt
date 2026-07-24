package afield.examples

import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/*
FIELD_TEST
id: remotejs.kotlinjs-lifecycle
runner: jvm-main
entrypoint: afield.examples.KotlinJsRemoteLifecycleSmokeTest
tags: [smoke, remotejs, kotlinjs, lifecycle]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsRemoteLifecycleSmokeTest ok"
*/

/**
 * Executes compiled Kotlin/JS against the browser head's REAL runners.js lifecycle.
 *
 * Guards the contract:
 * - Execute runs ordinary code but onStart/onFrame/onStop are inert.
 * - Launch runs onStart once, advances onFrame through the head service, and reports RUN-START.
 * - Relaunch cleans the previous generation before installing the next.
 * - Stop runs cleanup exactly once and prevents later frame callbacks.
 */
object KotlinJsRemoteLifecycleSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val node = which("node") ?: run {
            println("node not on PATH; lifecycle host execution skipped")
            println("KotlinJsRemoteLifecycleSmokeTest ok")
            return
        }
        val root = repoRoot()
        val runners = File(root, "src/commonMain/resources/extensions/remote_ar/field/runners.js")
        require(runners.isFile) { "missing browser lifecycle runtime: $runners" }

        val source = """
            @kotlin.js.JsName("record")
            external fun record(value: String)

            fun main() {
                record("body")
                onStart {
                    record("start")
                    onFrame { frame -> record("frame:" + frame.frameNumber) }
                    onStop { record("stop") }
                }
            }
        """.trimIndent()
        val selection = KotlinJsHeadProfiles.resolve(source)
        val prepared = selection.profile.prepareSource(selection.source)
        val result = KotlinToJsCompiler.compile(
            prepared,
            moduleName = "remote_lifecycle",
            wrapImplicitMain = false,
        )
        require(result.success && result.js != null) {
            "lifecycle sample did not compile:\n${result.rawLog.takeLast(1800)}"
        }

        val nodeSource = """
            const events = [];
            const messages = [];
            const window = globalThis;
            const _field = { send(_address, payload) { messages.push(payload.kind); } };
            function record(value) { events.push(String(value)); }

            ${runners.readText()}

            const compiled = ${JSONObject.quote(result.js)};
            const envelope = {
                codeName:"box_lifecycle|uid",
                lifecycleName:"box_lifecycle|uid::kotlinjs::music",
                returnTo:"return",
                launchable:false
            };

            function evaluate(launchable) {
                envelope.launchable = launchable;
                const lifecycle = __fieldBeginLifecycle(envelope, error => { throw error; });
                try {
                    eval(compiled);
                    __fieldSealLifecycle(lifecycle);
                    __fieldCommitLifecycle(lifecycle);
                } catch (error) {
                    __fieldAbortLifecycle(lifecycle);
                    throw error;
                }
            }

            // Execute: body runs, launch-only work is completely inert.
            evaluate(false);
            if (events.join(",") !== "body") throw new Error("execute leaked lifecycle work: " + events);
            if (Object.keys(__tasks).length !== 0) throw new Error("execute installed a task");

            // Launch: onStart once, then one callback per host frame.
            evaluate(true);
            if (__tasks[envelope.lifecycleName] == null || __tasks[envelope.codeName] != null) {
                throw new Error("lifecycle did not use the strand-specific task key");
            }
            __service();
            __service();
            if (events.join(",") !== "body,body,start,frame:0,frame:1") {
                throw new Error("launch/frame order wrong: " + events);
            }
            if (messages.join(",") !== "RUN-START") throw new Error("missing RUN-START: " + messages);

            // Relaunch: cleanup old generation before the new body/start.
            evaluate(true);
            if (events.slice(-3).join(",") !== "stop,body,start") {
                throw new Error("relaunch did not clean before start: " + events);
            }
            __service();
            if (events[events.length - 1] !== "frame:0") throw new Error("new generation did not restart frame count");

            // Stop is immediate/idempotent and no frame runs afterwards.
            if (!__fieldStopLifecycle(envelope.lifecycleName)) throw new Error("stop did not find lifecycle");
            const stopped = events.join(",");
            __fieldStopLifecycle(envelope.lifecycleName);
            __service();
            if (events.join(",") !== stopped) throw new Error("work continued after stop: " + events);
            if (events.filter(x => x === "stop").length !== 2) throw new Error("cleanup count wrong: " + events);
            if (messages.join(",") !== "RUN-START,RUN-STOP,RUN-START,RUN-STOP") {
                throw new Error("running-state sequence wrong: " + messages);
            }

            console.log("browser-lifecycle-ok " + events.join("|"));
        """.trimIndent()

        val script = Files.createTempFile("field-kotlinjs-lifecycle", ".js").toFile()
        try {
            script.writeText(nodeSource)
            val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().readText().trim()
            require(finished && process.exitValue() == 0 && output.contains("browser-lifecycle-ok")) {
                "browser lifecycle host test failed:\n$output"
            }
            println(output)
        } finally {
            script.delete()
        }

        println("KotlinJsRemoteLifecycleSmokeTest ok")
    }

    private fun repoRoot(): File {
        var at = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(at, "settings.gradle.kts").isFile) return at
            at = at.parentFile ?: return@repeat
        }
        error("could not locate Field repository root from ${System.getProperty("user.dir")}")
    }

    private fun which(executable: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .map { File(it, executable) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
}
