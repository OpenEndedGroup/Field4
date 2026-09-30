package afield.stacksl.study

import afield.Box
import afield.RunsAt
import afield.ai.core.AgentToolExtensions
import afield.ai.core.ToolExecutionContext
import afield.plugins
import afield.stdlib.StudySourceEdit
import afield.stdlib.LiveStudy
import afield.stdlib.StudyReport
import afield.stdlib.StudyRequest
import afield.stdlib.StudyToolClasses
import afield.stdlib.registerStudyTools
import afield.stdlib.numbers
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// The tools an AI4 conversation gets when it is opened on the code of a box whose shaders have
// `{{ stack.auto.<slot> }}` slots with programs bound. Each works on one slot's study
// (StackAutoStudies), inside the box's own shader: the end result is what the tools show.

@JsonClassDescription("Read how to work on a stack.auto slot: StackSL inside someone's own shader. Call it once, before your first edit in a conversation; with search, look a word up in the StackSL words guide instead.")
data class StackAutoSlotGuide(
    @JsonPropertyDescription("A word or phrase to look up in the StackSL words guide; empty returns how to work on a slot") val search: String = ""
)

@JsonClassDescription("Read a stack.auto slot's study: its program, the host shader's lines that declare and call it (what its input and output mean — the host decides, not StackSL's own frame), where the module is drawn (the window, a render target), the compiler's problems and warnings, its numbers (knobs: index, line:column, as typed, the operand the compiler took it as, the card's name in brackets, and whether it is turned live but not kept), keys, and history. slot may be empty when the box has only one. Inspect once before editing and reuse sourceHash.")
data class InspectStackAutoSlot(override val slot: String = "", override val revision: String = "current") : StackAutoSlotRequest, StudyRequest.Inspect

@JsonClassDescription("Replace a slot's whole StackSL program. It must compile into this slot (the host shader's own samplers and push constants are fine); it is then bound live, the slot recompiles in place, and it is written back into the box's Kotlin where it sits verbatim. Returns a picture of the result in the host shader — the real end result, or with over a sheet of moments across that many seconds. Needs expectedRevision and expectedSourceHash from Inspect. Prefer PatchStackAutoSlot or SetStackAutoNumbers for small changes.")
data class EditStackAutoSlot(override val slot: String = "", override val expectedRevision: String, override val expectedSourceHash: String,
    @JsonPropertyDescription("Complete StackSL source: one transform per line, # comments naming what each stage does") override val source: String,
    override val description: String,
    @JsonPropertyDescription("Also draw the program alone on StackSL's own frame, a debugging view whose input is not the host's") val alone: Boolean = false,
    @JsonPropertyDescription("Seconds to watch the shader for; above 0 the picture is a numbered sheet of real frames across that long — use it when the host animates the slot (by time it pushes), as one frame shows one moment") val over: Double = 0.0,
    @JsonPropertyDescription("How many moments the sheet holds, 2..9, evenly spaced across over") val moments: Int = 4) : StackAutoSlotRequest, StudyRequest.Edit

@JsonClassDescription("Preferred for local edits: exact, unique, non-overlapping text replacements in a slot's program, checked and bound like EditStackAutoSlot, with a picture (or, with over, a sheet of moments) in the host shader.")
data class PatchStackAutoSlot(override val slot: String = "", override val expectedRevision: String, override val expectedSourceHash: String,
    override val edits: List<StudySourceEdit>, override val description: String, val alone: Boolean = false,
    @JsonPropertyDescription("Seconds to watch the shader for; above 0 the picture is a numbered sheet of real frames across that long — use it when the host animates the slot (by time it pushes), as one frame shows one moment") val over: Double = 0.0,
    @JsonPropertyDescription("How many moments the sheet holds, 2..9, evenly spaced across over") val moments: Int = 4) : StackAutoSlotRequest, StudyRequest.Patch

/** One number to turn: knob index from Inspect, and its new value. */
data class StackAutoNumberEdit(override val knob: Int, override val value: Double) : afield.stdlib.NumberEdit

@JsonClassDescription("Turn a slot's numbers live, through its uniform block — no recompile — and see the host shader with them. They stay turned but unkept unless keep is true, which writes them into the program as a new revision (then expectedRevision and expectedSourceHash are needed). Use it to try values; keep what the person likes.")
data class SetStackAutoNumbers(override val slot: String = "", override val numbers: List<StackAutoNumberEdit>, val keep: Boolean = false,
    override val expectedRevision: String = "", override val expectedSourceHash: String = "", override val description: String = "") : StackAutoSlotRequest, StudyRequest.SetNumbers

