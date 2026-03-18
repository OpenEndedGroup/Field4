package afield

//import org.jetbrains.kotlin.scripting.ide_services.compiler.KJvmReplCompilerWithIdeServices
import afield.logger.lblock
import afield.logger.lprintln
import afield.macros.MacroRegistry
import afield.stdlib.applyInsertToPreamble
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.scripting.ide_services.compiler.KJvmReplCompilerWithIdeServices
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.Executors
import kotlin.reflect.*
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.kotlinProperty
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.*
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.host.with
import kotlin.script.experimental.impl._languageVersion
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.util.determineSep
import kotlin.script.experimental.jvm.util.toSourceCodePosition
import kotlin.script.experimental.util.LinkedSnippet
import kotlin.system.measureNanoTime

/**
 * Annotation to mark functions/values that should be automatically exported to the box's preamble.
 * When a declaration is annotated with @exported, its fully-typed signature will be added to
 * the box's preamble after execution, making it available as a declared property.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class exported

private fun kotlinInfoEscapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun kotlinInfoClip(text: String, maxChars: Int = 220): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars - 1) + "…"
}

private fun kotlinInfoTypeName(value: Any?): String {
    if (value == null) return "null"
    return value::class.qualifiedName ?: value::class.simpleName ?: value::class.java.name
}

class KotlinInterface(
    val propertyClasses: Map<String, KClass<*>> = emptyMap(), val properties: Map<String, Any?> = emptyMap(),

    val implicitReceivers: List<KotlinType> = emptyList(),

    var classLoader: ClassLoader? = null, val filePrefix: String = "EVAL_"
) {

    internal inner class SourceCodeImpl(number: Int, override val text: String) : SourceCode {
        override val name: String? = "${filePrefix}_\$$number"
        override val locationId: String? = "LOCATION_$number"

        override fun equals(other: Any?): Boolean {
            return this == other || (other as? SourceCodeImpl)?.let { text == it.text } == true
        }

        override fun hashCode(): Int {
            return text.hashCode()
        }
    }

    var evaluator: BasicJvmReplEvaluator
    var compiler: KJvmReplCompilerWithIdeServices
//    var compiler: K2KJvmReplCompilerWithCompletion

    //    var evalConfig: ScriptEvaluationConfiguration
    var compilerConfig: ScriptCompilationConfiguration
    val contextUpdater: ContextUpdater
    val context: KotlinContext

    // Reference to the box this KotlinInterface is associated with (for @exported support)
    var associatedBox: Any? = null

    private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

    init {
        assert(properties.size == propertyClasses.size)
        assert(properties.keys.containsAll(propertyClasses.keys))
        if (classLoader == null) classLoader = Thread.currentThread().contextClassLoader

        if (sharedClassLoader == null) {
            sharedClassLoader = URLClassLoader(arrayOf(), classLoader)

        }

        classLoader = sharedClassLoader

        val myHostConfiguration = defaultJvmScriptingHostConfiguration.with {
            jvm {
                baseClassLoader(classLoader!!)
            }
        }

        compilerConfig = ScriptCompilationConfiguration {
            defaultImports(DependsOn::class, Repository::class, exported::class)
            hostConfiguration.update {
                myHostConfiguration
            }
            this@KotlinInterface.implicitReceivers.forEach { implicitReceivers.append(it) }
            jvm {
                jvmTarget.put("24")
                _languageVersion.put("2.2")
                compilerOptions.put(listOf("-jvm-target", "24"))

                dependenciesFromClassloader(
                    classLoader = classLoader!!, wholeClasspath = true, unpackJarCollections = true
                )
                providedProperties(*propertyClasses.map { it.key to it.value }.toTypedArray())
            }

            refineConfiguration {
                onAnnotations(DependsOn::class, Repository::class, handler = ::configureMavenDepsOnAnnotations)
            }
        }

        compiler = KJvmReplCompilerWithIdeServices()
        evaluator = BasicJvmReplEvaluator()

        context = KotlinContext()
        contextUpdater = ContextUpdater(context, evaluator, this)
    }

    fun configureMavenDepsOnAnnotations(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        notify("configuring dependencies in script", "execution")
        val annotations =
            context.collectedData?.get(ScriptCollectedData.collectedAnnotations)?.takeIf { it.isNotEmpty() }
                ?: return context.compilationConfiguration.asSuccess() // If no action is performed, the original configuration should be returned
        return runBlocking {
            notify("running resolver for dependencies", "execution")
            // resolving maven artifacts using annotation arguments
            resolver.resolveFromScriptSourceAnnotations(annotations)
        }.onSuccess {
            notify("finished running resolver", "execution")
            context.compilationConfiguration.with {
                // updating the original configurations with the newly resolved artifacts as compilation dependencies

                try {
                    it.forEach {
                        val target = (classLoader!! as URLClassLoader)
                        lprintln("warning,kotlin", "sketchy url classloader front running ${it}")
                        val m = URLClassLoader::class.java.getDeclaredMethod("addURL", URL::class.java)
                        m.isAccessible = true
                        m.invoke(target, it.toURI().toURL())
                    }
                } catch (e: Throwable) {
                    lprintln("error,kotlin", "sketchy url classloader front running threw")
                    e.printStackTrace()
                }

                jvm {
                    dependenciesFromClassloader(
                        classLoader = classLoader!!, wholeClasspath = true, unpackJarCollections = true
                    )
                }
//                dependencies.append(JvmDependency(it))
            }.asSuccess()
        }
    }


    var id = 0

    var cache = mutableMapOf<String, ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>>?>()

    private fun resolveBoxContext(implicits: List<Any> = emptyList()): Box? {
        val fromImplicits = implicits.firstOrNull { it is Box } as? Box
        if (fromImplicits != null) return fromImplicits
        val fromAssociated = associatedBox as? Box
        if (fromAssociated != null) return fromAssociated
        return Box.context.get()
    }

    suspend fun compile(s: String): ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>> {
        val code = SourceCodeImpl(id++, s)
        var r: ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>>? = null
//        val r2 = cache.get(s)
//        if (r2!=null) return r2

        lprintln("info,kotlin,perf", "\t\t compile code $s")

        coroutineScope {
            print(" -- inside coroutineScope")
            async(compilationThread) {
                val ns = measureNanoTime {
                    r = compiler.compile(code, compilerConfig)
                }
                lprintln("info,kotlin,perf", "\t\tTIME<" + (ns / 1_000_000.0) + "ms> = compile (async)")
            }.await()
        }

//        cache.put(s, r)
        return r!!
    }


    suspend fun evaluate(
        e: LinkedSnippet<KJvmCompiledScript>, updateContext: Boolean = false
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
        return Box.withBoxContext(resolveBoxContext()) {
            var r: ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>>
            val ns = measureNanoTime {

                val evalConfig = ScriptEvaluationConfiguration {
                    jvm {
                        baseClassLoader(classLoader!!)
                    }
                    providedProperties(properties)
                }


                r = evaluator.eval(e, evalConfig)
                if (updateContext) contextUpdater.update()
            }
            lprintln("info,kotlin,perf", "\t\tTIME<" + (ns / 1_000_000.0) + "ms> = evaluate")
            r
        }
    }

    suspend fun evaluate(
        e: LinkedSnippet<KJvmCompiledScript>, updateContext: Boolean = true, implicits: List<Any>
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
        return Box.withBoxContext(resolveBoxContext(implicits)) {
            var r: ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>>
            val ns = measureNanoTime {

                val evalConfig = ScriptEvaluationConfiguration {
                    implicits.forEach { implicitReceivers.append(it) }
                    jvm {
                        baseClassLoader(classLoader!!)
                    }
                    providedProperties(properties)
                }

                r = evaluator.eval(e, evalConfig)
                if (updateContext) contextUpdater.update()
            }
            lprintln("info,kotlin,perf", "\t\tTIME<" + (ns / 1_000_000.0) + "ms> = evaluate")
            r
        }
    }

    fun String.findNthSubstring(s: String, n: Int, start: Int = 0): Int {
        if (n < 1 || start == -1) return -1

        var i = start

        for (k in 1..n) {
            i = indexOf(s, i)
            if (i == -1) return -1
            i += s.length
        }

        return i - s.length
    }


    fun Int.toSourceCodePositionWithNewAbsolute(code: SourceCode, newCode: SourceCode): SourceCode.Position? {
        val pos = toSourceCodePosition(code)
        val sep = code.text.determineSep()
        val absLineStart = if (pos.line == 1) 0
        else newCode.text.findNthSubstring(sep, pos.line - 1) + sep.length

        var nextNewLinePos = newCode.text.indexOf(sep, absLineStart)
        if (nextNewLinePos == -1) nextNewLinePos = newCode.text.length

        val abs = absLineStart + pos.col - 1
        if (abs > nextNewLinePos) return null

        return SourceCode.Position(pos.line, abs - absLineStart + 1, abs)
    }


    suspend fun complete(s: String, cursor: Int): CompletionResult {
        val code = SourceCodeImpl(id++, s)

        return try {
            val cc = compiler.complete(code, cursor.toSourceCodePositionWithNewAbsolute(code, code)!!, compilerConfig)
            cc?.valueOrNull()?.toList()?.let { completionList ->
                getResult(s, cursor, completionList)
            } ?: CompletionResult.Empty(s, cursor)
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            CompletionResult.Error(e.javaClass.simpleName, e.message ?: "", sw.toString())
        }

    }


    @JvmOverloads
    fun completeAndWait(s: String, cursor: Int = s.length): CompletionResult {
        return runBlocking { complete(s, cursor) }
    }

    fun applyMacros(box: Box, _s: String): String {
        var s = _s
        val needsRerun = false
        lblock("debug,kotlin", "rewriting AST analysis") {
            do {
                val cs = compiler.state.getCompilationState(compilerConfig)
                val env = cs.environment
                val project = env.project

                val factory = PsiFileFactory.getInstance(project)
                val lang = Language.findLanguageByID("kotlin")!!
                val instance = factory.createFileFromText(lang, s)

                val edits = mutableListOf<Pair<Pair<Int, Int>, String>>()

                instance.forEachDescendantOfType<PsiElement> { expression ->
                    when (expression) {
                        is KtCallExpression -> {
                            expression.children.forEach {
                                when (it) {
                                    is KtNameReferenceExpression -> {
                                        lprintln(
                                            "debug,kotlin",
                                            "child (named): ${it} ${it.text} ${it.javaClass} ${it.startOffset} -> ${it.endOffset} ${it.reference}"
                                        )

                                        var ns = it.nextSibling
                                        val t = mutableListOf<String>()
                                        while (ns != null) {
                                            t.add(ns.text)
                                            ns = ns.nextSibling
                                        }

                                        val e = MacroRegistry.rewrite(box, it.text, t)
                                        if (e != null) {
                                            if (e.start.length > 0) {
                                                edits.add((0 to 0) to e.preamble)
                                            }
                                            if (e.afterImports.length > 0) {
                                                val a = findLastImport(s)
                                                edits.add((a to a) to e.afterImports)
                                            }

                                            edits.add((expression.startOffset to expression.endOffset) to e.here)

                                            if (e.preamble.isNotBlank() || e.preambleAfterImports.isNotBlank()) {
                                                applyInsertToPreamble(box, e)
                                            }
                                        }
                                    }

                                    else -> {
//                                        lprintln("child: ${it} ${it.javaClass} ${it.startOffset} -> ${it.endOffset}")
                                    }
                                }
                            }
                        }

                        is KtNameReferenceExpression -> {
                        }
                    }
                }
                while (edits.size > 0) {
                    lprintln("debug,kotlin", "applying edits")
                    val e = edits.pop()
                    s = s.replaceRange(e.first.first, e.first.second, e.second)
                    val shrink = (e.first.second - e.first.first) - e.second.length
                    edits.forEachIndexed { index, it ->
                        val a = if (it.first.first > e.first.first) it.first.first - shrink else it.first.first
                        val b = if (it.first.second > e.first.first) it.first.second - shrink else it.first.second
                        edits[index] = (a to b) to it.second
                    }
                }
            } while (needsRerun)

            lprintln("debug,kotlin", "final string is:\n$s")
        }
        return s
    }


    fun findLastImport(s: String): Int {
        if (s.contains("import ")) {
            val m = s.findLastAnyOf(listOf("import "))!!.first
            val m2 = s.findAnyOf(listOf("\n"), startIndex = m)
            if (m2 == null) return s.length
            else return m2.first + 1
        } else return 0

    }

    fun parseAndRewrite(s: String, rules: List<(PsiElement, String) -> String>): String {

        // trying something new here

        if (s.contains("//%frames")) {
            val ll = s.split("\n")
            var imports = ""
            var rest = ""
            ll.forEach {
                if (it.trim().lowercase().startsWith("import ")) {
                    imports += it + "\n"
                } else if (it.trim().lowercase().contains(" by ")) {
                    imports += it + "\n"
                } else if (it.trim().lowercase().startsWith("//%frames")) {
                    rest += "var _r = frames {\n"
                } else {
                    rest += it + "\n"
                }
            }

            return imports + rest + "}"
        }

        return s;

    }

    var execNumber = 0
    suspend fun exec(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {
            lprintln(
                "error,kotlin",
                "onCompileError default handler $it"
            )
        },
        onEvalError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,kotlin", "onEvalError default handler $it") },
        onEvalException: (Throwable) -> Unit = {
            lprintln(
                "error,kotlin",
                "onEvalException default handler $it"
            ); throw it
        },
        implicits: List<Any> = emptyList(),
    ): ResultValue? {

        when (val r = compile(s)) {
            is ResultWithDiagnostics.Success -> {
                return when (val r2 = evaluate(r.value, implicits = implicits)) {
                    is ResultWithDiagnostics.Success -> {
                        when (val e = r2.value.get().result) {
                            is ResultValue.Error -> {
                                lprintln("error,kotlin", "Evaluation Error ${e.error} / ${e.error.javaClass}")
                                onEvalException(e.error)
                                e
                            }

                            is ResultValue.NotEvaluated -> {
                                lprintln("warning,kotlin", "not evaluated for ${r2.reports}")
                                onEvalError(r2.reports)
                                e
                            }

                            else -> {
                                execNumber++
                                e
                            }
                        }

                    }

                    is ResultWithDiagnostics.Failure -> {
                        lprintln("error,kotlin", "evaluator error ${r2}")
                        onEvalError(r2.reports)
                        null
                    }
                }
            }

            is ResultWithDiagnostics.Failure -> {
                lprintln("error,kotlin", "compiler error ${r}")
                onCompileError(r.reports)
                null
            }
        }

        return null
    }

    @JvmOverloads
    fun execAndWait(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalException: (Throwable) -> Unit = {},
        implicits: List<Any> = emptyList()
    ): ResultValue? {
        return runBlocking {
            exec(s, onCompileError, onEvalError, onEvalException, implicits)
        }
    }

    /**
     * CompiledSnippet represents a pre-compiled code snippet that can be evaluated multiple times.
     * This is useful for code that needs to be executed repeatedly without the compilation overhead.
     *
     * Example usage:
     * ```
     * val snippet = ki.compileSnippet("println(\"Hello \${x}\")")
     * snippet.eval { println("x = 1") }  // prints "Hello 1"
     * snippet.eval { println("x = 2") }  // prints "Hello 2"
     * ```
     */
    inner class CompiledSnippet internal constructor(
        private val compiled: LinkedSnippet<KJvmCompiledScript>,
        private val defaultImplicits: List<Any>
    ) {
        /**
         * Evaluates the compiled snippet with optional implicit receivers and context update.
         *
         * @param updateContext Whether to update the KotlinInterface context with new variables/functions
         * @param implicits Optional list of implicit receiver objects for this evaluation
         * @param onEvalError Callback for evaluation errors
         * @param onEvalException Callback for evaluation exceptions
         * @return The result value or null on error
         */
        suspend fun eval(
            updateContext: Boolean = false,
            implicits: List<Any> = defaultImplicits,
            onEvalError: (List<ScriptDiagnostic>) -> Unit = {
                lprintln(
                    "error,kotlin",
                    "onEvalError default handler $it"
                )
            },
            onEvalException: (Throwable) -> Unit = {
                lprintln(
                    "error,kotlin",
                    "onEvalException default handler $it"
                ); throw it
            }
        ): ResultValue? {
            return when (val r = evaluate(compiled, updateContext, implicits)) {
                is ResultWithDiagnostics.Success -> {
                    when (val e = r.value.get().result) {
                        is ResultValue.Error -> {
                            lprintln("error,kotlin", "Evaluation Error ${e.error} / ${e.error.javaClass}")
                            onEvalException(e.error)
                            e
                        }

                        is ResultValue.NotEvaluated -> {
                            lprintln("warning,kotlin", "not evaluated for ${r.reports}")
                            onEvalError(r.reports)
                            e
                        }

                        else -> {
                            execNumber++
                            e
                        }
                    }
                }

                is ResultWithDiagnostics.Failure -> {
                    lprintln("error,kotlin", "evaluator error ${r}")
                    onEvalError(r.reports)
                    null
                }
            }
        }

        /**
         * Synchronous version of eval() that blocks until evaluation completes.
         *
         * Note: Unlike the async eval(), this version uses empty default error handlers
         * to avoid throwing exceptions, providing a simpler API for synchronous use cases.
         */
        @JvmOverloads
        fun evalAndWait(
            updateContext: Boolean = false,
            implicits: List<Any> = defaultImplicits,
            onEvalError: (List<ScriptDiagnostic>) -> Unit = {
                lprintln(
                    "error,kotlin",
                    "onEvalError default handler $it"
                )
            },
            onEvalException: (Throwable) -> Unit = { lprintln("error,kotlin", "onEvalException default handler $it") }
        ): ResultValue? {
            return runBlocking {
                eval(updateContext, implicits, onEvalError, onEvalException)
            }
        }
    }

    /**
     * Compiles a code snippet that can be evaluated multiple times.
     * This is more efficient than calling exec() repeatedly for the same code,
     * as compilation happens only once.
     *
     * @param s The code to compile
     * @param implicits Default implicit receiver objects (e.g., Box instances)
     * @param onCompileError Callback for compilation errors
     * @return A CompiledSnippet that can be evaluated multiple times, or null on error
     */
    suspend fun compileSnippet(
        s: String,
        implicits: List<Any> = emptyList(),
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {
            lprintln(
                "error,kotlin",
                "onCompileError default handler $it"
            )
        }
    ): CompiledSnippet? {
        return when (val r = compile(s)) {
            is ResultWithDiagnostics.Success -> {
                CompiledSnippet(r.value, implicits)
            }

            is ResultWithDiagnostics.Failure -> {
                lprintln("error,kotlin", "compiler error ${r}")
                onCompileError(r.reports)
                null
            }
        }
    }

    /**
     * Synchronous version of compileSnippet() that blocks until compilation completes.
     */
    @JvmOverloads
    fun compileSnippetAndWait(
        s: String,
        implicits: List<Any> = emptyList(),
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {}
    ): CompiledSnippet? {
        return runBlocking {
            compileSnippet(s, implicits, onCompileError)
        }
    }


    enum class CompletionStatus(private val value: String) {
        OK("ok"), ERROR("error");

        override fun toString(): String {
            return value
        }
    }


    abstract class CompletionResult(private val status: CompletionStatus) {
//        open fun toJson(): JsonObject {
//            return jsonObject("status" to status.toString())
//        }

        open class Success(
            val matches: List<String>,
            val bounds: CompletionTokenBounds,
            val metadata: List<SourceCodeCompletionVariant>,
            val text: String,
            val cursor: Int
        ) : CompletionResult(CompletionStatus.OK) {
            init {
                assert(matches.size == metadata.size)
            }
        }

        class Empty(text: String, cursor: Int) :
            Success(emptyList(), CompletionTokenBounds(cursor, cursor), emptyList(), text, cursor)

        class Error(val errorName: String, val errorValue: String, val traceBack: String) :
            CompletionResult(CompletionStatus.ERROR) {

        }
    }


    companion object {

        var sharedClassLoader: ClassLoader? = null

        // multi-threaded compilation broken in current kotlin compiler
        var compilationThread = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

        fun functionSignature(function: KFunction<*>) = function.toString().replace("Line_\\d+\\.".toRegex(), "")

        fun shortenType(name: String) = name.replace("(\\b[_a-zA-Z$][_a-zA-Z0-9$]*\\b\\.)+".toRegex(), "")

        data class CompletionTokenBounds(val start: Int, val end: Int)


        fun getTokenBounds(buf: String, cursor: Int): CompletionTokenBounds {
            require(cursor <= buf.length) { "Position $cursor does not exist in code snippet <$buf>" }

            val startSubstring = buf.substring(0, cursor)

            val filter = { c: Char -> !c.isLetterOrDigit() && c != '_' }

            val start = startSubstring.indexOfLast(filter) + 1

            return CompletionTokenBounds(start, cursor)
        }

        fun getResult(
            code: String, cursor: Int, completions: List<SourceCodeCompletionVariant>
        ): CompletionResult.Success {
            val filteredCompletions = completions.filter { !it.text.contains("clojure", ignoreCase = true) }
                .filter { !it.text.contains("$", ignoreCase = true) }
            val bounds = getTokenBounds(code, cursor)
            return CompletionResult.Success(
                filteredCompletions.map { it.text },
                bounds,
                filteredCompletions,
                code,
                cursor
            )
        }
    }

    class KotlinContext(
        val vars: HashMap<String, KotlinVariableInfo> = HashMap(),
        val functions: HashMap<String, KotlinFunctionInfo> = HashMap()
    )


    class KotlinFunctionInfo(val function: KFunction<*>, val line: Any) : Comparable<KotlinFunctionInfo>, ToHTML {

        val name: String
            get() = function.name

        private fun parameterLabel(parameter: KParameter): String {
            val typeText = shortenType(parameter.type.toString())
            val base = when (parameter.kind) {
                KParameter.Kind.EXTENSION_RECEIVER -> "<receiver>"
                KParameter.Kind.INSTANCE -> "<instance>"
                else -> parameter.name ?: "<arg>"
            }
            val flags = buildList {
                if (parameter.isVararg) add("vararg")
                if (parameter.isOptional) add("optional")
            }
            return if (flags.isEmpty()) "$base: $typeText" else "$base: $typeText [${flags.joinToString(", ")}]"
        }

        override fun toHTML(): String {
            val signature = shortenType(functionSignature(function))
            val returnType = shortenType(function.returnType.toString())
            val scriptClass = kotlinInfoClip(shortenType(line.javaClass.name))
            val valueParams = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
            val extensionReceiver = function.parameters.firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }
            val instanceReceiver = function.parameters.firstOrNull { it.kind == KParameter.Kind.INSTANCE }

            return buildString {
                append("<div style='display:inline-block; max-width: 620px;'>")
                append("<div><span style='color:#f6c;'>fun</span> <span style='color:#fff;font-style:italic;'>")
                append(kotlinInfoEscapeHtml(name))
                append("</span></div>")

                append("<div style='color:#aaa; overflow-wrap:anywhere;'>")
                append(kotlinInfoEscapeHtml(signature))
                append("</div>")

                append("<div style='color:#999;'>returns <span style='color:#8cf;'>")
                append(kotlinInfoEscapeHtml(returnType))
                append("</span></div>")

                extensionReceiver?.let {
                    append("<div style='color:#999;'>extension receiver <span style='color:#8cf;'>")
                    append(kotlinInfoEscapeHtml(shortenType(it.type.toString())))
                    append("</span></div>")
                }
                instanceReceiver?.let {
                    append("<div style='color:#999;'>instance receiver <span style='color:#8cf;'>")
                    append(kotlinInfoEscapeHtml(shortenType(it.type.toString())))
                    append("</span></div>")
                }

                if (valueParams.isNotEmpty()) {
                    append("<div style='color:#999;'>params (")
                    append(valueParams.size)
                    append(")</div>")
                    valueParams.forEach { parameter ->
                        append("<div style='margin-left:12px; color:#bbb; overflow-wrap:anywhere;'>• ")
                        append(kotlinInfoEscapeHtml(parameterLabel(parameter)))
                        append("</div>")
                    }
                } else {
                    append("<div style='color:#777;'>params (0)</div>")
                }

                append("<div style='color:#777; overflow-wrap:anywhere;'>defined in ")
                append(kotlinInfoEscapeHtml(scriptClass))
                append("</div>")
                append("</div>")
            }
        }

        fun toString(shortenTypes: Boolean): String {
            return if (shortenTypes) {
                shortenType(toString())
            } else toString()
        }

        override fun toString(): String {
            return functionSignature(function)
        }

        override fun compareTo(other: KotlinFunctionInfo): Int {
            return this.toString().compareTo(other.toString())
        }

        override fun hashCode(): Int {
            return this.toString().hashCode()
        }

        override fun equals(other: Any?): Boolean {
            return if (other is KotlinFunctionInfo) {
                this.toString() == other.toString()
            } else false
        }
    }

    class KotlinVariableInfo(val value: Any?, val descriptor: KProperty<*>, val line: Any) : ToHTML {

        val name: String
            get() = descriptor.name

        @Suppress("MemberVisibilityCanBePrivate")
        val type: String
            get() = descriptor.toString() //!! suspect

        fun toString(shortenTypes: Boolean): String {
            var type: String = type
            if (shortenTypes) {
                type = shortenType(type)
            }
            return "$name: $type = $value"
        }

        override fun toString(): String {
            return toString(false)
        }

        private fun valuePreview(): String {
            return when (value) {
                null -> "<span style='color:#888;'>null</span>"
                is Number -> "<span style='color:#8cf;'>${kotlinInfoEscapeHtml(value.toString())}</span>"
                is Boolean -> "<span style='color:#f88;'>${kotlinInfoEscapeHtml(value.toString())}</span>"
                is Char -> "<span style='color:#8f8;'>'${kotlinInfoEscapeHtml(value.toString())}'</span>"
                is String -> "<span style='color:#8f8;'>${kotlinInfoEscapeHtml(kotlinInfoClip(value))}</span>"
                else -> {
                    val runtime = shortenType(kotlinInfoTypeName(value))
                    val text = kotlinInfoClip(value.toString())
                    "<span style='color:#bbb;'>${kotlinInfoEscapeHtml(runtime)}</span> <span style='color:#777;'>= ${kotlinInfoEscapeHtml(text)}</span>"
                }
            }
        }

        override fun toHTML(): String {
            val declaration = shortenType(descriptor.toString())
            val declaredType = runCatching { shortenType(descriptor.returnType.toString()) }.getOrDefault(shortenType(type))
            val runtimeType = shortenType(kotlinInfoTypeName(value))
            val scriptClass = kotlinInfoClip(shortenType(line.javaClass.name))
            val kind = if (descriptor is KMutableProperty<*>) "var" else "val"

            return buildString {
                append("<div style='display:inline-block; max-width: 620px;'>")
                append("<div><span style='color:#f6c;'>")
                append(kind)
                append("</span> <span style='color:#fff;font-style:italic;'>")
                append(kotlinInfoEscapeHtml(name))
                append("</span></div>")

                append("<div style='color:#aaa; overflow-wrap:anywhere;'>")
                append(kotlinInfoEscapeHtml(declaration))
                append("</div>")

                append("<div style='color:#999;'>declared type <span style='color:#8cf;'>")
                append(kotlinInfoEscapeHtml(declaredType))
                append("</span></div>")

                append("<div style='color:#999;'>runtime type <span style='color:#8cf;'>")
                append(kotlinInfoEscapeHtml(runtimeType))
                append("</span></div>")

                append("<div style='margin-top:2px; color:#999;'>value ")
                append(valuePreview())
                append("</div>")

                append("<div style='color:#777; overflow-wrap:anywhere;'>defined in ")
                append(kotlinInfoEscapeHtml(scriptClass))
                append("</div>")
                append("</div>")
            }
        }
    }

    class ContextUpdater(
        val context: KotlinContext,
        private val evaluator: BasicJvmReplEvaluator,
        private val ki: KotlinInterface
    ) {

        private var lastProcessedSnippet: LinkedSnippet<KJvmEvaluatedSnippet>? = null

        // Track exported declarations by alias for idempotent preamble updates
        private val exportedDeclarations = mutableMapOf<String, String>()

        fun update() {
            try {
                var lastSnippet = evaluator.lastEvaluatedSnippet
                val newSnippets = mutableListOf<Any>()
                while (lastSnippet != lastProcessedSnippet && lastSnippet != null) {
                    val line = lastSnippet.get().result.scriptInstance
                    if (line != null) newSnippets.add(line)
                    lastSnippet = lastSnippet.previous
                }
                newSnippets.reverse()
                refreshVariables(newSnippets)
                refreshMethods(newSnippets)

                lastProcessedSnippet = evaluator.lastEvaluatedSnippet
            } catch (e: ReflectiveOperationException) {
                lprintln("error,kotlin", "Exception updating current variables $e")
            } catch (e: NullPointerException) {
                lprintln("error,kotlin", "Exception updating current variables $e")
            }
        }

        private fun refreshMethods(lines: List<Any>) {
            for (line in lines) {
                val methods = line.javaClass.methods
                for (method in methods) {
                    if (objectMethods.contains(method) || method.name == "main") {
                        continue
                    }
                    val function = method.kotlinFunction ?: continue
                    context.functions[function.name] = KotlinFunctionInfo(function, line)

                    // Check for @exported annotation
                    if (method.isAnnotationPresent(exported::class.java)) {
                        processExportedFunction(function, line)
                    }
                }
            }
        }

        private fun processExportedFunction(function: KFunction<*>, line: Any) {
            try {
                val exportedName = exportedAlias(function.name)
                val declaration = generateFunctionDeclaration(function, exportedName)

                // Add to box preamble if box is available
                val box = ki.associatedBox
                if (box != null && box is HasProperties) {
                    try {
                        val functionReference = createBoundFunctionReference(function, line)
                        box.setPropertyRaw(exportedName, functionReference, doResync = false)
                        upsertExportedDeclaration(box, exportedName, declaration)
                    } catch (e: Exception) {
                        lprintln("error,kotlin", "Failed to export @exported function '${function.name}': ${e.message}")
                    }
                }
            } catch (e: Exception) {
                lprintln("error,kotlin", "Failed to process @exported function: ${e.message}")
            }
        }

        private fun generateFunctionDeclaration(function: KFunction<*>, exportedName: String): String {
            // Generate a property declaration with function type
            // e.g., "val Box._myFunc by InheritanceFromContext<(Int, String) -> Double>()"

            val params = function.parameters.filter { it.kind != kotlin.reflect.KParameter.Kind.INSTANCE }
            val paramTypes = params.joinToString(", ") { param ->
                simplifyTypeForExport(param.type.toString())
            }
            val returnType = simplifyTypeForExport(function.returnType.toString())

            val functionType = if (params.isEmpty()) {
                "() -> $returnType"
            } else {
                "($paramTypes) -> $returnType"
            }

            // Add the inherit marker /**/ at the end
            return "val Box.$exportedName by InheritanceFromContext<$functionType>() /**/"
        }

        private fun processExportedProperty(name: String, descriptor: KProperty<*>, value: Any?) {
            try {
                val exportedName = exportedAlias(name)
                val declaration = generatePropertyDeclaration(descriptor, exportedName)

                val box = ki.associatedBox
                if (box != null && box is HasProperties) {
                    try {
                        if (value != null) {
                            box.setPropertyRaw(exportedName, value, doResync = true)
                        }
                        upsertExportedDeclaration(box, exportedName, declaration)
                    } catch (e: Exception) {
                        lprintln("error,kotlin", "Failed to export @exported property '$name': ${e.message}")
                    }
                }
            } catch (e: Exception) {
                lprintln("error,kotlin", "Failed to process @exported property '$name': ${e.message}")
            }
        }

        private fun generatePropertyDeclaration(property: KProperty<*>, exportedName: String): String {
            val keyword = if (property is KMutableProperty<*>) "var" else "val"
            val typeName = simplifyTypeForExport(property.returnType.toString())
            return "$keyword Box.$exportedName by InheritanceFromContext<$typeName>() /**/"
        }

        private fun exportedAlias(name: String): String {
            return if (name.startsWith("_")) name else "_$name"
        }

        private fun upsertExportedDeclaration(box: HasProperties, exportedName: String, declaration: String) {
            val currentPreamble = box.getPropertyRaw("preamble", "") as? String ?: ""
            val currentLines = currentPreamble
                .lines()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }

            val filteredLines = currentLines.filterNot {
                isExportedDeclarationLineFor(it, exportedName)
            }
            val nextLines = filteredLines + declaration
            val newPreamble = nextLines.joinToString("\n")

            val previous = exportedDeclarations[exportedName]
            if (newPreamble != currentPreamble || previous != declaration) {
                box.setPropertyRaw("preamble", newPreamble, doResync = true)
                exportedDeclarations[exportedName] = declaration
                lprintln("info,kotlin", "Added @exported declaration to preamble: $declaration")
            }
        }

        private fun isExportedDeclarationLineFor(line: String, exportedName: String): Boolean {
            val withoutPrefixComment = line.trim()
                .replaceFirst(Regex("""^\s*/\*.*?\*/\s*"""), "")
                .trim()
            if (!withoutPrefixComment.endsWith("/**/")) return false

            val nameEscaped = Regex.escape(exportedName)
            val patterns = listOf(
                // Current form: val/var Box._name by ...
                Regex("""^(val|var)\s+Box\.$nameEscaped\b.*\bby\b.*$"""),
                // Legacy form: val/var _name by ...
                Regex("""^(val|var)\s+$nameEscaped\b.*\bby\b.*$"""),
                // Legacy typed forms: val/var Box._name : ...
                Regex("""^(val|var)\s+Box\.$nameEscaped\b\s*:\s*.*$"""),
                // Legacy typed forms: val/var _name : ...
                Regex("""^(val|var)\s+$nameEscaped\b\s*:\s*.*$""")
            )
            return patterns.any { it.matches(withoutPrefixComment) }
        }

        private fun createBoundFunctionReference(function: KFunction<*>, line: Any): Any {
            val arity = function.parameters.count { it.kind != KParameter.Kind.INSTANCE }
            val functionInterface = when (arity) {
                0 -> kotlin.jvm.functions.Function0::class.java
                1 -> kotlin.jvm.functions.Function1::class.java
                2 -> kotlin.jvm.functions.Function2::class.java
                3 -> kotlin.jvm.functions.Function3::class.java
                4 -> kotlin.jvm.functions.Function4::class.java
                5 -> kotlin.jvm.functions.Function5::class.java
                6 -> kotlin.jvm.functions.Function6::class.java
                7 -> kotlin.jvm.functions.Function7::class.java
                8 -> kotlin.jvm.functions.Function8::class.java
                9 -> kotlin.jvm.functions.Function9::class.java
                10 -> kotlin.jvm.functions.Function10::class.java
                11 -> kotlin.jvm.functions.Function11::class.java
                12 -> kotlin.jvm.functions.Function12::class.java
                13 -> kotlin.jvm.functions.Function13::class.java
                14 -> kotlin.jvm.functions.Function14::class.java
                15 -> kotlin.jvm.functions.Function15::class.java
                16 -> kotlin.jvm.functions.Function16::class.java
                17 -> kotlin.jvm.functions.Function17::class.java
                18 -> kotlin.jvm.functions.Function18::class.java
                19 -> kotlin.jvm.functions.Function19::class.java
                20 -> kotlin.jvm.functions.Function20::class.java
                21 -> kotlin.jvm.functions.Function21::class.java
                22 -> kotlin.jvm.functions.Function22::class.java
                else -> throw IllegalArgumentException("Cannot export function '${function.name}': arity $arity is not supported")
            }

            return Proxy.newProxyInstance(
                line.javaClass.classLoader,
                arrayOf(functionInterface)
            ) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        val values = args?.toList() ?: emptyList()
                        val callArgs = ArrayList<Any?>(function.parameters.size)
                        var valueIndex = 0
                        for (parameter in function.parameters) {
                            when (parameter.kind) {
                                KParameter.Kind.INSTANCE -> callArgs.add(line)
                                else -> {
                                    if (valueIndex >= values.size) {
                                        throw IllegalArgumentException(
                                            "Missing argument ${valueIndex + 1} for @exported function '${function.name}'"
                                        )
                                    }
                                    callArgs.add(values[valueIndex++])
                                }
                            }
                        }
                        function.call(*callArgs.toTypedArray())
                    }

                    "toString" -> functionSignature(function)
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> throw UnsupportedOperationException(
                        "Unsupported proxy method '${method.name}' for @exported function '${function.name}'"
                    )
                }
            }
        }

        private fun simplifyTypeForExport(typeName: String): String {
            // Whitelist of types that are known to be imported in other code boxes
            val whitelistedPrefixes = setOf(
                "kotlin.",
                "afield.",
                "org.joml.",
                // Common Kotlin types
                "Int", "Long", "Short", "Byte",
                "Float", "Double",
                "Boolean", "Char", "String",
                "Unit", "Any", "Nothing",
                // Collections
                "List", "Set", "Map", "Array",
                "MutableList", "MutableSet", "MutableMap",
                "Sequence", "Iterable", "Collection",
                // Common function types are already in format (A, B) -> C
            )

            // Check if the type is whitelisted
            if (whitelistedPrefixes.any { typeName.startsWith(it) }) {
                return typeName
            }

            // For non-whitelisted types, try to find a generic supertype
            // This is a simplified approach - we'll look for common patterns
            return when {
                typeName.contains("List") -> "List<*>"
                typeName.contains("Set") -> "Set<*>"
                typeName.contains("Map") -> "Map<*, *>"
                typeName.contains("Collection") -> "Collection<*>"
                typeName.contains("Iterable") -> "Iterable<*>"
                typeName.contains("Sequence") -> "Sequence<*>"
                else -> "Any?" // Fallback to the most generic type
            }
        }

        @Throws(ReflectiveOperationException::class)
        private fun refreshVariables(lines: List<Any>) {
            for (line in lines) {
                val fields = line.javaClass.declaredFields
                findVariables(fields, line)
            }
        }

        @Throws(IllegalAccessException::class)
        private fun findVariables(fields: Array<Field>, o: Any) {
            for (field in fields) {
                val fieldName = field.name
                if (fieldName.contains("$\$implicitReceiver") || fieldName.contains("script$") || fieldName.endsWith("\$delegate")) {
                    continue
                }

                field.isAccessible = true
                val value = field.get(o)
                val descriptor = field.kotlinProperty
                if (descriptor != null) {
                    context.vars[descriptor.name] = KotlinVariableInfo(value, descriptor, o)
                    val isExported = field.isAnnotationPresent(exported::class.java) ||
                            descriptor.annotations.any { it.annotationClass == exported::class } ||
                            descriptor.getter.annotations.any { it.annotationClass == exported::class }
                    if (isExported) {
                        processExportedProperty(descriptor.name, descriptor, value)
                    }
                }
            }
        }

        companion object {
            private val objectMethods = HashSet(listOf(*Any::class.java.methods))
        }
    }

}
