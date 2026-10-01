package afield.ai.core

/**
 * # What a tool is, besides its schema: where it belongs, how the model meets it, what it touches
 *
 * Until 2026-10-02 the registry asked one question of a tool — is it available in this conversation? —
 * and every tool that said yes sent its whole schema on every request. Pi (earendil-works/pi) asks a
 * second: *how should the model discover it?* That is [ToolExposure]. A third, also Pi's (its MCP-style
 * hints), is what calling it does: [ToolEffect]. Field uses effects for three things rather than for
 * confirmation dialogs — a central refusal of document mutations on a read-only document, eval checks
 * (`effects-absent:FILESYSTEM_WRITE`), and the catalogue line that tells a model which calls are cheap
 * looks and which change things.
 *
 * Metadata comes from [AgentToolMeta] on the request class, from `aiTool { namespace(); exposure();
 * effects() }` for a box-authored tool, or from `AgentToolExtensions.register(meta = ...)`. A class with
 * none gets [ToolMeta.DEFAULT]: namespace `core`, DIRECT, [ToolEffect.UNDECLARED] — undeclared rather
 * than "no effects", so an eval asserting an effect is absent fails on a tool that never said.
 */
enum class ToolExposure {
    /** Schema sent with every request; callable directly and from programs. */
    DIRECT,

    /**
     * Callable from a tool program, listed by name in the catalogue, schema not sent — for tools only
     * worth calling in bulk. Falls back to DIRECT when the conversation has no tool programs.
     */
    PROGRAM,

    /**
     * Listed by name in the catalogue (marked `*`); the schema is sent once the model asks for it with
     * `DescribeToolsRequest`, for the rest of the conversation. For tools most conversations never call.
     */
    DEFERRED,

    /** Never shown to a model, nor over MCP; callable only by Field itself and the backdoor. */
    HIDDEN,
}

enum class ToolEffect {
    READ_DOCUMENT,
    MUTATE_DOCUMENT,
    EXECUTE_CODE,
    /** Starts, stops or rebinds something live: a launched box, a shader pipeline. */
    CONTROL_RUNTIME,
    CAPTURE_IMAGE,
    GPU,
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    NETWORK,
    /** The agent's own state: scratchpad, checklist, which tool schemas are loaded. */
    AGENT_STATE,
    /** The tool never said. Distinct from an empty set, which means "no effects". */
    UNDECLARED,
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AgentToolMeta(
    val namespace: String,
    val exposure: ToolExposure = ToolExposure.DIRECT,
    val effects: Array<ToolEffect> = [ToolEffect.UNDECLARED],
)

data class ToolMeta(
    val namespace: String = "core",
    val exposure: ToolExposure = ToolExposure.DIRECT,
    val effects: Set<ToolEffect> = setOf(ToolEffect.UNDECLARED),
) {
    val mutates: Boolean get() = ToolEffect.MUTATE_DOCUMENT in effects

    /** Only looks: every effect is a read or a picture. What MCP calls `readOnlyHint`. */
    val readOnly: Boolean
        get() = ToolEffect.UNDECLARED !in effects &&
            effects.all { it == ToolEffect.READ_DOCUMENT || it == ToolEffect.FILESYSTEM_READ || it == ToolEffect.CAPTURE_IMAGE }

    companion object {
        val DEFAULT = ToolMeta()

        fun of(cls: Class<*>): ToolMeta =
            cls.getAnnotation(AgentToolMeta::class.java)?.let { ToolMeta(it.namespace, it.exposure, it.effects.toSet()) } ?: DEFAULT
    }
}

/** Who is calling: the model directly, a tool program, or Field's backdoor / MCP face. */
enum class CallOrigin { MODEL, PROGRAM, BACKDOOR }

/** Which descriptor list [ToolRegistry.describe] builds: what a model is shown, or what MCP clients are. */
enum class ToolFace { MODEL, MCP, PROGRAM }

@com.fasterxml.jackson.annotation.JsonClassDescription(
    "Load the full schemas of catalogue tools marked * (deferred): give a namespace or names. Returns each tool's " +
        "description, input schema and effects, and makes them callable as ordinary tools for the rest of this conversation."
)
@AgentToolMeta("core", effects = [ToolEffect.AGENT_STATE])
data class DescribeToolsRequest(
    @com.fasterxml.jackson.annotation.JsonPropertyDescription("A namespace from the tool catalogue, such as `macros`; blank to use `names` instead.")
    val namespace: String = "",
    @com.fasterxml.jackson.annotation.JsonPropertyDescription("Tool names from the catalogue, such as MacroRunRequest.")
    val names: List<String> = emptyList(),
)

data class DescribeToolsResult(
    val success: Boolean,
    val message: String,
    val tools: List<DescribedTool>,
) : SummarisesForToolLog {
    override fun toolLogSummary(): String = "success=$success loaded=${tools.joinToString(",") { it.name }}"
}

data class DescribedTool(val name: String, val namespace: String, val description: String, val effects: List<String>, val inputSchema: String)
