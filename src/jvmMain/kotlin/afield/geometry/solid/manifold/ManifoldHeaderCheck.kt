package afield.geometry.solid.manifold

import java.io.File
import java.lang.foreign.AddressLayout
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.GroupLayout
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Checks every FFM descriptor [ManifoldNative] binds against the prototypes in the installed
 * `manifoldc.h`, and against the structs and enums in its `types.h`. A descriptor has to declare the
 * same number of arguments, and at every position, and for the return, the same kind of value:
 * pointer, `double`, `float`, 32- or 64-bit integer (`size_t` is 64), `void`, or a struct passed by
 * value of the same size.
 *
 * A descriptor that disagrees with the header still links and still returns a number, so this is
 * the only thing that notices one. `ManifoldSmokeTest` runs it first.
 *
 * Pointer *types* are not compared, since FFM has one `ADDRESS`. The two places where the pointee
 * matters are handled once in the wrapper rather than at each call: [Solid.slice] and
 * [Solid.project] convert the `ManifoldPolygons` that `manifold_slice` and `manifold_project`
 * return into a `Section` in one place, and [Solid.raycast] turns the C API's segment fraction
 * into a distance in the solid's units.
 */

/** Every bound FFM descriptor against the installed `manifoldc.h`; [check] returns what disagrees. */
object ManifoldHeaderCheck {
    /** One C prototype: its return type and argument types, as written. */
    data class Prototype(val name: String, val returns: String, val arguments: List<String>)

    /** Where `manifoldc.h` is looked for; `AFIELD_MANIFOLD_HEADER` names it directly. */
    fun headerCandidates(): List<File> = listOfNotNull(
        System.getenv("AFIELD_MANIFOLD_HEADER")?.let(::File),
        ManifoldLibrary.resolvedPath?.toFile()?.parentFile?.parentFile?.resolve("include/manifold/manifoldc.h"),
        File("/opt/homebrew/include/manifold/manifoldc.h"),
        File("/usr/local/include/manifold/manifoldc.h"),
        File("/usr/include/manifold/manifoldc.h"),
    )

    /** The first of [headerCandidates] that exists, or null when none of them does. */
    fun header(): File? = headerCandidates().firstOrNull { it.isFile }

    /** Every problem with the bound descriptors, or empty. Binds every handle [ManifoldNative] declares first. */
    fun check(header: File): List<String> {
        val types = header.resolveSibling("types.h").takeIf { it.isFile }?.readText().orEmpty()
        val prototypes = parsePrototypes(header.readText())
        val sizes = structSizes(types)
        val enums = enumNames(types)
        val functionPointers = functionPointerNames(types)
        bindAll()
        val problems = ArrayList<String>()
        for ((name, descriptor) in ManifoldNative.boundDescriptors.toSortedMap()) {
            val p = prototypes[name]
            if (p == null) { problems += "$name is bound but not declared in ${header.name}"; continue }
            val want = listOf(kindOf(p.returns, sizes, enums, functionPointers)) + p.arguments.map { kindOf(it, sizes, enums, functionPointers) }
            val have = listOf(descriptor.returnLayout().map(::kindOf).orElse("void")) + descriptor.argumentLayouts().map(::kindOf)
            if (want != have) {
                problems += "$name: ${header.name} says ${render(want)} — `${p.returns} $name(${p.arguments.joinToString(", ")})` — " +
                    "but ManifoldNative binds ${render(have)}"
            }
        }
        return problems
    }

    private fun render(kinds: List<String>) = "${kinds.first()}(${kinds.drop(1).joinToString(", ")})"

