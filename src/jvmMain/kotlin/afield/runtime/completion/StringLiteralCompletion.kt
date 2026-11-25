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
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
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
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
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
 *
 * Index access completes too. In `foo.bar["pea`, an annotation on the property `bar` decides; failing
 * that, one on the key parameter of `bar`'s `operator fun get` or `set`:
 *
 * ```kotlin
 * class Shelf {
 *     @CompletesWith(Fruit::class) val stock = mutableMapOf<String, Int>()
 * }
 * class PaintToolbox {
 *     operator fun get(@CompletesWith(PaintToolNames::class) name: String): PaintTool?
 * }
 * ```
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
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
 * For index access, [callable] is `"[]"`.
 *
 * @property receiver the live object the call or index is on: in `model.layer("`, the value `model`
 * holds now, if `model` is a variable of the box that has already run; in `foo.bar["`, the value of
 * `foo`'s `bar`, read from its backing field. Completion runs no code, so it is null for an
 * expression receiver (`load("x.3dm").layer("`), a variable not yet run, a local inside a function or
 * lambda, a property with a custom getter or a delegate, and a call with no receiver.
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
 * enclosing `(`, its receiver a dotted name before that resolved as a live variable of the box (then
 * property by property through backing fields), an imported or indexed class, or a typed local; a
 * callee with no receiver is looked up as a top-level function through [KotlinSourceIndex] (which is
 * how `Box.` extensions are found) or as a constructor. Inside `[`, the dotted name before it is the
 * receiver and its last property's annotation, or its type's `get` / `set` operators, decide. A
 * top-level function declared in box code is not seen (it is not on the classpath), but a member of a
 * box-declared class is, once a box variable holds one.
 */
object StringLiteralCompletion {
    const val PATHS = "paths"
    const val CHOICES = "choices"

