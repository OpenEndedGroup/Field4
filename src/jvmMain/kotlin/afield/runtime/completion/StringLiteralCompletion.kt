package afield.runtime.completion

import afield.app.protocol.Completion
import afield.app.protocol.FileCompletionReply
import afield.app.protocol.Metadata
import afield.document.graph.Box
import afield.runtime.kotlin.findClassesNamed
import afield.ui.output.FileChooserSpec
import afield.ui.output.FileMask
import afield.ui.output.completeFilePath
import afield.ui.output.fileChooserPrefixes
import afield.ui.output.fileChooserRoot
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.kotlinFunction

/**
 * A `String` parameter whose argument is a path: inside its string literal, completion lists files the
 * way a [afield.ui.output.fileChooser] does, `%name/` FileManager directories included.
 *
 * ```kotlin
 * fun load(@CompletesPath("*.3dm") path: String): RhinoModel
 * ```
 *
 * @param mask the files offered; see [FileMask] (`"*.png"`, `"*.{wav,aif}"`, `"csv"`). Directories are always offered.
 * @param root where a relative path starts, as a FileManager spec; blank is the document's `files/`
 * @param chooseDirectory the argument names a directory, so files are not listed
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class CompletesPath(val mask: String = "", val root: String = "", val chooseDirectory: Boolean = false)

/**
 * A `String` parameter whose argument is one of a computed set of strings: inside its string literal,
 * completion offers what [provider] returns, asked afresh each time the popup opens.
 *
 * ```kotlin
 * object LayerPaths : StringCompletions {
 *     override fun candidates(context: StringCompletionContext) =
 *         (context.receiver as? RhinoModel)?.layers?.map { it.path }.orEmpty()
 * }
 * fun layer(@CompletesWith(LayerPaths::class) name: String): RhinoLayer
 * ```
 *
 * [provider] is an `object`, or a class with a no-argument constructor. For a member function it is
 * handed the object the call is on, when that is a box variable that has already run; see
 * [StringCompletionContext.receiver].
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class CompletesWith(val provider: KClass<out StringCompletions>)

/** The strings a [CompletesWith] parameter completes to. */
fun interface StringCompletions {
    /** Every string worth offering; the popup narrows them to what has been typed. */
    fun candidates(context: StringCompletionContext): List<String>
}

/**
 * Where a [StringCompletions] is being asked: [typed] is the literal's text up to the caret, [box] the box
 * being edited, and [callable] / [parameter] the call and parameter name the literal is an argument for.
 *
 * @property receiver the live object the call is on: in `model.layer("`, the value `model` holds now, if
 * `model` is a variable of the box that has already run. Completion evaluates nothing, so it is null
 * for an expression receiver (`load("x.3dm").layer("`), a variable not yet run, a local inside a
 * function or lambda, and a call with no receiver.
 */
class StringCompletionContext(
    val box: Box,
    val typed: String,
    val callable: String,
    val parameter: String,
    val receiver: Any? = null,
)

/**
 * Completion inside a Kotlin string literal, ahead of Kotlin completion (which has nothing to say there).
 *
 * When the literal is an argument of a call, the parameter it binds to is found by reflection and
 * its annotation decides: [CompletesPath] lists files under that parameter's mask and root,
 * [CompletesWith] offers the provider's strings. Otherwise a literal that already looks like a path
 * completes as one: absolute, `~/…`, `%name/…`, or relative with a `/` whose directory exists under the
 * document's `files/` — the same reading `"data/x.csv".file` gives it. Any other string (`"<div>"`,
 * `"https://…"`, `"%.2f"`) returns null and is left to Kotlin completion.
 *
 * The call is read from the text, not from a compiler: the callee is the identifier before the
 * enclosing `(`, its receiver a dotted name before that resolved as a live variable of the box, an
 * imported or indexed class, or a typed local; a callee with no receiver is looked up as a top-level
 * function through [KotlinSourceIndex] (which is how `Box.` extensions are found) or as a constructor.
 * A parameter of a function declared in box code is not seen — it is not on the classpath.
 */
object StringLiteralCompletion {
    const val PATHS = "paths"
    const val CHOICES = "choices"
    private const val LIMIT = 300

    /** The literal around the caret: [quote] is the opening quote, [open] where its text starts, [typed] that text up to the caret. */
    internal data class Literal(val quote: Int, val open: Int, val typed: String)

