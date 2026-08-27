@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.stdlib.AD
import afield.stdlib.ADTape
import afield.stdlib.abs
import afield.stdlib.clamp
import afield.stdlib.cos
import afield.stdlib.hypot
import afield.stdlib.max
import afield.stdlib.min
import afield.stdlib.sin
import afield.stdlib.sq
import afield.stdlib.sqrt
import afield.stdlib.valueAndGrad

/**
 * Lowering **(c)**: a [SolidExpr] as a value *and an exact gradient* — with respect to its own named
 * parameters, not only to position.
 *
 * ```kotlin
 * val template = (sphere(0.8) + cylinder(1.6, 0.3))
 *     .parameterize {
 *         param("r") { it.node == "Sphere" && it.field == "radius" }
 *         param("h") { it.node == "Cylinder" && it.field == "height" }
 *     }
 *     .orThrow()
 *
 * template.gradientAt(0.9, 0.0, 0.0, doubleArrayOf(0.8, 1.6))
 * // SolidGradientSample(value = 0.1, dPosition = [1, 0, 0], dParameters = [-1, 0])
 * ```
 *
 * # Why a third reading, and why *this* third reading
 *
 * `SolidField.kt` opens by saying a lambda "cannot be printed, compared, serialized, rewritten, or
 * emitted as GLSL", and this package answered that with two readings that can:
 * [toScalarField] and [exactSolid]. Both are *evaluations*. Neither changes what you can ask the
 * notation for.
 *
 * `afield.rectsl.RectSLFn` made the same argument one step further in 2D and is the model here,
 * verbatim: "a track that lowers to an `Fn` is a track you can take the derivative of, and therefore
 * a track you can **solve**: declare where a rect should be at some instant and let a gradient find
 * the numbers that put it there." That is what is missing in 3D, and this file plus
 * `SolidInverse.kt` is it.
 *
 * The 2D answer does not port. `afield.stdlib.Functions.Fn` is `(Double) -> Double` — one input, and
 * a solid is a function of `(x, y, z)` *and* of however many named parameters the author declared.
 * The tool that does fit is `afield.stdlib.AD`: reverse-mode automatic differentiation over `Double`,
 * with the whole elementary set `SolidField.kt` needs already present. So this lowering is a
 * line-for-line transposition of [SolidExpr.toField3] into [AD] arithmetic, and the two are meant to
 * be read side by side.
 *
 * ## Reverse mode, and not forward
 *
 * The quantity that matters is one scalar loss over many sample points and a handful of parameters.
 * Reverse mode gets *every* parameter derivative from one forward pass and one backward sweep;
 * forward mode costs one pass per parameter, and `autodiff_forward.kt` says so itself — its `Dual2`
 * is for the opposite shape, one input and many outputs. The position gradient is only three inputs
 * and falls off the same tape for free, so there was never a reason to run two modes.
 *
 * # The reading is total. The *parameterisation* is where refusals live
 *
 * `SolidExact.kt` puts every refusal in the type and enumerates five of them. This file has **none
 * of that flavour**, and that is a finding rather than an omission: the differentiable reading is
 * total on the algebra for exactly the reason [toScalarField] is total on it — it is the same algebra
 * evaluated in a different arithmetic. Every node that has a field form has a differentiable form,
 * because `AD` is closed under everything `Field3` uses.
 *
 * In fact the shape of it *inverts* the exact lowering's:
 *
 * | node | [toScalarField] | [exactSolid] | this reading |
 * |---|---|---|---|
 * | [SolidExpr.SmoothUnion] | native | **refused** — the fillet lies on neither operand | native, and **C¹**: the one boolean with no kink at all |
 * | [SolidExpr.SmoothDifference] | native | **refused** | native, C¹ |
 * | [SolidExpr.Gyroid] | native | **refused** — not a bounded volume | native; one `abs` kink on the mid-sheet |
 * | [SolidExpr.Round] | native | **refused** — would not converge | native, and exactly `f − r`: the cleanest derivative here |
 * | [SolidExpr.Union] | native | native | subgradient on the seam, split 0.5/0.5 |
 *
 * The constructs the exact kernel refuses hardest are the ones this reading is *best* at. A smooth
 * blend is differentiable by construction; a hard union is not. If you are going to fit a shape,
 * [smoothUnion] and [smoothDifference] are the operators to write it with, and
 * [SolidExpr.gradientNotes] will tell you so about your own expression.
 *
 * What *is* refused, always with a stated reason, is **binding a parameter to a slot** — see
 * [SolidExpr.parameterize]. Seven distinct refusals, from "that path does not exist, here are the
 * ones that do" to "`segments` is an integer facet count that [toScalarField] ignores entirely, so
 * there is no derivative to take". Nothing guesses; a wrong derivative is worse than no derivative.
 *
 * # Kinks, and the one fact that makes the whole night verifiable
 *
 * A distance field is full of kinks. `min` and `max` *are* CSG; `abs` is [SolidExpr.Shell] and
 * [SolidExpr.Gyroid]; `sqrt` at zero is every radial primitive's own centre. A derivative at a kink
 * is a choice, and `autodiff.kt`'s file KDoc has already made it: `abs`, `min` and `max` **split the
 * derivative 0.5/0.5 at an exact tie**, and it says why — that is "precisely what a central
 * difference reports at a kink, so the analytic gradient and the finite-difference oracle *agree
 * there too*".
 *
 * That is load-bearing here. It means the CSG seam does not have to be carved out of the
 * finite-difference census: a point sitting exactly on the boundary between two united children is a
 * point where the analytic answer and the numerical oracle still match. The convention is inherited
 * rather than re-chosen, so there is one place it is written down.
 *
 * Every remaining place where the gradient is a subgradient, is zero when you might have expected
 * otherwise, or is genuinely **singular**, is enumerated by [SolidExpr.gradientNotes] — the third
 * notes function in this package, beside [SolidExpr.fieldNotes] and [SolidExpr.allRefusals]. The
 * singular ones matter: `√(x²+y²+z²)` has an infinite position gradient at the origin, so a
 * [SolidExpr.Sphere]'s field is not differentiable at its own centre. Its *parameter* gradient
 * `∂/∂r = −1` is exact everywhere, which is the asymmetry that makes fitting work anyway.
 *
 * @see SolidExpr.parameterize to name the numbers you want derivatives with respect to
 * @see afield.solidsl.fitParameters for the inverse problem this exists to serve
 * @see SolidExpr.gradientAgreement for this reading measured against [toScalarField], which shares
 *   no code with it — [SolidAgreement]'s own argument applied to a third column
 */

// ==============================================================================================
// The differentiable field types
// ==============================================================================================

/**
 * A scalar field over three tracked coordinates — [Field3]'s differentiable twin.
 *
 * A `fun interface` for the same reason [Field3] is one, minus the boxing argument: [AD] is a
 * reference type either way, so what this buys is a name and a single-abstract-method shape that
 * composes as a tree exactly as `Field3` does. Read the two lowerings side by side; they are meant
 * to be diffable by eye.
 */
fun interface ADField3 {
    fun at(x: AD, y: AD, z: AD): AD
}

/** A scalar field over two tracked coordinates — [Field2]'s differentiable twin. */
fun interface ADField2 {
    fun at(x: AD, y: AD): AD
}

/**
 * Where a differentiable lowering gets each of an expression's numeric leaves, and the record of
 * which ones it asked for.
 *
 * A leaf that has been *bound* to a parameter comes back as that parameter's tape variable, so a
 * derivative flows into it. Everything else comes back as a tape constant, which costs one tape node
 * and contributes no derivative — the honest encoding of "this number is a literal".
 *
 * [pathsAsked] exists to be checked. It is the only mechanism by which [SolidExpr.numericSlots] (the
 * census a caller binds against) and [SolidExpr.toADField] (the lowering that consumes the binding)
 * can be *proved* to agree about what a path is called, rather than agreeing by inspection until
 * someone adds a node to one and not the other. `SolidSLGradientTest` asserts set equality.
 */
