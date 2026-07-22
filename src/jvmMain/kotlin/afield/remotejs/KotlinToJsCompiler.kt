package afield.remotejs

import afield.logger.lprintln
import java.io.File
import java.nio.file.Files

/**
 * SPIKE — Kotlin source -> JavaScript, for RemoteJS boxes.
 *
 * This mirrors what JetBrains' `kotlin-compiler-server` (KotlinToJSTranslator) does: a two-phase
 * K2 compile of a self-contained Kotlin snippet into a single, eval-able JS module that the existing
 * RemoteJS transport ships to the browser / visionOS "graphics head" over the WebSocket bus.
 *
 *   phase 1:  source.kt        --( -Xir-produce-klib-file )-->  <module>.klib
 *   phase 2:  <module>.klib     --( -Xir-produce-js, -Xinclude )-->  <module>.js   (module-kind=plain)
 *
 * The whole point: users write Kotlin (real types, completion, and — crucially — native
 * `operator fun plus` vector/matrix math, so the ASTA operator-rewrite hack is unnecessary for
 * Kotlin boxes) and it runs unchanged in the head via [executeRemoteJS] with `doASTA = false`.
 *
 * WHY A SUBPROCESS (and not in-process): the K2 REPL ([afield.KotlinInterface2]) already holds a
 * live IntelliJ `CoreApplicationEnvironment` singleton in this JVM; `K2JSCompiler` builds its own
 * application environment and the two clash. Spawning `java -cp <this app's classpath> K2JSCompiler`
 * isolates the JS compile completely and reuses the embeddable compiler that is *already* on our
 * runtime classpath (libs/kotlin-compiler-embeddable-2.4.255-SNAPSHOT.jar). Moving this in-process
 * (shared disposable, warm environment, incremental caches via JsIrCompilerWithIC) is the obvious
 * follow-up once the seam is proven — see dev_docs/kotlin_to_js_spike.md.
 */
object KotlinToJsCompiler {

    /** Compiler-fork version. The Kotlin/JS stdlib klib MUST match the compiler exactly (the fork
     *  hard-fails linking against a mismatched stdlib), which is why we vendor it next to the jars. */
    private const val FORK_VERSION = "2.4.255-SNAPSHOT"

    data class Diagnostic(val severity: String, val message: String, val line: Int, val column: Int)

    data class Result(
        val js: String?,
        val diagnostics: List<Diagnostic>,
        val success: Boolean,
        /** raw compiler stderr, kept for spike-time debugging */
        val rawLog: String,
    )

    /** The fork-matched Kotlin/JS stdlib klib. Override with -Dafield.kotlinjs.stdlibJs=<path>. */
    private val stdlibJsKlib: File by lazy {
        System.getProperty("afield.kotlinjs.stdlibJs")?.let { File(it) }?.takeIf { it.exists() }
            ?: locateVendoredKlib()
            ?: error(
                "Kotlin/JS stdlib klib not found. Expected libs/kotlin-stdlib-js-$FORK_VERSION.klib " +
                    "(git-lfs) or -Dafield.kotlinjs.stdlibJs=<path>."
            )
    }

    private fun locateVendoredKlib(): File? {
        val name = "kotlin-stdlib-js-$FORK_VERSION.klib"
        // Search a few plausible roots: CWD/libs, and up the tree from this class's location.
        val candidates = buildList {
            add(File("libs/$name"))
            add(File(System.getProperty("user.dir"), "libs/$name"))
        }
        return candidates.firstOrNull { it.exists() }
    }

    /**
     * Compile [kotlinSource] to a single plain-module JS string.
     *
     * The snippet is a normal Kotlin file: it may declare `external` values/functions to reach the
     * head's runtime (THREE, A-Frame, Field's browser JS runtime), and should contain a `fun main()` — the
     * plain module self-invokes `main` on eval.
     */
    fun compile(kotlinSource: String, moduleName: String = "field_box"): Result {
        val classpath = System.getProperty("java.class.path")
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val work = Files.createTempDirectory("field-ktjs").toFile()
        try {
            val srcDir = File(work, "src").apply { mkdirs() }
            val src = File(srcDir, "$moduleName.kt").apply { writeText(kotlinSource) }
            val klibDir = File(work, "klib").apply { mkdirs() }
            val jsDir = File(work, "js").apply { mkdirs() }
            val klib = File(klibDir, "$moduleName.klib")
            val js = File(jsDir, "$moduleName.js")

            // phase 1: source -> klib
            val log1 = run(
                javaBin, classpath,
                "-Xir-produce-klib-file",
                "-libraries", stdlibJsKlib.absolutePath,
                "-ir-output-dir", klibDir.absolutePath,
                "-ir-output-name", moduleName,
                src.absolutePath,
            )
            if (!klib.exists()) {
                return Result(null, parseDiagnostics(log1), false, log1)
            }

            // phase 2: klib -> js (plain module, self-invokes main, directly eval-able)
            val log2 = run(
                javaBin, classpath,
                "-Xir-produce-js",
                "-Xinclude=${klib.absolutePath}",
                "-libraries", stdlibJsKlib.absolutePath,
                "-ir-output-dir", jsDir.absolutePath,
                "-ir-output-name", moduleName,
                "-Xir-module-name=$moduleName",
                "-module-kind", "plain",
            )
            val log = log1 + log2
            if (!js.exists()) {
                return Result(null, parseDiagnostics(log), false, log)
            }
            return Result(js.readText(), parseDiagnostics(log), true, log)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun run(javaBin: String, classpath: String, vararg compilerArgs: String): String {
        val cmd = buildList {
            add(javaBin)
            add("-cp"); add(classpath)
            add("org.jetbrains.kotlin.cli.js.K2JSCompiler")
            addAll(compilerArgs)
        }
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        if (proc.exitValue() != 0) lprintln("debug,remotejs", "K2JSCompiler exit=${proc.exitValue()}\n$out")
        return out
    }

    // Compiler diagnostics look like: "path.kt:LINE:COL: error: message" (also warning:/info:).
    private val diagnosticLine =
        Regex("""^.*?\.kt:(\d+):(\d+):\s+(error|warning|info):\s+(.*)$""")

    private fun parseDiagnostics(log: String): List<Diagnostic> =
        log.lineSequence().mapNotNull { line ->
            diagnosticLine.matchEntire(line.trim())?.let { m ->
                Diagnostic(
                    severity = m.groupValues[3],
                    message = m.groupValues[4],
                    line = m.groupValues[1].toInt(),
                    column = m.groupValues[2].toInt(),
                )
            }
        }.toList()
}
