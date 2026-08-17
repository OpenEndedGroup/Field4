package afield.ai

import afield.ai.core.AgentToolExtensions
import afield.ai.core.CarriesToolImages
import afield.ai.core.LogDispatcher
import afield.ai.core.LogSink
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolImage
import afield.ai.core.ToolRegistry
import afield.ai.core.registerBuiltInAgentTools
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first tests of `AgentToolExtensions` — the seam every tool set outside `afield.ai.core` comes
 * in through, which until tonight had one call site in the whole tree and it was gitignored.
 *
 * # Why this file exists at all
 *
 * `AgentToolExtensions` is the load-bearing part of a document that the author uses daily: a box
 * registers a cluster of tools, the author opens AI Chat, and the model can drive that cluster. The
 * mechanism is public and carefully documented. It had no test, no public consumer, and no docs page,
 * so nothing in the build would have noticed it breaking, and nothing told a reader it was there.
 *
 * # What is asserted, and why each one
 *
 * Everything here goes through the **public** face — `register`, `isAvailable`, `availableToolNames`,
 * `describe`, `execute`, `unregister` — because that is the whole surface an extension author has.
 * Where the test reaches for `internal` (`ToolRegistry`, `registerBuiltInAgentTools`) it is because the
 * question is specifically *"does this reach the agent"*, which cannot be asked from outside.
 *
 *  - **The schema.** A request class is the tool's schema, and the model never sees anything else. The
 *    exact JSON is asserted, including the `required` list — because a field silently becoming optional
 *    is a change to what the model believes it may omit.
 *  - **The gate.** A gate is what decides whether a conversation can see a tool set at all, and both its
 *    states are load-bearing: closed means "not in the schema list", not "in the list and fails".
 *  - **`execute` with JSON.** The same deserialization the model's tool call takes, so a request class
 *    whose defaults or enums do not survive Jackson fails here rather than in a live conversation.
 *  - **Re-registration.** The documented promise a live-coded box depends on.
 *  - **The live-coding reach.** See [liveCodingReachesARegistryBuiltBeforeTheRegistration] — this is the
 *    part of the KDoc's claim that turned out to be false, and is now true.
 *
 * Nothing here needs an API key, a network, a GPU or a runloop. `AgentToolExtensions` is a process-wide
 * singleton, so every test cleans up after itself in [tearDown]; a leaked registration would show up as
 * a different test's `availableToolNames` count being wrong, which is a miserable failure to read.
 */
class AgentToolExtensionsTest {

    // ------------------------------------------------------------------ fixtures

    enum class NightlyRounding { NEAREST, DOWN, UP }

    @JsonClassDescription("Measures a span and reports it, for the extension-point tests.")
    data class NightlyMeasure(
        @JsonPropertyDescription("Where the span starts, in metres.")
        val from: Double,
        @JsonPropertyDescription("Where the span ends, in metres.")
        val to: Double = 1.0,
        @JsonPropertyDescription("How to round the reported length.")
        val rounding: NightlyRounding = NightlyRounding.NEAREST,
        @JsonPropertyDescription("Free-text labels to echo back.")
        val labels: List<String> = emptyList(),
    )

    data class NightlyMeasureResult(
        val length: Double,
        val rounding: String,
        val labels: List<String>,
        val handlerGeneration: String = "first",
    ) : SummarisesForToolLog {
        override fun toolLogSummary(): String =
            "length=$length rounding=$rounding labels=${labels.size} gen=$handlerGeneration"
    }

    /** A second tool, so ordering and per-tool gating have something to be wrong about. */
    @JsonClassDescription("A second extension tool that exists only to be counted.")
    data class NightlySecond(val unused: Int = 0)

    /** Registered late on purpose — the live-coding case. */
    @JsonClassDescription("Registered after the registry was built.")
    data class NightlyLate(val note: String = "")

    data class NightlyLateResult(val note: String, val generation: String)

    /**
     * Two *different* classes with the same simple name — a stand-in for the same box being re-run.
     *
     * A live REPL recompiles a box into a fresh classloader, so the second run's `MyRequest::class.java`
     * is a different `Class` object from the first run's even though every visible thing about it is
     * identical. Two nested classes in two objects reproduce that exactly, and without any classloader
     * machinery in a unit test.
     */
    object GenerationOne {
        @JsonClassDescription("A tool registered by the first run of a box.")
        data class NightlyReloaded(val note: String = "")
    }

    object GenerationTwo {
        @JsonClassDescription("A tool registered by the second run of the same box.")
        data class NightlyReloaded(val note: String = "")
    }