class SolidNumerics(
    private val tape: ADTape,
    private val bound: Map<String, AD> = emptyMap(),
) {
    private val asked = LinkedHashSet<String>()

    /** Every path this lowering read, in the order it read them. */
    val pathsAsked: Set<String> get() = asked

    /** The bound parameter at [path], or a tape constant holding [literal]. */
    fun at(path: String, literal: Double): AD {
        asked += path
        return bound[path] ?: tape.constant(literal)
    }

    /**
     * A number that is **not** a slot, and is therefore not recorded: the sentinel an empty subtree
     * reads as.
     *
     * It has to be outside [pathsAsked] rather than inside it under some path, because
     * [SolidExpr.numericSlots] does not enumerate it either — there is no `Double` field on
     * [SolidExpr.Empty] to bind. Giving it a path would make the two sets differ by exactly one
     * entry per empty subtree and turn the consistency check into a special case, which is how a
     * consistency check stops being one.
     */
    fun literal(value: Double): AD = tape.constant(value)
}

// ==============================================================================================
// The census of differentiable numbers
// ==============================================================================================

/**
 * One `Double` inside an expression that a parameter can be bound to.
 *
 * @param path the canonical structural address — `Union/0/Sphere.radius`,
 *   `Extrude/profile/Offset/Rect.x`, `Revolve/profile/Polygon.points[2].y`. Structural rather than
 *   nominal because a `SolidExpr` has no names in it: it is an anonymous tree of `data class`es, and
 *   the only stable way to point at the third child's radius is to say so.
 * @param node the node's simple type name, for the predicate form of [SolidParamSpec.param].
 * @param field the field's own name, likewise.
 * @param value what the number currently is — the default seed for a parameter bound here.
 */
data class SolidNumericSlot(
    val path: String,
    val node: String,
    val field: String,
    val value: Double,
) {
    override fun toString(): String = "$path = $value"
}

/**
 * Every `Double` in this expression that could be a parameter, in traversal order.
 *
 * **`Int` fields are absent by construction**, and that is the first and least negotiable refusal in
 * this file. `segments` and `slices` are facet counts: [toScalarField] ignores them completely — a
 * [SolidExpr.Sphere]'s field is a true sphere whatever its `segments` says, as its own KDoc points
 * out — and the exact lowering consumes them as an integer count of polygon sides. Neither has a
 * derivative with respect to them, in different ways: one because the number has no effect, the other
 * because the effect is a jump. The same goes for the *number* of points in a
 * [Profile.Polygon] or of children in a [SolidExpr.Union]: those are structure, and this reading
 * differentiates numbers inside a fixed structure. The coordinates of those points are slots; how
 * many there are is not.
 *
 * ```kotlin
 * torus(1.0, 0.3).numericSlots()
 * // [Revolve/profile/Translate.x = 1.0, Revolve/profile/Translate/Circle.radius = 0.3]
 * ```
 */
fun SolidExpr.numericSlots(): List<SolidNumericSlot> {
    val out = mutableListOf<SolidNumericSlot>()
    rewriteNumbers(this, "") { path, node, field, v ->
        out += SolidNumericSlot(path, node, field, v)
        v
    }
    return out
}

/**
 * This expression with the numbers at the given [values] paths replaced — an ordinary [SolidExpr],
 * so the result marches, exact-solids, emits GLSL and prints like anything else in the algebra.
 *
 * This is the half of the parametric story that makes it worth having. A fit that produced only a
 * `DoubleArray` would leave the caller to reconstruct the shape by hand; a fit that produces a
 * [SolidExpr] hands the answer straight back to [crossCheck], [march] and [toGlsl].
 *
 * Refuses, rather than ignoring, a path that does not exist — with the available paths in the
 * message, because a typo in a structural address is the likeliest mistake here and a silent no-op
 * would present as "the optimiser did nothing".
 */
fun SolidExpr.withNumericValues(values: Map<String, Double>): SolidLowering<SolidExpr> {
    val known = numericSlots().map { it.path }.toSet()
    val unknown = values.keys - known
    if (unknown.isNotEmpty()) {
        return SolidLowering.Refused(
            why = "no such numeric slot: ${unknown.sorted().joinToString(", ")}. " +
                "This expression's slots are: ${known.sorted().joinToString(", ")}",
            node = toString(),
            closable = true,
            missing = "a path that resolves — see numericSlots()",
        )
    }
    return SolidLowering.Lowered(
        rewriteNumbers(this, "") { path, _, _, v -> values[path] ?: v },
    )
}

// ==============================================================================================
// Parameters
// ==============================================================================================

/**
 * A [SolidExpr] plus the named parameters some of its numbers have been promoted into — the thing
 * that can be differentiated with respect to something a human named, and therefore the thing that
 * can be *solved*.
 *
 * A parameter may drive **several** slots, which is how a symmetry constraint is written: bind one
 * name to every `Cylinder.radiusLow` in a tree and every hole in the part is the same size by
 * construction, with one derivative that is the sum of their individual ones. That is not a
 * convenience — a fit over six independently-varying hole radii and a fit over one shared radius are
 * different problems, and the notation should be able to say which one is meant.
 *
 * @param base the expression the parameters were taken from; its numbers are the seed.
 * @param names the parameter names, in the order the vectors are indexed.
 * @param slotsOf which slots each name drives.
 * @param seed the base expression's own values at those slots — a natural starting point, and the
 *   thing a recovery test perturbs away from.
 */
data class SolidTemplate(
    val base: SolidExpr,
    val names: List<String>,
    val slotsOf: Map<String, List<String>>,
    val seed: DoubleArray,
) {
    /** How many parameters — the length of every vector this template takes and returns. */
    val size: Int get() = names.size

    /** This template with [values] substituted, as an ordinary [SolidExpr]. */
    fun withValues(values: DoubleArray): SolidExpr {
        require(values.size == names.size) {
            "this template has ${names.size} parameters (${names.joinToString(", ")}), got ${values.size} values"
        }
        val byPath = mutableMapOf<String, Double>()
        names.forEachIndexed { i, name -> slotsOf.getValue(name).forEach { byPath[it] = values[i] } }
        return base.withNumericValues(byPath).orThrow()
    }

    /**
     * The differentiable field with these already-tracked [parameters] bound to the named slots.
     *
     * Takes the tape variables rather than making them, so it drops straight inside a
     * `valueAndGrad { params -> … }` — which is where a fit lives, and which owns the tape. Build
     * **one** of these per tape and evaluate it at many points; that is the whole economy of reverse
     * mode, and rebuilding it per point would push every constant again each time.
     */
    fun adFieldWith(parameters: List<AD>): ADField3 {
        require(parameters.size == names.size) {
            "this template has ${names.size} parameters (${names.joinToString(", ")}), got ${parameters.size}"
        }
        val bound = mutableMapOf<String, AD>()
        names.forEachIndexed { i, name -> slotsOf.getValue(name).forEach { bound[it] = parameters[i] } }
        return base.toADField(SolidNumerics(parameters[0].tape, bound))
    }

    /** The value and the whole gradient at one point — position *and* parameters. */
    fun gradientAt(x: Double, y: Double, z: Double, values: DoubleArray): SolidGradientSample {
        val packed = doubleArrayOf(x, y, z) + values
        val g = valueAndGrad(packed) { p ->
            adFieldWith(p.subList(3, 3 + names.size)).at(p[0], p[1], p[2])
        }
        return SolidGradientSample(
            value = g.value,
            dPosition = doubleArrayOf(g.gradient[0], g.gradient[1], g.gradient[2]),
            dParameters = DoubleArray(names.size) { g.gradient[3 + it] },
        )
    }

    /** Which numbers these parameters actually reach, for a `println` in a box. */
    fun report(): String = buildString {
        appendLine("SolidTemplate over ${names.size} parameter${if (names.size == 1) "" else "s"}")
        names.forEachIndexed { i, name ->
            val slots = slotsOf.getValue(name)
            appendLine("  $name = ${seed[i]}  ->  ${slots.joinToString(", ")}")
        }
        val notes = base.gradientNotes()
        if (notes.isEmpty()) appendLine("  no gradient caveats") else notes.forEach { appendLine("  note: $it") }
    }

    override fun equals(other: Any?): Boolean =
        other is SolidTemplate && base == other.base && names == other.names &&
            slotsOf == other.slotsOf && seed.contentEquals(other.seed)

    override fun hashCode(): Int =
        31 * (31 * (31 * base.hashCode() + names.hashCode()) + slotsOf.hashCode()) + seed.contentHashCode()
}

