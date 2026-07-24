package afield.remotejs

import afield.Box
import afield.graphics.RemoteLifecycleKotlinJs
import afield.graphics.RemoteArKotlinJs
import afield.graphics.ToneKotlinJs
import afield.graphics.VisionMetalKotlinJs
import afield.graphics.visionMetal
import afield.graphics.visionMetalConnectedHeadCount
import afield.graphics.visionMetalExampleShader
import afield.stdlib.fragment
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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

    fun prepareSource(userSource: String): String {
        // Implicit-main wrapping must happen before declarations are appended. External declarations
        // are file-scope-only, and appending afterwards also keeps explicit-main diagnostic lines stable.
        val executable = KotlinToJsCompiler.ensureImplicitMain(userSource)
        return if (declarations.isBlank()) executable else "$executable\n\n$declarations"
    }
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
        val authoredShader = box.fragment.orEmpty().trim()
        val shader = authoredShader.ifEmpty { visionMetalExampleShader }
        val inferred = inferUniforms(selection.source)
        val uniforms = selection.optionList("uniforms")
            ?: if (authoredShader.isEmpty()) (defaultUniforms + inferred).distinct() else inferred
        require(uniforms.size <= 16) { "Vision Metal supports at most 16 uniform names, got ${uniforms.size}" }

        val state = box.visionMetal(
            shader = shader,
            script = javascript,
            launchable = launchable,
            uniforms = uniforms,
            name = "kotlinjs",
        )
        if (launchable && onResponse != null) {
            lifecycleResponses[state.uid] = LifecycleResponse(state.currentRevision, onResponse)
        }
        if (statusAttached.add(state.uid)) {
            state.onStatus { status ->
                if (!status.ok) box.output.errorProxy("${status.phase}: ${status.message}", "code", 0)
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
        val shader = box.fragment.orEmpty().trim().ifEmpty { visionMetalExampleShader }
        val uid = "${box.id}_kotlinjs"
        lifecycleResponses.remove(uid)
        box.visionMetal(
            shader = shader,
            script = "",
            launchable = false,
            uniforms = emptyList(),
            name = "kotlinjs",
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
