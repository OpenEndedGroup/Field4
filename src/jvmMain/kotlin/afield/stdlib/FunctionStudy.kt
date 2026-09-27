@file:afield.CompletionCategory("Function")

package afield.stdlib

import afield.ai.core.CarriesToolImages
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolImage
import afield.createKotlinScriptEngine
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.script.experimental.api.ResultValue

/** Stable, declared event landmarks in seconds. These are metadata, not a second function language. */
data class StudyEvent(val id: String, val start: Double, val rise: Double, val hold: Double = 30.0, val fall: Double = 60.0) {
    val plateauStart get() = start + rise
    val plateauEnd get() = plateauStart + hold
    val end get() = plateauEnd + fall
}

/** The source declares its choices; checks never silently promote an assumption to a requirement. */
data class StudyChoice(val statement: String, val provenance: String)

/** Exact, unique replacement in the inspected live source. All matches use the same base. */
data class StudySourceEdit(val before: String, val after: String)

/** Ordinary Functions.Fn values. Callers promise pure, deterministic, random-access evaluation. */
data class StudyComposition(
    val channels: List<Functions.Fn>,
    val events: List<StudyEvent> = emptyList(),
    val choices: List<StudyChoice> = emptyList(),
    val duration: Double = 3600.0,
    val channelNames: List<String> = listOf("x", "y", "z"),
    val maxFrequencyHz: Double = 2.0,
    val seed: Long = 0
)

data class StudyRevision(val id: String, val parent: String?, val description: String, val source: String, val composition: StudyComposition)
data class StudyReport(val revision: String, val facts: Map<String, Any?>,
    override val toolImages: List<ToolImage> = emptyList()) : CarriesToolImages, SummarisesForToolLog {
    override fun toolLogSummary() = "$revision ${facts["message"] ?: facts.keys.joinToString()} images=${toolImages.size}"
}

/**
 * Experimental observation/transaction layer over Fn. No model transport and no alternate evaluator.
 * Source is authoritative. A candidate is compiled on a fresh Field Kotlin engine and checked before
 * compare-and-swap publishes it. This is trusted local Kotlin, NOT an isolation/security boundary.
 * Cancellation invalidates pending publication; arbitrary non-cooperative Kotlin cannot be killed here.
 */