    /** An extension whose request class collides with a core built-in by simple name. */
    object ClashingCluster {
        @JsonClassDescription("An extension tool that wants a name core already uses.")
        data class Commentary(val text: String = "")
    }

    private val ctx = ToolExecutionContext()
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** The gate every fixture tool shares, so a test can open and close it by assignment. */
    private var gateOpen = true

    private fun registerMeasure(generation: String = "first") {
        AgentToolExtensions.register(NightlyMeasure::class.java, available = { gateOpen }) { _, request ->
            val raw = request.to - request.from
            NightlyMeasureResult(
                length = when (request.rounding) {
                    NightlyRounding.NEAREST -> Math.round(raw).toDouble()
                    NightlyRounding.DOWN -> Math.floor(raw)
                    NightlyRounding.UP -> Math.ceil(raw)
                },
                rounding = request.rounding.name,
                labels = request.labels,
                handlerGeneration = generation,
            )
        }
    }

    @AfterTest
    fun tearDown() {
        gateOpen = true
        AgentToolExtensions.unregister(NightlyMeasure::class.java)
        AgentToolExtensions.unregister(NightlySecond::class.java)
        AgentToolExtensions.unregister(NightlyLate::class.java)
        AgentToolExtensions.unregister("NightlyReloaded")
        AgentToolExtensions.unregister("Commentary")
        AgentToolExtensions.unregister("NightlyFragile")
    }

    // ------------------------------------------------------------------ the schema

