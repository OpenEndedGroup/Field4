package afield.four.synth

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.dough-dsp.smoke
runner: jvm-main
entrypoint: afield.four.synth.DoughDspSmokeTest
tags: [smoke, synth, dsp, dough, audio]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "DoughDsp smoke tests passed"
*/
/**
 * Numeric checks on the dough DSP ports in DoughDsp.kt.
 *
 * These assert *behaviour* rather than exact sample values: that the
 * approximations are within tolerance of [kotlin.math], that the filter
 * actually attenuates above cutoff, that the phaser's notch notches, that
 * the reverb produces a decaying decorrelated stereo tail, and that
 * [CurvedADSR] retriggers without a discontinuity.
 */
object DoughDspSmokeTest {

    private const val SR = 48_000f
    private const val BLOCK = 128

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "DoughDsp smoke tests failed" }
    }

    fun runTests(): Boolean {
        val report = Reporter()

        println("DoughMath")
        checkMath(report)

        println("ChamberlinLowpass")
        checkChamberlin(report)

        println("Phaser")
        checkPhaser(report)

        println("PlateReverb")
        checkPlateReverb(report)

        println("CurvedADSR")
        checkCurvedAdsr(report)

        println("SoftDistort")
        checkSoftDistort(report)

        println("ZawOsc / PulzeOsc")
        checkNaiveOscillators(report)

        return if (report.failures.isEmpty()) {
            println("DoughDsp smoke tests passed")
            true
        } else {
            println("DoughDsp smoke tests FAILED: ${report.failures.joinToString(", ")}")
            false
        }
    }

    /** Collects pass/fail lines so every check runs even after one fails. */
    private class Reporter {
        val failures = mutableListOf<String>()

        fun check(name: String, condition: Boolean, detail: String = "") {
            if (condition) {
                println("  ok   $name${if (detail.isEmpty()) "" else "  ($detail)"}")
            } else {
                println("  FAIL $name${if (detail.isEmpty()) "" else "  ($detail)"}")
                failures += name
            }
        }
    }

    // ── DoughMath ────────────────────────────────────────────────────

    private fun checkMath(report: Reporter) {
        var maxLog2Err = 0.0
        var x = 0.01
        while (x < 20_000.0) {
            val approx = DoughMath.log2(x.toFloat()).toDouble()
            val exact = log2(x)
            maxLog2Err = max(maxLog2Err, abs(approx - exact))
            x *= 1.05
        }
        report.check("log2 within 1e-3 absolute over 0.01…20k", maxLog2Err < 1e-3, "max err %.2e".format(maxLog2Err))

        var maxExp2RelErr = 0.0
        var e = -30.0
        while (e < 30.0) {
            val approx = DoughMath.exp2(e.toFloat()).toDouble()
            val exact = Math.pow(2.0, e)
            maxExp2RelErr = max(maxExp2RelErr, abs(approx - exact) / exact)
            e += 0.031
        }
        report.check("exp2 within 1e-3 relative over -30…30", maxExp2RelErr < 1e-3, "max rel err %.2e".format(maxExp2RelErr))

        report.check("exp2 saturates instead of returning NaN", DoughMath.exp2(1e9f).isFinite() && DoughMath.exp2(-1e9f) == 0f)

        val pow = DoughMath.pow(2f, 10f)
        report.check("pow(2,10) ≈ 1024", abs(pow - 1024f) / 1024f < 2e-3, "got $pow")
        report.check("pow(x<0) is NaN", DoughMath.pow(-1f, 2f).isNaN())
        report.check("pow10(3) ≈ 1000", abs(DoughMath.pow10(3f) - 1000f) / 1000f < 2e-3, "got ${DoughMath.pow10(3f)}")
        report.check("powHalf(1) ≈ 0.5", abs(DoughMath.powHalf(1f) - 0.5f) < 1e-3, "got ${DoughMath.powHalf(1f)}")

        var maxParSinErr = 0.0
        var maxSinFastErr = 0.0
        var t = -12.0
        while (t < 12.0) {
            maxParSinErr = max(maxParSinErr, abs(DoughMath.parSin(t.toFloat()) - sin(t)))
            maxSinFastErr = max(maxSinFastErr, abs(DoughMath.sinFast(t.toFloat()) - sin(t)))
            t += 0.001
        }
        // The parabola is a crude fit — ~5.6e-2 peak error is inherent to it, not a port defect.
        report.check("parSin within 6e-2 absolute", maxParSinErr < 6e-2, "max err %.2e".format(maxParSinErr))
        report.check("sinFast within 1e-4 absolute", maxSinFastErr < 1e-4, "max err %.2e".format(maxSinFastErr))
        report.check(
            "parCos is parSin shifted a quarter turn",
            abs(DoughMath.parCos(0f) - 1f) < 6e-2,
            "parCos(0) = ${DoughMath.parCos(0f)}"
        )

        report.check("ftz flushes denormals", DoughMath.ftz(1e-20f) == 0f && DoughMath.ftz(0.5f) == 0.5f)

        report.check("lerpCurve clamps endpoints", DoughMath.lerpCurve(-1f, 2f, 7f, 2f) == 2f && DoughMath.lerpCurve(2f, 2f, 7f, 2f) == 7f)
        val linear = DoughMath.lerpCurve(0.5f, 0f, 1f, 0f)
        val easeIn = DoughMath.lerpCurve(0.5f, 0f, 1f, 2f)
        val easeOut = DoughMath.lerpCurve(0.5f, 0f, 1f, -2f)
        report.check(
            "lerpCurve ease-in < linear < ease-out at midpoint",
            easeIn < linear && linear < easeOut,
            "easeIn=%.3f linear=%.3f easeOut=%.3f".format(easeIn, linear, easeOut)
        )
    }

    // ── ChamberlinLowpass ────────────────────────────────────────────

    private fun checkChamberlin(report: Reporter) {
        // A 6 kHz tone should survive a 12 kHz cutoff and be crushed by a 300 Hz one.
        val passed = rms(renderMono(SineOsc(Param(6000f)).through(ChamberlinLowpass(Param(12000f), Param(0f))), 0.5f))
        val blocked = rms(renderMono(SineOsc(Param(6000f)).through(ChamberlinLowpass(Param(300f), Param(0f))), 0.5f))
        report.check(
            "6 kHz tone attenuated >20x more at 300 Hz cutoff than at 12 kHz",
            blocked < passed / 20f,
            "passed=%.4f blocked=%.4f".format(passed, blocked)
        )

        // Resonance should boost energy at the cutoff, not merely change tone.
        val dry = rms(renderMono(SineOsc(Param(1000f)).through(ChamberlinLowpass(Param(1000f), Param(0f))), 0.5f))
        val resonant = rms(renderMono(SineOsc(Param(1000f)).through(ChamberlinLowpass(Param(1000f), Param(0.9f))), 0.5f))
        report.check(
            "resonance boosts energy at cutoff",
            resonant > dry * 1.2f,
            "dry=%.4f resonant=%.4f".format(dry, resonant)
        )

        // Must stay stable at extreme settings — this is what the c<=1.14 clamp buys.
        val extreme = renderMono(
            SineOsc(Param(200f)).through(ChamberlinLowpass(Param(20_000f), Param(1f))),
            1f
        )
        report.check(
            "stable at cutoff 20 kHz / resonance 1.0",
            extreme.all { it.isFinite() } && extreme.max() < 100f,
            "peak=%.3f".format(extreme.maxOf { abs(it) })
        )
    }

    // ── Phaser ───────────────────────────────────────────────────────

    private fun checkPhaser(report: Reporter) {
        // rate = 0 parks the LFO at phase 0, so notch 1 sits exactly on `center`.
        val notchFreq = 1000f
        fun run(inputFreq: Float): Float = rms(
            renderMono(
                SineOsc(Param(inputFreq)).through(
                    Phaser(
                        rate = Param(0f), depth = Param(0f),
                        center = Param(notchFreq), sweep = Param(0f), mix = Param(1f)
                    )
                ),
                0.5f
            )
        )

        val atNotch = run(notchFreq)
        val wellBelow = run(notchFreq / 8f)
        report.check(
            "tone at notch centre attenuated >5x vs three octaves below",
            atNotch < wellBelow / 5f,
            "atNotch=%.5f below=%.5f".format(atNotch, wellBelow)
        )

        // With the LFO running the output must move but stay finite and bounded.
        val swept = renderMono(
            SawOsc(Param(220f)).through(
                Phaser(rate = Param(2f), depth = Param(0.8f), center = Param(800f), sweep = Param(900f))
            ),
            1f
        )
        report.check("swept output finite and bounded", swept.all { it.isFinite() && abs(it) < 8f },
            "peak=%.3f".format(swept.maxOf { abs(it) }))

        val dryRms = rms(renderMono(SawOsc(Param(220f)), 1f))
        report.check("phaser changes the signal", abs(rms(swept) - dryRms) > 1e-4f,
            "dry=%.4f wet=%.4f".format(dryRms, rms(swept)))
    }

    // ── PlateReverb ──────────────────────────────────────────────────

    private fun checkPlateReverb(report: Reporter) {
        fun tail(decay: Float, mix: Float = 1f): Pair<FloatArray, FloatArray> =
            renderStereo(
                PlateReverb(
                    source = OneShotImpulse(),
                    decay = Param(decay),
                    mix = Param(mix)
                ),
                2f
            )

        val (l, r) = tail(0.85f)
        report.check("tail is finite", l.all { it.isFinite() } && r.all { it.isFinite() })
        report.check("tail is bounded", l.maxOf { abs(it) } < 10f, "peak=%.4f".format(l.maxOf { abs(it) }))

        val late = window(l, 0.5f, 1.0f)
        report.check("impulse produces a non-trivial late tail at 0.5–1.0 s", rms(late) > 1e-5f,
            "rms=%.3e".format(rms(late)))

        // Stereo must be decorrelated, not a copy: the tap sets differ per side.
        val correlation = correlation(window(l, 0.1f, 1.0f), window(r, 0.1f, 1.0f))
        report.check("left and right are decorrelated", abs(correlation) < 0.5f, "corr=%.3f".format(correlation))

        // Energy must decay over time and grow with the decay parameter.
        val early = rms(window(l, 0.05f, 0.25f))
        val later = rms(window(l, 1.0f, 1.5f))
        report.check("tail decays", later < early, "early=%.3e later=%.3e".format(early, later))

        val shortTail = rms(window(tail(0.3f).first, 0.5f, 1.0f))
        val longTail = rms(window(tail(0.9f).first, 0.5f, 1.0f))
        report.check("higher decay = longer tail", longTail > shortTail * 2f,
            "decay0.3=%.3e decay0.9=%.3e".format(shortTail, longTail))

        // mix = 0 must be exactly the dry impulse.
        val (dryL, dryR) = tail(0.85f, mix = 0f)
        report.check("mix=0 passes dry only", dryL[0] == 1f && dryL.drop(1).all { it == 0f } && dryR[0] == 1f)

        // Unscaled mode must also run clean (dough-identical tap lengths).
        val (unscaledL, _) = renderStereo(
            PlateReverb(source = OneShotImpulse(), mix = Param(1f), scaleToSampleRate = false),
            1f
        )
        report.check("scaleToSampleRate=false also produces a finite tail",
            unscaledL.all { it.isFinite() } && rms(window(unscaledL, 0.3f, 0.9f)) > 1e-6f)
    }

    // ── CurvedADSR ───────────────────────────────────────────────────

    private fun checkCurvedAdsr(report: Reporter) {
        val attack = 0.02f
        val decay = 0.05f
        val sustain = 0.6f
        val release = 0.1f

        val gate = Gate()
        val env = CurvedADSR(
            gate = gate,
            attack = Param(attack), decay = Param(decay),
            sustain = Param(sustain), release = Param(release)
        )

        // Gate on for 0.15 s, off for 0.03 s (mid-release), on again, then off.
        val ctx = SynthContext(SR, BLOCK)
        val buf = FloatArray(BLOCK)
        val trace = ArrayList<Float>()
        val blocksPerSecond = SR / BLOCK
        fun blocks(seconds: Float) = (seconds * blocksPerSecond).toInt()

        val schedule = listOf(
            true to blocks(0.15f),
            false to blocks(0.03f),
            true to blocks(0.15f),
            false to blocks(0.3f)
        )
        val gateDropBlock = schedule[0].second
        val retriggerBlock = schedule[0].second + schedule[1].second
        for ((open, count) in schedule) {
            if (open) gate.open() else gate.close()
            repeat(count) {
                env.render(buf, BLOCK, ctx)
                ctx.advanceCycle()
                for (i in 0 until BLOCK) trace += buf[i]
            }
        }

        val samples = trace.toFloatArray()
        report.check("envelope finite and in [0,1]", samples.all { it.isFinite() && it >= -1e-6f && it <= 1f + 1e-6f },
            "peak=%.4f".format(samples.maxOf { it }))

        val peak = samples.max()
        report.check("reaches full level after attack", peak > 0.99f, "peak=%.4f".format(peak))

        // Sustain plateau: sample just before the gate drops.
        val sustainLevel = samples[gateDropBlock * BLOCK - 1]
        report.check("holds at the sustain level", abs(sustainLevel - sustain) < 0.01f, "got %.4f".format(sustainLevel))

        report.check("returns to zero after release", samples.last() == 0f, "last=%.4f".format(samples.last()))

        // The distinguishing feature: no discontinuity anywhere, including the
        // retrigger part-way through the release segment.
        var maxJump = 0f
        var maxJumpAt = 0
        for (i in 1 until samples.size) {
            val jump = abs(samples[i] - samples[i - 1])
            if (jump > maxJump) { maxJump = jump; maxJumpAt = i }
        }
        report.check(
            "retriggers without a discontinuity",
            maxJump < 0.01f,
            "max jump %.5f at sample %d".format(maxJump, maxJumpAt)
        )

        // For contrast: the same gating through plain ADSR restarts from zero.
        val plainGate = Gate()
        val plain = ADSR(
            gate = plainGate,
            attack = Param(attack), decay = Param(decay),
            sustain = Param(sustain), release = Param(release)
        )
        val plainCtx = SynthContext(SR, BLOCK)
        val plainTrace = ArrayList<Float>()
        for ((open, count) in schedule) {
            if (open) plainGate.open() else plainGate.close()
            repeat(count) {
                plain.render(buf, BLOCK, plainCtx)
                plainCtx.advanceCycle()
                for (i in 0 until BLOCK) plainTrace += buf[i]
            }
        }
        val plainSamples = plainTrace.toFloatArray()
        var plainMaxJump = 0f
        for (i in 1 until plainSamples.size) {
            plainMaxJump = max(plainMaxJump, abs(plainSamples[i] - plainSamples[i - 1]))
        }
        println("       (for contrast, ADSR's max jump under the same gating: %.4f)".format(plainMaxJump))
    }

    // ── SoftDistort ──────────────────────────────────────────────────

    private fun checkSoftDistort(report: Reporter) {
        val clean = renderMono(SineOsc(Param(220f)).through(SoftDistort(Param(0f), Param(1f))), 0.2f)
        val reference = renderMono(SineOsc(Param(220f)), 0.2f)
        var maxDiff = 0f
        for (i in clean.indices) maxDiff = max(maxDiff, abs(clean[i] - reference[i]))
        report.check("amount=0 is near-identity", maxDiff < 2e-3f, "max diff %.2e".format(maxDiff))

        // Hard-driven output must stay bounded by (1+k)/k.
        val driven = renderMono((SineOsc(Param(220f)) * 20f).through(SoftDistort(Param(4f), Param(1f))), 0.2f)
        val k = DoughMath.expm1(4f)
        val bound = (1f + k) / k
        report.check(
            "bounded by (1+k)/k under heavy drive",
            driven.all { it.isFinite() && abs(it) <= bound + 1e-3f },
            "peak=%.4f bound=%.4f".format(driven.maxOf { abs(it) }, bound)
        )

        // Monotonic transfer curve.
        val shaper = SoftDistort(Param(3f), Param(1f))
        val ramp = Ramp()
        val shaped = renderMono(ramp.through(shaper), 0.05f)
        var monotonic = true
        for (i in 1 until shaped.size) if (shaped[i] < shaped[i - 1] - 1e-6f) monotonic = false
        report.check("transfer curve is monotonic", monotonic)

        val gained = renderMono(SineOsc(Param(220f)).through(SoftDistort(Param(1f), Param(0.5f))), 0.2f)
        val ungained = renderMono(SineOsc(Param(220f)).through(SoftDistort(Param(1f), Param(1f))), 0.2f)
        report.check("postGain scales the output", abs(rms(gained) * 2f - rms(ungained)) < 1e-4f,
            "gained=%.4f ungained=%.4f".format(rms(gained), rms(ungained)))
    }

    // ── ZawOsc / PulzeOsc ────────────────────────────────────────────

    private fun checkNaiveOscillators(report: Reporter) {
        val zaw = renderMono(ZawOsc(Param(1000f)), 0.2f)
        report.check("zaw spans [-1, 1]", zaw.min() < -0.99f && zaw.max() > 0.99f && zaw.all { it >= -1f && it <= 1f },
            "min=%.3f max=%.3f".format(zaw.min(), zaw.max()))

        // 1 kHz at 48 kHz = 48 samples per period, rising monotonically within one.
        var rising = true
        for (i in 1 until 47) if (zaw[i] <= zaw[i - 1]) rising = false
        report.check("zaw ramps monotonically within a period", rising)
        // 1 kHz over 0.2 s = 200 periods. The period is 48.0000008 samples rather than
        // exactly 48 (inverseSampleRate is a Float), so count wraps instead of indexing one.
        var wraps = 0
        for (i in 1 until zaw.size) if (zaw[i] - zaw[i - 1] < -1f) wraps++
        report.check("zaw completes 200 periods in 0.2 s at 1 kHz", abs(wraps - 200) <= 1, "wraps=$wraps")

        for (duty in listOf(0.25f, 0.5f, 0.75f)) {
            val pulze = renderMono(PulzeOsc(Param(1000f), Param(duty)), 0.5f)
            val positiveFraction = pulze.count { it > 0f }.toFloat() / pulze.size
            report.check(
                "pulze duty $duty matches the positive fraction",
                abs(positiveFraction - duty) < 0.02f,
                "got %.4f".format(positiveFraction)
            )
            report.check("pulze is two-valued", pulze.all { it == 1f || it == -1f }, "")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** A single 1.0 sample followed by silence. */
    private class OneShotImpulse : Signal() {
        private var fired = false
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            java.util.Arrays.fill(output, 0, numFrames, 0f)
            if (!fired) { output[0] = 1f; fired = true }
        }
    }

    /** A slow rising ramp from -2 to +2, for probing transfer curves. */
    private class Ramp : Signal() {
        private var v = -2.0
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            for (i in 0 until numFrames) {
                output[i] = v.toFloat()
                v += 4.0 / (SR * 0.05)
            }
        }
    }

    private fun renderMono(signal: Signal, seconds: Float): FloatArray {
        val ctx = SynthContext(SR, BLOCK)
        val total = (seconds * SR).toInt()
        val out = FloatArray(total)
        val buf = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            signal.render(buf, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(buf, 0, out, written, n)
            written += n
        }
        return out
    }

    private fun renderStereo(node: StereoRenderable, seconds: Float): Pair<FloatArray, FloatArray> {
        val ctx = SynthContext(SR, BLOCK)
        val total = (seconds * SR).toInt()
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val bufL = FloatArray(BLOCK)
        val bufR = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            node.render(bufL, bufR, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(bufL, 0, outL, written, n)
            System.arraycopy(bufR, 0, outR, written, n)
            written += n
        }
        return outL to outR
    }

    private fun window(samples: FloatArray, fromSeconds: Float, toSeconds: Float): FloatArray {
        val from = (fromSeconds * SR).toInt().coerceIn(0, samples.size)
        val to = (toSeconds * SR).toInt().coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var sumAB = 0.0; var sumAA = 0.0; var sumBB = 0.0
        for (i in 0 until n) {
            sumAB += a[i].toDouble() * b[i]
            sumAA += a[i].toDouble() * a[i]
            sumBB += b[i].toDouble() * b[i]
        }
        if (sumAA == 0.0 || sumBB == 0.0) return 0f
        return (sumAB / sqrt(sumAA * sumBB)).toFloat()
    }
}
