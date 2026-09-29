package afield.ai.core

import afield.Box
import afield.Runloop
import afield.notify
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType
import kotlin.reflect.typeOf

/**
 * # Box-authored AI tools
 *
 * A pleasant, idempotent Kotlin DSL that lets a Field box contribute tools to the
 * document's AI4 agents (both the in-app chat and external MCP agents). A tool is a
 * `suspend` block that runs with the box in scope, can span many frames, and has its
 * arguments/description/return value marshaled automatically.
 *
 * ```kotlin
 * val a = aiTool("rectsl.run") {
 *     describe("Compile & run RectSL in this document's window, capture PNGs")
 *     val source by param<String>("RectSL program text")
 *     val times  by param<List<Double>>("capture times (s)", default = listOf(0.0, 1.0))
 *     action {                       // suspend; box in scope; may call frame()/delay()
 *         val d = afield.rectsl.RectAnimationOracle.compile(source())
 *         if (!d.ok) return@action toolError(d.diagnostics)
 *         "compiled ${source().length} chars, would capture at ${times()}"
 *     }
 * }
 * // Test it yourself, same marshaling the agent uses — positional & type-coerced:
 * start { println(a("wall 1 1 ; rect 0 0 1 1 fill", listOf(0.0, 0.5))) }
 * ```
 *
 * The handle [a] is directly, `suspend`-invocable from box code — positional
 * (`a(src, times)`, coerced by declared param order) or named (`a.call("source" to src)`)
 * — so you can exercise a tool without going through an agent. See [BoxTool].
 *
 * Registration is a side effect of running box code: re-running a box replaces its tools
 * by name (idempotent); a re-run that no longer registers one, or retiring the box, drops it —
 * see [DocumentToolRegistry] for the lifecycle.
 */
fun Box.aiTool(name: String, build: BoxToolBuilder.() -> Unit): BoxTool {
    require(name.isNotBlank()) { "aiTool name must not be blank." }
    val builder = BoxToolBuilder(this, name).apply(build)
    // NB: `error(...)` here would resolve to Box.error (Unit), not kotlin.error (Nothing) —
    // the Box receiver shadows it — so throw explicitly to get a Nothing branch + smart-cast.
    val body = builder.body
        ?: throw IllegalStateException("aiTool(\"$name\") requires an action { } block.")
    val tool = BoxTool(
        name = name,
        description = builder.description,
        boxId = this.id,
        box = this,
        params = builder.params.toList(),
        body = body,
    )
    DocumentToolRegistry.register(documentKeyFor(this), tool)
    runCatching { notify("Registered AI tool '$name'", "AI tools") }
    return tool
}

/** The document-registry key for a box (full document path; blank for unsaved docs). */
internal fun documentKeyFor(box: Box): String =
    runCatching { box.inside.filename }.getOrNull().orEmpty()

// -- Builder ---------------------------------------------------------------------

/** Sentinel distinguishing "no default supplied" from an explicit `default = null`. */
@PublishedApi
internal object NoDefault

class BoxToolBuilder internal constructor(
    val box: Box,
    val name: String,
) {
    internal var description: String = ""
    internal val params = mutableListOf<BoxToolArg<*>>()
    internal var body: (suspend BoxToolScope.() -> Any?)? = null

    /** Human-readable description shown to the agent. */
    fun describe(text: String) {
        description = text
    }

    /**
     * Declare a parameter. Use with `by` so the argument name is taken from the property:
     * `val source by param<String>("RectSL program text")`. The JSON type is derived from
     * `T` (reusing the same emitter as the static tool schemas). Supply `default = …` to
     * make the argument optional.
     */
    inline fun <reified T> param(description: String = "", default: Any? = NoDefault): PendingParam<T> =
        PendingParam(this, typeOf<T>(), description, default)

    /** The tool body. Runs on the runloop with the box in scope; may call `frame()`/`delay()`. */
    fun action(body: suspend BoxToolScope.() -> Any?) {
        this.body = body
    }

    internal fun register(arg: BoxToolArg<*>) {
        require(params.none { it.name == arg.name }) {
            "Duplicate parameter '${arg.name}' in aiTool(\"$name\")."
        }
        params += arg
    }
}

