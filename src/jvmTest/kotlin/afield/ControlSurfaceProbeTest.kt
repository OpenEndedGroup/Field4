package afield

import afield.util.NodeTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The control surface, run in the language it ships in**, against the markup Field really emits.
 *
 * A control has two halves and they are written in different languages. Kotlin decides the range, the
 * law and where the handle starts; `src/commonMain/resources/controlLaws.js` and `sliders.js` decide
 * where the handle goes when a hand moves it, what the readout says, and what number is posted back.
 * A test that computed both halves in Kotlin would agree with itself forever while the browser did
 * something else — which is exactly the failure this is here to catch, because the symptom ("the number
 * under the handle is a bit wrong") is one nobody files.
 *
 * So this follows the house pattern set by [afield.graphics.FLineHitRecordProbeTest]: the controls are
 * built by the **real** [ControlGroupBuilder] through [Box.controlGroup] and captured off
 * `output.displayProxy`; the shipped `controlLaws.js` and `sliders.js` are staged **verbatim off the
 * classpath** — which makes them declared inputs of `jvmTest`, so editing either re-runs this instead
 * of leaving a stale `UP-TO-DATE` pass; and `node` drives the same entry points the live page does
 * (the delegated `pointerdown` that `addDelegatedListeners` installs, `applyFieldControlUpdate` for a
 * value arriving from the server, `sendViaAfield` for what goes back out).
 *
 * What is asserted, in both directions:
 *
 *  - **The two implementations of every law agree.** Kotlin's [ControlLawSpec.valueAt] and the
 *    JavaScript's `valueAt` are run over the same fractions of the same tracks and compared to a
 *    relative `1e-13` (measured worst case 1.33e-15). Disagreement here is the drift that would
 *    otherwise ship.
 *  - **The handle Kotlin bakes into the HTML is where the JavaScript would put it.** Both are read
 *    after a refresh, so the server's initial render and the client's re-render cannot differ.
 *  - **Nothing emits a `NaN`.** The markup is scanned for it directly, because `data-value="NaN"`
 *    wedges the handle and reads as "NaN" in the page.
 *  - **A momentary button cannot stick.** A `pointerdown` whose `pointerup` arrives outside the
 *    element, a `pointercancel`, and a window `blur` each have to release it — exactly once — and a
 *    stray release with nothing held has to post nothing.
 *  - **A meter is read-only.** Pressing it posts nothing, and a level arriving from the server moves
 *    the bar by the law rather than linearly.
 *
 * **Deliberately not covered**: layout and browser hit-testing. The stub DOM in
 * `src/jvmTest/resources/controlSurface/probe.mjs` is told the on-screen scale by each case (one case
 * renders the 320-unit viewBox into 640 pixels, so the pointer→view transform is exercised), and each
 * case names the element the pointer goes down on. The live cover for the rest is the demo document
 * named in `dev_docs/control_surface_laws.md`.
 *
 * Needs a `node` >= [NodeTool.DEFAULT_MIN_MAJOR]; `AFIELD_SKIP_JS_TESTS=1` opts out visibly.
 */
class ControlSurfaceProbeTest {

    // ------------------------------------------------------------------ the cases, as the probe sees them

    @Serializable
    private data class Arriving(val value: Double, val expectCx: Double, val expectText: String)

    @Serializable
    private data class SliderCase(
        val name: String,
        val id: String,
        val html: String,
        val fractions: List<Double>,
        val arriving: List<Arriving> = emptyList(),
        val scale: Double = 1.0,
        val originX: Double = 0.0
    )

    @Serializable
    private data class DecimalCase(
        val value: Double,
        val significantDigits: Int,
        val stepDecimals: Int,
        val expect: Int
    )

    @Serializable
    private data class ControlCase(val name: String, val id: String, val html: String, val levels: List<Level> = emptyList())

    @Serializable
    private data class Level(val value: Double, val expectWidth: Double, val expectText: String)

    @Serializable
    private data class Cases(
        val controlLawsPath: String,
        val slidersPath: String,
        val sliders: List<SliderCase>,
        val decimals: List<DecimalCase>,
        val buttons: List<ControlCase> = emptyList(),
        val meters: List<ControlCase> = emptyList()
    )

    @Serializable
    private data class ObservedValue(
        val fraction: Double,
        val sent: Double? = null,
        val dataset: Double? = null,
        val shown: String? = null
    )

    @Serializable
    private data class ObservedLaw(val name: String, val law: String, val values: List<ObservedValue>)

    @Serializable
    private data class ObservedStep(val step: String, val sent: List<Boolean>, val held: Boolean)

    @Serializable
    private data class ObservedButton(val name: String, val sequence: List<ObservedStep>)

    @Serializable
    private data class ObservedReading(
        val value: Double,
        val width: Double? = null,
        val shown: String? = null,
        val postedAnything: Int = 0
    )

    @Serializable
    private data class ObservedMeter(val name: String, val readings: List<ObservedReading>)

    @Serializable
    private data class Observed(
        val laws: List<ObservedLaw> = emptyList(),
        val buttons: List<ObservedButton> = emptyList(),
        val meters: List<ObservedMeter> = emptyList()
    )

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ building the real markup

    /** The geometry `buildSliderHtml` uses, restated so the expectations below are readable. */
    private val padding = 14.0
    private val trackLength = 320.0 - 2 * padding

    /**
     * Runs [block] through the real [ControlGroupBuilder] and returns the HTML and the control records.
     *
     * This is the production path — a `Box`, `controlGroup`, `displayProxy` — not a re-implementation
     * of it, which is the only way the markup under test is the markup that ships.
     */
    private fun built(block: ControlGroupBuilder.() -> Unit): Pair<String, List<ControllerControl>> {
        val box = Box("control-surface-probe-${counter++}")
        val seen = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, content -> seen += channel to content }
        box.controlGroup("controls", block)
        val html = seen.singleOrNull()?.second as? String
            ?: fail("controlGroup emitted ${seen.size} display payload(s), expected exactly one string")
        return html to controllerControlsForBox(box.id)
    }

    private val laws = listOf(
        Triple("log audio cutoff", ControlLawSpec(ControlLaw.LOGARITHMIC, 20.0, 20000.0), 1000.0),
        Triple("log three decades", ControlLawSpec(ControlLaw.LOGARITHMIC, 0.001, 1.0), 0.01),
        Triple("exp delay time", ControlLawSpec(ControlLaw.EXPONENTIAL, 0.0, 10.0, exponent = 3.0), 1.25),
        Triple("bipolar pan", ControlLawSpec(ControlLaw.BIPOLAR, -1.0, 1.0, center = 0.0), 0.0),
        Triple(
            "bipolar detune, cubic",
            ControlLawSpec(ControlLaw.BIPOLAR, -1200.0, 1200.0, exponent = 3.0, center = 0.0),
            300.0
        ),
        Triple("linear gain, unchanged", ControlLawSpec(ControlLaw.LINEAR, 0.0, 1.0), 0.35)
    )

    private val fractions = listOf(0.0, 0.05, 0.25, 0.5, 0.75, 0.95, 1.0)

    private fun sliderCases(): List<SliderCase> = laws.mapIndexed { index, (name, spec, initial) ->
        val step = if (spec.law == ControlLaw.LINEAR) 0.01 else 0.0
        val significantDigits = defaultSignificantDigits(spec.law)
        val (html, controls) = built {
            slider(
                name,
                min = spec.min,
                max = spec.max,
                step = step,
                initialValue = initial,
                law = spec.law,
                lawExponent = spec.exponent,
                lawCenter = spec.center,
                significantDigits = significantDigits
            ) { }
        }
        val control = controls.single()
        val stepDecimals = if (step >= 1.0 || step == 0.0) 0 else 2
        SliderCase(
            name = name,
            id = control.id,
            html = html,
            fractions = fractions,
            arriving = listOf(initial, spec.min, spec.max, spec.valueAt(0.5)).distinct().map { value ->
                Arriving(
                    value = value,
                    expectCx = padding + trackLength * spec.normalizedFor(value),
                    expectText = "%.${controlLawDecimals(value, significantDigits, stepDecimals)}f".format(value)
                )
            },
            // One case is rendered at 2x so the pointer→view transform is proved rather than assumed,
            // and one is offset so a non-zero page origin cannot be ignored either.
            scale = if (index == 0) 2.0 else 1.0,
            originX = if (index == 1) 137.0 else 0.0
        )
    }

    private fun decimalCases(): List<DecimalCase> {
        val values = listOf(0.0, 0.001, 0.02, 1.5, 20.0, 200.0, 999.0, 1000.0, 20000.0, -3.5, 1e-9)
        return values.flatMap { value ->
            listOf(0, 3, 4, 6).flatMap { budget ->
                listOf(0, 2).map { stepDecimals ->
                    DecimalCase(value, budget, stepDecimals, controlLawDecimals(value, budget, stepDecimals))
                }
            }
        }
    }

    // ------------------------------------------------------------------ the tests

    @Test
    fun `the shipped control JavaScript agrees with Kotlin's laws through the real markup`() {
        val cases = sliderCases()
        val observed = run(
            Cases(
                controlLawsPath = "controlLaws.js",
                slidersPath = "sliders.js",
                sliders = cases,
                decimals = decimalCases(),
                buttons = buttonCases(),
                meters = meterCases()
            )
        ) ?: return

        assertEquals(cases.size, observed.laws.size, "the probe did not report every slider case")

        var worst = 0.0
        var worstName = ""
        observed.laws.forEachIndexed { index, law ->
            val (name, spec, _) = laws[index]
            assertEquals(name, law.name)
            assertEquals(spec.law.wireName, law.law, "$name: the browser read the wrong law out of the markup")
            assertEquals(fractions.size, law.values.size, "$name: not every fraction was driven")
            law.values.forEach { value ->
                val sent = value.sent ?: fail("$name: nothing was posted for a press at ${value.fraction}")
                val expected = spec.valueAt(value.fraction)
                val scale = maxOf(abs(expected), 1e-9)
                val error = abs(sent - expected) / scale
                if (error > worst) {
                    worst = error
                    worstName = "$name at ${value.fraction} (js $sent, kotlin $expected)"
                }
                assertEquals(
                    sent,
                    value.dataset ?: Double.NaN,
                    0.0,
                    "$name: the value posted and the value left in the DOM differ at ${value.fraction}"
                )
            }
            // Both ends of the track, exactly. This is the assertion that fails if anyone removes the
            // endpoint short-circuit from the JavaScript.
            assertEquals(spec.min, law.values.first().sent, "$name: the left end of the track is not the minimum")
            assertEquals(spec.max, law.values.last().sent, "$name: the right end of the track is not the maximum")
        }
        println(
            "ControlSurfaceProbeTest: worst Kotlin↔JavaScript law disagreement " +
                "${"%.3e".format(worst)} relative ($worstName)"
        )
        // Measured worst case 1.33e-15 relative, on the cubic bipolar taper at three quarters travel
        // (V8's `Math.pow` and the JVM's differ in the last bit or two, which is all this is). The floor
        // is 1e-13 — two decades above the measurement, and far below any real difference of formula.
        assertTrue(worst < 1e-13, "the browser and the JVM disagree by a relative $worst at $worstName")
    }

    /**
     * A NaN must never reach the markup, so the markup is read.
     *
     * This is cheap and it is the failure the law's refusal exists to prevent; asserting it on the real
     * emitted string means a future law added without an endpoint guard is caught here even if its own
     * test forgets to look.
     */
    @Test
    fun `no control the builder can emit contains a NaN or an Infinity`() {
        val html = buildString {
            laws.forEach { (name, spec, initial) ->
                val (markup, _) = built {
                    slider(
                        name, min = spec.min, max = spec.max, step = 0.0, initialValue = initial,
                        law = spec.law, lawExponent = spec.exponent, lawCenter = spec.center
                    ) { }
                    button("$name gate") { }
                    meter("$name level", min = spec.min, max = spec.max, law = spec.law, initialValue = initial)
                }
                append(markup)
            }
        }
        listOf("NaN", "Infinity", "null", "undefined").forEach { poison ->
            assertTrue(poison !in html, "the emitted control markup contains '$poison'")
        }
        // Measured at 13 139 characters over six laws × (slider + button + meter).
        assertTrue(html.length > 12000, "only ${html.length} characters of markup — the sweep is not covering much")
        println("ControlSurfaceProbeTest: swept ${html.length} characters of emitted control markup, no NaN")
    }

    // ------------------------------------------------------------------ button and meter cases

    private fun buttonCases(): List<ControlCase> {
        val (html, controls) = built { button("gate") { } }
        return listOf(ControlCase(name = "momentary gate", id = controls.single().id, html = html))
    }

    private fun meterCases(): List<ControlCase> {
        val spec = ControlLawSpec(ControlLaw.LINEAR, 0.0, 1.0)
        val (html, controls) = built { meter("level", min = 0.0, max = 1.0, initialValue = 0.0) }
        val control = controls.single()
        val meterTrack = 320.0 - 2 * padding
        return listOf(
            ControlCase(
                name = "linear level meter",
                id = control.id,
                html = html,
                levels = listOf(0.0, 0.25, 0.5, 0.9, 1.0).map { level ->
                    Level(
                        value = level,
                        expectWidth = meterTrack * spec.normalizedFor(level),
                        expectText = "%.${controlLawDecimals(level, control.significantDigits, control.decimalPlaces)}f"
                            .format(level)
                    )
                }
            )
        )
    }

    @Test
    fun `a momentary button releases on every path that loses the pointer`() {
        val observed = run(
            Cases("controlLaws.js", "sliders.js", sliderCases(), decimalCases(), buttonCases(), meterCases())
        ) ?: return
        val button = observed.buttons.singleOrNull() ?: fail("the probe reported ${observed.buttons.size} buttons")
        val steps = button.sequence.associateBy { it.step }
        println("ControlSurfaceProbeTest: button sequence ${button.sequence.map { "${it.step}=${it.sent}" }}")

        assertEquals(listOf(true, false), steps.getValue("press and release").sent, "a press and release is not down-then-up")

        assertEquals(listOf(true), steps.getValue("press only").sent, "a press did not post exactly one 'down'")
        assertTrue(steps.getValue("press only").held, "a pressed button is not marked held")

        // The classic defect: the pointer comes up somewhere else entirely and the control stays on.
        assertEquals(
            listOf(false),
            steps.getValue("release far outside").sent,
            "a pointerup outside the element did not release the button — it is stuck on"
        )
        assertTrue(!steps.getValue("release far outside").held, "the button is still marked held after release")

        assertEquals(listOf(false), steps.getValue("pointercancel").sent, "pointercancel did not release the button")
        assertEquals(listOf(false), steps.getValue("window blur").sent, "losing window focus did not release the button")
        assertEquals(
            emptyList(),
            steps.getValue("stray release").sent,
            "a release with nothing held posted a spurious 'up'"
        )
    }

    @Test
    fun `a meter moves with the level and never posts anything back`() {
        val observed = run(
            Cases("controlLaws.js", "sliders.js", sliderCases(), decimalCases(), buttonCases(), meterCases())
        ) ?: return
        val meter = observed.meters.singleOrNull() ?: fail("the probe reported ${observed.meters.size} meters")
        println("ControlSurfaceProbeTest: meter readings ${meter.readings.map { "${it.value}→${it.width}" }}")
        assertEquals(5, meter.readings.size)
        meter.readings.forEach { reading ->
            assertEquals(0, reading.postedAnything, "a meter at ${reading.value} posted a message back")
        }
        val widths = meter.readings.mapNotNull { it.width }
        assertEquals(5, widths.size, "a meter reading did not draw a bar")
        assertTrue(
            widths.zipWithNext().all { (a, b) -> b > a },
            "the meter bar does not grow monotonically with the level: $widths"
        )
        assertEquals(0.0, widths.first(), 1e-9, "an empty meter is not empty")
        assertEquals(trackLength, widths.last(), 1e-9, "a full meter does not fill its track")
    }

    // ------------------------------------------------------------------ running the probe

    /** Runs the probe and returns what it observed, or `null` when JS testing is opted out of. */
    private fun run(cases: Cases): Observed? {
        if (System.getenv("AFIELD_SKIP_JS_TESTS") == "1") {
            println("ControlSurfaceProbeTest: skipped by AFIELD_SKIP_JS_TESTS=1")
            return null
        }
        val node = NodeTool.resolveNode()
            ?: fail(
                "the control-surface cover runs the real controlLaws.js and sliders.js under node: " +
                    "${NodeTool.unavailableMessage()}. Set AFIELD_SKIP_JS_TESTS=1 to opt out."
            )

        val dir = Files.createTempDirectory("field-control-surface-")
        // Verbatim off the classpath, which is also what makes both files declared inputs of this task.
        dir.resolve("controlLaws.js").writeText(resource("/controlLaws.js"))
        dir.resolve("sliders.js").writeText(resource("/sliders.js"))
        dir.resolve("probe.mjs").writeText(resource("/controlSurface/probe.mjs"))
        dir.resolve("package.json").writeText("{\"type\": \"module\"}\n")
        val casesFile = dir.resolve("cases.json")
        casesFile.writeText(
            json.encodeToString(
                cases.copy(
                    controlLawsPath = dir.resolve("controlLaws.js").toString(),
                    slidersPath = dir.resolve("sliders.js").toString()
                )
            )
        )
        val out = dir.resolve("observed.json")

        val process = ProcessBuilder(node, dir.resolve("probe.mjs").toString(), casesFile.toString(), out.toString())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("the control-surface probe never finished:\n$output")
        }
        println(output.trimEnd())
        if (process.exitValue() != 0 || PASSED !in output) {
            fail("the control-surface probe failed (exit ${process.exitValue()}) in $dir:\n$output")
        }
        return json.decodeFromString<Observed>(out.readText())
    }

    private fun resource(path: String): String =
        ControlSurfaceProbeTest::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: fail("no $path on the test classpath")

    private companion object {
        const val PASSED = "control surface probe passed"
        private var counter = 0
    }
}