    /** The call a literal is an argument of. */
    internal data class CallSite(val receiver: String?, val callee: String, val calleeEnd: Int, val parameterName: String?, val index: Int)

    fun complete(box: Box?, code: String, position: Int): Completion? {
        val literal = literalAt(code, position) ?: return null
        val typed = literal.typed
        if ('$' in typed || '\\' in typed || '\n' in typed) return null
        val call = callSiteOf(code, literal.quote)
        val annotation = call?.let { runCatching { parameterAnnotation(box, code, it) }.getOrNull() }
        return when {
            annotation is CompletesWith && box != null -> choices(box, annotation, call!!, literal, position)
            annotation is CompletesPath -> paths(pathSpec(box, annotation), literal, position, strict = true)
            else -> if (looksLikePath(typed)) paths(pathSpec(box, null), literal, position, strict = false) else null
        }
    }

    private fun looksLikePath(typed: String) =
        typed.startsWith("/") || typed.startsWith("~") || typed.startsWith("%") || '/' in typed

    private fun pathSpec(box: Box?, annotation: CompletesPath?): FileChooserSpec {
        val home = Paths.get(System.getProperty("user.home"))
        if (box == null) return FileChooserSpec(home, FileMask(annotation?.mask ?: ""), annotation?.chooseDirectory ?: false, mustExist = false)
        return FileChooserSpec(
            root = runCatching { box.fileChooserRoot(annotation?.root ?: "") }.getOrDefault(home),
            mask = FileMask(annotation?.mask ?: ""),
            chooseDirectory = annotation?.chooseDirectory ?: false,
            mustExist = false,
            prefixes = box.fileChooserPrefixes(),
        )
    }

    private fun paths(spec: FileChooserSpec, literal: Literal, position: Int, strict: Boolean): Completion? {
        val reply: FileCompletionReply = completeFilePath(spec, "", literal.typed, limit = LIMIT)
        if (!strict && (reply.error != null || reply.entries.isEmpty())) return null
        val metadata = reply.entries.map { entry ->
            val shown = entry.name + if (entry.directory) "/" else ""
            Metadata(
                icon = if (entry.directory) "dir" else "file",
                tail = when {
                    entry.detail.isNotEmpty() -> entry.detail
                    entry.directory -> "directory"
                    else -> humanSize(entry.size)
                },
                text = escapeForKotlinString(entry.insert),
                displayText = shown,
                documentation = "`" + spec.describe(spec.resolve(entry.insert)) + "`",
                category = PATHS,
            )
        }
        return Completion(boundsStart = literal.open, boundsEnd = position, metadata = metadata)
    }

    private fun choices(box: Box, annotation: CompletesWith, call: CallSite, literal: Literal, position: Int): Completion {
        val provider = runCatching { annotation.provider.objectInstance ?: annotation.provider.createInstance() }.getOrNull()
        val candidates = provider?.let {
            runCatching {
                it.candidates(StringCompletionContext(box, literal.typed, call.callee, call.parameterName ?: "", liveReceiver(box, call)))
            }.getOrNull()
        }.orEmpty()
        val typed = literal.typed
        val lower = typed.lowercase()
        val ranked = candidates.distinct().mapNotNull { c ->
            val l = c.lowercase()
            val rank = when {
                typed.isEmpty() || c.startsWith(typed) -> 0
                l.startsWith(lower) -> 1
                l.contains(lower) -> 2
                else -> return@mapNotNull null
            }
            rank to c
        }.sortedBy { it.first }.take(LIMIT).map { it.second }
        val source = annotation.provider.simpleName ?: "choices"
        val metadata = ranked.map { c ->
            Metadata(icon = "str", tail = source, text = escapeForKotlinString(c), displayText = c, category = CHOICES)
        }
        return Completion(boundsStart = literal.open, boundsEnd = position, metadata = metadata)
    }

