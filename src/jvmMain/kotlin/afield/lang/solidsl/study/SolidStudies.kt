@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.lang.solidsl.study

import afield.ai.tools.AgentToolExtensions
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolImage
import afield.app.commands.Action
import afield.app.commands.actions
import afield.document.graph.Box
import afield.lang.solidsl.SolidExpr
import afield.lang.solidsl.bounds
import afield.lang.solidsl.exactSolid
import afield.lang.solidsl.fieldNotes
import afield.lang.solidsl.simplified
import afield.lang.solidsl.toGlsl
import afield.runtime.kotlin.createKotlinScriptEngine
import afield.runtime.languages.LanguageRoots
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.newRoot
import afield.runtime.lifecycle.plugins
import afield.study.evidence.StudyEvidence
import afield.study.evidence.StudyReport
import afield.study.source.BoxStudies
import afield.study.source.LiveStudy
import afield.study.source.SourceFramesStudy
import afield.study.source.StudyCall
import afield.study.source.StudyRejection
import afield.study.source.StudyRequest
import afield.math.functions.StudySourceEdit
import afield.runtime.lifecycle.inMain
import afield.app.notifications.notify
import afield.lang.solidsl.SolidLowering
import afield.study.source.StudyToolClasses
import afield.study.source.registerStudyTools
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import kotlin.script.experimental.api.ResultValue

/**
 * SolidSL studies: a box whose Kotlin returns a [SolidExpr] (or a [SolidScene] with GLSL shading of
 * its own), kept as checked revisions an AI4 conversation edits, looks at from any side, cuts open
 * and versions, while the window above the box turns the current revision.
 *
 * **Make a SolidSL Study** marks the selected box ([SolidStudies.MARK]), gives an empty one
 * [SolidStudies.STARTER], and puts a **SolidSL window** root above it that opens a window. The tools
 * are the standard study verbs (through [SourceFramesStudy], with time as the turntable) plus
 * [OrbitSolidStudy], [DescribeSolidStudy] and [SolidStudyGuide].
 */
object SolidStudies {
    /** The property that marks a study, and its value for a SolidSL one. */
    const val MARK = "aiStudy"
    const val KIND = "solidsl"

    /** The main-language id the window root is marked with, so [LanguageRoots] can open its window. */
    const val LANGUAGE = "solidsl"

    /** The output channel the study's turntable strip is shown in. */
    const val CHANNEL = "solid"

    /** Where the window goes, and its size. */
    val WINDOW = afield.app.protocol.Rect(80.0, 80.0, 800.0, 600.0)

    /** The source a new study starts from: a sphere, waiting to be talked into something else. */
    val STARTER = """
        |// A solid, written in SolidSL. Describe what you want in this box's AI4 chat.
        |// The last expression is the solid: a SolidExpr, or solid.shaded(glsl) for shading of its own.
        |import afield.lang.solidsl.*
        |import afield.lang.solidsl.study.*
        |
        |sphere(1.0)
    """.trimMargin()

    /** Whether a conversation focused on [box]'s [property] is about a SolidSL study. */
    fun isStudy(box: Box, property: String): Boolean = property == "code" && box.getPropertyRaw(MARK, null) == KIND

