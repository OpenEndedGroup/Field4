@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.*
import afield.four.Renderer
import afield.four.device.Instance.Companion.globalAsyncDebugHandler
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.four.util.*
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import afield.md
import afield.Table
import afield.FieldMarkdown

/**
 * The bridge between a box's text and a live Vulkan pipeline: shader source lives in box
 * properties, and this file turns it into modules and keeps them hot-reloading.
 *
 * The properties come first — `vertex`, `fragment`, `compute`, `task`/`mesh`, the ray-tracing
 * stages, and a `_s` variant of each for Slang — alongside `once.vertex { }`-style seeders. The
 * user-facing entry points are the macro placeholders [Box.moduleHere], [Box.computeModuleHere],
 * [Box.meshModuleHere], [Box.rayTracingModuleHere] and [Box.shadertoyHere]; each one throws
 * `MacroNotExpandedException` if actually called, because `MacroRegistry` is expected to rewrite it
 * into the real `_moduleHere`-family call.
 *
 * Before compilation every stage goes through [preprocess], which resolves `{% include %}` template
 * tags against other boxes' shader properties (with cycle detection and `#line` fixups) and against
 * built-in includes such as the field probe, field stats and quantile warp. Registered include roots —
 * [registerShaderFilesystemIncludeRoot] for a directory, [registerLygiaIncludeRoot] /
 * [shaderCachedUrlIncludeRootResolver] for URL-fetched, disk-cached libraries — are **not** reachable
 * from here: they are resolved by shaderc during compilation, from a real `#include` only. It also expands
 * `{% copyPC %}` (copying a previous stage's push-constant block) and stackAuto template slots
 * compiled from StackSL via [stackAutoShaderSource].
 *
 * [reloadShader], [reloadComputeShader], [reloadMeshShader] and [reloadRayTracingShader] are the
 * reload path: compile, report errors back onto the box's source lines, introspect the result, and
 * rewrite the box's Kotlin preamble with generated binding structures before swapping the module.
 * [Box.attachShader] adopts an externally built module into that same path. [Box.fieldProbeHere],
 * [Box.fieldStatsHere] and [Box.fieldProbeAutoControl] attach the GPU readback instrumentation.
 *
 * Everything here needs a live `Box` — properties, error reporting, preamble edits and the
 * generation-guarded async reload all key off it.
 */

// ------------------------------
// Box properties
// ------------------------------
/**
 * The fragment-stage GLSL for this box, as text — the shader tab you type into, and the *only*
 * place a graphics module's fragment shader comes from (assignments inside a `moduleHere { }`
 * lambda do not set it).
 *
 * Inherited, so a box with none of its own reads its nearest ancestor's and one parent can dress a
 * whole family of children. Writing it does **not** recompile anything by itself: reflected
 * bindings and the live pipeline only change when something explicitly reloads — the *Reload
 * Shader* action (`alt-r`), *Update Shader* (`alt-shift-enter`), [Box.attachShader], or an AI
 * provisional accept — and those actions only exist on a box that has expanded a
 * `moduleHere`-family macro or called `attachShader`. [Box.fragment_s] and [Box.vertex_fragment]
 * both take precedence over this when they are non-blank.
 */
var Box.fragment by Inheritance<String?>()

/**
 * The vertex-stage GLSL for this box. Its `layout(location = …) in` declarations are the contract
 * that generates the box's `Mesh` writers (`pos`, `col`, …), so changing an input here changes the
 * Kotlin API of every box downstream of the next reload.
 *
 * Same rules as [Box.fragment]: inherited, read at prepare time, and overridden by [Box.vertex_s]
 * or [Box.vertex_fragment] when either is non-blank. If a box has neither vertex nor fragment
 * source when `moduleHere` expands, both are seeded from [defaultVertexShader] /
 * [defaultFragmentShader].
 */
var Box.vertex by Inheritance<String?>()

/**
 * One Slang source holding *both* the vertex and fragment stages, compiled twice (once per stage)
 * by `slangc`. It beats `vertex`, `fragment`, `vertex_s` and `fragment_s` outright: when this is
 * non-blank it is the source for both graphics stages.
 *
 * Worth knowing: this is the one path whose reflection comes from Slang's own reflection JSON
 * rather than from shaderc's SPIR-V walk, so the generated bindings are produced by a different
 * code path than the GLSL boxes use. Needs `slangc` on the machine; a transpile failure is reported
 * on this property's lines and the stage is abandoned with the previous module left running.
 */
var Box.vertex_fragment by Inheritance<String?>()

/** The task-shader (amplification) stage GLSL of a mesh-shader pipeline. `meshModuleHere` needs
 * `task`, `mesh` *and* `fragment` — there is no task-less mesh path here — and fills in whichever of
 * the three the box is missing, each independently and each with its own stub: an `EmitMeshTasksEXT`
 * amplifier here, a `SetMeshOutputsEXT` triangle emitter for `mesh`, and a constant-colour write for
 * `fragment`. A box that already has a task shader keeps it and gets only the other two. */
var Box.task by Inheritance<String?>()

/** The mesh-shader stage GLSL, the stage that emits vertices and primitives directly
 * (`SetMeshOutputsEXT` / `gl_MeshVerticesEXT`) instead of consuming a vertex buffer. Paired with
 * [Box.task] and [Box.fragment] by `meshModuleHere`; note the name collides conceptually with
 * `Mesh` the Kotlin draw object, which is unrelated. */
var Box.mesh by Inheritance<String?>()

/** Slang source for the fragment stage. When non-blank it wins over [Box.fragment] and is
 * transpiled to GLSL by `slangc` before shaderc ever sees it, so compile errors arrive in Slang's
 * `(line): error` format — which [numberFromErrorMessage] does understand. */
var Box.fragment_s by Inheritance<String?>()

/** Slang source for the vertex stage; wins over [Box.vertex] when non-blank. Prefer
 * [Box.vertex_fragment] if you want the two stages to share declarations — two separate `_s`
 * properties are transpiled independently and cannot see each other. */
var Box.vertex_s by Inheritance<String?>()

/** Slang source for the task stage; wins over [Box.task] when non-blank. */
var Box.task_s by Inheritance<String?>()

/** Slang source for the mesh stage; wins over [Box.mesh] when non-blank. */
var Box.mesh_s by Inheritance<String?>()

/**
 * The *prepared* fragment GLSL: box-local, post-`#include`-template, post-`{% copyPC %}`,
 * post-Slang-transpile — literally the text handed to shaderc. Written by every prepare pass and
 * read by `_moduleHere`, which throws a `NullPointerException` if no pass ever ran.
 *
 * Not inherited (unlike [Box.fragment]) and not worth hand-editing: the next prepare overwrites it.
 * It is, however, the right thing to read when you want to see what your `{{ }}` slots and
 * includes actually expanded to.
 */
var Box._fragment by Delegation<String?>()

/** The prepared vertex GLSL — see [Box._fragment] for what "prepared" means and why you should
 * treat it as read-only. */
var Box._vertex by Delegation<String?>()

/** The prepared task-stage GLSL. See [Box._fragment]. */
var Box._task by Delegation<String?>()

/** The prepared mesh-stage GLSL. See [Box._fragment]. */
var Box._mesh by Delegation<String?>()

/**
 * The compute-stage GLSL for this box, the source `computeModuleHere { }` builds from. Its
 * `layout(binding = …) buffer` blocks and push constants generate the box's `ComputeMesh`
 * accessors.
 *
 * Inherited, and seeded with a trivial `outData[i] = inData[i] + 1` kernel if the box has none when
 * the macro expands. [Box.compute_s] wins over it when non-blank.
 */
var Box.compute by Inheritance<String?>()

/** Slang source for the compute stage; wins over [Box.compute] when non-blank. */
var Box.compute_s by Inheritance<String?>()

/** The prepared compute GLSL. See [Box._fragment]. */
var Box._compute by Delegation<String?>()

/** The ray-generation stage GLSL: the one ray-tracing stage that is **required**, and the only one
 * `rayTracingModuleHere` seeds a default for ([defaultRaygenShader]). The others are all optional
 * and are simply skipped if their property is blank. */
var Box.raygen by Inheritance<String?>()

/** The miss-shader stage GLSL, run when a traced ray hits nothing. Optional: leave it blank and the
 * stage is left out of the pipeline entirely. */
var Box.miss by Inheritance<String?>()

/** The closest-hit stage GLSL. Optional; omitted from the pipeline when blank. */
var Box.closesthit by Inheritance<String?>()

/** The any-hit stage GLSL, run per candidate intersection (alpha-cutout style rejection).
 * Optional; omitted from the pipeline when blank. */
var Box.anyhit by Inheritance<String?>()

/** The intersection stage GLSL for custom (non-triangle) primitives. Optional; omitted from the
 * pipeline when blank. */
var Box.intersection by Inheritance<String?>()

/** The callable-shader stage GLSL. Optional; omitted from the pipeline when blank. */
var Box.callable by Inheritance<String?>()

/** Slang source for the raygen stage; wins over [Box.raygen] when non-blank. */
var Box.raygen_s by Inheritance<String?>()

/** Slang source for the miss stage; wins over [Box.miss] when non-blank. */
var Box.miss_s by Inheritance<String?>()

/** Slang source for the closest-hit stage; wins over [Box.closesthit] when non-blank. */
var Box.closesthit_s by Inheritance<String?>()

/** Slang source for the any-hit stage; wins over [Box.anyhit] when non-blank. */
var Box.anyhit_s by Inheritance<String?>()

/** Slang source for the intersection stage; wins over [Box.intersection] when non-blank. */
var Box.intersection_s by Inheritance<String?>()

/** Slang source for the callable stage; wins over [Box.callable] when non-blank. */
var Box.callable_s by Inheritance<String?>()

/** The prepared raygen GLSL. See [Box._fragment]. */
var Box._raygen by Delegation<String?>()

/** The prepared miss GLSL, or `null` when the box has no miss stage. See [Box._fragment]. */
var Box._miss by Delegation<String?>()

/** The prepared closest-hit GLSL, or `null` when the box has no such stage. See [Box._fragment]. */
var Box._closesthit by Delegation<String?>()

/** The prepared any-hit GLSL, or `null` when the box has no such stage. See [Box._fragment]. */
var Box._anyhit by Delegation<String?>()

/** The prepared intersection GLSL, or `null` when the box has no such stage. See [Box._fragment]. */
var Box._intersection by Delegation<String?>()

/** The prepared callable GLSL, or `null` when the box has no such stage. See [Box._fragment]. */
var Box._callable by Delegation<String?>()

/**
 * The live graphics module this box owns, or `null` if it has never built one. Set by
 * [Box._moduleHere] and [Box.attachShader]; a re-run replaces the pointer rather than adding a
 * second module.
 *
 * Its presence matters outside this file: it is how the editor decides that this box has a
 * graphics pipeline worth offering reflected GLSL completions for, and how the AI/provisional path
 * decides a shader-property edit should trigger a reload. Box-local, and it has to be — a child box
 * must build its own module rather than inherit its parent's.
 */
var Box._shader by Delegation<ModulePointer?>()

/** The live compute module this box owns, or `null`. Set by [Box._computeModuleHere] and
 * [Box.attachShader]; see [Box._shader] for the ownership rules. */
var Box._computeShader by Delegation<ComputeModulePointer?>()

/** The live ray-tracing module this box owns, or `null`. Set by [Box._rayTracingModuleHere] and
 * [Box.attachShader]; see [Box._shader]. */
var Box._rayTracingShader by Delegation<RayTracingModulePointer?>()

/** Vestigial: declared, inherited, and read or written by nothing in the tree. The mesh pipeline
 * uses the box-local [Box._meshShader] throughout; treat this as a name to avoid rather than an
 * API. */
var Box.meshShader by Inheritance<MeshModulePointer?>()

/** The live mesh-shader module this box owns, or `null`. Set by [Box._meshModuleHere] and
 * [Box.attachShader]; see [Box._shader]. */
var Box._meshShader by Delegation<MeshModulePointer?>()

/**
 * This box's GPU sample probe, or `null` if nothing has asked for one. Created on demand either by
 * [Box.fieldProbeHere] or by the first `#include "fieldProbe"` in one of this box's shaders, so the
 * include alone is enough to bring it into being.
 *
 * The SSBO that backs it is wired into a module's parameters at module *construction* or reload, so
 * a probe that appears after the module already exists stays unbound until the next reload.
 */
var Box._fieldProbe by Delegation<ShaderFieldProbe?>()

/** The per-frame controller installed by [Box.fieldProbeAutoControl], or `null`. Kept on the box so
 * a re-run can stop the previous one before installing its replacement — which is exactly what
 * `fieldProbeAutoControl` does, and why re-running a box does not leave two controllers fighting
 * over the same push constants. */
var Box._fieldProbeAutoController by Delegation<ShaderFieldAutoController?>()

/**
 * How this box is joined to a [ShaderFieldStats] block: the stats object plus whether this box is
 * its *producer* (it owns the buffers and its GLSL records into them) or merely a consumer.
 *
 * `#include "fieldStats"` reads this to decide which GLSL to emit, and throws if it is `null` —
 * so a box that includes `fieldStats` must first call [Box.fieldStatsHere] (producer) or
 * [Box.useFieldStats] (consumer).
 */
var Box._fieldStatsBinding by Delegation<ShaderFieldStatsBinding?>()

// `once.vertex { ... }` / `once.fragment { ... }` etc. seed a box's shader-source property once,
// mirroring `once.renderer { window = Window(...); Renderer(window) }`. `moduleHere { }` builds its
// module from the box's `vertex`/`fragment` properties (not from assignments in the lambda — those
// are overwritten by the box's prepared `_vertex`/`_fragment`), so this is how box code hands a
// module a non-default shader. Set these in a fragment *before* the `moduleHere { }` fragment (split
// with `//%-`) so the box's later `moduleHere` expansion prepares its shaders from what you supplied.
/**
 * `once.vertex { …GLSL… }` fills the box's [Box.vertex] property from code, the first time only.
 *
 * The trap is timing, not syntax: `once.vertex` runs at *runtime* and `moduleHere` reads the
 * property at *macro-expansion* time, so both in one fragment means `moduleHere` expands first,
 * finds nothing, and seeds [defaultVertexShader] instead. Put a `//%-` fragment break between them.
 */
val Once.vertex by Onceing<String>()

/** `once.fragment { …GLSL… }` fills [Box.fragment] once from code. Same fragment-ordering trap as
 * [Once.vertex]: seed it in a fragment *before* the one that expands `moduleHere`. */
val Once.fragment by Onceing<String>()

/** `once.vertex_fragment { …Slang… }` fills [Box.vertex_fragment] once — the shared-Slang route,
 * which overrides whatever `once.vertex`/`once.fragment` put in place. */
val Once.vertex_fragment by Onceing<String>()

/** `once.compute { …GLSL… }` fills [Box.compute] once, ahead of the `computeModuleHere` fragment. */
val Once.compute by Onceing<String>()

/** `once.task { …GLSL… }` fills [Box.task] once, ahead of the `meshModuleHere` fragment. */
val Once.task by Onceing<String>()

/** `once.mesh { …GLSL… }` fills [Box.mesh] once, ahead of the `meshModuleHere` fragment. */
val Once.mesh by Onceing<String>()

/** `once.raygen { …GLSL… }` fills [Box.raygen] once, ahead of the `rayTracingModuleHere` fragment. */
val Once.raygen by Onceing<String>()

/** `once.miss { …GLSL… }` fills [Box.miss] once; leave it unset to build a pipeline with no miss
 * stage at all. */
val Once.miss by Onceing<String>()

/** `once.closesthit { …GLSL… }` fills [Box.closesthit] once. Optional stage. */
val Once.closesthit by Onceing<String>()

/** `once.anyhit { …GLSL… }` fills [Box.anyhit] once. Optional stage. */
val Once.anyhit by Onceing<String>()

/** `once.intersection { …GLSL… }` fills [Box.intersection] once. Optional stage. */
val Once.intersection by Onceing<String>()

/** `once.callable { …GLSL… }` fills [Box.callable] once. Optional stage. */
val Once.callable by Onceing<String>()

/**
 * The named values this box's `{{ slot }}` template expressions resolve against — the mechanism
 * behind the shadertoy preamble and any GLSL boilerplate you would rather write once.
 *
 * Resolution walks up the box graph (a child's slots shadow its parents') and then falls back to the
 * process-global template registry, so a slot can be supplied by an ancestor box or by
 * `registerGlobalShaderTemplate`. Values may be `String`s, nested `Map`s (addressed as
 * `{{ a.b.c }}`) or `Resolver`s. An unresolved slot is a hard preprocessing error naming the line,
 * not a silent empty string.
 *
 * Concurrent, like [Box.stackAutoShaderSources]. It used to default to a plain `HashMap` while this very
 * KDoc noted that "the shader *update* path reads it from a background thread, so mutating it during a
 * live update is not safe" — a known hazard, left standing. It is also `gather`ed
 * (`four/util/templating.kt`), and `gather` merges every ancestor's copy with `putAll`, i.e. *iterates*
 * it — so this was a `ConcurrentModificationException` risk and not merely a torn-read one. See
 * `PropertyBag.kt` and `dev_docs/box_shared_state_races.md` for the rest of that family.
 */
val Box.shaderTemplate by Delegation<MutableMap<String, Any>>(
    defaultValue = { java.util.concurrent.ConcurrentHashMap<String, Any>() })

/**
 * StackSL source, by `stack.auto` slot name, for every `{{ stack.auto.foo }}` this box's shaders
 * declare. Concurrent, because the compile/reload path reads it off the main thread.
 *
 * You normally reach it through the generated `module.foo = "p length ]2s2.0"` properties rather
 * than directly; see [stackAutoShaderSource]. A name present with an empty value is normal — that
 * is a slot which has been discovered but never bound, and it compiles to the stable
 * `vec4 foo(vec4 foo_in) { return vec4(0.0); }` fallback.
 */
val Box.stackAutoShaderSources by Delegation<MutableMap<String, String>>(
    defaultValue = { ConcurrentHashMap<String, String>() })

private val stackAutoShaderNameRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val stackAutoEntrypointNameRegex = Regex("[A-Za-z_][A-Za-z0-9_-]*")
private val stackAutoModuleOwners = Collections.synchronizedMap(WeakHashMap<Any, Box>())

private data class StackAutoShaderTarget(
    val name: String,
    val entrypoint: String? = null,
    val sampleTexture: String = "source"
) {
    val key: String
        get() = buildString {
            append(name)
            entrypoint?.let { append(":").append(it) }
            if (sampleTexture != "source") append("@").append(sampleTexture)
        }
}

private fun validateStackAutoShaderName(name: String) {
    require(stackAutoShaderNameRegex.matches(name)) {
        "stack.auto name '$name' must be a GLSL identifier."
    }
}

private fun validateStackAutoEntrypointName(name: String) {
    require(stackAutoEntrypointNameRegex.matches(name)) {
        "stack.auto entrypoint '$name' must be a StackSL definition name."
    }
}

private fun validateStackAutoSamplerName(name: String) {
    require(stackAutoShaderNameRegex.matches(name)) {
        "stack.auto sampler '$name' must be a GLSL identifier."
    }
}

private fun parseStackAutoShaderTarget(raw: String): StackAutoShaderTarget {
    val trimmed = raw.trim()
    val samplerMarker = trimmed.indexOf('@')
    val body = if (samplerMarker >= 0) trimmed.substring(0, samplerMarker).trim() else trimmed
    val sampleTexture = if (samplerMarker >= 0) trimmed.substring(samplerMarker + 1).trim() else "source"
    val name = body.substringBefore(':').trim()
    val entrypoint = body.substringAfter(':', missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }
    validateStackAutoShaderName(name)
    entrypoint?.let(::validateStackAutoEntrypointName)
    validateStackAutoSamplerName(sampleTexture)
    return StackAutoShaderTarget(name, entrypoint, sampleTexture)
}

/**
 * What compiling one `stack.auto` slot had to say beyond "it compiled" — the warnings, and the stack
 * arithmetic that says whether the program is shaped the way its author thinks.
 *
 * ## Why this exists
 *
 * `StackSLCompileResult.ok` is *errors only*, and a stack language's characteristic mistake is not an
 * error. A definition must consume its input and leave its result — net zero. Open one with `=name`,
 * which **copies** the top (`a -- a`), where `>name` **moves** it (`a --`), and the definition leaves
 * its input sitting under its result. Every call then pushes two values instead of one, and everything
 * downstream silently takes the wrong operand. The compiler emits no diagnostic at all: it prunes the
 * surplus and carries on.
 *
 * Observed cost of that going unreported, on 2026-08-28: a three-channel noise force in which one
 * channel read a raw *coordinate* off the stack rather than a noise sample — a constant of about `+6.8`
 * on one axis — and the first of three calls pruned away entirely. It compiled, the shader compiled,
 * `ok` was true, and it was found by a person noticing that turning the gain up translated the field
 * instead of agitating it. The numbers that would have said so immediately were sitting in
 * `analysis.functions[].finalStackDepth`: `2` for the definition and `6` for its root, with
 * `prunedValues = 15`.
 *
 * ## What it does and does not claim
 *
 * [problems] is high confidence: a *definition* that does not end at depth 1 is wrong, near enough
 * always. [warnings] is whatever the compiler said at less than ERROR severity, verbatim — those were
 * previously discarded on the success path. [notes] is softer: pruned values are legitimate (a marked
 * value that is never read is pruned by design), so a count is worth showing and not worth failing on.
 *
 * A **root** program is deliberately not checked for depth. Its stack *starts* with the program's input
 * already on it, so `"input x 12 *"` — which pushes a second copy — legitimately ends at 2, and a
 * program that also leaves a marked value behind ends deeper still.
 *
 * Reporting only. A slot with problems still binds: a program is often mid-edit, and refusing the bind
 * would be a worse tool than saying so. See [stackAutoShaderReport] to ask directly, and
 * [Box.stackAutoReportChannel] for where the live-coding path puts it.
 */
data class StackAutoReport(
    /** The slot this is about — the `foo` of `{{ stack.auto.foo }}`. */
    val name: String,
    /** False when the program has an ERROR diagnostic; the caller has already been told separately. */
    val compiled: Boolean,
    /** Almost certainly bugs. A definition whose net stack effect is not "consume one, leave one". */
    val problems: List<String> = emptyList(),
    /** The compiler's own non-ERROR diagnostics: warnings and notes from a compile that succeeded. */
    val warnings: List<String> = emptyList(),
    /** Worth seeing, not worth failing on — chiefly how much computed work nothing consumes. */
    val notes: List<String> = emptyList()
) : ToHTML {

    /** Nothing to say: it compiled, no warnings, every definition balanced, nothing pruned. */
    val quiet: Boolean get() = compiled && problems.isEmpty() && warnings.isEmpty() && notes.isEmpty()

    /** Every line, most serious first, for a log or a one-shot println. */
    val lines: List<String>
        get() = problems.map { "problem: $it" } + warnings + notes.map { "note: $it" }

    /**
     * The report as a small monospaced card: one coloured line per problem, warning and note, and a
     * dim "clean" when there is nothing to say. This is what the `stack.auto` output channel shows.
     */
    override fun toHTML(): String = buildString {
        append("<div style='font-family:ui-monospace,monospace;font-size:11px;line-height:1.45'>")
        append("<div style='opacity:.65;margin-bottom:4px'>stack.auto.")
        append(escapeHtmlText(name))
        append(if (compiled) "</div>" else " &mdash; did not compile</div>")
        for ((label, colour, items) in listOf(
            Triple("problem", "#e0553a", problems),
            Triple("warning", "#d9a13a", warnings),
            Triple("note", "#7f8c9b", notes)
        )) {
            for (item in items) {
                append("<div><span style='color:")
                append(colour)
                append("'>")
                append(label)
                append("</span>  ")
                append(escapeHtmlText(item))
                append("</div>")
            }
        }
        if (quiet) append("<div style='opacity:.5'>clean</div>")
        append("</div>")
    }

    /** One line, for a log or a `println`. `"stack.auto.<name>: clean"` when there is nothing to say. */
    override fun toString(): String =
        if (quiet) "stack.auto.$name: clean" else "stack.auto.$name: " + lines.joinToString("; ")
}

private fun escapeHtmlText(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The box output channel the live-coding path writes a non-[StackAutoReport.quiet] report to.
 *
 * Opened when there is something to say and closed again when a later bind comes back clean, so it is
 * silent while the program is fine and in front of you the moment it is not.
 */
const val STACK_AUTO_REPORT_CHANNEL = "stack.auto"

/**
 * Compile one StackSL program the way a `stack.auto` slot compiles it, and report what that turned up
 * beyond success or failure.
 *
 * Pure: no device, no box, nothing bound or reloaded. The same call the live-coding setter makes, so a
 * box can ask before it binds — `println(stackAutoShaderReport(program, "noiseForce"))`.
 *
 * @param name the slot name, used as the generated GLSL prefix
 * @param entrypoint a StackSL definition to use as the root, as `{{ stack.auto.foo:word }}` would
 * @param sampleTexture the sampler `sample`/`gather` words read, as `@name` would set it
 */
fun stackAutoShaderReport(
    source: String,
    name: String = "program",
    entrypoint: String? = null,
    sampleTexture: String = "source"
): StackAutoReport {
    validateStackAutoShaderName(name)
    if (source.isBlank()) return StackAutoReport(name, compiled = true)

    val result = StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = name,
            entrypoint = entrypoint ?: "main",
            useEntrypointAsRoot = entrypoint != null,
            tolerant = false,
            includeHeader = false,
            declareSamplers = false,
            externalizeConstants = false,
            sampleTextures = listOf(sampleTexture)
        )
    )
    return stackAutoReportFor(name, result)
}

