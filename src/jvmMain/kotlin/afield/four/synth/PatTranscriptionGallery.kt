/*
 The figure that makes the round trip something you look at rather than read about.

 One panel per case, one shared MIDI axis, and the two note sets drawn on top of each other: the
 notation Field authored as a hollow outline, and what the detector heard as a solid bar inside it.
 Agreement is then a filled outline and disagreement is a bar with nothing around it or an outline with
 nothing in it — which is the whole reason to overlay rather than stack. A stacked pair of piano rolls
 asks the reader to compare two pictures; an overlay asks them to spot the exceptions.

 Drawn as SVG strings into a `StringBuilder`, following `PatPianoRollGallery` exactly, including its
 palette. Same reasons: a piano roll is line art, and a diffable figure shows a changed detector as a
 changed rectangle rather than as a changed PNG blob. No AWT, no browser, no GPU, no Field window.

 The F0 trace is drawn on the same axis as the notes, because it is in the same units. That is worth
 stating because it is the part of the figure that shows *why* a note was missed: a trace that wanders
 between two outlines is a straddling window, and a trace that vanishes is the voicing gate closing.
 Neither is visible in a score table.
 */
@file:afield.CompletionCategory("Patterns")

package afield.four.synth

import java.io.File
import java.util.Locale
import kotlin.math.max

/*
FIELD_TEST
id: synth.pat.transcription
runner: jvm-main
entrypoint: afield.four.synth.PatTranscriptionGallery
tags: [smoke, synth, pattern, pitch, transcription, plot, svg, capture, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "pat transcription gallery rendered"
*/

/**
 * Headless **notated-against-recovered** gallery for the pitch transcriber.
 *
 * As a FIELD_TEST (`synth.pat.transcription`) it renders every figure to a temp dir and asserts real
 * correctness — every rectangle in the file corresponds to a reference note, a recovered note or a
 * declined span, no coordinate is non-finite, and the panels that are supposed to disagree do. It
 * writes no committed assets during the test. Pass an output dir as `args[0]` (or set
 * `PAT_TRANSCRIPTION_DIR`) to regenerate the committed captures under
 * `dev_docs/assets/pat_transcription/`.
 */
object PatTranscriptionGallery {

    // ── palette, shared with PatPianoRollGallery so the two figures sit together ────────────

    private const val BG = "#14161c"
    private const val PANEL_BG = "#191c24"
    private const val GRID = "#2a2f3a"
    private const val GRID_STRONG = "#454d5e"
    private const val TEXT = "#c9d1e0"
    private const val TEXT_DIM = "#7d879b"

    /** Notated: hollow, cool. What Field asked for. */
    private const val NOTATED = "#4fc3f7"

    /** Recovered: solid, warm. What the detector heard. */
    private const val RECOVERED = "#ffb74d"

    /** Declined: a span that sounded and was not named. */
    private const val DECLINED = "#f06292"

    /** The frame-by-frame F0 trace. */
    private const val TRACE = "#81c784"

    private const val WIDTH = 1180
    private const val LEFT = 210
    private const val RIGHT_PAD = 26
    private const val TOP = 74
    private const val PANEL_H = 150
    private const val PANEL_GAP = 34
    private const val BOTTOM = 40

    /**
     * One case's worth of evidence, in the form the figure needs.
     *
     * Deliberately not a [PatCaseResult]: the legato probe is not a notated Pat case and has to appear
     * in the same figure, so the panel takes the four lists it draws and nothing else.
     */
    class Panel(
        val label: String,
        val summary: String,
        val note: String,
        val sampleRate: Int,
        val totalSeconds: Double,
        val reference: List<PatRefNote>,
        val recovered: List<PatDetectedNote>,
        val declined: List<PatDetectedNote>,
        val frames: List<PatF0Frame>,
        val notation: String
    ) {
        /** Rectangles this panel will draw, for the self-check in [main]. */
        val rectCount: Int get() = reference.size + recovered.size + declined.size
    }

    fun panelOf(result: PatCaseResult, note: String): Panel = Panel(
        label = result.case.label,
        summary = summaryOf(result.score),
        note = note,
        sampleRate = result.sampleRate,
        totalSeconds = result.case.totalSeconds,
        reference = result.reference,
        recovered = result.transcription.notes,
        declined = result.transcription.declined,
        frames = result.transcription.frames,
        notation = result.transcription.miniNotation
    )

