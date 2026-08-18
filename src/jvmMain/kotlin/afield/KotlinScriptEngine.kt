package afield

import afield.logger.lprintln
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import java.util.*
import kotlin.reflect.*
import kotlin.script.experimental.api.*

internal const val fieldKotlinLanguageVersion = "2.4"
internal val fieldKotlinExperimentalCompilerOptions = listOf(
    "-Xcollection-literals",
    "-Xname-based-destructuring=only-syntax",
)
internal val fieldKotlinJvmCompilerOptions = listOf("-jvm-target", "24") + fieldKotlinExperimentalCompilerOptions

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

    /**
     * The mutable Kotlin REPL context for this engine: tracked variables and functions, its
     * generation counter, and debug-script history.
     */
    val context: KotlinContext
    var associatedBox: Any?
    var compilerConfig: ScriptCompilationConfiguration
    var classLoader: ClassLoader?
    var execNumber: Int
    var sourceProperty: String

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

    /**
     * Run the stdlib bootstrap ([s], the intro imports) as this engine's first evaluation.
     *
     * An engine that gates ordinary [exec]/[evaluate] on bootstrap completion (so no snippet
     * evaluates before the intro that defines the K2 REPL `ReplState`) MUST run the bootstrap
     * itself *without* that gate and release the gate afterwards — otherwise the bootstrap
     * would wait on itself. Engines with no such gate can just [exec] it (the default).
     */
    suspend fun bootstrap(
        s: String,
        implicits: List<Any> = emptyList(),
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
        onEvalException: (Throwable) -> Unit = { throw it },
    ): ResultValue? = exec(s, onCompileError, onEvalError, onEvalException, implicits)

    /**
     * Arm the engine's bootstrap-readiness gate (make it pending) so ordinary [exec]/[evaluate]
     * block until [bootstrap] (or [releaseBootstrapGate]) opens it. Call this right after
     * building an engine that will be bootstrapped asynchronously. Engines left un-armed (the
     * default) never block — used directly in tests and ad-hoc snippet execution. No-op for
     * engines without such a gate.
     */
    fun armBootstrapGate() {}

    /**
     * Open the engine's bootstrap-readiness gate unconditionally, in case [bootstrap] never
     * ran (e.g. the box was tombstoned before it could) — otherwise a later [exec]/[evaluate]
     * would wait forever. Idempotent; a no-op for engines without such a gate.
     */
    fun releaseBootstrapGate() {}

    suspend fun complete(s: String, cursor: Int): CompletionResult

    /**
     * Eagerly build whatever machinery [complete] needs (for K2 this is the K1 completion
     * delegate — a full intro+preamble compile). Doing it up front, on the same main-runloop
     * dispatcher [complete] uses, gets the one heavy compile out of the way outside any
     * re-entrant runloop pump, so later completions are light and single-threaded-safe.
     * Idempotent; a no-op for engines with no lazy completion bootstrap.
     */
    suspend fun warmCompletion() {}

    /**
     * Build the *serving* completion backend, not just the fallback.
     *
     * [warmCompletion] warms the K1 delegate, which is the safety net; when the AA backend is selected
     * the first requests are still served by K1, because the sidecar answers "session building (warming
     * up; using K1 meanwhile)" until its Analysis-API session exists. A person sees that as one K1
     * completion and then AA — but a *capture* sees it as a picture of the fallback, which is why the
     * docs harness calls this before a completion figure. Blocks until the serving backend answers or
     * [timeoutMs] passes; returns a short status for the log. No-op (`"unsupported"`) for engines with
     * no second backend.
     */
    suspend fun warmCompletionBackend(timeoutMs: Long = 120_000): String = "unsupported"

    /**
     * Fire-and-forget proactive warm: schedule [warmCompletion] on the main-runloop dispatcher so
     * the one heavy K1 bootstrap happens *before* the first completion/error-suggestion request
     * rather than blocking it. Idempotent and self-de-duplicating (a no-op while a warm is already
     * in flight, and cheap when already warm for the current preamble). Called on interactive code
     * edits so the intelligent-error path is warm by run time. No-op for engines with no bootstrap.
     */
    fun warmCompletionAsync() {}

    fun completeAndWait(s: String, cursor: Int = s.length): CompletionResult

    fun applyMacros(box: Box, s: String, skipMacros: Set<String> = emptySet(), previewOnly: Boolean = false): String

    fun parseAndRewrite(s: String, rules: List<(PsiElement, String) -> String>): String

    fun findLastImport(s: String): Int

    /**
     * Compile [s] against this engine's current REPL state without evaluating it.
     *
     * The returned [PreparedSnippet] can be evaluated repeatedly without recompiling [s].
     * [implicits] become its default implicit receivers; a non-empty list passed to
     * [PreparedSnippet.eval] overrides them for that evaluation. Returns `null` after reporting
     * compiler diagnostics through [onCompileError].
     */
    suspend fun compileSnippet(
        s: String,
        implicits: List<Any> = emptyList(),
        onCompileError: (List<ScriptDiagnostic>) -> Unit = {}
    ): PreparedSnippet?

    /** Blocking wrapper around [compileSnippet]. */
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

