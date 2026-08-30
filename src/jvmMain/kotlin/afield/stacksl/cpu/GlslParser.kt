package afield.stacksl.cpu

// ---------------------------------------------------------------------------------------------
// A parser for the GLSL that StackSL's emitter actually produces.
//
// WHY A PARSER AND NOT A SECOND BACKEND IN THE COMPILER.
//
// `StackSLCompiler` has no symbolic node to hang a CPU arm off: its one internal node is
// `StackValue(id, expression: String, …)` and `expression` is already GLSL text, built by
// `String`-returning lambdas (`StackSL.kt:399-406`, `2024-2035`). Adding an `eval:` beside every
// `glsl:` — the RectSL `TrackOp` shape — would mean rewriting the emitter word by word, and every
// one of those rewrites is a chance to make the CPU arm disagree with the GPU arm. That
// disagreement is the exact defect this backend exists to rule out: a hand-written CPU twin that
// contradicted its own published documentation.
//
// So instead: parse the emitter's output. The CPU backend then consumes *the same text the GPU
// consumes*, byte for byte, and cannot drift from the shipping emitter by construction. It also
// needs zero edits to `StackSL.kt`, so it cannot regress the GPU path at all.
//
// WHAT THE SUBSET IS, MEASURED RATHER THAN GUESSED.
//
// Dumped for all 31 committed gallery programs (1472 lines of emitted GLSL), the entire surface
// is: scalar/vector/matrix declarations, `=`/`+=`/`-=`/`*=`/`/=`, `if`, `for (int i = …; …; i++)`,
// `return`, function definitions, and about 30 builtins. **No ternary, no `while`, no `switch`,
// no structs, no arrays, no bit operators.** This parser covers a little more than that (ternary,
// `while`, `do`, `break`/`continue`, interface blocks) so the first shader that reaches slightly
// further does not fail; anything genuinely outside throws [GlslUnsupported] with the token
// quoted rather than guessing.
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// AST
// ---------------------------------------------------------------------------------------------

internal sealed interface GExpr

internal class GLiteral(val value: GlslValue) : GExpr
internal class GVarRef(val name: String) : GExpr
internal class GField(val target: GExpr, val field: String) : GExpr
internal class GIndex(val target: GExpr, val index: GExpr) : GExpr
internal class GCall(val name: String, val args: List<GExpr>) : GExpr
internal class GConstruct(val type: GlslType, val args: List<GExpr>) : GExpr
internal class GUnary(val op: String, val operand: GExpr) : GExpr
internal class GBinary(val op: String, val left: GExpr, val right: GExpr) : GExpr
internal class GTernary(val cond: GExpr, val yes: GExpr, val no: GExpr) : GExpr

/** `x = e`, `x += e`, … as an expression, because GLSL allows assignment inside a `for` step. */
internal class GAssign(val target: GExpr, val op: String, val value: GExpr) : GExpr

/** `i++` / `i--`, postfix. The `for` steps the emitter writes are all of this shape. */
internal class GPostfix(val target: GExpr, val op: String) : GExpr

internal sealed interface GStmt

internal class GBlock(val statements: List<GStmt>) : GStmt
internal class GDeclare(val type: GlslType, val declarators: List<Pair<String, GExpr?>>) : GStmt
internal class GExprStmt(val expr: GExpr) : GStmt
internal class GIf(val cond: GExpr, val then: GStmt, val otherwise: GStmt?) : GStmt
internal class GFor(val init: GStmt?, val cond: GExpr?, val step: GExpr?, val body: GStmt) : GStmt
internal class GWhile(val cond: GExpr, val body: GStmt, val isDoWhile: Boolean = false) : GStmt
internal class GReturn(val value: GExpr?) : GStmt
internal object GBreak : GStmt
internal object GContinue : GStmt
internal object GDiscard : GStmt
internal object GNoop : GStmt

/** One function definition: its return type, parameters (by value) and body. */
internal class GFunction(
    val name: String,
    val returnType: GlslType,
    val params: List<Pair<GlslType, String>>,
    val body: GBlock
)

