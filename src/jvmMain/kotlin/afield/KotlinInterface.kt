package afield

//import org.jetbrains.kotlin.scripting.ide_services.compiler.KJvmReplCompilerWithIdeServices
import afield.logger.formatMillis
import afield.logger.lblock
import afield.logger.lprintln
import afield.macros.ContainingFunction
import afield.macros.MacroRegistry
import afield.stdlib.applyInsertToPreamble
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
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
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
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

    override val implicitReceivers: List<KotlinType> = emptyList(),

    override var classLoader: ClassLoader? = null, val filePrefix: String = "EVAL_"
) : KotlinScriptEngine {

    override val compilationDispatcher get() = compilationThread
    override var sourceProperty: String = "code"

    internal inner class SourceCodeImpl(number: Int, override val text: String) : SourceCode {
        override val name: String? = fieldSourceName(filePrefix, sourceProperty, number)
        override val locationId: String? = "LOCATION_$number"

        override fun equals(other: Any?): Boolean {
            // `this === other`, not `==`: `==` is a call to this same equals, and the self-call
            // recursed until the stack ran out. Latent until a compiler that compares SourceCode
            // instances came along (2.4.20 does), and then EVERY compile died in here.
            return this === other || (other as? SourceCodeImpl)?.let { text == it.text } == true
        }

        override fun hashCode(): Int {
            return text.hashCode()
        }
    }

    var evaluator: BasicJvmReplEvaluator
    var compiler: KJvmReplCompilerWithIdeServices
//    var compiler: K2KJvmReplCompilerWithCompletion

    //    var evalConfig: ScriptEvaluationConfiguration
    override var compilerConfig: ScriptCompilationConfiguration
    val contextUpdater: ContextUpdater
    override val context: KotlinContext

    // Reference to the box this KotlinInterface is associated with (for @exported support)
    override var associatedBox: Any? = null

    private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

    init {
        assert(properties.size == propertyClasses.size)
        assert(properties.keys.containsAll(propertyClasses.keys))
        if (classLoader == null) classLoader = Thread.currentThread().contextClassLoader

        if (sharedClassLoader == null) {
            sharedClassLoader = URLClassLoader(arrayOf(), classLoader)

        }

        classLoader = sharedClassLoader
        MavenClasspathManager.attachResolvedTo(classLoader)

        val myHostConfiguration = defaultJvmScriptingHostConfiguration.with {
            jvm {
                baseClassLoader(classLoader!!)
            }
        }

        compilerConfig = ScriptCompilationConfiguration {
            defaultImports(DependsOn::class, Repository::class, exported::class, rootExported::class, metarootExported::class)
            hostConfiguration.update {
                myHostConfiguration
            }
            this@KotlinInterface.implicitReceivers.forEach { implicitReceivers.append(it) }

            jvm {
                jvmTarget.put("24")
                _languageVersion.put(fieldKotlinLanguageVersion)
                compilerOptions.put(fieldKotlinJvmCompilerOptions)

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

    private fun compileSnippetKind(s: String): String {
        return when {
            s == intro -> "field-startup-imports"
            s.isBlank() -> "blank"
            s.trimStart().startsWith("import ") -> "imports-or-preamble"
            else -> "user-code"
        }
    }

    private fun compileSnippetPreview(s: String, maxLength: Int = 120): String {
        val compact = s.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(" | ")
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
    }

    private fun resolveBoxContext(implicits: List<Any> = emptyList()): Box? {
        val fromImplicits = implicits.firstOrNull { it is Box } as? Box
        if (fromImplicits != null) return fromImplicits
        val fromAssociated = associatedBox as? Box
        if (fromAssociated != null) return fromAssociated
        return Box.context.get()
    }

    suspend fun compile(s: String): ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>> {
        val code = SourceCodeImpl(id++, s)
        val snapshot = context.debugScripts.recordCompile(code.name ?: "snippet_${id - 1}", s)
        var r: ResultWithDiagnostics<LinkedSnippet<KJvmCompiledScript>>? = null

        lprintln(
            "info,kotlin,perf",
            "\t\t compile request engine=KotlinInterface filePrefix=$filePrefix source=${code.name} kind=${
                compileSnippetKind(
                    s
                )
            } length=${s.length} preview=${compileSnippetPreview(s)}"
        )
        lprintln("info,kotlin,perf", "\t\t compile code $s")

        val ns = measureNanoTime {
            if (KotlinEngineConfig.offloadCompilation) {
                // K1 is NOT thread-safe — always use the single compilationThread
                r = withContext(compilationDispatcher) {
                    compiler.compile(code, compilerConfig)
                }
            } else {
                r = compiler.compile(code, compilerConfig)
            }
        }
        lprintln("info,kotlin,compile", "compile=${formatMillis(ns)}")
        if (r is ResultWithDiagnostics.Failure) {
            context.debugScripts.discard(snapshot)
        }

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
                if (r is ResultWithDiagnostics.Success) {
                    val scriptInstance = r.value.get().result.scriptInstance
                    context.debugScripts.attachLatestRuntimeClass(scriptInstance?.javaClass?.name)
                }
                if (updateContext) contextUpdater.update()
            }
            lprintln("info,kotlin,evaluate", "evaluate=${formatMillis(ns)}")
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
                if (r is ResultWithDiagnostics.Success) {
                    val scriptInstance = r.value.get().result.scriptInstance
                    context.debugScripts.attachLatestRuntimeClass(scriptInstance?.javaClass?.name)
                }
                if (updateContext) contextUpdater.update()
            }
            lprintln("info,kotlin,evaluate", "evaluate=${formatMillis(ns)}")
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


    override suspend fun complete(s: String, cursor: Int): CompletionResult {
        val code = SourceCodeImpl(id++, s)

        return try {
            val cc = compiler.complete(code, cursor.toSourceCodePositionWithNewAbsolute(code, code)!!, compilerConfig)
            cc.valueOrNull()?.toList()?.let { completionList ->
                getResult(s, cursor, completionList)
            } ?: CompletionResult.Empty(s, cursor)
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            CompletionResult.Error(e.javaClass.simpleName, e.message ?: "", sw.toString())
        }

    }


    override fun completeAndWait(s: String, cursor: Int): CompletionResult {
        return runBlocking { complete(s, cursor) }
    }

    override fun applyMacros(box: Box, s: String, skipMacros: Set<String>, previewOnly: Boolean): String {
        var rewritten = s
        val needsRerun = false
        lblock("debug,kotlin", "rewriting AST analysis") {
            do {
                val cs = compiler.state.getCompilationState(compilerConfig)
                val env = cs.environment
                val project = env.project

                val factory = PsiFileFactory.getInstance(project)
                val lang = Language.findLanguageByID("kotlin")!!
                val instance = factory.createFileFromText(lang, rewritten)

                val edits = mutableListOf<Pair<Pair<Int, Int>, String>>()

                instance.forEachDescendantOfType<PsiElement> { expression ->
                    when (expression) {
                        is KtCallExpression -> {
                            expression.children.forEach {
                                when (it) {
                                    is KtNameReferenceExpression -> {
                                        if (it.text in skipMacros) return@forEach

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


                                        // won't work for inferred return types
                                        val containingFunction =
                                            PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java)
                                        val containingFunctionName = containingFunction?.name
                                        val valueParameterList = containingFunction?.valueParameterList?.text
                                        val returnParameterText = containingFunction?.typeReference?.text

                                        val containingFunctionInfo =
                                            if (containingFunctionName != null && valueParameterList != null && returnParameterText != null)
                                                ContainingFunction(
                                                    containingFunctionName,
                                                    valueParameterList,
                                                    returnParameterText
                                                )
                                            else null

                                        val e = MacroRegistry.rewrite(box, it.text, t, containingFunctionInfo)

                                        if (e != null) {
                                            if (!previewOnly) {
                                                if (e.start.length > 0) {
                                                    edits.add((0 to 0) to e.preamble)
                                                }
                                                if (e.afterImports.length > 0) {
                                                    val a = findLastImport(rewritten)
                                                    edits.add((a to a) to e.afterImports)
                                                }
                                            }

                                            val replacement =
                                                if (expression.parent is KtBlockExpression || expression.parent is KtScriptInitializer)
                                                    e.standaloneHere ?: e.here
                                                else
                                                    e.here
                                            edits.add((expression.startOffset to expression.endOffset) to replacement)

                                            if (!previewOnly) {
                                                if (e.preamble.isNotBlank() || e.preambleAfterImports.isNotBlank()) {
                                                    applyInsertToPreamble(box, e)
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
                    rewritten = rewritten.replaceRange(e.first.first, e.first.second, e.second)
                    val shrink = (e.first.second - e.first.first) - e.second.length
                    edits.forEachIndexed { index, it ->
                        val a = if (it.first.first > e.first.first) it.first.first - shrink else it.first.first
                        val b = if (it.first.second > e.first.first) it.first.second - shrink else it.first.second
                        edits[index] = (a to b) to it.second
                    }
                }
            } while (needsRerun)

            lprintln("debug,kotlin", "final string is:\n$rewritten")
        }
        return rewritten
    }


    override fun findLastImport(s: String): Int {
        if (s.contains("import ")) {
            val m = s.findLastAnyOf(listOf("import "))!!.first
            val m2 = s.findAnyOf(listOf("\n"), startIndex = m)
            if (m2 == null) return s.length
            else return m2.first + 1
        } else return 0

    }

    override fun parseAndRewrite(s: String, rules: List<(PsiElement, String) -> String>): String {
        return s;

    }

    override var execNumber = 0
    override suspend fun exec(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit,
        onEvalError: (List<ScriptDiagnostic>) -> Unit,
        onEvalException: (Throwable) -> Unit,
        implicits: List<Any>,
    ): ResultValue? {

        return when (val r = compile(s)) {
            is ResultWithDiagnostics.Success -> {
                when (val r2 = evaluate(r.value, implicits = implicits)) {
                    is ResultWithDiagnostics.Success -> {
                        when (val e = r2.value.get().result) {
                            is ResultValue.Error -> {
                                lprintln("error,kotlin", "Evaluation Error ${e.error} / ${e.error.javaClass}")
                                onEvalException(translateKotlinEvalException(e.error, s))
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
    }

    override fun execAndWait(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit,
        onEvalError: (List<ScriptDiagnostic>) -> Unit,
        onEvalException: (Throwable) -> Unit,
        implicits: List<Any>
    ): ResultValue? {
        return runBlocking {
            exec(s, onCompileError, onEvalError, onEvalException, implicits)
        }
    }

    /** K1 implementation of [PreparedSnippet]; see that interface for the evaluation contract. */
    inner class CompiledSnippet internal constructor(
        private val compiled: LinkedSnippet<KJvmCompiledScript>,
        private val defaultImplicits: List<Any>
    ) : PreparedSnippet {
        override suspend fun eval(
            updateContext: Boolean,
            implicits: List<Any>,
            onEvalError: (List<ScriptDiagnostic>) -> Unit,
            onEvalException: (Throwable) -> Unit
        ): ResultValue? {
            @Suppress("NAME_SHADOWING")
            val implicits = implicits.ifEmpty { defaultImplicits }
            return when (val r = evaluate(compiled, updateContext, implicits)) {
                is ResultWithDiagnostics.Success -> {
                    when (val e = r.value.get().result) {
                        is ResultValue.Error -> {
                            lprintln("error,kotlin", "Evaluation Error ${e.error} / ${e.error.javaClass}")
                            onEvalException(translateKotlinEvalException(e.error))
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

        override fun evalAndWait(
            updateContext: Boolean,
            implicits: List<Any>,
            onEvalError: (List<ScriptDiagnostic>) -> Unit,
            onEvalException: (Throwable) -> Unit
        ): ResultValue? {
            return runBlocking {
                eval(updateContext, implicits, onEvalError, onEvalException)
            }
        }
    }

    /** Compile a K1 [CompiledSnippet]; the shared contract is on the overridden interface method. */
    override suspend fun compileSnippet(
        s: String,
        implicits: List<Any>,
        onCompileError: (List<ScriptDiagnostic>) -> Unit
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

    /** Blocking wrapper around [compileSnippet]. */
    override fun compileSnippetAndWait(
        s: String,
        implicits: List<Any>,
        onCompileError: (List<ScriptDiagnostic>) -> Unit
    ): CompiledSnippet? {
        return runBlocking {
            compileSnippet(s, implicits, onCompileError)
        }
    }


    companion object {

        var sharedClassLoader: ClassLoader? = null

        // multi-threaded compilation broken in current kotlin compiler.
        // Daemon-threaded so this shared compile dispatcher never keeps a JVM alive
        // (it leaked as a non-daemon `pool-N` thread in headless smoke tests).
        var compilationThread = Executors.newFixedThreadPool(1) { runnable ->
            Thread(runnable, "afield-ki-compile").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        // Delegates to shared top-level functions for backwards compatibility
        fun functionSignature(function: KFunction<*>) = afield.functionSignature(function)
        fun shortenType(name: String) = afield.shortenType(name)

        // Keep CompletionTokenBounds as a typealias-like accessor for backwards compatibility
        @Suppress("unused")
        val CompletionTokenBounds = object {
            operator fun invoke(start: Int, end: Int) = afield.CompletionTokenBounds(start, end)
        }

        fun getTokenBounds(buf: String, cursor: Int) = afield.getTokenBounds(buf, cursor)

        fun getResult(
            code: String, cursor: Int, completions: List<SourceCodeCompletionVariant>
        ) = getCompletionResult(code, cursor, completions)
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
                if (newSnippets.isNotEmpty()) {
                    context.generation++
                }

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

                    val exportTargets = exportTargetsForFunction(method)
                    if (exportTargets.isNotEmpty()) {
                        processExportedFunction(function, line, exportTargets)
                    }
                }
            }
        }

        private enum class ExportTarget {
            Local,
            Root,
            Metaroot
        }

        private fun exportTargetsForFunction(method: java.lang.reflect.Method): Set<ExportTarget> {
            val targets = linkedSetOf<ExportTarget>()
            if (method.isAnnotationPresent(exported::class.java)) targets += ExportTarget.Local
            if (method.isAnnotationPresent(rootExported::class.java)) targets += ExportTarget.Root
            if (method.isAnnotationPresent(metarootExported::class.java)) targets += ExportTarget.Metaroot
            return targets.withLocalExport()
        }

        private fun exportTargetsForProperty(field: Field?, descriptor: KProperty<*>): Set<ExportTarget> {
            val targets = linkedSetOf<ExportTarget>()
            val annotations = descriptor.annotations + descriptor.getter.annotations
            if (field?.isAnnotationPresent(exported::class.java) == true ||
                annotations.any { it.annotationClass == exported::class }
            ) targets += ExportTarget.Local
            if (field?.isAnnotationPresent(rootExported::class.java) == true ||
                annotations.any { it.annotationClass == rootExported::class }
            ) targets += ExportTarget.Root
            if (field?.isAnnotationPresent(metarootExported::class.java) == true ||
                annotations.any { it.annotationClass == metarootExported::class }
            ) targets += ExportTarget.Metaroot
            return targets.withLocalExport()
        }

        private fun Set<ExportTarget>.withLocalExport(): Set<ExportTarget> =
            if (isEmpty() || contains(ExportTarget.Local)) this else this + ExportTarget.Local

        private fun boxesForExportTargets(targets: Set<ExportTarget>): List<Box> {
            val box = ki.associatedBox as? Box ?: return emptyList()
            return targets.mapNotNull {
                when (it) {
                    ExportTarget.Local -> box
                    ExportTarget.Root -> box.root
                    ExportTarget.Metaroot -> metaroot
                }
            }.distinctBy { it.id }
        }

        private fun processExportedFunction(function: KFunction<*>, line: Any, targets: Set<ExportTarget>) {
            try {
                val exportedName = exportedAlias(function.name)
                val declaration = generateFunctionDeclaration(function, exportedName)

                boxesForExportTargets(targets).forEach { box ->
                    try {
                        val functionReference = createBoundFunctionReference(function, line)
                        box.ki.context.functions[function.name] = KotlinFunctionInfo(function, line)
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
                ExportedTypeNames.render(param.type, ki.classLoader)
            }
            val returnType = ExportedTypeNames.render(function.returnType, ki.classLoader)

            val functionType = if (params.isEmpty()) {
                "() -> $returnType"
            } else {
                "($paramTypes) -> $returnType"
            }

            // Add the inherit marker /**/ at the end
            return "val Box.$exportedName by InheritanceFromContext<$functionType>() /**/"
        }

        private fun processExportedProperty(
            name: String,
            descriptor: KProperty<*>,
            value: Any?,
            line: Any,
            targets: Set<ExportTarget>
        ) {
            try {
                val exportedName = exportedAlias(name)
                val declaration = generatePropertyDeclaration(descriptor, exportedName)

                boxesForExportTargets(targets).forEach { box ->
                    try {
                        box.ki.context.vars[name] = KotlinVariableInfo(value, descriptor, line)
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
            val typeName = ExportedTypeNames.render(property.returnType, ki.classLoader)
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
                0 -> Function0::class.java
                1 -> Function1::class.java
                2 -> Function2::class.java
                3 -> Function3::class.java
                4 -> Function4::class.java
                5 -> Function5::class.java
                6 -> Function6::class.java
                7 -> Function7::class.java
                8 -> Function8::class.java
                9 -> Function9::class.java
                10 -> Function10::class.java
                11 -> Function11::class.java
                12 -> Function12::class.java
                13 -> Function13::class.java
                14 -> Function14::class.java
                15 -> Function15::class.java
                16 -> Function16::class.java
                17 -> Function17::class.java
                18 -> Function18::class.java
                19 -> Function19::class.java
                20 -> Function20::class.java
                21 -> Function21::class.java
                22 -> Function22::class.java
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

        @Throws(ReflectiveOperationException::class)
        private fun refreshVariables(lines: List<Any>) {
            for (line in lines) {
                val seen = mutableSetOf<String>()
                val fields = line.javaClass.declaredFields
                findVariables(fields, line, seen)
                findKotlinProperties(line, seen)
            }
        }

        @Throws(IllegalAccessException::class)
        private fun findVariables(fields: Array<Field>, o: Any, seen: MutableSet<String>) {
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
                    seen += descriptor.name
                    val exportTargets = exportTargetsForProperty(field, descriptor)
                    if (exportTargets.isNotEmpty()) {
                        processExportedProperty(descriptor.name, descriptor, value, o, exportTargets)
                    }
                }
            }
        }

        private fun findKotlinProperties(o: Any, seen: MutableSet<String>) {
            for (descriptor in o::class.memberProperties) {
                val name = descriptor.name
                if (name in seen || name.contains("$\$implicitReceiver") || name.contains("script$")) continue
                if (descriptor.parameters.size != 1) continue

                val value = runCatching {
                    descriptor.isAccessible = true
                    descriptor.getter.call(o)
                }.getOrNull() ?: continue

                context.vars[name] = KotlinVariableInfo(value, descriptor, o)
                seen += name

                val exportTargets = exportTargetsForProperty(null, descriptor)
                if (exportTargets.isNotEmpty()) {
                    processExportedProperty(name, descriptor, value, o, exportTargets)
                }
            }
        }

        companion object {
            private val objectMethods = HashSet(listOf(*Any::class.java.methods))
        }
    }

}