private fun sanitizeFieldSourceProperty(property: String): String {
    val sanitized = property.ifBlank { "code" }.map {
        if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_'
    }.joinToString("")
    return sanitized.ifBlank { "code" }
}

internal fun fieldSourceName(filePrefix: String, property: String, number: Int): String =
    "${filePrefix}__${sanitizeFieldSourceProperty(property)}_\$$number"

internal fun fieldSourcePropertyFromFileName(fileName: String?): String? {
    val name = fileName ?: return null
    val marker = "__"
    val at = name.indexOf(marker)
    if (at < 0) return null
    val rest = name.substring(at + marker.length)
    val end = rest.lastIndexOf("_$")
    return (if (end >= 0) rest.substring(0, end) else rest).ifBlank { null }
}

suspend fun <T> KotlinScriptEngine.withSourceProperty(property: String, block: suspend () -> T): T {
    val previous = sourceProperty
    sourceProperty = property.ifBlank { "code" }
    try {
        return block()
    } finally {
        sourceProperty = previous
    }
}

/**
 * Code compiled against one [KotlinScriptEngine] REPL state and reusable within that state.
 *
 * Evaluation observes changes subsequently made to variables in the parent engine. With
 * `updateContext` false, declarations and assignments made by the snippet are not committed to
 * later REPL evaluations; use true when the snippet intentionally advances shared state.
 *
 * The suspending [eval] rethrows runtime exceptions by default. The blocking [evalAndWait] instead
 * has a no-op default exception callback, matching the other blocking engine helpers. In either
 * form, a non-empty `implicits` list replaces the defaults captured at compile time.
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

internal fun translateKotlinEvalException(throwable: Throwable, source: String? = null): Throwable {
    val isMissingReplState = throwable.causeChain().any {
        val message = it.message.orEmpty().replace('/', '.')
        (it is NoClassDefFoundError || it is ClassNotFoundException) &&
            (message == "ReplState" || message.endsWith(".ReplState"))
    }
    if (!isMissingReplState) return throwable

    val packageDeclaration = source
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("package ") }

    val packageMessage = packageDeclaration?.let {
        " Remove `$it`; use `import ...` lines instead."
    } ?: " This usually means the snippet contains a `package ...` declaration; use `import ...` lines instead."

    return IllegalArgumentException(
        "Field code properties are evaluated as Kotlin REPL snippets, not standalone Kotlin source files." +
            packageMessage,
        throwable
    )
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
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

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class rootExported

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class metarootExported


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


/**
 * Mutable bookkeeping for one Kotlin scripting context.
 *
 * [vars] and [functions] are lookup tables populated from declarations introduced or exported during
 * script execution; [generation] changes when the visible context is updated.
 */
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

val kotlinCompletionBlacklistPatterns: List<String>
    get() = currentFieldPreferences().importClassBlacklist

fun kotlinCompletionBlacklistPatternMatches(pattern: String, candidate: String): Boolean {
    val normalizedPattern = pattern.trim().lowercase(Locale.ROOT)
    val normalizedCandidate = candidate.trim().lowercase(Locale.ROOT)
    if (normalizedPattern.isBlank() || normalizedCandidate.isBlank()) return false
    if ('*' !in normalizedPattern) return normalizedCandidate == normalizedPattern

    val parts = normalizedPattern.split('*')
    var offset = 0
    val first = parts.first()
    if (first.isNotEmpty()) {
        if (!normalizedCandidate.startsWith(first)) return false
        offset = first.length
    }
    for (part in parts.drop(1).dropLast(1)) {
        if (part.isEmpty()) continue
        val next = normalizedCandidate.indexOf(part, offset)
        if (next < 0) return false
        offset = next + part.length
    }
    val last = parts.last()
    if (last.isEmpty()) return true
    val next = normalizedCandidate.indexOf(last, offset)
    return next >= 0 && normalizedCandidate.endsWith(last)
}