/** [stackAutoShaderReport]'s analysis half, for a result that has already been compiled. */
internal fun stackAutoReportFor(name: String, result: StackSLCompileResult): StackAutoReport {
    val problems = mutableListOf<String>()
    val notes = mutableListOf<String>()

    // Only definitions that something actually *calls* are checked. Two shapes are legitimately
    // deeper than 1 and neither is a defect:
    //
    //  - the **root**, whose stack starts with the program's input already on it, so `"input x 12 *"`
    //    ends at 2;
    //  - a definition that is only ever used as an *entrypoint* (`{{ stack.auto.foo:tap }}`), because
    //    as a root it gets root semantics. `: tap 0.2 sample ;` is exactly that, and flagging it was
    //    this check's first false positive — caught by an existing fixture in StackSLSmokeTest.
    //
    // A definition nothing invokes cannot take the wrong operand from anything, so its shape is not a
    // claim about behaviour. `calls` gives reachability for free.
    val called = result.analysis.functions.flatMapTo(mutableSetOf()) { it.calls }

    var prunedTotal = 0
    for (f in result.analysis.functions) {
        prunedTotal += f.prunedValues
        if (f.name == "<root>" || f.name !in called) continue
        if (f.finalStackDepth != 1) {
            problems += "definition `${f.name}` ends with ${f.finalStackDepth} values on the stack, not 1 " +
                "— it must consume its input and leave its result. `=mark` copies (`a -- a`), " +
                "`>mark` moves (`a --`); everything after an unbalanced definition takes the wrong operand."
        }
    }
    if (prunedTotal > 0) {
        notes += "$prunedTotal computed value(s) pruned as unused — normal for a mark that is never " +
            "read, suspicious otherwise"
    }

    return StackAutoReport(
        name = name,
        compiled = result.ok,
        problems = problems,
        warnings = result.diagnostics
            .filter { it.severity != StackSLSeverity.ERROR }
            .map { it.toString() },
        notes = notes
    )
}

private fun defaultStackAutoShaderFunction(name: String): String =
    "vec4 $name(vec4 ${name}_in) { return vec4(0.0); }"

private fun compileStackAutoShaderFunction(target: StackAutoShaderTarget, source: String): String =
    compileStackAutoShaderFunctionReporting(target, source).first

/**
 * [compileStackAutoShaderFunction] plus the [StackAutoReport] the compile produced.
 *
 * One compile, two consumers: the GLSL for the pipeline and the report for whoever is watching. The
 * report is `null` only for a blank slot, which compiles to the stable no-op fallback and has nothing
 * to say about it.
 */
private fun compileStackAutoShaderFunctionReporting(
    target: StackAutoShaderTarget,
    source: String
): Pair<String, StackAutoReport?> {
    val name = target.name
    validateStackAutoShaderName(name)
    target.entrypoint?.let(::validateStackAutoEntrypointName)
    if (source.isBlank()) return defaultStackAutoShaderFunction(name) to null

    val result = StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = name,
            entrypoint = target.entrypoint ?: "main",
            useEntrypointAsRoot = target.entrypoint != null,
            tolerant = false,
            includeHeader = false,
            declareSamplers = false,
            externalizeConstants = false,
            sampleTextures = listOf(target.sampleTexture)
        )
    )

    if (result.ok) return result.glsl to stackAutoReportFor(name, result)

    val diagnostics = result.diagnostics
        .filter { it.severity == StackSLSeverity.ERROR }
        .ifEmpty { result.diagnostics }
        .joinToString("\n") { it.toString() }
    throw IllegalArgumentException("Unable to compile stack.auto.${target.key}:\n$diagnostics")
}

/**
 * Every slot of one box that currently has something to say, rendered as one output card.
 *
 * A shader usually declares several `stack.auto` slots and a reload re-expands all of them, so reports
 * arrive one per slot in whatever order the template does. The state has to be **per slot** and the
 * output **per box**: publishing each report on its own would let a clean slot close the card a dirty
 * one had just opened, which is exactly what the first version did — `open` immediately followed by
 * `close`, and a real problem invisible.
 */
data class StackAutoReportSet(
    /** Every slot with something to say, ordered by slot name so the card does not reshuffle. */
    val reports: List<StackAutoReport>
) : ToHTML {
    /** Each slot's card, stacked, so one output area covers the whole shader. */
    override fun toHTML(): String = reports.joinToString("") { it.toHTML() }

    /** One line per slot. */
    override fun toString(): String = reports.joinToString("\n") { it.toString() }
}

/** Per box, per slot, whatever that slot last had to say. Transient — never persisted with the box. */
private val stackAutoReports =
    Collections.synchronizedMap(WeakHashMap<Box, MutableMap<String, StackAutoReport>>())

/**
 * Put a [StackAutoReport] where the person editing the program will see it.
 *
 * A slot with nothing to say is *removed* rather than written as "clean", and the card is closed only
 * once **every** slot is clean — so the output area is silent while the programs are fine and appears
 * the moment one is not, without slots overwriting each other. Anything worth saying also goes to the
 * log, because a headless run has no output area to look at.
 */
private fun publishStackAutoReport(box: Box, report: StackAutoReport?) {
    if (report == null) return

    val perSlot = synchronized(stackAutoReports) {
        stackAutoReports.getOrPut(box) { java.util.concurrent.ConcurrentHashMap() }
    }
    val previous = if (report.quiet) perSlot.remove(report.name) else perSlot.put(report.name, report)

    // Log only on a change, or a shader reload would repeat itself every frame it happens on.
    if (!report.quiet && previous != report) {
        val severity = if (report.problems.isNotEmpty()) "warning,graphics" else "graphics"
        report.lines.forEach { lprintln(severity, "stack.auto.${report.name}: $it") }
    }

    val live = perSlot.values.sortedBy { it.name }
    if (live.isEmpty()) {
        box.displayClose(STACK_AUTO_REPORT_CHANNEL)
        return
    }
    box.display(STACK_AUTO_REPORT_CHANNEL, StackAutoReportSet(live))
    box.displayOpen(STACK_AUTO_REPORT_CHANNEL)
}

private fun stackAutoShaderFunctionFor(box: Box, target: StackAutoShaderTarget): String {
    validateStackAutoShaderName(target.name)
    box.stackAutoShaderSources.putIfAbsent(target.name, "")
    val (glsl, report) = compileStackAutoShaderFunctionReporting(
        target, box.stackAutoShaderSources[target.name] ?: ""
    )
    // Also here, not only on the live-coding setter: a slot bound before its shader existed is compiled
    // for the first time during preprocessing, and that is the compile whose warnings matter most.
    publishStackAutoReport(box, report)
    return glsl
}

private fun stackAutoTemplateResolverFor(box: Box, emitted: MutableMap<String, StackAutoShaderTarget>): Resolver =
    Resolver { key ->
        fun resolveTarget(raw: String): String {
            val target = parseStackAutoShaderTarget(raw)
            val previous = emitted[target.name]
            if (previous != null) {
                require(previous == target) {
                    "stack.auto.${target.name} was already emitted as stack.auto.${previous.key}; cannot also emit stack.auto.${target.key} in the same shader stage."
                }
                return ""
            }
            emitted[target.name] = target
            return stackAutoShaderFunctionFor(box, target)
        }

        when {
            key == "stack" -> Resolver { stackKey ->
                when {
                    stackKey == "auto" -> Resolver { name -> resolveTarget(name) }
                    stackKey.startsWith("auto.") -> resolveTarget(stackKey.removePrefix("auto."))
                    else -> null
                }
            }

            key == "stack.auto" -> Resolver { name -> resolveTarget(name) }
            key.startsWith("stack.auto.") -> resolveTarget(key.removePrefix("stack.auto."))
            else -> null
        }
    }

private fun stackAutoShaderOwner(module: Any): Box? =
    synchronized(stackAutoModuleOwners) { stackAutoModuleOwners[module] }

private fun registerStackAutoShaderOwner(box: Box, module: Any) {
    synchronized(stackAutoModuleOwners) {
        stackAutoModuleOwners[module] = box
    }
}

private fun stackAutoShaderBoxFor(module: Any): Box? =
    stackAutoShaderOwner(module) ?: Box.context.get()

/**
 * The StackSL source currently bound to the `{{ stack.auto.name }}` slot of this graphics module's
 * shaders — the getter behind the generated `module.name` property.
 *
 * The module is only a handle to find the owning box by (registered when the module was built, and
 * falling back to the ambient `Box.context`); the source itself lives on the box. An unbound or
 * unknown slot reads back as `""`, never `null`, so you cannot tell "never set" from "set to
 * empty". A name that is not a GLSL identifier throws.
 */
fun stackAutoShaderSource(module: ModulePointer, name: String): String {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
    return box?.stackAutoShaderSources?.get(name) ?: ""
}

/** The StackSL source bound to a compute module's `stack.auto` slot; `""` when unbound. See the
 * [ModulePointer][stackAutoShaderSource] overload for the ownership and empty-string rules. */
fun stackAutoShaderSource(module: ComputeModulePointer, name: String): String {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
    return box?.stackAutoShaderSources?.get(name) ?: ""
}

/** The StackSL source bound to a mesh module's `stack.auto` slot; `""` when unbound. See the
 * [ModulePointer][stackAutoShaderSource] overload for the ownership and empty-string rules. */
fun stackAutoShaderSource(module: MeshModulePointer, name: String): String {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
    return box?.stackAutoShaderSources?.get(name) ?: ""
}

private fun setStackAutoShaderSource(module: Any, name: String, source: String, reloadType: ShaderReloadType) {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
        ?: throw IllegalStateException("Cannot set stack.auto.$name without a module owner or active Box context.")

    val currentSource = box.stackAutoShaderSources[name] ?: ""
    if (currentSource == source) {
        return
    }

    val compiled = try {
        compileStackAutoShaderFunctionReporting(StackAutoShaderTarget(name), source)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            "code",
            "stack.auto.$name",
            t.message ?: "Unable to compile stack.auto.$name",
            t,
            lineOverride = stackAutoErrorLine(box, source, t.message)
        )
        return
    }

    box.stackAutoShaderSources[name] = source
    requestStackAutoShaderUpdate(box, reloadType)
    // The compile succeeded, which is not the same as the program being right. See StackAutoReport.
    publishStackAutoReport(box, compiled.second)
    lprintln(
        "graphics",
        "compiled stack.auto.$name (${compiled.first.length} chars GLSL); queued shader update." +
            (compiled.second?.takeIf { !it.quiet }?.let { " ${it.problems.size} problem(s), ${it.warnings.size} warning(s)." } ?: "")
    )
}

/**
 * Bind new StackSL source to a graphics module's `{{ stack.auto.name }}` slot and queue the pipeline
 * update — the setter behind the generated `module.name = "…"` property, and the fastest live-coding
 * loop in the file.
 *
 * The StackSL is compiled *before* anything is stored. On a compile error nothing changes: the
 * previous binding and the running pipeline are both left alone and the diagnostic is reported onto
 * the box's `code` property rather than thrown, so a half-typed expression does not take the frame
 * down. On success the update runs on a background coroutine through the ABI-preserving update path
 * (no full module rebuild), and rapid writes are coalesced into one pending follow-up. Writing the
 * value it already has is a no-op.
 *
 * Throws only for a name that is not a GLSL identifier, or when the module has no registered owner
 * box *and* there is no ambient `Box.context` to fall back on.
 */
fun stackAutoShaderSource(module: ModulePointer, name: String, source: String) {
    setStackAutoShaderSource(module, name, source, ShaderReloadType.GRAPHICS)
}

/** Bind StackSL source to a compute module's `stack.auto` slot; queues the same coalesced,
 * ABI-preserving background update as the graphics [stackAutoShaderSource] setter. */
fun stackAutoShaderSource(module: ComputeModulePointer, name: String, source: String) {
    setStackAutoShaderSource(module, name, source, ShaderReloadType.COMPUTE)
}

/**
 * Bind StackSL source to a mesh module's `stack.auto` slot.
 *
 * Unlike the graphics and compute setters this one currently goes through the *full* mesh reload
 * path rather than an ABI-preserving pipeline update, so it rebuilds the module and is
 * correspondingly heavier. (Ray-tracing modules have no `stack.auto` setter at all.)
 */
fun stackAutoShaderSource(module: MeshModulePointer, name: String, source: String) {
    setStackAutoShaderSource(module, name, source, ShaderReloadType.MESH)
}

/**
 * One `#include` that landed inside a registered include root, handed to a
 * [ShaderIncludeRootResolver] to turn into text.
 *
 * The path arrives pre-chewed: [root] has already been matched against the registry, [includePath]
 * is the remainder with `.`/`..` collapsed and guaranteed not to climb above the root, and
 * [requestedPath] is what the shader literally wrote. Resolvers should key off [includePath] and use
 * [requestedPath] only for error messages.
 */
data class ShaderIncludeRootRequest(
    /** The registered root name that matched — the first path segment of a `root/…` include, or, for
     * a relative include, the root the *including* file itself came from. */
    val root: String,
    /** The path within [root], normalised: separators unified to `/`, `.` and empty segments
     * dropped, `..` resolved (and rejected if it would escape the root), no leading slash. This is
     * the key a resolver should look up. */
    val includePath: String,
    /** Exactly what the `#include` said, root prefix and all. Useful in diagnostics; do not resolve
     * against it. */
    val requestedPath: String,
    /** The resolved path of the file doing the including (a previous resolution's `resolvedPath`, or
     * a `field/<box>-<id>/<property>.glsl` name for box source), or `null` when shaderc did not
     * supply one. */
    val requestingSourcePath: String?,
    /** `RELATIVE` for `#include "…"`, `STANDARD` for `#include <…>`. Only `RELATIVE` includes are
     * resolved relative to [requestingSourcePath]'s directory. */
    val includeType: ShaderIncludeType,
    /** How deep this include is nested, straight from shaderc. Informational — nothing in Field
     * enforces a limit on it. */
    val includeDepth: Long
)

/**
 * Turns an include inside one registered root into source text. Register one under a name with
 * [registerShaderIncludeRoot] and every `#include "thatName/whatever.glsl"` in every box's shaders
 * goes through it.
 *
 * Returning `null` does **not** mean "let someone else try": once a root has claimed a path the
 * caller escalates a `null` into a thrown, box-reported error naming the root. Throwing is
 * equivalent, so prefer whichever gives the better message. Called on whichever thread is compiling
 * — the main runloop for a reload, a background coroutine for an update — so anything slow in here
 * (a network fetch, say) is felt directly by that thread.
 */
fun interface ShaderIncludeRootResolver {
    /** Resolve one include, or return `null` if this root does not have it (which the caller turns
     * into a hard error). */
    fun resolve(request: ShaderIncludeRootRequest): ShaderIncludeResolution?
}

private val shaderIncludeRoots = ConcurrentHashMap<String, ShaderIncludeRootResolver>()

/**
 * Claim a first path segment for a resolver, so `#include "root/some/file.glsl"` in any box's shader
 * is answered by [resolver] instead of being looked for as a box name.
 *
 * The registry is **process-global**, not per document or per box: one registration serves every
 * open workspace, and registering the same name again silently replaces the previous resolver
 * (nothing warns). [root] must be a single path segment — leading and trailing slashes are trimmed,
 * but an embedded `/` throws, as does an empty name.
 *
 * The trap worth knowing: roots are honoured by the `#include` path (resolved by shaderc while
 * compiling) but **not** by the template engine's `{% include "…" %}` tag, which only knows about
 * built-in includes and `box.property` references. A `{% include "lygia/math/const.glsl" %}` fails
 * with "no box named …" no matter what you have registered here.
 */
fun registerShaderIncludeRoot(root: String, resolver: ShaderIncludeRootResolver) {
    shaderIncludeRoots[normalizeShaderIncludeRootName(root)] = resolver
}

/** Remove an include root, returning the resolver that was registered under it (or `null` if none
 * was). Shaders already compiled keep working — this only affects the next compile. */
fun unregisterShaderIncludeRoot(root: String): ShaderIncludeRootResolver? =
    shaderIncludeRoots.remove(normalizeShaderIncludeRootName(root))

/** Every include-root name currently registered, sorted — the quickest way to answer "why isn't
 * `#include "lygia/…"` resolving?" from a box. */
fun listShaderIncludeRoots(): Set<String> = shaderIncludeRoots.keys.toSortedSet()

/**
 * Where a URL-backed include root keeps its on-disk copies, and how patient it is with the network.
 *
 * The cache is permanent and has no invalidation: once a file is written it is served from disk
 * forever, so picking up an upstream change means deleting the cached file (or the whole
 * [cacheDirectory]) yourself. Failures are not cached at all, so a 404 or a timeout is re-attempted
 * on every compile.
 */
data class ShaderRemoteIncludeCacheOptions(
    /** Directory the fetched includes are mirrored into, one file per include path. Created on
     * demand. Resolved to an absolute, normalised path, and includes that would escape it are
     * rejected — a remote server cannot talk you into writing outside this directory. */
    val cacheDirectory: Path,
    /** `HttpURLConnection` connect timeout, in milliseconds. */
    val connectTimeoutMillis: Int = 5_000,
    /** `HttpURLConnection` read timeout, in milliseconds. Both timeouts are felt by whichever thread
     * is compiling — the main runloop, on the reload path — so the defaults are short. */
    val readTimeoutMillis: Int = 5_000
)

private const val LYGIA_SHADER_ROOT = "lygia"
private const val LYGIA_REMOTE_BASE_URL = "https://lygia.xyz"

private fun defaultShaderIncludeCacheDirectory(): Path =
    Paths.get(System.getProperty("user.home"), ".field4", "shader-include-cache")

private fun cachePathForShaderInclude(cacheDirectory: Path, includePath: String): Path {
    val normalizedCacheDirectory = cacheDirectory.toAbsolutePath().normalize()
    val normalizedIncludePath = normalizeShaderIncludePath(includePath)
    val target = normalizedCacheDirectory.resolve(normalizedIncludePath).normalize()
    require(target.startsWith(normalizedCacheDirectory)) {
        "Shader include path '$includePath' escapes cache directory '$normalizedCacheDirectory'."
    }
    return target
}

