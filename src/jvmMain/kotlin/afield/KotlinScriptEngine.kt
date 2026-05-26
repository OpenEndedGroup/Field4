package afield

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import java.util.*
import kotlin.reflect.*
import kotlin.script.experimental.api.*

/**
 * Common interface for Kotlin scripting engines used by Field4.
 *
 * Two implementations exist:
 * - [KotlinInterface]: Uses the legacy K1 compiler and KJvmReplCompilerWithIdeServices (well-tested, deprecated backend)
 * - [KotlinInterface2]: Uses the K2 compiler and Analysis API (future-proof, targeting Kotlin 2.4+)
 *
 * Use [createKotlinScriptEngine] to obtain the active implementation based on compile-time configuration.
 */
interface KotlinScriptEngine {

    val context: KotlinContext
    var associatedBox: Any?
    var compilerConfig: ScriptCompilationConfiguration
    var classLoader: ClassLoader?
    var execNumber: Int

    /**
     * Dispatcher for CPU-bound compilation and macro-expansion work.
     * K1 returns its single [KotlinInterface.compilationThread] (K1 is not thread-safe).
     * K2 returns [Dispatchers.Default], with each K2 REPL state serializing its own compiles.
     */
    val compilationDispatcher: CoroutineDispatcher
        get() = Dispatchers.Default

    suspend fun exec(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalException: (Throwable) -> Unit = { throw it },
        implicits: List<Any> = emptyList(),
    ): ResultValue?

    fun execAndWait(
        s: String,
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalException: (Throwable) -> Unit = {},
        implicits: List<Any> = emptyList()
    ): ResultValue?

    suspend fun complete(s: String, cursor: Int): CompletionResult

    fun completeAndWait(s: String, cursor: Int = s.length): CompletionResult

    fun applyMacros(box: Box, s: String, skipMacros: Set<String> = emptySet(), previewOnly: Boolean = false): String

    fun parseAndRewrite(s: String, rules: List<(PsiElement, String) -> String>): String

    fun findLastImport(s: String): Int

    suspend fun compileSnippet(
        s: String,
        implicits: List<Any> = emptyList(),
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {}
    ): PreparedSnippet?

    fun compileSnippetAndWait(
        s: String,
        implicits: List<Any> = emptyList(),
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {}
    ): PreparedSnippet?

    /**
     * Dispose any resources held by this engine.
     * Not all implementations require disposal; default is a no-op.
     */
    fun dispose() {}
}

/**
 * A pre-compiled code snippet that can be evaluated multiple times.
 * Obtained from [KotlinScriptEngine.compileSnippet] or [KotlinScriptEngine.compileSnippetAndWait].
 */
interface PreparedSnippet {
    suspend fun eval(
        updateContext: Boolean = false,
        implicits: List<Any> = emptyList(),
        onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalException: (Throwable) -> Unit = { throw it }
    ): ResultValue?

    fun evalAndWait(
        updateContext: Boolean = false,
        implicits: List<Any> = emptyList(),
        onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalException: (Throwable) -> Unit = {}
    ): ResultValue?
}


// ---------------------------------------------------------------------------
// Shared types previously duplicated in KotlinInterface and KotlinInterface2
// ---------------------------------------------------------------------------

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


enum class CompletionStatus(private val value: String) {
    OK("ok"), ERROR("error");

    override fun toString(): String = value
}


abstract class CompletionResult(private val status: CompletionStatus) {

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
        CompletionResult(CompletionStatus.ERROR)
}


data class CompletionTokenBounds(val start: Int, val end: Int)


class KotlinContext(
    val vars: HashMap<String, KotlinVariableInfo> = HashMap(),
    val functions: HashMap<String, KotlinFunctionInfo> = HashMap(),
    var generation: Int = 0,
    val debugScripts: KotlinDebugScriptTracker = KotlinDebugScriptTracker()
)

data class KotlinDebugScriptSnapshot(
    val sourceName: String,
    val sourceText: String,
    val normalizedSourceText: String = normalizeDebugSourceText(sourceText),
    val sourceHash: Int = normalizedSourceText.hashCode(),
    var className: String? = null,
    var updatedAtMs: Long = System.currentTimeMillis()
)

class KotlinDebugScriptTracker(private val maxEntries: Int = 64) {
    private val snapshots = ArrayDeque<KotlinDebugScriptSnapshot>()

    @Synchronized
    fun recordCompile(sourceName: String, sourceText: String): KotlinDebugScriptSnapshot {
        val snapshot = KotlinDebugScriptSnapshot(
            sourceName = sourceName,
            sourceText = sourceText
        )
        snapshots.addLast(snapshot)
        trim()
        return snapshot
    }