    private val compiled = object : LinkedHashMap<String, SolidScene>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SolidScene>?) = size > 24
    }

    /**
     * [source]'s scene: the box Kotlin compiled on a fresh engine with the SolidSL imports, its last
     * expression a [SolidExpr] or [SolidScene]. Cached by source text.
     */
    fun compile(source: String): SolidScene {
        synchronized(compiled) { compiled[source] }?.let { return it }
        val engine = createKotlinScriptEngine(filePrefix = "solid_study_${System.nanoTime()}")
        val diagnostics = mutableListOf<String>()
        try {
            engine.execAndWait("import afield.lang.solidsl.*\nimport afield.lang.solidsl.study.*\nimport kotlin.math.*",
                onCompileError = { ds -> diagnostics += ds.map { it.message } }, onEvalException = { throw it })
            val value = engine.execAndWait(source,
                onCompileError = { ds -> diagnostics += ds.map { d -> (d.location?.start?.let { "line ${it.line}: " } ?: "") + d.message } },
                onEvalError = { ds -> diagnostics += ds.map { it.message } },
                onEvalException = { throw it })
            if (value is ResultValue.Error) throw StudyRejection("The Kotlin threw: ${value.error}")
            val scene = when (val v = (value as? ResultValue.Value)?.value) {
                is SolidScene -> v
                is SolidExpr -> SolidScene(v)
                else -> throw StudyRejection(if (diagnostics.isNotEmpty()) "The Kotlin does not compile:\n" + diagnostics.joinToString("\n").take(6000)
                    else "The last expression must be a SolidExpr or a SolidScene (solid.shaded(glsl)); it was ${v?.let { it::class.simpleName } ?: "nothing"}")
            }
            synchronized(compiled) { compiled[source] = scene }
            return scene
        } finally {
            engine.dispose()
        }
    }

    /** [box]'s study, created on first use. */
    fun study(box: Box): SolidStudy {
        (box.getPropertyRaw("_solidStudy", null) as? SolidStudy)?.let { return it }
        synchronized(lockOf(box)) {
            (box.getPropertyRaw("_solidStudy", null) as? SolidStudy)?.let { return it }
            return SolidStudy(box).also { box.properties["_solidStudy"] = it }
        }
    }

    /** [study], with its source checked in as the first revision if it has none. Off the main thread. */
    fun ready(box: Box): SolidStudy {
        val s = study(box)
        if (s.frames.revisions.currentId() == afield.study.source.SourceStudy.EMPTY) {
            val source = box.getPropertyRaw("code", "") as? String ?: ""
            s.frames.edit(StudyCall.Edit("empty", afield.study.source.SourceStudy.hash(source), source, "the starting solid"))
        }
        s.refresh()
        return s
    }

    private val locks = java.util.WeakHashMap<Box, Any>()
    private fun lockOf(box: Box): Any = synchronized(locks) { locks.getOrPut(box) { Any() } }
}

/**
 * One box's SolidSL study: [frames] holds its revisions and answers the standard verbs; every verb
 * that publishes refreshes the window and the box's turntable strip.
 */
class SolidStudy internal constructor(val box: Box) : LiveStudy {
    /** Draws the box's frames: the window's GPU, or the CPU. */
    val renderer = SolidFrames({ BoxStudies.rendererAbove(box) }, SolidStudies::compile)

    /** The revisions and the standard verbs. */
    val frames: SourceFramesStudy = SourceFramesStudy(File(BoxStudies.directory(box), "solid"), "kt",
        { box.getPropertyRaw("code", "") as? String ?: "" }, BoxStudies.replacer(box, "code"), renderer,
        check = { source -> renderer.check(SolidStudies.compile(source))?.let { throw StudyRejection(it) } },
        title = "SolidSL", aspect = 4.0 / 3.0, defaultSeconds = listOf(0.0, 3.0, 6.0, 9.0))

    private val live = SolidLive({ BoxStudies.rendererAbove(box) }, box.id)

    /** The line the person's editor cursor is on. */
    fun personAt(): Map<String, Any?>? = BoxStudies.personAt(BoxStudies.editorLine(box, "code"), frames.revisions.liveSource())

    /** Draw the current revision in the window and in the box's strip. */
    fun refresh() {
        val scene = runCatching { SolidStudies.compile(frames.revisions.revision().source) }.getOrNull() ?: return
        runCatching { live.show(scene) }
        runCatching {
            val tiles = mutableListOf<afield.lang.stacksl.cpu.CpuRaster>()
            renderer.renderViews(scene, 240, 180, listOf(0.0, 3.0, 6.0).map(SolidView::turntable)) { _, r -> tiles += r }
            val bytes = StudyEvidence.sheet("SolidSL · ${frames.revisions.currentId()}", frames.revisions.revision().description.take(100),
                listOf(tiles.map { StudyEvidence.tile(StudyEvidence.presented(it), "") }), 240, tileHeight = 180)
            BoxStudies.show(box, SolidStudies.CHANNEL, "<img style='width:100%' src='data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}'>")
        }
    }

    private fun <T : StudyReport> published(r: T): T { refresh(); return r }

