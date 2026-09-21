@file:afield.CompletionCategory("Patterns")

package afield.four.synth

import kotlin.math.roundToInt

/*
FIELD_TEST
id: synth.marf.readers
runner: jvm-main
entrypoint: afield.four.synth.MarfSmokeTest
tags: [smoke, synth, pattern, marf]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "MARF reader smoke tests passed"
*/
/**
 * The worked example for `Marf.kt`, and the check that it holds together: one bank, several
 * readers, and what each reader is doing printed as text.
 *
 * `main` prints five rows per reader — current address, current stage, stage phase, generated
 * value and stage-boundary pulses — for two readers over shared material, then swaps
 * `every(4) { rev() }` into one reader's address and prints them again. Content, timing, addressing
 * and interpolation are separate, so transforming one reader's address cannot disturb the other and
 * cannot alter the bank; the run checks both.
 *
 * The transform itself only bites on an address with steps inside the cycle: on a `<…>`
 * address, which holds one event per cycle, `rev()` has nothing to reverse, so the run repeats the
 * comparison over four stages a cycle and asserts that cycle 0 comes out reversed and cycle 1 does
 * not.
 *
 * #### The livecoding version
 *
 * The same patch in a box, driving sound. `marfBank` is the material; the three readers are three
 * journeys through it; nothing about the bank knows it is being read three times:
 *
 *     val b = marfBank(
 *         rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
 *         oscTo(0.7, 1.0, cycles = 3.0), curveTo(0.2, 0.25, amount = 4.0)
 *     )
 *
 *     val slowArc  = b.read(pat("<0 1 2 1 3 4>").nums(), name = "arc")
 *     val fastArc  = b.read(pat("<0 2 4 1 3>").nums().slow(3), speed = constant(4.0), name = "fast")
 *     val glide    = b.read(sine().range(0.0, 4.0), duration = constant(0.25), name = "glide")
 *
 *     // Continuous out: three readers of one bank as three parameters of one voice.
 *     val voice = pat("bd*8")
 *         .with(gain(fastArc.signal().range(0.25, 1.0)))
 *         .with(pan(glide.signal()))
 *         .with(speed(slowArc.signal().range(0.5, 2.0)))
 *
 *     voice.respond(cps = 1.0) { patVoice(it) }
 *
 *     // Discrete out, from the same temporal structure: one pulse per stage.
 *     slowArc.triggers().respond(cps = 1.0) { println("stage ${it.value}") }
 *
 * Then change one line and nothing else:
 *
 *     val slowArc = b.read(pat("<0 1 2 1 3 4>").nums().every(4) { it.rev() })   // periodic reversal
 *     val slowArc = b.read(struct(euclid(5, 8), pat("0 4 2 3").nums()))         // euclidean trajectory
 *     val slowArc = b.read(sine().range(0.0, 5.0))                              // glide, not step
 *
 * #### The graphics version
 *
 * Driving geometry shows the architecture more directly than driving notes does. Three readers
 * share one bank and become three coordinates, so the shapes are related without being
 * synchronised:
 *
 *     val x = b.read(pat("<0 1 2 3 4>").nums(), name = "x")
 *     val y = b.read(pat("<0 2 4 1 3>").nums().slow(3), name = "y")
 *     val r = b.read(sine().range(0.0, 4.0), duration = constant(0.5), name = "r")
 *
 *     frames {
 *         val t = musicClock().now
 *         val p = vec2(x.atSeconds(t).value, y.atSeconds(t).value) * 400.0
 *         ln.clear().circle(p, 10.0 + 60.0 * r.atSeconds(t).value)
 *     }
 *
 * A `frames { }` block needs a renderer to tick at all, so that last one belongs in a box with a
 * graphics parent rather than here, which stays headless.
 */

/**
 * Prints two readers of one shared bank as text, then asserts what transforming one reader's
 * address may and may not change.
 *
 * `main` is the `synth.marf.readers` FIELD_TEST: it writes nothing, and reports every failed
 * assertion before exiting non-zero.
 */
object MarfSmokeTest {

    private const val COLUMNS_PER_CYCLE = 8
    private const val CYCLES = 8
    private val LEVELS = "▁▂▃▄▅▆▇█"