/** Intermediate returned by [BoxToolBuilder.param]; `provideDelegate` captures the property name. */
class PendingParam<T> @PublishedApi internal constructor(
    private val builder: BoxToolBuilder,
    private val kType: KType,
    private val description: String,
    private val default: Any?,
) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): BoxToolArg<T> {
        @Suppress("UNCHECKED_CAST")
        val arg = BoxToolArg<T>(
            name = property.name,
            kType = kType,
            description = description,
            hasDefault = default !== NoDefault,
            default = default as T?,
        )
        builder.register(arg)
        return arg
    }
}

/**
 * An argument accessor. Inside `action { }` call it like a function — `source()` — to read
 * the current invocation's (coerced) value. Reads a coroutine/thread-local set for the
 * duration of the call, so it is safe across `frame()` suspensions.
 */
class BoxToolArg<T> internal constructor(
    internal val name: String,
    internal val kType: KType,
    val description: String,
    internal val hasDefault: Boolean,
    internal val default: T?,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): BoxToolArg<T> = this

    @Suppress("UNCHECKED_CAST")
    operator fun invoke(): T {
        val args = currentToolArgs.get()
            ?: error("Tool argument '$name' read outside a tool invocation. Call the tool via the agent or a.call(...).")
        if (args.containsKey(name)) return args[name] as T
        if (hasDefault) return default as T
        error("Missing required tool argument '$name'.")
    }
}

// -- Execution scope -------------------------------------------------------------

/** Coroutine/thread-local carrying the current invocation's bound arguments. */
internal val currentToolArgs = ThreadLocal<Map<String, Any?>>()

/** Receiver of a tool's `action { }` body. */
class BoxToolScope internal constructor(
    val box: Box,
    /** The chat/MCP execution context when driven by an agent; null for direct box-code calls. */
    val ctx: ToolExecutionContext?,
) {
    val thisBox: Box get() = box

    /** Advance one runloop frame (yields the continuation to resume next frame). */
    suspend fun frame() {
        yield()
    }

    /** Wait [ms] real milliseconds, then settle onto the next frame. */
    suspend fun delay(ms: Long) {
        coroutineDelay(ms)
        yield()
    }

    /** Return a structured error to the agent (also usable as a normal return value). */
    fun toolError(vararg messages: String): BoxToolError = BoxToolError(messages.toList())

    fun toolError(messages: List<String>): BoxToolError = BoxToolError(messages)

    /**
     * Return one or more images to the conversation. Return the result from `action { }`;
     * the chat loop attaches the images as visible content and lets the model inspect them.
     * Each entry must be a `data:image/...;base64,...` URL (see [afield.util.PngEncoder] or a
     * captured JPEG). The base64 payload is kept out of the tool's JSON output (only [note]
     * and count are serialized) so it does not bloat the transcript.
     */
    fun images(dataUrls: List<String>, note: String = ""): BoxToolImages =
        BoxToolImages(note = note, count = dataUrls.size, imageDataUrls = dataUrls)

    fun image(dataUrl: String, note: String = ""): BoxToolImages = images(listOf(dataUrl), note)
}

/** A structured tool error, serialized back to the agent. */
data class BoxToolError(val messages: List<String>, val error: Boolean = true)

/**
 * Image(s) produced by a tool, injected into the conversation as visible content by the chat
 * loop (mirrors the shader-reload visual-check path). The base64 URLs are [JsonIgnore]d so the
 * tool's JSON function-output stays small; only [note] and [count] are serialized.
 */
data class BoxToolImages(
    val note: String = "",
    val count: Int = 0,
    @get:JsonIgnore val imageDataUrls: List<String> = emptyList()
) : CarriesToolImages {
    /** Carried by both bridges and the MCP backdoor through the shared [CarriesToolImages] path. */
    @get:JsonIgnore
    override val toolImages: List<ToolImage>
        get() = imageDataUrls.mapIndexed { i, url ->
            ToolImage(url, if (imageDataUrls.size > 1) "${note.ifBlank { "tool image" }} (${i + 1}/${imageDataUrls.size})" else note)
        }
}