fun isBlacklistedKotlinCompletionName(
    name: String,
    blacklistPatterns: List<String> = kotlinCompletionBlacklistPatterns
): Boolean {
    val normalized = name.trim()
        .removePrefix("import ")
        .replace('$', '.')
        .lowercase(Locale.ROOT)
    if (normalized.isBlank()) return false

    return normalized.split(Regex("\\s+")).any { token ->
        val candidate = token.trim('`', '"', '\'', '(', ')', '[', ']', '{', '}', ',', ';', ':')
        blacklistPatterns.any { pattern ->
            kotlinCompletionBlacklistPatternMatches(pattern, candidate)
        }
    }
}

fun isBlacklistedKotlinCompletionCandidate(
    variant: SourceCodeCompletionVariant,
    blacklistPatterns: List<String> = kotlinCompletionBlacklistPatterns
): Boolean {
    return sequenceOf(
        variant.text,
        variant.displayText,
        variant.tail
    ).plus(
        variant.extraInfo.values.asSequence().mapNotNull { it as? String }
    ).any { isBlacklistedKotlinCompletionName(it, blacklistPatterns) }
}

fun getCompletionResult(
    code: String, cursor: Int, completions: List<SourceCodeCompletionVariant>
): CompletionResult.Success {
    val blacklistPatterns = kotlinCompletionBlacklistPatterns
    val filteredCompletions = completions.filterNot { isBlacklistedKotlinCompletionCandidate(it, blacklistPatterns) }
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
 * - `k1`: Legacy KJvmReplCompilerWithIdeServices — well-tested, deprecated compiler
 * - `k2` (default): K2 compiler + Analysis API — future-proof, targeting Kotlin 2.4+
 *
 * Can also be set programmatically before any KotlinScriptEngine is created.
 */
object KotlinEngineConfig {
    enum class Backend { K1, K2 }

    var activeBackend: Backend = when (System.getProperty("afield.kotlin.engine", "k2").lowercase()) {
        "k2" -> Backend.K2
        else -> Backend.K1
    }

    /** Completion candidate-generation backend, independent of the execution [activeBackend]. AA is the
     *  out-of-process K2 Analysis-API sidecar (`AaCompletionClient` → `/complete`) and is the DEFAULT
     *  (Stage D cutover): it matches-or-beats K1 recall, passes all anti-goals, and adds named-arg
     *  completion. It only engages when a sidecar URL is configured
     *  (`afield.completion.sidecarUrl` / `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`); otherwise, and on any
     *  timeout / error / empty result, it falls through to the compiler-grade K1 delegate, so AA is
     *  never worse than K1. Force the legacy path with `-Dafield.kotlin.completion=k1`. */
    enum class CompletionBackend { K1, AA }

    var activeCompletionBackend: CompletionBackend = when (System.getProperty("afield.kotlin.completion", "aa").lowercase()) {
        "k1" -> CompletionBackend.K1
        else -> CompletionBackend.AA
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

    /**
     * When true (the default), [KotlinScriptEngine.applyMacros] prints the post-macro-expansion
     * source — the code actually handed to the Kotlin compiler — to the terminal whenever macro
     * expansion changed the code (execute path only, never editor previews). Invaluable for
     * debugging `rewrite {}`/`unquote {}`/builtin-macro output. Disable with
     * `-Dafield.macros.logExpanded=false`.
     */
    var logExpandedMacros: Boolean = System.getProperty("afield.macros.logExpanded", "true").toBoolean()
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
): KotlinScriptEngine {
    val backend = KotlinEngineConfig.activeBackend
    lprintln(
        "debug,ki,startup",
        "create KotlinScriptEngine backend=$backend filePrefix=$filePrefix implicitReceivers=${implicitReceivers.size} properties=${properties.keys}"
    )
    return when (backend) {
        KotlinEngineConfig.Backend.K1 -> KotlinInterface(propertyClasses, properties, implicitReceivers, classLoader, filePrefix)
        KotlinEngineConfig.Backend.K2 -> KotlinInterface2(propertyClasses, properties, implicitReceivers, classLoader, filePrefix)
    }
}
