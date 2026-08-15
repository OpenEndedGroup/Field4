package afield.solidsl

import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The GLSL backend has to survive two things, and the second is the one that matters.
 *
 * **shaderc** proves the emitted text is *valid*: a missing semicolon or an int-where-a-float-goes
 * fails here rather than at pipeline creation on someone else's machine. That is where the 2D
 * precedent (`RectSLGlslSdfTest`) stops, and it is not enough. A dropped negation, a swapped `min`
 * and `max`, an argument in the wrong position — every one of those compiles perfectly.
 *
 * So the second check **reads the shader back and runs it**. [GlslInterpreter] below parses the
 * restricted grammar `SolidGlsl` emits — straight-line `float NAME = EXPR;` over literals, previously
 * bound names, `p.x`/`p.y`/`p.z`, a whitelist of seven functions and the conditional operator — and
 * evaluates it at thousands of points, against [SolidExpr.toField3] computed by the completely
 * separate Kotlin path. It is the emitted *characters* being executed, so nothing about the two
 * agreeing is guaranteed by their having come from the same tree walk.
 *
 * That is the whole reason the emitter's output is scalar SSA with no `vec3` locals and no swizzles.
 * It looks like an odd thing to hand a shader compiler; any driver re-vectorises it in its first
 * pass. What it buys is a grammar small enough that a two-hundred-line reader can be trusted, and
 * therefore a backend whose fidelity is a number rather than an intention.
 */
class SolidSLGlslTest {

    private val subjects: List<Pair<String, SolidExpr>> = listOf(
        "sphere" to sphere(1.0),
        "cuboid" to cuboid(2.0, 1.0, 0.5),
        "cylinder" to cylinder(2.0, 0.6),
        "cone" to cone(1.5, 0.7),
        "frustum" to cylinder(2.0, 0.8, 0.3),
        "union" to (sphere(0.8) + cuboid(1.4, 0.4, 0.4)),
        "difference" to (cuboid(2.0, 2.0, 1.0) - cylinder(3.0, 0.4)),
        "intersect" to (sphere(1.0) and sphere(1.0).at(1.0, 0.0, 0.0)),
        "nested" to ((cuboid(3.0, 2.0, 0.8) + cylinder(1.6, 0.7)) - unionOf(
            listOf(-1.0, 1.0).map { cylinder(3.0, 0.25).at(it, 0.0, 0.0) },
        )),
        "translate" to sphere(0.7).at(0.3, -0.4, 0.2),
        "rotate" to cuboid(2.0, 1.0, 0.5).turned(30.0, 40.0, 50.0),
        "scale" to sphere(1.0).sized(2.0, 1.0, 0.5),
        "mirror" to cuboid(1.0, 1.0, 1.0).at(0.7, 0.3, 0.0).mirrored(1.0, 1.0, 0.0),
        "rounded" to cuboid(1.4, 1.0, 0.6).rounded(0.2),
        "shell" to sphere(1.0).shelled(0.1),
        "extrudeRect" to Profile.rect(2.0, 1.0).extruded(1.5),
        "extrudeStar" to Profile.star(5, 1.0, 0.45).extruded(0.8),
        "extrudeTwist" to Profile.rect(2.0, 0.6).extruded(2.0, twistDegrees = 90.0),
        "extrudeTaper" to Profile.rect(2.0, 1.0).extruded(2.0, scaleTop = 0.4),
        "extrudeOffset" to Profile.rect(2.0, 1.0).offset(0.35).extruded(1.0),
        "profileBoolean" to (Profile.rect(2.0, 2.0) - Profile.circle(0.7)).extruded(1.0),
        "profileTransform" to Profile.rect(1.4, 0.6).turned(35.0).translated(0.2, 0.1).sized(1.3).extruded(1.0),
        "revolve" to torus(1.2, 0.35),
        "revolveWedge" to Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(270.0),
        "revolveNarrow" to Profile.rect(0.6, 0.6).translated(1.2, 0.0).revolved(90.0),
        "gyroid" to (gyroid(3.0, 0.25) and cuboid(2.0, 2.0, 2.0)),
        "smoothUnion" to smoothUnion(sphere(0.8), cuboid(1.6, 0.4, 0.4), k = 0.3),
        "smoothDifference" to smoothDifference(cuboid(1.6, 1.6, 0.6), sphere(0.8).at(0.6, 0.0, 0.4), k = 0.25),
        "empty" to SolidExpr.Empty,
    )

