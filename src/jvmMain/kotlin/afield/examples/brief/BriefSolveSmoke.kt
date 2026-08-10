package afield.examples.brief

import afield.brief.BriefException
import afield.brief.checkBrief
import afield.brief.compileBrief
import afield.brief.solve
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs

/**
 * Brief, solved through the real Ceres backend, against answers worked out on paper.
 *
 * A `jvmTest` would have been the natural home, but the native library is optional and not committed,
 * so a JUnit test would have to skip when it is absent — and a test that skips is a test that is
 * silently green on the day it matters. Every other Ceres check in this repo is an `examples/ceres`
 * entrypoint driven by a `smokeCeres*` Gradle task for exactly that reason, and this follows it:
 * `./gradlew smokeBrief -PceresLibrary=…`.
 *
 * **Why every case here is over-determined.** A residual of `1e-15` on an exactly-determined square
 * system says the solver ran; it says nothing about whether the model means what it reads. So each
 * case states two things that cannot both hold and checks the *compromise* — which for a weighted
 * least-squares problem is a closed form, because a sum of weighted squares in one unknown is a
 * parabola and its minimum is a weighted mean. That makes the assertion a statement about the
 * notation's semantics: if `insist` did not mean `1e3`, or if a weight were dropped somewhere in the
 * lowering, the number would move.
 *
 * The weights are cost weights — `s = sqrt(weight)` inside every native functor, per
 * `CeresGeometry.kt`'s header — so a statement contributes `weight * residual²` and the minimum of
 * `wa(x - ta)² + wb(x - tb)²` is `(wa·ta + wb·tb) / (wa + wb)`. Every expected value below is that
 * formula and nothing else.
 */
fun main() {
    weightIsANumberNotAFlag()
    aThirdOfTheWayAlong()
    threeWayCompromiseOnHeight()
    theSameFigureThreeWays()
    hostValuesRoundTrip()
    diagnosticsAreLocated()
    println("ok brief")
}

private fun check(label: String, expected: Double, actual: Double, tolerance: Double = 1e-5) {
    if (abs(expected - actual) > tolerance) {
        error("$label: expected $expected, got $actual (off by ${abs(expected - actual)})")
    }
    println("  ok  $label = %.9f".format(actual))
}

/**
 * The headline. One point, two statements about how far away it is, and the answer is the weighted
 * mean of the two targets — `prefer` against `insist` is `1` against `1000`.
 */
private fun weightIsANumberNotAFlag() {
    val program = compileBrief(
        """
        #brief
        settle none
        point o at (0, 0, 0)
        point p at (2, 0, 0)
        fix o
        fix p in yz
        prefer distance(o, p) = 1
        insist distance(o, p) = 3
        """.trimIndent()
    )
    val solution = program.solve()
    if (!solution.ok) error("solve failed: ${solution.report()}")
    // min over x of 1*(x-1)^2 + 1000*(x-3)^2  ->  x = (1*1 + 1000*3)/1001
    check("weighted mean of 1 and 3 at 1:1000", (1.0 * 1.0 + 1000.0 * 3.0) / 1001.0, solution["p"].x)

    // And the same brief with the strengths exchanged lands on the mirror answer, which is what makes
    // this a statement about the *weights* rather than about the solver finding some fixed point.
    val mirrored = compileBrief(
        program.source.replace("prefer distance(o, p) = 1", "insist distance(o, p) = 1")
            .replace("insist distance(o, p) = 3", "prefer distance(o, p) = 3")
    ).solve()
    check("weighted mean with the strengths exchanged", (1000.0 * 1.0 + 1.0 * 3.0) / 1001.0, mirrored["p"].x)

    // A bare number in the strength slot is the same slot, so it has to agree with the ladder word.
    val numeric = compileBrief(program.source.replace("insist ", "1000 ")).solve()
    check("a bare 1000 means what 'insist' means", solution["p"].x, numeric["p"].x, 1e-9)
}

/**
 * "Put the spine a third of the way in" against "that room has to be six wide" — the two-kinds-of
 * -relation case, and the one the demo document lets you poke.
 */
private fun aThirdOfTheWayAlong() {
    // `1e10 fix`, not `demand fix`, and the reason is worth stating: `demand` is 1e6, so a `1e3`
    // statement pulling on a locked point moves it by about 1e-3 — real, correct least-squares
    // behaviour, and enough to spoil a closed form that assumes the span endpoints cannot move. That the
    // strength slot takes a bare number when the ladder is not enough is exactly why it takes one.
    val source = """
        #brief
        settle none
        point west  at (0, 0, 0)
        point east  at (9, 0, 0)
        point spine at (3, 0, 0)
        1e10 fix west east
        1e10 fix spine in yz
        prefer spine at 1/3 along (west, east)
        insist distance(west, spine) = 6
    """.trimIndent()
    val solution = compileBrief(source).solve()
    if (!solution.ok) error("solve failed: ${solution.report()}")
    // The span wants x = 3 at weight 1; the length wants x = 6 at weight 1000.
    check("a third of 9, outvoted by a demanded 6", (1.0 * 3.0 + 1000.0 * 6.0) / 1001.0, solution["spine"].x)

    // Raise the span statement to the same rung and the answer is the midpoint, exactly. This is the
    // assertion that would break if `at … along` still went through `PointExpr.ceq`, whose weight is
    // fixed at 1.0 — the notation would read as weighted and not be.
    val evens = compileBrief(source.replace("prefer spine at", "insist spine at")).solve()
    check("both insisted: the exact midpoint of 3 and 6", 4.5, evens["spine"].x)

    // And one rung further makes the span win by the same factor, in the other direction.
    val spanWins = compileBrief(source.replace("prefer spine at", "demand spine at")).solve()
    check("demanded span, insisted length", (1e6 * 3.0 + 1e3 * 6.0) / (1e6 + 1e3), spanWins["spine"].x)
}

