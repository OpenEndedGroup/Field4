package afield.ml

import afield.Box
import afield.HasOutput
import afield.ai.KotlinSourceIndex
import afield.graphics.FLine
import afield.ml.core.FieldMLValue
import afield.ml.core.applyFieldMLFunction
import afield.ml.core.fieldMLValueToHost
import afield.ml.core.hostToFieldMLValue
import afield.ml.core.renderFieldMLValue
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

object FieldMLRuntimePrelude {
    fun values(output: HasOutput? = null, box: Box? = null): Map<String, FieldMLValue> =
        linkedMapOf(
            "Unit" to FieldMLValue.UnitValue,
            "thisBox" to FieldMLValue.HostValue(box),
            "print" to native1("print") { value ->
                output?.print(renderFieldMLValue(value))
                value
            },
            "println" to native1("println") { value ->
                output?.println(renderFieldMLValue(value))
                value
            },
            "display" to native2("display") { channel, value ->
                output?.display(channel.asString("display channel"), outputPayload(value))
                value
            },
            "show" to native1("show") { value -> FieldMLValue.StringValue(renderFieldMLValue(value)) },
            "tap" to native2("tap") { effect, value ->
                applyFieldMLFunction(effect, value)
                value
            },

            "vec2" to native2("vec2") { x, y -> FieldMLValue.HostValue(Vector2f(x.asFloat("x"), y.asFloat("y"))) },
            "vec3" to native3("vec3") { x, y, z ->
                FieldMLValue.HostValue(Vector3f(x.asFloat("x"), y.asFloat("y"), z.asFloat("z")))
            },
            "vec4" to native4("vec4") { x, y, z, w ->
                FieldMLValue.HostValue(Vector4f(x.asFloat("x"), y.asFloat("y"), z.asFloat("z"), w.asFloat("w")))
            },

            "fline" to FieldMLValue.FactoryValue("fline") { FieldMLValue.HostValue(FLine()) },
            "moveTo" to native3("moveTo") { x, y, line ->
                FieldMLValue.HostValue(line.asFLine("moveTo").moveTo(x.asDouble("x"), y.asDouble("y")))
            },
            "moveTo3" to native4("moveTo3") { x, y, z, line ->
                FieldMLValue.HostValue(line.asFLine("moveTo3").moveTo(x.asDouble("x"), y.asDouble("y"), z.asDouble("z")))
            },
            "lineTo" to native3("lineTo") { x, y, line ->
                FieldMLValue.HostValue(line.asFLine("lineTo").lineTo(x.asDouble("x"), y.asDouble("y")))
            },
            "lineTo3" to native4("lineTo3") { x, y, z, line ->
                FieldMLValue.HostValue(line.asFLine("lineTo3").lineTo(x.asDouble("x"), y.asDouble("y"), z.asDouble("z")))
            },
            "moveToV" to native2("moveToV") { point, line ->
                FieldMLValue.HostValue(line.asFLine("moveToV").moveTo(point.asVec2("point")))
            },
            "lineToV" to native2("lineToV") { point, line ->
                FieldMLValue.HostValue(line.asFLine("lineToV").lineTo(point.asVec2("point")))
            },
            "cubicTo" to native7("cubicTo") { x0, y0, x1, y1, x, y, line ->
                FieldMLValue.HostValue(
                    line.asFLine("cubicTo").cubicTo(
                        x0.asDouble("x0"),
                        y0.asDouble("y0"),
                        x1.asDouble("x1"),
                        y1.asDouble("y1"),
                        x.asDouble("x"),
                        y.asDouble("y"),
                    ),
                )
            },
            "close" to native1("close") { line -> FieldMLValue.HostValue(line.asFLine("close").close()) },
            "thickness" to native2("thickness") { amount, line ->
                FieldMLValue.HostValue(line.asFLine("thickness").also { it.thickness = amount.asDouble("amount") })
            },
            "color" to native2("color") { color, line ->
                FieldMLValue.HostValue(line.asFLine("color").also { it.color = Vector4f(color.asVec4("color")) })
            },
            "filled" to native2("filled") { enabled, line ->
                FieldMLValue.HostValue(line.asFLine("filled").also { it.filled = enabled.asBool("enabled") })
            },
            "stroked" to native2("stroked") { enabled, line ->
                FieldMLValue.HostValue(line.asFLine("stroked").also { it.stroked = enabled.asBool("enabled") })
            },
            "pointed" to native2("pointed") { enabled, line ->
                FieldMLValue.HostValue(line.asFLine("pointed").also { it.pointed = enabled.asBool("enabled") })
            },
            "pointSize" to native2("pointSize") { amount, line ->
                FieldMLValue.HostValue(line.asFLine("pointSize").also { it.pointSize = amount.asDouble("amount") })
            },

            "hostInput" to FieldMLValue.HostValue(HostInputPlaceholder),
            "hostClass" to native1("hostClass") { name -> FieldMLValue.HostValue(Class.forName(name.asString("class name"))) },
            "hostNew0" to native1("hostNew0") { klass ->
                val javaClass = klass.asHost("hostNew0") as? Class<*> ?: error("hostNew0 expects a Class host value")
                hostToFieldMLValue(javaClass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance())
            },
            "hostCall0" to native2("hostCall0") { name, target ->
                callHostMethod(name.asString("method name"), target.asHost("target"), emptyList())
            },
            "hostCall1" to native3("hostCall1") { name, argument, target ->
                callHostMethod(name.asString("method name"), target.asHost("target"), listOf(fieldMLValueToHost(argument)))
            },
            "hostCall2" to native4("hostCall2") { name, a, b, target ->
                callHostMethod(name.asString("method name"), target.asHost("target"), listOf(fieldMLValueToHost(a), fieldMLValueToHost(b)))
            },
            "hostStaticCall" to native3("hostStaticCall") { name, arguments, klass ->
                val javaClass = klass.asHost("hostStaticCall class") as? Class<*> ?: error("hostStaticCall expects a Class host value")
                val args = arguments.asHostArguments("hostStaticCall arguments")
                callHostStaticMethod(name.asString("method name"), javaClass, args)
            },
            "hostStaticTap" to native4("hostStaticTap") { name, arguments, klass, input ->
                val javaClass = klass.asHost("hostStaticTap class") as? Class<*> ?: error("hostStaticTap expects a Class host value")
                val args = arguments.substituteHostInput(input).asHostArguments("hostStaticTap arguments")
                callHostStaticMethod(name.asString("method name"), javaClass, args)
                input
            },
            "hostTap" to native3("hostTap") { target, arguments, input ->
                callIndexedHostTap(target.asString("hostTap target"), arguments.asRecord("hostTap arguments"), input, box)
                input
            },
            "hostGet" to native2("hostGet") { name, target ->
                getHostMember(name.asString("member name"), target.asHost("target"))
            },
            "hostSet" to native3("hostSet") { name, value, target ->
                val host = target.asHost("target")
                setHostMember(name.asString("member name"), host, fieldMLValueToHost(value))
                FieldMLValue.HostValue(host)
            },
        )

