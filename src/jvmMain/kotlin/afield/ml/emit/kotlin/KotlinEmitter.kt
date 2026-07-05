package afield.ml.emit.kotlin

import afield.ml.ast.BinaryOp
import afield.ml.core.CApp
import afield.ml.core.CBinary
import afield.ml.core.CBool
import afield.ml.core.CField
import afield.ml.core.CFloat
import afield.ml.core.CIf
import afield.ml.core.CInt
import afield.ml.core.CLam
import afield.ml.core.CLet
import afield.ml.core.CList
import afield.ml.core.CMatch
import afield.ml.core.CRecord
import afield.ml.core.CString
import afield.ml.core.CTuple
import afield.ml.core.CTypeDecl
import afield.ml.core.CVar
import afield.ml.core.CPBool
import afield.ml.core.CPConstructor
import afield.ml.core.CPInt
import afield.ml.core.CPList
import afield.ml.core.CPString
import afield.ml.core.CPTuple
import afield.ml.core.CPVar
import afield.ml.core.CPWildcard
import afield.ml.core.CoreConstructor
import afield.ml.core.CoreExpr
import afield.ml.core.CorePattern
import afield.ml.type.TBool
import afield.ml.type.TFloat
import afield.ml.type.TInt
import afield.ml.type.TString
import afield.ml.type.TUnit
import afield.ml.type.Type

class KotlinEmitter {
    private var nextTemp = 0

    fun emit(expr: CoreExpr): String =
        """
        |@file:Suppress("UNCHECKED_CAST")
        |
        |data class FRecord(val fields: Map<String, Any?>) {
        |    operator fun get(name: String): Any? =
        |        fields[name] ?: error("No such field: ${'$'}name")
        |}
        |
        |data class FTuple(val elements: List<Any?>)
        |
        |data class FAdt(val tag: String, val args: List<Any?> = emptyList())
        |
        |data class FHost(val kind: String, val payload: Any? = null)
        |
        |fun runFieldML(): Any? =
        |${indent(emitWithPrelude(expr), 1)}
        """.trimMargin()

    private fun emitWithPrelude(expr: CoreExpr): String {
        val body = emitExpr(expr)
        return """
            |run {
            |    val Unit = kotlin.Unit
            |    val print = { value: Any? -> kotlin.io.print(value); value }
            |    val println = { value: Any? -> kotlin.io.println(value); value }
            |    val display = { channel: Any? -> { value: Any? -> kotlin.io.println("${'$'}channel: ${'$'}value"); value } }
            |    val show = { value: Any? -> value.toString() }
            |    val vec2 = { x: Any? -> { y: Any? -> FHost("Vec2", listOf(x, y)) } }
            |    val vec3 = { x: Any? -> { y: Any? -> { z: Any? -> FHost("Vec3", listOf(x, y, z)) } } }
            |    val vec4 = { x: Any? -> { y: Any? -> { z: Any? -> { w: Any? -> FHost("Vec4", listOf(x, y, z, w)) } } } }
            |    val fline = FHost("FLine", mutableListOf<Any?>())
            |    val moveTo = { x: Any? -> { y: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("moveTo", x, y)) } } } } }
            |    val moveTo3 = { x: Any? -> { y: Any? -> { z: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("moveTo3", x, y, z)) } } } } } }
            |    val lineTo = { x: Any? -> { y: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("lineTo", x, y)) } } } } }
            |    val lineTo3 = { x: Any? -> { y: Any? -> { z: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("lineTo3", x, y, z)) } } } } } }
            |    val moveToV = { point: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("moveToV", point)) } } } }
            |    val lineToV = { point: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("lineToV", point)) } } } }
            |    val cubicTo = { x0: Any? -> { y0: Any? -> { x1: Any? -> { y1: Any? -> { x: Any? -> { y: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("cubicTo", x0, y0, x1, y1, x, y)) } } } } } } } } }
            |    val close = { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add("close") } } }
            |    val thickness = { amount: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("thickness", amount)) } } } }
            |    val color = { color: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("color", color)) } } } }
            |    val filled = { enabled: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("filled", enabled)) } } } }
            |    val stroked = { enabled: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("stroked", enabled)) } } } }
            |    val pointed = { enabled: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("pointed", enabled)) } } } }
            |    val pointSize = { amount: Any? -> { line: Any? -> line.also { (it as? FHost)?.let { h -> (h.payload as? MutableList<Any?>)?.add(listOf("pointSize", amount)) } } } }
            |    val hostClass = { name: Any? -> FHost("Class", name) }
            |    val hostNew0 = { klass: Any? -> FHost("Instance", klass) }
            |    val hostCall0 = { name: Any? -> { target: Any? -> FHost("Call", listOf(name, target)) } }
            |    val hostCall1 = { name: Any? -> { arg: Any? -> { target: Any? -> FHost("Call", listOf(name, target, arg)) } } }
            |    val hostCall2 = { name: Any? -> { a: Any? -> { b: Any? -> { target: Any? -> FHost("Call", listOf(name, target, a, b)) } } } }
            |    val hostGet = { name: Any? -> { target: Any? -> FHost("Get", listOf(name, target)) } }
            |    val hostSet = { name: Any? -> { value: Any? -> { target: Any? -> target.also { } } } }
            |    $body
            |}
        """.trimMargin()
    }