private fun fetchRemoteShaderInclude(url: String, options: ShaderRemoteIncludeCacheOptions): String {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = true
    connection.connectTimeout = options.connectTimeoutMillis
    connection.readTimeout = options.readTimeoutMillis
    connection.setRequestProperty("Accept", "text/plain, text/x-glsl, */*")
    connection.setRequestProperty("User-Agent", "Field4 Shader Include Resolver")

    notify("Fetching remote shader include for ${url}", "remoteShader")

    return try {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {

            val details = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val message = buildString {
                append("HTTP ")
                append(code)
                append(" while fetching '")
                append(url)
                append("'")
                if (details != null) {
                    append(": ")
                    append(details)
                }
            }
            notify("error ${code} fetching remote shader include for ${url} ${details}", "remoteShader")

            throw IllegalArgumentException(message)
        }

        notify("fetched remote shader include for ${url}", "remoteShader")
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * An include root that fetches `baseUrl/<path>` over HTTP the first time and reads the local copy
 * every time after — how Field consumes a shader library that lives on a website rather than on
 * disk.
 *
 * Fetches are plain `GET`s with redirects followed; a non-200 throws (the body, if any, is appended
 * to the message) and is reported onto the box's shader lines like any other include failure.
 * Progress and failures are also pushed to the `remoteShader` notification channel, so the fetch is
 * visible in the UI. Because the fetch happens inline in shader compilation, a cold cache on a slow
 * link stalls whichever thread is compiling.
 *
 * The returned resolver is not registered anywhere; hand it to [registerShaderIncludeRoot]. Note
 * that [root] is used for the cache subdirectory *and* for the `resolvedPath` reported back to
 * shaderc, which is what makes relative includes *inside* the fetched library resolve against the
 * same root.
 */
fun shaderCachedUrlIncludeRootResolver(
    root: String,
    baseUrl: String,
    cacheOptions: ShaderRemoteIncludeCacheOptions = ShaderRemoteIncludeCacheOptions(
        cacheDirectory = defaultShaderIncludeCacheDirectory().resolve(normalizeShaderIncludeRootName(root))
    )
): ShaderIncludeRootResolver {
    val normalizedRoot = normalizeShaderIncludeRootName(root)
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    val normalizedCacheDirectory = cacheOptions.cacheDirectory.toAbsolutePath().normalize()

    return ShaderIncludeRootResolver { request ->
        val includePath = normalizeShaderIncludePath(request.includePath)
        val cacheFile = cachePathForShaderInclude(normalizedCacheDirectory, includePath)

        if (Files.isRegularFile(cacheFile)) {
            notify("remote shader is cached at ${cacheFile.toAbsolutePath()}", "remoteShader")
            return@ShaderIncludeRootResolver ShaderIncludeResolution(
                resolvedPath = canonicalShaderRootPath(normalizedRoot, includePath),
                source = Files.readString(cacheFile)
            )
        }

        val remoteUrl = "$normalizedBaseUrl/$includePath"
        val source = fetchRemoteShaderInclude(remoteUrl, cacheOptions)
        cacheFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(cacheFile, source, StandardCharsets.UTF_8)

        ShaderIncludeResolution(
            resolvedPath = canonicalShaderRootPath(normalizedRoot, includePath),
            source = source
        )
    }
}

/**
 * The [Lygia](https://lygia.xyz) GLSL library as an include root, cached under
 * `~/.field4/shader-include-cache/lygia.xyz` by default — one call and `#include "lygia/math/const.glsl"`
 * works.
 *
 * Just builds the resolver; [registerLygiaIncludeRoot] is the one-liner that also registers it under
 * the name `lygia`. Note the default cache directory is named after the *host* (`lygia.xyz`) rather
 * than after the root (`lygia`), so it differs from what [shaderCachedUrlIncludeRootResolver] would
 * have chosen for the same root.
 */
fun shaderLygiaIncludeRootResolver(
    cacheOptions: ShaderRemoteIncludeCacheOptions = ShaderRemoteIncludeCacheOptions(
        cacheDirectory = defaultShaderIncludeCacheDirectory().resolve("lygia.xyz")
    )
): ShaderIncludeRootResolver = shaderCachedUrlIncludeRootResolver(
    root = LYGIA_SHADER_ROOT,
    baseUrl = LYGIA_REMOTE_BASE_URL,
    cacheOptions = cacheOptions
)

/** The Lygia include root with the cache pointed somewhere of your choosing and the default
 * timeouts — a per-project cache directory, say, rather than the shared one under `~/.field4`. */
fun shaderLygiaIncludeRootResolver(cacheDirectory: Path): ShaderIncludeRootResolver =
    shaderLygiaIncludeRootResolver(ShaderRemoteIncludeCacheOptions(cacheDirectory = cacheDirectory))

/** The Lygia include root with the cache directory given as a string. Interpreted by
 * `Paths.get`, so a relative path is relative to the JVM's working directory — *not* to the
 * workspace. Prefer an absolute path. */
fun shaderLygiaIncludeRootResolver(cacheDirectory: String): ShaderIncludeRootResolver =
    shaderLygiaIncludeRootResolver(Paths.get(cacheDirectory))

/**
 * Register Lygia under the include root `lygia`, so every box's shaders can `#include "lygia/…"`.
 *
 * Process-global and idempotent-ish: calling it twice replaces the resolver, which is harmless but
 * also means a second call with different [cacheOptions] silently wins. Nothing fetches yet — the
 * first `#include` that needs a file it has not cached does.
 */
fun registerLygiaIncludeRoot(
    cacheOptions: ShaderRemoteIncludeCacheOptions = ShaderRemoteIncludeCacheOptions(
        cacheDirectory = defaultShaderIncludeCacheDirectory().resolve("lygia.xyz")
    )
) {
    registerShaderIncludeRoot(LYGIA_SHADER_ROOT, shaderLygiaIncludeRootResolver(cacheOptions))
}

/** Register Lygia under the root `lygia`, caching into [cacheDirectory]. */
fun registerLygiaIncludeRoot(cacheDirectory: Path) {
    registerShaderIncludeRoot(LYGIA_SHADER_ROOT, shaderLygiaIncludeRootResolver(cacheDirectory))
}

/** Register Lygia under the root `lygia`, caching into a directory named by string —
 * `Paths.get`-relative to the JVM's working directory, so prefer an absolute path. */
fun registerLygiaIncludeRoot(cacheDirectory: String) {
    registerLygiaIncludeRoot(Paths.get(cacheDirectory))
}

/**
 * An include root served straight from a directory on disk: `#include "root/sub/thing.glsl"` reads
 * `directory/sub/thing.glsl`.
 *
 * Read once per compile, not watched — editing a file on disk changes nothing until something
 * triggers a shader reload. Paths that would climb out of [directory] throw, and a path that simply
 * does not exist resolves to `null`, which the caller turns into a "cannot resolve include" error
 * against the including shader's line.
 *
 * [directory] is captured absolute and normalised at construction, so the resolver keeps working
 * whatever the JVM's working directory does later — but that also means a relative [directory] is
 * frozen against the working directory at *this* moment, not against any workspace.
 */
fun shaderFilesystemIncludeRootResolver(directory: Path): ShaderIncludeRootResolver {
    val rootDirectory = directory.toAbsolutePath().normalize()
    return ShaderIncludeRootResolver { request ->
        val target = rootDirectory.resolve(request.includePath).normalize()
        require(target.startsWith(rootDirectory)) {
            "Include '${request.requestedPath}' escapes filesystem shader root '$rootDirectory'."
        }
        if (!Files.isRegularFile(target)) return@ShaderIncludeRootResolver null
        ShaderIncludeResolution(
            resolvedPath = canonicalShaderRootPath(request.root, request.includePath),
            source = Files.readString(target)
        )
    }
}

/** A directory-backed include root named by string. `Paths.get`-relative to the JVM's working
 * directory, which is *not* the workspace directory — pass an absolute path unless you are certain. */
fun shaderFilesystemIncludeRootResolver(directory: String): ShaderIncludeRootResolver =
    shaderFilesystemIncludeRootResolver(Paths.get(directory))

/**
 * Register [directory] as the include root [root], so `#include "root/…"` reads files under it —
 * the one-liner for pulling a checked-out shader library into a workspace.
 *
 * Process-global (see [registerShaderIncludeRoot]) and read-through, not watched: edit a file on
 * disk and the change appears at the next shader reload, not immediately.
 */
fun registerShaderFilesystemIncludeRoot(root: String, directory: Path) {
    val normalizedRoot = normalizeShaderIncludeRootName(root)
    registerShaderIncludeRoot(normalizedRoot, shaderFilesystemIncludeRootResolver(directory))
}

/** Register a directory as an include root, with the directory given as a string —
 * `Paths.get`-relative to the JVM's working directory, so prefer an absolute path. */
fun registerShaderFilesystemIncludeRoot(root: String, directory: String) {
    registerShaderFilesystemIncludeRoot(root, Paths.get(directory))
}

/**
 * Create (or reconfigure) this box's GPU sample probe and hand it back for chaining — the Kotlin
 * half of `#include "fieldProbe"`, which lets a shader call `fieldProbe(x)` and see the resulting
 * distribution on the CPU.
 *
 * Typically `fieldProbeHere(samples = 4096).display("probe")`. The call is idempotent in the sense
 * that it reuses the box's existing probe rather than making a second one, but it **clears any
 * previously registered display channel and `onSnapshot` callback first** — that is deliberate, so a
 * re-run of the box does not accumulate stale registrations, and it is why `.display(…)` belongs on
 * the end of every call rather than being set up once.
 *
 * Read-back happens off the render thread. Two different throttles are involved and only one is
 * unconditional: the *display dispatch* is always limited to `probe.maxDisplayHz`, but the GPU→CPU
 * *read* is limited only in the plain `.display(…)` case. Turn on continuous reads, register an
 * `onSnapshot` callback, or use no display channel at all, and `shouldReadSnapshot` returns true every
 * frame — so an `onSnapshot` that does real work is on the read path at full frame rate and can cost
 * you. The probe's buffer is bound into a module's parameters when that module is built or reloaded,
 * so calling this *after* the module exists needs a reload to take effect.
 *
 * @param samples reservoir size pulled back to the CPU; clamped to 1..262144 by the probe
 * @param binding SSBO binding for `FieldProbeBuffer` — must match what the include declares
 * @param descriptorSet descriptor set the probe buffer lives in
 * @param enabled `false` leaves the instrumentation compiled in but recording nothing
 * @param ordinalRange restrict sampling to a slice of shader invocations; `null` resets to all of
 *   them (it does not mean "leave the previous range alone")
 */
fun Box.fieldProbeHere(
    samples: Int = afield.four.util.FIELD_PROBE_DEFAULT_SAMPLES,
    binding: Int = afield.four.util.FIELD_PROBE_DEFAULT_BINDING,
    descriptorSet: Int = afield.four.util.FIELD_PROBE_DEFAULT_SET,
    enabled: Boolean = true,
    ordinalRange: UIntRange? = null
): ShaderFieldProbe {
    val probe = ensureFieldProbe()
    probe.resetLiveCodingRegistrations()
    probe.configure(
        samples = samples,
        binding = binding,
        descriptorSet = descriptorSet,
        enabled = enabled
    )
    if (ordinalRange != null) probe.ordinalRange(ordinalRange)
    else probe.ordinalRange()
    return probe
}

/**
 * Declare that this box *produces* a GPU statistics block, and get the handle to read it back with —
 * the Kotlin half of `#include "fieldStats"` in a shader that records into it.
 *
 * Unlike the probe, nothing is sampled back to the CPU by default beyond a few scalars per channel:
 * the mean, variance, min/max and a quantile sketch are all reduced on the GPU, so a shader can feed
 * its own statistics back into itself without a round trip. See the Field Probes page for the GLSL
 * side.
 *
 * This is a **macro**: written in a box it is expanded at compile time (which is why every argument
 * has to be an integer *literal* — see [Box.macro_fieldStatsHere]) and rewritten to
 * [Box._fieldStatsHere]. Calling it from ordinary Kotlin outside a box works too, since it simply
 * delegates.
 *
 * A box that only *reads* someone else's stats calls [Box.useFieldStats] instead; calling this on
 * both boxes would allocate two unrelated blocks.
 *
 * @param channels independent distributions, 1..64
 * @param sketchSize quantile-sketch resolution; a power of two in 16..256
 * @param maxPartials per-channel partial-record capacity — if a snapshot reports `droppedPartials`
 *   above zero, this is the number to raise
 * @param descriptorSet descriptor set holding both stats buffers
 * @param partialBinding SSBO binding of the partial (per-invocation) buffer
 * @param resultBinding SSBO binding of the reduced result buffer
 * @param stateSlots GPU-side smoothed/EMA slots, 1..64
 */
fun Box.fieldStatsHere(
    channels: Int = 1,
    sketchSize: Int = 256,
    maxPartials: Int = 65_536,
    descriptorSet: Int = 0,
    partialBinding: Int = FIELD_STATS_DEFAULT_PARTIAL_BINDING,
    resultBinding: Int = FIELD_STATS_DEFAULT_RESULT_BINDING,
    stateSlots: Int = 8
): ShaderFieldStats = _fieldStatsHere(
    channels = channels,
    sketchSize = sketchSize,
    maxPartials = maxPartials,
    descriptorSet = descriptorSet,
    partialBinding = partialBinding,
    resultBinding = resultBinding,
    stateSlots = stateSlots
)

/**
 * The real work behind [Box.fieldStatsHere] — what the macro rewrites the call site to. Reach for it
 * directly only when you are driving a box from outside and do not want macro expansion.
 *
 * Reuses the box's existing [ShaderFieldStats] when *every* parameter matches and the block belongs
 * to this box; change one number and you get a fresh block with fresh buffers, and the previous one
 * is simply dropped on the floor — [ShaderFieldStats] has no disposal call of its own. Either way the
 * box is re-marked as the block's producer, so a box that had been a consumer via
 * [Box.useFieldStats] becomes a producer again.
 */
fun Box._fieldStatsHere(
    channels: Int = 1,
    sketchSize: Int = 256,
    maxPartials: Int = 65_536,
    descriptorSet: Int = 0,
    partialBinding: Int = FIELD_STATS_DEFAULT_PARTIAL_BINDING,
    resultBinding: Int = FIELD_STATS_DEFAULT_RESULT_BINDING,
    stateSlots: Int = 8
): ShaderFieldStats {
    _fieldStatsBinding?.stats?.takeIf {
        it.box === this &&
            it.channels == channels &&
            it.sketchSize == sketchSize &&
            it.maxPartials == maxPartials &&
            it.descriptorSet == descriptorSet &&
            it.partialBinding == partialBinding &&
            it.resultBinding == resultBinding &&
            it.stateSlots == stateSlots
    }?.let { existing ->
        _fieldStatsBinding = ShaderFieldStatsBinding(existing, producer = true)
        return existing
    }

    val stats = ShaderFieldStats(
        box = this,
        channels = channels,
        sketchSize = sketchSize,
        maxPartials = maxPartials,
        stateSlots = stateSlots,
        descriptorSet = descriptorSet,
        partialBinding = partialBinding,
        resultBinding = resultBinding
    )
    _fieldStatsBinding = ShaderFieldStatsBinding(stats, producer = true)
    return stats
}

/**
 * Macro expansion for `fieldStatsHere(...)`: parse the argument text, allocate the stats block *now*
 * (at expansion time, so a `computeModuleHere` in the same fragment can already resolve
 * `#include "fieldStats"`), and rewrite the call to [Box._fieldStatsHere].
 *
 * Because it has to know the numbers before anything is compiled, every argument must be an integer
 * literal — `channels = n` where `n` is a variable throws with that explanation rather than silently
 * using a default. Underscores in literals are fine (`65_536`). Positional and named arguments both
 * work, in the same order as [Box.fieldStatsHere]'s parameters.
 */
fun Box.macro_fieldStatsHere(args: List<String>): Insert {
    val call = args.joinToString("").trim()
    val body = call.removePrefix("(").removeSuffix(")").trim()
    val defaults = linkedMapOf(
        "channels" to 1,
        "sketchSize" to 256,
        "maxPartials" to 65_536,
        "descriptorSet" to 0,
        "partialBinding" to FIELD_STATS_DEFAULT_PARTIAL_BINDING,
        "resultBinding" to FIELD_STATS_DEFAULT_RESULT_BINDING,
        "stateSlots" to 8
    )
    val positionalNames = defaults.keys.toList()
    var positionalIndex = 0

    if (body.isNotEmpty()) {
        body.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { argument ->
            val (name, valueText) = if ('=' in argument) {
                argument.substringBefore('=').trim() to argument.substringAfter('=').trim()
            } else {
                require(positionalIndex < positionalNames.size) {
                    "fieldStatsHere has too many positional arguments."
                }
                positionalNames[positionalIndex++] to argument
            }
            require(name in defaults) { "Unknown fieldStatsHere argument '$name'." }
            val value = valueText.replace("_", "").toIntOrNull()
                ?: throw IllegalArgumentException(
                    "fieldStatsHere argument '$name' must be an integer literal so computeModuleHere can compile during macro expansion."
                )
            defaults[name] = value
        }
    }

    _fieldStatsHere(
        channels = defaults.getValue("channels"),
        sketchSize = defaults.getValue("sketchSize"),
        maxPartials = defaults.getValue("maxPartials"),
        descriptorSet = defaults.getValue("descriptorSet"),
        partialBinding = defaults.getValue("partialBinding"),
        resultBinding = defaults.getValue("resultBinding"),
        stateSlots = defaults.getValue("stateSlots")
    )

    return Insert(here = "_fieldStatsHere$call")
}

/**
 * Bind another box's [ShaderFieldStats] into this box as a **consumer**, so `#include "fieldStats"`
 * here emits the read-only GLSL instead of the recording GLSL.
 *
 * The idiom is one box recording (`fieldStatsHere`) and another reading the reduced result —
 * `consumerBox.useFieldStats(producerBox.fieldStatsHere(channels = 1))`. Allocates nothing; it only
 * records the binding, so the buffers stay owned by the producing box. Returns [stats] unchanged for
 * chaining.
 */
fun Box.useFieldStats(stats: ShaderFieldStats): ShaderFieldStats {
    _fieldStatsBinding = ShaderFieldStatsBinding(stats, producer = false)
    return stats
}

/**
 * How [Box.fieldProbeAutoControl] turns probe read-backs into controller updates: when a batch is
 * big enough to act on, what counts as clipped, and where the diagnostics card goes.
 *
 * The defaults assume the thing being controlled is a shader whose output should fill `0..1` — hence
 * `clipLower = 0.0`, `clipUpper = 1.0` — and that you want to watch it happen (`displayChannel =
 * "auto"`).
 */
data class ShaderProbeAutoControlConfig(
    /** Minimum samples in a read-back before the controller is allowed to learn from it. A snapshot
     * with fewer is skipped entirely — `tick()` returns `null` and nothing is applied. */
    val minSamples: Int = 128,
    /** Values at or below this (within [clipEpsilon]) count as clipped low and are reported to the
     * controller as a clip rate; `null` means "nothing clips at the bottom". */
    val clipLower: Double? = 0.0,
    /** Values at or above this (within [clipEpsilon]) count as clipped high; `null` means nothing
     * clips at the top. With both bounds `null` the reported clip rate is a flat `0.0`. */
    val clipUpper: Double? = 1.0,
    /** Tolerance for the two clip comparisons — floating-point slop, not a percentage. */
    val clipEpsilon: Double = 1.0e-6,
    /** `true` applies the controller's *nominal* parameters, `false` (the default) its *applied*
     * ones, which carry the small dither the controller needs to identify the system. Turn it on
     * once identification has settled and the dither is visible in the output. */
    val useNominalParams: Boolean = false,
    /** Box output channel for the HTML diagnostics table (nominal vs applied parameters, objective,
     * confidence, clip rate, p10/p50/p90). `null` disables the display entirely, which is what a
     * headless test wants. */
    val displayChannel: String? = "auto",
    /** Whether `stop()` closes the display channel by default. */
    val closeDisplayOnStop: Boolean = false,
    /** Whether `stop()` clears the display channel's contents by default. */
    val clearDisplayOnStop: Boolean = false,
    /** Prefix of the runloop hook key, which is `"$runloopKeySuffix:${box.id}"`. Two controllers on
     * one box need two different values here or the second silently displaces the first. */
    val runloopKeySuffix: String = "shaderFieldAuto"
)

/**
 * The running loop that closes a shader's feedback circuit: read the probe, push the samples into an
 * adaptive controller, hand the new parameters to your `apply` lambda, and draw the diagnostics.
 *
 * Build one with [Box.fieldProbeAutoControl] rather than directly — that is what registers it on the
 * box so a re-run can stop the previous one. Once [install]ed it ticks once per runloop frame; a
 * headless test can leave `install = false` and call [tick] by hand, as
 * `ShaderFieldAutoControlSmokeTest` does.
 *
 * Everything happens on whichever thread calls [tick] — the main runloop, normally — so the `apply`
 * lambda may write push constants directly without further synchronisation.
 */
class ShaderFieldAutoController(
    /** The box this controller belongs to: the source of the display channel and the identity in the
     * runloop hook key. */
    val box: Box,
    /** The probe being read. [Box.fieldProbeAutoControl] has already put it into continuous-read mode,
     * so it delivers a snapshot every frame rather than at the display rate. */
    val probe: ShaderFieldProbe,
    /** The adaptive controller doing the actual learning; its `parameterNames` label the diagnostics
     * table and its `pushSamples` is what turns a batch into parameters. */
    val controller: AdaptiveDistributionController,
    /** The thresholds, clip bounds and display settings this controller was built with. Read on every
     * tick, but the object is immutable — copy it and rebuild to change your mind. */
    val config: ShaderProbeAutoControlConfig,
    private val apply: (params: DoubleArray, output: ControlOutput, batch: FieldProbeScalarBatch) -> Unit
) {
    private val hookKey = "${config.runloopKeySuffix}:${box.id}"
    private var installed = false
    private var lastSnapshot: FieldProbeSnapshot? = null

    /** How many ticks have actually done work. Ticks that found no new snapshot, or too few samples,
     * do not count — so a `tickCount` stuck at zero means the probe is not delivering, not that the
     * hook is missing. */
    var tickCount: Int = 0
        private set

    /** The last probe batch the controller learned from, or `null` before the first useful tick.
     * Carries both the raw snapshot and the clip rate computed from [config]. */
    var lastBatch: FieldProbeScalarBatch? = null
        private set

    /** The last controller output — nominal and applied parameters plus diagnostics — or `null` before
     * the first useful tick. The same object the `apply` lambda was handed. */
    var lastOutput: ControlOutput? = null
        private set

    /**
     * Do one control step, returning the controller's output, or `null` if there was nothing to do.
     *
     * Two of the reasons are boring: the probe has produced no *new* snapshot since the last tick
     * (compared by identity, so a frame with no dispatch is skipped rather than double-counted), or the
     * snapshot has fewer than `config.minSamples` samples. The third is not — a probe recording anything
     * other than **scalars** yields no scalar batch at all, so a shader probing a `vec2`/`vec3`/`vec4`
     * makes this a permanent silent no-op with [tickCount] stuck at 0. None of the three is an error, and
     * none advances [tickCount].
     *
     * When it does run: samples go into the controller, the resulting parameters go to the `apply`
     * lambda *before* the diagnostics are drawn, and the display channel is refreshed. Call it on the
     * thread that owns the resources `apply` touches.
     */
    fun tick(): ControlOutput? {
        val batch = probe.readLatestScalarBatch(
            minSamples = config.minSamples,
            clipLower = config.clipLower,
            clipUpper = config.clipUpper,
            clipEpsilon = config.clipEpsilon
        ) ?: return null

        if (batch.snapshot === lastSnapshot) return null

        val output = controller.pushSamples(batch.batch.samples, batch.batch.clipRate)
        val params = output.params(useNominalParams = config.useNominalParams)

        apply(params, output, batch)

        lastSnapshot = batch.snapshot
        lastBatch = batch
        lastOutput = output
        tickCount += 1

        config.displayChannel?.let { channel ->
            box.display(
                channel,
                shaderFieldAutoSummaryHtml(
                    batch = batch,
                    output = output,
                    parameterNames = controller.parameterNames,
                    useNominalParams = config.useNominalParams
                )
            )
        }

        return output
    }

    /**
     * Install a `preRun` hook that calls [tick] once per frame, and return `this` for chaining.
     * Idempotent — a second call while already installed does nothing.
     *
     * The hook is keyed `"${config.runloopKeySuffix}:${box.id}"`, so one box gets one controller per
     * suffix. Note that the runloop is *not* remembered: if you install into a non-default runloop you
     * must pass the same one to [stop], or the hook is never removed and keeps ticking.
     */
    fun install(runloop: Runloop = Runloop.runLoop): ShaderFieldAutoController {
        if (installed) return this
        runloop.preRun[hookKey] = sequence {
            while (true) {
                tick()
                yield(Unit)
            }
        }
        installed = true
        return this
    }

    /**
     * Remove the runloop hook, optionally tidy the display channel, and forget this controller on the
     * box. Returns `this`, and can be re-[install]ed afterwards.
     *
     * Two things it does *not* do: it does not reset the controller's learned state (re-installing
     * resumes where it left off), and it does not take the probe out of continuous-read mode. It also
     * only removes the hook from the [runloop] you pass — the same asymmetry [install] warns about.
     */
    fun stop(
        runloop: Runloop = Runloop.runLoop,
        closeDisplay: Boolean = config.closeDisplayOnStop,
        clearDisplay: Boolean = config.clearDisplayOnStop
    ): ShaderFieldAutoController {
        runloop.preRun.remove(hookKey)
        installed = false

        config.displayChannel?.let { channel ->
            if (clearDisplay) box.displayClear(channel)
            if (closeDisplay) box.displayClose(channel)
        }

        if (box._fieldProbeAutoController === this) {
            box.properties.remove("_fieldProbeAutoController")
        }
        return this
    }
}

/**
 * Wire this box's probe into an adaptive controller, so a shader parameter is steered every frame by
 * what the shader's own output distribution turns out to be.
 *
 * The one-liner version of "close the loop": the probe reports a batch of scalar samples, the
 * controller compares their distribution against the target its feature extractor encodes, and your
 * `apply` lambda writes the resulting parameters wherever they belong — typically
 * `dispatch.push.set("offset", params[0].toFloat())`.
 *
 * Housekeeping done for you: any controller already on this box is stopped first (so re-running a box
 * cannot leave two of them fighting over the same push constants), the probe is switched into
 * continuous-read mode so a snapshot arrives every frame rather than at display rate, and the new
 * controller is stored on the box. With `install = true` (the default) it starts ticking immediately;
 * with `false` you own the pacing and call `tick()` yourself, which is what the smoke test does.
 *
 * @param probe the probe to read; defaults to this box's, created on demand
 * @param controller the learner; the default is an `AdaptiveDistributionController` over uniform
 *   quantile features, i.e. "make the output uniform over the clip range"
 * @param config thresholds, clip bounds and where the diagnostics card goes
 * @param install `false` to build it without registering the per-frame hook
 * @param runloop which runloop to install into — pass the *same* one to `stop()`
 * @param apply called on every useful tick with the parameters to apply, the full controller output,
 *   and the batch they were derived from
 */
fun Box.fieldProbeAutoControl(
    probe: ShaderFieldProbe = ensureFieldProbe(),
    controller: AdaptiveDistributionController = AdaptiveDistributionController(
        ControllerConfig(),
        UniformQuantileFeatureExtractor()
    ),
    config: ShaderProbeAutoControlConfig = ShaderProbeAutoControlConfig(),
    install: Boolean = true,
    runloop: Runloop = Runloop.runLoop,
    apply: (params: DoubleArray, output: ControlOutput, batch: FieldProbeScalarBatch) -> Unit
): ShaderFieldAutoController {
    _fieldProbeAutoController?.stop(runloop = runloop)
    probe.requireContinuousSnapshotReads()

    return ShaderFieldAutoController(
        box = this,
        probe = probe,
        controller = controller,
        config = config,
        apply = apply
    ).also { runtime ->
        _fieldProbeAutoController = runtime
        if (install) runtime.install(runloop)
    }
}

private fun shaderFieldAutoSummaryHtml(
    batch: FieldProbeScalarBatch,
    output: ControlOutput,
    parameterNames: List<String>,
    useNominalParams: Boolean
): String {
    fun format(value: Double): String = String.format(Locale.US, "%.4f", value)

    val activeParams = output.params(useNominalParams)
    val parameters = Table(listOf("parameter", "nominal", "applied"))
        .rows(parameterNames.indices.map { i ->
            listOf(parameterNames[i], output.nominalParams[i], output.appliedParams[i])
        })
        .format("nominal") { format(it as Double) }
        .format("applied") { format(it as Double) }

    val featureError = output.diagnostics.filteredFeatureError.joinToString(", ") { format(it) }
    val p10 = batch.snapshot.percentile(0.10)
    val p50 = batch.snapshot.percentile(0.50)
    val p90 = batch.snapshot.percentile(0.90)

    return FieldMarkdown.card(
        """
        {muted:updates} ${output.diagnostics.pushIndex} ·
        {muted:objective} ${format(output.diagnostics.objective)} ·
        {muted:confidence} ${format(output.diagnostics.confidence)} ·
        {muted:clip} ${format(batch.batch.clipRate)} ·
        {muted:samples} ${batch.batch.samples.size} ·
        {muted:p10/p50/p90} ${format(p10)} / ${format(p50)} / ${format(p90)}

        {muted:filtered feature error} = [${featureError.md}]

        {muted:active params} = [${activeParams.joinToString(", ") { format(it) }.md}]

        ${parameters.toHTML()}
        """
    )
}

// ------------------------------
// Public macro placeholders (expanded by MacroRegistry)
// ------------------------------
/**
 * Macro placeholder expanded to a graphics [ModulePointer] for the current box.
 *
 * This should only appear at macro expansion sites; calling it directly throws.
 */
fun Box.moduleHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro placeholder expanded to a [ComputeModulePointer] built from this box's `compute` property.
 *
 * Exists only to be rewritten; calling it directly throws. Expansion seeds a trivial default compute
 * shader if the box has none, compiles it, and generates the `ComputeMesh` push-constant and buffer
 * accessors into the box's preamble.
 */
fun Box.computeModuleHere(p: Parameters): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro placeholder expanded to a [MeshModulePointer] built from this box's `task`, `mesh` *and*
 * `fragment` properties — all three are required, and defaults are seeded for any that are missing.
 *
 * Exists only to be rewritten; calling it directly throws.
 */
fun Box.meshModuleHere(p: Parameters): MeshModulePointer {
    throw MacroRegistry.MacroNotExpandedException("meshModuleHere should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro placeholder expanded to a [RayTracingModulePointer] built from this box's `raygen` property
 * plus whichever of `miss`/`closesthit`/`anyhit`/`intersection`/`callable` are non-blank.
 *
 * Exists only to be rewritten; calling it directly throws.
 */
fun Box.rayTracingModuleHere(p: Parameters): RayTracingModulePointer {
    throw MacroRegistry.MacroNotExpandedException("rayTracingModuleHere should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro placeholder for the trailing-lambda form, `moduleHere { primitiveType = triangles }`.
 *
 * The lambda configures the module's [Parameters] — blending, depth test, primitive type — but **not**
 * its shaders: assigning `vertex` inside it has no effect, because the prepared source from the box's
 * properties is written over the top. Seed shaders with `once.vertex { }` in an earlier `//%-`
 * fragment instead. Calling it directly throws.
 */
fun Box.moduleHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

/** Macro placeholder for `computeModuleHere { … }`, the trailing-lambda form. The lambda configures
 * [Parameters], not the shader source. Calling it directly throws. */
fun Box.computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

/** Macro placeholder for `meshModuleHere { … }`, the trailing-lambda form. Calling it directly
 * throws. */
fun Box.meshModuleHere(p: Parameters.() -> Unit): MeshModulePointer {
    throw MacroRegistry.MacroNotExpandedException("meshModuleHere should not be callable directly, but should be expanded as a macro")
}

/** Macro placeholder for `rayTracingModuleHere { … }`, the trailing-lambda form. Calling it directly
 * throws. */
fun Box.rayTracingModuleHere(p: Parameters.() -> Unit): RayTracingModulePointer {
    throw MacroRegistry.MacroNotExpandedException("rayTracingModuleHere should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro placeholder expanded to a Shadertoy-shaped graphics module: a full-screen triangle, the
 * `iResolution`/`iTime`/`iMouse`/`iDate` uniform block already bound, four 1×1 dummy `iChannel`
 * textures, and depth testing off — so pasting a `mainImage` body into the box's fragment shader
 * mostly just works.
 *
 * Exists only to be rewritten; calling it directly throws.
 */
fun Box.shadertoyHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("shadertoyHere should not be callable directly, but should be expanded as a macro")
}

/** Macro placeholder for `shadertoyHere { … }`, the trailing-lambda form. Calling it directly
 * throws. */
fun Box.shadertoyHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("shadertoyHere should not be callable directly, but should be expanded as a macro")
}


// ------------------------------
// Utilities
// ------------------------------
private val glslSourceLineRegex = Regex("""\b(?:ERROR|WARNING):\s*\d+:(\d+):""", RegexOption.IGNORE_CASE)
private val fileLineRegex = Regex(""":[ \t]*(\d+):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val slangSourceLineRegex = Regex("""\((\d+)\):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val anyColonLineRegex = Regex(""":(\d+):""")
private val explicitLineRegex = Regex("""\bline\s+(\d+)\b""", RegexOption.IGNORE_CASE)

private val slangShaderPropertyByBase = mapOf(
    "vertex" to "vertex_s",
    "fragment" to "fragment_s",
    "compute" to "compute_s",
    "task" to "task_s",
    "mesh" to "mesh_s",
    "raygen" to "raygen_s",
    "miss" to "miss_s",
    "closesthit" to "closesthit_s",
    "anyhit" to "anyhit_s",
    "intersection" to "intersection_s",
    "callable" to "callable_s"
)
private const val sharedVertexFragmentProperty = "vertex_fragment"

private data class ShaderIncludeRef(val boxName: String, val property: String) {
    val key = "${boxName.trim()}.${property.trim()}"
}

/**
 * Dig a line number out of a shader (or Vulkan) error message, so the error can be pinned to a line
 * in the box's editor rather than dumped in the log.
 *
 * Tries four known shapes in order — glslang's `ERROR: 0:12:`, a `file:12: error`, Slang's
 * `(12): error`, and a bare `line 12` — and only then falls back to "the *last* `:N:` anywhere in the
 * message", which is a guess and occasionally a wrong one. Returns `null` when even that finds
 * nothing; callers in this file treat `null` as line 0, i.e. "somewhere in this text".
 */
fun numberFromErrorMessage(m: String): Int? {
    glslSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    fileLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    slangSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    explicitLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return anyColonLineRegex.findAll(m).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.lastOrNull()
}

private fun slangPropertyFor(baseProperty: String): String? = slangShaderPropertyByBase[baseProperty.lowercase()]

private fun shaderPropertyVariants(baseProperty: String): List<String> {
    val normalized = baseProperty.lowercase()
    val shared = if (normalized == "vertex" || normalized == "fragment") listOf(sharedVertexFragmentProperty) else emptyList()
    return shared + listOfNotNull(normalized, slangPropertyFor(normalized))
}

private fun isSlangShaderProperty(property: String): Boolean =
    property.lowercase() == sharedVertexFragmentProperty ||
        property.lowercase().endsWith("_s") && baseShaderProperty(property) in slangShaderPropertyByBase

private fun baseShaderProperty(property: String): String = when (property.lowercase()) {
    "vertex", "vertex_s" -> "vertex"
    "fragment", "fragment_s" -> "fragment"
    "compute", "compute_s" -> "compute"
    "task", "task_s" -> "task"
    "mesh", "mesh_s" -> "mesh"
    "raygen", "raygen_s" -> "raygen"
    "miss", "miss_s" -> "miss"
    "closesthit", "closesthit_s" -> "closesthit"
    "anyhit", "anyhit_s" -> "anyhit"
    "intersection", "intersection_s" -> "intersection"
    "callable", "callable_s" -> "callable"
    else -> property.lowercase()
}

private fun defaultShaderProperty(kind: ShaderKind): String = when (kind) {
    ShaderKind.VERTEX_SHADER -> "vertex"
    ShaderKind.FRAGMENT_SHADER -> "fragment"
    ShaderKind.COMPUTE_SHADER -> "compute"
    ShaderKind.TASK_SHADER -> "task"
    ShaderKind.MESH_SHADER -> "mesh"
    ShaderKind.RAYGEN_SHADER -> "raygen"
    ShaderKind.MISS_SHADER -> "miss"
    ShaderKind.CLOSEST_HIT_SHADER -> "closesthit"
    ShaderKind.ANY_HIT_SHADER -> "anyhit"
    ShaderKind.INTERSECTION_SHADER -> "intersection"
    ShaderKind.CALLABLE_SHADER -> "callable"
    else -> "fragment"
}

private fun shaderKindForProperty(property: String, fallback: ShaderKind): ShaderKind = when (property.lowercase()) {
    "vertex", "vertex_s" -> ShaderKind.VERTEX_SHADER
    "fragment", "fragment_s" -> ShaderKind.FRAGMENT_SHADER
    "compute", "compute_s" -> ShaderKind.COMPUTE_SHADER
    "task", "task_s" -> ShaderKind.TASK_SHADER
    "mesh", "mesh_s" -> ShaderKind.MESH_SHADER
    "raygen", "raygen_s" -> ShaderKind.RAYGEN_SHADER
    "miss", "miss_s" -> ShaderKind.MISS_SHADER
    "closesthit", "closesthit_s" -> ShaderKind.CLOSEST_HIT_SHADER
    "anyhit", "anyhit_s" -> ShaderKind.ANY_HIT_SHADER
    "intersection", "intersection_s" -> ShaderKind.INTERSECTION_SHADER
    "callable", "callable_s" -> ShaderKind.CALLABLE_SHADER
    else -> fallback
}

private fun normalizeShaderIncludeRootName(root: String): String {
    val normalized = root.trim().trim('/').trim('\\')
    require(normalized.isNotEmpty()) { "Shader include root name cannot be empty." }
    require('/' !in normalized && '\\' !in normalized) {
        "Shader include root '$normalized' must be a single path segment."
    }
    return normalized
}

private fun normalizeShaderIncludePath(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    require(normalized.isNotEmpty()) { "Shader include path cannot be empty." }

    val parts = mutableListOf<String>()
    normalized.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> {
                require(parts.isNotEmpty()) { "Shader include path '$path' escapes its root." }
                parts.removeLast()
            }
            else -> parts += part
        }
    }

    return parts.joinToString("/")
}

private fun canonicalShaderRootPath(root: String, includePath: String): String =
    buildString {
        append(normalizeShaderIncludeRootName(root))
        if (includePath.isNotBlank()) {
            append('/')
            append(normalizeShaderIncludePath(includePath))
        }
    }

private fun boxShaderSourceName(box: Box, property: String): String {
    val boxLabel = box.name.trim().ifBlank { "box" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
    val propertyLabel = property.trim().ifBlank { "shader" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return "field/$boxLabel-${box.id}/$propertyLabel.glsl"
}

private fun resolveShaderIncludeRoot(request: ShaderIncludeRequest): ShaderIncludeResolution? {
    val requestedPath = request.requestedPath.trim()
    val requestingSource = request.requestingSource?.trim()?.takeIf { it.isNotEmpty() }

    val directRoot = requestedPath.substringBefore('/', missingDelimiterValue = "")
    if (directRoot.isNotEmpty() && shaderIncludeRoots.containsKey(directRoot)) {
        val includePath = normalizeShaderIncludePath(requestedPath.substringAfter('/'))
        val resolution = shaderIncludeRoots.getValue(directRoot).resolve(
            ShaderIncludeRootRequest(
                root = directRoot,
                includePath = includePath,
                requestedPath = requestedPath,
                requestingSourcePath = requestingSource,
                includeType = request.includeType,
                includeDepth = request.includeDepth
            )
        )
        requireNotNull(resolution) {
            "Unable to resolve include '$requestedPath' from '${requestingSource ?: "<root>"}' in shader root '$directRoot'."
        }
        return resolution
    }

    if (request.includeType == ShaderIncludeType.RELATIVE && requestingSource != null) {
        val requestingRoot = requestingSource.substringBefore('/', missingDelimiterValue = "")
        if (shaderIncludeRoots.containsKey(requestingRoot)) {
            val basePath = requestingSource.removePrefix("$requestingRoot/")
            val baseDirectory = basePath.substringBeforeLast('/', "")
            val resolvedRelativePath = normalizeShaderIncludePath(
                listOfNotNull(baseDirectory.takeIf { it.isNotBlank() }, requestedPath)
                    .joinToString("/")
            )
            val resolution = shaderIncludeRoots.getValue(requestingRoot).resolve(
                ShaderIncludeRootRequest(
                    root = requestingRoot,
                    includePath = resolvedRelativePath,
                    requestedPath = requestedPath,
                    requestingSourcePath = requestingSource,
                    includeType = request.includeType,
                    includeDepth = request.includeDepth
                )
            )
            requireNotNull(resolution) {
                "Unable to resolve include '$requestedPath' from '$requestingSource' in shader root '$requestingRoot'."
            }
            return resolution
        }
    }

    return null
}

private fun parseShaderIncludeRef(path: String, defaultProperty: String): ShaderIncludeRef {
    val trimmed = path.trim()
    require(trimmed.isNotEmpty()) { "Empty shader include path." }
    val dot = trimmed.lastIndexOf('.')
    if (dot >= 0) {
        require(dot > 0 && dot < trimmed.lastIndex) {
            "Invalid shader include path '$trimmed'. Use '<box>' or '<box>.<property>'."
        }
        val boxName = trimmed.substring(0, dot).trim()
        val property = trimmed.substring(dot + 1).trim()
        require(boxName.isNotEmpty() && property.isNotEmpty()) {
            "Invalid shader include path '$trimmed'."
        }
        return ShaderIncludeRef(boxName, property)
    }
    return ShaderIncludeRef(trimmed, defaultProperty)
}

private fun findBoxByNameForInclude(inside: Box, requestedName: String): Box? {
    val name = requestedName.trim()
    if (name.isEmpty()) return null

    var found: Box? = null
    inside.visitParents {
        if (found == null && it.name == name && !it.getPropertyRaw("_tombstone").isTrue()) found = it
    }
    if (found != null) return found

    val fringe = ArrayDeque<Box>()
    val seen = mutableSetOf<Box>()
    fringe.add(inside.root)
    while (fringe.isNotEmpty()) {
        val next = fringe.removeFirst()
        if (!seen.add(next)) continue
        if (next.name == name && !next.getPropertyRaw("_tombstone").isTrue()) return next
        fringe.addAll(next.children)
    }
    return null
}

private fun getIncludePropertyValue(box: Box, property: String): String? {
    val normalized = property.trim()
    return when (normalized.lowercase()) {
        "vertex" -> box.vertex
        "vertex_fragment" -> box.vertex_fragment
        "vertex_s" -> box.vertex_s
        "fragment" -> box.fragment
        "fragment_s" -> box.fragment_s
        "compute" -> box.compute
        "compute_s" -> box.compute_s
        "task" -> box.task
        "task_s" -> box.task_s
        "mesh" -> box.mesh
        "mesh_s" -> box.mesh_s
        "raygen" -> box.raygen
        "raygen_s" -> box.raygen_s
        "miss" -> box.miss
        "miss_s" -> box.miss_s
        "closesthit" -> box.closesthit
        "closesthit_s" -> box.closesthit_s
        "anyhit" -> box.anyhit
        "anyhit_s" -> box.anyhit_s
        "intersection" -> box.intersection
        "intersection_s" -> box.intersection_s
        "callable" -> box.callable
        "callable_s" -> box.callable_s
        else -> box.getPropertyRaw(normalized) as? String
    }
}

private fun Box.ensureFieldProbe(): ShaderFieldProbe {
    val existing = _fieldProbe
    if (existing != null) return existing
    return ShaderFieldProbe(this).also { _fieldProbe = it }
}

private fun builtInShaderInclude(inside: Box, includePath: String): String? {
    return when (includePath.trim()) {
        FIELD_PROBE_INCLUDE_NAME -> inside.ensureFieldProbe().includeSource()
        FIELD_STATS_INCLUDE_NAME -> inside._fieldStatsBinding?.let {
            it.stats.includeSource(producer = it.producer)
        }
            ?: throw IllegalStateException(
                "Include '$FIELD_STATS_INCLUDE_NAME' requires fieldStatsHere(...) or useFieldStats(...)."
            )
        FIELD_QUANTILE_WARP_INCLUDE_NAME -> fieldQuantileWarpIncludeSource()
        else -> null
    }
}

private fun shaderIncludeResolverFor(inside: Box, defaultProperty: String): ShaderIncludeResolver = ShaderIncludeResolver { request ->
    val includePath = request.requestedPath.trim()

    builtInShaderInclude(inside, includePath)?.let { builtIn ->
        return@ShaderIncludeResolver ShaderIncludeResolution(
            resolvedPath = "field/include/$includePath.glsl",
            source = builtIn
        )
    }

    resolveShaderIncludeRoot(request)?.let { return@ShaderIncludeResolver it }

    if (includePath.contains('/')) {
        val source = request.requestingSource?.takeIf { it.isNotBlank() } ?: boxShaderSourceName(inside, defaultProperty)
        throw IllegalArgumentException(
            "Unable to resolve include '$includePath' from '$source': no registered include root named '${includePath.substringBefore('/')}'."
        )
    }

    val includeRef = parseShaderIncludeRef(includePath, defaultProperty)
    val targetBox = findBoxByNameForInclude(inside, includeRef.boxName)
        ?: throw IllegalArgumentException("Unable to resolve include '$includePath': no box named '${includeRef.boxName}'.")
    val includeBody = getIncludePropertyValue(targetBox, includeRef.property)
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException(
            "Unable to resolve include '$includePath': '${includeRef.property}' on box '${targetBox.name}' is empty."
        )

    ShaderIncludeResolution(
        resolvedPath = boxShaderSourceName(targetBox, includeRef.property),
        source = includeBody
    )
}

private fun attachFieldProbe(box: Box, parameters: Parameters) {
    box._fieldProbe?.let { probe ->
        parameters.fieldProbe = probe

        val previousResolver = parameters.ssboResolver
        parameters.ssboResolver = { requested ->
            if (requested.name == FIELD_PROBE_BUFFER_NAME) {
                probe.bufferParameters(parameters.renderer)
            } else {
                previousResolver(requested)
            }
        }
    }

    box._fieldStatsBinding?.let { binding ->
        parameters.fieldStats = binding
        val previousResolver = parameters.ssboResolver
        parameters.ssboResolver = { requested ->
            when (requested.name) {
                FIELD_STATS_PARTIAL_BUFFER_NAME -> binding.stats.partialBufferParameters(parameters.renderer)
                FIELD_STATS_RESULT_BUFFER_NAME -> binding.stats.resultBufferParameters(parameters.renderer)
                else -> previousResolver(requested)
            }
        }
    }
}

private fun preprocess(
    inside: Box,
    source: String,
    kind: ShaderKind,
    defaultProperty: String = defaultShaderProperty(kind),
    includeStack: List<String> = emptyList(),
    priorStages: Map<ShaderKind, PreparedShaderStage> = emptyMap(),
    stackAutoEmitted: MutableMap<String, StackAutoShaderTarget> = mutableMapOf()
): String {
    val sourceWithCopiedPushConstants = expandCopyPushConstantTags(
        source = source,
        kind = kind,
        currentProperty = defaultProperty,
        priorStages = priorStages
    )

    val includeResolver = IncludeResolver { includePath, line, _ ->
        builtInShaderInclude(inside, includePath)?.let { builtIn ->
            return@IncludeResolver buildString {
                append(builtIn)
                if (!builtIn.endsWith('\n')) append('\n')
                append("#line ${line + 2}\n")
            }
        }

        val includeRef = parseShaderIncludeRef(includePath, defaultProperty)
        val includeKey = includeRef.key
        if (includeStack.contains(includeKey)) {
            val chain = (includeStack + includeKey).joinToString(" -> ")
            throw IllegalArgumentException("Include cycle at line $line while resolving '$includePath': $chain")
        }

        val targetBox = findBoxByNameForInclude(inside, includeRef.boxName)
            ?: throw IllegalArgumentException(
                "Unable to resolve include '$includePath' at line $line: no box named '${includeRef.boxName}'."
            )

        val includeBody = getIncludePropertyValue(targetBox, includeRef.property)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "Unable to resolve include '$includePath' at line $line: '${includeRef.property}' on box '${targetBox.name}' is empty."
            )

        val nestedKind = shaderKindForProperty(includeRef.property, kind)
        val expandedInclude = preprocess(
            inside,
            includeBody,
            nestedKind,
            defaultProperty = includeRef.property,
            includeStack = includeStack + includeKey,
            priorStages = priorStages,
            stackAutoEmitted = stackAutoEmitted
        )
        buildString {
            append(expandedInclude)
            if (!expandedInclude.endsWith('\n')) append('\n')
            append("#line ${line + 2}\n")
        }
    }

    return runShaderTemplate(
        sourceWithCopiedPushConstants,
        listOf(stackAutoTemplateResolverFor(inside, stackAutoEmitted)) + getShaderTemplateResolverForBox(inside),
        includeResolver
    )
}

/**
 * Run one shader stage's source through Field's preprocessor and hand back the GLSL that shaderc would
 * see — the thing to call when you are building a module by hand instead of through a
 * `moduleHere`-family macro.
 *
 * Three expansions happen here, all resolved against [inside] and its ancestors: `{{ slot }}` template
 * expressions (from [Box.shaderTemplate] and the global registry), `{% include "box.property" %}` tags
 * (recursively preprocessed, cycle-detected, and fitted with `#line` directives so error line numbers
 * still point at your text), and `{{ stack.auto.name }}` slots compiled from StackSL. Built-in
 * includes — `fieldProbe`, `fieldStats`, `quantileWarp` — resolve here too, and asking for `fieldStats`
 * without a [Box.fieldStatsHere] or [Box.useFieldStats] binding throws.
 *
 * Two things this overload cannot do, because it has no notion of a previous stage or a
 * registered root: `{% copyPC %}` throws (there is no prior stage to copy a push-constant block from),
 * and `{% include "someRoot/file.glsl" %}` fails as "no box named …" — registered include roots are
 * only reachable from a real `#include` during compilation, not from the template tag.
 *
 * Failures throw rather than being reported onto the box; the reload path catches them for you, a
 * hand-rolled caller must not.
 */
fun preprocess(inside: Box, code: String, kind: ShaderKind): String {
    return preprocess(inside, code, kind, defaultShaderProperty(kind), emptyList())
}

private val copyPushConstantTagRegex = Regex("""\{%\s*copyPC\s*%}""")
private val layoutKeywordRegex = Regex("""\blayout\b""")
private val pushConstantLayoutRegex = Regex("""\bpush_constant\b""")

private fun expandCopyPushConstantTags(
    source: String,
    kind: ShaderKind,
    currentProperty: String,
    priorStages: Map<ShaderKind, PreparedShaderStage>
): String {
    if (!copyPushConstantTagRegex.containsMatchIn(source)) return source

    val previousStage = previousStageForCopyPushConstants(kind, priorStages)
        ?: throw IllegalArgumentException(
            "'{% copyPC %}' in '$currentProperty' requires a previous prepared shader stage. " +
                "Supported flows are vertex -> fragment and task -> mesh -> fragment."
        )

    val declaration = extractPushConstantLayoutDeclaration(previousStage.glslSource, previousStage.sourceName)
    return copyPushConstantTagRegex.replace(source, declaration)
}

private fun previousStageForCopyPushConstants(
    kind: ShaderKind,
    priorStages: Map<ShaderKind, PreparedShaderStage>
): PreparedShaderStage? = when (kind) {
    ShaderKind.MESH_SHADER -> priorStages[ShaderKind.TASK_SHADER]
    ShaderKind.FRAGMENT_SHADER -> priorStages[ShaderKind.MESH_SHADER] ?: priorStages[ShaderKind.VERTEX_SHADER]
    ShaderKind.MISS_SHADER,
    ShaderKind.CLOSEST_HIT_SHADER,
    ShaderKind.ANY_HIT_SHADER,
    ShaderKind.INTERSECTION_SHADER,
    ShaderKind.CALLABLE_SHADER -> priorStages[ShaderKind.RAYGEN_SHADER]
    else -> null
}

private fun extractPushConstantLayoutDeclaration(source: String, sourceName: String): String {
    val matches = mutableListOf<String>()
    var searchFrom = 0

    while (true) {
        val layoutMatch = layoutKeywordRegex.find(source, searchFrom) ?: break
        val layoutStart = layoutMatch.range.first
        var cursor = skipShaderTrivia(source, layoutMatch.range.last + 1)
        if (cursor >= source.length || source[cursor] != '(') {
            searchFrom = layoutStart + 1
            continue
        }

        val layoutEnd = findMatchingDelimiter(source, cursor, '(', ')')
            ?: throw IllegalArgumentException("Unclosed layout(...) declaration while scanning '$sourceName'.")
        val layoutArgs = source.substring(cursor + 1, layoutEnd)
        if (!pushConstantLayoutRegex.containsMatchIn(layoutArgs)) {
            searchFrom = layoutEnd + 1
            continue
        }

        cursor = skipShaderTrivia(source, layoutEnd + 1)
        if (!startsWithShaderKeyword(source, cursor, "uniform")) {
            throw IllegalArgumentException(
                "Found layout(... push_constant ...) in '$sourceName' but could not parse the following uniform block."
            )
        }
        cursor = skipShaderTrivia(source, cursor + "uniform".length)

        val blockStart = findNextShaderChar(source, cursor, '{')
            ?: throw IllegalArgumentException(
                "Push constant layout in '$sourceName' is missing the '{' that starts its uniform block."
            )
        val blockEnd = findMatchingDelimiter(source, blockStart, '{', '}')
            ?: throw IllegalArgumentException("Unclosed push constant uniform block while scanning '$sourceName'.")
        val declarationEnd = findNextShaderChar(source, blockEnd + 1, ';')
            ?: throw IllegalArgumentException("Push constant uniform block in '$sourceName' is missing its trailing ';'.")

        matches += source.substring(layoutStart, declarationEnd + 1).trimEnd()
        searchFrom = declarationEnd + 1
    }

    return when (matches.size) {
        0 -> throw IllegalArgumentException("'{% copyPC %}' could not find a push constant layout declaration in '$sourceName'.")
        1 -> matches.single()
        else -> throw IllegalArgumentException(
            "'{% copyPC %}' expected exactly one push constant layout declaration in '$sourceName', found ${matches.size}."
        )
    }
}

private fun skipShaderTrivia(source: String, start: Int): Int {
    var index = start
    while (index < source.length) {
        when {
            source[index].isWhitespace() -> index += 1
            source.startsWith("//", index) -> {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline == -1) source.length else newline + 1
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end == -1) return source.length
                index = end + 2
            }
            else -> return index
        }
    }
    return index
}

private fun findMatchingDelimiter(source: String, openIndex: Int, openChar: Char, closeChar: Char): Int? {
    var depth = 0
    var index = openIndex
    while (index < source.length) {
        when {
            source.startsWith("//", index) -> {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline == -1) source.length else newline + 1
                continue
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end == -1) return null
                index = end + 2
                continue
            }
            source[index] == openChar -> depth += 1
            source[index] == closeChar -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += 1
    }
    return null
}

private fun startsWithShaderKeyword(source: String, index: Int, keyword: String): Boolean {
    if (index < 0 || index + keyword.length > source.length) return false
    if (!source.regionMatches(index, keyword, 0, keyword.length)) return false
    val before = source.getOrNull(index - 1)
    val after = source.getOrNull(index + keyword.length)
    return !before.isShaderIdentifierPart() && !after.isShaderIdentifierPart()
}

private fun findNextShaderChar(source: String, start: Int, target: Char): Int? {
    var index = start
    while (index < source.length) {
        when {
            source.startsWith("//", index) -> {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline == -1) source.length else newline + 1
                continue
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end == -1) return null
                index = end + 2
                continue
            }
            source[index] == target -> return index
            else -> index += 1
        }
    }
    return null
}

private fun Char?.isShaderIdentifierPart(): Boolean =
    this != null && (this == '_' || this.isLetterOrDigit())

/**
 * What one stage's compile produced: its [Introspection], or `null` when the compile failed.
 *
 * [hadError] is the authoritative flag — `introspection == null` implies it, but callers test
 * [hadError] because a stage can fail after producing partial reflection. The compiling itself, and
 * the `box.error` reporting, live in `compileStage`; this is only the value it hands back.
 */
private data class CompileResult(val introspection: Introspection?, val hadError: Boolean)

private enum class ShaderReloadType {
    GRAPHICS,
    COMPUTE,
    MESH,
    RAY_TRACING
}

private data class ShaderReloadKey(
    val boxIdentity: Int,
    val type: ShaderReloadType
)

private val shaderReloadScope =
    CoroutineScope(SupervisorJob() + Runloop.miscPool.asCoroutineDispatcher())

private val shaderReloadGenerations = ConcurrentHashMap<ShaderReloadKey, AtomicLong>()
private val shaderReloadErrorGate = ThreadLocal<(() -> Boolean)?>()

private fun currentShaderReloadMayReport(): Boolean =
    shaderReloadErrorGate.get()?.invoke() ?: true

private fun nextShaderGeneration(box: Box, type: ShaderReloadType): Pair<ShaderReloadKey, Long> {
    val key = ShaderReloadKey(System.identityHashCode(box), type)
    val generation = shaderReloadGenerations.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    return key to generation
}

private fun isCurrentShaderGeneration(key: ShaderReloadKey, generation: Long): Boolean =
    shaderReloadGenerations[key]?.get() == generation

private inline fun <T> withShaderReloadErrorGate(key: ShaderReloadKey, generation: Long, block: () -> T): T {
    val previous = shaderReloadErrorGate.get()
    shaderReloadErrorGate.set { isCurrentShaderGeneration(key, generation) }
    try {
        return block()
    } finally {
        shaderReloadErrorGate.set(previous)
    }
}

private data class StackAutoShaderUpdateState(
    val inFlight: AtomicBoolean = AtomicBoolean(false),
    val pending: AtomicBoolean = AtomicBoolean(false)
)

private val stackAutoShaderUpdateStates = ConcurrentHashMap<ShaderReloadKey, StackAutoShaderUpdateState>()

private suspend fun runStackAutoShaderUpdate(box: Box, type: ShaderReloadType, key: ShaderReloadKey, generation: Long): Boolean =
    when (type) {
        ShaderReloadType.GRAPHICS -> updateShader(box, key, generation)
        ShaderReloadType.COMPUTE -> updateComputeShader(box, key, generation)
        ShaderReloadType.MESH -> withContext(Runloop.runLoop.main) {
            if (!isCurrentShaderGeneration(key, generation)) return@withContext false
            withShaderReloadErrorGate(key, generation) {
                reloadMeshShader(box)
            }
        }
        ShaderReloadType.RAY_TRACING -> withContext(Runloop.runLoop.main) {
            if (!isCurrentShaderGeneration(key, generation)) return@withContext false
            withShaderReloadErrorGate(key, generation) {
                reloadRayTracingShader(box)
            }
        }
    }

private fun stackAutoShaderTypeLabel(type: ShaderReloadType): String =
    when (type) {
        ShaderReloadType.GRAPHICS -> "shader"
        ShaderReloadType.COMPUTE -> "compute shader"
        ShaderReloadType.MESH -> "mesh shader"
        ShaderReloadType.RAY_TRACING -> "ray tracing shader"
    }

private fun requestStackAutoShaderUpdate(box: Box, type: ShaderReloadType) {
    val updateKey = ShaderReloadKey(System.identityHashCode(box), type)
    val state = stackAutoShaderUpdateStates.computeIfAbsent(updateKey) {
        StackAutoShaderUpdateState()
    }
    state.pending.set(true)

    if (!state.inFlight.compareAndSet(false, true)) {
        nextShaderGeneration(box, type)
        return
    }

    shaderReloadScope.launch {
        try {
            do {
                state.pending.set(false)
                val (key, generation) = nextShaderGeneration(box, type)
                val label = stackAutoShaderTypeLabel(type)
                val success = try {
                    runStackAutoShaderUpdate(box, type, key, generation)
                } catch (t: Throwable) {
                    lprintln(
                        "error,graphics",
                        "unexpected error updating stack.auto $label in '${box.name}' (${box.id}): ${t.message}"
                    )
                    if (isCurrentShaderGeneration(key, generation)) {
                        withContext(Runloop.runLoop.main) {
                            box.error(t.message ?: "stack.auto $label update failed", "code", -1)
                        }
                    }
                    false
                }

                if (isCurrentShaderGeneration(key, generation)) {
                    withContext(Runloop.runLoop.main) {
                        notify(
                            if (success) "Updated stack.auto $label in '${box.name}'"
                            else "Stack.auto $label update failed in '${box.name}'",
                            "shader"
                        )
                    }
                }
            } while (state.pending.get())
        } finally {
            state.inFlight.set(false)
            if (state.pending.get()) requestStackAutoShaderUpdate(box, type)
        }
    }
}

private data class PreparedShaderStage(
    val kind: ShaderKind,
    val baseProperty: String,
    val sourceProperty: String,
    val sourceName: String,
    val source: String,
    val preprocessedSource: String,
    val glslSource: String,
    val usedSlang: Boolean,
    val spirv: ShaderSPIRVUtils.SPIRV? = null,
    val reflectionJson: String? = null
) {
    val logLabel: String
        get() = if (usedSlang) "$baseProperty shader (slang)" else "$baseProperty shader"
}

private fun shaderPropertyValue(box: Box, property: String): String? = when (property.lowercase()) {
    "vertex" -> box.vertex
    "vertex_fragment" -> box.vertex_fragment
    "vertex_s" -> box.vertex_s
    "fragment" -> box.fragment
    "fragment_s" -> box.fragment_s
    "compute" -> box.compute
    "compute_s" -> box.compute_s
    "task" -> box.task
    "task_s" -> box.task_s
    "mesh" -> box.mesh
    "mesh_s" -> box.mesh_s
    "raygen" -> box.raygen
    "raygen_s" -> box.raygen_s
    "miss" -> box.miss
    "miss_s" -> box.miss_s
    "closesthit" -> box.closesthit
    "closesthit_s" -> box.closesthit_s
    "anyhit" -> box.anyhit
    "anyhit_s" -> box.anyhit_s
    "intersection" -> box.intersection
    "intersection_s" -> box.intersection_s
    "callable" -> box.callable
    "callable_s" -> box.callable_s
    else -> box.getPropertyRaw(property) as? String
}

private fun hasShaderSource(box: Box, baseProperty: String): Boolean =
    shaderPropertyVariants(baseProperty).any { property ->
        !shaderPropertyValue(box, property).isNullOrBlank()
    }

private fun sourcePropertyForStage(box: Box, baseProperty: String): String {
    val normalized = baseProperty.lowercase()
    if ((normalized == "vertex" || normalized == "fragment") && !shaderPropertyValue(box, sharedVertexFragmentProperty).isNullOrBlank()) {
        return sharedVertexFragmentProperty
    }
    val slangProperty = slangPropertyFor(normalized)
    val slangSource = slangProperty?.let { shaderPropertyValue(box, it) }
    return if (!slangSource.isNullOrBlank()) slangProperty else normalized
}

private fun assignPreparedShaderStage(box: Box, stage: PreparedShaderStage) {
    when (stage.baseProperty) {
        "vertex" -> box._vertex = stage.glslSource
        "fragment" -> box._fragment = stage.glslSource
        "compute" -> box._compute = stage.glslSource
        "task" -> box._task = stage.glslSource
        "mesh" -> box._mesh = stage.glslSource
        "raygen" -> box._raygen = stage.glslSource
        "miss" -> box._miss = stage.glslSource
        "closesthit" -> box._closesthit = stage.glslSource
        "anyhit" -> box._anyhit = stage.glslSource
        "intersection" -> box._intersection = stage.glslSource
        "callable" -> box._callable = stage.glslSource
    }
}

private fun clearShaderErrorsForBaseProperties(box: Box, vararg baseProperties: String) {
    val properties = baseProperties
        .flatMap(::shaderPropertyVariants)
        .distinct()
        .toTypedArray()
    clearShaderErrors(box, *properties)
}

private fun reportShaderStageError(
    box: Box,
    propertyForUI: String,
    logLabel: String,
    message: String,
    throwable: Throwable? = null,
    lineOverride: Int? = null
) {
    val trimmed = message.trim().ifBlank { throwable?.message ?: logLabel }
    lprintln("error,graphics", "error during $logLabel $trimmed")
    throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    if (currentShaderReloadMayReport()) {
        box.error(trimmed, propertyForUI, lineOverride ?: numberFromErrorMessage(trimmed) ?: 0)
    }
}

/**
 * The line in the box's own `code` that a failing `stack.auto` diagnostic should mark, or null.
 *
 * Two corrections are needed to get from a StackSL diagnostic to an editor marker, and without both
 * the marker lands somewhere arbitrary and reads as noise.
 *
 * **The number is a column, not a line.** StackSL renders a location as `line:column`
 * (`StackSL.kt:16`), so a diagnostic reads `error: 3:11: Unknown StackSL word 'fb'`. [numberFromErrorMessage]
 * tries `glslSourceLineRegex` first, which is written for glslang's `ERROR: 0:12:` where the leading
 * number is a *file index* and the second is the line. The two formats are the same shape and mean
 * different things, so the generic helper confidently returns 11 -- the column -- as a line number.
 *
 * **And the line is relative to the StackSL, not the box.** A slot is usually assigned from a string
 * literal partway down a Kotlin box, so StackSL line 3 is not box line 3. This locates the program
 * inside the box's code and offsets by where it starts.
 *
 * Returns null when the source is not found verbatim -- it may have been built programmatically -- and
 * the caller then falls back to the generic extraction rather than inventing a position.
 */
private fun stackAutoErrorLine(box: Box, source: String, message: String?): Int? {
    val stackSlLine = message
        ?.let { Regex("""(?:error|warning):\s*(\d+):\d+:""", RegexOption.IGNORE_CASE).find(it) }
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: return null
    val code = box.code.takeIf { it.isNotEmpty() } ?: return null
    // Match the source VERBATIM first. StackSL counts lines from the start of the string it was
    // handed, and a `"""` literal usually begins with a newline, so its line 1 is the empty remainder
    // of the assignment's own line. Trimming that newline away here would shift every marker up by
    // one -- which it did, on the first attempt.
    val at = code.indexOf(source).takeIf { it >= 0 }
        ?: code.indexOf(source.trimEnd()).takeIf { it >= 0 }
        ?: code.indexOf(source.trim()).takeIf { it >= 0 }
        ?: return null
    val startLine = code.substring(0, at).count { it == '\n' } + 1
    return startLine + stackSlLine - 1
}

private fun prepareShaderStage(
    box: Box,
    kind: ShaderKind,
    baseProperty: String,
    priorStages: Map<ShaderKind, PreparedShaderStage> = emptyMap()
): PreparedShaderStage? {
    val sourceProperty = sourcePropertyForStage(box, baseProperty)
    val source = shaderPropertyValue(box, sourceProperty)?.takeIf { it.isNotBlank() } ?: run {
        reportShaderStageError(
            box,
            sourceProperty,
            "$baseProperty shader setup",
            "No shader source found for property '$sourceProperty'."
        )
        return null
    }

    val preprocessedSource = try {
        preprocess(box, source, kind, defaultProperty = sourceProperty, priorStages = priorStages)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            sourceProperty,
            "$baseProperty shader preprocessing",
            t.message ?: "while preprocessing $baseProperty shader",
            t
        )
        return null
    }

    val usedSlang = isSlangShaderProperty(sourceProperty)
    var spirv: ShaderSPIRVUtils.SPIRV? = null
    var reflectionJson: String? = null
    val glslSource = if (sourceProperty == sharedVertexFragmentProperty) {
        try {
            val compiled = SlangCompiler.compileSharedStage(preprocessedSource, kind, "$sourceProperty.slang")
            spirv = compiled.spirv
            reflectionJson = compiled.reflectionJson
            compiled.glslSource
        } catch (t: Throwable) {
            reportShaderStageError(
                box,
                sourceProperty,
                "$baseProperty shader transpilation",
                t.message ?: "slangc failed for $sourceProperty",
                t
            )
            return null
        }
    } else if (usedSlang) {
        try {
            SlangCompiler.compileToGlsl(preprocessedSource, kind, "$sourceProperty.slang")
        } catch (t: Throwable) {
            reportShaderStageError(
                box,
                sourceProperty,
                "$baseProperty shader transpilation",
                t.message ?: "slangc failed for $sourceProperty",
                t
            )
            return null
        }
    } else {
        preprocessedSource
    }

    return PreparedShaderStage(
        kind = kind,
        baseProperty = baseProperty,
        sourceProperty = sourceProperty,
        sourceName = boxShaderSourceName(box, sourceProperty),
        source = source,
        preprocessedSource = preprocessedSource,
        glslSource = glslSource,
        usedSlang = usedSlang,
        spirv = spirv,
        reflectionJson = reflectionJson
    )
}

