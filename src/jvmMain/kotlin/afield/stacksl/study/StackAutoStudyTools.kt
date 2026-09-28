package afield.stacksl.study

import afield.Box
import afield.RunsAt
import afield.ai.core.AgentToolExtensions
import afield.ai.core.ToolExecutionContext
import afield.plugins
import afield.stdlib.StudySourceEdit
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// The tools an AI4 conversation gets when it is opened on the code of a box whose shaders have
// `{{ stack.auto.<slot> }}` slots with programs bound. Each works on one slot's study
// (StackAutoStudies), inside the box's own shader: the end result is what the tools show.

@JsonClassDescription("Read how to work on a stack.auto slot: StackSL inside someone's own shader. Call it once, before your first edit in a conversation.")
class StackAutoSlotGuide

@JsonClassDescription("Read a stack.auto slot's study: its program, the host shader's lines that declare and call it (what its input and output mean — the host decides, not StackSL's own frame), where the module is drawn (the window, a render target), the compiler's problems and warnings, its numbers (knobs: index, line:column, as typed, the operand the compiler took it as, the card's name in brackets, and whether it is turned live but not kept), keys, and history. slot may be empty when the box has only one. Inspect once before editing and reuse sourceHash.")
data class InspectStackAutoSlot(val slot: String = "", val revision: String = "current")

@JsonClassDescription("Replace a slot's whole StackSL program. It must compile into this slot (the host shader's own samplers and push constants are fine); it is then bound live, the slot recompiles in place, and it is written back into the box's Kotlin where it sits verbatim. Returns a picture of the result in the host shader — the real end result. Needs expectedRevision and expectedSourceHash from Inspect. Prefer PatchStackAutoSlot or SetStackAutoNumbers for small changes.")
data class EditStackAutoSlot(val slot: String = "", val expectedRevision: String, val expectedSourceHash: String,
    @JsonPropertyDescription("Complete StackSL source: one transform per line, # comments naming what each stage does") val source: String,
    val description: String,
    @JsonPropertyDescription("Also draw the program alone on StackSL's own frame, a debugging view whose input is not the host's") val alone: Boolean = false)

@JsonClassDescription("Preferred for local edits: exact, unique, non-overlapping text replacements in a slot's program, checked and bound like EditStackAutoSlot, with a picture in the host shader.")
data class PatchStackAutoSlot(val slot: String = "", val expectedRevision: String, val expectedSourceHash: String,
    val edits: List<StudySourceEdit>, val description: String, val alone: Boolean = false)

/** One number to turn: knob index from Inspect, and its new value. */
data class StackAutoNumberEdit(val knob: Int, val value: Double)

@JsonClassDescription("Turn a slot's numbers live, through its uniform block — no recompile — and see the host shader with them. They stay turned but unkept unless keep is true, which writes them into the program as a new revision (then expectedRevision and expectedSourceHash are needed). Use it to try values; keep what the person likes.")
data class SetStackAutoNumbers(val slot: String = "", val numbers: List<StackAutoNumberEdit>, val keep: Boolean = false,
    val expectedRevision: String = "", val expectedSourceHash: String = "", val description: String = "")

@JsonClassDescription("See a slot as it is drawing now, in the host shader — wherever its module is drawn — and, with alone, its program by itself on StackSL's own frame (a debugging view; its input is not the host's).")
data class ObserveStackAutoSlot(val slot: String = "", val alone: Boolean = false)

@JsonClassDescription("Draw one of a slot's numbers at 2..9 values, each in the real host shader with only that number changed, then put it back. Nothing is kept. Use it to choose a value before SetStackAutoNumbers.")
data class SweepStackAutoNumber(val slot: String = "", val knob: Int, val values: List<Double>)

@JsonClassDescription("Make an earlier revision a slot's program again, as a new revision, bound live and written back into the code like an edit.")
data class RestoreStackAutoRevision(val slot: String = "", val expectedRevision: String, val expectedSourceHash: String, val revision: String, val description: String = "")

/** The [StackAutoSlotGuide] tool's answer. */
data class StackAutoGuideText(val text: String) : afield.ai.core.SummarisesForToolLog {
    override fun toolLogSummary(): String = "guide ${text.length} chars"
}

private val GUIDE = """
    A stack.auto slot is StackSL compiled into a function, vec4 NAME(vec4 NAME_in), inside the box's own GLSL.
    The host shader decides what the input is and what the output means: read InspectStackAutoSlot's host lines
    first. The input is often vec4(fragUV, 0, 0) — uv is then the host's coordinates — but it may be a position,
    a velocity or a texture sample, and the output may be a colour, a height or a force.

    The evidence is the host shader's real result: the window the module draws in (all of it, with whatever else
    draws there), a render target it draws into, or — for a ping-pong or stereo target — the module drawn once more
    by itself. The alone view is StackSL's own frame and input; use it to debug a stage, never to judge the result.

    Numbers turn live through the slot's uniform block, with no recompile: SetStackAutoNumbers and
    SweepStackAutoNumber are cheap, so try values before you edit text. Keep only what the person likes; a turned
    number that is not kept is lost when the box runs again. An edit is bound live and written back into the Kotlin
    string it came from; if the program is built in Kotlin rather than written out, the edit is live only, and the
    reply says so. The words are StackSL's: StackSLStudyGuide with search looks one up.
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

/** Register the stack.auto slot tools, for every conversation opened on a box's code that has bound slots. */
@RunsAt(plugins)
fun installStackAutoStudyTools() {
    val r = AgentToolExtensions
    r.register(StackAutoSlotGuide::class.java, onASlotBox, LIBRARY) { _, _ -> StackAutoGuideText(GUIDE) }
    r.register(InspectStackAutoSlot::class.java, onASlotBox, LIBRARY) { ctx, q -> study(ctx, q.slot).inspect(q.revision) }
    r.register(EditStackAutoSlot::class.java, onASlotBox, LIBRARY) { ctx, q ->
        study(ctx, q.slot).edit(q.expectedRevision, q.expectedSourceHash, q.source, q.description, q.alone) }
    r.register(PatchStackAutoSlot::class.java, onASlotBox, LIBRARY) { ctx, q ->
        study(ctx, q.slot).patch(q.expectedRevision, q.expectedSourceHash, q.edits, q.description, q.alone) }
    r.register(SetStackAutoNumbers::class.java, onASlotBox, LIBRARY) { ctx, q ->
        study(ctx, q.slot).setNumbers(q.numbers.associate { it.knob to it.value }, q.keep, q.expectedRevision, q.expectedSourceHash,
            q.description.ifBlank { "numbers set: " + q.numbers.joinToString { "k${it.knob}=${it.value}" } }) }
    r.register(ObserveStackAutoSlot::class.java, onASlotBox, LIBRARY) { ctx, q -> study(ctx, q.slot).observe(q.alone) }
    r.register(SweepStackAutoNumber::class.java, onASlotBox, LIBRARY) { ctx, q -> study(ctx, q.slot).sweep(q.knob, q.values) }
    r.register(RestoreStackAutoRevision::class.java, onASlotBox, LIBRARY) { ctx, q ->
        study(ctx, q.slot).restore(q.expectedRevision, q.expectedSourceHash, q.revision, q.description.ifBlank { "restored ${q.revision}" }) }
}