    private fun outputPayload(value: FieldMLValue): Any? =
        when (value) {
            is FieldMLValue.HostValue -> value.value
            is FieldMLValue.StringValue -> value.value
            else -> renderFieldMLValue(value)
        }

    private fun native1(name: String, block: (FieldMLValue) -> FieldMLValue): FieldMLValue.NativeFunctionValue =
        FieldMLValue.NativeFunctionValue(name, block)

    private fun native2(name: String, block: (FieldMLValue, FieldMLValue) -> FieldMLValue): FieldMLValue.NativeFunctionValue =
        native1(name) { a -> native1("$name/1") { b -> block(a, b) } }

    private fun native3(
        name: String,
        block: (FieldMLValue, FieldMLValue, FieldMLValue) -> FieldMLValue,
    ): FieldMLValue.NativeFunctionValue =
        native1(name) { a -> native2("$name/1") { b, c -> block(a, b, c) } }

    private fun native4(
        name: String,
        block: (FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue) -> FieldMLValue,
    ): FieldMLValue.NativeFunctionValue =
        native1(name) { a -> native3("$name/1") { b, c, d -> block(a, b, c, d) } }

    private fun native7(
        name: String,
        block: (FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue) -> FieldMLValue,
    ): FieldMLValue.NativeFunctionValue =
        native1(name) { a ->
            native1("$name/1") { b ->
                native1("$name/2") { c ->
                    native1("$name/3") { d ->
                        native1("$name/4") { e ->
                            native1("$name/5") { f ->
                                native1("$name/6") { g -> block(a, b, c, d, e, f, g) }
                            }
                        }
                    }
                }
            }
        }