    // ==========================================================================================
    // The measurement
    // ==========================================================================================

    @Test
    fun `the emitted GLSL computes the same field as the Kotlin lowering`() {
        var comparisons = 0
        var worstRelative = 0.0
        var worstCase = ""
        println("%-18s %7s %14s %14s".format("case", "stmts", "worst abs", "worst rel"))
        subjects.forEach { (name, expr) ->
            val emitted = expr.toGlsl()
            val program = GlslInterpreter.parse(emitted.glsl, emitted.functionName)
            val kotlinField = expr.toField3()
            val span = expr.bounds()?.let { maxOf(it.size.x, it.size.y, it.size.z).toDouble() } ?: 2.0
            val random = kotlin.random.Random(3L)
            var worstAbs = 0.0
            var worstRel = 0.0
            repeat(2000) {
                // Deliberately over a box larger than the shape, so the field is exercised outside,
                // inside, and across the surface rather than only where it happens to be small.
                val x = (random.nextDouble() - 0.5) * 3.0 * span
                val y = (random.nextDouble() - 0.5) * 3.0 * span
                val z = (random.nextDouble() - 0.5) * 3.0 * span
                val a = kotlinField.at(x, y, z)
                val b = program.eval(x, y, z)
                val diff = abs(a - b)
                worstAbs = max(worstAbs, diff)
                worstRel = max(worstRel, diff / max(1e-9, abs(a)))
                comparisons++
            }
            println("%-18s %7d %14.3e %14.3e".format(name, emitted.statementCount, worstAbs, worstRel))
            if (worstRel > worstRelative) { worstRelative = worstRel; worstCase = name }
            // Both sides do the same real arithmetic in `double`, so the only difference should be
            // the order the compiler-free interpreter happens to associate in. A part in 10^12 is
            // rounding; anything a reader would call a bug is many orders larger than this.
            assertTrue(
                worstRel < 1e-12,
                "$name: the emitted GLSL and the Kotlin field disagree by $worstRel relative " +
                    "(worst absolute $worstAbs)\n${emitted.glsl}",
            )
        }
        println("$comparisons point comparisons across ${subjects.size} shapes; worst relative $worstRelative on $worstCase")
        assertTrue(comparisons >= 50_000, "only $comparisons comparisons were made")
    }

    @Test
    fun `shaderc accepts every emitted shader`() {
        val failures = mutableListOf<String>()
        var compiled = 0
        var biggest = 0
        subjects.forEach { (name, expr) ->
            val source = expr.toRaymarchFragment()
            biggest = max(biggest, source.length)
            try {
                val spirv = ShaderSPIRVUtils.compileShader("solidsl-$name.frag", source, ShaderKind.FRAGMENT_SHADER)
                val words = spirv.bytecode()?.remaining() ?: 0
                if (words <= 0) failures += "$name: empty SPIR-V" else compiled++
            } catch (t: Throwable) {
                failures += "$name: ${t.message?.lines()?.take(8)?.joinToString(" | ")}"
            }
        }
        println("$compiled of ${subjects.size} raymarch shaders produced SPIR-V; largest source $biggest chars")
        assertTrue(failures.isEmpty(), "emitted GLSL failed to compile:\n${failures.joinToString("\n")}")
        assertEquals(subjects.size, compiled)
    }

    @Test
    fun `the step scale says when the field cannot be sphere-traced at full speed`() {
        // A true distance field marches at 1.0. Anything else has to be told to slow down, and the
        // reason has to travel with the number — a bare 0.7 in someone's shader is folklore.
        assertEquals(1.0, sphere(1.0).toGlsl().stepScale)
        assertEquals(1.0, (sphere(1.0) + cuboid(1.0, 1.0, 1.0)).toGlsl().stepScale, "a union of exact fields is exact")

        val cut = (cuboid(2.0, 2.0, 1.0) - cylinder(3.0, 0.4)).toGlsl()
        assertTrue(cut.stepScale < 1.0, "a difference is a pointwise max and over-estimates outside the cut")
        assertTrue(cut.notes.isNotEmpty())

        val twisted = Profile.rect(2.0, 0.6).extruded(2.0, twistDegrees = 90.0).toGlsl()
        assertTrue(
            twisted.stepScale < cut.stepScale,
            "a shear over-estimates by an unbounded factor and must be slower than a boolean's max",
        )
    }