/**
 * A parsed fragment shader.
 *
 * [globals] are the shader's interface — `in`/`out`/`uniform` variables, in declaration order,
 * with samplers separated out into [samplers] because they are bound differently. [blocks] holds
 * `layout(push_constant) uniform Push { vec4 params; } pc;` style interface blocks, as
 * instance-name to member list; the interpreter turns those into a flat `pc.params` binding.
 */
class GlslShader internal constructor(
    internal val functions: Map<String, GFunction>,
    internal val globals: List<GlslGlobal>,
    internal val samplers: List<String>,
    internal val blocks: List<GlslBlock>,
    /** Preprocessor lines seen and skipped, for reporting; `#version` is the only expected one. */
    val preprocessorLines: List<String>
) {
    /** Names of every function this shader defines — the emitter's helpers plus `main`. */
    val functionNames: Set<String> get() = functions.keys

    /** Names of `in`/`out`/`uniform` variables, samplers included. */
    val globalNames: List<String> get() = globals.map { it.name } + samplers
}

internal class GlslGlobal(
    val name: String,
    val type: GlslType,
    val storage: String,
    val initializer: GExpr?
)

internal class GlslBlock(val instance: String, val members: List<Pair<GlslType, String>>)

// ---------------------------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------------------------

private class GToken(val text: String, val kind: Kind, val line: Int) {
    enum class Kind { IDENT, NUMBER, PUNCT, END }
    override fun toString() = "'$text' (line $line)"
}

private val multiCharOperators = listOf(
    "<<=", ">>=", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "^^",
    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^="
)

private fun lex(source: String): Pair<List<GToken>, List<String>> {
    val tokens = ArrayList<GToken>()
    val preprocessor = ArrayList<String>()
    var i = 0
    var line = 1
    val n = source.length
    var atLineStart = true
    while (i < n) {
        val ch = source[i]
        when {
            ch == '\n' -> { line++; i++; atLineStart = true }
            ch == ' ' || ch == '\t' || ch == '\r' -> i++
            ch == '/' && i + 1 < n && source[i + 1] == '/' -> {
                while (i < n && source[i] != '\n') i++
            }
            ch == '/' && i + 1 < n && source[i + 1] == '*' -> {
                i += 2
                while (i + 1 < n && !(source[i] == '*' && source[i + 1] == '/')) {
                    if (source[i] == '\n') line++
                    i++
                }
                i += 2
            }
            // A preprocessor directive occupies its whole line. `#version` is the only one the
            // harness templates carry; anything else is recorded and skipped rather than obeyed,
            // because obeying `#define` would mean being a preprocessor as well as an evaluator.
            ch == '#' && atLineStart -> {
                val start = i
                while (i < n && source[i] != '\n') i++
                preprocessor += source.substring(start, i).trim()
            }
            ch.isDigit() || (ch == '.' && i + 1 < n && source[i + 1].isDigit()) -> {
                val start = i
                while (i < n && (source[i].isDigit() || source[i] == '.')) i++
                if (i < n && (source[i] == 'e' || source[i] == 'E')) {
                    i++
                    if (i < n && (source[i] == '+' || source[i] == '-')) i++
                    while (i < n && source[i].isDigit()) i++
                }
                if (i < n && (source[i] == 'f' || source[i] == 'F' || source[i] == 'u' || source[i] == 'U')) i++
                tokens += GToken(source.substring(start, i), GToken.Kind.NUMBER, line)
                atLineStart = false
            }
            ch.isLetter() || ch == '_' -> {
                val start = i
                while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                // Interned, so that the interpreter's variable lookup — a backwards linear scan
                // comparing names — hits `String.equals`'s reference-identity fast path instead
                // of comparing characters. A shader has a few dozen distinct identifiers and its
                // names are compared once per variable reference per pixel.
                tokens += GToken(source.substring(start, i).intern(), GToken.Kind.IDENT, line)
                atLineStart = false
            }
            else -> {
                val op = multiCharOperators.firstOrNull { source.startsWith(it, i) }
                if (op != null) {
                    tokens += GToken(op, GToken.Kind.PUNCT, line)
                    i += op.length
                } else {
                    tokens += GToken(ch.toString(), GToken.Kind.PUNCT, line)
                    i++
                }
                atLineStart = false
            }
        }
    }
    tokens += GToken("", GToken.Kind.END, line)
    return tokens to preprocessor
}

// ---------------------------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------------------------

