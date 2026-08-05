package afield.rectsl

import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.four.synth.degradeBy
import afield.four.synth.delay
import afield.four.synth.fast
import afield.four.synth.iter
import afield.four.synth.iterBack
import afield.four.synth.linger
import afield.four.synth.palindrome
import afield.four.synth.ply
import afield.four.synth.press
import afield.four.synth.pressBy
import afield.four.synth.rev
import afield.four.synth.rot
import afield.four.synth.shuffle
import afield.four.synth.slow
import afield.four.synth.stack
import afield.four.synth.switchEvery
import afield.four.synth.stutter
import afield.four.synth.swing
import afield.four.synth.swingBy
import afield.four.synth.unDegradeBy
import kotlin.math.floor

/**
 * The variation and layout halves of RectSL's musical layer. See [installPatternWords] in
 * RectSLPattern.kt for the overview; this file holds §4 (variation) and §5 (layout).
 */

// --- Tracks that vary by cycle -------------------------------------------------------------

/** Which cycle scene-time `t` falls in, given the scene clock. */
private fun cycleOf(t: Double, cps: Double): Long = floor(t * cps).toLong()

/**
 * Choose between two versions of a track depending on the cycle — the mechanism behind
 * `->every`. Tidal's `every k` fires on cycles `0, k, 2k, …`, and so does this.
 */
data class CycleSwitchTrack(
    val before: Track<Double>,
    val after: Track<Double>,
    val k: Int,
    val cps: Double,
    val phase: Long = 0L
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double =
        if (Math.floorMod(cycleOf(t, cps) - phase, k.toLong()) == 0L) after.sample(t) else before.sample(t)
}

/** 1.0 on the cycles `->every` fires on, 0.0 elsewhere — gates whatever the body *created*. */
data class CycleGateTrack(
    val k: Int,
    val cps: Double,
    val phase: Long = 0L
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double =
        if (Math.floorMod(cycleOf(t, cps) - phase, k.toLong()) == 0L) 1.0 else 0.0
}

/**
 * Deterministic per-object, per-cycle dropout — the visual `degradeBy`. The same scene always
 * drops the same copies on the same cycles, which is the point: a groove you cannot reproduce
 * is not a groove. The hash is a plain integer mix rather than [afield.four.synth.rand] because
 * it is keyed on *object ordinal* and cycle, not on a rational time instant.
 */
data class DegradeGateTrack(
    val ordinal: Int,
    val probability: Double,
    val cps: Double,
    val salt: Int = 0
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double

    override fun sample(t: Double): Double {
        var h = (ordinal * 0x9E3779B1L) xor (cycleOf(t, cps) * 0x85EBCA77L) xor (salt * 0xC2B2AE3DL)
        h = h xor (h ushr 33); h *= -0xAE502812AA7333L
        h = h xor (h ushr 29); h *= -0x3B314601E57A13ADL
        h = h xor (h ushr 32)
        val u = ((h ushr 11).toDouble() / (1L shl 53).toDouble())
        return if (u < probability) 0.0 else 1.0
    }
}

// --- §4a Pattern transforms ------------------------------------------------------------------

/**
 * A time transform that is generic in the pattern's value type, so one word body can serve both
 * [VPat] (rhythms) and [VSig] (signals). Kotlin has no rank-2 function types, so the generic
 * method needs an interface rather than a lambda.
 */
private interface PatTimeOp {
    fun <A> apply(pattern: PatPattern<A>): PatPattern<A>
}

/** Apply a value-type-agnostic transform to whichever kind of pattern is on top of the stack. */
private fun RectLangVm.timeOp(word: String, op: PatTimeOp) {
    when (val value = pop()) {
        is VPat -> push(VPat(op.apply(value.pattern)))
        is VSig -> push(VSig(op.apply(value.signal)))
        is VStr -> push(VPat(op.apply(afield.four.synth.pat(value.x))))
        else -> throw RectLangException("$word expected a pattern, found ${value::class.simpleName}")
    }
}

private fun opOf(f: PatTimeOpBody): PatTimeOp = object : PatTimeOp {
    override fun <A> apply(pattern: PatPattern<A>): PatPattern<A> = f.run(pattern)
}

private interface PatTimeOpBody {
    fun <A> run(pattern: PatPattern<A>): PatPattern<A>
}

internal fun RectLangVm.installVariationWordsImpl() {
    installPatternTransforms()
    installSelectionVariations()
}