    @Test
    fun `the emitted source is the restricted grammar it claims to be`() {
        // The reader above is only trustworthy if the emitter cannot quietly start using something
        // outside the subset — so the subset is asserted rather than assumed.
        val forbidden = listOf("vec2", "vec3 ", "for(", "for (", "while", ".xy", ".xz", ".yz", "[", "]")
        subjects.forEach { (name, expr) ->
            val body = expr.toGlsl().glsl.lines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
                // The signature is the one place a vec3 appears, by construction.
                .replace("float sdSolid(vec3 p) {", "")
            forbidden.forEach { token ->
                assertTrue(
                    !body.contains(token),
                    "$name: the emitted body uses '$token', which is outside the grammar the reader parses",
                )
            }
        }
    }

    @Test
    fun `a shader is proportional to the expression, not to what it draws`() {
        // The point of a symbolic backend: a hundred instances of a thing cost a hundred blocks, and
        // one instance repeated by a transform costs one. This is the ratchet on emitter bloat.
        val one = sphere(1.0).toGlsl().statementCount
        val ten = unionOf((0 until 10).map { sphere(1.0).at(it.toDouble(), 0.0, 0.0) }).toGlsl().statementCount
        println("one sphere: $one statements; ten translated spheres: $ten")
        assertTrue(ten < one * 10 + 60, "ten spheres should be about ten spheres; got $ten against $one")
        assertTrue(ten > one * 5, "ten spheres cannot be cheaper than five; got $ten")

        // And a five-pointed star's polygon unrolls to ten edge blocks, not to a loop.
        val star = Profile.star(5, 1.0, 0.45).extruded(1.0).toGlsl()
        val star8 = Profile.star(8, 1.0, 0.45).extruded(1.0).toGlsl()
        assertTrue(
            star8.statementCount > star.statementCount,
            "more vertices must be more code, since the polygon is unrolled",
        )
    }
}

// ==============================================================================================
// A reader for the emitted subset
// ==============================================================================================

/**
 * A tiny evaluator for exactly the GLSL `SolidGlsl` emits, and nothing more.
 *
 * Deliberately written from the *grammar* rather than from the emitter: it knows about statements,
 * precedence and seven builtins, and nothing at all about signed distance functions. That is what
 * makes running it a check on the emitter instead of a restatement of it.
 *
 * Supported: `float NAME = EXPR;`, `return EXPR;`, `// comments`, and expressions over number
 * literals, bound names, `p.x` / `p.y` / `p.z`, `+ - * /`, unary `-`, `< > <= >=`, the conditional
 * operator, and `sqrt abs min max sin cos clamp`. Anything else throws, loudly, which is the
 * behaviour a guard wants.
 */