    private fun FieldMLValue.asDouble(name: String): Double =
        when (this) {
            is FieldMLValue.IntValue -> value.toDouble()
            is FieldMLValue.FloatValue -> value
            else -> error("Expected numeric $name, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.asFloat(name: String): Float = asDouble(name).toFloat()

    private fun FieldMLValue.asBool(name: String): Boolean =
        (this as? FieldMLValue.BoolValue)?.value ?: error("Expected Bool $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asString(name: String): String =
        (this as? FieldMLValue.StringValue)?.value ?: error("Expected String $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asHost(name: String): Any =
        (this as? FieldMLValue.HostValue)?.value ?: error("Expected host $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asRecord(name: String): Map<String, FieldMLValue> =
        (this as? FieldMLValue.RecordValue)?.fields ?: error("Expected record $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asHostArguments(name: String): List<Any?> =
        when (this) {
            is FieldMLValue.ListValue -> elements.map(::fieldMLValueToHost)
            is FieldMLValue.TupleValue -> elements.map(::fieldMLValueToHost)
            else -> error("Expected tuple or list $name, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.substituteHostInput(input: FieldMLValue): FieldMLValue =
        when (this) {
            is FieldMLValue.HostValue -> if (value === HostInputPlaceholder) input else this
            is FieldMLValue.TupleValue -> copy(elements = elements.map { it.substituteHostInput(input) })
            is FieldMLValue.ListValue -> copy(elements = elements.map { it.substituteHostInput(input) })
            is FieldMLValue.RecordValue -> copy(fields = fields.mapValues { (_, value) -> value.substituteHostInput(input) })
            else -> this
        }

    private fun FieldMLValue.asFLine(name: String): FLine =
        asHost(name) as? FLine ?: error("Expected FLine for $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asVec2(name: String): Vector2f =
        asHost(name) as? Vector2f ?: error("Expected Vec2 for $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asVec4(name: String): Vector4f =
        asHost(name) as? Vector4f ?: error("Expected Vec4 for $name, found ${renderFieldMLValue(this)}")

    private fun callHostMethod(name: String, target: Any, args: List<Any?>): FieldMLValue {
        val method = target.javaClass.methods
            .asSequence()
            .filter { it.name == name && it.parameterCount == args.size }
            .firstOrNull { method -> args.zip(method.parameterTypes.toList()).all { (arg, type) -> isCompatible(type, arg) } }
            ?: error("No compatible method '$name/${args.size}' on ${target.javaClass.name}")
        val coerced = args.zip(method.parameterTypes.toList()).map { (arg, type) -> coerceHostArgument(type, arg) }.toTypedArray()
        return hostToFieldMLValue(method.invoke(target, *coerced))
    }

    private fun callHostStaticMethod(name: String, target: Class<*>, args: List<Any?>): FieldMLValue {
        val method = target.methods
            .asSequence()
            .filter { it.name == name && Modifier.isStatic(it.modifiers) && it.parameterCount == args.size }
            .firstOrNull { method -> args.zip(method.parameterTypes.toList()).all { (arg, type) -> isCompatible(type, arg) } }
            ?: error("No compatible static method '$name/${args.size}' on ${target.name}")
        val coerced = args.zip(method.parameterTypes.toList()).map { (arg, type) -> coerceHostArgument(type, arg) }.toTypedArray()
        return hostToFieldMLValue(method.invoke(null, *coerced))
    }

    private fun callIndexedHostTap(
        target: String,
        namedArguments: Map<String, FieldMLValue>,
        input: FieldMLValue,
        box: Box?,
    ) {
        val candidates = indexedHostFunctions(target)
        if (candidates.isEmpty()) error("No indexed Kotlin function found for '$target'")
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            val built = candidate.buildArguments(namedArguments, input, box)
            if (built != null) {
                val javaClass = Class.forName(candidate.ownerClassName)
                val result = runCatching { callHostStaticMethod(candidate.name, javaClass, built) }
                if (result.isSuccess) return
                errors += "${candidate.ownerClassName}.${candidate.name}: ${result.exceptionOrNull()?.message}"
            }
        }
        error(
            "No compatible indexed hostTap target '$target'. Tried: " +
                (errors.ifEmpty { candidates.map { "${it.ownerClassName}.${it.name}" } }).joinToString("; "),
        )
    }

    private fun IndexedHostFunction.buildArguments(
        namedArguments: Map<String, FieldMLValue>,
        input: FieldMLValue,
        box: Box?,
    ): List<Any?>? {
        val remaining = namedArguments.keys.toMutableSet()
        val out = mutableListOf<Any?>()
        if (receiverType == "Box" || receiverType == "afield.Box") {
            out += box ?: error("hostTap target '$qualifiedName' needs a current Field box")
        } else if (receiverType != null) {
            return null
        }
        var usedInput = false
        for (parameter in parameters) {
            val value = namedArguments[parameter.name]
            if (value != null) {
                remaining -= parameter.name
                out += fieldMLValueToHost(value)
            } else if (!parameter.hasDefault) {
                if (usedInput) return null
                out += if (parameter.isVararg || parameter.type.startsWith("List<")) {
                    listOf(fieldMLValueToHost(input))
                } else {
                    fieldMLValueToHost(input)
                }
                usedInput = true
            } else {
                out += parameter.defaultValue ?: return null
            }
        }
        if (remaining.isNotEmpty()) return null
        return out.takeIf { usedInput }
    }

    private fun indexedHostFunctions(target: String): List<IndexedHostFunction> {
        val simpleName = target.substringAfterLast('.')
        val requestedPackage = target.substringBeforeLast('.', missingDelimiterValue = "")
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val indexedFiles = (KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex())
            ?.definitions
            ?.filter { it.kind == "fun" && it.name.substringAfterLast('.') == simpleName }
            ?.map { root.resolve(it.filePath).normalize() }
            ?.distinct()
            .orEmpty()

        val sourceFiles = if (indexedFiles.isNotEmpty()) {
            indexedFiles
        } else {
            listOf(root.resolve("src/jvmMain/kotlin"), root.resolve("src/personal/kotlin"))
                .filter { Files.exists(it) }
                .flatMap { sourceRoot ->
                    Files.walk(sourceRoot).use { stream ->
                        stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.toList()
                    }
                }
        }

        return sourceFiles.flatMap { file ->
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return@flatMap emptyList()
            val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            if (requestedPackage.isNotEmpty() && pkg != requestedPackage) return@flatMap emptyList()
            parseIndexedHostFunctions(text, file, pkg).filter { it.name == simpleName }
        }
    }

    private fun parseIndexedHostFunctions(text: String, file: Path, pkg: String): List<IndexedHostFunction> {
        val out = mutableListOf<IndexedHostFunction>()
        val regex = Regex("""(?m)(?:^|\n)\s*(?:public\s+|internal\s+)?fun\s+([A-Za-z_][A-Za-z0-9_.<>?]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        for (match in regex.findAll(text)) {
            val receiver = match.groupValues[1].substringBefore("<").removeSuffix("?")
            val name = match.groupValues[2]
            val openParen = match.range.last
            val closeParen = findMatchingParen(text, openParen) ?: continue
            val parameters = parseIndexedParameters(text.substring(openParen + 1, closeParen))
            val owner = topLevelOwnerClassName(pkg, file)
            out += IndexedHostFunction(
                qualifiedName = listOf(pkg, name).filter { it.isNotBlank() }.joinToString("."),
                ownerClassName = owner,
                name = name,
                receiverType = receiver,
                parameters = parameters,
            )
        }
        return out
    }

    private fun parseIndexedParameters(text: String): List<IndexedHostParameter> =
        splitTopLevel(text, ',').mapNotNull { raw ->
            val segment = raw.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val withoutAnnotations = segment.replace(Regex("""^@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?\s*"""), "")
            val isVararg = withoutAnnotations.startsWith("vararg ")
            val cleaned = withoutAnnotations.removePrefix("vararg ").trim()
            val colon = cleaned.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = cleaned.substring(0, colon).trim()
            val afterColon = cleaned.substring(colon + 1).trim()
            val equals = topLevelIndexOf(afterColon, '=')
            val type = (if (equals >= 0) afterColon.substring(0, equals) else afterColon).trim()
            val defaultSource = if (equals >= 0) afterColon.substring(equals + 1).trim() else null
            IndexedHostParameter(
                name = name,
                type = type,
                isVararg = isVararg,
                hasDefault = defaultSource != null,
                defaultValue = defaultSource?.let(::parseHostDefaultLiteral),
            )
        }

    private fun parseHostDefaultLiteral(source: String): Any? {
        val s = source.trim().removeSuffix(",")
        return when {
            s == "true" -> true
            s == "false" -> false
            s.startsWith("\"") && s.endsWith("\"") -> s.removeSurrounding("\"")
            s.toIntOrNull() != null -> s.toInt()
            s.toDoubleOrNull() != null -> s.toDouble()
            else -> null
        }
    }

    private fun findMatchingParen(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val ch = text[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var paren = 0
        var angle = 0
        var bracket = 0
        var inString = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"') inString = !inString
            if (!inString) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    '[' -> bracket++
                    ']' -> if (bracket > 0) bracket--
                    delimiter -> if (paren == 0 && angle == 0 && bracket == 0) {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
            }
        }
        parts += text.substring(start)
        return parts
    }

    private fun topLevelIndexOf(text: String, target: Char): Int {
        var paren = 0
        var angle = 0
        var inString = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"') inString = !inString
            if (!inString) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    target -> if (paren == 0 && angle == 0) return i
                }
            }
        }
        return -1
    }

    private fun topLevelOwnerClassName(pkg: String, file: Path): String {
        val base = file.fileName.toString().removeSuffix(".kt")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + "Kt"
        return if (pkg.isBlank()) base else "$pkg.$base"
    }

    private data class IndexedHostFunction(
        val qualifiedName: String,
        val ownerClassName: String,
        val name: String,
        val receiverType: String?,
        val parameters: List<IndexedHostParameter>,
    )

    private data class IndexedHostParameter(
        val name: String,
        val type: String,
        val isVararg: Boolean,
        val hasDefault: Boolean,
        val defaultValue: Any?,
    )

    private fun getHostMember(name: String, target: Any): FieldMLValue {
        if (target is Map<*, *>) return hostToFieldMLValue(target[name])
        target.javaClass.publicFieldOrNull(name)?.let { return hostToFieldMLValue(it.get(target)) }
        target.javaClass.publicMethodOrNull(name, 0)?.let { return hostToFieldMLValue(it.invoke(target)) }
        target.javaClass.publicMethodOrNull("get${name.replaceFirstChar(Char::uppercaseChar)}", 0)?.let {
            return hostToFieldMLValue(it.invoke(target))
        }
        error("No readable member '$name' on ${target.javaClass.name}")
    }

    private fun setHostMember(name: String, target: Any, value: Any?) {
        if (target is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (target as MutableMap<String, Any?>)[name] = value
            return
        }
        target.javaClass.publicFieldOrNull(name)?.let {
            it.set(target, coerceHostArgument(it.type, value))
            return
        }
        target.javaClass.publicMethodOrNull("set${name.replaceFirstChar(Char::uppercaseChar)}", 1)?.let {
            it.invoke(target, coerceHostArgument(it.parameterTypes.single(), value))
            return
        }
        error("No writable member '$name' on ${target.javaClass.name}")
    }

    private fun Class<*>.publicFieldOrNull(name: String): Field? =
        fields.firstOrNull { it.name == name }?.also { it.isAccessible = true }

    private fun Class<*>.publicMethodOrNull(name: String, arity: Int): Method? =
        methods.firstOrNull { it.name == name && it.parameterCount == arity }?.also { it.isAccessible = true }

    private fun isCompatible(type: Class<*>, arg: Any?): Boolean =
        arg == null ||
            type.isInstance(arg) ||
            Number::class.java.isAssignableFrom(boxed(type)) && arg is Number ||
            boxed(type) == Boolean::class.javaObjectType && arg is Boolean ||
            boxed(type) == String::class.java && arg is String

    private fun coerceHostArgument(type: Class<*>, arg: Any?): Any? {
        if (arg == null) return null
        if (type.isInstance(arg)) return arg
        if (arg is Number) {
            return when (boxed(type)) {
                Int::class.javaObjectType -> arg.toInt()
                Long::class.javaObjectType -> arg.toLong()
                Float::class.javaObjectType -> arg.toFloat()
                Double::class.javaObjectType -> arg.toDouble()
                Short::class.javaObjectType -> arg.toShort()
                Byte::class.javaObjectType -> arg.toByte()
                else -> arg
            }
        }
        return arg
    }

    private fun boxed(type: Class<*>): Class<*> =
        when (type) {
            java.lang.Integer.TYPE -> Int::class.javaObjectType
            java.lang.Long.TYPE -> Long::class.javaObjectType
            java.lang.Float.TYPE -> Float::class.javaObjectType
            java.lang.Double.TYPE -> Double::class.javaObjectType
            java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
            java.lang.Short.TYPE -> Short::class.javaObjectType
            java.lang.Byte.TYPE -> Byte::class.javaObjectType
            java.lang.Character.TYPE -> Char::class.javaObjectType
            else -> type
        }

    private object HostInputPlaceholder {
        override fun toString(): String = "<hostInput>"
    }
}