/** A field value at a point, and its exact derivatives in both directions that matter. */
data class SolidGradientSample(
    /** The signed distance, negative inside — the same number [toScalarField] gives. */
    val value: Double,
    /** `∂value/∂(x, y, z)` — the surface normal, unnormalised, and unit-length where the field is exact. */
    val dPosition: DoubleArray,
    /** `∂value/∂pᵢ` for each named parameter, in the template's order. */
    val dParameters: DoubleArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SolidGradientSample && value == other.value &&
            dPosition.contentEquals(other.dPosition) && dParameters.contentEquals(other.dParameters)

    override fun hashCode(): Int =
        31 * (31 * value.hashCode() + dPosition.contentHashCode()) + dParameters.contentHashCode()

    override fun toString(): String =
        "value ${"%.6g".format(value)}  dPos ${dPosition.joinToString(", ") { "%.4g".format(it) }}" +
            "  dParam ${dParameters.joinToString(", ") { "%.4g".format(it) }}"
}

/**
 * The builder [SolidExpr.parameterize] runs — one `param` call per name, either against explicit
 * paths or against a predicate over the slot census.
 */
class SolidParamSpec internal constructor(
    /** The whole census, so a predicate has something to select from and an error something to list. */
    val slots: List<SolidNumericSlot>,
) {
    internal val order = mutableListOf<String>()
    internal val chosen = linkedMapOf<String, List<SolidNumericSlot>>()
    internal val explicitSeed = mutableMapOf<String, Double>()
    internal val problems = mutableListOf<String>()

    /**
     * Bind [name] to exactly these structural [paths].
     *
     * The verbose form, and the one to reach for when a predicate would over-match — two spheres in
     * a tree that should be allowed to differ.
     */
    fun param(name: String, vararg paths: String, seed: Double? = null) {
        if (paths.isEmpty()) {
            problems += "parameter '$name' names no path at all; pass a path, or a predicate over the " +
                "${slots.size} slot(s) this expression has"
            return
        }
        val byPath = slots.associateBy { it.path }
        val missing = paths.filter { it !in byPath }
        if (missing.isNotEmpty()) {
            problems += "parameter '$name' names ${missing.size} path(s) that do not exist " +
                "(${missing.joinToString(", ")}); this expression's slots are " +
                slots.joinToString(", ") { it.path }
            return
        }
        record(name, paths.map { byPath.getValue(it) }, seed, "the paths given")
    }

    /**
     * Bind [name] to every slot matching [where] — the form that says "all of them", and therefore
     * the form that expresses a symmetry.
     *
     * ```kotlin
     * param("hole") { it.node == "Cylinder" && it.field == "radiusLow" }
     * ```
     */
    fun param(name: String, seed: Double? = null, where: (SolidNumericSlot) -> Boolean) {
        val matched = slots.filter(where)
        if (matched.isEmpty()) {
            problems += "parameter '$name' matched no slot; this expression's slots are " +
                slots.joinToString(", ") { "${it.path} (node=${it.node}, field=${it.field})" }
            return
        }
        record(name, matched, seed, "the predicate")
    }

    private fun record(name: String, matched: List<SolidNumericSlot>, seed: Double?, how: String) {
        if (name in chosen) {
            problems += "parameter '$name' is declared twice; a name addresses one number"
            return
        }
        val already = chosen.entries.firstOrNull { (_, s) -> s.any { m -> matched.any { it.path == m.path } } }
        if (already != null) {
            val clash = matched.first { m -> already.value.any { it.path == m.path } }.path
            problems += "parameter '$name' and parameter '${already.key}' both claim ${clash}; " +
                "one number cannot take two derivatives"
            return
        }
        val values = matched.map { it.value }.distinct()
        if (seed == null && values.size > 1) {
            problems += "parameter '$name' ties ${matched.size} slots that currently hold different " +
                "values (${values.sorted().joinToString(", ")}), so there is no seed to start from — " +
                "pass seed = … to say which one wins, or narrow $how"
            return
        }
        val s = seed ?: values.single()
        singularityRefusal(name, matched, s)?.let { problems += it; return }
        chosen[name] = matched
        order += name
        explicitSeed[name] = s
    }

    /**
     * The seed-value refusals: places where a *legal* binding would hand the lowering a number it
     * cannot differentiate through, or cannot evaluate at all.
     *
     * These are not hypothetical. [SolidExpr.Scale] divides by its factors, so a factor at zero is a
     * division by zero and a factor near zero is a gradient of `1/s²` that swamps every other
     * parameter in the vector. `normalize3` *already* refuses a zero [SolidExpr.Mirror] normal with a
     * `require`, and a fit that walks a normal to zero would hit that `require` mid-descent, dozens of
     * iterations in, with no way back — much better to say so before the first step. And a smooth
     * blend's `k` is a denominator that [SolidExpr.SmoothUnion]'s field clamps at `1e-9`, which means
     * a `k` below that is silently a hard union with a zero derivative.
     *
     * The threshold is deliberately loose (`1e-6` against a lowering that breaks at `1e-12`) because
     * the seed is a *starting* point: a descent moves, and refusing only the exactly-broken value
     * would let the first step arrive there.
     */
    private fun singularityRefusal(name: String, matched: List<SolidNumericSlot>, seed: Double): String? {
        val bad = matched.firstOrNull { slot ->
            when {
                slot.node == "Scale" -> kotlin.math.abs(seed) < 1e-6
                slot.node == "Mirror" -> false // a single component may be zero; the vector may not
                (slot.node == "SmoothUnion" || slot.node == "SmoothDifference") && slot.field == "k" ->
                    seed < 1e-6
                else -> false
            }
        } ?: return null
        return when {
            bad.node == "Scale" ->
                "parameter '$name' seeds ${bad.path} at $seed, and a Scale factor divides — the field " +
                    "lowering requires |factor| > 1e-12 and the derivative goes as 1/s², so a descent " +
                    "started here is a division by nearly zero rather than a fit"
            else ->
                "parameter '$name' seeds ${bad.path} at $seed, and a smooth blend's k is a " +
                    "denominator its field clamps at 1e-9 — below that the blend is silently a hard " +
                    "boolean with a zero derivative in k, so the parameter would not move"
        }
    }
}

/**
 * Promote some of this expression's numbers into named parameters, or refuse and say why.
 *
 * ```kotlin
 * val template = (cuboid(3, 2, 0.5) - cylinder(2, 0.4).at(1, 0, 0) - cylinder(2, 0.4).at(-1, 0, 0))
 *     .parameterize {
 *         param("thickness", "Difference/0/Cuboid.z")
 *         param("hole") { it.node == "Cylinder" && it.field == "radiusLow" }   // both, tied
 *     }
 *     .orThrow()
 * ```
 *
 * The refusal is [SolidLowering.Refused] rather than a thrown exception for the reason `SolidExact.kt`
 * gives about its own: "a `null` would invite a caller to substitute something plausible; a thrown
 * exception loses the reason at the first `runCatching`". A misbound parameter is *exactly* the
 * failure that presents as "the optimiser ran and nothing happened", which is the hardest kind of
 * wrong to find, so the reason has to survive to the caller.
 *
 * All problems are collected before refusing, not just the first — a five-parameter spec with three
 * typos should report three typos.
 */
