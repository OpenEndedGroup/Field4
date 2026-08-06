package afield.rectsl

import afield.stdlib.Functions
import afield.stdlib.graphNode
import kotlin.math.abs

/**
 * RectSL tracks as differentiable `Fn` graphs — the third backend.
 *
 * # Why there is a third backend at all
 *
 * `ddf732260` made the track IR symbolic and said why: a closure "cannot be printed, compared,
 * serialized, rewritten, or emitted as GLSL". It then delivered GLSL. [TrackOp1], [TrackOp2],
 * [TrackOp3] and [TimeWarpKind] have carried an `eval:` and a `glsl:` ever since — a two-backend
 * enum asking for a third.
 *
 * This is that third column, `fn:`, and it is the one that changes what RectSL can *do* rather
 * than where it can run. [afield.stdlib.Functions.Fn] is Field's own expression type, and
 * `autodiff_fn.kt` differentiates an `Fn` graph exactly with respect to its numeric leaves. So a
 * track that lowers to an `Fn` is a track you can take the derivative of, and therefore a track
 * you can **solve**: declare where a rect should be at some instant and let a gradient find the
 * numbers that put it there. That is [Track.fitParameters], and it is the point of this file.
 *
 * # The two identities that make it possible
 *
 * `Fn` was not designed as a general expression language, and two gaps look fatal until they are
 * not.
 *
 * **There is no "apply a unary function to another `Fn`'s value" op.** The differentiable value
 * transforms are `flip`, `abs`, `exp`, `log`, `pow`, `clamp`, `scaleY`, `offsetY` and pointwise
 * arithmetic — no `sin`. `Functions.sin` is an *oscillator* over the sample position,
 * `offset + amplitude·sin(2π·frequency·x + phase)`, which is not a composition. But its `phase`
 * child is evaluated **at x**, so
 *
 * ```
 * sin(frequency = 0, phase = u, amplitude = 1, offset = 0)   ==   sin(u(x))
 * ```
 *
 * exactly (`0 + 1·sin(2π·0·x + u)` collapses with no rounding). That single identity gives the
 * whole trigonometric family, which is most of the Penner easings.
 *
 * **There is no piecewise-in-x op, and every keyframed track is piecewise.** A clamped-ramp sum
 * fixes it. For keys `(tᵢ, vᵢ)` and any segment shape `Sᵢ` with `Sᵢ(0) = vᵢ` and `Sᵢ(1) = vᵢ₊₁`,
 *
 * ```
 * v(t)  =  v₀ + Σᵢ [ Sᵢ(clamp01((t - tᵢ) / (tᵢ₊₁ - tᵢ))) - vᵢ ]
 * ```
 *
 * is *identically* the clamped piecewise interpolant: for t inside span j every earlier term is
 * `vᵢ₊₁ - vᵢ` and every later one is 0, so the sum telescopes to `vⱼ + (vⱼ₊₁ - vⱼ)·Sⱼ(u)`. Before
 * the first key everything is 0 and after the last everything is 1, which is exactly the sampler's
 * clamping. It needs only clamp / divide / multiply / add, all differentiable, and it is exact to
 * a few ulps rather than approximate. [LinearScalarTrack], [EasedScalarTrack] and [TcbTrack]
 * (whose tangents [TcbTrack.hermiteSegments] has already resolved) all lower through it.
 *
 * # Refusals are in the type
 *
 * A wrong derivative is worse than no derivative, so the lowering never guesses. Every op declares
 * either a lowering or a refusal *with a reason* — [RectSLFnLowering] — and [Track.toFn] propagates
 * the first refusal it meets. This is the same discipline `FnNonDifferentiable` already applies
 * inside the stdlib, and for the same reason: the gap has to be readable, or it grows in silence.
 *
 * What is refused, and why:
 *
 * - `FLOOR` / `CEIL` / `FRACT` / `MOD` — a lattice. The derivative is zero on every open interval
 *   and undefined on the jumps, so a gradient through one carries no information at all.
 * - `LOOP` / `PINGPONG` time warps, unless the whole requested window sits inside one period, in
 *   which case they *are* the identity and lower exactly.
 * - [HoldScalarTrack] with more than one key, and [GateTrack] straddling an edge — genuine
 *   discontinuities. A gate wholly inside or wholly outside its window is a constant, and lowers.
 * - [HeadingTrack] — `atan2` has no `Fn` op.
 * - The pattern tracks ([PatTrack] and friends) — an interpreter over a mini-notation tree, which
 *   is the same reason `FnNonDifferentiable` files `pat` itself.
 * - The near-zero guards. RectSL's `/` returns 0 where `|divisor| <= 1e-12` and its `smoothstep`
 *   returns 0 for coincident edges; both are discontinuities no `Fn` op can express. Where the
 *   divisor (or the edge pair) is statically constant the guard is *folded* and the lowering is
 *   exact; where it is not, the op is refused rather than silently dropped.
 *
 * # How the fidelity is checked
 *
 * `RectSLFnBackendTest` is the reason to believe any of this. `ddf732260` also shipped
 * `src/jvmTest/resources/rectsl/track_characterization.txt`: every sampled rect of all 34 gallery
 * programs at 41 times, to full double precision, **generated from pre-refactor code**. The test
 * reads it as data and recomputes each rect's channels through Fn-lowered tracks, so the oracle
 * shares no code path with the thing under test. It reports the worst disagreement, and it
 * reports coverage — how much of the corpus lowered at all — because a differential test that
 * quietly refused everything would otherwise read as a pass.
 */

/** A track lowered to an `Fn`, or an explanation of why it was not. */
typealias TrackFnUnary = (Functions.Fn) -> Functions.Fn

/** Two `Fn` operands to one — the shape of [TrackOp2]'s lowering. */
typealias TrackFnBinary = (Functions.Fn, Functions.Fn) -> Functions.Fn

/** Three `Fn` operands to one — the shape of [TrackOp3]'s lowering. */
typealias TrackFnTernary = (Functions.Fn, Functions.Fn, Functions.Fn) -> Functions.Fn

