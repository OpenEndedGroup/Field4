package afield.ai.tools

import afield.ai.diagnostics.LogDispatcher
import afield.ai.diagnostics.log
import afield.document.graph.Box
import afield.runtime.lifecycle.Runloop
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType
import kotlin.reflect.typeOf

/**
 * # Box-authored AI tools
 *
 * A box contributes tools to its document's AI agents with `aiTool(name) { }`, an idempotent
 * Kotlin DSL that serves both the in-app chat and external MCP agents. A tool is a
 * `suspend` block that runs with the box in scope, can span many frames, and has its
 * arguments/description/return value marshaled automatically.
 *
 * ```kotlin
 * val a = aiTool("rectsl.run") {
 *     describe("Compile & run RectSL in this document's window, capture PNGs")
 *     val source by param<String>("RectSL program text")
 *     val times  by param<List<Double>>("capture times (s)", default = listOf(0.0, 1.0))
 *     action {                       // suspend; box in scope; may call frame()/delay()
 *         val d = afield.lang.rectsl.RectAnimationOracle.compile(source())
 *         if (!d.ok) return@action toolError(d.diagnostics)
 *         "compiled ${source().length} chars, would capture at ${times()}"
 *     }
 * }
 * // Test it yourself, same marshaling the agent uses — positional & type-coerced:
 * start { println(a("wall 1 1 ; rect 0 0 1 1 fill", listOf(0.0, 0.5))) }
 * ```
 *
 * The returned handle is directly `suspend`-invocable from box code — positional
 * (`a(src, times)`, coerced by declared param order) or named (`a.call("source" to src)`)
 * — so you can exercise a tool without going through an agent. See [BoxTool].
 *
 * Registration is a side effect of running box code: re-running a box replaces its tools
 * by name (idempotent); a re-run that no longer registers one, or retiring the box, drops it —
 * see [DocumentToolRegistry] for the lifecycle.
 */

/** Registers (or replaces) the tool [name] owned by this box and returns its [BoxTool] handle. */
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
        tests = builder.tests.toList(),
        body = body,
        meta = ToolMeta(
            namespace = builder.namespace ?: name.substringBefore('.', "doc").ifBlank { "doc" },
            exposure = builder.exposure,
            effects = builder.effects ?: setOf(ToolEffect.UNDECLARED),
        ),
        sourceRevision = afield.runtime.execution.sourceRevision((getPropertyRaw("code") as? String).orEmpty()).take(16),
    )
    DocumentToolRegistry.register(documentKeyFor(this), tool)
    LogDispatcher.log("info,ai.tools", "Registered AI tool '$name'")
    return tool
}

/**
 * [BoxTool.wireName]: a tool name the model providers accept (`^[A-Za-z0-9_-]{1,64}$`). A name that is too
 * requires an alias carries a readable prefix and a hash of the complete authored name.
 * A dotted name and its underscore spelling remain distinct. Registration still refuses any
 * collision with a built-in or another tool's transport identity.
 */
internal fun wireToolName(name: String): String = boxToolWireName(name)

/**
 * Built-in tool names a box tool may not take: core's, and whatever is registered through
 * [AgentToolExtensions] when the box registers. A box tool with one of these names would be shadowed
 * (built-ins win), so the model would call the built-in thinking it was the box's.
 */
internal object BuiltInToolNames {
    private val core: Set<String> by lazy { runCatching { ToolRegistry().also { registerBuiltInAgentTools(it) }.handlerNames() }.getOrDefault(emptySet()) }
    fun contains(name: String): Boolean = name in core || AgentToolExtensions.registeredNames().contains(name)
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
    internal val tests = mutableListOf<BoxToolTest>()
    internal var body: (suspend BoxToolScope.() -> Any?)? = null
    internal var namespace: String? = null
    internal var exposure: ToolExposure = ToolExposure.DIRECT
    internal var effects: Set<ToolEffect>? = null

    /** Human-readable description shown to the agent. */
    fun describe(text: String) {
        description = text
    }

    /** The catalogue namespace; defaults to the part of the name before its first `.` (`rectsl.run` → `rectsl`), else `doc`. */
    fun namespace(name: String) {
        namespace = name
    }

    /**
     * How the model meets this tool ([ToolExposure]): DIRECT (the default) sends its schema with every request;
     * DEFERRED lists it by name and sends the schema only once the model asks; PROGRAM leaves it to tool programs.
     */
    fun exposure(exposure: ToolExposure) {
        this.exposure = exposure
    }