fun SolidExpr.parameterize(spec: SolidParamSpec.() -> Unit): SolidLowering<SolidTemplate> {
    val census = numericSlots()
    val s = SolidParamSpec(census)
    s.spec()
    if (s.problems.isNotEmpty()) {
        return SolidLowering.Refused(
            why = s.problems.joinToString("; "),
            node = toString(),
            closable = true,
            missing = "a binding that resolves to distinct, differentiable, consistently-seeded slots",
        )
    }
    if (s.order.isEmpty()) {
        return SolidLowering.Refused(
            why = "no parameters were declared, so there is nothing to differentiate with respect to; " +
                "this expression has ${census.size} bindable slot(s)",
            node = toString(),
            closable = true,
            missing = "at least one param(…) call",
        )
    }
    return SolidLowering.Lowered(
        SolidTemplate(
            base = this,
            names = s.order.toList(),
            slotsOf = s.order.associateWith { n -> s.chosen.getValue(n).map { it.path } },
            seed = DoubleArray(s.order.size) { s.explicitSeed.getValue(s.order[it]) },
        ),
    )
}

// ==============================================================================================
// Gradient notes — the third notes function
// ==============================================================================================

/**
 * Every place in this expression where the gradient is a **subgradient**, is structurally **zero**,
 * or is genuinely **singular**, in the order they appear — beside [fieldNotes] and [allRefusals] as
 * the third disclosure this package owes a reader.
 *
 * `fieldNotes` answers "where is this field not a true distance?". This answers "where is the
 * derivative a choice, and what did we choose?". They are different lists: [SolidExpr.Round] is on
 * neither (`f − r` is exact in value and in both derivatives), and [SolidExpr.Cuboid] is on this one
 * but not on that one (an exact distance field with a kink on its diagonal planes).
 *
 * A note is not a warning. Most fits proceed perfectly well across a subgradient — the 0.5/0.5
 * convention is a real element of the subdifferential and a descent that lands on a seam takes a
 * step that decreases the loss. The notes worth acting on are the two flavours that *cannot* be
 * fixed by taking a smaller step:
 *
 *  - **singular** — `√·` at zero has an infinite derivative, so a sample at a radial primitive's own
 *    centre produces `Infinity` in the position gradient and `NaN` the moment it is multiplied by
 *    zero. Sample away from centres, or use the parameter gradient, which is finite there.
 *  - **structurally zero** — a parameter that only feeds a [SolidExpr.Empty] subtree, or a
 *    `segments` count, has derivative exactly `0` for all values, and a descent on it reports
 *    convergence immediately because the gradient really is zero. That reads as success.
 */
fun SolidExpr.gradientNotes(): List<String> {
    val out = mutableListOf<String>()
    fun visit(e: SolidExpr) {
        when (e) {
            is SolidExpr.Sphere ->
                out += "Sphere: singular at its own centre — sqrt(x²+y²+z²) has an infinite position " +
                    "gradient at the origin. ∂/∂radius = −1 is exact everywhere, including there"
            is SolidExpr.Cuboid ->
                out += "Cuboid: sdBox3 kinks on the three diagonal planes where two |p| − h terms tie; " +
                    "min/max split 0.5/0.5 there, which is what a central difference reports"
            is SolidExpr.Cylinder -> {
                out += "Cylinder: the field picks the nearer of a cap and a flank by a comparison, so " +
                    "the derivative is the taken branch's; on the ridge between them it is a " +
                    "subgradient, and at the apex of a cone (radius 0) it is singular"
                if (e.radiusLow != e.radiusHigh) {
                    out += "Cylinder(radiusLow=${e.radiusLow}, radiusHigh=${e.radiusHigh}): the flank " +
                        "parameter t is clamped to [0, 1], so beyond the ends ∂/∂radius is exactly 0 " +
                        "rather than small"
                }
            }
            is SolidExpr.Extrude -> {
                visitProfileGradient(e.profile, out)
                out += "Extrude: the height parameter u is clamped to [0, 1], so a sample past either " +
                    "end contributes nothing to ∂/∂twistDegrees or ∂/∂scaleTop — those derivatives are " +
                    "structurally 0 outside the sweep, not merely small"
            }
            is SolidExpr.Revolve -> {
                visitProfileGradient(e.profile, out)
                out += "Revolve: hypot(x, y) is singular on the Z axis, so a sample on the axis has an " +
                    "undefined radial derivative"
                if (e.degrees != 360.0) {
                    out += "Revolve(degrees=${e.degrees}): the wedge is a min or a max of two " +
                        "half-spaces depending on whether the sweep is reflex, and *which* is chosen " +
                        "from the degrees value — so ∂/∂degrees is the taken branch's and is " +
                        "discontinuous as the sweep crosses 180"
                }
            }
            is SolidExpr.Gyroid ->
                out += "Gyroid: abs() kinks on the mid-sheet where the trig sum crosses zero, split " +
                    "0.5/0.5; and because the field is not a distance function its gradient is not " +
                    "unit length, so a normal read off it needs normalising"
            SolidExpr.Empty ->
                out += "Empty: the field is a finite constant, so every derivative through it is " +
                    "exactly 0 — a parameter that only reaches an Empty subtree cannot move, and a " +
                    "descent on it reports convergence at once"
            is SolidExpr.Translate -> visit(e.child)
            is SolidExpr.Rotate -> {
                out += "Rotate: differentiating an angle in degrees carries a π/180, which is exact " +
                    "but means ∂/∂degrees is ~57x smaller than ∂/∂radians — badly scaled against a " +
                    "length parameter in the same vector, so prefer a line-searched method (lbfgs) " +
                    "or scale the parameter"
                visit(e.child)
            }
            is SolidExpr.Scale -> {
                out += "Scale: the field divides by each factor and multiplies by min(|sx|,|sy|,|sz|), " +
                    "so ∂/∂factor goes as 1/s² — the worst-conditioned parameter in this algebra, and " +
                    "singular at 0"
                if (e.x != e.y || e.y != e.z) {
                    out += "Scale(${e.x}, ${e.y}, ${e.z}): min over the three |factors| kinks where two " +
                        "of them tie, split 0.5/0.5"
                }
                visit(e.child)
            }
            is SolidExpr.Mirror -> {
                out += "Mirror: the normal is normalised, so the three components have one degree of " +
                    "freedom too many — a gradient along the normal's own direction is exactly 0, and " +
                    "a fit over all three components has a rank-deficient Jacobian by construction"
                visit(e.child)
            }
            is SolidExpr.Round -> {
                // Deliberately not a note. `f - r` is exact in value and in both derivatives; it is
                // the cleanest node here, and listing it would dilute the list.
                visit(e.child)
            }
            is SolidExpr.Shell -> {
                out += "Shell: abs(f) − t kinks on the mid-surface f = 0, split 0.5/0.5; ∂/∂thickness " +
                    "= −1 is exact everywhere"
                visit(e.child)
            }
            is SolidExpr.Union -> {
                if (e.children.size > 1) {
                    out += "Union: a pointwise min, so on the seam where two children tie the position " +
                        "gradient is a subgradient — 0.5/0.5, the central difference's own answer. Away " +
                        "from the seam exactly one child receives the whole derivative, so a parameter " +
                        "belonging to a child that is nowhere nearest gets 0 from every sample"
                }
                e.children.forEach(::visit)
            }
            is SolidExpr.Intersect -> {
                if (e.children.size > 1) out += "Intersect: a pointwise max; see Union's note, mirrored"
                e.children.forEach(::visit)
            }
            is SolidExpr.Difference -> {
                if (e.children.size > 1) {
                    out += "Difference: a pointwise max against negated cutters; a cutter that is " +
                        "entirely inside the body receives 0 from every sample outside it, which is " +
                        "the classic reason an inverse-design parameter refuses to move"
                }
                e.children.forEach(::visit)
            }
            is SolidExpr.SmoothUnion -> {
                out += "SmoothUnion(k=${e.k}): C¹ everywhere — the polynomial smooth minimum has no " +
                    "kink at all, so this is the differentiable-by-construction path and the operator " +
                    "to prefer when the shape is going to be fitted. The h clamp does saturate: where " +
                    "the two children are more than k apart the blend degenerates to the nearer one " +
                    "and the other's derivative is 0"
                visit(e.a); visit(e.b)
            }
            is SolidExpr.SmoothDifference -> {
                out += "SmoothDifference(k=${e.k}): C¹ everywhere, same as SmoothUnion, same clamp " +
                    "saturation. The exact CSG lowering refuses this node outright; the gradient " +
                    "reading is at its best on it"
                visit(e.a); visit(e.b)
            }
        }
    }
    visit(this)
    return out
}