private fun RectLangVm.installPatternTransforms() {
    word("fast") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("fast", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.fast(n)
        }))
    }
    word("slow") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("slow", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.slow(n)
        }))
    }
    word("rev") { vm ->
        vm.timeOp("rev", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.rev()
        }))
    }
    word("palindrome") { vm ->
        vm.timeOp("palindrome", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.palindrome()
        }))
    }
    word("iter") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("iter", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.iter(n)
        }))
    }
    word("iter-back") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("iter-back", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.iterBack(n)
        }))
    }
    word("ply") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("ply", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.ply(n)
        }))
    }
    // `pattern n gapDen -- pattern`: n strikes per event, spaced a 1/gapDen of a cycle apart.
    // The gap is a denominator rather than a number of seconds so that it stays exact rational
    // time and survives `fast`/`slow` as a musical subdivision rather than a wall-clock offset.
    word("stut") { vm ->
        val gapDen = vm.popNum().toInt().coerceAtLeast(1)
        val n = vm.popNum().toInt()
        vm.timeOp("stut", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.stutter(n, PatQ.of(1, gapDen))
        }))
    }
    word("linger") { vm ->
        val den = vm.popNum().toInt().coerceAtLeast(1)
        val num = vm.popNum().toInt()
        vm.timeOp("linger", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.linger(num, den)
        }))
    }
    word("pat-rot") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("pat-rot", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.rot(n)
        }))
    }
    word("shuffle") { vm ->
        val n = vm.popNum().toInt()
        vm.timeOp("shuffle", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.shuffle(n)
        }))
    }
    word("degrade-by") { vm ->
        val p = vm.popNum()
        vm.timeOp("degrade-by", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.degradeBy(p)
        }))
    }
    word("swing-by") { vm ->
        val n = vm.popNum().toInt().coerceAtLeast(1)
        val den = vm.popNum().toInt().coerceAtLeast(1)
        val num = vm.popNum().toInt()
        vm.timeOp("swing-by", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.swingBy(PatQ.of(num, den), n)
        }))
    }
    word("swing") { vm ->
        val n = vm.popNum().toInt().coerceAtLeast(1)
        vm.timeOp("swing", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.swing(n)
        }))
    }
    word("press") { vm ->
        vm.timeOp("press", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.press()
        }))
    }
    word("press-by") { vm ->
        val den = vm.popNum().toInt().coerceAtLeast(1)
        val num = vm.popNum().toInt()
        vm.timeOp("press-by", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.pressBy(PatQ.of(num, den))
        }))
    }
    word("pat-delay") { vm ->
        val den = vm.popNum().toInt().coerceAtLeast(1)
        val num = vm.popNum().toInt()
        vm.timeOp("pat-delay", opOf(object : PatTimeOpBody {
            override fun <A> run(pattern: PatPattern<A>) = pattern.delay(PatQ.of(num, den))
        }))
    }

    // --- Quote-taking combinators.
    //
    // Every one of these composes its quote *eagerly*: the quote runs once, here, against a
    // pattern this code hands it, and the result is a plain pattern with no VM in it. That is a
    // hard requirement, not a shortcut — a `PatTrack` is sampled during rendering, long after
    // `compile()` returns, and a pattern holding a closure over the VM would re-enter a
    // single-threaded interpreter from the render loop.
    //
    // It also decides which combinators exist. Eager composition is exactly correct when the
    // quote's argument is a pattern this code can name (`p`, `p.delay(…)`, `p.slow(n)`), so
    // `every`, `off`, `superimpose`, `inside` and `outside` are all built here out of public
    // primitives rather than delegated. Tidal's `chunk` is deliberately absent: it applies its
    // transform to a different slice of the bar on every cycle, so there is no one pattern to
    // hand the quote. `iter` gives the walking-through-the-bar feel without needing a quote.
    word("every") { vm ->
        val quote = vm.popQuote()
        val k = vm.popNum().toInt().coerceAtLeast(1)
        val source = vm.popPat("every")
        // `switchEvery` is `every(k) { alt }` — it walks the query cycle by cycle itself, so a
        // wide query still switches at each bar line rather than at the query's own start.
        vm.push(VPat(source.switchEvery(k, vm.applyQuoteToPattern(quote, source, "every"))))
    }
    word("off") { vm ->
        val quote = vm.popQuote()
        val den = vm.popNum().toInt().coerceAtLeast(1)
        val num = vm.popNum().toInt()
        val source = vm.popPat("off")
        val shifted = vm.applyQuoteToPattern(quote, source.delay(PatQ.of(num, den)), "off")
        vm.push(VPat(stack(source, shifted)))
    }
    word("superimpose") { vm ->
        val quote = vm.popQuote()
        val source = vm.popPat("superimpose")
        vm.push(VPat(stack(source, vm.applyQuoteToPattern(quote, source, "superimpose"))))
    }
    word("inside") { vm ->
        val quote = vm.popQuote()
        val n = vm.popNum().toInt().coerceAtLeast(1)
        val source = vm.popPat("inside")
        vm.push(VPat(vm.applyQuoteToPattern(quote, source.slow(n), "inside").fast(n)))
    }
    word("outside") { vm ->
        val quote = vm.popQuote()
        val n = vm.popNum().toInt().coerceAtLeast(1)
        val source = vm.popPat("outside")
        vm.push(VPat(vm.applyQuoteToPattern(quote, source.fast(n), "outside").slow(n)))
    }
    word("sometimes-by") { vm ->
        val quote = vm.popQuote()
        val p = vm.popNum()
        val source = vm.popPat("sometimes-by")
        val touched = vm.applyQuoteToPattern(quote, source.unDegradeBy(p), "sometimes-by")
        // degradeBy and unDegradeBy share one hash, so the two halves partition the events
        // exactly — no event is both transformed and left alone, and none is dropped.
        vm.push(VPat(stack(source.degradeBy(p), touched)))
    }

    word("stack-pat") { vm ->
        val b = vm.popPat("stack-pat")
        val a = vm.popPat("stack-pat")
        vm.push(VPat(stack(a, b)))
    }
    word("cat-pat") { vm ->
        val b = vm.popPat("cat-pat")
        val a = vm.popPat("cat-pat")
        vm.push(VPat(afield.four.synth.cat(a, b)))
    }
}

