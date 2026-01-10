package afield

import afield.logger.lblock
import afield.logger.lprintln
import afield.macros.MacroRegistry
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
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.Executors
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
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

class KotlinInterface(
    val propertyClasses: Map<String, KClass<*>> = emptyMap(), val properties: Map<String, Any?> = emptyMap(),

    val implicitReceivers: List<KotlinType> = emptyList(),

    var classLoader: ClassLoader? = null, val filePrefix: String = "EVAL_"
) {

    internal inner class SourceCodeImpl(number: Int, override val text: String) : SourceCode {
        override val name: String? = "$filePrefix\$$number"
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
            defaultImports(DependsOn::class, Repository::class)
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
        contextUpdater = ContextUpdater(context, evaluator)
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
    suspend fun compile(s: String): ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>> {
        val code = SourceCodeImpl(id++, s)
        var r: ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>>? = null
//        val r2 = cache.get(s)
//        if (r2!=null) return r2

        coroutineScope {
//            async(Runloop.miscPool.asCoroutineDispatcher()) { // Not until K2
                async(compilationThread) {
                val ns = measureNanoTime {
                    r = compiler.compile(code, compilerConfig)
                }
                lprintln("info,kotlin,perf", "\t\tTIME<" + (ns / 1_000_000.0) + "ms> = compile (async), code = ${code.text}")
            }.await()
        }

//        cache.put(s, r)
        return r!!
    }


    suspend fun evaluate(
        e: LinkedSnippet<KJvmCompiledScript>, updateContext: Boolean = false
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
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
        return r
    }

    suspend fun evaluate(
        e: LinkedSnippet<KJvmCompiledScript>, updateContext: Boolean = true, implicits: List<Any>
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
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
        return r
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

//                    for (i in expression.parents) print("$i, ")
                    when (expression) {

                        is KtCallExpression -> {
//                            lprintln(" -- call expression, calling -- ${expression.name} ${expression.startOffset} -> ${expression.endOffset}")
                            expression.children.forEach {
                                when (it) {
                                    is KtNameReferenceExpression -> {
                                        lprintln("debug,kotlin", "child (named): ${it} ${it.text} ${it.javaClass} ${it.startOffset} -> ${it.endOffset} ${it.reference}")

                                        var ns = it.nextSibling
                                        val t = mutableListOf<String>()
                                        while (ns != null) {
                                            t.add(ns.text)
                                            ns = ns.nextSibling
                                        }
//                                        lprintln("accumulated arguments $t")

                                        val e = MacroRegistry.rewrite(box, it.text, t)
//                                        lprintln("rewrite got ${e}")
                                        if (e != null) {
                                                if (e.start.length > 0) {
                                                    edits.add((0 to 0) to e.preamble)
                                                }
                                                if (e.afterImports.length > 0) {
                                                    val a = findLastImport(s)
                                                    edits.add((a to a) to e.afterImports)
                                                }

                                            edits.add((expression.startOffset to expression.endOffset) to e.here)

                                            (e.target ?: box).let {box ->
                                                if (e.preamble.length > 0) {
                                                    box.preamble = "\n" + e.preamble.trim() + "\n" + box.preamble
                                                }
                                                if (e.preambleAfterImports.length > 0) {
                                                    box.preamble =
                                                        box.preamble + "\n" + e.preambleAfterImports.trim() + "\n"
                                                }
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
        onCompileError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,kotlin", "onCompileError default handler $it") },
        onEvalError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,kotlin", "onEvalError default handler $it") },
        onEvalException: (Throwable) -> Unit = { lprintln("error,kotlin", "onEvalException default handler $it"); throw it },
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
            val filteredCompletions = completions.filter { !it.text.contains("clojure", ignoreCase = true) }.filter { !it.text.contains("$", ignoreCase = true) }
            val bounds = getTokenBounds(code, cursor)
            return CompletionResult.Success(filteredCompletions.map { it.text }, bounds, filteredCompletions, code, cursor)
        }


        @JvmStatic
        fun main3(a: Array<String>) {

            val a1 = KotlinInterface(mapOf("BANANA" to Int::class), mapOf("BANANA" to 10))
            var a2 = KotlinInterface(mapOf("BANANA" to Int::class), mapOf("BANANA" to 10))
            lprintln("info,kotlin", "###   " + a1.execAndWait("val x = mutableListOf(1,2,3)"))
            lprintln("info,kotlin", "###   " + a1.execAndWait("x"))
            runBlocking {

                val cc = a1.complete("x.", 2)

                lprintln("info,kotlin", "###   " + cc)
            }

        }

        @JvmStatic
        fun main(a: Array<String>) {

            val a1 = KotlinInterface(mapOf("BANANA" to Int::class), mapOf("BANANA" to 10))
            lprintln(
                "info,kotlin",
                "###   " + a1.parseAndRewrite(
                    """
                val x = mutableListOf(1,2,3)
                fun banana() : Int {
                    return 5 
                }
                val y = banana()
                """.trimIndent(), emptyList()
                )
            )

        }

        @JvmStatic
        fun main2(a: Array<String>) {
            lprintln("info,kotlin", "hello world")
            val a = KotlinInterface(mapOf("BANANA" to Int::class), mapOf("BANANA" to 10))
            for (i in 0 until 2) {
                runBlocking {
                    val r = a.exec("var x = ${i} + BANANA \n lprintln(\"from code \"+x)")
                    lprintln("info,kotlin", "R1 = $r")
                    val r2 = a.exec("var x = ${i} + BANANA2 \n lprintln(\"from code \"+x)", onCompileError = {

                        lprintln("error,kotlin", "R2(onCompileError) = $it")
                    })
                    lprintln("info,kotlin", "R2 = $r2")

                    val r3 = a.exec("var x = ${i} + BANANA \n lprintln(\"from code \"+x/(1-1))", onEvalException = {
                        it.printStackTrace()
                    })
                    lprintln("info,kotlin", "R3 = $r3")

                    val r4 = a.exec("var x = ${i} + BANANA \n x")
                    lprintln("info,kotlin", "R4 = $r4")

                }

            }
        }
    }

    class KotlinContext(
        val vars: HashMap<String, KotlinVariableInfo> = HashMap(),
        val functions: HashMap<String, KotlinFunctionInfo> = HashMap()
    )


    class KotlinFunctionInfo(val function: KFunction<*>, val line: Any) : Comparable<KotlinFunctionInfo> {

        val name: String
            get() = function.name

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

    class KotlinVariableInfo(val value: Any?, val descriptor: KProperty<*>, val line: Any) {

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
    }

    class ContextUpdater(val context: KotlinContext, private val evaluator: BasicJvmReplEvaluator) {

        private var lastProcessedSnippet: LinkedSnippet<KJvmEvaluatedSnippet>? = null

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
                }
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
                if (fieldName.contains("$\$implicitReceiver") || fieldName.contains("script$")) {
                    continue
                }

                field.isAccessible = true
                val value = field.get(o)
                val descriptor = field.kotlinProperty
                if (descriptor != null) {
                    context.vars[fieldName] = KotlinVariableInfo(value, descriptor, o)
                }
            }
        }

        companion object {
            private val objectMethods = HashSet(listOf(*Any::class.java.methods))
        }
    }

}