    fun panelOf(probe: PatLegatoProbe, note: String): Panel = Panel(
        label = "legato-dough",
        summary = summaryOf(probe.score),
        note = note,
        sampleRate = probe.sampleRate,
        totalSeconds = probe.samples.size.toDouble() / probe.sampleRate,
        reference = probe.reference,
        recovered = probe.transcription.notes,
        declined = probe.transcription.declined,
        frames = probe.transcription.frames,
        notation = probe.transcription.miniNotation
    )

    private fun summaryOf(score: PatTranscriptionScore): String = buildString {
        append("${score.recovered}/${score.reference} notes")
        if (score.declined > 0) append(", ${score.declined} declined")
        append("  ·  note-F ${"%.2f".format(score.noteF)}")
        if (score.onsetMatched > 0) {
            append("  ·  ${"%.1f".format(score.centsMedian)} cents median")
            append(" (p90 ${"%.1f".format(score.centsP90)})")
            append("  ·  onset ${"%.1f".format(score.onsetMedianMs)} ms median")
        }
    }

    // ── rendering ─────────────────────────────────────────────────────────────────────────

    /** Renders [panels] to a standalone SVG document. */
    fun renderSvg(title: String, subtitle: String, panels: List<Panel>): String {
        require(panels.isNotEmpty()) { "a figure with no panels" }
        val plotWidth = WIDTH - LEFT - RIGHT_PAD
        val subtitleLines = wrap(subtitle, 118).size
        val headerExtra = (subtitleLines - 1) * 14
        val height = TOP + headerExtra + panels.size * (PANEL_H + PANEL_GAP) + BOTTOM
        val sb = StringBuilder(1 shl 16)

        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$WIDTH" height="$height" """)
        sb.append("""viewBox="0 0 $WIDTH $height" font-family="Iosevka, Menlo, monospace">""").append('\n')
        sb.append("""<rect x="0" y="0" width="$WIDTH" height="$height" fill="$BG"/>""").append('\n')
        sb.append(text(LEFT, 28, title, TEXT, 18, weight = "600")).append('\n')
        // SVG <text> does not wrap, so the subtitle is wrapped here or it runs off the page — which is
        // exactly what the first render of this figure did.
        wrap(subtitle, 118).forEachIndexed { line, content ->
            sb.append(text(LEFT, 46 + line * 14, content, TEXT_DIM, 12)).append('\n')
        }

        // Legend, so the figure reads without this file next to it.
        val legendY = 62 + headerExtra
        sb.append("""<rect x="$LEFT" y="${legendY - 8}" width="16" height="10" fill="none" """)
        sb.append("""stroke="$NOTATED" stroke-width="1.4"/>""").append('\n')
        sb.append(text(LEFT + 22, legendY, "notated", TEXT_DIM, 11)).append('\n')
        sb.append("""<rect x="${LEFT + 92}" y="${legendY - 6}" width="16" height="6" fill="$RECOVERED"/>""").append('\n')
        sb.append(text(LEFT + 114, legendY, "recovered", TEXT_DIM, 11)).append('\n')
        sb.append("""<rect x="${LEFT + 200}" y="${legendY - 6}" width="16" height="6" fill="$DECLINED"/>""").append('\n')
        sb.append(text(LEFT + 222, legendY, "declined (sounded, not named)", TEXT_DIM, 11)).append('\n')
        sb.append("""<line x1="${LEFT + 434}" y1="${legendY - 3}" x2="${LEFT + 450}" y2="${legendY - 3}" """)
        sb.append("""stroke="$TRACE" stroke-width="1.4"/>""").append('\n')
        sb.append(text(LEFT + 456, legendY, "measured F0, voiced frames only", TEXT_DIM, 11)).append('\n')

