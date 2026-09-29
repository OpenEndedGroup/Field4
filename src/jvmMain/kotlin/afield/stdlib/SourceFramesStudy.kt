@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.ai.core.ToolImage
import afield.stacksl.cpu.CpuRaster
import java.io.File
import java.util.Base64
import java.util.concurrent.CancellationException

/**
 * A study of any text that draws, built entirely from the shared pieces — [SourceStudy] for
 * revisions, [StudyFrames] for pictures, [StudyEvidence] for measurements and sheets,
 * [StudyCompare] and [StudyVariants] for comparing and speculating — so a document can have a study as
 * capable as the library's without writing one. Hand it to [Box.registerStudyTools].
 *
 * The source is checked by drawing one small probe frame (and by [check], if given): a source that
 * does not draw is refused and nothing is published. [knobs] and [withNumbers] are the language's
 * numbers — [StudyKnobs.plainNumbers] and [StudyKnobs.withPlainNumbers] suit any text whose numbers
 * are written plainly. Frames are [aspect] wide for each unit of height.
 *
 * ```kotlin
 * val swatch = sourceFramesStudy("swatch", studyFrames("CPU painter") { source: String, w: Int, h: Int, t: Double -> paint(source, w, h, t) })
 * registerStudyTools("Swatch", swatch, about = "The source is three numbers, red green blue, each 0..1.")
 * ```
 */
