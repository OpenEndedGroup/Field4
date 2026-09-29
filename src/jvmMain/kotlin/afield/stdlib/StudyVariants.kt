@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.stacksl.cpu.CpuRaster
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.concurrent.CancellationException

/**
 * The candidates a study has tried and is holding — checked, drawn, unpublished — until a person (in
 * the study's card) or a model adopts one: the half of *TryVariants* / *AdoptVariant* that does not
 * depend on what the study draws.
 *
 * A batch is written to `speculations/<millis>/` under the study's [directory]: `sheet.png`, one
 * `<label>.<extension>` per held candidate, and `variants.json` naming what they were tried against
 * and, once one is adopted, which and as what revision. [resume] picks the newest unadopted batch
 * back up when it was tried against the revision that is still current, so a restarted Field offers
 * the same candidates.
 */
class StudyVariants(val directory: File, val extension: String) {

    /** A held candidate: its label, the note it came with, and its complete source. */
    data class Held(val label: String, val note: String, val source: String)

    /** A candidate to try: a complete [source], or exact [edits] to the live source; [label] and [note] as the model gave them. */
    data class Candidate(val label: String, val note: String, val source: String, val edits: List<StudySourceEdit>)

    /** What [tryAll] made of a list of candidates: one row of tiles and one label per candidate, what it holds, and the facts for the report. */
    class Trial(val rows: List<List<StudyEvidence.Tile>>, val labels: List<String>, val held: List<Held>, val facts: List<Map<String, Any?>>)

    private val held = linkedMapOf<String, Held>()
    private var heldBatch: File? = null
    private val mapper = jacksonObjectMapper()

    companion object {
        /** What a variant label may be: short, so it fits under a tile and in a button. */
        val LABEL = Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,31}")

        /** Refuse a batch that is empty, too large, or whose labels are not distinct and short. */
        fun requireLabels(labels: List<String>) {
            if (labels.size !in 1..8) throw StudyRejection("Try 1..8 variants at a time")
            if (labels.distinct().size != labels.size || labels.any { !it.matches(LABEL) }) {
                throw StudyRejection("Variant labels must be distinct and short: up to 32 letters, digits, spaces, '.', '-' or '_', starting with a letter or digit")
            }
        }
    }

    /** Pick up the newest batch still unadopted, if it was tried against [currentId]. */
    fun resume(currentId: String) {
        val latest = latestBatch() ?: return
        runCatching {
            val batch = mapper.readTree(File(latest, "variants.json"))
            if (batch["against"]?.asText() == currentId && batch["adopted"]?.isNull != false) {
                synchronized(held) {
                    held.clear()
                    batch["variants"].forEach { v ->
                        val label = v["label"].asText()
                        held[label] = Held(label, v["note"].asText(), File(latest, v["file"].asText()).readText())
                    }
                }
            }
        }
    }

    /**
     * Check and draw each of [candidates] — resolving edits against [live] — with [check] (the study's
     * admission, publishing nothing) and [frames]; a refused candidate gets an error tile with the reason
     * instead. [facts] says what the report records of an admitted one beside its label, note and
     * warnings; [timeLabel] captions each frame's tile.
     */
    fun <T> tryAll(candidates: List<Candidate>, live: String, check: (String) -> StudyAdmission<T>, cancelled: () -> Boolean,
                   frames: (String) -> List<CpuRaster>, timeLabel: (Int) -> String,
                   facts: (value: T, frames: List<CpuRaster>) -> Map<String, Any?>): Trial {
        val rows = mutableListOf<List<StudyEvidence.Tile>>()
        val labels = mutableListOf<String>()
        val kept = mutableListOf<Held>()
        val out = candidates.map { v ->
            if (cancelled()) throw CancellationException()
            labels += v.label + if (v.note.isNotBlank()) "\n" + v.note.take(40) else ""
            try {
                val source = v.source.ifBlank { SourceStudy.applyEdits(live, v.edits) }
                val admitted = check(source)
                val drawn = frames(source)
                rows += drawn.mapIndexed { i, f -> StudyEvidence.tile(StudyEvidence.presented(f), timeLabel(i), v.label) }
                kept += Held(v.label, v.note, source)
                linkedMapOf<String, Any?>("label" to v.label, "ok" to true, "note" to v.note) + facts(admitted.value, drawn)
            } catch (e: StudyRejection) {
                rows += listOf(StudyEvidence.tile(null, v.label, "", e.message))
                linkedMapOf("label" to v.label, "ok" to false, "error" to e.message)
            }
        }
        return Trial(rows, labels, kept, out)
    }

    /** Hold [candidates] in place of whatever was held, and write the batch to disk; returns its folder. */
    fun hold(against: String, liveSourceHash: String, candidates: Collection<Held>, sheet: ByteArray): File {
        synchronized(held) { held.clear(); candidates.forEach { held[it.label] = it } }
        val batch = File(directory, "speculations/${System.currentTimeMillis()}").also { it.mkdirs() }
        File(batch, "sheet.png").writeBytes(sheet)
        val files = candidates.associate { it.label to it.label.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".$extension" }
        candidates.forEach { File(batch, files.getValue(it.label)).writeText(it.source) }
        File(batch, "variants.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(linkedMapOf(
            "against" to against, "liveSourceHash" to liveSourceHash, "adopted" to null,
            "variants" to candidates.map { linkedMapOf("label" to it.label, "note" to it.note, "file" to files.getValue(it.label)) })))
        heldBatch = batch
        return batch
    }

    /** The held candidates as label, note, source — what a card offers to adopt. */
    fun held(): List<Triple<String, String, String>> = synchronized(held) { held.values.map { Triple(it.label, it.note, it.source) } }

    /** The held candidates' labels and notes, for an Inspect; null when there are none. */
    fun pending(): List<Map<String, String>>? = synchronized(held) { held.values.map { mapOf("label" to it.label, "note" to it.note) } }.ifEmpty { null }

    /**
     * Publish the held candidate [label] through [revisions] (guards as for any edit), with [publish]
     * turning the new revision into the study's report, then forget the batch and record the adoption.
     */
    fun <T> adopt(revisions: SourceStudy<T>, expectedRevision: String, expectedSourceHash: String, label: String, description: String,
                  publish: (SourceRevision<T>) -> StudyReport): StudyReport {
        val chosen = synchronized(held) { held[label] }
            ?: throw StudyRejection("No held variant '$label'; held: ${held().joinToString { it.first }.ifBlank { "none" }}")
        val report = publish(revisions.edit(expectedRevision, expectedSourceHash, chosen.source,
            description.ifBlank { "adopt variant $label" + if (chosen.note.isNotBlank()) ": ${chosen.note}" else "" }))
        synchronized(held) { held.clear() }
        latestBatch()?.let { batch ->
            runCatching {
                val record = mapper.readTree(File(batch, "variants.json")) as ObjectNode
                record.put("adopted", label).put("as", report.revision)
                File(batch, "variants.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record))
            }
        }
        return report
    }

    private fun latestBatch(): File? = heldBatch ?: File(directory, "speculations").listFiles().orEmpty()
        .filter { File(it, "variants.json").isFile }.maxByOrNull { it.name.toLongOrNull() ?: 0L }
}