    /**
     * The schema a model is shown is derived from the request class and nothing else, so this asserts
     * the whole object rather than poking at one field. The four properties cover the four shapes that
     * behave differently in `ToolSchema`: a required primitive, an optional primitive, an enum (which
     * becomes `type: string` plus an `enum` list, *not* an object) and a `List<String>`.
     */
    @Test
    fun theSchemaAModelSeesIsDerivedFromTheRequestClass() {
        registerMeasure()

        val descriptors = AgentToolExtensions.describe(ctx)
        val measure = descriptors.map { Json.parseToJsonElement(it).jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "NightlyMeasure" }

        assertEquals(
            "Measures a span and reports it, for the extension-point tests.",
            measure["description"]!!.jsonPrimitive.content,
            "the @JsonClassDescription is the tool description the model reads",
        )

        val schema = measure["inputSchema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)

        val properties = schema["properties"]!!.jsonObject
        assertEquals(
            setOf("from", "to", "rounding", "labels"),
            properties.keys,
            "every primary-constructor parameter becomes a property, and nothing else does",
        )

        assertEquals("number", properties.type("from"))
        assertEquals("Where the span starts, in metres.", properties.description("from"))
        assertEquals("number", properties.type("to"))

        assertEquals("string", properties.type("rounding"), "an enum is a string with an enum list")
        assertEquals(
            listOf("NEAREST", "DOWN", "UP"),
            properties["rounding"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
            "in declaration order, which is the order the model will read them in",
        )
        assertEquals("How to round the reported length.", properties.description("rounding"))

        assertEquals("array", properties.type("labels"))
        assertEquals(
            "string",
            properties["labels"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )

        assertEquals(
            listOf("from"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "only the parameter with no default is required — the other three the model may omit",
        )
    }

    /**
     * `unused: Int = 0` is `ToolSchema`'s conventional "this tool takes no arguments" marker, and it is
     * dropped from the schema rather than shown. A tool that appears to take a meaningless integer is a
     * tool a model will pass a meaningless integer to.
     */
    @Test
    fun theUnusedMarkerIsNotShownToTheModel() {
        AgentToolExtensions.register(NightlySecond::class.java) { _, _ -> mapOf("ok" to true) }

        val second = AgentToolExtensions.describe(ctx)
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "NightlySecond" }

        val schema = second["inputSchema"]!!.jsonObject
        assertEquals(emptySet(), schema["properties"]!!.jsonObject.keys, "`unused` is filtered out")
        assertFalse("required" in schema, "and with nothing required, the key is absent, not empty")
    }

    // ------------------------------------------------------------------ the gate

    /**
     * A closed gate removes the tool from every listing *and* refuses to run it. Both halves matter:
     * a tool that is listed but throws teaches a model that the tool set is broken, and a tool that is
     * unlisted but runnable is a tool the model can only reach by guessing.
     */
    @Test
    fun theGateOpensAndClosesEverySurfaceTogether() {
        registerMeasure()
        AgentToolExtensions.register(NightlySecond::class.java) { _, _ -> mapOf("ok" to true) }

        gateOpen = true
        assertTrue(AgentToolExtensions.isAvailable(ctx, "NightlyMeasure"))
        assertEquals(
            listOf("NightlyMeasure", "NightlySecond"),
            AgentToolExtensions.availableToolNames(ctx).filter { it.startsWith("Nightly") },
            "sorted, and both open",
        )
        assertEquals(
            1,
            AgentToolExtensions.describe(ctx).count { it.contains("\"NightlyMeasure\"") },
            "described exactly once",
        )

        gateOpen = false
        assertFalse(AgentToolExtensions.isAvailable(ctx, "NightlyMeasure"), "the gate closed")
        assertEquals(
            listOf("NightlySecond"),
            AgentToolExtensions.availableToolNames(ctx).filter { it.startsWith("Nightly") },
            "the ungated tool is unaffected — gates are per registration, not per extension",
        )
        assertEquals(
            0,
            AgentToolExtensions.describe(ctx).count { it.contains("\"NightlyMeasure\"") },
            "and it is gone from the descriptors, not merely marked",
        )

        assertTrue(
            AgentToolExtensions.registeredToolNames().contains("NightlyMeasure"),
            "registeredToolNames ignores the gate on purpose — it is the diagnostic readout",
        )
    }

    // ------------------------------------------------------------------ execute

    /**
     * The path a model's tool call actually takes: a name and a JSON string. Asserts the three things
     * that only break at this boundary — omitted fields falling back to Kotlin defaults, an enum
     * arriving by name, and a JSON array becoming a `List<String>`.
     */
    @Test
    fun executeTakesTheSameJsonAModelWouldEmit() {
        registerMeasure()

        val full = AgentToolExtensions.execute(
            ctx, "NightlyMeasure",
            """{"from": 1.0, "to": 4.4, "rounding": "UP", "labels": ["kitchen", "hall"]}""",
        ) as NightlyMeasureResult
        assertEquals(4.0, full.length, "ceil(4.4 - 1.0) = ceil(3.4) = 4")
        assertEquals("UP", full.rounding)
        assertEquals(listOf("kitchen", "hall"), full.labels)

        val minimal = AgentToolExtensions.execute(ctx, "NightlyMeasure", """{"from": 0.0}""")
            as NightlyMeasureResult
        assertEquals(1.0, minimal.length, "to defaults to 1.0 and NEAREST rounds 1.0 to 1")
        assertEquals("NEAREST", minimal.rounding, "the enum default survives Jackson")
        assertEquals(emptyList(), minimal.labels)

        assertEquals(
            "length=1.0 rounding=NEAREST labels=0 gen=first",
            minimal.toolLogSummary(),
            "SummarisesForToolLog is what the tool I/O log records instead of a bare class name",
        )
    }

    /** Two error paths, both of which must be loud: a closed gate and a name nobody registered. */
    @Test
    fun executeRefusesAClosedGateAndAnUnknownName() {
        registerMeasure()

        gateOpen = false
        val closed = assertFailsWith<IllegalStateException> {
            AgentToolExtensions.execute(ctx, "NightlyMeasure", "{\"from\": 0.0}")
        }
        assertEquals(
            "Extension tool 'NightlyMeasure' is not available in this context.",
            closed.message,
        )

        gateOpen = true
        val unknown = assertFailsWith<IllegalArgumentException> {
            AgentToolExtensions.execute(ctx, "NoSuchNightlyTool", "{}")
        }
        assertEquals("No extension tool registered for: NoSuchNightlyTool", unknown.message)
    }

    /** Blank arguments are the empty object, because that is what a no-argument tool call looks like. */
    @Test
    fun blankArgumentsAreTheEmptyObject() {
        AgentToolExtensions.register(NightlyLate::class.java) { _, request ->
            NightlyLateResult(request.note, "only")
        }
        assertEquals("", (AgentToolExtensions.execute(ctx, "NightlyLate", "") as NightlyLateResult).note)
        assertEquals("", (AgentToolExtensions.execute(ctx, "NightlyLate", "{}") as NightlyLateResult).note)
    }

    // ------------------------------------------------------------------ re-registration

    /**
     * The documented promise a live-coded box leans on: running the box twice replaces the tool rather
     * than adding a second copy, and the *second* handler is the one that runs.
     */
    @Test
    fun reRegisteringReplacesRatherThanDuplicates() {
        registerMeasure(generation = "first")
        assertEquals(1, AgentToolExtensions.registeredToolNames().count { it == "NightlyMeasure" })

        registerMeasure(generation = "second")
        assertEquals(
            1,
            AgentToolExtensions.registeredToolNames().count { it == "NightlyMeasure" },
            "still exactly one registration, not two",
        )
        assertEquals(
            1,
            AgentToolExtensions.describe(ctx).count { it.contains("\"NightlyMeasure\"") },
            "and the model is shown the schema once, not twice",
        )
        assertEquals(
            "second",
            (AgentToolExtensions.execute(ctx, "NightlyMeasure", "{\"from\": 0.0}") as NightlyMeasureResult)
                .handlerGeneration,
            "the replacement handler is the live one",
        )

        AgentToolExtensions.unregister(NightlyMeasure::class.java)
        assertFalse("NightlyMeasure" in AgentToolExtensions.registeredToolNames())
        assertFailsWith<IllegalArgumentException> {
            AgentToolExtensions.execute(ctx, "NightlyMeasure", "{\"from\": 0.0}")
        }
    }

    /**
     * The same promise, under the condition it was written for and did not hold in: a **re-run of a
     * live-coded box**, which hands over a fresh `Class` object every time.
     *
     * `register` used to de-duplicate with `it.toolClass == toolClass`, i.e. by `Class` *identity*. That
     * is true for a `register` call from a compiled package, which is why nothing noticed — but a box
     * recompiles into a new classloader on every run, so the second run's request class is a different
     * object and the first run's registration survived beside it. Two entries, one name. `describe`
     * then showed the model the tool twice, and `execute`'s `firstOrNull { simpleName == name }` picked
     * the *older* one, so the agent kept running the code the author had just replaced.
     *
     * De-duplication is by simple name now, which is the key everything else in the tool path already
     * uses: `ToolRegistry.handlers`, `execute`, `isAvailable`, and the tool name a model sends back.
     */
    @Test
    fun aReRunOfALiveCodedBoxReplacesItsToolRatherThanShadowingIt() {
        @Suppress("UNCHECKED_CAST")
        val first = GenerationOne.NightlyReloaded::class.java as Class<Any>
        @Suppress("UNCHECKED_CAST")
        val second = GenerationTwo.NightlyReloaded::class.java as Class<Any>
        assertEquals(first.simpleName, second.simpleName, "the fixture is only meaningful if these match")
        assertTrue(first != second, "and only if they are genuinely different Class objects")

        AgentToolExtensions.register(first) { _, _ -> NightlyLateResult("", "first") }
        AgentToolExtensions.register(second) { _, _ -> NightlyLateResult("", "second") }

        assertEquals(
            1,
            AgentToolExtensions.registeredToolNames().count { it == "NightlyReloaded" },
            "one tool, however many times the box has been re-run",
        )
        assertEquals(
            1,
            AgentToolExtensions.describe(ctx).count { it.contains("\"NightlyReloaded\"") },
            "and the model is offered it once",
        )
        assertTrue(
            AgentToolExtensions.describe(ctx)
                .single { it.contains("\"NightlyReloaded\"") }
                .contains("second run of the same box"),
            "the description shown is the current run's, not the first run's",
        )
        assertEquals(
            "second",
            (AgentToolExtensions.execute(ctx, "NightlyReloaded", "{}") as NightlyLateResult).generation,
            "and the handler that runs is the current run's",
        )

        // One unregister is enough, because there is only one registration to remove.
        AgentToolExtensions.unregister(second)
        assertFalse("NightlyReloaded" in AgentToolExtensions.registeredToolNames())
    }

    /**
     * Core's own tool names are reserved. An extension whose request class happens to be called
     * `Commentary` or `EditFileRequest` must not be able to take over the built-in of that name — the
     * agent's instructions are written around those tools behaving as documented.
     *
     * Before the registries were subscribed this was accidentally true in one direction only:
     * `applyTo` ran first inside `registerBuiltInAgentTools`, so a built-in registered afterwards
     * overwrote a colliding extension. Now that a registration can arrive *after* the built-ins, the
     * precedence has to be stated rather than implied by call order — and stated loudly, because a
     * silently ignored registration is the same failure this whole night was about.
     */
    @Test
    fun anExtensionCannotTakeOverACoreToolName() {
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools)

        val clash = assertFailsWith<IllegalArgumentException> {
            AgentToolExtensions.register(ClashingCluster.Commentary::class.java) { _, _ ->
                mapOf("hijacked" to true)
            }
        }
        assertTrue(
            "Commentary" in clash.message.orEmpty(),
            "the message must name the tool that is already taken; got: ${clash.message}",
        )

        // And the built-in is untouched: still registered, still core's handler.
        assertTrue(tools.isAvailable(ctx, "Commentary"))
        assertEquals(
            mapOf("ok" to true),
            tools.executeByName(ctx, "Commentary", """{"text": "hello"}"""),
            "core's Commentary handler, not the extension's",
        )
    }

    // ------------------------------------------------- the semantics of a live registration
    //
    // Making a registration reach an open conversation is the point of this branch, and it is also a
    // new sharp edge: a half-edited box that registers a broken handler now reaches a live model
    // instead of being quietly ignored. These four tests are the deliberate answer to that, written
    // down so the next person changing this cannot reverse it by accident.
    //
    // The answer is that immediate liveness is right, for a reason that is easy to miss. The
    // alternative was never "the broken handler is ignored" — it was "the STALE handler runs", the
    // one compiled from source that is no longer on screen. A model calling the code you are looking
    // at and getting an error is recoverable in one keystroke; a model calling code you deleted an
    // hour ago, and succeeding, is not even diagnosable. Live coding's bargain is that what runs is
    // what you see, and a tool registration is not special.
    //
    // What that bargain requires in exchange is the three properties below: a throwing handler must
    // not damage anything, re-running must fix it, and a *failed* registration must not destroy the
    // working tool it was trying to replace.

    @JsonClassDescription("A tool whose handler is about to be replaced by a broken one.")
    data class NightlyFragile(val value: Int = 0)

    /**
     * A handler that throws is an ordinary tool error, and the *next* run of the box repairs it.
     *
     * This is the recovery path that makes immediate liveness safe, so it is asserted end to end
     * through a real registry: break it, observe a legible failure that names the tool, fix it, and
     * observe the fix land in the same conversation with nothing restarted.
     */
    @Test
    fun aBrokenHandlerIsRecoverableByRunningTheBoxAgain() {
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools)

        AgentToolExtensions.register(NightlyFragile::class.java) { _, request ->
            mapOf("doubled" to request.value * 2)
        }
        assertEquals(mapOf("doubled" to 8), tools.executeByName(ctx, "NightlyFragile", """{"value": 4}"""))

        // The author saves the box mid-edit and it now throws.
        AgentToolExtensions.register(NightlyFragile::class.java) { _, _ ->
            throw IllegalStateException("half-written")
        }
        val boom = assertFailsWith<IllegalStateException> {
            tools.executeByName(ctx, "NightlyFragile", """{"value": 4}""")
        }
        assertEquals("half-written", boom.message, "the author's own exception, not one we invented")

        // Crucially, nothing else is damaged: the tool is still listed, and every other tool works.
        assertTrue(
            tools.isAvailable(ctx, "NightlyFragile"),
            "a throwing handler must not deregister the tool — that would make the failure look like " +
                "a disappearance, which is far harder to diagnose",
        )
        assertTrue(tools.isAvailable(ctx, "Commentary"), "and must not disturb core's tools")

        // The author fixes the box and runs it again. Same conversation, no restart.
        AgentToolExtensions.register(NightlyFragile::class.java) { _, request ->
            mapOf("doubled" to request.value * 2, "fixed" to true)
        }
        assertEquals(
            mapOf("doubled" to 8, "fixed" to true),
            tools.executeByName(ctx, "NightlyFragile", """{"value": 4}"""),
            "one re-run is the whole recovery procedure",
        )
    }

    /**
     * A registration that is *refused* leaves the previous one working.
     *
     * `register` validates before it mutates, so the failure is atomic. Without that, an author who
     * renamed a request class into a collision would lose the tool they already had — a second,
     * invisible failure on top of the one they can see.
     */
    @Test
    fun aRefusedRegistrationDoesNotDestroyTheWorkingOne() {
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools)

        AgentToolExtensions.register(NightlyFragile::class.java) { _, _ -> mapOf("alive" to true) }
        assertTrue(tools.isAvailable(ctx, "NightlyFragile"))

        assertFailsWith<IllegalArgumentException> {
            AgentToolExtensions.register(ClashingCluster.Commentary::class.java) { _, _ -> mapOf<String, Any>() }
        }

        assertEquals(
            mapOf("alive" to true),
            tools.executeByName(ctx, "NightlyFragile", "{}"),
            "the unrelated tool the author already had is untouched by a refused registration",
        )
        assertEquals(
            1,
            AgentToolExtensions.registeredToolNames().count { it == "NightlyFragile" },
            "and the registration list did not grow or shrink",
        )
    }

