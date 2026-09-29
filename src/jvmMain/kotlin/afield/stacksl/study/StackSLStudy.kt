package afield.stacksl.study

import afield.stdlib.StudyEvidence
import afield.stdlib.StudyVariants
import afield.stdlib.StudyCompare
import afield.stdlib.StudyDifference
import afield.ai.core.ToolImage
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.KnobNames
import afield.stdlib.SourceRevision
import afield.stdlib.SourceStudy
import afield.stdlib.StudyAdmission
import afield.stdlib.StudyRejection
import afield.stdlib.StudyReport
import afield.stdlib.StudySourceEdit
import afield.stdlib.stackAutoShaderReport
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Talking a shader into existence: the StackSL study a document's AI4 tools, and the person beside
 * them, work on together.
 *
 * The source is a box's `stackSL` property — ordinary StackSL a person can read and edit, and that a
 * GPU module runs unchanged (see [StackSLStudyShader.FRAGMENT]). Every edit, whether a model's, a
 * person's knob turn or a person's typing, becomes a checked revision through [SourceStudy]: stale
 * edits are refused, a program that does not compile or whose definitions leave the stack unbalanced
 * never reaches the box, and every accepted version stays on disk to compare and restore.
 *
 * What makes this more than a text editor with an undo stack is what it can *show*, all of it
 * rendered by the CPU reference ([StackSLCpu]) with no graphics context, so the same call returns the
 * same pixels in a test, a headless run and a live session:
 *
 *  - [observe] — frames at chosen times, with the measurements a thumbnail hides (clipping, NaN,
 *    detail, feature size, palette, motion), optionally against another revision.
 *  - [trace] — what is on top of the stack after each line, because every prefix of a concatenative
 *    program is a program; and optionally the whole stack at one pixel after each word.
 *  - [knobs] and [sweep] — the numbers a person typed, which of them matter, and what a range of
 *    values for one of them looks like, without committing any.
 *  - [tryVariants] — several candidate programs rendered side by side and held, unpublished, until a
 *    model or a person adopts one ([adoptVariant]).
 *  - [compare] — whether an edit changed only what it claimed to: outside a region, in brightness only,
 *    or in what one stage leaves on the stack.
 *
 * It renders single-pass programs over `vec4(uv, hash, seconds)`. A program that samples a texture —
 * `sample`, `prev`, feedback — needs iterated state the CPU reference does not keep, and is refused at
 * the check with that reason rather than rendered against a black texture.
 */