    /**
     * What calling it does ([ToolEffect]); `effects()` with nothing says "none". Undeclared by default. A tool
     * declaring MUTATE_DOCUMENT is refused on a read-only document without its action running.
     */
    fun effects(vararg effects: ToolEffect) {
        this.effects = effects.toSet()
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

    /** Attach an explicit, executable example to this tool. Tests run only when requested.
     * Supply ordinary arguments; [verify] must throw when the returned result is wrong.
     * A test may call a mutating tool: run those tests in a disposable project.
     */
    fun test(name: String, arguments: Map<String, Any?> = emptyMap(), verify: (Any) -> Unit) {
        require(name.isNotBlank() && tests.none { it.name == name }) { "A tool test needs a unique name" }
        tests += BoxToolTest(name, aiToolMapper.writeValueAsString(arguments), verify)
    }

    /**
     * Attach an example that must reject its arguments with a specific diagnostic.
     * [messageContains] must occur in the thrown exception message or structured tool error.
     * An unexpected success or unrelated error fails the test; cancellation always propagates.
     * Like [test], this invokes the real tool and should use a disposable project for mutations.
     */
    fun testFailure(name: String, arguments: Map<String, Any?> = emptyMap(), messageContains: String) {
        require(name.isNotBlank() && tests.none { it.name == name }) { "A tool test needs a unique name" }
        require(messageContains.isNotBlank()) { "Name the diagnostic an expected failure must contain" }
        tests += BoxToolTest(name, aiToolMapper.writeValueAsString(arguments), messageContains)
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
     * Each entry must be a `data:image/...;base64,...` URL (see [afield.media.image.util.PngEncoder] or a
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

// Transport names are bounded ASCII identifiers. Keep authored names (including namespaces)
// in Field/MCP, and use a stable alias only on transports with stricter naming rules.
internal fun boxToolWireName(name: String): String {
    if (name.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && !name.startsWith("_field_")) return name
    val readable = name.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-') it else '_' }.joinToString("").take(32)
    val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    return "_field_${readable}_$hash"
}

// -- The tool --------------------------------------------------------------------

class BoxTool internal constructor(
    val name: String,
    val description: String,
    val boxId: String,
    internal val box: Box,
    internal val params: List<BoxToolArg<*>>,
    internal val tests: List<BoxToolTest> = emptyList(),
    internal val body: suspend BoxToolScope.() -> Any?,
    val meta: ToolMeta = ToolMeta(namespace = name.substringBefore('.', "doc").ifBlank { "doc" }),
    /** A hash of the registering box's code when it registered this, for capability checkpoints. */
    val sourceRevision: String = "",
) {
    /** Exact provider source at registration; a later source edit cannot relabel old evidence. */
    val source: String = box.getPropertyRaw("code") as? String ?: ""
    val sourceHash: String = ToolPool.hash(source)
    /** Local diagnostic records, shared by chat, MCP and direct Kotlin calls. */
    val diagnostics: ToolDiagnostics = ToolDiagnostics(this)

    /** JSON-Schema for the parameters (kotlinx form), reusing the static-tool emitter. */
    val parametersSchema: JsonObject by lazy { buildParamsSchema(params) }

    val wireName: String get() = boxToolWireName(name)

    /** Build the SDK descriptor, using a portable alias when the authored name needs one. */
    fun toFunctionTool(): FunctionTool {
        val paramsBuilder = FunctionTool.Parameters.builder()
        // Iterate the kotlinx schema object directly (it is a Map<String, JsonElement>), converting
        // each top-level entry (type/properties/required) to a JsonValue via its JSON text.
        parametersSchema.forEach { (key, element) ->
            paramsBuilder.putAdditionalProperty(key, JsonValue.fromJsonNode(aiToolMapper.readTree(element.toString())))
        }
        val builder = FunctionTool.builder()
            .name(wireName)
            .parameters(paramsBuilder.build())
            // Optional/defaulted parameters mean this is not an all-required strict schema.
            .strict(false)
        val transportDescription = if (wireName != name) "Field tool: $name. $description" else description
        if (transportDescription.isNotBlank()) builder.description(transportDescription)
        return builder.build()
    }

    /** Core execution: bind+coerce [argsJson], then run the body. `suspend`. */
    suspend fun call(argsJson: String, ctx: ToolExecutionContext? = null): Any =
        diagnostics.record(argsJson, if (ctx == null) "kotlin" else "agent") { runBody(bindFromJson(argsJson), ctx) }

    /**
     * Terse positional call from box code: `a(src, listOf(0.0))`. Values are coerced by
     * declared parameter order and arity-checked. `suspend` — invoke from a coroutine
     * (e.g. inside `start { }` / `frames { }`).
     */
    suspend operator fun invoke(vararg positional: Any?): Any =
        diagnostics.record(aiToolMapper.writeValueAsString(positional.toList()), "kotlin") { runBody(bindFromPositional(positional.toList()), null) }

    /** Named call from box code: `a.call("source" to src)`. `suspend`. */
    suspend fun call(vararg named: Pair<String, Any?>): Any =
        call(aiToolMapper.writeValueAsString(named.toMap()))

    /** Bridge for the synchronous agent tool loop (runs off the runloop, on an IO thread). */
    internal fun runBlockingFromToolLoop(ctx: ToolExecutionContext, argsJson: String): Any =
        runBlocking { call(argsJson, ctx) }

    private suspend fun runBody(bound: Map<String, Any?>, ctx: ToolExecutionContext?): Any {
        checkToolRunloopDispatch()
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

    /** Run the attached examples through the same binding/execution path as AI4. */
    suspend fun test(): List<ToolTestOutcome> {
        checkToolRunloopDispatch()
        return tests.map { example ->
        evaluateToolTest(example, { call(example.arguments) }) { result ->
            withContext(Runloop.runLoop.main + Box.boxContextElement(box)) { example.verify(result) }
        }
        }.also { diagnostics.recordTests(it) }
    }

    // --- argument binding & coercion ---

    private fun bindFromJson(argsJson: String): Map<String, Any?> {
        val root: JsonNode = aiToolMapper.readTree(argsJson.ifBlank { "{}" })
        require(root.isObject) { "Arguments for tool '$name' must be a JSON object." }
        val allowed = params.map { it.name }.toSet()
        val unknown = root.fieldNames().asSequence().filter { it !in allowed }.toList()
        require(unknown.isEmpty()) {
            "Unknown argument(s) ${unknown.joinToString()} for tool '$name'. " +
                "Allowed arguments: ${allowed.joinToString().ifEmpty { "none" }}."
        }
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
        put("additionalProperties", false)
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
 * **Lifecycle.** Each registration is an [afield.runtime.lifecycle.Ownership] in the [afield.runtime.lifecycle.LifecycleLedger]
 * (kind [AI_TOOL_KIND]), owned by the registering run's [afield.runtime.lifecycle.RunScope] as
 * [afield.runtime.lifecycle.ReleasePolicy.ACCUMULATING] — the same policy as a slider callback, and for the same
 * reasons. A re-run that registers the tool again replaces it through the slot, with no gap in
 * which an agent would see it missing; a re-run that no longer registers it drops it (the old
 * run's scope is superseded); a stop leaves it answering, as a stopped launch's slider does; and
 * retiring the box (delete, tombstone, document close, the `reset` action) releases it. Before the
 * ledger this was only a `clearBox` in `Box.disposeKi`, which kept a tool the code no longer
 * defined for as long as the engine lived.
 */
object DocumentToolRegistry {
    /** The [afield.runtime.lifecycle.LifecycleLedger] kind of a box-authored tool registration. */
    const val AI_TOOL_KIND = "ai-tool"

    private val byDocument = ConcurrentHashMap<String, ConcurrentHashMap<String, BoxTool>>()

    /**
     * Register (upsert by name) — re-running a box replaces its prior tool of the same name.
     *
     * A model sees a tool by its [BoxTool.wireName], so two tools whose names differ but whose wire names
     * agree would be one tool to the model and whichever the registry found first to Field. The second is
     * refused, with both names in the message; a tool from the same box replaces the one it collides with,
     * since that is a box renaming its own tool between runs. A wire name a built-in tool already has is
     * refused too.
     */
    fun register(documentKey: String, tool: BoxTool) {
        val tools = byDocument.computeIfAbsent(documentKey) { ConcurrentHashMap() }
        require(!BuiltInToolNames.contains(tool.name) && !BuiltInToolNames.contains(tool.wireName)) {
            "aiTool(\"${tool.name}\") has the name of a built-in tool; choose another name."
        }
        synchronized(tools) {
            val existing = tools[tool.name]
            require(existing == null || existing.boxId == tool.boxId) {
                "aiTool(\"${tool.name}\") is already owned by box ${existing!!.box.name.ifBlank { existing.boxId }}; choose another name."
            }
            tools.values.filter { it.name != tool.name && it.wireName == tool.wireName }.forEach { other ->
                require(other.boxId == tool.boxId) {
                    "aiTool(\"${tool.name}\") would reach the model as ${tool.wireName}, the same name as " +
                        "aiTool(\"${other.name}\") in box ${other.box.name.ifBlank { other.boxId }}; rename one of them."
                }
                tools.remove(other.name, other)
            }
            tools[tool.name] = tool
        }
        afield.runtime.lifecycle.LifecycleLedger.claim(
            AI_TOOL_KIND,
            "$documentKey:${tool.name}",
            slot = afield.runtime.lifecycle.LedgerSlot(tools, tool.name),
            owner = tool.box,
            scope = afield.runtime.lifecycle.RunScope.currentFor(tool.box),
            live = { tools[tool.name] === tool },
            policy = afield.runtime.lifecycle.ReleasePolicy.ACCUMULATING,
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