class FunctionStudy(
    private val directory: File,
    private val readSource: () -> String,
    private val replaceSource: (expected: String, replacement: String) -> Boolean,
    private val compiler: (String) -> StudyComposition = ::compileStudy
) {
    private val revisions = linkedMapOf<String, StudyRevision>()
    private val cancelled = AtomicLong()

    /** The line the person's editor cursor is on in this study's box, or null — see [BoxStudies.personAt]. */
    @Volatile
    var editorLine: () -> Int? = { null }
    private val mapper = jacksonObjectMapper()
    private var selected = emptyList<String>()
    private var selection = 0.0 to 3600.0
    private var active = "empty"
    init { directory.mkdirs() }

    companion object {
        fun hash(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }

        /** Uses Field's established compiler; each expression must return StudyComposition. */
        fun compileStudy(source: String): StudyComposition {
            val engine = createKotlinScriptEngine(filePrefix = "function_study_${System.nanoTime()}")
            val diagnostics = mutableListOf<String>()
            try {
                val value = engine.execAndWait(source,
                    onCompileError = { ds -> diagnostics += ds.map { it.message } },
                    onEvalError = { ds -> diagnostics += ds.map { it.message } },
                    onEvalException = { throw it })
                if (value is ResultValue.Error) throw value.error
                return (value as? ResultValue.Value)?.value as? StudyComposition
                    ?: throw IllegalArgumentException("Candidate must return StudyComposition. " + diagnostics.joinToString("\n").take(6000))
            } finally { engine.dispose() }
        }
    }

    fun cancel(): String { cancelled.incrementAndGet(); return "Pending publication/observations cancelled; working revision retained." }
    private fun checkCancelled(epoch: Long) {
        if (cancelled.get() != epoch || Thread.currentThread().isInterrupted) throw CancellationException("Function study cancelled")
    }

    @Synchronized
    fun currentId() = active
    private fun historyIds(): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = active.takeUnless { it == "empty" }
        while (cursor != null && cursor !in ids) {
            ids += cursor
            cursor = mapper.readTree(File(directory, "$cursor.json"))["parent"]?.takeUnless { it.isNull }?.asText()
        }
        return ids.reversed()
    }
    /** A revision by id, or the active one for `"current"` or a blank id — as a model often sends. */
    @Synchronized
    fun revision(id: String = "current"): StudyRevision {
        val key = if (id.isBlank() || id == "current") active else id
        revisions[key]?.let { return it }
        require(key.matches(Regex("r[0-9]+-[a-f0-9]{12}"))) { "Unknown revision $key" }
        val sourceFile = File(directory, "$key.kt")
        require(sourceFile.isFile) { "Unknown revision $key" }
        val meta = mapper.readTree(File(directory, "$key.json"))
        val source = sourceFile.readText()
        require(key.substringAfter('-') == hash(source).take(12)) { "Revision source changed on disk: $key" }
        val result = StudyRevision(key, meta["parent"]?.takeUnless { it.isNull }?.asText(), meta["description"].asText(), source, compiler(source))
        revisions[key] = result
        return result
    }

    /** Resume only when the saved source still agrees. A manual edit remains explicitly dirty. */
    @Synchronized
    fun resume(): String {
        val head = File(directory, "HEAD")
        if (head.isFile) { active = head.readText().trim(); revision(active) }
        return active
    }

    @Synchronized
    fun inspect(id: String = "current"): StudyReport {
        val live = readSource()
        if (active == "empty") return StudyReport(active, mapOf("message" to "No checked composition yet", "liveSourceHash" to hash(live), "liveSource" to live))
        val r = revision(id)
        return StudyReport(r.id, mapOf("parent" to r.parent, "description" to r.description,
            "durationSeconds" to r.composition.duration, "channels" to r.composition.channelNames,
            "events" to r.composition.events.sortedBy { it.start }, "choices" to r.composition.choices,
            "source" to r.source, "liveSourceHash" to hash(live), "dirty" to (live != revision().source),
            "liveSource" to live.takeIf { live != revision().source },
            "personAt" to BoxStudies.personAt(editorLine(), live),
            "selectedIds" to selected, "selectionSeconds" to listOf(selection.first, selection.second),
            "history" to historyIds(),
            "semantics" to "Seconds; pure random access required. Event metadata is declared, not a proof about arbitrary Kotlin. Fn lambdas may be opaque to graph rewrites."))
    }

    @Synchronized
    fun select(ids: List<String>, start: Double, end: Double): StudyReport {
        val r = revision()
        require(ids.all { id -> r.composition.events.any { it.id == id } }) { "Unknown event ID" }
        interval(r, start, end)
        selected = ids.toList(); selection = start to end
        return inspect()
    }

    /** Two guards: checked revision AND the current editable source, including uncompiled manual edits. */
    @Synchronized
    fun patch(base: String, sourceHash: String, edits: List<StudySourceEdit>, description: String): StudyReport {
        val source = readSource()
        require(base == active && sourceHash == hash(source)) { "Conflict: inspect the current revision and live source before patching." }
        require(edits.size in 1..32) { "Supply 1..32 exact replacements" }
        val ranges = edits.map { change ->
            require(change.before.isNotEmpty()) { "Empty match is ambiguous; include surrounding source" }
            val at = source.indexOf(change.before)
            require(at >= 0 && source.indexOf(change.before, at + 1) < 0) { "Patch match must occur exactly once; include more context" }
            Triple(at, at + change.before.length, change.after)
        }.sortedBy { it.first }
        require(ranges.zipWithNext().all { (a, b) -> a.second <= b.first }) { "Patch matches overlap" }
        val candidate = StringBuilder(source)
        ranges.asReversed().forEach { (start, end, text) -> candidate.replace(start, end, text) }
        return edit(base, sourceHash, candidate.toString(), description)
    }

    @Synchronized
    fun edit(base: String, sourceHash: String, source: String, description: String): StudyReport {
        val before = readSource()
        require(base == active && sourceHash == hash(before)) { "Conflict: revision or live source changed. Inspect and reconcile; nothing overwritten." }
        require(source.length in 1..40000) { "Candidate source must contain 1..40000 characters" }
        val epoch = cancelled.get()
        val candidate = compiler(source)
        checkCancelled(epoch)
        validate(candidate, epoch)
        checkCancelled(epoch)
        require(readSource() == before) { "Conflict: source changed during compilation; candidate not activated." }
        val number = directory.listFiles().orEmpty().filter { it.extension == "json" }
            .mapNotNull { it.name.substringBefore('-').removePrefix("r").toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        val id = "r$number-${hash(source).take(12)}"
        val parent = active.takeUnless { it == "empty" }
        // Write the durable candidate before publication; unreferenced files are harmless on a conflict.
        File(directory, "$id.kt").writeText(source)
        File(directory, "$id.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "id" to id, "parent" to parent, "description" to description, "events" to candidate.events,
            "choices" to candidate.choices, "seed" to candidate.seed)))
        checkCancelled(epoch)
        require(replaceSource(before, source)) { "Conflict: source changed at publication; working revision retained." }
        revisions[id] = StudyRevision(id, parent, description, source, candidate)
        active = id
        File(directory, "HEAD").writeText(id)
        return StudyReport(id, mapOf("message" to "Compiled, checked and activated", "parent" to parent,
            "sourceHash" to hash(source), "events" to candidate.events, "description" to description,
            "validation" to mapOf("compileSuccess" to true, "checksPassed" to true,
                "sourceHash" to hash(source), "revision" to id, "scope" to "complete standalone StudyComposition source"),
            "checks" to "Finite/bounded samples at <=20ms, declared holds, deterministic repeated probes. Observe and compare before judging the edit."))
    }

    /** Preview a committed edit without turning a preview error into a misleading failed edit. */
    @Synchronized
    fun previewEdit(report: StudyReport, start: Double = 0.0, end: Double = 25.0): StudyReport {
        return try {
            val r = revision(report.revision)
            val stop = min(end, r.composition.duration)
            val parent = r.parent?.let(::revision)?.takeIf {
                it.composition.channelNames == r.composition.channelNames && stop <= it.composition.duration
            }
            val preview = observe(r.id, start, stop, parent?.id.orEmpty())
            report.copy(facts = report.facts + ("preview" to preview.facts), toolImages = preview.toolImages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report.copy(facts = report.facts + ("previewError" to
                "Revision ${report.revision} is already activated. Request a valid ObserveFunctionStudy interval; do not repeat the edit. ${e.message}"))
        }
    }

    /** Export a checked immutable revision, staging and verifying every file before replacing destinations. */
    @Synchronized
    fun exportCsv(id: String, expectedSourceHash: String, outputs: List<File>,
        sampleRate: Double = 100.0, scale: Double = 100.0, header: Boolean = false, overwrite: Boolean = false): StudyReport {
        val r = revision(id)
        require(hash(r.source) == expectedSourceHash) { "Export source hash differs from the inspected revision" }
        if (id == "current" || r.id == active) require(readSource() == r.source) {
            "Live source differs from the checked revision. Reconcile it with PatchFunctionStudy/EditFunctionStudy before exporting."
        }
        require(outputs.size == r.composition.channels.size && outputs.map { it.canonicalPath }.distinct().size == outputs.size)
        require(sampleRate.isFinite() && sampleRate > 0 && scale.isFinite())
        val samples = floor(r.composition.duration * sampleRate)
        require(samples.isFinite() && samples in 1.0..2_000_000.0) { "Export needs 1..2000000 sample intervals per channel" }
        require(outputs.all { !it.exists() || (overwrite && it.isFile) }) { "Output exists; explicitly request overwrite to re-export" }
        val last = samples.toInt()
        val extra = last / sampleRate < r.composition.duration
        val rows = last + 1 + if (extra) 1 else 0
        val epoch = cancelled.get()
        val staged = mutableListOf<File>()
        try {
            val records = outputs.mapIndexed { channel, output ->
                val target = output.absoluteFile
                target.parentFile.mkdirs()
                val temp = Files.createTempFile(target.parentFile.toPath(), ".study-export-", ".csv").toFile()
                staged += temp
                val f = r.composition.channels[channel]
                temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    if (header) writer.append("time_seconds,signal_scaled\n")
                    for (i in 0 until rows) {
                        if (i % 2048 == 0) checkCancelled(epoch)
                        val t = if (i <= last) i / sampleRate else r.composition.duration
                        val value = f(t) * scale
                        require(value.isFinite()) { "Non-finite export at $t seconds" }
                        writer.append(t.toString()).append(',').append(value.toString()).append('\n')
                    }
                }
                var verified = 0
                temp.bufferedReader(Charsets.UTF_8).use { reader ->
                    if (header) require(reader.readLine() == "time_seconds,signal_scaled")
                    reader.lineSequence().forEach { line ->
                        if (verified % 2048 == 0) checkCancelled(epoch)
                        val fields = line.split(',')
                        val time = if (verified <= last) verified / sampleRate else r.composition.duration
                        require(fields.size == 2 && fields[0].toDouble() == time && fields[1].toDouble() == f(time) * scale)
                        verified++
                    }
                }
                require(verified == rows)
                val digest = MessageDigest.getInstance("SHA-256")
                temp.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                mapOf("channel" to r.composition.channelNames[channel], "path" to target.path,
                    "rows" to verified, "bytes" to temp.length(), "sha256" to digest.digest().joinToString("") { "%02x".format(it) })
            }
            checkCancelled(epoch)
            // Re-check before publication; no overwrite unless the caller requested it.
            require(overwrite || outputs.none { it.exists() }) { "Output appeared during export; nothing replaced" }
            staged.zip(outputs).forEach { (temp, output) ->
                if (overwrite) Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                else Files.move(temp.toPath(), output.toPath())
            }
            return StudyReport(r.id, mapOf("message" to "Exported and verified CSV files", "sourceHash" to expectedSourceHash,
                "durationSeconds" to r.composition.duration, "sampleRateHz" to sampleRate, "scale" to scale,
                "header" to header, "endpointIncluded" to true, "files" to records))
        } finally { staged.forEach { it.delete() } }
    }

    private fun validate(c: StudyComposition, epoch: Long) {
        require(c.duration.isFinite() && c.duration > 0 && c.duration <= 7200) { "Duration outside (0,7200] seconds" }
        require(c.channels.size in 1..8 && c.channels.size == c.channelNames.size)
        require(c.channels.all { it.domain.min <= 0.0 && it.domain.max >= c.duration }) {
            "Every Fn domain must cover the composition duration; check arithmetic domain intersections"
        }
        require(c.maxFrequencyHz.isFinite() && c.maxFrequencyHz in 0.0..10.0)
        require(c.events.size <= 64 && c.events.map { it.id }.distinct().size == c.events.size) { "Events need unique stable IDs" }
        c.events.forEach { e ->
            require(e.id.matches(Regex("[a-zA-Z0-9_-]{1,60}")))
            require(listOf(e.start, e.rise, e.hold, e.fall).all { it.isFinite() } && e.start >= 0 && e.rise > 0 && e.hold > 0 && e.fall > 0 && e.end <= c.duration) { "Invalid support for ${e.id}; no silent truncation" }
            for (k in 0..60) for (f in c.channels) require(abs(f(e.plateauStart + e.hold * k / 60.0) - 1.0) <= 1e-9) { "${e.id} hold is not (1,...,1) for its declared duration" }
        }
        val n = ceil(c.duration / 0.02).toInt()
        val probes = listOf(0.0, c.duration * .137, c.duration * .51, c.duration)
        val expected = c.channels.map { f -> probes.map { f(it) } }
        for (i in 0..n) {
            if (i % 2048 == 0) checkCancelled(epoch)
            val t = c.duration * i / n
            for (f in c.channels) require(f(t).let { it.isFinite() && it >= -1e-9 && it <= 1 + 1e-9 }) { "Non-finite or outside assumed [0,1] at $t seconds" }
        }
        c.channels.forEachIndexed { ch, f -> probes.indices.reversed().forEach { i ->
            require(f(probes[i]) == expected[ch][i]) { "Non-deterministic evaluation detected; stateful signals not supported" }
        } }
    }

    private fun interval(r: StudyRevision, start: Double, end: Double) {
        require(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= r.composition.duration) { "Interval must lie inside the composition, in seconds" }
    }

    @Synchronized
    fun sample(id: String, times: List<Double>): StudyReport {
        require(times.size in 1..256) { "Request 1..256 explicit times" }
        val r = revision(id)
        require(times.all { it.isFinite() && it in 0.0..r.composition.duration })
        return StudyReport(r.id, mapOf("timesSeconds" to times, "channelNames" to r.composition.channelNames,
            "values" to times.map { t -> r.composition.channels.map { it(t) } }, "method" to "Exact Fn evaluation at requested times; not an interval guarantee"))
    }

    private fun grid(r: StudyRevision, start: Double, end: Double): List<Double> {
        interval(r, start, end)
        val step = min(.02, if (r.composition.maxFrequencyHz > 0) 1 / (32 * r.composition.maxFrequencyHz) else .02)
        val n = ceil((end - start) / step).toInt()
        require(n <= 2_400_000) { "Sampling budget exceeded; request a smaller interval" }
        return (List(n + 1) { start + (end - start) * it / n } + r.composition.events.flatMap {
            listOf(it.start, it.plateauStart, it.plateauEnd, it.end)
        }.filter { it in start..end }).distinct().sorted()
    }

    /** Revision-consistent pixels + landmarks + measured holds/joins. Same samples feed image and extrema. */
    @Synchronized
    fun observe(id: String, start: Double, end: Double, compareTo: String = ""): StudyReport {
        val epoch = cancelled.get()
        val r = revision(id)
        val old = compareTo.takeIf { it.isNotBlank() }?.let(::revision)
        require(old == null || old.composition.channelNames == r.composition.channelNames)
        if (old != null) interval(old, start, end)
        val times = grid(r, start, end)
        val values = r.composition.channels.map { f -> DoubleArray(times.size) { i ->
            if (i % 2048 == 0) checkCancelled(epoch)
            f(times[i])
        } }
        val prior = old?.composition?.channels?.map { f -> DoubleArray(times.size) { f(times[it]) } }
        val image = render(r, times, values, old, prior)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val filename = "${r.id}-${start}-${end}${old?.let { "-vs-${it.id}" }.orEmpty()}.png"
        File(directory, filename).writeBytes(bytes)
        val events = r.composition.events.filter { it.end >= start && it.start <= end }
        val joins = events.flatMap { e -> listOf(e.start, e.plateauStart, e.plateauEnd, e.end).map { t ->
            val h = 1e-4
            mapOf("id" to e.id, "timeSeconds" to t, "channels" to r.composition.channels.map { f ->
                mapOf("value" to f(t), "leftSlope" to (f(t) - f(max(0.0, t-h))) / h,
                    "rightSlope" to (f(min(r.composition.duration, t+h)) - f(t)) / h)
            })
        } }
        val comparisons = prior?.mapIndexed { ch, a ->
            val delta = DoubleArray(times.size) { abs(values[ch][it] - a[it]) }
            val at = delta.indices.maxBy { delta[it] }
            mapOf("channel" to r.composition.channelNames[ch], "maxAbs" to delta[at], "atSeconds" to times[at],
                "rms" to sqrt(delta.sumOf { it*it } / delta.size))
        }
        return StudyReport(r.id, mapOf("intervalSeconds" to listOf(start, end), "comparedTo" to old?.id,
            "sourceHash" to hash(r.source), "channels" to r.composition.channelNames, "events" to events,
            "choices" to r.composition.choices, "selectedIds" to selected,
            "sampling" to mapOf("count" to times.size, "maxStepSeconds" to times.zipWithNext { a,b -> b-a }.maxOrNull(),
                "method" to "Uniform samples plus exact declared joins; per-pixel min/max for wide views. Finite samples cannot rule out hidden narrow features. Min/max loses ordering within bins."),
            "extrema" to values.mapIndexed { ch, a -> mapOf("channel" to r.composition.channelNames[ch], "min" to a.minOrNull(), "max" to a.maxOrNull()) },
            "holdChecks" to events.map { e -> mapOf("id" to e.id, "declaredSeconds" to e.hold,
                "sampledMaxTargetError" to (0..60).maxOf { i -> r.composition.channels.maxOf { f -> abs(f(e.plateauStart + e.hold*i/60.0)-1) } }) },
            "joins" to joins, "difference" to comparisons, "imageFile" to File(directory, filename).absolutePath,
            "guarantees" to "All numerical measurements are sampled estimates. Constant holds and phase/frequency claims require source inspection as well."),
            listOf(ToolImage("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
                "${r.id}; $start..$end seconds. White=current; dashed grey=${old?.id ?: "none"}. Inspect local waveforms before judging pulse rate; wide views are min/max envelopes.")))
    }

    private fun render(r: StudyRevision, times: List<Double>, values: List<DoubleArray>, old: StudyRevision?, prior: List<DoubleArray>?): BufferedImage {
        val w = 1280; val row = 150; val h = 135 + row * values.size
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.BLACK; g.fillRect(0,0,w,h)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.font = Font("Iosevka", Font.PLAIN, 15)
        g.color = Color.WHITE; g.drawString("FUNCTION STUDY / ${r.id}", 32, 30)
        g.color = Color.LIGHT_GRAY
        g.drawString("${times.first()} — ${times.last()} seconds  /  ${times.size} samples  /  ${if (times.last()-times.first()>25) "MIN/MAX ENVELOPE" else "WAVEFORM"}",32,54)
        if (old != null) g.drawString("white: current    dashed grey: ${old.id}", 32, 76)
        val left=80; val right=w-30; val width=right-left
        fun x(t: Double) = left + ((t-times.first())/(times.last()-times.first())*width).roundToInt()
        r.composition.events.filter { it.end>=times.first() && it.start<=times.last() }.forEach { e ->
            g.color=Color(65,65,65); g.fillRect(x(max(e.start,times.first())),89, max(2,x(min(e.end,times.last()))-x(max(e.start,times.first()))),5)
            g.color=Color.LIGHT_GRAY; g.drawString(e.id, x(max(e.start,times.first())),110)
        }
        values.forEachIndexed { ch, data ->
            val top=125+ch*row; val bottom=top+105
            fun y(v:Double)=bottom-(v*105).roundToInt()
            g.color=Color(48,48,48); for(v in listOf(0.0,.5,1.0)) g.drawLine(left,y(v),right,y(v))
            g.color=Color.LIGHT_GRAY; g.drawString(r.composition.channelNames[ch],25,top+45)
            g.drawString("1",57,top+5); g.drawString("0",57,bottom+4)
            for (tick in 0..6) {
                val t=times.first()+(times.last()-times.first())*tick/6
                g.color=Color(40,40,40); g.drawLine(x(t),top,x(t),bottom)
                g.color=Color.GRAY; g.drawString("%.1f".format(java.util.Locale.US,t),x(t)-15,bottom+23)
            }
            fun curve(a:DoubleArray, color:Color, dashed:Boolean) {
                g.color=color; g.stroke=if(dashed) BasicStroke(1f,0,0,10f,floatArrayOf(4f,4f),0f) else BasicStroke(1.3f)
                if(times.last()-times.first()>25) {
                    var i=0
                    val low = mutableListOf<Pair<Double, Double>>()
                    val high = mutableListOf<Pair<Double, Double>>()
                    for(px in left..right) {
                        var lo=Double.POSITIVE_INFINITY; var hi=Double.NEGATIVE_INFINITY
                        while(i<times.size && x(times[i])<=px) { lo=min(lo,a[i]); hi=max(hi,a[i]); i++ }
                        if(lo.isFinite()) {
                            low += px.toDouble() to (bottom-lo*105)
                            high += px.toDouble() to (bottom-hi*105)
                        }
                    }
                    if (low.isNotEmpty()) {
                        val band = java.awt.geom.Path2D.Double()
                        band.moveTo(low.first().first, low.first().second)
                        low.drop(1).forEach { (x,y) -> band.lineTo(x,y) }
                        high.asReversed().forEach { (x,y) -> band.lineTo(x,y) }
                        band.closePath()
                        g.color = Color(color.red,color.green,color.blue,if(dashed) 70 else 115)
                        g.fill(band)
                        g.color = color
                        for (edge in listOf(low, high)) {
                            val path = java.awt.geom.Path2D.Double()
                            path.moveTo(edge.first().first, edge.first().second)
                            edge.drop(1).forEach { (x,y) -> path.lineTo(x,y) }
                            g.draw(path)
                        }
                    }
                } else for(i in 1 until times.size) g.drawLine(x(times[i-1]),y(a[i-1]),x(times[i]),y(a[i]))
            }
            prior?.let { curve(it[ch],Color(110,110,110),true) }
            curve(data,Color(235,235,235),false)
        }
        g.dispose(); return image
    }
}