class StackSLStudy(
    val directory: File,
    readSource: () -> String,
    replaceSource: (expected: String, replacement: String) -> Boolean
) {
    /**
     * What draws the study's frames. The CPU reference until a tools box hands it a
     * [StackSLGpuRasterizer] for a running window — then everything below renders on the GPU, and
     * falls back, saying why, whenever the window cannot draw.
     */
    @Volatile
    var rasterizer: StackSLRasterizer = CpuStackSLRasterizer

    /** The revision history this study publishes through. */
    val revisions = SourceStudy(directory, "stacksl", readSource, replaceSource, { source, cancelled -> check(source, cancelled, rasterizer) }, maxSourceLength = 20000)

    /** Frames of [program] from [rasterizer]; with [parallel] false the CPU reference keeps to one thread. */
    internal fun frames(program: String, size: Int, seconds: List<Double>, parallel: Boolean = true): List<CpuRaster> =
        if (!parallel && rasterizer === CpuStackSLRasterizer) seconds.map { StackSLStudyShader.renderRaw(program, size, it, parallel = false) }
        else rasterizer.renderAll(program, size, seconds)

    internal fun frame(program: String, size: Int, seconds: Double, parallel: Boolean = true): CpuRaster =
        frames(program, size, listOf(seconds), parallel).single()

    /**
     * What [line] of [source] shows, for the editor ([afield.stdlib.LineNotes]): the stage the line ends
     * — the program up to it, or the value it stores for a line ending `>name` — drawn at the card's
     * reference time and scaled to its own range when it runs outside `0..1`, as the card's stage tiles
     * are. Null for a line that ends no stage (a comment, a definition's body) or one whose prefix does
     * not compile. Recent notes are kept, so moving the cursor back is free.
     */
    fun lineNote(source: String, line: Int): afield.stdlib.LineNotes.Note? {
        val key = SourceStudy.hash(source) + ":" + line
        synchronized(lineNotes) { lineNotes[key]?.let { return it.orElse(null) } }
        val note = runCatching {
            val stage = StackSLStudyShader.stages(source).filter { it.line == line }.maxByOrNull { it.wordsThrough } ?: return@runCatching null
            val outline = StackSLCompiler.outline(source)
            if (!StackSLCpu.compile(outline.rootPrefix(source, stage.wordsThrough)).ok) return@runCatching null
            val raw = frame(outline.stageProgram(source, stage.wordsThrough), LINE_NOTE_SIZE, StackSLStudyShader.REFERENCE_TIME, parallel = false)
            val (tile, scaled) = StudyEvidence.normalizedTile(raw, "", "")
            val stored = outline.storedMark(stage.wordsThrough)
            val what = stored?.let { "the value it stores in >$it" } ?: "what it leaves on the stack"
            afield.stdlib.LineNotes.Note(
                "$what, at t = ${StackSLStudyShader.REFERENCE_TIME} s" + (if (scaled) ", scaled to its range" else "") + " · ${rasterizer.description}",
                afield.util.PngEncoder.encode(tile.argb!!, tile.width, tile.height))
        }.getOrNull()
        synchronized(lineNotes) {
            lineNotes[key] = java.util.Optional.ofNullable(note)
            while (lineNotes.size > 64) lineNotes.remove(lineNotes.keys.first())
        }
        return note
    }

    private val lineNotes = LinkedHashMap<String, java.util.Optional<afield.stdlib.LineNotes.Note>>()

    /** The candidates [tryVariants] tried and holds for [adoptVariant]. */
    private val speculation = StudyVariants(directory, "stacksl")
    private val previews = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Called after anything that changes what the human view should show — a publication, a new batch of variants. */
    var onChange: (() -> Unit)? = null

    /** The line the person's editor cursor is on in this study's box, or null — see [afield.stdlib.BoxStudies.personAt]. */
    @Volatile
    var editorLine: () -> Int? = { null }

    companion object {
        /** What StackSL is, for the fast model that names a program's knobs ([knobNames]). */
        internal const val ABOUT_FOR_NAMES = "StackSL is a stack-based shader language: numbers are pushed onto a stack and the words after them " +
            "consume them, and the program leaves a colour for every pixel of the window; >name stores a value and <name reads it back, " +
            "uv is the pixel's position and time the seconds since the program started."

        /** A line note's picture, in pixels on a side: big enough to read a field, small enough to render as the cursor moves. */
        const val LINE_NOTE_SIZE = 96

        /** The size an edit's preview frames are rendered at: enough to judge, cheap enough to wait for. */
        const val PREVIEW_SIZE = 128

        /** A still renders one frame, so its preview can have the pixels an animated preview spends on four. */
        const val STILL_PREVIEW_SIZE = 256

        /** How a study frame is defined, in the words every tool result repeats. */
        const val FRAME = "Square frame; the program is called as program(vec4(uv, hash, seconds)): uv runs 0..1 with y up, hash is a per-pixel value in 0..1, seconds is time. The top of the stack is the colour, clamped to 0..1; NaN draws black."

        /**
         * What is wrong with [source] without running it: the compiler's errors and warnings, texture
         * sampling (refused), and definitions that leave the stack unbalanced (refused). This is what
         * the editor shows on a StackSL box, and the first half of [check].
         */
        fun diagnose(source: String): List<StackSLProblem> {
            val compiled = StackSLCpu.compile(source)
            val problems = compiled.diagnostics.map { StackSLProblem(it.severity == StackSLSeverity.ERROR, it.message, it.location?.line, it.toString()) }
                .toMutableList()
            if (problems.none { it.error } && compiled.analysis.requiredSamplers.isNotEmpty()) {
                problems += StackSLProblem(true, "This program samples a texture (${compiled.analysis.requiredSamplers.joinToString()}). " +
                    "A study renders single-pass programs over vec4(uv, hash, seconds); sample, prev, gather and feedback need iterated state the CPU reference does not keep.",
                    null, "texture sampling")
            }
            if (problems.none { it.error }) {
                val report = stackAutoShaderReport(source)
                report.problems.forEach {
                    problems += StackSLProblem(true, "A definition leaves the stack unbalanced — each called definition must consume one value and leave one: $it. " +
                        "Use >name (move) rather than =name (copy) to take a value off the stack.", null, it)
                }
                report.warnings.filter { w -> problems.none { it.summary == w } }.forEach { problems += StackSLProblem(false, it, null, it) }
            }
            // `>x` looks like storing into a mark called x, and is the word that replaces a lane, so
            // the `<x` meant to read it back finds nothing. Say that, not just "unknown mark".
            for (lane in "xyzw") {
                val stores = Regex("""(^|\s)>$lane(\s|$)""").containsMatchIn(source)
                val read = Regex("""(^|\s)<$lane(\s|$)""").find(source) ?: continue
                if (!stores) continue
                val line = source.substring(0, read.range.first + read.groupValues[1].length).count { it == '\n' } + 1
                problems.add(0, StackSLProblem(true,
                    "A mark cannot be called $lane: >$lane replaces the $lane lane of the value on top of the stack, so <$lane reads a mark " +
                        "that was never stored. Use another name, such as >v and <v.", line, "mark named $lane"))
            }
            return problems
        }

        /**
         * Admit or refuse a candidate program: it must pass [diagnose] with no error and render
         * probe frames on [rasterizer]. Warnings are kept, not refused.
         */
        fun check(source: String, cancelled: () -> Boolean = { false }, rasterizer: StackSLRasterizer = CpuStackSLRasterizer): StudyAdmission<StackSLCheck> {
            val compiled = StackSLCpu.compile(source)
            val errors = compiled.diagnostics.filter { it.severity == StackSLSeverity.ERROR }
            if (errors.isNotEmpty()) {
                throw StudyRejection("The program does not compile:\n" + errors.joinToString("\n") { "  $it" },
                    mapOf("errors" to errors.map { it.toString() }))
            }
            diagnose(source).firstOrNull { it.error }?.let { refused ->
                throw StudyRejection(refused.message, mapOf("problems" to listOf(refused.summary)))
            }
            val report = stackAutoShaderReport(source)
            if (cancelled()) throw CancellationException("Study check cancelled")
            val outline = StackSLCompiler.outline(source)
            val probes = try {
                if (rasterizer === CpuStackSLRasterizer) listOf(0.0, StackSLStudyShader.REFERENCE_TIME, 7.3).map { StackSLStudyShader.renderRaw(source, 24, it, parallel = false) }
                else rasterizer.renderAll(source, 24, listOf(0.0, StackSLStudyShader.REFERENCE_TIME, 7.3))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                throw StudyRejection("This program cannot be rendered: ${e.message}")
            }
            val reference = probes[1]
            val pixels = reference.width * reference.height
            val nonFinite = (0 until pixels).count { p -> (0 until 3).any { !reference.rgba[p * 4 + it].isFinite() } }.toDouble() / pixels
            val animated = probes.zipWithNext().any { (a, b) -> a.rgba.indices.any { i ->
                val x = a.rgba[i]; val y = b.rgba[i]
                x.isFinite() && y.isFinite() && abs(x - y) > 1e-6
            } }
            val knobs = runCatching { StackSLStudyShader.knobs(source) }.getOrDefault(emptyList())
            val result = StackSLCheck(
                warnings = report.warnings,
                notes = report.notes,
                definitions = compiled.analysis.functions.filter { it.name != "<root>" }
                    .map { StackSLDefinitionReport(it.name, it.finalStackDepth, it.maxStackDepth, it.prunedValues) },
                rootFinalDepth = compiled.analysis.functions.lastOrNull { it.name == "<root>" }?.finalStackDepth ?: 0,
                prunedValues = compiled.analysis.prunedValues,
                stages = StackSLStudyShader.stages(source, outline),
                knobs = knobs,
                animated = animated,
                nonFiniteFraction = (nonFinite * 10000).toInt() / 10000.0,
                words = outline.root.size,
                glslLines = compiled.glsl.lines().size
            )
            return StudyAdmission(result, linkedMapOf(
                "warnings" to result.warnings, "notes" to result.notes, "animated" to animated,
                "nonFiniteFraction" to result.nonFiniteFraction, "words" to result.words,
                "knobs" to knobs.size, "stages" to result.stages.size
            ))
        }

        private fun png(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // ---- Reading ------------------------------------------------------------------------------

    /**
     * Resume from `HEAD` if a previous session left one, and pick up the variants it was holding if
     * they were tried against the revision that is still current.
     */
    fun resume(): String {
        val id = revisions.resume()
        speculation.resume(id)
        return id
    }


    /** The checked revision's id, or `empty`. */
    fun currentId(): String = revisions.currentId()

    /** Everything a model needs before editing: source, live-edit state, history, checks, stages and knobs. */
    fun inspect(id: String = "current"): StudyReport {
        val live = revisions.liveState()
        if (revisions.currentId() == SourceStudy.EMPTY) {
            return StudyReport(SourceStudy.EMPTY, live + mapOf("message" to "No checked program yet. Make the first edit with expectedRevision=empty and this liveSourceHash.", "frame" to FRAME))
        }
        val r = revisions.revision(id)
        val liveText = live["liveSource"] as String?
        val liveCheck = liveText?.let { text ->
            try { check(text).let { mapOf("compiles" to true, "warnings" to it.value.warnings, "knobs" to it.value.knobs.size) } }
            catch (e: StudyRejection) { mapOf("compiles" to false, "problem" to e.message) }
        }
        val history = revisions.history()
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "parent" to r.parent, "description" to r.description,
            "source" to r.source,
            "liveSourceHash" to live["liveSourceHash"], "dirty" to live["dirty"], "liveSource" to liveText,
            "liveCheck" to liveCheck,
            "personAt" to afield.stdlib.BoxStudies.personAt(editorLine(), liveText ?: r.source),
            "history" to history.takeLast(24).map { mapOf("id" to it.id, "description" to it.description) },
            "otherRevisions" to (revisions.allRevisions().size - history.size).takeIf { it > 0 },
            "warnings" to r.value.warnings, "notes" to r.value.notes,
            "definitions" to r.value.definitions, "animated" to r.value.animated,
            "nonFiniteFraction" to r.value.nonFiniteFraction,
            "stages" to r.value.stages.map { mapOf("stage" to it.stage, "line" to it.line, "text" to it.text, "comment" to it.comment) },
            "knobs" to knobNames(r.source, r.value.knobs).let { names -> r.value.knobs.map { knobSummary(it, names[it.knob]) } },
            "keys" to afield.stdlib.StudyKeys.summary(directory, knobShape(r.source, r.value.knobs), r.value.knobs.associate { it.knob to it.value }),
            "pendingVariants" to speculation.pending(),
            "exports" to exports().ifEmpty { null },
            "frame" to FRAME
        ))
    }

    /**
     * One knob as one short line — `k3 L6:30 0.035 → multiply b [sway speed]` — index, where, the number as
     * typed, what reads it, and the name the person's card shows it by when it has one: a program has
     * dozens, and a list of objects was most of every Inspect.
     */
    private fun knobSummary(k: StackSLKnob, name: String? = null): String =
        "k${k.knob} L${k.line}:${k.column} ${k.token}" + (k.role.takeIf { it != "number" }?.let { " $it" } ?: "") +
            (k.takenAs?.let { " → $it" } ?: k.feeds?.let { " → $it" } ?: "") + (k.spans.size.takeIf { it > 1 }?.let { " (×$it)" } ?: "") +
            (name?.let { " [$it]" } ?: "")

    /**
     * The names the person's card shows [knobs] of [source] by ([KnobNames]), those that are known; [ask]
     * asks the fast model for the rest, calling [ready] once they are stored.
     */
    internal fun knobNames(source: String, knobs: List<StackSLKnob>, ask: Boolean = false, ready: () -> Unit = {}): Map<Int, String> =
        KnobNames.names("StackSL", ABOUT_FOR_NAMES, source, namingKnobs(source, knobs), directory, ask, ready).orEmpty()

    /** The shape of [source] that knob names and the card's keys belong to ([KnobNames.shape]). */
    internal fun knobShape(source: String, knobs: List<StackSLKnob>): String = KnobNames.shape("StackSL", source, namingKnobs(source, knobs))

    private fun namingKnobs(source: String, knobs: List<StackSLKnob>): List<KnobNames.Knob> {
        val lines = source.lines()
        return knobs.map { KnobNames.Knob(it.knob, it.line, it.token, StackSLStudyViewer.purpose(it, lines.getOrElse(it.line - 1) { "" })) }
    }

    // ---- Publishing ---------------------------------------------------------------------------

    private fun published(r: SourceRevision<StackSLCheck>, previewSeconds: List<Double>, message: String): StudyReport {
        previews.remove(r.id)
        val base = StudyReport(r.id, linkedMapOf(
            "message" to message, "revision" to r.id, "parent" to r.parent, "description" to r.description,
            "sourceHash" to SourceStudy.hash(r.source),
            "validation" to mapOf("compileSuccess" to true, "checksPassed" to true, "revision" to r.id,
                "sourceHash" to SourceStudy.hash(r.source), "scope" to "complete StackSL program, stack.auto rules, CPU reference"),
            "warnings" to r.value.warnings, "notes" to r.value.notes, "animated" to r.value.animated,
            "nonFiniteFraction" to r.value.nonFiniteFraction,
            "knobs" to "${r.value.knobs.size} (InspectStackSLStudy lists them)",
            "stages" to r.value.stages.size
        ))
        val report = try {
            val preview = observe(r.id, previewSeconds, if (!r.value.animated || rasterizer.fast) STILL_PREVIEW_SIZE else PREVIEW_SIZE, r.parent.orEmpty())
            base.copy(facts = base.facts + ("preview" to preview.facts), toolImages = preview.toolImages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base.copy(facts = base.facts + ("previewError" to "Revision ${r.id} is already active; request ObserveStackSLStudy instead of repeating the edit. ${e.message}"))
        }
        runCatching { onChange?.invoke() }
        return report
    }

    /** Publish a complete program. See [SourceStudy.edit] for the guards. */
    fun edit(expectedRevision: String, expectedSourceHash: String, source: String, description: String,
             previewSeconds: List<Double> = emptyList()): StudyReport =
        published(revisions.edit(expectedRevision, expectedSourceHash, source, description), previewSeconds, "Compiled, checked and activated")

    /** Publish exact replacements against the live source. */
    fun patch(expectedRevision: String, expectedSourceHash: String, edits: List<StudySourceEdit>, description: String,
              previewSeconds: List<Double> = emptyList()): StudyReport =
        published(revisions.patch(expectedRevision, expectedSourceHash, edits, description), previewSeconds, "Patched, checked and activated")

    /** Publish new values for knobs of the live source (knob index to value), rewritten in place. */
    fun setNumbers(expectedRevision: String, expectedSourceHash: String, numbers: Map<Int, Double>, description: String,
                   previewSeconds: List<Double> = emptyList()): StudyReport {
        if (numbers.isEmpty()) throw StudyRejection("Name at least one knob to set")
        val live = revisions.liveSource()
        if (expectedRevision != revisions.currentId() || expectedSourceHash != SourceStudy.hash(live)) {
            throw StudyRejection("Conflict: inspect again; knob numbers refer to the live source you read.")
        }
        val rewritten = try { StackSLStudyShader.setKnobs(live, numbers) } catch (e: IllegalArgumentException) { throw StudyRejection(e.message ?: "Could not set those numbers") }
        return published(revisions.edit(expectedRevision, expectedSourceHash, rewritten, description), previewSeconds, "Numbers set, checked and activated")
    }

    /** Check in whatever is in the editor now — a person's own edit — as a revision. */
    fun checkIn(description: String = "edited by hand"): StudyReport {
        val live = revisions.liveSource()
        val current = revisions.currentId()
        if (current != SourceStudy.EMPTY && revisions.revision().source == live) {
            return StudyReport(current, mapOf("message" to "Nothing to check in: the editor holds $current."))
        }
        return published(revisions.edit(current, SourceStudy.hash(live), live, description), emptyList(), "Checked in the editor's program")
    }

    /** Make an earlier revision current again, as a new revision. */
    fun restore(expectedRevision: String, expectedSourceHash: String, id: String, description: String = ""): StudyReport =
        published(revisions.restore(expectedRevision, expectedSourceHash, id, description), emptyList(), "Restored as a new revision")

    // ---- Looking ------------------------------------------------------------------------------

    private fun sizeIn(size: Int, max: Int = 512) = size.coerceIn(24, max)

    private fun framesFor(r: SourceRevision<StackSLCheck>, seconds: List<Double>): List<Double> {
        val requested = seconds.filter { it.isFinite() }.distinct().take(8)
        return when {
            !r.value.animated -> listOf(requested.firstOrNull() ?: StackSLStudyShader.REFERENCE_TIME)
            requested.isEmpty() -> listOf(0.0, 3.5, 7.0)
            else -> requested
        }
    }

    private fun save(name: String, bytes: ByteArray): File {
        val folder = File(directory, "observations").also { it.mkdirs() }
        return File(folder, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.writeBytes(bytes) }
    }

    /**
     * Frames of a revision at [seconds] (a still program is rendered once), with measurements, and
     * optionally the same frames of [compareTo] and a difference map beneath.
     */
    fun observe(id: String = "current", seconds: List<Double> = emptyList(), size: Int = 0, compareTo: String = ""): StudyReport {
        val r = revisions.revision(id)
        val other = compareTo.takeIf { it.isNotBlank() }?.let { revisions.revision(it) }
        // 0 asks for the default: a still's single frame gets the pixels several animated frames would share.
        val dim = sizeIn(if (size > 0) size else if (r.value.animated && !rasterizer.fast) 160 else 256, 256)
        val clock = System.nanoTime()
        val times = framesFor(r, seconds).let { t -> if (other != null && other.value.animated && !r.value.animated) framesFor(other, seconds) else t }
        val epoch = revisions.epoch()
        if (revisions.isCancelled(epoch)) throw CancellationException()
        val frames = frames(r.source, dim, times)
        val before = other?.let { o -> frames(o.source, dim, times) }
        val stats = frames.mapIndexed { i, f -> StudyEvidence.stats(f, times[i]) }
        val rows = mutableListOf(frames.mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), "t = ${StudyEvidence.format(times[i])} s",
            "luma ${StudyEvidence.format(stats[i].luma)} · clip ${pct(stats[i].clippedAbove)}") })
        val labels = mutableListOf(r.id)
        val differences = before?.let { b ->
            val o = other
            rows += b.mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), "t = ${StudyEvidence.format(times[i])} s", o.id) }
            labels += o.id
            val d = b.indices.map { i -> StudyEvidence.difference(b[i], frames[i], null, false).first }
            // A difference map of two unrelated pictures is solid white and says nothing a glance at the rows does not.
            if (d.any { it.mean <= 40.0 }) {
                rows += b.indices.map { i -> StudyEvidence.differenceTile(b[i], frames[i], "difference", "max ${d[i].max.toInt()} · mean ${StudyEvidence.format(d[i].mean)} levels") }
                labels += "difference\nwhite ≥ 32 levels"
            }
            d.mapIndexed { i, diff -> mapOf("seconds" to times[i], "difference" to diff) }
        }
        val motion = frames.zipWithNext().mapIndexed { i, (a, b) -> StudyEvidence.motion(a, b, times[i], times[i + 1]) }
        val grain = stats.any { f -> f.featureSize != null && f.featureSize * dim < 2.5 }
        val bytes = StudyEvidence.sheet(
            "StackSL study · ${r.id}", r.description.take(140), rows, if (dim < 160) dim * 2 else dim,
            labels.map { it }, if (!r.value.animated) "This program does not read time: one frame is all there is." else "")
        val file = save("${r.id}-${times.joinToString("_")}-${dim}${other?.let { "-vs-${it.id}" }.orEmpty()}.png", bytes)
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "comparedTo" to other?.id, "seconds" to times, "size" to dim, "animated" to r.value.animated,
            "frames" to stats, "motion" to motion.ifEmpty { null }, "differences" to differences,
            "renderSeconds" to cost(clock, (frames.size + (before?.size ?: 0)) * dim * dim),
            "grain" to if (grain) "Features are about one pixel wide at this size: the picture varies faster than it can be sampled. Often the per-pixel hash in input.z reaching a noise coordinate (use uv, or zero z), or a spatial scale too large to resolve." else null,
            "warnings" to r.value.warnings.ifEmpty { null }, "imageFile" to file.absolutePath,
            "method" to "CPU reference render of the study shader; statistics from the raw program output before the clamp."
        ), listOf(ToolImage(png(bytes), "${r.id}: frames at ${times.joinToString { StudyEvidence.format(it) }} s" +
            (other?.let { "; second row ${it.id}, third row the difference" } ?: ""))))
    }

    /** Seconds since [clock] and the CPU reference's throughput — so a caller can size the next request. */
    private fun cost(clock: Long, pixels: Int): Map<String, Any> {
        val seconds = (System.nanoTime() - clock) / 1e9
        val gpu = rasterizer.description.startsWith("GPU")
        return linkedMapOf("seconds" to (seconds * 1000).toInt() / 1000.0, "pixels" to pixels, "renderedBy" to rasterizer.description,
            "note" to if (gpu) "Rendered on the GPU: a frame costs about two display frames at any size." else
                "CPU reference: cost grows with size squared and the program's noise octaves; launch the window box to render on the GPU.")
    }

    private fun pct(v: Double) = "${(v * 100).let { if (it < 10) "%.1f".format(java.util.Locale.US, it) else "%.0f".format(java.util.Locale.US, it) }}%"

    /**
     * What each stage leaves on top of the stack: one tile per line (or per word, for a one-line
     * program), shown as colour when it already is one and stretched per channel into view when not.
     * With [probeU]/[probeV], also the top four stack values at that point after every root word.
     */
    fun trace(id: String = "current", seconds: Double = StackSLStudyShader.REFERENCE_TIME, size: Int = 0,
              probeU: Double? = null, probeV: Double? = null): StudyReport {
        val r = revisions.revision(id)
        val outline = StackSLCompiler.outline(r.source)
        val stages = r.value.stages.let { all -> if (all.size <= 24) all else (all.indices.filter { it % ((all.size + 22) / 23) == 0 } + all.lastIndex).distinct().map { all[it] } }
        val dim = sizeIn(if (size > 0) size else if (rasterizer.fast) 128 else 72, 160)
        val clock = System.nanoTime()
        val tiles = mutableListOf<StudyEvidence.Tile>()
        val facts = stages.map { stage ->
            val prefix = outline.rootPrefix(r.source, stage.wordsThrough)
            val compiled = StackSLCpu.compile(prefix)
            val depth = compiled.analysis.functions.lastOrNull { it.name == "<root>" }?.finalStackDepth
            val stored = outline.storedMark(stage.wordsThrough)
            if (!compiled.ok) {
                tiles += StudyEvidence.tile(null, "${stage.granularity} ${stage.line}", stage.text, "does not compile on its own")
                mapOf("stage" to stage.stage, "line" to stage.line, "text" to stage.text, "compiles" to false)
            } else {
                // A line that ends by storing (>name) is shown by the value it stored.
                val raw = frame(outline.stageProgram(r.source, stage.wordsThrough), dim, seconds)
                val label = "${if (stage.granularity == "line") "L" else "w"}${stage.line}${if (stage.granularity == "word") ":${stage.stage}" else ""}" +
                    (stored?.let { " → $it" } ?: " · depth $depth")
                val (tile, normalized) = StudyEvidence.normalizedTile(raw, label, stage.text)
                tiles += tile
                val ranges = (0 until 4).map { c ->
                    val values = (0 until dim * dim).map { raw.rgba[it * 4 + c] }.filter { it.isFinite() }
                    if (values.isEmpty()) null else listOf(values.min().toDouble().round4(), values.max().toDouble().round4())
                }
                linkedMapOf("stage" to stage.stage, "line" to stage.line, "text" to stage.text, "comment" to stage.comment, "depth" to depth,
                    "shows" to (stored?.let { "the value stored in $it" } ?: "the top of the stack"),
                    "range" to mapOf("x" to ranges[0], "y" to ranges[1], "z" to ranges[2], "w" to ranges[3]),
                    "shownAs" to if (normalized) "stretched per channel (values leave 0..1)" else "colour",
                    "constant" to ranges.take(3).all { it == null || it[1] - it[0] < 1e-6 },
                    "splat" to (0 until dim * dim).all { p -> raw.rgba[p * 4] == raw.rgba[p * 4 + 1] && raw.rgba[p * 4 + 1] == raw.rgba[p * 4 + 2] })
            }
        }
        val probe = if (probeU != null && probeV != null) probe(r.source, outline, probeU, probeV, seconds) else null
        val columns = 6
        val bytes = StudyEvidence.sheet("StackSL trace · ${r.id} · t = ${StudyEvidence.format(seconds)} s",
            "Top of the stack after each ${stages.firstOrNull()?.granularity ?: "stage"} (→ name: the value a store line put in that mark); depth counts the program's input at the bottom.",
            tiles.chunked(columns), 150,
            footer = "Stretched tiles map each channel's own min..max to 0..1; colour tiles are shown as the frame would draw them.")
        val file = save("${r.id}-trace-${seconds}.png", bytes)
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "seconds" to seconds, "size" to dim, "stages" to facts,
            "probe" to probe, "imageFile" to file.absolutePath, "renderSeconds" to cost(clock, stages.size * dim * dim),
            "method" to "Each tile renders the program cut after that stage; definitions are kept whole."),
            listOf(ToolImage(png(bytes), "${r.id}: what each stage leaves on top of the stack at ${StudyEvidence.format(seconds)} s")))
    }

    private fun Double.round4() = (this * 10000).let { kotlin.math.round(it) } / 10000

    private fun probe(source: String, outline: afield.stacksl.StackSLOutline, u: Double, v: Double, seconds: Double): Map<String, Any?> {
        require(u in 0.0..1.0 && v in 0.0..1.0) { "probeU and probeV are uv coordinates in 0..1" }
        val words = outline.root.take(64)
        val steps = words.indices.map { i ->
            val prefix = outline.rootPrefix(source, i + 1)
            val compiled = StackSLCpu.compile(prefix)
            val depth = compiled.analysis.functions.lastOrNull { it.name == "<root>" }?.finalStackDepth ?: 0
            val stack = if (!compiled.ok) null else (0 until minOf(depth, 4)).map { j ->
                StackSLStudyShader.sample(prefix + " drop".repeat(j), u, v, seconds).map { (it * 10000).toInt() / 10000.0 }
            }
            linkedMapOf("word" to words[i].text, "at" to "${words[i].location.line}:${words[i].location.column}", "depth" to depth, "stackTopFirst" to stack)
        }
        return linkedMapOf("u" to u, "v" to v, "seconds" to seconds, "words" to steps,
            "truncated" to (outline.root.size > words.size).takeIf { it })
    }

    /** The knobs of a revision, and — unless [sensitivity] is false — how many levels a small nudge to each moves the frame. */
    fun knobs(id: String = "current", seconds: Double = StackSLStudyShader.REFERENCE_TIME, sensitivity: Boolean = true): StudyReport {
        val r = revisions.revision(id)
        val base = if (sensitivity) frame(r.source, 48, seconds) else null
        val epoch = revisions.epoch()
        val names = knobNames(r.source, r.value.knobs)
        val rows = r.value.knobs.mapIndexed { i, k ->
            val summary: Map<String, Any?> = linkedMapOf("knob" to knobSummary(k, names[k.knob]), "context" to k.context)
            if (base == null || i >= 40) return@mapIndexed summary
            if (revisions.isCancelled(epoch)) throw CancellationException()
            val step = if (abs(k.value) > 1e-3) abs(k.value) * 0.1 else 0.05
            val effect = try {
                val nudged = frame(StackSLStudyShader.setKnobs(r.source, mapOf(k.knob to k.value + step)), 48, seconds)
                val d = StudyEvidence.difference(base, nudged, null, false).first
                mapOf("nudge" to step.round4(), "meanLevels" to d.mean, "maxLevels" to d.max, "changedFraction" to d.changedFraction,
                    "reading" to when { d.max == 0.0 -> "no visible effect here"; d.mean < 0.5 -> "subtle"; d.mean < 4 -> "moderate"; else -> "strong" })
            } catch (e: IllegalArgumentException) {
                mapOf("nudge" to step.round4(), "error" to e.message)
            }
            summary + ("sensitivity" to effect)
        }
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "seconds" to seconds, "knobs" to rows,
            "method" to "Each knob nudged by 10% of its value (0.05 when near zero), rendered at 48x48, compared in 8-bit levels. A knob with no visible effect may feed a pruned value, sit behind a threshold, or matter only at another time."))
    }

    /** One knob at several values, side by side. Nothing is published. */
    fun sweep(id: String = "current", knob: Int, values: List<Double>, seconds: Double = StackSLStudyShader.REFERENCE_TIME, size: Int = 0): StudyReport {
        val r = revisions.revision(id)
        val k = r.value.knobs.getOrNull(knob) ?: throw StudyRejection("No knob $knob; ${r.id} has ${r.value.knobs.size}")
        val dim = sizeIn(if (size > 0) size else if (r.value.animated && !rasterizer.fast) 112 else 160, 192)
        val clock = System.nanoTime()
        val results = StudyCompare.sweep(values) { value -> frame(StackSLStudyShader.setKnobs(r.source, mapOf(knob to value)), dim, seconds) }
            .map { Triple(it.value, it.raster, it.error) }
        val tiles = results.map { (value, raw, error) ->
            val s = raw?.let { StudyEvidence.stats(it, seconds) }
            StudyEvidence.tile(raw?.let(StudyEvidence::displayed), StackSLStudyShader.formatKnob(value, "$value") +
                if (abs(value - k.value) < 1e-12) "  (now)" else "", s?.let { "luma ${StudyEvidence.format(it.luma)} · detail ${StudyEvidence.format(it.detail)}" } ?: "", error)
        }
        val bytes = StudyEvidence.sheet("StackSL sweep · ${r.id} · knob $knob '${k.token}' at ${k.line}:${k.column}",
            "${k.role}${k.feeds?.let { " feeding $it" } ?: ""} — ${k.context}", tiles.chunked(5), maxOf(dim, 150))
        val file = save("${r.id}-sweep-$knob-${values.joinToString("_")}.png", bytes)
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "knob" to knobSummary(k), "seconds" to seconds,
            "values" to results.map { (value, raw, error) -> linkedMapOf("value" to value, "error" to error,
                "stats" to raw?.let { StudyEvidence.stats(it, seconds) }?.let { mapOf("luma" to it.luma, "detail" to it.detail, "featureSize" to it.featureSize, "clippedAbove" to it.clippedAbove, "palette" to it.palette.take(3)) }) },
            "imageFile" to file.absolutePath, "published" to false, "renderSeconds" to cost(clock, values.size * dim * dim)),
            listOf(ToolImage(png(bytes), "${r.id}: knob $knob ('${k.token}') at ${values.joinToString()}")))
    }

    /** A candidate for [tryVariants]: a complete program, or exact edits to the live source. */
    data class Variant(val label: String, val source: String = "", val edits: List<StudySourceEdit> = emptyList(), val note: String = "")

    /**
     * Check and render several candidates beside the current program, and hold them — unpublished —
     * for [adoptVariant]. A rejected candidate shows its reason in its row.
     */
    fun tryVariants(variants: List<Variant>, seconds: List<Double> = emptyList(), size: Int = 0): StudyReport {
        StudyVariants.requireLabels(variants.map { it.label })
        val current = revisions.revision()
        val live = revisions.liveSource()
        val times = seconds.filter { it.isFinite() }.distinct().take(4).ifEmpty { listOf(StackSLStudyShader.REFERENCE_TIME) }
        // One time shows each candidate once, side by side, so it can afford a larger square.
        val dim = sizeIn(if (size > 0) size else if (times.size == 1 || rasterizer.fast) 192 else 112, 192)
        val clock = System.nanoTime()
        val baseFrames = frames(current.source, dim, times)
        val epoch = revisions.epoch()
        val trial = speculation.tryAll(variants.map { StudyVariants.Candidate(it.label, it.note, it.source, it.edits) }, live,
            revisions::check, { revisions.isCancelled(epoch) }, { frames(it, dim, times) },
            { "t = ${StudyEvidence.format(times[it])} s" }) { value, drawn ->
            val diff = StudyEvidence.difference(baseFrames[0], drawn[0], null, false).first
            linkedMapOf("warnings" to value.warnings.ifEmpty { null }, "animated" to value.animated, "knobs" to value.knobs.size,
                "differenceFromCurrent" to mapOf("meanLevels" to diff.mean, "changedFraction" to diff.changedFraction),
                "frame" to StudyEvidence.stats(drawn[0], times[0]).let { mapOf("luma" to it.luma, "detail" to it.detail, "colourfulness" to it.colourfulness, "clippedAbove" to it.clippedAbove, "palette" to it.palette.take(3)) })
        }
        val rows = listOf(baseFrames.mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), "t = ${StudyEvidence.format(times[i])} s", "current") }) + trial.rows
        val labels = listOf("${current.id}\n(current)") + trial.labels
        val single = times.size == 1
        val sheetRows = if (!single) rows else listOf(rows.mapIndexed { i, row ->
            val t = row.first()
            StudyEvidence.Tile(t.argb, t.width, t.height, labels[i].substringBefore('\n').let { if (i == 0) "current" else it },
                labels[i].substringAfter('\n', "").ifBlank { if (i == 0) current.id else "" }, t.error)
        })
        val bytes = StudyEvidence.sheet("StackSL variants · against ${current.id}" + if (single) " · t = ${StudyEvidence.format(times[0])} s" else "",
            "Unpublished candidates; adopt one with AdoptStackSLVariant, or ask for more.",
            if (single) sheetRows.flatten().chunked(5) else sheetRows, if (dim < 150) dim * 2 else dim, if (single) emptyList() else labels)
        val batch = speculation.hold(current.id, SourceStudy.hash(live), trial.held, bytes)
        runCatching { onChange?.invoke() }
        return StudyReport(current.id, linkedMapOf("against" to current.id, "liveDirty" to (live != current.source), "seconds" to times,
            "variants" to trial.facts, "held" to trial.held.map { it.label }, "imageFile" to File(batch, "sheet.png").absolutePath, "published" to false,
            "renderSeconds" to cost(clock, (1 + variants.size) * times.size * dim * dim)),
            listOf(ToolImage(png(bytes), "Variants against ${current.id}: first row current, then one row per candidate")))
    }

    /** The candidates [tryVariants] is holding, label to source — what the human view offers to adopt. */
    fun heldVariants(): List<Triple<String, String, String>> = speculation.held()

    /** Publish a held candidate. */
    fun adoptVariant(expectedRevision: String, expectedSourceHash: String, label: String, description: String = ""): StudyReport =
        speculation.adopt(revisions, expectedRevision, expectedSourceHash, label, description) { r -> published(r, emptyList(), "Adopted variant $label") }

    /**
     * Test what an edit preserved. `mode` `"rgb"` compares displayed colour, `"luma"` brightness only,
     * `"stage"` what the two programs leave on the stack after [beforeStage] and [afterStage].
     * [region] (`u0, v0, u1, v1`) is where change is allowed; everything else is held to one level.
     */
    fun compare(before: String, after: String = "current", seconds: List<Double> = emptyList(), size: Int = 0, mode: String = "rgb",
                region: List<Double>? = null, beforeStage: Int? = null, afterStage: Int? = null): StudyReport {
        val a = revisions.revision(before); val b = revisions.revision(after)
        val dim = sizeIn(if (size > 0) size else if (rasterizer.fast) 192 else 112, 192)
        val clock = System.nanoTime()
        val times = seconds.filter { it.isFinite() }.distinct().take(6).ifEmpty {
            if (a.value.animated || b.value.animated) listOf(0.0, StackSLStudyShader.REFERENCE_TIME, 5.0) else listOf(StackSLStudyShader.REFERENCE_TIME)
        }
        fun program(r: SourceRevision<StackSLCheck>, stage: Int?): String = if (stage == null) r.source else {
            val s = r.value.stages.firstOrNull { it.stage == stage } ?: throw StudyRejection("${r.id} has no stage $stage (it has ${r.value.stages.size})")
            StackSLCompiler.outline(r.source).stageProgram(r.source, s.wordsThrough)
        }
        if (mode == "stage" && (beforeStage == null || afterStage == null)) throw StudyRejection("mode=stage needs beforeStage and afterStage")
        if (mode !in setOf("rgb", "luma", "stage")) throw StudyRejection("mode is rgb, luma or stage")
        val pa = program(a, beforeStage.takeIf { mode == "stage" }); val pb = program(b, afterStage.takeIf { mode == "stage" })
        val allA = frames(pa, dim, times); val allB = frames(pb, dim, times)
        val px = StudyCompare.pixels(allA, allB, times, a.id, b.id, region, mode, { "t = ${StudyEvidence.format(it)} s" },
            captions = if (mode != "stage") null else { t -> val w = "t = ${StudyEvidence.format(t)} s"; "stage $beforeStage · $w" to "stage $afterStage · $w" })
        val rows = px.rows; val results = px.results
        val tolerance = px.tolerance
        val preserved = px.preserved
        // A line that ends in a store (`>edge`) leaves only the program's input on top, and two
        // programs always agree about their input: such a comparison is true and says nothing.
        // The input is what the root with no words returns; its definitions are the ones that compiled.
        fun inputOnly(r: SourceRevision<StackSLCheck>, stage: List<CpuRaster>): Boolean {
            val input = frames(StackSLCompiler.outline(r.source).rootPrefix(r.source, 0), dim, times)
            return stage.indices.all { StudyEvidence.rawDifference(stage[it], input[it], null).first.max <= tolerance }
        }
        val programInput = if (mode == "stage") listOf(inputOnly(a, allA), inputOnly(b, allB)) else null
        val bytes = StudyEvidence.sheet("StackSL compare · ${a.id} → ${b.id} · $mode",
            (region?.let { "Change allowed inside uv [${it.joinToString()}]; " } ?: "No change allowed; ") + "held to ${if (mode == "stage") "1e-6" else "one 8-bit level"}.",
            rows, if (dim < 150) dim * 2 else dim)
        val file = save("${a.id}-vs-${b.id}-$mode.png", bytes)
        return StudyReport(b.id, linkedMapOf("before" to a.id, "after" to b.id, "mode" to mode, "region" to region,
            "beforeStage" to beforeStage, "afterStage" to afterStage, "size" to dim, "results" to results,
            "preservedOutsideRegion" to preserved, "tolerance" to tolerance,
            "stageIsProgramInput" to programInput,
            "vacuous" to programInput?.all { it }?.takeIf { it }?.let {
                "Both stages leave only the program's input on top (each line ends in a store), so they agree whatever the programs do. Compare a stage that leaves a value; TraceStackSLStudy shows which do."
            },
            "imageFile" to file.absolutePath,
            "renderSeconds" to cost(clock, 2 * times.size * dim * dim),
            "method" to "Both rendered by ${rasterizer.description} at the same times and size; sampled at pixel centres, so a feature narrower than a pixel can be missed."),
            listOf(ToolImage(png(bytes), "${a.id} → ${b.id}: before | after | difference, per time")))
    }

    // ---- Leaving ------------------------------------------------------------------------------

    private fun exportable(id: String, expectedSourceHash: String): SourceRevision<StackSLCheck> {
        val r = revisions.revision(id)
        if (SourceStudy.hash(r.source) != expectedSourceHash) throw StudyRejection("Export source hash differs from ${r.id}'s")
        if (r.id == revisions.currentId() && revisions.liveSource() != r.source) {
            throw StudyRejection("The editor holds an unchecked edit; check it in or reconcile it before exporting ${r.id}.")
        }
        return r
    }

    /** One export running or finished in the background — see [startExport]. */
    data class ExportJob(val id: String, val kind: String, val path: String, val revision: String, val frames: Int,
                         val estimatedSeconds: Double) {
        @Volatile var framesDone: Int = 0
        @Volatile var status: String = "queued"
        @Volatile var message: String? = null
        @Volatile var elapsedSeconds: Double = 0.0
        @Volatile var cancelled: Boolean = false
    }

    private val exportJobs = java.util.concurrent.CopyOnWriteArrayList<ExportJob>()
    private val exportWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "stacksl-study-export").apply { isDaemon = true } }
    // Exports render in their own pool, a little smaller than the machine, so a tool call made while
    // a clip renders still gets cores.
    private val exportPool = java.util.concurrent.ForkJoinPool(maxOf(1, Runtime.getRuntime().availableProcessors() - 2))

    /** Every export this session started, newest last. */
    fun exports(): List<Map<String, Any?>> = exportJobs.map {
        linkedMapOf("id" to it.id, "kind" to it.kind, "path" to it.path, "revision" to it.revision, "status" to it.status,
            "frames" to "${it.framesDone}/${it.frames}", "estimatedSeconds" to it.estimatedSeconds,
            "elapsedSeconds" to it.elapsedSeconds, "message" to it.message)
    }

    /**
     * Start exporting a checked revision in the background and return at once with an estimate —
     * `png` (one frame, at [seconds]) or `mp4` (a clip [duration] long, starting at [seconds]). The CPU reference renders a noise-heavy program at a few
     * thousand pixels a second, so a clip can take minutes; progress appears in [exports], in
     * [inspect], and in the shader card.
     */
    fun startExport(id: String, expectedSourceHash: String, file: File, kind: String, size: Int = 0, seconds: Double = StackSLStudyShader.REFERENCE_TIME,
                    duration: Double = 6.0, fps: Int = 24, overwrite: Boolean = false, maxMinutes: Double = 10.0): StudyReport {
        val r = exportable(id, expectedSourceHash)
        if (kind !in setOf("png", "mp4")) throw StudyRejection("kind is png or mp4")
        if (file.exists() && !overwrite) throw StudyRejection("${file.name} exists; ask for overwrite to replace it")
        if (kind == "mp4" && ffmpeg() == null) throw StudyRejection("No ffmpeg found; install it to export video")
        if (exportJobs.any { it.path == file.absolutePath && it.status in setOf("queued", "running") }) throw StudyRejection("${file.name} is already being exported")
        val requested = if (size > 0) size else if (kind == "png") 1024 else 256
        val dim = if (kind == "png") requested.coerceIn(16, 2048) else (requested.coerceIn(32, 512) / 2) * 2
        val frames = if (kind == "png") 1 else (duration * fps).toInt()
        if (kind == "mp4" && frames !in 2..480) throw StudyRejection("A clip is 2..480 frames (seconds × fps)")
        // Time one real frame. On the GPU a frame costs about the same at any size, so it is timed at
        // the export's own size; on the CPU the cost is per pixel, so a small probe is scaled up.
        val probeDim = if (rasterizer === CpuStackSLRasterizer) 48 else dim
        // The first GPU frame builds the pipeline; time the second, which is what every export frame costs.
        if (rasterizer !== CpuStackSLRasterizer) frame(r.source, probeDim, seconds)
        val probeStart = System.nanoTime()
        frame(r.source, probeDim, seconds)
        val probeSeconds = (System.nanoTime() - probeStart) / 1e9
        val onGpu = rasterizer.description.startsWith("GPU")
        val perPixel = probeSeconds / (probeDim * probeDim)
        val estimate = ((if (onGpu) probeSeconds * frames + 0.05 * frames else perPixel * dim * dim * frames) * 10).toInt() / 10.0
        if (estimate > maxMinutes * 60) {
            // Offer the largest square that fits the budget at this length and rate, rather than just "no".
            val fits = if (onGpu) dim else (Math.sqrt(maxMinutes * 60 / (perPixel * frames)).toInt() / 2) * 2
            throw StudyRejection("That export would take about ${(estimate / 60).toInt()} minutes on the CPU reference, over the ${maxMinutes.toInt()}-minute budget. " +
                (if (fits >= 32) "At ${fits}px it would fit" + (if (kind == "mp4") " at $fps fps for ${duration}s" else "") + "; " else "") +
                "a shorter clip or lower frame rate also helps. Raise maxMinutes only if the person agrees to wait.",
                mapOf("estimatedSeconds" to estimate, "sizeThatFits" to fits))
        }
        val job = ExportJob("x${exportJobs.size + 1}", kind, file.absolutePath, r.id, frames, estimate)
        exportJobs += job
        exportWorker.execute {
            val clock = System.nanoTime()
            job.status = "running"
            try {
                exportPool.submit {
                    if (kind == "png") exportPng(r.id, expectedSourceHash, file, dim, seconds, overwrite)
                    else exportVideo(r.id, expectedSourceHash, file, dim, duration, fps, seconds, overwrite) { done ->
                        job.framesDone = done
                        if (job.cancelled) throw CancellationException("cancelled")
                    }
                }.get()
                job.framesDone = frames
                job.status = "done"
                job.message = "${file.length()} bytes"
            } catch (e: Exception) {
                job.status = if (job.cancelled) "cancelled" else "failed"
                job.message = if (job.cancelled) null else (e.cause ?: e).message
            } finally {
                job.elapsedSeconds = ((System.nanoTime() - clock) / 1e8).toInt() / 10.0
                runCatching { onChange?.invoke() }
            }
        }
        runCatching { onChange?.invoke() }
        return StudyReport(r.id, linkedMapOf("message" to "Export started in the background", "job" to job.id, "kind" to kind,
            "path" to file.absolutePath, "size" to dim, "frames" to frames,
            (if (kind == "png") "atSeconds" else "fromSeconds") to seconds, "durationSeconds" to (if (kind == "png") null else duration),
            "estimatedSeconds" to estimate,
            "note" to "The file appears when the job finishes; its progress is in InspectStackSLStudy's exports and in the shader card. Nothing else waits on it."))
    }

    /** Stop a running export after its current frame. */
    fun cancelExport(jobId: String): StudyReport {
        val job = exportJobs.firstOrNull { it.id == jobId } ?: throw StudyRejection("No export $jobId")
        if (job.status !in setOf("queued", "running")) return StudyReport(job.revision, mapOf("message" to "Export $jobId is already ${job.status}"))
        job.cancelled = true
        return StudyReport(job.revision, mapOf("message" to "Export $jobId will stop after its current frame"))
    }

    /** Write one frame of a checked revision as a PNG. */
    fun exportPng(id: String, expectedSourceHash: String, file: File, size: Int = 1024, seconds: Double = StackSLStudyShader.REFERENCE_TIME, overwrite: Boolean = false): StudyReport {
        val r = exportable(id, expectedSourceHash)
        if (file.exists() && !overwrite) throw StudyRejection("${file.name} exists; ask for overwrite to replace it")
        val dim = size.coerceIn(16, 2048)
        val bytes = StudyEvidence.presented(frame(r.source, dim, seconds)).toPngBytes()
        file.absoluteFile.parentFile.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".stacksl-export-", ".png")
        Files.write(temp, bytes)
        Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return StudyReport(r.id, linkedMapOf("message" to "Exported", "path" to file.absolutePath, "size" to dim, "seconds" to seconds,
            "bytes" to bytes.size, "sha256" to sha(bytes)))
    }

    /** Where ffmpeg is, or null. */
    fun ffmpeg(): File? = (listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").map(::File) +
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "ffmpeg") }).firstOrNull { it.canExecute() }

    /** Render [seconds] of a checked revision at [fps] and encode an H.264 MP4 with ffmpeg. */
    fun exportVideo(id: String, expectedSourceHash: String, file: File, size: Int = 256, seconds: Double = 6.0, fps: Int = 24,
                    start: Double = 0.0, overwrite: Boolean = false, progress: (Int) -> Unit = {}): StudyReport {
        val r = exportable(id, expectedSourceHash)
        if (file.exists() && !overwrite) throw StudyRejection("${file.name} exists; ask for overwrite to replace it")
        val encoder = ffmpeg() ?: throw StudyRejection("No ffmpeg found; install it to export video")
        val dim = (size.coerceIn(32, 512) / 2) * 2
        val frames = (seconds * fps).toInt()
        if (frames !in 2..480) throw StudyRejection("A clip is 2..480 frames (seconds × fps)")
        val folder = Files.createTempDirectory("stacksl-frames").toFile()
        val epoch = revisions.epoch()
        try {
            rasterizer.render(r.source, dim, List(frames) { start + it.toDouble() / fps }) { i, raw ->
                if (revisions.isCancelled(epoch)) throw CancellationException("Export cancelled")
                File(folder, "%05d.png".format(i)).writeBytes(StudyEvidence.presented(raw).toPngBytes())
                progress(i + 1)
            }
            file.absoluteFile.parentFile.mkdirs()
            val temp = File(file.absoluteFile.parentFile, ".stacksl-export-${System.nanoTime()}.mp4")
            val process = ProcessBuilder(encoder.path, "-y", "-loglevel", "error", "-framerate", fps.toString(), "-i", File(folder, "%05d.png").path,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", temp.path).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0 || !temp.isFile) {
                temp.delete(); throw StudyRejection("ffmpeg failed: ${log.take(600)}")
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return StudyReport(r.id, linkedMapOf("message" to "Exported", "path" to file.absolutePath, "size" to dim, "frames" to frames,
                "fps" to fps, "start" to start, "seconds" to seconds, "bytes" to file.length()))
        } finally { folder.deleteRecursively() }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A cached PNG of a revision at [size] — what the human view shows for history and the evidence still. */
    fun previewPng(id: String = "current", size: Int = 192, seconds: Double = StackSLStudyShader.REFERENCE_TIME, parallel: Boolean = true): ByteArray {
        val r = revisions.revision(id)
        return previews.getOrPut("${r.id}@$size@$seconds") {
            val folder = File(directory, "thumbnails").also { it.mkdirs() }
            val file = File(folder, "${r.id}-$size-$seconds.png")
            if (file.isFile) file.readBytes()
            else StudyEvidence.presented(frame(r.source, size, seconds, parallel)).toPngBytes().also { file.writeBytes(it) }
        }
    }
}

/** What the check found about an admitted program. */
data class StackSLCheck(
    /** The compiler's warnings — each one is worth reading; `reusing wrapped stack values` is almost always a mistake. */
    val warnings: List<String>,
    val notes: List<String>,
    val definitions: List<StackSLDefinitionReport>,
    /** Stack depth at the end of the root; it counts the program's input, so a simple program ends at 2. */
    val rootFinalDepth: Int,
    val prunedValues: Int,
    val stages: List<StackSLStage>,
    val knobs: List<StackSLKnob>,
    /** Whether frames differ over time — whether the program reads `t`/`time` in a way that shows. */
    val animated: Boolean,
    /** Fraction of pixels whose colour is NaN or infinite at the reference time. */
    val nonFiniteFraction: Double,
    val words: Int,
    val glslLines: Int
)

/** One knob to set, by its index in the live source's knob list, and the value to write there. */
data class StackSLNumberEdit(val knob: Int, val value: Double)

/** A definition's stack bookkeeping: a called definition should end at depth 1. */
data class StackSLDefinitionReport(val name: String, val finalDepth: Int, val maxDepth: Int, val pruned: Int)
