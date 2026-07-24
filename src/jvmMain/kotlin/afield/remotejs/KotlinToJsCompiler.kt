package afield.remotejs

import afield.logger.lprintln
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.Services
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
 *   phase 2:  <module>.klib     --( -Xir-produce-js, -Xinclude, -Xir-dce )-->  <module>.js
 *
 * The whole point: users write Kotlin (real types, completion, and — crucially — native
 * `operator fun plus` vector/matrix math, so the ASTA operator-rewrite hack is unnecessary for
 * Kotlin boxes) and it runs unchanged in the head via [executeRemoteJS] with `doASTA = false`.
 *
 * Output is a `plain` module (DCE'd: a trivial program is ~4.7 KB, not ~600 KB) that self-invokes
 * `main`, so the head's `global.eval(code)` runs it with no changes.
 *
 * Two execution modes ([Mode]):
 *  - IN_PROCESS: calls `K2JSCompiler().exec(...)` directly. Fast (no JVM launch). Risk: the K2 REPL
 *    ([afield.KotlinInterface2]) already holds a live IntelliJ application environment in this JVM;
 *    `K2JSCompiler` reuses the process-wide `getOrCreateApplicationEnvironment`, which normally
 *    coexists — but if it ever throws we transparently fall back to SUBPROCESS.
 *  - SUBPROCESS: spawns `java -cp <this app's classpath> K2JSCompiler`. Fully isolated; reuses the
 *    embeddable compiler already on our runtime classpath. Slower (cold JVM per compile).
 *
 * Follow-up (see dev_docs/kotlin_to_js_spike.md): incremental compiles via JsIrCompilerWithIC, and a
 * typed `external` façade over the head runtime (THREE / A-Frame / Field JS).
 */
object KotlinToJsCompiler {

    /** Compiler-fork version. The Kotlin/JS stdlib klib MUST match the compiler exactly (the fork
     *  hard-fails linking against a mismatched stdlib), which is why we vendor it next to the jars. */
    private const val FORK_VERSION = "2.4.255-SNAPSHOT"

    enum class Mode { IN_PROCESS, SUBPROCESS }

    /**
     * Output shape.
     *  - PLAIN_DCE: one self-contained, tree-shaken `plain` module (~5 KB for a trivial program).
     *    Eval-able as-is; DCE runs the whole-program reachability pass on every compile.
     *  - ESM_SHARED: per-module ES output — a tiny box module (~2 KB) that `import`s from a
     *    `kotlin-kotlin-stdlib.mjs` shipped to the head once. No per-compile DCE over the stdlib.
     */
    enum class Profile { PLAIN_DCE, ESM_SHARED }

    /** Default mode; override with -Dafield.kotlinjs.mode=subprocess. */
    private val defaultMode: Mode =
        if (System.getProperty("afield.kotlinjs.mode").equals("subprocess", ignoreCase = true))
            Mode.SUBPROCESS else Mode.IN_PROCESS

    data class Diagnostic(val severity: String, val message: String, val line: Int, val column: Int)

    data class Result(
        val js: String?,
        val diagnostics: List<Diagnostic>,
        val success: Boolean,
        /** which path actually produced this result */
        val mode: Mode,
        /** raw compiler log, kept for spike-time debugging */
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
        return listOf(File("libs/$name"), File(System.getProperty("user.dir"), "libs/$name"))
            .firstOrNull { it.exists() }
    }

    // Top-level lines that must stay at file scope: `import`/`package`/`@file:` (position-required)
    // and `external` (illegal as a local declaration). Everything else is legal inside `main` as a
    // local — data classes, functions, `operator fun`, statements (verified against K2/JS).
    private val HOIST_PREFIX = Regex("""^\s*(import\s|package\s|@file:|(?:public\s+|private\s+|internal\s+|expect\s+|actual\s+)*external\b)""")
    private val HAS_MAIN = Regex("""(?m)^\s*(?:(?:public|private|internal|suspend|expect|actual|external)\s+)*fun\s+main\s*\(""")

    /**
     * Let box authors skip the `fun main() { … }` wrapper. If [source] has no top-level `fun main(`,
     * hoist the file-scope-only lines (imports, package, `@file:` annotations, `external` declarations)
     * to the top and wrap everything else in a synthesized `fun main()`. Author-written data classes,
     * functions, `operator fun`s and statements all become locals inside `main`, which K2/JS accepts.
     *
     * If a `fun main(` is already present, the source is returned untouched. Brace/string/comment aware
     * so `external interface X { … }` blocks and braces inside strings/comments don't confuse hoisting.
     *
     * Known limits (rare in box code): top-level named `object`, `const val`, and `annotation class`
     * cannot be locals — write an explicit `fun main()` (which disables wrapping) if you need them.
     */
    internal fun ensureImplicitMain(source: String): String {
        if (HAS_MAIN.containsMatchIn(source)) return source
        if (source.isBlank()) return source

        val head = StringBuilder()
        val body = StringBuilder()
        var depth = 0
        var inBlockComment = false
        var inTripleString = false
        var hoistingBlock = false

        for (line in source.split("\n")) {
            val atTopLevelStart = depth == 0 && !inBlockComment && !inTripleString
            val startHoist = !hoistingBlock && atTopLevelStart && HOIST_PREFIX.containsMatchIn(line)
            val before = depth
            val scan = scanBraces(line, depth, inBlockComment, inTripleString)
            depth = scan.depth; inBlockComment = scan.inBlockComment; inTripleString = scan.inTripleString

            when {
                hoistingBlock -> {
                    head.append(line).append('\n')
                    if (depth <= 0) hoistingBlock = false
                }
                startHoist -> {
                    head.append(line).append('\n')
                    if (depth > before) hoistingBlock = true // multi-line external { … } block
                }
                else -> body.append(line).append('\n')
            }
        }

        return buildString {
            if (head.isNotEmpty()) append(head).append('\n')
            append("fun main() {\n")
            append(body.toString().trimEnd('\n'))
            append("\n}\n")
        }
    }

    private class BraceScan(val depth: Int, val inBlockComment: Boolean, val inTripleString: Boolean)

    /** Net brace-depth after [line], ignoring braces inside strings, char literals and comments. */
    private fun scanBraces(line: String, startDepth: Int, startBlockComment: Boolean, startTriple: Boolean): BraceScan {
        var depth = startDepth
        var inBlock = startBlockComment
        var inTriple = startTriple
        var inString = false      // "..."
        var inChar = false        // '...'
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val next = line.getOrNull(i + 1)
            when {
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
                inTriple -> if (c == '"' && next == '"' && line.getOrNull(i + 2) == '"') { inTriple = false; i += 2 }
                inString -> when {
                    c == '\\' -> i++
                    c == '"' -> inString = false
                }
                inChar -> when {
                    c == '\\' -> i++
                    c == '\'' -> inChar = false
                }
                c == '/' && next == '/' -> return BraceScan(depth, false, false) // line comment: rest ignored
                c == '/' && next == '*' -> { inBlock = true; i++ }
                c == '"' && next == '"' && line.getOrNull(i + 2) == '"' -> { inTriple = true; i += 2 }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '{' -> depth++
                c == '}' -> depth--
            }
            i++
        }
        return BraceScan(depth, inBlock, inTriple)
    }

    /**
     * Compile [kotlinSource] to a single plain-module, DCE'd JS string.
     *
     * The snippet is a normal Kotlin file: it may declare `external` values/functions to reach the
     * head's runtime (THREE, A-Frame, Field browser JS), and should contain a `fun main()` — the
     * plain module self-invokes `main` on eval.
     */
    /**
     * @param cacheDir persistent incremental-compilation cache. When set, the stdlib IC cache is
     *   built once and reused across compiles (and the box cache is reused when [moduleName] is
     *   stable, i.e. re-editing the same box) — the main per-edit latency lever. Pass a stable,
     *   per-session directory; null disables caching.
     */
    fun compile(
        kotlinSource: String,
        moduleName: String = "field_box",
        mode: Mode = defaultMode,
        profile: Profile = Profile.PLAIN_DCE,
        cacheDir: File? = null,
        wrapImplicitMain: Boolean = true,
    ): Result {
        val work = Files.createTempDirectory("field-ktjs").toFile()
        return try {
            val srcDir = File(work, "src").apply { mkdirs() }
            val prepared = if (wrapImplicitMain) ensureImplicitMain(kotlinSource) else kotlinSource
            val src = File(srcDir, "$moduleName.kt").apply { writeText(prepared) }
            val klibDir = File(work, "klib").apply { mkdirs() }
            val jsDir = File(work, "js").apply { mkdirs() }
            val klib = File(klibDir, "$moduleName.klib")
            // per-module ES output is <name>.mjs; plain output is <name>.js
            val js = File(jsDir, if (profile == Profile.ESM_SHARED) "$moduleName.mjs" else "$moduleName.js")

            if (mode == Mode.IN_PROCESS) {
                try {
                    return compileInProcess(moduleName, src, klibDir, jsDir, klib, js, profile, cacheDir)
                } catch (t: Throwable) {
                    lprintln("warning,remotejs", "in-process Kotlin->JS failed (${t.message}); falling back to subprocess")
                    klibDir.deleteRecursively(); klibDir.mkdirs()
                    jsDir.deleteRecursively(); jsDir.mkdirs()
                }
            }
            compileSubprocess(moduleName, src, klibDir, jsDir, klib, js, profile, cacheDir)
        } finally {
            work.deleteRecursively()
        }
    }

    // ---- in-process --------------------------------------------------------------------------

    private fun compileInProcess(
        moduleName: String, src: File, klibDir: File, jsDir: File, klib: File, js: File,
        profile: Profile, cacheDir: File?,
    ): Result {
        val collector = CollectingMessageCollector()

        val phase1 = K2JSCompilerArguments().apply {
            libraries = stdlibJsKlib.absolutePath
            outputDir = klibDir.absolutePath
            this.moduleName = moduleName
            freeArgs = listOf(src.absolutePath)
        }
        val e1 = K2JSCompiler().exec(collector, Services.EMPTY, phase1)
        if (e1 != ExitCode.OK || !klib.exists()) {
            return Result(null, collector.diagnostics, false, Mode.IN_PROCESS, collector.log())
        }

        val phase2 = K2JSCompilerArguments().apply {
            irProduceJs = true
            includes = klib.absolutePath
            libraries = stdlibJsKlib.absolutePath
            outputDir = jsDir.absolutePath
            this.moduleName = moduleName
            irModuleName = moduleName
            sourceMap = false
            if (cacheDir != null) {
                cacheDir.mkdirs()
                cacheDirectory = cacheDir.absolutePath
                irBuildCache = true
            }
            applyProfile(profile)
        }
        val e2 = K2JSCompiler().exec(collector, Services.EMPTY, phase2)
        if (e2 != ExitCode.OK || !js.exists()) {
            return Result(null, collector.diagnostics, false, Mode.IN_PROCESS, collector.log())
        }
        return Result(js.readText(), collector.diagnostics, true, Mode.IN_PROCESS, collector.log())
    }

    private fun K2JSCompilerArguments.applyProfile(profile: Profile) {
        when (profile) {
            Profile.PLAIN_DCE -> {
                moduleKind = "plain"
                irDce = true            // whole-program tree-shake; ~600 KB -> ~5 KB
            }
            Profile.ESM_SHARED -> {
                moduleKind = "es"
                irPerModule = true      // emit a tiny box module that imports the shared stdlib
                irDce = false           // no per-compile DCE over the stdlib
            }
        }
    }

    /**
     * OPEN BLOCKER — producing the shared `kotlin-kotlin-stdlib.mjs` for ESM_SHARED.
     *
     * ESM_SHARED box modules `import { … } from './kotlin-kotlin-stdlib.mjs'`. For "ship once" to
     * work, that shared file must export the stdlib's COMPLETE public surface, so any box resolves
     * against it. This is *not* something we can emit as a side effect of compiling a small program:
     * Kotlin/JS per-module output prunes the stdlib module's exports to what the *current
     * compilation* references (verified: a `fun main(){}` stub yields a 0-export stdlib; Field's own
     * build yields a 369–502-alias, afield-usage-scoped one). Compiling the stdlib klib as its own
     * main module (`-Xinclude=<stdlib.klib>`) throws in the backend — a full-surface stdlib is the
     * stdlib's dedicated build path (`-Xstdlib-compilation`), not a flag we can flip here.
     *
     * Candidate resolutions (see dev_docs/kotlin_to_js_spike.md):
     *  1. Run the stdlib's own JS build once in ~/temp/kotlin to produce the canonical full-surface
     *     `kotlin-kotlin-stdlib.mjs`, vendor it next to the klib, and serve it statically.
     *  2. Per-session "growing" stdlib: recompile the shared stdlib when a box imports a symbol it
     *     doesn't yet export (import-resolution miss → superset rebuild).
     *  3. Fall back to PLAIN_DCE (self-contained, no shared stdlib) for boxes that miss.
     *
     * Until one of those lands, this throws rather than emit a silently-incomplete stdlib.
     */
    fun emitSharedStdlibMjs(@Suppress("UNUSED_PARAMETER") destDir: File): File =
        error(
            "Shared full-surface kotlin-kotlin-stdlib.mjs generation is not implemented — per-module " +
                "stdlib exports are usage-scoped. See KotlinToJsCompiler.emitSharedStdlibMjs docs / " +
                "dev_docs/kotlin_to_js_spike.md for the candidate resolutions.",
        )

    private class CollectingMessageCollector : MessageCollector {
        val diagnostics = mutableListOf<Diagnostic>()
        private val raw = StringBuilder()
        private var errors = false

        override fun clear() {}
        override fun hasErrors() = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            val sev = when {
                severity == CompilerMessageSeverity.ERROR || severity == CompilerMessageSeverity.EXCEPTION -> "error"
                severity == CompilerMessageSeverity.WARNING || severity == CompilerMessageSeverity.STRONG_WARNING -> "warning"
                else -> "info"
            }
            if (sev == "error") errors = true
            // Keep only real diagnostics; skip the compiler's chatty logging/output severities.
            if (sev != "info" || location != null) {
                diagnostics.add(Diagnostic(sev, message, location?.line ?: -1, location?.column ?: -1))
            }
            raw.append(sev).append(": ").append(message)
            if (location != null) raw.append(" (").append(location.line).append(':').append(location.column).append(')')
            raw.append('\n')
        }

        fun log(): String = raw.toString()
    }

    // ---- subprocess (isolated fallback) ------------------------------------------------------

    private fun compileSubprocess(
        moduleName: String, src: File, klibDir: File, jsDir: File, klib: File, js: File,
        profile: Profile, cacheDir: File?,
    ): Result {
        val classpath = System.getProperty("java.class.path")
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

        val log1 = runCompiler(
            javaBin, classpath,
            "-Xir-produce-klib-file",
            "-libraries", stdlibJsKlib.absolutePath,
            "-ir-output-dir", klibDir.absolutePath,
            "-ir-output-name", moduleName,
            src.absolutePath,
        )
        if (!klib.exists()) return Result(null, parseDiagnostics(log1), false, Mode.SUBPROCESS, log1)

        val profileArgs = when (profile) {
            Profile.PLAIN_DCE -> arrayOf("-module-kind", "plain", "-Xir-dce")
            Profile.ESM_SHARED -> arrayOf("-module-kind", "es", "-Xir-per-module")
        }
        val cacheArgs =
            if (cacheDir != null) { cacheDir.mkdirs(); arrayOf("-Xcache-directory=${cacheDir.absolutePath}", "-Xir-build-cache") }
            else emptyArray()
        val log2 = runCompiler(
            javaBin, classpath,
            "-Xir-produce-js",
            "-Xinclude=${klib.absolutePath}",
            "-libraries", stdlibJsKlib.absolutePath,
            "-ir-output-dir", jsDir.absolutePath,
            "-ir-output-name", moduleName,
            "-Xir-module-name=$moduleName",
            *profileArgs,
            *cacheArgs,
        )
        val log = log1 + log2
        if (!js.exists()) return Result(null, parseDiagnostics(log), false, Mode.SUBPROCESS, log)
        return Result(js.readText(), parseDiagnostics(log), true, Mode.SUBPROCESS, log)
    }

    private fun runCompiler(javaBin: String, classpath: String, vararg compilerArgs: String): String {
        val cmd = buildList {
            add(javaBin); add("-cp"); add(classpath)
            add("org.jetbrains.kotlin.cli.js.K2JSCompiler")
            addAll(compilerArgs)
        }
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        if (proc.exitValue() != 0) lprintln("debug,remotejs", "K2JSCompiler exit=${proc.exitValue()}\n$out")
        return out
    }

    // Compiler stderr diagnostics look like: "path.kt:LINE:COL: error: message".
    private val diagnosticLine = Regex("""^.*?\.kt:(\d+):(\d+):\s+(error|warning|info):\s+(.*)$""")

    private fun parseDiagnostics(log: String): List<Diagnostic> =
        log.lineSequence().mapNotNull { line ->
            diagnosticLine.matchEntire(line.trim())?.let { m ->
                Diagnostic(m.groupValues[3], m.groupValues[4], m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }.toList()
}
