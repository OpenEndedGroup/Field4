package afield.ai.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How code outside core adds tools to the AI4 agents.
 *
 * # Why this exists
 *
 * Core used to name a domain cluster directly: `registerBuiltInAgentTools` called
 * `afield.rect.registerHouseTools(tools)`, and `OpenAiIdeCodexBridge` had a `when` branch on that
 * cluster's result type. Both worked, and both were the wrong shape — the AI plumbing should not know
 * what subject matters exist.
 *
 * The forcing function was moving that cluster into `src/personal`, which is a **separate compilation**:
 * the compiler will not let core reference it, so the reference had to be inverted. That is the right
 * outcome for a reason beyond the move. Any cluster — in core, in personal, or in a document's own boxes —
 * now adds tools the same way, and none of them require an edit to core to do it.
 *
 * # Registering
 *
 * ```kotlin
 * AgentToolExtensions.register(
 *     MyToolRequest::class.java,
 *     available = { ctx -> myThingExists() },
 * ) { _, request -> MyToolResult(...) }
 * ```
 *
 * Call it once, from wherever the owning code is first touched — a personal package's `init`, a
 * `Box` helper, an installer. Registration is idempotent per tool class: registering the same class
 * again replaces the handler rather than adding a second one, so a re-run of a live-coded box does not
 * accumulate duplicates.
 *
 * The request class is the tool's schema. Annotate it with [JsonClassDescription] and its fields with
 * `@JsonPropertyDescription`; the same reflection that describes a built-in tool describes this one, so
 * there is nothing extra to write for the OpenAI path or the MCP face.
 *
 * # What a tool result should implement
 *
 * Two optional interfaces, both in core so anything can implement them:
 *
 *  - [CarriesToolImages] — the result's pictures are appended to the conversation as images, which is
 *    how a geometry tool lets a model see what it just did.
 *  - [SummarisesForToolLog] — one line for the tool I/O log. Worth implementing: without it the log
 *    records only the class name, and the tool-use improvement loop reads that log.
 */
object AgentToolExtensions {

    /** One registered tool: its request class, its availability gate, and its handler. */
    class Registration<T : Any> internal constructor(
        val toolClass: Class<T>,
        val available: (ToolExecutionContext) -> Boolean,
        val handler: (ToolExecutionContext, T) -> Any,
    )

    private val registrations = CopyOnWriteArrayList<Registration<*>>()

    /**
     * Add a tool, replacing any previous registration of the same request class.
     *
     * [available] is asked on every turn, so it is the place to gate a cluster's tools on whether that
     * cluster has anything to work on. Seventeen tool schemas on every request is a real cost for a
     * conversation that is not about the subject.
     */
    fun <T : Any> register(
        toolClass: Class<T>,
        available: (ToolExecutionContext) -> Boolean = { true },
        handler: (ToolExecutionContext, T) -> Any,
    ) {
        registrations.removeAll { it.toolClass == toolClass }
        registrations.add(Registration(toolClass, available, handler))
    }

    /** Drop a tool. Mostly for tests, and for a live-coded registration being retired. */
    fun unregister(toolClass: Class<*>) {
        registrations.removeAll { it.toolClass == toolClass }
    }

    /** Every registered tool's request class, for diagnostics and for a status readout. */
    fun registeredToolNames(): List<String> = registrations.map { it.toolClass.simpleName }.sorted()

    /**
     * True if [name] is registered here and its gate passes under [ctx].
     *
     * Part of the public face because an extension author otherwise has no way to check that their gate does
     * what they think — core's own registry is `internal`, so a tool set living outside core cannot inspect
     * itself through it. That gap made the house tool set's own smoke untestable after the move.
     */
    fun isAvailable(ctx: ToolExecutionContext, name: String): Boolean =
        registrations.any { it.toolClass.simpleName == name && it.available(ctx) }

    /** The names of every extension tool available under [ctx], sorted. */
    fun availableToolNames(ctx: ToolExecutionContext): List<String> =
        registrations.filter { it.available(ctx) }.map { it.toolClass.simpleName }.sorted()

    /**
     * Runs an extension tool by name with JSON arguments, exactly as a model's tool call would.
     *
     * The same Jackson deserialization core uses, so a mistake in a request class's shape surfaces here
     * rather than only in a live conversation. Throws if the tool is unknown or its gate is closed —
     * silently returning nothing would make a closed gate look like a working tool.
     */
    fun execute(ctx: ToolExecutionContext, name: String, argsJson: String): Any {
        val registration = registrations.firstOrNull { it.toolClass.simpleName == name }
            ?: throw IllegalArgumentException("No extension tool registered for: $name")
        if (!registration.available(ctx)) {
            throw IllegalStateException("Extension tool '$name' is not available in this context.")
        }
        @Suppress("UNCHECKED_CAST")
        val typed = registration as Registration<Any>
        val args: Any = aiToolMapper.readValue(argsJson.ifBlank { "{}" }, typed.toolClass)
        return typed.handler(ctx, args)
    }

    /**
     * MCP-style descriptors for the extension tools available under [ctx] — `{name, description, inputSchema}`.
     *
     * Deliberately the same shape core's own registry emits, so an extension's tools look identical to a
     * built-in's from the outside and anything reading descriptors needs no special case for them.
     */
    fun describe(ctx: ToolExecutionContext): List<String> =
        registrations.filter { it.available(ctx) }
            .sortedBy { it.toolClass.simpleName }
            .map { registration ->
                buildJsonObject {
                    put("name", registration.toolClass.simpleName)
                    val description = registration.toolClass.kotlin
                        .annotations.filterIsInstance<JsonClassDescription>().firstOrNull()?.value
                    if (!description.isNullOrBlank()) put("description", description)
                    put("inputSchema", ToolSchema.forClass(registration.toolClass))
                }.toString()
            }

    /** Applies every registration to [tools]. Called by core once, while building the registry. */
    internal fun applyTo(tools: ToolRegistry) {
        registrations.forEach { registration ->
            @Suppress("UNCHECKED_CAST")
            val typed = registration as Registration<Any>
            tools.register(typed.toolClass, typed.available, typed.handler)
        }
    }
}

/**
 * A tool result that can describe itself in one line for the tool I/O log.
 *
 * The log is what `AiToolUseImprovementMonitor` reads, and a result that does not implement this is
 * recorded as bare `type=MyResult` — which cannot distinguish a turn where the model got what it needed
 * from one where it got an error and gave up. Say what mattered: whether it succeeded, how many issues
 * came back, the worst of them, and how many images went with it.
 *
 * Keep it short. This is one line in a log, not a report.
 */
interface SummarisesForToolLog {

    /** One line for the tool I/O log — see the interface docs for what belongs in it. */
    fun toolLogSummary(): String
}
