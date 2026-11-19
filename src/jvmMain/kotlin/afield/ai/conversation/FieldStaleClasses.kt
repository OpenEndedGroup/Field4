package afield.ai.conversation

import java.io.File

/**
 * Whether a failure means Field's own classes were rewritten under the running JVM (a rebuild in the checkout
 * it runs from), and the one message every tool gives when it does: `stage="field-stale"`, restart Field.
 *
 * A class the JVM has not loaded yet is read from `build/classes` the first time it is needed; once a rebuild
 * has replaced or removed it, that load fails (`NoClassDefFoundError`, cached at the call site) for the rest of
 * the process's life. Nothing the agent writes can fix that. On 2026-11-16 an AI4 session spent five rounds
 * learning it: RunBox answered a bare `afield/runtime/kotlin/SnippetClassReceiverCrash` with "fix the code",
 * CompileKotlin gave 611 "Unresolved reference 'graphics'" diagnostics, and a `NoClassDefFoundError` from
 * CodeUseQueryRequest ended the turn.
 */
object FieldStaleClasses {
    const val STAGE = "field-stale"

    /** What the model reads; [detail] is the evidence (the missing class, the count of diagnostics). */
    fun restartMessage(detail: String): String =
        "Field's own classes changed on disk while it was running (a rebuild of the checkout it runs from)" +
            (if (detail.isNotBlank()) ": $detail" else "") + ". This is not the code's fault, and it will keep failing the same way " +
            "until Field is restarted. Stop retrying or editing code for this: tell the person to restart Field, then try again."

    /** The [LinkageError] in [failure]'s cause chain, or null. */
    fun linkageFailure(failure: Throwable?): LinkageError? {
        val seen = mutableSetOf<Throwable>()
        var cursor = failure
        while (cursor != null && seen.add(cursor)) {
            if (cursor is LinkageError && cursor !is ExceptionInInitializerError) return cursor
            cursor = cursor.cause
        }
        return null
    }

    /** [restartMessage] for a [failure] that is one, or null. */
    fun messageFor(failure: Throwable?): String? = linkageFailure(failure)?.let { e ->
        restartMessage("${e::class.java.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
    }

    private val LINKAGE_NAMES = Regex("\\b(NoClassDefFoundError|IncompatibleClassChangeError|NoSuchMethodError|NoSuchFieldError|AbstractMethodError|ClassFormatError)\\b")
    private val BARE_CLASS = Regex("^\\s*(?:[\\w$.]+:\\s*)?afield(?:/[\\w$]+)+\\s*$")

    /**
     * Whether a box's reported [error] text is a linkage failure: a JVM linkage error by name, or a bare
     * `afield/...` internal class name, which is all a `NoClassDefFoundError`'s message holds.
     */
    fun errorTextIsStale(error: String): Boolean {
        val head = error.trim().lineSequence().firstOrNull().orEmpty()
        return BARE_CLASS.matches(head) || (LINKAGE_NAMES.containsMatchIn(error) && "afield" in error)
    }

    private val UNRESOLVED = Regex("Unresolved reference:?\\s*'?([A-Za-z_][\\w]*)'?")

    /**
     * When a compile's [diagnostics] (their messages) are mostly unresolved references to Field's own packages
     * (`graphics`, `math`, `runtime`, ...), a single summary of them, otherwise null. A real mistake in box
     * code names one or two symbols; dozens of `afield.<package>` names unresolved at once means the compiler
     * classpath this Field built at start no longer matches `build/classes`.
     */
    fun summarizeMassUnresolved(diagnostics: List<String>, isFieldPackage: (String) -> Boolean = ::isFieldPackage): String? {
        val names = diagnostics.mapNotNull { UNRESOLVED.find(it)?.groupValues?.get(1) }
        val field = names.filter(isFieldPackage)
        if (field.size < MASS_UNRESOLVED || field.size * 2 < diagnostics.size) return null
        val top = field.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4)
            .joinToString { "'${it.key}' ×${it.value}" }
        return restartMessage("the compiler could not resolve Field's own packages (${field.size} of ${diagnostics.size} diagnostics: $top)")
    }

    const val MASS_UNRESOLVED = 10

    /** Whether `afield.<name>` is one of Field's packages, as this JVM's class loader sees it. */
    fun isFieldPackage(name: String): Boolean =
        name.isNotEmpty() && name.first().isLowerCase() &&
            FieldStaleClasses::class.java.classLoader?.getResource("afield/$name") != null

    /** When this JVM started, in epoch ms, or null. */
    fun jvmStartMs(): Long? = ProcessHandle.current().info().startInstant().orElse(null)?.toEpochMilli()

    /** Whether [dir] is a directory on [classPath]: this JVM loads classes from it. */
    fun runsFrom(dir: File, classPath: String = System.getProperty("java.class.path").orEmpty()): Boolean {
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        return classPath.split(File.pathSeparator).any { entry -> runCatching { File(entry).canonicalFile == canonical }.getOrDefault(false) }
    }

    /**
     * Up to [limit] `afield` class files on this JVM's class-path directories written after [startedMs]: the
     * evidence that a rebuild happened under it. Jars are skipped (an installed Field is not rebuilt in place).
     */
    fun classesRewrittenSince(
        startedMs: Long? = jvmStartMs(),
        classPath: String = System.getProperty("java.class.path").orEmpty(),
        limit: Int = 3,
    ): List<File> {
        if (startedMs == null) return emptyList()
        return classPath.split(File.pathSeparator).asSequence().map(::File)
            .filter { it.isDirectory }.map { File(it, "afield") }.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" && it.lastModified() > startedMs } }
            .take(limit).toList()
    }
}

/** The result a tool call gives in place of a [LinkageError]: an error the model reads, with `stage="field-stale"`. */
internal fun fieldStaleToolResult(tool: String, message: String): Map<String, Any> =
    mapOf("error" to true, "success" to false, "tool" to tool, "stage" to FieldStaleClasses.STAGE, "message" to message)