    override fun inspect(q: StudyRequest.Inspect): StudyReport {
        val r = frames.inspect(q)
        return r.copy(facts = r.facts + ("personAt" to personAt()))
    }
    override fun edit(q: StudyRequest.Edit) = published(frames.edit(q))
    override fun patch(q: StudyRequest.Patch) = published(frames.patch(q))
    override fun setNumbers(q: StudyRequest.SetNumbers) = published(frames.setNumbers(q))
    override fun observe(q: StudyRequest.Observe) = frames.observe(q)
    override fun compare(q: StudyRequest.Compare) = frames.compare(q)
    override fun sweep(q: StudyRequest.Sweep) = frames.sweep(q)
    override fun tryVariants(q: StudyRequest.TryVariants) = frames.tryVariants(q)
    override fun adoptVariant(q: StudyRequest.Adopt) = published(frames.adoptVariant(q))
    override fun restore(q: StudyRequest.Restore) = published(frames.restore(q))

    /** [revision] from each of [views], as one sheet, with what each view saw. */
    fun orbit(revision: String, views: List<SolidView>, size: Int): StudyReport {
        if (views.isEmpty() || views.size > 9) throw StudyRejection("Ask for 1..9 views")
        val r = frames.revisions.revision(revision)
        val scene = SolidStudies.compile(r.source)
        val w = size.takeIf { it > 0 }?.coerceIn(96, 1024) ?: if (views.size == 1) 640 else 320
        val h = w * 3 / 4
        val shown = arrayOfNulls<afield.lang.stacksl.cpu.CpuRaster>(views.size)
        renderer.renderViews(scene, w, h, views) { i, raster -> shown[i] = raster }
        val background = scene.background
        val tiles = views.mapIndexed { i, v ->
            StudyEvidence.tile(StudyEvidence.presented(shown[i]!!), "yaw ${fmt(v.yawDegrees)}° pitch ${fmt(v.pitchDegrees)}°" +
                (if (v.section.isNotBlank()) " · cut ${v.section}" else ""), "distance ${fmt(v.distance)}")
        }
        val bytes = StudyEvidence.sheet("SolidSL orbit · ${r.id}", r.description.take(120), tiles.chunked(3), w, tileHeight = h)
        val file = File(File(frames.directory, "observations").also { it.mkdirs() }, "${r.id}-orbit-${System.currentTimeMillis()}.png").also { it.writeBytes(bytes) }
        return StudyReport(r.id, linkedMapOf("revision" to r.id, "views" to views.mapIndexed { i, v ->
            linkedMapOf("view" to v, "coverage" to coverage(shown[i]!!, background)) },
            "imageFile" to file.absolutePath, "renderedBy" to renderer.description),
            listOf(ToolImage("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
                "${r.id} from ${views.size} view(s); the caption under each says where the camera was")))
    }

    /** The fraction of [raw]'s pixels that are not the scene's background: how much of the frame the solid fills. */
    private fun coverage(raw: afield.lang.stacksl.cpu.CpuRaster, background: Int): Double {
        val bg = intArrayOf(16, 8, 0).let { s -> FloatArray(3) { k -> SolidStudyShader.srgbToLinear(((background shr s[k]) and 0xff) / 255.0).toFloat() } }
        var n = 0
        for (p in 0 until raw.width * raw.height) {
            var d = 0f
            for (c in 0 until 3) d = maxOf(d, kotlin.math.abs(raw.rgba[p * 4 + c] - bg[c]))
            if (d > 0.08f) n++
        }
        return n.toDouble() / (raw.width * raw.height)
    }