class SourceFramesStudy(
    val directory: File,
    extension: String,
    readSource: () -> String,
    replaceSource: (expected: String, replacement: String) -> Boolean,
    val frames: StudyFrames<String>,
    private val knobs: (String) -> List<StudyKnob> = StudyKnobs::plainNumbers,
    private val withNumbers: (String, Map<Int, Double>) -> String = StudyKnobs::withPlainNumbers,
    private val check: (String) -> Unit = {},
    private val title: String = "Study",
    private val aspect: Double = 1.0,
    private val defaultSeconds: List<Double> = listOf(0.0),
) : LiveStudy {

    /** The revision history this study publishes through. */
    val revisions: SourceStudy<Unit> = SourceStudy(directory, extension, readSource, replaceSource, { source, _ -> admit(source) })

    private val speculation = StudyVariants(directory, extension)

    init {
        revisions.resume()
        speculation.resume(revisions.currentId())
    }

    private fun admit(source: String): StudyAdmission<Unit> {
        try {
            check(source)
            frames.renderAll(source, dims(24).first, dims(24).second, listOf(defaultSeconds.first()))
        } catch (e: StudyRejection) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StudyRejection("That source does not draw: ${e.message ?: e::class.java.simpleName}")
        }
        return StudyAdmission(Unit, linkedMapOf("knobs" to runCatching { knobs(source).size }.getOrNull()))
    }

    private fun dims(long: Int): Pair<Int, Int> =
        if (aspect >= 1) long to maxOf(1, (long / aspect).toInt()) else maxOf(1, (long * aspect).toInt()) to long

    private fun draw(source: String, long: Int, seconds: List<Double>): List<CpuRaster> {
        val (w, h) = dims(long)
        return frames.renderAll(source, w, h, seconds)
    }

    private fun times(seconds: List<Double>) = seconds.filter { it.isFinite() }.distinct().take(8).ifEmpty { defaultSeconds }
    private fun sizeOf(q: Any, default: Int) = ((q as? StudyRequest.Sized)?.size?.takeIf { it > 0 } ?: default).coerceIn(16, 512)
    private fun label(t: Double) = "t = ${StudyEvidence.format(t)} s"
    private fun png(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    private fun save(name: String, bytes: ByteArray): File =
        File(File(directory, "observations").also { it.mkdirs() }, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.writeBytes(bytes) }

    private fun knobLine(k: StudyKnob) = "k${k.index} L${k.line} ${k.typed}" + k.operand.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()

    override fun inspect(q: StudyRequest.Inspect): StudyReport {
        val live = revisions.liveState()
        if (revisions.currentId() == SourceStudy.EMPTY) {
            return StudyReport(SourceStudy.EMPTY, live + mapOf("message" to "No checked source yet. Make the first edit with expectedRevision=empty and this liveSourceHash."))
        }
        val r = revisions.revision(q.revision)
        return StudyReport(r.id, linkedMapOf(
            "revision" to r.id, "parent" to r.parent, "description" to r.description, "source" to r.source,
            "liveSourceHash" to live["liveSourceHash"], "dirty" to live["dirty"], "liveSource" to live["liveSource"],
            "history" to revisions.history().takeLast(24).map { mapOf("id" to it.id, "description" to it.description) },
            "knobs" to runCatching { knobs(r.source).map(::knobLine) }.getOrDefault(emptyList()),
            "pendingVariants" to speculation.pending(),
            "renderedBy" to frames.description))
    }

    private fun published(r: SourceRevision<Unit>, previewSeconds: List<Double>, message: String): StudyReport {
        val base = StudyReport(r.id, linkedMapOf("message" to message, "revision" to r.id, "parent" to r.parent,
            "description" to r.description, "sourceHash" to SourceStudy.hash(r.source)))
        return try {
            val preview = observe(StudyCall.Observe(r.id, previewSeconds, r.parent.orEmpty()))
            base.copy(facts = base.facts + ("preview" to preview.facts), toolImages = preview.toolImages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base.copy(facts = base.facts + ("previewError" to "Revision ${r.id} is already active; observe it rather than repeating the edit. ${e.message}"))
        }
    }

    private fun preview(q: Any) = (q as? StudyRequest.Preview)?.previewSeconds.orEmpty()

    override fun edit(q: StudyRequest.Edit): StudyReport =
        published(revisions.edit(q.expectedRevision, q.expectedSourceHash, q.source, q.description), preview(q), "Checked and activated")

    override fun patch(q: StudyRequest.Patch): StudyReport =
        published(revisions.patch(q.expectedRevision, q.expectedSourceHash, q.edits, q.description), preview(q), "Patched, checked and activated")

    override fun setNumbers(q: StudyRequest.SetNumbers): StudyReport =
        published(revisions.setNumbers(q.expectedRevision, q.expectedSourceHash, q.numbers.numbers(), q.description, withNumbers), preview(q),
            "Numbers set, checked and activated")

    override fun restore(q: StudyRequest.Restore): StudyReport =
        published(revisions.restore(q.expectedRevision, q.expectedSourceHash, q.revision, q.description), emptyList(), "Restored as a new revision")

    override fun observe(q: StudyRequest.Observe): StudyReport {
        val r = revisions.revision(q.revision)
        val other = q.compareTo.takeIf { it.isNotBlank() }?.let { revisions.revision(it) }
        val long = sizeOf(q, 256)
        val times = times(q.seconds)
        val shown = draw(r.source, long, times)
        val before = other?.let { draw(it.source, long, times) }
        val stats = shown.mapIndexed { i, f -> StudyEvidence.stats(f, times[i]) }
        val rows = mutableListOf(shown.mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), label(times[i]), "luma ${StudyEvidence.format(stats[i].luma)}") })
        val labels = mutableListOf(r.id)
        val differences = before?.let { b ->
            rows += b.mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), label(times[i]), other.id) }
            labels += other.id
            val d = b.indices.map { i -> StudyEvidence.difference(b[i], shown[i], null, false).first }
            rows += b.indices.map { i -> StudyEvidence.differenceTile(b[i], shown[i], "difference", "max ${d[i].max.toInt()} levels") }
            labels += "difference"
            d.mapIndexed { i, diff -> mapOf("seconds" to times[i], "difference" to diff) }
        }
        val (w, h) = dims(if (long < 160) long * 2 else long)
        val bytes = StudyEvidence.sheet("$title · ${r.id}", r.description.take(140), rows, w, labels, tileHeight = h)
        val file = save("${r.id}-${times.joinToString("_")}${other?.let { "-vs-${it.id}" }.orEmpty()}.png", bytes)
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "comparedTo" to other?.id, "seconds" to times, "frames" to stats,
            "motion" to shown.zipWithNext().mapIndexed { i, (a, b) -> StudyEvidence.motion(a, b, times[i], times[i + 1]) }.ifEmpty { null },
            "differences" to differences, "imageFile" to file.absolutePath, "renderedBy" to frames.description),
            listOf(ToolImage(png(bytes), "${r.id}: frames at ${times.joinToString { StudyEvidence.format(it) }} s" + (other?.let { "; then ${it.id}, then the difference" } ?: ""))))
    }

    override fun compare(q: StudyRequest.Compare): StudyReport {
        if (q.mode !in setOf("rgb", "luma")) throw StudyRejection("mode is rgb or luma")
        val a = revisions.revision(q.beforeRevision); val b = revisions.revision(q.afterRevision)
        val long = sizeOf(q, 192)
        val times = times(q.seconds).take(6)
        val region = q.allowedRegion.takeIf { it.isNotEmpty() }
        val px = StudyCompare.pixels(draw(a.source, long, times), draw(b.source, long, times), times, a.id, b.id, region, q.mode, ::label)
        val (w, h) = dims(if (long < 150) long * 2 else long)
        val bytes = StudyEvidence.sheet("$title compare · ${a.id} → ${b.id} · ${q.mode}",
            (region?.let { "Change allowed inside uv [${it.joinToString()}]; " } ?: "No change allowed; ") + "held to one 8-bit level.", px.rows, w, tileHeight = h)
        val file = save("${a.id}-vs-${b.id}-${q.mode}.png", bytes)
        return StudyReport(b.id, linkedMapOf("before" to a.id, "after" to b.id, "mode" to q.mode, "region" to region, "results" to px.results,
            "preservedOutsideRegion" to px.preserved, "tolerance" to px.tolerance, "imageFile" to file.absolutePath, "renderedBy" to frames.description),
            listOf(ToolImage(png(bytes), "${a.id} → ${b.id}: before | after | difference, per time")))
    }

    override fun sweep(q: StudyRequest.Sweep): StudyReport {
        val r = revisions.revision((q as? StudyRequest.Revisioned)?.revision ?: "current")
        val all = knobs(r.source)
        val k = all.firstOrNull { it.index == q.knob } ?: throw StudyRejection("No knob ${q.knob}; ${r.id} has ${all.size}")
        val at = (q as? StudyRequest.At)?.seconds ?: defaultSeconds.first()
        val long = sizeOf(q, 160)
        val swept = StudyCompare.sweep(q.values) { v -> draw(withNumbers(r.source, mapOf(q.knob to v)), long, listOf(at)).single() }
        val tiles = swept.map { s -> StudyEvidence.tile(s.raster?.let(StudyEvidence::presented), StudyEvidence.format(s.value) + if (s.value == k.value) "  (now)" else "", "", s.error) }
        val (w, h) = dims(long)
        val bytes = StudyEvidence.sheet("$title sweep · ${r.id} · knob ${q.knob} '${k.typed}'", k.operand, tiles.chunked(5), w, tileHeight = h)
        val file = save("${r.id}-sweep-${q.knob}-${q.values.joinToString("_")}.png", bytes)
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "knob" to knobLine(k), "seconds" to at,
            "values" to swept.map { linkedMapOf("value" to it.value, "error" to it.error, "luma" to it.raster?.let { f -> StudyEvidence.stats(f, at).luma }) },
            "imageFile" to file.absolutePath, "published" to false),
            listOf(ToolImage(png(bytes), "${r.id}: knob ${q.knob} ('${k.typed}') at ${q.values.joinToString()}")))
    }

    override fun tryVariants(q: StudyRequest.TryVariants): StudyReport {
        StudyVariants.requireLabels(q.variants.map { it.label })
        val current = revisions.revision()
        val live = revisions.liveSource()
        val times = times(q.seconds).take(4)
        val long = sizeOf(q, 160)
        val epoch = revisions.epoch()
        val base = draw(current.source, long, times).mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), label(times[i]), "current") }
        val trial = speculation.tryAll(q.variants.map { StudyVariants.Candidate(it.label, it.note, it.source, it.edits) }, live, revisions::check,
            { revisions.isCancelled(epoch) }, { draw(it, long, times) }, { label(times[it]) }) { _, _ -> emptyMap() }
        val (w, h) = dims(long)
        val bytes = StudyEvidence.sheet("$title variants · against ${current.id}", "Unpublished candidates; adopt one, or ask for more.",
            listOf(base) + trial.rows, w, listOf("${current.id}\n(current)") + trial.labels, tileHeight = h)
        val batch = speculation.hold(current.id, SourceStudy.hash(live), trial.held, bytes)
        return StudyReport(current.id, linkedMapOf("against" to current.id, "seconds" to times, "variants" to trial.facts,
            "held" to trial.held.map { it.label }, "imageFile" to File(batch, "sheet.png").absolutePath, "published" to false),
            listOf(ToolImage(png(bytes), "Variants against ${current.id}: first row current, then one row per candidate")))
    }

    override fun adoptVariant(q: StudyRequest.Adopt): StudyReport =
        speculation.adopt(revisions, q.expectedRevision, q.expectedSourceHash, q.label, q.description) { r -> published(r, emptyList(), "Adopted variant ${q.label}") }

    /** The held candidates: label, note, source. */
    fun heldVariants(): List<Triple<String, String, String>> = speculation.held()
}