    @Synchronized
    fun discard(snapshot: KotlinDebugScriptSnapshot) {
        snapshots.remove(snapshot)
    }

    @Synchronized
    fun attachLatestRuntimeClass(className: String?) {
        if (className.isNullOrBlank()) return
        snapshots.lastOrNull()?.let {
            it.className = className
            it.updatedAtMs = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun latestMatchingSource(sourceText: String): KotlinDebugScriptSnapshot? {
        val normalized = normalizeDebugSourceText(sourceText)
        val hash = normalized.hashCode()
        return snapshots.lastOrNull { it.sourceHash == hash && it.normalizedSourceText == normalized }
    }

    @Synchronized
    fun latest(): KotlinDebugScriptSnapshot? = snapshots.lastOrNull()

    @Synchronized
    fun all(): List<KotlinDebugScriptSnapshot> = snapshots.toList()

    private fun trim() {
        while (snapshots.size > maxEntries) {
            snapshots.removeFirst()
        }
    }
}

private fun normalizeDebugSourceText(sourceText: String): String =
    sourceText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trimEnd()


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
        get() = descriptor.toString()

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
            append("</div>")

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


// ---------------------------------------------------------------------------
// Shared utility functions
// ---------------------------------------------------------------------------

fun functionSignature(function: KFunction<*>): String =
    function.toString().replace("Line_\\d+\\.".toRegex(), "")

fun shortenType(name: String): String =
    name.replace("(\\b[_a-zA-Z$][_a-zA-Z0-9$]*\\b\\.)+".toRegex(), "")

fun getTokenBounds(buf: String, cursor: Int): CompletionTokenBounds {
    require(cursor <= buf.length) { "Position $cursor does not exist in code snippet <$buf>" }
    val startSubstring = buf.substring(0, cursor)
    val filter = { c: Char -> !c.isLetterOrDigit() && c != '_' }
    val start = startSubstring.indexOfLast(filter) + 1
    return CompletionTokenBounds(start, cursor)
}

fun getCompletionResult(
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


// ---------------------------------------------------------------------------
// Internal HTML helpers shared by both implementations
// ---------------------------------------------------------------------------

internal fun kotlinInfoEscapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

internal fun kotlinInfoClip(text: String, maxChars: Int = 220): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars - 1) + "…"
}

internal fun kotlinInfoTypeName(value: Any?): String {
    if (value == null) return "null"
    return value::class.qualifiedName ?: value::class.simpleName ?: value::class.java.name
}


// ---------------------------------------------------------------------------
// Factory / compile-time switching
// ---------------------------------------------------------------------------

/**
 * Controls which Kotlin scripting backend to use.
 *
 * Set via the system property `afield.kotlin.engine`:
 * - `k1` (default): Legacy KJvmReplCompilerWithIdeServices — well-tested, deprecated compiler
 * - `k2`: K2 compiler + Analysis API — future-proof, targeting Kotlin 2.4+
 *
 * Can also be set programmatically before any KotlinScriptEngine is created.
 */
object KotlinEngineConfig {
    enum class Backend { K1, K2 }

    var activeBackend: Backend = when (System.getProperty("afield.kotlin.engine", "k2").lowercase()) {
        "k2" -> Backend.K2
        else -> Backend.K1
    }

    /**
     * When true (default), compilation runs on a background dispatcher so the Runloop
     * frame loop is not blocked while the Kotlin compiler works.
     *
     * K2 dispatches to [kotlinx.coroutines.Dispatchers.Default], but serializes compiles per REPL state.
     * K1 dispatches to its single [KotlinInterface.compilationThread] (K1 is not thread-safe).
     *
     * Set to false to force compilation on the calling dispatcher (legacy behaviour).
     */
    var offloadCompilation: Boolean = System.getProperty("afield.kotlin.offloadCompilation", "true").toBoolean()
}


/**
 * Creates a [KotlinScriptEngine] using the currently configured backend.
 *
 * @see KotlinEngineConfig.activeBackend
 */
fun createKotlinScriptEngine(
    propertyClasses: Map<String, KClass<*>> = emptyMap(),
    properties: Map<String, Any?> = emptyMap(),
    implicitReceivers: List<KotlinType> = emptyList(),
    classLoader: ClassLoader? = null,
    filePrefix: String = "EVAL_"
): KotlinScriptEngine = when (KotlinEngineConfig.activeBackend) {
    KotlinEngineConfig.Backend.K1 -> KotlinInterface(propertyClasses, properties, implicitReceivers, classLoader, filePrefix)
    KotlinEngineConfig.Backend.K2 -> KotlinInterface2(propertyClasses, properties, implicitReceivers, classLoader, filePrefix)
}