/**
 * The picture-level halves of a study's *Compare* and *Sweep*, which do not depend on what it draws.
 */
object StudyCompare {

    /** Per-time before | after | difference tiles, the measurements, and whether everything outside the region held. */
    class Pixels(val rows: List<List<StudyEvidence.Tile>>, val results: List<Map<String, Any?>>, val preserved: Boolean, val tolerance: Double)

    /**
     * Compare [before] and [after], frame by frame at [times]: displayed colour (`rgb`), brightness
     * (`luma`), or raw values (`stage`, for what a program leaves on the stack). [region] (`u0, v0, u1,
     * v1`) is where change is allowed; everything else is held to one 8-bit level, or 1e-6 raw.
     * [captions] replaces each tile's time caption, for a stage comparison that names its stages.
     */
    fun pixels(before: List<CpuRaster>, after: List<CpuRaster>, times: List<Double>, beforeLabel: String, afterLabel: String,
               region: List<Double>?, mode: String, timeLabel: (Double) -> String,
               captions: ((Double) -> Pair<String, String>)? = null): Pixels {
        val raw = mode == "stage"
        val rows = mutableListOf<List<StudyEvidence.Tile>>()
        val results = times.mapIndexed { i, t ->
            val fa = before[i]; val fb = after[i]
            val (outside, inside) = if (raw) StudyEvidence.rawDifference(fa, fb, region) else StudyEvidence.difference(fa, fb, region, mode == "luma")
            val (ca, cb) = captions?.invoke(t) ?: (timeLabel(t) to timeLabel(t))
            rows += listOf(
                if (raw) StudyEvidence.normalizedTile(fa, beforeLabel, ca).first else StudyEvidence.tile(StudyEvidence.presented(fa), beforeLabel, ca),
                if (raw) StudyEvidence.normalizedTile(fb, afterLabel, cb).first else StudyEvidence.tile(StudyEvidence.presented(fb), afterLabel, cb),
                if (raw) StudyEvidence.rawDifferenceTile(fa, fb, "difference", "outside: max ${StudyEvidence.format(outside.max)}")
                else StudyEvidence.differenceTile(fa, fb, "difference", "outside: max ${StudyEvidence.format(outside.max)} levels"))
            linkedMapOf("seconds" to t, "outside" to outside, "inside" to inside)
        }
        val tolerance = if (raw) 1e-6 else 1.0
        return Pixels(rows, results, results.all { (it["outside"] as StudyDifference).max <= tolerance }, tolerance)
    }

    /** One value of a sweep: the frame drawn with it, or why it could not be. */
    data class Swept(val value: Double, val raster: CpuRaster?, val error: String?)

    /** Draw [values] (2..9 of them) with [draw]; a value that fails records its error instead. Cancellation propagates. */
    fun sweep(values: List<Double>, draw: (Double) -> CpuRaster): List<Swept> {
        if (values.size !in 2..9) throw StudyRejection("Sweep 2..9 values")
        return values.map { value ->
            try {
                Swept(value, draw(value), null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Swept(value, null, e.message)
            }
        }
    }
}
