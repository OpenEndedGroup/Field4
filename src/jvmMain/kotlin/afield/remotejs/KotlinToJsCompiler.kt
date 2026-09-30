package afield.remotejs

import afield.FieldKtParser
import afield.logger.lprintln
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.io.File
import java.nio.file.Files

/**
 * SPIKE — Kotlin source -> JavaScript, for RemoteJS boxes.
 *
 * This mirrors what JetBrains' `kotlin-compiler-server` (KotlinToJSTranslator) does: a two-phase
 * K2 compile of a self-contained Kotlin snippet into a single, eval-able JS module that the existing
 * RemoteJS transport ships to the browser / visionOS "graphics head" over the WebSocket bus.
 *
 *     phase 1:  source.kt      --( -Xir-produce-klib-file )-->                 <module>.klib
 *     phase 2:  <module>.klib  --( -Xir-produce-js, -Xinclude, -Xir-dce )-->   <module>.js
 *
 * What this buys a box author is Kotlin: real types, completion, and native `operator fun plus`
 * vector and matrix math, so a Kotlin box needs none of the operator rewriting `Asta.kt` does for a
 * JavaScript one. The result runs unchanged in the head via `executeRemoteJS` with
 * `doASTA = false`.
 *
 * Output is a `plain` module that self-invokes `main`, so the head's `global.eval(code)` runs it
 * with no changes. Dead-code elimination is what keeps that module small enough to ship on every
 * compile: it tree-shakes the stdlib, taking a trivial program well under the ~600 KB an
 * unshaken one contributes, and `KotlinToJsSpikeTest` holds the emitted JS under 50 000
 * characters.
 *
 * Two execution modes (`Mode`):
 *  - IN_PROCESS: calls `K2JSCompiler().exec(...)` directly, with no JVM launch to pay for. The K2
 *    REPL (`KotlinInterface2`) already holds a live IntelliJ application environment in this JVM
 *    and `K2JSCompiler` reuses the process-wide `getOrCreateApplicationEnvironment`; the two
 *    coexist, and a throw from that call falls back to SUBPROCESS rather than failing the compile.
 *  - SUBPROCESS: spawns `java -cp <this app's classpath> K2JSCompiler`. Fully isolated, and reuses
 *    the embeddable compiler already on the runtime classpath. Slower, by a cold JVM per compile.
 *
 * Follow-up (see dev_docs/kotlin_to_js_spike.md): incremental compiles via JsIrCompilerWithIC, and a
 * typed `external` façade over the head runtime (THREE / A-Frame / Field JS).
 */

/**
 * The compiler itself: `compile` is the entry point, `Mode` picks how it runs and `Profile` picks
 * the shape of what comes out.
 *
 * Errors arrive on `Result` as `Diagnostic` rows carrying a line and column, so a box can decorate
 * the source line that caused them, with the compiler's own log kept alongside in `rawLog`. The
 * severities the compiler uses for progress chatter are dropped from those rows unless they carry
 * a location. Those line numbers take one more step to become usable: a compiled box has its head
 * declarations and an implicit-main wrapper prepended first, so the line the compiler reports is
 * not the line the author is looking at, and the preparation step keeps a map back to the editor's
 * own numbering for the diagnostics to be rewritten through.
 */
object KotlinToJsCompiler {

