package afield.forth

import afield.classesWithPrefix
import afield.findClassesNamed
import afield.graphics.FLine
import afield.h
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import kotlin.math.*
import afield.Table
import afield.CellRole

/**
 * ForthField — a compact, extensible Forth used by Field4 for live coding.
 *
 * It is host-driven:
 * - it executes ForthField source and keeps its own data stack/dictionary
 * - it ships with creative-coding oriented builtins (vectors + [FLine])
 * - it can be extended at runtime from Kotlin via [defineWord]
 * - it can interoperate with host objects using `@?` / `@?!` reflection calls
 *
 * #### The host loop
 *
 * Typical host loop:
 * 1. Construct an interpreter once (`val forth = ForthInterpreter()`).
 * 2. Register host bridge words with [defineWord] as needed.
 * 3. Push host objects (buffers, context values, services) onto [stack].
 * 4. Run `eval(...)`.
 * 5. Pop resulting values (for example an [FLine]) and hand them to rendering/output code.
 *
 * Example: basic execution and stack access:
 * ```kotlin
 * val forth = ForthInterpreter()
 * val diagnostics = forth.eval("2 3 +")
 * check(diagnostics.isEmpty())
 * println(forth.popNum()) // 5.0
 * ```
 *
 * Example: defining words in ForthField:
 * ```forth
 * : sqr ( n -- n2 ) dup * ;
 * : hypot2 ( x y -- d2 ) sqr swap sqr + ;
 * 3 4 hypot2 .
 * ```
 *
 * Example: create an [FLine] shape in ForthField:
 * ```forth
 * : stroke-ribbon ( -- f )
 *   fline
 *   0   0   moveto
 *   120 20  lineto
 *   160 90  210 90  260 20 cubicto
 *   320 0   lineto
 *   true  fline-stroked
 *   false fline-filled
 *   1.0 0.55 0.2 1.0 fline-color
 *   2.5 fline-thickness ;
 *
 * stroke-ribbon
 * ```
 *
 * Host-side consumption of the resulting [FLine]:
 * ```kotlin
 * val forth = ForthInterpreter()
 * val source = """
 *   : stroke-ribbon ( -- f )
 *     fline
 *     0 0 moveto
 *     120 20 lineto
 *     160 90 210 90 260 20 cubicto
 *     true fline-stroked
 *     1 0.55 0.2 1 fline-color
 *     2.5 fline-thickness ;
 *   stroke-ribbon
 * """.trimIndent()
 *
 * val diagnostics = forth.eval(source)
 * if (diagnostics.isEmpty()) {
 *   val line = forth.pop() as FLine
 *   // Forward `line` into your output channel / renderer.
 * }
 * ```
 *
 * Example: use vector helpers with FLine convenience words:
 * ```forth
 * : zig ( -- f )
 *   fline
 *   0   0   vec2 fline-moveto-v2
 *   40  20  vec2 fline-lineto-v2
 *   80 -10  vec2 fline-lineto-v2
 *   120 25  vec2 fline-lineto-v2
 *   1 0 0 1 fline-node-color ;
 * zig
 * ```
 *
 * #### The reflection bridge
 *
 * Example: resolve classes without full FQNs.
 * `class-for` consults Field's class cache (`importHelp.kt`) so short names usually work.
 *
 * ```forth
 * s"FLine" class-for @? getName println
 * s"Vec3"  class-for @? getName println
 *
 * s"Fli" class-matches print   \ returns a list of FQNs
 * s"Fli" class-help            \ prints one-per-line suggestions
 * ```
 *
 * Example: reflection bridge (`@?` / `@?!`) with [FLine], built entirely by class lookup.
 * This demonstrates construction + method calls without using the `fline` helper.
 *
 * ```forth
 * variable f
 * s"FLine" class-for @? newInstance f !
 *
 * \ Explicit arity call: f.moveTo(Number, Number, Number)
 * f @ 0   0   0   3 @?! moveTo drop
 *
 * \ Explicit arity call: f.lineTo(Number, Number, Number)
 * f @ 120 30  0   3 @?! lineTo drop
 * f @ 220 10  0   3 @?! lineTo drop
 * f @ 260 80 0  320 80 0  380 20 0  9 @?! cubicTo drop
 *
 * \ Properties are just a map on FLine
 * f @ @? properties  s"thickness" 2.5 2 @?! put
 * f @ @? properties  s"stroked" true 2 @?! put
 *
 * \ Heuristic call for zero-arg methods works well with @?
 * f @ @? close drop
 *
 * \ Leave the final FLine on the stack
 * f @
 * ```
 *
 * Example: `@?` reads data too (field/getter/method fallback):
 * ```forth
 * f @ @? nodes   \ push internal node list
 * @? size .      \ call size() on that list and print node count
 * ```
 *
 * Example: constructor with explicit args via `@?! new`:
 * ```forth
 * variable sb
 * s"StringBuilder" class-for s"hello" 1 @?! new sb !
 * sb @ @? toString println
 * ```
 *
 * Example: extending ForthField with Kotlin bridge words.
 * Here we expose a helper that appends a `vec4` worth of floats into any
 * `DynamicallyWriteable` buffer (for example `VulkanBuffer` or `FrameBufferGroup`).
 *
 * ```kotlin
 * import afield.four.buffers.DynamicallyWriteable
 * import afield.stdlib.Vec4
 *
 * val forth = ForthInterpreter()
 * forth.defineWord("append-vec4", "( buf x y z w -- buf )") {
 *   val w = popNum().toFloat()
 *   val z = popNum().toFloat()
 *   val y = popNum().toFloat()
 *   val x = popNum().toFloat()
 *   val buf = pop() as DynamicallyWriteable
 *
 *   buf.append {
 *     put(Vec4(x, y, z, w))
 *   }
 *   push(buf)
 * }
 * ```
 *
 * Example: call that bridge word from ForthField after pushing a host buffer:
 * ```kotlin
 * forth.push(myDynamicBuffer) // host object: VulkanBuffer / FrameBufferGroup / similar
 * forth.eval("""
 *   0.0 1.0 0.0 1.0 append-vec4
 *   1.0 0.0 0.0 1.0 append-vec4
 *   0.0 0.0 1.0 1.0 append-vec4
 * """.trimIndent())
 * ```
 *
 * Example: long-form output areas from ForthField:
 * ```forth
 * s"<b>Inline-ish output area content</b>" html
 * s"<pre>Detailed log text...</pre>" s"debug" html-channel
 * ```
 *
 * Example: launchable animation with cooperative yielding via `frame`.
 * Run this in Launch mode (or call `begin("default")` on the box), so `spinner` executes
 * every RunLoop tick. `frame` yields immediately, letting the rest of Field proceed.
 * Store evolving state in variables, because each strand tick re-enters the word from the top.
 *
 * ```forth
 * variable tick
 * variable line
 * 0 tick !
 *
 * : spinner ( -- )
 *   s"FLine" class-for @? newInstance line !
 *
 *   \ center point
 *   line @ 0 0 0 3 @?! moveTo drop
 *
 *   \ animated endpoint (simple circular motion)
 *   line @
 *   tick @ 0.05 * dup cos 120 *          ( x )
 *              swap sin 120 *            ( x y )
 *   0 3 @?! lineTo drop
 *
 *   \ style via reflected property map
 *   line @ @? properties s"thickness" 2.0 2 @?! put
 *   line @ @? properties s"stroked" true 2 @?! put
 *   line @ s"geom" display-channel
 *
 *   \ show a debug line in output area
 *   s"tick=" tick @ str str+ s"debug" display-channel
 *
 *   1 tick +!
 *   frame
 * ;
 *
 * spinner
 * ```
 *
 * Example: same pattern with an explicit ForthField loop.
 * `frame` exits the current tick immediately; on next tick the word starts again.
 *
 * ```forth
 * variable phase
 * 0 phase !
 *
 * : pulse-loop ( -- )
 *   begin
 *     s"phase=" phase @ str str+ println
 *     1 phase +!
 *     frame
 *   again ;
 *
 * pulse-loop
 * ```
 *
 * Example: full overwrite of float data each tick, since `write` replaces the payload rather than
 * adding to it:
 * ```kotlin
 * import afield.four.buffers.DynamicallyWriteable
 *
 * forth.defineWord("write-triangle", "( buf -- buf )") {
 *   val buf = pop() as DynamicallyWriteable
 *   buf.write {
 *     // XYZW packed per vertex (3 vertices)
 *     put(-0.5f); put(-0.5f); put(0f); put(1f)
 *     put( 0.5f); put(-0.5f); put(0f); put(1f)
 *     put( 0.0f); put( 0.5f); put(0f); put(1f)
 *   }
 *   push(buf)
 * }
 * ```
 *
 * Notes for dynamic-buffer bridging:
 * - [defineWord] closures execute on the interpreter thread/context used by your host.
 * - Prefer `write { ... }` when replacing the payload; `append { ... }` when accumulating.
 * - `DynamicBuffer.put(Vec4)` follows std430-friendly alignment rules used by Field's buffer code.
 * - If you need explicit control, call `putScalar(...)` repeatedly instead of vector helpers.
 *
 * #### Builtins
 *
 * Builtins included by default:
 * - stack ops (`dup`, `swap`, `over`, `pick`, `roll`, ...)
 * - math/logic/control flow (`if/else/then`, `begin/until`, `do/loop`, ...)
 * - variables/constants (`variable`, `!`, `@`, `constant`)
 * - strings/output (`."..."`, `s"..."`, `.`, `println`, ...)
 * - dictionary tools (`words`, `see`, `forget`, `recurse`)
 * - return-stack transfer (`>r`, `r>`, `r@`)
 * - vectors (`vec2`, `vec3`, `vec4` + arithmetic helpers)
 * - [FLine] primitives (`fline`, `moveto`, `lineto`, `cubicto`, styling words)
 * - small visualization words (`html`, `watch`, `stack-display`, `bar`, `plot-stack`)
 * - frame/yield primitive (`frame`) for cooperative launch-mode animations
 * - host reflection hooks (`@?`, `@?!`, `class-for`, `class-matches`, `class-help`)
 *
 * #### Dictionary, recursion and the return stack
 *
 * Dictionary / debugging examples:
 * ```forth
 * : sqr dup * ;
 * words              \ print all currently known words
 * see sqr            \ print ": sqr dup * ;"
 * forget sqr         \ remove the user-defined word
 * ```
 *
 * Recursion example:
 * ```forth
 * : countdown ( n -- )
 *   dup 0> if
 *     dup println
 *     1- recurse
 *   else
 *     drop
 *   then ;
 * 5 countdown
 * ```
 *
 * Infinite loop example. Use `again` mainly in launched words where `frame`
 * yields back to Field each tick:
 * ```forth
 * variable tick
 * : ticker begin tick @ println 1 tick +! frame again ;
 * ticker
 * ```
 *
 * Return-stack examples. `>r` moves a temporary value away from the data stack,
 * `r@` copies it back, and `r>` restores/removes it:
 * ```forth
 * : add-twice ( a b -- a+b+b )
 *   >r        \ return stack: b ; data stack: a
 *   r@ +      \ data stack: a+b, b is still saved
 *   r> + ;    \ data stack: a+b+b
 * ```
 *
 * #### Visualization words
 *
 * Visualization examples:
 * ```forth
 * s"<b>Hello from Forth</b>" html
 * 0.72 1.0 s"progress" bar
 * 42 s"answer" watch
 * stack-display
 * 1 4 9 16 plot-stack
 * ```
 */

