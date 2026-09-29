package afield.stacksl.study

import afield.Box
import afield.ai.core.ToolImage
import afield.four.ModuleDestinations
import afield.four.modules.ComputeModulePointer
import afield.four.modules.MeshModulePointer
import afield.four.modules.ModulePointer
import afield.stdlib.BoxStudies
import afield.stdlib.KnobNames
import afield.stdlib.SourceRevision
import afield.stdlib.SourceStudy
import afield.stdlib.StackAutoNumbers
import afield.stdlib.StudyAdmission
import afield.stdlib.StudyKeys
import afield.stdlib.StudyRejection
import afield.stdlib.StudyReport
import afield.stdlib.StudySourceEdit
import afield.stdlib.stackAutoModulesOf
import afield.stdlib.stackAutoShaderReport
import afield.stdlib.stackAutoShaderSource
import afield.stdlib.stackAutoShaderSources
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures of a `stack.auto` slot: [inShader] the end result — wherever its module is drawn, found by
 * walking what the renderer draws, else the module drawn alone into a target of its own
 * ([ModuleDestinations]) — and [alone], the slot's program by itself on the StackSL study's frame. The
 * numbers card and the slot study's tools both show them.
 */
object StackAutoView {
    /** A picture (PNG bytes, or null when there is none) and what it is. */
    data class Picture(val png: ByteArray?, val note: String) {
        val dataUrl: String? get() = png?.let { "data:image/png;base64," + Base64.getEncoder().encodeToString(it) }
    }

    /** [slot] of [box] in the host shader, as it is drawing now; see the object docs. */
    fun inShader(box: Box, slot: String): Picture = try {
        val renderer = BoxStudies.rendererAbove(box) ?: throw IllegalStateException("the box has no running renderer above it")
        val modules = stackAutoModulesOf(box)
        require(modules.isNotEmpty()) { "no module of this box has been built yet" }
        if (modules.all { it is ComputeModulePointer })
            throw IllegalStateException("stack.auto.$slot is in a compute module, which writes buffers rather than a picture; reading those back is not done yet")
        val destinations = modules.flatMap { ModuleDestinations.of(renderer, it) }.distinct()
        val shown = destinations.firstOrNull { it !is ModuleDestinations.Destination.Headset }
        val captured = shown?.let { d -> runCatching { ModuleDestinations.capture(d) }.getOrNull()?.let { d to it } }
        if (captured != null) {
            val (d, png) = captured
            val others = destinations.filter { it != d }
            Picture(fitWithin(png, 1024), "stack.auto.$slot in your shader: $d, as it is drawing now, at the slot's current numbers" +
                (if (d is ModuleDestinations.Destination.Window) " — everything the window draws, not only this module" else "") +
                (if (others.isNotEmpty()) " · also drawn to ${others.joinToString()}" else ""))
        } else {
            val module = modules.filterIsInstance<ModulePointer>().firstOrNull()
                ?: throw IllegalStateException("this box's module is not one that can be drawn alone")
            val size = (shown as? ModuleDestinations.Destination.Texture)?.target?.let { it.width to it.height } ?: (1024 to 1024)
            Picture(fitWithin(ModuleDestinations.renderDisposable(renderer, module, size.first, size.second), 1024),
                "stack.auto.$slot in your shader: the module drawn once more, by itself, into a ${size.first}×${size.second} target of its own, at the slot's current numbers — " +
                    (if (destinations.isEmpty()) "it is not drawn anywhere the renderer's lists reach (a ping-pong or stereo target, or none yet)" else "where it is drawn could not be read back") +
                    "; the same module and state, without whatever else draws beside it")
        }
    } catch (e: Exception) {
        Picture(null, "Cannot capture stack.auto.$slot in your shader: ${e.message}")
    }

