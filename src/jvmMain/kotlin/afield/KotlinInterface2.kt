package afield

import afield.ai.KotlinSourceIndex
import afield.logger.lblock
import afield.logger.lprintln
import afield.macros.ContainingFunction
import afield.macros.MacroRegistry
import afield.stdlib.applyInsertToPreamble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompiler
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplEvaluator
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.compiler.plugin.services.FirReplHistoryProviderImpl
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.services.isReplSnippetSource
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.reflect.*
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.kotlinProperty
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.*
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.host.with
import kotlin.script.experimental.impl._languageVersion
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvm.util.determineSep
import kotlin.script.experimental.jvm.util.toSourceCodePosition
import kotlin.script.experimental.util.LinkedSnippet
import kotlin.system.measureNanoTime


class KotlinInterface2(
    val propertyClasses: Map<String, KClass<*>> = emptyMap(),
    val properties: Map<String, Any?> = emptyMap(),
    val implicitReceivers: List<KotlinType> = emptyList(),
    override var classLoader: ClassLoader? = null,
    val filePrefix: String = "EVAL_"
) : KotlinScriptEngine {

    override val compilationDispatcher get() = Dispatchers.Default

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

    var evaluator: K2ReplEvaluator
    var compiler: K2ReplCompiler
    override var compilerConfig: ScriptCompilationConfiguration
    val contextUpdater: ContextUpdater
    override val context: KotlinContext

    // Reference to the box this KotlinInterface is associated with (for @exported support)
    override var associatedBox: Any? = null

    // Disposable for the K2 REPL compilation state
    private val rootDisposable: Disposable = Disposer.newDisposable("K2ReplCompilationState")

    // Message collector for the K2 REPL compiler
    private val messageCollector = ScriptDiagnosticsMessageCollector(null)

    // The K2 compilation state (holds FIR sessions, project environment, etc.)
    private val compilationState: org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompilationState

    // PSI project reused from the K2 compiler's environment (compilerContext is internal, so accessed via reflection)
    private val psiProject by lazy {
        val field = compilationState::class.java.getDeclaredField("compilerContext")
        field.isAccessible = true
        val ctx = field.get(compilationState)
        val envField = ctx::class.java.getDeclaredField("environment")
        envField.isAccessible = true
        val env = envField.get(ctx) as KotlinCoreEnvironment
        env.project
    }

    // K2 can compile different REPL states concurrently, but a single
    // K2ReplCompilationState is not safe to enter from overlapping snippets.
    private val compileMutex = Mutex()

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
            repl {
                firReplHistoryProvider(FirReplHistoryProviderImpl())
                isReplSnippetSource { _, _ -> true }
            }
        }

        compilerConfig = ScriptCompilationConfiguration {
            defaultImports(DependsOn::class, Repository::class, exported::class)
            hostConfiguration.update {
                myHostConfiguration
            }
            this@KotlinInterface2.implicitReceivers.forEach { implicitReceivers.append(it) }

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

        // Create K2 REPL compilation state
        compilationState = K2ReplCompiler.createCompilationState(
            messageCollector,
            rootDisposable,
            compilerConfig,
            myHostConfiguration
        )

        compiler = K2ReplCompiler(compilationState)
        evaluator = K2ReplEvaluator()

        context = KotlinContext()
        contextUpdater = ContextUpdater(context, evaluator, this)
    }

    fun configureMavenDepsOnAnnotations(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        notify("configuring dependencies in script", "execution")
        val annotations =
            context.collectedData?.get(ScriptCollectedData.collectedAnnotations)?.takeIf { it.isNotEmpty() }
                ?: return context.compilationConfiguration.asSuccess()
        return runBlocking {
            notify("running resolver for dependencies", "execution")
            resolver.resolveFromScriptSourceAnnotations(annotations)
        }.onSuccess {
            notify("finished running resolver", "execution")
            context.compilationConfiguration.with {
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
            }.asSuccess()
        }
    }

    var id = 0

    var cache = mutableMapOf<String, ResultWithDiagnostics<LinkedSnippet<CompiledScript>>?>()

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

    private fun Throwable.causeChain(): List<Throwable> {
        val out = mutableListOf<Throwable>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = this
        while (current != null && seen.add(current)) {
            out += current
            current = current.cause
        }
        return out
    }

    private fun firstMessageLine(throwable: Throwable): String {
        return throwable.message
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: throwable::class.qualifiedName
            ?: throwable.javaClass.name
    }

    private fun compilerExceptionDiagnostic(throwable: Throwable): ScriptDiagnostic {
        val chain = throwable.causeChain()
        val root = chain.lastOrNull() ?: throwable
        val hasImplicitReceiverFailure = chain.any {
            it.message?.contains("SCRIPT_IMPLICIT_RECEIVER") == true ||
                it.message?.contains("\$script_receiver_") == true
        }

        val message = buildString {
            append("Kotlin compiler exception: ")
            append(throwable::class.qualifiedName ?: throwable.javaClass.name)
            append(": ")
            append(firstMessageLine(throwable))

            if (root !== throwable) {
                append("\nCaused by: ")
                append(root::class.qualifiedName ?: root.javaClass.name)
                append(": ")
                append(firstMessageLine(root))
            }

            if (hasImplicitReceiverFailure) {
                append("\n\nK2 could not codegen a named function that captures the script's implicit Box receiver. ")
                append("Make that receiver explicit, for example `fun Box.show() { ... }`, ")
                append("or pass the Box explicitly.")
            }
        }

        return ScriptDiagnostic(
            ScriptDiagnostic.unspecifiedException,
            message,
            ScriptDiagnostic.Severity.ERROR,
            null,
            null,
            throwable
        )
    }

    suspend fun compile(s: String): ResultWithDiagnostics<LinkedSnippet<CompiledScript>> {
        return compileMutex.withLock {
            val code = SourceCodeImpl(id++, s)
            val snapshot = context.debugScripts.recordCompile(code.name ?: "snippet_${id - 1}", s)

            lprintln(
                "info,kotlin,perf",
                "\t\t compile request engine=KotlinInterface2 filePrefix=$filePrefix source=${code.name} kind=${compileSnippetKind(s)} length=${s.length} preview=${compileSnippetPreview(s)}"
            )
            lprintln("info,kotlin,perf", "\t\t compile code $s")

            // Clear diagnostics from previous snippet. The collector is shared by this K2 REPL state.
            messageCollector.clear()

            var r: ResultWithDiagnostics<LinkedSnippet<CompiledScript>>? = null
            val ns = measureNanoTime {
                try {
                    if (KotlinEngineConfig.offloadCompilation) {
                        // Offload the expensive compiler work, but keep this REPL state single-entered.
                        r = withContext(compilationDispatcher) {
                            compiler.compile(code, compilerConfig)
                        }
                    } else {
                        r = compiler.compile(code, compilerConfig)
                    }
                } catch (t: Throwable) {
                    context.debugScripts.discard(snapshot)
                    lprintln("error,kotlin", "compiler exception while compiling snippet")
                    lprintln("error,kotlin", t.stackTraceToString())
                    r = ResultWithDiagnostics.Failure(compilerExceptionDiagnostic(t))
                }
            }
            lprintln("info,kotlin,perf", "\t\tTIME<" + (ns / 1_000_000.0) + "ms> = compile")
            if (r is ResultWithDiagnostics.Failure) {
                context.debugScripts.discard(snapshot)
            }

            r!!
        }
    }


    suspend fun evaluate(
        e: LinkedSnippet<out CompiledScript>, updateContext: Boolean = false
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
        return Box.withBoxContext(resolveBoxContext()) {
            var r: ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>>
            val ns = measureNanoTime {

                val evalConfig = ScriptEvaluationConfiguration {
                    jvm {
                        baseClassLoader(classLoader!!)
                    }
                }

                r = evaluator.eval(e, evalConfig)
                if (r is ResultWithDiagnostics.Success) {
                    val scriptInstance = (r as ResultWithDiagnostics.Success).value.get().result.scriptInstance
                    context.debugScripts.attachLatestRuntimeClass(scriptInstance?.javaClass?.name)
                }
                if (updateContext) contextUpdater.update()
            }
            lprintln("info,kotlin,perf", "\t\tTIME<" + (ns / 1_000_000.0) + "ms> = evaluate")
            r
        }
    }

    suspend fun evaluate(
        e: LinkedSnippet<out CompiledScript>, updateContext: Boolean = true, implicits: List<Any>
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
        return Box.withBoxContext(resolveBoxContext(implicits)) {
            var r: ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>>
            val ns = measureNanoTime {

                val evalConfig = ScriptEvaluationConfiguration {
                    implicits.forEach { implicitReceivers.append(it) }
                    jvm {
                        baseClassLoader(classLoader!!)
                    }
                }

                r = evaluator.eval(e, evalConfig)
                if (r is ResultWithDiagnostics.Success) {
                    val scriptInstance = (r as ResultWithDiagnostics.Success).value.get().result.scriptInstance
                    context.debugScripts.attachLatestRuntimeClass(scriptInstance?.javaClass?.name)
                }
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


    /**
     * Completion intentionally delegates to the K1 IDE-services path.
     * K2 remains the execution backend, but autocomplete needs compiler-grade
     * language semantics rather than partial handwritten Analysis API coverage.
     */
    override suspend fun complete(s: String, cursor: Int): CompletionResult {
        return k1CompletionDelegate().complete(s, cursor)
    }

    private var k1CompletionDelegate: KotlinInterface? = null
    private var k1CompletionDelegateBootstrapped = false
    private val k1CompletionDelegateMutex = Mutex()

    private suspend fun k1CompletionDelegate(): KotlinInterface {
        return k1CompletionDelegateMutex.withLock {
            val existing = k1CompletionDelegate
            if (existing != null) {
                existing.associatedBox = associatedBox
                return@withLock existing
            }

            val box = resolveBoxContext()
            val boxLabel = box?.let { "'${it.name.ifBlank { "Untitled" }}' (${it.id})" } ?: "none"
            lprintln(
                "info,ki,startup",
                "request K1 completion delegate for K2 filePrefix=$filePrefix box=$boxLabel"
            )

            val created = KotlinInterface(
                propertyClasses = propertyClasses,
                properties = properties,
                implicitReceivers = implicitReceivers,
                classLoader = classLoader,
                filePrefix = "${filePrefix}_completion"
            )
            created.associatedBox = associatedBox
            k1CompletionDelegate = created

            if (!k1CompletionDelegateBootstrapped) {
                val implicits = if (box != null) listOf(box) else emptyList()
                lprintln(
                    "info,ki,startup",
                    "bootstrap K1 completion delegate filePrefix=${filePrefix}_completion box=$boxLabel introLength=${intro.length}"
                )
                if (box != null) {
                    Box.withBoxContext(box) {
                        created.exec(intro, implicits = implicits)
                    }
                } else {
                    created.exec(intro)
                }
                k1CompletionDelegateBootstrapped = true
            }

            created
        }
    }

    /**
     * Build a source text suitable for FIR analysis: prepend REPL imports and
     * insert a dummy identifier at the cursor so incomplete expressions parse cleanly.
     */
    private fun buildFirSourceText(s: String, cursor: Int): String {
        // Extract import lines from the REPL intro preamble so FIR can resolve stdlib symbols
        val introImports = intro.lines()
            .filter { it.trimStart().startsWith("import ") }
            .joinToString("\n")

        // Insert a dummy identifier at cursor to make incomplete expressions valid for parsing.
        // e.g. "q." becomes "q.COMPLETION_PLACEHOLDER" which is syntactically valid.
        val patched = StringBuilder(s).insert(cursor, "COMPLETION_PLACEHOLDER").toString()

        return introImports + "\n" + patched
    }

    private fun computeK2Completion(s: String, cursor: Int): List<SourceCodeCompletionVariant> {
        val results = mutableListOf<SourceCodeCompletionVariant>()

        // Parse the completion site to determine what kind of completion we need
        val completionSite = parseCompletionSite(s, cursor)

        // Build FIR-friendly source text with imports and a dummy at cursor
        val firSource = buildFirSourceText(s, cursor)

        val nameFilter: (String) -> Boolean = { name ->
            completionSite.prefix.isEmpty() || name.startsWith(completionSite.prefix, ignoreCase = true)
        }

        when (completionSite) {
            is CompletionSite.DotCompletion -> {
                var receiverType = resolveReceiverType(completionSite.receiverText, firSource)
                if (receiverType == null) {
                    receiverType = resolveExpressionTypeViaFir(completionSite.receiverText, s, cursor)
                }
                if (receiverType != null) {
                    addMembersFromClass(receiverType, completionSite.prefix, results)
                }
                addStaticMembersIfPackageOrClass(completionSite.receiverText, completionSite.prefix, results)
            }

            is CompletionSite.SafeDotCompletion -> {
                var receiverType = resolveReceiverType(completionSite.receiverText, firSource)
                if (receiverType == null) {
                    receiverType = resolveExpressionTypeViaFir(completionSite.receiverText, s, cursor)
                }
                if (receiverType != null) {
                    addMembersFromClass(receiverType, completionSite.prefix, results)
                }
            }

            is CompletionSite.NameCompletion -> {
                // Variables from REPL context
                for ((name, info) in context.vars) {
                    if (!nameFilter(name)) continue
                    if (name.contains("$")) continue
                    val type = try { shortenType(info.descriptor.returnType.toString()) } catch (_: Exception) { "Any?" }
                    val kind = if (info.descriptor is KMutableProperty<*>) "var" else "val"
                    results.add(SourceCodeCompletionVariant(
                        text = name, displayText = "$kind $name", tail = ": $type",
                        icon = kind,
                    ))
                }

                // Functions from REPL context — with full parameter signatures
                for ((name, info) in context.functions) {
                    if (!nameFilter(name)) continue
                    if (name.contains("$") || name == "main") continue
                    val params = info.function.parameters.filter { it.kind == KParameter.Kind.VALUE }
                    val paramsStr = formatKotlinParams(params)
                    val returnType = try { shortenType(info.function.returnType.toString()) } catch (_: Exception) { "Any?" }
                    results.add(SourceCodeCompletionVariant(
                        text = name, displayText = "fun $name$paramsStr", tail = ": $returnType",
                        icon = "method",
                    ))
                }

                // Members from implicit receivers (e.g., Box members are callable as top-level in the REPL)
                for (kotlinType in implicitReceivers) {
                    val klass = try { kotlinType.fromClass } catch (_: Exception) { null } ?: continue
                    addMembersFromClass(klass, completionSite.prefix, results)
                }

                // Members from lambda receiver types (e.g., inside `fline { }`, FLine methods are available)
                for (receiverClass in detectLambdaReceiverTypes(s, cursor)) {
                    addMembersFromClass(receiverClass, completionSite.prefix, results)
                }

                // Keywords
                val keywords = listOf(
                    "val", "var", "fun", "class", "object", "interface", "if", "else", "when",
                    "for", "while", "do", "return", "break", "continue", "throw", "try", "catch",
                    "finally", "import", "package", "is", "as", "in", "true", "false", "null",
                    "this", "super", "data", "sealed", "enum", "abstract", "open", "override",
                    "private", "protected", "internal", "public", "companion", "lateinit", "lazy",
                    "suspend", "inline", "infix", "operator", "tailrec", "external", "annotation",
                    "crossinline", "noinline", "reified", "typealias", "typeof", "by", "where",
                )
                for (kw in keywords) {
                    if (nameFilter(kw)) {
                        results.add(SourceCodeCompletionVariant(text = kw, displayText = kw, tail = "", icon = "keyword"))
                    }
                }

                // Declarations from the current editor text that haven't been executed yet (via FIR resolution)
                addFirSourceDeclarations(firSource, nameFilter, results)

                // Top-level functions and extension functions on implicit receivers from imported packages
                val existingNames = results.map { it.text }.toSet()
                addImportedTopLevelFunctions(completionSite.prefix, results, existingNames)

                // Class names from classpath — provides constructor completion for types like FLine, Vec3, etc.
                if (completionSite.prefix.length >= 2) {
                    addClasspathClasses(completionSite.prefix, results)
                }
            }
        }

        return results.distinctBy { it.text }.sortedWith(completionComparator(completionSite.prefix))
    }

    /**
     * Sort completions: exact case-prefix match first, then by sortGroup,
     * then shorter names first, then alphabetical.
     */
    private fun completionComparator(prefix: String) = compareBy<SourceCodeCompletionVariant>(
        { if (prefix.isNotEmpty() && it.text.startsWith(prefix)) 0 else 1 },
        { (it.extraInfo?.get("sortGroup") as? Int) ?: 0 },
        { it.text.length },
        { it.text.lowercase() }
    )

    // --- Kotlin parameter formatting helpers ---

    private fun formatKotlinParams(params: List<KParameter>): String {
        if (params.isEmpty()) return "()"
        return "(${params.joinToString(", ") { p -> formatKotlinParam(p) }})"
    }

    private fun formatKotlinParam(param: KParameter): String {
        val name = param.name
        val type = try { shortenType(param.type.toString()) } catch (_: Exception) { "Any?" }
        val sb = StringBuilder()
        if (param.isVararg) sb.append("vararg ")
        // If the name is null or synthetic (arg0, p0, etc.), show just the type
        if (name != null && !name.matches(Regex("^(arg|p)\\d+$"))) {
            sb.append("$name: $type")
        } else {
            sb.append(type)
        }
        if (param.isOptional) sb.append(" = ...")
        return sb.toString()
    }

    private fun formatJavaMethodParams(method: java.lang.reflect.Method): String {
        val kfunc = try { method.kotlinFunction } catch (_: Exception) { null }
        if (kfunc != null) {
            val valueParams = kfunc.parameters.filter { it.kind == KParameter.Kind.VALUE }
            return formatKotlinParams(valueParams)
        }
        // Fallback to Java reflection
        val params = method.parameters
        if (params.isEmpty()) return "()"
        return "(${params.joinToString(", ") { p ->
            val name = p.name
            val type = shortenType(p.type.kotlin.simpleName ?: p.type.simpleName)
            // If the name is synthetic (arg0, p0, etc.), show just the type
            if (name.matches(Regex("^(arg|p)\\d+$"))) type else "$name: $type"
        }})"
    }

    private fun formatMethodReturnType(method: java.lang.reflect.Method): String {
        val kfunc = try { method.kotlinFunction } catch (_: Exception) { null }
        return try {
            kfunc?.let { shortenType(it.returnType.toString()) }
                ?: shortenType(method.returnType.kotlin.simpleName ?: method.returnType.simpleName)
        } catch (_: Exception) { "Any?" }
    }

    private fun isDeprecated(method: java.lang.reflect.Method): DeprecationLevel? {
        if (method.isAnnotationPresent(Deprecated::class.java)) return DeprecationLevel.WARNING
        val kfunc = try { method.kotlinFunction } catch (_: Exception) { null }
        if (kfunc?.annotations?.any { it is Deprecated } == true) return DeprecationLevel.WARNING
        return null
    }

    // --- Completion site parsing ---

    private sealed class CompletionSite {
        abstract val prefix: String
        data class NameCompletion(override val prefix: String) : CompletionSite()
        data class DotCompletion(val receiverText: String, override val prefix: String) : CompletionSite()
        data class SafeDotCompletion(val receiverText: String, override val prefix: String) : CompletionSite()
    }

    private fun parseCompletionSite(s: String, cursor: Int): CompletionSite {
        // Extract the prefix (identifier being typed at cursor)
        val prefix = extractPrefix(s, cursor)
        val beforePrefix = cursor - prefix.length

        if (beforePrefix <= 0) return CompletionSite.NameCompletion(prefix)

        // Check for ?. (safe call)
        val isSafeDot = beforePrefix >= 2 && s[beforePrefix - 1] == '.' && s[beforePrefix - 2] == '?'
        val isDot = !isSafeDot && s[beforePrefix - 1] == '.'

        if (!isDot && !isSafeDot) return CompletionSite.NameCompletion(prefix)

        // Extract receiver text (everything before the dot, going back through chained expressions)
        val dotPos = if (isSafeDot) beforePrefix - 2 else beforePrefix - 1
        val receiverText = extractReceiverExpression(s, dotPos)

        return if (isSafeDot) CompletionSite.SafeDotCompletion(receiverText, prefix)
        else CompletionSite.DotCompletion(receiverText, prefix)
    }

    private fun extractReceiverExpression(s: String, endPos: Int): String {
        // Walk backwards from endPos to find the start of the receiver expression
        // Handles: simple names, chained dots (a.b.c), function calls (a.foo()), indexing (a[0])
        var pos = endPos - 1
        var parenDepth = 0
        var bracketDepth = 0

        while (pos >= 0) {
            val c = s[pos]
            when {
                c == ')' -> { parenDepth++; pos-- }
                c == '(' && parenDepth > 0 -> { parenDepth--; pos-- }
                c == ']' -> { bracketDepth++; pos-- }
                c == '[' && bracketDepth > 0 -> { bracketDepth--; pos-- }
                parenDepth > 0 || bracketDepth > 0 -> pos--
                c.isLetterOrDigit() || c == '_' || c == '.' || c == '?' -> pos--
                else -> break
            }
        }
        return s.substring(pos + 1, endPos).trim()
    }

    // --- FIR-based type resolution for un-executed code ---

    /**
     * Cache of FIR-resolved types from the last analysis run.
     * Maps variable/function name -> resolved KClass.
     * Invalidated when source text changes.
     */
    private var firTypeCache: Map<String, KClass<*>> = emptyMap()
    private var firFunctionCache: Map<String, KClass<*>> = emptyMap()
    private var firCacheSourceHash: Int = 0

    /**
     * Resolve types from source code using the K2 compiler's FIR pipeline.
     *
     * This mirrors the first half of K2ReplCompiler.compileImpl:
     * 1. Create a KtFile from the source text
     * 2. Create a temporary FIR source session
     * 3. Build raw FIR from the KtFile
     * 4. Run full type resolution on the FIR
     * 5. Walk the resolved FIR tree to extract variable/function types
     * 6. Clean up: remove the temporary module data from history
     *
     * Returns a pair of (variableTypes, functionReturnTypes) where each maps name -> KClass.
     */
    private fun resolveTypesViaFir(sourceText: String): Pair<Map<String, KClass<*>>, Map<String, KClass<*>>> {
        val hash = sourceText.hashCode()
        if (hash == firCacheSourceHash && firTypeCache.isNotEmpty()) {
            return firTypeCache to firFunctionCache
        }

        try {
            // Access compilation state internals via reflection
            val stateClass = compilationState::class.java

            val moduleDataProvider = stateClass.getDeclaredField("moduleDataProvider").let {
                it.isAccessible = true; it.get(compilationState)
            }
            val sessionFactoryContext = stateClass.getDeclaredField("sessionFactoryContext").let {
                it.isAccessible = true; it.get(compilationState)
            }
            val sharedLibrarySession = stateClass.getDeclaredField("sharedLibrarySession").let {
                it.isAccessible = true; it.get(compilationState)
            }
            val compilerContext = stateClass.getDeclaredField("compilerContext").let {
                it.isAccessible = true; it.get(compilationState)
            }

            // Get CompilerConfiguration from the environment
            val environment = compilerContext::class.java.getMethod("getEnvironment").invoke(compilerContext)
            val compilerConfiguration = environment::class.java.getMethod("getConfiguration").invoke(environment)
            val configCopy = compilerConfiguration::class.java.getMethod("copy").invoke(compilerConfiguration)

            // Create KtFile from the source text via getScriptKtFile
            val project = psiProject
            val sourceCode = SourceCodeImpl(id, sourceText)

            val getScriptKtFileMethod = Class.forName(
                "org.jetbrains.kotlin.scripting.compiler.plugin.impl.JvmCompilationUtilKt"
            ).getMethod(
                "getScriptKtFile",
                Class.forName("kotlin.script.experimental.api.SourceCode"),
                Class.forName("kotlin.script.experimental.api.ScriptCompilationConfiguration"),
                Class.forName("org.jetbrains.kotlin.com.intellij.openapi.project.Project"),
                Class.forName("org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector")
            )

            val ktFileResult = getScriptKtFileMethod.invoke(null, sourceCode, compilerConfig, project, messageCollector)

            // Extract KtFile from ResultWithDiagnostics<KtFile>
            val ktFile = if (ktFileResult is ResultWithDiagnostics.Success<*>) {
                ktFileResult.value as KtFile
            } else {
                lprintln("debug,kotlin", "FIR analysis: failed to create KtFile: $ktFileResult")
                return emptyMap<String, KClass<*>>() to emptyMap()
            }

            // Mark script as REPL snippet
            ktFile.script?.let { script ->
                try {
                    val markMethod = script::class.java.getMethod("markAsReplSnippet")
                    markMethod.invoke(script)
                } catch (_: Exception) {}
            }

            // Get extension registrars from compiler configuration
            val firExtensionRegistrarCompanion = Class.forName(
                "org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar"
            ).getDeclaredField("Companion").get(null)
            val getExtensionsMethod = Class.forName(
                "org.jetbrains.kotlin.compiler.plugin.FirExtensionRegistrarConfigurationUtilKt"
            ).methods.first { it.name == "getCompilerExtensions" }
            val extensionRegistrars = getExtensionsMethod.invoke(null, configCopy, firExtensionRegistrarCompanion) as List<*>

            // Record the module data history size before we add our temporary entry
            val moduleDataHistory = moduleDataProvider::class.java.getMethod("getModuleDataHistory")
                .invoke(moduleDataProvider) as MutableList<*>
            val historySize = moduleDataHistory.size

            // Create temporary module data
            val nameClass = Class.forName("org.jetbrains.kotlin.name.Name")
            val nameSpecial = nameClass.getMethod("special", String::class.java)
            val snippetName = nameSpecial.invoke(null, "<completion-analysis>")

            val addSnippetMethod = moduleDataProvider::class.java.getMethod("addNewSnippetModuleData", nameClass)
            val moduleData = addSnippetMethod.invoke(moduleDataProvider, snippetName)

            try {
                // Create source session via FirJvmSessionFactory.INSTANCE.createSourceSession(...)
                val sessionFactoryClass = Class.forName("org.jetbrains.kotlin.fir.session.FirJvmSessionFactory")
                val factoryInstance = sessionFactoryClass.getDeclaredField("INSTANCE").get(null)

                // Get EMPTY search scope
                val emptyScope = Class.forName(
                    "org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope\$EMPTY"
                ).getDeclaredField("INSTANCE").get(null)

                // Null-returning lambda for incremental compilation providers
                val incrementalProvidersLambda = java.lang.reflect.Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(kotlin.Function1::class.java)
                ) { _, _, _ -> null }

                // No-op lambda for session configurator init
                val initLambda = java.lang.reflect.Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(kotlin.Function1::class.java)
                ) { _, _, _ -> kotlin.Unit }

                // The method signature is:
                // createSourceSession(moduleData, scope, incrementalProviders, extensionRegistrars,
                //                     config, context, needRegisterJavaElementFinder, isForLeafHmppModule, init)
                // All 9 params are explicit in the bytecode (Kotlin default param generates a separate $default bridge)
                val createSourceSessionMethod = sessionFactoryClass.methods.firstOrNull {
                    it.name == "createSourceSession" && it.parameterCount == 9
                } ?: sessionFactoryClass.methods.first {
                    // Fallback: try the $default synthetic bridge if present
                    it.name == "createSourceSession" && it.parameterCount > 0
                }

                val sourceSession = if (createSourceSessionMethod.parameterCount == 9) {
                    createSourceSessionMethod.invoke(
                        factoryInstance,
                        moduleData,                // FirModuleData
                        emptyScope,                // AbstractProjectFileSearchScope
                        incrementalProvidersLambda, // Function1<FirSession, FirJvmIncrementalCompilationSymbolProviders?>
                        extensionRegistrars,       // List<FirExtensionRegistrar>
                        configCopy,                // CompilerConfiguration
                        sessionFactoryContext,     // FirJvmSessionFactory.Context
                        true,                      // needRegisterJavaElementFinder
                        false,                     // isForLeafHmppModule
                        initLambda                 // Function1<FirSessionConfigurator, Unit>
                    )
                } else {
                    // Unexpected param count — try best effort
                    createSourceSessionMethod.invoke(
                        factoryInstance, moduleData, emptyScope, incrementalProvidersLambda,
                        extensionRegistrars, configCopy, sessionFactoryContext, true, false
                    )
                }

                if (sourceSession == null) {
                    lprintln("debug,kotlin", "FIR analysis: createSourceSession returned null")
                    return emptyMap<String, KClass<*>>() to emptyMap()
                }

                // Build FIR from KtFile: FirUtilsKt.buildFirFromKtFiles(session, listOf(ktFile))
                val buildFirMethod = Class.forName("org.jetbrains.kotlin.fir.pipeline.FirUtilsKt")
                    .getMethod("buildFirFromKtFiles",
                        Class.forName("org.jetbrains.kotlin.fir.FirSession"),
                        Collection::class.java)
                val rawFir = buildFirMethod.invoke(null, sourceSession, listOf(ktFile)) as List<*>

                lprintln("debug,kotlin", "FIR analysis: buildFirFromKtFiles returned ${rawFir.size} files")

                // Run resolution: AnalyseKt.runResolution(session, rawFir)
                val runResolutionMethod = Class.forName("org.jetbrains.kotlin.fir.pipeline.AnalyseKt")
                    .getMethod("runResolution",
                        Class.forName("org.jetbrains.kotlin.fir.FirSession"),
                        List::class.java)
                val result = runResolutionMethod.invoke(null, sourceSession, rawFir) as kotlin.Pair<*, *>
                val firFiles = result.second as List<*>

                lprintln("debug,kotlin", "FIR analysis: runResolution returned ${firFiles.size} files")

                // Walk resolved FIR tree to extract types
                val varTypes = mutableMapOf<String, KClass<*>>()
                val funcTypes = mutableMapOf<String, KClass<*>>()
                extractTypesFromFir(firFiles, varTypes, funcTypes)

                lprintln("debug,kotlin", "FIR analysis: extracted ${varTypes.size} var types: ${varTypes.keys}")
                lprintln("debug,kotlin", "FIR analysis: extracted ${funcTypes.size} func types: ${funcTypes.keys}")

                firTypeCache = varTypes
                firFunctionCache = funcTypes
                firCacheSourceHash = hash

                return varTypes to funcTypes
            } finally {
                // Clean up: remove the temporary module data from history
                if (moduleDataHistory.size > historySize) {
                    moduleDataHistory.removeAt(moduleDataHistory.size - 1)
                }
            }
        } catch (e: Exception) {
            lprintln("warning,kotlin", "FIR type resolution failed: ${e::class.simpleName}: ${e.message}")
            if (e.cause != null) {
                lprintln("debug,kotlin", "  caused by: ${e.cause?.message}")
            }
            return emptyMap<String, KClass<*>>() to emptyMap()
        }
    }

    /**
     * Walk the resolved FIR tree to extract variable types, function return types,
     * and lambda parameter types (like implicit `it`).
     * FirFile → FirScript → declarations → FirProperty / FirNamedFunction
     * Also walks into property initializers to find FirAnonymousFunction parameters.
     */
    private fun extractTypesFromFir(
        firFiles: List<*>,
        varTypes: MutableMap<String, KClass<*>>,
        funcTypes: MutableMap<String, KClass<*>>
    ) {
        val firScriptClass = Class.forName("org.jetbrains.kotlin.fir.declarations.FirScript")
        val firPropertyClass = Class.forName("org.jetbrains.kotlin.fir.declarations.FirProperty")
        val firNamedFunctionClass = Class.forName("org.jetbrains.kotlin.fir.declarations.FirNamedFunction")
        val firResolvedTypeRefClass = Class.forName("org.jetbrains.kotlin.fir.types.FirResolvedTypeRef")
        val coneClassLikeTypeClass = Class.forName("org.jetbrains.kotlin.fir.types.ConeClassLikeType")
        val firAnonymousFunctionClass = try {
            Class.forName("org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction")
        } catch (_: Exception) { null }
        val firAnonymousFunctionExpressionClass = try {
            Class.forName("org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression")
        } catch (_: Exception) { null }
        val firValueParameterClass = try {
            Class.forName("org.jetbrains.kotlin.fir.declarations.FirValueParameter")
        } catch (_: Exception) { null }

        for (firFile in firFiles) {
            if (firFile == null) continue
            val fileDecls = firFile::class.java.getMethod("getDeclarations").invoke(firFile) as? List<*> ?: continue

            lprintln("debug,kotlin", "FIR walk: FirFile has ${fileDecls.size} top-level declarations")

            for (decl in fileDecls) {
                if (decl == null) continue

                lprintln("debug,kotlin", "FIR walk: top-level decl type: ${decl::class.java.simpleName}")

                if (firScriptClass.isInstance(decl)) {
                    val scriptDecls = firScriptClass.getMethod("getDeclarations").invoke(decl) as? List<*> ?: continue

                    lprintln("debug,kotlin", "FIR walk: FirScript has ${scriptDecls.size} declarations")

                    for (scriptDecl in scriptDecls) {
                        if (scriptDecl == null) continue

                        lprintln("debug,kotlin", "FIR walk: script decl type: ${scriptDecl::class.java.simpleName}")

                        processDeclaration(scriptDecl, firPropertyClass, firNamedFunctionClass,
                            firResolvedTypeRefClass, coneClassLikeTypeClass, varTypes, funcTypes)

                        // Walk into property initializers to extract lambda parameters (e.g. `it`)
                        if (firPropertyClass.isInstance(scriptDecl) && firAnonymousFunctionClass != null) {
                            extractLambdaParameters(scriptDecl, firAnonymousFunctionClass,
                                firAnonymousFunctionExpressionClass, firValueParameterClass,
                                firResolvedTypeRefClass, coneClassLikeTypeClass, varTypes)
                        }
                    }
                }
            }
        }
    }

    /**
     * Walk into a FirProperty's initializer to find FirAnonymousFunction value parameters.
     * This handles cases like: `var q : (Vec3) -> Unit = { it. }` where `it` is an
     * implicit lambda parameter that needs to be available for completion.
     */
    private fun extractLambdaParameters(
        property: Any,
        firAnonymousFunctionClass: Class<*>,
        firAnonymousFunctionExpressionClass: Class<*>?,
        firValueParameterClass: Class<*>?,
        firResolvedTypeRefClass: Class<*>,
        coneClassLikeTypeClass: Class<*>,
        varTypes: MutableMap<String, KClass<*>>
    ) {
        try {
            val initializer = property::class.java.getMethod("getInitializer").invoke(property) ?: return

            // The initializer might be a FirAnonymousFunctionExpression wrapping a FirAnonymousFunction
            val anonFunction = when {
                firAnonymousFunctionClass.isInstance(initializer) -> initializer
                firAnonymousFunctionExpressionClass != null && firAnonymousFunctionExpressionClass.isInstance(initializer) ->
                    firAnonymousFunctionExpressionClass.getMethod("getAnonymousFunction").invoke(initializer)
                else -> null
            } ?: return

            val valueParams = firAnonymousFunctionClass.getMethod("getValueParameters").invoke(anonFunction) as? List<*>
                ?: return

            for (param in valueParams) {
                if (param == null || firValueParameterClass == null) continue
                if (!firValueParameterClass.isInstance(param)) continue

                val name = firValueParameterClass.getMethod("getName").invoke(param)
                val nameStr = name?.toString() ?: continue
                if (nameStr.startsWith("<")) continue  // skip synthetic names

                val klass = extractKClassFromReturnType(param, firResolvedTypeRefClass, coneClassLikeTypeClass)
                if (klass != null) {
                    lprintln("debug,kotlin", "FIR walk: lambda param '$nameStr' -> ${klass.simpleName}")
                    varTypes[nameStr] = klass
                }
            }
        } catch (e: Exception) {
            lprintln("debug,kotlin", "FIR walk: error extracting lambda params: ${e.message}")
        }
    }

    /**
     * Process a single FIR declaration and extract its type.
     */
    private fun processDeclaration(
        decl: Any,
        firPropertyClass: Class<*>,
        firNamedFunctionClass: Class<*>,
        firResolvedTypeRefClass: Class<*>,
        coneClassLikeTypeClass: Class<*>,
        varTypes: MutableMap<String, KClass<*>>,
        funcTypes: MutableMap<String, KClass<*>>
    ) {
        try {
            if (firPropertyClass.isInstance(decl)) {
                val name = firPropertyClass.getMethod("getName").invoke(decl)
                val nameStr = name?.toString() ?: return
                if (nameStr.contains("$") || nameStr.startsWith("<")) return

                val klass = extractKClassFromReturnType(decl, firResolvedTypeRefClass, coneClassLikeTypeClass)
                lprintln("debug,kotlin", "FIR walk: property '$nameStr' -> ${klass?.simpleName ?: "UNRESOLVED"}")
                if (klass != null) {
                    varTypes[nameStr] = klass
                }
            } else if (firNamedFunctionClass.isInstance(decl)) {
                val name = firNamedFunctionClass.getMethod("getName").invoke(decl)
                val nameStr = name?.toString() ?: return
                if (nameStr.contains("$") || nameStr == "main" || nameStr.startsWith("<")) return

                val klass = extractKClassFromReturnType(decl, firResolvedTypeRefClass, coneClassLikeTypeClass)
                if (klass != null) {
                    funcTypes[nameStr] = klass
                }
            }
        } catch (e: Exception) {
            lprintln("debug,kotlin", "Error extracting type from FIR declaration: ${e.message}")
        }
    }

    /**
     * Extract a KClass from a FIR declaration's return type ref.
     * Chain: decl.returnTypeRef → FirResolvedTypeRef.coneType → ConeClassLikeType.lookupTag.classId → Class.forName
     */
    private fun extractKClassFromReturnType(
        decl: Any,
        firResolvedTypeRefClass: Class<*>,
        coneClassLikeTypeClass: Class<*>
    ): KClass<*>? {
        val returnTypeRef = decl::class.java.getMethod("getReturnTypeRef").invoke(decl) ?: run {
            lprintln("debug,kotlin", "FIR type: returnTypeRef is null")
            return null
        }
        if (!firResolvedTypeRefClass.isInstance(returnTypeRef)) {
            lprintln("debug,kotlin", "FIR type: returnTypeRef is ${returnTypeRef::class.java.simpleName}, not FirResolvedTypeRef")
            return null
        }

        val coneType = firResolvedTypeRefClass.getMethod("getConeType").invoke(returnTypeRef) ?: return null
        if (!coneClassLikeTypeClass.isInstance(coneType)) {
            lprintln("debug,kotlin", "FIR type: coneType is ${coneType::class.java.simpleName}, not ConeClassLikeType")
            return null
        }

        val lookupTag = coneClassLikeTypeClass.getMethod("getLookupTag").invoke(coneType) ?: return null
        val classId = lookupTag::class.java.getMethod("getClassId").invoke(lookupTag) ?: return null
        val fqName = classId::class.java.getMethod("asFqNameString").invoke(classId) as? String ?: return null

        // Convert slash-separated FQ name to dot-separated: "kotlin/Int" -> "kotlin.Int"
        val dotName = fqName.replace('/', '.')

        lprintln("debug,kotlin", "FIR type: resolved to '$dotName'")

        return resolveTypeName(dotName)
    }

    // --- FIR-based expression type resolution for complex receivers ---

    /**
     * Resolve the type of an arbitrary expression by synthesizing a probe variable
     * and running FIR type resolution on it.
     *
     * This handles cases where the receiver is a complex expression like
     * `(a + b)`, `if (c) x else y`, `listOf(1,2,3).map { ... }`, etc.
     * that can't be resolved by simple name/chain lookup in [resolveReceiverType].
     *
     * The approach:
     * 1. Take the original code up to the dot as context (variable/function declarations)
     * 2. Add synthetic declarations for REPL context variables not in the current code
     * 3. Append `val __completion_probe__ = <receiverExpression>`
     * 4. Run FIR resolution and read the type of `__completion_probe__`
     */
    private fun resolveExpressionTypeViaFir(receiverText: String, s: String, cursor: Int): KClass<*>? {
        try {
            val introImports = intro.lines()
                .filter { it.trimStart().startsWith("import ") }
                .joinToString("\n")

            // Find the dot position: cursor is after any typed prefix, dot is just before that
            val prefix = extractPrefix(s, cursor)
            val beforePrefix = cursor - prefix.length
            // Determine if safe dot (?.) or regular dot (.)
            val dotOffset = if (beforePrefix >= 2 && s[beforePrefix - 1] == '.' && s[beforePrefix - 2] == '?')
                beforePrefix - 2
            else
                beforePrefix - 1

            // Code context: everything before the dot (includes receiver expr and prior declarations)
            val codeContext = if (dotOffset > 0) s.substring(0, dotOffset) else ""

            // Include synthetic declarations for REPL context variables not declared in current code
            val contextDecls = buildContextDeclarationsForFir(codeContext)

            val probeSource = "$introImports\n$contextDecls\n$codeContext\nval __completion_probe__ = $receiverText\n"

            lprintln("debug,kotlin", "FIR expression probe for receiver '$receiverText'")

            val (varTypes, _) = resolveTypesViaFir(probeSource)
            val resolved = varTypes["__completion_probe__"]
            if (resolved != null) {
                lprintln("debug,kotlin", "FIR expression probe resolved: ${resolved.simpleName}")
            }
            return resolved
        } catch (e: Exception) {
            lprintln("debug,kotlin", "FIR expression type resolution failed: ${e.message}")
            return null
        }
    }

    /**
     * Detect lambda receiver types at the cursor position using a FIR probe.
     *
     * When the cursor is inside a lambda with a receiver (e.g. `fline { moveTo^ }`),
     * the compiler resolves `this` to the receiver type of the lambda.
     * We synthesize `val __this_probe__ = this` at the cursor and run FIR resolution
     * to discover what `this` refers to.
     *
     * Returns a list of KClass receiver types that should contribute members
     * to name completion (excluding the REPL-level implicit receivers which are
     * already handled separately).
     */
    private fun detectLambdaReceiverTypes(s: String, cursor: Int): List<KClass<*>> {
        try {
            val introImports = intro.lines()
                .filter { it.trimStart().startsWith("import ") }
                .joinToString("\n")

            // Code context: everything before the cursor
            val codeContext = s.substring(0, cursor)

            // Include synthetic declarations for REPL context variables
            val contextDecls = buildContextDeclarationsForFir(codeContext)

            // Insert a probe that captures `this` — if we're inside a receiver lambda,
            // FIR will resolve `this` to the lambda's receiver type
            val probeSource = "$introImports\n$contextDecls\n${codeContext}val __this_probe__ = this\n${s.substring(cursor)}\n"

            val (varTypes, _) = resolveTypesViaFir(probeSource)
            val resolved = varTypes["__this_probe__"] ?: return emptyList()

            // Filter out the REPL-level implicit receivers since they're already included
            val implicitReceiverClasses = implicitReceivers.mapNotNull { kotlinType ->
                try { kotlinType.fromClass } catch (_: Exception) { null }
            }.toSet()

            if (resolved in implicitReceiverClasses) return emptyList()

            // Skip uninteresting types
            if (resolved == Any::class || resolved == Nothing::class || resolved == Unit::class) return emptyList()

            lprintln("debug,kotlin", "Detected lambda receiver type: ${resolved.simpleName}")
            return listOf(resolved)
        } catch (e: Exception) {
            lprintln("debug,kotlin", "Lambda receiver detection failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Build synthetic `val` declarations for REPL context variables that are not
     * already declared in the given code text. This allows FIR to resolve expressions
     * that reference variables defined in earlier REPL evaluations.
     */
    private fun buildContextDeclarationsForFir(codeContext: String): String {
        val sb = StringBuilder()
        for ((name, info) in context.vars) {
            if (name.contains("$") || name.startsWith("<")) continue
            // Skip if already declared in the current code context
            if (Regex("""\b(val|var)\s+${Regex.escape(name)}\b""").containsMatchIn(codeContext)) continue
            val type = try {
                val t = info.descriptor.returnType.toString()
                // Skip internal/error types
                if (t.contains("$") || t.contains("<error>") || t.contains("<unresolved>")) continue
                t
            } catch (_: Exception) {
                // Fallback to runtime class name
                info.value?.let { it::class.qualifiedName ?: it::class.java.name } ?: continue
            }
            sb.appendLine("val $name: $type = TODO()")
        }
        // Also emit function declarations for REPL context functions
        for ((name, info) in context.functions) {
            if (name.contains("$") || name == "main" || name.startsWith("<")) continue
            if (Regex("""\bfun\s+${Regex.escape(name)}\b""").containsMatchIn(codeContext)) continue
            try {
                val params = info.function.parameters.filter { it.kind == KParameter.Kind.VALUE }
                val paramsDecl = params.joinToString(", ") { p ->
                    val pName = p.name ?: "p${p.index}"
                    val pType = try { p.type.toString() } catch (_: Exception) { "Any?" }
                    "$pName: $pType"
                }
                val returnType = try { info.function.returnType.toString() } catch (_: Exception) { "Any?" }
                if (returnType.contains("$") || returnType.contains("<error>")) continue
                sb.appendLine("fun $name($paramsDecl): $returnType = TODO()")
            } catch (_: Exception) { /* skip functions we can't introspect */ }
        }
        return sb.toString()
    }

    // --- Receiver type resolution (reflection-based + FIR fallback) ---

    private fun resolveReceiverType(receiverText: String, sourceText: String = ""): KClass<*>? {
        // Split chained access: "foo.bar.baz" -> ["foo", "bar", "baz"]
        val chain = splitDotChain(receiverText)
        if (chain.isEmpty()) return null

        // Resolve root element type
        val rootElement = chain[0]
        var currentValue: Any? = null
        var currentType: KClass<*>? = null
        val isCall = rootElement.contains("(")

        if (isCall) {
            val funcName = rootElement.substringBefore("(").trim()
            if (funcName.isEmpty()) return null

            // Constructor call: starts with uppercase
            if (funcName.first().isUpperCase()) {
                currentType = resolveTypeName(funcName)
            } else {
                // Function call: check runtime context first
                val funcInfo = context.functions[funcName]
                if (funcInfo != null) {
                    currentType = try { funcInfo.function.returnType.classifier as? KClass<*> } catch (_: Exception) { null }
                }
                // Fallback: FIR-resolved function return types
                if (currentType == null && sourceText.isNotEmpty()) {
                    val (_, funcTypes) = resolveTypesViaFir(sourceText)
                    currentType = funcTypes[funcName]
                }
            }
        } else {
            // Simple name: check runtime context first
            val varInfo = context.vars[rootElement]
            if (varInfo != null) {
                currentValue = varInfo.value
                currentType = currentValue?.let { it::class }
                    ?: runCatching { varInfo.descriptor.returnType.classifier as? KClass<*> }.getOrNull()
            }
            // Fallback: FIR-resolved variable types
            if (currentType == null && sourceText.isNotEmpty()) {
                val (varTypes, _) = resolveTypesViaFir(sourceText)
                currentType = varTypes[rootElement]
            }
        }

        if (currentType == null) return null

        // Walk the chain, resolving each step via reflection
        for (i in 1 until chain.size) {
            val step = chain[i]
            val memberName = step.removeSuffix("?").let {
                if (it.contains("(")) it.substringBefore("(") else it
            }
            if (memberName.isEmpty()) continue
            val isStepCall = step.contains("(")

            // Try property first
            val prop = currentType!!.memberProperties.firstOrNull { it.name == memberName }
            if (prop != null) {
                if (currentValue != null) {
                    prop.isAccessible = true
                    currentValue = runCatching { prop.getter.call(currentValue) }.getOrNull()
                    currentType = currentValue?.let { it::class }
                        ?: runCatching { prop.returnType.classifier as? KClass<*> }.getOrNull()
                } else {
                    currentType = runCatching { prop.returnType.classifier as? KClass<*> }.getOrNull()
                    currentValue = null
                }
                if (currentType == null) return null
                continue
            }

            // Try as a method call
            if (isStepCall) {
                val method = currentType.java.methods.firstOrNull { it.name == memberName }
                if (method != null) {
                    currentType = try { method.returnType.kotlin } catch (_: Exception) { null }
                    currentValue = null
                    if (currentType == null) return null
                    continue
                }
            }

            // No property or method found — can't resolve further
            return null
        }

        return currentType
    }

    // --- Resolve a type name to KClass ---

    private fun resolveTypeName(typeName: String): KClass<*>? {
        if (typeName.isEmpty()) return null
        val baseName = typeName.substringBefore('<').trim()
        if (baseName.isEmpty()) return null

        // Built-in Kotlin types
        when (baseName) {
            "Int", "kotlin.Int" -> return Int::class
            "Long", "kotlin.Long" -> return Long::class
            "Double", "kotlin.Double" -> return Double::class
            "Float", "kotlin.Float" -> return Float::class
            "Boolean", "kotlin.Boolean" -> return Boolean::class
            "String", "kotlin.String" -> return String::class
            "Char", "kotlin.Char" -> return Char::class
            "Byte", "kotlin.Byte" -> return Byte::class
            "Short", "kotlin.Short" -> return Short::class
            "Unit", "kotlin.Unit" -> return Unit::class
            "Any", "kotlin.Any" -> return Any::class
            "Nothing", "kotlin.Nothing" -> return Nothing::class
            "List", "MutableList",
            "kotlin.collections.List", "kotlin.collections.MutableList" -> return List::class
            "Set", "MutableSet",
            "kotlin.collections.Set", "kotlin.collections.MutableSet" -> return Set::class
            "Map", "MutableMap",
            "kotlin.collections.Map", "kotlin.collections.MutableMap" -> return Map::class
            "Array", "kotlin.Array" -> return Array::class
            "IntArray", "kotlin.IntArray" -> return IntArray::class
            "DoubleArray", "kotlin.DoubleArray" -> return DoubleArray::class
            "FloatArray", "kotlin.FloatArray" -> return FloatArray::class
            "LongArray", "kotlin.LongArray" -> return LongArray::class
            "BooleanArray", "kotlin.BooleanArray" -> return BooleanArray::class
            "Pair", "kotlin.Pair" -> return Pair::class
            "Triple", "kotlin.Triple" -> return Triple::class
            "Regex", "kotlin.text.Regex" -> return Regex::class
            "IntRange", "kotlin.ranges.IntRange" -> return IntRange::class
            "LongRange", "kotlin.ranges.LongRange" -> return LongRange::class
        }

        // Direct classloader lookup
        try {
            return classLoader?.loadClass(baseName)?.kotlin
        } catch (_: Exception) {}

        // Try common packages
        val commonPackages = listOf(
            "kotlin.", "kotlin.collections.", "kotlin.ranges.", "kotlin.text.",
            "java.lang.", "java.util.", "java.io.",
            "afield.", "afield.graphics.", "afield.four.", "afield.stdlib.",
            "afield.four.modules.", "afield.four.synth.",
        )
        for (pkg in commonPackages) {
            try {
                return classLoader?.loadClass("$pkg$baseName")?.kotlin
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Add variable, function, and class declarations from the current source text
     * that aren't yet in the runtime context, using FIR resolution for type info.
     */
    private fun addFirSourceDeclarations(
        sourceText: String,
        nameFilter: (String) -> Boolean,
        results: MutableList<SourceCodeCompletionVariant>
    ) {
        val existingNames = results.map { it.text }.toSet()

        // Use FIR resolution to get typed declarations
        val (varTypes, funcReturnTypes) = resolveTypesViaFir(sourceText)

        // Also do a lightweight PSI scan for declaration names and structure (params, var/val)
        // that FIR resolution doesn't easily give us without more tree walking
        try {
            val factory = PsiFileFactory.getInstance(psiProject)
            val lang = Language.findLanguageByID("kotlin")!!
            val ktFile = factory.createFileFromText("_decl_scan_.kts", lang, sourceText) as KtFile

            // Variables
            ktFile.forEachDescendantOfType<KtProperty> { prop ->
                val name = prop.name ?: return@forEachDescendantOfType
                if (!nameFilter(name) || name in existingNames || name.contains("$")) return@forEachDescendantOfType

                val typeStr = varTypes[name]?.simpleName
                    ?: prop.typeReference?.text
                    ?: "?"
                val kind = if (prop.isVar) "var" else "val"
                results.add(SourceCodeCompletionVariant(
                    text = name, displayText = "$kind $name", tail = ": $typeStr",
                    icon = kind,
                ))
            }

            // Functions
            ktFile.forEachDescendantOfType<KtNamedFunction> { func ->
                val name = func.name ?: return@forEachDescendantOfType
                if (!nameFilter(name) || name in existingNames || name.contains("$") || name == "main") return@forEachDescendantOfType

                val params = func.valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "p"}: ${p.typeReference?.text ?: "?"}"
                }
                val returnType = funcReturnTypes[name]?.simpleName
                    ?: func.typeReference?.text
                    ?: "?"
                results.add(SourceCodeCompletionVariant(
                    text = name, displayText = "fun $name($params)", tail = ": $returnType",
                    icon = "method",
                ))
            }

            // Classes
            ktFile.forEachDescendantOfType<KtClass> { cls ->
                val name = cls.name ?: return@forEachDescendantOfType
                if (!nameFilter(name) || name in existingNames || name.contains("$")) return@forEachDescendantOfType

                val kind = when {
                    cls.isData() -> "data class"
                    cls.isEnum() -> "enum class"
                    cls.isSealed() -> "sealed class"
                    cls.isInterface() -> "interface"
                    else -> "class"
                }
                results.add(SourceCodeCompletionVariant(
                    text = name, displayText = "$kind $name", tail = "",
                    icon = "class",
                ))
            }
        } catch (e: Exception) {
            lprintln("debug,kotlin", "FIR source declaration scan failed: ${e.message}")
        }
    }

    private fun splitDotChain(text: String): List<String> {
        // Split on dots, but respect parentheses: "foo.bar(x).baz" -> ["foo", "bar(x)", "baz"]
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var parenDepth = 0
        var bracketDepth = 0
        for (c in text) {
            when {
                c == '(' -> { parenDepth++; current.append(c) }
                c == ')' -> { parenDepth--; current.append(c) }
                c == '[' -> { bracketDepth++; current.append(c) }
                c == ']' -> { bracketDepth--; current.append(c) }
                c == '.' && parenDepth == 0 && bracketDepth == 0 -> {
                    if (current.isNotEmpty()) parts.add(current.toString())
                    current = StringBuilder()
                }
                c == '?' && parenDepth == 0 -> { /* skip safe-call ? */ }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    // --- Member enumeration from a KClass (reflection-based) ---

    /**
     * Add class names from the classpath that match [prefix], with constructor parameter info.
     * This makes typing "FLine" or "FLin" produce constructor suggestions.
     */
    private fun addClasspathClasses(prefix: String, results: MutableList<SourceCodeCompletionVariant>) {
        val existingNames = results.map { it.text }.toHashSet()
        val added = mutableSetOf<String>()
        val maxResults = 40

        val classCache = afield.cache // classpath name cache from importHelp.kt
        for (entry in classCache.entries) {
            if (added.size >= maxResults) break
            val simpleName = entry.key
            val fqNames: List<String> = entry.value
            if (!simpleName.startsWith(prefix, ignoreCase = true)) continue
            if (simpleName in existingNames || !added.add(simpleName)) continue
            if (simpleName.contains("$")) continue
            // Skip lowercase-starting names (packages, not classes)
            if (simpleName.firstOrNull()?.isUpperCase() != true) continue

            // Try to load the class and get constructor info
            var fqName: String? = null
            for (n in fqNames) { if (!n.contains("$")) { fqName = n; break } }
            if (fqName == null) continue
            val constructorInfo = try {
                val klass = classLoader?.loadClass(fqName)?.kotlin ?: Class.forName(fqName).kotlin
                val primary = klass.primaryConstructor ?: klass.constructors.firstOrNull()
                if (primary != null) {
                    val valueParams = primary.parameters.filter { it.kind == KParameter.Kind.VALUE }
                    val paramsStr = formatKotlinParams(valueParams)
                    paramsStr
                } else "()"
            } catch (_: Exception) {
                null
            }

            val displayText = if (constructorInfo != null) "$simpleName$constructorInfo" else simpleName
            results.add(SourceCodeCompletionVariant(
                text = simpleName, displayText = displayText, tail = fqName,
                icon = "class",
            ))
        }
    }

    /**
     * Lazy index of Kotlin file-level classes (ending in "Kt") per package from the classpath.
     * These classes contain top-level functions and extension functions.
     */
    private val kotlinFileClassesByPackage: Map<String, List<String>> by lazy {
        val result = mutableMapOf<String, MutableList<String>>()
        for ((_, fqNames) in afield.cache) {
            for (fqName in fqNames) {
                val simpleName = fqName.substringAfterLast('.')
                if (simpleName.endsWith("Kt") && !simpleName.contains('$')) {
                    val pkg = fqName.substringBeforeLast('.', "")
                    result.getOrPut(pkg) { mutableListOf() }.add(fqName)
                }
            }
        }
        result
    }

    /**
     * Add top-level functions and extension functions on implicit receiver types
     * from the imported packages (parsed from the intro preamble).
     * This enables completion of functions like lines(), display(), etc. that are
     * accessible as top-level calls in the REPL due to implicit receiver scope.
     */
    private fun addImportedTopLevelFunctions(
        prefix: String,
        results: MutableList<SourceCodeCompletionVariant>,
        existingNames: Set<String>
    ) {
        val nameFilter: (String) -> Boolean = { name ->
            prefix.isEmpty() || name.startsWith(prefix, ignoreCase = true)
        }
        val seen = existingNames.toMutableSet()

        // Get implicit receiver classes to identify extension functions callable as top-level
        val implicitReceiverClasses = implicitReceivers.mapNotNull { kotlinType ->
            try { kotlinType.fromClass?.java } catch (_: Exception) { null }
        }

        // Parse star-import packages from intro
        val starImportPackages = intro.lines()
            .map { it.trim() }
            .filter { it.startsWith("import ") && it.endsWith(".*") }
            .map { it.removePrefix("import ").removeSuffix(".*").trim() }

        for (pkg in starImportPackages) {
            val fileClasses = kotlinFileClassesByPackage[pkg] ?: continue
            for (fqClassName in fileClasses) {
                val javaClass = try {
                    classLoader?.loadClass(fqClassName) ?: continue
                } catch (_: Exception) { continue }

                for (method in javaClass.declaredMethods) {
                    if (!Modifier.isPublic(method.modifiers) || !Modifier.isStatic(method.modifiers)) continue
                    val name = method.name
                    if (name.contains('$') || name.contains('-')) continue // skip internal/mangled names
                    if (!nameFilter(name) || !seen.add(name)) continue

                    val kfunc = try { method.kotlinFunction } catch (_: Exception) { null }

                    if (kfunc != null) {
                        // Use Kotlin reflection — reliable extension receiver detection
                        val extensionReceiver = kfunc.parameters.firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }
                        if (extensionReceiver != null) {
                            // Extension function — only include if receiver matches an implicit receiver
                            val receiverClass = try { extensionReceiver.type.classifier as? KClass<*> } catch (_: Exception) { null }
                            val matchesImplicit = receiverClass != null &&
                                implicitReceiverClasses.any { it.isAssignableFrom(receiverClass.java) }
                            if (!matchesImplicit) continue  // skip extension on unrelated type

                            val valueParams = kfunc.parameters.filter { it.kind == KParameter.Kind.VALUE }
                            val paramsStr = formatKotlinParams(valueParams)
                            val returnTypeStr = try { shortenType(kfunc.returnType.toString()) } catch (_: Exception) { "Any?" }
                            val variant = SourceCodeCompletionVariant(
                                text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                                icon = "method",
                            )
                            // Embed KDoc for later use in documentation building
                            val qualifiedName = receiverClass?.simpleName?.let { "$it.$name" } ?: name
                            val kdoc = try { KotlinSourceIndex.getKDocForSymbol(qualifiedName) ?: KotlinSourceIndex.getKDocForSymbol(name) } catch (_: Exception) { null }
                            if (kdoc != null) variant.extraInfo = mapOf("kdoc" to kdoc)
                            results.add(variant)
                        } else {
                            // Plain top-level function
                            val valueParams = kfunc.parameters.filter { it.kind == KParameter.Kind.VALUE }
                            val paramsStr = formatKotlinParams(valueParams)
                            val returnTypeStr = try { shortenType(kfunc.returnType.toString()) } catch (_: Exception) { "Any?" }
                            val variant = SourceCodeCompletionVariant(
                                text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                                icon = "method",
                            )
                            val kdoc = try { KotlinSourceIndex.getKDocForSymbol(name) } catch (_: Exception) { null }
                            if (kdoc != null) variant.extraInfo = mapOf("kdoc" to kdoc)
                            results.add(variant)
                        }
                    } else {
                        // Java fallback — can't detect extension receivers, include as top-level
                        val params = method.parameters
                        val paramsStr = formatJavaParams(params.toList())
                        val returnTypeStr = shortenType(method.returnType.kotlin.simpleName ?: method.returnType.simpleName)
                        val variant = SourceCodeCompletionVariant(
                            text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                            icon = "method",
                        )
                        val kdoc = try { KotlinSourceIndex.getKDocForSymbol(name) } catch (_: Exception) { null }
                        if (kdoc != null) variant.extraInfo = mapOf("kdoc" to kdoc)
                        results.add(variant)
                    }
                }
            }
        }
    }

    private fun formatJavaParams(params: List<java.lang.reflect.Parameter>): String {
        if (params.isEmpty()) return "()"
        return "(${params.joinToString(", ") { p ->
            val name = p.name
            val type = shortenType(p.type.kotlin.simpleName ?: p.type.simpleName)
            if (name.matches(Regex("^(arg|p)\\d+$"))) type else "$name: $type"
        }})"
    }

    private fun addMembersFromClass(klass: KClass<*>, prefix: String, results: MutableList<SourceCodeCompletionVariant>) {
        val nameFilter: (String) -> Boolean = { name ->
            prefix.isEmpty() || name.startsWith(prefix, ignoreCase = true)
        }
        val seen = mutableSetOf<String>()
        val receiverName = klass.simpleName ?: "Unknown"

        fun tagExtra(variant: SourceCodeCompletionVariant, sortGroup: Int): SourceCodeCompletionVariant {
            variant.extraInfo = mapOf(
                "sortGroup" to sortGroup,
                "receiverType" to receiverName,
                "receiverQualifiedName" to (klass.qualifiedName ?: receiverName)
            )
            return variant
        }

        // Properties (including inherited)
        try {
            for (prop in klass.memberProperties) {
                val name = prop.name
                if (!nameFilter(name) || name.contains("$") || !seen.add(name)) continue
                val typeStr = try { shortenType(prop.returnType.toString()) } catch (_: Exception) { "Any?" }
                val kind = if (prop is KMutableProperty<*>) "var" else "val"
                val deprecated = if (prop.annotations.any { it is Deprecated }) DeprecationLevel.WARNING else null
                results.add(tagExtra(SourceCodeCompletionVariant(
                    text = name, displayText = "$kind $name", tail = ": $typeStr",
                    icon = kind, deprecationLevel = deprecated,
                ), 0))
            }
        } catch (_: Exception) {}

        // Extension properties from kotlin stdlib
        addCommonExtensions(klass, prefix, results, seen)

        // Functions — try Kotlin reflection first (gives proper parameter names for Kotlin-compiled classes),
        // then fall back to Java reflection for anything missed
        val kotlinFuncsSeen = mutableSetOf<String>()
        try {
            for (func in klass.members) {
                if (func !is KFunction<*>) continue
                val name = func.name
                if (!nameFilter(name) || name.contains("$")) continue
                if (name.startsWith("get") && func.parameters.count { it.kind == KParameter.Kind.VALUE } == 0 && name.length > 3) continue
                if (name.startsWith("set") && func.parameters.count { it.kind == KParameter.Kind.VALUE } == 1 && name.length > 3) continue
                if (name in setOf("hashCode", "equals", "toString", "notify", "notifyAll", "wait", "getClass")) continue
                if (!seen.add(name)) continue
                kotlinFuncsSeen.add(name)

                val valueParams = func.parameters.filter { it.kind == KParameter.Kind.VALUE }
                val paramsStr = formatKotlinParams(valueParams)
                val returnTypeStr = try { shortenType(func.returnType.toString()) } catch (_: Exception) { "Any?" }
                val deprecated = if (func.annotations.any { it is Deprecated }) DeprecationLevel.WARNING else null

                results.add(tagExtra(SourceCodeCompletionVariant(
                    text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                    icon = "method", deprecationLevel = deprecated,
                ), 0))
            }
        } catch (e: Exception) {
            lprintln("debug,kotlin", "Kotlin reflection memberFunctions failed for ${klass.simpleName}: ${e.message}")
        }
        // Fall back to Java reflection for any functions not covered by Kotlin reflection
        try {
            for (method in klass.java.methods) {
                val name = method.name
                if (!nameFilter(name) || name.contains("$") || name in kotlinFuncsSeen || !seen.add(name)) continue
                if (name.startsWith("get") && method.parameterCount == 0 && name.length > 3) continue
                if (name.startsWith("set") && method.parameterCount == 1 && name.length > 3) continue
                if (name in setOf("hashCode", "equals", "toString", "notify", "notifyAll", "wait", "getClass")) continue

                val paramsStr = formatJavaMethodParams(method)
                val returnTypeStr = formatMethodReturnType(method)
                val deprecated = isDeprecated(method)

                results.add(tagExtra(SourceCodeCompletionVariant(
                    text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                    icon = "method", deprecationLevel = deprecated,
                ), 0))
            }
        } catch (_: Exception) {}

        // Also add toString/hashCode/equals explicitly since they're commonly used
        if (!seen.contains("toString") && nameFilter("toString")) {
            results.add(tagExtra(SourceCodeCompletionVariant(text = "toString", displayText = "fun toString()", tail = ": String", icon = "method"), 3))
            seen.add("toString")
        }
        if (!seen.contains("hashCode") && nameFilter("hashCode")) {
            results.add(tagExtra(SourceCodeCompletionVariant(text = "hashCode", displayText = "fun hashCode()", tail = ": Int", icon = "method"), 3))
            seen.add("hashCode")
        }
        if (!seen.contains("equals") && nameFilter("equals")) {
            results.add(tagExtra(SourceCodeCompletionVariant(text = "equals", displayText = "fun equals(other: Any?)", tail = ": Boolean", icon = "method"), 3))
            seen.add("equals")
        }
    }

    private fun addCommonExtensions(klass: KClass<*>, prefix: String, results: MutableList<SourceCodeCompletionVariant>, seen: MutableSet<String>) {
        val nameFilter: (String) -> Boolean = { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }

        // Collection-like extensions
        val isIterable = Iterable::class.java.isAssignableFrom(klass.java)
        val isCollection = Collection::class.java.isAssignableFrom(klass.java)
        val isList = List::class.java.isAssignableFrom(klass.java)
        val isMap = Map::class.java.isAssignableFrom(klass.java)
        val isArray = klass.java.isArray
        val isString = klass == String::class
        val isComparable = Comparable::class.java.isAssignableFrom(klass.java)

        val receiverName = klass.simpleName ?: "Unknown"

        fun addExt(name: String, params: String, returnType: String, icon: String = "method", sortGroup: Int = 1) {
            if (nameFilter(name) && seen.add(name)) {
                val display = if (icon == "val" || icon == "var") "$icon $name" else "fun $name$params"
                val tail = ": $returnType"
                val variant = SourceCodeCompletionVariant(text = name, displayText = display, tail = tail, icon = icon)
                variant.extraInfo = mapOf(
                    "sortGroup" to sortGroup,
                    "receiverType" to receiverName,
                    "receiverQualifiedName" to (klass.qualifiedName ?: receiverName)
                )
                results.add(variant)
            }
        }

        if (isIterable || isArray) {
            addExt("filter", "(predicate: (T) -> Boolean)", "List<T>")
            addExt("map", "(transform: (T) -> R)", "List<R>")
            addExt("flatMap", "(transform: (T) -> Iterable<R>)", "List<R>")
            addExt("forEach", "(action: (T) -> Unit)", "Unit")
            addExt("forEachIndexed", "(action: (Int, T) -> Unit)", "Unit")
            addExt("any", "(predicate: (T) -> Boolean)", "Boolean")
            addExt("all", "(predicate: (T) -> Boolean)", "Boolean")
            addExt("none", "(predicate: (T) -> Boolean)", "Boolean")
            addExt("first", "()", "T")
            addExt("firstOrNull", "()", "T?")
            addExt("last", "()", "T")
            addExt("lastOrNull", "()", "T?")
            addExt("find", "(predicate: (T) -> Boolean)", "T?")
            addExt("count", "()", "Int")
            addExt("sortedBy", "(selector: (T) -> R)", "List<T>")
            addExt("sortedByDescending", "(selector: (T) -> R)", "List<T>")
            addExt("groupBy", "(keySelector: (T) -> K)", "Map<K, List<T>>")
            addExt("associate", "(transform: (T) -> Pair<K, V>)", "Map<K, V>")
            addExt("associateBy", "(keySelector: (T) -> K)", "Map<K, T>")
            addExt("toList", "()", "List<T>")
            addExt("toSet", "()", "Set<T>")
            addExt("toMutableList", "()", "MutableList<T>")
            addExt("joinToString", "(separator: String)", "String")
            addExt("take", "(n: Int)", "List<T>")
            addExt("drop", "(n: Int)", "List<T>")
            addExt("zip", "(other: Iterable<R>)", "List<Pair<T, R>>")
            addExt("distinct", "()", "List<T>")
            addExt("distinctBy", "(selector: (T) -> K)", "List<T>")
            addExt("flatten", "()", "List<T>")
            addExt("reversed", "()", "List<T>")
            addExt("shuffled", "()", "List<T>")
            addExt("maxByOrNull", "(selector: (T) -> R)", "T?")
            addExt("minByOrNull", "(selector: (T) -> R)", "T?")
            addExt("sumOf", "(selector: (T) -> Int)", "Int")
            addExt("withIndex", "()", "Iterable<IndexedValue<T>>")
            addExt("mapIndexed", "(transform: (Int, T) -> R)", "List<R>")
            addExt("filterNot", "(predicate: (T) -> Boolean)", "List<T>")
            addExt("filterNotNull", "()", "List<T>")
            addExt("partition", "(predicate: (T) -> Boolean)", "Pair<List<T>, List<T>>")
            addExt("chunked", "(size: Int)", "List<List<T>>")
            addExt("windowed", "(size: Int)", "List<List<T>>")
        }
        if (isList || isArray) {
            addExt("indices", "", "IntRange", "val")
            addExt("lastIndex", "", "Int", "val")
            addExt("getOrNull", "(index: Int)", "T?")
            addExt("getOrElse", "(index: Int, defaultValue: (Int) -> T)", "T")
        }
        if (isCollection) {
            addExt("isNotEmpty", "()", "Boolean")
            addExt("isNullOrEmpty", "()", "Boolean")
            addExt("orEmpty", "()", "Collection<T>")
        }
        if (isMap) {
            addExt("getOrDefault", "(key: K, defaultValue: V)", "V")
            addExt("getOrElse", "(key: K, defaultValue: () -> V)", "V")
            addExt("mapValues", "(transform: (Map.Entry<K, V>) -> R)", "Map<K, R>")
            addExt("mapKeys", "(transform: (Map.Entry<K, V>) -> R)", "Map<R, V>")
            addExt("filterKeys", "(predicate: (K) -> Boolean)", "Map<K, V>")
            addExt("filterValues", "(predicate: (V) -> Boolean)", "Map<K, V>")
            addExt("toList", "()", "List<Pair<K, V>>")
            addExt("toMutableMap", "()", "MutableMap<K, V>")
        }
        if (isString) {
            addExt("lines", "()", "List<String>")
            addExt("trim", "()", "String")
            addExt("trimEnd", "()", "String")
            addExt("trimStart", "()", "String")
            addExt("split", "(delimiter: String)", "List<String>")
            addExt("substringBefore", "(delimiter: String)", "String")
            addExt("substringAfter", "(delimiter: String)", "String")
            addExt("removePrefix", "(prefix: String)", "String")
            addExt("removeSuffix", "(suffix: String)", "String")
            addExt("toIntOrNull", "()", "Int?")
            addExt("toDoubleOrNull", "()", "Double?")
            addExt("isBlank", "()", "Boolean")
            addExt("isNotBlank", "()", "Boolean")
            addExt("isNullOrBlank", "()", "Boolean")
            addExt("repeat", "(n: Int)", "String")
            addExt("padStart", "(length: Int)", "String")
            addExt("padEnd", "(length: Int)", "String")
            addExt("lowercase", "()", "String")
            addExt("uppercase", "()", "String")
            addExt("capitalize", "()", "String")
            addExt("decapitalize", "()", "String")
            addExt("toRegex", "()", "Regex")
        }
        if (isComparable) {
            addExt("coerceIn", "(min: T, max: T)", "T")
            addExt("coerceAtLeast", "(minimumValue: T)", "T")
            addExt("coerceAtMost", "(maximumValue: T)", "T")
        }

        // Universal extensions (available on Any/Any?) — sort after type-specific extensions
        addExt("let", "(block: (T) -> R)", "R", sortGroup = 2)
        addExt("also", "(block: (T) -> Unit)", "T", sortGroup = 2)
        addExt("apply", "(block: T.() -> Unit)", "T", sortGroup = 2)
        addExt("run", "(block: T.() -> R)", "R", sortGroup = 2)
        addExt("takeIf", "(predicate: (T) -> Boolean)", "T?", sortGroup = 2)
        addExt("takeUnless", "(predicate: (T) -> Boolean)", "T?", sortGroup = 2)
        addExt("to", "(that: B)", "Pair<A, B>", sortGroup = 2)
    }

    private fun addStaticMembersIfPackageOrClass(receiverText: String, prefix: String, results: MutableList<SourceCodeCompletionVariant>) {
        // Try to resolve as a class name (supports short names like "Math" → java.lang.Math)
        val klass = resolveTypeName(receiverText) ?: return

        val nameFilter: (String) -> Boolean = { name ->
            prefix.isEmpty() || name.startsWith(prefix, ignoreCase = true)
        }
        val seen = mutableSetOf<String>()

        // Add companion object members (Kotlin classes)
        try {
            val companion = klass.companionObject
            if (companion != null) {
                for (prop in companion.memberProperties) {
                    val name = prop.name
                    if (!nameFilter(name) || name.contains("$") || !seen.add(name)) continue
                    val typeStr = try { shortenType(prop.returnType.toString()) } catch (_: Exception) { "Any?" }
                    val kind = if (prop is KMutableProperty<*>) "var" else "val"
                    val deprecated = if (prop.annotations.any { it is Deprecated }) DeprecationLevel.WARNING else null
                    results.add(SourceCodeCompletionVariant(
                        text = name, displayText = "$kind $name", tail = ": $typeStr",
                        icon = kind, deprecationLevel = deprecated,
                    ))
                }
                for (func in companion.declaredFunctions) {
                    val name = func.name
                    if (!nameFilter(name) || name.contains("$") || !seen.add(name)) continue
                    val paramsStr = formatKotlinParams(func.parameters.filter { it.kind == KParameter.Kind.VALUE })
                    val returnTypeStr = try { shortenType(func.returnType.toString()) } catch (_: Exception) { "Any?" }
                    val deprecated = if (func.annotations.any { it is Deprecated }) DeprecationLevel.WARNING else null
                    results.add(SourceCodeCompletionVariant(
                        text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                        icon = "method", deprecationLevel = deprecated,
                    ))
                }
            }
        } catch (_: Exception) {}

        // Add Java static methods and fields (e.g., Math.random(), Math.PI)
        try {
            val javaClass = klass.java
            for (method in javaClass.methods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                val name = method.name
                if (!nameFilter(name) || name.contains("$") || !seen.add(name)) continue
                if (name in setOf("getClass", "wait", "notify", "notifyAll")) continue
                val paramsStr = formatJavaMethodParams(method)
                val returnTypeStr = formatMethodReturnType(method)
                val deprecated = isDeprecated(method)
                results.add(SourceCodeCompletionVariant(
                    text = name, displayText = "fun $name$paramsStr", tail = ": $returnTypeStr",
                    icon = "method", deprecationLevel = deprecated,
                ))
            }
            for (field in javaClass.fields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                val name = field.name
                if (!nameFilter(name) || name.contains("$") || !seen.add(name)) continue
                val typeStr = try { shortenType(field.type.kotlin.simpleName ?: field.type.simpleName) } catch (_: Exception) { "Any?" }
                val kind = if (Modifier.isFinal(field.modifiers)) "val" else "var"
                results.add(SourceCodeCompletionVariant(
                    text = name, displayText = "$kind $name", tail = ": $typeStr",
                    icon = kind,
                ))
            }
        } catch (_: Exception) {}
    }

    private fun extractPrefix(s: String, cursor: Int): String {
        if (cursor <= 0 || cursor > s.length) return ""
        var start = cursor - 1
        while (start >= 0 && (s[start].isLetterOrDigit() || s[start] == '_')) {
            start--
        }
        return s.substring(start + 1, cursor)
    }

    override fun completeAndWait(s: String, cursor: Int): CompletionResult {
        return runBlocking { complete(s, cursor) }
    }

    override fun applyMacros(box: Box, s: String, skipMacros: Set<String>, previewOnly: Boolean): String {
        var rewritten = s
        val needsRerun = false
        lblock("debug,kotlin", "rewriting AST analysis") {
            do {
                val factory = PsiFileFactory.getInstance(psiProject)
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

                                        val containingFunction = PsiTreeUtil.getParentOfType(expression, KtNamedFunction::class.java)
                                        val containingFunctionName = containingFunction?.name
                                        val valueParameterList =
                                            (containingFunction?.valueParameterList as? KtParameterList)?.text
                                        val returnParameterText =
                                            (containingFunction?.typeReference as? KtTypeReference)?.text

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

                                            edits.add((expression.startOffset to expression.endOffset) to e.here)

                                            if (!previewOnly) {
                                                if (e.preamble.isNotBlank() || e.preambleAfterImports.isNotBlank()) {
                                                    applyInsertToPreamble(box, e)
                                                }
                                            }
                                        }
                                    }

                                    else -> {
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

    override var execNumber = 0
    override suspend fun exec(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit,
        onEvalError: (List<ScriptDiagnostic>) -> Unit,
        onEvalException: (Throwable) -> Unit,
        implicits: List<Any>,
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

    /**
     * PreparedSnippet represents a pre-compiled code snippet that can be evaluated multiple times.
     */
    inner class K2PreparedSnippet internal constructor(
        private val compiled: LinkedSnippet<out CompiledScript>,
        private val defaultImplicits: List<Any>
    ) : afield.PreparedSnippet {
        override suspend fun eval(
            updateContext: Boolean,
            implicits: List<Any>,
            onEvalError: (List<ScriptDiagnostic>) -> Unit,
            onEvalException: (Throwable) -> Unit
        ): ResultValue? {
            return when (val r = evaluate(compiled, updateContext, implicits.ifEmpty { defaultImplicits })) {
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

    override suspend fun compileSnippet(
        s: String,
        implicits: List<Any>,
        onCompileError: (List<ScriptDiagnostic>) -> Unit
    ): K2PreparedSnippet? {
        return when (val r = compile(s)) {
            is ResultWithDiagnostics.Success -> {
                K2PreparedSnippet(r.value, implicits)
            }

            is ResultWithDiagnostics.Failure -> {
                lprintln("error,kotlin", "compiler error ${r}")
                onCompileError(r.reports)
                null
            }
        }
    }
    override fun compileSnippetAndWait(
        s: String,
        implicits: List<Any>,
        onCompileError: (List<ScriptDiagnostic>) -> Unit
    ): K2PreparedSnippet? {
        return runBlocking {
            compileSnippet(s, implicits, onCompileError)
        }
    }


    /**
     * Dispose the underlying K2 REPL compilation state.
     * Call this when the KotlinInterface is no longer needed.
     */
    override fun dispose() {
        Disposer.dispose(rootDisposable)
    }


    companion object {

        var sharedClassLoader: ClassLoader? = null

        // multi-threaded compilation broken in current kotlin compiler
        var compilationThread = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

        fun functionSignature(function: KFunction<*>) = afield.functionSignature(function)

        fun shortenType(name: String) = afield.shortenType(name)

        fun getTokenBounds(buf: String, cursor: Int) = afield.getTokenBounds(buf, cursor)

        fun getResult(
            code: String, cursor: Int, completions: List<SourceCodeCompletionVariant>
        ) = getCompletionResult(code, cursor, completions)
    }

    class ContextUpdater(
        val context: KotlinContext,
        private val evaluator: K2ReplEvaluator,
        private val ki: KotlinInterface2
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
                Regex("""^(val|var)\s+Box\.$nameEscaped\b.*\bby\b.*$"""),
                Regex("""^(val|var)\s+$nameEscaped\b.*\bby\b.*$"""),
                Regex("""^(val|var)\s+Box\.$nameEscaped\b\s*:\s*.*$"""),
                Regex("""^(val|var)\s+$nameEscaped\b\s*:\s*.*$""")
            )
            return patterns.any { it.matches(withoutPrefixComment) }
        }

        private fun createBoundFunctionReference(function: KFunction<*>, line: Any): Any {
            val arity = function.parameters.count { it.kind != KParameter.Kind.INSTANCE }
            val functionInterface = when (arity) {
                0 -> kotlin.Function0::class.java
                1 -> kotlin.Function1::class.java
                2 -> kotlin.Function2::class.java
                3 -> kotlin.Function3::class.java
                4 -> kotlin.Function4::class.java
                5 -> kotlin.Function5::class.java
                6 -> kotlin.Function6::class.java
                7 -> kotlin.Function7::class.java
                8 -> kotlin.Function8::class.java
                9 -> kotlin.Function9::class.java
                10 -> kotlin.Function10::class.java
                11 -> kotlin.Function11::class.java
                12 -> kotlin.Function12::class.java
                13 -> kotlin.Function13::class.java
                14 -> kotlin.Function14::class.java
                15 -> kotlin.Function15::class.java
                16 -> kotlin.Function16::class.java
                17 -> kotlin.Function17::class.java
                18 -> kotlin.Function18::class.java
                19 -> kotlin.Function19::class.java
                20 -> kotlin.Function20::class.java
                21 -> kotlin.Function21::class.java
                22 -> kotlin.Function22::class.java
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
            val whitelistedPrefixes = setOf(
                "kotlin.",
                "afield.",
                "org.joml.",
                "Int", "Long", "Short", "Byte",
                "Float", "Double",
                "Boolean", "Char", "String",
                "Unit", "Any", "Nothing",
                "List", "Set", "Map", "Array",
                "MutableList", "MutableSet", "MutableMap",
                "Sequence", "Iterable", "Collection",
            )

            if (whitelistedPrefixes.any { typeName.startsWith(it) }) {
                return typeName
            }

            return when {
                typeName.contains("List") -> "List<*>"
                typeName.contains("Set") -> "Set<*>"
                typeName.contains("Map") -> "Map<*, *>"
                typeName.contains("Collection") -> "Collection<*>"
                typeName.contains("Iterable") -> "Iterable<*>"
                typeName.contains("Sequence") -> "Sequence<*>"
                else -> "Any?"
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
                    val isExported = field.isAnnotationPresent(exported::class.java) ||
                            descriptor.annotations.any { it.annotationClass == exported::class } ||
                            descriptor.getter.annotations.any { it.annotationClass == exported::class }
                    if (isExported) {
                        processExportedProperty(descriptor.name, descriptor, value)
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

                val isExported = descriptor.annotations.any { it.annotationClass == exported::class } ||
                        descriptor.getter.annotations.any { it.annotationClass == exported::class }
                if (isExported) {
                    processExportedProperty(name, descriptor, value)
                }
            }
        }

        companion object {
            private val objectMethods = HashSet(listOf(*Any::class.java.methods))
        }
    }
}