    /** Compiler-fork version. The Kotlin/JS stdlib klib MUST match the compiler exactly (the fork
     *  hard-fails linking against a mismatched stdlib), which is why we vendor it next to the jars. */
    private const val FORK_VERSION = "2.5.255-SNAPSHOT"

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
    ) {
        internal fun remapDiagnostics(source: PreparedSource): Result = copy(
            diagnostics = diagnostics.map { diagnostic ->
                diagnostic.copy(line = source.userLineFor(diagnostic.line))
            },
        )
    }

    /**
     * Compiler input plus the line map back to the editor source. Generated wrapper/declaration
     * lines map to null; user-authored lines retain their original one-based line number.
     */
    internal data class PreparedSource(
        val text: String,
        private val generatedToUserLine: List<Int?>,
    ) {
        fun userLineFor(generatedLine: Int): Int {
            val index = generatedLine - 1
            return if (index in generatedToUserLine.indices) generatedToUserLine[index] ?: 0 else generatedLine
        }

        fun appendUnmapped(suffix: String): PreparedSource {
            if (suffix.isBlank()) return this
            val separator = if (text.endsWith('\n')) "\n" else "\n\n"
            val appended = separator + suffix
            return PreparedSource(
                text + appended,
                generatedToUserLine + List(appended.count { it == '\n' }) { null },
            )
        }

        companion object {
            fun identity(source: String): PreparedSource =
                PreparedSource(source, source.split('\n').indices.map { it + 1 })
        }
    }

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

    private val HAS_MAIN = Regex("""(?m)^\s*(?:(?:public|private|internal|suspend|expect|actual|external)\s+)*fun\s+main\s*\(""")
    private val FILE_SCOPE_MODIFIERS = listOf(
        KtTokens.EXTERNAL_KEYWORD,
        KtTokens.EXPECT_KEYWORD,
        KtTokens.ACTUAL_KEYWORD,
        KtTokens.CONST_KEYWORD,
        KtTokens.SEALED_KEYWORD,
        KtTokens.ENUM_KEYWORD,
        KtTokens.ANNOTATION_KEYWORD,
        KtTokens.PUBLIC_KEYWORD,
        KtTokens.PRIVATE_KEYWORD,
        KtTokens.PROTECTED_KEYWORD,
        KtTokens.INTERNAL_KEYWORD,
    )

    private fun mustStayAtFileScope(element: KtElement): Boolean =
        element is KtObjectDeclaration ||
            element is KtTypeAlias ||
            (element as? KtModifierListOwner)?.let { owner ->
                FILE_SCOPE_MODIFIERS.any(owner::hasModifier)
            } == true

    /**
     * Lower Field's script-like box syntax to a normal Kotlin/JS file. PSI gives us complete
     * top-level declarations (including multiline properties, functions/classes, annotations and
     * expression-bodied declarations), which stay at file scope. Script initializers become the
     * synthesized main body.
     *
     * [activeUserLines] is used for fragment execution: all declarations from the current box remain
     * compiler-visible, while only selected script initializers run. This is the Kotlin/JS analogue
     * of a REPL fragment seeing names established by the box, without replaying unrelated print,
     * drawing, or lifecycle statements.
     */
    internal fun prepareImplicitMain(
        source: String,
        activeUserLines: IntRange? = null,
        stateKey: String? = null,
    ): PreparedSource {
        if (source.isBlank()) return PreparedSource.identity(source)
        if (HAS_MAIN.containsMatchIn(source)) {
            return PreparedSource.identity(runCatching { instrumentEditorPrints(source) }.getOrDefault(source))
        }

        return try {
            val instrumentedSource = instrumentEditorPrints(source)
            val file = FieldKtParser.parse(instrumentedSource)
            val script = file.script ?: return PreparedSource.identity(source)
            val lineStarts = buildList {
                add(0)
                instrumentedSource.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
            }
            fun lineAt(offset: Int): Int {
                val found = lineStarts.binarySearch(offset)
                return if (found >= 0) found + 1 else -found - 1
            }
            val lines = mutableListOf<String>()
            val lineMap = mutableListOf<Int?>()

            fun appendGenerated(line: String) {
                lines += line
                lineMap += null
            }

            fun appendElement(text: String, sourceStartLine: Int) {
                text.split('\n').forEachIndexed { index, line ->
                    lines += line
                    lineMap += sourceStartLine + index
                }
            }

            listOfNotNull(file.fileAnnotationList, file.packageDirective?.takeIf { it.text.isNotBlank() }, file.importList)
                .filter { it.text.isNotBlank() }
                .forEach {
                    appendElement(it.text, lineAt(it.textRange.startOffset))
                    appendGenerated("")
                }

            val statements = script.blockExpression.statements
            val declarations = statements.filterNot { it is KtScriptInitializer }
            val hoisted = if (activeUserLines == null) {
                declarations.filter(::mustStayAtFileScope)
            } else {
                declarations
            }
            hoisted.forEach {
                val text = if (activeUserLines != null && stateKey != null && it is KtProperty) {
                    statefulProperty(it) ?: it.text
                } else {
                    it.text
                }
                appendElement(text, lineAt(it.textRange.startOffset))
                appendGenerated("")
            }

            appendGenerated("fun main() {")
            if (activeUserLines == null && stateKey != null) appendGenerated("__fieldKotlinStateClear()")
            statements
                .filter { statement ->
                    if (activeUserLines == null) {
                        statement !in hoisted
                    } else {
                        statement is KtScriptInitializer && run {
                            val start = lineAt(statement.textRange.startOffset)
                            val end = lineAt(
                                (statement.textRange.endOffset - 1).coerceAtLeast(statement.textRange.startOffset),
                            )
                            start <= activeUserLines.last && end >= activeUserLines.first
                        }
                    }
                }
                .forEach {
                    appendElement(it.text, lineAt(it.textRange.startOffset))
                }
            if (stateKey != null) {
                declarations.filterIsInstance<KtProperty>().mapNotNull { it.name }.forEach { name ->
                    appendGenerated("""__fieldKotlinStateSet("$name", $name)""")
                }
            }
            appendGenerated("}")

            var prepared = PreparedSource(lines.joinToString("\n", postfix = "\n"), lineMap)
            if (stateKey != null) prepared = prepared.appendUnmapped(stateSupport(stateKey))
            prepared
        } catch (t: Throwable) {
            // Let K2 report malformed source rather than replacing a useful compiler diagnostic
            // with a ktfmt parser exception. The simple wrapper still carries the common +1 map.
            val bodyLines = source.split('\n')
            PreparedSource(
                buildString {
                    append("fun main() {\n")
                    append(source)
                    if (!source.endsWith('\n')) append('\n')
                    append("}\n")
                },
                listOf(null) + bodyLines.indices.map { it + 1 } + listOf(null),
            )
        }
    }

    private data class SourceReplacement(val start: Int, val end: Int, val text: String)

    /**
     * Carry direct Kotlin print/println call sites through the generated JavaScript. Remote heads
     * accept the optional second argument and echo it in the execution response, allowing Monaco
     * to render the output beside a real source line.
     */
    private fun instrumentEditorPrints(source: String): String {
        val file = FieldKtParser.parse(source)
        val shadowedNames = PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java)
            .mapNotNullTo(mutableSetOf()) { it.name }
        val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }
        fun lineAt(offset: Int): Int {
            val found = lineStarts.binarySearch(offset)
            return if (found >= 0) found + 1 else -found - 1
        }

        val replacements = mutableListOf<SourceReplacement>()
        PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).forEach { call ->
            val callee = call.calleeExpression ?: return@forEach
            if (callee.text != "print" && callee.text != "println") return@forEach
            if (callee.text in shadowedNames) return@forEach
            val parent = call.parent
            if (parent is KtDotQualifiedExpression && parent.selectorExpression == call) return@forEach
            val arguments = call.valueArgumentList ?: return@forEach
            val leftParen = arguments.leftParenthesis ?: return@forEach
            val line = lineAt(callee.textRange.startOffset)
            replacements += SourceReplacement(
                callee.textRange.startOffset,
                callee.textRange.endOffset,
                "__fieldPrintAt",
            )
            replacements += SourceReplacement(
                leftParen.textRange.endOffset,
                leftParen.textRange.endOffset,
                if (arguments.arguments.isEmpty()) "$line" else "$line, ",
            )
        }
        if (replacements.isEmpty()) return source

        return StringBuilder(source).apply {
            replacements.sortedWith(compareByDescending<SourceReplacement> { it.start }.thenByDescending { it.end })
                .forEach { replace(it.start, it.end, it.text) }
        }.toString()
    }

    private fun statefulProperty(property: KtProperty): String? {
        val name = property.name ?: return null
        val initializer = property.initializer?.text ?: return null
        if (property.delegate != null) return null
        val keyword = if (property.isVar) "var" else "val"
        val type = property.typeReference?.text?.let { ": $it" }.orEmpty()
        return """$keyword $name$type = __fieldKotlinStateRestore("$name") { $initializer }"""
    }

    private fun stateSupport(stateKey: String): String {
        require(stateKey.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid Kotlin/JS state key '$stateKey'" }
        return """
            private fun __fieldKotlinStateRoot(): dynamic =
                js("(globalThis.__fieldKotlinStates || (globalThis.__fieldKotlinStates = Object.create(null)))")

            private fun __fieldKotlinState(): dynamic {
                val root = __fieldKotlinStateRoot()
                var state = root["$stateKey"]
                if (state == null) {
                    state = js("Object.create(null)")
                    root["$stateKey"] = state
                }
                return state
            }

            private fun __fieldKotlinStateHas(name: String): Boolean {
                val state = __fieldKotlinState()
                return js("Object.prototype.hasOwnProperty.call(state, name)")
            }

            private fun __fieldKotlinStateGet(name: String): dynamic = __fieldKotlinState()[name]

            private fun __fieldKotlinStateSet(name: String, value: Any?) {
                __fieldKotlinState()[name] = value
            }

            private fun __fieldKotlinStateClear() {
                __fieldKotlinStateRoot()["$stateKey"] = js("Object.create(null)")
            }

            @Suppress("UNCHECKED_CAST")
            private inline fun <T> __fieldKotlinStateRestore(name: String, otherwise: () -> T): T =
                if (__fieldKotlinStateHas(name)) __fieldKotlinStateGet(name) as T else otherwise()
        """.trimIndent()
    }

    internal fun ensureImplicitMain(source: String): String = prepareImplicitMain(source).text

    /**
     * Compile [kotlinSource] to a single plain-module, DCE'd JS string.
     *
     * The snippet is a normal Kotlin file: it may declare `external` values/functions to reach the
     * head's runtime (THREE, A-Frame, Field browser JS), and should contain a `fun main()` — the
     * plain module self-invokes `main` on eval.
     *
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
            val prepared =
                if (wrapImplicitMain) prepareImplicitMain(kotlinSource)
                else PreparedSource.identity(kotlinSource)
            val src = File(srcDir, "$moduleName.kt").apply { writeText(prepared.text) }
            val klibDir = File(work, "klib").apply { mkdirs() }
            val jsDir = File(work, "js").apply { mkdirs() }
            val klib = File(klibDir, "$moduleName.klib")
            // per-module ES output is <name>.mjs; plain output is <name>.js
            val js = File(jsDir, if (profile == Profile.ESM_SHARED) "$moduleName.mjs" else "$moduleName.js")

            if (mode == Mode.IN_PROCESS) {
                try {
                    val result = compileInProcess(moduleName, src, klibDir, jsDir, klib, js, profile, cacheDir)
                    return result.remapDiagnostics(prepared)
                } catch (t: Throwable) {
                    lprintln("warning,remotejs", "in-process Kotlin->JS failed (${t.message}); falling back to subprocess")
                    klibDir.deleteRecursively(); klibDir.mkdirs()
                    jsDir.deleteRecursively(); jsDir.mkdirs()
                }
            }
            compileSubprocess(moduleName, src, klibDir, jsDir, klib, js, profile, cacheDir)
                .remapDiagnostics(prepared)
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