        panels.forEachIndexed { index, panel ->
            val top = TOP + headerExtra + index * (PANEL_H + PANEL_GAP)
            renderPanel(sb, panel, top, plotWidth)
        }

        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun renderPanel(sb: StringBuilder, panel: Panel, top: Int, plotWidth: Int) {
        val bottom = top + PANEL_H
        val inner = PANEL_H - 18.0

        // The axis spans the notated pitches and everything the detector claimed, so a wrong note is
        // drawn where it actually was rather than clipped to the edge and made to look like a near miss.
        val pitches = panel.reference.map { it.midi } +
            panel.recovered.map { it.measuredMidi } +
            panel.declined.map { it.measuredMidi }
        val lo = (pitches.minOrNull() ?: 60.0) - 1.5
        val hi = (pitches.maxOrNull() ?: 72.0) + 1.5
        val span = max(4.0, hi - lo)

        fun x(seconds: Double) = LEFT + (seconds / panel.totalSeconds).coerceIn(0.0, 1.0) * plotWidth
        fun y(midi: Double) = bottom - 9.0 - ((midi - lo) / span).coerceIn(0.0, 1.0) * inner
        val semitone = max(3.0, inner / span)

        sb.append("""<rect x="$LEFT" y="$top" width="$plotWidth" height="$PANEL_H" fill="$PANEL_BG"/>""").append('\n')

        // Time gridlines every 0.25 s, emphasised each second.
        val quarters = (panel.totalSeconds * 4).toInt()
        for (q in 0..quarters) {
            val gx = fmt(x(q / 4.0))
            val strong = q % 4 == 0
            sb.append(
                """<line x1="$gx" y1="$top" x2="$gx" y2="$bottom" """ +
                    """stroke="${if (strong) GRID_STRONG else GRID}" """ +
                    """stroke-width="${if (strong) "1.2" else "0.5"}"/>"""
            ).append('\n')
        }

        // Where the detector called the audio unvoiced. This is the band that explains missing notes.
        for (frame in panel.frames) {
            if (frame.voiced) continue
            val fx = x(frame.centerSample.toDouble() / panel.sampleRate)
            sb.append(
                """<line x1="${fmt(fx)}" y1="$top" x2="${fmt(fx)}" y2="$bottom" """ +
                    """stroke="#3a2a34" stroke-width="1.6" stroke-opacity="0.5"/>"""
            ).append('\n')
        }

        sb.append(text(12, top + 16, panel.label, TEXT, 13, weight = "600")).append('\n')
        // One <text> per line. A newline inside an SVG <text> is whitespace, so the first version of
        // this ran four sentences together and out over the plot area.
        panel.note.split('\n').forEachIndexed { line, content ->
            sb.append(text(12, top + 34 + line * 12, content.trim(), TEXT_DIM, 9.5)).append('\n')
        }
        sb.append(text(LEFT + 6, top + 15, panel.summary, TEXT_DIM, 10.5)).append('\n')

        // MIDI axis labels.
        sb.append(text(LEFT - 8, top + 14, hi.toInt().toString(), TEXT_DIM, 9, anchor = "end")).append('\n')
        sb.append(text(LEFT - 8, bottom - 6, lo.toInt().toString(), TEXT_DIM, 9, anchor = "end")).append('\n')

        // Notated: hollow outlines, one semitone tall.
        for (ref in panel.reference) {
            val x0 = x(ref.startSample.toDouble() / panel.sampleRate)
            val x1 = x(ref.endSample.toDouble() / panel.sampleRate)
            sb.append(
                """<rect x="${fmt(x0)}" y="${fmt(y(ref.midi) - semitone / 2)}" """ +
                    """width="${fmt(max(2.0, x1 - x0))}" height="${fmt(semitone)}" rx="1.5" """ +
                    """fill="none" stroke="$NOTATED" stroke-width="1.3"/>"""
            ).append('\n')
        }

        // Recovered: solid bars, inset so an agreeing pair reads as a filled outline.
        for (note in panel.recovered) {
            val x0 = x(note.startSeconds)
            val x1 = x(note.endSeconds)
            val h = max(2.0, semitone * 0.5)
            sb.append(
                """<rect x="${fmt(x0)}" y="${fmt(y(note.measuredMidi) - h / 2)}" """ +
                    """width="${fmt(max(2.0, x1 - x0))}" height="${fmt(h)}" rx="1" """ +
                    """fill="$RECOVERED" fill-opacity="0.95"/>"""
            ).append('\n')
        }

        // Declined: drawn, because a refusal that leaves no mark is indistinguishable from silence.
        for (note in panel.declined) {
            val x0 = x(note.startSeconds)
            val x1 = x(note.endSeconds)
            val h = max(2.0, semitone * 0.5)
            sb.append(
                """<rect x="${fmt(x0)}" y="${fmt(y(note.measuredMidi) - h / 2)}" """ +
                    """width="${fmt(max(2.0, x1 - x0))}" height="${fmt(h)}" rx="1" """ +
                    """fill="$DECLINED" fill-opacity="0.85"/>"""
            ).append('\n')
        }

        // The F0 trace, broken wherever voicing stops so a gap is a gap and not a diagonal.
        val runs = mutableListOf<MutableList<PatF0Frame>>()
        for (frame in panel.frames) {
            if (frame.voiced && frame.midi.isFinite() && frame.midi in lo..hi) {
                if (runs.isEmpty() || runs.last().isEmpty()) runs += mutableListOf(frame)
                else runs.last() += frame
            } else if (runs.isNotEmpty() && runs.last().isNotEmpty()) {
                runs += mutableListOf<PatF0Frame>()
            }
        }
        for (run in runs) {
            if (run.size < 2) continue
            val points = run.joinToString(" ") { frame ->
                "${fmt(x(frame.centerSample.toDouble() / panel.sampleRate))},${fmt(y(frame.midi))}"
            }
            sb.append(
                """<polyline points="$points" fill="none" stroke="$TRACE" """ +
                    """stroke-opacity="0.85" stroke-width="1.1"/>"""
            ).append('\n')
        }

        // The recovered notation, under the panel. This is the artifact, so it belongs in the figure.
        val shown = if (panel.notation.length <= 132) panel.notation else panel.notation.take(129) + "…"
        sb.append(text(LEFT, bottom + 15, "pat(\"$shown\")", TEXT_DIM, 10)).append('\n')
        sb.append(text(LEFT, bottom + 27, "0 s", TEXT_DIM, 9)).append('\n')
        sb.append(
            text(
                LEFT + plotWidth, bottom + 27,
                "${"%.2f".format(panel.totalSeconds)} s", TEXT_DIM, 9, anchor = "end"
            )
        ).append('\n')
    }

