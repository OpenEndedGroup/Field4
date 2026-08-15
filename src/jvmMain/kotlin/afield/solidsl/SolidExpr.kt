@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.stdlib.AxisAlignedBoundingBox3D
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A 3D solid written down as a **value** instead of as a closure — the symbolic notation that
 * `afield.stdlib`'s signed-distance family and `afield.manifold`'s exact CSG kernel can *both*
 * evaluate.
 *
 * ```kotlin
 * val bracket = cuboid(60, 40, 8) - Profile.rect(26, 9).offset(4.5).extruded(30).at(0, 0, -15)
 *
 * bracket.toScalarField()          // -> (Vec3) -> Float, marchable, raymarchable
 * bracket.exactSolid()             // -> Lowered(Solid) — Manifold's exact booleans
 * bracket.toGlsl()                 // -> a float sdSolid(vec3) you can put in a shader
 * println(bracket)                 // -> Difference(Cuboid(60, 40, 8), Translate(...))
 * ```
 *
 * # Why a notation rather than more combinators
 *
 * `mesh_sdf.kt:70` declares `typealias ScalarField3D = (Vec3) -> Float` and builds twenty-five
 * primitives and combinators on top of it. Every one of them returns a Kotlin lambda, and a lambda —
 * as `ddf732260` put it about RectSL's 2D tracks, in the commit that made *those* symbolic — "cannot
 * be printed, compared, serialized, rewritten, or emitted as GLSL". The 3D family never got the same
 * treatment. This is it.
 *
 * The reason it is worth doing *now*, and not any time in the last three years, is that there is
 * finally a second thing worth lowering to. `afield.manifold` (`9384444a4`) binds
 * [Manifold](https://github.com/elalish/manifold): a boolean kernel whose answer is a **guaranteed
 * closed** solid with an exact volume, an exact area and a countable genus. So the same written shape
 * has two completely independent readings:
 *
 * | | [toScalarField] | [exactSolid] |
 * |---|---|---|
 * | what it is | a distance field, sampled | a CSG tree, evaluated exactly |
 * | how a surface comes out | marching cubes, `O(h²)` | it *is* the surface |
 * | curved primitives | true spheres and cylinders | inscribed polyhedra, `segments` of them |
 * | smooth blends, gyroids | native | **refused**, with a reason |
 * | can be raymarched on a GPU | yes ([toGlsl]) | no |
 * | can be subtracted from safely | not reliably | always |
 *
 * Neither is a superset of the other, which is the whole point: [afield.solidsl.SolidAgreement] runs
 * both on one source and measures how far apart they land. Two implementations that share no code
 * agreeing to five figures is a much better argument that either one is right than any number of
 * assertions against remembered constants.
 *
 * # Conventions are upstream's, deliberately
 *
 * Every primitive here is **centred on the origin**, and everything else follows Manifold rather than
 * being tidied up:
 *
 *  - [Cylinder] runs along **Z**.
 *  - [Extrude] runs from `z = 0` to `z = height` — *not* centred, because `Section.extruded` is not.
 *  - [Revolve] turns about **Y**, with the profile at `x >= 0` — because `Section.revolved` does.
 *  - [Rotate] is in **degrees**, applied X then Y then Z, because `Solid.rotated` is.
 *
 * Inventing a nicer convention and translating at the boundary would add exactly one class of bug
 * (a silently mirrored or transposed solid) to the one thing this package exists to rule out.
 *
 * # There is no `Torus`
 *
 * On purpose. `torus(R, r)` is a *function* that builds
 * `Profile.circle(r).translated(R, 0.0).revolved()`, which is what a torus is. Keeping it out of the
 * algebra keeps the algebra small and makes the revolve path load-bearing rather than decorative.
 *
 * # Mutation, and why these are `data class`es
 *
 * Every node is a `data class` over `Double` and immutable `P2`, so structural equality, `hashCode`
 * and a readable `toString` are free and a node can be a map key. [Profile.Polygon] holds [P2] rather
 * than [Vec2] for exactly that reason: `Vec2` is `Vector3f`'s two-dimensional cousin and is
 * *mutable*, and a symbolic expression whose leaves can change under it is not symbolic.
 *
 * @see toScalarField for the approximate-but-total lowering
 * @see exactSolid for the exact-but-refusing one
 * @see buildSolid for the hybrid, which marches only the subtrees that have no exact form
 */
sealed interface SolidExpr {

    // ==========================================================================================
    // Primitives
    // ==========================================================================================

    /**
     * A sphere of [radius] centred on the origin.
     *
     * @param segments how many segments Manifold's sphere gets. `0` consults the global circular
     *   quality. This number has **no effect on [toScalarField]**, which is a true sphere — the gap
     *   between the two is the inscribed-polyhedron deficit, and it is the floor that
     *   [SolidAgreement] converges to rather than to zero.
     */
    data class Sphere(val radius: Double, val segments: Int = 0) : SolidExpr

    /** An axis-aligned box of full extents [x] by [y] by [z], centred on the origin. */
    data class Cuboid(val x: Double, val y: Double, val z: Double) : SolidExpr

    /**
     * A cylinder, cone or truncated cone along **Z**, centred on the origin, so `z` runs from
     * `-height/2` to `+height/2`.
     *
     * @param radiusLow the radius at `-height/2`, [radiusHigh] the radius at `+height/2`. Equal for a
     *   cylinder; `0.0` at one end for a cone.
     */
    data class Cylinder(
        val height: Double,
        val radiusLow: Double,
        val radiusHigh: Double = radiusLow,
        val segments: Int = 0,
    ) : SolidExpr

    /**
     * A [Profile] swept along **Z** from `z = 0` to `z = height`, optionally twisting and tapering.
     *
     * @param twistDegrees total rotation of the profile between the bottom and the top.
     * @param scaleTop the profile's scale at the top; `1.0` for a straight prism.
     * @param slices intermediate rings Manifold builds. Only meaningful when twisting or tapering,
     *   and it is the *only* thing that makes the exact lowering approximate for those cases: a
     *   twisted extrusion is a stack of `slices` straight prisms, where the field lowering twists
     *   continuously. `0` lets Manifold choose.
     */
    data class Extrude(
        val profile: Profile,
        val height: Double,
        val twistDegrees: Double = 0.0,
        val scaleTop: Double = 1.0,
        val slices: Int = 0,
    ) : SolidExpr

    /**
     * A [Profile] revolved about **Z**: the profile's `x` becomes the radius in the XY plane and its
     * `y` becomes `z`. The profile must lie at `x >= 0`; anything at negative x is discarded rather
     * than folded, which is Manifold's behaviour and is therefore this notation's.
     *
     * **Z, and not Y.** `Section.revolved`'s own KDoc says "about the Y axis" and it is wrong: a
     * 0.6-square offset to `x = 1.2` comes back with bounds `((-1.5, -1.5, -0.3), (1.5, 1.5, 0.3))`,
     * which is a ring in the XY plane. Point-membership between the two lowerings agrees on 100% of
     * samples under this reading and on about 65% under the documented one, which is how it was
     * settled — see `SolidSLConventionProbe`. Upstream's convention is at least *self*-consistent:
     * revolving about Z matches [Cylinder] running along Z.
     *
     * @param degrees less than 360 leaves a wedge, swept counter-clockwise from +X.
     */
    data class Revolve(
        val profile: Profile,
        val degrees: Double = 360.0,
        val segments: Int = 0,
    ) : SolidExpr

    /**
     * The gyroid, `sin x cos y + sin y cos z + sin z cos x`, thresholded to a sheet of [thickness].
     *
     * Unbounded, and not a distance function, and it has no CSG form at all — so it is here precisely
     * *because* it is the shape that forces the exact lowering to refuse. Intersect it with something
     * bounded before asking for a solid.
     */
    data class Gyroid(val scale: Double = 3.0, val thickness: Double = 0.2) : SolidExpr

    /** Nothing. The unit of [Union] and the annihilator of [Intersect]. */
    data object Empty : SolidExpr

    // ==========================================================================================
    // Transforms
    // ==========================================================================================

    data class Translate(val child: SolidExpr, val x: Double, val y: Double, val z: Double) : SolidExpr

    /** Rotation by Euler angles **in degrees**, applied X then Y then Z — Manifold's own order. */
    data class Rotate(val child: SolidExpr, val x: Double, val y: Double, val z: Double) : SolidExpr

    /**
     * Scale per axis about the origin.
     *
     * A **uniform** scale keeps the field an exact distance function; a non-uniform one does not, and
     * [fieldNotes] says so. The exact lowering is exact either way — Manifold moves vertices.
     */
    data class Scale(val child: SolidExpr, val x: Double, val y: Double, val z: Double) : SolidExpr

    /** Mirror through the plane with this normal, through the origin. */
    data class Mirror(val child: SolidExpr, val x: Double, val y: Double, val z: Double) : SolidExpr

    /**
     * This shape grown outward by [radius], rounding every convex edge — `f(p) - r`.
     *
     * **Refused by the exact lowering**, and this is the more interesting of the two refusal flavours
     * in this package. Manifold *can* express a growth: `minkowskiSum` with a sphere. But that sphere
     * is an inscribed polyhedron, so the result is strictly *inside* the true offset by up to the
     * sphere's sagitta — and unlike a faceted [Sphere], that error does not sit at a primitive where
     * a reader can see it, it silently rounds every edge of a whole assembly by slightly too little.
     * Refining the march does not close that gap, because the gap is not the march's. So this takes
     * the level set, and says why.
     */
    data class Round(val child: SolidExpr, val radius: Double) : SolidExpr

    /** A hollow shell of wall thickness `2 * thickness` about this shape's surface — `|f(p)| - t`. */
    data class Shell(val child: SolidExpr, val thickness: Double) : SolidExpr

    // ==========================================================================================
    // Booleans
    // ==========================================================================================

    /**
     * Everything inside any child.
     *
     * N-ary rather than binary, and that is not just sugar: `Solid.union(list)` lets Manifold *plan*
     * the whole batch, which is a different and better computation than folding `+` over the same
     * solids. A notation that could only say `a + b + c` would be unable to ask for it.
     */
    data class Union(val children: List<SolidExpr>) : SolidExpr

    /** Everything inside every child. */
    data class Intersect(val children: List<SolidExpr>) : SolidExpr

    /** The first child with all the others removed. */
    data class Difference(val children: List<SolidExpr>) : SolidExpr

    /**
     * A union with a fillet of radius roughly [k] where the surfaces meet — the polynomial smooth
     * minimum, the operator that makes field modelling feel like clay.
     *
     * **Refused by the exact lowering, permanently.** There is no CSG form: the blend surface lies on
     * *neither* operand, so no sequence of unions, intersections and differences of the two can
     * produce it. This is the honest refusal the package is built around, and it takes the level set.
     */
    data class SmoothUnion(val a: SolidExpr, val b: SolidExpr, val k: Double = 0.2) : SolidExpr

    /** [SmoothUnion]'s counterpart for subtraction — a drilled hole whose rim is not a knife edge. */
    data class SmoothDifference(val a: SolidExpr, val b: SolidExpr, val k: Double = 0.2) : SolidExpr

    companion object {
        /** [Union] of a handful named inline. */
        fun union(vararg children: SolidExpr): SolidExpr = Union(children.toList())

        /** [Intersect] of a handful named inline. */
        fun intersect(vararg children: SolidExpr): SolidExpr = Intersect(children.toList())

        /** [Difference] of a handful named inline: the first minus all the rest. */
        fun difference(vararg children: SolidExpr): SolidExpr = Difference(children.toList())
    }
}

/**
 * A 2D region, written down the same way — the thing [SolidExpr.Extrude] sweeps and
 * [SolidExpr.Revolve] turns.
 *
 * Lowers to a 2D signed distance function on one side and to a `afield.manifold.Section` on the
 * other, by exactly the same discipline as [SolidExpr].
 *
 * The interesting member is [Offset]. `Section.offset(d, JoinType.ROUND)` is *definitionally* the
 * Minkowski sum with a disc of radius `d`, and for a region whose field is a true signed distance
 * function that is *exactly* `sdf(p) - d`. So the two lowerings of an offset profile agree for a
 * mathematical reason rather than a numerical coincidence — which is why the rounded slot in the
 * cross-validation corpus is a better test than another cuboid. The other [JoinType]s have no such
 * form, which is why this node does not offer them.
 */
sealed interface Profile {

    /** A disc of [radius] centred on the origin. */
    data class Circle(val radius: Double, val segments: Int = 0) : Profile

    /** An axis-aligned rectangle of full extents [x] by [y], centred on the origin. */
    data class Rect(val x: Double, val y: Double) : Profile

    /**
     * A closed polygon through [points], counter-clockwise for a positive region.
     *
     * The one profile with **no curvature anywhere**, so an extrusion or revolution of it has a
     * genuinely polyhedral exact lowering and the cross-validation against it converges to zero
     * rather than to a faceting floor. That makes it the most informative shape in the corpus.
     */
    data class Polygon(val points: List<P2>) : Profile

    /** This region grown by [delta] with a round join — see the note on [Profile]. */
    data class Offset(val child: Profile, val delta: Double, val segments: Int = 0) : Profile

    data class Translate(val child: Profile, val x: Double, val y: Double) : Profile

    /** Rotation about the origin, **in degrees**, counter-clockwise. */
    data class Rotate(val child: Profile, val degrees: Double) : Profile

    data class Scale(val child: Profile, val x: Double, val y: Double) : Profile

    data class Union(val children: List<Profile>) : Profile

    data class Intersect(val children: List<Profile>) : Profile

    data class Difference(val children: List<Profile>) : Profile

    data object Empty : Profile

    companion object {
        /** A disc of [radius]. */
        fun circle(radius: Number, segments: Int = 0): Profile = Circle(radius.toDouble(), segments)

        /** A rectangle of full extents [x] by [y], centred on the origin. */
        fun rect(x: Number, y: Number = x): Profile = Rect(x.toDouble(), y.toDouble())

        /** A closed polygon through these points. */
        fun polygon(points: List<P2>): Profile = Polygon(points)

        /** A closed polygon through these points, given as [Vec2]s (copied, so later edits do not leak in). */
        fun polygonOf(points: List<Vec2>): Profile = Polygon(points.map { P2(it.x.toDouble(), it.y.toDouble()) })

        /** A regular [n]-gon inscribed in [radius], first vertex on +X. */
        fun ngon(n: Int, radius: Number): Profile {
            require(n >= 3) { "an n-gon needs at least 3 sides, got $n" }
            val r = radius.toDouble()
            return Polygon((0 until n).map { P2(r * cos(2 * PI * it / n), r * sin(2 * PI * it / n)) })
        }

        /**
         * A [points]-pointed star between [innerRadius] and [outerRadius].
         *
         * Non-convex on purpose: a star extruded is the shape that catches a marcher confusing a
         * concave corner for a hole, and a polygon SDF that gets the winding test wrong.
         */
        fun star(points: Int, outerRadius: Number, innerRadius: Number): Profile {
            require(points >= 2) { "a star needs at least 2 points, got $points" }
            val ro = outerRadius.toDouble()
            val ri = innerRadius.toDouble()
            return Polygon(
                (0 until 2 * points).map {
                    val r = if (it % 2 == 0) ro else ri
                    val a = PI * it / points
                    P2(r * cos(a), r * sin(a))
                },
            )
        }

        fun union(vararg children: Profile): Profile = Union(children.toList())
        fun intersect(vararg children: Profile): Profile = Intersect(children.toList())
        fun difference(vararg children: Profile): Profile = Difference(children.toList())
    }
}

/**
 * An immutable 2D point, in `Double`.
 *
 * [Vec2] would have been the obvious choice and is the wrong one: it is `org.joml.Vector2f`, it is
 * mutable, and it is `Float`. A symbolic expression whose leaves someone else can move is not
 * symbolic, and the profile coordinates go straight into Clipper, which works in `Double`.
 */
data class P2(val x: Double, val y: Double) {
    constructor(x: Number, y: Number) : this(x.toDouble(), y.toDouble())

    fun toVec2(): Vec2 = Vec2(x.toFloat(), y.toFloat())
}

// ==============================================================================================
// The terse surface — what a box actually types
// ==============================================================================================

/** A sphere of [radius] centred on the origin. See [SolidExpr.Sphere] for what `segments` costs. */
fun sphere(radius: Number = 1.0, segments: Int = 0): SolidExpr =
    SolidExpr.Sphere(radius.toDouble(), segments)

/** An axis-aligned box of full extents [x] by [y] by [z], centred on the origin. */
fun cuboid(x: Number, y: Number, z: Number): SolidExpr =
    SolidExpr.Cuboid(x.toDouble(), y.toDouble(), z.toDouble())

/** A cube of side [size], centred on the origin. */
fun cuboid(size: Number): SolidExpr = cuboid(size, size, size)

/** A cylinder along **Z**, centred on the origin. Pass a different [radiusTop] for a frustum. */
fun cylinder(height: Number, radius: Number, radiusTop: Number = radius, segments: Int = 0): SolidExpr =
    SolidExpr.Cylinder(height.toDouble(), radius.toDouble(), radiusTop.toDouble(), segments)

/** A cone along **Z**, centred on the origin, point at `+height/2`. */
fun cone(height: Number, radius: Number, segments: Int = 0): SolidExpr =
    SolidExpr.Cylinder(height.toDouble(), radius.toDouble(), 0.0, segments)

/**
 * A torus: the ring of tube radius [minor] at distance [major] from the **Z** axis, lying in the XY
 * plane.
 *
 * Not a primitive — this *is* `Profile.circle(minor).translated(major, 0).revolved()`, spelled out,
 * and it lowers through the revolve path like any other profile. Keeping it out of the algebra keeps
 * the algebra small and makes [SolidExpr.Revolve] load-bearing rather than decorative.
 *
 * Note the axis: `afield.stdlib.sdfTorus` puts its ring in the **XZ** plane, this one is in **XY**,
 * because [SolidExpr.Revolve] follows Manifold. `torus(R, r).turned(90.0)` is the `sdfTorus` one.
 */
fun torus(major: Number, minor: Number, segments: Int = 0): SolidExpr =
    Profile.circle(minor, segments).translated(major, 0.0).revolved(segments = segments)

/** The gyroid sheet — see [SolidExpr.Gyroid], and note that it is unbounded. */
fun gyroid(scale: Number = 3.0, thickness: Number = 0.2): SolidExpr =
    SolidExpr.Gyroid(scale.toDouble(), thickness.toDouble())

/** Union — `Solid.union` in the exact lowering, pointwise `min` in the field one. */
operator fun SolidExpr.plus(other: SolidExpr): SolidExpr = when {
    this is SolidExpr.Union && other is SolidExpr.Union -> SolidExpr.Union(children + other.children)
    this is SolidExpr.Union -> SolidExpr.Union(children + other)
    else -> SolidExpr.Union(listOf(this, other))
}

/** Difference — this shape with [other] removed. */
operator fun SolidExpr.minus(other: SolidExpr): SolidExpr =
    if (this is SolidExpr.Difference) SolidExpr.Difference(children + other)
    else SolidExpr.Difference(listOf(this, other))

/** Intersection. */
infix fun SolidExpr.and(other: SolidExpr): SolidExpr = when {
    this is SolidExpr.Intersect && other is SolidExpr.Intersect -> SolidExpr.Intersect(children + other.children)
    this is SolidExpr.Intersect -> SolidExpr.Intersect(children + other)
    else -> SolidExpr.Intersect(listOf(this, other))
}

/** Moved by ([x], [y], [z]). */
fun SolidExpr.at(x: Number, y: Number, z: Number): SolidExpr =
    SolidExpr.Translate(this, x.toDouble(), y.toDouble(), z.toDouble())

/** Moved by [offset]. */
fun SolidExpr.at(offset: Vec3): SolidExpr = at(offset.x, offset.y, offset.z)

/** Rotated by Euler angles **in degrees**, X then Y then Z. */
fun SolidExpr.turned(x: Number = 0.0, y: Number = 0.0, z: Number = 0.0): SolidExpr =
    SolidExpr.Rotate(this, x.toDouble(), y.toDouble(), z.toDouble())

/** Uniformly scaled about the origin. */
fun SolidExpr.sized(factor: Number): SolidExpr =
    SolidExpr.Scale(this, factor.toDouble(), factor.toDouble(), factor.toDouble())

/** Scaled per axis about the origin — see [SolidExpr.Scale] for what a non-uniform one costs. */
fun SolidExpr.sized(x: Number, y: Number, z: Number): SolidExpr =
    SolidExpr.Scale(this, x.toDouble(), y.toDouble(), z.toDouble())

/** Mirrored through the plane with this normal, through the origin. */
fun SolidExpr.mirrored(x: Number, y: Number, z: Number): SolidExpr =
    SolidExpr.Mirror(this, x.toDouble(), y.toDouble(), z.toDouble())

/** Grown outward by [radius], rounding every convex edge. See [SolidExpr.Round] — the exact path refuses. */
fun SolidExpr.rounded(radius: Number): SolidExpr = SolidExpr.Round(this, radius.toDouble())

/** Hollowed to a wall of `2 * thickness`. See [SolidExpr.Shell] — the exact path refuses. */
fun SolidExpr.shelled(thickness: Number): SolidExpr = SolidExpr.Shell(this, thickness.toDouble())

/** A filleted union of radius roughly [k]. See [SolidExpr.SmoothUnion] — the exact path refuses. */
fun smoothUnion(a: SolidExpr, b: SolidExpr, k: Number = 0.2): SolidExpr =
    SolidExpr.SmoothUnion(a, b, k.toDouble())

/** A filleted difference. See [SolidExpr.SmoothDifference] — the exact path refuses. */
fun smoothDifference(a: SolidExpr, b: SolidExpr, k: Number = 0.2): SolidExpr =
    SolidExpr.SmoothDifference(a, b, k.toDouble())

/** [SolidExpr.Union] of a list — the batch form, which is what Manifold wants. */
fun unionOf(children: List<SolidExpr>): SolidExpr = SolidExpr.Union(children)

/** [SolidExpr.Intersect] of a list. */
fun intersectionOf(children: List<SolidExpr>): SolidExpr = SolidExpr.Intersect(children)

/** [SolidExpr.Difference] of a list: the first minus all the rest. */
fun differenceOf(children: List<SolidExpr>): SolidExpr = SolidExpr.Difference(children)

// ---- profiles ----

operator fun Profile.plus(other: Profile): Profile = when {
    this is Profile.Union && other is Profile.Union -> Profile.Union(children + other.children)
    this is Profile.Union -> Profile.Union(children + other)
    else -> Profile.Union(listOf(this, other))
}

operator fun Profile.minus(other: Profile): Profile =
    if (this is Profile.Difference) Profile.Difference(children + other)
    else Profile.Difference(listOf(this, other))

infix fun Profile.and(other: Profile): Profile = when {
    this is Profile.Intersect && other is Profile.Intersect -> Profile.Intersect(children + other.children)
    this is Profile.Intersect -> Profile.Intersect(children + other)
    else -> Profile.Intersect(listOf(this, other))
}

/** Grown by [delta] with a round join — see the note on [Profile]. Negative shrinks. */
fun Profile.offset(delta: Number, segments: Int = 0): Profile =
    Profile.Offset(this, delta.toDouble(), segments)

fun Profile.translated(x: Number, y: Number): Profile =
    Profile.Translate(this, x.toDouble(), y.toDouble())

/** Rotated about the origin, **in degrees**, counter-clockwise. */
fun Profile.turned(degrees: Number): Profile = Profile.Rotate(this, degrees.toDouble())

fun Profile.sized(factor: Number): Profile = Profile.Scale(this, factor.toDouble(), factor.toDouble())

fun Profile.sized(x: Number, y: Number): Profile = Profile.Scale(this, x.toDouble(), y.toDouble())

/** Swept along **Z** from `z = 0` to `z = height`. See [SolidExpr.Extrude]. */
fun Profile.extruded(
    height: Number,
    twistDegrees: Number = 0.0,
    scaleTop: Number = 1.0,
    slices: Int = 0,
): SolidExpr = SolidExpr.Extrude(this, height.toDouble(), twistDegrees.toDouble(), scaleTop.toDouble(), slices)

/** Revolved about **Y**; the profile must lie at `x >= 0`. See [SolidExpr.Revolve]. */
fun Profile.revolved(degrees: Number = 360.0, segments: Int = 0): SolidExpr =
    SolidExpr.Revolve(this, degrees.toDouble(), segments)

// ==============================================================================================
// Symbolic bounds
// ==============================================================================================

/**
 * A conservative axis-aligned bound on this shape, computed **from the expression** with no sampling
 * — or `null` when the shape is unbounded ([SolidExpr.Gyroid], or anything unioned with one).
 *
 * This is the first thing a notation buys that a closure cannot: [buildSolid] needs a window to march
 * a refused subtree in, and a `(Vec3) -> Float` cannot be asked where it is. A bound is conservative,
 * not tight — a rotated box reports the bound of its rotated corners, which is larger than the box —
 * and that is the right trade for a march window.
 */
fun SolidExpr.bounds(): AxisAlignedBoundingBox3D? = when (this) {
    is SolidExpr.Sphere -> boundsOfHalfExtents(radius, radius, radius)
    is SolidExpr.Cuboid -> boundsOfHalfExtents(x / 2, y / 2, z / 2)
    is SolidExpr.Cylinder -> {
        val r = max(abs(radiusLow), abs(radiusHigh))
        boundsOfHalfExtents(r, r, abs(height) / 2)
    }
    is SolidExpr.Extrude -> {
        val p = profile.bounds()
        if (p == null) null else {
            val s = max(1.0, abs(scaleTop))
            val (lo, hi) = if (twistDegrees != 0.0) {
                // A twisted profile sweeps the circle through its own farthest corner.
                val r = p.radius() * s
                P2(-r, -r) to P2(r, r)
            } else {
                P2(p.minX * s, p.minY * s) to P2(p.maxX * s, p.maxY * s)
            }
            AxisAlignedBoundingBox3D(
                Vec3(lo.x.toFloat(), lo.y.toFloat(), min(0.0, height).toFloat()),
                Vec3(hi.x.toFloat(), hi.y.toFloat(), max(0.0, height).toFloat()),
            )
        }
    }
    is SolidExpr.Revolve -> {
        val p = profile.bounds()
        if (p == null) null else {
            // The profile lives at x >= 0; that x sweeps out into the XY plane and its y becomes z.
            val r = max(0.0, p.maxX)
            AxisAlignedBoundingBox3D(
                Vec3((-r).toFloat(), (-r).toFloat(), p.minY.toFloat()),
                Vec3(r.toFloat(), r.toFloat(), p.maxY.toFloat()),
            )
        }
    }
    is SolidExpr.Gyroid -> null
    SolidExpr.Empty -> EMPTY_BOUNDS
    is SolidExpr.Translate -> child.bounds()?.let {
        AxisAlignedBoundingBox3D(
            Vec3(it.min).add(x.toFloat(), y.toFloat(), z.toFloat()),
            Vec3(it.max).add(x.toFloat(), y.toFloat(), z.toFloat()),
        )
    }
    is SolidExpr.Rotate -> child.bounds()?.let { b ->
        val m = eulerMatrix(x, y, z)
        boundsOfCorners(b) { p -> applyMatrix(m, p) }
    }
    is SolidExpr.Scale -> child.bounds()?.let { b -> boundsOfCorners(b) { p -> Triple(p.first * x, p.second * y, p.third * z) } }
    is SolidExpr.Mirror -> child.bounds()?.let { b ->
        val n = normalize3(x, y, z)
        boundsOfCorners(b) { p ->
            val d = 2.0 * (p.first * n.first + p.second * n.second + p.third * n.third)
            Triple(p.first - d * n.first, p.second - d * n.second, p.third - d * n.third)
        }
    }
    is SolidExpr.Round -> child.bounds()?.expandedBy(abs(radius))
    is SolidExpr.Shell -> child.bounds()?.expandedBy(abs(thickness))
    is SolidExpr.Union -> {
        val bs = children.map { it.bounds() }
        if (bs.isEmpty()) EMPTY_BOUNDS else if (bs.any { it == null }) null else bs.filterNotNull().reduce(::unionBounds)
    }
    is SolidExpr.Intersect -> {
        val bs = children.mapNotNull { it.bounds() }
        if (children.isEmpty()) EMPTY_BOUNDS else if (bs.isEmpty()) null else bs.reduce(::intersectBounds)
    }
    is SolidExpr.Difference -> children.firstOrNull()?.bounds() ?: EMPTY_BOUNDS
    is SolidExpr.SmoothUnion -> {
        val ba = a.bounds(); val bb = b.bounds()
        if (ba == null || bb == null) null else unionBounds(ba, bb).expandedBy(abs(k))
    }
    // A smooth difference's fillet bulges outward from `a` where `b` meets it, so `a` alone is not
    // conservative; `a` grown by k is.
    is SolidExpr.SmoothDifference -> a.bounds()?.expandedBy(abs(k))
}

/** A conservative bound on this 2D region, or `null` if it is unbounded. */
fun Profile.bounds(): Rect2? = when (this) {
    is Profile.Circle -> Rect2(-radius, -radius, radius, radius)
    is Profile.Rect -> Rect2(-x / 2, -y / 2, x / 2, y / 2)
    is Profile.Polygon -> if (points.isEmpty()) EMPTY_RECT2 else Rect2(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
    )
    is Profile.Offset -> child.bounds()?.expandedBy(max(0.0, delta))
    is Profile.Translate -> child.bounds()?.let { Rect2(it.minX + x, it.minY + y, it.maxX + x, it.maxY + y) }
    is Profile.Rotate -> child.bounds()?.let { b ->
        val a = Math.toRadians(degrees); val c = cos(a); val s = sin(a)
        val xs = listOf(b.minX, b.maxX); val ys = listOf(b.minY, b.maxY)
        val pts = xs.flatMap { px -> ys.map { py -> P2(c * px - s * py, s * px + c * py) } }
        Rect2(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }
    is Profile.Scale -> child.bounds()?.let { b ->
        Rect2(
            min(b.minX * x, b.maxX * x), min(b.minY * y, b.maxY * y),
            max(b.minX * x, b.maxX * x), max(b.minY * y, b.maxY * y),
        )
    }
    is Profile.Union -> {
        val bs = children.map { it.bounds() }
        if (bs.isEmpty()) EMPTY_RECT2 else if (bs.any { it == null }) null else bs.filterNotNull().reduce(Rect2::union)
    }
    is Profile.Intersect -> {
        val bs = children.mapNotNull { it.bounds() }
        if (children.isEmpty()) EMPTY_RECT2 else if (bs.isEmpty()) null else bs.reduce(Rect2::intersect)
    }
    is Profile.Difference -> children.firstOrNull()?.bounds() ?: EMPTY_RECT2
    Profile.Empty -> EMPTY_RECT2
}

/** A 2D bounding rectangle, in `Double` — [Profile]'s counterpart of [AxisAlignedBoundingBox3D]. */
data class Rect2(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun union(other: Rect2) = Rect2(
        min(minX, other.minX), min(minY, other.minY), max(maxX, other.maxX), max(maxY, other.maxY),
    )

    fun intersect(other: Rect2) = Rect2(
        max(minX, other.minX), max(minY, other.minY), min(maxX, other.maxX), min(maxY, other.maxY),
    )

    fun expandedBy(m: Double) = Rect2(minX - m, minY - m, maxX + m, maxY + m)

    /** The distance from the origin to the farthest corner — the radius a twist sweeps through. */
    fun radius(): Double = listOf(
        hypot(minX, minY), hypot(minX, maxY), hypot(maxX, minY), hypot(maxX, maxY),
    ).max()
}

private val EMPTY_RECT2 = Rect2(0.0, 0.0, 0.0, 0.0)

private val EMPTY_BOUNDS = AxisAlignedBoundingBox3D(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))

private fun boundsOfHalfExtents(hx: Double, hy: Double, hz: Double) = AxisAlignedBoundingBox3D(
    Vec3((-abs(hx)).toFloat(), (-abs(hy)).toFloat(), (-abs(hz)).toFloat()),
    Vec3(abs(hx).toFloat(), abs(hy).toFloat(), abs(hz).toFloat()),
)

/** These bounds grown outward by [m] on every axis. */
fun AxisAlignedBoundingBox3D.expandedBy(m: Double): AxisAlignedBoundingBox3D = AxisAlignedBoundingBox3D(
    Vec3(min.x - m.toFloat(), min.y - m.toFloat(), min.z - m.toFloat()),
    Vec3(max.x + m.toFloat(), max.y + m.toFloat(), max.z + m.toFloat()),
)

internal fun unionBounds(a: AxisAlignedBoundingBox3D, b: AxisAlignedBoundingBox3D) = AxisAlignedBoundingBox3D(
    Vec3(min(a.min.x, b.min.x), min(a.min.y, b.min.y), min(a.min.z, b.min.z)),
    Vec3(max(a.max.x, b.max.x), max(a.max.y, b.max.y), max(a.max.z, b.max.z)),
)

internal fun intersectBounds(a: AxisAlignedBoundingBox3D, b: AxisAlignedBoundingBox3D) = AxisAlignedBoundingBox3D(
    Vec3(max(a.min.x, b.min.x), max(a.min.y, b.min.y), max(a.min.z, b.min.z)),
    Vec3(min(a.max.x, b.max.x), min(a.max.y, b.max.y), min(a.max.z, b.max.z)),
)

private inline fun boundsOfCorners(
    b: AxisAlignedBoundingBox3D,
    map: (Triple<Double, Double, Double>) -> Triple<Double, Double, Double>,
): AxisAlignedBoundingBox3D {
    var lox = Double.MAX_VALUE; var loy = Double.MAX_VALUE; var loz = Double.MAX_VALUE
    var hix = -Double.MAX_VALUE; var hiy = -Double.MAX_VALUE; var hiz = -Double.MAX_VALUE
    for (i in 0..7) {
        val px = if (i and 1 == 0) b.min.x.toDouble() else b.max.x.toDouble()
        val py = if (i and 2 == 0) b.min.y.toDouble() else b.max.y.toDouble()
        val pz = if (i and 4 == 0) b.min.z.toDouble() else b.max.z.toDouble()
        val (qx, qy, qz) = map(Triple(px, py, pz))
        lox = min(lox, qx); loy = min(loy, qy); loz = min(loz, qz)
        hix = max(hix, qx); hiy = max(hiy, qy); hiz = max(hiz, qz)
    }
    return AxisAlignedBoundingBox3D(
        Vec3(lox.toFloat(), loy.toFloat(), loz.toFloat()),
        Vec3(hix.toFloat(), hiy.toFloat(), hiz.toFloat()),
    )
}

// ==============================================================================================
// Shared rotation arithmetic
// ==============================================================================================

/**
 * The 3x3 rotation matrix for Euler angles **in degrees**, applied X then Y then Z — i.e.
 * `R = Rz · Ry · Rx`, which is `manifold_rotate`'s order.
 *
 * Row-major, as nine doubles. Written out rather than delegated to [afield.stdlib.Quat] because both
 * lowerings and the GLSL emitter need the *same* nine numbers, and the one place a convention can go
 * wrong should be one place.
 */
internal fun eulerMatrix(xDeg: Double, yDeg: Double, zDeg: Double): DoubleArray {
    val a = Math.toRadians(xDeg); val b = Math.toRadians(yDeg); val c = Math.toRadians(zDeg)
    val ca = cos(a); val sa = sin(a)
    val cb = cos(b); val sb = sin(b)
    val cc = cos(c); val sc = sin(c)
    // Rz(c) * Ry(b) * Rx(a)
    return doubleArrayOf(
        cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa,
        sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa,
        -sb, cb * sa, cb * ca,
    )
}

/** The transpose of [eulerMatrix] — which, for a rotation, is its inverse. */
internal fun transpose3(m: DoubleArray) = doubleArrayOf(
    m[0], m[3], m[6],
    m[1], m[4], m[7],
    m[2], m[5], m[8],
)

internal fun applyMatrix(m: DoubleArray, p: Triple<Double, Double, Double>) = Triple(
    m[0] * p.first + m[1] * p.second + m[2] * p.third,
    m[3] * p.first + m[4] * p.second + m[5] * p.third,
    m[6] * p.first + m[7] * p.second + m[8] * p.third,
)

internal fun normalize3(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
    val l = kotlin.math.sqrt(x * x + y * y + z * z)
    require(l > 1e-12) { "a mirror plane needs a non-zero normal, got ($x, $y, $z)" }
    return Triple(x / l, y / l, z / l)
}
