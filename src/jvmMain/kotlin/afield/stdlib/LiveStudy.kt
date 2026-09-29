@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.ai.core.AgentToolExtensions
import afield.ai.core.BoxTool
import afield.ai.core.ToolExecutionContext
import afield.ai.core.aiTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a study's standard tool requests have in common, whatever the language.
 *
 * Each library study keeps its own request classes — their names are the tool names, and their
 * fields and descriptions the schemas a model has been shown (pinned by `StudyToolSchemaPinTest`) —
 * and each implements the matching interface here, so [registerStudyTools] can wire every one of
 * them to a [LiveStudy] once. The small interfaces at the end ([Preview], [Sized], [At],
 * [Revisioned]) are the optional extras a request may carry; a study reads them when present.
 */
object StudyRequest {
    /** A request naming a revision (`current`, or blank, for the checked one). */
    interface Revisioned { val revision: String }

    /** Read the study: source, live-edit state, history, knobs. */
    interface Inspect : Revisioned

    /** Replace the whole source, guarded by the revision and live-source hash the caller read. */
    interface Edit { val expectedRevision: String; val expectedSourceHash: String; val source: String; val description: String }

    /** Exact, unique, non-overlapping replacements in the live source, guarded like [Edit]. */
    interface Patch { val expectedRevision: String; val expectedSourceHash: String; val edits: List<StudySourceEdit>; val description: String }

    /** New values for knobs of the live source, by index, guarded like [Edit]. */
    interface SetNumbers { val expectedRevision: String; val expectedSourceHash: String; val numbers: List<NumberEdit>; val description: String }

    /** Frames of a revision at [seconds], optionally against [compareTo]. */
    interface Observe : Revisioned { val seconds: List<Double>; val compareTo: String }

    /** One knob at several values, nothing published. */
    interface Sweep { val knob: Int; val values: List<Double> }

    /** A candidate: a complete [source], or [edits] to the live source. */
    interface Variant { val label: String; val source: String; val edits: List<StudySourceEdit>; val note: String }

    /** Check and draw candidates beside the current revision and hold them, unpublished. */
    interface TryVariants { val variants: List<Variant>; val seconds: List<Double> }

    /** Publish a held candidate by label, guarded like [Edit]. */
    interface Adopt { val expectedRevision: String; val expectedSourceHash: String; val label: String; val description: String }

    /** Make an earlier revision current again, as a new revision. */
    interface Restore { val expectedRevision: String; val expectedSourceHash: String; val revision: String; val description: String }

    /** What an edit preserved, outside [allowedRegion], in `rgb` or `luma` (a study may offer more modes). */
    interface Compare { val beforeRevision: String; val afterRevision: String; val seconds: List<Double>; val mode: String; val allowedRegion: List<Double> }

    /** Optional: the times an accepted edit's preview frames are drawn at. */
    interface Preview { val previewSeconds: List<Double> }

    /** Optional: the frame size asked for; 0 chooses. */
    interface Sized { val size: Int }

    /** Optional: the one time a sweep is drawn at. */
    interface At { val seconds: Double }
}

/** The standard study verbs, for choosing which a document's [registerStudyTools] offers. */
enum class StudyVerb { INSPECT, EDIT, PATCH, SET_NUMBERS, OBSERVE, COMPARE, SWEEP, TRY_VARIANTS, ADOPT_VARIANT, RESTORE }

/**
 * A study a conversation can work on through the standard verbs: the face [registerStudyTools] wires
 * tools to. A library study adapts its own class to it; a document can implement it directly, or use
 * [SourceFramesStudy], which implements all of it from the shared pieces. A verb a study does not
 * offer refuses with a message saying so.
 */
interface LiveStudy {
    fun inspect(q: StudyRequest.Inspect): StudyReport
    fun edit(q: StudyRequest.Edit): StudyReport = unsupported("Edit")
    fun patch(q: StudyRequest.Patch): StudyReport = unsupported("Patch")
    fun setNumbers(q: StudyRequest.SetNumbers): StudyReport = unsupported("SetNumbers")
    fun observe(q: StudyRequest.Observe): StudyReport = unsupported("Observe")
    fun compare(q: StudyRequest.Compare): StudyReport = unsupported("Compare")
    fun sweep(q: StudyRequest.Sweep): StudyReport = unsupported("Sweep")
    fun tryVariants(q: StudyRequest.TryVariants): StudyReport = unsupported("TryVariants")
    fun adoptVariant(q: StudyRequest.Adopt): StudyReport = unsupported("AdoptVariant")
    fun restore(q: StudyRequest.Restore): StudyReport = unsupported("Restore")