    /** What [revision]'s solid is: bounds, tree size, its shader, whether the exact path takes it. */
    fun describe(revision: String, exact: Boolean): StudyReport {
        val r = frames.revisions.revision(revision)
        val scene = SolidStudies.compile(r.source)
        val e = scene.solid
        val glsl = e.toGlsl()
        val simple = e.simplified()
        fun nodes(x: SolidExpr): Int = 1 + when (x) {
            is SolidExpr.Union -> x.children.sumOf(::nodes)
            is SolidExpr.Intersect -> x.children.sumOf(::nodes)
            is SolidExpr.Difference -> x.children.sumOf(::nodes)
            is SolidExpr.SmoothUnion -> nodes(x.a) + nodes(x.b)
            is SolidExpr.SmoothDifference -> nodes(x.a) + nodes(x.b)
            is SolidExpr.Translate -> nodes(x.child)
            is SolidExpr.Rotate -> nodes(x.child)
            is SolidExpr.Scale -> nodes(x.child)
            is SolidExpr.Mirror -> nodes(x.child)
            is SolidExpr.Round -> nodes(x.child)
            is SolidExpr.Shell -> nodes(x.child)
            else -> 0
        }
        val b = e.bounds()
        val exactResult = if (!exact) "not asked (exact = true builds it with Manifold)" else runCatching {
            when (val lowered = e.exactSolid()) {
                is SolidLowering.Lowered -> "builds: Manifold makes a closed solid".also { (lowered.value as? AutoCloseable)?.close() }
                is SolidLowering.Refused -> "refused: ${lowered.why}"
            }
        }.getOrElse { "failed: ${it.message}" }
        return StudyReport(r.id, linkedMapOf("revision" to r.id,
            "bounds" to b?.let { mapOf("min" to listOf(it.min.x, it.min.y, it.min.z), "max" to listOf(it.max.x, it.max.y, it.max.z)) },
            "nodes" to nodes(e), "simplifiedNodes" to nodes(simple),
            "glslStatements" to glsl.statementCount, "stepScale" to glsl.stepScale, "fieldNotes" to e.fieldNotes(),
            "ownShading" to scene.shade.isNotBlank(), "exact" to exactResult,
            "tree" to e.toString().take(1500)))
    }

    private fun fmt(v: Double) = StudyEvidence.format(v)
}

// ---- The tools -------------------------------------------------------------------------------

@JsonClassDescription("Read how to work on a SolidSL study: the vocabulary, the coordinate convention (z up), how frames and views are drawn, idioms that keep the Kotlin readable, the shade() GLSL contract, traps. Call it once, before your first edit in a conversation.")
data class SolidStudyGuide(@JsonPropertyDescription("Ignored; reserved") val search: String = "")

@JsonClassDescription("Read this SolidSL study: the checked Kotlin, whether the editor holds an unchecked human edit (dirty; then liveSource is what is in the box), revision history, knobs (typed numbers, by index), held variants, and personAt — the line the person's editor cursor is on. Inspect once before editing and reuse liveSourceHash.")
data class InspectSolidStudy(override val revision: String = "current") : StudyRequest.Inspect

@JsonClassDescription("Replace the whole Kotlin. It is compiled (the last expression must be a SolidExpr or solid.shaded(glsl)), its shader built on the GPU, then activated as a new revision with turntable preview frames against the parent. Needs expectedRevision and expectedSourceHash from Inspect; a stale guard or a failed compile changes nothing. Prefer PatchSolidStudy for local edits.")
data class EditSolidStudy(
    override val expectedRevision: String,
    override val expectedSourceHash: String,
    @JsonPropertyDescription("The complete Kotlin. Keep it readable: one named val per part with a comment saying what it is, the solid as the last expression.")
    override val source: String,
    override val description: String,
    @JsonPropertyDescription("Turntable times for the preview: t seconds is yaw 35 + 30t degrees; empty chooses 0, 3, 6, 9")
    override val previewSeconds: List<Double> = emptyList(),
) : StudyRequest.Edit, StudyRequest.Preview

@JsonClassDescription("Preferred for local edits: exact, unique, non-overlapping text replacements in the live Kotlin, checked and activated like EditSolidStudy.")
data class PatchSolidStudy(override val expectedRevision: String, override val expectedSourceHash: String,
    override val edits: List<StudySourceEdit>, override val description: String, override val previewSeconds: List<Double> = emptyList()) : StudyRequest.Patch, StudyRequest.Preview

/** One knob to set, by its index in the live source's knob list. */
data class SolidNumberEdit(override val knob: Int, override val value: Double) : afield.study.ui.NumberEdit

@JsonClassDescription("Set typed numbers (knobs, by index from Inspect) to new values, rewritten in place, then checked and activated like an edit.")
data class SetSolidNumbers(override val expectedRevision: String, override val expectedSourceHash: String,
    override val numbers: List<SolidNumberEdit>, override val description: String, override val previewSeconds: List<Double> = emptyList()) : StudyRequest.SetNumbers, StudyRequest.Preview

