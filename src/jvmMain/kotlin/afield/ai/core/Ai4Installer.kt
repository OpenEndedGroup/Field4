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

@kotlinx.serialization.Serializable
private data class AiBoxChatRequest(val uid: String, val clear: Boolean = false)

@kotlinx.serialization.Serializable
private data class AiBoxChatReply(val uid: String, val history: String, val error: String = "")

@kotlinx.serialization.Serializable
private data class AiChatContextRequest(val uid: String)

@kotlinx.serialization.Serializable
private data class AiChatContextReply(val uid: String, val markdown: String)

private val ai4Scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun macroRunToolResult(
    documentPath: String,
    req: MacroRunRequest,
    targets: List<String>
): MacroRunResult {
    val result = OperationTranscript.runMacro(documentPath, req.name, targets, req.direction, req.strict)
    return MacroRunResult(
        result.success, result.message, result.name, result.direction,
        result.targetBoxes, result.applied, result.skipped, result.errors, result.output, result.setupVerified
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
                message = "Unknown document '${ref.document}'. Name the box instead (its name or uid), or use " +
                    "this document's ref form afield://$docName/<box uid>/code."
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
                message = "Unknown box '${ref.uid}'. Give `file` as the box's name or uid, or as afield://$docName/<box uid>/code."
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
    fun createIdeBridge(uiLog: LogSink): LlmBridge = afield.ai.core.createIdeBridge(uiLog)
    val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val chatThreadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    val activeBoxChats = ConcurrentHashMap.newKeySet<String>()
    val agentMemoryByKey = ConcurrentHashMap<String, AgentMemoryState>()
    /** The last turn's [ContextReport] per box (`doc\u0000uid`), for `/context`. Metadata only, never the text. */
    val lastContextByBox = ConcurrentHashMap<String, ContextReport>()
    var bridgeRevision = aiProviderConfigRevision()

    fun invalidateBridgesAfterSettingsChange() {
        val revision = aiProviderConfigRevision()
        if (revision == bridgeRevision) return
        bridge = null
        helpers = null
        chatBridge = null
        chatHelpers = null
        threadByKey.clear()
        chatThreadByKey.clear()
        agentMemoryByKey.clear()
        bridgeRevision = revision
        lprintln("info,ai4", "AI provider settings changed; reset provider threads; saved box conversations retained")
    }

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

    fun threadFor(fileKey: String, fileText: String, cacheKey: String = fileKey, history: String = ""): OpenAiIdeCodexBridge.ThreadState =
        threadByKey[cacheKey] ?: bridge!!.startThread(fileKey, fileText + AiBoxConversation.resumeContext(history)).also {
            threadByKey[cacheKey] = it
        }

    fun chatThreadFor(fileKey: String, fileText: String, cacheKey: String = fileKey, history: String = ""): OpenAiIdeCodexBridge.ThreadState =
        chatThreadByKey[cacheKey] ?: chatBridge!!.startThread(
            fileKey,
            fileText + AiBoxConversation.resumeContext(history),
            instructions = OpenAiIdeCodexBridge.defaultChatThreadInstructions
        ).also {
            chatThreadByKey[cacheKey] = it
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

    handlers["ai-chat-screenshot"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AiChatScreenshotRequest>(message.payload!!)
        val sessionInfo = thisSessionInfo.get()
        val target = sessionInfo.boxes[request.uid]
        val key = "${sessionInfo.filename}\u0000${request.uid}\u0000"
        if (!activeBoxChats.add(key)) {
            reply(Message("ai-chat-screenshot").payload(AiChatScreenshotResponse(false, message = "Wait for the current AI turn before taking a chat screenshot.")))
            return@mutableSetOf Handled.yes
        }
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
            try {
                if (target != null) {
                    val entry = buildString {
                        append(AiBoxConversation.read(target).trimEnd())
                        append("\n<<you>>\n/screenshot ${request.target}\n<<ai>>\n")
                        append(response.message)
                        if (response.success) append("\n\n![screenshot](${response.dataUrl})")
                    }
                    AiBoxConversation.write(target, entry)
                }
                reply(Message("ai-chat-screenshot").payload(response))
            } finally {
                activeBoxChats.remove(key)
            }
        }
        Handled.yes
    })

    // `/context` in a box's chat: what the last turn sent besides the conversation, as a table.
    handlers["ai-chat-context"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AiChatContextRequest>(message.payload!!)
        val report = lastContextByBox["${thisSessionInfo.get().filename}\u0000${request.uid}"]
        val markdown = report?.toMarkdown()
            ?: "_No AI turn has run in this box since Field started, or the last one sent its instructions inline " +
                "(AFIELD_AI4_EPHEMERAL_INSTRUCTIONS=off, or the OpenRouter provider)._"
        reply(Message("ai-chat-context").payload(AiChatContextReply(request.uid, markdown)))
        Handled.yes
    })

    handlers["ai-chat-history"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<AiBoxChatRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val target = info.boxes[request.uid] ?: info.root.takeIf { request.uid == it.id }
        if (target == null) {
            reply(Message("ai-chat-history").payload(AiBoxChatReply(request.uid, "", "Box no longer exists.")))
            return@mutableSetOf Handled.yes
        }
        val key = "${thisSessionInfo.get().filename}\u0000${target.id}\u0000"
        val busy = request.clear && key in activeBoxChats
        if (key !in activeBoxChats) AiBoxConversation.recoverInterrupted(target)
        if (request.clear && !busy) {
            AiBoxConversation.write(target, "")
            threadByKey.keys.removeIf { it.startsWith(key) }
            chatThreadByKey.keys.removeIf { it.startsWith(key) }
            agentMemoryByKey.keys.removeIf { it.startsWith(key) }
            send(Message("set-output").payload(Set_output(target.id, "outputarea", "ai-chat", "")))
        }
        reply(Message("ai-chat-history").payload(AiBoxChatReply(
            request.uid, AiBoxConversation.read(target), if (busy) "Wait for the current AI turn before clearing." else ""
        )))
        Handled.yes
    })

    handlers["ai-assist"] = mutableSetOf({ message, _ ->
        invalidateBridgesAfterSettingsChange()
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
            val uiLog = NotifyLog()
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
        val documentGuides = DocumentGuideTools.forSession(sessionInfo)
        val focus = ToolFocus(docPath, box, exec.property)
        // Document-authored tools contributed by boxes via the aiTool DSL (this request's snapshot).
        val documentAiTools = DocumentToolRegistry.toolsFor(docPath)
        // Native graph + execution tools (NewBox, RunBox, PatchBoxText, ReadBoxOutput...). The editor
        // kinds' reply rewrites this box's property, so their tools refuse to write it; the general
        // chat's reply is prose, so nothing is reply-owned there.
        val replyGraph = afield.ai.agent.FieldGraphTools.forTurn(sessionInfo, box, exec.property)
        val chatGraph = afield.ai.agent.FieldGraphTools.forTurn(sessionInfo)
        val escapedDocPath = Regex.escape(docPath)
        val fileKey = fileRefFor(docName, exec.uid, exec.property)
        val boxChatKey = "$docPath\u0000${box.id}\u0000"
        val conversationKey = boxChatKey + exec.property
        fun history() = AiBoxConversation.read(box)
        fun saveHistory(text: String) = AiBoxConversation.write(box, text)
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
        val agentMemory = agentMemoryByKey.computeIfAbsent(conversationKey) { AgentMemoryState() }

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
                limit = req.limit,
                includeRelatedContext = req.includeRelatedContext
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

        val runtimeTools = Ai4RuntimeToolProviders(
            sessionInfo, { raw, property -> requestedRefOrCurrent(raw, property) },
            ::boxByIdOrNull, ::applyStagedShaderSourcesForReload
        )
        val listKotlinContextVariables = runtimeTools.listKotlinContextVariables
        val reloadShaders = runtimeTools.reloadShaders
        val captureRenderWindow = runtimeTools.captureRenderWindow

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

        val codeUseQuery = runtimeTools.codeUseQuery

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
                val previous = history()
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
                saveHistory(next)
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

        if (!activeBoxChats.add(boxChatKey)) {
            notify("This box already has an AI turn running.", "AI4")
            return@mutableSetOf Handled.yes
        }
        if (exec.kind == "chat" || exec.kind == "cursor" || exec.kind == "rewrite" || exec.kind == "error") {
            clearProvisional(docPath)
        }

        val provisionalBeforeAi = provisionalEntries(docPath)
            .associate { provisionalKey(it) to it.contents }

        AiBoxConversation.recoverInterrupted(box)
        AiBoxConversation.running(box, true)
        // Switching property or assistance kind resumes from the shared transcript, not a stale thread.
        val currentThreads = if (exec.kind == "general") chatThreadByKey else threadByKey
        val otherThreads = if (exec.kind == "general") threadByKey else chatThreadByKey
        currentThreads.keys.removeIf { it.startsWith(boxChatKey) && it != conversationKey }
        otherThreads.keys.removeIf { it.startsWith(boxChatKey) }
        val savedHistory = history()
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
            var turnModel = (if (exec.kind == "general") chatBridge else bridge)?.modelIdentity

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
                    // Keep the requested/reported model visible even while only tool progress exists.
                    if (aiText.isEmpty() && entries.isNotEmpty()) {
                        append("<<ai>>\n")
                        turnModel?.let { append(it.marker()) }
                    }
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
                        turnModel?.let { append(it.marker()) }
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
                saveHistory(payload.value)
                _send(Message("set-output").payload(payload), document = escapedDocPath)
            }

            fun visibleUserBlockForKind(): String = when (exec.kind) {
                "general" -> (listOf(expandedExtraPrompt.trim()) + exec.images.map { "![attachment]($it)" })
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                "chat" -> expandedExtraPrompt.trim().ifBlank { "Inline chat requested." }
                "cursor" -> "Complete code at the cursor."
                "rewrite" -> "Rewrite the selected code."
                "error" -> "Fix the current error."
                else -> "AI assistance requested."
            }

            fun beginVisibleAiPanel(title: String, userBlock: String) {
                val previous = history()
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
                        title = title
                    )
                }
                streamActive.set(true)
                pushLiveConversation(latestProgressOpen = true)
            }

            val progressCallback: (OpenAiIdeCodexBridge.ProgressUpdate) -> Unit = { progress ->
                if (streamActive.get()) {
                    synchronized(liveLock) {
                        progress.modelIdentity?.let { turnModel = it }
                        // One current activity, replaced as work moves from model to tool to result.
                        if (progress.phase != null && progressEntries.lastOrNull()?.phase in setOf("model", "tool")) {
                            progressEntries.removeAt(progressEntries.lastIndex)
                        }
                        if (progress.title.isNotBlank()) progressEntries += progress
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
                saveHistory(next)
                val payload = Set_output(box.id, "outputarea", "ai-chat", next)
                _send(Message("set-output").payload(payload), document = escapedDocPath)
                return next
            }

            beginVisibleAiPanel(
                title = when (exec.kind) {
                    "general" -> "Starting AI conversation"
                    "chat" -> "Starting inline chat"
                    "cursor" -> "Starting completion"
                    "rewrite" -> "Starting rewrite"
                    "error" -> "Starting error fix"
                    else -> "Starting AI work"
                },
                userBlock = visibleUserBlockForKind()
            )

            // Append any caller-supplied instruction (e.g. from the error-suggestion "Ask AI4"
            // buttons) to the fixed per-kind directive so cursor/rewrite/error requests can be
            // shaped without a bespoke kind.
            fun withExtraPrompt(base: String): String {
                val extra = exec.extraPrompt.trim()
                return if (extra.isEmpty()) base else "$base\n\n$extra"
            }

            try {
                val state = if (exec.kind == "general") {
                    chatThreadFor(fileKey, exec.prompt, conversationKey, savedHistory)
                } else {
                    threadFor(fileKey, exec.prompt, conversationKey, savedHistory)
                }
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
                            documentGuides = documentGuides,
                            focus = focus,
                            fieldGraph = replyGraph,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            dynamicTools = documentAiTools,
                            onProgress = progressCallback
                        )
                    }

                    "general" -> {
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
                            documentGuides = documentGuides,
                            focus = focus,
                            fieldGraph = chatGraph,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            dynamicTools = documentAiTools,
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
                            documentGuides = documentGuides,
                            focus = focus,
                            fieldGraph = replyGraph,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            dynamicTools = documentAiTools,
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
                            documentGuides = documentGuides,
                            focus = focus,
                            fieldGraph = replyGraph,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            dynamicTools = documentAiTools,
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
                            documentGuides = documentGuides,
                            focus = focus,
                            fieldGraph = replyGraph,
                            workspaceSymbolSearch = workspaceSymbolSearch,
                            dynamicTools = documentAiTools,
                            onProgress = progressCallback
                        )
                    }

                    else -> {
                        chatResult = null
                        editResult = null
                    }
                }
            } catch (e: Exception) {
                val outcome = if (isAiTurnCancellation(e)) "cancelled" else "failed"
                // A failed tool round can leave a provider thread awaiting an unsent result.
                // Continue from saved text next time instead of reusing that incomplete chain.
                threadByKey.remove(conversationKey)
                chatThreadByKey.remove(conversationKey)
                if (livePrefix != null) {
                    sendFinalVisibleAiPanel(
                        synchronized(liveLock) { streamBuffer.toString() } +
                            "\n\n_AI ${exec.kind} $outcome: ${e.message ?: e::class.java.simpleName}_"
                    )
                }
                streamActive.set(false)
                inMain {
                    notify("AI ${exec.kind} $outcome: ${e.message ?: e::class.java.simpleName}", "AI4")
                }
                return@launch
            }

            (chatResult?.contextReport ?: editResult?.contextReport)?.let { lastContextByBox["$docPath\u0000${box.id}"] = it }
            inMain {
                if (chatResult != null) {
                    chatResult.modelIdentity?.let { turnModel = it }
                    chatThreadByKey[conversationKey] = chatResult.threadState
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
                            val previous = history()
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
                    saveHistory(next)
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
                    threadByKey[conversationKey] = editResult.threadState
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
            }.join()
        }.invokeOnCompletion {
            AiBoxConversation.running(box, false)
            activeBoxChats.remove(boxChatKey)
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
        invalidateBridgesAfterSettingsChange()
        val input = Json.decodeFromJsonElement<Chat_input>(message.payload!!)
        
        if (chatBridge == null) {
            val uiLog = NotifyLog()
            chatBridge = createIdeBridge(uiLog)
            chatHelpers = IdeChatHelpers(chatBridge!!, log = uiLog)
        }
        
        val chatSession = currentSessionInfoOrNull()
        val chatDocPath = chatSession?.filename.orEmpty()
        val documentGuides = chatSession?.let { DocumentGuideTools.forSession(it) }
        val fileKey = "chat:$chatDocPath"
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
                    agentMemory = agentMemory,
                    documentGuides = documentGuides,
                    dynamicTools = DocumentToolRegistry.toolsFor(chatDocPath),
                    // The document chat had no way to edit a box before: no EditFile, no graph tools.
                    fieldGraph = chatSession?.let { afield.ai.agent.FieldGraphTools.forTurn(it) }
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