@JsonClassDescription("See a slot as it is drawing now, in the host shader — wherever its module is drawn — or, with over, at several moments across that many seconds; with alone, its program by itself on StackSL's own frame too (a debugging view; its input is not the host's).")
data class ObserveStackAutoSlot(val slot: String = "", val alone: Boolean = false,
    @JsonPropertyDescription("Seconds to watch the shader for; above 0 the picture is a numbered sheet of real frames across that long — use it when the host animates the slot (by time it pushes), as one frame shows one moment") val over: Double = 0.0,
    @JsonPropertyDescription("How many moments the sheet holds, 2..9, evenly spaced across over") val moments: Int = 4)

@JsonClassDescription("Draw one of a slot's numbers at 2..9 values, each in the real host shader with only that number changed, then put it back. Nothing is kept. Use it to choose a value before SetStackAutoNumbers.")
data class SweepStackAutoNumber(override val slot: String = "", override val knob: Int, override val values: List<Double>) : StackAutoSlotRequest, StudyRequest.Sweep

@JsonClassDescription("Make an earlier revision a slot's program again, as a new revision, bound live and written back into the code like an edit.")
data class RestoreStackAutoRevision(override val slot: String = "", override val expectedRevision: String, override val expectedSourceHash: String,
    override val revision: String, override val description: String = "") : StackAutoSlotRequest, StudyRequest.Restore

@JsonClassDescription("Check what an edit preserved, in the host shader: each revision is bound live in turn and captured where the module draws, then the slot's own program is put back. mode rgb compares displayed colour, luma brightness only; allowedRegion [u0,v0,u1,v1] (0..1 of the capture, y up) is where change may happen, and outside it everything must stay within one level. A host that animates the slot differs between the two captures by its own motion too.")
data class CompareStackAutoSlot(override val slot: String = "", override val beforeRevision: String, override val afterRevision: String = "current",
    override val seconds: List<Double> = emptyList(), override val mode: String = "rgb", override val allowedRegion: List<Double> = emptyList()) :
    StackAutoSlotRequest, StudyRequest.Compare

/** A candidate program for a slot: a complete source, or exact edits to the slot's live program. */
data class StackAutoVariant(override val label: String, override val source: String = "", override val edits: List<StudySourceEdit> = emptyList(),
    override val note: String = "") : StudyRequest.Variant

@JsonClassDescription("Speculate in the host shader: check 1..8 candidate programs for a slot, bind each live in turn and capture it where the module draws, then put the slot's own program back. Nothing is kept or written into the code. Each is a complete source or exact edits, with a short label (A, B or 'ember') and note; they are held for AdoptStackAutoVariant.")
data class TryStackAutoVariants(override val slot: String = "", override val variants: List<StackAutoVariant>, override val seconds: List<Double> = emptyList()) :
    StackAutoSlotRequest, StudyRequest.TryVariants

@JsonClassDescription("Publish a held slot variant by label as a new revision: bound live and written back into the code like an edit (guards as for an edit).")
data class AdoptStackAutoVariant(override val slot: String = "", override val expectedRevision: String, override val expectedSourceHash: String,
    override val label: String, override val description: String = "") : StackAutoSlotRequest, StudyRequest.Adopt

/** A stack.auto request names the slot it is about; empty when the box has only one. */
interface StackAutoSlotRequest { val slot: String }

/** The [StackAutoSlotGuide] tool's answer. */
data class StackAutoGuideText(val text: String) : afield.ai.core.SummarisesForToolLog {
    override fun toolLogSummary(): String = "guide ${text.length} chars"
}

private val GUIDE = """
    A stack.auto slot is StackSL compiled into a function, vec4 NAME(vec4 NAME_in), inside the box's own GLSL.
    The host shader decides what the input is and what the output means: read InspectStackAutoSlot's host lines
    first. The input is often vec4(fragUV, 0, 0) — uv is then the host's coordinates — but it may be a position,
    a velocity or a texture sample, and the output may be a colour, a height or a force. `input` pushes the whole
    vec4; `input x` … `input w` push one component of it, in all four places, and `uv` is `input xy`.

    Time is the host's too: when the host passes it in (often as input w), the slot moves by itself and one picture
    is one moment. Give Edit, Patch and Observe over (seconds) for a numbered sheet of real frames across that long;
    cover a whole cycle of whatever repeats.

    The evidence is the host shader's real result: the window the module draws in (all of it, with whatever else
    draws there), a render target it draws into, or — for a ping-pong or stereo target — the module drawn once more
    by itself. The alone view is StackSL's own frame and input; use it to debug a stage, never to judge the result.

    Numbers turn live through the slot's uniform block, with no recompile: SetStackAutoNumbers and
    SweepStackAutoNumber are cheap, so try values before you edit text. Keep only what the person likes; a turned
    number that is not kept is lost when the box runs again. An edit is bound live and written back into the Kotlin
    string it came from; if the program is built in Kotlin rather than written out, the edit is live only, and the
    reply says so. There is nothing to compile or reload after an edit: the reply's picture is already the result.

    To choose between ideas, TryStackAutoVariants draws each candidate in the host shader (bound live in turn, then
    the slot's own program put back; nothing is kept) and holds them for AdoptStackAutoVariant. CompareStackAutoSlot
    draws two revisions the same way and says what changed outside an allowed region — remembering that a host
    which animates the slot also moves between the two captures.
    The words are StackSL's: this guide with search looks one up.
""".trimIndent()