/** A source `Fn` and a fixed scalar amount to one — the shape of [TimeWarpKind]'s lowering. */
typealias TrackFnWarp = (Functions.Fn, Double) -> Functions.Fn

/**
 * Either a lowering into the `Fn` world, or a refusal that says why.
 *
 * The refusal is deliberately a value rather than a null or a thrown exception. A null invites a
 * caller to substitute something plausible; a stated reason is a fact that survives into a test
 * assertion, a diagnostic, and a docs page. [Refused] is `RectSLFnLowering<Nothing>` so one
 * refusal serves every arity.
 */
sealed interface RectSLFnLowering<out T> {

    /** The lowering exists: [value] is the builder (or, at track level, the `Fn` itself). */
    data class Lowered<out T>(val value: T) : RectSLFnLowering<T>

    /**
     * There is no faithful differentiable form, [why] is the reason in one sentence, and [closable]
     * says whether that is permanent.
     *
     * The distinction is borrowed wholesale from [afield.stdlib.FnOpStatus], because it is the one
     * that matters to a reader: `closable = true` means the lowering could be written and has not
     * been, with [missing] naming what it would take; `false` means there is no honest lowering to
     * write. Both are gaps in coverage. Only one is a gap in ambition, and a reader deserves to be
     * able to tell them apart without reading the code.
     */
    data class Refused(
        val why: String,
        val closable: Boolean = false,
        val missing: String = ""
    ) : RectSLFnLowering<Nothing>
}

/** The lowered value, or null for a refusal. */
fun <T> RectSLFnLowering<T>.valueOrNull(): T? = (this as? RectSLFnLowering.Lowered<T>)?.value

/** The refusal reason, or null when this did lower. */
fun RectSLFnLowering<*>.refusalOrNull(): String? = (this as? RectSLFnLowering.Refused)?.why

/** The lowered value, or a thrown [RectLangException] carrying the refusal. */
fun <T> RectSLFnLowering<T>.orThrow(): T = when (this) {
    is RectSLFnLowering.Lowered -> value
    is RectSLFnLowering.Refused -> throw RectLangException("this track has no differentiable form: $why")
}

/**
 * A number as an unbounded `Fn` leaf.
 *
 * `Number.c` and not `Functions.c(v)`: the latter carries the default `0..1` domain and domains
 * *intersect* when functions combine, so one bounded constant anywhere in the tree would silently
 * crop the whole track to the unit interval. Every leaf this backend builds is over
 * [Functions.Domain.ALL] and the declared domain is applied once, at the top, by [toFn].
 */
internal fun fnLeaf(value: Double): Functions.Fn = with(Functions) { value.c }

/** `sin(u(x))` — see the oscillator identity in this file's KDoc. */
internal fun fnSinOf(u: Functions.Fn): Functions.Fn = with(Functions) {
    sin(
        frequency = fnLeaf(0.0),
        phase = u,
        amplitude = fnLeaf(1.0),
        offset = fnLeaf(0.0),
        domain = Functions.Domain.ALL
    )
}

/** `cos(u(x))` — see the oscillator identity in this file's KDoc. */
internal fun fnCosOf(u: Functions.Fn): Functions.Fn = with(Functions) {
    cos(
        frequency = fnLeaf(0.0),
        phase = u,
        amplitude = fnLeaf(1.0),
        offset = fnLeaf(0.0),
        domain = Functions.Domain.ALL
    )
}

/**
 * `min(a, b)` and `max(a, b)` as `clamp`, which is the only differentiable op that takes two
 * bounds. An infinite bound is inert: `coerceIn` compares and returns, and `-inf <= b` holds for
 * every non-NaN b, so no range is ever inverted.
 */
internal fun fnMinOf(a: Functions.Fn, b: Functions.Fn): Functions.Fn =
    with(Functions) { a.clamp(fnLeaf(Double.NEGATIVE_INFINITY), b) }

internal fun fnMaxOf(a: Functions.Fn, b: Functions.Fn): Functions.Fn =
    with(Functions) { a.clamp(b, fnLeaf(Double.POSITIVE_INFINITY)) }

/** `x.coerceIn(0.0, 1.0)` — the segment parameter every keyed lowering rides on. */
internal fun fnClamp01(x: Functions.Fn): Functions.Fn =
    with(Functions) { x.clamp(fnLeaf(0.0), fnLeaf(1.0)) }

/** The lowering's own spelling of time — `Fn`'s sample position, unbounded. */
internal fun fnTime(): Functions.Fn = with(Functions) { identity(Functions.Domain.ALL) }

/**
 * `1.0` where `x >= 0` and `0.0` below it, **exactly**, for every finite or infinite x.
 *
 * `clamp01(floor(x) + 1)` is the whole trick. On `[0, 1)` the floor is 0 and the sum is 1; on
 * `[1, 2)` it is 2 and the clamp brings it back to 1; on `[-1, 0)` the floor is -1 and the sum is 0;
 * below that the sum is negative and the clamp brings it to 0. The half-open boundary lands the right
 * way round — `x = 0` gives 1 — which is what `GateTrack` and `HoldScalarTrack` mean by `>=`.
 *
 * This is why [Functions.Fn.floor] was worth adding to the stdlib rather than worked around: a step
 * is not just a step, it is every hard-edged thing in an animation language, and RectSL has three of
 * them (a gate, a hold, the wrap of a looped clock).
 */
internal fun fnStepFrom(x: Functions.Fn): Functions.Fn =
    with(Functions) { fnClamp01(x.floor() + 1.0) }

/**
 * The floored modulo `x - period·floor(x / period)` — `positiveModulo`'s closed form.
 *
 * `positiveModulo` computes a truncated `%` and then adds the period back when the result came out
 * negative; that is the same function as this, written as a branch instead of as arithmetic. The
 * branch is what could not be lowered; the arithmetic can.
 */