    private fun emitExpr(expr: CoreExpr): String =
        when (expr) {
            is CInt -> expr.value.toString()
            is CFloat -> formatDouble(expr.value)
            is CBool -> expr.value.toString()
            is CString -> quote(expr.value)
            is CVar -> sanitize(expr.name)
            is CTuple -> "FTuple(listOf(${expr.elements.joinToString(", ") { emitExpr(it) }}))"
            is CList -> "listOf(${expr.elements.joinToString(", ") { emitExpr(it) }})"
            is CTypeDecl -> emitTypeDecl(expr)
            is CLet -> "run {\n    val ${sanitize(expr.name)} = ${emitExpr(expr.value)}\n    ${emitExpr(expr.body)}\n}"
            is CLam -> "{ ${sanitize(expr.parameter)}: Any? -> ${emitExpr(expr.body)} }"
            is CApp -> "(${emitExpr(expr.function)} as (Any?) -> Any?)(${emitExpr(expr.argument)})"
            is CIf -> "if (${cast(emitExpr(expr.condition), TBool)}) ${emitExpr(expr.thenBranch)} else ${emitExpr(expr.elseBranch)}"
            is CMatch -> emitMatch(expr)
            is CRecord -> "FRecord(mapOf(${expr.fields.joinToString(", ") { (name, value) -> "${quote(name)} to ${emitExpr(value)}" }}))"
            is CField -> "(${emitExpr(expr.target)} as FRecord)[${quote(expr.field)}]"
            is CBinary -> emitBinary(expr)
        }

    private fun emitTypeDecl(expr: CTypeDecl): String {
        val constructors = expr.constructors.joinToString("\n") { constructor ->
            "    val ${sanitize(constructor.name)} = ${emitConstructor(constructor)}"
        }
        return "run {\n$constructors\n    ${emitExpr(expr.body)}\n}"
    }

    private fun emitConstructor(constructor: CoreConstructor): String {
        if (constructor.arity == 0) return "FAdt(${quote(constructor.name)})"
        val parameters = List(constructor.arity) { index -> sanitize("${constructor.name}_value$index") }
        fun emitLayer(index: Int): String =
            if (index == parameters.size) {
                "FAdt(${quote(constructor.name)}, listOf(${parameters.joinToString(", ")}))"
            } else {
                "{ ${parameters[index]}: Any? -> ${emitLayer(index + 1)} }"
            }
        return emitLayer(0)
    }

    private fun emitMatch(expr: CMatch): String {
        val temp = freshTemp("match")
        val cases = expr.cases.joinToString(" else ") { case ->
            val compiled = compilePattern(case.pattern, temp)
            val body = emitExpr(case.body)
            val bindings = if (compiled.bindings.isEmpty()) "" else compiled.bindings.joinToString("\n") { "        $it" } + "\n"
            "if (${compiled.condition}) run {\n$bindings        $body\n    }"
        }
        return "run {\n    val $temp = ${emitExpr(expr.scrutinee)}\n    $cases else error(${quote("Non-exhaustive match")})\n}"
    }