private fun compileStage(
    box: Box,
    stage: PreparedShaderStage
): CompileResult {
    var hadError = false
    val includeResolver = shaderIncludeResolverFor(box, stage.sourceProperty)
    val handler = { message: String, throwable: Throwable ->
        val throwableMsg = throwable.message?.let { " $it" } ?: ""
        lprintln("error,graphics", "error during ${stage.logLabel} compilation $message$throwableMsg")
        lprintln("error,graphics", throwable.stackTraceToString())
        if (currentShaderReloadMayReport()) {
            box.error(message, stage.sourceProperty, numberFromErrorMessage(message) ?: 0)
        }
        hadError = true
    }
    val i = if (stage.spirv != null && stage.reflectionJson != null) {
        Introspection.fromSlangReflection(
            source = stage.glslSource,
            kind = stage.kind,
            spirv = stage.spirv,
            reflectionJson = stage.reflectionJson,
            sourceName = stage.sourceName,
            includeResolver = includeResolver,
            errorHandler = handler
        )
    } else {
        Introspection(
            stage.glslSource,
            stage.kind,
            errorHandler = handler,
            sourceName = stage.sourceName,
            includeResolver = includeResolver
        )
    }
    return CompileResult(if (hadError) null else i, hadError)
}

// ---- Preamble assembly: the machinery applyInsertToPreamble uses to merge an Insert's
// generated lines into a box's preamble in place. (This was a section heading that a later
// declaration turned into a KDoc block for the regex below; it is a plain comment now.)