    /** The shared material. Five stages, of unequal length and unequal shape. */
    private fun bank(): MarfBank<Double> = marfBank(
        rampTo(0.1, 1.0, label = "a"),
        rampTo(0.9, 0.5, label = "b"),
        holdAt(0.4, 2.0, label = "c"),
        oscTo(0.7, 1.0, cycles = 3.0, label = "d"),
        curveTo(0.2, 0.25, amount = 4.0, label = "e")
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val failures = mutableListOf<String>()

        println("── one bank, two readers ───────────────────────────────────────────────")
        val shared = bank()
        val a = shared.read(pat("<0 1 2 1 3 4>").nums(), name = "A")
        val b = shared.read(pat("<0 2 4 1 3>").nums().slow(3), name = "B")
        val plainA = render(a)
        val plainB = render(b)
        print(plainA)
        print(plainB)

        println()
        println("── the same bank, one reader's address reversed every fourth cycle ─────")
        val flipped = shared.read(pat("<0 1 2 1 3 4>").nums().every(4) { it.rev() }, name = "A'")
        val flippedB = shared.read(pat("<0 2 4 1 3>").nums().slow(3), name = "B")
        print(render(flipped))
        print(render(flippedB))

        // `<…>` holds one event per cycle, so rev() inside a cycle is a no-op — a Tidal fact, and
        // the reason the interesting reversal below uses an address with steps inside the cycle.
        if (render(flippedB) != plainB) failures += "transforming A's address changed B"
        if (shared.stages.map { it.label } != listOf("a", "b", "c", "d", "e")) {
            failures += "the bank was mutated by being read"
        }

        println()
        println("── four stages a cycle, so rev() has something to reverse ──────────────")
        val quarters = constant(0.25)
        val stepped = shared.read(pat("0 1 2 3").nums(), duration = quarters, name = "C")
        val reversed = shared.read(
            pat("0 1 2 3").nums().every(4) { it.rev() },
            duration = quarters,
            name = "C'"
        )
        print(render(stepped))
        print(render(reversed))
        if (render(stepped) == render(reversed)) failures += "every(4) { rev() } changed nothing"
        val firstCycle = (0..3).map { reversed.at(it * 0.25 + 0.05).address }
        if (firstCycle != listOf(3.0, 2.0, 1.0, 0.0)) failures += "cycle 0 not reversed: $firstCycle"
        val secondCycle = (4..7).map { reversed.at(it * 0.25 + 0.05).address }
        if (secondCycle != listOf(0.0, 1.0, 2.0, 3.0)) failures += "cycle 1 was touched: $secondCycle"

        println()
        println("── a continuous address glides through the bank ────────────────────────")
        // A periodic address is latched once per stage, so it is only ever a *sequence* of
        // lcm(address period, stage period) / stage period steps. Eight cycles of saw against
        // quarter-cycle stages is 32 of them, which reads as a glide; one cycle of sine against the
        // same stages is four, which reads as a four-step sequencer. See the note in `Marf.kt`.
        val glide = shared.read(saw().slow(8).range(0.0, 4.0), duration = quarters, name = "~")
        print(render(glide))
        val addresses = (0 until 32).map { glide.at(it * 0.25 + 0.05).address }
        if (addresses.count { it % 1.0 > 1e-4 } < 20) {
            failures += "a continuous address mostly landed on whole slots: $addresses"
        }
        if (addresses.zipWithNext().any { (p, q) -> q <= p }) {
            failures += "a rising address did not rise monotonically: $addresses"
        }

        val aliased = shared.read(sine().range(0.0, 4.0), duration = quarters, name = "aliased")
        val aliasedAddresses = (0 until 32).map { aliased.at(it * 0.25 + 0.05).address }
        // Count distinct addresses to 3dp, not as raw doubles: an aliased address repeats to
        // within a few parts in 10^4 rather than bit-exactly, because the probe instant is
        // rationally exact but `PatQ.toDouble()` rounds it at 12 places. Raw `distinct()` reports
        // 17 "different" addresses for a sequence that visibly cycles through four.
        fun coarse(xs: List<Double>) = xs.map { "%.3f".format(it) }.distinct().size
        println("  saw slowed 8x:  ${coarse(addresses)} distinct addresses over 32 stages")
        println("  plain sine:     ${coarse(aliasedAddresses)} distinct addresses over 32 stages")
        println("  plain sine, first eight: " + aliasedAddresses.take(8).joinToString(" ") { "%.4f".format(it) })
        if (coarse(aliasedAddresses) > 6) failures += "the one-cycle address did not alias: ${coarse(aliasedAddresses)}"
        if (coarse(addresses) < 24) failures += "the eight-cycle address did not glide: ${coarse(addresses)}"

        println()
        println("── three readers of one bank as three coordinates ──────────────────────")
        val x = shared.read(pat("<0 1 2 3 4>").nums(), name = "x")
        val y = shared.read(pat("<0 2 4 1 3>").nums().slow(3), name = "y")
        val r = shared.read(sine().slow(5).range(0.0, 4.0), duration = constant(0.5), name = "r")
        println("  cycle      x       y       r    stage x/y/r")
        for (step in 0 until 12) {
            val t = step * 1.1
            val sx = x.at(t)
            val sy = y.at(t)
            val sr = r.at(t)
            println(
                "  %5.2f  %6.3f  %6.3f  %6.3f    %s / %s / %s".format(
                    t, sx.value, sy.value, sr.value, sx.label, sy.label, sr.label
                )
            )
        }
        if (x.at(3.0).value == y.at(3.0).value && y.at(3.0).value == r.at(3.0).value) {
            failures += "three readers of one bank agreed exactly, which means they are not independent"
        }

        println()
        println("── pulses come from the same structure as the curve ────────────────────")
        val pulses = a.triggers().events(PatArc(PatQ.ZERO, PatQ.of(CYCLES.toLong())))
        val stages = a.stagesIn(PatArc(PatQ.ZERO, PatQ.of(CYCLES.toLong())))
        println("  ${stages.size} stages over $CYCLES cycles, ${pulses.size} pulses")
        if (pulses.size != stages.size) failures += "pulse count ${pulses.size} != stage count ${stages.size}"
        val rebuilt = shared.read(pat("<0 1 2 1 3 4>").nums(), name = "A-again")
        val gated = marf(
            value = pat("0 .3 .6 .9").nums(),
            duration = quarters,
            trigger = bools("1 0 0 1")
        )
        val gatedPulses = gated.triggers().events(PatArc(PatQ.ZERO, PatQ.ONE)).size
        println("  a `1 0 0 1` trigger over four stages a cycle gives $gatedPulses pulses")
        if (gatedPulses != 2) failures += "trigger gating gave $gatedPulses pulses, expected 2"
        if (rebuilt.at(1.5).value != a.at(1.5).value) failures += "two identically built readers disagreed"

        println()
        if (failures.isEmpty()) {
            println("MARF reader smoke tests passed")
        } else {
            failures.forEach { println("FAIL: $it") }
            error("MARF reader smoke tests failed: ${failures.size} failure(s)")
        }
    }