/**
 * A [SourceFramesStudy] of this box's [property], its revisions in [directory] (`studies/<box id>/<property>`
 * in the box's document by default). Publishing writes the property back, compare-and-swap, the way a
 * library study does.
 */
fun Box.sourceFramesStudy(property: String, frames: StudyFrames<String>, extension: String = "txt",
                          directory: File = File(BoxStudies.directory(this), property),
                          knobs: (String) -> List<StudyKnob> = StudyKnobs::plainNumbers,
                          withNumbers: (String, Map<Int, Double>) -> String = StudyKnobs::withPlainNumbers,
                          check: (String) -> Unit = {}, aspect: Double = 1.0, defaultSeconds: List<Double> = listOf(0.0)): SourceFramesStudy {
    val box = this
    val replace: (String, String) -> Boolean = if (BoxStudies.documentOf(box) != null) BoxStudies.replacer(box, property) else { expected, replacement ->
        synchronized(box) {
            if ((box.getPropertyRaw(property, "") ?: "") != expected) false else { box.setPropertyRaw(property, replacement); true }
        }
    }
    return SourceFramesStudy(directory, extension, { box.getPropertyRaw(property, "") as? String ?: "" }, replace, frames,
        knobs, withNumbers, check, title = "${box.name.ifBlank { property }} study", aspect = aspect, defaultSeconds = defaultSeconds)
}