    /** A core-side request class that collides with [ClashingCluster.Commentary] by simple name. */
    object CoreSideCluster {
        @JsonClassDescription("Stands in for core's built-in of this name.")
        data class Commentary(val text: String = "")
    }

    /**
     * Core displacing an extension's tool name is reported rather than done in silence.
     *
     * This is the collision an extension author actually hits, and finding it meant correcting where the
     * bug was. Every other test here — [anExtensionCannotTakeOverACoreToolName] included — builds a
     * `ToolRegistry` *first*, so `AgentToolExtensions.register` can see the clash and throws. The
     * ordering the docs recommend is the opposite one: *"call it once, from wherever the owning code is
     * first touched — a personal package's `init`, a `Box` helper, an installer"*, i.e. before any chat
     * exists. In that ordering nothing is bound, `register` has no registry to find the clash in, and its
     * check is vacuous.
     *
     * What happens next is not a refusal at all, which is why looking for one was the wrong place to
     * look. `applyTo` runs at the top of `registerBuiltInAgentTools` against an **empty** registry, so
     * `registerExtension` succeeds; core's own `register` then overwrites the name a few calls later and
     * takes ownership. Documented, deliberate, correct — and completely silent, leaving exactly the state
     * `registerExtension`'s KDoc says a caller must never allow: *"a tool that is registered on their side
     * and invisible to every model"*.
     *
     * Asserted at the `ToolRegistry` level rather than through `AgentToolExtensions`, because that object
     * is a process-wide singleton whose bound-registry set is polluted by every other test in this class —
     * so "nothing is bound yet" is not a state a test in this JVM can reach. The displacement itself is,
     * and it is where the fix lives.
     */
    @Test
    fun coreDisplacingAnExtensionsToolNameIsReported() {
        val tools = ToolRegistry()
        assertTrue(
            tools.registerExtension(ClashingCluster.Commentary::class.java, { true }) { _, _ ->
                mapOf("extension" to true)
            },
            "an empty registry accepts the extension — this is the step that makes the silence possible, " +
                "so it is asserted rather than assumed",
        )
        assertEquals(
            mapOf("extension" to true),
            tools.executeByName(ctx, "Commentary", """{"text": "hello"}"""),
            "and for a moment the extension really is the Commentary tool",
        )

        val captured = mutableListOf<String>()
        val sink = LogSink { event -> captured += "${event.channel}|${event.message}" }
        LogDispatcher.addSink(sink)
        try {
            tools.register(CoreSideCluster.Commentary::class.java) { _, _ -> mapOf("core" to true) }
        } finally {
            LogDispatcher.removeSink(sink)
        }

        // Core wins, which is right and is not what changed.
        assertEquals(
            mapOf("core" to true),
            tools.executeByName(ctx, "Commentary", """{"text": "hello"}"""),
            "core's names are reserved; the extension must not survive the collision",
        )

        val reported = captured.filter { "Commentary" in it && "displaced" in it }
        assertTrue(
            reported.isNotEmpty(),
            "the displacement must be reported somewhere the author can see it — otherwise they have a " +
                "tool registered on their side that no model will ever call, and nothing to tell them " +
                "why. Captured ${captured.size} log line(s):\n" +
                captured.joinToString("\n").take(2000),
        )
        assertTrue(
            reported.any { it.startsWith("warning") },
            "and on a warning channel, not buried in an info stream: $reported",
        )
    }