internal fun fnFlooredModulo(x: Functions.Fn, period: Double): Functions.Fn =
    with(Functions) { x - fnLeaf(period) * (x / fnLeaf(period)).floor() }

/**
 * [source] looked up at [at] rather than at x — the general x-domain substitution.
 *
 * `Fn`'s only general lookup transform is `warp`, which reads at `x + depth·mod(x)`. Any target
 * position `g(x)` is `x + 1·(g(x) - x)`, so one `warp` expresses all of them. Two extra roundings
 * against the direct form, which is why [TimeWarpKind.SHIFT] and friends use `shift`/`scaleX`
 * instead and only the wrapping warps come through here.
 */
internal fun fnLookupAt(source: Functions.Fn, at: Functions.Fn): Functions.Fn =
    with(Functions) { source.warp(mod = at - fnTime(), depth = fnLeaf(1.0)) }

/**
 * The value a track takes when it does not vary with time, else null.
 *
 * Shared with the GLSL emitter, which needs exactly the same fold for exactly the same reason
 * (RectSL's array words build a hundred per-copy positions by offsetting one constant). The single
 * axis of difference is [foldFreeParameters]: the shader bakes a [FreeParamTrack] at its current
 * value, because a shader has no notion of an unknown, while the `Fn` backend must keep it as a
 * live leaf or there would be nothing to differentiate with respect to.
 */
internal fun trackConstantValue(
    track: Track<Double>,
    foldFreeParameters: Boolean = true
): Double? {
    fun rec(t: Track<Double>): Double? = trackConstantValue(t, foldFreeParameters)
    return when (track) {
        is ConstantTrack<*> -> track.value as Double
        is FreeParamTrack -> if (foldFreeParameters) track.value else null
        is MappedDoubleTrack -> rec(track.source)?.let(track.op.eval)
        is BinaryDoubleTrack -> {
            val a = rec(track.a)
            val b = rec(track.b)
            if (a != null && b != null) track.op.eval(a, b) else null
        }
        is TernaryDoubleTrack -> {
            val a = rec(track.a)
            val b = rec(track.b)
            val c = rec(track.c)
            if (a != null && b != null && c != null) track.op.eval(a, b, c) else null
        }
        // Warping the clock of something that ignores the clock changes nothing.
        is TimeWarpTrack -> rec(track.source)
        is TcbTrack -> track.keys.singleOrNull()?.value
        is LinearScalarTrack -> track.keys.singleOrNull()?.value
        is HoldScalarTrack -> track.keys.singleOrNull()?.value
        is EasedScalarTrack -> track.keys.singleOrNull()?.value
        is GateTrack -> null
        is HeadingTrack -> null
        // Every pattern track is a function of the cycle, so none is constant.
        is CycleGateTrack, is CycleSwitchTrack, is DegradeGateTrack, is PatTrack, is PatHoldTrack -> null
    }
}

/**
 * Every named easing that has a differentiable form, as a transform of the already-normalised
 * segment parameter `u`.
 *
 * Keyed identically to [RECTSL_EASINGS], and a subset of it: `RectSLFnBackendTest` asserts both
 * halves of that — every key here names a real easing, and the lowering agrees with that easing's own
 * closure across a 1001-point grid. What is here rests on three tricks, each of which was the reason
 * a family had looked impossible:
 *
 * - The `*-in-out` curves are piecewise but *continuous at the halfway point*, so the same
 *   clamped-ramp fold that lowers a keyed track lowers them:
 *   `f(u) = A(clamp01(2u)) + [B(0) - B(clamp01(2u-1))]`, arranged so the second bracket is
 *   identically zero on the first half.
 * - The trigonometric curves go through the oscillator-phase identity (see this file's KDoc).
 * - `expo-*` and `elastic-*` have exact-endpoint special cases the closed form does not reach —
 *   expo-in is 2^-10 at u = 0, not 0 — and in a keyed track `u` is exactly 0 or exactly 1 everywhere
 *   outside the active span, so those branches are the common path. [Functions.Fn.floor] turns them
 *   into arithmetic: see `withExactEndpoints` below.
 *
 * What is left out is `bounce-*`: four parabolic arcs, each join needing its own ramp. Expressible
 * with the same machinery, not yet expressed.
 */
