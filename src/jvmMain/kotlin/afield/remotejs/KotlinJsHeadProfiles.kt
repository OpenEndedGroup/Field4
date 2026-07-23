package afield.remotejs

import afield.Box
import afield.graphics.RemoteArKotlinJs
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
    abstract fun send(box: Box, selection: KotlinJsHeadSelection, javascript: String, launchable: Boolean): Boolean
    abstract fun stop(box: Box, selection: KotlinJsHeadSelection)

    fun prepareSource(userSource: String): String {
        // Implicit-main wrapping must happen before declarations are appended. External declarations
        // are file-scope-only, and appending afterwards also keeps explicit-main diagnostic lines stable.
        val executable = KotlinToJsCompiler.ensureImplicitMain(userSource)
        return if (declarations.isBlank()) executable else "$executable\n\n$declarations"
    }
}

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
        declarations = "",
        completionDeclarations = "",
        aliases = setOf("default", "remotejs"),
        requireRemoteArCapabilities = false,
    )

    val remoteAr: KotlinJsHeadProfile = RemoteProfile(
        id = RemoteArKotlinJs.surfaceId,
        declarations = RemoteArKotlinJs.declarations,
        completionDeclarations = RemoteArKotlinJs.completionDeclarations,
        aliases = setOf("ar", "remote-ar", "remotear"),
        requireRemoteArCapabilities = true,
    )

    val visionMetal: KotlinJsHeadProfile = VisionMetalProfile

    val all: List<KotlinJsHeadProfile> = listOf(remote, remoteAr, visionMetal)

    private val byName: Map<String, KotlinJsHeadProfile> = buildMap {
        all.forEach { profile ->
            put(profile.id.lowercase(), profile)
            profile.aliases.forEach { put(it.lowercase(), profile) }
        }
    }

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
    private val requireRemoteArCapabilities: Boolean,
) : KotlinJsHeadProfile(id, declarations, completionDeclarations, aliases) {
    private fun matches(clientId: String): Boolean {
        if (!requireRemoteArCapabilities) return true
        val capabilities = remoteClientCapabilities[clientId] ?: return false
        return capabilities.optString("aframe").isNotBlank() && capabilities.optString("three").isNotBlank()
    }

    override fun connectedHeadCount(): Int = knownOpenRemoteClients.keys.count(::matches)

    override fun send(box: Box, selection: KotlinJsHeadSelection, javascript: String, launchable: Boolean): Boolean =
        executeRemoteJS(
            javascript,
            filename = target(box),
            launchable = launchable,
            requiresSandbox = false,
            doASTA = false,
            filterSession = { clientId, _ -> matches(clientId) },
            diagnosticsTo = { box.output.errorProxy(it.message, "code", it.lineNumber.toInt()) },
        ) { response ->
            reportRemoteResult(box, response)
        }

    override fun stop(box: Box, selection: KotlinJsHeadSelection) {
        executeRemoteJS(
            "if (__tasks[\"${target(box)}\"]) __tasks[\"${target(box)}\"].stop()",
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
    private val uniformWrite = Regex("""\buniform\s*\(\s*\"((?:\\.|[^\"\\])*)\"""")
    private val defaultUniforms = listOf("phase", "amount", "tint")

    override fun connectedHeadCount(): Int = visionMetalConnectedHeadCount()

    override fun send(box: Box, selection: KotlinJsHeadSelection, javascript: String, launchable: Boolean): Boolean {
        val authoredShader = box.fragment.orEmpty().trim()
        val shader = authoredShader.ifEmpty { visionMetalExampleShader }
        val inferred = inferUniforms(selection.source)
        val uniforms = selection.optionList("uniforms")
            ?: if (authoredShader.isEmpty()) (defaultUniforms + inferred).distinct() else inferred
        require(uniforms.size <= 16) { "Vision Metal supports at most 16 uniform names, got ${uniforms.size}" }

        val state = box.visionMetal(
            shader = shader,
            script = javascript,
            uniforms = uniforms,
            name = "kotlinjs",
        )
        if (statusAttached.add(state.uid)) {
            state.onStatus { status ->
                if (!status.ok) box.output.errorProxy("${status.phase}: ${status.message}", "code", 0)
            }
        }
        return true // publication is durable and replays when a native head subscribes later
    }

    override fun stop(box: Box, selection: KotlinJsHeadSelection) {
        val shader = box.fragment.orEmpty().trim().ifEmpty { visionMetalExampleShader }
        box.visionMetal(shader = shader, script = "", uniforms = emptyList(), name = "kotlinjs")
    }

    private fun inferUniforms(source: String): List<String> = uniformWrite.findAll(source)
        .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
        .distinct()
        .toList()
}

private fun reportRemoteResult(box: Box, response: JSONObject): Boolean {
    val payload = response.getJSONObject("payload")
    val kind = payload.getString("kind")
    val message = payload.optString("message", "")
    val line = if (payload.has("line")) payload.getInt("line") else 0
    when (kind) {
        "ERROR" -> box.output.errorProxy(message, "code", line)
        "SUCCESS" -> if (message.trim() != "OK") box.output.proxy(message.replace("<json>", ""), true, line)
        else -> box.output.proxy(message, true, line)
    }
    return kind != "ERROR" && kind != "RUN-STOP"
}