// Deliberately does NOT include `sample` or `patch`: those are tessellation/geometry qualifiers
// that no StackSL shader uses, and treating them as qualifiers would make a *variable* named
// `sample` — a plausible name in a language whose sampling word is `sample` — get silently eaten.
private val storageQualifiers = setOf(
    "in", "out", "inout", "uniform", "buffer", "const",
    "flat", "smooth", "noperspective", "centroid",
    "highp", "mediump", "lowp", "precise", "invariant"
)

private val samplerTypes = setOf(
    "sampler1D", "sampler2D", "sampler3D", "samplerCube", "sampler2DArray",
    "sampler2DShadow", "isampler2D", "usampler2D", "samplerBuffer"
)

/**
 * Parse a whole fragment shader.
 *
 * The caller hands over exactly the text a GPU would be given — `#version`, `layout(...)`
 * declarations, helper functions and `main()` — because the whole point is that the two backends
 * read one string. `#version` and any other directive is recorded on
 * [GlslShader.preprocessorLines] and otherwise ignored.
 */
fun parseGlslShader(source: String): GlslShader = GlslShaderParser(source).parseShader()

private class GlslShaderParser(source: String) {
    private val tokens: List<GToken>
    private val preprocessor: List<String>
    private var pos = 0

    init {
        val (t, p) = lex(source)
        tokens = t
        preprocessor = p
    }

    private fun peek(offset: Int = 0) = tokens[(pos + offset).coerceIn(0, tokens.size - 1)]
    private fun at(text: String) = peek().text == text
    private fun atEnd() = peek().kind == GToken.Kind.END
    private fun next() = tokens[pos++]

    private fun expect(text: String): GToken {
        if (!at(text)) fail("expected '$text', found ${peek()}")
        return next()
    }

    private fun accept(text: String): Boolean {
        if (at(text)) { pos++; return true }
        return false
    }

    private fun fail(message: String): Nothing = throw GlslEvalError("GLSL parse: $message")

    fun parseShader(): GlslShader {
        val functions = LinkedHashMap<String, GFunction>()
        val globals = ArrayList<GlslGlobal>()
        val samplers = ArrayList<String>()
        val blocks = ArrayList<GlslBlock>()

        while (!atEnd()) {
            if (accept(";")) continue
            if (at("precision")) { while (!atEnd() && !accept(";")) next(); continue }
            if (at("struct")) throw GlslUnsupported("GLSL `struct` is not in the StackSL subset")

            // A `layout(...)` prefix carries binding numbers and `push_constant`; none of it
            // changes what the CPU has to compute, so it is consumed and dropped.
            if (at("layout")) {
                next()
                skipParens()
            }

            val qualifiers = ArrayList<String>()
            while (peek().text in storageQualifiers) qualifiers += next().text

            // An interface block: `uniform Push { vec4 params; } pc;`
            if (peek().kind == GToken.Kind.IDENT && peek(1).text == "{" && GlslType.byName(peek().text) == null) {
                next() // block name
                expect("{")
                val members = ArrayList<Pair<GlslType, String>>()
                while (!at("}")) {
                    while (peek().text in storageQualifiers) next()
                    val t = GlslType.byName(next().text) ?: fail("block member type at ${peek()}")
                    do {
                        members += t to next().text
                    } while (accept(","))
                    expect(";")
                }
                expect("}")
                val instance = if (peek().kind == GToken.Kind.IDENT) next().text else ""
                expect(";")
                blocks += GlslBlock(instance, members)
                continue
            }

            val typeName = peek().text
            if (typeName in samplerTypes) {
                next()
                samplers += next().text
                expect(";")
                continue
            }

            val type = if (typeName == "void") { next(); null } else {
                GlslType.byName(next().text) ?: fail("unknown type '$typeName' at ${peek(-1)}")
            }

            val name = next().let { if (it.kind != GToken.Kind.IDENT) fail("expected a name, found $it") else it.text }

            if (at("(")) {
                val params = parseParameters()
                if (accept(";")) continue // a prototype; the definition follows
                val body = parseBlock()
                functions[name] = GFunction(name, type ?: GlslType.FLOAT, params, body)
                continue
            }

            // A global variable, possibly several to a declaration.
            var currentName = name
            while (true) {
                if (at("[")) throw GlslUnsupported("GLSL arrays are not in the StackSL subset")
                val init = if (accept("=")) parseAssignment() else null
                globals += GlslGlobal(currentName, type ?: fail("void variable '$currentName'"), qualifiers.joinToString(" "), init)
                if (!accept(",")) break
                currentName = next().text
            }
            expect(";")
        }

        return GlslShader(functions, globals, samplers, blocks, preprocessor)
    }