internal val RECTSL_EASING_FNS: Map<String, TrackFnUnary> = buildMap {

    // Penner's back constants, transcribed from Functions.shapeAlpha rather than restated.
    val c1 = 1.70158
    val c2 = c1 * 1.525
    val c3 = c1 + 1.0

    /** `u` folded to the first half's parameter: `2u` clamped, so it saturates at 1 past halfway. */
    fun lower(u: Functions.Fn): Functions.Fn = fnClamp01(with(Functions) { u * 2.0 })

    /** `u` folded to the second half's parameter: 0 until halfway, then `2u-1`. */
    fun upper(u: Functions.Fn): Functions.Fn = fnClamp01(with(Functions) { u * 2.0 - 1.0 })

    fun powIn(p: Double): TrackFnUnary = { u -> with(Functions) { u.pow(fnLeaf(p)) } }
    fun powOut(p: Double): TrackFnUnary = { u -> with(Functions) { 1.0 - (1.0 - u).pow(fnLeaf(p)) } }

    /**
     * `if (t < 0.5) 0.5·(2t)^p else 1 - 0.5·(2-2t)^p`, as one expression.
     *
     * Associated as `A + (0.5 - B)` rather than `A + 0.5 - B` so the first half is bit-exact: there
     * `B` is exactly 0.5, the bracket is exactly 0.0, and nothing is added to `A` at all.
     */
    fun powInOut(p: Double): TrackFnUnary = { u ->
        with(Functions) {
            val a = lower(u).pow(fnLeaf(p)) * 0.5
            val b = (1.0 - upper(u)).pow(fnLeaf(p)) * 0.5
            a + (0.5 - b)
        }
    }

    put("linear") { u -> u }
    put("quad-in", powIn(2.0))
    put("quad-out", powOut(2.0))
    put("quad-in-out", powInOut(2.0))
    put("cubic-in", powIn(3.0))
    put("cubic-out", powOut(3.0))
    put("cubic-in-out", powInOut(3.0))
    put("quart-in", powIn(4.0))
    put("quart-out", powOut(4.0))
    put("quart-in-out", powInOut(4.0))
    put("quint-in", powIn(5.0))
    put("quint-out", powOut(5.0))
    put("quint-in-out", powInOut(5.0))

    // `t * Math.PI / 2.0` associates as `(t·π)/2`, not `t·(π/2)`; transcribed in that order.
    put("sine-in") { u -> with(Functions) { 1.0 - fnCosOf(u * Math.PI / 2.0) } }
    put("sine-out") { u -> with(Functions) { fnSinOf(u * Math.PI / 2.0) } }
    put("sine-in-out") { u -> with(Functions) { (fnCosOf(u * Math.PI) - 1.0).flip() * 0.5 } }

    // `sqrt` is spelled `pow(0.5)`: Fn has no square-root op, and Math.pow(x, 0.5) agrees with
    // Math.sqrt(x) to within an ulp on every value a normalised segment parameter can take.
    put("circ-in") { u -> with(Functions) { 1.0 - (1.0 - u * u).pow(fnLeaf(0.5)) } }
    put("circ-out") { u -> with(Functions) { ((1.0 - (u - 1.0) * (u - 1.0))).pow(fnLeaf(0.5)) } }
    put("circ-in-out") { u ->
        with(Functions) {
            val a = lower(u)
            val s = upper(u)
            val first = (1.0 - (1.0 - a * a).pow(fnLeaf(0.5))) * 0.5
            val second = (1.0 - (1.0 - s) * (1.0 - s)).pow(fnLeaf(0.5)) * 0.5
            first + second
        }
    }

    put("back-in") { u -> with(Functions) { u * u * u * c3 - u * u * c1 } }
    put("back-out") { u ->
        with(Functions) { 1.0 + (u - 1.0).pow(fnLeaf(3.0)) * c3 + (u - 1.0).pow(fnLeaf(2.0)) * c1 }
    }
    put("back-in-out") { u ->
        with(Functions) {
            val a = lower(u)
            val s = upper(u)
            val first = (a * a * (a * (c2 + 1.0) - c2)) * 0.5
            // On the first half `s` is 0 and this bracket is exactly ((1)(-(c2+1)+c2)+1)/2 = 0.
            val second = (((s - 1.0) * (s - 1.0)) * ((s - 1.0) * (c2 + 1.0) + c2) + 1.0) * 0.5
            first + second
        }
    }

    // ---- the exact-endpoint family --------------------------------------------------------------
    // `Functions.shapeAlpha` special-cases `if (t == 0.0) 0.0` / `if (t == 1.0) 1.0` for the expo and
    // elastic curves, because their closed forms do not reach their own endpoints: expo-in is 2^-10 at
    // t = 0, not 0. In a keyed track `u` is *exactly* 0 or exactly 1 everywhere outside the active
    // span, so those branches are the common path rather than a corner case, and a lowering without
    // them would be wrong by 1e-3 instead of by an ulp. That is why they were refused.
    //
    // `floor` closes them. `floor(u)` is 1 exactly when u = 1 and 0 on [0, 1); `floor(1 - u)` is 1
    // exactly when u = 0. So
    //
    //     E(u) = closed(u) * (1 - floor(u)) * (1 - floor(1 - u)) + floor(u)
    //
    // is the closed form strictly inside, exactly 0 at 0, and exactly 1 at 1 — the branch structure
    // of the original, as arithmetic.
    fun withExactEndpoints(closed: (Functions.Fn) -> Functions.Fn): TrackFnUnary = { u ->
        with(Functions) {
            val atOne = u.floor()
            val atZero = (1.0 - u).floor()
            closed(u) * (1.0 - atOne) * (1.0 - atZero) + atOne
        }
    }

    // 2^y is spelled exp(y * ln 2): Fn has a natural exponential and no other base.
    fun twoTo(y: Functions.Fn): Functions.Fn =
        with(Functions) { (y * kotlin.math.ln(2.0)).exp() }

    put("expo-in", withExactEndpoints { u -> with(Functions) { twoTo(u * 10.0 - 10.0) } })
    put("expo-out", withExactEndpoints { u -> with(Functions) { 1.0 - twoTo(u * -10.0) } })
    put(
        "expo-in-out",
        withExactEndpoints { u ->
            with(Functions) {
                // `if (t < 0.5) 2^(20t-10)/2 else (2 - 2^(-20t+10))/2`, continuous at the halfway
                // point (both give 0.5), so the clamped-ramp fold applies exactly as it does to the
                // polynomial in-outs: the second bracket is identically zero on the first half.
                val a = lower(u)
                val s = upper(u)
                twoTo(a * 10.0 - 10.0) * 0.5 + (0.5 - twoTo(s * -10.0) * 0.5)
            }
        }
    )

    // Penner's elastic constants, transcribed from Functions.shapeAlpha.
    val c4 = (Math.PI * 2.0) / 3.0
    val c5 = (Math.PI * 2.0) / 4.5

    put(
        "elastic-in",
        withExactEndpoints { u ->
            with(Functions) { (twoTo(u * 10.0 - 10.0) * fnSinOf((u * 10.0 - 10.75) * c4)).flip() }
        }
    )
    put(
        "elastic-out",
        withExactEndpoints { u ->
            with(Functions) { twoTo(u * -10.0) * fnSinOf((u * 10.0 - 0.75) * c4) + 1.0 }
        }
    )
    put(
        "elastic-in-out",
        withExactEndpoints { u ->
            with(Functions) {
                // Unlike the other in-outs this one is *not* continuous through a shared value at
                // u = 0.5 in a form the ramp fold likes, so the halves are selected with the same
                // exact step the gates use rather than blended.
                val below = 1.0 - fnStepFrom(u - 0.5)
                val above = fnStepFrom(u - 0.5)
                val first = (twoTo(u * 20.0 - 10.0) * fnSinOf((u * 20.0 - 11.125) * c5)).flip() * 0.5
                val second = twoTo(u * -20.0 + 10.0) * fnSinOf((u * 20.0 - 11.125) * c5) * 0.5 + 1.0
                first * below + second * above
            }
        }
    )

    put("smoothstep") { u -> with(Functions) { u * u * (3.0 - u * 2.0) } }
    put("smootherstep") { u -> with(Functions) { u * u * u * (u * (u * 6.0 - 15.0) + 10.0) } }

    // The four shorthand words that predate the Penner family. `ease-in` is `u*u` written as a
    // multiplication, not as `pow(u, 2)`, because that is what RectSL's own easeIn does.
    put("ease-in") { u -> with(Functions) { u * u } }
    put("ease-out") { u -> with(Functions) { 1.0 - (1.0 - u) * (1.0 - u) } }
    put("ease-in-out") { u ->
        with(Functions) {
            val a = lower(u)
            val s = upper(u)
            a * a * 0.5 + (0.5 - (1.0 - s) * (1.0 - s) * 0.5)
        }
    }
    put("ease-smoothstep") { u -> with(Functions) { u * u * (3.0 - u * 2.0) } }
    put("smooth") { u -> with(Functions) { u * u * (3.0 - u * 2.0) } }
}