@JsonClassDescription("See a revision on the turntable: t seconds is the camera at yaw 35 + 30t degrees, pitch 24, so 0, 3, 6, 9 walk round it. Sent as an image with luma, detail and palette per frame. compareTo adds that revision's frames and a difference map. For chosen angles, close-ups or a cut, use OrbitSolidStudy.")
data class ObserveSolidStudy(override val revision: String = "current", override val seconds: List<Double> = emptyList(),
    @JsonPropertyDescription("Pixels along the longer side, 16..512; 0 chooses") override val size: Int = 0,
    override val compareTo: String = "") : StudyRequest.Observe, StudyRequest.Sized

@JsonClassDescription("Point the camera yourself: draw a revision from 1..9 views, each {yawDegrees, pitchDegrees, distance, section, sectionOffset}. yaw 0 looks from -y (the front), 90 from +x; pitch 90 looks straight down, negative from below; distance is in bounding radii (2.2 frames the whole solid, 1.3 is a close-up). section x, y or z cuts the solid open through its centre (moved by sectionOffset) on the camera's side, to see walls and insides. Returns the image and how much of each frame the solid fills.")
data class OrbitSolidStudy(val revision: String = "current", val views: List<SolidView> = listOf(SolidView()),
    @JsonPropertyDescription("Pixels across each view, 96..1024; 0 chooses 640 for one view and 320 for several") val size: Int = 0)

@JsonClassDescription("Describe a revision's solid without drawing it: bounds, how many nodes the tree has (and after the simplifier), the GLSL statement count and safe march step, field notes (nodes that are not true distances, which slow the march), whether it has its own shading, and with exact = true whether Manifold's exact path builds it.")
data class DescribeSolidStudy(val revision: String = "current", val exact: Boolean = false)

@JsonClassDescription("Draw one knob at 2..9 values side by side at one turntable time, without publishing. Use it to choose a value before SetSolidNumbers.")
data class SweepSolidNumber(override val revision: String = "current", override val knob: Int, override val values: List<Double>, override val seconds: Double = 0.0) :
    StudyRequest.Sweep, StudyRequest.Revisioned, StudyRequest.At

@JsonClassDescription("Speculate: compile and draw 1..8 candidate sources beside the current one without publishing. Each is {label, source} or {label, edits}, with a note. They are held for AdoptSolidVariant.")
data class TrySolidVariants(override val variants: List<StudyCall.Variant>, override val seconds: List<Double> = emptyList()) : StudyRequest.TryVariants

@JsonClassDescription("Publish a held variant by label as a new revision (guards as for an edit).")
data class AdoptSolidVariant(override val expectedRevision: String, override val expectedSourceHash: String, override val label: String, override val description: String = "") :
    StudyRequest.Adopt

@JsonClassDescription("Make an earlier revision current again, as a new revision; history is only appended to.")
data class RestoreSolidRevision(override val expectedRevision: String, override val expectedSourceHash: String, override val revision: String, override val description: String = "") :
    StudyRequest.Restore

@JsonClassDescription("Check what an edit preserved, on the turntable: before | after | difference per time; mode rgb or luma. allowedRegion [u0,v0,u1,v1] (0..1 of the frame, y up) is where change may happen.")
data class CompareSolidStudy(override val beforeRevision: String, override val afterRevision: String = "current", override val seconds: List<Double> = emptyList(),
    override val mode: String = "rgb", override val allowedRegion: List<Double> = emptyList()) : StudyRequest.Compare

/** The [SolidStudyGuide] tool's answer. */
data class SolidGuideText(val text: String) : afield.ai.tools.SummarisesForToolLog {
    override fun toolLogSummary(): String = "guide ${text.length} chars"
}

/** The working guide the [SolidStudyGuide] tool serves. */
object SolidStudyGuides {
    /** How to work on a SolidSL study. */
    val working: String by lazy {
        SolidStudyGuides::class.java.getResourceAsStream("/afield/solidsl/study/working.md")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("missing resource afield/solidsl/study/working.md")
    }
}

