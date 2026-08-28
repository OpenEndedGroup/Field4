package afield.ai.core

import afield.*
import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.RelatedContextDiscovery
import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import afield.ai.searchWorkspaceSymbols
import afield.four.Renderer
import afield.four.util.Window
import afield.four.util.lastWindowToOpen
import afield.four.util.openWindowsSnapshot
import afield.logger.lprintln
import afield.macros.checkAtticAndExecute
import afield.macros.checkIntroductionAndExecute
import afield.macros.checkPreambleAndExecute
import afield.macros.gatherPreambleUpwards
import afield.stdlib.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic

private val ai4Scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun macroRunToolResult(
    documentPath: String,
    req: MacroRunRequest,
    targets: List<String>
): MacroRunResult {
    val result = OperationTranscript.runMacro(
        documentPath = documentPath,
        name = req.name,
        targetBoxes = targets,
        direction = req.direction,
        strict = req.strict
    )
    if (req.direction.equals("undo", ignoreCase = true) || result.errors.isEmpty()) {
        return MacroRunResult(
            success = result.success,
            message = result.message,
            name = result.name,
            direction = result.direction,
            targetBoxes = result.targetBoxes,
            applied = result.applied,
            skipped = result.skipped,
            errors = result.errors
        )
    }

    val undo = OperationTranscript.runMacro(
        documentPath = documentPath,
        name = req.name,
        targetBoxes = targets,
        direction = "undo",
        strict = false
    )
    val message = buildString {
        append(result.message)
        append(" Macro run produced errors, so Field automatically attempted undo. ")
        append(undo.message)
        append(" The agent should inspect the errors, explain what happened, and ask what to change before rerunning.")
    }
    notify(message, "AI4 macro")
    return MacroRunResult(
        success = false,
        message = message,
        name = result.name,
        direction = result.direction,
        targetBoxes = result.targetBoxes,
        applied = result.applied,
        skipped = result.skipped,
        errors = result.errors +
            undo.errors.map { "Undo error: $it" } +
            "Automatic undo attempted: ${undo.applied} applied, ${undo.skipped} skipped, ${undo.errors.size} error(s)."
    )
}

// --- Pure code-tool helpers (promoted to top level so both installAi4()'s chat
// handler and Ai4CodeToolProviders/the backdoor share one implementation). These
// close over nothing request-scoped; unqualified call sites inside installAi4()
// still resolve here after the nested copies are removed. ---

data class FileRef(val document: String, val uid: String, val property: String)

fun fileRefFor(docName: String, uid: String, property: String): String =
    "afield://$docName/$uid/$property"

fun boxLabel(target: Box): String = target.name.trim().ifBlank { target.id }

/**
 * Thrown when a box *name* an agent supplied matches more than one box.
 *
 * Box names are freely duplicated in a Field document — two boxes called `render` is an ordinary
 * thing for a person to have — so a name is a label, not an identifier. Only the uid is one. Every
 * tool that accepts "a box" accepts either, and until this existed the name path resolved a duplicate
 * with `firstOrNull`: the edit landed on whichever box the document's map happened to iterate first,
 * silently, with a success result. That is the worst available outcome, because the agent is then
 * told it changed the box it asked for.
 *
 * Refusing is safe to do by throwing because *both* dispatchers already turn an exception out of a
 * provider into a per-call tool error carrying [message], and neither aborts the conversation:
 * `agent-ai-tool-call` in `agent_backdoor.kt` replies `ok=false, error=<message>`, and the OpenAI
 * harness in [OpenAiIdeCodexBridge] feeds back `{error: true, message: <message>}`. So the agent
 * reads the count and the instruction, and retries with a uid.
 *
 * The wording is copied from `tools/field_mcp.py`'s `_resolve_box`, which has always refused here —
 * including its single quotes, which are what Python's `{token!r}` produces for an ordinary name — so
 * that the canonical MCP path and the in-app paths read identically to whoever hits it.
 */
class AmbiguousBoxNameException(val name: String, val matchCount: Int) : IllegalArgumentException(
    "$matchCount boxes are named '$name'; reference one by its uid instead."
)

/**
 * The single box in [matches], or `null` if there were none — refusing rather than choosing when
 * [name] matched more than one.
 *
 * Deduplicates by uid first, so a caller that assembled [matches] from overlapping collections (the
 * chat handler searches `root + boxes.values`) cannot report the same box twice as an ambiguity.
 *
 * `null` means "no such name", which every caller already has a path for. The refusal throws instead
 * of returning, because there is no honest value to return: see [AmbiguousBoxNameException].
 */
fun uniqueBoxOrRefuse(name: String, matches: List<Box>): Box? {
    val distinct = matches.distinctBy { it.id }
    if (distinct.size > 1) throw AmbiguousBoxNameException(name, distinct.size)
    return distinct.firstOrNull()
}

fun diagnosticToCompileDiagnostic(diagnostic: ScriptDiagnostic): CompileDiagnostic {
    val location = diagnostic.location
    return CompileDiagnostic(
        severity = diagnostic.severity.name,
        message = diagnostic.message,
        line = location?.start?.line ?: -1,
        column = location?.start?.col ?: -1,
        endLine = location?.end?.line ?: -1,
        endColumn = location?.end?.col ?: -1,
        code = diagnostic.code.toString()
    )
}

data class SetupSnapshot(
    val preamble: String,
    val preambleCache: String,
    val intro: String,
    val introCache: String,
    val attic: String,
    val atticCache: String
)

fun setupSnapshot(target: Box): SetupSnapshot = SetupSnapshot(
    preamble = target.getPropertyRaw("preamble", "") as? String ?: "",
    preambleCache = target.getPropertyRaw("_preamble", "") as? String ?: "",
    intro = target.getPropertyRaw("introduction", "") as? String ?: "",
    introCache = target.getPropertyRaw("_introduction", "") as? String ?: "",
    attic = target.getPropertyRaw("attic", "") as? String ?: "",
    atticCache = target.getPropertyRaw("_attic", "") as? String ?: ""
)

fun setupChanges(before: SetupSnapshot, after: SetupSnapshot): List<SetupPropertyChange> = listOf(
    SetupPropertyChange(
        property = "preamble",
        changed = before.preamble != after.preamble,
        cacheChanged = before.preambleCache != after.preambleCache,
        beforeChars = before.preamble.length,
        afterChars = after.preamble.length
    ),
    SetupPropertyChange(
        property = "intro",
        changed = before.intro != after.intro,
        cacheChanged = before.introCache != after.introCache,
        beforeChars = before.intro.length,
        afterChars = after.intro.length
    ),
    SetupPropertyChange(
        property = "attic",
        changed = before.attic != after.attic,
        cacheChanged = before.atticCache != after.atticCache,
        beforeChars = before.attic.length,
        afterChars = after.attic.length
    )
)

fun clipToolText(value: String, requestedMaxChars: Int): String {
    val maxChars = requestedMaxChars.coerceIn(64, 100_000)
    if (value.length <= maxChars) return value
    return value.take(maxChars) + "\n...<truncated ${value.length - maxChars} chars>"
}

fun safeString(value: Any?): String = runCatching { value.toString() }
    .getOrElse { throwable -> "<toString failed: ${throwable.message ?: throwable::class.java.simpleName}>" }

fun runtimeTypeName(value: Any?): String =
    value?.let { shortenType(it::class.qualifiedName ?: it::class.java.name) } ?: "null"

fun primitiveKind(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> "Boolean"
    is Byte -> "Byte"
    is Short -> "Short"
    is Int -> "Int"
    is Long -> "Long"
    is Float -> "Float"
    is Double -> "Double"
    is Char -> "Char"
    is String -> "String"
    else -> ""
}

fun primitiveToolValue(value: Any?): Any? = when (value) {
    is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is String -> value
    is Char -> value.toString()
    else -> null
}

/**
 * Box-scoped Kotlin code tools (CompileKotlin, EvaluateKotlin), extracted from the
 * ai-assist chat handler so the OpenAI harness and the MCP backdoor share one
 * implementation. Context is supplied via four closures; everything else resolves
 * to the promoted top-level helpers above. `refResolver` replaces the chat handler's
 * `exec`-coupled `requestedRefOrCurrent` — the backdoor passes an explicit-target
 * resolver with no "current box" fallback.
 */