class GlslInterpreter private constructor(
    private val statements: List<Pair<String, Node>>,
    private val result: Node,
) {

    fun eval(x: Double, y: Double, z: Double): Double {
        val env = HashMap<String, Double>(statements.size * 2)
        env["p.x"] = x; env["p.y"] = y; env["p.z"] = z
        statements.forEach { (name, node) -> env[name] = node.eval(env) }
        return result.eval(env)
    }

    /** The number of bound names — the interpreter's own view of the statement count. */
    val statementCount: Int get() = statements.size

    sealed interface Node {
        fun eval(env: Map<String, Double>): Double

        data class Num(val v: Double) : Node {
            override fun eval(env: Map<String, Double>) = v
        }

        data class Ref(val name: String) : Node {
            override fun eval(env: Map<String, Double>) =
                env[name] ?: error("the shader reads '$name' before it is bound")
        }

        data class Unary(val op: String, val a: Node) : Node {
            override fun eval(env: Map<String, Double>) = when (op) {
                "-" -> -a.eval(env)
                else -> error("unary '$op'")
            }
        }

        data class Binary(val op: String, val a: Node, val b: Node) : Node {
            override fun eval(env: Map<String, Double>): Double {
                val l = a.eval(env); val r = b.eval(env)
                return when (op) {
                    "+" -> l + r
                    "-" -> l - r
                    "*" -> l * r
                    "/" -> l / r
                    "<" -> if (l < r) 1.0 else 0.0
                    ">" -> if (l > r) 1.0 else 0.0
                    "<=" -> if (l <= r) 1.0 else 0.0
                    ">=" -> if (l >= r) 1.0 else 0.0
                    else -> error("binary '$op'")
                }
            }
        }

        data class Cond(val c: Node, val a: Node, val b: Node) : Node {
            override fun eval(env: Map<String, Double>) =
                if (c.eval(env) != 0.0) a.eval(env) else b.eval(env)
        }

        data class Call(val name: String, val args: List<Node>) : Node {
            override fun eval(env: Map<String, Double>): Double {
                val v = args.map { it.eval(env) }
                return when (name) {
                    "sqrt" -> sqrt(v[0])
                    "abs" -> abs(v[0])
                    "min" -> min(v[0], v[1])
                    "max" -> max(v[0], v[1])
                    "sin" -> sin(v[0])
                    "cos" -> cos(v[0])
                    "clamp" -> v[0].coerceIn(v[1], v[2])
                    else -> error("the shader calls '$name', which is outside the emitted subset")
                }
            }
        }
    }

    companion object {

        /** Parse the body of `float <functionName>(vec3 p) { … }` out of [source]. */
        fun parse(source: String, functionName: String): GlslInterpreter {
            val header = "float $functionName(vec3 p) {"
            val start = source.indexOf(header)
            require(start >= 0) { "no '$header' in the emitted source" }
            val body = source.substring(start + header.length).substringBeforeLast("}")

            val statements = mutableListOf<Pair<String, Node>>()
            var result: Node? = null
            body.lines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) return@forEach
                require(line.endsWith(";")) { "not a statement: '$line'" }
                val text = line.dropLast(1)
                when {
                    text.startsWith("float ") -> {
                        val eq = text.indexOf('=')
                        require(eq > 0) { "no '=' in '$line'" }
                        val name = text.substring("float ".length, eq).trim()
                        statements += name to Parser(text.substring(eq + 1)).parseWhole()
                    }
                    text.startsWith("return ") -> result = Parser(text.removePrefix("return ")).parseWhole()
                    else -> error("unparseable statement: '$line'")
                }
            }
            return GlslInterpreter(statements, result ?: error("the emitted function never returns"))
        }
    }

    /** Recursive descent, one precedence level per method, in GLSL's own order. */
    private class Parser(text: String) {
        private val s = text
        private var i = 0

        fun parseWhole(): Node {
            val n = conditional()
            skip()
            require(i >= s.length) { "trailing '${s.substring(i)}' in '$s'" }
            return n
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun at(token: String): Boolean {
            skip()
            return s.startsWith(token, i)
        }

        private fun take(token: String): Boolean {
            if (!at(token)) return false
            i += token.length
            return true
        }

        private fun conditional(): Node {
            val c = comparison()
            if (!take("?")) return c
            val a = conditional()
            require(take(":")) { "expected ':' in a conditional in '$s'" }
            return Node.Cond(c, a, conditional())
        }

        private fun comparison(): Node {
            var a = additive()
            // Two-character operators first, or "<=" reads as "<" followed by a stray "=".
            for (op in listOf("<=", ">=", "<", ">")) {
                if (take(op)) { a = Node.Binary(op, a, additive()); break }
            }
            return a
        }

        private fun additive(): Node {
            var a = multiplicative()
            while (true) {
                a = when {
                    take("+") -> Node.Binary("+", a, multiplicative())
                    // Not "-" of a "->" or a "-=" — neither is emitted, so a bare check is enough.
                    take("-") -> Node.Binary("-", a, multiplicative())
                    else -> return a
                }
            }
        }

        private fun multiplicative(): Node {
            var a = unary()
            while (true) {
                a = when {
                    take("*") -> Node.Binary("*", a, unary())
                    take("/") -> Node.Binary("/", a, unary())
                    else -> return a
                }
            }
        }

        private fun unary(): Node = if (take("-")) Node.Unary("-", unary()) else primary()

        private fun primary(): Node {
            skip()
            require(i < s.length) { "expression ended early in '$s'" }
            if (take("(")) {
                val n = conditional()
                require(take(")")) { "expected ')' in '$s'" }
                return n
            }
            val c = s[i]
            if (c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit())) return number()
            require(c.isLetter() || c == '_') { "unexpected '$c' in '$s'" }
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val name = s.substring(start, i)
            if (!at("(")) return Node.Ref(name)
            take("(")
            val args = mutableListOf<Node>()
            if (!take(")")) {
                do { args += conditional() } while (take(","))
                require(take(")")) { "expected ')' after the arguments of $name in '$s'" }
            }
            return Node.Call(name, args)
        }

        private fun number(): Node {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return Node.Num(s.substring(start, i).toDouble())
        }
    }
}