    /**
     * [slot] in the host shader at [moments] moments spread evenly across the next [seconds] seconds, as
     * it really draws, on one numbered sheet (read left to right, then down). A host that animates the
     * slot — by time it pushes, say — cannot be moved to a chosen time from here, so this waits for it.
     * Run it off the main thread: every capture waits for a frame the main thread draws.
     */
    fun overTime(box: Box, slot: String, seconds: Double, moments: Int): Picture {
        require(moments in 2..9) { "2..9 moments" }
        require(seconds > 0.0 && seconds <= 20.0) { "over 0..20 seconds" }
        val start = System.nanoTime()
        val frames = (0 until moments).map { i ->
            val at = (seconds * i / moments * 1e9).toLong()
            (at - (System.nanoTime() - start)).takeIf { it > 0 }?.let { Thread.sleep(it / 1_000_000) }
            (System.nanoTime() - start) / 1e9 to inShader(box, slot)
        }
        val first = frames.first().second
        val images = frames.mapNotNull { (_, p) -> p.png?.let { javax.imageio.ImageIO.read(it.inputStream()) } }
        if (images.size != frames.size) return Picture(null, first.note)
        val columns = Math.ceil(Math.sqrt(moments.toDouble())).toInt()
        val rows = (moments + columns - 1) / columns
        val cell = 1024 / columns
        val sheet = java.awt.image.BufferedImage(cell * columns, cell * rows, java.awt.image.BufferedImage.TYPE_INT_RGB)
        with(sheet.createGraphics()) {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, maxOf(14, cell / 18))
            images.forEachIndexed { i, image ->
                val x = i % columns * cell; val y = i / columns * cell
                val scale = cell.toDouble() / maxOf(image.width, image.height)
                drawImage(image, x, y, (image.width * scale).toInt(), (image.height * scale).toInt(), null)
                color = java.awt.Color(255, 255, 255, 220)
                drawString("${i + 1}  +%.1f s".format(frames[i].first), x + cell / 30, y + cell / 30 + font.size)
            }
            dispose()
        }
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(sheet, "png", it) }.toByteArray()
        return Picture(png, "$moments moments across ${"%.1f".format(seconds)} s, numbered and read left to right, then down, each labelled with when it was " +
            "captured; each is ${first.note}")
    }

    private val rasterizers = ConcurrentHashMap<Box, StackSLRasterizer>()

    /** [program] (a slot's, at [numbers] by knob index) drawn alone on the StackSL study's frame, for debugging. */
    fun alone(box: Box, slot: String, program: String, entrypoint: String?, numbers: Map<Int, Double> = emptyMap()): Picture = try {
        require(entrypoint == null) { "stack.auto.$slot runs the word $entrypoint; drawing a single word alone is not done yet" }
        val drawn = if (numbers.isEmpty()) program else StackSLStudyShader.setKnobs(program, numbers)
        val compiled = afield.stacksl.StackSLCompiler.compile(drawn, afield.stacksl.cpu.StackSLCpu.stackAutoOptions("program"))
        require(compiled.analysis.requiredSamplers.isEmpty()) { "it samples ${compiled.analysis.requiredSamplers.joinToString()}, which only your shader provides" }
        val rasterizer = rasterizers.getOrPut(box) { StackSLGpuRasterizer({ BoxStudies.rendererAbove(box) }) }
        val raster = rasterizer.renderAll(drawn, 256, listOf(StackSLStudyShader.REFERENCE_TIME)).single()
        Picture(StackSLStudyShader.presented(raster).toPngBytes(),
            "stack.auto.$slot alone, at t = ${StackSLStudyShader.REFERENCE_TIME} s, input vec4(uv, hash, 0, time) on a unit square — not the input your shader gives it · ${rasterizer.description}")
    } catch (e: Exception) {
        Picture(null, "Cannot draw stack.auto.$slot alone: ${e.message}")
    }

    /** [png] scaled so its longer side is at most [long] pixels: a window capture is the display's full resolution. */
    fun fitWithin(png: ByteArray, long: Int): ByteArray {
        val image = javax.imageio.ImageIO.read(png.inputStream()) ?: return png
        val scale = long.toDouble() / maxOf(image.width, image.height)
        if (scale >= 1.0) return png
        val w = (image.width * scale).toInt().coerceAtLeast(1); val h = (image.height * scale).toInt().coerceAtLeast(1)
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, w, h, null); dispose()
        }
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "png", it) }.toByteArray()
    }
}

/** What a slot study checks a candidate program for: that it compiles into this slot, and what it says. */
data class StackAutoSlotCheck(val problems: List<String>, val warnings: List<String>, val knobs: List<StackSLKnob>)

/**
 * A model's study of one `stack.auto` slot, inside a box's own shader: the StackSL study's loop — inspect,
 * edit, turn numbers, observe, sweep, restore — where the program lives in someone else's GLSL.
 *
 * The live source is the slot's, the text the GPU runs (`module.foo`); accepting an edit binds it to the
 * slot, which recompiles in place, and writes it back into the Kotlin string it sits in, when it sits
 * there verbatim, so that it survives the box running again. The check is "compiles into this slot"
 * rather than the StackSL study's "renders on its own frame": a slot that samples a texture, reads the
 * host's push constants or leaves something other than a colour is fine here. The evidence is the end
 * result ([StackAutoView.inShader]); the program alone on the study's frame is the secondary, debugging
 * view. Numbers turn through the slot's live block ([StackAutoNumbers]), with no recompile.
 *
 * Revisions live in `studies/<box>/stack.auto/<slot>/revisions`, beside the slot's knob names and keys.
 */