/**
 * Matches a leading `/*marker*/` on one preamble line, capturing the marker text.
 *
 * Every generated preamble line is stamped with such a marker by `prefixed`, and it is the marker —
 * not the code after it — that identifies the line for dedupe and replacement. Used by
 * [preambleLineKey] to extract it and by [isImportLine] to look past it.
 */
private val preamblePrefixRegex = Regex("^\\s*/\\*(.*?)\\*/\\s*")
private const val preambleBlockStartMarker = "/*start*/"
private const val preambleBlockEndMarker = "/*end*/"

private data class PreambleSegment(val lines: List<String>)

private fun preambleLineKey(line: String): String {
    val normalized = line.trimEnd()
    if (normalized.isBlank()) return ""
    val prefix = preamblePrefixRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
    return if (prefix.isNullOrEmpty()) normalized else prefix
}

private fun isImportLine(line: String): Boolean {
    val withoutPrefix = line.replaceFirst(preamblePrefixRegex, "")
    return withoutPrefix.trimStart().startsWith("import ")
}

private fun stripPreambleBlockMarkers(line: String): String =
    line.replace(preambleBlockStartMarker, "").replace(preambleBlockEndMarker, "").trim()

private fun splitPreambleSegments(lines: List<String>): List<PreambleSegment> {
    val segments = mutableListOf<PreambleSegment>()
    var currentBlock: MutableList<String>? = null

    fun flushCurrentBlock() {
        val block = currentBlock ?: return
        if (block.isNotEmpty()) segments += PreambleSegment(block.toList())
        currentBlock = null
    }

    lines.forEach { line ->
        val normalized = line.trimEnd()
        if (normalized.isBlank()) return@forEach

        if (currentBlock != null) {
            currentBlock?.add(normalized)
            if (normalized.contains(preambleBlockEndMarker)) {
                flushCurrentBlock()
            }
            return@forEach
        }

        if (normalized.contains(preambleBlockStartMarker)) {
            currentBlock = mutableListOf(normalized)
            if (normalized.contains(preambleBlockEndMarker)) {
                flushCurrentBlock()
            }
        } else {
            segments += PreambleSegment(listOf(normalized))
        }
    }

    flushCurrentBlock()

    return segments
}

private fun preambleSegmentKey(segment: PreambleSegment): String {
    val prefixedLine = segment.lines
        .asSequence()
        .map(::stripPreambleBlockMarkers)
        .firstOrNull { preamblePrefixRegex.containsMatchIn(it) }

    if (prefixedLine != null) return preambleLineKey(prefixedLine)

    val normalized = segment.lines.joinToString(separator = "\n") { it.trimEnd() }.trim()
    return normalized
}

private fun isImportSegment(segment: PreambleSegment): Boolean {
    val firstMeaningfulLine = segment.lines
        .asSequence()
        .map(::stripPreambleBlockMarkers)
        .firstOrNull { it.isNotBlank() }
        ?: return false

    return isImportLine(firstMeaningfulLine)
}

private fun generatedPreambleGroup(key: String): String? {
    val prefix = key.substringBefore(":", key)
    return when {
        prefix.startsWith("graphics-stack-auto") -> "stack-auto"
        prefix.startsWith("stack-auto") -> "stack-auto"
        prefix.startsWith("graphics-") -> "graphics"
        prefix.startsWith("compute-") -> "compute"
        prefix.startsWith("mesh-") -> "mesh"
        prefix.startsWith("ray-") -> "ray"
        else -> null
    }
}

/**
 * Tidy a box's preamble text: drop duplicate declarations, hoist every `import` to the top, and remove
 * blank lines.
 *
 * "Duplicate" is judged by a segment's key, and for a generated line the key is only the name in its
 * leading marker comment — so two lines both tagged `graphics-ubo:params` collapse to whichever appeared
 * **first**, whatever their bodies say. That is the right rule for de-duplicating a preamble that has
 * accumulated over several runs, and the wrong one if you were hoping the newer line would win; for
 * that, go through [applyInsertToPreamble], where incoming segments do replace existing ones.
 */
fun normalizePreambleText(preamble: String): String {
    val merged = LinkedHashMap<String, PreambleSegment>()
    splitPreambleSegments(preamble.lines()).forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (key.isNotEmpty() && !merged.containsKey(key)) {
            merged[key] = segment
        }
    }
    val imports = merged.values.filter(::isImportSegment)
    val nonImports = merged.values.filterNot(::isImportSegment)
    return (imports + nonImports).flatMap { it.lines }.joinToString(separator = "\n")
}

private fun prefixed(prefix: String, code: String): String = "/*$prefix*/ $code"

private fun prefixedBlock(prefix: String, code: String): String =
    "$preambleBlockStartMarker\n/*$prefix*/\n${code.trim()}\n$preambleBlockEndMarker"

private val kotlinIdentifierRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val kotlinHardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while"
)

private fun kotlinMemberName(name: String): String =
    if (kotlinIdentifierRegex.matches(name) && name !in kotlinHardKeywords) name else "`$name`"

private data class GeneratedShaderStructure(
    val glslName: String,
    val className: String,
    val members: List<Introspected.Member>,
    val byteSize: Int,
    val signature: String,
    val bufferReference: Boolean
)

private fun shaderMemberSignature(member: Introspected.Member): String =
    buildString {
        append(member.name)
        append("@")
        append(member.offset)
        append(":")
        append(member.arrayStride)
        append(":")
        append(member.physicalPointer)
        append(":")
        append(member.type.fold({ it.name }, { nested -> nested.joinToString(prefix = "{", postfix = "}", separator = ",", transform = ::shaderMemberSignature) }))
    }

private fun shaderMembersSignature(members: List<Introspected.Member>): String =
    members.joinToString(separator = ";", transform = ::shaderMemberSignature)

private fun sanitizeKotlinTypeName(name: String): String {
    val parts = name.split(Regex("[^A-Za-z0-9_]+")).filter { it.isNotBlank() }
    val joined = parts.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }.ifBlank { "ShaderStructure" }
    val prefixed = if (joined.first().isDigit()) "Shader$joined" else joined
    return prefixed
}

private fun sanitizeKotlinFunctionSuffix(name: String): String =
    sanitizeKotlinTypeName(name)

private fun collectGeneratedShaderStructures(introspections: List<Introspection>): List<GeneratedShaderStructure> {
    data class Candidate(
        val name: String,
        val members: List<Introspected.Member>,
        val size: Int,
        val bufferReference: Boolean
    )

    val candidates = mutableListOf<Candidate>()
    introspections.forEach { i ->
        i.structureTypes.forEach { s ->
            candidates += Candidate(s.name, s.members, s.size, s.bufferReference)
        }
        i.ubos.forEach { ubo ->
            candidates += Candidate(ubo.name, ubo.members, ubo.size, bufferReference = false)
        }
        i.ssbo.forEach { ssbo ->
            candidates += Candidate(ssbo.name, ssbo.members, ssbo.size, bufferReference = false)
        }
    }

    val out = mutableListOf<GeneratedShaderStructure>()
    val signatureByClassName = mutableMapOf<String, String>()
    val seenByNameAndSignature = mutableSetOf<Pair<String, String>>()

    candidates.forEach { candidate ->
        val signature = shaderMembersSignature(candidate.members)
        if (!seenByNameAndSignature.add(candidate.name to signature)) return@forEach

        val baseClassName = sanitizeKotlinTypeName(candidate.name)
        var className = baseClassName
        var suffix = 2
        while (signatureByClassName[className] != null && signatureByClassName[className] != signature) {
            className = "$baseClassName$suffix"
            suffix += 1
        }
        signatureByClassName[className] = signature

        out += GeneratedShaderStructure(
            glslName = candidate.name,
            className = className,
            members = candidate.members,
            byteSize = maxOf(candidate.size, candidate.members.membersLayoutSizeInBytes()),
            signature = signature,
            bufferReference = candidate.bufferReference
        )
    }

    return out
}

private fun buildShaderStructurePreamble(prefix: String, introspections: List<Introspection>): String {
    val structures = collectGeneratedShaderStructures(introspections)
    if (structures.isEmpty()) return ""

    val bySignature = structures.associateBy { it.signature }
    val emitted = mutableSetOf<String>()
    val chunks = mutableListOf<String>()

    fun nestedLayoutFor(member: Introspected.Member): GeneratedShaderStructure? {
        if (member.physicalPointer) return null
        val nested = member.type.fold({ null }, { it }) ?: return null
        return bySignature[shaderMembersSignature(nested)]
    }

    lateinit var memberListExpression: (List<Introspected.Member>, String) -> String

    fun memberTypeExpression(member: Introspected.Member): String {
        if (member.physicalPointer) {
            return "PrimitiveType.deviceptr.left()"
        }
        return member.type.fold(
            { "PrimitiveType.${it.name}.left()" },
            { nested ->
                val nestedLayout = bySignature[shaderMembersSignature(nested)]
                if (nestedLayout != null) "${nestedLayout.className}LayoutMembers().right()"
                else memberListExpression(nested, "                    ").trim().removeSuffix(",").let { "$it.right()" }
            }
        )
    }

    memberListExpression = fun(members: List<Introspected.Member>, indent: String): String {
        if (members.isEmpty()) return "emptyList()"
        val nextIndent = "$indent    "
        return buildString {
            appendLine("listOf(")
            members.forEach { member ->
                append(nextIndent)
                append("Introspected.Member(")
                append("\"${member.name}\", ")
                append("${member.offset}, ")
                append(memberTypeExpression(member))
                append(", ")
                append(member.arrayStride)
                append(", ")
                append(member.physicalPointer)
                appendLine("),")
            }
            append("$indent)")
        }
    }

    fun emit(structure: GeneratedShaderStructure) {
        if (!emitted.add(structure.className)) return

        structure.members.mapNotNull(::nestedLayoutFor).forEach(::emit)

        val nestedProperties = structure.members.mapNotNull { member ->
            nestedLayoutFor(member)?.let { nested ->
                val memberName = kotlinMemberName(member.name)
                """    val $memberName: ${nested.className}
        get() = childStructure("${member.name}") { members, baseOffset, pathPrefix, root ->
            ${nested.className}(members, baseOffset, pathPrefix, root)
        }"""
            }
        }

        val primitiveProperties = structure.members.filter { nestedLayoutFor(it) == null }.map { member ->
            val ty = kotlinBindingTypeName(member)
            "    var ${kotlinMemberName(member.name)} by Structure.FromStructure<$ty>()" +
                shaderCommentSuffix(introspections, member.name, structure.glslName)
        }

        val helperSuffix = sanitizeKotlinFunctionSuffix(structure.className)
        val layoutFunctionName = "${structure.className}LayoutMembers"
        val byteSizeFunctionName = "${structure.className}ByteSize"
        val body = buildString {
            appendLine("class ${structure.className}(")
            appendLine("    members: List<Introspected.Member> = $layoutFunctionName(),")
            appendLine("    baseOffset: Int = 0,")
            appendLine("    pathPrefix: String = \"\",")
            appendLine("    root: Structure? = null")
            appendLine(") : Structure(members, baseOffset, pathPrefix, root) {")
            appendLine("    init { setMembers(members) }")
            (nestedProperties + primitiveProperties).forEach {
                appendLine(it)
            }
            appendLine()
            appendLine("}")
            appendLine()
            appendLine("fun $byteSizeFunctionName(): Long = ${structure.byteSize}L")
            appendLine()
            appendLine("fun $layoutFunctionName(): List<Introspected.Member> =")
            appendLine("    ${memberListExpression(structure.members, "    ")}")
            appendLine()
            appendLine("fun HasBuffer.as$helperSuffix(): ${structure.className} =")
            appendLine("    asStructure(${structure.className}(), $layoutFunctionName())")
            appendLine()
            appendLine("fun BufferParameters.as$helperSuffix(): ${structure.className} =")
            appendLine("    buffer.as$helperSuffix()")
        }

        chunks += prefixedBlock("$prefix-structure-type:${structure.glslName}:${structure.className}", body)
    }

    structures.forEach(::emit)
    return chunks.joinToString(separator = "\n") + "\n"
}

private fun mergePreambleSegments(existing: List<String>, incoming: List<String>): List<PreambleSegment> {
    val merged = LinkedHashMap<String, PreambleSegment>()
    val incomingSegments = splitPreambleSegments(incoming)
    val replacementGroups = incomingSegments
        .mapNotNull { generatedPreambleGroup(preambleSegmentKey(it)) }
        .toSet()

    splitPreambleSegments(existing).forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (generatedPreambleGroup(key) in replacementGroups) return@forEach
        if (key.isNotEmpty() && !merged.containsKey(key)) {
            merged[key] = segment
        }
    }

    incomingSegments.forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (key.isNotEmpty()) {
            // New insertions should win so reloads replace generated bindings in-place.
            merged[key] = segment
        }
    }

    return merged.values.toList()
}

/**
 * Fold an [Insert]'s generated declarations into a box's preamble — how a shader reload gets its new
 * typed bindings in front of the Kotlin compiler.
 *
 * Writes to `e.target` if the Insert names one, otherwise to [box]. The merge is per-segment and
 * key-based, with two rules worth knowing: an incoming segment **replaces** an existing one with the
 * same key (so a reload updates a binding in place rather than appending a second copy), and an
 * incoming segment from a generated family — `graphics-`, `compute-`, `mesh-`, `ray-`, `stack-auto` —
 * causes *every* existing segment of that family to be dropped first, so bindings for a uniform you
 * just deleted from the shader disappear instead of lingering. `import` lines are hoisted above
 * everything else. An Insert with no preamble content is a no-op; the Insert's `here` text is not this
 * function's business.
 *
 * What it does *not* do is re-run the box. New accessors are in the preamble but the box's Kotlin has
 * to execute again before they mean anything at a call site.
 */
fun applyInsertToPreamble(box: Box, e: Insert) {
    val b = e.target ?: box
    val incomingLines = buildList {
        if (e.preamble.isNotBlank()) addAll(e.preamble.trim().lines())
        if (e.preambleAfterImports.isNotBlank()) addAll(e.preambleAfterImports.trim().lines())
    }.map { it.trimEnd() }.filter { it.isNotBlank() }

    if (incomingLines.isEmpty()) return

    val existingLines = b.preamble.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    val merged = mergePreambleSegments(existingLines, incomingLines)
    val imports = merged.filter(::isImportSegment)
    val nonImports = merged.filterNot(::isImportSegment)
    b.preamble = normalizePreambleText((imports + nonImports).flatMap { it.lines }.joinToString(separator = "\n"))

}

private fun clearShaderErrors(box: Box, vararg properties: String) {
    if (!currentShaderReloadMayReport()) return
    properties.forEach { property ->
        box.error(null, property, -1)
    }
}

private fun runShaderReloadAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    reloadedMessage: String,
    reload: (Box) -> Boolean
) {
    notify(reloadingMessage, "shader")
    var ok = 0
    var failed = 0
    boxes.forEach { selected ->
        val success = try {
            reload(selected)
        } catch (t: Throwable) {
            lprintln(
                "error,graphics",
                "unexpected error reloading shader in '${selected.name}' (${selected.id}): ${t.message}"
            )
            selected.error(t.message ?: "shader reload failed", "code", -1)
            false
        }
        if (success) ok++ else failed++
    }
    val summary = if (failed == 0) "$reloadedMessage ($ok)" else "$reloadedMessage ($ok ok, $failed failed)"
    notify(summary, "shader")
}

private fun runShaderUpdateAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    update: suspend (Box, ShaderReloadKey, Long) -> Boolean
) {
    val selected = boxes.toList()
    if (selected.isEmpty()) return

    notify(reloadingMessage, "shader")
    selected.forEach { box ->
        val (key, generation) = nextShaderGeneration(box, ShaderReloadType.GRAPHICS)
        shaderReloadScope.launch {
            val success = try {
                update(box, key, generation)
            } catch (t: Throwable) {
                lprintln(
                    "error,graphics",
                    "unexpected error updating shader in '${box.name}' (${box.id}): ${t.message}"
                )
                if (isCurrentShaderGeneration(key, generation)) {
                    withContext(Runloop.runLoop.main) {
                        box.error(t.message ?: "shader update failed", "code", -1)
                    }
                }
                false
            }

            if (isCurrentShaderGeneration(key, generation)) {
                withContext(Runloop.runLoop.main) {
                    notify(
                        if (success) "Updated shader in '${box.name}'" else "Shader update failed in '${box.name}'",
                        "shader"
                    )
                }
            }
        }
    }
}

private fun runComputeShaderUpdateAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    update: suspend (Box, ShaderReloadKey, Long) -> Boolean
) {
    val selected = boxes.toList()
    if (selected.isEmpty()) return

    notify(reloadingMessage, "shader")
    selected.forEach { box ->
        val (key, generation) = nextShaderGeneration(box, ShaderReloadType.COMPUTE)
        shaderReloadScope.launch {
            val success = try {
                update(box, key, generation)
            } catch (t: Throwable) {
                lprintln(
                    "error,graphics",
                    "unexpected error updating compute shader in '${box.name}' (${box.id}): ${t.message}"
                )
                if (isCurrentShaderGeneration(key, generation)) {
                    withContext(Runloop.runLoop.main) {
                        box.error(t.message ?: "compute shader update failed", "code", -1)
                    }
                }
                false
            }

            if (isCurrentShaderGeneration(key, generation)) {
                withContext(Runloop.runLoop.main) {
                    notify(
                        if (success) "Updated compute shader in '${box.name}'" else "Compute shader update failed in '${box.name}'",
                        "shader"
                    )
                }
            }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installGraphicsShaderReloadAction() {
    this.actions += "moduleHere_reload" to Action("Reload Shader|alt r", "Reloads the graphics shader in this box") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadShader(selected)
                }
            }
        }
    }
}

private fun Box.installGraphicsShaderUpdateAction() {
    this.actions += "moduleHere_update" to Action(
        "Update Shader|alt shift enter",
        "Updates the graphics shader only if its reflected ABI is unchanged"
    ) {
        runShaderUpdateAction(
            boxes = it,
            reloadingMessage = "Updating shader…"
        ) { selected, key, generation ->
            updateShader(selected, key, generation)
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installComputeShaderReloadAction() {
    this.actions += "computeModuleHere_reload" to Action(
        "Reload Compute Shader|alt r",
        "reloads the compute shader in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadComputeShader(selected)
                }
            }
        }
    }
}

private fun Box.installComputeShaderUpdateAction() {
    this.actions += "computeModuleHere_update" to Action(
        "Update Compute Shader|alt shift enter",
        "updates the compute shader only if its reflected ABI is unchanged"
    ) {
        runComputeShaderUpdateAction(
            boxes = it,
            reloadingMessage = "Updating compute shader…"
        ) { selected, key, generation ->
            updateComputeShader(selected, key, generation)
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installMeshShaderReloadAction() {
    this.actions += "meshModuleHere_reload" to Action(
        "Reload Mesh Shader|alt r",
        "reloads the mesh shader in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadMeshShader(selected)
                }
            }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installRayTracingShaderReloadAction() {
    this.actions += "rayTracingModuleHere_reload" to Action(
        "Reload Ray Tracing Shader|alt r",
        "reloads the ray tracing shader pipeline in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadRayTracingShader(selected)
                }
            }
        }
    }
}

// ------------------------------
// Graphics (vertex/fragment) reload path
// ------------------------------
private suspend fun updateShader(box: Box, key: ShaderReloadKey, generation: Long): Boolean {
    withContext(Runloop.runLoop.main) {
        if (isCurrentShaderGeneration(key, generation)) clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    }

    val prepared = withShaderReloadErrorGate(key, generation) {
        val vertexStage = prepareShaderStage(box, ShaderKind.VERTEX_SHADER, "vertex") ?: return false
        val fragmentStage = prepareShaderStage(
            box,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(ShaderKind.VERTEX_SHADER to vertexStage)
        ) ?: return false
        val vertexCompile = compileStage(box, vertexStage)
        val fragmentCompile = compileStage(box, fragmentStage)
        if (vertexCompile.hadError || fragmentCompile.hadError) {
            lprintln("error,graphics", "shader update compilation failed; keeping previous module.")
            return false
        }
        val vi = vertexCompile.introspection ?: return false
        val fi = fragmentCompile.introspection ?: return false
        Pair(vertexStage to vi, fragmentStage to fi)
    }

    val (vertexPrepared, fragmentPrepared) = prepared
    val (vertexStage, vi) = vertexPrepared
    val (fragmentStage, fi) = fragmentPrepared
    val pointer = box._shader ?: run {
        reportShaderStageError(box, "code", "shader update", "No graphics shader is attached to this box.")
        return false
    }
    val current = pointer.current

    val abiCheck = graphicsShaderAbiCheck(
        current.parameters.vertexIntrospection ?: current.vi,
        current.parameters.fragmentIntrospection ?: current.fi,
        vi,
        fi
    )
    if (!abiCheck.compatible) {
        reportShaderStageError(box, "code", "shader update", abiCheck.message)
        return false
    }

    val pipelineUpdate = try {
        current.buildPipelineUpdate(vi, fi)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            "code",
            "shader update pipeline creation",
            t.message ?: "Unable to create graphics pipeline update.",
            t
        )
        return false
    }

    return withContext(Runloop.runLoop.main) {
        if (!isCurrentShaderGeneration(key, generation) || box._shader !== pointer || pointer.current !== current) {
            current.destroyUnusedPipelineUpdate(pipelineUpdate)
            return@withContext false
        }

        assignPreparedShaderStage(box, vertexStage)
        assignPreparedShaderStage(box, fragmentStage)

        current.parameters.vertex = vertexStage.glslSource
        current.parameters.fragment = fragmentStage.glslSource
        current.parameters.vertexSourceName = vertexStage.sourceName
        current.parameters.fragmentSourceName = fragmentStage.sourceName
        current.parameters.vertexIncludeResolver = shaderIncludeResolverFor(box, vertexStage.sourceProperty)
        current.parameters.fragmentIncludeResolver = shaderIncludeResolverFor(box, fragmentStage.sourceProperty)

        FramePresentationLog.mark("graphics shader update install begin box=${box.name} module=${current.parameters.name}")
        current.installPipelineUpdate(pipelineUpdate)
        FramePresentationLog.mark("graphics shader update install end box=${box.name} module=${current.parameters.name}")
        clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
        true
    }
}

/**
 * Recompile this box's vertex and fragment stages from its shader properties and swap the live
 * graphics module — the *Reload Shader* action (`alt-r`), as a function.
 *
 * The full-rebuild path, as opposed to the ABI-preserving *Update Shader* (`alt-shift-enter`): it
 * re-preprocesses both stages, re-introspects them, rewrites the box's preamble with the resulting
 * typed bindings, and reloads the module with `forceReresolve`. That flag reaches only the *texture*
 * resolver — textures are re-resolved by name, falling back to the previous module's binding when the
 * resolver comes up empty. Uniform buffers and SSBOs are *carried over* instead: a UBO
 * whose name still matches keeps its existing source, and an SSBO keeps its previous source whenever
 * `checkCompatibility` still passes. Use this when the shader's interface changed; use the update path
 * when only the arithmetic did.
 *
 * Must be called on the main runloop — it touches the Vulkan module directly. Errors are not thrown:
 * they are reported onto the offending shader property's line and the function returns `false` with the
 * **previous module left running**, so a broken edit does not black out the window. Stale errors from
 * a previous attempt are cleared on the way in and again on success.
 *
 * One rough edge: a box with no graphics module at all throws a `NullPointerException` here, rather
 * than reporting "no shader attached" the way the update path politely does. Check `box._shader != null`
 * first if you cannot be sure.
 */
fun reloadShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    val vertexStage = prepareShaderStage(box, ShaderKind.VERTEX_SHADER, "vertex") ?: return false
    val fragmentStage = prepareShaderStage(
        box,
        ShaderKind.FRAGMENT_SHADER,
        "fragment",
        priorStages = mapOf(ShaderKind.VERTEX_SHADER to vertexStage)
    ) ?: return false

    assignPreparedShaderStage(box, vertexStage)
    assignPreparedShaderStage(box, fragmentStage)

    val (vi, vErr) = compileStage(box, vertexStage)
    val (fi, fErr) = compileStage(box, fragmentStage)

    if (vErr || fErr) {
        lprintln("error,graphics", "shader compilation failed; skipping module reload.")
        return false
    }

    // keep consistent with the Module path: build Insert and apply to preamble
    val insert = insertForShader(vi!!, fi!!, "_moduleHere", listOf("()"), box)
    applyInsertToPreamble(box, insert)

    val p = box._shader!!.current.parameters.copy().apply {
        vertex = box._vertex!!
        fragment = box._fragment!!
        vertexIntrospection = vi
        fragmentIntrospection = fi
    }
    attachFieldProbe(box, p)

    // todo: patch error handler if you need finer-grained feedback
    box._shader!!.reload(p, forceReresolve = true)
    clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    return true
}

/**
 * Adopt a graphics module you built yourself into this box's hot-reload machinery: the box gains the
 * *Reload Shader* and *Update Shader* actions, its shader properties are seeded from the module's
 * source, and the module becomes `box._shader`.
 *
 * The route in for code that constructs a `Module` directly rather than through `moduleHere`. Ownership
 * does not change — you get the same pointer back — but the box now drives it, and an immediate
 * [reloadShader] runs before this returns, so an unreachable `#include` in the adopted source surfaces
 * as a box error straight away.
 *
 * Requires both stages to have non-blank source (it throws otherwise). By default the box's `vertex` /
 * `fragment` properties are only seeded if the box has none of its own, so an existing shader tab wins;
 * pass `overwriteProperties = true` to clobber it. Note what gets seeded is the module's *already
 * expanded* GLSL, so template slots and includes in the original are gone from the copy.
 *
 * @param overwriteProperties replace the box's existing shader properties instead of leaving them alone
 */
