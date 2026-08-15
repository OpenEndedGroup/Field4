package afield.solidsl

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A simplifier that quietly changes the shape is the worst thing that could be in this package, so
 * the shape is the thing that is checked.
 *
 * Every rewrite below is asserted twice: once structurally, that the rule fired and produced the tree
 * it was supposed to, and once **numerically**, that the simplified expression's field agrees with
 * the original's at thousands of points over a box larger than the shape. The second is the one that
 * matters. A rule that folds two translations into a subtraction rather than an addition passes any
 * structural assertion you would think to write and fails the sampling immediately.
 *
 * No Manifold needed for any of it: simplification is a property of the notation.
 */
class SolidSLSimplifyTest {

    /**
     * Assert that [before] and [after] describe the same set of points, by sampling.
     *
     * Exact equality of the two `Double`s, not a tolerance — every rule in `SolidSimplify.kt` either
     * removes a node that contributed nothing (an identity transform, an `Empty` operand, a
     * duplicate) or folds two constants that the field lowering would have applied in sequence
     * anyway. `Translate(Translate(x, a), b)` evaluates `((p − a) − b)` before and `(p − (a + b))`
     * after, which is not bit-identical in general, so *that* one gets a rounding tolerance and the
     * rest do not have to.
     */
    private fun sameShape(name: String, before: SolidExpr, after: SolidExpr, tolerance: Double = 0.0) {
        val fb = before.toField3()
        val fa = after.toField3()
        val span = before.bounds()?.let { maxOf(it.size.x, it.size.y, it.size.z).toDouble() } ?: 2.0
        val random = kotlin.random.Random(19L)
        var worst = 0.0
        var signFlips = 0
        repeat(4000) {
            val x = (random.nextDouble() - 0.5) * 3.0 * span
            val y = (random.nextDouble() - 0.5) * 3.0 * span
            val z = (random.nextDouble() - 0.5) * 3.0 * span
            val a = fb.at(x, y, z)
            val b = fa.at(x, y, z)
            worst = max(worst, abs(a - b))
            if ((a < 0) != (b < 0)) signFlips++
        }
        assertEquals(0, signFlips, "$name: simplification moved the surface — $signFlips of 4000 points changed side")
        assertTrue(worst <= tolerance, "$name: the field changed by $worst (tolerance $tolerance)\n  $before\n  $after")
    }

    @Test
    fun `identity transforms disappear`() {
        val s = sphere(1.0)
        assertEquals(s, s.at(0, 0, 0).simplified())
        assertEquals(s, s.turned(0.0, 0.0, 0.0).simplified())
        assertEquals(s, s.sized(1.0).simplified())
        assertEquals(s, s.rounded(0.0).simplified())
        assertEquals(Profile.circle(1.0), Profile.circle(1.0).offset(0.0).simplified())
        sameShape("identity", s.at(0, 0, 0).sized(1.0).turned(0.0, 0.0, 0.0), s)
    }

    @Test
    fun `adjacent translations and scales fold`() {
        val before = sphere(1.0).at(1.0, 2.0, 3.0).at(0.5, -1.0, 0.25)
        val after = before.simplified()
        assertEquals(SolidExpr.Translate(SolidExpr.Sphere(1.0), 1.5, 1.0, 3.25), after)
        // A rounding tolerance, and only here: (p − a) − b and p − (a + b) are the same real number
        // and not always the same double.
        sameShape("translate fold", before, after, tolerance = 1e-12)

        val scaled = sphere(1.0).sized(2.0).sized(3.0).simplified()
        assertEquals(SolidExpr.Scale(SolidExpr.Sphere(1.0), 6.0, 6.0, 6.0), scaled)
        sameShape("scale fold", sphere(1.0).sized(2.0).sized(3.0), scaled, tolerance = 1e-12)
    }

    @Test
    fun `rotations fold only about a shared single axis`() {
        val zz = cuboid(2, 1, 0.5).turned(z = 20.0).turned(z = 25.0)
        assertEquals(SolidExpr.Rotate(SolidExpr.Cuboid(2.0, 1.0, 0.5), 0.0, 0.0, 45.0), zz.simplified())
        sameShape("Rz then Rz", zz, zz.simplified(), tolerance = 1e-12)

        // Refused, on purpose: two general Euler rotations compose to a matrix, and coming back out
        // to three angles has a gimbal case a rewrite must not get nearly right.
        val mixed = cuboid(2, 1, 0.5).turned(x = 20.0).turned(z = 25.0)
        assertEquals(mixed, mixed.simplified(), "an X rotation and a Z rotation must not be folded")
        val both = cuboid(2, 1, 0.5).turned(10.0, 20.0, 0.0).turned(5.0, 0.0, 0.0)
        assertEquals(both, both.simplified(), "a two-axis rotation must not be folded with anything")
    }