// -- The tool --------------------------------------------------------------------

class BoxTool internal constructor(
    val name: String,
    val description: String,
    val boxId: String,
    internal val box: Box,
    internal val params: List<BoxToolArg<*>>,
    internal val body: suspend BoxToolScope.() -> Any?,
) {
    /** JSON-Schema for the parameters (kotlinx form), reusing the static-tool emitter. */
    val parametersSchema: JsonObject by lazy { buildParamsSchema(params) }

    /** Build the OpenAI SDK function-tool descriptor from [parametersSchema]. */
    fun toFunctionTool(): FunctionTool {
        val paramsBuilder = FunctionTool.Parameters.builder()
        // Iterate the kotlinx schema object directly (it is a Map<String, JsonElement>), converting
        // each top-level entry (type/properties/required) to a JsonValue via its JSON text.
        parametersSchema.forEach { (key, element) ->
            paramsBuilder.putAdditionalProperty(key, JsonValue.fromJsonNode(aiToolMapper.readTree(element.toString())))
        }
        val builder = FunctionTool.builder()
            .name(name)
            .parameters(paramsBuilder.build())
            // Not strict: our schema does not guarantee additionalProperties:false + all-required.
            .strict(false)
        if (description.isNotBlank()) builder.description(description)
        return builder.build()
    }

    /** Core execution: bind+coerce [argsJson], then run the body. `suspend`. */
    suspend fun call(argsJson: String, ctx: ToolExecutionContext? = null): Any =
        runBody(bindFromJson(argsJson), ctx)

    /**
     * Terse positional call from box code: `a(src, listOf(0.0))`. Values are coerced by
     * declared parameter order and arity-checked. `suspend` — invoke from a coroutine
     * (e.g. inside `start { }` / `frames { }`).
     */
    suspend operator fun invoke(vararg positional: Any?): Any =
        runBody(bindFromPositional(positional.toList()), null)

    /** Named call from box code: `a.call("source" to src)`. `suspend`. */
    suspend fun call(vararg named: Pair<String, Any?>): Any =
        runBody(bindFromNodes(named.toMap().mapValues { aiToolMapper.valueToTree<JsonNode>(it.value) }), null)

    /** Bridge for the synchronous agent tool loop (runs off the runloop, on an IO thread). */
    internal fun runBlockingFromToolLoop(ctx: ToolExecutionContext, argsJson: String): Any =
        runBlocking { call(argsJson, ctx) }

    private suspend fun runBody(bound: Map<String, Any?>, ctx: ToolExecutionContext?): Any {
        val scope = BoxToolScope(box, ctx)
        val result = withContext(
            Runloop.runLoop.main +
                currentToolArgs.asContextElement(bound) +
                Box.boxContextElement(box)
        ) {
            body(scope)
        }
        return when (result) {
            null, Unit -> mapOf("ok" to true)
            else -> result
        }
    }

    // --- argument binding & coercion ---

    private fun bindFromJson(argsJson: String): Map<String, Any?> {
        val root: JsonNode = aiToolMapper.readTree(argsJson.ifBlank { "{}" })
        return bindFromNodes(params.associate { it.name to root.get(it.name) })
    }

    private fun bindFromPositional(values: List<Any?>): Map<String, Any?> {
        require(values.size <= params.size) {
            "Tool '$name' takes ${params.size} argument(s); got ${values.size}."
        }
        val nodes = LinkedHashMap<String, JsonNode?>()
        params.forEachIndexed { index, param ->
            if (index < values.size) nodes[param.name] = aiToolMapper.valueToTree<JsonNode>(values[index])
        }
        return bindFromNodes(nodes)
    }

    private fun bindFromNodes(provided: Map<String, JsonNode?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        params.forEach { param ->
            val node = provided[param.name]
            out[param.name] = when {
                node == null || node.isNull -> when {
                    param.hasDefault -> param.default
                    param.kType.isMarkedNullable -> null
                    else -> throw IllegalArgumentException(
                        "Missing required argument '${param.name}' for tool '$name'."
                    )
                }
                else -> try {
                    aiToolMapper.convertValue(node, aiToolMapper.typeFactory.constructType(param.kType.javaType))
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Argument '${param.name}' for tool '$name' could not be coerced to ${param.kType}: ${e.message}"
                    )
                }
            }
        }
        return out
    }
}