class Ai4CodeToolProviders(
    private val docName: String,
    private val refResolver: (String, String) -> FileRef,
    private val boxByIdOrNull: (String) -> Box?,
    private val stagedOrCurrentSource: (Box, String, String?) -> String?,
) {
    val evaluateKotlinExpression: (EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult =
        evaluateKotlinExpression@{ req ->
            val expression = req.expression.trim()
            if (expression.isBlank()) {
                return@evaluateKotlinExpression EvaluateKotlinExpressionResult(
                    success = false,
                    target = req.target,
                    boxUid = "",
                    boxName = "",
                    expression = req.expression,
                    valueKind = "error",
                    message = "Expression is empty."
                )
            }

            val ref = refResolver(req.target, "code")
            if (ref.document != docName) {
                return@evaluateKotlinExpression EvaluateKotlinExpressionResult(
                    success = false,
                    target = req.target,
                    boxUid = ref.uid,
                    boxName = "",
                    expression = expression,
                    valueKind = "error",
                    message = "Unknown document '${ref.document}'."
                )
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@evaluateKotlinExpression EvaluateKotlinExpressionResult(
                    success = false,
                    target = req.target,
                    boxUid = ref.uid,
                    boxName = "",
                    expression = expression,
                    valueKind = "error",
                    message = "Unknown box '${ref.uid}'."
                )

            notify("Evaluating Kotlin expression for ${target.id}", "AI4")
            val diagnostics = mutableListOf<CompileDiagnostic>()
            var resultValue: ResultValue? = null

            runCatching {
                runBlocking {
                    Box.withBoxContext(target) {
                        val prepared = target.ki.compileSnippet(
                            expression,
                            implicits = listOf(target),
                            onCompileError = { list ->
                                diagnostics += list.map(::diagnosticToCompileDiagnostic)
                            }
                        )
                        if (prepared != null) {
                            resultValue = withContext(Runloop.runLoop.main) {
                                prepared.eval(
                                    updateContext = false,
                                    implicits = listOf(target),
                                    onEvalError = { list ->
                                        diagnostics += list.map(::diagnosticToCompileDiagnostic)
                                    },
                                    onEvalException = { throwable ->
                                        diagnostics += CompileDiagnostic(
                                            severity = "ERROR",
                                            message = throwable.message ?: throwable::class.java.name
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }.onFailure { throwable ->
                diagnostics += CompileDiagnostic(
                    severity = "ERROR",
                    message = throwable.message ?: throwable::class.java.name
                )
            }

            val targetRef = fileRefFor(docName, target.id, ref.property)
            when (val result = resultValue) {
                is ResultValue.Value -> {
                    val value = result.value
                    val kind = if (value == null) "null" else if (primitiveKind(value).isNotBlank()) "primitive" else "object"
                    val stringValue = clipToolText(safeString(value), req.maxChars)
                    val html = if (req.renderHtml) {
                        clipToolText(
                            runCatching { value.h }
                                .getOrElse { throwable ->
                                    "<pre>.h visualization failed: ${safeString(throwable.message ?: throwable::class.java.simpleName)}</pre>"
                                },
                            req.maxChars
                        )
                    } else ""
                    EvaluateKotlinExpressionResult(
                        success = true,
                        target = targetRef,
                        boxUid = target.id,
                        boxName = boxLabel(target),
                        expression = expression,
                        valueKind = kind,
                        type = runCatching { shortenType(result.type) }.getOrDefault(runtimeTypeName(value)),
                        runtimeType = runtimeTypeName(value),
                        primitiveValue = primitiveToolValue(value),
                        primitiveKind = primitiveKind(value),
                        stringValue = stringValue,
                        html = html,
                        diagnostics = diagnostics,
                        message = "Expression evaluated."
                    )
                }

                is ResultValue.Unit -> EvaluateKotlinExpressionResult(
                    success = true,
                    target = targetRef,
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    expression = expression,
                    valueKind = "unit",
                    type = "Unit",
                    runtimeType = "Unit",
                    stringValue = "Unit",
                    diagnostics = diagnostics,
                    message = "Expression evaluated to Unit."
                )

                is ResultValue.NotEvaluated -> EvaluateKotlinExpressionResult(
                    success = false,
                    target = targetRef,
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    expression = expression,
                    valueKind = "not_evaluated",
                    diagnostics = diagnostics,
                    message = "Expression was not evaluated."
                )

                is ResultValue.Error -> EvaluateKotlinExpressionResult(
                    success = false,
                    target = targetRef,
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    expression = expression,
                    valueKind = "error",
                    diagnostics = diagnostics,
                    message = result.error.message ?: result.error::class.java.name
                )

                null -> EvaluateKotlinExpressionResult(
                    success = false,
                    target = targetRef,
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    expression = expression,
                    valueKind = "error",
                    diagnostics = diagnostics,
                    message = if (diagnostics.isEmpty()) "Expression did not produce a result." else "Expression failed."
                )
            }
        }

    val compileKotlin: (CompileKotlinRequest) -> CompileKotlinResult = compileKotlin@{ req ->
        val ref = refResolver(req.file, "code")
        if (ref.document != docName) {
            return@compileKotlin CompileKotlinResult(
                success = false,
                file = req.file,
                message = "Unknown document '${ref.document}'."
            )
        }
        if (ref.property != "code") {
            return@compileKotlin CompileKotlinResult(
                success = false,
                file = fileRefFor(docName, ref.uid, ref.property),
                message = "CompileKotlin only supports the code property; got '${ref.property}'."
            )
        }
        val target = boxByIdOrNull(ref.uid)
            ?: return@compileKotlin CompileKotlinResult(
                success = false,
                file = fileRefFor(docName, ref.uid, ref.property),
                message = "Unknown box '${ref.uid}'."
            )
        val source = stagedOrCurrentSource(target, ref.property, req.source)
            ?: return@compileKotlin CompileKotlinResult(
                success = false,
                file = fileRefFor(docName, target.id, ref.property),
                message = "No Kotlin source is available for ${target.id}/${ref.property}."
            )

        notify("Compiling Kotlin for ${target.id}", "AI4")

        val diagnostics = mutableListOf<CompileDiagnostic>()
        val before = setupSnapshot(target)
        var snippetsCompiled = 0
        var failed = false

        runCatching {
            runBlocking {
                Box.withBoxContext(target) {
                    val lines = source.trimEnd().lines()
                    val breaks = lines.mapIndexedNotNull { index, line ->
                        if (line.trim().startsWith("//%-") || line.trim().startsWith("/*%-*/")) index else null
                    } + lines.size

                    var at = 0
                    var lineCounter = 0
                    for (breakIndex in breaks) {
                        val snippetSource = lines.subList(at, breakIndex).joinToString("\n")
                        val expanded = if (KotlinEngineConfig.offloadCompilation) {
                            withContext(target.ki.compilationDispatcher) { target.ki.applyMacros(target, snippetSource) }
                        } else {
                            target.ki.applyMacros(target, snippetSource)
                        }
                        val compiledSource = expanded.split("\n").joinToString("\n") { line ->
                            lineCounter++
                            line.replace("__line__", "${lineCounter - 1}")
                        }

                        if (req.runSetupBlocks) {
                            val setupCompileError: (List<ScriptDiagnostic>) -> Unit = { list ->
                                diagnostics += list.map(::diagnosticToCompileDiagnostic)
                            }
                            val setupEvalError: (List<ScriptDiagnostic>) -> Unit = { list ->
                                diagnostics += list.map(::diagnosticToCompileDiagnostic)
                            }
                            val setupEvalException: (Throwable) -> Unit = { throwable ->
                                diagnostics += CompileDiagnostic(
                                    severity = "ERROR",
                                    message = "Setup block exception: ${throwable.message ?: throwable::class.java.name}"
                                )
                            }
                            withContext(Runloop.runLoop.main) {
                                checkPreambleAndExecute(target, setupCompileError, setupEvalError, setupEvalException)
                                checkIntroductionAndExecute(target, setupCompileError, setupEvalError, setupEvalException)
                                checkAtticAndExecute(target, setupCompileError, setupEvalError, setupEvalException)
                            }
                        }

                        val prepared = target.ki.compileSnippet(
                            compiledSource,
                            implicits = listOf(target),
                            onCompileError = { list ->
                                diagnostics += list.map(::diagnosticToCompileDiagnostic)
                            }
                        )
                        if (prepared == null) {
                            failed = true
                            break
                        }
                        snippetsCompiled++
                        at = breakIndex
                    }
                }
            }
        }.onFailure { throwable ->
            failed = true
            diagnostics += CompileDiagnostic(
                severity = "ERROR",
                message = throwable.message ?: throwable::class.java.name
            )
        }

        val after = setupSnapshot(target)
        val hasHardDiagnostic = diagnostics.any { severity ->
            val normalized = severity.severity.uppercase()
            normalized != "DEBUG" && normalized != "INFO" && normalized != "WARNING"
        }
        val success = !failed && !hasHardDiagnostic
        val changed = setupChanges(before, after)
        val changedNames = changed.filter { it.changed || it.cacheChanged }.joinToString(", ") { it.property }
        val message = buildString {
            append(if (success) "Kotlin compiled successfully" else "Kotlin compile failed")
            append(" for ${target.id}; snippets=$snippetsCompiled")
            if (changedNames.isNotBlank()) append("; setup changed: $changedNames")
        }
        notify(message, "AI4")

        CompileKotlinResult(
            success = success,
            file = fileRefFor(docName, target.id, "code"),
            message = message,
            diagnostics = diagnostics,
            setupChanges = changed
        )
    }
}

private const val AI4_CONNECTED_RENDER_EXAMPLE_REF = "afield://ai4-examples/connected-compute-render"

private val AI4_CONNECTED_RENDER_EXAMPLE = """
# AI4 example: connected compute box and render box

When a user asks for both particle motion and visual appearance changes, inspect the graph instead of assuming one file owns everything.

Typical layout:
- Current box: Kotlin `code` creates `computeModuleHere`, `ComputeMesh`, ping-pong buffers, and `prop<FrameBufferGroup>(outputPositions) = outData`.
- Current box: `compute` shader updates particle positions or velocity. Speed changes usually belong here, or in Kotlin params such as `m.params`.
- Connected render box: Kotlin `code` creates a graphics `moduleHere`, `Mesh`, and reads the exported positions with `m.inData = { outputPositions }`.
- Connected render box: `fragment` shader sets `uFragColor`. Color, alpha, contrast, blue/orange palette, and particle edge treatment usually belong here.

Preamble clue:
- `Box.outputPositions by Inheritance...` means `outputPositions` is a graph-relevant Field property; inspect parents/children that mention `outputPositions`.
- Common inherited setup properties can be useful for understanding how the scene is wired, but are weaker graph-discovery clues than domain-specific names like `outputPositions`.
- `Mesh.inData by BoundFromStructure...`, `ComputeMesh.params by BoundFromStructure...`, `ModulePointer.someUniform by FromUniform...`, etc. are shader binding helpers. They explain how Kotlin talks to shader push constants/uniforms, but they are not by themselves evidence that the current box owns the value.

Good workflow:
1. Call `ListContents` early and look at both the current box and graph-connected boxes.
2. Edit the compute box for motion/speed.
3. Edit the connected render box's fragment shader for color/contrast.
4. After Kotlin edits, call `CompileKotlin` on the edited `code` ref and fix errors before finalizing.
5. After shader edits, call `ReloadShaders` for each affected shader or target box. Use `visualCheck=true` when screenshot context exists, inspect the post-reload screenshot, and keep editing/reloading if the visual result is not achieved.
""".trimIndent()

fun installAi4() {
    var bridge: LlmBridge? = null
    var helpers: IdeChatHelpers? = null
    var chatBridge: LlmBridge? = null
    var chatHelpers: IdeChatHelpers? = null

    // Pick the agent engine by the /welcome provider setting. Both implement LlmBridge,
    // so IdeChatHelpers and the thread caches are provider-agnostic downstream.
    fun createIdeBridge(uiLog: LogSink): LlmBridge = createLlmBridge(Ai4ModelDefaults.flagshipModel(), uiLog)
    val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatThreadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatHistoryByKey = ConcurrentHashMap<String, String>()
    val agentMemoryByKey = ConcurrentHashMap<String, AgentMemoryState>()

    fun langSpecForName(name: String): LanguageSpec = when {
        name.equals("Kotlin", ignoreCase = true) -> KOTLIN_SCRIPT
        name.equals(GLSL_VERTEX.name, ignoreCase = true) -> GLSL_VERTEX
        name.equals(GLSL_FRAGMENT.name, ignoreCase = true) -> GLSL_FRAGMENT
        name.equals(GLSL_MESH.name, ignoreCase = true) -> GLSL_MESH
        name.equals(GLSL_TASK.name, ignoreCase = true) -> GLSL_TASK
        name.equals(GLSL_COMPUTE.name, ignoreCase = true) -> GLSL_COMPUTE
        name.equals(SLANG_VERTEX.name, ignoreCase = true) -> SLANG_VERTEX
        name.equals(SLANG_FRAGMENT.name, ignoreCase = true) -> SLANG_FRAGMENT
        name.equals(SLANG_MESH.name, ignoreCase = true) -> SLANG_MESH
        name.equals(SLANG_TASK.name, ignoreCase = true) -> SLANG_TASK
        name.equals(SLANG_COMPUTE.name, ignoreCase = true) -> SLANG_COMPUTE
        name.startsWith("GLSL (ray", ignoreCase = true) -> GLSL_RAY_TRACING
        name.equals(GLSL_MISS.name, ignoreCase = true) -> GLSL_MISS
        name.equals(GLSL_CLOSEST_HIT.name, ignoreCase = true) -> GLSL_CLOSEST_HIT
        name.equals(GLSL_ANY_HIT.name, ignoreCase = true) -> GLSL_ANY_HIT
        name.equals(GLSL_INTERSECTION.name, ignoreCase = true) -> GLSL_INTERSECTION
        name.equals(GLSL_CALLABLE.name, ignoreCase = true) -> GLSL_CALLABLE
        name.startsWith("Slang (ray", ignoreCase = true) -> SLANG_RAY_TRACING
        name.equals(SLANG_MISS.name, ignoreCase = true) -> SLANG_MISS
        name.equals(SLANG_CLOSEST_HIT.name, ignoreCase = true) -> SLANG_CLOSEST_HIT
        name.equals(SLANG_ANY_HIT.name, ignoreCase = true) -> SLANG_ANY_HIT
        name.equals(SLANG_INTERSECTION.name, ignoreCase = true) -> SLANG_INTERSECTION
        name.equals(SLANG_CALLABLE.name, ignoreCase = true) -> SLANG_CALLABLE
        name.equals("nano", ignoreCase = true) -> NANO_ASM
        else -> LanguageSpec(
            name = name,
            wrap = { it.trimEnd() },
            unwrap = { it.trimEnd() }
        )
    }

    fun threadFor(fileKey: String, fileText: String): OpenAiIdeCodexBridge.ThreadState =
        threadByKey[fileKey] ?: bridge!!.startThread(fileKey, fileText).also {
            threadByKey[fileKey] = it
        }

    fun chatThreadFor(fileKey: String, fileText: String): OpenAiIdeCodexBridge.ThreadState =
        chatThreadByKey[fileKey] ?: chatBridge!!.startThread(
            fileKey,
            fileText,
            instructions = OpenAiIdeCodexBridge.defaultChatThreadInstructions
        ).also {
            chatThreadByKey[fileKey] = it
        }

    fun provisionalKey(entry: ProvisionalEntry): String = "${entry.uid}\u0000${entry.property}"

    fun provisionalDelta(
        before: Map<String, String>,
        after: List<ProvisionalEntry>
    ): List<ProvisionalEntry> =
        after.filter { entry -> before[provisionalKey(entry)] != entry.contents }

    fun markdownFence(text: String): String =
        text.replace("```", "`\u200b``")

    fun aiPanelMarkdownForArchive(panelMarkdown: String): String {
        val lines = panelMarkdown.replace("\r\n", "\n").replace("\r", "\n").lines()
        return buildString {
            lines.forEach { line ->
                when (line.trim()) {
                    "<<you>>" -> appendLine("## User")
                    "<<ai>>" -> appendLine("## Assistant")
                    "<<progress>>", "<<progress-open>>" -> appendLine("### Progress")
                    else -> appendLine(line)
                }
            }
        }.trim()
    }

    fun buildAiProposalArchiveMarkdown(
        docPath: String,
        fileKey: String,
        exec: AiAssist,
        responseId: String,
        panelMarkdown: String,
        acceptedEntries: List<ProvisionalEntry>,
        toolTranscript: List<OpenAiIdeCodexBridge.ToolTranscriptEntry>
    ): String = buildString {
        appendLine("# AI4 Proposal Archive")
        appendLine()
        appendLine("- Document: `$docPath`")
        appendLine("- Primary file: `$fileKey`")
        appendLine("- Request kind: `${exec.kind}`")
        appendLine("- Primary property: `${exec.property}`")
        appendLine("- Start line: `${exec.startLine}`")
        appendLine("- Response id: `$responseId`")
        if (exec.extraPrompt.isNotBlank()) {
            appendLine()
            appendLine("## User Request")
            appendLine()
            appendLine(exec.extraPrompt.trim())
        }
        if (acceptedEntries.isNotEmpty()) {
            appendLine()
            appendLine("## Provisional Edits")
            appendLine()
            acceptedEntries.forEach { entry ->
                appendLine("- `${entry.uid}/${entry.property}` (${entry.contents.length} chars)")
            }
        }
        appendLine()
        appendLine("## Conversation")
        appendLine()
        appendLine(aiPanelMarkdownForArchive(panelMarkdown).ifBlank { "_No visible chat transcript was recorded._" })
        appendLine()
        appendLine("## Tool Calls")
        appendLine()
        if (toolTranscript.isEmpty()) {
            appendLine("_No tool calls were made._")
        } else {
            toolTranscript.forEachIndexed { index, tool ->
                appendLine("### ${index + 1}. ${tool.name}")
                appendLine()
                appendLine("- Call id: `${tool.callId}`")
                appendLine("- Result: ${tool.resultSummary}")
                appendLine()
                appendLine("Arguments:")
                appendLine()
                appendLine("```json")
                appendLine(markdownFence(tool.argumentsJson))
                appendLine("```")
                appendLine()
                appendLine("Result:")
                appendLine()
                appendLine("```json")
                appendLine(markdownFence(tool.resultJson))
                appendLine("```")
                appendLine()
            }
        }
    }.trim()

    fun aiSetupMessage(t: Throwable): String {
        if (t is MissingOpenAiCredentialException) return t.message ?: "AI4 needs an OpenAI Platform key."
        return "AI4 could not initialize OpenAI support: ${t.message ?: t::class.java.simpleName}"
    }

    fun currentSessionInfoOrNull(): SessionInformation? =
        runCatching { thisSessionInfo.get() }.getOrNull()

    fun documentName(): String =
        currentSessionInfoOrNull()
            ?.filename
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).name }
            ?: "global-chat"

    fun parseFileRef(raw: String, defaultProperty: String? = null): FileRef? {
        if (!raw.startsWith("afield://")) return null
        val parts = raw.removePrefix("afield://").split("/", limit = 3)
        if (parts.size == 2 && defaultProperty != null) return FileRef(parts[0], parts[1], defaultProperty)
        if (parts.size != 3) return null
        return FileRef(parts[0], parts[1], parts[2])
    }

    fun addWindowsFromValue(value: Any?, windows: MutableList<Window>) {
        when (value) {
            is Window -> windows.add(value)
            is Renderer -> windows.addAll(value.windows)
        }
    }

    fun Box.hierarchyForChatScreenshot(): List<Box> {
        val seen = mutableSetOf<Box>()
        val queue = ArrayDeque<Box>()
        val ordered = mutableListOf<Box>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            ordered.add(next)
            next.parents.forEach { queue.add(it) }
        }
        return ordered
    }

    fun windowsVisibleFrom(box: Box?): List<Window> {
        val windows = mutableListOf<Window>()
        if (box != null) {
            box.hierarchyForChatScreenshot().forEach { candidate ->
                candidate.properties.values.forEach { addWindowsFromValue(it, windows) }
                runCatching {
                    candidate.ki.context.vars.values.forEach { addWindowsFromValue(it.value, windows) }
                }
            }
        }
        openWindowsSnapshot().forEach { windows.add(it) }
        lastWindowToOpen?.let { windows.add(it) }
        return windows.distinct()
    }

    fun chooseChatScreenshotWindow(box: Box?, target: String): Window? {
        val windows = windowsVisibleFrom(box).filter { window ->
            val renderer = window.associatedRenderer
            renderer != null && renderer.supportsDirectWindowCapture(window)
        }
        if (windows.isEmpty()) return null
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return windows.first()

        trimmed.toIntOrNull()?.let { index ->
            windows.getOrNull(index - 1)?.let { return it }
        }

        return windows.firstOrNull { it.title.contains(trimmed, ignoreCase = true) }
            ?: windows.first()
    }

    fun resolveChatScreenshotBox(uid: String, sessionInfo: SessionInformation?): Box? {
        val requestUid = uid.trim()
        if (requestUid.isBlank()) return sessionInfo?.root

        if (sessionInfo != null) {
            if (requestUid == "root" || requestUid == sessionInfo.root.id) return sessionInfo.root
            sessionInfo.boxes[requestUid]?.let { return it }
            return sessionInfo.root
        }

        return runCatching { box(requestUid, createIfMissing = false) }.getOrNull()
    }

    suspend fun captureChatScreenshot(
        request: AiChatScreenshotRequest,
        sessionInfo: SessionInformation? = thisSessionInfo.get()
    ): AiChatScreenshotResponse {
        val requestUid = request.uid.trim()
        val box = resolveChatScreenshotBox(requestUid, sessionInfo)
        if (box == null && requestUid.isNotBlank()) {
            lprintln("warning,ai4", "AI chat screenshot uid '$requestUid' was not found; trying open windows only")
        }
        if (box == null && requestUid.isBlank() && openWindowsSnapshot().isEmpty() && lastWindowToOpen == null) {
            return AiChatScreenshotResponse(
                success = false,
                message = "No active Field document or capturable afield.four Window for screenshot."
            )
        }

        val window = chooseChatScreenshotWindow(box, request.target) ?: return AiChatScreenshotResponse(
            success = false,
            message = if (requestUid.isNotBlank() && box == null) {
                "No capturable afield.four Window is visible; box '$requestUid' was not found in the active session."
            } else {
                "No capturable afield.four Window is visible from this box."
            }
        )
        val renderer = window.associatedRenderer ?: return AiChatScreenshotResponse(
            success = false,
            message = "The selected afield.four Window is not attached to a Renderer."
        )

        val out = Files.createTempFile("field4_ai_chat_screenshot_", ".jpg")
        try {
            renderer.captureWindowToFile(window, out.toString())
            val bytes = Files.readAllBytes(out)
            val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
            return AiChatScreenshotResponse(
                success = true,
                dataUrl = dataUrl,
                message = "Captured ${window.title} (${window.width} x ${window.height}; JPEG ${bytes.size / 1024} KiB)",
                title = window.title,
                width = window.width,
                height = window.height
            )
        } finally {
            runCatching { Files.deleteIfExists(out) }
        }
    }

    handlers["ai-chat-screenshot"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AiChatScreenshotRequest>(message.payload!!)
        val sessionInfo = thisSessionInfo.get()
        CoroutineScope(Runloop.miscPool.asCoroutineDispatcher()).launch {
            val response = runCatching {
                captureChatScreenshot(request, sessionInfo)
            }.getOrElse { throwable ->
                lprintln("warning,ai4", "AI chat screenshot failed: ${throwable.message}")
                AiChatScreenshotResponse(
                    success = false,
                    message = throwable.message ?: throwable::class.java.simpleName
                )
            }
            reply(Message("ai-chat-screenshot").payload(response))
        }
        Handled.yes
    })

    handlers["ai-assist"] = mutableSetOf({ message, _ ->
        val exec = Json.decodeFromJsonElement<AiAssist>(message.payload!!)
        val box = box(exec.uid)

        if (bridge == null) {
            val uiLog = NotifyLog()
            try {
                bridge = createIdeBridge(uiLog)
                helpers = IdeChatHelpers(bridge!!, log = uiLog)
                notify("Initializing AI support v4", "AI4")
            } catch (t: Throwable) {
                val messageText = aiSetupMessage(t)
                lprintln("warning,ai4", messageText)
                notify(messageText, "AI4 setup")
                return@mutableSetOf Handled.yes
            }
        }

        if (chatBridge == null) {
            val uiLog = NotifyLog(label = "")
            try {
                chatBridge = createIdeBridge(uiLog)
                chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
            } catch (t: Throwable) {
                val messageText = aiSetupMessage(t)
                lprintln("warning,ai4", messageText)
                notify(messageText, "AI4 setup")
                return@mutableSetOf Handled.yes
            }
        }

        val sessionInfo = thisSessionInfo.get()
        if (sessionInfo == null) {
            notify("AI4 unavailable: no active session context.", "AI4")
            return@mutableSetOf Handled.yes
        }

        val langName = languageNameForProperty(box, exec.property)
        val lang = langSpecForName(langName)
        val docName = File(sessionInfo.filename).name
        val docPath = sessionInfo.filename
        val escapedDocPath = Regex.escape(docPath)
        val fileKey = fileRefFor(docName, exec.uid, exec.property)
        val textProperties = linkedSetOf(
            "code",
            "vertex",
            "fragment",
            "vertex_fragment",
            "mesh",
            "task",
            "compute",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "vertex_s",
            "fragment_s",
            "mesh_s",
            "task_s",
            "compute_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s"
        ).apply {
            if (exec.property.isNotBlank()) add(exec.property)
        }

        fun languageNameForBoxProperty(target: Box, property: String): String = when (property) {
            "code" -> target.ke.languageName
            "vertex" -> "GLSL (vertex)"
            "fragment" -> "GLSL (fragment)"
            "vertex_fragment" -> "Slang (vertex+fragment)"
            "compute" -> "GLSL (compute)"
            "mesh" -> "GLSL (mesh)"
            "task" -> "GLSL (task)"
            "raygen" -> "GLSL (ray generation)"
            "miss" -> "GLSL (miss)"
            "closesthit" -> "GLSL (closest hit)"
            "anyhit" -> "GLSL (any hit)"
            "intersection" -> "GLSL (intersection)"
            "callable" -> "GLSL (callable)"
            "vertex_s" -> "Slang (vertex)"
            "fragment_s" -> "Slang (fragment)"
            "compute_s" -> "Slang (compute)"
            "mesh_s" -> "Slang (mesh)"
            "task_s" -> "Slang (task)"
            "raygen_s" -> "Slang (ray generation)"
            "miss_s" -> "Slang (miss)"
            "closesthit_s" -> "Slang (closest hit)"
            "anyhit_s" -> "Slang (any hit)"
            "intersection_s" -> "Slang (intersection)"
            "callable_s" -> "Slang (callable)"
            "customization", "remote_js" -> "JavaScript"
            "metadata" -> "YAML"
            else -> languageNameForProperty(target, property)
        }

        fun boxByIdOrNull(uid: String): Box? =
            if (uid == sessionInfo.root.id) sessionInfo.root else sessionInfo.boxes[uid]

        fun textPropertyContents(target: Box, property: String): String? =
            (target.getPropertyRaw(property) as? String)?.takeIf { it.isNotBlank() }

        /**
         * Resolve what a model called a box to a box: uid, then exact label, then label ignoring case,
         * then a unique substring of a label.
         *
         * The uid tier can be `firstOrNull` because uids are unique. The two *label* tiers cannot, and
         * were `firstOrNull` until 2026-08-17: two boxes named `render` and an agent asking for `render`
         * got whichever one the document's map iterated first, silently. They go through
         * [uniqueBoxOrRefuse], which throws [AmbiguousBoxNameException] instead of picking.
         *
         * Exactness still disambiguates, and that ordering is why the tiers are worth keeping separate:
         * boxes `Foo` and `foo` are not ambiguous for `foo`, because tier 2 resolves it before the
         * case-insensitive tier ever sees both.
         *
         * The loose substring tier deliberately stays `singleOrNull` — silent `null`, no refusal. It is
         * a *guess*, not a claim about identity: several labels containing `ren` is the normal state of
         * a document, not a naming collision an author should be told to fix, and callers already
         * handle "no match".
         */
        fun resolveBoxByChatName(rawName: String): Box? {
            val requested = rawName.trim()
            if (requested.isBlank()) return null
            val boxes = (listOf(sessionInfo.root) + sessionInfo.boxes.values)
                .distinctBy { it.id }
            boxes.firstOrNull { it.id == requested }?.let { return it }
            uniqueBoxOrRefuse(requested, boxes.filter { boxLabel(it) == requested })?.let { return it }
            uniqueBoxOrRefuse(
                requested,
                boxes.filter { boxLabel(it).equals(requested, ignoreCase = true) }
            )?.let { return it }
            val partial = boxes.filter { boxLabel(it).contains(requested, ignoreCase = true) }
            return partial.singleOrNull()
        }

        /**
         * Ambiguity is swallowed here, and only here: this feeds [expandBoxSlashRefs], which rewrites
         * `/box name` inside **prose the human typed**. Throwing out of a regex replacement would abort
         * the whole chat turn over a phrase in a sentence. A `null` leaves the `/box render` text
         * standing unexpanded, which is what an unknown name has always done and is visible to both
         * the model and the author. Every *tool* target goes through [requestedRefOrCurrent] instead,
         * which lets the refusal through.
         */
        fun boxChatRef(rawName: String, rawProperty: String?): String? {
            val target = try {
                resolveBoxByChatName(rawName)
            } catch (ambiguous: AmbiguousBoxNameException) {
                null
            } ?: return null
            val property = rawProperty?.trim()?.takeIf { it.isNotBlank() } ?: "code"
            return fileRefFor(docName, target.id, property)
        }

        fun expandBoxSlashRefs(text: String): String {
            if (!text.contains("/box")) return text
            var expanded = text
            val quoted = Regex("""/box\s+"([^"]+)"(?:/([A-Za-z0-9_-]+))?""")
            expanded = quoted.replace(expanded) { match ->
                boxChatRef(match.groupValues[1], match.groupValues.getOrNull(2)) ?: match.value
            }
            val unquotedWithProperty = Regex("""/box\s+([^/\n]+?)/([A-Za-z0-9_-]+)""")
            expanded = unquotedWithProperty.replace(expanded) { match ->
                boxChatRef(match.groupValues[1], match.groupValues[2]) ?: match.value
            }
            val unquotedAtEnd = Regex("""/box\s+([^/\n]+?)\s*$""")
            expanded = unquotedAtEnd.replace(expanded) { match ->
                boxChatRef(match.groupValues[1], "code") ?: match.value
            }
            return expanded
        }

        val expandedExtraPrompt = expandBoxSlashRefs(exec.extraPrompt)
        val agentMemory = agentMemoryByKey.computeIfAbsent(fileKey) { AgentMemoryState() }

        val preamblePropertyRegex =
            Regex("""(?:var|val)\s+([A-Za-z0-9_.$]+)\.([A-Za-z_][A-Za-z0-9_]*)\s+by\s+([A-Za-z0-9_.$]+)(?:\s*<\s*([^>\n]+)\s*>)?""")

        fun preambleText(target: Box): String =
            (listOf("preamble", "_preamble")
                .mapNotNull { property -> target.getPropertyRaw(property) as? String }
                + listOfNotNull(runCatching { gatherPreambleUpwards(target) }.getOrNull()))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")

        fun preambleHintsForBox(target: Box, relation: String): List<PreamblePropertyHint> =
            preamblePropertyRegex.findAll(preambleText(target)).map { match ->
                val receiver = match.groupValues[1]
                val property = match.groupValues[2]
                val delegate = match.groupValues[3].substringAfterLast('.')
                PreamblePropertyHint(
                    property = property,
                    receiver = receiver,
                    delegate = delegate,
                    type = match.groupValues.getOrNull(4).orEmpty(),
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    relation = relation,
                    graphRelevant = receiver == "Box" && delegate.startsWith("Inheritance")
                )
            }.distinctBy { "${it.receiver}.${it.property}:${it.delegate}:${it.boxUid}" }.toList()

        fun inheritedBoxPropertyNames(target: Box): Set<String> =
            preambleHintsForBox(target, "current")
                .filter { it.graphRelevant }
                .map { it.property }
                .toSet()

        fun textForGraphCueSearch(target: Box): String =
            textProperties.mapNotNull { textPropertyContents(target, it) }.joinToString("\n") + "\n" + preambleText(target)

        data class RelatedBox(val box: Box, val relation: String)

        fun graphNeighborBoxes(target: Box): List<RelatedBox> {
            val seen = linkedMapOf<String, RelatedBox>()
            fun add(candidate: Box?, relation: String) {
                if (candidate == null) return
                if (candidate.id == target.id || candidate.id == "root") return
                val existing = seen[candidate.id]
                if (existing == null) {
                    seen[candidate.id] = RelatedBox(candidate, relation)
                } else if (!existing.relation.contains(relation)) {
                    seen[candidate.id] = RelatedBox(candidate, "${existing.relation}; $relation")
                }
            }
            target.parents.forEach { add(it, "connected parent of ${boxLabel(target)}") }
            target.children.forEach { add(it, "connected child of ${boxLabel(target)}") }
            sessionInfo.boxes.values.forEach { candidate ->
                if (candidate.parents.any { it.id == target.id }) add(candidate, "connected child of ${boxLabel(target)}")
                if (candidate.children.any { it.id == target.id }) add(candidate, "connected parent of ${boxLabel(target)}")
            }

            val inheritedNames = inheritedBoxPropertyNames(target)
            if (inheritedNames.isNotEmpty()) {
                sessionInfo.boxes.values.forEach { candidate ->
                    if (candidate.id == target.id || candidate.id == "root") return@forEach
                    val candidateText = textForGraphCueSearch(candidate)
                    val matches = inheritedNames
                        .filter { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(candidateText) }
                        .take(4)
                    if (matches.isNotEmpty()) {
                        add(candidate, "shares inherited Box propert${if (matches.size == 1) "y" else "ies"} ${matches.joinToString(", ")} with ${boxLabel(target)}")
                    }
                }
            }

            return seen.values.sortedBy { it.box.name.ifBlank { it.box.id } }
        }

        fun contentEntriesForBox(target: Box, relation: String): List<ListContentEntry> =
            textProperties.mapNotNull { property ->
                val content = textPropertyContents(target, property) ?: return@mapNotNull null
                ListContentEntry(
                    fileRef = fileRefFor(docName, target.id, property),
                    property = property,
                    language = languageNameForBoxProperty(target, property),
                    content = content,
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    relation = relation
                )
            }

        fun preambleHintDescription(target: Box): String {
            val hints = preambleHintsForBox(target, "current")
            val inherited = hints.filter { it.graphRelevant }.map { it.property }.distinct().take(8)
            val shaderDriven = hints
                .filter { !it.graphRelevant && (it.receiver.contains("Mesh") || it.receiver.contains("Module") || it.receiver == "Structure") }
                .map { "${it.receiver}.${it.property}" }
                .distinct()
                .take(8)
            return buildString {
                if (inherited.isNotEmpty()) {
                    append(" inherited Box properties: ")
                    append(inherited.joinToString(", "))
                    append(".")
                }
                if (shaderDriven.isNotEmpty()) {
                    append(" shader binding helpers: ")
                    append(shaderDriven.joinToString(", "))
                    append(".")
                }
            }.trim()
        }

        fun languageForFilePath(path: Path): String {
            val lower = path.fileName?.toString()?.lowercase().orEmpty()
            return when {
                lower.endsWith(".kt") || lower.endsWith(".kts") -> "Kotlin"
                lower.endsWith(".glsl") || lower.endsWith(".vert") || lower.endsWith(".frag")
                    || lower.endsWith(".comp") || lower.endsWith(".mesh") || lower.endsWith(".task")
                    || lower.endsWith(".rgen") || lower.endsWith(".rmiss") || lower.endsWith(".rchit")
                    || lower.endsWith(".rahit") || lower.endsWith(".rint") || lower.endsWith(".rcall") -> "GLSL"
                lower.endsWith(".slang") -> "Slang"
                lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") -> "JavaScript"
                lower.endsWith(".ts") || lower.endsWith(".tsx") -> "TypeScript"
                lower.endsWith(".java") -> "Java"
                lower.endsWith(".py") -> "Python"
                lower.endsWith(".md") -> "Markdown"
                lower.endsWith(".yaml") || lower.endsWith(".yml") -> "YAML"
                lower.endsWith(".json") -> "JSON"
                lower.endsWith(".html") -> "HTML"
                lower.endsWith(".css") -> "CSS"
                lower.endsWith(".xml") -> "XML"
                else -> "text"
            }
        }

        val listDefinitions = {
            fun definitionsForBox(target: Box): List<DefinitionInfo> {
                val langForBox = languageNameForProperty(target, "code")
                val fileRef = fileRefFor(docName, target.id, "code")
                val vars = target.ki.context.vars.values.map { v ->
                    DefinitionInfo(
                        name = v.name,
                        type = "variable",
                        fileRef = fileRef,
                        language = langForBox
                    )
                }
                val funcs = target.ki.context.functions.values.map { f ->
                    DefinitionInfo(
                        name = f.toString(true),
                        type = "function",
                        fileRef = fileRef,
                        language = langForBox
                    )
                }
                return vars + funcs
            }

            val currentDefs = definitionsForBox(box)
            val otherDefs = sessionInfo.boxes.values
                .filter { it.id != box.id }
                .sortedBy { it.name.ifBlank { it.id } }
                .flatMap { definitionsForBox(it) }

            ListDefinitionsResult(definitions = currentDefs + otherDefs)
        }

        val listFileContents = {
            val relatedBoxes = graphNeighborBoxes(box)
            val entries = buildList {
                addAll(contentEntriesForBox(box, "current box (${boxLabel(box)})"))
                relatedBoxes.forEach { related ->
                    addAll(contentEntriesForBox(related.box, related.relation))
                }
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("No text content available for box '${box.id}'.")
            }
            val hints = buildList {
                addAll(preambleHintsForBox(box, "current box (${boxLabel(box)})"))
                relatedBoxes.forEach { related ->
                    addAll(preambleHintsForBox(related.box, related.relation))
                }
            }
            val primary = entries.firstOrNull { it.boxUid == box.id && it.property == exec.property }
                ?: entries.firstOrNull { it.boxUid == box.id }
                ?: entries.first()
            notify("Listing ${entries.size} current/connected content property/properties for ${box.id}", "AI4")
            ListContentsResult(
                fileRef = primary.fileRef,
                content = primary.content,
                language = primary.language,
                contents = entries,
                preambleHints = hints
            )
        }

        val contextAttachments: List<ContextAttachment> = buildList {
            fun addTextPropertyAttachment(target: Box, property: String, description: String) {
                val content = textPropertyContents(target, property) ?: return
                val hintDescription = preambleHintDescription(target)
                val combinedDescription = listOf(description, hintDescription)
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                add(
                    ContextAttachment(
                        fileName = fileRefFor(docName, target.id, property),
                        language = languageNameForBoxProperty(target, property),
                        code = content,
                        description = combinedDescription
                    )
                )
            }

            textProperties.forEach { property ->
                addTextPropertyAttachment(box, property, "current box ${boxLabel(box)} / $property")
            }

            val relatedBoxes = graphNeighborBoxes(box)
            relatedBoxes.forEach { related ->
                textProperties.forEach { property ->
                    addTextPropertyAttachment(
                        related.box,
                        property,
                        "${related.relation}; box ${boxLabel(related.box)} / $property"
                    )
                }
            }

            if (relatedBoxes.isNotEmpty() || exec.prompt.contains("outputPositions") || expandedExtraPrompt.contains("particles", ignoreCase = true)) {
                add(
                    ContextAttachment(
                        fileName = AI4_CONNECTED_RENDER_EXAMPLE_REF,
                        language = "Markdown",
                        code = AI4_CONNECTED_RENDER_EXAMPLE,
                        description = "example workflow for particle speed in a compute box plus color/contrast in a connected render box"
                    )
                )
            }

            val hasGraphicsProperties = listOf(
                "vertex",
                "fragment",
                "mesh",
                "task",
                "compute",
                "raygen",
                "miss",
                "closesthit",
                "anyhit",
                "intersection",
                "callable",
                "vertex_s",
                "fragment_s",
                "vertex_fragment",
                "mesh_s",
                "task_s",
                "compute_s",
                "raygen_s",
                "miss_s",
                "closesthit_s",
                "anyhit_s",
                "intersection_s",
                "callable_s"
            )
                .any { property -> textPropertyContents(box, property) != null }
            if (hasGraphicsProperties || exec.prompt.contains("moduleHere") || exec.prompt.contains("renderer")) {
                val guidePath = Paths.get(System.getProperty("user.dir")).resolve("src/VULKAN_GRAPHICS_SYSTEM.md")
                if (Files.exists(guidePath) && Files.isRegularFile(guidePath)) {
                    val guide = runCatching { Files.readString(guidePath, StandardCharsets.UTF_8) }.getOrNull()
                    if (!guide.isNullOrBlank()) {
                        add(
                            ContextAttachment(
                                fileName = guidePath.toString(),
                                language = "Markdown",
                                code = guide
                            )
                        )
                    }
                }
            }

            if (hasGraphicsProperties || glslSpecForProperty(exec.property) != null || expandedExtraPrompt.contains("include", ignoreCase = true)) {
                GlslIncludeLibraryIndex.contextAttachmentFor(expandedExtraPrompt.ifBlank { exec.prompt })?.let { add(it) }
            }
        }

        val resolveAttachment: (String) -> AttachmentResponse? = attachmentResolver@{ raw ->
            val requested = raw.trim()
            if (requested.isEmpty()) return@attachmentResolver null

            if (requested == AI4_CONNECTED_RENDER_EXAMPLE_REF) {
                return@attachmentResolver AttachmentResponse(
                    file = AI4_CONNECTED_RENDER_EXAMPLE_REF,
                    language = "Markdown",
                    content = AI4_CONNECTED_RENDER_EXAMPLE
                )
            }

            parseFileRef(requested)?.let { ref ->
                if (ref.document != docName) return@let
                val target = boxByIdOrNull(ref.uid) ?: return@let
                val content = textPropertyContents(target, ref.property) ?: return@let
                return@attachmentResolver AttachmentResponse(
                    file = fileRefFor(docName, target.id, ref.property),
                    language = languageNameForBoxProperty(target, ref.property),
                    content = content,
                    relatedExamples = listOf(AI4_CONNECTED_RENDER_EXAMPLE_REF)
                )
            }

            val requestedProperty = requested.removePrefix("/")
            if (requestedProperty in textProperties) {
                val content = textPropertyContents(box, requestedProperty) ?: return@attachmentResolver null
                return@attachmentResolver AttachmentResponse(
                    file = fileRefFor(docName, box.id, requestedProperty),
                    language = languageNameForBoxProperty(box, requestedProperty),
                    content = content
                )
            }

            GlslIncludeLibraryIndex.resolveAttachment(requested)?.let { return@attachmentResolver it }

            val candidatePaths = linkedSetOf<Path>()
            val requestedPath = runCatching { Paths.get(requested) }.getOrNull()
            if (requestedPath != null) {
                if (requestedPath.isAbsolute) {
                    candidatePaths.add(requestedPath.normalize())
                } else {
                    candidatePaths.add(Paths.get(System.getProperty("user.dir")).resolve(requestedPath).normalize())
                    val docDir = Paths.get(docPath).parent
                    if (docDir != null) {
                        candidatePaths.add(docDir.resolve(requestedPath).normalize())
                    }
                }
            }

            for (candidate in candidatePaths) {
                if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) continue
                val content = runCatching { Files.readString(candidate, StandardCharsets.UTF_8) }.getOrNull() ?: continue
                val related = RelatedContextDiscovery.forPath(candidate.toString())
                return@attachmentResolver AttachmentResponse(
                    file = candidate.toString(),
                    language = languageForFilePath(candidate),
                    content = content,
                    relatedGuides = related.guides,
                    relatedExamples = related.examples
                )
            }

            null
        }

        val editOtherFile: (EditFileRequest) -> EditFileResponse = editOtherFile@{ req ->
            notify("Editing ${req.file}", "AI4")
            val ref = parseFileRef(req.file)
                ?: return@editOtherFile EditFileResponse(false, "Unsupported file ref '${req.file}'")
            if (ref.document != docName) {
                return@editOtherFile EditFileResponse(false, "Unknown document '${ref.document}'")
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@editOtherFile EditFileResponse(false, "Unknown box '${ref.uid}'")
            setProvisional(docPath, target.id, ref.property, req.newContent)
            EditFileResponse(true, "Updated ${req.file}")
        }

        val workspaceSymbolSearch: (WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult = workspaceSymbolSearch@{ req ->
            val query = req.query.trim()
            if (query.isEmpty()) return@workspaceSymbolSearch WorkspaceSymbolSearchResult(emptyList())
            searchWorkspaceSymbols(
                index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex(),
                rawQuery = query,
                limit = req.limit
            )
        }

        fun requestedRefOrCurrent(raw: String, defaultProperty: String = exec.property.ifBlank { "code" }): FileRef {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return FileRef(docName, box.id, defaultProperty)
            parseFileRef(trimmed, defaultProperty = defaultProperty)?.let { return it }
            if (boxByIdOrNull(trimmed) != null) return FileRef(docName, trimmed, defaultProperty)
            resolveBoxByChatName(trimmed)?.let { return FileRef(docName, it.id, defaultProperty) }
            return FileRef(docName, box.id, trimmed.removePrefix("/").ifBlank { defaultProperty })
        }

        fun stagedOrCurrentSource(target: Box, property: String, explicitSource: String?): String? {
            if (explicitSource != null) return explicitSource
            provisionalEntries(docPath)
                .firstOrNull { it.uid == target.id && it.property == property }
                ?.let { return it.contents }
            return textPropertyContents(target, property)
        }

        val listKotlinContextVariables: (ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult =
            listKotlinContextVariables@{ req ->
                val ref = requestedRefOrCurrent(req.target, defaultProperty = "code")
                if (ref.document != docName) {
                    return@listKotlinContextVariables ListKotlinContextVariablesResult(
                        success = false,
                        target = req.target,
                        boxUid = ref.uid,
                        boxName = "",
                        generation = 0,
                        message = "Unknown document '${ref.document}'."
                    )
                }
                val target = boxByIdOrNull(ref.uid)
                    ?: return@listKotlinContextVariables ListKotlinContextVariablesResult(
                        success = false,
                        target = req.target,
                        boxUid = ref.uid,
                        boxName = "",
                        generation = 0,
                        message = "Unknown box '${ref.uid}'."
                    )

                val variables = target.ki.context.vars.values
                    .sortedBy { it.name }
                    .map { info ->
                        val value = info.value
                        KotlinContextVariableInfo(
                            name = info.name,
                            declaration = runCatching { shortenType(info.descriptor.toString()) }.getOrDefault(info.type),
                            declaredType = runCatching { shortenType(info.descriptor.returnType.toString()) }.getOrDefault(""),
                            runtimeType = runtimeTypeName(value),
                            mutable = info.descriptor is kotlin.reflect.KMutableProperty<*>,
                            valuePreview = clipToolText(safeString(value), req.maxValueChars),
                            primitiveValue = primitiveToolValue(value),
                            primitiveKind = primitiveKind(value)
                        )
                    }

                ListKotlinContextVariablesResult(
                    success = true,
                    target = fileRefFor(docName, target.id, ref.property),
                    boxUid = target.id,
                    boxName = boxLabel(target),
                    generation = target.ki.context.generation,
                    message = "Found ${variables.size} Kotlin context variable(s) for ${target.id}.",
                    variables = variables
                )
            }

        val codeToolProviders = Ai4CodeToolProviders(
            docName = docName,
            refResolver = { raw, dp -> requestedRefOrCurrent(raw, dp) },
            boxByIdOrNull = { uid -> boxByIdOrNull(uid) },
            stagedOrCurrentSource = { t, prop, explicit -> stagedOrCurrentSource(t, prop, explicit) },
        )
        val evaluateKotlinExpression = codeToolProviders.evaluateKotlinExpression
        val compileKotlin = codeToolProviders.compileKotlin

        val shaderProperties = setOf(
            "vertex",
            "fragment",
            "vertex_fragment",
            "mesh",
            "task",
            "compute",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "vertex_s",
            "fragment_s",
            "mesh_s",
            "task_s",
            "compute_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s"
        )

        fun modeForShaderProperty(property: String): String? = when (property) {
            "vertex", "fragment", "vertex_fragment", "vertex_s", "fragment_s" -> "graphics"
            "compute", "compute_s" -> "compute"
            "task", "mesh", "task_s", "mesh_s" -> "mesh"
            "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
            "raygen_s", "miss_s", "closesthit_s", "anyhit_s", "intersection_s", "callable_s" -> "ray"
            else -> null
        }

        class LocalShaderSourceSnapshot(
            val property: String,
            val hadLocalValue: Boolean,
            val value: Any?
        )

        fun applyStagedShaderSourcesForReload(target: Box): () -> Unit {
            val stagedSources = provisionalEntries(docPath)
                .filter { it.uid == target.id && it.property in shaderProperties }
            if (stagedSources.isEmpty()) return {}

            val reloadSideEffectProperties = listOf("preamble", "_preamble", "introduction", "_introduction", "attic", "_attic")
            val snapshotProperties = (stagedSources.map { it.property } + reloadSideEffectProperties).distinct()
            val snapshots = snapshotProperties.map { property ->
                LocalShaderSourceSnapshot(
                    property = property,
                    hadLocalValue = target.properties.containsKey(property),
                    value = target.properties[property]
                )
            }
            stagedSources.forEach { staged ->
                target.setPropertyRaw(staged.property, staged.contents, doResync = false)
            }
            return {
                snapshots.forEach { snapshot ->
                    if (snapshot.hadLocalValue) {
                        target.properties[snapshot.property] = snapshot.value
                    } else {
                        target.properties.remove(snapshot.property)
                    }
                }
            }
        }

        fun modesForShaderReload(target: Box, requestedMode: String, requestedProperty: String): List<String> {
            val normalized = requestedMode.trim().lowercase()
            if (normalized !in setOf("", "auto")) return listOf(
                when (normalized) {
                    "graphics", "graphic", "module", "vertex", "fragment" -> "graphics"
                    "compute" -> "compute"
                    "mesh", "task" -> "mesh"
                    "ray", "rays", "raytracing", "ray-tracing", "ray_tracing" -> "ray"
                    else -> normalized
                }
            )

            modeForShaderProperty(requestedProperty)?.let { return listOf(it) }

            return buildList {
                if (runCatching { target._shader != null }.getOrDefault(false)) add("graphics")
                if (runCatching { target._computeShader != null }.getOrDefault(false)) add("compute")
                if (runCatching { target._meshShader != null }.getOrDefault(false)) add("mesh")
                if (runCatching { target._rayTracingShader != null }.getOrDefault(false)) add("ray")
            }
        }

        val reloadShaders: (ReloadShadersRequest) -> ReloadShadersResult = reloadShaders@{ req ->
            val ref = requestedRefOrCurrent(req.target, defaultProperty = "code")
            if (ref.document != docName) {
                return@reloadShaders ReloadShadersResult(
                    success = false,
                    target = req.target,
                    mode = req.mode,
                    message = "Unknown document '${ref.document}'."
                )
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@reloadShaders ReloadShadersResult(
                    success = false,
                    target = req.target,
                    mode = req.mode,
                    message = "Unknown box '${ref.uid}'."
                )

            val modes = modesForShaderReload(target, req.mode, ref.property).distinct()
            if (modes.isEmpty()) {
                return@reloadShaders ReloadShadersResult(
                    success = false,
                    target = fileRefFor(docName, target.id, ref.property),
                    mode = req.mode,
                    message = "No shader module pointer is attached to '${target.id}'."
                )
            }

            notify("Reloading shader(s): ${modes.joinToString(", ")}", "AI4")
            val errors = mutableListOf<ShaderReloadError>()
            val reloaded = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val oldErrorProxy = target.output.errorProxy
            val restoreShaderSources = applyStagedShaderSourcesForReload(target)

            try {
                runCatching {
                    runBlocking {
                        withContext(Runloop.runLoop.main) {
                            target.output.errorProxy = { content, property, line ->
                                if (content != null) {
                                    errors += ShaderReloadError(
                                        property = property.ifBlank { "shader" },
                                        line = line,
                                        message = content.toString()
                                    )
                                }
                                oldErrorProxy(content, property, line)
                            }
                            try {
                                modes.forEach { mode ->
                                    val ok = when (mode) {
                                        "graphics" -> if (runCatching { target._shader != null }.getOrDefault(false)) {
                                            reloadShader(target)
                                        } else {
                                            false
                                        }
                                        "compute" -> if (runCatching { target._computeShader != null }.getOrDefault(false)) {
                                            reloadComputeShader(target)
                                        } else {
                                            false
                                        }
                                        "mesh" -> if (runCatching { target._meshShader != null }.getOrDefault(false)) {
                                            reloadMeshShader(target)
                                        } else {
                                            false
                                        }
                                        "ray" -> if (runCatching { target._rayTracingShader != null }.getOrDefault(false)) {
                                            reloadRayTracingShader(target)
                                        } else {
                                            false
                                        }
                                        else -> {
                                            errors += ShaderReloadError(mode, message = "Unknown shader reload mode '$mode'.")
                                            false
                                        }
                                    }
                                    if (ok) reloaded += mode else failed += mode
                                }
                            } finally {
                                target.output.errorProxy = oldErrorProxy
                            }
                        }
                    }
                }.onFailure { throwable ->
                    failed += modes.filter { it !in reloaded }
                    errors += ShaderReloadError(
                        property = "shader",
                        message = throwable.message ?: throwable::class.java.name
                    )
                    target.output.errorProxy = oldErrorProxy
                }
            } finally {
                restoreShaderSources()
            }

            val success = failed.isEmpty() && errors.isEmpty()
            var visualAttached = false
            var visualMessage = ""
            var visualDataUrl = ""
            // `visualVerificationAvailable()` used to gate this too, and it was the whole bug: it asks
            // whether the conversation *already* contains a `data:image/`, which an agent has no way to
            // arrange for itself. The only two things that put one there are a person attaching an image
            // and a person typing `/screenshot`, so a fresh chat could never see the render window, and
            // `visualCheck=true` was refused for the life of the conversation. Worse, it was refused
            // with `visualCheckMessage` left empty, so the model got `visualCheckAttached=false` and no
            // reason at all — which is indistinguishable from a capture that was attempted and failed.
            //
            // Every branch below now leaves a message behind. A refusal that cannot explain itself sends
            // the agent off guessing, and that is exactly what happened.
            if (req.visualCheck && success) {
                runCatching {
                    runBlocking {
                        delay(2_000)
                        val screenshot = captureChatScreenshot(
                            AiChatScreenshotRequest(uid = target.id, target = ""),
                            sessionInfo
                        )
                        visualMessage = screenshot.message
                        if (screenshot.success && screenshot.dataUrl.isNotBlank()) {
                            visualAttached = true
                            visualDataUrl = screenshot.dataUrl
                        }
                    }
                }.onFailure { throwable ->
                    visualMessage = throwable.message ?: throwable::class.java.simpleName
                }
            } else if (req.visualCheck) {
                // Still conditional on the reload having worked, but for a real reason: this is a
                // *post-reload* screenshot, and after a failed reload the window still shows the old
                // shader, so attaching it would be actively misleading. Name the other tool, because
                // "look at it as it stands" is a legitimate thing to want here.
                visualMessage = "Not captured: the reload did not succeed, so the window still shows the " +
                    "previous shader and a screenshot would be misleading. Fix the errors and reload " +
                    "again, or call CaptureRenderWindow to see the window as it currently stands."
            }

            val message = buildString {
                append(if (success) "Shader reload succeeded" else "Shader reload failed")
                append(" for ${target.id}")
                if (reloaded.isNotEmpty()) append("; reloaded=${reloaded.joinToString(",")}")
                if (failed.isNotEmpty()) append("; failed=${failed.joinToString(",")}")
                if (visualMessage.isNotBlank()) append("; visual=$visualMessage")
            }
            notify(message, "AI4")

            ReloadShadersResult(
                success = success,
                target = fileRefFor(docName, target.id, ref.property),
                mode = req.mode,
                message = message,
                reloaded = reloaded,
                errors = errors,
                visualCheckAttached = visualAttached,
                visualCheckMessage = visualMessage,
                visualDataUrl = visualDataUrl
            )
        }

        /**
         * Look at the afield.four render window, on demand.
         *
         * Separate from [reloadShaders]' `visualCheck` because that one is a *post-reload* check: it
         * only means anything when a reload has just succeeded, and it presupposes there was a shader
         * edit at all. An agent doing graphics work wants to look *before* it changes anything, and
         * wants to look when a reload has just failed. Until this tool existed the only route to the
         * window was a successful shader reload, so an agent asked to inspect a scene had to call
         * ReloadShaders on a shader it had not modified and hope — which is precisely what the logs
         * show one doing, twice, before giving up and reporting that no image had been attached.
         *
         * Ungated on purpose. See the note on the gate in [reloadShaders] above.
         */
        val captureRenderWindow: (CaptureRenderWindowRequest) -> CaptureRenderWindowResult = { req ->
            val settle = req.settleMs.coerceIn(0, CAPTURE_RENDER_WINDOW_MAX_SETTLE_MS)
            // requestedRefOrCurrent already handles all three spellings: blank (this chat's box), an
            // afield:// ref, and a bare uid.
            val uid = runCatching { requestedRefOrCurrent(req.uid).uid }.getOrDefault(req.uid.trim())
            runCatching {
                runBlocking {
                    if (settle > 0) delay(settle.toLong())
                    val shot = captureChatScreenshot(
                        AiChatScreenshotRequest(uid = uid, target = req.target),
                        sessionInfo
                    )
                    CaptureRenderWindowResult(
                        success = shot.success && shot.dataUrl.isNotBlank(),
                        message = shot.message.ifBlank {
                            if (shot.success) "Captured the render window." else "Capture failed."
                        },
                        title = shot.title,
                        width = shot.width,
                        height = shot.height,
                        settledMs = settle,
                        dataUrl = if (shot.success) shot.dataUrl else ""
                    )
                }
            }.getOrElse { throwable ->
                val why = throwable.message ?: throwable::class.java.simpleName
                lprintln("warning,ai4", "CaptureRenderWindow failed for uid '$uid': $why")
                CaptureRenderWindowResult(success = false, message = why, settledMs = settle)
            }
        }

        val listMacros: (MacroListRequest) -> MacroListResult = { _ ->
            val result = OperationTranscript.listMacros(docPath)
            MacroListResult(result.success, result.message, result.macros)
        }

        val readMacro: (MacroReadRequest) -> MacroReadResult = { req ->
            val result = OperationTranscript.readMacro(docPath, req.name)
            MacroReadResult(
                success = result.success,
                message = if (result.success) "Loaded macro '${result.name}'." else result.message,
                name = result.name.ifBlank { req.name },
                content = if (result.success) result.content else "",
                operationCount = result.operationCount
            )
        }

        val writeMacro: (MacroWriteRequest) -> MacroWriteResult = { req ->
            val result = OperationTranscript.writeMacro(docPath, req.name, req.content)
            MacroWriteResult(
                success = result.success,
                message = result.message,
                name = result.name.ifBlank { req.name },
                operationCount = result.operationCount
            )
        }

        val runMacro: (MacroRunRequest) -> MacroRunResult = { req ->
            val targets = req.targetBoxes.map { raw ->
                val trimmed = raw.trim()
                resolveBoxByChatName(trimmed)?.id ?: trimmed
            }.filter { it.isNotBlank() }
            macroRunToolResult(docPath, req, targets)
        }

        val codeUseQuery: (CodeUseQueryRequest) -> CodeUseQueryResult = codeUseQuery@{ req ->
            val kind = req.kind.trim().lowercase().ifBlank { "failures" }
            val limit = req.limit.coerceIn(1, 50)
            val fetchLimit = (limit * 5).coerceIn(limit, 500)
            val documentFilter = req.document.trim()
            val boxFilter = req.box.trim()

            fun matches(document: String, boxUid: String, boxName: String): Boolean {
                if (documentFilter.isNotBlank() && !document.contains(documentFilter, ignoreCase = true)) return false
                if (boxFilter.isNotBlank()) {
                    val hit = boxUid.contains(boxFilter, ignoreCase = true) ||
                        boxName.contains(boxFilter, ignoreCase = true)
                    if (!hit) return false
                }
                return true
            }

            when (kind) {
                "summary", "status" -> {
                    val summary = CodeUseTelemetryService.summary()
                    CodeUseQueryResult(
                        success = summary.enabled,
                        kind = "summary",
                        message = if (summary.enabled) {
                            "Code-use telemetry is enabled; snippets=${summary.snippets}, failures available via kind=failures."
                        } else {
                            summary.message.ifBlank { "Code-use telemetry is disabled." }
                        },
                        summary = summary
                    )
                }

                "failure", "failures", "error", "errors", "exceptions" -> {
                    val rows = CodeUseTelemetryService.failures(fetchLimit)
                        .filter { matches(it.document, it.boxUid, it.boxName) }
                        .take(limit)
                    CodeUseQueryResult(
                        success = true,
                        kind = "failures",
                        message = "Returned ${rows.size} retained compiler/runtime failure row(s).",
                        summary = CodeUseTelemetryService.summary(),
                        failures = rows
                    )
                }

                "snippet", "snippets", "timeline" -> {
                    val rows = CodeUseTelemetryService.recentSnippets(fetchLimit)
                        .filter { matches(it.document, it.boxUid, it.boxName) }
                        .take(limit)
                    CodeUseQueryResult(
                        success = true,
                        kind = "snippets",
                        message = "Returned ${rows.size} recent snippet row(s).",
                        summary = CodeUseTelemetryService.summary(),
                        snippets = rows
                    )
                }

                "launch", "launches", "lifecycle", "lifecycles" -> {
                    val rows = CodeUseTelemetryService.recentLaunches(fetchLimit)
                        .filter { matches(it.document, it.boxUid, it.boxName) }
                        .take(limit)
                    CodeUseQueryResult(
                        success = true,
                        kind = "launches",
                        message = "Returned ${rows.size} launch lifecycle row(s).",
                        summary = CodeUseTelemetryService.summary(),
                        launches = rows
                    )
                }

                "symbol", "symbols" -> {
                    val rows = CodeUseTelemetryService.symbols(limit)
                    CodeUseQueryResult(
                        success = true,
                        kind = "symbols",
                        message = "Returned ${rows.size} symbol row(s). Symbol rows are populated once the analyzer has resolved uses.",
                        summary = CodeUseTelemetryService.summary(),
                        symbols = rows
                    )
                }

                "unresolved" -> {
                    val rows = CodeUseTelemetryService.unresolved(limit)
                    CodeUseQueryResult(
                        success = true,
                        kind = "unresolved",
                        message = "Returned ${rows.size} unresolved analyzer row(s).",
                        summary = CodeUseTelemetryService.summary(),
                        unresolved = rows
                    )
                }

                "line-callers", "callers", "callers-for-source-line", "source-line-callers" -> {
                    val (target, rows) = CodeUseTelemetryService.callersForSourceLine(
                        sourcePath = req.sourcePath,
                        line = req.line,
                        limit = limit
                    )
                    CodeUseQueryResult(
                        success = target != null,
                        kind = "line-callers",
                        message = if (target != null) {
                            "Returned ${rows.size} recent Field snippet caller row(s) for ${target.symbolName} at ${target.sourceFile}:${target.line}."
                        } else {
                            "Could not resolve a source symbol for '${req.sourcePath}:${req.line}'."
                        },
                        summary = CodeUseTelemetryService.summary(),
                        target = target,
                        callers = rows
                    )
                }

                "references", "references-for-code", "code-references", "refs" -> {
                    val rows = CodeUseTelemetryService.referencesForCode(req.code, limit.coerceAtMost(10))
                    CodeUseQueryResult(
                        success = true,
                        kind = "references-for-code",
                        message = "Returned ${rows.size} likely Field source reference row(s).",
                        summary = CodeUseTelemetryService.summary(),
                        references = rows
                    )
                }

                "related-source", "related-source-for-code", "source-bundle", "source-context" -> {
                    val rows = CodeUseTelemetryService.relatedSourceForCode(
                        code = req.code,
                        symbolLimit = limit.coerceAtMost(10),
                        callersPerSymbol = req.callersPerSymbol,
                        sourceRadiusLines = req.sourceRadiusLines
                    )
                    CodeUseQueryResult(
                        success = true,
                        kind = "related-source-for-code",
                        message = "Returned ${rows.size} related Field source bundle row(s) with excerpts, usage counts, and peer callers.",
                        summary = CodeUseTelemetryService.summary(),
                        relatedSources = rows
                    )
                }

                "semantic", "semantic-analyze", "semantic-analysis", "analyze-code", "semantic-references" -> {
                    val analysis = CodeUseTelemetryService.semanticAnalyzeCode(
                        CodeUseSemanticAnalyzeRequest(
                            code = req.code,
                            sourceName = req.sourceName,
                            limit = limit.coerceAtMost(10),
                            callersPerSymbol = req.callersPerSymbol,
                            sourceRadiusLines = req.sourceRadiusLines
                        )
                    )
                    CodeUseQueryResult(
                        success = analysis.success,
                        kind = "semantic-analyze",
                        message = analysis.message,
                        summary = CodeUseTelemetryService.summary(),
                        references = analysis.references,
                        relatedSources = analysis.relatedSources,
                        semanticAnalysis = analysis
                    )
                }

                "hot", "hot-symbols", "hot-source-symbols" -> {
                    val rows = CodeUseTelemetryService.hotSourceSymbols(req.window, limit)
                    CodeUseQueryResult(
                        success = true,
                        kind = "hot-symbols",
                        message = "Returned ${rows.size} hot source symbol row(s) for window='${req.window}'. Confidence values are literal: k2-resolved is semantic; source-index values are conservative static matches; textual values are fallback evidence.",
                        summary = CodeUseTelemetryService.summary(),
                        hotSymbols = rows
                    )
                }

                "early", "early-document-symbols", "open-time", "open-only" -> {
                    val rows = CodeUseTelemetryService.earlyDocumentSymbols(
                        document = req.document,
                        earlyWindowMs = req.earlyWindowMs,
                        limit = limit
                    )
                    CodeUseQueryResult(
                        success = true,
                        kind = "early-document-symbols",
                        message = "Returned ${rows.size} source symbol row(s) used early in a document and rarely afterward.",
                        summary = CodeUseTelemetryService.summary(),
                        earlySymbols = rows
                    )
                }

                else -> CodeUseQueryResult(
                    success = false,
                    kind = kind,
                    message = "Unknown kind '${req.kind}'. Use summary, failures, snippets, launches, symbols, unresolved, line-callers, references-for-code, related-source-for-code, semantic-analyze, hot-symbols, or early-document-symbols."
                )
            }
        }

        val semanticRetrievalSearch: (SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult = { req ->
            val result = SemanticRetrievalService.query(
                SemanticRetrievalQueryRequest(
                    query = req.query,
                    limit = req.limit,
                    corpus = req.corpus,
                    corpora = req.corpora,
                    pathPrefix = req.pathPrefix,
                    kinds = req.kinds,
                    documentName = req.documentName,
                    boxId = req.boxId,
                    property = req.property,
                    includeCapsule = req.includeCapsule,
                    includeSource = req.includeSource
                )
            )
            SemanticRetrievalToolResult(
                success = result.success,
                message = result.message,
                status = result.status,
                model = SemanticRetrievalService.modelInfo(),
                availableCorpora = SemanticRetrievalService.availableCorpora,
                results = result.results
            )
        }

        val semanticRetrievalStatus: () -> SemanticRetrievalToolResult = {
            val status = SemanticRetrievalService.status()
            SemanticRetrievalToolResult(
                success = true,
                message = status.message,
                status = status,
                model = SemanticRetrievalService.modelInfo(),
                availableCorpora = SemanticRetrievalService.availableCorpora
            )
        }

        val semanticRetrievalRebuild: () -> SemanticRetrievalToolResult = {
            val result = SemanticRetrievalService.rebuild()
            SemanticRetrievalToolResult(
                success = result.success,
                message = result.message,
                status = result.status,
                model = SemanticRetrievalService.modelInfo(),
                availableCorpora = SemanticRetrievalService.availableCorpora
            )
        }

        val vectorStoreIds = buildList {
            KotlinSourceIndex.currentVectorStoreId()?.let { add(it) }
            addAll(GlslIncludeLibraryIndex.currentVectorStoreIdsFor(exec.property, expandedExtraPrompt + "\n" + exec.prompt))
        }.distinct()

        fun parsedScreenshotCommand(): String? {
            val text = when (exec.kind) {
                "general", "chat" -> exec.extraPrompt.trim()
                else -> ""
            }
            val match = Regex("^/(screenshot|shot)(?:\\s+([\\s\\S]*))?$", RegexOption.IGNORE_CASE).matchEntire(text)
                ?: return null
            return match.groupValues.getOrNull(2)?.trim().orEmpty()
        }

        parsedScreenshotCommand()?.let { target ->
            ai4Scope.launch {
                val response = runCatching {
                    captureChatScreenshot(AiChatScreenshotRequest(uid = exec.uid, target = target), sessionInfo)
                }.getOrElse { throwable ->
                    AiChatScreenshotResponse(
                        success = false,
                        message = throwable.message ?: throwable::class.java.simpleName
                    )
                }
                val previous = chatHistoryByKey[fileKey].orEmpty()
                val commandText = buildString {
                    append("/screenshot")
                    if (target.isNotBlank()) {
                        append(" ")
                        append(target)
                    }
                }
                val next = buildString {
                    if (previous.isNotBlank()) {
                        append(previous)
                        append("\n")
                    }
                    append("<<you>>\n")
                    append(commandText)
                    if (response.success && response.dataUrl.isNotBlank()) {
                        val title = if (response.title.isNotBlank()) " from ${response.title}" else ""
                        append("\n\n_")
                        append(response.message.ifBlank { "Captured screenshot$title" })
                        append("_\n\n![screenshot")
                        append(title)
                        append("](")
                        append(response.dataUrl)
                        append(")")
                    } else {
                        append("\n<<ai>>\n_")
                        append(response.message.ifBlank { "Screenshot failed" })
                        append("_")
                    }
                }
                chatHistoryByKey[fileKey] = next
                _send(
                    Message("set-output").payload(Set_output(box.id, "outputarea", "ai-chat", next)),
                    document = escapedDocPath
                )
                _send(
                    Message("notify").payload(
                        Notify(
                            response.message.ifBlank { if (response.success) "Captured screenshot" else "Screenshot failed" },
                            "AI4"
                        )
                    ),
                    document = escapedDocPath
                )
            }
            return@mutableSetOf Handled.yes
        }

        if (exec.kind == "chat" || exec.kind == "cursor" || exec.kind == "rewrite" || exec.kind == "error") {
            clearProvisional(docPath)
        }

        val provisionalBeforeAi = provisionalEntries(docPath)
            .associate { provisionalKey(it) to it.contents }

        val state = if (exec.kind == "general") {
            chatThreadFor(fileKey, exec.prompt)
        } else {
            threadFor(fileKey, exec.prompt)
        }

        when (exec.kind) {
            "chat" -> notify("Initiating AI Chat", "AI4")
            "general" -> notify("Initiating AI Conversation", "AI4")
            "cursor" -> notify("Initiating Completion", "AI4")
            "rewrite" -> notify("Initiating AI Rewrite", "AI4")
            "error" -> notify("Initiating AI Error Fix", "AI4")
            else -> {
                notify("Unknown AI request kind '${exec.kind}'", "AI4")
                return@mutableSetOf Handled.yes
            }
        }

        ai4Scope.launch {
            val chatResult: OpenAiIdeCodexBridge.SendResult?
            val editResult: IdeChatHelpers.HighLevelResult?
            val streamActive = AtomicBoolean(false)
            var streamPrefix: String? = null
            val liveLock = Any()
            val streamBuffer = StringBuilder()
            val progressEntries = mutableListOf<OpenAiIdeCodexBridge.ProgressUpdate>()
            var livePrefix: String? = null
            var lastPush = 0L

            fun baseWithoutPendingAi(): String {
                val prefix = livePrefix ?: streamPrefix ?: ""
                return prefix
                    .removeSuffix("<<ai>>\n")
                    .removeSuffix("<<ai>>")
            }

            fun renderLiveConversation(
                aiText: String = "",
                latestProgressOpen: Boolean = true,
                additions: String = ""
            ): String {
                val entries = synchronized(liveLock) { progressEntries.toList() }
                val base = baseWithoutPendingAi()
                return buildString {
                    append(base)
                    if (length > 0 && this[length - 1] != '\n') append("\n")
                    entries.forEachIndexed { index, progress ->
                        val open = latestProgressOpen && aiText.isBlank() && index == entries.lastIndex
                        append(if (open) "<<progress-open>>\n" else "<<progress>>\n")
                        append(progress.title.trim().ifBlank { "AI activity" })
                        val body = progress.body.trim()
                        if (body.isNotBlank()) {
                            append("\n")
                            append(body)
                        }
                        append("\n")
                    }
                    if (additions.isNotBlank()) {
                        append(additions.trimEnd())
                        append("\n")
                    }
                    if (aiText.isNotEmpty() || (entries.isEmpty() && additions.isBlank())) {
                        append("<<ai>>\n")
                        append(aiText)
                    }
                }
            }

            fun pushLiveConversation(latestProgressOpen: Boolean = true) {
                if (!streamActive.get()) return
                val partial = synchronized(liveLock) { streamBuffer.toString() }
                val payload = Set_output(
                    box.id,
                    "outputarea",
                    "ai-chat",
                    renderLiveConversation(partial, latestProgressOpen)
                )
                _send(Message("set-output").payload(payload), document = escapedDocPath)
            }

            fun visibleUserBlockForKind(): String = when (exec.kind) {
                "chat" -> expandedExtraPrompt.trim().ifBlank { "Inline chat requested." }
                "cursor" -> "Complete code at the cursor."
                "rewrite" -> "Rewrite the selected code."
                "error" -> "Fix the current error."
                else -> "AI assistance requested."
            }

            fun beginVisibleAiPanel(title: String, userBlock: String) {
                val previous = chatHistoryByKey[fileKey].orEmpty()
                livePrefix = buildString {
                    if (previous.isNotBlank()) {
                        append(previous)
                        append("\n")
                    }
                    if (userBlock.isNotBlank()) {
                        append("<<you>>\n")
                        append(userBlock)
                        append("\n")
                    }
                    append("<<ai>>\n")
                }
                streamPrefix = livePrefix
                synchronized(liveLock) {
                    progressEntries += OpenAiIdeCodexBridge.ProgressUpdate(
                        title = title,
                        body = "Preparing context and tools."
                    )
                }
                streamActive.set(true)
                pushLiveConversation(latestProgressOpen = true)
            }

            val progressCallback: (OpenAiIdeCodexBridge.ProgressUpdate) -> Unit = { progress ->
                if (streamActive.get()) {
                    synchronized(liveLock) {
                        progressEntries += progress
                    }
                    pushLiveConversation(latestProgressOpen = true)
                }
            }

            fun sendFinalVisibleAiPanel(markdown: String, additions: String = ""): String {
                val next = renderLiveConversation(
                    aiText = markdown,
                    latestProgressOpen = false,
                    additions = additions
                )
                chatHistoryByKey[fileKey] = next
                val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                _send(Message("set-output").payload(payload), document = escapedDocPath)
                return next
            }

            if (exec.kind != "general") {
                beginVisibleAiPanel(
                    title = when (exec.kind) {
                        "chat" -> "Starting inline chat"
                        "cursor" -> "Starting completion"
                        "rewrite" -> "Starting rewrite"
                        "error" -> "Starting error fix"
                        else -> "Starting AI work"
                    },
                    userBlock = visibleUserBlockForKind()
                )
            }

            // Append any caller-supplied instruction (e.g. from the error-suggestion "Ask AI4"
            // buttons) to the fixed per-kind directive so cursor/rewrite/error requests can be
            // shaped without a bespoke kind.
            fun withExtraPrompt(base: String): String {
                val extra = exec.extraPrompt.trim()
                return if (extra.isEmpty()) base else "$base\n\n$extra"
            }

            try {
                when (exec.kind) {
                    "chat" -> {
                        chatResult = null
                        editResult = helpers!!.chatInline(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            lineNumber = exec.startLine,
                            userPrompt = expandedExtraPrompt,
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            listKotlinContextVariables = listKotlinContextVariables,
                            evaluateKotlinExpression = evaluateKotlinExpression,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            captureRenderWindow = captureRenderWindow,
                            listMacros = listMacros,
                            readMacro = readMacro,
                            writeMacro = writeMacro,
                            runMacro = runMacro,
                            codeUseQuery = codeUseQuery,
                            semanticRetrievalSearch = semanticRetrievalSearch,
                            semanticRetrievalStatus = semanticRetrievalStatus,
                            semanticRetrievalRebuild = semanticRetrievalRebuild,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    "general" -> {
                        val trimmedPrompt = expandedExtraPrompt.trim()
                        val attachmentMarkdown = exec.images.joinToString("\n") { url ->
                            "![attachment]($url)"
                        }.trim()
                        val userBlock = buildString {
                            if (trimmedPrompt.isNotEmpty()) append(trimmedPrompt)
                            if (attachmentMarkdown.isNotEmpty()) {
                                if (trimmedPrompt.isNotEmpty()) append("\n\n")
                                append(attachmentMarkdown)
                            }
                        }.trim()
                        val previous = chatHistoryByKey[fileKey].orEmpty()
                        val prefix = buildString {
                            if (previous.isNotBlank()) {
                                append(previous)
                                append("\n")
                            }
                            if (userBlock.isNotEmpty()) {
                                append("<<you>>\n")
                                append(userBlock)
                                append("\n")
                            }
                            append("<<ai>>\n")
                        }
                        streamPrefix = prefix
                        livePrefix = prefix
                        streamActive.set(true)
                        synchronized(liveLock) {
                            progressEntries += OpenAiIdeCodexBridge.ProgressUpdate(
                                title = "Starting AI conversation",
                                body = "Preparing context and tools."
                            )
                        }
                        pushLiveConversation(latestProgressOpen = true)
                        chatResult = chatHelpers!!.chatGeneral(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = expandedExtraPrompt,
                            imageUrls = exec.images,
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            listKotlinContextVariables = listKotlinContextVariables,
                            evaluateKotlinExpression = evaluateKotlinExpression,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            captureRenderWindow = captureRenderWindow,
                            listMacros = listMacros,
                            readMacro = readMacro,
                            writeMacro = writeMacro,
                            runMacro = runMacro,
                            codeUseQuery = codeUseQuery,
                            semanticRetrievalSearch = semanticRetrievalSearch,
                            semanticRetrievalStatus = semanticRetrievalStatus,
                            semanticRetrievalRebuild = semanticRetrievalRebuild,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            vectorStoreIds = vectorStoreIds,
                            onTextDelta = { chunk ->
                                if (!streamActive.get() || chunk.isEmpty()) return@chatGeneral
                                synchronized(liveLock) {
                                    streamBuffer.append(chunk)
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastPush >= 80L) {
                                    lastPush = now
                                    if (!streamActive.get()) return@chatGeneral
                                    pushLiveConversation(latestProgressOpen = false)
                                }
                            },
                            onProgress = { progress ->
                                progressCallback(progress)
                            }
                        )
                        streamActive.set(false)
                        editResult = null
                    }

                    "cursor" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = withExtraPrompt("Complete the code at <cursor>."),
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            listKotlinContextVariables = listKotlinContextVariables,
                            evaluateKotlinExpression = evaluateKotlinExpression,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            captureRenderWindow = captureRenderWindow,
                            listMacros = listMacros,
                            readMacro = readMacro,
                            writeMacro = writeMacro,
                            runMacro = runMacro,
                            codeUseQuery = codeUseQuery,
                            semanticRetrievalSearch = semanticRetrievalSearch,
                            semanticRetrievalStatus = semanticRetrievalStatus,
                            semanticRetrievalRebuild = semanticRetrievalRebuild,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    "rewrite" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = withExtraPrompt("Rewrite only the code between <rewrite> and </rewrite>."),
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            listKotlinContextVariables = listKotlinContextVariables,
                            evaluateKotlinExpression = evaluateKotlinExpression,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            captureRenderWindow = captureRenderWindow,
                            listMacros = listMacros,
                            readMacro = readMacro,
                            writeMacro = writeMacro,
                            runMacro = runMacro,
                            codeUseQuery = codeUseQuery,
                            semanticRetrievalSearch = semanticRetrievalSearch,
                            semanticRetrievalStatus = semanticRetrievalStatus,
                            semanticRetrievalRebuild = semanticRetrievalRebuild,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    "error" -> {
                        chatResult = null
                        editResult = helpers!!.chatFile(
                            state = state,
                            code = exec.prompt,
                            lang = lang,
                            userPrompt = withExtraPrompt("Fix the error described between <error> and </error>."),
                            attachments = contextAttachments,
                            resolveAttachment = resolveAttachment,
                            listDefinitions = listDefinitions,
                            listFileContents = listFileContents,
                            listKotlinContextVariables = listKotlinContextVariables,
                            evaluateKotlinExpression = evaluateKotlinExpression,
                            editOtherFile = editOtherFile,
                            compileKotlin = compileKotlin,
                            reloadShaders = reloadShaders,
                            captureRenderWindow = captureRenderWindow,
                            listMacros = listMacros,
                            readMacro = readMacro,
                            writeMacro = writeMacro,
                            runMacro = runMacro,
                            codeUseQuery = codeUseQuery,
                            semanticRetrievalSearch = semanticRetrievalSearch,
                            semanticRetrievalStatus = semanticRetrievalStatus,
                            semanticRetrievalRebuild = semanticRetrievalRebuild,
                            agentMemory = agentMemory,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            vectorStoreIds = vectorStoreIds,
                            onProgress = progressCallback
                        )
                    }

                    else -> {
                        chatResult = null
                        editResult = null
                    }
                }
            } catch (e: Exception) {
                if (livePrefix != null) {
                    sendFinalVisibleAiPanel(
                        "_AI ${exec.kind} failed: ${e.message ?: e::class.java.simpleName}_"
                    )
                }
                streamActive.set(false)
                inMain {
                    notify("AI ${exec.kind} failed: ${e.message ?: e::class.java.simpleName}", "AI4")
                }
                return@launch
            }

            inMain {
                if (chatResult != null) {
                    chatThreadByKey[fileKey] = chatResult.threadState
                    val trimmedPrompt = expandedExtraPrompt.trim()
                    val attachmentMarkdown = exec.images.joinToString("\n") { url ->
                        "![attachment]($url)"
                    }.trim()
                    val userBlock = buildString {
                        if (trimmedPrompt.isNotEmpty()) append(trimmedPrompt)
                        if (attachmentMarkdown.isNotEmpty()) {
                            if (trimmedPrompt.isNotEmpty()) append("\n\n")
                            append(attachmentMarkdown)
                        }
                    }.trim()
                    val trimmedResponse = chatResult.text.trim()
                    if (livePrefix == null) {
                        livePrefix = streamPrefix ?: buildString {
                            val previous = chatHistoryByKey[fileKey].orEmpty()
                            if (previous.isNotBlank()) {
                                append(previous)
                                append("\n")
                            }
                            if (userBlock.isNotEmpty()) {
                                append("<<you>>\n")
                                append(userBlock)
                                append("\n")
                            }
                            append("<<ai>>\n")
                        }
                    }
                    val additions = chatResult.conversationAdditions
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { it.trimEnd() }
                    val checklistMarkdown = agentMemory.checklistMarkdown()
                    val responseWithChecklist = buildString {
                        append(trimmedResponse.ifEmpty { "_(no response)_" })
                        if (checklistMarkdown.isNotBlank()) {
                            append("\n\n")
                            append(checklistMarkdown)
                        }
                    }
                    val next = renderLiveConversation(
                        aiText = responseWithChecklist,
                        latestProgressOpen = false,
                        additions = additions
                    )
                    chatHistoryByKey[fileKey] = next
                    val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                    _send(Message("set-output").payload(payload), document = escapedDocPath)
                    val changedEntries = provisionalDelta(provisionalBeforeAi, provisionalEntries(docPath))
                    if (changedEntries.isNotEmpty()) {
                        setProvisionalArchive(
                            docPath,
                            changedEntries,
                            AiProvisionalArchive(
                                markdown = buildAiProposalArchiveMarkdown(
                                    docPath = docPath,
                                    fileKey = fileKey,
                                    exec = exec,
                                    responseId = chatResult.rawResponseId,
                                    panelMarkdown = next,
                                    acceptedEntries = changedEntries,
                                    toolTranscript = chatResult.toolTranscript
                                )
                            )
                        )
                    }
                    notify("Finished AI conversation", "AI4")
                } else if (editResult != null) {
                    threadByKey[fileKey] = editResult.threadState
                    markProvisional(box, exec.property, editResult.editedCode)
                    val commentary = editResult.commentary
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    val finalMarkdown = buildString {
                        append("_Prepared a provisional ${exec.kind} edit for `${exec.property}`._")
                        if (commentary.isNotBlank()) {
                            append("\n\n")
                            append(commentary)
                        }
                        val checklistMarkdown = agentMemory.checklistMarkdown()
                        if (checklistMarkdown.isNotBlank()) {
                            append("\n\n")
                            append(checklistMarkdown)
                        }
                    }
                    val next = sendFinalVisibleAiPanel(finalMarkdown)
                    val changedEntries = provisionalDelta(provisionalBeforeAi, provisionalEntries(docPath))
                    if (changedEntries.isNotEmpty()) {
                        setProvisionalArchive(
                            docPath,
                            changedEntries,
                            AiProvisionalArchive(
                                markdown = buildAiProposalArchiveMarkdown(
                                    docPath = docPath,
                                    fileKey = fileKey,
                                    exec = exec,
                                    responseId = editResult.rawResponseId,
                                    panelMarkdown = next,
                                    acceptedEntries = changedEntries,
                                    toolTranscript = editResult.toolTranscript
                                )
                            )
                        )
                    }
                    streamActive.set(false)
                    notify("Finished AI ${exec.kind}", "AI4")
                }
            }
        }
        Handled.yes
    })
    
    // Chat interface handlers
    handlers["hello_chat"] = mutableSetOf({ message, reply ->
        val hello = Json.decodeFromJsonElement<Hello>(message.payload!!)
        lprintln("info,chat", "Chat connection from ${hello.username} for file ${hello.filename}")
        // Initialize chat state if needed
        Handled.yes
    })
    
    handlers["chat_input"] = mutableSetOf({ message, reply ->
        val input = Json.decodeFromJsonElement<Chat_input>(message.payload!!)
        
        if (chatBridge == null) {
            val uiLog = NotifyLog(label = "")
            chatBridge = createIdeBridge(uiLog)
            chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
        }
        
        val fileKey = "chat:${documentName()}"
        val state = chatThreadFor(fileKey, "")
        val agentMemory = agentMemoryByKey.computeIfAbsent(fileKey) { AgentMemoryState() }
        
        ai4Scope.launch {
            try {
                // Format the user message with images if present
                val userMessage = buildString {
                    append(input.value)
                    if (input.images.isNotEmpty()) {
                        append("\n\n[User attached ${input.images.size} image(s)]")
                    }
                }
                
                // Send intermediate response
                reply(Message("chat_input").payload(Chat_input(
                    value = "<div style='opacity: 0.5;'>Thinking...</div>",
                    phase = 1
                )))
                
                val lang = LanguageSpec(
                    name = "text",
                    wrap = { it.trimEnd() },
                    unwrap = { it.trimEnd() }
                )
                val chatDocPath = currentSessionInfoOrNull()?.filename.orEmpty()
                val codeUseQuery: (CodeUseQueryRequest) -> CodeUseQueryResult = { req ->
                    val kind = req.kind.trim().lowercase().ifBlank { "failures" }
                    val limit = req.limit.coerceIn(1, 50)
                    when (kind) {
                        "summary", "status" -> {
                            val summary = CodeUseTelemetryService.summary()
                            CodeUseQueryResult(
                                success = summary.enabled,
                                kind = "summary",
                                message = if (summary.enabled) "Code-use telemetry is enabled." else summary.message,
                                summary = summary
                            )
                        }
                        "failure", "failures", "error", "errors", "exceptions" -> CodeUseQueryResult(
                            success = true,
                            kind = "failures",
                            message = "Returned retained compiler/runtime failure rows.",
                            summary = CodeUseTelemetryService.summary(),
                            failures = CodeUseTelemetryService.failures(limit)
                        )
                        "snippet", "snippets", "timeline" -> CodeUseQueryResult(
                            success = true,
                            kind = "snippets",
                            message = "Returned recent snippet rows.",
                            summary = CodeUseTelemetryService.summary(),
                            snippets = CodeUseTelemetryService.recentSnippets(limit)
                        )
                        "launch", "launches", "lifecycle", "lifecycles" -> CodeUseQueryResult(
                            success = true,
                            kind = "launches",
                            message = "Returned launch lifecycle rows.",
                            summary = CodeUseTelemetryService.summary(),
                            launches = CodeUseTelemetryService.recentLaunches(limit)
                        )
                        "symbol", "symbols" -> CodeUseQueryResult(
                            success = true,
                            kind = "symbols",
                            message = "Returned symbol rows.",
                            summary = CodeUseTelemetryService.summary(),
                            symbols = CodeUseTelemetryService.symbols(limit)
                        )
                        "unresolved" -> CodeUseQueryResult(
                            success = true,
                            kind = "unresolved",
                            message = "Returned unresolved analyzer rows.",
                            summary = CodeUseTelemetryService.summary(),
                            unresolved = CodeUseTelemetryService.unresolved(limit)
                        )
                        "line-callers", "callers", "callers-for-source-line", "source-line-callers" -> {
                            val (target, rows) = CodeUseTelemetryService.callersForSourceLine(
                                sourcePath = req.sourcePath,
                                line = req.line,
                                limit = limit
                            )
                            CodeUseQueryResult(
                                success = target != null,
                                kind = "line-callers",
                                message = if (target != null) {
                                    "Returned ${rows.size} recent Field snippet caller row(s)."
                                } else {
                                    "Could not resolve a source symbol for '${req.sourcePath}:${req.line}'."
                                },
                                summary = CodeUseTelemetryService.summary(),
                                target = target,
                                callers = rows
                            )
                        }
                        "references", "references-for-code", "code-references", "refs" -> CodeUseQueryResult(
                            success = true,
                            kind = "references-for-code",
                            message = "Returned likely Field source reference rows.",
                            summary = CodeUseTelemetryService.summary(),
                            references = CodeUseTelemetryService.referencesForCode(req.code, limit.coerceAtMost(10))
                        )
                        "related-source", "related-source-for-code", "source-bundle", "source-context" -> CodeUseQueryResult(
                            success = true,
                            kind = "related-source-for-code",
                            message = "Returned related Field source bundle rows with excerpts, usage counts, and peer callers.",
                            summary = CodeUseTelemetryService.summary(),
                            relatedSources = CodeUseTelemetryService.relatedSourceForCode(
                                code = req.code,
                                symbolLimit = limit.coerceAtMost(10),
                                callersPerSymbol = req.callersPerSymbol,
                                sourceRadiusLines = req.sourceRadiusLines
                            )
                        )
                        "semantic", "semantic-analyze", "semantic-analysis", "analyze-code", "semantic-references" -> {
                            val analysis = CodeUseTelemetryService.semanticAnalyzeCode(
                                CodeUseSemanticAnalyzeRequest(
                                    code = req.code,
                                    sourceName = req.sourceName,
                                    limit = limit.coerceAtMost(10),
                                    callersPerSymbol = req.callersPerSymbol,
                                    sourceRadiusLines = req.sourceRadiusLines
                                )
                            )
                            CodeUseQueryResult(
                                success = analysis.success,
                                kind = "semantic-analyze",
                                message = analysis.message,
                                summary = CodeUseTelemetryService.summary(),
                                references = analysis.references,
                                relatedSources = analysis.relatedSources,
                                semanticAnalysis = analysis
                            )
                        }
                        "hot", "hot-symbols", "hot-source-symbols" -> CodeUseQueryResult(
                            success = true,
                            kind = "hot-symbols",
                            message = "Returned hot source symbol rows. Confidence values are literal: k2-resolved is semantic; source-index values are conservative static matches; textual values are fallback evidence.",
                            summary = CodeUseTelemetryService.summary(),
                            hotSymbols = CodeUseTelemetryService.hotSourceSymbols(req.window, limit)
                        )
                        "early", "early-document-symbols", "open-time", "open-only" -> CodeUseQueryResult(
                            success = true,
                            kind = "early-document-symbols",
                            message = "Returned source symbol rows used early in a document and rarely afterward.",
                            summary = CodeUseTelemetryService.summary(),
                            earlySymbols = CodeUseTelemetryService.earlyDocumentSymbols(req.document, req.earlyWindowMs, limit)
                        )
                        else -> CodeUseQueryResult(
                            success = false,
                            kind = kind,
                            message = "Unknown kind '${req.kind}'. Use summary, failures, snippets, launches, symbols, unresolved, line-callers, references-for-code, related-source-for-code, semantic-analyze, hot-symbols, or early-document-symbols."
                        )
                    }
                }
                val semanticRetrievalSearch: (SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult = { req ->
                    val result = SemanticRetrievalService.query(
                        SemanticRetrievalQueryRequest(
                            query = req.query,
                            limit = req.limit,
                            corpus = req.corpus,
                            corpora = req.corpora,
                            pathPrefix = req.pathPrefix,
                            kinds = req.kinds,
                            documentName = req.documentName,
                            boxId = req.boxId,
                            property = req.property,
                            includeCapsule = req.includeCapsule,
                            includeSource = req.includeSource
                        )
                    )
                    SemanticRetrievalToolResult(
                        success = result.success,
                        message = result.message,
                        status = result.status,
                        model = SemanticRetrievalService.modelInfo(),
                        availableCorpora = SemanticRetrievalService.availableCorpora,
                        results = result.results
                    )
                }
                val semanticRetrievalStatus: () -> SemanticRetrievalToolResult = {
                    val status = SemanticRetrievalService.status()
                    SemanticRetrievalToolResult(
                        success = true,
                        message = status.message,
                        status = status,
                        model = SemanticRetrievalService.modelInfo(),
                        availableCorpora = SemanticRetrievalService.availableCorpora
                    )
                }
                val semanticRetrievalRebuild: () -> SemanticRetrievalToolResult = {
                    val build = SemanticRetrievalService.rebuild()
                    SemanticRetrievalToolResult(
                        success = build.success,
                        message = build.message,
                        status = build.status,
                        model = SemanticRetrievalService.modelInfo(),
                        availableCorpora = SemanticRetrievalService.availableCorpora
                    )
                }
                val result = chatHelpers!!.chatGeneral(
                    state = state,
                    code = "",
                    lang = lang,
                    userPrompt = userMessage,
                    imageUrls = input.images,
                    listMacros = { _ ->
                        val macro = OperationTranscript.listMacros(chatDocPath)
                        MacroListResult(macro.success, macro.message, macro.macros)
                    },
                    readMacro = { req ->
                        val macro = OperationTranscript.readMacro(chatDocPath, req.name)
                        MacroReadResult(macro.success, macro.message, macro.name.ifBlank { req.name }, if (macro.success) macro.content else "", macro.operationCount)
                    },
                    writeMacro = { req ->
                        val macro = OperationTranscript.writeMacro(chatDocPath, req.name, req.content)
                        MacroWriteResult(macro.success, macro.message, macro.name.ifBlank { req.name }, macro.operationCount)
                    },
                    runMacro = { req ->
                        macroRunToolResult(chatDocPath, req, req.targetBoxes.filter { it.isNotBlank() })
                    },
                    codeUseQuery = codeUseQuery,
                    semanticRetrievalSearch = semanticRetrievalSearch,
                    semanticRetrievalStatus = semanticRetrievalStatus,
                    semanticRetrievalRebuild = semanticRetrievalRebuild,
                    agentMemory = agentMemory
                )
                
                chatThreadByKey[fileKey] = result.threadState
                
                // Format response with markdown
                val responseWithChecklist = buildString {
                    append(result.text)
                    val checklistMarkdown = agentMemory.checklistMarkdown()
                    if (checklistMarkdown.isNotBlank()) {
                        append("\n\n")
                        append(checklistMarkdown)
                    }
                }
                val formattedResponse = formatMarkdown(responseWithChecklist)
                
                // Send final response
                inMain {
                    reply(Message("chat_input").payload(Chat_input(
                        value = formattedResponse,
                        phase = 20
                    )))
                }
            } catch (e: Exception) {
                inMain {
                    reply(Message("chat_input").payload(Chat_input(
                        value = "<div style='color: red;'>Error: ${e.message}</div>",
                        phase = 20
                    )))
                }
            }
        }
        
        Handled.yes
    })
}

private fun formatMarkdown(text: String): String {
    // Simple markdown formatting for chat output
    var result = text
        .replace(Regex("```([\\s\\S]*?)```"), "<pre><code>$1</code></pre>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        .replace("\n", "<br/>")
    return result
}