fun Box.attachShader(module: ModulePointer, overwriteProperties: Boolean = false): ModulePointer {
    installGraphicsShaderReloadAction()
    installGraphicsShaderUpdateAction()

    val parameters = module.current.parameters
    require(parameters.vertex.isNotBlank()) { "Attached graphics module must have a vertex shader source." }
    require(parameters.fragment.isNotBlank()) { "Attached graphics module must have a fragment shader source." }

    if (overwriteProperties || !hasShaderSource(this, "vertex")) setPropertyRaw("vertex", parameters.vertex)
    if (overwriteProperties || !hasShaderSource(this, "fragment")) setPropertyRaw("fragment", parameters.fragment)

    _vertex = parameters.vertex
    _fragment = parameters.fragment
    _shader = module
    registerStackAutoShaderOwner(this, module)

    reloadShader(this)
    return module
}

// ------------------------------
// Compute helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun kotlinBindingTypeName(member: Introspected.Member): String =
    if (member.physicalPointer) "afield.four.buffers.HasBuffer"
    else member.type.fold({ it.kotlinTypeName }, { "Any" })

/**
 * The `// …` note the shader source carried at the end of a member's declaration line, formatted to
 * append to the generated preamble declaration for it — `""` when the source said nothing.
 *
 * A *trailing* line comment, rather than a KDoc block in front, for two reasons: the preamble is
 * line-oriented and keyed by the `/*marker*/` that must stay at the START of the line
 * (`preambleLineKey`), and a shader author's comment is arbitrary text that could otherwise close the
 * KDoc it was pasted into. The comment is normalised to one line and capped, so a paragraph-length
 * note cannot stretch the preamble.
 *
 * @param block the enclosing push-constant block, struct or UBO name, to disambiguate a member name
 * that several blocks share
 */
private fun shaderCommentSuffix(
    introspections: List<Introspection>,
    member: String,
    block: String? = null
): String {
    val comment = introspections.firstNotNullOfOrNull { it.memberComment(member, block) } ?: return ""
    val oneLine = comment.replace(Regex("""\s+"""), " ").trim().take(240)
    return if (oneLine.isEmpty()) "" else " // $oneLine"
}

private fun buildComputeInsert(ci: Introspection, box: Box? = null): Insert {
    var postImport = ""
    val commentSources = listOf(ci)
    postImport += buildShaderStructurePreamble("compute", listOf(ci))
    buildStackAutoShaderPreamble(box).takeIf { it.isNotBlank() }?.let {
        postImport += it + "\n"
    }

    // Push constants
    ci.pushConstants.flatMap { pc -> pc.members.map { pc.name to it } }.distinctBy { it.second.name }.forEach { (block, member) ->
            val note = shaderCommentSuffix(commentSources, member.name, block)
            postImport += prefixed(
                "compute-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()$note"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "compute-pc-binding:${member.name}",
                "var ComputeMesh.${member.name} by ComputeMesh.BoundFromStructure<$ty>()$note"
            ) + "\n"
            postImport += prefixed(
                "compute-pc-binding-group:${member.name}",
                "var ComputeMeshGroup.${member.name} by ComputeMeshGroup.BoundFromStructure<$ty>()$note"
            ) + "\n"
    }

    // UBOs
    ci.ubos.distinctBy { it.name }.forEach { ubo ->
        postImport += prefixed(
            "compute-ubo:${ubo.name}",
            "val ModulePointer.${ubo.name} by FromUniform()"
        ) + "\n"
        postImport += prefixed(
            "compute-ubo-mesh:${ubo.name}",
            "var Mesh.${ubo.name} by Mesh.FromStructure<Structure>()"
        ) + "\n"
        ubo.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "compute-ubo-member:${ubo.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()" +
                    shaderCommentSuffix(commentSources, m.name, ubo.name)
            ) + "\n"
        }
    }

    // SSBOs
    ci.ssbo.distinctBy { it.name }.forEach { ssbo ->
        postImport += prefixed(
            "compute-ssbo:${ssbo.name}",
            "val ComputeModulePointer.${ssbo.name} by ComputeMesh.FromUniform()"
        ) + "\n"
        // Arrays / headers would need special handling if you decide to expose members
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

/**
 * Recompile this box's compute stage and swap the live compute module — the *Reload Compute Shader*
 * action (`alt-r`) as a function.
 *
 * Same contract as [reloadShader]: main runloop only, errors reported onto the `compute` property rather
 * than thrown, `false` means the previous module is still the one running, and the box's preamble is
 * rewritten with the freshly introspected `ComputeMesh` bindings. Likewise throws a
 * `NullPointerException` if the box has no compute module to reload.
 */
fun reloadComputeShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "compute")
    val computeStage = prepareShaderStage(box, ShaderKind.COMPUTE_SHADER, "compute") ?: return false
    assignPreparedShaderStage(box, computeStage)

    val (ci, cErr) = compileStage(box, computeStage)
    if (cErr) {
        lprintln("error,graphics", "compute shader compilation failed; skipping compute module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildComputeInsert(ci!!, box)
    applyInsertToPreamble(box, insert)

    val p = box._computeShader!!.current.parameters.copy().apply {
        compute = box._compute!!
        computeIntrospection = ci
    }
    attachFieldProbe(box, p)
    // todo: patch error handler if you need finer-grained feedback
    box._computeShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "compute")
    return true
}

private suspend fun updateComputeShader(box: Box, key: ShaderReloadKey, generation: Long): Boolean {
    withContext(Runloop.runLoop.main) {
        if (isCurrentShaderGeneration(key, generation)) clearShaderErrorsForBaseProperties(box, "compute")
    }

    val prepared = withShaderReloadErrorGate(key, generation) {
        val computeStage = prepareShaderStage(box, ShaderKind.COMPUTE_SHADER, "compute") ?: return false
        val computeCompile = compileStage(box, computeStage)
        if (computeCompile.hadError) {
            lprintln("error,graphics", "compute shader update compilation failed; keeping previous module.")
            return false
        }
        val ci = computeCompile.introspection ?: return false
        computeStage to ci
    }

    val (computeStage, ci) = prepared
    val pointer = box._computeShader ?: run {
        reportShaderStageError(box, "code", "compute shader update", "No compute shader is attached to this box.")
        return false
    }
    val current = pointer.current

    val abiCheck = computeShaderAbiCheck(
        current.parameters.computeIntrospection ?: current.ci,
        ci
    )
    if (!abiCheck.compatible) {
        reportShaderStageError(box, "code", "compute shader update", abiCheck.message)
        return false
    }

    val pipelineUpdate = try {
        current.buildPipelineUpdate(ci)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            "code",
            "compute shader update pipeline creation",
            t.message ?: "Unable to create compute pipeline update.",
            t
        )
        return false
    }

    return withContext(Runloop.runLoop.main) {
        if (!isCurrentShaderGeneration(key, generation) || box._computeShader !== pointer || pointer.current !== current) {
            current.destroyUnusedPipelineUpdate(pipelineUpdate)
            return@withContext false
        }

        assignPreparedShaderStage(box, computeStage)

        current.parameters.compute = computeStage.glslSource
        current.parameters.computeSourceName = computeStage.sourceName
        current.parameters.computeIncludeResolver = shaderIncludeResolverFor(box, computeStage.sourceProperty)

        FramePresentationLog.mark("compute shader update install begin box=${box.name} module=${current.parameters.name}")
        current.installPipelineUpdate(pipelineUpdate)
        FramePresentationLog.mark("compute shader update install end box=${box.name} module=${current.parameters.name}")
        clearShaderErrorsForBaseProperties(box, "compute")
        true
    }
}

/**
 * Adopt an externally built compute module into this box: install the reload and update actions, seed
 * the `compute` property from the module's source, take it as `box._computeShader`, and reload once.
 *
 * See the [graphics overload][Box.attachShader] for the ownership and property-seeding rules; the only
 * difference is that just one stage is required.
 */
fun Box.attachShader(module: ComputeModulePointer, overwriteProperties: Boolean = false): ComputeModulePointer {
    installComputeShaderReloadAction()
    installComputeShaderUpdateAction()

    val parameters = module.current.parameters
    require(parameters.compute.isNotBlank()) { "Attached compute module must have a compute shader source." }

    if (overwriteProperties || !hasShaderSource(this, "compute")) setPropertyRaw("compute", parameters.compute)

    _compute = parameters.compute
    _computeShader = module
    registerStackAutoShaderOwner(this, module)

    reloadComputeShader(this)
    return module
}

// ------------------------------
// Mesh helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun buildMeshInsert(ti: Introspection, mi: Introspection, fi: Introspection, box: Box? = null): Insert {
    var postImport = ""
    val commentSources = listOf(ti, mi, fi)
    postImport += buildShaderStructurePreamble("mesh", listOf(ti, mi, fi))
    buildStackAutoShaderPreamble(box).takeIf { it.isNotBlank() }?.let {
        postImport += it + "\n"
    }

    // Push constants across task+mesh+fragment
    (ti.pushConstants + mi.pushConstants + fi.pushConstants).flatMap { pc -> pc.members.map { pc.name to it } }
        .distinctBy { it.second.name }.forEach { (block, member) ->
            val note = shaderCommentSuffix(commentSources, member.name, block)
            postImport += prefixed(
                "mesh-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()$note"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "mesh-pc-binding:${member.name}",
                "var MeshMesh.${member.name} by MeshMesh.BoundFromStructure<$ty>()$note"
            ) + "\n"
            postImport += prefixed(
                "mesh-pc-binding-group:${member.name}",
                "var MeshMeshGroup.${member.name} by MeshMeshGroup.BoundFromStructure<$ty>()$note"
            ) + "\n"
    }

    // UBOs across task+mesh+fragment
    (ti.ubos + mi.ubos + fi.ubos).distinctBy { it.name }.forEach { ubo ->
        postImport += prefixed(
            "mesh-ubo:${ubo.name}",
            "val MeshModulePointer.${ubo.name} by FromUniform()"
        ) + "\n"
        postImport += prefixed(
            "mesh-ubo-mesh:${ubo.name}",
            "var MeshMesh.${ubo.name} by MeshMesh.FromStructure<Structure>()"
        ) + "\n"
        ubo.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "mesh-ubo-member:${ubo.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()" +
                    shaderCommentSuffix(commentSources, m.name, ubo.name)
            ) + "\n"
        }
    }

    // SSBOs across task+mesh+fragment
    (ti.ssbo + mi.ssbo + fi.ssbo).distinctBy { it.name }.forEach { ssbo ->
        postImport += prefixed(
            "mesh-ssbo:${ssbo.name}",
            "val MeshModulePointer.${ssbo.name} by MeshMesh.FromUniform()"
        ) + "\n"
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

/**
 * Recompile all three stages of this box's mesh-shader pipeline — task, then mesh, then fragment — and
 * swap the live module.
 *
 * Order matters and is why they are not independent: each stage is prepared with the previous ones
 * available, which is what makes `{% copyPC %}` able to copy the task stage's push-constant block into
 * the mesh stage and the mesh stage's into the fragment stage. All three must have source; a blank one
 * is an error, not an omission.
 *
 * Main runloop only, errors reported onto the stage properties rather than thrown, `false` leaves the
 * previous module running. There is no ABI-preserving *Update* action for mesh pipelines — only this
 * full reload — which is also why binding a `stack.auto` slot on a mesh module is heavier than on a
 * graphics one. Throws `NullPointerException` if the box has no mesh module.
 */
fun reloadMeshShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "task", "mesh", "fragment")
    val taskStage = prepareShaderStage(box, ShaderKind.TASK_SHADER, "task") ?: return false
    val meshStage = prepareShaderStage(
        box,
        ShaderKind.MESH_SHADER,
        "mesh",
        priorStages = mapOf(ShaderKind.TASK_SHADER to taskStage)
    ) ?: return false
    val fragmentStage = prepareShaderStage(
        box,
        ShaderKind.FRAGMENT_SHADER,
        "fragment",
        priorStages = mapOf(
            ShaderKind.TASK_SHADER to taskStage,
            ShaderKind.MESH_SHADER to meshStage
        )
    ) ?: return false

    assignPreparedShaderStage(box, taskStage)
    assignPreparedShaderStage(box, meshStage)
    assignPreparedShaderStage(box, fragmentStage)

    val (ti, tErr) = compileStage(box, taskStage)
    val (mi, mErr) = compileStage(box, meshStage)
    val (fi, fErr) = compileStage(box, fragmentStage)

    if (tErr || mErr || fErr) {
        lprintln("error,graphics", "mesh pipeline compilation failed; skipping mesh module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildMeshInsert(ti!!, mi!!, fi!!, box)
    applyInsertToPreamble(box, insert)

    val p = box._meshShader!!.current.parameters.copy().apply {
        mesh = box._mesh!!
        task = box._task!!
        fragment = box._fragment!!
        taskIntrospection = ti
        meshIntrospection = mi
        fragmentIntrospection = fi
    }
    attachFieldProbe(box, p)
    // todo: patch error handler if you need finer-grained feedback
    box._meshShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "task", "mesh", "fragment")
    return true
}

/**
 * Adopt an externally built mesh-shader module into this box, seeding the `task`, `mesh` and `fragment`
 * properties and reloading once.
 *
 * All three stages must have source (it throws otherwise). Note this installs only the *Reload Mesh
 * Shader* action — there is no ABI-preserving update action for mesh pipelines, so `alt-shift-enter`
 * does nothing on such a box.
 */
fun Box.attachShader(module: MeshModulePointer, overwriteProperties: Boolean = false): MeshModulePointer {
    installMeshShaderReloadAction()

    val parameters = module.current.parameters
    require(parameters.task.isNotBlank()) { "Attached mesh module must have a task shader source." }
    require(parameters.mesh.isNotBlank()) { "Attached mesh module must have a mesh shader source." }
    require(parameters.fragment.isNotBlank()) { "Attached mesh module must have a fragment shader source." }

    if (overwriteProperties || !hasShaderSource(this, "task")) setPropertyRaw("task", parameters.task)
    if (overwriteProperties || !hasShaderSource(this, "mesh")) setPropertyRaw("mesh", parameters.mesh)
    if (overwriteProperties || !hasShaderSource(this, "fragment")) setPropertyRaw("fragment", parameters.fragment)

    _task = parameters.task
    _mesh = parameters.mesh
    _fragment = parameters.fragment
    _meshShader = module
    registerStackAutoShaderOwner(this, module)

    reloadMeshShader(this)
    return module
}

// ------------------------------
// Ray tracing helpers
// ------------------------------
private data class RayTracingStageSpec(
    val baseProperty: String,
    val kind: ShaderKind,
    val required: Boolean = false
)

private val rayTracingStageSpecs = listOf(
    RayTracingStageSpec("raygen", ShaderKind.RAYGEN_SHADER, required = true),
    RayTracingStageSpec("miss", ShaderKind.MISS_SHADER),
    RayTracingStageSpec("closesthit", ShaderKind.CLOSEST_HIT_SHADER),
    RayTracingStageSpec("anyhit", ShaderKind.ANY_HIT_SHADER),
    RayTracingStageSpec("intersection", ShaderKind.INTERSECTION_SHADER),
    RayTracingStageSpec("callable", ShaderKind.CALLABLE_SHADER)
)

private fun prepareRayTracingStages(box: Box): List<PreparedShaderStage>? {
    val prepared = mutableListOf<PreparedShaderStage>()
    val prior = mutableMapOf<ShaderKind, PreparedShaderStage>()
    for (spec in rayTracingStageSpecs) {
        if (!hasShaderSource(box, spec.baseProperty)) {
            if (spec.required) {
                reportShaderStageError(
                    box,
                    sourcePropertyForStage(box, spec.baseProperty),
                    "${spec.baseProperty} shader setup",
                    "No shader source found for property '${spec.baseProperty}'."
                )
                return null
            }
            continue
        }
        val stage = prepareShaderStage(box, spec.kind, spec.baseProperty, priorStages = prior) ?: return null
        prepared += stage
        prior[spec.kind] = stage
    }
    return prepared
}

private fun buildRayTracingInsert(introspections: List<Introspection>): Insert {
    var postImport = ""
    val commentSources = introspections
    postImport += buildShaderStructurePreamble("ray", introspections)

    introspections.flatMap { it.pushConstants }.flatMap { pc -> pc.members.map { pc.name to it } }
        .distinctBy { it.second.name }.forEach { (block, member) ->
            val note = shaderCommentSuffix(commentSources, member.name, block)
            postImport += prefixed(
                "ray-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()$note"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "ray-pc-binding:${member.name}",
                "var RayTracingDispatch.${member.name} by RayTracingDispatch.BoundFromStructure<$ty>()$note"
            ) + "\n"
    }

    introspections.flatMap { it.ubos }.distinctBy { it.name }.forEach { ubo ->
        postImport += prefixed(
            "ray-ubo:${ubo.name}",
            "val RayTracingModulePointer.${ubo.name} by RayTracingFromUniform()"
        ) + "\n"
        ubo.members.forEach { member ->
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "ray-ubo-member:${ubo.name}:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<$ty>()" +
                    shaderCommentSuffix(commentSources, member.name, ubo.name)
            ) + "\n"
        }
    }

    introspections.flatMap { it.ssbo }.distinctBy { it.name }.forEach { ssbo ->
        postImport += prefixed(
            "ray-ssbo:${ssbo.name}",
            "val RayTracingModulePointer.${ssbo.name} by RayTracingFromUniform()"
        ) + "\n"
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

/**
 * Recompile whichever ray-tracing stages this box has and swap the live module.
 *
 * `raygen` is required; the other five are included only if their property is non-blank, so deleting a
 * miss shader and reloading genuinely removes that stage from the pipeline. Stages are prepared in
 * declaration order with raygen available to the rest, which is what lets the hit and miss stages
 * `{% copyPC %}` the raygen push-constant block. `rayTracingGroups` is reset to `null` so the shader
 * binding table is rebuilt from the new stage set.
 *
 * Main runloop only; errors are reported onto the stage properties and `false` leaves the previous
 * module running. Like the mesh path there is no ABI-preserving update action. Throws
 * `NullPointerException` if the box has no ray-tracing module.
 */
fun reloadRayTracingShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    val stages = prepareRayTracingStages(box) ?: return false
    stages.forEach { assignPreparedShaderStage(box, it) }

    val compiled = stages.map { stage -> stage to compileStage(box, stage) }
    if (compiled.any { it.second.hadError || it.second.introspection == null }) {
        lprintln("error,graphics", "ray tracing pipeline compilation failed; skipping ray tracing module reload.")
        return false
    }

    val introspections = compiled.map { it.second.introspection!! }
    applyInsertToPreamble(box, buildRayTracingInsert(introspections))

    val p = box._rayTracingShader!!.current.parameters.copy().apply {
        raygen = box._raygen ?: ""
        miss = box._miss ?: ""
        closesthit = box._closesthit ?: ""
        anyhit = box._anyhit ?: ""
        intersection = box._intersection ?: ""
        callable = box._callable ?: ""
        raygenIntrospection = compiled.introspectionFor(ShaderKind.RAYGEN_SHADER)
        missIntrospection = compiled.introspectionFor(ShaderKind.MISS_SHADER)
        closesthitIntrospection = compiled.introspectionFor(ShaderKind.CLOSEST_HIT_SHADER)
        anyhitIntrospection = compiled.introspectionFor(ShaderKind.ANY_HIT_SHADER)
        intersectionIntrospection = compiled.introspectionFor(ShaderKind.INTERSECTION_SHADER)
        callableIntrospection = compiled.introspectionFor(ShaderKind.CALLABLE_SHADER)
        rayTracingGroups = null
    }
    attachFieldProbe(box, p)
    box._rayTracingShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    return true
}

private fun List<Pair<PreparedShaderStage, CompileResult>>.introspectionFor(kind: ShaderKind): Introspection? =
    firstOrNull { it.first.kind == kind }?.second?.introspection

/**
 * Adopt an externally built ray-tracing module into this box, seeding `raygen` (required) and whichever
 * optional stages the module actually has.
 *
 * Blank optional stages are left unset rather than seeded as empty strings, so the box's property list
 * reflects the pipeline's real shape. Installs only the *Reload Ray Tracing Shader* action; unlike the
 * graphics and compute overloads it does **not** register `_rayTracingShader` via stack.auto ownership,
 * so `{{ stack.auto.… }}` slots in ray-tracing shaders have no module-pointer setter.
 */
fun Box.attachShader(module: RayTracingModulePointer, overwriteProperties: Boolean = false): RayTracingModulePointer {
    installRayTracingShaderReloadAction()

    val parameters = module.current.parameters
    require(parameters.raygen.isNotBlank()) { "Attached ray tracing module must have a raygen shader source." }

    if (overwriteProperties || !hasShaderSource(this, "raygen")) setPropertyRaw("raygen", parameters.raygen)
    if (overwriteProperties || !hasShaderSource(this, "miss")) parameters.miss.takeIf { it.isNotBlank() }?.let { setPropertyRaw("miss", it) }
    if (overwriteProperties || !hasShaderSource(this, "closesthit")) parameters.closesthit.takeIf { it.isNotBlank() }?.let { setPropertyRaw("closesthit", it) }
    if (overwriteProperties || !hasShaderSource(this, "anyhit")) parameters.anyhit.takeIf { it.isNotBlank() }?.let { setPropertyRaw("anyhit", it) }
    if (overwriteProperties || !hasShaderSource(this, "intersection")) parameters.intersection.takeIf { it.isNotBlank() }?.let { setPropertyRaw("intersection", it) }
    if (overwriteProperties || !hasShaderSource(this, "callable")) parameters.callable.takeIf { it.isNotBlank() }?.let { setPropertyRaw("callable", it) }

    _raygen = parameters.raygen
    _miss = parameters.miss.takeIf { it.isNotBlank() }
    _closesthit = parameters.closesthit.takeIf { it.isNotBlank() }
    _anyhit = parameters.anyhit.takeIf { it.isNotBlank() }
    _intersection = parameters.intersection.takeIf { it.isNotBlank() }
    _callable = parameters.callable.takeIf { it.isNotBlank() }
    _rayTracingShader = module

    reloadRayTracingShader(this)
    return module
}

// ------------------------------
// Graphics macro
// ------------------------------
/**
 * Macro expansion for `moduleHere(…)` — the entry point `MacroRegistry` calls when it finds one in a
 * box's text. Delegates to [Box.macro_moduleHereWithDefaultShaders] with Field's stock vertex and
 * fragment shaders as the fallbacks.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_moduleHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(args)
}


/**
 * The vertex shader a box gets if `moduleHere` expands and the box has no vertex source of its own:
 * one `vec3 pos` input transformed by a `push_constant` block of two `mat4`s, `p` and `m`.
 *
 * Worth knowing because it decides the box's *initial* Kotlin API — `pos` as the only mesh writer, and
 * `p`/`m` reachable as `mesh.p` / `mesh.m`. If your first run produced a mesh whose vertex attributes
 * do not match your real shader, this is what it matched instead: seed the shader in an earlier `//%-`
 * fragment.
 */
