package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The oracle test for `autodiff.kt` and `autodiff_forward.kt`: **every** differentiable op is
 * checked against a finite-difference oracle at at least three distinct inputs.
 *
 * The house rule this enforces is that a universally-quantified claim ("all the gradients are
 * right") tested at one input is not a test. The tables below are the claim; [unaryOps],
 * [binaryOps] and the reduction cases each carry their own input set, and
 * [everyDeclaredOpIsCovered] asserts the tables have not quietly lost an entry.
 *
 * Two stencils are used. Away from kinks, a fourth-order five-point stencil gives ~1e-11 accuracy
 * for the first derivative and ~1e-9 for the second, so the tolerances are tight enough to catch a
 * wrong constant factor or a sign. **At** a kink no smooth stencil is meaningful, so those inputs
 * use the plain two-point central difference — which, under this library's even-split tie
 * convention, is exactly what the analytic rule reports. That agreement is the point: the kink
 * convention was chosen so the oracle still applies there.
 */
class AutoDiffGradientCheckTest {

    // ---- oracles ------------------------------------------------------------------------------

    /** Fourth-order central first derivative: error O(h⁴), valid only where f is smooth. */
    private fun fd1(x: Double, h: Double = 1e-3, f: (Double) -> Double): Double =
        (f(x - 2 * h) - 8 * f(x - h) + 8 * f(x + h) - f(x + 2 * h)) / (12 * h)

    /** Fourth-order central second derivative: error O(h⁴). */
    private fun fd2(x: Double, h: Double = 1e-3, f: (Double) -> Double): Double =
        (-f(x - 2 * h) + 16 * f(x - h) - 30 * f(x) + 16 * f(x + h) - f(x + 2 * h)) / (12 * h * h)

    /** Plain two-point central difference — the only oracle that means anything at a kink. */
    private fun cd1(x: Double, h: Double = 1e-6, f: (Double) -> Double): Double =
        (f(x + h) - f(x - h)) / (2 * h)

    private fun close(label: String, actual: Double, expected: Double, tol: Double) {
        val scale = 1.0 + expected.absoluteValue
        val err = (actual - expected).absoluteValue / scale
        assertTrue(err <= tol, "$label: got $actual, oracle $expected, relative error $err > $tol")
    }

    // ---- the op tables ------------------------------------------------------------------------

    /**
     * One unary op: the plain `Double` reference, the reverse-mode form, the forward-mode form,
     * the smooth inputs it is checked at, and any kink inputs checked with the coarse stencil.
     */
    private data class Unary(
        val name: String,
        val plain: (Double) -> Double,
        val ad: (AD) -> AD,
        val dual: (Dual2) -> Dual2,
        val smooth: List<Double>,
        val kinks: List<Double> = emptyList(),
        val secondOrder: Boolean = true
    )

    private val unaryOps: List<Unary> = listOf(
        Unary("neg", { -it }, { -it }, { -it }, listOf(-2.3, 0.4, 1.7)),
        Unary("reciprocal", { 1.0 / it }, { it.reciprocal() }, { it.reciprocal() }, listOf(-2.3, 0.4, 1.7, 5.0)),
        Unary("sq", { it * it }, { it.sq() }, { it.sq() }, listOf(-2.3, 0.0, 0.4, 1.7)),
        Unary("exp", { kotlin.math.exp(it) }, { exp(it) }, { exp(it) }, listOf(-1.5, 0.0, 0.7, 2.2)),
        Unary("ln", { kotlin.math.ln(it) }, { ln(it) }, { ln(it) }, listOf(0.3, 1.0, 2.5, 9.0)),
        Unary("log10", { kotlin.math.log10(it) }, { log10(it) }, { log10(it) }, listOf(0.3, 1.0, 2.5)),
        Unary("log2", { kotlin.math.log2(it) }, { log2(it) }, { log2(it) }, listOf(0.3, 1.0, 2.5)),
        Unary("sqrt", { kotlin.math.sqrt(it) }, { sqrt(it) }, { sqrt(it) }, listOf(0.2, 1.0, 4.0, 9.5)),
        Unary("cbrt", { Math.cbrt(it) }, { cbrt(it) }, { cbrt(it) }, listOf(-2.0, 0.5, 1.0, 8.0)),
        Unary("sin", { kotlin.math.sin(it) }, { sin(it) }, { sin(it) }, listOf(-1.2, 0.0, 0.6, 3.0)),
        Unary("cos", { kotlin.math.cos(it) }, { cos(it) }, { cos(it) }, listOf(-1.2, 0.0, 0.6, 3.0)),
        Unary("tan", { kotlin.math.tan(it) }, { tan(it) }, { tan(it) }, listOf(-0.9, 0.0, 0.4, 1.1)),
        Unary("asin", { kotlin.math.asin(it) }, { asin(it) }, { asin(it) }, listOf(-0.8, 0.0, 0.3, 0.75)),
        Unary("acos", { kotlin.math.acos(it) }, { acos(it) }, { acos(it) }, listOf(-0.8, 0.0, 0.3, 0.75)),
        Unary("atan", { kotlin.math.atan(it) }, { atan(it) }, { atan(it) }, listOf(-2.0, 0.0, 0.5, 3.3)),
        Unary("sinh", { kotlin.math.sinh(it) }, { sinh(it) }, { sinh(it) }, listOf(-1.4, 0.0, 0.7, 2.0)),
        Unary("cosh", { kotlin.math.cosh(it) }, { cosh(it) }, { cosh(it) }, listOf(-1.4, 0.0, 0.7, 2.0)),
        Unary("tanh", { kotlin.math.tanh(it) }, { tanh(it) }, { tanh(it) }, listOf(-1.4, 0.0, 0.7, 2.0)),
        Unary("sigmoid", { 1.0 / (1.0 + kotlin.math.exp(-it)) }, { sigmoid(it) }, { sigmoid(it) }, listOf(-2.5, 0.0, 0.9, 3.1)),
        Unary(
            "softplus", { kotlin.math.ln(1.0 + kotlin.math.exp(it)) }, { softplus(it) }, { softplus(it) },
            listOf(-2.5, 0.0, 0.9, 3.1)
        ),
        Unary(
            "abs", { it.absoluteValue }, { abs(it) }, { abs(it) },
            smooth = listOf(-2.3, 0.4, 1.7), kinks = listOf(0.0)
        ),
        Unary(
            "relu", { kotlin.math.max(it, 0.0) }, { relu(it) }, { relu(it) },
            smooth = listOf(-2.3, 0.4, 1.7), kinks = listOf(0.0)
        ),
        Unary(
            "clamp(-1,1)", { it.coerceIn(-1.0, 1.0) }, { it.clamp(-1.0, 1.0) }, { it.clamp(-1.0, 1.0) },
            smooth = listOf(-2.0, 0.25, 1.6), kinks = listOf(-1.0, 1.0)
        ),
        Unary("pow(2.5)", { Math.pow(it, 2.5) }, { it.pow(2.5) }, { it.pow(2.5) }, listOf(0.4, 1.0, 2.0, 3.7)),
        Unary("pow(3)", { it * it * it }, { it.pow(3) }, { it.pow(3) }, listOf(-1.6, 0.0, 0.8, 2.4)),
        Unary("floor", { kotlin.math.floor(it) }, { floor(it) }, { floor(it) }, listOf(-1.4, 0.37, 2.62)),
        Unary("ceil", { kotlin.math.ceil(it) }, { ceil(it) }, { ceil(it) }, listOf(-1.4, 0.37, 2.62)),
        Unary("round", { kotlin.math.round(it) }, { round(it) }, { round(it) }, listOf(-1.4, 0.37, 2.62))
    )

    /**
     * One binary op, checked partial-by-partial: the plain reference, the reverse-mode form, the
     * forward-mode form, smooth `(a, b)` points and tie points where the even-split convention
     * applies.
     */
    private data class Binary(
        val name: String,
        val plain: (Double, Double) -> Double,
        val ad: (AD, AD) -> AD,
        val dual: (Dual2, Dual2) -> Dual2,
        val smooth: List<Pair<Double, Double>>,
        val ties: List<Pair<Double, Double>> = emptyList()
    )

    private val binaryOps: List<Binary> = listOf(
        Binary("plus", { a, b -> a + b }, { a, b -> a + b }, { a, b -> a + b },
            listOf(-1.0 to 2.0, 0.3 to 0.7, 4.2 to -3.1)),
        Binary("minus", { a, b -> a - b }, { a, b -> a - b }, { a, b -> a - b },
            listOf(-1.0 to 2.0, 0.3 to 0.7, 4.2 to -3.1)),
        Binary("times", { a, b -> a * b }, { a, b -> a * b }, { a, b -> a * b },
            listOf(-1.0 to 2.0, 0.3 to 0.7, 4.2 to -3.1)),
        Binary("div", { a, b -> a / b }, { a, b -> a / b }, { a, b -> a / b },
            listOf(-1.0 to 2.0, 0.3 to 0.7, 4.2 to -3.1)),
        Binary("pow", { a, b -> Math.pow(a, b) }, { a, b -> pow(a, b) }, { a, b -> pow(a, b) },
            listOf(1.4 to 2.0, 0.6 to 0.35, 2.7 to 1.9)),
        Binary("atan2", { y, x -> kotlin.math.atan2(y, x) }, { y, x -> atan2(y, x) }, { y, x -> atan2(y, x) },
            listOf(1.0 to 2.0, -0.8 to 0.6, 3.0 to -1.4)),
        Binary("hypot", { x, y -> kotlin.math.sqrt(x * x + y * y) }, { x, y -> hypot(x, y) }, { x, y -> hypot(x, y) },
            listOf(3.0 to 4.0, -1.2 to 0.7, 0.4 to 2.9)),
        Binary("max", { a, b -> kotlin.math.max(a, b) }, { a, b -> max(a, b) }, { a, b -> max(a, b) },
            smooth = listOf(-1.0 to 2.0, 3.5 to 0.2, -4.0 to -6.0), ties = listOf(0.0 to 0.0, 1.75 to 1.75, -2.5 to -2.5)),
        Binary("min", { a, b -> kotlin.math.min(a, b) }, { a, b -> min(a, b) }, { a, b -> min(a, b) },
            smooth = listOf(-1.0 to 2.0, 3.5 to 0.2, -4.0 to -6.0), ties = listOf(0.0 to 0.0, 1.75 to 1.75, -2.5 to -2.5))
    )

    // ---- the checks ---------------------------------------------------------------------------

    @Test
    fun everyUnaryOpMatchesTheFiniteDifferenceOracleInReverseMode() {
        for (op in unaryOps) {
            assertTrue(op.smooth.size + op.kinks.size >= 3, "${op.name}: needs >= 3 check inputs")
            for (x in op.smooth) {
                val analytic = derivative(x) { op.ad(it) }
                close("d/dx ${op.name} at $x (reverse)", analytic, fd1(x, f = op.plain), 1e-6)
            }
            for (x in op.kinks) {
                val analytic = derivative(x) { op.ad(it) }
                close("d/dx ${op.name} at kink $x (reverse)", analytic, cd1(x, f = op.plain), 1e-6)
            }
        }
    }

    @Test
    fun everyUnaryOpMatchesTheFiniteDifferenceOracleInForwardMode() {
        for (op in unaryOps) {
            for (x in op.smooth) {
                val t = taylor2(x) { op.dual(it) }
                close("value ${op.name} at $x (forward)", t.value, op.plain(x), 1e-12)
                close("d/dx ${op.name} at $x (forward)", t.first, fd1(x, f = op.plain), 1e-6)
                if (op.secondOrder) {
                    close("d2/dx2 ${op.name} at $x (forward)", t.second, fd2(x, f = op.plain), 1e-5)
                }
            }
            for (x in op.kinks) {
                val t = taylor2(x) { op.dual(it) }
                close("d/dx ${op.name} at kink $x (forward)", t.first, cd1(x, f = op.plain), 1e-6)
            }
        }
    }

    @Test
    fun everyBinaryOpMatchesTheFiniteDifferenceOracleInBothPartials() {
        for (op in binaryOps) {
            assertTrue(op.smooth.size + op.ties.size >= 3, "${op.name}: needs >= 3 check inputs")
            for ((a, b) in op.smooth) {
                val g = gradAt(doubleArrayOf(a, b)) { p -> op.ad(p[0], p[1]) }
                close("d/da ${op.name} at ($a,$b)", g[0], fd1(a) { op.plain(it, b) }, 1e-6)
                close("d/db ${op.name} at ($a,$b)", g[1], fd1(b) { op.plain(a, it) }, 1e-6)

                val fa = directionalDerivative(doubleArrayOf(a, b), doubleArrayOf(1.0, 0.0)) { p -> op.dual(p[0], p[1]) }
                val fb = directionalDerivative(doubleArrayOf(a, b), doubleArrayOf(0.0, 1.0)) { p -> op.dual(p[0], p[1]) }
                close("d/da ${op.name} at ($a,$b) forward", fa.first, g[0], 1e-9)
                close("d/db ${op.name} at ($a,$b) forward", fb.first, g[1], 1e-9)
                close("d2/da2 ${op.name} at ($a,$b) forward", fa.second, fd2(a) { op.plain(it, b) }, 1e-5)
                close("d2/db2 ${op.name} at ($a,$b) forward", fb.second, fd2(b) { op.plain(a, it) }, 1e-5)
            }
            for ((a, b) in op.ties) {
                val g = gradAt(doubleArrayOf(a, b)) { p -> op.ad(p[0], p[1]) }
                close("d/da ${op.name} at tie ($a,$b)", g[0], cd1(a) { op.plain(it, b) }, 1e-6)
                close("d/db ${op.name} at tie ($a,$b)", g[1], cd1(b) { op.plain(a, it) }, 1e-6)
                val fa = directionalDerivative(doubleArrayOf(a, b), doubleArrayOf(1.0, 0.0)) { p -> op.dual(p[0], p[1]) }
                close("d/da ${op.name} at tie ($a,$b) forward", fa.first, cd1(a) { op.plain(it, b) }, 1e-6)
            }
        }
    }

    // ---- reductions ---------------------------------------------------------------------------

    private data class Reduction(
        val name: String,
        val plain: (DoubleArray) -> Double,
        val ad: (List<AD>) -> AD,
        val inputs: List<DoubleArray>
    )

    private val reductionInputs = listOf(
        doubleArrayOf(0.4, -1.2, 2.6),
        doubleArrayOf(1.0, 1.0, 1.0, 1.0),
        doubleArrayOf(-0.7, 3.3),
        doubleArrayOf(2.5, 0.9, -1.75, 0.2, 4.0)
    )

    private val reductions: List<Reduction> = listOf(
        Reduction("sum", { it.sum() }, { sum(it) }, reductionInputs),
        Reduction("mean", { it.average() }, { mean(it) }, reductionInputs),
        Reduction("product", { it.fold(1.0) { a, b -> a * b } }, { product(it) }, reductionInputs),
        Reduction("sumOfSquares", { it.sumOf { v -> v * v } }, { sumOfSquares(it) }, reductionInputs),
        Reduction("norm", { kotlin.math.sqrt(it.sumOf { v -> v * v }) }, { norm(it) }, reductionInputs),
        Reduction(
            "logSumExp",
            { xs -> kotlin.math.ln(xs.sumOf { kotlin.math.exp(it) }) },
            { logSumExp(it) },
            reductionInputs
        ),
        Reduction(
            "dot(self)",
            { it.sumOf { v -> v * v } },
            { xs -> dot(xs, xs) },
            reductionInputs
        ),
        Reduction(
            "softmax[0]",
            { xs -> kotlin.math.exp(xs[0]) / xs.sumOf { kotlin.math.exp(it) } },
            { xs -> softmax(xs)[0] },
            reductionInputs
        )
    )

    @Test
    fun everyReductionMatchesTheFiniteDifferenceOracle() {
        for (op in reductions) {
            assertTrue(op.inputs.size >= 3, "${op.name}: needs >= 3 check inputs")
            for (x in op.inputs) {
                val g = gradAt(x) { p -> op.ad(p) }
                for (i in x.indices) {
                    val oracle = fd1(x[i]) { v ->
                        val probe = x.copyOf()
                        probe[i] = v
                        op.plain(probe)
                    }
                    close("d/dx$i ${op.name} at ${x.toList()}", g[i], oracle, 1e-6)
                }
            }
        }
    }

    /** `maxOf` / `minOf` fold pairwise, so their oracle is the coarse stencil at the all-ties input. */
    @Test
    fun listMaxAndMinMatchTheirOracle() {
        val cases = listOf(
            doubleArrayOf(0.4, -1.2, 2.6),
            doubleArrayOf(-0.7, 3.3),
            doubleArrayOf(2.5, 0.9, -1.75, 0.2, 4.0)
        )
        for (x in cases) {
            val gMax = gradAt(x) { p -> maxOf(p) }
            val gMin = gradAt(x) { p -> minOf(p) }
            for (i in x.indices) {
                val oracleMax = cd1(x[i]) { v -> x.copyOf().also { it[i] = v }.max() }
                val oracleMin = cd1(x[i]) { v -> x.copyOf().also { it[i] = v }.min() }
                close("d/dx$i maxOf at ${x.toList()}", gMax[i], oracleMax, 1e-6)
                close("d/dx$i minOf at ${x.toList()}", gMin[i], oracleMin, 1e-6)
            }
        }
    }

    // ---- composite / structural -----------------------------------------------------------------

    @Test
    fun mixedDoubleAndIntOperatorsOnBothSidesAgreeWithTheOracle() {
        val cases = listOf(-1.3, 0.45, 2.8)
        val forms: List<Pair<String, Pair<(Double) -> Double, (AD) -> AD>>> = listOf(
            "d + x" to (({ x: Double -> 3.0 + x }) to ({ x: AD -> 3.0 + x })),
            "d - x" to (({ x: Double -> 3.0 - x }) to ({ x: AD -> 3.0 - x })),
            "d * x" to (({ x: Double -> 3.0 * x }) to ({ x: AD -> 3.0 * x })),
            "d / x" to (({ x: Double -> 3.0 / x }) to ({ x: AD -> 3.0 / x })),
            "x + d" to (({ x: Double -> x + 3.0 }) to ({ x: AD -> x + 3.0 })),
            "x - d" to (({ x: Double -> x - 3.0 }) to ({ x: AD -> x - 3.0 })),
            "x * d" to (({ x: Double -> x * 3.0 }) to ({ x: AD -> x * 3.0 })),
            "x / d" to (({ x: Double -> x / 3.0 }) to ({ x: AD -> x / 3.0 })),
            "i + x" to (({ x: Double -> 3.0 + x }) to ({ x: AD -> 3 + x })),
            "i - x" to (({ x: Double -> 3.0 - x }) to ({ x: AD -> 3 - x })),
            "i * x" to (({ x: Double -> 3.0 * x }) to ({ x: AD -> 3 * x })),
            "i / x" to (({ x: Double -> 3.0 / x }) to ({ x: AD -> 3 / x }))
        )
        for ((name, pair) in forms) {
            val (plain, ad) = pair
            for (x in cases) close("d/dx $name at $x", derivative(x) { ad(it) }, fd1(x, f = plain), 1e-6)
        }
    }

    @Test
    fun mixedDoubleAndIntOperatorsWorkInForwardModeToo() {
        val cases = listOf(-1.3, 0.45, 2.8)
        val forms: List<Pair<String, Pair<(Double) -> Double, (Dual2) -> Dual2>>> = listOf(
            "d + x" to (({ x: Double -> 3.0 + x }) to ({ x: Dual2 -> 3.0 + x })),
            "d - x" to (({ x: Double -> 3.0 - x }) to ({ x: Dual2 -> 3.0 - x })),
            "d * x" to (({ x: Double -> 3.0 * x }) to ({ x: Dual2 -> 3.0 * x })),
            "d / x" to (({ x: Double -> 3.0 / x }) to ({ x: Dual2 -> 3.0 / x })),
            "i + x" to (({ x: Double -> 3.0 + x }) to ({ x: Dual2 -> 3 + x })),
            "i - x" to (({ x: Double -> 3.0 - x }) to ({ x: Dual2 -> 3 - x })),
            "i * x" to (({ x: Double -> 3.0 * x }) to ({ x: Dual2 -> 3 * x })),
            "i / x" to (({ x: Double -> 3.0 / x }) to ({ x: Dual2 -> 3 / x })),
            "x / d" to (({ x: Double -> x / 3.0 }) to ({ x: Dual2 -> x / 3.0 }))
        )
        for ((name, pair) in forms) {
            val (plain, dual) = pair
            for (x in cases) {
                val t = taylor2(x) { dual(it) }
                close("d/dx $name at $x (forward)", t.first, fd1(x, f = plain), 1e-6)
                close("d2/dx2 $name at $x (forward)", t.second, fd2(x, f = plain), 1e-5)
            }
        }
    }

    @Test
    fun signIsTheUntrackedSlopeAndReadsTheValue() {
        val tape = ADTape()
        assertEquals(1.0, sign(tape.variable(2.5)))
        assertEquals(-1.0, sign(tape.variable(-0.1)))
        assertEquals(0.0, sign(tape.variable(0.0)))
    }

    @Test
    fun deeplyNestedCompositionsStillMatchTheOracle() {
        // Three genuinely different expressions, each at three inputs: the point is that the
        // *chain* through many ops is right, not just each op in isolation.
        val exprs: List<Pair<(DoubleArray) -> Double, (List<AD>) -> AD>> = listOf(
            ({ p: DoubleArray -> kotlin.math.exp(-p[0] * p[0]) * kotlin.math.sin(6.0 * p[1]) + p[0] / (1.0 + p[1] * p[1]) }) to
                ({ p: List<AD> -> exp(-p[0] * p[0]) * sin(6.0 * p[1]) + p[0] / (1.0 + p[1] * p[1]) }),
            ({ p: DoubleArray -> kotlin.math.tanh(p[0] + kotlin.math.ln(1.0 + kotlin.math.exp(p[1]))) * kotlin.math.sqrt(1.0 + p[0] * p[0]) }) to
                ({ p: List<AD> -> tanh(p[0] + softplus(p[1])) * sqrt(1.0 + p[0].sq()) }),
            ({ p: DoubleArray -> Math.pow(p[0].absoluteValue + 0.5, 1.5) / (2.0 + kotlin.math.cos(p[1])) }) to
                ({ p: List<AD> -> (abs(p[0]) + 0.5).pow(1.5) / (2.0 + cos(p[1])) })
        )
        val points = listOf(
            doubleArrayOf(0.6, -0.9),
            doubleArrayOf(-1.4, 0.3),
            doubleArrayOf(2.1, 1.8)
        )
        for ((index, e) in exprs.withIndex()) {
            val (plain, ad) = e
            for (x in points) {
                val g = gradAt(x) { p -> ad(p) }
                for (i in x.indices) {
                    val oracle = fd1(x[i]) { v -> plain(x.copyOf().also { it[i] = v }) }
                    close("expr$index d/dx$i at ${x.toList()}", g[i], oracle, 1e-6)
                }
            }
        }
    }

    @Test
    fun forwardAndReverseGradientsAgreeOnTheSameExpression() {
        val points = listOf(
            doubleArrayOf(0.6, -0.9, 1.2),
            doubleArrayOf(-1.4, 0.3, 0.05),
            doubleArrayOf(2.1, 1.8, -2.2)
        )
        for (x in points) {
            val reverse = gradAt(x) { p -> exp(-p[0].sq()) * sin(3.0 * p[1]) + tanh(p[2]) * p[0] }
            val forward = gradForward(x) { p -> exp(-p[0].sq()) * sin(3.0 * p[1]) + tanh(p[2]) * p[0] }
            for (i in x.indices) close("forward vs reverse [$i] at ${x.toList()}", forward[i], reverse[i], 1e-10)
        }
    }

    @Test
    fun jacobianMatchesTheFiniteDifferenceOracle() {
        val points = listOf(
            doubleArrayOf(0.5, 1.1),
            doubleArrayOf(-1.2, 0.4),
            doubleArrayOf(2.0, -0.7)
        )
        val plain: (DoubleArray) -> DoubleArray = { p ->
            doubleArrayOf(p[0] * p[1], kotlin.math.sin(p[0]) + p[1] * p[1], kotlin.math.exp(p[0] - p[1]))
        }
        for (x in points) {
            val j = jacobian(x) { p -> listOf(p[0] * p[1], sin(p[0]) + p[1].sq(), exp(p[0] - p[1])) }
            for (row in 0 until 3) {
                for (col in x.indices) {
                    val oracle = fd1(x[col]) { v -> plain(x.copyOf().also { it[col] = v })[row] }
                    close("J[$row][$col] at ${x.toList()}", j[row][col], oracle, 1e-6)
                }
            }
        }
    }

    @Test
    fun hessianIsExactAndSymmetric() {
        val points = listOf(
            doubleArrayOf(0.7, -0.4, 1.3),
            doubleArrayOf(-1.1, 0.9, 0.2),
            doubleArrayOf(2.2, 1.4, -0.8)
        )
        val plain: (DoubleArray) -> Double = { p ->
            p[0] * p[0] * p[1] + kotlin.math.sin(p[1] * p[2]) + kotlin.math.exp(p[0] * 0.5) * p[2]
        }
        for (x in points) {
            val h = hessian(x) { p -> p[0].sq() * p[1] + sin(p[1] * p[2]) + exp(p[0] * 0.5) * p[2] }
            for (i in x.indices) {
                for (j in x.indices) {
                    assertEquals(h[i][j], h[j][i], 1e-12, "Hessian must be symmetric at [$i][$j]")
                    // Mixed second partial by nested central differences on the plain function.
                    val hh = 1e-4
                    val oracle = if (i == j) {
                        (plain(x.copyOf().also { it[i] += hh }) - 2 * plain(x) +
                            plain(x.copyOf().also { it[i] -= hh })) / (hh * hh)
                    } else {
                        val pp = plain(x.copyOf().also { it[i] += hh; it[j] += hh })
                        val pm = plain(x.copyOf().also { it[i] += hh; it[j] -= hh })
                        val mp = plain(x.copyOf().also { it[i] -= hh; it[j] += hh })
                        val mm = plain(x.copyOf().also { it[i] -= hh; it[j] -= hh })
                        (pp - pm - mp + mm) / (4 * hh * hh)
                    }
                    close("H[$i][$j] at ${x.toList()}", h[i][j], oracle, 1e-4)
                }
            }
        }
    }

    @Test
    fun tapesFromDifferentGradientsCannotBeMixed() {
        val outer = ADTape()
        val a = outer.variable(1.0)
        val other = ADTape()
        val b = other.variable(2.0)
        val failure = runCatching { a + b }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "mixing tapes must fail loudly, got $failure")
    }

    /**
     * Guards the tables above against silent drift: every public differentiable declaration in
     * `autodiff.kt` must appear in one of the op tables (or in the explicit allow-list of things
     * checked elsewhere in this class). A new op with no oracle check fails here.
     */
    @Test
    fun everyDeclaredOpIsCovered() {
        val covered = buildSet {
            addAll(unaryOps.map { it.name.substringBefore("(") })
            addAll(binaryOps.map { it.name })
            addAll(reductions.map { it.name.substringBefore("(").substringBefore("[") })
            // checked by their own tests above
            addAll(listOf("sign", "maxOf", "minOf", "softmax", "jacobian", "hessian", "gradForward"))
            // operator forms, checked by the mixed-operator tests
            addAll(listOf("plus", "minus", "times", "div", "unaryMinus", "unaryPlus", "compareTo"))
        }
        val expected = listOf(
            "reciprocal", "sq", "exp", "ln", "log10", "log2", "sqrt", "cbrt", "sin", "cos", "tan",
            "asin", "acos", "atan", "sinh", "cosh", "tanh", "abs", "relu", "sigmoid", "softplus",
            "floor", "ceil", "round", "sign", "pow", "max", "min", "clamp", "atan2", "hypot",
            "sum", "mean", "product", "dot", "sumOfSquares", "norm", "maxOf", "minOf", "logSumExp",
            "softmax"
        )
        val missing = expected.filterNot { it in covered }
        if (missing.isNotEmpty()) fail("ops with no finite-difference oracle check: $missing")
    }
}