/**
 * This track as a differentiable function of time, or a refusal saying why not.
 *
 * [over] is both the declared domain of the result and the window the lowering has to be *valid*
 * on, which is what lets the two branch-shaped warps ([TimeWarpKind.LOOP],
 * [TimeWarpKind.PINGPONG]) and [GateTrack] lower exactly whenever the window sits inside a single
 * branch. Pass the scene's own time range; [Functions.Domain.ALL] asks for a lowering valid for
 * all time, which is stricter and refuses more.
 */
fun Track<Double>.toFn(
    over: Functions.Domain = Functions.Domain.ALL
): RectSLFnLowering<Functions.Fn> {
    val lowered = lowerTrackToFn(this, over)
    return when (lowered) {
        is RectSLFnLowering.Refused -> lowered
        is RectSLFnLowering.Lowered -> RectSLFnLowering.Lowered(lowered.value.withDomain(over))
    }
}

/** True when the given closed window lies inside `[lo, hi)`. */
private fun windowInside(window: Functions.Domain, lo: Double, hi: Double): Boolean =
    window.isFinite && window.start >= lo && window.endInclusive < hi

/** True when the given closed window shares no point with `[lo, hi)`. */
private fun windowOutside(window: Functions.Domain, lo: Double, hi: Double): Boolean =
    window.isFinite && (window.endInclusive < lo || window.start >= hi)

private fun lowered(fn: Functions.Fn): RectSLFnLowering<Functions.Fn> =
    RectSLFnLowering.Lowered(fn)

private fun refused(why: String): RectSLFnLowering<Functions.Fn> =
    RectSLFnLowering.Refused(why)

/**
 * The walker. Constant subtrees are folded to a single leaf first — that is not an optimisation
 * for its own sake, it is what makes `FLOOR`, `MOD` and a coincident-edge `smoothstep` lower at
 * all in the cases that actually occur, and it keeps a hundred-copy array from becoming a
 * hundred-node graph.
 */
private fun lowerTrackToFn(
    track: Track<Double>,
    window: Functions.Domain
): RectSLFnLowering<Functions.Fn> {
    trackConstantValue(track, foldFreeParameters = false)?.let { return lowered(fnLeaf(it)) }

    return when (track) {
        is FreeParamTrack -> lowered(fnLeaf(track.value).withName(FREE_PARAM_GRAPH_PREFIX + track.name))

        is ConstantTrack<*> -> lowered(fnLeaf(track.value as Double))

        is MappedDoubleTrack -> when (val op = track.op.fn) {
            is RectSLFnLowering.Refused -> refused("${track.op} — ${op.why}")
            is RectSLFnLowering.Lowered -> lowerTrackToFn(track.source, window).map(op.value)
        }

        is BinaryDoubleTrack -> lowerBinary(track, window)

        is TernaryDoubleTrack -> lowerTernary(track, window)

        is TimeWarpTrack -> lowerWarp(track, window)

        is LinearScalarTrack -> lowerKeyed(track.keys, "linear") { u, _ -> u }

        is EasedScalarTrack -> {
            val ease = RECTSL_EASING_FNS[track.easeName]
                ?: return refused(
                    "the '${track.easeName}' easing has no differentiable form " +
                        "(see RECTSL_EASING_FNS for which do and why the rest do not)"
                )
            lowerKeyed(track.keys, track.easeName) { u, _ -> ease(u) }
        }

        is TcbTrack -> lowerTcb(track)

        // `v0 + sum_i (v_i - v_(i-1)) * [t >= t_i]` — the sampler's `lastOrNull { it.time <= t }`
        // written as arithmetic. Before its keys the sampler falls back to the first value, which is
        // what the bare `v0` is. The derivative is zero almost everywhere and that is the truth
        // about a staircase, not an approximation of it.
        is HoldScalarTrack -> {
            val sorted = track.keys.sortedBy { it.time }
            if (sorted.isEmpty()) {
                refused("a hold track with no keys cannot be sampled at all")
            } else {
                with(Functions) {
                    var acc = fnLeaf(sorted.first().value)
                    for (i in 1 until sorted.size) {
                        val rise = sorted[i].value - sorted[i - 1].value
                        acc += fnStepFrom(fnTime() - fnLeaf(sorted[i].time)) * fnLeaf(rise)
                    }
                    lowered(acc)
                }
            }
        }

        // `[t >= lo] * (1 - [t >= hi])`, which is exactly the sampler's `t >= lo && t < hi`,
        // including which way round the half-open boundary falls. The window checks stay in front
        // because a gate the window never crosses folds to a literal 1 or 0, which is a smaller
        // graph and one fewer place for a step to sit under a fitted parameter.
        is GateTrack -> when {
            windowInside(window, track.lo, track.hi) -> lowered(fnLeaf(1.0))
            windowOutside(window, track.lo, track.hi) -> lowered(fnLeaf(0.0))
            else -> with(Functions) {
                // An infinite edge is dropped rather than lowered: `floor(+inf) + 1` does clamp to
                // the right answer, but leaving an infinity in a theta vector is asking for a NaN
                // the first time anything perturbs it.
                val fromLo = if (track.lo == Double.NEGATIVE_INFINITY) {
                    null
                } else {
                    fnStepFrom(fnTime() - fnLeaf(track.lo))
                }
                val beforeHi = if (track.hi == Double.POSITIVE_INFINITY) {
                    null
                } else {
                    1.0 - fnStepFrom(fnTime() - fnLeaf(track.hi))
                }
                lowered(
                    when {
                        fromLo != null && beforeHi != null -> fromLo * beforeHi
                        fromLo != null -> fromLo
                        beforeHi != null -> beforeHi
                        else -> fnLeaf(1.0)
                    }
                )
            }
        }

        is HeadingTrack -> RectSLFnLowering.Refused(
            "a heading is atan2 of a finite difference, and Fn has no atan2 op",
            closable = true,
            missing = "an FnExpr.Atan (derivative 1/(1+x^2)) plus the quadrant fix-up atan2 needs, " +
                "which the exact step from fnStepFrom can now supply; the finite difference itself " +
                "is already just two lookups of the same lowered track"
        )

        is CycleGateTrack, is CycleSwitchTrack, is DegradeGateTrack, is PatTrack, is PatHoldTrack ->
            RectSLFnLowering.Refused(
                "a pattern track is an interpreter over a mini-notation tree, not an expression of " +
                    "fixed shape — the same reason FnNonDifferentiable refuses `pat` itself"
            )
    }
}