private fun visitProfileGradient(p: Profile, out: MutableList<String>) {
    when (p) {
        is Profile.Circle ->
            out += "Profile.Circle: hypot is singular at the centre; ∂/∂radius = −1 is exact"
        is Profile.Rect ->
            out += "Profile.Rect: sdBox2 kinks on its two diagonals, split 0.5/0.5"
        is Profile.Polygon ->
            out += "Profile.Polygon: the sign comes from a crossing-parity test, which is a discrete " +
                "±1 and contributes no derivative — correct, because the parity only changes where the " +
                "distance is 0. But sqrt of the squared distance *is* singular exactly there, so a " +
                "sample on the outline has an undefined gradient. Each edge's projection is clamped to " +
                "[0, 1], so a vertex coordinate gets 0 from samples nearest a different edge"
        is Profile.Offset -> { out += "Profile.Offset: −delta is exact in value and derivative"; visitProfileGradient(p.child, out) }
        is Profile.Translate -> visitProfileGradient(p.child, out)
        is Profile.Rotate -> {
            out += "Profile.Rotate: degrees again, so ∂/∂degrees carries π/180 — see Rotate's note"
            visitProfileGradient(p.child, out)
        }
        is Profile.Scale -> {
            out += "Profile.Scale: 1/s² conditioning, as Scale"
            visitProfileGradient(p.child, out)
        }
        is Profile.Union -> { if (p.children.size > 1) out += "Profile.Union: pointwise min, subgradient on the seam"; p.children.forEach { visitProfileGradient(it, out) } }
        is Profile.Intersect -> { if (p.children.size > 1) out += "Profile.Intersect: pointwise max, subgradient on the seam"; p.children.forEach { visitProfileGradient(it, out) } }
        is Profile.Difference -> { if (p.children.size > 1) out += "Profile.Difference: pointwise max, subgradient on the seam"; p.children.forEach { visitProfileGradient(it, out) } }
        Profile.Empty -> out += "Profile.Empty: a constant, so every derivative through it is exactly 0"
    }
}

// ==============================================================================================
// The lowering itself — read this beside SolidField.kt's toField3
// ==============================================================================================

/**
 * How far outside everything [SolidExpr.Empty] reads. The same finite value [toField3] uses, and
 * finite for the same reason plus one more: an infinity here would make every adjoint downstream of
 * a `min` with it a `NaN` rather than a zero.
 */
private const val AD_FAR = 1e18

/**
 * This shape as an [ADField3], negative inside — the differentiable transposition of [toField3].
 *
 * Every numeric leaf is read through [numerics], which decides whether it is a tape constant or a
 * bound parameter. Build **one** of these per [ADTape] and evaluate it at as many points as you
 * like: the constants and any per-node precomputation (a rotation matrix, a normalised mirror
 * normal) are pushed once, exactly as [toField3] hoists them out of its lambdas.
 *
 * The correspondence with [toField3] is line for line and is meant to stay that way. `SolidSLGradientTest`
 * asserts that this reading's `.value` matches [toScalarField] to `1e-6` relative over a census of
 * every construct, which is the mechanical guard on that promise — and, since the two share no code,
 * is [SolidAgreement]'s own two-independent-implementations argument applied to a third column.
 */
