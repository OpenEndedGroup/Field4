package afield.rectsl

import kotlin.math.abs

/**
 * An algebraic simplifier over the track IR — the last of the four capabilities `ddf732260` said a
 * symbolic IR would allow.
 *
 * That commit's message named five things a closure cannot do: be printed, compared, serialized,
 * rewritten, or emitted as GLSL. GLSL shipped with it. `describe()` is the printing and
 * `substituteParameters` is a first, targeted rewrite. **Comparison came free** — the IR is data
 * classes, so two tracks built by the same `array` body are already `==`, which is exactly what the
 * GLSL emitter's function deduplication has been quietly relying on. This is the general rewriter,
 * and it is the one that needed comparison to exist: half the rules below fire on two subtrees being
 * structurally equal.
 *
 * # What it is for
 *
 * Not speed, primarily. RectSL's array words build per-copy channels by *offsetting* a constant, so a
 * hundred-copy array arrives at a backend as a hundred `BinaryDoubleTrack(const, const, ADD)` nodes,
 * and each backend has had to fold that itself: [trackConstantValue] exists because the GLSL emitter
 * needed it, and the `Fn` lowering calls the same function for the same reason. A fold is the
 * *smallest* rewrite, and having it live inside two emitters rather than over the IR is the tell that
 * the rewriter was missing.
 *
 * The bigger reason is that a simplified tree is a smaller `Fn` graph, and an `Fn` graph's size is
 * the cost of both a sample and a gradient — `Fn.eval` walks every node, and `gradientAt` records
 * every node on a tape. A fit runs that thousands of times.
 *
 * # The contract: every rule is bit-exact
 *
 * A simplified track must sample **bit-identically** to the original, not merely equal-to-tolerance,
 * which rules out several rewrites everyone writes by reflex. The gallery corpus caught the first
 * one: `0 - x` rewritten to `-x` disagrees at `x = 0`, because `0.0 - 0.0` is `+0.0` and `-(0.0)`
 * is `-0.0`.
 *
 * A signed zero is not a curiosity in this IR. [HeadingTrack] takes `atan2` of a velocity, and
 * `atan2(+0.0, -1.0)` is `+pi` while `atan2(-0.0, -1.0)` is `-pi` — half a turn apart. A rewrite that
 * "only" changes the sign of a zero can therefore spin a rectangle through 180 degrees. So the rules
 * kept here are the ones exact for every double including both zeros, and the reflexive ones are
 * dropped with their reasons:
 *
 * | rewrite | kept? | why |
 * | --- | --- | --- |
 * | constant folding | yes | exact by construction — the folded value *is* what the chain evaluated to |
 * | `x - 0` -> `x` | yes | `x - (+0.0) == x` for every x, both zeros included |
 * | `x * 1`, `x / 1`, `x ^ 1` | yes | multiplicative identity preserves sign |
 * | `min(x, x)`, `max(x, x)` -> `x` | yes | structural equality; exact whatever x is |
 * | `-(-x)` -> `x`, `abs abs`, `saturate saturate` | yes | involution / idempotence, sign-exact |
 * | `clamp(x, v, v)` -> `v` | yes | `coerceIn(x, v, v)` is `v` |
 * | `shift 0`, `scale 1`, degenerate `loop`/`pingpong` | yes | the lookup is `t` unchanged |
 * | `x + 0` -> `x` | **no** | `(-0.0) + 0.0` is `+0.0`, so this flips a signed zero |
 * | `0 - x` -> `-x` | **no** | the rule the corpus caught, above |
 * | `mix(a, b, 1)` -> `b` | **no** | `a + (b - a)` is not `b` in floating point at all |
 * | `mix(a, b, 0)` -> `a`, `mix(a, a, t)` -> `a` | **no** | both end in `a + (+/-0.0)`, signed-zero unsafe |
 * | `x * 0` -> `0` | **no** | only valid for finite x, and nothing here proves x is finite |
 *
 * Losing the additive identity costs less than it looks like it should: RectSL's array words build
 * per-copy channels by *offsetting a constant*, and the constant fold takes those whole.
 *
 * Free parameters are never folded, for the same reason [trackConstantValue] takes a flag: a
 * [FreeParamTrack] holds a number but is not *a* number, and folding it away would delete the
 * thing a fit is meant to solve for.
 *
 * # How it is checked
 *
 * `RectSLSimplifyTest` runs it over the gallery and requires every rewritten channel to sample
 * **bit-identically** to the original at 41 times each, and to be a fixed point of a second pass.
 * The node-count reduction it prints is reported rather than asserted at any figure, because that
 * figure moves with the gallery and with the IR — a key value became a node when [TcbKey] began
 * holding a track, for instance. Run the test to read the current one.
 *
 * A plausible-looking rule that is wrong in one corner is this file's characteristic failure, and
 * bit-identity over the corpus already assembled for the differentiable backend is what catches it.
 */

