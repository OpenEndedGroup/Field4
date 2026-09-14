package afield.remotejs

import afield.Box
import afield.graphics.RemoteLifecycleKotlinJs
import afield.graphics.RemoteArKotlinJs
import afield.graphics.ToneKotlinJs
import afield.graphics.VISION_METAL_MAX_UNIFORMS
import afield.graphics.VisionMetalGeometryOrigin
import afield.graphics.VisionMetalKotlinJs
import afield.graphics.visionMetal
import afield.graphics.visionMetalConnectedHeadCount
import afield.graphics.visionMetalExampleShader
import afield.graphics.visionMetalShaderSource
import afield.graphics.visionMetalUID
import afield.notify
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Which remote head a Kotlin/JS box runs on, chosen by a marker on its first line.
 *
 * A box whose main language is `kotlinjs` compiles to JavaScript and ships to a head over the
 * RemoteJS transport, but the heads are not interchangeable: each has a different set of globals
 * loaded, and Kotlin can only be typed against the globals its target actually has. A profile is
 * that pairing. It carries the `external` declarations spliced in front of the author's source
 * before compiling, a second surface used for completion, and the transport and lifecycle calls
 * that put the compiled JavaScript in front of a head.
 *
 * `KotlinJsHeadProfiles.resolve` reads a `// {{ name }}` marker on the first non-blank line and
 * returns a [KotlinJsHeadSelection]. A name is matched case-insensitively against an id or an
 * alias:
 *
 *  - `remote` — the plain RemoteJS head; aliases `default` and `remotejs`, and what a box with no
 *    marker at all gets.
 *  - `remoteAR` — the A-Frame and THREE scene head; aliases `ar`, `remote-ar`, `remotear`.
 *  - `tone` — the Tone.js computer-music head; aliases `tonejs` and `music`.
 *  - `visionmetal` — the visionOS Metal head; aliases `metal` and `vision-metal`.
 *
 * An unknown name raises instead of falling back, because a box compiled against the wrong globals
 * type-checks and then fails in the head. The same reasoning gates delivery at run time: a head
 * has to report the capability keys its profile requires in its `initialize` payload before it is
 * counted or sent to, so `// {{ tone }}` reaches a head with Tone loaded and no other.
 *
 * Two further things `resolve` does, both about line numbers. It blanks the marker's line rather
 * than removing it, so the lines the compiler reports still line up with what the author sees. And
 * `resolveForBox` re-reads the marker from the box's stored `code` when the source handed to it
 * carries none, which is what fragment and symbol execution pass when only a selection is being
 * run.
 *
 * The two implementations behind the sealed class mean different things by sending. The WebSocket
 * one hands its JavaScript to `executeRemoteJS` with `doASTA = false`, broadcasts to every
 * connected head that matches, and reports results back through the box's output. The visionOS one
 * publishes a Metal program on the box instead and reports success without waiting, because that
 * publication is durable and replays for a native head that subscribes later. Its `uniforms`
 * option is the only marker option here: slot order is what the head turns into `#define u_NAME`,
 * so it is declared rather than inferred, and the inference that covers an absent declaration says
 * what it guessed.
 */

/** A compiler surface plus the transport and lifecycle semantics of one remote JavaScript head. */
sealed class KotlinJsHeadProfile(
    val id: String,
    val declarations: String,
    val completionDeclarations: String,
    val aliases: Set<String> = emptySet(),
    val optionNames: Set<String> = emptySet(),
) {
    abstract fun connectedHeadCount(): Int
    abstract fun send(
        box: Box,
        selection: KotlinJsHeadSelection,
        javascript: String,
        launchable: Boolean,
        lifecycleName: String,
        onResponse: ((KotlinJsHeadResponse) -> Boolean)? = null,
    ): Boolean
    abstract fun stop(box: Box, selection: KotlinJsHeadSelection, lifecycleNames: Collection<String>)

    fun prepareSource(userSource: String): String = prepareSourceMapped(userSource).text

    internal fun prepareSourceMapped(
        userSource: String,
        activeUserLines: IntRange? = null,
        stateKey: String? = null,
    ): KotlinToJsCompiler.PreparedSource =
        KotlinToJsCompiler.prepareImplicitMain(userSource, activeUserLines, stateKey)
            .appendUnmapped(declarations)
}

data class KotlinJsHeadResponse(
    val kind: String,
    val headId: String,
    val expectedHeadIds: Set<String>,
    val terminal: Boolean = false,
)

data class KotlinJsHeadSelection(
    val profile: KotlinJsHeadProfile,
    val source: String,
    val options: Map<String, String>,
    val explicit: Boolean,
) {
    fun optionList(name: String): List<String>? = options[name]
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
}