fun SolidExpr.toADField(numerics: SolidNumerics, prefix: String = ""): ADField3 {
    val self = prefix + nodeName()
    fun num(field: String, v: Double): AD = numerics.at("$self.$field", v)
    val kids = "$self/"

    return when (this) {

        is SolidExpr.Sphere -> {
            val r = num("radius", radius)
            ADField3 { x, y, z -> sqrt(x.sq() + y.sq() + z.sq()) - r }
        }

        is SolidExpr.Cuboid -> {
            val hx = num("x", x) / 2.0; val hy = num("y", y) / 2.0; val hz = num("z", z) / 2.0
            ADField3 { px, py, pz -> adBox3(px, py, pz, hx, hy, hz) }
        }

        is SolidExpr.Cylinder -> {
            val h = abs(num("height", height)) / 2.0
            val r1 = num("radiusLow", radiusLow); val r2 = num("radiusHigh", radiusHigh)
            ADField3 { px, py, pz -> adCappedConeZ(px, py, pz, h, r1, r2) }
        }

        is SolidExpr.Extrude -> {
            val p2 = profile.toADField2(numerics, kids + "profile/")
            val h = num("height", height)
            val twist = num("twistDegrees", twistDegrees) * (Math.PI / 180.0)
            val taper = num("scaleTop", scaleTop)
            ADField3 { px, py, pz ->
                val u = (pz / h).clamp(0.0, 1.0)
                val s = (taper - 1.0) * u + 1.0
                val a = -twist * u
                val ca = cos(a); val sa = sin(a)
                val qx = (ca * px - sa * py) / s
                val qy = (sa * px + ca * py) / s
                val d2 = p2.at(qx, qy) * s
                val dz = max(-pz, pz - h)
                min(max(d2, dz), 0.0) + hypot(max(d2, 0.0), max(dz, 0.0))
            }
        }

        is SolidExpr.Revolve -> {
            val p2 = profile.toADField2(numerics, kids + "profile/")
            val sweep = num("degrees", degrees)
            if (sweep.value >= 360.0) {
                ADField3 { px, py, pz -> p2.at(hypot(px, py), pz) }
            } else {
                // The reflex/convex choice is made from the *value*, exactly as the Double lowering
                // makes it, and is therefore differentiated as taken — `gradientNotes` says so.
                val a = sweep * (Math.PI / 180.0)
                val sa = sin(a); val ca = cos(a)
                val reflex = sweep.value > 180.0
                ADField3 { px, py, pz ->
                    val body = p2.at(hypot(px, py), pz)
                    val d0 = -py
                    val d1 = -(sa * px - ca * py)
                    val wedge = if (reflex) min(d0, d1) else max(d0, d1)
                    max(body, wedge)
                }
            }
        }

        is SolidExpr.Gyroid -> {
            val s = num("scale", scale); val t = num("thickness", thickness)
            ADField3 { x, y, z ->
                val gx = x * s; val gy = y * s; val gz = z * s
                abs(sin(gx) * cos(gy) + sin(gy) * cos(gz) + sin(gz) * cos(gx)) - t
            }
        }

        SolidExpr.Empty -> emptyADField(numerics)

        is SolidExpr.Translate -> {
            val f = child.toADField(numerics, kids)
            val tx = num("x", x); val ty = num("y", y); val tz = num("z", z)
            ADField3 { px, py, pz -> f.at(px - tx, py - ty, pz - tz) }
        }

        is SolidExpr.Rotate -> {
            val f = child.toADField(numerics, kids)
            // The transpose of eulerMatrix, in AD, and in the *same* X-then-Y-then-Z order — because
            // a second place that writes the convention down is a second place it can go wrong.
            val m = adEulerMatrixTranspose(num("x", x), num("y", y), num("z", z))
            ADField3 { px, py, pz ->
                f.at(
                    m[0] * px + m[1] * py + m[2] * pz,
                    m[3] * px + m[4] * py + m[5] * pz,
                    m[6] * px + m[7] * py + m[8] * pz,
                )
            }
        }

        is SolidExpr.Scale -> {
            val f = child.toADField(numerics, kids)
            val sx = num("x", x); val sy = num("y", y); val sz = num("z", z)
            require(
                kotlin.math.abs(sx.value) > 1e-12 &&
                    kotlin.math.abs(sy.value) > 1e-12 &&
                    kotlin.math.abs(sz.value) > 1e-12,
            ) { "a scale factor must not be zero, got (${sx.value}, ${sy.value}, ${sz.value})" }
            val k = min(abs(sx), min(abs(sy), abs(sz)))
            ADField3 { px, py, pz -> f.at(px / sx, py / sy, pz / sz) * k }
        }

        is SolidExpr.Mirror -> {
            val f = child.toADField(numerics, kids)
            val nx0 = num("x", x); val ny0 = num("y", y); val nz0 = num("z", z)
            val l = sqrt(nx0.sq() + ny0.sq() + nz0.sq())
            require(l.value > 1e-12) {
                "a mirror plane needs a non-zero normal, got (${nx0.value}, ${ny0.value}, ${nz0.value})"
            }
            val nx = nx0 / l; val ny = ny0 / l; val nz = nz0 / l
            ADField3 { px, py, pz ->
                val d = (px * nx + py * ny + pz * nz) * 2.0
                f.at(px - d * nx, py - d * ny, pz - d * nz)
            }
        }

        is SolidExpr.Round -> {
            val f = child.toADField(numerics, kids); val r = num("radius", radius)
            ADField3 { px, py, pz -> f.at(px, py, pz) - r }
        }

        is SolidExpr.Shell -> {
            val f = child.toADField(numerics, kids); val t = num("thickness", thickness)
            ADField3 { px, py, pz -> abs(f.at(px, py, pz)) - t }
        }

        is SolidExpr.Union -> {
            val fs = children.mapIndexed { i, c -> c.toADField(numerics, "$kids$i/") }
            if (fs.isEmpty()) emptyADField(numerics)
            else ADField3 { px, py, pz ->
                var v = fs[0].at(px, py, pz)
                for (i in 1 until fs.size) v = min(v, fs[i].at(px, py, pz))
                v
            }
        }

        is SolidExpr.Intersect -> {
            val fs = children.mapIndexed { i, c -> c.toADField(numerics, "$kids$i/") }
            if (fs.isEmpty()) emptyADField(numerics)
            else ADField3 { px, py, pz ->
                var v = fs[0].at(px, py, pz)
                for (i in 1 until fs.size) v = max(v, fs[i].at(px, py, pz))
                v
            }
        }

        is SolidExpr.Difference -> {
            val fs = children.mapIndexed { i, c -> c.toADField(numerics, "$kids$i/") }
            if (fs.isEmpty()) emptyADField(numerics)
            else ADField3 { px, py, pz ->
                var v = fs[0].at(px, py, pz)
                for (i in 1 until fs.size) v = max(v, -fs[i].at(px, py, pz))
                v
            }
        }

        is SolidExpr.SmoothUnion -> {
            val fa = a.toADField(numerics, kids + "a/")
            val fb = b.toADField(numerics, kids + "b/")
            val kk = max(num("k", k), 1e-9)
            ADField3 { px, py, pz -> adSmoothMin(fa.at(px, py, pz), fb.at(px, py, pz), kk) }
        }

        is SolidExpr.SmoothDifference -> {
            val fa = a.toADField(numerics, kids + "a/")
            val fb = b.toADField(numerics, kids + "b/")
            val kk = max(num("k", k), 1e-9)
            ADField3 { px, py, pz -> adSmoothMax(fa.at(px, py, pz), -fb.at(px, py, pz), kk) }
        }
    }
}

/** This region as an [ADField2], negative inside — the differentiable transposition of [toField2]. */
fun Profile.toADField2(numerics: SolidNumerics, prefix: String = ""): ADField2 {
    val self = prefix + nodeName()
    fun num(field: String, v: Double): AD = numerics.at("$self.$field", v)
    val kids = "$self/"

    return when (this) {

        is Profile.Circle -> {
            val r = num("radius", radius)
            ADField2 { x, y -> hypot(x, y) - r }
        }

        is Profile.Rect -> {
            val hx = num("x", x) / 2.0; val hy = num("y", y) / 2.0
            ADField2 { px, py -> adBox2(px, py, hx, hy) }
        }

        is Profile.Polygon -> {
            if (points.size < 3) emptyADField2(numerics) else {
                val n = points.size
                val xs = List(n) { numerics.at("$self.points[$it].x", points[it].x) }
                val ys = List(n) { numerics.at("$self.points[$it].y", points[it].y) }
                ADField2 { px, py -> adPolygon(xs, ys, px, py) }
            }
        }

        is Profile.Offset -> {
            val f = child.toADField2(numerics, kids); val d = num("delta", delta)
            ADField2 { px, py -> f.at(px, py) - d }
        }

        is Profile.Translate -> {
            val f = child.toADField2(numerics, kids)
            val tx = num("x", x); val ty = num("y", y)
            ADField2 { px, py -> f.at(px - tx, py - ty) }
        }

        is Profile.Rotate -> {
            val f = child.toADField2(numerics, kids)
            val a = num("degrees", degrees) * (-Math.PI / 180.0)
            val c = cos(a); val s = sin(a)
            ADField2 { px, py -> f.at(c * px - s * py, s * px + c * py) }
        }

        is Profile.Scale -> {
            val f = child.toADField2(numerics, kids)
            val sx = num("x", x); val sy = num("y", y)
            require(kotlin.math.abs(sx.value) > 1e-12 && kotlin.math.abs(sy.value) > 1e-12) {
                "a profile scale must not be zero, got (${sx.value}, ${sy.value})"
            }
            val k = min(abs(sx), abs(sy))
            ADField2 { px, py -> f.at(px / sx, py / sy) * k }
        }

        is Profile.Union -> {
            val fs = children.mapIndexed { i, c -> c.toADField2(numerics, "$kids$i/") }
            if (fs.isEmpty()) emptyADField2(numerics)
            else ADField2 { px, py ->
                var v = fs[0].at(px, py)
                for (i in 1 until fs.size) v = min(v, fs[i].at(px, py))
                v
            }
        }

        is Profile.Intersect -> {
            val fs = children.mapIndexed { i, c -> c.toADField2(numerics, "$kids$i/") }
            if (fs.isEmpty()) emptyADField2(numerics)
            else ADField2 { px, py ->
                var v = fs[0].at(px, py)
                for (i in 1 until fs.size) v = max(v, fs[i].at(px, py))
                v
            }
        }

        is Profile.Difference -> {
            val fs = children.mapIndexed { i, c -> c.toADField2(numerics, "$kids$i/") }
            if (fs.isEmpty()) emptyADField2(numerics)
            else ADField2 { px, py ->
                var v = fs[0].at(px, py)
                for (i in 1 until fs.size) v = max(v, -fs[i].at(px, py))
                v
            }
        }

        Profile.Empty -> emptyADField2(numerics)
    }
}