    private fun unsupported(verb: String): Nothing = throw StudyRejection("This study does not offer $verb")
}

/**
 * The request classes a library study's standard tools are declared with; null for a verb it does not
 * offer. Each class's simple name is the tool's name.
 */
class StudyToolClasses(
    val inspect: Class<out StudyRequest.Inspect>? = null,
    val edit: Class<out StudyRequest.Edit>? = null,
    val patch: Class<out StudyRequest.Patch>? = null,
    val setNumbers: Class<out StudyRequest.SetNumbers>? = null,
    val observe: Class<out StudyRequest.Observe>? = null,
    val compare: Class<out StudyRequest.Compare>? = null,
    val sweep: Class<out StudyRequest.Sweep>? = null,
    val tryVariants: Class<out StudyRequest.TryVariants>? = null,
    val adoptVariant: Class<out StudyRequest.Adopt>? = null,
    val restore: Class<out StudyRequest.Restore>? = null,
)

/**
 * Register a study's standard tools with [AgentToolExtensions]: one per class in [classes], each
 * offered when [gate] passes and run on the study [studyOf] finds for the call — usually the focused
 * box's. [scope] is the registration's scope (see AgentToolExtensions, "Scopes"): a library's own id,
 * so a document's same-named tool adds to it. A study registers its extras (a guide, a trace, an
 * export) itself.
 */
fun registerStudyTools(scope: Any, gate: (ToolExecutionContext) -> Boolean, studyOf: (ToolExecutionContext) -> LiveStudy, classes: StudyToolClasses) {
    fun <Q : Any> on(cls: Class<out Q>?, run: (LiveStudy, Q) -> Any) {
        @Suppress("UNCHECKED_CAST")
        val c = (cls ?: return) as Class<Q>
        AgentToolExtensions.register(c, gate, scope) { ctx, q -> run(studyOf(ctx), q) }
    }
    on(classes.inspect) { s, q -> s.inspect(q) }
    on(classes.edit) { s, q -> s.edit(q) }
    on(classes.patch) { s, q -> s.patch(q) }
    on(classes.setNumbers) { s, q -> s.setNumbers(q) }
    on(classes.observe) { s, q -> s.observe(q) }
    on(classes.compare) { s, q -> s.compare(q) }
    on(classes.sweep) { s, q -> s.sweep(q) }
    on(classes.tryVariants) { s, q -> s.tryVariants(q) }
    on(classes.adoptVariant) { s, q -> s.adoptVariant(q) }
    on(classes.restore) { s, q -> s.restore(q) }
}

/**
 * The requests a document's study tools ([Box.registerStudyTools]) hand their [LiveStudy]: the same
 * interfaces a library study's request classes implement, with every optional extra.
 */
object StudyCall {
    data class Inspect(override val revision: String = "current") : StudyRequest.Inspect
    data class Edit(override val expectedRevision: String, override val expectedSourceHash: String, override val source: String,
                    override val description: String, override val previewSeconds: List<Double> = emptyList()) : StudyRequest.Edit, StudyRequest.Preview
    data class Patch(override val expectedRevision: String, override val expectedSourceHash: String, override val edits: List<StudySourceEdit>,
                     override val description: String, override val previewSeconds: List<Double> = emptyList()) : StudyRequest.Patch, StudyRequest.Preview
    /** One knob to set, by index, and its value. */
    data class Number(override val knob: Int, override val value: Double) : NumberEdit
    data class SetNumbers(override val expectedRevision: String, override val expectedSourceHash: String, override val numbers: List<Number>,
                          override val description: String, override val previewSeconds: List<Double> = emptyList()) : StudyRequest.SetNumbers, StudyRequest.Preview
    data class Observe(override val revision: String = "current", override val seconds: List<Double> = emptyList(), override val compareTo: String = "",
                       override val size: Int = 0) : StudyRequest.Observe, StudyRequest.Sized
    data class Compare(override val beforeRevision: String, override val afterRevision: String = "current", override val seconds: List<Double> = emptyList(),
                       override val mode: String = "rgb", override val allowedRegion: List<Double> = emptyList(), override val size: Int = 0) : StudyRequest.Compare, StudyRequest.Sized
    data class Sweep(override val revision: String = "current", override val knob: Int, override val values: List<Double>, override val seconds: Double = 0.0,
                     override val size: Int = 0) : StudyRequest.Sweep, StudyRequest.Revisioned, StudyRequest.At, StudyRequest.Sized
    data class Variant(override val label: String, override val source: String = "", override val edits: List<StudySourceEdit> = emptyList(),
                       override val note: String = "") : StudyRequest.Variant
    data class TryVariants(override val variants: List<Variant>, override val seconds: List<Double> = emptyList(), override val size: Int = 0) : StudyRequest.TryVariants, StudyRequest.Sized
    data class Adopt(override val expectedRevision: String, override val expectedSourceHash: String, override val label: String,
                     override val description: String = "") : StudyRequest.Adopt
    data class Restore(override val expectedRevision: String, override val expectedSourceHash: String, override val revision: String,
                       override val description: String = "") : StudyRequest.Restore
}