    /** Consume a balanced `( … )` and return its text, for `layout(...)` whose contents are flags. */
    private fun skipParens(): String {
        expect("(")
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0 && !atEnd()) {
            val t = next()
            if (t.text == "(") depth++
            if (t.text == ")") { depth--; if (depth == 0) break }
            sb.append(t.text).append(' ')
        }
        return sb.toString()
    }

    private fun parseParameters(): List<Pair<GlslType, String>> {
        expect("(")
        val params = ArrayList<Pair<GlslType, String>>()
        if (accept(")")) return params
        if (at("void") && peek(1).text == ")") { next(); next(); return params }
        while (true) {
            while (peek().text in storageQualifiers) next()
            val t = GlslType.byName(next().text) ?: fail("parameter type at ${peek(-1)}")
            val n = next().text
            params += t to n
            if (!accept(",")) break
        }
        expect(")")
        return params
    }

    // -- statements ----------------------------------------------------------------------------

    private fun parseBlock(): GBlock {
        expect("{")
        val statements = ArrayList<GStmt>()
        while (!at("}")) {
            if (atEnd()) fail("unterminated block")
            statements += parseStatement()
        }
        expect("}")
        return GBlock(statements)
    }

    private fun parseStatement(): GStmt {
        if (at("{")) return parseBlock()
        if (accept(";")) return GNoop
        when (peek().text) {
            "if" -> {
                next(); expect("(")
                val cond = parseExpression()
                expect(")")
                val then = parseStatement()
                val otherwise = if (accept("else")) parseStatement() else null
                return GIf(cond, then, otherwise)
            }

            "for" -> {
                next(); expect("(")
                val init = if (accept(";")) null else parseSimpleStatement().also { expect(";") }
                val cond = if (at(";")) null else parseExpression()
                expect(";")
                val step = if (at(")")) null else parseExpression()
                expect(")")
                return GFor(init, cond, step, parseStatement())
            }

            "while" -> {
                next(); expect("(")
                val cond = parseExpression()
                expect(")")
                return GWhile(cond, parseStatement())
            }

            "do" -> {
                next()
                val body = parseStatement()
                expect("while"); expect("(")
                val cond = parseExpression()
                expect(")"); expect(";")
                return GWhile(cond, body, isDoWhile = true)
            }

            "return" -> {
                next()
                val v = if (at(";")) null else parseExpression()
                expect(";")
                return GReturn(v)
            }

            "break" -> { next(); expect(";"); return GBreak }
            "continue" -> { next(); expect(";"); return GContinue }
            "discard" -> { next(); expect(";"); return GDiscard }
            "switch" -> throw GlslUnsupported("GLSL `switch` is not in the StackSL subset")
        }
        val s = parseSimpleStatement()
        expect(";")
        return s
    }

    /**
     * A declaration or an expression, with the `;` left for the caller — a `for` init is the same
     * grammar as a statement but its terminator belongs to the `for`, so neither side may eat it.
     *
     * A declaration is distinguished from a call by lookahead, not by backtracking: `vec4 x = …`
     * has a type keyword followed by an identifier, whereas `vec4(…)` has a type keyword followed
     * by `(`. Qualifiers before the type are stepped over on the *lookahead* index so that a
     * misjudged guess costs nothing.
     */
    private fun parseSimpleStatement(): GStmt {
        var pending = pos
        while (tokens[pending].text in storageQualifiers) pending++
        val declType = GlslType.byName(tokens[pending].text)
        if (declType != null && tokens[pending + 1].kind == GToken.Kind.IDENT) {
            pos = pending + 1
            val declarators = ArrayList<Pair<String, GExpr?>>()
            while (true) {
                val name = next().text
                if (at("[")) throw GlslUnsupported("GLSL arrays are not in the StackSL subset")
                val init = if (accept("=")) parseAssignment() else null
                declarators += name to init
                if (!accept(",")) break
            }
            return GDeclare(declType, declarators)
        }
        return GExprStmt(parseExpression())
    }

    // -- expressions ---------------------------------------------------------------------------

    private fun parseExpression(): GExpr {
        var e = parseAssignment()
        while (at(",")) { next(); e = parseAssignment() } // comma operator: value of the last
        return e
    }

    private val assignOps = setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")

    private fun parseAssignment(): GExpr {
        val left = parseTernary()
        if (peek().text in assignOps) {
            val op = next().text
            return GAssign(left, op, parseAssignment())
        }
        return left
    }

    private fun parseTernary(): GExpr {
        val cond = parseBinary(0)
        if (accept("?")) {
            val yes = parseAssignment()
            expect(":")
            return GTernary(cond, yes, parseAssignment())
        }
        return cond
    }

    // Lowest precedence first, matching the GLSL grammar. Bit operators are present so that a
    // shader using them parses rather than dying in the lexer, and the interpreter rejects them.
    private val precedence: List<Set<String>> = listOf(
        setOf("||"),
        setOf("^^"),
        setOf("&&"),
        setOf("|"),
        setOf("^"),
        setOf("&"),
        setOf("==", "!="),
        setOf("<", ">", "<=", ">="),
        setOf("<<", ">>"),
        setOf("+", "-"),
        setOf("*", "/", "%")
    )

    private fun parseBinary(level: Int): GExpr {
        if (level >= precedence.size) return parseUnary()
        var left = parseBinary(level + 1)
        while (peek().text in precedence[level]) {
            val op = next().text
            left = GBinary(op, left, parseBinary(level + 1))
        }
        return left
    }

    private fun parseUnary(): GExpr {
        val t = peek().text
        if (t == "-" || t == "+" || t == "!" || t == "~") {
            next()
            return GUnary(t, parseUnary())
        }
        if (t == "++" || t == "--") {
            next()
            val operand = parseUnary()
            // Prefix ++/-- as an assignment, which is what it is.
            return GAssign(operand, if (t == "++") "+=" else "-=", GLiteral(GlslValue.int(1)))
        }
        return parsePostfix()
    }

    private fun parsePostfix(): GExpr {
        var e = parsePrimary()
        while (true) {
            when {
                at(".") -> { next(); e = GField(e, next().text) }
                at("[") -> { next(); val i = parseExpression(); expect("]"); e = GIndex(e, i) }
                at("++") || at("--") -> { val op = next().text; e = GPostfix(e, op) }
                else -> return e
            }
        }
    }

    private fun parsePrimary(): GExpr {
        val t = next()
        when {
            t.text == "(" -> {
                val e = parseExpression()
                expect(")")
                return e
            }

            t.kind == GToken.Kind.NUMBER -> {
                val text = t.text.trimEnd('f', 'F', 'u', 'U')
                val isInt = '.' !in text && 'e' !in text && 'E' !in text && !t.text.endsWith("f") && !t.text.endsWith("F")
                return GLiteral(
                    if (isInt) GlslValue.int(text.toInt())
                    // The literal is parsed as Double then narrowed once, so `0.1` becomes the
                    // nearest Float exactly as a GLSL compiler would make it.
                    else GlslValue.float(text.toDouble().toFloat())
                )
            }

            t.text == "true" -> return GLiteral(GlslValue.bool(true))
            t.text == "false" -> return GLiteral(GlslValue.bool(false))

            t.kind == GToken.Kind.IDENT -> {
                val type = GlslType.byName(t.text)
                if (at("(")) {
                    val args = parseArguments()
                    return if (type != null) GConstruct(type, args) else GCall(t.text, args)
                }
                if (type != null) fail("type name '${t.text}' used as a value")
                return GVarRef(t.text)
            }

            else -> fail("unexpected token $t")
        }
    }

    private fun parseArguments(): List<GExpr> {
        expect("(")
        val args = ArrayList<GExpr>()
        if (accept(")")) return args
        if (at("void") && peek(1).text == ")") { next(); next(); return args }
        while (true) {
            args += parseAssignment()
            if (!accept(",")) break
        }
        expect(")")
        return args
    }
}