private val onAStudy: (ToolExecutionContext) -> Boolean = { ctx -> ctx.focus?.let { SolidStudies.isStudy(it.box, it.property) } == true }
private fun studyBox(ctx: ToolExecutionContext): Box = ctx.focus?.box ?: throw IllegalStateException("SolidSL study tools work on the box whose chat is open")
private const val LIBRARY = "afield.lang.solidsl.study"

/** Register the SolidSL study tools once, for every conversation opened on a SolidSL study's `code`. */
@RunsAt(plugins)
fun installSolidStudyTools() {
    val r = AgentToolExtensions
    r.register(SolidStudyGuide::class.java, onAStudy, LIBRARY) { _, _ -> SolidGuideText(SolidStudyGuides.working) }
    registerStudyTools(LIBRARY, onAStudy, { SolidStudies.ready(studyBox(it)) }, StudyToolClasses(
        inspect = InspectSolidStudy::class.java, edit = EditSolidStudy::class.java, patch = PatchSolidStudy::class.java,
        setNumbers = SetSolidNumbers::class.java, observe = ObserveSolidStudy::class.java, compare = CompareSolidStudy::class.java,
        sweep = SweepSolidNumber::class.java, tryVariants = TrySolidVariants::class.java, adoptVariant = AdoptSolidVariant::class.java,
        restore = RestoreSolidRevision::class.java))
    r.register(OrbitSolidStudy::class.java, onAStudy, LIBRARY) { ctx, q -> SolidStudies.ready(studyBox(ctx)).orbit(q.revision, q.views, q.size) }
    r.register(DescribeSolidStudy::class.java, onAStudy, LIBRARY) { ctx, q -> SolidStudies.ready(studyBox(ctx)).describe(q.revision, q.exact) }
}

/** Keep the `aiStudy` mark with the document. */
@RunsAt(plugins)
fun installSolidStudyMark() {
    Box.ioPersistentProperties.add(SolidStudies.MARK)
}

private val opening = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "solid-studies").apply { isDaemon = true } }

private fun openWindowAndShow(box: Box) {
    runBlocking { withContext(Runloop.runLoop.main) { LanguageRoots.ensure(box, SolidStudies.LANGUAGE, "SolidSL", SolidStudies.WINDOW) } }
        ?.let { afield.app.diagnostics.logging.lprintln("warning,study", "SolidSL study '${box.name}': $it") }
    opening.execute {
        runCatching { SolidStudies.ready(box) }
            .onFailure { afield.app.diagnostics.logging.lprintln("warning,study", "showing SolidSL study '${box.name}' failed: $it") }
    }
}

/** Show each SolidSL study when its document opens: open its window and check its source in. */
@RunsAt(plugins)
fun installSolidStudyWatcher() {
    afield.runtime.TopologyWatcher("solidsl-study") { boxes ->
        boxes.filter { it.getPropertyRaw(SolidStudies.MARK, null) == SolidStudies.KIND && it.getPropertyRaw("_solidStudyShown", null) != true }
            .forEach { box ->
                box.properties["_solidStudyShown"] = true
                opening.execute { runCatching { openWindowAndShow(box) } }
            }
    }.start()
}

/**
 * "Make a SolidSL Study": the selected box becomes a SolidSL study beneath a **SolidSL window** root —
 * a new one just above it, if it has none — which opens a window turning the solid.
 */
@RunsAt(newRoot)
fun installSolidStudyAction(root: Box) {
    root.actions["make-solidsl-study"] = Action(
        desc = "Make a SolidSL Study",
        detail = "The selected box becomes a SolidSL study: Kotlin that returns a solid, turning in a window of its own. Its AI4 chat gets tools that check, draw from any side, cut open and version it.",
    ) { selection ->
        inMain {
        val studies = LanguageRoots.adopt(selection, SolidStudies.LANGUAGE, "SolidSL window", "SolidSL study", "code", SolidStudies.STARTER)
        studies.forEach { box ->
            // A study is checked in by its tools, not run as a scene: it does not need autorun.
            box.setPropertyRaw("autorun", false, doResync = true)
            box.setPropertyRaw(SolidStudies.MARK, SolidStudies.KIND, doResync = true)
            box.properties["_solidStudyShown"] = true
            opening.execute { runCatching { openWindowAndShow(box) } }
            notify("'${box.name}' is a SolidSL study: open its AI4 chat and describe a solid", "study")
        }
        }
    }
}