val defaultVertexShader = """
    #version 450
    layout (location = 0) in vec3 pos;

    layout(push_constant) uniform matrices {
        mat4 p;
        mat4 m;
    } pc;

    void main()
    {
        gl_Position = pc.p * pc.m * vec4(pos, 1);
    }
""".trimIndent()

/** The fragment shader a box gets if `moduleHere` expands with no fragment source of its own: a flat
 * warm orange, chosen so "it drew *something*" is unmistakable. Declares no uniforms, so it generates
 * no accessors. */
val defaultFragmentShader = """
    #version 450
    layout(location = 0) out vec4 uFragColor;
    void main()
    {
        uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
    }
""".trimIndent()

private const val SHADERTOY_VERTEX_TEMPLATE_KEY = "shadertoy.vertexPreamble"
private const val SHADERTOY_FRAGMENT_TEMPLATE_KEY = "shadertoy.fragmentPreamble"

private val shadertoyVertexPreamble = """
    #version 450
""".trimIndent()

private val shadertoyFragmentPreamble = """
    #version 450

    layout(location = 0) out vec4 uFragColor;

    layout(set = 0, binding = 0) uniform ShadertoyUniforms {
        vec3 iResolution;
        float iTime;
        float iTimeDelta;
        float iFrameRate;
        int iFrame;
        vec4 iMouse;
        vec4 iDate;
        vec3 iChannelResolution0;
        float iChannelTime0;
        vec3 iChannelResolution1;
        float iChannelTime1;
        vec3 iChannelResolution2;
        float iChannelTime2;
        vec3 iChannelResolution3;
        float iChannelTime3;
    } st;

    layout(set = 0, binding = 1) uniform sampler2D iChannel0;
    layout(set = 0, binding = 2) uniform sampler2D iChannel1;
    layout(set = 0, binding = 3) uniform sampler2D iChannel2;
    layout(set = 0, binding = 4) uniform sampler2D iChannel3;

    #define iResolution st.iResolution
    #define iTime st.iTime
    #define iTimeDelta st.iTimeDelta
    #define iFrameRate st.iFrameRate
    #define iFrame st.iFrame
    #define iMouse st.iMouse
    #define iDate st.iDate
    #define iChannelTime float[4](st.iChannelTime0, st.iChannelTime1, st.iChannelTime2, st.iChannelTime3)
    #define iChannelResolution vec3[4](st.iChannelResolution0, st.iChannelResolution1, st.iChannelResolution2, st.iChannelResolution3)
""".trimIndent()

private fun Box.ensureShadertoyTemplates() {
    shaderTemplate.putIfAbsent(SHADERTOY_VERTEX_TEMPLATE_KEY, shadertoyVertexPreamble)
    shaderTemplate.putIfAbsent(SHADERTOY_FRAGMENT_TEMPLATE_KEY, shadertoyFragmentPreamble)
}

/**
 * The vertex shader `shadertoyHere` seeds: no inputs at all, just a full-screen triangle synthesised
 * from `gl_VertexIndex`. That is why the shadertoy module needs a 3-vertex mesh and no vertex buffer.
 *
 * It opens with a `{{ shadertoy.vertexPreamble }}` template slot, which `shadertoyHere` puts into the
 * box's [Box.shaderTemplate] map. Copy this text into a box that has never expanded `shadertoyHere` and
 * preprocessing fails on the unresolved slot.
 */
val shadertoyVertexShader = """
    {{ shadertoy.vertexPreamble }}

    void main() {
        vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    }
""".trimIndent()

/**
 * The fragment shader `shadertoyHere` seeds: the `{{ shadertoy.fragmentPreamble }}` slot (which brings
 * in the `iResolution`/`iTime`/`iMouse`/… uniform block and the `#define`s that make them look like
 * shadertoy globals), a `main` that calls `mainImage`, and a placeholder `mainImage` drawing a uv
 * gradient that pulses with `iTime`.
 *
 * Replace the `mainImage` body and leave the rest alone. As with [shadertoyVertexShader], the template
 * slot only resolves on a box whose [Box.shaderTemplate] carries the shadertoy preambles.
 */
val shadertoyFragmentShader = """
    {{ shadertoy.fragmentPreamble }}

    void mainImage(out vec4 fragColor, in vec2 fragCoord);

    void main() {
        vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
        mainImage(color, gl_FragCoord.xy);
        uFragColor = color;
    }

    void mainImage(out vec4 fragColor, in vec2 fragCoord) {
        vec2 uv = fragCoord / max(iResolution.xy, vec2(1.0));
        fragColor = vec4(uv, 0.5 + 0.5 * sin(iTime), 1.0);
    }
""".trimIndent()

private data class ShadertoyTimeState(
    val startedAtNs: Long = System.nanoTime(),
    var lastFrameSampleNs: Long = startedAtNs,
    var lastDeltaSeconds: Float = 1f / 60f,
    var clickOrigin: Vector2Like? = null
)

private data class Vector2Like(val x: Float, val y: Float)

private object ShadertoyTextures {
    private val cache = java.util.WeakHashMap<Renderer, Map<String, LoadedTexture>>()
    private val colors = listOf(
        "iChannel0" to byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()),
        "iChannel1" to byteArrayOf(255.toByte(), 0, 0, 255.toByte()),
        "iChannel2" to byteArrayOf(0, 255.toByte(), 0, 255.toByte()),
        "iChannel3" to byteArrayOf(0, 0, 255.toByte(), 255.toByte())
    )

    fun forRenderer(renderer: Renderer): Map<String, LoadedTexture> = cache.getOrPut(renderer) {
        colors.associate { (name, rgba) ->
            val data = MemoryUtil.memAlloc(4)
            data.put(rgba)
            data.flip()
            name to LoadedTexture.fromRawRGBA(name, 1, 1, data, renderer, VK_FORMAT_R8G8B8A8_SRGB)
        }
    }
}