    @Test
    fun `nested booleans of one kind flatten into an n-ary node`() {
        // Not cosmetic. `Solid.union(list)` lets Manifold plan the whole batch; a fold of `+` is
        // n-1 nested operations, which is a different and worse computation.
        val nested = (sphere(1.0) + sphere(1.0).at(1, 0, 0)) + (sphere(1.0).at(2, 0, 0) + sphere(1.0).at(3, 0, 0))
        val flat = nested.simplified()
        assertTrue(flat is SolidExpr.Union, "four spheres should be one union, not a tree of them")
        assertEquals(4, flat.children.size, "four spheres should be one four-way union")
        sameShape("union flatten", nested, flat)

        val deep = intersectionOf(listOf(cuboid(3, 3, 3), intersectionOf(listOf(sphere(1.8), cuboid(2, 4, 4)))))
        val deepFlat = deep.simplified()
        assertEquals(3, (deepFlat as SolidExpr.Intersect).children.size)
        sameShape("intersect flatten", deep, deepFlat)
    }

    @Test
    fun `duplicate operands collapse, which a closure could never notice`() {
        // Two `sdfSphere(1.0)` lambdas are different objects forever. Two `SolidExpr.Sphere(1.0)`
        // are `==`, and that is the entire content of this rule.
        assertEquals(SolidExpr.Sphere(1.0), unionOf(listOf(sphere(1.0), sphere(1.0))).simplified())
        assertEquals(SolidExpr.Sphere(1.0), intersectionOf(listOf(sphere(1.0), sphere(1.0))).simplified())
        assertEquals(SolidExpr.Empty, (sphere(1.0) - sphere(1.0)).simplified())

        val mixed = unionOf(listOf(cuboid(2, 2, 2), sphere(1.4), cuboid(2, 2, 2), sphere(1.4), cuboid(2, 2, 2)))
        val once = mixed.simplified()
        assertEquals(2, (once as SolidExpr.Union).children.size)
        sameShape("dedupe", mixed, once)
    }

    @Test
    fun `an operand that cannot reach the body is removed, from bounds alone`() {
        // The rule that pays on a generated assembly. Nothing is sampled and nothing is built: the
        // bounds are computed from the expression.
        val plate = cuboid(4.0, 3.0, 1.0)
        val reaching = cylinder(3.0, 0.3).at(1.0, 0.0, 0.0)
        val nowhereNear = cylinder(3.0, 0.3).at(40.0, 0.0, 0.0)

        val cut = differenceOf(listOf(plate, reaching, nowhereNear))
        val simplified = cut.simplified()
        assertEquals(SolidExpr.Difference(listOf(plate, reaching)), simplified)
        sameShape("far cutter dropped", cut, simplified)

        assertEquals(SolidExpr.Empty, (plate and nowhereNear).simplified())
        assertEquals(plate, differenceOf(listOf(plate, nowhereNear)).simplified())

        // Conservative in the right direction: bounds that merely touch are not disjoint, and an
        // unbounded operand is never disjoint from anything.
        val touching = cuboid(1.0, 1.0, 1.0).at(2.0, 0.0, 0.0)
        assertTrue((plate and touching).simplified() is SolidExpr.Intersect, "overlapping bounds must survive")
        assertTrue((gyroid() and plate).simplified() is SolidExpr.Intersect, "an unbounded operand must survive")

        // And the payoff, measured: a plate with fifty bores of which six actually meet it.
        val many = differenceOf(
            listOf(plate) + (0 until 50).map { cylinder(3.0, 0.2).at(-1.5 + it * 1.3, 0.0, 0.0) },
        )
        val trimmed = many.simplified()
        println("50 bores -> ${(trimmed as SolidExpr.Difference).children.size - 1} that reach the plate; " +
            "GLSL ${many.toGlsl().statementCount} -> ${trimmed.toGlsl().statementCount} statements")
        assertTrue(trimmed.children.size - 1 <= 6, "only the bores over the plate should survive")
        assertTrue(
            trimmed.toGlsl().statementCount < many.toGlsl().statementCount / 4,
            "dropping 44 of 50 cutters should shrink the shader by much more than 4x",
        )
        sameShape("50 bores", many, trimmed)
    }