/** Run a quote with `pattern` on the stack and take the pattern it leaves. */
private fun RectLangVm.applyQuoteToPattern(
    quote: VQuote,
    pattern: PatPattern<String>,
    word: String
): PatPattern<String> {
    push(VPat(pattern))
    evalBlock(quote.tokens)
    return popPat(word)
}

// --- §4b Selection variations -----------------------------------------------------------------

private fun RectLangVm.installSelectionVariations() {
    /**
     * `selection k quote -- selection` — run the quote on the selection, but let it take effect
     * only on cycles `0, k, 2k, …`. Both versions of every track are kept and chosen between per
     * sample, so *any* mutator becomes conditional; anything the quote creates is gated to the
     * same cycles.
     */
    mutator("every") { vm ->
        val quote = vm.popQuote()
        val k = vm.popNum().toInt().coerceAtLeast(1)
        val selection = vm.popSelectionOrActive("->every")
        val b = vm.builder()
        val cps = b.cps
        selection.refs.forEach { ref ->
            val spawned = b.captureNewNodes {
                b.combineScalarTracks(
                    ref,
                    body = { vm.withSelected(ref) { vm.evalBlock(quote.tokens) } },
                    combine = { before, after -> CycleSwitchTrack(before, after, k, cps) }
                )
            }
            spawned.forEach { node -> b.withActive(node) { b.multiplyAlpha(CycleGateTrack(k, cps)) } }
        }
        vm.push(selection)
    }

    /**
     * `selection seconds quote -- selection` — superimpose a delayed, transformed echo. The
     * visual `off`. Delay is in seconds so it composes with the rest of RectSL; write
     * `1 8 / cycles` or `1 beats` for a musical one.
     */
    mutator("off") { vm ->
        val quote = vm.popQuote()
        val dt = vm.popNum()
        val selection = vm.popSelectionOrActive("->off")
        val b = vm.builder()
        val copies = (b.cloneSelection(selection.refs, deep = true) as VSelection).refs
        copies.forEach { ref -> b.mapScalarTracks(ref) { track -> TimeWarpTrack(track, TimeWarpKind.SHIFT, dt) } }
        copies.forEach { ref -> vm.withSelected(ref) { vm.evalBlock(quote.tokens) } }
        vm.push(VSelection(selection.refs + copies))
    }

    /**
     * `selection quote -- selection` — the visual `jux`: a mirrored twin, transformed by the
     * quote. Tidal's `jux` sends the transformed copy to the other stereo channel; left/right is
     * already the spatial reading of pan, so mirroring about the wall's centre is the same idea
     * in the same place.
     */
    mutator("jux") { vm ->
        val quote = vm.popQuote()
        val selection = vm.popSelectionOrActive("->jux")
        val b = vm.builder()
        val copies = (b.cloneSelection(selection.refs, deep = true) as VSelection).refs
        copies.forEach { ref -> b.withActive(ref) { b.mirrorX(0.0) } }
        copies.forEach { ref -> vm.withSelected(ref) { vm.evalBlock(quote.tokens) } }
        vm.push(VSelection(selection.refs + copies))
    }

    /** `selection probability -- selection` — deterministic per-object, per-cycle dropout. */
    mutator("degrade") { vm ->
        val p = vm.popNum()
        val selection = vm.popSelectionOrActive("->degrade")
        val b = vm.builder()
        val cps = b.cps
        selection.refs.forEachIndexed { ordinal, ref ->
            b.withActive(ref) { b.multiplyAlpha(DegradeGateTrack(ordinal, p, cps)) }
        }
        vm.push(selection)
    }

    /**
     * `selection stepSeconds -- selection` — `->stagger-time` in musical units is just
     * `->stagger-time` with a converted number, but a *swung* stagger is not expressible that
     * way, so this is the one that earns its place: copy `k` is delayed by `k` steps, and every
     * odd copy is pushed a further `amount` of a step late.
     */
    mutator("stagger-swing") { vm ->
        val amount = vm.popNum()
        val step = vm.popNum()
        val selection = vm.popSelectionOrActive("->stagger-swing")
        val b = vm.builder()
        selection.refs.forEachIndexed { ordinal, ref ->
            val delayBy = step * ordinal + if (ordinal % 2 == 1) step * amount else 0.0
            b.mapScalarTracks(ref) { track -> TimeWarpTrack(track, TimeWarpKind.SHIFT, delayBy) }
        }
        vm.push(selection)
    }
}