    /** Forces every `MethodHandle` property of [ManifoldNative], so each records its descriptor. */
    private fun bindAll() {
        ManifoldNative::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == MethodHandle::class.java && it.name.startsWith("get") }
            .forEach { m ->
                m.isAccessible = true
                // A symbol this library version lacks still recorded its descriptor before failing.
                runCatching { m.invoke(ManifoldNative) }
            }
    }

    private fun kindOf(layout: MemoryLayout): String = when (layout) {
        is AddressLayout -> "pointer"
        is GroupLayout -> "struct${layout.byteSize()}"
        ValueLayout.JAVA_DOUBLE.withoutName() -> "double"
        ValueLayout.JAVA_FLOAT.withoutName() -> "float"
        ValueLayout.JAVA_LONG.withoutName() -> "int64"
        ValueLayout.JAVA_INT.withoutName() -> "int32"
        ValueLayout.JAVA_SHORT.withoutName() -> "int16"
        ValueLayout.JAVA_BYTE.withoutName(), ValueLayout.JAVA_BOOLEAN.withoutName() -> "int8"
        else -> layout.withoutName().toString()
    }

    /** The kind of a C type as written in a prototype (argument names already removed or ignored). */
    internal fun kindOf(cType: String, structSizes: Map<String, Long>, enums: Set<String>, functionPointers: Set<String> = emptySet()): String {
        val t = cType.replace(Regex("""\bconst\b"""), "").trim()
        if ('*' in t || '(' in t || '[' in t) return "pointer"
        val base = t.split(Regex("""\s+""")).first()
        return when (base) {
            "void" -> "void"
            "double" -> "double"
            "float" -> "float"
            "size_t", "uint64_t", "int64_t", "long", "ptrdiff_t" -> "int64"
            "int", "uint32_t", "int32_t", "unsigned" -> "int32"
            "uint16_t", "int16_t", "short" -> "int16"
            "char", "bool", "uint8_t", "int8_t" -> "int8"
            in enums -> "int32"
            in functionPointers -> "pointer"
            in structSizes -> "struct${structSizes.getValue(base)}"
            else -> "unknown:$base"
        }
    }

    /** `manifold_*` prototypes, by name. Comments removed, function-pointer arguments kept whole. */
    internal fun parsePrototypes(text: String): Map<String, Prototype> {
        val src = text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), " ")
        val out = LinkedHashMap<String, Prototype>()
        val start = Regex("""([A-Za-z_][\w\s\*]*?)\b(manifold_\w+)\s*\(""")
        var at = 0
        while (true) {
            val m = start.find(src, at) ?: break
            var depth = 1
            var i = m.range.last + 1
            while (i < src.length && depth > 0) { if (src[i] == '(') depth++ else if (src[i] == ')') depth--; i++ }
            val inner = src.substring(m.range.last + 1, i - 1)
            at = i
            if (!src.substring(i).trimStart().startsWith(";")) continue
            val returns = m.groupValues[1].trim().split(Regex("""[;}{]""")).last().trim()
            out[m.groupValues[2]] = Prototype(m.groupValues[2], returns, splitArguments(inner))
        }
        return out
    }

    private fun splitArguments(inner: String): List<String> {
        if (inner.isBlank() || inner.trim() == "void") return emptyList()
        val parts = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in inner) {
            when {
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { parts += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString().trim()
        return parts
    }

    private fun enumNames(types: String): Set<String> =
        Regex("""typedef\s+enum\s+\w*\s*\{[^}]*\}\s*(\w+)\s*;""").findAll(types).map { it.groupValues[1] }.toSet()

    /** Function-pointer typedefs (`typedef double (*ManifoldSdf)(...)`), which pass as a pointer. */
    private fun functionPointerNames(types: String): Set<String> =
        Regex("""typedef[^;{}]*\(\s*\*\s*(\w+)\s*\)""").findAll(types).map { it.groupValues[1] }.toSet()

    /** The size of each by-value struct in `types.h`, laid out with C's natural alignment. */
    internal fun structSizes(types: String): Map<String, Long> {
        val sizes = LinkedHashMap<String, Long>()
        val aligns = HashMap<String, Long>()
        for (m in Regex("""typedef\s+struct\s+\w*\s*\{([^}]*)\}\s*(\w+)\s*;""").findAll(types)) {
            var offset = 0L
            var maxAlign = 1L
            for (field in m.groupValues[1].split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val type = field.substringBeforeLast(' ').trim()
                val (size, align) = when {
                    '*' in field -> 8L to 8L
                    type in sizes -> sizes.getValue(type) to aligns.getValue(type)
                    else -> when (kindOf(type, sizes, emptySet())) {
                        "double", "int64" -> 8L to 8L
                        "float", "int32" -> 4L to 4L
                        "int16" -> 2L to 2L
                        else -> 1L to 1L
                    }
                }
                offset = (offset + align - 1) / align * align + size
                maxAlign = maxOf(maxAlign, align)
            }
            sizes[m.groupValues[2]] = (offset + maxAlign - 1) / maxAlign * maxAlign
            aligns[m.groupValues[2]] = maxAlign
        }
        return sizes
    }
}