object KotlinJsHeadProfiles {
    private val marker = Regex("""^\s*//\s*\{\{\s*([A-Za-z][A-Za-z0-9_-]*)(.*?)}}\s*$""")
    private val option = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*([^\s]+)""")

    val remote: KotlinJsHeadProfile = RemoteProfile(
        id = "remote",
        declarations = RemoteLifecycleKotlinJs.declarations,
        completionDeclarations = RemoteLifecycleKotlinJs.completionDeclarations,
        aliases = setOf("default", "remotejs"),
        requiredCapabilities = emptyList(),
    )

    val remoteAr: KotlinJsHeadProfile = RemoteProfile(
        id = RemoteArKotlinJs.surfaceId,
        declarations = joined(RemoteLifecycleKotlinJs.declarations, RemoteArKotlinJs.declarations),
        completionDeclarations = joined(
            RemoteLifecycleKotlinJs.completionDeclarations,
            RemoteArKotlinJs.completionDeclarations,
        ),
        aliases = setOf("ar", "remote-ar", "remotear"),
        requiredCapabilities = listOf("aframe", "three"),
    )

    /**
     * Tone.js computer-music head. Tone is installed as the global `Tone` by the RemoteAR head
     * (`index.html` loads `vendor/tone/…/Tone.js`), so this profile shares that transport but requires
     * the head to actually report the `tone` capability — a bound-but-absent name would compile then
     * fail at runtime, so we route `// {{ tone }}` only to a head that has Tone loaded.
     */
    val tone: KotlinJsHeadProfile = RemoteProfile(
        id = ToneKotlinJs.surfaceId,
        declarations = joined(RemoteLifecycleKotlinJs.declarations, ToneKotlinJs.declarations),
        completionDeclarations = joined(
            RemoteLifecycleKotlinJs.completionDeclarations,
            ToneKotlinJs.completionDeclarations,
        ),
        aliases = setOf("tonejs", "music"),
        requiredCapabilities = listOf("tone"),
    )

    val visionMetal: KotlinJsHeadProfile = VisionMetalProfile

    val all: List<KotlinJsHeadProfile> = listOf(remote, remoteAr, tone, visionMetal)

    private val byName: Map<String, KotlinJsHeadProfile> = buildMap {
        all.forEach { profile ->
            put(profile.id.lowercase(), profile)
            profile.aliases.forEach { put(it.lowercase(), profile) }
        }
    }

    private fun joined(vararg surfaces: String): String =
        surfaces.filter(String::isNotBlank).joinToString("\n\n")

    /**
     * Resolve only the first non-blank line. An absent marker preserves the original RemoteJS
     * broadcast behavior; an unknown explicit marker is an error instead of silently using a head
     * with the wrong globals.
     */
    fun resolve(source: String): KotlinJsHeadSelection {
        val lines = source.split('\n').toMutableList()
        val lineIndex = lines.indexOfFirst { it.isNotBlank() }
        if (lineIndex < 0) return KotlinJsHeadSelection(remote, source, emptyMap(), explicit = false)
        val match = marker.matchEntire(lines[lineIndex])
            ?: return KotlinJsHeadSelection(remote, source, emptyMap(), explicit = false)
        val requested = match.groupValues[1]
        val profile = byName[requested.lowercase()]
            ?: throw IllegalArgumentException(
                "Unknown Kotlin/JS head '$requested'; choose ${all.joinToString { it.id }}",
            )
        val tail = match.groupValues[2].trim()
        val options = parseOptions(tail)
        val unknownOptions = options.keys - profile.optionNames
        require(unknownOptions.isEmpty()) {
            "Kotlin/JS head '${profile.id}' does not support option(s) ${unknownOptions.joinToString()}; " +
                if (profile.optionNames.isEmpty()) "it has no options" else "choose ${profile.optionNames.joinToString()}"
        }
        lines[lineIndex] = "" // retain the marker's line, and therefore user diagnostic line numbers
        return KotlinJsHeadSelection(profile, lines.joinToString("\n"), options, explicit = true)
    }

    /** Fragment/symbol execution often contains only the selected text; inherit the marker from code. */
    fun resolveForBox(box: Box, source: String): KotlinJsHeadSelection {
        val direct = resolve(source)
        if (direct.explicit) return direct
        val stored = box.getPropertyRaw("code", "") as? String ?: ""
        val inherited = resolve(stored)
        return if (inherited.explicit) inherited.copy(source = source) else direct
    }

    private fun parseOptions(tail: String): Map<String, String> {
        if (tail.isBlank()) return emptyMap()
        val matches = option.findAll(tail).toList()
        val residue = option.replace(tail, "").trim().trim(',').trim()
        require(matches.isNotEmpty() && residue.isEmpty()) {
            "Malformed Kotlin/JS head options '$tail'; use key=value (for example uniforms=phase,amount,tint)"
        }
        return buildMap {
            matches.forEach {
                val name = it.groupValues[1].lowercase()
                require(name !in this) { "Duplicate Kotlin/JS head option '$name'" }
                put(name, it.groupValues[2])
            }
        }
    }
}

private class RemoteProfile(
    id: String,
    declarations: String,
    completionDeclarations: String,
    aliases: Set<String>,
    /** Capability keys the head's `initialize` payload must report non-blank (empty = any head). */
    private val requiredCapabilities: List<String>,
) : KotlinJsHeadProfile(id, declarations, completionDeclarations, aliases) {
    private fun matches(clientId: String): Boolean {
        if (requiredCapabilities.isEmpty()) return true
        val capabilities = remoteClientCapabilities[clientId] ?: return false
        return requiredCapabilities.all { capabilities.optString(it).isNotBlank() }
    }

    override fun connectedHeadCount(): Int = knownOpenRemoteClients.keys.count(::matches)

    override fun send(
        box: Box,
        selection: KotlinJsHeadSelection,
        javascript: String,
        launchable: Boolean,
        lifecycleName: String,
        onResponse: ((KotlinJsHeadResponse) -> Boolean)?,
    ): Boolean {
        val expectedHeadIds = knownOpenRemoteClients.keys.filter(::matches).toSet()
        return executeRemoteJS(
            javascript,
            filename = target(box),
            lifecycleName = lifecycleName,
            launchable = launchable,
            requiresSandbox = false,
            doASTA = false,
            filterSession = { clientId, _ -> matches(clientId) },
            diagnosticsTo = { box.output.errorProxy(it.message, "code", it.lineNumber.toInt()) },
        ) { response ->
            val keepForOutput = reportRemoteResult(box, response)
            onResponse?.invoke(
                KotlinJsHeadResponse(
                    kind = response.getJSONObject("payload").getString("kind"),
                    headId = response.optString("from", "unknown-head"),
                    expectedHeadIds = expectedHeadIds,
                    terminal = response.getJSONObject("payload").optBoolean("terminal", false),
                ),
            ) ?: keepForOutput
        }
    }

    override fun stop(box: Box, selection: KotlinJsHeadSelection, lifecycleNames: Collection<String>) {
        val names = lifecycleNames.ifEmpty { listOf(kotlinJsLifecycleName(box, "default")) }
        val quotedNames = names.joinToString(",") { JSONObject.quote(it) }
        executeRemoteJS(
            """
            for (const name of [$quotedNames]) {
                if (typeof __fieldStopLifecycle === "function") {
                    __fieldStopLifecycle(name)
                } else if (__tasks[name]) {
                    __tasks[name].stop()
                }
            }
            """.trimIndent(),
            filename = target(box),
            launchable = true,
            requiresSandbox = true,
            filterSession = { clientId, _ -> matches(clientId) },
        ) { false }
    }
}

private object VisionMetalProfile : KotlinJsHeadProfile(
    id = VisionMetalKotlinJs.surfaceId,
    declarations = VisionMetalKotlinJs.declarations,
    completionDeclarations = VisionMetalKotlinJs.completionDeclarations,
    aliases = setOf("metal", "vision-metal"),
    optionNames = setOf("uniforms"),
) {
    /** Distinguishes the Kotlin/JS program from a JVM-side `visionMetal(…)` one on the same box. */
    private const val PROGRAM_NAME = "kotlinjs"

    private val statusAttached = ConcurrentHashMap.newKeySet<String>()
    private data class LifecycleResponse(
        val revision: Int,
        val callback: (KotlinJsHeadResponse) -> Boolean,
    )
    private val lifecycleResponses = ConcurrentHashMap<String, LifecycleResponse>()
    private val uniformWrite = Regex("""\buniform\s*\(\s*\"((?:\\.|[^\"\\])*)\"""")
    private val defaultUniforms = listOf("phase", "amount", "tint")