/** Applies [f] to a successful lowering, propagating a refusal untouched. */
private fun RectSLFnLowering<Functions.Fn>.map(
    f: (Functions.Fn) -> Functions.Fn
): RectSLFnLowering<Functions.Fn> = when (this) {
    is RectSLFnLowering.Refused -> this
    is RectSLFnLowering.Lowered -> RectSLFnLowering.Lowered(f(value))
}

private fun lowerBinary(
    track: BinaryDoubleTrack,
    window: Functions.Domain
): RectSLFnLowering<Functions.Fn> {
    // `/` and `%` guard a near-zero divisor by returning 0, which is a discontinuity. A statically
    // constant divisor settles the branch here and the lowering stays exact; anything else is
    // refused rather than quietly losing the guard. (`%` is refused either way — see TrackOp2.MOD.)
    if (track.op == TrackOp2.DIV) {
        val divisor = trackConstantValue(track.b, foldFreeParameters = false)
            ?: return refused(
                "TrackOp2.DIV — ${(track.op.fn as RectSLFnLowering.Refused).why}, and this " +
                    "divisor is not statically constant"
            )
        if (abs(divisor) <= 1e-12) return lowered(fnLeaf(0.0))
        return lowerTrackToFn(track.a, window).map { a -> with(Functions) { a / fnLeaf(divisor) } }
    }

    val op = track.op.fn
    if (op is RectSLFnLowering.Refused) return refused("${track.op} — ${op.why}")
    val build = (op as RectSLFnLowering.Lowered).value
    val a = lowerTrackToFn(track.a, window)
    if (a is RectSLFnLowering.Refused) return a
    val b = lowerTrackToFn(track.b, window)
    if (b is RectSLFnLowering.Refused) return b
    return lowered(
        build(
            (a as RectSLFnLowering.Lowered).value,
            (b as RectSLFnLowering.Lowered).value
        )
    )
}

private fun lowerTernary(
    track: TernaryDoubleTrack,
    window: Functions.Domain
): RectSLFnLowering<Functions.Fn> {
    // smoothstep's coincident-edge guard is the same shape of problem as DIV's: constant edges
    // settle it, anything else loses it.
    if (track.op == TrackOp3.SMOOTHSTEP) {
        val low = trackConstantValue(track.a, foldFreeParameters = false)
        val high = trackConstantValue(track.b, foldFreeParameters = false)
        if (low == null || high == null) {
            return refused(
                "TrackOp3.SMOOTHSTEP — ${(track.op.fn as RectSLFnLowering.Refused).why}, and these " +
                    "edges are not statically constant"
            )
        }
        if (abs(high - low) <= 1e-12) return lowered(fnLeaf(0.0))
        return lowerTrackToFn(track.c, window).map { x ->
            with(Functions) {
                val u = fnClamp01((x - fnLeaf(low)) / fnLeaf(high - low))
                u * u * (3.0 - u * 2.0)
            }
        }
    }

    val op = track.op.fn
    if (op is RectSLFnLowering.Refused) return refused("${track.op} — ${op.why}")
    val build = (op as RectSLFnLowering.Lowered).value
    val a = lowerTrackToFn(track.a, window)
    if (a is RectSLFnLowering.Refused) return a
    val b = lowerTrackToFn(track.b, window)
    if (b is RectSLFnLowering.Refused) return b
    val c = lowerTrackToFn(track.c, window)
    if (c is RectSLFnLowering.Refused) return c
    return lowered(
        build(
            (a as RectSLFnLowering.Lowered).value,
            (b as RectSLFnLowering.Lowered).value,
            (c as RectSLFnLowering.Lowered).value
        )
    )
}