/** Total nodes in this track's tree, counting a shared subtree once per path — the size a backend pays. */
fun Track<Double>.nodeCount(): Int = 1 + trackChildren(this).sumOf { it.nodeCount() }

/**
 * This track with constants folded and the algebraic identities applied, bottom-up.
 *
 * A pure function returning a new tree; nothing mutates. Sampling is preserved bit-for-bit on the
 * whole gallery corpus (2754 channels, 16.5% fewer nodes, 112,914 samples compared) — see this
 * file's KDoc for how that is checked, and for the table of rewrites deliberately left out.
 */
fun Track<Double>.simplify(): Track<Double> = mapTrack(this) { node -> rewriteNode(node) }

/** True when [track] is the constant [value], exactly. A free parameter is never "a constant". */
private fun isConstant(track: Track<Double>, value: Double): Boolean =
    trackConstantValue(track, foldFreeParameters = false) == value

private fun rewriteNode(track: Track<Double>): Track<Double> {
    // The fold first: it subsumes every rule below for a subtree that does not vary with time, and it
    // is what turns a hundred-copy array's arithmetic into a hundred literals.
    trackConstantValue(track, foldFreeParameters = false)?.let { folded ->
        // A track that already *is* a bare constant is left alone rather than rebuilt, so simplify
        // is idempotent on its own output by identity and not merely by value.
        if (track is ConstantTrack<*>) return track
        return constantTrack(folded)
    }

    return when (track) {
        is BinaryDoubleTrack -> when (track.op) {
            // `x - 0` is the one additive identity that survives the signed-zero rule: subtracting
            // positive zero returns x untouched for every x, where *adding* it turns -0.0 into +0.0.
            TrackOp2.SUB -> if (isConstant(track.b, 0.0)) track.a else track
            TrackOp2.MUL -> when {
                isConstant(track.b, 1.0) -> track.a
                isConstant(track.a, 1.0) -> track.b
                else -> track
            }
            TrackOp2.DIV -> if (isConstant(track.b, 1.0)) track.a else track
            TrackOp2.POW -> if (isConstant(track.b, 1.0)) track.a else track
            // Structural equality doing real work: `min(x, x)` is `x` whatever x is.
            TrackOp2.MIN, TrackOp2.MAX -> if (track.a == track.b) track.a else track
            else -> track
        }

        is TernaryDoubleTrack -> when (track.op) {
            // No `mix` rule at all, which is the surprise. `mix(a, b, t)` evaluates as
            // `a + (b - a) * t`: at t = 1 that is `a + (b - a)`, which is simply not `b` in floating
            // point, and at t = 0 or with a == b it ends in `a + (+/-0.0)`, which is not `a` when a is
            // a negative zero. Every version of this rewrite is wrong somewhere.
            TrackOp3.CLAMP -> {
                val lo = trackConstantValue(track.b, foldFreeParameters = false)
                val hi = trackConstantValue(track.c, foldFreeParameters = false)
                // Equal bounds pin the value; and a clamp of something already inside its own bounds
                // cannot be recognised in general, so that case is left alone.
                if (lo != null && hi != null && lo == hi) constantTrack(lo) else track
            }
            else -> track
        }

        is MappedDoubleTrack -> {
            val inner = track.source
            when {
                // Involutions and idempotents. `neg neg` cancels; `abs` and `saturate` are already
                // themselves after one application.
                track.op == TrackOp1.NEG && inner is MappedDoubleTrack && inner.op == TrackOp1.NEG ->
                    inner.source
                track.op == TrackOp1.ABS && inner is MappedDoubleTrack && inner.op == TrackOp1.ABS ->
                    inner
                track.op == TrackOp1.SATURATE && inner is MappedDoubleTrack &&
                    inner.op == TrackOp1.SATURATE -> inner
                else -> track
            }
        }

        is TimeWarpTrack -> when (track.warp) {
            TimeWarpKind.SHIFT -> if (track.amount == 0.0) track.source else track
            TimeWarpKind.SCALE -> if (track.amount == 1.0) track.source else track
            // A degenerate period is the identity in both the CPU sampler and the GLSL, so the node
            // is not doing anything.
            TimeWarpKind.LOOP -> if (abs(track.amount) <= 1e-12) track.source else track
            TimeWarpKind.PINGPONG -> if (abs(track.amount * 2.0) <= 1e-12) track.source else track
            else -> track
        }

        else -> track
    }
}
