package afield.forth

import afield.graphics.FLine
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import kotlin.math.*

/**
 * A minimal, self-defining FORTH interpreter for Field4 creative coding.
 *
 * Supports:
 * - Standard FORTH stack operations (dup, drop, swap, over, rot, etc.)
 * - Arithmetic and logic
 * - Word definitions (`: name ... ;`)
 * - Variables and constants
 * - Control flow (if/else/then, begin/until/while/repeat, do/loop)
 * - String literals and output (." ... " and .)
 * - JOML vector support (vec2, vec3, vec4)
 * - FLine integration (fline, moveto, lineto, cubicto, etc.)
 * - Kotlin bridging via `defineWord`
 */
class ForthInterpreter {

    data class Token(val text: String, val line: Int)

    // Data stack
    val stack = ArrayDeque<Any>()

    // Return stack (for do/loop)
    val returnStack = ArrayDeque<Any>()

    // Dictionary: word name (lowercase) -> compiled word
    val dictionary = LinkedHashMap<String, ForthWord>()

    // Variables storage
    val variables = HashMap<String, Any?>()

    // Output callback
    var onOutput: (String) -> Unit = { print(it) }

    // Per-box run-loop strands: strandName -> word name to execute each frame
    val strands = HashMap<String, String>()

    init {
        installBuiltins()
    }

    // -------------------------------------------------------------------------
    // Stack helpers
    // -------------------------------------------------------------------------

    fun push(v: Any) = stack.addLast(v)

    fun pop(): Any = stack.removeLastOrNull() ?: throw ForthError("Stack underflow")

    fun peek(): Any = stack.lastOrNull() ?: throw ForthError("Stack underflow")

    fun popNum(): Double = anyToDouble(pop())

    fun popInt(): Int = popNum().toInt()