    override fun connectedHeadCount(): Int = visionMetalConnectedHeadCount()

    override fun send(
        box: Box,
        selection: KotlinJsHeadSelection,
        javascript: String,
        launchable: Boolean,
        lifecycleName: String,
        onResponse: ((KotlinJsHeadResponse) -> Boolean)?,
    ): Boolean {
        val authoredShader = box.visionMetalShaderSource(PROGRAM_NAME)?.trim().orEmpty()
        val shader = authoredShader.ifEmpty { visionMetalExampleShader }
        // Slot order is what the head turns into `#define u_NAME`, so it has to be explicit. The
        // marker option declares it; inference is a fallback that says so rather than staying silent.
        val declared = selection.optionList("uniforms")
        val uniforms = declared ?: inferUniforms(selection.source).let { inferred ->
            if (authoredShader.isEmpty()) (defaultUniforms + inferred).distinct() else inferred
        }
        if (declared == null && uniforms.isNotEmpty()) {
            notify(
                "Inferred vision uniforms ${uniforms.joinToString(",")} in '${box.name}'; " +
                    "declare them as // {{ visionmetal uniforms=${uniforms.joinToString(",")} }} to fix slot order",
                "vision-metal",
            )
        }
        require(uniforms.size <= VISION_METAL_MAX_UNIFORMS) {
            "Vision Metal supports at most $VISION_METAL_MAX_UNIFORMS uniform names, got ${uniforms.size}"
        }

        val state = box.visionMetal(
            shader = shader,
            script = javascript,
            launchable = launchable,
            uniforms = uniforms,
            name = PROGRAM_NAME,
            // The box's Kotlin/JS `geometry { … }` builder owns the vertex buffers.
            geometry = VisionMetalGeometryOrigin.SCRIPT,
        )
        if (launchable && onResponse != null) {
            lifecycleResponses[state.uid] = LifecycleResponse(state.currentRevision, onResponse)
        }
        if (statusAttached.add(state.uid)) {
            state.onStatus { status ->
                // Metal diagnostics carry the author's own line number, because the head emits
                // `#line 1` after its preamble — so a shader error decorates the `msl` property at
                // the offending line instead of landing at line zero of the Kotlin.
                if (!status.ok) {
                    val property = if (status.phase == "shader") "msl" else "code"
                    box.output.errorProxy("${status.phase}: ${status.message}", property, status.line)
                }
                val receiver = lifecycleResponses[status.uid]
                if (receiver != null && receiver.revision == status.revision) {
                    val kind = when {
                        status.phase == "lifecycle" && status.message == "RUN-START" -> "RUN-START"
                        status.phase == "lifecycle" && status.message == "RUN-STOP" -> "RUN-STOP"
                        status.phase == "javascript" && !status.ok -> "ERROR"
                        else -> null
                    }
                    if (kind != null) {
                        val keep = receiver.callback(
                            KotlinJsHeadResponse(
                                kind = kind,
                                headId = status.uid,
                                expectedHeadIds = setOf(status.uid),
                                terminal = kind != "RUN-START",
                            ),
                        )
                        if (!keep) lifecycleResponses.remove(status.uid, receiver)
                    }
                }
            }
        }
        return true // publication is durable and replays when a native head subscribes later
    }