private fun lowerWarp(
    track: TimeWarpTrack,
    window: Functions.Domain
): RectSLFnLowering<Functions.Fn> {
    // A wrap the requested window never reaches is the identity, exactly. That is worth checking
    // rather than refusing: `loop-time` with the scene's own duration is the common spelling, and
    // over `[0, duration)` it does nothing at all.
    val period = track.amount
    val identity = when (track.warp) {
        TimeWarpKind.LOOP -> abs(period) <= 1e-12 || windowInside(window, 0.0, period)
        TimeWarpKind.PINGPONG -> abs(period * 2.0) <= 1e-12 || windowInside(window, 0.0, period)
        else -> false
    }
    // The source's own clock is the warped one, so a warp that is the identity on this window also
    // hands the source that same window.
    if (identity) return lowerTrackToFn(track.source, window)

    val op = track.warp.fn
    if (op is RectSLFnLowering.Refused) return refused("${track.warp} — ${op.why}")
    val build = (op as RectSLFnLowering.Lowered).value
    // A warp moves the clock, so the source is asked for a lowering valid over the *warped*
    // window, not the original one. Both bounds are mapped and re-sorted, since SHIFT and SCALE
    // can invert the interval.
    val warpedWindow = if (window.isFinite) {
        val a = track.warp.eval(window.start, period)
        val b = track.warp.eval(window.endInclusive, period)
        Functions.Domain(minOf(a, b), maxOf(a, b))
    } else {
        window
    }
    return lowerTrackToFn(track.source, warpedWindow).map { src -> build(src, period) }
}

/**
 * A keyed track as the clamped-ramp sum described in this file's KDoc.
 *
 * [shape] turns a span's normalised parameter into its 0..1 progress; [LinearScalarTrack] passes it
 * through and an [EasedScalarTrack] runs its easing over it. The span index is handed along because
 * a TCB span needs it, though the two keyed callers here do not.
 */
private fun lowerKeyed(
    keys: List<TcbKey>,
    label: String,
    shape: (Functions.Fn, Int) -> Functions.Fn
): RectSLFnLowering<Functions.Fn> {
    val sorted = keys.sortedBy { it.time }
    if (sorted.isEmpty()) return refused("a $label track with no keys cannot be sampled at all")
    if (sorted.size == 1) return lowered(fnLeaf(sorted.first().value))
    for (i in 0 until sorted.size - 1) {
        val dt = sorted[i + 1].time - sorted[i].time
        if (abs(dt) <= 1e-12) {
            return refused(
                "two keys of this $label track share a time (${sorted[i].time}), which is a step " +
                    "rather than a span"
            )
        }
    }
    return with(Functions) {
        var acc = fnLeaf(sorted.first().value)
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val u = fnClamp01((identity(Functions.Domain.ALL) - fnLeaf(a.time)) / fnLeaf(b.time - a.time))
            acc += shape(u, i) * fnLeaf(b.value - a.value)
        }
        lowered(acc)
    }
}

/**
 * A TCB track as Hermite spans, tangents already resolved by [TcbTrack.hermiteSegments].
 *
 * The same telescoping sum works because the Hermite basis is `p0` at u=0 and `p1` at u=1, so each
 * span contributes `S(u) - p0` and the terms before the active span add up to exactly the key value
 * it starts from.
 *
 * The tangents are **baked at lowering time**. They are derived from the key values and times, so a
 * fit that moves a key value ought to move the tangents with it and this lowering will not; the
 * derivative is exact for the spline whose tangents are these, which is the right answer for
 * evaluation and an approximation for fitting a TCB key. Stated here because it is the one place in
 * this file where the differentiated function is not literally the sampled one.
 */
private fun lowerTcb(track: TcbTrack): RectSLFnLowering<Functions.Fn> {
    val segments = track.hermiteSegments()
    if (segments.isEmpty()) {
        return lowered(fnLeaf(track.keys.minByOrNull { it.time }?.value ?: 0.0))
    }
    for (segment in segments) {
        if (abs(segment.dt) <= 1e-12) {
            return refused("two keys of this TCB track share a time (${segment.t0})")
        }
    }
    return with(Functions) {
        var acc = fnLeaf(segments.first().p0)
        for (segment in segments) {
            val u = fnClamp01(
                (identity(Functions.Domain.ALL) - fnLeaf(segment.t0)) / fnLeaf(segment.dt)
            )
            val u2 = u * u
            val u3 = u2 * u
            // TcbTrack.sample's basis, transcribed term for term.
            val h00 = u3 * 2.0 - u2 * 3.0 + 1.0
            val h10 = u3 - u2 * 2.0 + u
            val h01 = u3 * -2.0 + u2 * 3.0
            val h11 = u3 - u2
            val value = h00 * fnLeaf(segment.p0) +
                h10 * fnLeaf(segment.dt * segment.m0) +
                h01 * fnLeaf(segment.p1) +
                h11 * fnLeaf(segment.dt * segment.m1)
            acc += value - fnLeaf(segment.p0)
        }
        lowered(acc)
    }
}

/**
 * The graph-node name prefix that marks a lowered [FreeParamTrack].
 *
 * `Fn` has no notion of a *named* parameter — every numeric leaf is just θ index i. But
 * `Fn.withName` wraps a leaf in a differentiable label node, so the name survives into the graph
 * and [freeParameterIndices] can read the mapping back out. Prefixed so a track called "x" cannot
 * collide with a leaf some other part of the library happened to name "x".
 */
internal const val FREE_PARAM_GRAPH_PREFIX: String = "rectsl.param:"

/**
 * Which θ index each named free parameter ended up at, for the graph [fn].
 *
 * The traversal is the one `FnAutoDiff.leafIndices` uses and `Fn.extractParameters` reads —
 * post-order DFS over children in declaration order, one index per node carrying `numbers["value"]`
 * — so index *i* here is index *i* there. A label node contains exactly one leaf, so the index
 * assigned while descending into it is the one the name belongs to.
 */
fun freeParameterIndices(fn: Functions.Fn): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    var counter = 0
    fun walk(f: Functions.Fn) {
        val node = f.graphNode()
        val before = counter
        for (edge in node.children) walk(edge.fn)
        if (node.numbers.containsKey("value")) counter++
        val label = node.strings["name"]
        if (node.op == "name" && label != null && label.startsWith(FREE_PARAM_GRAPH_PREFIX) &&
            counter == before + 1
        ) {
            out[label.removePrefix(FREE_PARAM_GRAPH_PREFIX)] = before
        }
    }
    walk(fn)
    return out
}