/**
 * Give this document's AI4 agents the standard study verbs on [study], as tools named after [prefix]
 * the way the library's are: `Inspect<prefix>Study`, `Edit<prefix>Study`, `Patch<prefix>Study`,
 * `Set<prefix>Numbers`, `Observe<prefix>Study`, `Compare<prefix>Study`, `Sweep<prefix>Number`,
 * `Try<prefix>Variants`, `Adopt<prefix>Variant` and `Restore<prefix>Revision` — [verbs] of them.
 *
 * Call it from box code. The tools are box-authored ([aiTool]): they belong to this box's document,
 * are replaced when the box runs again, and go when it retires. Each returns its study's
 * [StudyReport], pictures included, and runs off the main thread so a slow render does not stop the
 * window. [about] is added to every tool's description — what the study's source is and how its
 * frames are to be read. Returns the tools by verb, each callable from box code too.
 */
fun Box.registerStudyTools(prefix: String, study: LiveStudy, about: String = "", verbs: Set<StudyVerb> = StudyVerb.entries.toSet()): Map<StudyVerb, BoxTool> {
    require(prefix.matches(Regex("[A-Z][A-Za-z0-9]{0,31}"))) { "A study prefix is a short CamelCase word, such as Swatch" }
    val out = LinkedHashMap<StudyVerb, BoxTool>()
    fun say(text: String) = if (about.isBlank()) text else "$text $about"
    suspend fun <T> offMain(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    if (StudyVerb.INSPECT in verbs) out[StudyVerb.INSPECT] = aiTool("Inspect${prefix}Study") {
        describe(say("Read this study: the checked source, whether the editor holds an unchecked edit (dirty; then liveSource is what is in the box), history, knobs (numbers by index) and held variants. Inspect once before editing and reuse liveSourceHash."))
        val revision by param<String>("A revision id, or current", default = "current")
        action { val q = StudyCall.Inspect(revision()); offMain { study.inspect(q) } }
    }
    if (StudyVerb.EDIT in verbs) out[StudyVerb.EDIT] = aiTool("Edit${prefix}Study") {
        describe(say("Replace the whole source. It is checked, then activated as a new revision, with a preview against its parent. Needs expectedRevision and expectedSourceHash from Inspect; a stale guard or failed check changes nothing."))
        val expectedRevision by param<String>("The revision Inspect reported")
        val expectedSourceHash by param<String>("The liveSourceHash Inspect reported")
        val source by param<String>("The complete new source")
        val description by param<String>("What the edit does, in a few words")
        val previewSeconds by param<List<Double>>("Times for the preview frames; empty chooses", default = emptyList<Double>())
        action { val q = StudyCall.Edit(expectedRevision(), expectedSourceHash(), source(), description(), previewSeconds()); offMain { study.edit(q) } }
    }
    if (StudyVerb.PATCH in verbs) out[StudyVerb.PATCH] = aiTool("Patch${prefix}Study") {
        describe(say("Preferred for local edits: exact, unique, non-overlapping replacements in the live source, each {before, after}, checked and activated like an edit."))
        val expectedRevision by param<String>("The revision Inspect reported")
        val expectedSourceHash by param<String>("The liveSourceHash Inspect reported")
        val edits by param<List<StudySourceEdit>>("Replacements, each an object {before, after}; each before must occur exactly once")
        val description by param<String>("What the edit does, in a few words")
        val previewSeconds by param<List<Double>>("Times for the preview frames; empty chooses", default = emptyList<Double>())
        action { val q = StudyCall.Patch(expectedRevision(), expectedSourceHash(), edits(), description(), previewSeconds()); offMain { study.patch(q) } }
    }
    if (StudyVerb.SET_NUMBERS in verbs) out[StudyVerb.SET_NUMBERS] = aiTool("Set${prefix}Numbers") {
        describe(say("Set typed numbers (knobs, by index from Inspect) to new values, rewritten in place, then checked and activated like an edit."))
        val expectedRevision by param<String>("The revision Inspect reported")
        val expectedSourceHash by param<String>("The liveSourceHash Inspect reported")
        val numbers by param<List<StudyCall.Number>>("Knobs to set, each an object {knob, value}")
        val description by param<String>("What the change does, in a few words")
        val previewSeconds by param<List<Double>>("Times for the preview frames; empty chooses", default = emptyList<Double>())
        action { val q = StudyCall.SetNumbers(expectedRevision(), expectedSourceHash(), numbers(), description(), previewSeconds()); offMain { study.setNumbers(q) } }
    }
    if (StudyVerb.OBSERVE in verbs) out[StudyVerb.OBSERVE] = aiTool("Observe${prefix}Study") {
        describe(say("See a revision: frames at the given times, sent as an image, with measurements (luma, clipping, detail, palette, motion). compareTo adds that revision's frames and a difference map."))
        val revision by param<String>("A revision id, or current", default = "current")
        val seconds by param<List<Double>>("Times in seconds; empty chooses", default = emptyList<Double>())
        val compareTo by param<String>("Another revision to show beneath, or empty", default = "")
        val size by param<Int>("Pixels on the longer side; 0 chooses", default = 0)
        action { val q = StudyCall.Observe(revision(), seconds(), compareTo.invoke(), size()); offMain { study.observe(q) } }
    }
    if (StudyVerb.COMPARE in verbs) out[StudyVerb.COMPARE] = aiTool("Compare${prefix}Study") {
        describe(say("Check what an edit preserved: before | after | difference per time; mode rgb compares displayed colour, luma brightness only. allowedRegion [u0,v0,u1,v1] (0..1, y up) is where change may happen; outside it everything must stay within one level."))
        val beforeRevision by param<String>("The revision before the edit")
        val afterRevision by param<String>("The revision after it", default = "current")
        val seconds by param<List<Double>>("Times in seconds; empty chooses", default = emptyList<Double>())
        val mode by param<String>("rgb or luma", default = "rgb")
        val allowedRegion by param<List<Double>>("[u0, v0, u1, v1], or empty for none", default = emptyList<Double>())
        action { val q = StudyCall.Compare(beforeRevision(), afterRevision(), seconds(), mode(), allowedRegion()); offMain { study.compare(q) } }
    }
    if (StudyVerb.SWEEP in verbs) out[StudyVerb.SWEEP] = aiTool("Sweep${prefix}Number") {
        describe(say("Draw one knob at 2..9 values side by side at one time, without publishing anything. Use it to choose a value before Set${prefix}Numbers."))
        val revision by param<String>("A revision id, or current", default = "current")
        val knob by param<Int>("The knob's index from Inspect")
        val values by param<List<Double>>("2..9 values to draw")
        val seconds by param<Double>("The time to draw at", default = 0.0)
        action { val q = StudyCall.Sweep(revision(), knob(), values(), seconds()); offMain { study.sweep(q) } }
    }
    if (StudyVerb.TRY_VARIANTS in verbs) out[StudyVerb.TRY_VARIANTS] = aiTool("Try${prefix}Variants") {
        describe(say("Speculate: check and draw 1..8 candidates beside the current source without publishing. Each is {label, source} or {label, edits}, with an optional note; they are held for Adopt${prefix}Variant."))
        val variants by param<List<StudyCall.Variant>>("Candidates, each an object {label, source, edits, note}")
        val seconds by param<List<Double>>("Times in seconds; empty chooses", default = emptyList<Double>())
        action { val q = StudyCall.TryVariants(variants(), seconds()); offMain { study.tryVariants(q) } }
    }
    if (StudyVerb.ADOPT_VARIANT in verbs) out[StudyVerb.ADOPT_VARIANT] = aiTool("Adopt${prefix}Variant") {
        describe(say("Publish a held variant by label as a new revision (guards as for an edit)."))
        val expectedRevision by param<String>("The revision Inspect reported")
        val expectedSourceHash by param<String>("The liveSourceHash Inspect reported")
        val label by param<String>("The held variant's label")
        val description by param<String>("What it does; empty says which variant", default = "")
        action { val q = StudyCall.Adopt(expectedRevision(), expectedSourceHash(), label(), description()); offMain { study.adoptVariant(q) } }
    }
    if (StudyVerb.RESTORE in verbs) out[StudyVerb.RESTORE] = aiTool("Restore${prefix}Revision") {
        describe(say("Make an earlier revision current again, as a new revision; history is only appended to."))
        val expectedRevision by param<String>("The revision Inspect reported")
        val expectedSourceHash by param<String>("The liveSourceHash Inspect reported")
        val revision by param<String>("The revision to restore")
        val description by param<String>("Why; empty says which", default = "")
        action { val q = StudyCall.Restore(expectedRevision(), expectedSourceHash(), revision(), description()); offMain { study.restore(q) } }
    }
    return out
}