/** The field of nothing: [AD_FAR], as one shared tape constant with no path. */
private fun emptyADField(numerics: SolidNumerics): ADField3 {
    val far = numerics.literal(AD_FAR)
    return ADField3 { _, _, _ -> far }
}

/** [emptyADField]'s 2D twin. */
private fun emptyADField2(numerics: SolidNumerics): ADField2 {
    val far = numerics.literal(AD_FAR)
    return ADField2 { _, _ -> far }
}

// ==============================================================================================
// The closed forms, in AD — transpositions of SolidField.kt's
// ==============================================================================================

/**
 * [sdBox3] in [AD].
 *
 * # The interior branch is not an optimisation and not an epsilon
 *
 * Strictly inside the box all three clamped terms are exactly zero, so the exterior distance is
 * `sqrt(0)` — whose derivative is `+∞`. Reverse mode then multiplies that infinity by the clamp's own
 * zero partial and gets **`NaN`**, which propagates to every half-extent and, through the sum, to the
 * whole loss gradient. The value is perfectly correct the entire time; only the derivative is
 * poisoned, and only for samples *inside* a box — a region of positive volume, not a measure-zero
 * surface.
 *
 * That was a real bug in this file, and it is worth saying how it hid. `SolidSLGradientTest`'s
 * central-difference census *saw* it — the `cuboid` subject reported 57 of 72 components usable where
 * every other primitive reported 72 — but the check treated a non-finite analytic value as "the
 * oracle has no opinion here" and skipped it, which is the same verdict it gives at a genuine
 * singularity. Conflating "the numerical oracle is untrustworthy" with "the thing under test returned
 * `NaN`" is how a differential test reports 95.9% coverage over a broken derivative.
 * [SolidGradientCheck] now separates them and fails on the second.
 *
 * The branch below is *exact*, not a regularisation: on the open region where all three `q` are
 * negative the exterior term is identically zero, so its derivative there genuinely is zero, and
 * returning the interior term alone is the right answer rather than an approximation to it. On the
 * boundary itself the field is still non-differentiable, which is true of a box and not a defect.
 */
internal fun adBox3(px: AD, py: AD, pz: AD, hx: AD, hy: AD, hz: AD): AD {
    val qx = abs(px) - hx; val qy = abs(py) - hy; val qz = abs(pz) - hz
    val inner = min(max(qx, max(qy, qz)), 0.0)
    if (qx.value < 0.0 && qy.value < 0.0 && qz.value < 0.0) return inner
    val ox = max(qx, 0.0); val oy = max(qy, 0.0); val oz = max(qz, 0.0)
    return sqrt(ox.sq() + oy.sq() + oz.sq()) + inner
}

/**
 * [sdBox2] in [AD].
 *
 * Carries [adBox3]'s interior branch for the same reason and by the same argument: `hypot(0, 0)`'s
 * partials are `0/0`, so a sample strictly inside a [Profile.Rect] produced a `NaN` gradient — and
 * a rect is the most common profile in the algebra.
 */
internal fun adBox2(px: AD, py: AD, hx: AD, hy: AD): AD {
    val qx = abs(px) - hx; val qy = abs(py) - hy
    val inner = min(max(qx, qy), 0.0)
    if (qx.value < 0.0 && qy.value < 0.0) return inner
    return hypot(max(qx, 0.0), max(qy, 0.0)) + inner
}

/**
 * [sdCappedConeZ] in [AD].
 *
 * Two things are differentiated *as taken* here and are named in [SolidExpr.gradientNotes]: the
 * `if (qy < 0) r1 else r2` cap selection, and the `s` inside/outside sign. Both are discrete choices
 * that flip only where the field is continuous across them — the cap choice at `z = 0`, where the
 * frustum's two radii are being interpolated anyway, and the sign at the surface, where the value is
 * zero. `t`'s clamp to `[0, 1]` is the honest zero-derivative one.
 */
internal fun adCappedConeZ(px: AD, py: AD, pz: AD, h: AD, r1: AD, r2: AD): AD {
    val qx = hypot(px, py)
    val qy = pz
    val k1x = r2; val k1y = h
    val k2x = r2 - r1; val k2y = h * 2.0
    val cax = qx - min(qx, if (qy.value < 0.0) r1 else r2)
    val cay = abs(qy) - h
    val k2dot = k2x.sq() + k2y.sq()
    val t = if (k2dot.value < 1e-30) k2dot * 0.0 else (((k1x - qx) * k2x + (k1y - qy) * k2y) / k2dot).clamp(0.0, 1.0)
    val cbx = qx - k1x + k2x * t
    val cby = qy - k1y + k2y * t
    val s = if (cbx.value < 0.0 && cay.value < 0.0) -1.0 else 1.0
    return sqrt(min(cax.sq() + cay.sq(), cbx.sq() + cby.sq())) * s
}

/**
 * [sdPolygon] in [AD], so a polygon's *vertices* are fittable.
 *
 * The crossing-parity sign is a plain `Double` ±1 and carries no derivative, which is correct rather
 * than a compromise: parity changes only where the unsigned distance is zero. What *is* a real
 * singularity is the `sqrt` of that squared distance at zero, i.e. exactly on the outline.
 */
internal fun adPolygon(xs: List<AD>, ys: List<AD>, px: AD, py: AD): AD {
    val n = xs.size
    var d = (px - xs[0]).sq() + (py - ys[0]).sq()
    var s = 1.0
    var j = n - 1
    for (i in 0 until n) {
        val ex = xs[j] - xs[i]; val ey = ys[j] - ys[i]
        val wx = px - xs[i]; val wy = py - ys[i]
        val e2 = ex.sq() + ey.sq()
        val t = if (e2.value < 1e-30) e2 * 0.0 else ((wx * ex + wy * ey) / e2).clamp(0.0, 1.0)
        val bx = wx - ex * t; val by = wy - ey * t
        d = min(d, bx.sq() + by.sq())
        val c1 = py.value >= ys[i].value
        val c2 = py.value < ys[j].value
        val c3 = (ex * wy).value > (ey * wx).value
        if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s
        j = i
    }
    return sqrt(d) * s
}

/** [smoothMin] in [AD] — C¹, and the reason a fitted shape should be written with [smoothUnion]. */
internal fun adSmoothMin(a: AD, b: AD, k: AD): AD {
    val h = ((b - a) / k * 0.5 + 0.5).clamp(0.0, 1.0)
    return b + (a - b) * h - k * h * (h * -1.0 + 1.0)
}

/** [smoothMax] in [AD]. */
internal fun adSmoothMax(a: AD, b: AD, k: AD): AD {
    val h = ((b - a) / k * -0.5 + 0.5).clamp(0.0, 1.0)
    return b + (a - b) * h + k * h * (h * -1.0 + 1.0)
}

/** [transpose3] of [eulerMatrix], in [AD], in the same X-then-Y-then-Z order. */
private fun adEulerMatrixTranspose(xDeg: AD, yDeg: AD, zDeg: AD): List<AD> {
    val d = Math.PI / 180.0
    val a = xDeg * d; val b = yDeg * d; val c = zDeg * d
    val ca = cos(a); val sa = sin(a)
    val cb = cos(b); val sb = sin(b)
    val cc = cos(c); val sc = sin(c)
    // eulerMatrix, row-major, then transposed — written out so the two agree by construction.
    val m = listOf(
        cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa,
        sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa,
        -sb, cb * sa, cb * ca,
    )
    return listOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
}

// ==============================================================================================
// Structural paths — one traversal, so the census and the substitution cannot disagree
// ==============================================================================================

/**
 * The simple type name a path segment uses. Deliberately *not* `this::class.simpleName`: a name in a
 * printed path is part of this file's contract, and a rename or a `@JvmName` should not silently
 * change every path a caller has written down.
 */