/**
 * A three-statement compromise, so the closed form is a small linear solve rather than a mean — which
 * is a stronger check, because getting it right needs all three weights to be where they say.
 */
private fun threeWayCompromiseOnHeight() {
    val solution = compileBrief(
        """
        #brief
        settle none
        point a at (0, 0, 1)
        point b at (1, 0, 5)
        1e10 fix a in xy
        1e10 fix b in xy
        prefer a.z = 0
        insist b.z = 10
        prefer a b same-z
        """.trimIndent()
    ).solve()
    if (!solution.ok) error("solve failed: ${solution.report()}")
    // f = za^2 + 1000(zb - 10)^2 + (za - zb)^2
    //   d/dza: 4za - 2zb = 0            -> za = zb/2
    //   d/dzb: 2000(zb - 10) - 2(za - zb) = 0  ->  1000.5 zb = 10000
    check("b.z, pulled down by a level statement", 10000.0 / 1000.5, solution["b"].z, 1e-4)
    check("a.z, half of it", 5000.0 / 1000.5, solution["a"].z, 1e-4)

    // The per-statement residuals are measured back off the solved geometry in Kotlin, not read out of
    // Ceres, so they are an independent check on the solve rather than a restatement of it. What they
    // must show is the *opposite* of the naive guess: the costliest statement is a `prefer`, because the
    // `insist` got what it asked for. So the insisted statement's residual is the smallest of the three.
    val worst = solution.worst ?: error("no residuals measured")
    if (!worst.label.startsWith("prefer")) {
        error("expected a 'prefer' to be the costliest statement, got ${worst.label}")
    }
    val insisted = solution.residuals.single { it.label.startsWith("insist") }
    val preferences = solution.residuals.filter { it.label.startsWith("prefer") }
    if (preferences.any { abs(it.residual) < abs(insisted.residual) }) {
        error("the insisted statement should have the smallest residual; residuals were ${solution.residuals}")
    }
    println("  ok  costliest is '${worst.label}' (residual ${"%.4f".format(worst.residual)}); " +
        "the insisted one is off by only ${"%.2e".format(abs(insisted.residual))}")
}

/** Equal-area through the expression backend: two panels, no number, one has to give. */
private fun theSameFigureThreeWays() {
    val solution = compileBrief(
        """
        #brief
        point a1 at (0, 0, 0)
        point b1 at (2, 0, 0)
        point c1 at (2, 2, 0)
        point d1 at (0, 2, 0)
        point a2 at (5, 0, 0)
        point b2 at (9, 0, 0)
        point c2 at (9, 1, 0)
        point d2 at (5, 1, 0)
        panel small corners a1 b1 c1 d1
        panel wide  corners a2 b2 c2 d2
        demand fix a1 b1 c1 d1
        prefer wide.area = small.area
        insist distance(a2, b2) = 4
        demand a2 d2 same-x
        demand a2 b2 same-y
        demand c2 d2 same-y
        demand b2 c2 same-x
        """.trimIndent()
    ).solve()
    if (!solution.ok) error("solve failed: ${solution.report()}")
    // The small panel is nailed at 2x2 = 4. `wide` is held rectangular, 4 long, so its area is
    // 4 * height and matching 4 makes the height 1. The area statement is the only thing that sets it.
    val height = abs(solution["d2"].y - solution["a2"].y)
    check("equal-area drove the wide panel's height to 1", 1.0, height, 1e-3)
}

/** The host seam: a brief moving `Vector3f`s that live in Kotlin, in place. */
private fun hostValuesRoundTrip() {
    val corner = Vector3f(0f, 0f, 0f)
    val far = Vector3f(4f, 0f, 0f)
    val program = compileBrief(
        """
        #brief
        settle none
        point hinge at (0, 0, 0)
        point tip   at (4, 0, 0)
        fix hinge
        fix tip in yz
        demand distance(hinge, tip) = 2.5
        """.trimIndent()
    )
    program.solve(bindings = mapOf("hinge" to corner, "tip" to far))
    check("the solved answer was written back into the Vector3f", 2.5, far.x().toDouble(), 1e-4)
    check("the bound hinge stayed put", 0.0, corner.x().toDouble(), 1e-6)
}

/** A misspelling is a located message, not a stack trace — checked against the real compiler. */
private fun diagnosticsAreLocated() {
    val diagnostics = checkBrief(
        """
        #brief
        point a at (0, 0, 0)
        point b at (1, 0, 0)
        line spine from a to b
        insist spien.length = 4
        """.trimIndent()
    )
    val first = diagnostics.firstOrNull() ?: error("expected a diagnostic")
    if (first.line != 5) error("expected the error on line 5, got ${first.line}: ${first.format()}")
    if (first.hint?.contains("spine") != true) error("expected a did-you-mean for 'spine': ${first.format()}")
    println("  ok  ${first.format()}")

    try {
        compileBrief("#brief\ninsist spien.length = 4\n")
        error("expected a BriefException")
    } catch (e: BriefException) {
        if (e.diagnostics.isEmpty()) error("BriefException carried no diagnostics")
        println("  ok  compileBrief threw BriefException with ${e.diagnostics.size} diagnostic(s)")
    }
}