/** A [StudyFrames] from one function that draws a frame: [draw] gets the input, width, height and time. */
fun <P> studyFrames(description: String, fast: Boolean = true, draw: (input: P, width: Int, height: Int, seconds: Double) -> CpuRaster): StudyFrames<P> =
    object : StudyFrames<P> {
        override val description = description
        override val fast = fast
        override fun render(input: P, width: Int, height: Int, seconds: List<Double>, each: (index: Int, raster: CpuRaster) -> Unit) {
            seconds.forEachIndexed { i, t -> each(i, draw(input, width, height, t)) }
        }
    }

/** Knobs for text whose numbers are written plainly — the default for a document's [SourceFramesStudy]. */
object StudyKnobs {
    private val number = Regex("""(?<![A-Za-z_\d.])-?(?:\d+\.\d*|\.\d+|\d+)(?![A-Za-z_\d])""")

    /** Every plain number in [source], in order, as a knob whose operand is the word after it (or `number`). */
    fun plainNumbers(source: String): List<StudyKnob> {
        val lineStarts = listOf(0) + source.indices.filter { source[it] == '\n' }.map { it + 1 }
        return number.findAll(source).mapIndexed { i, m ->
            val line = lineStarts.indexOfLast { it <= m.range.first } + 1
            val next = source.substring(m.range.last + 1).trimStart().takeWhile { !it.isWhitespace() }.takeIf { it.isNotBlank() && number.matchEntire(it) == null }
            StudyKnob(i, line, m.value, next ?: "number", m.value.toDouble())
        }.toList()
    }

    /** [source] with the numbers at [numbers]' indices replaced, keeping an integer an integer when the value is whole. */
    fun withPlainNumbers(source: String, numbers: Map<Int, Double>): String {
        val found = number.findAll(source).toList()
        numbers.keys.forEach { require(it in found.indices) { "No knob $it; the source has ${found.size}" } }
        val out = StringBuilder(source)
        found.withIndex().reversed().forEach { (i, m) ->
            val v = numbers[i] ?: return@forEach
            require(v.isFinite()) { "Knob $i cannot hold $v" }
            val text = if (v == Math.rint(v) && '.' !in m.value && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            out.replace(m.range.first, m.range.last + 1, text)
        }
        return out.toString()
    }
}