    /**
     * Displacing a name **core already owned** says nothing, so the report above cannot become noise.
     *
     * Core re-registering its own tool is ordinary (`registerBuiltInAgentTools` runs once per bridge, and
     * a process builds several), and a warning on every one of those would be worse than the silence it
     * replaced — a channel nobody reads is how the original failure stayed invisible for so long.
     */
    @Test
    fun coreReplacingItsOwnToolNameIsNotReported() {
        val tools = ToolRegistry()
        tools.register(CoreSideCluster.Commentary::class.java) { _, _ -> mapOf("core" to 1) }

        val captured = mutableListOf<String>()
        val sink = LogSink { event -> captured += "${event.channel}|${event.message}" }
        LogDispatcher.addSink(sink)
        try {
            tools.register(CoreSideCluster.Commentary::class.java) { _, _ -> mapOf("core" to 2) }
        } finally {
            LogDispatcher.removeSink(sink)
        }

        assertTrue(
            captured.none { "displaced" in it },
            "core replacing its own registration is not a collision and must stay quiet: $captured",
        )
    }

    /**
     * Retiring a tool that was never registered is a no-op.
     *
     * A box's teardown runs unconditionally — it does not know whether its `register` ever succeeded,
     * and on the first run of a freshly opened document it did not. Making `unregister` throw would
     * mean every author writing a guard around it.
     */
    @Test
    fun retiringSomethingThatWasNeverThereIsHarmless() {
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools)