    /**
     * The single-line string literal the caret is in, or null. Scans only the caret's line: a `"..."`
     * literal cannot span lines, and a path in a `"""` literal is written on one line too.
     */
    internal fun literalAt(code: String, position: Int): Literal? {
        if (position < 0 || position > code.length) return null
        val lineStart = code.lastIndexOf('\n', position - 1) + 1
        var i = lineStart
        var quote = -1
        var open = -1
        var raw = false
        while (i < position) {
            val c = code[i]
            if (open < 0) {
                if (c == '/' && code.getOrNull(i + 1) == '/') return null
                if (c == '\'') {
                    i += if (code.getOrNull(i + 1) == '\\') 4 else 3
                    continue
                }
                if (c == '"') {
                    raw = code.startsWith("\"\"\"", i)
                    quote = i
                    i += if (raw) 3 else 1
                    open = i
                    continue
                }
            } else if (raw) {
                if (code.startsWith("\"\"\"", i)) {
                    open = -1
                    i += 3
                    continue
                }
            } else {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') open = -1
            }
            i++
        }
        if (open < 0 || open > position) return null
        return Literal(quote, open, code.substring(open, position))
    }

    internal fun stringPrefixAt(code: String, position: Int): String? = literalAt(code, position)?.typed

    /**
     * The call whose argument list holds [quote], read forwards from the start of [code] so that strings,
     * comments and lambdas before it are skipped properly: a stack of open brackets, with a comma count
     * for each `(`.
     */
    internal fun callSiteOf(code: String, quote: Int): CallSite? {
        class Frame(val bracket: Char, val at: Int) { var commas = 0; var argStart = at + 1 }
        val stack = ArrayDeque<Frame>()
        var i = 0
        while (i < quote) {
            val c = code[i]
            when {
                c == '/' && code.getOrNull(i + 1) == '/' -> { i = code.indexOf('\n', i).let { if (it < 0) quote else it }; continue }
                c == '/' && code.getOrNull(i + 1) == '*' -> { i = code.indexOf("*/", i + 2).let { if (it < 0) quote else it + 2 }; continue }
                code.startsWith("\"\"\"", i) -> { i = code.indexOf("\"\"\"", i + 3).let { if (it < 0) quote else it + 3 }; continue }
                c == '"' -> {
                    var j = i + 1
                    while (j < quote && code[j] != '"' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    i = j + 1
                    continue
                }
                c == '\'' -> { i += if (code.getOrNull(i + 1) == '\\') 4 else 3; continue }
                c == '(' || c == '[' || c == '{' -> stack.addLast(Frame(c, i))
                c == ')' || c == ']' || c == '}' -> stack.removeLastOrNull()
                c == ',' -> stack.lastOrNull()?.let { it.commas++; it.argStart = i + 1 }
            }
            i++
        }
        val frame = stack.lastOrNull()?.takeIf { it.bracket == '(' } ?: return null
        val before = code.substring(frame.argStart, quote)
        val named = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""").find(before)?.groupValues?.get(1)
        if (named == null && before.isNotBlank()) return null
        var end = frame.at
        while (end > 0 && code[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        if (start == end) return null
        val callee = code.substring(start, end)
        var receiver: String? = null
        var dot = start
        if (dot > 0 && code[dot - 1] == '.') {
            dot--
            if (dot > 0 && code[dot - 1] == '?') dot--
            var r = dot
            while (r > 0 && (code[r - 1].isLetterOrDigit() || code[r - 1] == '_' || code[r - 1] == '.')) r--
            receiver = code.substring(r, dot).trim('.').takeIf { it.isNotEmpty() } ?: return null
            if (r > 0 && (code[r - 1] == ')' || code[r - 1] == ']')) return null
        }
        return CallSite(receiver, callee, end, named, frame.commas)
    }

    /** The value a plain-variable receiver holds in [box] right now, or null; nothing is evaluated. */
    private fun liveReceiver(box: Box?, call: CallSite): Any? {
        val receiver = call.receiver ?: return null
        if (box == null || '.' in receiver) return null
        return box.ki.context.vars[receiver]?.value
    }

    private val functionCache = ConcurrentHashMap<String, List<KFunction<*>>>()

    /** The [CompletesPath] or [CompletesWith] on the parameter [call]'s literal binds to, in any overload; null for none. */
    internal fun parameterAnnotation(box: Box?, code: String, call: CallSite): Annotation? =
        annotationIn(candidateFunctions(box, code, call), call)

    internal fun annotationIn(functions: Sequence<KFunction<*>>, call: CallSite): Annotation? {
        for (function in functions) {
            val values = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
            val parameter = (if (call.parameterName != null) values.firstOrNull { it.name == call.parameterName } else values.getOrNull(call.index))
                ?: continue
            if (parameter.type.classifier != String::class) continue
            parameter.findAnnotation<CompletesWith>()?.let { return it }
            parameter.findAnnotation<CompletesPath>()?.let { return it }
        }
        return null
    }

    /** Members of the receiver's class (or constructors) first; top-level functions, which cost an index read, only if those say nothing. */
    private fun candidateFunctions(box: Box?, code: String, call: CallSite): Sequence<KFunction<*>> {
        val receiver = call.receiver
        if (receiver != null) {
            val live = liveReceiver(box, call)
            val receiverClass: KClass<*>? = live?.let { it::class }
                ?: resolveClass(code, receiver)
                ?: KotlinCompletionSupport.receiverQualifiedNameAtCompletion(box, code, call.calleeEnd)?.let(::classForName)
            val members = receiverClass?.let { k ->
                functionCache.getOrPut("member:${k.qualifiedName}#${call.callee}") {
                    runCatching {
                        (k.memberFunctions + (k.companionObject?.memberFunctions ?: emptyList())).filter { it.name == call.callee }
                    }.getOrDefault(emptyList())
                }
            }.orEmpty()
            return members.asSequence() + sequence<KFunction<*>> { yieldAll(topLevelFunctions(call.callee)) }
        }
        val constructors: List<KFunction<*>> = if (call.callee.first().isUpperCase()) {
            resolveClass(code, call.callee)?.let { runCatching { it.constructors.toList() }.getOrNull() }.orEmpty()
        } else emptyList()
        return constructors.asSequence() + sequence<KFunction<*>> { yieldAll(topLevelFunctions(call.callee)) }
    }

    /** Top-level (and extension) functions named [name], through the source index and each file's facade class. */
    private fun topLevelFunctions(name: String): List<KFunction<*>> {
        functionCache["top:$name"]?.let { return it }
        // No index yet (a cold start before the background sync): answer nothing, and do not cache that.
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return emptyList()
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        return index.definitions.asSequence()
            .filter { it.kind == "fun" && it.name.substringAfterLast('.') == name }
            .map { it.filePath }
            .distinct()
            .take(40)
            .mapNotNull { facadeClass(root.resolve(it)) }
            .flatMap { facade ->
                runCatching {
                    facade.declaredMethods.filter { it.name == name && Modifier.isStatic(it.modifiers) }.mapNotNull { it.kotlinFunction }
                }.getOrDefault(emptyList())
            }
            .toList()
            .also { functionCache["top:$name"] = it }
    }

    private fun facadeClass(file: java.nio.file.Path): Class<*>? {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)
        val jvmName = Regex("""@file:JvmName\(\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        val simple = jvmName ?: (file.fileName.toString().removeSuffix(".kt").replaceFirstChar { it.uppercaseChar() } + "Kt")
        return runCatching { Class.forName(if (pkg != null) "$pkg.$simple" else simple, false, StringLiteralCompletion::class.java.classLoader) }.getOrNull()
    }

    /** [name] (simple or dotted) as a class: fully qualified, imported by [code], or the only class of that name. */
    private fun resolveClass(code: String, name: String): KClass<*>? {
        if ('.' in name) {
            classForName(name)?.let { return it }
            val head = name.substringBefore('.')
            val imported = importedName(code, head) ?: findClassesNamed(head)?.singleOrNull() ?: return null
            return classForName(imported + "." + name.substringAfter('.'))
        }
        val qualified = importedName(code, name) ?: findClassesNamed(name)?.singleOrNull() ?: return null
        return classForName(qualified)
    }

    private fun importedName(code: String, simple: String): String? =
        Regex("""(?m)^\s*import\s+([\w.]+)\.${Regex.escape(simple)}\s*$""").find(code)?.groupValues?.get(1)?.let { "$it.$simple" }

    /** A class by its Kotlin name, trying `$` for each trailing dot so nested classes and objects resolve. */
    private fun classForName(name: String): KClass<*>? {
        var candidate = name
        repeat(4) {
            runCatching { return Class.forName(candidate, false, StringLiteralCompletion::class.java.classLoader).kotlin }
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1)
        }
        return null
    }

    private fun escapeForKotlinString(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