private val onASlotBox: (ToolExecutionContext) -> Boolean = { ctx ->
    ctx.focus?.let { it.property == "code" && StackAutoStudies.slots(it.box).isNotEmpty() } == true
}

private val LIBRARY = "afield.stacksl.study.stackauto"

private fun box(ctx: ToolExecutionContext): Box =
    ctx.focus?.box ?: throw IllegalStateException("stack.auto slot tools work on the box whose chat is open")

private fun study(ctx: ToolExecutionContext, slot: String): StackAutoSlotStudy {
    val b = box(ctx)
    val slots = StackAutoStudies.slots(b)
    val name = slot.ifBlank { slots.singleOrNull() ?: throw IllegalArgumentException("This box has slots ${slots.joinToString()}; name one") }
    require(name in slots) { "No bound slot $name; this box has ${slots.joinToString()}" }
    return StackAutoStudies.study(b, name)
}

/**
 * The slot studies of the focused box through the standard verbs: each request names its slot, so this
 * routes it to that slot's [StackAutoSlotStudy]. Observe stays a stack.auto extra, since what it takes
 * (over, moments, alone) is not a revision at chosen times.
 */
internal class StackAutoLiveStudy(private val ctx: ToolExecutionContext) : LiveStudy {
    private fun s(q: Any) = study(ctx, (q as StackAutoSlotRequest).slot)
    override fun inspect(q: StudyRequest.Inspect): StudyReport = s(q).inspect(q.revision)
    override fun edit(q: StudyRequest.Edit): StudyReport {
        val e = q as? EditStackAutoSlot
        return s(q).edit(q.expectedRevision, q.expectedSourceHash, q.source, q.description, e?.alone ?: false, e?.over ?: 0.0, e?.moments ?: 4)
    }
    override fun patch(q: StudyRequest.Patch): StudyReport {
        val p = q as? PatchStackAutoSlot
        return s(q).patch(q.expectedRevision, q.expectedSourceHash, q.edits, q.description, p?.alone ?: false, p?.over ?: 0.0, p?.moments ?: 4)
    }
    override fun setNumbers(q: StudyRequest.SetNumbers): StudyReport = s(q).setNumbers(q.numbers.numbers(), (q as? SetStackAutoNumbers)?.keep ?: true,
        q.expectedRevision, q.expectedSourceHash, q.description.ifBlank { "numbers set: " + q.numbers.joinToString { "k${it.knob}=${it.value}" } })
    override fun sweep(q: StudyRequest.Sweep): StudyReport = s(q).sweep(q.knob, q.values)
    override fun restore(q: StudyRequest.Restore): StudyReport =
        s(q).restore(q.expectedRevision, q.expectedSourceHash, q.revision, q.description.ifBlank { "restored ${q.revision}" })
    override fun compare(q: StudyRequest.Compare): StudyReport = s(q).compare(q.beforeRevision, q.afterRevision, q.mode, q.allowedRegion.takeIf { it.isNotEmpty() })
    override fun tryVariants(q: StudyRequest.TryVariants): StudyReport = s(q).tryVariants(q.variants)
    override fun adoptVariant(q: StudyRequest.Adopt): StudyReport = s(q).adoptVariant(q.expectedRevision, q.expectedSourceHash, q.label, q.description)
}

/** Register the stack.auto slot tools, for every conversation opened on a box's code that has bound slots. */
@RunsAt(plugins)
fun installStackAutoStudyTools() {
    val r = AgentToolExtensions
    r.register(StackAutoSlotGuide::class.java, onASlotBox, LIBRARY) { _, q ->
        StackAutoGuideText(if (q.search.isBlank()) GUIDE else StackSLStudyGuides.search(q.search)) }
    registerStudyTools(LIBRARY, onASlotBox, { StackAutoLiveStudy(it) }, StudyToolClasses(
        inspect = InspectStackAutoSlot::class.java, edit = EditStackAutoSlot::class.java, patch = PatchStackAutoSlot::class.java,
        setNumbers = SetStackAutoNumbers::class.java, compare = CompareStackAutoSlot::class.java, sweep = SweepStackAutoNumber::class.java,
        tryVariants = TryStackAutoVariants::class.java, adoptVariant = AdoptStackAutoVariant::class.java, restore = RestoreStackAutoRevision::class.java))
    r.register(ObserveStackAutoSlot::class.java, onASlotBox, LIBRARY) { ctx, q -> study(ctx, q.slot).observe(q.alone, q.over, q.moments) }
}