    @Test
    fun `a vanishing blend removes a refusal`() {
        // The most surprising thing a peephole optimiser in this package can do: move an expression
        // from the level-set fallback onto the exact CSG path.
        val blend = smoothUnion(sphere(1.0), cuboid(2, 0.5, 0.5), k = 0.0)
        assertTrue(blend.exactRefusal() != null, "a SmoothUnion always refuses, whatever its k")
        val plain = blend.simplified()
        assertEquals(null, plain.exactRefusal(), "with k = 0 it is a union, and a union is exact")
        assertTrue(plain is SolidExpr.Union)
        // And it is the same shape: smoothMin with k -> 0 is min.
        sameShape("k = 0 blend", blend, plain, tolerance = 1e-9)

        // A real blend is untouched.
        val real = smoothUnion(sphere(1.0), cuboid(2, 0.5, 0.5), k = 0.3)
        assertEquals(real, real.simplified())
        assertTrue(real.simplified().exactRefusal() != null)
    }

    @Test
    fun `Empty is absorbed everywhere it can be`() {
        val e = SolidExpr.Empty
        assertEquals(SolidExpr.Sphere(1.0), unionOf(listOf(sphere(1.0), e, e)).simplified())
        assertEquals(e, intersectionOf(listOf(sphere(1.0), e)).simplified())
        assertEquals(SolidExpr.Sphere(1.0), differenceOf(listOf(sphere(1.0), e)).simplified())
        assertEquals(e, differenceOf(listOf(e, sphere(1.0))).simplified())
        assertEquals(e, unionOf(emptyList()).simplified())
        assertEquals(e, e.at(1, 2, 3).sized(4.0).simplified())
    }

    @Test
    fun `simplification reaches a fixed point`() {
        // Flattening a boolean exposes a fold that exposes an identity, so one pass is not enough —
        // and a rule that oscillated would spin. Both halves are checked.
        val messy = unionOf(
            listOf(
                unionOf(listOf(sphere(1.0).at(0, 0, 0), SolidExpr.Empty)).at(1.0, 0.0, 0.0).at(2.0, 0.0, 0.0),
                unionOf(listOf(SolidExpr.Empty)),
                sphere(1.0).at(3.0, 0.0, 0.0),
            ),
        )
        val once = messy.simplified()
        assertEquals(once, once.simplified(), "simplifying twice must change nothing")
        assertEquals(SolidExpr.Translate(SolidExpr.Sphere(1.0), 3.0, 0.0, 0.0), once,
            "the two branches are the same sphere at the same place, so it should collapse to one")
        println("messy: ${messy.nodeCount()} nodes -> ${once.nodeCount()}")
        assertTrue(once.nodeCount() < messy.nodeCount())
    }

    @Test
    fun `profiles simplify too, and the extrusion follows`() {
        val p = Profile.rect(2.0, 1.0).translated(1.0, 0.0).translated(0.5, 0.5).turned(0.0).sized(1.0)
        assertEquals(Profile.Translate(Profile.Rect(2.0, 1.0), 1.5, 0.5), p.simplified())

        val solid = p.extruded(2.0)
        val simplified = solid.simplified()
        assertEquals(Profile.Translate(Profile.Rect(2.0, 1.0), 1.5, 0.5), (simplified as SolidExpr.Extrude).profile)
        sameShape("profile inside an extrude", solid, simplified, tolerance = 1e-12)

        // 2D rotations are scalars, so unlike the 3D case they always compose.
        assertEquals(
            Profile.Rotate(Profile.Rect(1.0, 1.0), 75.0),
            Profile.rect(1.0, 1.0).turned(30.0).turned(45.0).simplified(),
        )
    }

    @Test
    fun `the report says what changed, including the path`() {
        val before = smoothUnion(cuboid(2, 2, 1), cuboid(2, 2, 1), k = 0.0)
        val text = before.simplificationReport()
        println(text)
        assertTrue(text.contains("level set -> exact"), text)
        assertTrue(text.contains("nodes 3 -> 1"), text)
    }
}