internal fun buildParamsSchema(params: List<BoxToolArg<*>>): JsonObject {
    val required = mutableListOf<String>()
    val properties = buildJsonObject {
        params.forEach { param ->
            put(param.name, ToolSchema.propertySchema(param.kType, param.description.ifBlank { null }))
            if (!param.hasDefault && !param.kType.isMarkedNullable) required += param.name
        }
    }
    return buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

// -- Per-document registry -------------------------------------------------------

/**
 * Document-scoped registry of box-authored tools. A tool registered by any box in a
 * document is visible to every agent anchored in that document — the in-app AI4 chat and
 * external MCP users alike. Keyed by document path; tools remember their owning box id so
 * [clearBox] can drop them on box disposal.
 *
 * **Lifecycle.** Each registration is an [afield.Ownership] in the [afield.LifecycleLedger]
 * (kind [AI_TOOL_KIND]), owned by the registering run's [afield.RunScope] as
 * [afield.ReleasePolicy.ACCUMULATING] — the same policy as a slider callback, and for the same
 * reasons. A re-run that registers the tool again replaces it through the slot, with no gap in
 * which an agent would see it missing; a re-run that no longer registers it drops it (the old
 * run's scope is superseded); a stop leaves it answering, as a stopped launch's slider does; and
 * retiring the box (delete, tombstone, document close, the `reset` action) releases it. Before the
 * ledger this was only a `clearBox` in `Box.disposeKi`, which kept a tool the code no longer
 * defined for as long as the engine lived.
 */
object DocumentToolRegistry {
    /** The [afield.LifecycleLedger] kind of a box-authored tool registration. */
    const val AI_TOOL_KIND = "ai-tool"

    private val byDocument = ConcurrentHashMap<String, ConcurrentHashMap<String, BoxTool>>()

    /** Register (upsert by name) — re-running a box replaces its prior tool of the same name. */
    fun register(documentKey: String, tool: BoxTool) {
        val tools = byDocument.computeIfAbsent(documentKey) { ConcurrentHashMap() }
        tools[tool.name] = tool
        afield.LifecycleLedger.claim(
            AI_TOOL_KIND,
            "$documentKey:${tool.name}",
            slot = afield.LedgerSlot(tools, tool.name),
            owner = tool.box,
            scope = afield.RunScope.currentFor(tool.box),
            live = { tools[tool.name] === tool },
            policy = afield.ReleasePolicy.ACCUMULATING,
            release = { tools.remove(tool.name, tool) },
        )
    }

    /** All tools for a document, sorted by name (stable for schemas/tests). */
    fun toolsFor(documentKey: String): List<BoxTool> =
        byDocument[documentKey]?.values?.sortedBy { it.name } ?: emptyList()

    fun get(documentKey: String, name: String): BoxTool? = byDocument[documentKey]?.get(name)

    /** Drop every tool owned by [boxId] (call on box dispose/stop). */
    fun clearBox(documentKey: String, boxId: String) {
        byDocument[documentKey]?.entries?.removeIf { it.value.boxId == boxId }
    }

    /** Drop tools owned by [box] across whatever document it belongs to. */
    fun clearBox(box: Box) {
        clearBox(documentKeyFor(box), box.id)
    }

    fun remove(documentKey: String, name: String) {
        byDocument[documentKey]?.remove(name)
    }

    fun clearDocument(documentKey: String) {
        byDocument.remove(documentKey)
    }
}