private fun bindShadertoyUniforms(module: ModulePointer, renderer: Renderer) {
    val uniforms = module.current.liveUniformStructures["ShadertoyUniforms"] ?: return
    val timeState = ShadertoyTimeState()
    val window = renderer.window

    fun cursorNow(): Vector2Like {
        val c = window.cursor
        return Vector2Like(c.x, c.y)
    }

    uniforms.bind("iResolution") {
        Vector3f(window.width.toFloat(), window.height.toFloat(), 1f)
    }
    uniforms.bind("iTime") {
        ((System.nanoTime() - timeState.startedAtNs) / 1_000_000_000.0).toFloat()
    }
    uniforms.bind("iTimeDelta") {
        val now = System.nanoTime()
        val delta = ((now - timeState.lastFrameSampleNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
        timeState.lastFrameSampleNs = now
        timeState.lastDeltaSeconds = if (delta > 0f) delta else timeState.lastDeltaSeconds
        timeState.lastDeltaSeconds
    }
    uniforms.bind("iFrameRate") {
        (1f / timeState.lastDeltaSeconds.coerceAtLeast(1f / 1000f))
    }
    uniforms.bind("iFrame") {
        ((System.nanoTime() - timeState.startedAtNs) / 16_666_667L).toInt()
    }
    uniforms.bind("iMouse") {
        val leftDown = window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        val cursor = cursorNow()
        if (leftDown && timeState.clickOrigin == null) {
            timeState.clickOrigin = cursor
        } else if (!leftDown) {
            timeState.clickOrigin = null
        }
        val origin = timeState.clickOrigin ?: cursor
        val shaderY = window.height.toFloat() - cursor.y
        val originShaderY = window.height.toFloat() - origin.y
        Vector4f(
            cursor.x,
            shaderY,
            if (leftDown) origin.x else -origin.x,
            if (leftDown) originShaderY else -originShaderY
        )
    }
    uniforms.bind("iDate") {
        val now = LocalDateTime.now()
        val seconds =
            now.hour * 3600f + now.minute * 60f + now.second.toFloat() + now.nano / 1_000_000_000f
        Vector4f(now.year.toFloat(), now.monthValue.toFloat(), now.dayOfMonth.toFloat(), seconds)
    }
    for (index in 0..3) {
        uniforms.bind("iChannelResolution$index") { Vector3f(1f, 1f, 1f) }
        uniforms.bind("iChannelTime$index") {
            ((System.nanoTime() - timeState.startedAtNs) / 1_000_000_000.0).toFloat()
        }
    }
}

private fun installShadertoyMesh(module: ModulePointer, renderer: Renderer, name: String = "shadertoy"): Mesh {
    val existing = module.current.meshes[name]
    if (existing != null) return existing

    val mesh = Mesh(renderer, module)
    mesh.resize(3, 0, module)
    module.current.meshes[name] = mesh
    return mesh
}

/**
 * The shared body of every graphics-module macro: seed the box's shader properties if it has none,
 * preprocess and compile both stages *at expansion time*, and return the [Insert] that carries the
 * generated typed bindings plus the rewritten call site.
 *
 * Compiling during expansion is the whole point — the reflected interface is what the generated `Mesh`
 * writers, push-constant properties and structure classes are made of, so there is no way to produce
 * them without knowing what the shader declares. It is also why macro expansion is not side-effect
 * free: this installs the box's reload/update actions and can write its `vertex`/`fragment` properties.
 *
 * If either stage fails to compile it still returns an Insert — but one carrying only the rewritten call
 * site, no bindings — so the box's Kotlin compiles and the shader error is what you see rather than a
 * cascade of unresolved references. The seeding is all-or-nothing across the two stages: a box with a
 * vertex shader but no fragment shader gets [fragment] filled in too.
 *
 * @param args the macro's raw argument text, as captured from the call site
 * @param vertex fallback vertex source, used only if the box has no vertex source of its own
 * @param fragment fallback fragment source, likewise
 * @param resolvesToCall the runtime function the call site is rewritten to — `_moduleHere` normally,
 *   `_shadertoyHere` for the shadertoy flavour
 */
fun Box.macro_moduleHereWithDefaultShaders(
    args: List<String>,
    vertex: String = defaultVertexShader,
    fragment: String = defaultFragmentShader,
    resolvesToCall: String = "_moduleHere"
): Insert? {
    installGraphicsShaderReloadAction()
    installGraphicsShaderUpdateAction()

    if (!hasShaderSource(this, "fragment") || !hasShaderSource(this, "vertex")) {
        this.setPropertyRaw(
            "fragment",
            shaderPropertyValue(this, "fragment")?.takeIf { it.isNotBlank() } ?: fragment
        )
        this.setPropertyRaw(
            "vertex",
            shaderPropertyValue(this, "vertex")?.takeIf { it.isNotBlank() } ?: vertex
        )
    }

    // Prepare code & introspect
    clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
    val vertexStage = prepareShaderStage(this, ShaderKind.VERTEX_SHADER, "vertex")
    val fragmentStage = vertexStage?.let {
        prepareShaderStage(
            this,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(ShaderKind.VERTEX_SHADER to it)
        )
    }
    val vi = vertexStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val fi = fragmentStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (vi == null || fi == null) {
        var here = args[0]
        if (args.size == 2) here += args[1]
        return Insert(here = "$resolvesToCall$here")
    } else {
        clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
        return insertForShader(vi, fi, resolvesToCall, args, this)
    }
}

/**
 * Macro expansion for `shadertoyHere(…)`: register the shadertoy template preambles on the box, then run
 * the ordinary graphics expansion with the shadertoy vertex/fragment shaders as fallbacks and
 * `_shadertoyHere` as the runtime call.
 *
 * The template registration has to happen first, because the seeded shaders reference
 * `{{ shadertoy.fragmentPreamble }}` and preprocessing would otherwise fail on the unresolved slot.
 */
fun Box.macro_shadertoyHere(args: List<String>): Insert? {
    ensureShadertoyTemplates()
    return macro_moduleHereWithDefaultShaders(
        args,
        vertex = shadertoyVertexShader,
        fragment = shadertoyFragmentShader,
        resolvesToCall = "_shadertoyHere"
    )
}


// ------------------------------
// Graphics helpers for Insert
// ------------------------------
private fun buildStackAutoShaderPreamble(box: Box?): String {
    val names = box?.stackAutoShaderSources
        ?.keys
        ?.filter { stackAutoShaderNameRegex.matches(it) }
        ?.sorted()
        ?: return ""

    /*
     * How to drive stack.auto templates:
     *
     *   1. Put a StackSL auto slot in GLSL with `{{ stack.auto.foo }}`,
     *      `{{ stack.auto.foo:word }}`, `{{ stack.auto.foo@samplerName }}`, or
     *      `{{ stack.auto.foo:word@samplerName }}`. Template expansion emits a GLSL function
     *      named `foo` exactly once per expanded shader stage, so later GLSL can
     *      call `foo(vec4(...))`. The optional `:word` forces a StackSL definition
     *      in `module.foo` to be used as the generated function body. The optional
     *      `@samplerName` makes sample words read that GLSL sampler instead of `source`.
     *   2. If no Kotlin code has bound `foo`, the emitted function is the stable
     *      fallback `vec4 foo(vec4 foo_in) { return vec4(0.0); }`. The argument
     *      name deliberately avoids `input`, which is reserved in GLSL 450.
     *   3. During shader macro/reload preamble generation, every discovered
     *      `stack.auto.*` name becomes a Kotlin extension property on shader module pointers:
     *
     *          module.foo = "p length ]2s2.0"
     *
     *      The setter first compiles the StackSL string. Failed compiles leave the
     *      previous binding and current shader pipeline untouched.
     *   4. Successful setter calls queue a shader-family-specific update. Graphics
     *      and compute use the ABI-preserving background path; mesh currently uses
     *      the mesh reload path. Rapid writes are coalesced into one pending follow-up.
     */
    return names.joinToString(separator = "\n") { name ->
        prefixedBlock(
            "stack-auto:$name",
            """
                var ModulePointer.${kotlinMemberName(name)}: String
                    get() = afield.stdlib.stackAutoShaderSource(this, "$name")
                    set(value) { afield.stdlib.stackAutoShaderSource(this, "$name", value) }

                var ComputeModulePointer.${kotlinMemberName(name)}: String
                    get() = afield.stdlib.stackAutoShaderSource(this, "$name")
                    set(value) { afield.stdlib.stackAutoShaderSource(this, "$name", value) }

                var MeshModulePointer.${kotlinMemberName(name)}: String
                    get() = afield.stdlib.stackAutoShaderSource(this, "$name")
                    set(value) { afield.stdlib.stackAutoShaderSource(this, "$name", value) }
            """.trimIndent()
        )
    }
}

/**
 * Turn a compiled vertex+fragment pair into the [Insert] that gives a box its typed graphics API: one
 * `val Mesh.<name> by FromInput()` per vertex attribute, the `m.set { }` writer map, a generated Kotlin
 * class per GLSL struct/UBO/SSBO layout, `stack.auto` string properties, and `Mesh`/`MeshGroup`
 * accessors for every push-constant member. UBO members get *less* than that: the block
 * itself becomes a `val ModulePointer.<name> by FromUniform()` and each member a
 * `var Structure.<name>`, with no `Mesh`/`MeshGroup` accessor, so a UBO member is written through the
 * uniform rather than per-mesh. (The compute and mesh-shader inserts do emit `Mesh.<uboName>`; this
 * graphics path does not.)
 *
 * Shared by macro expansion and [reloadShader] so both produce byte-identical preamble segments — which
 * is what lets [applyInsertToPreamble] recognise and replace them in place instead of piling up copies.
 * Members with the same name across the two stages are emitted once. Push-constant members also get an
 * untyped `Structure.<name>` accessor, so the `m.push["name"]` form works without any of this.
 *
 * @param resolvesToCall the runtime function the call site becomes — `_moduleHere` or `_shadertoyHere`
 * @param args the call site's argument text, as PSI siblings after the callee; the first is appended to
 * the call, and a second or third (a trailing lambda) is appended after it — `args[1]` of three, which is
 * only the whitespace before a lambda, is dropped
 * @param box the owning box, used only to discover its `stack.auto` slot names; `null` skips those
 */
fun insertForShader(
    vi: Introspection,
    fi: Introspection,
    resolvesToCall: String = "_moduleHere",
    args: List<String>,
    box: Box? = null
): Insert {
    var postImport = ""
    val commentSources = listOf(vi, fi)
    vi.inputs.forEach {
        postImport += prefixed(
            "graphics-input:${it.name}",
            "val Mesh.${it.name} by FromInput()"
        ) + "\n"
    }

    postImport += writeSuperMap(vi.inputs) + "\n"
    postImport += buildShaderStructurePreamble("graphics", listOf(vi, fi))
    buildStackAutoShaderPreamble(box).takeIf { it.isNotBlank() }?.let {
        postImport += it + "\n"
    }

    (vi.pushConstants + fi.pushConstants).flatMap { pc -> pc.members.map { pc.name to it } }
        .distinctBy { it.second.name }.forEach { (block, member) ->
            val note = shaderCommentSuffix(commentSources, member.name, block)
            postImport += prefixed(
                "graphics-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()$note"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "graphics-pc-binding:${member.name}",
                "var Mesh.${member.name} by Mesh.BoundFromStructure<$ty>()$note"
            ) + "\n"

            postImport += prefixed(
                "graphics-pc-binding-group:${member.name}",
                "var MeshGroup.${member.name} by MeshGroup.BoundFromStructure<$ty>()$note"
            ) + "\n"
    }

    (fi.ubos + vi.ubos).distinctBy { it.name }.forEach {
        postImport += prefixed(
            "graphics-ubo:${it.name}",
            "val ModulePointer.${it.name} by FromUniform()"
        ) + "\n"
        it.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "graphics-ubo-member:${it.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()" +
                    shaderCommentSuffix(commentSources, m.name, it.name)
            ) + "\n"
        }
    }

    (fi.ssbo + vi.ssbo).distinctBy { it.name }.forEach {
        postImport += prefixed(
            "graphics-ssbo:${it.name}",
            "val ModulePointer.${it.name} by FromUniform()"
        ) + "\n"
    }

    var here = "$resolvesToCall${args[0]}"
    if (args.size == 2) here += args[1]
    if (args.size == 3) here += args[2]

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
            import java.nio.*
        """.trimIndent(),
        preambleAfterImports = postImport,
        here = here
    )
}

// ------------------------------
// Expanded graphics module creation
// ------------------------------
/**
 * The runtime graphics-module builder for the trailing-lambda call site, `moduleHere { … }` after
 * expansion. Resolves the [Renderer] from the ambient context — which means this box must be a
 * descendant of the box that owns the window, or `fromContext()` finds nothing.
 */
fun Box._moduleHere(p: Parameters.() -> Unit): ModulePointer {
    return _moduleHere(parameters(renderer = fromContext(), p))
}

/**
 * Build this box's graphics module and store it as `box._shader` — the function every expanded
 * `moduleHere` call actually ends up in.
 *
 * Takes its GLSL from the *prepared* `_vertex` / `_fragment` properties, overwriting whatever the caller
 * put in [p], which is the mechanism behind "assigning `vertex` inside the lambda does nothing". Throws
 * a `NullPointerException` if no prepare pass has run — normally impossible from an expanded macro,
 * easy to hit when calling this by hand.
 *
 * Also installed here: the box's include resolvers and source names (so shader errors point at box
 * lines), a texture resolver that finds samplers by matching their GLSL name against this box's
 * properties, the field-probe and field-stats SSBO resolvers, and an error handler that reports
 * compilation and validation messages onto the box's `code` property.
 *
 * Two things to be aware of. The shared-Slang (`vertex_fragment`) path re-prepares and re-compiles both
 * stages here, so a box on that route pays for compilation twice — once at expansion, once now. And it
 * sets `globalAsyncDebugHandler`, which is exactly as global as it sounds: the most recently built module
 * wins, so asynchronous Vulkan debug messages are reported onto *that* box regardless of which one
 * provoked them.
 */
fun Box._moduleHere(p: Parameters = parameters {}): ModulePointer {
    val vertexSourceProperty = sourcePropertyForStage(this, "vertex")
    val fragmentSourceProperty = sourcePropertyForStage(this, "fragment")
    if (!shaderPropertyValue(this, sharedVertexFragmentProperty).isNullOrBlank()) {
        clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
        val vertexStage = prepareShaderStage(this, ShaderKind.VERTEX_SHADER, "vertex")
            ?: throw IllegalStateException("Unable to prepare shared vertex shader")
        val fragmentStage = prepareShaderStage(
            this,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(ShaderKind.VERTEX_SHADER to vertexStage)
        )
            ?: throw IllegalStateException("Unable to prepare shared fragment shader")

        assignPreparedShaderStage(this, vertexStage)
        assignPreparedShaderStage(this, fragmentStage)

        val vertexCompile = compileStage(this, vertexStage)
        val fragmentCompile = compileStage(this, fragmentStage)
        val vi = vertexCompile.introspection
            ?: throw IllegalStateException("Unable to compile shared vertex shader")
        val fi = fragmentCompile.introspection
            ?: throw IllegalStateException("Unable to compile shared fragment shader")

        p.vertex = this._vertex!!
        p.fragment = this._fragment!!
        p.vertexIntrospection = vi
        p.fragmentIntrospection = fi
    } else {
        p.vertex = this._vertex!!
        p.fragment = this._fragment!!
    }

    p.vertexSourceName = boxShaderSourceName(this, vertexSourceProperty)
    p.fragmentSourceName = boxShaderSourceName(this, fragmentSourceProperty)
    p.vertexIncludeResolver = shaderIncludeResolverFor(this, vertexSourceProperty)
    p.fragmentIncludeResolver = shaderIncludeResolverFor(this, fragmentSourceProperty)

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    val previousResolver = p.textureResolver
    p.textureResolver = textureResolverFor(previousResolver)

    globalAsyncDebugHandler = { message ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
    }

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    attachFieldProbe(this, p)
    this._shader = Module(p).pointer()
    registerStackAutoShaderOwner(this, this._shader!!)
    return this._shader!!
}

/** The runtime shadertoy-module builder for the trailing-lambda call site. Resolves the [Renderer] from
 * the ambient context, so the box must be a descendant of its renderer box. */
fun Box._shadertoyHere(p: Parameters.() -> Unit): ModulePointer {
    return _shadertoyHere(parameters(renderer = fromContext(), p))
}

/**
 * Build a shadertoy-shaped module: an ordinary [Box._moduleHere] with depth testing off, four 1×1 dummy
 * `iChannel` textures, the shadertoy uniform block bound to live values, and a 3-vertex mesh named
 * `"shadertoy"` already attached so the box draws without you building any geometry.
 *
 * The dummy channels are white/red/green/blue single pixels, cached per renderer, and they are consulted
 * *before* the normal by-name texture search — so `iChannel0` resolves to the dummy even if the box has a
 * property of that name. Two quirks in the uniform bindings are worth knowing: `iFrame` is derived from
 * wall-clock time divided by 16.667 ms rather than from a real frame counter, and `iChannelResolution*`
 * is hard-coded to `(1,1,1)` whatever is actually bound.
 */
fun Box._shadertoyHere(p: Parameters = parameters {}): ModulePointer {
    val renderer: Renderer = p.renderer
    val dummyTextures = ShadertoyTextures.forRenderer(renderer)
    val previousResolver = p.textureResolver

    p.depthTest = false
    p.textureResolver = { textureSpec ->
        dummyTextures[textureSpec.name]?.let { tex -> fromViews(listOf(tex), textureSpec.name) } ?: previousResolver(
            textureSpec
        )
    }

    val module = _moduleHere(p)
    bindShadertoyUniforms(module, renderer)
    installShadertoyMesh(module, renderer)
    return module
}

/**
 * A hook for deciding yourself what a shader's `sampler2D someName` should be bound to, consulted before
 * Field's own by-name search of the box's properties.
 *
 * Called with the box and the sampler's GLSL name; return `null` to fall through to the normal search
 * (which tries `HasImageViewArray`, `HasImageView`, `ImageView`, arrays of those, `LoadedTexture` and
 * `RenderToTexture`, in that order, and throws if none matches). Inherited, so a renderer box can supply
 * one resolver for a whole family of shader boxes.
 *
 * Invoked from the texture resolver during module construction and resolution, on the graphics thread.
 * Note that the surrounding resolver sets the thread-local `Box.context` to the box and never restores it
 * — a `todo` in the source, and something to be aware of if your lambda cares about that context.
 */
var Box.customTextureResolver by Inheritance<((Box, String) -> HasImageViewArray?)?>()

/**
 * Package a fixed list of image views as the `(provider, sampler)` pair a texture resolver returns — the
 * usual way to answer "bind *these* images to that sampler".
 *
 * The list is **captured**: later changes to the underlying object's view array are not seen, which makes
 * this the wrong overload for something like a [RenderToTexture] that can swap its views (use the
 * `HasImageViewArray` overload for that). The returned provider also forwards `prepareSampledImagesFor`
 * to any view that wants it, so render-to-texture layout transitions still happen.
 *
 * The sampler is the first of: [sampler] if you gave one, the default sampler of the first view that
 * *has* one — the list is scanned, so with a mixed list a later view can supply it — or a new
 * mip-aware [TextureSampler] built against `fromContext()`'s renderer, whose mip count comes from
 * `views.first()` regardless of which view supplied the sampler. That last case needs a live graphics
 * context on this thread. Throws if [views] is empty.
 */
fun fromViews(
    views: List<HasImageView>,
    name: String = "",
    sampler: TextureSampler? = null
): Pair<HasImageViewArray, TextureSampler> {
    require(views.isNotEmpty()) { "No image views found for texture $name" }
    val provider = object : HasImageViewArray, PreparesSampledImages {
        override val imageViewArray = views

        override fun prepareSampledImagesFor(command: afield.four.device.CommandBuffer) {
            views.forEach { view ->
                (view as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            }
        }
    }
    val defaultSampler = views.asSequence()
        .mapNotNull { (it as? HasDefaultTextureSampler)?.defaultSampler }
        .firstOrNull()
    val sampler0 = sampler ?: defaultSampler ?: TextureSampler(
        renderer = fromContext(),
        sampledTextureMipLevels(views.first()),
        true
    )
    return provider to sampler0
}

private fun sampledTextureMipLevels(view: HasImageView): Int {
    return (view as? RenderToTexture)?.mipLevels
        ?: view.imageView.imageViewData.mipLevels
}

/**
 * Package a live view *provider* as the `(provider, sampler)` pair a texture resolver returns — the
 * overload to prefer when the thing you are binding can change its views underneath you.
 *
 * Unlike the `List<HasImageView>` overload this does not snapshot: the wrapper re-reads
 * `provider.imageViewArray` every time it is asked, so a [RenderToTexture] that reallocates keeps
 * resolving to its current images. Sampler selection has the same shape as the `List` overload but a
 * different middle step: only the *provider's* own default sampler is consulted, never an individual
 * view's, so a provider that is not `HasDefaultTextureSampler` goes straight to building a new
 * [TextureSampler] — and therefore needs a live graphics context — even when its views carry default
 * samplers of their own. Mip levels are taken from the provider when it is a [RenderToTexture].
 * Throws if the provider is currently empty.
 */
fun fromViews(
    provider: HasImageViewArray,
    name: String = "",
    sampler: TextureSampler? = null
): Pair<HasImageViewArray, TextureSampler> {
    val providerSampler = (provider as? HasDefaultTextureSampler)?.defaultSampler
    val views = provider.imageViewArray
    require(views.isNotEmpty()) { "No image views found for texture $name" }
    val sampler0 = sampler ?: providerSampler ?: TextureSampler(
        renderer = fromContext(),
        sampledTextureMipLevels(provider, views.first()),
        true
    )
    val forwardingProvider = object : HasImageViewArray, PreparesSampledImages {
        override val imageViewArray: List<HasImageView>
            get() = provider.imageViewArray

        override fun prepareSampledImagesFor(command: afield.four.device.CommandBuffer) {
            (provider as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            provider.imageViewArray.forEach { view ->
                (view as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            }
        }
    }
    return forwardingProvider to sampler0
}

private fun sampledTextureMipLevels(provider: HasImageViewArray, firstView: HasImageView): Int {
    return (provider as? RenderToTexture)?.mipLevels
        ?: sampledTextureMipLevels(firstView)
}

private fun Box.textureResolverFor(previousResolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>?): (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler> =
    { textureSpec ->

        Box.context.set(this) // todo, should be a 'with'

        val cached: Pair<HasImageViewArray, TextureSampler>? = previousResolver.invoke(textureSpec)
        if (cached != null) cached else {
            lprintln("debug,graphics", "searching for texture ${textureSpec.name}")
            val name = textureSpec.name

            val c = customTextureResolver
            var found : Pair<HasImageViewArray, TextureSampler>? = null

            if (c != null) {
                val r = c(this, name)
                if (r != null) {
                    found = fromViews(r, name)
                }
            }

            val r = found ?:
                findWithNameOrNull<HasImageViewArray>(this, name)?.let { provider -> fromViews(provider, name) }
                    ?: findWithNameOrNull<HasImageView>(this, name)?.let { hv -> fromViews(listOf(hv), name) }
                    ?: findWithNameOrNull<ImageView>(this, name)?.let { view -> fromViews(listOf(view), name) }
                    ?: findWithNameOrNull<Array<HasImageView>>(this, name)?.let { arr -> fromViews(arr.toList(), name) }
                    ?: findWithNameOrNull<Array<ImageView>>(
                        this,
                        name
                    )?.let { arr -> fromViews(arr.map { it as HasImageView }, name) }
                    ?: findWithNameOrNull<LoadedTexture>(this, name)?.let { tex -> fromViews(listOf(tex), name) }
                    ?: findWithNameOrNull<RenderToTexture>(this, name)?.let { rtt -> fromViews(listOf(rtt), name) }
                    ?: throw Exception(" can't find anything to build a texture out of from this box to ${textureSpec.name}")
            lprintln("debug,graphics", "found $r")
            r
        }
    }

// ------------------------------
// Compute macro + module creation
// ------------------------------
/**
 * Macro expansion for `computeModuleHere(…)`: install the reload and update actions, seed a trivial
 * `outData[i] = inData[i] + 1` kernel if the box has no compute source, compile it, and return the
 * [Insert] carrying the generated `ComputeMesh` bindings and the rewritten `_computeModuleHere` call.
 *
 * As with the graphics macro, compilation happens *now*, at expansion time, because the reflected
 * interface is what the generated accessors are built from. If the shader does not compile the Insert
 * still rewrites the call site but carries no bindings, so the shader error is what surfaces rather than a
 * wall of unresolved Kotlin references.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_computeModuleHere(args: List<String>): Insert {
    installComputeShaderReloadAction()
    installComputeShaderUpdateAction()

    if (!hasShaderSource(this, "compute")) {
        this.setPropertyRaw(
            "compute", """
#version 450

layout(local_size_x = 64) in;

layout(binding = 0) buffer InputBuffer {
    uint inData[];
};

layout(binding = 1) buffer OutputBuffer {
    uint outData[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    outData[idx] = inData[idx] + 1;
}
        """.trimIndent()
        )
    }

    clearShaderErrorsForBaseProperties(this, "compute")
    val computeStage = prepareShaderStage(this, ShaderKind.COMPUTE_SHADER, "compute")
    val ci = computeStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (ci == null) {
        return Insert(here = "_computeModuleHere${args[0]}")
    } else {
        clearShaderErrorsForBaseProperties(this, "compute")
        val insert = buildComputeInsert(ci, this)
        return insert.copy(here = run {
            var here = "_computeModuleHere${args[0]}"
            if (args.size == 2) here += args[1]
            here
        })
    }
}

/** The runtime compute-module builder for the trailing-lambda call site, `computeModuleHere { … }` after
 * expansion. Resolves the [Renderer] from the ambient context, so the box must be a descendant of its
 * renderer box. */
fun Box._computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    return _computeModuleHere(parameters(renderer = fromContext(), p))
}

/**
 * Build this box's compute module and store it as `box._computeShader`.
 *
 * Takes its GLSL from the prepared `_compute` property, overriding whatever the caller set, and throws a
 * `NullPointerException` if no prepare pass has run. Wires up the same by-name texture resolver, include
 * resolver, source name and box-reporting error handler as the graphics path, and attaches the box's field
 * probe / field stats buffers. Unlike [Box._moduleHere] it leaves `globalAsyncDebugHandler` alone.
 */
fun Box._computeModuleHere(p: Parameters): ComputeModulePointer {
    p.compute = this._compute!!
    val computeSourceProperty = sourcePropertyForStage(this, "compute")
    p.computeSourceName = boxShaderSourceName(this, computeSourceProperty)
    p.computeIncludeResolver = shaderIncludeResolverFor(this, computeSourceProperty)

    p.textureResolver = textureResolverFor(p.textureResolver)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = ComputeModule(p).pointer()
    this._computeShader = pp
    registerStackAutoShaderOwner(this, pp)
    return pp
}

// ------------------------------
// Mesh macro + module creation
// ------------------------------
/**
 * Macro expansion for `meshModuleHere(…)`: install the reload action, seed a runnable task/mesh/fragment
 * trio for any of the three the box is missing, compile all three in order, and return the [Insert] with
 * the generated `MeshMesh` bindings.
 *
 * The seeded default is a real, if minimal, mesh pipeline — a task shader that emits three mesh workgroups
 * and a mesh shader that emits one triangle each — so a fresh box draws something. Any stage failing to
 * compile drops the bindings and rewrites the call site only.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_meshModuleHere(args: List<String>): Insert {
    installMeshShaderReloadAction()

    if (!hasShaderSource(this, "task")) {
        this.setPropertyRaw(
            "task", """
#version 450
#extension GL_EXT_mesh_shader : enable

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

struct PerTask { uvec3 taskGlobalID; };
taskPayloadSharedEXT PerTask perTask;

void main ()
{
    uvec3 globalID = gl_WorkGroupID * gl_WorkGroupSize + gl_LocalInvocationID;
    perTask.taskGlobalID = globalID;

    EmitMeshTasksEXT(3,1,1);
}        """.trimIndent()
        )
    }

    if (!hasShaderSource(this, "mesh")) {
        this.setPropertyRaw(
            "mesh", """
#version 450
#extension GL_EXT_mesh_shader : enable

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
layout (triangles) out; 
layout (max_vertices=3, max_primitives=1) out;

struct PerTask { uvec3 taskGlobalID; };
taskPayloadSharedEXT PerTask perTask;

void main ()
{
    SetMeshOutputsEXT(3, 1);
    uvec3 globalID = gl_WorkGroupID * gl_WorkGroupSize + gl_LocalInvocationID;
    uvec3 parentTaskGlobalID = perTask.taskGlobalID;

    float y = globalID.x/9.0;
    float x = parentTaskGlobalID.x/9.0;
    gl_MeshVerticesEXT[0].gl_Position = vec4(-x,y,0,1);
    gl_MeshVerticesEXT[1].gl_Position = vec4(-x+0.25,y,0,1);
    gl_MeshVerticesEXT[2].gl_Position = vec4(-x+0,0.5+y,0,1);
    gl_PrimitiveTriangleIndicesEXT[0] = uvec3(0,1,2);
}
        """.trimIndent()
        )
    }

    if (!hasShaderSource(this, "fragment")) {
        this.setPropertyRaw(
            "fragment", """
 #version 450
layout(location = 0) out vec4 uFragColor;
void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
}
        """.trimIndent()
        )
    }

    clearShaderErrorsForBaseProperties(this, "task", "mesh", "fragment")
    val taskStage = prepareShaderStage(this, ShaderKind.TASK_SHADER, "task")
    val meshStage = taskStage?.let {
        prepareShaderStage(
            this,
            ShaderKind.MESH_SHADER,
            "mesh",
            priorStages = mapOf(ShaderKind.TASK_SHADER to it)
        )
    }
    val fragmentStage = if (taskStage != null && meshStage != null) {
        prepareShaderStage(
            this,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(
                ShaderKind.TASK_SHADER to taskStage,
                ShaderKind.MESH_SHADER to meshStage
            )
        )
    } else {
        null
    }

    val ti = taskStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val mi = meshStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val fi = fragmentStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (ti == null || mi == null || fi == null) {
        return Insert(here = "_meshModuleHere${args[0]}")
    } else {
        clearShaderErrorsForBaseProperties(this, "task", "mesh", "fragment")
        val insert = buildMeshInsert(ti, mi, fi, this)
        return insert.copy(here = run {
            var here = "_meshModuleHere${args[0]}"
            if (args.size == 2) here += args[1]
            here
        })
    }
}

/** The runtime mesh-module builder for the trailing-lambda call site. Resolves the [Renderer] from the
 * ambient context, so the box must be a descendant of its renderer box. */
fun Box._meshModuleHere(p: Parameters.() -> Unit): MeshModulePointer {
    return _meshModuleHere(parameters(renderer = fromContext(), p))
}

/**
 * Build this box's mesh-shader module from the prepared `_task`, `_mesh` and `_fragment` properties and
 * store it as `box._meshShader`.
 *
 * All three are required and dereferenced without a null check, so a missing prepare pass is a
 * `NullPointerException`. Per-stage include resolvers and source names are installed so errors land on the
 * right property. Note that, unlike the graphics and compute paths, no custom texture resolver is
 * installed here — a mesh-pipeline shader's samplers are resolved by whatever [p] already carries.
 */
fun Box._meshModuleHere(p: Parameters = parameters {}): MeshModulePointer {

    p.mesh = this._mesh!!
    p.task = this._task!!
    p.fragment = this._fragment!!
    val taskSourceProperty = sourcePropertyForStage(this, "task")
    val meshSourceProperty = sourcePropertyForStage(this, "mesh")
    val fragmentSourceProperty = sourcePropertyForStage(this, "fragment")
    p.taskSourceName = boxShaderSourceName(this, taskSourceProperty)
    p.meshSourceName = boxShaderSourceName(this, meshSourceProperty)
    p.fragmentSourceName = boxShaderSourceName(this, fragmentSourceProperty)
    p.taskIncludeResolver = shaderIncludeResolverFor(this, taskSourceProperty)
    p.meshIncludeResolver = shaderIncludeResolverFor(this, meshSourceProperty)
    p.fragmentIncludeResolver = shaderIncludeResolverFor(this, fragmentSourceProperty)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "mesh module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = MeshModule(p).pointer()
    this._meshShader = pp
    registerStackAutoShaderOwner(this, pp)
    return pp
}

// ------------------------------
// Ray tracing macro + module creation
// ------------------------------
/**
 * The ray-generation shader a box gets when `rayTracingModuleHere` expands and the box has no `raygen`
 * source: it writes the launch uv into the ray payload and traces nothing at all.
 *
 * Trace-free, so the pipeline builds and dispatches on any device with ray tracing without
 * needing an acceleration structure to exist yet. Declares no push constants or bindings, so it generates
 * no accessors either.
 */
val defaultRaygenShader = """
#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadEXT vec4 payload;

void main()
{
    vec2 uv = (vec2(gl_LaunchIDEXT.xy) + vec2(0.5)) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));
    payload = vec4(uv, 0.0, 1.0);
}
""".trimIndent()

/**
 * Macro expansion for `rayTracingModuleHere(…)`: install the reload action, seed [defaultRaygenShader] if
 * the box has no `raygen`, compile every stage the box does have, and return the [Insert] with the
 * generated `RayTracingDispatch` bindings.
 *
 * Only `raygen` is seeded — the optional stages stay absent rather than being filled with placeholders, so
 * the expansion reflects the pipeline you actually asked for. If any present stage fails to compile the
 * bindings are dropped and only the call site is rewritten.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_rayTracingModuleHere(args: List<String>): Insert {
    installRayTracingShaderReloadAction()

    if (!hasShaderSource(this, "raygen")) {
        setPropertyRaw("raygen", defaultRaygenShader)
    }

    clearShaderErrorsForBaseProperties(this, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    val stages = prepareRayTracingStages(this)
    val introspections = stages?.mapNotNull {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    val here = run {
        var h = "_rayTracingModuleHere${args[0]}"
        if (args.size == 2) h += args[1]
        h
    }

    if (stages == null || introspections == null || introspections.size != stages.size) {
        return Insert(here = here)
    }

    clearShaderErrorsForBaseProperties(this, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    return buildRayTracingInsert(introspections).copy(here = here)
}

/** The runtime ray-tracing-module builder for the trailing-lambda call site. Resolves the [Renderer] from
 * the ambient context, so the box must be a descendant of its renderer box. */
fun Box._rayTracingModuleHere(p: Parameters.() -> Unit): RayTracingModulePointer {
    return _rayTracingModuleHere(parameters(renderer = fromContext(), p))
}

/**
 * Build this box's ray-tracing module from the prepared stage properties and store it as
 * `box._rayTracingShader`.
 *
 * `_raygen` is required and dereferenced without a check; the five optional stages become `""` when absent,
 * and only stages the box actually has get a source name and include resolver, so a blank stage genuinely
 * stays out of the pipeline. Installs the by-name texture resolver and the box-reporting error handler.
 *
 * Unlike the other three builders this one does not register the module for `stack.auto` ownership, so
 * `{{ stack.auto.… }}` slots in ray-tracing shaders have no generated setter to drive them.
 */
fun Box._rayTracingModuleHere(p: Parameters = parameters {}): RayTracingModulePointer {
    p.raygen = this._raygen!!
    p.miss = this._miss ?: ""
    p.closesthit = this._closesthit ?: ""
    p.anyhit = this._anyhit ?: ""
    p.intersection = this._intersection ?: ""
    p.callable = this._callable ?: ""

    val raygenSourceProperty = sourcePropertyForStage(this, "raygen")
    p.raygenSourceName = boxShaderSourceName(this, raygenSourceProperty)
    p.raygenIncludeResolver = shaderIncludeResolverFor(this, raygenSourceProperty)

    fun configureOptional(base: String, assign: (String, ShaderIncludeResolver?) -> Unit) {
        if (!hasShaderSource(this, base)) return
        val sourceProperty = sourcePropertyForStage(this, base)
        assign(boxShaderSourceName(this, sourceProperty), shaderIncludeResolverFor(this, sourceProperty))
    }

    configureOptional("miss") { sourceName, resolver ->
        p.missSourceName = sourceName
        p.missIncludeResolver = resolver
    }
    configureOptional("closesthit") { sourceName, resolver ->
        p.closesthitSourceName = sourceName
        p.closesthitIncludeResolver = resolver
    }
    configureOptional("anyhit") { sourceName, resolver ->
        p.anyhitSourceName = sourceName
        p.anyhitIncludeResolver = resolver
    }
    configureOptional("intersection") { sourceName, resolver ->
        p.intersectionSourceName = sourceName
        p.intersectionIncludeResolver = resolver
    }
    configureOptional("callable") { sourceName, resolver ->
        p.callableSourceName = sourceName
        p.callableIncludeResolver = resolver
    }

    p.textureResolver = textureResolverFor(p.textureResolver)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "ray tracing module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = RayTracingModule(p).pointer()
    this._rayTracingShader = pp
    return pp
}