        AgentToolExtensions.unregister("NeverRegisteredAnywhere")
        AgentToolExtensions.unregister(NightlyFragile::class.java)

        // And it cannot be used to remove one of core's tools by name, which is the one way a no-op
        // unregister could have been dangerous.
        AgentToolExtensions.unregister("Commentary")
        assertTrue(
            tools.isAvailable(ctx, "Commentary"),
            "unregister is scoped to extension-owned names; core's Commentary survives",
        )
        assertEquals(
            mapOf("ok" to true),
            tools.executeByName(ctx, "Commentary", """{"text": "still here"}"""),
        )
    }

    /**
     * The gate is re-evaluated per call, not captured at registration — so a box can turn its own
     * tools off without re-registering, and turning them off really does close `execute`.
     */
    @Test
    fun theGateIsReReadOnEveryCallRatherThanSnapshotted() {
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools)

        var open = true
        AgentToolExtensions.register(NightlyFragile::class.java, available = { open }) { _, _ ->
            mapOf("ran" to true)
        }
        assertTrue(tools.isAvailable(ctx, "NightlyFragile"))

        open = false
        assertFalse(tools.isAvailable(ctx, "NightlyFragile"), "no re-registration was needed")
        assertFailsWith<IllegalStateException> { tools.executeByName(ctx, "NightlyFragile", "{}") }

        open = true
        assertEquals(mapOf("ran" to true), tools.executeByName(ctx, "NightlyFragile", "{}"))
    }

    // ------------------------------------------------------------------ reaching the agent

    /**
     * The claim in `AgentToolExtensions`' own KDoc that this file was written to check: that a tool
     * registered from **a document's own boxes** reaches the model.
     *
     * # What was actually true before 2026-08-11
     *
     * `applyTo` is called from `registerBuiltInAgentTools`, which is called from a bridge's
     * *constructor*, and `Ai4Installer` builds each bridge lazily on the first AI turn and then caches
     * it for the life of the window. `applyTo` **copied** the registrations into the `ToolRegistry`.
     *
     * So the claim held only in one order — run the box, *then* open the chat — and failed silently in
     * every other:
     *
     *  - a box run after the first AI turn registered a tool the model never saw;
     *  - and re-running a box, which the KDoc explicitly says is safe, swapped the registration in
     *    `AgentToolExtensions` while the agent kept executing the **previous** handler, from the
     *    previous REPL classloader, for the rest of the session. That is the dangerous one: it looks
     *    like it works.
     *
     * Neither produced an error anywhere. `AgentToolExtensions` now subscribes registries instead of
     * copying into them, and these two tests are what that means.
     */
    @Test
    fun liveCodingReachesARegistryBuiltBeforeTheRegistration() {
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools) // "the first AI turn of the session"

        assertFalse(tools.isAvailable(ctx, "NightlyLate"), "nothing registered it yet")

        // "the human runs a box, which registers a tool" — after the agent already exists.
        AgentToolExtensions.register(NightlyLate::class.java) { _, request ->
            NightlyLateResult(request.note, "first")
        }

        assertTrue(
            tools.isAvailable(ctx, "NightlyLate"),
            "a tool registered after the registry was built must still reach the agent",
        )
        assertEquals(
            "hello",
            (tools.executeByName(ctx, "NightlyLate", """{"note": "hello"}""") as NightlyLateResult).note,
        )
    }

    @Test
    fun reRunningTheBoxReplacesTheHandlerTheAgentWillCall() {
        AgentToolExtensions.register(NightlyLate::class.java) { _, request ->
            NightlyLateResult(request.note, "first")
        }
        val tools = ToolRegistry()
        registerBuiltInAgentTools(tools)
        assertEquals(
            "first",
            (tools.executeByName(ctx, "NightlyLate", "{}") as NightlyLateResult).generation,
        )

        // The box is edited and re-run. Same simple name, a brand-new Class object in a live REPL.
        AgentToolExtensions.register(NightlyLate::class.java) { _, request ->
            NightlyLateResult(request.note, "second")
        }

        assertEquals(
            "second",
            (tools.executeByName(ctx, "NightlyLate", "{}") as NightlyLateResult).generation,
            "the agent must call the handler the box has now, not the one it had at chat-open time",
        )

        // And retiring it takes it away from the agent too, rather than leaving a zombie.
        AgentToolExtensions.unregister(NightlyLate::class.java)
        assertFalse(tools.isAvailable(ctx, "NightlyLate"))
        assertFailsWith<IllegalArgumentException> { tools.executeByName(ctx, "NightlyLate", "{}") }
    }

    /**
     * Every registry that has ever been applied to is updated, not just the most recent one. Field
     * really does hold two at once — `Ai4Installer` keeps a separate `bridge` and `chatBridge`, so the
     * editor-location chat and the panel chat each own a `ToolRegistry` — and a fix that only tracked
     * the last one would leave one of the two chats stale, which is the hardest possible version of
     * this bug to report.
     */
    @Test
    fun bothOfTheBridgesFieldActuallyKeepsSeeTheUpdate() {
        val editorChat = ToolRegistry()
        val panelChat = ToolRegistry()
        registerBuiltInAgentTools(editorChat)
        registerBuiltInAgentTools(panelChat)

        AgentToolExtensions.register(NightlyLate::class.java) { _, request ->
            NightlyLateResult(request.note, "shared")
        }

        for ((label, tools) in listOf("editor" to editorChat, "panel" to panelChat)) {
            assertTrue(tools.isAvailable(ctx, "NightlyLate"), "$label chat should see the new tool")
            assertEquals(
                "shared",
                (tools.executeByName(ctx, "NightlyLate", "{}") as NightlyLateResult).generation,
                "$label chat should run the live handler",
            )
        }
    }

    /**
     * The MCP face carries extension tools too.
     *
     * `AgentToolExtensions`' KDoc has always promised "nothing extra to write for the OpenAI path or
     * the MCP face". The MCP face was the one surface where that was false: the agent backdoor's
     * `agent-ai-tools-list` / `agent-ai-tool-call` handlers read `Ai4BackdoorTools.registry`, which is
     * assembled by hand rather than by `registerBuiltInAgentTools`, and so had no route to
     * `AgentToolExtensions` at all. A cluster's tools were reachable from AI Chat and invisible to an
     * agent driving Field over the backdoor, with nothing anywhere saying so.
     *
     * Asserted through the registry the two handlers actually use, so this fails if that wiring is
     * removed — the handlers themselves need a live session, which a unit test has no business faking.
     */
    @Test
    fun theMcpBackdoorRegistryCarriesExtensionToolsToo() {
        val backdoor = afield.ai.core.Ai4BackdoorTools.registry

        AgentToolExtensions.register(NightlyLate::class.java) { _, request ->
            NightlyLateResult(request.note, "over-mcp")
        }

        assertTrue(
            backdoor.isAvailable(ctx, "NightlyLate"),
            "an extension tool must appear on the backdoor's tool list",
        )
        assertTrue(
            backdoor.describe(ctx).any { """"NightlyLate"""" in it.toString() },
            "and in the MCP-style descriptors, which is what field_mcp.py forwards",
        )
        assertEquals(
            "over-mcp",
            (backdoor.executeByName(ctx, "NightlyLate", """{"note": "x"}""") as NightlyLateResult).generation,
            "and must be callable through agent-ai-tool-call's dispatch",
        )

        AgentToolExtensions.unregister(NightlyLate::class.java)
        assertFalse(backdoor.isAvailable(ctx, "NightlyLate"), "and retiring it takes it off that face too")
    }

    /**
     * A tool result's images travel on [CarriesToolImages] and must stay **out** of the JSON the model
     * is sent as function output — a base64 PNG spent as text tokens is invisible cost that buys
     * nothing, because a model cannot see an image encoded in a JSON string.
     *
     * The interface annotates `toolImages` with `@get:JsonIgnore` so that an implementor gets this right
     * by inheriting it. This asserts the inheritance actually works through Jackson, which is the whole
     * reason it is an interface rather than a convention.
     */
    @Test
    fun carriedImagesAreExcludedFromTheJsonTheModelReads() {
        data class Pictured(val note: String) : CarriesToolImages {
            override val toolImages = listOf(ToolImage("data:image/png;base64,AAAA", "a caption"))
        }

        val json = mapper.writeValueAsString(Pictured("visible"))
        assertEquals("""{"note":"visible"}""", json, "only the note is serialised")
        assertFalse("AAAA" in json, "the base64 payload never reaches the function-output JSON")
        assertFalse("caption" in json)
    }

    // ------------------------------------------------------------------ helpers

    private fun JsonObject.type(name: String): String =
        this[name]!!.jsonObject["type"]!!.jsonPrimitive.content

    private fun JsonObject.description(name: String): String =
        this[name]!!.jsonObject["description"]!!.jsonPrimitive.content
}