    private fun text(
        x: Number,
        y: Number,
        content: String,
        fill: String,
        size: Number,
        weight: String = "400",
        anchor: String = "start"
    ): String =
        """<text x="${fmt(x.toDouble())}" y="${fmt(y.toDouble())}" fill="$fill" font-size="$size" """ +
            """font-weight="$weight" text-anchor="$anchor">${escape(content)}</text>"""

    /** Greedy word wrap, because SVG text does not wrap and a clipped caption is a lost caption. */
    private fun wrap(s: String, columns: Int): List<String> {
        if (s.isBlank()) return listOf("")
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in s.split(' ')) {
            if (current.isNotEmpty() && current.length + 1 + word.length > columns) {
                lines += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun fmt(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return v.toLong().toString()
        return String.format(Locale.US, "%.2f", v)
    }

    // ── the figures ───────────────────────────────────────────────────────────────────────

    /** One figure: name, and the panels it draws from a corpus run. */
    class Figure(
        val name: String,
        val title: String,
        val subtitle: String,
        val build: (List<PatCaseResult>, PatLegatoProbe) -> List<Panel>
    )

    val FIGURES: List<Figure> = listOf(
        Figure(
            name = "roundtrip",
            title = "notated against recovered — the round trip closing",
            subtitle = "hollow outline: the notation Field authored. solid bar: what the detector heard " +
                "back from the render. Same axis and scale, so the exceptions are what you see. Vertical " +
                "position and left edge are measurements; bar LENGTH is not \u2014 a recovered note ends where " +
                "its pitch stopped being stable, which reaches into the previous note's decay.",
            build = { results, _ ->
                listOf(
                    panelOf(
                        results.named("detached-scale"),
                        "the clean case. 125 ms notes,\nwell inside the analysis window."
                    ),
                    panelOf(
                        results.named("wide-leaps"),
                        "leaps over three octaves.\nThe pitch sequence comes back\nexactly, including the order."
                    ),
                    panelOf(
                        results.named("detuned-cents"),
                        "notes 25/50/75/50 cents sharp.\nThe bars sit off the outlines\nby exactly that much."
                    )
                )
            }
        ),
        Figure(
            name = "opposite-failures",
            title = "the two onset criteria fail in opposite directions",
            subtitle = "top: a flat-envelope Dough render an energy detector cannot segment. bottom: repeated " +
                "pitches a pitch detector cannot segment. Neither criterion is the better one.",
            build = { results, legato ->
                listOf(
                    panelOf(
                        legato,
                        "one oscillator, stepped frequency,\ncontinuous phase — the RMS envelope\n" +
                            "is flat. Energy recall 0.13,\npitch recall 0.86."
                    ),
                    panelOf(
                        results.named("repeated-pitch"),
                        "each pitch struck twice, so half\nthe onsets have no pitch change.\n" +
                            "Energy 0.13, pitch 0.81, merged 0.88."
                    )
                )
            }
        ),
        Figure(
            name = "refusals-and-limits",
            title = "where it declines, and where it runs out of resolution",
            subtitle = "a monophonic detector's useful output on hard material is a stated inability. The " +
                "dark vertical bands are frames the voicing gate rejected.",
            build = { results, _ ->
                listOf(
                    panelOf(
                        results.named("chord-triad"),
                        "three simultaneous pitches.\nNothing named, verdict POLYPHONIC —\n" +
                            "the outlines have no bars in them\nand that is the correct answer."
                    ),
                    panelOf(
                        results.named("fast-line"),
                        "31 ms notes, told its register\nso the window shrinks to 15 ms.\nRecovered, but with a long tail."
                    ),
                    panelOf(
                        results.named("fast-line-untold"),
                        "the same audio, not told its\nregister. The window is longer\n" +
                            "than the notes. Nothing survives."
                    )
                )
            }
        )
    )

    private fun List<PatCaseResult>.named(label: String): PatCaseResult =
        firstOrNull { it.case.label == label } ?: error("corpus has no case '$label'")

    /** Renders figure [name] and returns its SVG source. */
    fun svgFor(name: String): String {
        val figure = FIGURES.firstOrNull { it.name == name }
            ?: error("unknown figure '$name'; known: ${FIGURES.map { it.name }}")
        val results = PatTranscriptionCorpus.runAll()
        val legato = PatTranscriptionCorpus.runLegatoProbe()
        return renderSvg(figure.title, figure.subtitle, figure.build(results, legato))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("PAT_TRANSCRIPTION_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "pat-transcription-gallery").path
        )
        outDir.mkdirs()

        val results = PatTranscriptionCorpus.runAll()
        val legato = PatTranscriptionCorpus.runLegatoProbe()
        println(PatTranscriptionCorpus.report(results))
        println("legato probe (Dough, ${legato.sampleRate} Hz, flat envelope):")
        println(legato.describe())

        for (figure in FIGURES) {
            val panels = figure.build(results, legato)
            check(panels.isNotEmpty()) { "${figure.name}: no panels" }

            val svg = renderSvg(figure.title, figure.subtitle, panels)

            // Every rectangle must be a note somebody asked for or heard: one per reference note, one
            // per recovered note, one per declined span, plus one backdrop per panel, plus the page
            // background, plus the three legend swatches. A figure that draws anything else is
            // decorating rather than reporting.
            val rects = Regex("<rect ").findAll(svg).count()
            val expected = panels.sumOf { it.rectCount } + panels.size + 1 + 3
            check(rects == expected) {
                "${figure.name}: SVG has $rects rects, expected $expected " +
                    "(${panels.sumOf { it.rectCount }} notes + ${panels.size} panels + 1 background + 3 legend)"
            }
            check(svg.startsWith("<svg") && svg.trimEnd().endsWith("</svg>")) {
                "${figure.name}: malformed SVG"
            }
            check(!svg.contains("NaN") && !svg.contains("Infinity")) {
                "${figure.name}: SVG contains a non-finite coordinate"
            }
            // A blank figure passes every check above, so assert there is ink of both kinds.
            check(svg.contains(NOTATED)) { "${figure.name}: nothing notated was drawn" }
            check(Regex("<polyline ").findAll(svg).count() >= panels.size) {
                "${figure.name}: fewer F0 traces than panels — the analysis drew nothing"
            }

            val file = File(outDir, "pat-transcription-${figure.name}.svg")
            file.writeText(svg)
            println(
                "wrote ${file.absolutePath} (${panels.size} panels, " +
                    "${panels.sumOf { it.rectCount }} notes, ${svg.length} bytes)"
            )
        }

        // The claims the figures are captioned with, re-checked here so a caption cannot go stale
        // silently. These duplicate PatPitchTranscriptionTest on purpose: the figure ships as a
        // committed asset and its prose has to be true in the same run that draws it.
        val told = results.named("fast-line").pitchTally.recall
        val untold = results.named("fast-line-untold").pitchTally.recall
        check(told >= 0.80 && untold <= 0.15) {
            "the register-lever caption claims 0.91 vs 0.03; measured $told vs $untold"
        }
        check(legato.energyMatch.recall <= 0.30 && legato.pitchMatch.recall >= 0.70) {
            "the legato caption claims energy 0.13 / pitch 0.86; measured " +
                "${legato.energyMatch.recall} / ${legato.pitchMatch.recall}"
        }
        val repeated = results.named("repeated-pitch")
        check(repeated.mergedTally.recall > repeated.pitchTally.recall) {
            "the merge caption claims merged beats pitch on repeats; measured " +
                "${repeated.mergedTally.recall} vs ${repeated.pitchTally.recall}"
        }
        check(results.named("chord-triad").transcription.notes.isEmpty()) {
            "the chord caption claims nothing was named"
        }

        println("pat transcription gallery rendered to ${outDir.absolutePath} (${FIGURES.size} figures)")
    }
}

/** Renders a [PatTranscriptionGallery] figure into this box's output as an inline SVG. */
fun afield.Box.showPatTranscription(name: String = "roundtrip", channel: String = "main") =
    display(channel, PatTranscriptionGallery.svgFor(name))

/**
 * Transcribes [samples] and draws the result against a notated reference in this box's output.
 *
 * The live-coding form of the whole loop: render a pattern, hand the buffer here with the notation you
 * rendered, and see the two overlaid. [reference] may be empty, in which case only what was heard is
 * drawn — which is the honest picture when there is no ground truth, and the situation any audio not
 * generated by Field is in.
 */
fun afield.Box.showPatTranscriptionOf(
    samples: FloatArray,
    sampleRate: Int = PatSoundDocs.SR,
    reference: List<PatRefNote> = emptyList(),
    minGapSeconds: Double? = null,
    minHz: Double = PAT_PITCH_MIN_HZ,
    maxHz: Double = PAT_PITCH_MAX_HZ,
    label: String = "transcription",
    channel: String = "main"
): PatTranscription {
    val transcription = patTranscribe(
        samples, sampleRate, minGapSeconds = minGapSeconds, minHz = minHz, maxHz = maxHz
    )
    val score = patScoreTranscription(
        label = label,
        reference = reference,
        recovered = transcription.notes,
        declined = transcription.declined.size,
        sampleRate = sampleRate,
        toleranceSeconds = PatTranscriptionCorpus.ONSET_TOLERANCE_SECONDS
    )
    val panel = PatTranscriptionGallery.Panel(
        label = label,
        summary = if (reference.isEmpty()) {
            "${transcription.notes.size} notes heard; no reference to score against"
        } else {
            "${score.recovered}/${score.reference} notes  ·  note-F ${"%.2f".format(score.noteF)}" +
                if (score.onsetMatched > 0) "  ·  ${"%.1f".format(score.centsMedian)} cents median" else ""
        },
        note = transcription.warnings.firstOrNull()?.take(120) ?: "",
        sampleRate = sampleRate,
        totalSeconds = samples.size.toDouble() / sampleRate,
        reference = reference,
        recovered = transcription.notes,
        declined = transcription.declined,
        frames = transcription.frames,
        notation = transcription.miniNotation
    )
    display(
        channel,
        PatTranscriptionGallery.renderSvg(
            title = label,
            subtitle = "measured from ${samples.size} samples at $sampleRate Hz, offline, no service",
            panels = listOf(panel)
        )
    )
    return transcription
}

/** [PatRefNote]s for the notation that produced a buffer — the ground truth Field already owns. */
fun patReferenceNotes(
    notation: String,
    cycles: Int,
    cps: Double,
    sustain: Double = 1.0,
    sampleRate: Int = PatSoundDocs.SR
): List<PatRefNote> {
    val secondsPerCycle = 1.0 / cps
    return pat(notation)
        .events(PatArc(PatQ.ZERO, PatQ.of(cycles)))
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
        .mapNotNull { event ->
            val midi = event.annotations["note"]?.toDoubleOrNull()
                ?: event.value.trim().toDoubleOrNull()
                ?: return@mapNotNull null
            val startSec = event.arc.start.toDouble() * secondsPerCycle
            val arcSec = (event.arc.end - event.arc.start).toDouble() * secondsPerCycle
            PatRefNote(
                startSample = (startSec * sampleRate).toInt(),
                endSample = ((startSec + arcSec * sustain.coerceIn(0.0, 1.0)) * sampleRate).toInt(),
                midi = midi
            )
        }
}