    /**
     * The five rows of the design note's first test, as text.
     *
     * `bound` marks a stage boundary, `addr` is the integer part of the current address (`~` when it
     * is between two slots), `phase` and `value` are sparklines, and `pulse` marks the stages that
     * emitted one. Every column is a real [MarfReader.at] call, so the picture cannot flatter the
     * reader it illustrates.
     */
    private fun render(reader: MarfReader<Double>): String {
        val columns = COLUMNS_PER_CYCLE * CYCLES
        val samples = (0 until columns).map { reader.at((it + 0.5) / COLUMNS_PER_CYCLE) }
        val boundaries = BooleanArray(columns)
        var previousIndex = -1L
        for ((column, sample) in samples.withIndex()) {
            if (sample.stageIndex != previousIndex) boundaries[column] = true
            previousIndex = sample.stageIndex
        }
        fun row(label: String, body: String) = "  %-6s %s\n".format(label, body)
        val name = reader.name
        return buildString {
            append("  reader $name\n")
            append(row("cycle", (0 until columns).joinToString("") {
                if (it % COLUMNS_PER_CYCLE == 0) (it / COLUMNS_PER_CYCLE).toString().last().toString() else "·"
            }))
            append(row("bound", (0 until columns).joinToString("") { if (boundaries[it]) "|" else " " }))
            append(row("addr", samples.joinToString("") {
                if (it.address % 1.0 > 1e-6) "~" else (it.address.toInt() % 10).toString()
            }))
            append(row("phase", samples.joinToString("") { level(it.stagePhase) }))
            append(row("value", samples.joinToString("") { level(it.value) }))
            append(row("pulse", (0 until columns).joinToString("") {
                if (boundaries[it] && samples[it].label.isNotEmpty()) "^" else " "
            }))
        }
    }

    private fun level(v: Double): String {
        val clamped = v.coerceIn(0.0, 1.0)
        return LEVELS[(clamped * (LEVELS.length - 1)).roundToInt()].toString()
    }
}