class StackAutoSlotStudy internal constructor(
    val box: Box, val slot: String, directory: File,
    // How an accepted program reaches the slot: bound live through the box's module, on Field's main
    // thread. A test, which has neither, passes its own.
    publisher: ((expected: String, replacement: String) -> Boolean)? = null
) {
    val revisions = SourceStudy(File(directory, "revisions"), "stacksl", ::liveSource, publisher ?: ::publish, ::admit)

    private fun liveSource(): String = box.stackAutoShaderSources[slot] ?: ""

    private fun entrypoint() = StackAutoNumbers.slots(box)[slot]?.entrypoint

    private fun admit(source: String, @Suppress("UNUSED_PARAMETER") cancelled: () -> Boolean): StudyAdmission<StackAutoSlotCheck> {
        val report = stackAutoShaderReport(source, slot, entrypoint())
        if (!report.compiled) throw StudyRejection("That program does not compile into stack.auto.$slot:\n" + report.lines.joinToString("\n"))
        val knobs = runCatching { StackSLStudyShader.knobs(source, entrypoint()) }.getOrDefault(emptyList())
        return StudyAdmission(StackAutoSlotCheck(report.problems, report.warnings, knobs),
            linkedMapOf("problems" to report.problems.ifEmpty { null }, "warnings" to report.warnings.ifEmpty { null }, "knobs" to knobs.size))
    }

    // The pipeline update the last publish queued: true once the new pipeline draws, false if it failed.
    @Volatile private var bound: java.util.concurrent.CompletableFuture<Boolean>? = null

    /** Bind [replacement] to the slot, live, and write it into the box's code where [expected] sits verbatim. */
    private fun publish(expected: String, replacement: String): Boolean = onMain {
        if (liveSource() != expected) return@onMain false
        val module = stackAutoModulesOf(box).firstOrNull() ?: return@onMain false
        // Armed before the slot changes, so the update cannot finish unseen.
        val done = java.util.concurrent.CompletableFuture<Boolean>()
        val listener: (Box, Boolean) -> Unit = { b, ok -> if (b === box) done.complete(ok) }
        afield.stdlib.stackAutoShaderUpdated += listener
        done.whenComplete { _, _ -> afield.stdlib.stackAutoShaderUpdated -= listener }
        bound = done
        when (module) {
            is ModulePointer -> stackAutoShaderSource(module, slot, replacement)
            is ComputeModulePointer -> stackAutoShaderSource(module, slot, replacement)
            is MeshModulePointer -> stackAutoShaderSource(module, slot, replacement)
            else -> return@onMain false
        }
        val code = box.getPropertyRaw("code", null) as? String
        val at = code?.indexOf(expected)?.takeIf { it >= 0 && expected.isNotBlank() && code.indexOf(expected, it + 1) < 0 }
        if (code != null && at != null) BoxStudies.replacer(box, "code")(code, code.substring(0, at) + replacement + code.substring(at + expected.length))
        // Rebinding the same text queues nothing, and neither does a bind that was refused.
        val took = box.stackAutoShaderSources[slot] == replacement
        if (replacement == expected || !took) done.complete(took)
        took
    }

    private fun <T> onMain(block: () -> T): T =
        if (afield.Runloop.isMainThread) block() else kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(afield.Runloop.runLoop.main) { block() } }

    /**
     * Wait (off the main thread) until the pipeline the last publish queued is drawing, so a capture shows
     * it: true when it is, false when the update failed (the old pipeline still draws), null when it did not
     * finish in time — or there was nothing to wait for, as with a test's publisher.
     */
    private fun awaitBound(timeoutMs: Long = 30_000): Boolean? {
        val done = bound ?: return null
        val ok = runCatching { done.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        if (ok == true) Thread.sleep(100) // the next frame draws it
        return ok
    }

    /** The slot in the shader now, or — [over] seconds > 0 — at [moments] moments across that long; with [alone], its program by itself too. */
    private fun pictures(evidence: String, alone: Boolean, over: Double = 0.0, moments: Int = 4): List<ToolImage> {
        val shown = if (over > 0.0) StackAutoView.overTime(box, slot, over, moments) else StackAutoView.inShader(box, slot)
        val out = mutableListOf<ToolImage>()
        shown.dataUrl?.let { out += ToolImage(it, "$evidence — ${shown.note}") }
        if (alone || shown.png == null) {
            val by = StackAutoView.alone(box, slot, revisions.revision().source, entrypoint())
            by.dataUrl?.let { out += ToolImage(it, by.note) }
        }
        return out
    }

    private fun names(r: SourceRevision<StackAutoSlotCheck>): Map<Int, String> = KnobNames.names("StackSL", StackSLStudy.ABOUT_FOR_NAMES, r.source,
        r.value.knobs.map { KnobNames.Knob(it.knob, it.line, it.token, StackSLStudyViewer.purpose(it, r.source.lines().getOrElse(it.line - 1) { "" })) },
        File(revisions.directory.parentFile.path), false) {}.orEmpty()

    /** The host GLSL around the slot: where it is declared and every line that calls it — what its input and output mean. */
    private fun host(): List<String> = listOf("vertex", "fragment", "compute", "task", "mesh").flatMap { p ->
        val text = box.getPropertyRaw(p, null) as? String ?: return@flatMap emptyList()
        text.lines().mapIndexedNotNull { i, line ->
            if (line.contains("stack.auto.$slot") || Regex("""\b${Regex.escape(slot)}\s*\(""").containsMatchIn(line)) "$p:${i + 1}: ${line.trim().take(200)}" else null
        }
    }

    /** Everything a model needs before editing: the slot's program, how the host uses it, its numbers, where it draws, history. */
    fun inspect(id: String = "current"): StudyReport {
        val r = revisions.revision(id)
        val live = StackAutoNumbers.slots(box)[slot]
        val named = names(r)
        val modules = stackAutoModulesOf(box)
        val renderer = BoxStudies.rendererAbove(box)
        return StudyReport(r.id, linkedMapOf(
            "slot" to slot, "revision" to r.id, "sourceHash" to SourceStudy.hash(r.source), "source" to r.source,
            "live" to revisions.liveState(),
            "entrypoint" to entrypoint(),
            "host" to host(),
            "hostMeaning" to "The host shader calls $slot(vec4) and uses what it returns; its input and the use of its output are in these lines, not in StackSL's own frame.",
            "drawnTo" to renderer?.let { r0 -> modules.flatMap { ModuleDestinations.of(r0, it) }.map { it.toString() } },
            "problems" to r.value.problems.ifEmpty { null }, "warnings" to r.value.warnings.ifEmpty { null },
            "knobs" to r.value.knobs.map { k ->
                val now = live?.let { StackAutoNumbers.value(box, slot, k.wire) }
                "k${k.knob} L${k.line}:${k.column} ${k.token}" + (k.takenAs?.let { " → $it" } ?: "") +
                    (named[k.knob]?.let { " [$it]" } ?: "") + (now?.takeIf { kotlin.math.abs(it - k.value) > 1e-9 }?.let { " (turned to $it, not kept)" } ?: "")
            },
            "keys" to StudyKeys.summary(File(revisions.directory.parentFile.path), KnobNames.shape("StackSL", r.source,
                r.value.knobs.map { KnobNames.Knob(it.knob, it.line, it.token, StackSLStudyViewer.purpose(it, r.source.lines().getOrElse(it.line - 1) { "" })) }),
                r.value.knobs.associate { it.knob to it.value }),
            "history" to revisions.history().takeLast(16).map { mapOf("id" to it.id, "description" to it.description) }
        ))
    }

    /** Replace the slot's program: checked, bound live, written back into the code, and shown in the shader. */
    fun edit(expectedRevision: String, expectedSourceHash: String, source: String, description: String, alone: Boolean = false,
             over: Double = 0.0, moments: Int = 4): StudyReport {
        val r = revisions.edit(expectedRevision, expectedSourceHash, source, description)
        return published(r, "after the edit", alone, over, moments)
    }

    /** Exact text replacements in the slot's program, through the same checks. */
    fun patch(expectedRevision: String, expectedSourceHash: String, edits: List<StudySourceEdit>, description: String, alone: Boolean = false,
              over: Double = 0.0, moments: Int = 4): StudyReport {
        val r = revisions.patch(expectedRevision, expectedSourceHash, edits, description)
        return published(r, "after the patch", alone, over, moments)
    }

    /** Make an earlier revision the slot's program again, as a new revision. */
    fun restore(expectedRevision: String, expectedSourceHash: String, id: String, description: String): StudyReport =
        published(revisions.restore(expectedRevision, expectedSourceHash, id, description), "restored", false)

    private fun published(r: SourceRevision<StackAutoSlotCheck>, evidence: String, alone: Boolean, over: Double = 0.0, moments: Int = 4): StudyReport {
        val live = awaitBound()
        val written = (box.getPropertyRaw("code", null) as? String)?.contains(r.source) == true
        return StudyReport(r.id, linkedMapOf("message" to "stack.auto.$slot is now ${r.id}", "revision" to r.id, "sourceHash" to SourceStudy.hash(r.source),
            // What ReloadShaders would do is done: the picture below is the new pipeline drawing.
            "boundLive" to (live == true),
            "boundNote" to when (live) {
                true -> null
                false -> "the pipeline update failed, so the window still draws the previous program; the box's code shows the error"
                null -> "the pipeline update had not finished when the picture was taken, so it may still show the previous program"
            },
            "writtenIntoCode" to written,
            "note" to if (written) null else "the program is live but not written into the box's code (it is not there verbatim), so running the box again puts the old one back",
            "problems" to r.value.problems.ifEmpty { null }, "warnings" to r.value.warnings.ifEmpty { null }), pictures(evidence, alone, over, moments))
    }

    /**
     * Turn numbers live, through the slot's block — no recompile — and show the result in the shader. With
     * [keep], write them into the program as a new revision too; without, they stay turned but unkept.
     */
    fun setNumbers(numbers: Map<Int, Double>, keep: Boolean, expectedRevision: String, expectedSourceHash: String, description: String): StudyReport {
        val r = revisions.revision()
        numbers.keys.forEach { require(it in r.value.knobs.indices) { "No knob $it; stack.auto.$slot has ${r.value.knobs.size}" } }
        numbers.forEach { (k, v) -> StackAutoNumbers.set(box, slot, r.value.knobs[k].wire, v) }
        Thread.sleep(150)
        if (!keep) return StudyReport(r.id, linkedMapOf("message" to "turned ${numbers.size} numbers live, not kept", "revision" to r.id), pictures("turned live", false))
        val source = StackSLStudyShader.setKnobs(r.source, numbers, entrypoint())
        return edit(expectedRevision, expectedSourceHash, source, description)
    }

    /** One number at several values, each drawn in the shader for real, then put back as it was. */
    fun sweep(knob: Int, values: List<Double>): StudyReport {
        val r = revisions.revision()
        require(knob in r.value.knobs.indices) { "No knob $knob; stack.auto.$slot has ${r.value.knobs.size}" }
        require(values.size in 2..9) { "Sweep 2..9 values" }
        val k = r.value.knobs[knob]
        val before = StackAutoNumbers.value(box, slot, k.wire) ?: k.value
        val images = mutableListOf<ToolImage>()
        try {
            values.forEach { v ->
                StackAutoNumbers.set(box, slot, k.wire, v)
                Thread.sleep(150)
                StackAutoView.inShader(box, slot).let { p -> p.dataUrl?.let { images += ToolImage(it, "k$knob = $v — ${p.note}") } }
            }
        } finally {
            StackAutoNumbers.set(box, slot, k.wire, before)
        }
        return StudyReport(r.id, linkedMapOf("knob" to "k$knob ${k.token}", "values" to values, "putBack" to before,
            "note" to "each picture is the real shader with only this number changed; nothing was kept"), images)
    }

    /** The slot as it is drawing now, in the shader, or across [over] seconds — and, with [alone], its program by itself. */
    fun observe(alone: Boolean, over: Double = 0.0, moments: Int = 4): StudyReport {
        val r = revisions.revision()
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "slot" to slot), pictures(if (over > 0.0) "over time" else "now", alone, over, moments))
    }

    /** Check the slot's live program in as a revision, if it has none or has changed outside the study. */
    fun checkIn(description: String) {
        val live = liveSource()
        val current = revisions.currentId()
        if (live.isNotBlank() && (current == SourceStudy.EMPTY || revisions.revision().source != live))
            revisions.edit(current, SourceStudy.hash(live), live, description)
    }
}

/** A box's slot studies, one per slot, made on first use. */
object StackAutoStudies {
    private val studies = ConcurrentHashMap<Pair<Box, String>, StackAutoSlotStudy>()

    /** [box]'s study of [slot], with the slot's live program checked in. */
    fun study(box: Box, slot: String): StackAutoSlotStudy {
        val s = studies.getOrPut(box to slot) { StackAutoSlotStudy(box, slot, File(BoxStudies.directory(box), "stack.auto/$slot")).also { it.revisions.resume() } }
        s.checkIn("the slot's program as the box bound it")
        return s
    }

    /** The slots of [box] a study can take: those with a program bound. */
    fun slots(box: Box): List<String> = box.stackAutoShaderSources.filterValues { it.isNotBlank() }.keys.sorted()
}