    override fun stop(box: Box, selection: KotlinJsHeadSelection, lifecycleNames: Collection<String>) {
        val shader = box.visionMetalShaderSource(PROGRAM_NAME)?.trim().orEmpty()
            .ifEmpty { visionMetalExampleShader }
        lifecycleResponses.remove(box.visionMetalUID(PROGRAM_NAME))
        // Stop replaces the script with an inert one. Geometry stays SCRIPT-owned, so the last mesh
        // the builder uploaded remains on screen rather than snapping back to the built-in cube.
        box.visionMetal(
            shader = shader,
            script = "",
            launchable = false,
            uniforms = emptyList(),
            name = PROGRAM_NAME,
            geometry = VisionMetalGeometryOrigin.SCRIPT,
        )
    }

    private fun inferUniforms(source: String): List<String> = uniformWrite.findAll(source)
        .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
        .distinct()
        .toList()
}

internal fun kotlinJsLifecycleName(box: Box, strand: String): String =
    "${target(box)}::kotlinjs::${strand.ifBlank { "default" }}"

private fun reportRemoteResult(box: Box, response: JSONObject): Boolean {
    val payload = response.getJSONObject("payload")
    val kind = payload.getString("kind")
    val message = payload.optString("message", "")
    val line = if (payload.has("line")) payload.getInt("line") else 0
    when (kind) {
        "ERROR" -> box.output.errorProxy(message, "code", line)
        "SUCCESS" -> if (message.trim() != "OK") box.output.proxy(message.replace("<json>", ""), true, line)
        "RUN-START", "RUN-STOP" -> Unit
        else -> box.output.proxy(message, true, line)
    }
    return kind != "ERROR" && kind != "RUN-STOP"
}