/**
 * Every named free parameter in this track tree, with its current value, in first-seen order.
 *
 * Reads the IR rather than the lowered graph, so it answers even for a track that does not lower.
 */
fun Track<Double>.freeParameters(): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    walkTrack(this) { if (it is FreeParamTrack) out.putIfAbsent(it.name, it.value) }
    return out
}

/**
 * This track with each named free parameter set to the value [values] gives for it, leaving
 * anything the map does not mention alone.
 *
 * This is the *rewriting* half of what `ddf732260` promised a symbolic IR would allow, and it is
 * three lines of real work because the IR is data: a closure-carrying track could not have been
 * rewritten at all.
 */
fun Track<Double>.substituteParameters(values: Map<String, Double>): Track<Double> =
    mapTrack(this) { t ->
        if (t is FreeParamTrack && values.containsKey(t.name)) {
            t.copy(value = values.getValue(t.name))
        } else {
            t
        }
    }

/** Visits [track] and every scalar track beneath it, parents before children. */
internal fun walkTrack(track: Track<Double>, visit: (Track<Double>) -> Unit) {
    visit(track)
    for (child in trackChildren(track)) walkTrack(child, visit)
}

/**
 * The scalar sub-tracks of [track], in the order the IR declares them.
 *
 * Exhaustive over the sealed hierarchy on purpose rather than falling through an `else`: a new
 * track shape should not silently become a leaf here, it should fail to compile.
 */
internal fun trackChildren(track: Track<Double>): List<Track<Double>> = when (track) {
    is MappedDoubleTrack -> listOf(track.source)
    is BinaryDoubleTrack -> listOf(track.a, track.b)
    is TernaryDoubleTrack -> listOf(track.a, track.b, track.c)
    is TimeWarpTrack -> listOf(track.source)
    is HeadingTrack -> listOf(track.x, track.y)
    is CycleSwitchTrack -> listOf(track.before, track.after)
    is ConstantTrack<*>, is FreeParamTrack, is TcbTrack, is LinearScalarTrack, is HoldScalarTrack,
    is EasedScalarTrack, is GateTrack, is CycleGateTrack, is DegradeGateTrack, is PatTrack,
    is PatHoldTrack -> emptyList()
}

/**
 * Rebuilds [track] bottom-up, applying [f] to every node after its children have been rewritten.
 *
 * The whole reason this can be written at all is that the IR is data. The pre-`ddf732260` tracks
 * carried closures, and a closure has no children to rebuild.
 */
internal fun mapTrack(track: Track<Double>, f: (Track<Double>) -> Track<Double>): Track<Double> {
    val rebuilt: Track<Double> = when (track) {
        is MappedDoubleTrack -> track.copy(source = mapTrack(track.source, f))
        is BinaryDoubleTrack -> track.copy(a = mapTrack(track.a, f), b = mapTrack(track.b, f))
        is TernaryDoubleTrack -> track.copy(
            a = mapTrack(track.a, f),
            b = mapTrack(track.b, f),
            c = mapTrack(track.c, f)
        )
        is TimeWarpTrack -> track.copy(source = mapTrack(track.source, f))
        is HeadingTrack -> track.copy(x = mapTrack(track.x, f), y = mapTrack(track.y, f))
        is CycleSwitchTrack -> track.copy(
            before = mapTrack(track.before, f),
            after = mapTrack(track.after, f)
        )
        is ConstantTrack<*>, is FreeParamTrack, is TcbTrack, is LinearScalarTrack,
        is HoldScalarTrack, is EasedScalarTrack, is GateTrack, is CycleGateTrack,
        is DegradeGateTrack, is PatTrack, is PatHoldTrack -> track
    }
    return f(rebuilt)
}

/**
 * The track tree as one line of prose — the *printing* half of what `ddf732260` promised.
 *
 * Deliberately not `toString`: a data class already prints its fields, and what is wanted here is
 * the algebra, so `x + sin(t)` reads as `(k(3) + SIN(linear[0..2]))` rather than as three nested
 * constructor dumps. Constant subtrees are folded, so an array's hundredth copy prints as its
 * value rather than as the arithmetic that produced it.
 */
fun Track<Double>.describe(): String {
    trackConstantValue(this, foldFreeParameters = false)?.let { return "k($it)" }
    return when (this) {
        is FreeParamTrack -> "?$name=$value"
        is ConstantTrack<*> -> "k($value)"
        is MappedDoubleTrack -> "$op(${source.describe()})"
        is BinaryDoubleTrack -> "$op(${a.describe()}, ${b.describe()})"
        is TernaryDoubleTrack -> "$op(${a.describe()}, ${b.describe()}, ${c.describe()})"
        is TimeWarpTrack -> "$warp[$amount](${source.describe()})"
        is TcbTrack -> "tcb[${keys.joinToString(" ") { "${it.time}=${it.value}" }}]"
        is LinearScalarTrack -> "linear[${keys.joinToString(" ") { "${it.time}=${it.value}" }}]"
        is HoldScalarTrack -> "hold[${keys.joinToString(" ") { "${it.time}=${it.value}" }}]"
        is EasedScalarTrack -> "$easeName[${keys.joinToString(" ") { "${it.time}=${it.value}" }}]"
        is GateTrack -> "gate[$lo..$hi]"
        is HeadingTrack -> "heading(${x.describe()}, ${y.describe()})"
        is CycleSwitchTrack -> "every[$k](${before.describe()}, ${after.describe()})"
        is CycleGateTrack -> "everyGate[$k]"
        is DegradeGateTrack -> "degrade[$ordinal@$probability]"
        is PatTrack -> "pat[$cps]"
        is PatHoldTrack -> "patHold[$cps]"
    }
}