    private fun compilePattern(pattern: CorePattern, value: String): CompiledPattern =
        when (pattern) {
            is CPWildcard -> CompiledPattern("true")
            is CPVar -> CompiledPattern("true", listOf("val ${sanitize(pattern.name)} = $value"))
            is CPInt -> CompiledPattern("($value == ${pattern.value})")
            is CPBool -> CompiledPattern("($value == ${pattern.value})")
            is CPString -> CompiledPattern("($value == ${quote(pattern.value)})")
            is CPTuple -> {
                val tuple = "($value as FTuple)"
                val compiledElements = pattern.elements.mapIndexed { index, element ->
                    compilePattern(element, "$tuple.elements[$index]")
                }
                CompiledPattern(
                    buildList {
                        add("($value is FTuple)")
                        add("($tuple.elements.size == ${pattern.elements.size})")
                        compiledElements.forEach { add(it.condition) }
                    }.joinToString(" && "),
                    compiledElements.flatMap { it.bindings },
                )
            }
            is CPList -> {
                val compiledElements = pattern.elements.mapIndexed { index, element ->
                    compilePattern(element, "($value as List<*>).get($index)")
                }
                CompiledPattern(
                    buildList {
                        add("($value is List<*>)")
                        add("(($value as List<*>).size == ${pattern.elements.size})")
                        compiledElements.forEach { add(it.condition) }
                    }.joinToString(" && "),
                    compiledElements.flatMap { it.bindings },
                )
            }
            is CPConstructor -> {
                val adt = "($value as FAdt)"
                val compiledArguments = pattern.arguments.mapIndexed { index, argument ->
                    compilePattern(argument, "$adt.args[$index]")
                }
                val conditions = buildList {
                    add("($value is FAdt)")
                    add("($adt.tag == ${quote(pattern.name)})")
                    add("($adt.args.size == ${pattern.arguments.size})")
                    compiledArguments.forEach { add(it.condition) }
                }
                CompiledPattern(
                    conditions.joinToString(" && "),
                    compiledArguments.flatMap { it.bindings },
                )
            }
        }

    private fun emitBinary(expr: CBinary): String {
        val left = cast(emitExpr(expr.left), expr.left.type)
        val right = cast(emitExpr(expr.right), expr.right.type)
        return when (expr.op) {
            BinaryOp.ADD -> "($left + $right)"
            BinaryOp.SUB -> "($left - $right)"
            BinaryOp.MUL -> "($left * $right)"
            BinaryOp.DIV -> "($left / $right)"
            BinaryOp.LT -> "($left < $right)"
            BinaryOp.LTE -> "($left <= $right)"
            BinaryOp.GT -> "($left > $right)"
            BinaryOp.GTE -> "($left >= $right)"
            BinaryOp.EQ -> "($left == $right)"
            BinaryOp.NEQ -> "($left != $right)"
        }
    }

    private fun cast(code: String, type: Type): String =
        when (type) {
            TInt -> "($code as Int)"
            TFloat -> "($code as Double)"
            TBool -> "($code as Boolean)"
            TString -> "($code as String)"
            TUnit -> "($code as kotlin.Unit)"
            else -> code
        }

    private fun sanitize(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        val prefixed = if (cleaned.firstOrNull()?.isDigit() == true) "v_$cleaned" else cleaned.ifEmpty { "v" }
        return if (prefixed in keywords) "${prefixed}_" else prefixed
    }

    private fun quote(text: String): String =
        buildString {
            append('"')
            for (c in text) {
                append(
                    when (c) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> c
                    },
                )
            }
            append('"')
        }

    private fun formatDouble(value: Double): String =
        value.toString().let { if ('.' in it || 'e' in it.lowercase()) it else "$it.0" }

    private fun freshTemp(prefix: String): String = "__${prefix}_${nextTemp++}"

    private fun indent(text: String, levels: Int): String {
        val prefix = "    ".repeat(levels)
        return text.lineSequence().joinToString("\n") { "$prefix$it" }
    }

    private companion object {
        data class CompiledPattern(
            val condition: String,
            val bindings: List<String> = emptyList(),
        )

        val keywords = setOf(
            "as",
            "break",
            "class",
            "continue",
            "do",
            "else",
            "false",
            "for",
            "fun",
            "if",
            "in",
            "interface",
            "is",
            "null",
            "object",
            "package",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "typeof",
            "val",
            "var",
            "when",
            "while",
        )
    }
}