// --- §5 Layout: rhythm in space ------------------------------------------------------------------

internal fun RectLangVm.installLayoutWordsImpl() {
    /**
     * `[template] pattern quote -- selection` — lay one copy per event **across the wall**
     * instead of across time: a rhythm read as a ruler.
     *
     * This is the Fischinger closure, and it is the reason mini-notation is worth having on the
     * geometry side at all. `"bd [sd sd] hh*4"` is a nested subdivision of the unit interval;
     * so is a row of rectangles. The same string can drive when things happen and where they
     * sit, which is what "visual music" means literally rather than metaphorically.
     *
     * One cycle spans the wall. Each copy is centred on its event and given that event's
     * duration as its width, so an `@`-elongated step is a wider rect and a `[a b]` subdivision
     * is two narrow ones. The body sees the same `t`/`dur`/`step`/`atom` context a `pat` body
     * does, so a strip can still vary per copy.
     */
    word("strip") { vm -> vm.stripLayout() }

    /**
     * `selection pulses steps rotation -- selection` — keep the Euclidean onsets of a selection
     * and hide the rest. `12 array  5 12 0 ->euclid` turns twelve evenly spaced copies into a
     * five-against-twelve figure without rebuilding the array.
     *
     * The off-steps are hidden rather than deleted: they may already be referenced by a
     * selection the caller is holding, and RectSL has no node removal.
     */
    mutator("euclid") { vm ->
        val rotation = vm.popNum().toInt()
        val steps = vm.popNum().toInt().coerceAtLeast(1)
        val pulses = vm.popNum().toInt()
        val selection = vm.popSelectionOrActive("->euclid")
        val hits = afield.four.synth.bjorklund(pulses.coerceIn(0, steps), steps)
        val b = vm.builder()
        val kept = mutableListOf<NodeRef>()
        selection.refs.forEachIndexed { ordinal, ref ->
            if (hits.getOrElse(ordinal % hits.size) { false }) {
                kept += ref
            } else {
                b.withActive(ref) { b.multiplyAlpha(constantTrack(0.0)) }
            }
        }
        vm.push(VSelection(kept))
    }
}

private fun RectLangVm.stripLayout() {
    val mods = popQuote()
    val pattern = popPat("strip")
    val b = builder()
    val cps = b.cps.coerceAtLeast(1e-9)
    val wallWidth = b.wall.width

    // One cycle across the wall — a strip is a picture of a bar, not of the whole scene.
    val events = pattern.events(afield.four.synth.PatArc(PatQ.ZERO, PatQ.ONE))
        .filter { it.hasOnset }
        .sortedBy { it.arc.start.toDouble() }
    if (events.isEmpty()) {
        push(VSelection(emptyList()))
        return
    }

    val template = popTemplateRef("strip")
    val refs = b.instantiateArray(template, events.size)
    refs.forEachIndexed { ordinal, ref ->
        val event = events[ordinal]
        val start = event.arc.start.toDouble()
        val end = event.extent.end.toDouble().coerceAtMost(1.0)
        val width = (end - start).coerceAtLeast(1e-6)
        val centre = (start + width / 2.0 - 0.5) * wallWidth
        val context = PatEventContext(
            t = start / cps,
            dur = width / cps,
            cycle = 0,
            step = ordinal,
            atom = event.value,
            params = event.annotations
        )
        withSelected(ref) {
            b.setX(constantTrack(centre))
            b.setW(constantTrack(width * wallWidth))
            withPatLoop(ordinal, events.size, context) { evalBlock(mods.tokens) }
        }
    }
    push(VSelection(refs))
}
