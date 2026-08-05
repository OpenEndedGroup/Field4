package afield.examples.rectsl

import afield.rectsl.RectAnimationOracle
import afield.rectsl.RectLangVm
import afield.rectsl.SampledRect
import afield.rectsl.SceneProgram
import kotlin.math.abs

/*
FIELD_TEST
id: rectsl.music.workout
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLMusicWorkout
tags: [smoke, examples, rectsl, pattern, animation]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "rectsl music workout passed"
*/

/**
 * The musical layer's contract, asserted rather than asserted-to-be-true.
 *
 * Every test here checks a *number* — an onset in seconds, a count of visible rects on a given
 * cycle, an exact x — not that a scene compiled. "It compiled" and "the rhythm is right" are
 * different claims and only the second one is the point; the first is already covered by the
 * language workout.
 */
object RectSLMusicWorkout {
    private const val EPS = 1e-6

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "rectsl music workout failed" }
    }

    fun runTests(): Boolean {
        val tests = listOf(
            "clock converts and commutes" to ::clockConvertsAndCommutes,
            "pat gives one copy per event" to ::patGivesOneCopyPerEvent,
            "pat life windows match the rhythm" to ::patLifeWindowsMatchTheRhythm,
            "pat carries mini-notation whole" to ::patCarriesMiniNotationWhole,
            "pat exposes the event context" to ::patExposesEventContext,
            "pat bodies author in local time" to ::patBodiesAuthorInLocalTime,
            "a bar-crossing note is struck once" to ::barCrossingNoteStruckOnce,
            "signals drive tracks" to ::signalsDriveTracks,
            "a rhythm read as a track is stepped" to ::rhythmAsTrackIsStepped,
            "hold-pat holds through a rest" to ::holdPatHoldsThroughRest,
            "pattern transforms compose" to ::patternTransformsCompose,
            "every varies by cycle" to ::everyVariesByCycle,
            "selection every switches tracks" to ::selectionEverySwitchesTracks,
            "off superimposes a delayed echo" to ::offSuperimposesDelayedEcho,
            "jux mirrors" to ::juxMirrors,
            "degrade is deterministic" to ::degradeIsDeterministic,
            "strip lays rhythm out in space" to ::stripLaysRhythmOutInSpace,
            "euclid selects the right ordinals" to ::euclidSelectsRightOrdinals,
            "no musical word shadows an old one" to ::noWordShadowsAnOldOne,
            "old scenes are unaffected" to ::oldScenesAreUnaffected
        )

        tests.forEach { (name, test) ->
            try {
                test()
                println("ok - $name")
            } catch (t: Throwable) {
                println("failed - $name: ${t.message}")
                t.printStackTrace()
                return false
            }
        }

        println("rectsl music workout passed")
        return true
    }

    // --- §1 Clock ---------------------------------------------------------------------------

    private fun clockConvertsAndCommutes() {
        // 120 bpm, 4 beats to a cycle -> half a cycle per second -> a 2s bar, a 0.5s beat.
        val program = compile(
            """
            ->wall 8 8 abstract
            ->duration 8
            ->tempo 120
            "r" rect  1 ->square
              1 beats  0 ->xy
            ;
            """.trimIndent()
        )
        near(0.5, program.sample(0.0).rects.single().center.x)

        // The same two facts stated in the other order must give the same clock: `->tempo`
        // remembers its bpm so `->beats-per-cycle` can re-derive from it.
        val forward = cpsOf("->tempo 120 ->beats-per-cycle 3")
        val backward = cpsOf("->beats-per-cycle 3 ->tempo 120")
        near(forward, backward)
        near(120.0 / 60.0 / 3.0, forward)

        // ->cps is a direct statement, not a tempo, so it wins outright.
        near(4.0, cpsOf("->tempo 120 ->cps 4"))

        // cycles/cycle-secs read off the same clock.
        val units = compile(
            """
            ->wall 8 8 abstract
            ->duration 8
            ->cps 2
            "r" rect  1 ->square
              3 cycles  cycle-secs ->xy
            ;
            """.trimIndent()
        ).sample(0.0).rects.single()
        near(1.5, units.center.x)
        near(0.5, units.center.y)
    }

    private fun cpsOf(clockSource: String): Double {
        // `->duration-cycles n` sets duration = n / cps, so the duration reports the clock back.
        val program = compile(
            """
            ->wall 4 4 abstract
            $clockSource
            ->duration-cycles 1
            "r" rect 1 ->square ;
            """.trimIndent()
        )
        return 1.0 / program.duration
    }

    // --- §2 pat -----------------------------------------------------------------------------

    private fun patGivesOneCopyPerEvent() {
        // Four kicks a cycle over two cycles is eight copies — the count is the rhythm's, not a
        // number anyone typed.
        val program = compile(patScene(pattern = "bd*4", cycles = 2))
        require(program.sample(0.0).rects.size == 8) {
            "expected 8 copies for bd*4 over 2 cycles, got ${program.sample(0.0).rects.size}"
        }

        // An all-rests pattern shows nothing and does not throw. RectSL has no node removal, so
        // the unused template is hidden rather than deleted — what must be empty is the picture.
        val silent = compile(patScene(pattern = "~", cycles = 2))
        require(visibleAt(silent, 0.0).isEmpty()) { "a rest-only pattern should show nothing" }
        require(visibleAt(silent, 1.5).isEmpty()) { "a rest-only pattern should show nothing, ever" }
    }

    private fun patLifeWindowsMatchTheRhythm() {
        // cps 1, so a cycle is a second and `bd*4` fires at 0, 0.25, 0.5, 0.75.
        val program = compile(patScene(pattern = "bd*4", cycles = 1))
        val onsets = (0..3).map { it * 0.25 }
        onsets.forEachIndexed { index, onset ->
            // Just after its onset, exactly index+1 copies have been born and none has died
            // (each lives a full quarter, so they tile the cycle without overlap).
            val visible = visibleAt(program, onset + 0.01)
            require(visible.size == 1) {
                "at t=${onset + 0.01} expected exactly one live copy, got ${visible.size}"
            }
        }
        // Between onsets the previous copy is still the live one, and at the very start of a
        // window the new one has already taken over: the gate is [birth, birth+life).
        require(visibleAt(program, 0.24).size == 1)
        require(visibleAt(program, 0.25).size == 1)
        require(visibleAt(program, 0.999).size == 1)
    }

    private fun patCarriesMiniNotationWhole() {
        // Nothing here is parsed by RectSL: Euclid, polymeter and per-cycle alternation all
        // arrive from the mini-notation intact. The counts are the point.
        require(compile(patScene("bd(3,8)", 1)).sample(0.0).rects.size == 3)
        require(compile(patScene("bd(3,8)", 4)).sample(0.0).rects.size == 12)
        require(compile(patScene("bd sd . hh hh hh", 1)).sample(0.0).rects.size == 5)
        require(compile(patScene("bd _ _ sd", 1)).sample(0.0).rects.size == 2)
        require(compile(patScene("<a b> c", 2)).sample(0.0).rects.size == 4)

        // Polymeter phases against the grid rather than falling silent after one bar — the
        // §5 bug this language would otherwise have inherited.
        val poly = compile(patScene("{bd hh 808:4}%8", 3))
        require(poly.sample(0.0).rects.size == 24) {
            "polymeter should keep sounding on later cycles, got ${poly.sample(0.0).rects.size}"
        }
    }

    private fun patExposesEventContext() {
        // Each copy parks at x = its own onset in seconds, y = its step within the cycle.
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 2
            ->cps 1
            "e" rect  0.2 ->square
              "bd*4" '( t  step  ->xy ) pat
            ;
            """.trimIndent()
        )
        // Read positions at each copy's own birth, since ->live time-warps the tracks.
        val seen = (0..7).map { index ->
            val t = index * 0.25
            val live = visibleAt(program, t + 0.01).single()
            live.center.x to live.center.y
        }
        seen.forEachIndexed { index, (x, y) ->
            near(index * 0.25, x)
            near((index % 4).toDouble(), y)
        }
    }

    private fun patBodiesAuthorInLocalTime() {
        // The body says "travel from x=0 to x=1 over my first second". Every copy should be at
        // its own local 0 when it is born, whatever its birth time — that is what makes one
        // template a stream rather than a synchronised chorus.
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 4
            ->cps 1
            "e" rect  0.2 ->square
              "bd*2" '( '( 0=0  1=1 ) linear ->x ) pat
            ;
            """.trimIndent()
        )
        listOf(0.0, 0.5, 1.0, 1.5).forEach { birth ->
            near(0.0, visibleAt(program, birth + 1e-4).single().center.x, tol = 1e-3)
        }
        // ... and a quarter of the way along a quarter-second later.
        near(0.25, visibleAt(program, 0.25).single().center.x, tol = 1e-3)
    }

    private fun barCrossingNoteStruckOnce() {
        // `pat("a b c").slow(2)` is three steps over two cycles, so `b` straddles the bar line.
        // Queried cycle by cycle it comes back twice, clipped both times; `pat` must use the
        // onset-carrying fragment widened to its whole, so `b` is ONE copy of full length.
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 2
            ->cps 1
            "e" rect  0.2 ->square
              "a b c" 2 slow '( ) pat
            ;
            """.trimIndent()
        )
        require(program.sample(0.0).rects.size == 3) {
            "a bar-crossing note must be one copy, not two: got ${program.sample(0.0).rects.size}"
        }
        // b runs 2/3..4/3 of a cycle = 0.667..1.333s. It is alive on both sides of the bar line.
        val atBar = visibleAt(program, 1.0)
        require(atBar.size == 1) { "expected exactly one live copy across the bar line" }
        require(visibleAt(program, 0.7).single().id == atBar.single().id) {
            "the copy alive before the bar line must be the same one alive after it"
        }
    }

    // --- §3 Patterns as tracks -----------------------------------------------------------------

    private fun signalsDriveTracks() {
        // `sine ->x` with no conversion word anywhere: the coercion happens at RectSL's single
        // Value->Track site, which is why every track-taking mutator gets this for free.
        val program = compile(
            """
            ->wall 8 8 abstract
            ->duration 4
            ->cps 1
            "r" rect  1 ->square
              sine ->x
            ;
            """.trimIndent()
        )
        // sine() is 0.5 + 0.5 sin(2 pi t), so 0.5 at t=0, 1 at t=0.25, 0.5 at t=0.5, 0 at 0.75.
        near(0.5, program.sample(0.0).rects.single().center.x, tol = 1e-3)
        near(1.0, program.sample(0.25).rects.single().center.x, tol = 1e-3)
        near(0.0, program.sample(0.75).rects.single().center.x, tol = 1e-3)

        // The clock scales it: at cps 2 the same sine completes twice per second.
        val fast = compile(
            """
            ->wall 8 8 abstract
            ->duration 4
            ->cps 2
            "r" rect  1 ->square  sine ->x ;
            """.trimIndent()
        )
        near(1.0, fast.sample(0.125).rects.single().center.x, tol = 1e-3)

        // A signal is a value, so it composes with the scalar operators already in the language.
        val scaled = compile(
            """
            ->wall 8 8 abstract
            ->duration 4
            ->cps 1
            "r" rect  1 ->square  sine 4 * ->x ;
            """.trimIndent()
        )
        near(4.0, scaled.sample(0.25).rects.single().center.x, tol = 1e-3)
    }

    private fun rhythmAsTrackIsStepped() {
        // The musical opposite of a TCB track: it holds, then snaps.
        val program = compile(
            """
            ->wall 8 8 abstract
            ->duration 4
            ->cps 1
            "r" rect  1 ->square
              "0 1 2 3" ->x
            ;
            """.trimIndent()
        )
        listOf(0.0 to 0.0, 0.1 to 0.0, 0.25 to 1.0, 0.4 to 1.0, 0.5 to 2.0, 0.99 to 3.0)
            .forEach { (t, expected) -> near(expected, program.sample(t).rects.single().center.x) }
    }

    private fun holdPatHoldsThroughRest() {
        val stepped = compile(patTrackScene("\"1 ~ 3\" mini nums"))
        val held = compile(patTrackScene("\"1 ~ 3\" mini nums hold-pat"))
        // A rest is genuinely no event, so the plain reading falls back to zero...
        near(0.0, stepped.sample(0.5).rects.single().center.x)
        // ... and the sample-and-hold reading keeps the previous value. Both are pure.
        near(1.0, held.sample(0.5).rects.single().center.x)
        near(3.0, held.sample(0.8).rects.single().center.x)
    }

    // --- §4 Variation ---------------------------------------------------------------------------

    private fun patternTransformsCompose() {
        countIs(4, "bd*2", "2 fast")
        countIs(8, "bd*4", "2 ply")
        // `stut n gapDen` — n strikes per event, each a 1/gapDen of a cycle after the last.
        countIs(6, "bd sd", "3 16 stut")

        // `rev` keeps the count and reverses the order: with x = onset, the atoms swap ends.
        val forward = atomsInOrder("a b c d", transform = "")
        val reversed = atomsInOrder("a b c d", transform = "rev")
        require(forward == listOf("a", "b", "c", "d")) { forward.toString() }
        require(reversed == listOf("d", "c", "b", "a")) { reversed.toString() }

        // `off` superimposes rather than replacing: 4 + 4.
        countIs(8, "bd*4", "1 8 '( ) off")

        // `degrade-by 1` drops everything, `degrade-by 0` drops nothing — the two ends pin the
        // sense of the probability, which is the bit that is easy to invert by accident.
        require(visibleAt(compile(patScene("bd*8", 1, transform = "1 degrade-by")), 0.5).isEmpty()) {
            "degrade-by 1 should drop every event"
        }
        countIs(8, "bd*8", "0 degrade-by")

        // `sometimes-by` partitions: transformed half + untouched half = the whole, no event
        // both transformed and left alone, none dropped.
        countIs(8, "bd*8", "0.5 '( ) sometimes-by")
    }

    private fun everyVariesByCycle() {
        // The pattern-level `every`: on cycle 0 the quote applies (4 events), on cycles 1 and 2
        // it does not (2 each) — 8 over three cycles, where a plain `2 fast` would give 12 and
        // no transform at all would give 6.
        val program = compile(patScene("bd*2", 3, transform = "3 '( 2 fast ) every"))
        require(program.sample(0.0).rects.size == 8) {
            "every 3 over 3 cycles should give 4+2+2=8, got ${program.sample(0.0).rects.size}"
        }
    }

    private fun selectionEverySwitchesTracks() {
        // The scene-level `->every`: one rect, whose x is 0 on most cycles and 5 on every 2nd.
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 6
            ->cps 1
            "r" rect  1 ->square  0 0 ->xy
              selected 2 '( 5 ->x ) ->every
            ;
            """.trimIndent()
        )
        near(5.0, program.sample(0.5).rects.single().center.x)   // cycle 0 — fires
        near(0.0, program.sample(1.5).rects.single().center.x)   // cycle 1 — does not
        near(5.0, program.sample(2.5).rects.single().center.x)   // cycle 2 — fires
        near(0.0, program.sample(3.5).rects.single().center.x)   // cycle 3 — does not
    }

    private fun offSuperimposesDelayedEcho() {
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 6
            ->cps 1
            "r" rect  1 ->square
              '( 0=0  4=4 ) linear ->x
              selected 1 '( 0.5 ->alpha ) ->off
            ;
            """.trimIndent()
        )
        val at2 = program.sample(2.0).rects
        require(at2.size == 2) { "->off should leave the original and one echo, got ${at2.size}" }
        val original = at2.maxByOrNull { it.alpha }!!
        val echo = at2.minByOrNull { it.alpha }!!
        near(2.0, original.center.x)
        near(1.0, echo.center.x)      // one second behind
        near(0.5, echo.alpha)
    }

    private fun juxMirrors() {
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 4
            ->cps 1
            "r" rect  1 ->square  3 0 ->xy
              selected '( 0.5 ->alpha ) ->jux
            ;
            """.trimIndent()
        )
        val rects = program.sample(0.0).rects
        require(rects.size == 2)
        near(3.0, rects.maxByOrNull { it.alpha }!!.center.x)
        near(-3.0, rects.minByOrNull { it.alpha }!!.center.x)
    }

    private fun degradeIsDeterministic() {
        val source = """
            ->wall 20 20 abstract
            ->duration 4
            ->cps 1
            "e" rect  0.5 ->square
              16 '( ic 1 * 0 ->offset ) array  0.5 ->degrade
            ;
        """.trimIndent()
        val a = compile(source)
        val b = compile(source)
        // Same source, same drops, every cycle — a groove you cannot reproduce is not a groove.
        listOf(0.5, 1.5, 2.5).forEach { t ->
            val liveA = visibleAt(a, t).map { it.id }.toSet()
            val liveB = visibleAt(b, t).map { it.id }.toSet()
            require(liveA == liveB) { "->degrade must be deterministic; differed at t=$t" }
        }
        // It actually drops some, and not all, and the set differs between cycles.
        val cycle0 = visibleAt(a, 0.5).map { it.id }.toSet()
        val cycle1 = visibleAt(a, 1.5).map { it.id }.toSet()
        require(cycle0.isNotEmpty() && cycle0.size < 16) { "expected a partial dropout, got ${cycle0.size}/16" }
        require(cycle0 != cycle1) { "->degrade should re-roll each cycle" }
    }

    // --- §5 Layout ---------------------------------------------------------------------------------

    private fun stripLaysRhythmOutInSpace() {
        // `bd [sd sd] hh*4` subdivides the bar 1/2 + (1/4 + 1/4)... no: three top-level steps of
        // 1/3 each, the middle one split in two and the last in four. A strip must show exactly
        // that as widths across a 12-wide wall.
        val program = compile(
            """
            ->wall 12 4 abstract
            ->duration 4
            ->cps 1
            "s" rect  1 ->square
              "bd [sd sd] hh*4" '( ) strip
            ;
            """.trimIndent()
        )
        val rects = program.sample(0.0).rects.sortedBy { it.center.x }
        require(rects.size == 7) { "expected 1 + 2 + 4 = 7 cells, got ${rects.size}" }
        // First cell: a third of the bar, so a third of the wall, centred a third from the left.
        near(4.0, rects.first().size.x)
        near(-4.0, rects.first().center.x)
        // The last four are each a twelfth of the bar — one wall unit wide.
        rects.takeLast(4).forEach { near(1.0, it.size.x) }
        // Widths must sum to the wall: a subdivision tiles its interval exactly.
        near(12.0, rects.sumOf { it.size.x }, tol = 1e-4)
    }

    private fun euclidSelectsRightOrdinals() {
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 4
            ->cps 1
            "e" rect  0.5 ->square
              8 '( ic 1.5 * 0 ->offset ) array
              3 8 0 ->euclid
            ;
            """.trimIndent()
        )
        val live = visibleAt(program, 0.5)
        require(live.size == 3) { "3-in-8 should leave three visible, got ${live.size}" }
        // bjorklund(3,8) here is x..x.x.. — gaps of 3, 2, 3 (PATTERN_LANGUAGE §4). Assert the
        // gaps rather than the slots, for the same reason the pattern gallery does.
        val slots = live.map { Math.round((it.center.x / 1.5) + 3.5).toInt() }.sorted()
        val gaps = slots.zipWithNext { a, b -> b - a } + (slots.first() + 8 - slots.last())
        require(gaps.sorted() == listOf(2, 3, 3)) { "expected Euclidean gaps 3,2,3; got $gaps from $slots" }
    }

    // --- Compatibility ----------------------------------------------------------------------------

    private fun noWordShadowsAnOldOne() {
        // Every musical word must be a genuinely new spelling. A collision would silently
        // change the meaning of scenes that predate this layer, which no test would catch
        // because they would still compile.
        val spellings = RectLangVm().wordMetadata().map { it.spelling }
        require(spellings.size == spellings.toSet().size) {
            "duplicate word spellings: ${spellings.groupBy { it }.filter { it.value.size > 1 }.keys}"
        }
        // The near-misses that were live risks while naming this layer.
        listOf("bars", "range", "noise", "gaussian", "i", "n", "f", "col", "row", "sample", "each", "map")
            .forEach { require(it in spellings) { "pre-existing word '$it' disappeared" } }
    }

    private fun oldScenesAreUnaffected() {
        // A scene that never mentions the clock must behave exactly as it did: nothing outside
        // the musical words reads `cps`, and the default of 1.0 is inert.
        val program = compile(
            """
            ->wall 30 4 meters
            ->duration 10
            ->fps 60
            "left" rect
              -1 0 ->xy
              1 ->square
              '( 0 -1  2 0  4 3 ) ->x
              '( 0 0   2 1  4 0 ) ->alpha
            ;
            dup "right" ->rename
              ->mirror-x
            ;
            """.trimIndent()
        )
        val at2 = program.sample(2.0).rects.associateBy { it.id }
        near(0.0, at2.getValue("left").center.x)
        near(0.0, at2.getValue("right").center.x)
        near(1.0, at2.getValue("left").alpha)
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private fun patScene(pattern: String, cycles: Int, transform: String = ""): String = """
        ->wall 20 20 abstract
        ->duration $cycles
        ->cps 1
        "e" rect  0.2 ->square
          "$pattern" ${if (transform.isBlank()) "" else "mini $transform"} '( ) pat
        ;
    """.trimIndent()

    private fun patTrackScene(expression: String): String = """
        ->wall 8 8 abstract
        ->duration 4
        ->cps 1
        "r" rect  1 ->square  $expression ->x ;
    """.trimIndent()

    /** The atom of each copy, in onset order — reads a rhythm's *content*, not just its count. */
    private fun atomsInOrder(pattern: String, transform: String): List<String> {
        // Encode the atom as an x offset so it survives into the sampled scene: a..d -> 0..3.
        val program = compile(
            """
            ->wall 20 20 abstract
            ->duration 1
            ->cps 1
            "e" rect  0.2 ->square
              "$pattern" ${if (transform.isBlank()) "" else "mini $transform"} '(
                  "a" atom? 0 *
                  "b" atom? 1 * +
                  "c" atom? 2 * +
                  "d" atom? 3 * +
                  0 ->xy
              ) pat
            ;
            """.trimIndent()
        )
        val names = listOf("a", "b", "c", "d")
        return (0..3).map { step ->
            val live = visibleAt(program, step * 0.25 + 0.01).single()
            names[Math.round(live.center.x).toInt()]
        }
    }

    /** How many copies a pattern makes over one cycle, with a message that says what it made. */
    private fun countIs(expected: Int, pattern: String, transform: String, cycles: Int = 1) {
        val actual = compile(patScene(pattern, cycles, transform)).sample(0.0).rects.size
        require(actual == expected) {
            "\"$pattern\" ${if (transform.isBlank()) "" else "| $transform "}over $cycles cycle(s): " +
                "expected $expected copies, got $actual"
        }
    }

    private fun visibleAt(program: SceneProgram, t: Double): List<SampledRect> =
        program.sample(t).rects.filter { it.alpha > 1e-9 }

    private fun compile(source: String): SceneProgram {
        val result = RectAnimationOracle.compile(source)
        require(result.ok) { result.diagnostics.joinToString("\n") }
        return result.program
    }

    private fun near(expected: Double, actual: Double, tol: Double = EPS) {
        require(abs(expected - actual) <= tol) { "expected $expected, got $actual" }
    }
}