/**
 * The interpreter: the data stack, the dictionary, the tokenizer and `eval`.
 *
 * One instance is one ForthField environment. `eval` returns a list of [ForthDiagnostic] rather than
 * throwing, so a live-coding host can report a bad word and keep the environment; the stack and
 * the dictionary survive an `eval` that failed.
 */
class ForthInterpreter {

    data class Token(val text: String, val line: Int)
    data class CallFrame(val word: String, val line: Int)

    var currentLine: Int = -1
        private set
    var currentToken: String = ""
        private set

    // Data stack
    val stack = ArrayDeque<Any>()

    // Return stack (for do/loop)
    val returnStack = ArrayDeque<Any>()

    // Word call stack, used for diagnostics and `recurse`.
    val callStack = ArrayDeque<CallFrame>()
    private var lastErrorCallStack: List<CallFrame> = emptyList()

    // Dictionary: word name (lowercase) -> compiled word
    val dictionary = LinkedHashMap<String, ForthWord>()

    // Variables storage
    val variables = HashMap<String, Any?>()

    // Output callback
    var onOutput: (String) -> Unit = { print(it) }
    var onDisplay: (String, Any?) -> Unit = { _, _ -> }
    var onFrame: () -> Unit = { throw ForthError("'frame' is only available while a ForthField strand is running") }