    fun anyToDouble(v: Any): Double = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Number -> v.toDouble()
        is Boolean -> if (v) -1.0 else 0.0
        else -> throw ForthError("Expected number, got ${v::class.simpleName}: $v")
    }

    fun anyToBool(v: Any): Boolean = when (v) {
        is Boolean -> v
        is Int -> v != 0
        is Double -> v != 0.0
        is Float -> v != 0.0f
        else -> true
    }

    private fun forthEq(a: Any, b: Any): Boolean {
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }

    // -------------------------------------------------------------------------
    // Interpreter entry point
    // -------------------------------------------------------------------------

    /**
     * Evaluate a string of FORTH code.
     * Returns a list of diagnostics (empty = success).
     */
    fun eval(source: String): List<ForthDiagnostic> {
        val diagnostics = mutableListOf<ForthDiagnostic>()
        try {
            val tokens = tokenize(source)
            execTokens(tokens, 0, tokens.size)
        } catch (e: ForthError) {
            diagnostics += ForthDiagnostic(e.message ?: "Unknown error", e.line)
        } catch (e: Throwable) {
            diagnostics += ForthDiagnostic(e.message ?: e.javaClass.name, -1)
        }
        return diagnostics
    }

    // -------------------------------------------------------------------------
    // Tokenizer
    // -------------------------------------------------------------------------

    fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var lineNum = 1
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\n' -> { lineNum++; i++ }
                c.isWhitespace() -> i++
                c == '\\' -> { while (i < source.length && source[i] != '\n') i++ }
                c == '(' -> {
                    i++
                    while (i < source.length && source[i] != ')') {
                        if (source[i] == '\n') lineNum++
                        i++
                    }
                    if (i < source.length) i++
                }
                source.startsWith(".\"", i) -> {
                    i += 2
                    val start = i
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\n') lineNum++
                        i++
                    }
                    val str = source.substring(start, i)
                    if (i < source.length) i++
                    tokens += Token(".\"$str\"", lineNum)
                }
                source.startsWith("s\"", i) -> {
                    i += 2
                    if (i < source.length && source[i] == ' ') i++
                    val start = i
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\n') lineNum++
                        i++
                    }
                    val str = source.substring(start, i)
                    if (i < source.length) i++
                    tokens += Token("s\"$str\"", lineNum)
                }
                else -> {
                    val start = i
                    while (i < source.length && !source[i].isWhitespace()) i++
                    tokens += Token(source.substring(start, i), lineNum)
                }
            }
        }
        return tokens
    }

    // -------------------------------------------------------------------------
    // Token execution
    // -------------------------------------------------------------------------

    /**
     * Execute tokens[start..end) — the core interpreter loop.
     * Returns the index one past the last token consumed (= end, normally).
     */
    fun execTokens(tokens: List<Token>, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val tok = tokens[i]
            val word = tok.text
            val lw = word.lowercase()
            val line = tok.line

            i = when {
                // -------- word definition --------
                lw == ":" -> {
                    if (i + 1 >= tokens.size) throw ForthError("Expected name after ':'", line)
                    val name = tokens[i + 1].text.lowercase()
                    var j = i + 2
                    val body = mutableListOf<Token>()
                    var depth = 0
                    while (j < tokens.size) {
                        val t = tokens[j]
                        when (t.text.lowercase()) {
                            ";" -> if (depth == 0) break else { depth--; body += t }
                            ":" -> { depth++; body += t }
                            else -> body += t
                        }
                        j++
                    }
                    if (j >= tokens.size) throw ForthError("Missing ';' for '$name'", line)
                    dictionary[name] = ForthWord.Compiled(name, body.toList())
                    j + 1
                }

                lw == "variable" -> {
                    if (i + 1 >= tokens.size) throw ForthError("Expected variable name", line)
                    val name = tokens[i + 1].text.lowercase()
                    if (!variables.containsKey(name)) variables[name] = 0.0
                    dictionary[name] = ForthWord.Variable(name)
                    i + 2
                }

                lw == "constant" -> {
                    if (i + 1 >= tokens.size) throw ForthError("Expected constant name", line)
                    val name = tokens[i + 1].text.lowercase()
                    dictionary[name] = ForthWord.Constant(name, pop())
                    i + 2
                }

                // -------- if/else/then --------
                lw == "if" -> {
                    val cond = anyToBool(pop())
                    execIf(tokens, i + 1, end, cond)
                }

                // -------- begin loops --------
                lw == "begin" -> execBegin(tokens, i + 1, end)

                // -------- do/loop --------
                lw == "do" -> {
                    val idx = popInt(); val limit = popInt()
                    execDo(tokens, i + 1, end, idx, limit)
                }

                // -------- leave --------
                lw == "leave" -> throw LeaveException()

                // -------- reflection bridge --------
                // @? : ( arg1...argN obj -- result? ) next-token is member name
                lw == "@?" -> {
                    if (i + 1 >= end) throw ForthError("@? expects a member name after it", tok.line)
                    val memberName = tokens[i + 1].text
                    val obj = pop()
                    reflectMember(obj, memberName)
                    i + 2
                }

                // @?! : ( obj arg1...argN n -- result? ) explicit arg count, obj pushed first
                lw == "@?!" -> {
                    if (i + 1 >= end) throw ForthError("@?! expects a member name after it", tok.line)
                    val memberName = tokens[i + 1].text
                    val n = popInt()
                    val args = (0 until n).map { pop() }.reversed()
                    val obj = pop()
                    reflectCallN(obj, memberName, args)
                    i + 2
                }

                // -------- loop index words (only valid inside do/loop) --------
                lw == "i" -> {
                    val ls = returnStack.lastOrNull() as? LoopState
                        ?: throw ForthError("'i' outside do/loop", line)
                    push(ls.index.toDouble()); i + 1
                }

                lw == "j" -> {
                    val ls = returnStack.reversed().drop(1).filterIsInstance<LoopState>().firstOrNull()
                        ?: throw ForthError("'j' outside nested do/loop", line)
                    push(ls.index.toDouble()); i + 1
                }

                // -------- string literals --------
                word.startsWith(".\"") -> {
                    onOutput(word.removePrefix(".\"").removeSuffix("\""))
                    i + 1
                }
                word.startsWith("s\"") -> {
                    push(word.removePrefix("s\"").removeSuffix("\""))
                    i + 1
                }

                // -------- number literals --------
                isNum(word) -> { push(parseNum(word)); i + 1 }

                // -------- dictionary lookup --------
                else -> {
                    val fw = dictionary[lw] ?: throw ForthError("Unknown word: '$word'", line)
                    execWord(fw)
                    i + 1
                }
            }
        }
        return i
    }

    fun execWord(fw: ForthWord) {
        when (fw) {
            is ForthWord.Builtin -> fw.impl(this)
            is ForthWord.Lambda -> fw.impl(this)
            is ForthWord.Compiled -> execTokens(fw.body, 0, fw.body.size)
            is ForthWord.Variable -> push(fw.name)
            is ForthWord.Constant -> push(fw.value)
        }
    }

    // -------------------------------------------------------------------------
    // Control flow helpers
    // -------------------------------------------------------------------------

    private fun execIf(tokens: List<Token>, start: Int, end: Int, cond: Boolean): Int {
        var depth = 0
        var elseAt = -1
        var thenAt = -1
        var j = start
        while (j < end) {
            when (tokens[j].text.lowercase()) {
                "if" -> depth++
                "else" -> if (depth == 0) elseAt = j
                "then" -> if (depth == 0) { thenAt = j; break } else depth--
            }
            j++
        }
        if (thenAt == -1) throw ForthError("Missing 'then' for 'if'")
        return if (cond) {
            execTokens(tokens, start, if (elseAt != -1) elseAt else thenAt)
            thenAt + 1
        } else {
            if (elseAt != -1) execTokens(tokens, elseAt + 1, thenAt)
            thenAt + 1
        }
    }

    private fun execBegin(tokens: List<Token>, start: Int, end: Int): Int {
        var depth = 0
        var untilAt = -1
        var whileAt = -1
        var repeatAt = -1
        var j = start
        while (j < end) {
            when (tokens[j].text.lowercase()) {
                "begin" -> depth++
                "until" -> if (depth == 0) { untilAt = j; break } else depth--
                "while" -> if (depth == 0) whileAt = j
                "repeat" -> if (depth == 0) { repeatAt = j; break } else depth--
            }
            j++
        }
        return when {
            untilAt != -1 -> {
                while (true) {
                    execTokens(tokens, start, untilAt)
                    if (anyToBool(pop())) break
                }
                untilAt + 1
            }
            whileAt != -1 && repeatAt != -1 -> {
                while (true) {
                    execTokens(tokens, start, whileAt)
                    if (!anyToBool(pop())) break
                    execTokens(tokens, whileAt + 1, repeatAt)
                }
                repeatAt + 1
            }
            else -> throw ForthError("'begin' without 'until' or 'while/repeat'")
        }
    }

    private data class LoopState(var index: Int, val limit: Int)
    private class LeaveException : Throwable()

    private fun execDo(tokens: List<Token>, start: Int, end: Int, startIdx: Int, limit: Int): Int {
        var depth = 0
        var loopAt = -1
        var plusLoopAt = -1
        var j = start
        while (j < end) {
            when (tokens[j].text.lowercase()) {
                "do" -> depth++
                "loop" -> if (depth == 0) { loopAt = j; break } else depth--
                "+loop" -> if (depth == 0) { plusLoopAt = j; break } else depth--
            }
            j++
        }
        val loopEnd = when {
            loopAt != -1 -> loopAt
            plusLoopAt != -1 -> plusLoopAt
            else -> throw ForthError("'do' without 'loop' or '+loop'")
        }
        val state = LoopState(startIdx, limit)
        returnStack.addLast(state)
        try {
            while (state.index != state.limit) {
                try {
                    execTokens(tokens, start, loopEnd)
                } catch (_: LeaveException) {
                    break
                }
                state.index += if (plusLoopAt != -1) popInt() else 1
            }
        } finally {
            returnStack.removeLastOrNull()
        }
        return loopEnd + 1
    }

    // -------------------------------------------------------------------------
    // Number helpers
    // -------------------------------------------------------------------------

    private fun isNum(s: String): Boolean {
        if (s.isEmpty()) return false
        val t = if (s[0] == '-' || s[0] == '+') s.drop(1) else s
        if (t.isEmpty()) return false
        if (t.startsWith("0x", ignoreCase = true)) return t.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return t.any { it.isDigit() } && t.all { it.isDigit() || it == '.' }
    }

    private fun parseNum(s: String): Double {
        if (s.startsWith("0x", ignoreCase = true)) return s.drop(2).toLong(16).toDouble()
        return s.toDoubleOrNull() ?: throw ForthError("Not a number: '$s'")
    }

    // -------------------------------------------------------------------------
    // Completion support
    // -------------------------------------------------------------------------

    fun complete(code: String, cursor: Int): List<ForthCompletion> {
        val pos = cursor.coerceIn(0, code.length)
        var start = pos
        while (start > 0 && !code[start - 1].isWhitespace()) start--
        val prefix = code.substring(start, pos).lowercase()
        return dictionary.keys
            .filter { it.startsWith(prefix) }
            .map { name ->
                val fw = dictionary[name]!!
                ForthCompletion(
                    word = name,
                    kind = when (fw) {
                        is ForthWord.Builtin -> "builtin"
                        is ForthWord.Compiled -> "word"
                        is ForthWord.Variable -> "variable"
                        is ForthWord.Constant -> "constant"
                        is ForthWord.Lambda -> "lambda"
                    },
                    documentation = when (fw) {
                        is ForthWord.Builtin -> fw.doc
                        is ForthWord.Compiled -> ": ${fw.name} ${fw.body.joinToString(" ") { it.text }} ;"
                        is ForthWord.Variable -> "variable ${fw.name}"
                        is ForthWord.Constant -> "constant ${fw.name} = ${fw.value}"
                        is ForthWord.Lambda -> fw.doc
                    }
                )
            }
            .sortedBy { it.word }
    }

    // -------------------------------------------------------------------------
    // Public API: Kotlin-bridged words and run-loop strands
    // -------------------------------------------------------------------------

    fun defineWord(name: String, doc: String = "", impl: ForthInterpreter.() -> Unit) {
        dictionary[name.lowercase()] = ForthWord.Lambda(name.lowercase(), doc, impl)
    }

    fun addStrand(strandName: String, wordName: String) {
        strands[strandName] = wordName.lowercase()
    }

    fun removeStrand(strandName: String) {
        strands.remove(strandName)
    }

    fun tickStrands() {
        strands.forEach { (_, wordName) ->
            val fw = dictionary[wordName]
            if (fw != null) execWord(fw)
        }
    }

    // -------------------------------------------------------------------------
    // JVM Reflection bridge
    // -------------------------------------------------------------------------

    /**
     * `@?` implementation: access a field, property, or method on [obj] by name.
     *
     * Stack convention: `( arg1 ... argN obj -- result? )` — [obj] is popped from TOS before
     * this is called; remaining stack holds args (deepest = first arg, TOS = last arg).
     *
     * - Public fields and 0-arg methods: no args consumed.
     * - Single non-vararg overload: auto-pops the required number of args.
     * - Multiple overloads: picks the one whose param count best matches the current stack depth.
     * - Static access: if [obj] is a [Class], searches static fields/methods.
     */
    fun reflectMember(obj: Any, memberName: String) {
        val klass = if (obj is Class<*>) obj else obj::class.java
        val instance = if (obj is Class<*>) null else obj
        val isStatic = obj is Class<*>

        // 1. Public fields (instance or static)
        klass.fields.firstOrNull { it.name == memberName && (isStatic == java.lang.reflect.Modifier.isStatic(it.modifiers)) }
            ?.let { field ->
                val result = field.get(instance)
                if (result != null) push(result)
                return
            }

        // 2. Methods matching name (exclude bridge/synthetic/vararg)
        val methods = klass.methods
            .filter { it.name == memberName && !it.isBridge && !it.isSynthetic && !it.isVarArgs
                && (isStatic == java.lang.reflect.Modifier.isStatic(it.modifiers)) }
            .sortedBy { it.parameterCount }

        if (methods.isNotEmpty()) {
            val method = when {
                methods.first().parameterCount == 0 -> methods.first()
                methods.size == 1 -> methods.first()
                else -> methods.firstOrNull { it.parameterCount <= stack.size } ?: methods.first()
            }
            val n = method.parameterCount
            if (n > stack.size) throw ForthError(
                "Not enough args for ${klass.simpleName}.$memberName (needs $n, have ${stack.size})")
            val args = (0 until n).map { pop() }.reversed()
            val coerced = args.zip(method.parameterTypes).map { (a, t) -> coerceArg(t, a) }
            val result = method.invoke(instance, *coerced.toTypedArray())
            if (method.returnType != Void.TYPE && result != null) push(result)
            return
        }

        // 3. Kotlin property getter: getXxx()
        klass.methods.firstOrNull { it.name == "get${memberName.replaceFirstChar { c -> c.uppercase() }}"
            && it.parameterCount == 0 }
            ?.let { getter ->
                val result = getter.invoke(instance)
                if (result != null) push(result)
                return
            }

        // 4. Declared (possibly private) fields
        generateSequence<Class<*>>(klass) { it.superclass }.forEach { c ->
            try {
                val field = c.getDeclaredField(memberName)
                field.isAccessible = true
                val result = field.get(instance)
                if (result != null) push(result)
                return
            } catch (_: NoSuchFieldException) {}
        }

        throw ForthError("No member '$memberName' on ${klass.simpleName}")
    }

    /**
     * `@?!` implementation: call a method on [obj] with an explicit argument list.
     *
     * Picks the first non-vararg overload of [memberName] with exactly [args].size params.
     * For 0-arg calls, also falls back to Kotlin property getters (`getXxx()`) and fields.
     */
    fun reflectCallN(obj: Any, memberName: String, args: List<Any>) {
        val klass = if (obj is Class<*>) obj else obj::class.java
        val instance = if (obj is Class<*>) null else obj
        val isStatic = obj is Class<*>
        val n = args.size
        val method = klass.methods.firstOrNull {
            it.name == memberName && it.parameterCount == n && !it.isVarArgs
                && (isStatic == java.lang.reflect.Modifier.isStatic(it.modifiers))
        }
        if (method != null) {
            val coerced = args.zip(method.parameterTypes).map { (a, t) -> coerceArg(t, a) }
            val result = method.invoke(instance, *coerced.toTypedArray())
            if (method.returnType != Void.TYPE && result != null) push(result)
            return
        }
        // For 0-arg: also try field access and Kotlin getter fallback (same as reflectMember)
        if (n == 0) {
            klass.fields.firstOrNull { it.name == memberName }?.let { field ->
                val result = field.get(instance); if (result != null) push(result); return
            }
            klass.methods.firstOrNull { it.name == "get${memberName.replaceFirstChar { c -> c.uppercase() }}"
                && it.parameterCount == 0 }?.let { getter ->
                val result = getter.invoke(instance); if (result != null) push(result); return
            }
            generateSequence<Class<*>>(klass) { it.superclass }.forEach { c ->
                try { val f = c.getDeclaredField(memberName); f.isAccessible = true
                    val result = f.get(instance); if (result != null) push(result); return
                } catch (_: NoSuchFieldException) {}
            }
        }
        throw ForthError("No ${if (isStatic) "static " else ""}method '${klass.simpleName}.$memberName' with $n params")
    }

    private fun coerceArg(targetType: Class<*>, value: Any): Any? = when {
        targetType == Float::class.java || targetType == java.lang.Float.TYPE ->
            (value as? Number)?.toFloat() ?: value
        targetType == Double::class.java || targetType == java.lang.Double.TYPE ->
            (value as? Number)?.toDouble() ?: value
        targetType == Int::class.java || targetType == java.lang.Integer.TYPE ->
            (value as? Number)?.toInt() ?: (value as? Boolean)?.let { if (it) 1 else 0 } ?: value
        targetType == Long::class.java || targetType == java.lang.Long.TYPE ->
            (value as? Number)?.toLong() ?: value
        targetType == Short::class.java || targetType == java.lang.Short.TYPE ->
            (value as? Number)?.toShort() ?: value
        targetType == Byte::class.java || targetType == java.lang.Byte.TYPE ->
            (value as? Number)?.toByte() ?: value
        targetType == Boolean::class.java || targetType == java.lang.Boolean.TYPE ->
            anyToBool(value)
        targetType == String::class.java -> value.toString()
        targetType.isAssignableFrom(value.javaClass) -> value
        else -> value
    }


    private fun builtin(name: String, doc: String = "", impl: ForthInterpreter.() -> Unit) {
        dictionary[name.lowercase()] = ForthWord.Builtin(name, doc, impl)
    }

    private fun installBuiltins() {
        // --- Stack ---
        builtin("dup",   "( a -- a a ) Duplicate top") { push(peek()) }
        builtin("drop",  "( a -- ) Remove top") { pop() }
        builtin("swap",  "( a b -- b a )") { val b = pop(); val a = pop(); push(b); push(a) }
        builtin("over",  "( a b -- a b a )") { val b = pop(); val a = pop(); push(a); push(b); push(a) }
        builtin("rot",   "( a b c -- b c a )") { val c = pop(); val b = pop(); val a = pop(); push(b); push(c); push(a) }
        builtin("-rot",  "( a b c -- c a b )") { val c = pop(); val b = pop(); val a = pop(); push(c); push(a); push(b) }
        builtin("nip",   "( a b -- b )") { val b = pop(); pop(); push(b) }
        builtin("tuck",  "( a b -- b a b )") { val b = pop(); val a = pop(); push(b); push(a); push(b) }
        builtin("2dup",  "( a b -- a b a b )") { val b = pop(); val a = pop(); push(a); push(b); push(a); push(b) }
        builtin("2drop", "( a b -- )") { pop(); pop() }
        builtin("2swap", "( a b c d -- c d a b )") {
            val d = pop(); val c = pop(); val b = pop(); val a = pop()
            push(c); push(d); push(a); push(b)
        }
        builtin("depth", "( -- n ) Stack depth") { push(stack.size.toDouble()) }
        builtin("clear", "( ... -- ) Clear stack") { stack.clear() }
        builtin("?dup",  "( n -- n n | 0 ) Dup if nonzero") { val v = pop(); push(v); if (anyToBool(v)) push(v) }
        builtin("pick",  "( n -- item ) Copy item at depth n to TOS (0=TOS copy=dup)") {
            val n = popInt()
            if (n < 0 || n >= stack.size) throw ForthError("pick: index $n out of range (stack depth ${stack.size})")
            push(stack[stack.size - 1 - n])
        }
        builtin("roll",  "( n -- item ) Move item at depth n to TOS") {
            val n = popInt()
            if (n < 0 || n >= stack.size) throw ForthError("roll: index $n out of range (stack depth ${stack.size})")
            val idx = stack.size - 1 - n
            val item = stack.removeAt(idx)
            push(item)
        }

        // --- Arithmetic ---
        builtin("+",      "( a b -- a+b )") { val b = popNum(); push(popNum() + b) }
        builtin("-",      "( a b -- a-b )") { val b = popNum(); push(popNum() - b) }
        builtin("*",      "( a b -- a*b )") { val b = popNum(); push(popNum() * b) }
        builtin("/",      "( a b -- a/b )") { val b = popNum(); push(popNum() / b) }
        builtin("mod",    "( a b -- a%b )") { val b = popNum(); push(popNum() % b) }
        builtin("/mod",   "( a b -- rem quot )") { val b = popNum(); val a = popNum(); push(a % b); push(floor(a / b)) }
        builtin("1+",     "( n -- n+1 )") { push(popNum() + 1) }
        builtin("1-",     "( n -- n-1 )") { push(popNum() - 1) }
        builtin("2+",     "( n -- n+2 )") { push(popNum() + 2) }
        builtin("2-",     "( n -- n-2 )") { push(popNum() - 2) }
        builtin("2*",     "( n -- n*2 )") { push(popNum() * 2) }
        builtin("2/",     "( n -- n/2 )") { push(popNum() / 2) }
        builtin("negate", "( n -- -n )") { push(-popNum()) }
        builtin("abs",    "( n -- |n| )") { push(abs(popNum())) }
        builtin("max",    "( a b -- max )") { val b = popNum(); push(max(popNum(), b)) }
        builtin("min",    "( a b -- min )") { val b = popNum(); push(min(popNum(), b)) }
        builtin("**",     "( a b -- a^b )") { val b = popNum(); push(popNum().pow(b)) }

        // --- Math ---
        builtin("sqrt",  "( n -- sqrt )") { push(sqrt(popNum())) }
        builtin("sin",   "( n -- sin )") { push(sin(popNum())) }
        builtin("cos",   "( n -- cos )") { push(cos(popNum())) }
        builtin("tan",   "( n -- tan )") { push(tan(popNum())) }
        builtin("asin",  "( n -- asin )") { push(asin(popNum())) }
        builtin("acos",  "( n -- acos )") { push(acos(popNum())) }
        builtin("atan",  "( n -- atan )") { push(atan(popNum())) }
        builtin("atan2", "( y x -- atan2 )") { val x = popNum(); push(atan2(popNum(), x)) }
        builtin("exp",   "( n -- e^n )") { push(exp(popNum())) }
        builtin("ln",    "( n -- ln )") { push(ln(popNum())) }
        builtin("log",   "( n -- log10 )") { push(log10(popNum())) }
        builtin("floor", "( n -- floor )") { push(floor(popNum())) }
        builtin("ceil",  "( n -- ceil )") { push(ceil(popNum())) }
        builtin("round", "( n -- round )") { push(round(popNum()).toDouble()) }
        builtin("pi",    "( -- pi )") { push(PI) }
        builtin("e",     "( -- e )") { push(E) }

        // --- Comparison ---
        builtin("=",  "( a b -- flag )") { val b = pop(); push(if (forthEq(pop(), b)) -1.0 else 0.0) }
        builtin("<>", "( a b -- flag )") { val b = pop(); push(if (!forthEq(pop(), b)) -1.0 else 0.0) }
        builtin("<",  "( a b -- flag )") { val b = popNum(); push(if (popNum() < b) -1.0 else 0.0) }
        builtin(">",  "( a b -- flag )") { val b = popNum(); push(if (popNum() > b) -1.0 else 0.0) }
        builtin("<=", "( a b -- flag )") { val b = popNum(); push(if (popNum() <= b) -1.0 else 0.0) }
        builtin(">=", "( a b -- flag )") { val b = popNum(); push(if (popNum() >= b) -1.0 else 0.0) }
        builtin("0=", "( n -- flag )") { push(if (popNum() == 0.0) -1.0 else 0.0) }
        builtin("0<", "( n -- flag )") { push(if (popNum() < 0.0) -1.0 else 0.0) }
        builtin("0>", "( n -- flag )") { push(if (popNum() > 0.0) -1.0 else 0.0) }

        // --- Logic ---
        builtin("and",    "( a b -- a&b )") { val b = popInt(); push((popInt() and b).toDouble()) }
        builtin("or",     "( a b -- a|b )") { val b = popInt(); push((popInt() or b).toDouble()) }
        builtin("xor",    "( a b -- a^b )") { val b = popInt(); push((popInt() xor b).toDouble()) }
        builtin("invert", "( a -- ~a )") { push(popInt().inv().toDouble()) }
        builtin("not",    "( f -- !f )") { push(if (anyToBool(pop())) 0.0 else -1.0) }
        builtin("true",   "( -- -1 )") { push(-1.0) }
        builtin("false",  "( -- 0 )") { push(0.0) }

        // --- Variables ---
        builtin("!", "( val addr -- ) Store") {
            val addr = pop().toString(); variables[addr] = pop()
        }
        builtin("@", "( addr -- val ) Fetch") {
            val addr = pop().toString()
            push(variables[addr] ?: throw ForthError("Undefined variable: $addr"))
        }
        builtin("+!", "( n addr -- ) Add to variable") {
            val addr = pop().toString()
            val n = popNum()
            variables[addr] = anyToDouble(variables[addr] ?: 0.0) + n
        }

        // --- Output ---
        builtin(".",       "( n -- ) Print number") { onOutput("${popNum()} ") }
        builtin(".s",      "( -- ) Print stack") { onOutput("<${stack.size}> ${stack.joinToString(" ")} ") }
        builtin("cr",      "( -- ) Newline") { onOutput("\n") }
        builtin("space",   "( -- ) Space") { onOutput(" ") }
        builtin("spaces",  "( n -- ) N spaces") { onOutput(" ".repeat(popInt().coerceAtLeast(0))) }
        builtin("emit",    "( c -- ) Print char") { onOutput(popInt().toChar().toString()) }
        builtin("type",    "( str -- ) Print string") { onOutput(pop().toString()) }
        builtin("print",   "( val -- ) Print") { onOutput("${pop()} ") }
        builtin("println", "( val -- ) Print+newline") { onOutput("${pop()}\n") }

        // --- Control flow placeholders (only valid inside word bodies) ---
        for (cfWord in listOf("if", "else", "then", "begin", "until", "while", "repeat", "do", "loop", "+loop", "leave")) {
            val captured = cfWord
            dictionary[captured] = ForthWord.Builtin(captured, "control flow") {
                throw ForthError("'$captured' is only valid inside a word definition")
            }
        }

        // --- Utility ---
        builtin("noop",         "( -- ) No-op") {}
        builtin("between",      "( n lo hi -- f ) True if lo<=n<=hi") {
            val hi = popNum(); val lo = popNum(); val n = popNum()
            push(if (n in lo..hi) -1.0 else 0.0)
        }
        builtin("clamp",        "( n lo hi -- n' )") {
            val hi = popNum(); val lo = popNum(); push(popNum().coerceIn(lo, hi))
        }
        builtin("lerp",         "( a b t -- lerp )") {
            val t = popNum(); val b = popNum(); val a = popNum(); push(a + (b - a) * t)
        }
        builtin("random",       "( -- n ) [0,1)") { push(Math.random()) }
        builtin("random-range", "( lo hi -- n )") { val hi = popNum(); val lo = popNum(); push(lo + Math.random() * (hi - lo)) }
        builtin("int",          "( n -- int )") { push(floor(popNum())) }
        builtin("float",        "( n -- f )") { push(popNum()) }
        builtin("str",          "( n -- str )") { push(pop().toString()) }
        builtin("str+",         "( a b -- a++b )") { val b = pop().toString(); push(pop().toString() + b) }

        // --- JVM reflection bridge (see also @? and @?! handled in execTokens) ---
        builtin("class-for", "( className -- class ) Load a JVM Class by fully-qualified name") {
            val name = pop().toString()
            push(Class.forName(name))
        }
        // Placeholder entries so @? / @?! appear in completions; real logic is in execTokens.
        builtin("@?",  "( arg1...argN obj memberName -- result? ) Reflection: field/method on obj") {
            throw ForthError("@? must be followed by a member name token")
        }
        builtin("@?!", "( obj arg1...argN n memberName -- result? ) Reflection: explicit-N method call") {
            throw ForthError("@?! must be followed by a method name token")
        }

        // --- Vec2 ---
        builtin("vec2",     "( x y -- v )") { val y = popNum().toFloat(); push(Vec2(popNum().toFloat(), y)) }
        builtin("vec2@x",   "( v -- x )") { push((pop() as Vec2).x.toDouble()) }
        builtin("vec2@y",   "( v -- y )") { push((pop() as Vec2).y.toDouble()) }
        builtin("vec2+",    "( a b -- a+b )") { val b = pop() as Vec2; push(Vec2(pop() as Vec2).add(b)) }
        builtin("vec2-",    "( a b -- a-b )") { val b = pop() as Vec2; push(Vec2(pop() as Vec2).sub(b)) }
        builtin("vec2*",    "( v s -- v*s )") { val s = popNum().toFloat(); push(Vec2(pop() as Vec2).mul(s)) }
        builtin("vec2len",  "( v -- len )") { push((pop() as Vec2).length().toDouble()) }
        builtin("vec2norm", "( v -- v )") { push(Vec2(pop() as Vec2).normalize()) }
        builtin("vec2dot",  "( a b -- dot )") { val b = pop() as Vec2; push((pop() as Vec2).dot(b).toDouble()) }

        // --- Vec3 ---
        builtin("vec3",     "( x y z -- v )") {
            val z = popNum().toFloat(); val y = popNum().toFloat(); push(Vec3(popNum().toFloat(), y, z))
        }
        builtin("vec3@x",   "( v -- x )") { push((pop() as Vec3).x.toDouble()) }
        builtin("vec3@y",   "( v -- y )") { push((pop() as Vec3).y.toDouble()) }
        builtin("vec3@z",   "( v -- z )") { push((pop() as Vec3).z.toDouble()) }
        builtin("vec3+",    "( a b -- a+b )") { val b = pop() as Vec3; push(Vec3(pop() as Vec3).add(b)) }
        builtin("vec3-",    "( a b -- a-b )") { val b = pop() as Vec3; push(Vec3(pop() as Vec3).sub(b)) }
        builtin("vec3*",    "( v s -- v*s )") { val s = popNum().toFloat(); push(Vec3(pop() as Vec3).mul(s)) }
        builtin("vec3len",  "( v -- len )") { push((pop() as Vec3).length().toDouble()) }
        builtin("vec3norm", "( v -- v )") { push(Vec3(pop() as Vec3).normalize()) }
        builtin("vec3dot",  "( a b -- dot )") { val b = pop() as Vec3; push((pop() as Vec3).dot(b).toDouble()) }
        builtin("vec3cross","( a b -- a×b )") { val b = pop() as Vec3; push(Vec3(pop() as Vec3).cross(b)) }

        // --- Vec4 ---
        builtin("vec4",   "( x y z w -- v )") {
            val w = popNum().toFloat(); val z = popNum().toFloat(); val y = popNum().toFloat()
            push(Vec4(popNum().toFloat(), y, z, w))
        }
        builtin("vec4@x", "( v -- x )") { push((pop() as Vec4).x.toDouble()) }
        builtin("vec4@y", "( v -- y )") { push((pop() as Vec4).y.toDouble()) }
        builtin("vec4@z", "( v -- z )") { push((pop() as Vec4).z.toDouble()) }
        builtin("vec4@w", "( v -- w )") { push((pop() as Vec4).w.toDouble()) }
        builtin("vec4+",  "( a b -- a+b )") { val b = pop() as Vec4; push(Vec4(pop() as Vec4).add(b)) }
        builtin("vec4*",  "( v s -- v*s )") { val s = popNum().toFloat(); push(Vec4(pop() as Vec4).mul(s)) }
        builtin("vec4len","( v -- len )") { push((pop() as Vec4).length().toDouble()) }

        // --- FLine ---
        builtin("fline",          "( -- f ) New FLine") { push(FLine()) }
        builtin("moveto",         "( f x y -- f )") {
            val y = popNum(); val x = popNum(); val f = pop() as FLine; f.moveTo(x, y); push(f)
        }
        builtin("moveto3",        "( f x y z -- f )") {
            val z = popNum(); val y = popNum(); val x = popNum(); val f = pop() as FLine; f.moveTo(x, y, z); push(f)
        }
        builtin("lineto",         "( f x y -- f )") {
            val y = popNum(); val x = popNum(); val f = pop() as FLine; f.lineTo(x, y); push(f)
        }
        builtin("lineto3",        "( f x y z -- f )") {
            val z = popNum(); val y = popNum(); val x = popNum(); val f = pop() as FLine; f.lineTo(x, y, z); push(f)
        }
        builtin("cubicto",        "( f cx1 cy1 cx2 cy2 x y -- f )") {
            val y = popNum(); val x = popNum()
            val cy2 = popNum(); val cx2 = popNum(); val cy1 = popNum(); val cx1 = popNum()
            val f = pop() as FLine; f.cubicTo(cx1, cy1, cx2, cy2, x, y); push(f)
        }
        builtin("cubicto3",       "( f cx1 cy1 cz1 cx2 cy2 cz2 x y z -- f )") {
            val z = popNum(); val y = popNum(); val x = popNum()
            val cz2 = popNum(); val cy2 = popNum(); val cx2 = popNum()
            val cz1 = popNum(); val cy1 = popNum(); val cx1 = popNum()
            val f = pop() as FLine; f.cubicTo(cx1, cy1, cz1, cx2, cy2, cz2, x, y, z); push(f)
        }
        builtin("fline-close",    "( f -- f ) Close path") {
            val f = pop() as FLine; f.close(); push(f)
        }
        builtin("fline-filled",   "( f b -- f ) Set filled") {
            val b = anyToBool(pop()); val f = pop() as FLine; f.properties["filled"] = b; push(f)
        }
        builtin("fline-stroked",  "( f b -- f ) Set stroked") {
            val b = anyToBool(pop()); val f = pop() as FLine; f.properties["stroked"] = b; push(f)
        }
        builtin("fline-color",    "( f r g b a -- f ) Set color") {
            val a = popNum().toFloat(); val b = popNum().toFloat()
            val g = popNum().toFloat(); val r = popNum().toFloat()
            val f = pop() as FLine; f.properties["color"] = Vec4(r, g, b, a); push(f)
        }
        builtin("fline-thickness","( f n -- f ) Set thickness") {
            val t = popNum(); val f = pop() as FLine; f.properties["thickness"] = t; push(f)
        }
        builtin("fline-moveto-v2","( f v2 -- f ) MoveTo vec2") {
            val v = pop() as Vec2; val f = pop() as FLine; f.moveTo(v); push(f)
        }
        builtin("fline-lineto-v2","( f v2 -- f ) LineTo vec2") {
            val v = pop() as Vec2; val f = pop() as FLine; f.lineTo(v); push(f)
        }
        builtin("fline-moveto-v3","( f v3 -- f ) MoveTo vec3") {
            val v = pop() as Vec3; val f = pop() as FLine; f.moveTo(v); push(f)
        }
        builtin("fline-lineto-v3","( f v3 -- f ) LineTo vec3") {
            val v = pop() as Vec3; val f = pop() as FLine; f.lineTo(v); push(f)
        }
        builtin("fline-node-color","( f r g b a -- f ) Color last node") {
            val a = popNum().toFloat(); val b = popNum().toFloat()
            val g = popNum().toFloat(); val r = popNum().toFloat()
            val f = pop() as FLine; f.here.properties["color"] = Vec4(r, g, b, a); push(f)
        }
    }
}

// -------------------------------------------------------------------------
// Supporting types
// -------------------------------------------------------------------------

sealed class ForthWord {
    data class Builtin(val name: String, val doc: String, val impl: ForthInterpreter.() -> Unit) : ForthWord()
    data class Compiled(val name: String, val body: List<ForthInterpreter.Token>) : ForthWord()
    data class Variable(val name: String) : ForthWord()
    data class Constant(val name: String, val value: Any) : ForthWord()
    data class Lambda(val name: String, val doc: String, val impl: ForthInterpreter.() -> Unit) : ForthWord()
}

class ForthError(message: String, val line: Int = -1) : RuntimeException(message)

data class ForthDiagnostic(val message: String, val line: Int)

data class ForthCompletion(
    val word: String,
    val kind: String,
    val documentation: String
)
