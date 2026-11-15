package afield.lang.postscript

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A PostScript interpreter that draws into [FLine]s.
 *
 * The language half follows John Earnest's "Let's Write a PostScript Interpreter"
 * (https://beyondloom.com/blog/postscript.html), reimplemented in Kotlin: a tokenizer that splits on
 * whitespace and on the self-delimiting `[ ] { } /` and `%`, an operand stack, a dictionary stack,
 * procedures as executable arrays, arrays built between a mark and `]`, and the control operators
 * `if ifelse for repeat loop exit forall`. Strings in `( )`, reals and the usual arithmetic, stack
 * and dictionary operators are added to what the post covers.
 *
 * The graphics half is the part the post leaves out: a graphics state (current transformation
 * matrix, colour, line width, current path and point) saved and restored by `gsave` / `grestore`,
 * the path operators (`newpath moveto rmoveto lineto rlineto curveto rcurveto arc arcn closepath`),
 * the painting operators `stroke` and `fill`, and `translate rotate scale`. Each paint appends to
 * [lines] one FLine per subpath, already in device space, with PostScript's upward y flipped so the
 * drawing reads the right way up on Field's canvas.
 *
 * One interpreter keeps its dictionaries between [eval] calls, as a PostScript REPL does; the operand
 * stack and the drawing are cleared by [reset].
 */
class PostScriptInterpreter {

    /** A PostScript object. Executable arrays and names are what the interpreter runs. */
    sealed class Obj {
        /** An integer or real; [isInt] keeps `3` printing as `3` and `3.0` as `3.0`. */
        data class Num(val v: Double, val isInt: Boolean) : Obj()
        data class Bool(val v: Boolean) : Obj()
        /** A name, literal (`/x`) or executable (`x`), with the source line it was read on. */
        class Name(val name: String, val literal: Boolean, val line: Int = -1) : Obj()
        class Str(val s: String) : Obj()
        /** An array; an executable one is a procedure. */
        class Arr(val items: MutableList<Obj>, val executable: Boolean) : Obj()
        class Dict(val map: LinkedHashMap<String, Obj> = LinkedHashMap()) : Obj()
        /** A built-in operator. */
        class Op(val name: String, val fn: () -> Unit) : Obj()
        object Mark : Obj()
        object Null : Obj()
    }

    /** A PostScript error, reported on [line] (1-based, -1 when unknown). */
    class PsError(message: String, val line: Int) : RuntimeException(message)

    private class Exit : RuntimeException(null, null, false, false)

    /** A token of source text with the line it began on. */
    data class Token(val text: String, val line: Int)

    /** The operand stack, bottom first. */
    val stack = ArrayList<Obj>()

    /** The dictionary stack: systemdict, userdict, then whatever `begin` pushed. */
    val dicts = ArrayList<Obj.Dict>()

    /** Everything painted since the last [reset], in device space (y down). */
    val lines = ArrayList<FLine>()

    /** How many times `showpage` ran. Drawing is not cleared by it, so a page is everything painted. */
    var pages = 0
        private set

    /** Where `=`, `==`, `print` and `pstack` write. */
    var onOutput: (String) -> Unit = { print(it) }

    /** The line of the token being executed, for diagnostics. */
    var currentLine = -1
        private set

    /** Steps allowed in one [eval]; a runaway `loop` stops here instead of hanging the main thread. */
    var stepBudget = 20_000_000L

    private var steps = 0L

    // ---- graphics state ----

    private class GState(
        var ctm: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        var rgb: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
        var lineWidth: Double = 1.0,
        var path: MutableList<FLine> = ArrayList(),
        var point: DoubleArray? = null,
        var subpathStart: DoubleArray? = null,
    ) {
        fun copy() = GState(ctm.copyOf(), rgb.copyOf(), lineWidth, path.map { it.copy() }.toMutableList(), point?.copyOf(), subpathStart?.copyOf())
    }

    private var gs = GState()
    private val gstack = ArrayList<GState>()

    init {
        dicts += Obj.Dict(LinkedHashMap())
        dicts += Obj.Dict(LinkedHashMap())
        installOperators()
    }

    /** Empty the operand stack, the drawing and the graphics state; definitions survive. */
    fun reset() {
        stack.clear(); lines.clear(); gstack.clear(); gs = GState(); pages = 0
        while (dicts.size > 2) dicts.removeAt(dicts.size - 1)
    }

    // ---- tokenizer and parser ----

    /** Split [src] into tokens: whitespace separates, `[ ] { }` stand alone, `/` and `%` start new tokens. */
    fun tokenize(src: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        var line = 1
        val delims = "[]{}()/%<>"
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '%' -> { while (i < src.length && src[i] != '\n') i++ }
                c in "[]{}" -> { out += Token(c.toString(), line); i++ }
                c == '(' -> {
                    val startLine = line
                    val sb = StringBuilder("(")
                    var depth = 1
                    i++
                    while (i < src.length && depth > 0) {
                        val d = src[i]
                        if (d == '\\' && i + 1 < src.length) {
                            val e = src[i + 1]
                            sb.append(when (e) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> e })
                            i += 2; continue
                        }
                        if (d == '(') depth++
                        if (d == ')') { depth--; if (depth == 0) { i++; break } }
                        if (d == '\n') line++
                        sb.append(d); i++
                    }
                    if (depth > 0) throw PsError("unterminated string: no ) for the ( here", startLine)
                    out += Token(sb.toString(), startLine)
                }
                else -> {
                    val start = i
                    i++
                    while (i < src.length && !src[i].isWhitespace() && src[i] !in delims) i++
                    out += Token(src.substring(start, i), line)
                }
            }
        }
        return out
    }

    /** Parse [tokens] into one executable array, collecting `{ … }` into procedures without running them. */
    fun parse(tokens: List<Token>): Obj.Arr {
        var pos = 0
        fun body(closing: Boolean, openLine: Int): MutableList<Obj> {
            val items = ArrayList<Obj>()
            while (pos < tokens.size) {
                val t = tokens[pos++]
                when (t.text) {
                    "{" -> items += Obj.Arr(body(true, t.line), executable = true)
                    "}" -> if (closing) return items else throw PsError("unmatched }", t.line)
                    else -> items += atom(t)
                }
            }
            if (closing) throw PsError("unmatched {: no } closes the procedure opened here", openLine)
            return items
        }
        return Obj.Arr(body(false, -1), executable = true)
    }

    private val numberPattern = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    private fun atom(t: Token): Obj {
        val s = t.text
        if (s.startsWith("(")) return Obj.Str(s.substring(1))
        if (s.startsWith("/")) return Obj.Name(s.substring(1), literal = true, line = t.line)
        if (numberPattern.matches(s)) {
            val isInt = !s.contains('.') && !s.contains('e') && !s.contains('E')
            return Obj.Num(s.toDouble(), isInt)
        }
        val radix = Regex("(\\d+)#([0-9a-zA-Z]+)").matchEntire(s)
        if (radix != null) return Obj.Num(radix.groupValues[2].toLong(radix.groupValues[1].toInt()).toDouble(), true)
        return Obj.Name(s, literal = false, line = t.line)
    }

    // ---- evaluation ----

    /** Run [src]. Throws [PsError] with the line of the failing token. */
    fun eval(src: String) {
        steps = 0
        val program = parse(tokenize(src))
        try {
            runProc(program)
        } catch (e: Exit) {
            throw PsError("exit outside a loop", currentLine)
        } catch (e: PsError) {
            throw if (e.line < 0) PsError(e.message ?: "error", currentLine) else e
        } catch (e: StackOverflowError) {
            throw PsError("recursion too deep (does a procedure call itself without stopping?)", currentLine)
        }
    }

    /** Execute the items of procedure [p] in order. Nested procedures are pushed, not run. */
    fun runProc(p: Obj.Arr) {
        // Counted here as well as in exec, so `{ } loop` (a body with nothing to execute) still runs out of steps.
        if (++steps > stepBudget) throw PsError("ran more than $stepBudget steps, so it was stopped (a loop with no exit?)", currentLine)
        for (item in p.items) {
            if (item is Obj.Arr) push(item) else exec(item)
        }
    }

    /** Execute one object: names are looked up and run, procedures run, everything else is pushed. */
    fun exec(o: Obj) {
        if (++steps > stepBudget) throw PsError("ran more than $stepBudget steps, so it was stopped (a loop with no exit?)", currentLine)
        when (o) {
            is Obj.Name -> if (o.literal) push(o) else {
                if (o.line >= 0) currentLine = o.line
                val v = lookup(o.name) ?: throw PsError("undefined: ${o.name}", currentLine)
                when (v) {
                    is Obj.Arr -> if (v.executable) runProc(v) else push(v)
                    is Obj.Op -> v.fn()
                    is Obj.Name -> exec(v)
                    else -> push(v)
                }
            }
            is Obj.Op -> o.fn()
            is Obj.Arr -> if (o.executable) runProc(o) else push(o)
            else -> push(o)
        }
    }

    /** The value of [name] in the innermost dictionary that holds it. */
    fun lookup(name: String): Obj? {
        for (i in dicts.indices.reversed()) dicts[i].map[name]?.let { return it }
        return null
    }

    fun push(o: Obj) {
        if (stack.size > 100_000) throw PsError("stack overflow", currentLine)
        stack += o
    }

    fun pop(): Obj = if (stack.isEmpty()) throw PsError("stack underflow", currentLine) else stack.removeAt(stack.size - 1)

    private fun popNum(): Double = when (val o = pop()) {
        is Obj.Num -> o.v
        else -> throw PsError("expected a number, found ${show(o)}", currentLine)
    }

    private fun popInt(): Int = popNum().toInt()
    private fun popBool(): Boolean = (pop() as? Obj.Bool)?.v ?: throw PsError("expected a boolean", currentLine)
    private fun popProc(): Obj.Arr = (pop() as? Obj.Arr) ?: throw PsError("expected a procedure", currentLine)

    private fun num(v: Double, isInt: Boolean = false): Obj.Num =
        Obj.Num(v, isInt && v == floor(v) && abs(v) < 1e15)

    private fun def(name: String, fn: () -> Unit) { dicts[0].map[name] = Obj.Op(name, fn) }

    /** `==` notation for [o]: procedures in braces, arrays in brackets, literal names with a slash. */
    fun show(o: Obj): String = when (o) {
        is Obj.Num -> if (o.isInt) o.v.toLong().toString() else trimReal(o.v)
        is Obj.Bool -> o.v.toString()
        is Obj.Name -> (if (o.literal) "/" else "") + o.name
        is Obj.Str -> "(" + o.s + ")"
        is Obj.Arr -> if (o.executable) o.items.joinToString(" ", "{", "}") { show(it) } else o.items.joinToString(" ", "[", "]") { show(it) }
        is Obj.Dict -> "-dict-"
        is Obj.Op -> "--${o.name}--"
        Obj.Mark -> "-mark-"
        Obj.Null -> "null"
    }

    private fun trimReal(v: Double): String {
        val s = "%.6g".format(v).let { if ('e' in it) it else it.trimEnd('0') }
        return if (s.endsWith(".")) s + "0" else s
    }

    /** The stack as `==` writes each value, bottom first. */
    fun stackStrings(): List<String> = stack.map { show(it) }

    private fun arith(name: String, f: (Double, Double) -> Double, intResult: Boolean = true) = def(name) {
        val b = pop(); val a = pop()
        if (a !is Obj.Num || b !is Obj.Num) throw PsError("$name needs two numbers", currentLine)
        push(num(f(a.v, b.v), intResult && a.isInt && b.isInt))
    }

    private fun compare(name: String, f: (Int) -> Boolean) = def(name) {
        val b = pop(); val a = pop()
        val c = when {
            a is Obj.Num && b is Obj.Num -> a.v.compareTo(b.v)
            a is Obj.Str && b is Obj.Str -> a.s.compareTo(b.s)
            else -> throw PsError("$name cannot compare ${show(a)} and ${show(b)}", currentLine)
        }
        push(Obj.Bool(f(c)))
    }

    private fun equal(a: Obj, b: Obj): Boolean = when {
        a is Obj.Num && b is Obj.Num -> a.v == b.v
        a is Obj.Bool && b is Obj.Bool -> a.v == b.v
        a is Obj.Name && b is Obj.Name -> a.name == b.name
        a is Obj.Str && b is Obj.Str -> a.s == b.s
        a is Obj.Name && b is Obj.Str -> a.name == b.s
        a is Obj.Str && b is Obj.Name -> a.s == b.name
        else -> a === b
    }

    private fun key(o: Obj): String = when (o) {
        is Obj.Name -> o.name
        is Obj.Str -> o.s
        else -> show(o)
    }

    private var seed = 1L

    private fun installOperators() {
        // stack
        def("pop") { pop() }
        def("exch") { val b = pop(); val a = pop(); push(b); push(a) }
        def("dup") { val a = pop(); push(a); push(a) }
        def("copy") {
            val n = popInt()
            if (n > stack.size) throw PsError("stack underflow", currentLine)
            stack.addAll(stack.subList(stack.size - n, stack.size).toList())
        }
        def("index") {
            val n = popInt()
            if (n < 0 || n >= stack.size) throw PsError("stack underflow", currentLine)
            push(stack[stack.size - 1 - n])
        }
        def("roll") {
            val j = popInt(); val n = popInt()
            if (n > stack.size) throw PsError("stack underflow", currentLine)
            if (n > 0) {
                val top = stack.subList(stack.size - n, stack.size)
                val copy = top.toList()
                val s = ((j % n) + n) % n
                for (i in 0 until n) top[(i + s) % n] = copy[i]
            }
        }
        def("clear") { stack.clear() }
        def("count") { push(num(stack.size.toDouble(), true)) }
        def("mark") { push(Obj.Mark) }
        def("[") { push(Obj.Mark) }
        def("]") {
            val i = stack.indexOfLast { it === Obj.Mark }
            if (i < 0) throw PsError("] with no matching [", currentLine)
            val items = stack.subList(i + 1, stack.size).toMutableList()
            while (stack.size > i) stack.removeAt(stack.size - 1)
            push(Obj.Arr(items, executable = false))
        }
        def("cleartomark") {
            val i = stack.indexOfLast { it === Obj.Mark }
            if (i < 0) throw PsError("unmatched mark", currentLine)
            while (stack.size > i) stack.removeAt(stack.size - 1)
        }
        def("counttomark") {
            val i = stack.indexOfLast { it === Obj.Mark }
            if (i < 0) throw PsError("unmatched mark", currentLine)
            push(num((stack.size - i - 1).toDouble(), true))
        }

        // arithmetic
        arith("add", { a, b -> a + b })
        arith("sub", { a, b -> a - b })
        arith("mul", { a, b -> a * b })
        arith("div", { a, b -> if (b == 0.0) throw PsError("undefinedresult: division by zero", currentLine) else a / b }, intResult = false)
        arith("idiv", { a, b -> if (b == 0.0) throw PsError("undefinedresult: division by zero", currentLine) else (a.toLong() / b.toLong()).toDouble() })
        arith("mod", { a, b -> if (b == 0.0) throw PsError("undefinedresult: division by zero", currentLine) else (a.toLong() % b.toLong()).toDouble() })
        def("neg") { val a = pop() as? Obj.Num ?: throw PsError("neg needs a number", currentLine); push(num(-a.v, a.isInt)) }
        def("abs") { val a = pop() as? Obj.Num ?: throw PsError("abs needs a number", currentLine); push(num(abs(a.v), a.isInt)) }
        def("floor") { val a = pop() as? Obj.Num ?: throw PsError("floor needs a number", currentLine); push(num(floor(a.v), a.isInt)) }
        def("ceiling") { val a = pop() as? Obj.Num ?: throw PsError("ceiling needs a number", currentLine); push(num(ceil(a.v), a.isInt)) }
        def("round") { val a = pop() as? Obj.Num ?: throw PsError("round needs a number", currentLine); push(num(floor(a.v + 0.5), a.isInt)) }
        def("truncate") { val a = pop() as? Obj.Num ?: throw PsError("truncate needs a number", currentLine); push(num(a.v.toLong().toDouble(), a.isInt)) }
        def("cvi") { push(num(popNum().toLong().toDouble(), true)) }
        def("cvr") { push(num(popNum())) }
        def("sqrt") { val a = popNum(); if (a < 0) throw PsError("rangecheck: sqrt of a negative number", currentLine); push(num(sqrt(a))) }
        def("sin") { push(num(sin(popNum() * PI / 180))) }
        def("cos") { push(num(cos(popNum() * PI / 180))) }
        def("atan") { val den = popNum(); val n = popNum(); push(num((atan2(n, den) * 180 / PI + 360) % 360)) }
        def("exp") { val e = popNum(); val b = popNum(); push(num(b.pow(e))) }
        def("ln") { push(num(ln(popNum()))) }
        def("log") { push(num(log10(popNum()))) }
        def("rand") { seed = (seed * 1103515245 + 12345) and 0x7fffffff; push(num(seed.toDouble(), true)) }
        def("srand") { seed = popNum().toLong() }

        // relational and boolean
        def("eq") { val b = pop(); val a = pop(); push(Obj.Bool(equal(a, b))) }
        def("ne") { val b = pop(); val a = pop(); push(Obj.Bool(!equal(a, b))) }
        compare("gt") { it > 0 }; compare("ge") { it >= 0 }
        compare("lt") { it < 0 }; compare("le") { it <= 0 }
        dicts[0].map["true"] = Obj.Bool(true)
        dicts[0].map["false"] = Obj.Bool(false)
        dicts[0].map["null"] = Obj.Null
        fun logic(name: String, fb: (Boolean, Boolean) -> Boolean, fi: (Long, Long) -> Long) = def(name) {
            val b = pop(); val a = pop()
            when {
                a is Obj.Bool && b is Obj.Bool -> push(Obj.Bool(fb(a.v, b.v)))
                a is Obj.Num && b is Obj.Num -> push(num(fi(a.v.toLong(), b.v.toLong()).toDouble(), true))
                else -> throw PsError("$name needs two booleans or two integers", currentLine)
            }
        }
        logic("and", { a, b -> a && b }, { a, b -> a and b })
        logic("or", { a, b -> a || b }, { a, b -> a or b })
        logic("xor", { a, b -> a xor b }, { a, b -> a xor b })
        def("not") {
            when (val a = pop()) {
                is Obj.Bool -> push(Obj.Bool(!a.v))
                is Obj.Num -> push(num(a.v.toLong().inv().toDouble(), true))
                else -> throw PsError("not needs a boolean or an integer", currentLine)
            }
        }

        // control
        def("exec") { exec(pop()) }
        def("if") { val p = popProc(); if (popBool()) runProc(p) }
        def("ifelse") { val no = popProc(); val yes = popProc(); if (popBool()) runProc(yes) else runProc(no) }
        def("for") {
            val p = popProc(); val limit = pop() as? Obj.Num; val inc = pop() as? Obj.Num; val init = pop() as? Obj.Num
            if (limit == null || inc == null || init == null) throw PsError("for needs: initial increment limit {proc}", currentLine)
            val ints = init.isInt && inc.isInt
            if (inc.v == 0.0) throw PsError("for with a zero increment never ends", currentLine)
            var i = init.v
            try {
                while (if (inc.v > 0) i <= limit.v + 1e-12 else i >= limit.v - 1e-12) {
                    push(num(i, ints)); runProc(p); i += inc.v
                }
            } catch (_: Exit) {}
        }
        def("repeat") {
            val p = popProc(); val n = popInt()
            if (n < 0) throw PsError("repeat needs a count of 0 or more", currentLine)
            try { repeat(n) { runProc(p) } } catch (_: Exit) {}
        }
        def("loop") { val p = popProc(); try { while (true) runProc(p) } catch (_: Exit) {} }
        def("exit") { throw Exit() }
        def("forall") {
            val p = popProc()
            try {
                when (val a = pop()) {
                    is Obj.Arr -> for (item in a.items.toList()) { push(item); runProc(p) }
                    is Obj.Str -> for (ch in a.s) { push(num(ch.code.toDouble(), true)); runProc(p) }
                    is Obj.Dict -> for ((k, v) in a.map.entries.toList()) { push(Obj.Name(k, true)); push(v); runProc(p) }
                    else -> throw PsError("forall needs an array, string or dictionary", currentLine)
                }
            } catch (_: Exit) {}
        }
        def("bind") { }
        def("cvx") { when (val a = pop()) { is Obj.Arr -> push(Obj.Arr(a.items, true)); is Obj.Name -> push(Obj.Name(a.name, false, a.line)); else -> push(a) } }
        def("cvlit") { when (val a = pop()) { is Obj.Arr -> push(Obj.Arr(a.items, false)); is Obj.Name -> push(Obj.Name(a.name, true, a.line)); else -> push(a) } }
        def("type") {
            push(Obj.Name(when (pop()) {
                is Obj.Num -> "realtype"; is Obj.Bool -> "booleantype"; is Obj.Name -> "nametype"; is Obj.Str -> "stringtype"
                is Obj.Arr -> "arraytype"; is Obj.Dict -> "dicttype"; is Obj.Op -> "operatortype"; Obj.Mark -> "marktype"; Obj.Null -> "nulltype"
            }, literal = true))
        }

        // dictionaries
        def("def") { val v = pop(); val k = pop(); dicts.last().map[key(k)] = v }
        def("load") { val k = key(pop()); push(lookup(k) ?: throw PsError("undefined: $k", currentLine)) }
        def("store") {
            val v = pop(); val k = key(pop())
            (dicts.lastOrNull { k in it.map } ?: dicts.last()).map[k] = v
        }
        def("dict") { popInt(); push(Obj.Dict()) }
        def("begin") { dicts += (pop() as? Obj.Dict ?: throw PsError("begin needs a dictionary", currentLine)) }
        def("end") { if (dicts.size <= 2) throw PsError("dictstackunderflow: end with no begin", currentLine); dicts.removeAt(dicts.size - 1) }
        def("currentdict") { push(dicts.last()) }
        def("known") { val k = key(pop()); val d = pop() as? Obj.Dict ?: throw PsError("known needs a dictionary", currentLine); push(Obj.Bool(k in d.map)) }
        def("where") {
            val k = key(pop())
            val d = dicts.lastOrNull { k in it.map }
            if (d != null) { push(d); push(Obj.Bool(true)) } else push(Obj.Bool(false))
        }

        // arrays and strings
        def("array") { val n = popInt(); push(Obj.Arr(MutableList(n) { Obj.Null }, false)) }
        def("length") {
            push(num(when (val a = pop()) {
                is Obj.Arr -> a.items.size; is Obj.Str -> a.s.length; is Obj.Dict -> a.map.size; is Obj.Name -> a.name.length
                else -> throw PsError("length needs an array, string or dictionary", currentLine)
            }.toDouble(), true))
        }
        def("get") {
            val k = pop()
            when (val a = pop()) {
                is Obj.Arr -> { val i = (k as? Obj.Num)?.v?.toInt() ?: -1; push(a.items.getOrNull(i) ?: throw PsError("rangecheck: index ${show(k)} of an array of ${a.items.size}", currentLine)) }
                is Obj.Str -> { val i = (k as? Obj.Num)?.v?.toInt() ?: -1; push(num((a.s.getOrNull(i) ?: throw PsError("rangecheck", currentLine)).code.toDouble(), true)) }
                is Obj.Dict -> push(a.map[key(k)] ?: throw PsError("undefined: ${key(k)}", currentLine))
                else -> throw PsError("get needs an array, string or dictionary", currentLine)
            }
        }
        def("put") {
            val v = pop(); val k = pop()
            when (val a = pop()) {
                is Obj.Arr -> { val i = (k as? Obj.Num)?.v?.toInt() ?: -1; if (i !in a.items.indices) throw PsError("rangecheck", currentLine); a.items[i] = v }
                is Obj.Dict -> a.map[key(k)] = v
                else -> throw PsError("put needs an array or dictionary", currentLine)
            }
        }
        def("aload") { val a = pop() as? Obj.Arr ?: throw PsError("aload needs an array", currentLine); a.items.forEach { push(it) }; push(a) }
        def("astore") {
            val a = pop() as? Obj.Arr ?: throw PsError("astore needs an array", currentLine)
            for (i in a.items.indices.reversed()) a.items[i] = pop()
            push(a)
        }
        def("getinterval") {
            val n = popInt(); val i = popInt()
            when (val a = pop()) {
                is Obj.Arr -> push(Obj.Arr(a.items.subList(i, i + n).toMutableList(), a.executable))
                is Obj.Str -> push(Obj.Str(a.s.substring(i, i + n)))
                else -> throw PsError("getinterval needs an array or string", currentLine)
            }
        }
        def("cvs") { pop(); val a = pop(); push(Obj.Str(if (a is Obj.Str) a.s else show(a).removePrefix("/"))) }

        // output
        def("=") { val a = pop(); onOutput((if (a is Obj.Str) a.s else show(a).removePrefix("/")) + "\n") }
        def("==") { onOutput(show(pop()) + "\n") }
        def("print") { val a = pop() as? Obj.Str ?: throw PsError("print needs a string", currentLine); onOutput(a.s) }
        def("pstack") { stack.reversed().forEach { onOutput(show(it) + "\n") } }
        def("stack") { stack.reversed().forEach { onOutput(show(it) + "\n") } }

        installGraphics()
    }

    // ---- graphics ----

    private fun transform(x: Double, y: Double, m: DoubleArray = gs.ctm) =
        doubleArrayOf(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])

    private fun inverse(px: Double, py: Double): DoubleArray {
        val m = gs.ctm
        val det = m[0] * m[3] - m[1] * m[2]
        if (det == 0.0) throw PsError("undefinedresult: the transformation is singular", currentLine)
        val x = px - m[4]; val y = py - m[5]
        return doubleArrayOf((m[3] * x - m[2] * y) / det, (-m[1] * x + m[0] * y) / det)
    }

    /** Pre-multiply the CTM by [t]: the new user space is [t] applied within the old one. */
    private fun concat(t: DoubleArray) {
        val m = gs.ctm
        gs.ctm = doubleArrayOf(
            t[0] * m[0] + t[1] * m[2], t[0] * m[1] + t[1] * m[3],
            t[2] * m[0] + t[3] * m[2], t[2] * m[1] + t[3] * m[3],
            t[4] * m[0] + t[5] * m[2] + m[4], t[4] * m[1] + t[5] * m[3] + m[5],
        )
    }

    private fun needPoint(op: String): DoubleArray = gs.point ?: throw PsError("nocurrentpoint: $op needs a current point (moveto first)", currentLine)

    private fun currentSubpath(): FLine {
        val last = gs.path.lastOrNull()
        if (last != null && gs.point != null) return last
        throw PsError("nocurrentpoint", currentLine)
    }

    private fun deviceMoveTo(p: DoubleArray) {
        val f = FLine()
        f.moveTo(p[0], -p[1])
        gs.path += f
        gs.point = p; gs.subpathStart = p
    }

    private fun deviceLineTo(p: DoubleArray) {
        if (gs.point == null) { deviceMoveTo(p); return }
        currentSubpath().lineTo(p[0], -p[1])
        gs.point = p
    }

    private fun userLineTo(x: Double, y: Double) = deviceLineTo(transform(x, y))

    private fun arcPath(cx: Double, cy: Double, r: Double, a1: Double, a2In: Double, clockwise: Boolean) {
        var a2 = a2In
        if (!clockwise) { while (a2 < a1) a2 += 360.0 } else { while (a2 > a1) a2 -= 360.0 }
        val sweep = a2 - a1
        val n = maxOf(1, ceil(abs(sweep) / 90.0).toInt())
        val start = transform(cx + r * cos(a1 * PI / 180), cy + r * sin(a1 * PI / 180))
        if (gs.point == null) deviceMoveTo(start) else deviceLineTo(start)
        // One cubic per quarter turn or less, in user space and then transformed: exact under any CTM.
        val step = sweep / n
        for (i in 0 until n) {
            val t0 = (a1 + step * i) * PI / 180
            val t1 = (a1 + step * (i + 1)) * PI / 180
            val k = 4.0 / 3.0 * kotlin.math.tan((t1 - t0) / 4)
            val c1 = transform(cx + r * (cos(t0) - k * sin(t0)), cy + r * (sin(t0) + k * cos(t0)))
            val c2 = transform(cx + r * (cos(t1) + k * sin(t1)), cy + r * (sin(t1) - k * cos(t1)))
            val e = transform(cx + r * cos(t1), cy + r * sin(t1))
            currentSubpath().cubicTo(c1[0], -c1[1], 0, c2[0], -c2[1], 0, e[0], -e[1], 0)
            gs.point = e
        }
    }

    private fun paint(fill: Boolean) {
        val m = gs.ctm
        val scale = sqrt(abs(m[0] * m[3] - m[1] * m[2]))
        for (sub in gs.path) {
            if (sub.nodes.size < 2) continue
            val f = sub.copy()
            f.color = vec(gs.rgb[0], gs.rgb[1], gs.rgb[2], 1.0)
            if (fill) { f.filled = true; f.stroked = false }
            else { f.thickness = (gs.lineWidth * scale).coerceAtLeast(0.5); f.stroked = true }
            lines += f
        }
        gs.path = ArrayList(); gs.point = null; gs.subpathStart = null
    }

    private fun hsbToRgb(h: Double, s: Double, b: Double): DoubleArray {
        val c = java.awt.Color.getHSBColor(h.toFloat(), s.toFloat(), b.toFloat())
        return doubleArrayOf(c.red / 255.0, c.green / 255.0, c.blue / 255.0)
    }

    private fun installGraphics() {
        def("newpath") { gs.path = ArrayList(); gs.point = null; gs.subpathStart = null }
        def("moveto") { val y = popNum(); val x = popNum(); deviceMoveTo(transform(x, y)) }
        def("rmoveto") {
            val dy = popNum(); val dx = popNum(); val p = needPoint("rmoveto"); val u = inverse(p[0], p[1])
            deviceMoveTo(transform(u[0] + dx, u[1] + dy))
        }
        def("lineto") { val y = popNum(); val x = popNum(); needPoint("lineto"); userLineTo(x, y) }
        def("rlineto") {
            val dy = popNum(); val dx = popNum(); val p = needPoint("rlineto"); val u = inverse(p[0], p[1])
            userLineTo(u[0] + dx, u[1] + dy)
        }
        def("curveto") {
            val y3 = popNum(); val x3 = popNum(); val y2 = popNum(); val x2 = popNum(); val y1 = popNum(); val x1 = popNum()
            needPoint("curveto")
            val c1 = transform(x1, y1); val c2 = transform(x2, y2); val e = transform(x3, y3)
            currentSubpath().cubicTo(c1[0], -c1[1], 0, c2[0], -c2[1], 0, e[0], -e[1], 0)
            gs.point = e
        }
        def("rcurveto") {
            val y3 = popNum(); val x3 = popNum(); val y2 = popNum(); val x2 = popNum(); val y1 = popNum(); val x1 = popNum()
            val p = needPoint("rcurveto"); val u = inverse(p[0], p[1])
            val c1 = transform(u[0] + x1, u[1] + y1); val c2 = transform(u[0] + x2, u[1] + y2); val e = transform(u[0] + x3, u[1] + y3)
            currentSubpath().cubicTo(c1[0], -c1[1], 0, c2[0], -c2[1], 0, e[0], -e[1], 0)
            gs.point = e
        }
        def("arc") { val a2 = popNum(); val a1 = popNum(); val r = popNum(); val y = popNum(); val x = popNum(); arcPath(x, y, r, a1, a2, false) }
        def("arcn") { val a2 = popNum(); val a1 = popNum(); val r = popNum(); val y = popNum(); val x = popNum(); arcPath(x, y, r, a1, a2, true) }
        def("closepath") {
            val s = gs.subpathStart ?: return@def
            if (gs.point == null) return@def
            currentSubpath().close()
            gs.point = s
        }
        def("currentpoint") { val p = needPoint("currentpoint"); val u = inverse(p[0], p[1]); push(num(u[0])); push(num(u[1])) }
        def("stroke") { paint(false) }
        def("fill") { paint(true) }
        def("eofill") { paint(true) }
        def("gsave") { gstack += gs.copy() }
        def("grestore") { if (gstack.isNotEmpty()) gs = gstack.removeAt(gstack.size - 1) }
        def("translate") { val ty = popNum(); val tx = popNum(); concat(doubleArrayOf(1.0, 0.0, 0.0, 1.0, tx, ty)) }
        def("scale") { val sy = popNum(); val sx = popNum(); concat(doubleArrayOf(sx, 0.0, 0.0, sy, 0.0, 0.0)) }
        def("rotate") {
            val a = popNum() * PI / 180
            concat(doubleArrayOf(cos(a), sin(a), -sin(a), cos(a), 0.0, 0.0))
        }
        def("initmatrix") { gs.ctm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) }
        def("setlinewidth") { gs.lineWidth = popNum() }
        def("currentlinewidth") { push(num(gs.lineWidth)) }
        def("setgray") { val g = popNum().coerceIn(0.0, 1.0); gs.rgb = doubleArrayOf(g, g, g) }
        def("setrgbcolor") { val b = popNum(); val g = popNum(); val r = popNum(); gs.rgb = doubleArrayOf(r, g, b).map { it.coerceIn(0.0, 1.0) }.toDoubleArray() }
        def("sethsbcolor") { val b = popNum(); val s = popNum(); val h = popNum(); gs.rgb = hsbToRgb(h, s, b) }
        def("setlinecap") { popNum() }
        def("setlinejoin") { popNum() }
        def("showpage") { pages++ }
    }
}