    // Per-box run-loop strands: strandName -> word name to execute each frame
    val strands = HashMap<String, String>()

    /**
     * Called after each top-level step of [eval] — a word, a literal, a whole `: … ;` definition or
     * `if … then` — with the first and last source lines the step's tokens sat on (1-based, in the
     * evaluated text) and the name it defined, if it was a definition. Steps inside a word's body do
     * not count. The box records the stack here for its line notes; null costs nothing.
     */
    var onTopLevelStep: ((firstLine: Int, lastLine: Int, defined: String?) -> Unit)? = null

    init {
        installBuiltins()
    }

    // -------------------------------------------------------------------------
    // Stack helpers
    // -------------------------------------------------------------------------

    fun push(v: Any) = stack.addLast(v)

    fun pop(): Any = stack.removeLastOrNull() ?: throw ForthError("Stack underflow", currentLine)

    fun peek(): Any = stack.lastOrNull() ?: throw ForthError("Stack underflow", currentLine)

    fun popNum(): Double = anyToDouble(pop())

    fun popInt(): Int = popNum().toInt()

    fun anyToDouble(v: Any): Double = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Number -> v.toDouble()
        is Boolean -> if (v) -1.0 else 0.0
        else -> throw ForthError("Expected number, got ${v::class.simpleName}: $v", currentLine)
    }

    fun anyToBool(v: Any): Boolean = when (v) {
        is Boolean -> v
        is Int -> v != 0
        is Double -> v != 0.0
        is Float -> v != 0.0f
        else -> true
    }

    private fun stackPreview(values: Iterable<Any>, limit: Int = 8): String {
        val list = values.toList()
        val shown = list.takeLast(limit).joinToString(" ") { shortValue(it) }
        val prefix = if (list.size > limit) "... " else ""
        return "<${list.size}> $prefix$shown"
    }

    private fun returnStackPreview(): String {
        return stackPreview(returnStack.map {
            when (it) {
                is LoopState -> "loop(i=${it.index},limit=${it.limit})"
                else -> it
            }
        })
    }

    /** The data stack, bottom first, each value as a diagnostic shows it. */
    fun stackSnapshot(): List<String> = stack.map { shortValue(it) }

    private fun shortValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.take(48) + (if (v.length > 48) "..." else "") + "\""
        is Class<*> -> "Class(${v.simpleName})"
        else -> {
            val raw = v.toString()
            if (raw.length > 64) raw.take(61) + "..." else raw
        }
    }

    private fun forthEq(a: Any, b: Any): Boolean {
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }

    // -------------------------------------------------------------------------
    // Interpreter entry point
    // -------------------------------------------------------------------------

    /**
     * Evaluate a string of ForthField code.
     * Returns a list of diagnostics (empty = success).
     */
    fun eval(source: String): List<ForthDiagnostic> {
        val diagnostics = mutableListOf<ForthDiagnostic>()
        currentLine = -1
        currentToken = ""
        lastErrorCallStack = emptyList()
        try {
            val tokens = tokenize(source)
            execTokens(tokens, 0, tokens.size, topLevel = true)
        } catch (_: ForthFrameYield) {
            // Cooperative yield from `frame` inside launch/strand execution.
        } catch (e: ForthError) {
            diagnostics += diagnosticFor(e)
        } catch (e: Throwable) {
            val line = if (currentLine >= 0) currentLine else -1
            val summary = e.message ?: e.javaClass.simpleName
            diagnostics += ForthDiagnostic(
                formatDiagnosticMessage("${e.javaClass.simpleName}: $summary"),
                line
            )
        }
        return diagnostics
    }

    fun diagnosticFor(e: ForthError): ForthDiagnostic {
        val line = when {
            e.line >= 0 -> e.line
            currentLine >= 0 -> currentLine
            else -> -1
        }
        return ForthDiagnostic(formatDiagnosticMessage(e.message ?: "Unknown error"), line)
    }

    private fun formatDiagnosticMessage(message: String): String {
        val pieces = mutableListOf(message)
        if (currentToken.isNotBlank()) pieces += "while reading '$currentToken'"
        if (stack.isNotEmpty()) pieces += "data stack: ${stackPreview(stack)}"
        if (returnStack.isNotEmpty()) pieces += "return stack: ${returnStackPreview()}"
        val diagnosticStack = if (callStack.isNotEmpty()) callStack.toList() else lastErrorCallStack
        if (diagnosticStack.isNotEmpty()) {
            pieces += "call stack: " + diagnosticStack
                .asReversed()
                .joinToString(" -> ") { frame ->
                    if (frame.line >= 0) "${frame.word}@${frame.line}" else frame.word
                }
        }
        return pieces.joinToString("\n")
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
    fun execTokens(tokens: List<Token>, start: Int, end: Int, topLevel: Boolean = false): Int {
        var i = start
        while (i < end) {
            val tok = tokens[i]
            val word = tok.text
            val lw = word.lowercase()
            val line = tok.line
            currentLine = line
            currentToken = word

            val stepStart = i
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
                    dictionary[name] = ForthWord.Compiled(name, body.toList(), line)
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
                    reflectMember(obj, memberName, tok.line)
                    i + 2
                }

                // @?! : ( obj arg1...argN n -- result? ) explicit arg count, obj pushed first
                lw == "@?!" -> {
                    if (i + 1 >= end) throw ForthError("@?! expects a member name after it", tok.line)
                    val memberName = tokens[i + 1].text
                    val n = popInt()
                    val args = (0 until n).map { pop() }.reversed()
                    val obj = pop()
                    reflectCallN(obj, memberName, args, tok.line)
                    i + 2
                }

                // -------- dictionary tools --------
                lw == "see" -> {
                    if (i + 1 >= end) throw ForthError("see expects a word name after it", tok.line)
                    onOutput(describeWord(tokens[i + 1].text.lowercase()) + "\n")
                    i + 2
                }

                lw == "forget" -> {
                    if (i + 1 >= end) throw ForthError("forget expects a word name after it", tok.line)
                    forgetWord(tokens[i + 1].text.lowercase(), tok.line)
                    i + 2
                }

                lw == "recurse" -> {
                    val frame = callStack.lastOrNull()
                        ?: throw ForthError("'recurse' outside a word definition", tok.line)
                    val fw = dictionary[frame.word]
                        ?: throw ForthError("'recurse' cannot find current word '${frame.word}'", tok.line)
                    execWord(fw)
                    i + 1
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
            if (topLevel) onTopLevelStep?.let { step ->
                val defined = if (lw == ":" && stepStart + 1 < tokens.size) tokens[stepStart + 1].text.lowercase()
                    else if ((lw == "variable" || lw == "constant") && stepStart + 1 < tokens.size) tokens[stepStart + 1].text.lowercase()
                    else null
                step(line, tokens[minOf(i, tokens.size) - 1].line, defined)
            }
        }
        return i
    }

    fun execWord(fw: ForthWord) {
        when (fw) {
            is ForthWord.Builtin -> fw.impl(this)
            is ForthWord.Lambda -> fw.impl(this)
            is ForthWord.Compiled -> {
                callStack.addLast(CallFrame(fw.name, fw.sourceLine))
                try {
                    execTokens(fw.body, 0, fw.body.size)
                } catch (t: Throwable) {
                    if (t !is ForthFrameYield && callStack.size >= lastErrorCallStack.size) {
                        lastErrorCallStack = callStack.toList()
                    }
                    throw t
                } finally {
                    callStack.removeLastOrNull()
                }
            }
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
        var againAt = -1
        var j = start
        while (j < end) {
            when (tokens[j].text.lowercase()) {
                "begin" -> depth++
                "until" -> if (depth == 0) { untilAt = j; break } else depth--
                "while" -> if (depth == 0) whileAt = j
                "repeat" -> if (depth == 0) { repeatAt = j; break } else depth--
                "again" -> if (depth == 0) { againAt = j; break } else depth--
            }
            j++
        }
        return when {
            untilAt != -1 -> {
                while (true) {
                    try {
                        execTokens(tokens, start, untilAt)
                    } catch (_: LeaveException) {
                        break
                    }
                    if (anyToBool(pop())) break
                }
                untilAt + 1
            }
            whileAt != -1 && repeatAt != -1 -> {
                while (true) {
                    try {
                        execTokens(tokens, start, whileAt)
                    } catch (_: LeaveException) {
                        break
                    }
                    if (!anyToBool(pop())) break
                    try {
                        execTokens(tokens, whileAt + 1, repeatAt)
                    } catch (_: LeaveException) {
                        break
                    }
                }
                repeatAt + 1
            }
            againAt != -1 -> {
                while (true) {
                    try {
                        execTokens(tokens, start, againAt)
                    } catch (_: LeaveException) {
                        break
                    }
                }
                againAt + 1
            }
            else -> throw ForthError("'begin' without 'until', 'again', or 'while/repeat'")
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

    private fun classNameVariants(raw: String): List<String> {
        val q = raw.trim()
        if (q.isEmpty()) return emptyList()
        val cased = q.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
        return listOf(q, cased).distinct()
    }

    private fun classMatches(raw: String, limit: Int = 24): List<String> {
        val variants = classNameVariants(raw)
        if (variants.isEmpty()) return emptyList()

        val exact = variants
            .flatMap { findClassesNamed(it).orEmpty() }
            .distinct()

        val prefix = variants
            .flatMap { classesWithPrefix(it).map { pair -> pair.second } }
            .distinct()

        return (exact + prefix)
            .distinct()
            .sorted()
            .take(limit.coerceAtLeast(1))
    }

    private fun resolveClassByName(raw: String): Class<*> {
        val variants = classNameVariants(raw)
        if (variants.isEmpty()) throw ForthError("class-for: expected a class name")

        // First allow direct FQNs (or direct binary names) as-is.
        variants.forEach { candidate ->
            try {
                return Class.forName(candidate)
            } catch (_: Throwable) {
            }
        }

        // Then resolve short/simple names from importHelp cache.
        val exactClasses = variants
            .flatMap { findClassesNamed(it).orEmpty() }
            .distinct()
            .mapNotNull { fqn ->
                try {
                    Class.forName(fqn)
                } catch (_: Throwable) {
                    null
                }
            }
            .distinctBy { it.name }

        if (exactClasses.size == 1) return exactClasses.first()
        if (exactClasses.size > 1) {
            val matches = exactClasses.map { it.name }.sorted()
            val shown = matches.take(8).joinToString(", ")
            val suffix = if (matches.size > 8) ", ..." else ""
            throw ForthError("class-for: '${variants.first()}' is ambiguous; use FQN ($shown$suffix)")
        }

        val suggestions = classMatches(variants.first(), limit = 8)
        if (suggestions.isNotEmpty()) {
            throw ForthError("class-for: no exact class '${variants.first()}'. Try: ${suggestions.joinToString(", ")}")
        }

        throw ForthError("class-for: no class '${variants.first()}'")
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

    fun describeWord(name: String): String {
        val key = name.lowercase()
        val fw = dictionary[key] ?: throw ForthError("see: unknown word '$name'")
        return when (fw) {
            is ForthWord.Builtin -> "${fw.name} ${fw.doc}".trim()
            is ForthWord.Compiled -> ": ${fw.name} ${fw.body.joinToString(" ") { it.text }} ;"
            is ForthWord.Variable -> "variable ${fw.name} = ${variables[fw.name]}"
            is ForthWord.Constant -> "constant ${fw.name} = ${fw.value}"
            is ForthWord.Lambda -> "${fw.name} ${fw.doc}".trim()
        }
    }

    fun forgetWord(name: String, line: Int = -1) {
        val key = name.lowercase()
        val fw = dictionary[key] ?: throw ForthError("forget: unknown word '$name'", line)
        if (fw is ForthWord.Builtin || fw is ForthWord.Lambda) {
            throw ForthError("forget: '$name' is host-defined and cannot be forgotten", line)
        }
        dictionary.remove(key)
        if (fw is ForthWord.Variable) variables.remove(fw.name)
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
    fun reflectMember(obj: Any, memberName: String, line: Int = -1) {
        if (obj is Class<*> && (memberName == "new" || memberName == "newInstance")) {
            constructClass(obj, emptyList(), line)
            return
        }
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
                "Not enough args for ${klass.simpleName}.$memberName (needs $n, have ${stack.size})", line)
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

        throw ForthError("No member '$memberName' on ${klass.simpleName}", line)
    }

    /**
     * `@?!` implementation: call a method on [obj] with an explicit argument list.
     *
     * Picks the first non-vararg overload of [memberName] with exactly [args].size params.
     * For 0-arg calls, also falls back to Kotlin property getters (`getXxx()`) and fields.
     */
    fun reflectCallN(obj: Any, memberName: String, args: List<Any>, line: Int = -1) {
        if (obj is Class<*> && (memberName == "new" || memberName == "newInstance")) {
            constructClass(obj, args, line)
            return
        }
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
        throw ForthError(
            "No ${if (isStatic) "static " else ""}method '${klass.simpleName}.$memberName' with $n params",
            line
        )
    }

    private fun constructClass(klass: Class<*>, args: List<Any>, line: Int = -1) {
        val ctors = klass.constructors
            .filter { it.parameterCount == args.size }
            .sortedBy { it.parameterCount }
        if (ctors.isEmpty()) throw ForthError(
            "No constructor '${klass.simpleName}(${args.size} args)'",
            line
        )

        var lastFailure: Throwable? = null
        for (ctor in ctors) {
            val coerced = args.zip(ctor.parameterTypes).map { (a, t) -> coerceArg(t, a) }.toTypedArray()
            try {
                val result = ctor.newInstance(*coerced)
                if (result != null) push(result)
                return
            } catch (t: Throwable) {
                lastFailure = t
            }
        }

        val details = lastFailure?.cause?.message ?: lastFailure?.message
        throw ForthError(
            "Could not construct '${klass.simpleName}' with ${args.size} args" + (details?.let { ": $it" } ?: ""),
            line
        )
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

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    // `depth` is an Int, so Table right-aligns it without being told; the cells are plain text, so
    // Table escapes them and the local escapeHtml is not needed here any more.
    private fun stackHtml(): String = Table(listOf("depth", "value", "type"))
        .rows(
            stack.toList().mapIndexed { index, value ->
                listOf(stack.size - index - 1, shortValue(value), value::class.simpleName ?: "value")
            }.asReversed()
        )
        .role("type", CellRole.MUTED)
        .toHTML()

    private fun barHtml(value: Double, max: Double): String {
        val denom = if (max == 0.0) 1.0 else max
        val pct = (value / denom).coerceIn(0.0, 1.0)
        val label = "${round(pct * 1000.0) / 10.0}%"
        return "<div style='min-width:220px;padding:6px 0;font:12px/1.3 sans-serif'>" +
            "<div style='height:10px;background:#222;border-radius:2px;overflow:hidden'>" +
            "<div style='height:100%;width:${pct * 100.0}%;background:#4da3ff'></div></div>" +
            "<div style='margin-top:4px;color:#777'>${escapeHtml(label)} (${escapeHtml(value.toString())}/${escapeHtml(max.toString())})</div></div>"
    }

    private fun plotStackHtml(): String {
        val nums = stack.toList().mapNotNull { (it as? Number)?.toDouble() }
        if (nums.isEmpty()) return "<div style='font:12px sans-serif;color:#777'>plot-stack: no numeric values on stack</div>"
        val maxAbs = nums.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
        val bars = nums.takeLast(64).joinToString("") { n ->
            val h = (abs(n) / maxAbs * 80.0).coerceIn(1.0, 80.0)
            val color = if (n >= 0.0) "#4da3ff" else "#ff6b6b"
            "<div title='${escapeHtml(n.toString())}' style='width:8px;height:${h}px;background:$color'></div>"
        }
        return "<div style='display:flex;align-items:flex-end;gap:2px;height:88px;padding:4px 0'>$bars</div>"
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

        // --- Return stack ---
        builtin(">r", "( x -- ) Move x from data stack to return stack") { returnStack.addLast(pop()) }
        builtin("r>", "( -- x ) Move x from return stack to data stack") {
            val v = returnStack.removeLastOrNull() ?: throw ForthError("Return stack underflow")
            if (v is LoopState) {
                returnStack.addLast(v)
                throw ForthError("r>: top of return stack is loop state; this would corrupt do/loop")
            }
            push(v)
        }
        builtin("r@", "( -- x ) Copy top of return stack to data stack") {
            val v = returnStack.lastOrNull() ?: throw ForthError("Return stack underflow")
            if (v is LoopState) throw ForthError("r@: top of return stack is loop state")
            push(v)
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
        builtin("round", "( n -- round )") { push(round(popNum())) }
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
        builtin(".s",      "( -- ) Print stack") { onOutput("${stack.h}") }
        builtin("cr",      "( -- ) Newline") { onOutput("\n") }
        builtin("space",   "( -- ) Space") { onOutput(" ") }
        builtin("spaces",  "( n -- ) N spaces") { onOutput(" ".repeat(popInt().coerceAtLeast(0))) }
        builtin("emit",    "( c -- ) Print char") { onOutput(popInt().toChar().toString()) }
        builtin("type",    "( str -- ) Print string") { onOutput(pop().toString()) }
        builtin("print",   "( val -- ) Print") { onOutput("${pop().h} ") }
        builtin("println", "( val -- ) Print+newline") { onOutput("${pop().h}\n") }
        builtin("display", "( val -- ) Send to output area channel 'output'") { onDisplay("output", pop().h) }
        builtin("display-channel", "( val channel -- ) Send to output area channel") {
            val channel = pop().toString()
            val value = pop()
            onDisplay(channel, value.h)
        }

        // --- Dictionary / introspection ---
        builtin("words", "( -- ) Print all known dictionary words") {
            onOutput(dictionary.keys.sorted().joinToString(" ") + "\n")
        }
        builtin("word-list", "( -- words ) Push all known dictionary words as a list") {
            push(dictionary.keys.sorted())
        }
        builtin("see-word", "( name -- source ) Push a printable definition for name") {
            push(describeWord(pop().toString()))
        }
        builtin("forget-word", "( name -- ) Remove a user-defined word, variable, or constant") {
            forgetWord(pop().toString(), currentLine)
        }
        builtin("see", "( -- ) Immediate-style: `see name` prints a word definition") {
            throw ForthError("see must be followed by a word name")
        }
        builtin("forget", "( -- ) Immediate-style: `forget name` removes a user-defined word") {
            throw ForthError("forget must be followed by a word name")
        }
        builtin("recurse", "( ... -- ... ) Re-run the current word") {
            throw ForthError("'recurse' is only valid while a word is executing")
        }

        // --- Small Field-native visualization layer ---
        builtin("html", "( html -- ) Display raw HTML in channel 'output'") {
            onDisplay("output", pop().toString())
        }
        builtin("html-channel", "( html channel -- ) Display raw HTML in channel") {
            val channel = pop().toString()
            onDisplay(channel, pop().toString())
        }
        builtin("watch", "( value name -- ) Display a named value in its own channel") {
            val name = pop().toString()
            val value = pop()
            onDisplay(name, "<div style='font:12px/1.4 monospace'><b>${escapeHtml(name)}</b> = ${escapeHtml(shortValue(value))}</div>")
        }
        builtin("stack-display", "( -- ) Display the current data stack as an HTML table") {
            onDisplay("stack", stackHtml())
        }
        builtin("bar", "( value max channel -- ) Display value/max as a progress bar") {
            val channel = pop().toString()
            val max = popNum()
            val value = popNum()
            onDisplay(channel, barHtml(value, max))
        }
        builtin("plot-stack", "( n1 n2 ... -- ) Display numeric stack values as simple bars") {
            onDisplay("plot", plotStackHtml())
        }

        // --- Control flow placeholders (only valid inside word bodies) ---
        for (cfWord in listOf("if", "else", "then", "begin", "until", "while", "repeat", "again", "do", "loop", "+loop", "leave")) {
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
        builtin("frame",        "( -- ) Yield until the next RunLoop tick (launch mode only)") { onFrame() }

        // --- JVM reflection bridge (see also @? and @?! handled in execTokens) ---
        builtin("class-for", "( className -- class ) Resolve a JVM Class (FQN or simple name via importHelp cache)") {
            val name = pop().toString()
            push(resolveClassByName(name))
        }
        builtin("class-matches", "( prefix -- matches ) List FQNs matching a class-name prefix") {
            push(classMatches(pop().toString(), limit = 64))
        }
        builtin("class-help", "( prefix -- ) Print matching class FQNs, one per line") {
            val prefix = pop().toString()
            val matches = classMatches(prefix, limit = 24)
            if (matches.isEmpty()) {
                onOutput("(no class matches for '$prefix')\n")
            } else {
                onOutput(matches.joinToString(separator = "\n", postfix = "\n"))
            }
        }
        // Placeholder entries so @? / @?! appear in completions; real logic is in execTokens.
        builtin("@?",  "( arg1...argN obj -- result? ) Reflection: next token is member name") {
            throw ForthError("@? must be followed by a member name token")
        }
        builtin("@?!", "( obj arg1...argN n -- result? ) Reflection: next token is member name, explicit arity") {
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
    data class Compiled(val name: String, val body: List<ForthInterpreter.Token>, val sourceLine: Int = -1) : ForthWord()
    data class Variable(val name: String) : ForthWord()
    data class Constant(val name: String, val value: Any) : ForthWord()
    data class Lambda(val name: String, val doc: String, val impl: ForthInterpreter.() -> Unit) : ForthWord()
}

class ForthError(message: String, val line: Int = -1) : RuntimeException(message)
class ForthFrameYield : Throwable()

data class ForthDiagnostic(val message: String, val line: Int)

data class ForthCompletion(
    val word: String,
    val kind: String,
    val documentation: String
)