internal fun SolidExpr.nodeName(): String = when (this) {
    is SolidExpr.Sphere -> "Sphere"
    is SolidExpr.Cuboid -> "Cuboid"
    is SolidExpr.Cylinder -> "Cylinder"
    is SolidExpr.Extrude -> "Extrude"
    is SolidExpr.Revolve -> "Revolve"
    is SolidExpr.Gyroid -> "Gyroid"
    SolidExpr.Empty -> "Empty"
    is SolidExpr.Translate -> "Translate"
    is SolidExpr.Rotate -> "Rotate"
    is SolidExpr.Scale -> "Scale"
    is SolidExpr.Mirror -> "Mirror"
    is SolidExpr.Round -> "Round"
    is SolidExpr.Shell -> "Shell"
    is SolidExpr.Union -> "Union"
    is SolidExpr.Intersect -> "Intersect"
    is SolidExpr.Difference -> "Difference"
    is SolidExpr.SmoothUnion -> "SmoothUnion"
    is SolidExpr.SmoothDifference -> "SmoothDifference"
}

/** [SolidExpr.nodeName] for a [Profile]. */
internal fun Profile.nodeName(): String = when (this) {
    is Profile.Circle -> "Circle"
    is Profile.Rect -> "Rect"
    is Profile.Polygon -> "Polygon"
    is Profile.Offset -> "Offset"
    is Profile.Translate -> "Translate"
    is Profile.Rotate -> "Rotate"
    is Profile.Scale -> "Scale"
    is Profile.Union -> "Union"
    is Profile.Intersect -> "Intersect"
    is Profile.Difference -> "Difference"
    Profile.Empty -> "Empty"
}

/** What a traversal does with each number it finds: reports it, and says what to put back. */
private fun interface NumberRewrite {
    fun at(path: String, node: String, field: String, value: Double): Double
}

/**
 * The single traversal that both [numericSlots] and [withNumericValues] are built from.
 *
 * One walk rather than two is not a tidiness argument. A census that enumerates paths and a
 * substitution that resolves them are the two halves of one contract, and two independent `when`
 * blocks over thirty node kinds is an invitation for them to drift apart by one field — at which
 * point a caller binds a parameter to a path that enumerates but does not substitute, the optimiser
 * runs, nothing moves, and the gradient is a perfectly correct derivative of the wrong expression.
 * Sharing the walk makes that impossible. ([SolidNumerics.pathsAsked] closes the same loop against
 * the third `when`, in [toADField], which cannot share it because it returns a field rather than a
 * tree.)
 */
private fun rewriteNumbers(e: SolidExpr, prefix: String, r: NumberRewrite): SolidExpr {
    val self = prefix + e.nodeName()
    val kids = "$self/"
    fun n(field: String, v: Double) = r.at("$self.$field", e.nodeName(), field, v)

    return when (e) {
        is SolidExpr.Sphere -> e.copy(radius = n("radius", e.radius))
        is SolidExpr.Cuboid -> e.copy(x = n("x", e.x), y = n("y", e.y), z = n("z", e.z))
        is SolidExpr.Cylinder -> e.copy(
            height = n("height", e.height),
            radiusLow = n("radiusLow", e.radiusLow),
            radiusHigh = n("radiusHigh", e.radiusHigh),
        )
        is SolidExpr.Extrude -> e.copy(
            profile = rewriteProfileNumbers(e.profile, kids + "profile/", r),
            height = n("height", e.height),
            twistDegrees = n("twistDegrees", e.twistDegrees),
            scaleTop = n("scaleTop", e.scaleTop),
        )
        is SolidExpr.Revolve -> e.copy(
            profile = rewriteProfileNumbers(e.profile, kids + "profile/", r),
            degrees = n("degrees", e.degrees),
        )
        is SolidExpr.Gyroid -> e.copy(scale = n("scale", e.scale), thickness = n("thickness", e.thickness))
        SolidExpr.Empty -> e
        is SolidExpr.Translate -> e.copy(
            child = rewriteNumbers(e.child, kids, r),
            x = n("x", e.x), y = n("y", e.y), z = n("z", e.z),
        )
        is SolidExpr.Rotate -> e.copy(
            child = rewriteNumbers(e.child, kids, r),
            x = n("x", e.x), y = n("y", e.y), z = n("z", e.z),
        )
        is SolidExpr.Scale -> e.copy(
            child = rewriteNumbers(e.child, kids, r),
            x = n("x", e.x), y = n("y", e.y), z = n("z", e.z),
        )
        is SolidExpr.Mirror -> e.copy(
            child = rewriteNumbers(e.child, kids, r),
            x = n("x", e.x), y = n("y", e.y), z = n("z", e.z),
        )
        is SolidExpr.Round -> e.copy(
            child = rewriteNumbers(e.child, kids, r),
            radius = n("radius", e.radius),
        )
        is SolidExpr.Shell -> e.copy(
            child = rewriteNumbers(e.child, kids, r),
            thickness = n("thickness", e.thickness),
        )
        is SolidExpr.Union -> e.copy(children = e.children.mapIndexed { i, c -> rewriteNumbers(c, "$kids$i/", r) })
        is SolidExpr.Intersect -> e.copy(children = e.children.mapIndexed { i, c -> rewriteNumbers(c, "$kids$i/", r) })
        is SolidExpr.Difference -> e.copy(children = e.children.mapIndexed { i, c -> rewriteNumbers(c, "$kids$i/", r) })
        is SolidExpr.SmoothUnion -> e.copy(
            a = rewriteNumbers(e.a, kids + "a/", r),
            b = rewriteNumbers(e.b, kids + "b/", r),
            k = n("k", e.k),
        )
        is SolidExpr.SmoothDifference -> e.copy(
            a = rewriteNumbers(e.a, kids + "a/", r),
            b = rewriteNumbers(e.b, kids + "b/", r),
            k = n("k", e.k),
        )
    }
}

private fun rewriteProfileNumbers(p: Profile, prefix: String, r: NumberRewrite): Profile {
    val self = prefix + p.nodeName()
    val kids = "$self/"
    fun n(field: String, v: Double) = r.at("$self.$field", p.nodeName(), field, v)

    return when (p) {
        is Profile.Circle -> p.copy(radius = n("radius", p.radius))
        is Profile.Rect -> p.copy(x = n("x", p.x), y = n("y", p.y))
        is Profile.Polygon -> p.copy(
            points = p.points.mapIndexed { i, q ->
                P2(
                    r.at("$self.points[$i].x", "Polygon", "points[$i].x", q.x),
                    r.at("$self.points[$i].y", "Polygon", "points[$i].y", q.y),
                )
            },
        )
        is Profile.Offset -> p.copy(
            child = rewriteProfileNumbers(p.child, kids, r),
            delta = n("delta", p.delta),
        )
        is Profile.Translate -> p.copy(
            child = rewriteProfileNumbers(p.child, kids, r),
            x = n("x", p.x), y = n("y", p.y),
        )
        is Profile.Rotate -> p.copy(
            child = rewriteProfileNumbers(p.child, kids, r),
            degrees = n("degrees", p.degrees),
        )
        is Profile.Scale -> p.copy(
            child = rewriteProfileNumbers(p.child, kids, r),
            x = n("x", p.x), y = n("y", p.y),
        )
        is Profile.Union -> p.copy(children = p.children.mapIndexed { i, c -> rewriteProfileNumbers(c, "$kids$i/", r) })
        is Profile.Intersect -> p.copy(children = p.children.mapIndexed { i, c -> rewriteProfileNumbers(c, "$kids$i/", r) })
        is Profile.Difference -> p.copy(children = p.children.mapIndexed { i, c -> rewriteProfileNumbers(c, "$kids$i/", r) })
        Profile.Empty -> p
    }
}