    /** The [CallSite.callee] of index access, `foo.bar["…"]`. */
    const val INDEX = "[]"
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
        val receiver = call?.let { runCatching { receiverOf(box, code, it) }.getOrNull() }
        val annotation = call?.let { runCatching { parameterAnnotation(box, code, it, receiver) }.getOrNull() }
        return when {
            annotation is CompletesWith && box != null -> choices(box, annotation, call, receiver?.value, literal, position)
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

    private fun choices(box: Box, annotation: CompletesWith, call: CallSite, receiver: Any?, literal: Literal, position: Int): Completion {
        val provider = runCatching { annotation.provider.objectInstance ?: annotation.provider.createInstance() }.getOrNull()
        val candidates = provider?.let {
            runCatching {
                it.candidates(StringCompletionContext(box, literal.typed, call.callee, call.parameterName ?: "", receiver))
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
        val frame = stack.lastOrNull()?.takeIf { it.bracket == '(' || it.bracket == '[' } ?: return null
        val before = code.substring(frame.argStart, quote)
        if (frame.bracket == '[') {
            if (before.isNotBlank()) return null
            val chain = receiverChainBefore(code, frame.at) ?: return null
            return CallSite(chain, INDEX, frame.at, null, frame.commas)
        }
        val named = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""").find(before)?.groupValues?.get(1)
        if (named == null && before.isNotBlank()) return null
        var end = frame.at
        while (end > 0 && code[end - 1].isWhitespace()) end--
        // Explicit type arguments, `fromContextWithNameOrNull<Renderer>("…")`: the callee is before them.
        if (end > 0 && code[end - 1] == '>') {
            var depth = 0
            var t = end
            while (t > 0) {
                val ch = code[t - 1]
                if (ch == '>') depth++
                else if (ch == '<') depth--
                else if (!(ch.isLetterOrDigit() || ch in "_.,?* \t")) return null
                t--
                if (depth == 0) break
            }
            if (depth != 0) return null
            end = t
            while (end > 0 && code[end - 1].isWhitespace()) end--
        }
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

    /**
     * The dotted name ending right at [end] (`foo.bar` in `foo.bar[`), with `!!` and `?` dropped, or
     * null when there is none or it ends a call or index (`f()[`, `a["x"][`), which would have to run.
     */
    internal fun receiverChainBefore(code: String, end: Int): String? {
        var start = end
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] in "_.?!")) start--
        if (start > 0 && (code[start - 1] == ')' || code[start - 1] == ']')) return null
        val chain = code.substring(start, end).replace("!!", "").replace("?", "").trim('.')
        val segments = chain.split('.')
        if (chain.isEmpty() || segments.any { it.isEmpty() || !(it[0].isLetter() || it[0] == '_') }) return null
        return chain
    }

    /**
     * What a receiver resolves to: its class ([type]), its value if that could be had without running
     * code, and, when it was reached through a property, that property and the class that declares it.
     */
    internal class Receiver(val type: KClass<*>?, val value: Any?, val property: KProperty1<*, *>? = null, val owner: KClass<*>? = null)

    /**
     * [call]'s receiver: a live variable of [box] followed property by property, else the longest
     * leading part that names a class followed by property types, else (for a call) the compiler's
     * type at the callee. A call with no receiver has the box being edited as its implicit one, the
     * way `display("…")` in box code is the box's own `display`. Null for none of those.
     */
    internal fun receiverOf(box: Box?, code: String, call: CallSite): Receiver? {
        val text = call.receiver ?: return box?.takeIf { call.callee != INDEX }?.let { Receiver(it::class, it) }
        val segments = text.split('.')
        val live = box?.let { runCatching { it.ki.context.vars[segments[0]] }.getOrNull() }
        if (live != null) {
            val value = live.value
            val type = value?.let { it::class } ?: (runCatching { live.descriptor.returnType.classifier }.getOrNull() as? KClass<*>)
            return follow(Receiver(type, value), segments.drop(1))
        }
        for (k in segments.size downTo 1) {
            val type = resolveClass(code, segments.take(k).joinToString(".")) ?: continue
            return follow(Receiver(type, null), segments.drop(k))
        }
        if (call.callee == INDEX) return null
        return KotlinCompletionSupport.receiverQualifiedNameAtCompletion(box, code, call.calleeEnd)?.let(::classForName)?.let { Receiver(it, null) }
    }

    /** [start] followed through the properties [names], each value read from its backing field so that no getter runs. */
    private fun follow(start: Receiver, names: List<String>): Receiver? {
        var at = start
        for (name in names) {
            val owner = at.type ?: return null
            val property = runCatching { owner.memberProperties.firstOrNull { it.name == name } }.getOrNull() ?: return null
            val type = property.returnType.classifier as? KClass<*>
            val value = at.value?.let { backingFieldValue(property, it, type) }
            at = Receiver(value?.let { it::class } ?: type, value, property, owner)
        }
        return at
    }

    /** [property]'s backing field on [target], or null for a computed property, a delegate, or a field that will not open. */
    private fun backingFieldValue(property: KProperty1<*, *>, target: Any, type: KClass<*>?): Any? {
        val field = runCatching { property.javaField }.getOrNull() ?: return null
        if (Modifier.isStatic(field.modifiers)) return null
        val value = runCatching { if (field.trySetAccessible()) field.get(target) else null }.getOrNull() ?: return null
        return value.takeIf { type == null || type.isInstance(it) }
    }

    private val functionCache = ConcurrentHashMap<String, List<KFunction<*>>>()

    /**
     * The [CompletesPath] or [CompletesWith] on the parameter [call]'s literal binds to, in any overload;
     * for index access the indexed property's own annotation comes first. Null for none.
     */
    internal fun parameterAnnotation(box: Box?, code: String, call: CallSite, receiver: Receiver? = receiverOf(box, code, call)): Annotation? {
        if (call.callee == INDEX) propertyAnnotation(receiver)?.let { return it }
        return annotationIn(candidateFunctions(box, code, call, receiver), call)
    }

    /** An annotation on the property [receiver] was read through, or on the constructor parameter that declares it. */
    private fun propertyAnnotation(receiver: Receiver?): Annotation? {
        val property = receiver?.property ?: return null
        property.findAnnotation<CompletesWith>()?.let { return it }
        property.findAnnotation<CompletesPath>()?.let { return it }
        val parameter = runCatching { receiver.owner?.primaryConstructor?.parameters?.firstOrNull { it.name == property.name } }.getOrNull()
            ?: return null
        return parameter.findAnnotation<CompletesWith>() ?: parameter.findAnnotation<CompletesPath>()
    }

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

    /**
     * Members of the receiver's class (or constructors) first; top-level functions, which cost an index
     * read, only if those say nothing. Index access looks only at the receiver's `get` / `set` operators.
     */
    private fun candidateFunctions(box: Box?, code: String, call: CallSite, receiver: Receiver?): Sequence<KFunction<*>> {
        if (call.callee == INDEX) {
            val k = receiver?.type ?: return emptySequence()
            return functionCache.getOrPut("index:${k.java.name}") {
                runCatching { k.memberFunctions.filter { it.isOperator && (it.name == "get" || it.name == "set") } }.getOrDefault(emptyList())
            }.asSequence()
        }
        if (call.receiver != null) {
            val receiverClass: KClass<*>? = receiver?.type
            val members = receiverClass?.let { membersNamed(it, call.callee) }.orEmpty()
            return members.asSequence() + sequence<KFunction<*>> { yieldAll(topLevelFunctions(call.callee)) }
        }
        val constructors: List<KFunction<*>> = if (call.callee.first().isUpperCase()) {
            resolveClass(code, call.callee)?.let { runCatching { it.constructors.toList() }.getOrNull() }.orEmpty()
        } else emptyList()
        // No written receiver: the implicit box's members (`display`), then constructors and top-level functions.
        val implicit = receiver?.type?.let { membersNamed(it, call.callee) }.orEmpty()
        return implicit.asSequence() + constructors.asSequence() + sequence<KFunction<*>> { yieldAll(topLevelFunctions(call.callee)) }
    }

    /**
     * Member functions of [k] named [name], each as declared in [k] or a supertype: an annotation
     * written on an interface method (`HasOutput.display`) is not repeated on the class that
     * implements or delegates it, so the declaration that carries it is the one to read.
     */
    private fun membersNamed(k: KClass<*>, name: String): List<KFunction<*>> =
        functionCache.getOrPut("member:${k.java.name}#$name") {
            runCatching {
                val declared = (listOf(k) + k.allSuperclasses).flatMap { c ->
                    runCatching { c.declaredMemberFunctions.filter { it.name == name } }.getOrDefault(emptyList())
                }
                declared + (k.companionObject?.memberFunctions?.filter { it.name == name } ?: emptyList())
            }.getOrDefault(emptyList())
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
            val imported = importedName(code, head) ?: uniqueClassNamed(head) ?: return null
            return classForName(imported + "." + name.substringAfter('.'))
        }
        val qualified = importedName(code, name) ?: uniqueClassNamed(name) ?: return null
        return classForName(qualified)
    }

    /**
     * The one class called [simple]; between homonyms, the one in Field. `FileManager` is also two shaded
     * IntelliJ classes inside the bundled compiler, which box code never means.
     */
    private fun uniqueClassNamed(simple: String): String? {
        val candidates = findClassesNamed(simple) ?: return null
        return candidates.singleOrNull() ?: candidates.filter { it.startsWith("afield.") }.singleOrNull()
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
