package afield.ai.core

import afield.Box
import afield.SessionInformation
import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import afield.OperationTranscript
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider-neutral bridge that exposes a **Tier-1 subset** of the ai4 agent tool
 * vocabulary (the same `ToolRegistry` used by the OpenAI harness in
 * [OpenAiIdeCodexBridge]) to the agent backdoor, and thence to MCP via
 * `tools/field_mcp.py`.
 *
 * "Tier-1" = the providers that depend only on a [SessionInformation]'s document
 * path or on backend singletons, so they can be wired here **without touching the
 * production chat handler `installAi4()`**:
 *   - Macro*        → [OperationTranscript]
 *   - SemanticRetrieval* → [SemanticRetrievalService]
 *   - Scratchpad / Checklist → per-document [AgentMemoryState]
 *
 * The box-scoped ("Tier-2") providers — CompileKotlin, EvaluateKotlin, ReloadShaders,
 * ListDefinitions, EditFile, CodeUse*, workspaceSymbolSearch — are entangled with
 * ~1000 lines of request-scoped helpers inside `installAi4()` and are intentionally
 * left unwired here. Because [ToolRegistry] gates every tool on its provider being
 * present, an unwired provider simply means the tool does not appear over MCP;
 * nothing errors. They come online when `installAi4()` is refactored to expose a
 * shared context factory (the staged Tier-2 work).
 *
 * This registry deliberately mirrors the relevant registrations in
 * [OpenAiIdeCodexBridge.registerBuiltIns]; keep the two in sync until the shared
 * factory dedups them.
 */
internal object Ai4BackdoorTools {
    private val memoryByDocument = ConcurrentHashMap<String, AgentMemoryState>()

    /** A registry populated with the Tier-1 tools (passthroughs to context providers). */
    val registry: ToolRegistry = ToolRegistry().apply {
        register(MacroListRequest::class.java, { it.listMacros != null }) { ctx, req ->
            ctx.listMacros!!.invoke(req)
        }
        register(MacroReadRequest::class.java, { it.readMacro != null }) { ctx, req ->
            ctx.readMacro!!.invoke(req)
        }
        register(MacroWriteRequest::class.java, { it.writeMacro != null }) { ctx, req ->
            ctx.writeMacro!!.invoke(req)
        }
        register(MacroRunRequest::class.java, { it.runMacro != null }) { ctx, req ->
            ctx.runMacro!!.invoke(req)
        }
        register(SemanticRetrievalSearchRequest::class.java, { it.semanticRetrievalSearch != null }) { ctx, req ->
            ctx.semanticRetrievalSearch!!.invoke(req)
        }
        register(SemanticRetrievalStatusRequest::class.java, { it.semanticRetrievalStatus != null }) { ctx, _ ->
            ctx.semanticRetrievalStatus!!.invoke()
        }
        register(SemanticRetrievalRebuildRequest::class.java, { it.semanticRetrievalRebuild != null }) { ctx, _ ->
            ctx.semanticRetrievalRebuild!!.invoke()
        }
        register(ScratchpadReadRequest::class.java, { it.agentMemory != null }) { ctx, _ ->
            ctx.agentMemory!!.readScratchpad()
        }
        register(ScratchpadUpdateRequest::class.java, { it.agentMemory != null }) { ctx, req ->
            ctx.agentMemory!!.updateScratchpad(req)
        }
        register(ChecklistReadRequest::class.java, { it.agentMemory != null }) { ctx, _ ->
            ctx.agentMemory!!.readChecklist()
        }
        register(ChecklistUpdateRequest::class.java, { it.agentMemory != null }) { ctx, req ->
            ctx.agentMemory!!.updateChecklist(req)
        }
        // Tier-2 box-scoped code tools (shared with the OpenAI harness via Ai4CodeToolProviders).
        register(CompileKotlinRequest::class.java, { it.compileKotlin != null }) { ctx, req ->
            ctx.compileKotlin!!.invoke(req)
        }
        register(EvaluateKotlinExpressionRequest::class.java, { it.evaluateKotlinExpression != null }) { ctx, req ->
            ctx.evaluateKotlinExpression!!.invoke(req)
        }
    }

    /** Build a [ToolExecutionContext] wiring the Tier-1 providers for [sessionInfo]. */
    fun buildContext(sessionInfo: SessionInformation): ToolExecutionContext {
        val docPath = sessionInfo.filename
        val docName = File(docPath).name
        val agentMemory = memoryByDocument.computeIfAbsent(docPath) { AgentMemoryState() }

        // Box-scoped Kotlin tools, sharing Ai4CodeToolProviders with the OpenAI harness.
        // Explicit-target resolver: a box is named by uid, name, or an afield:// fileRef;
        // there is no "current box" fallback (that only exists in the chat handler).
        val boxByIdOrNull: (String) -> Box? = { uid ->
            if (uid == sessionInfo.root.id) sessionInfo.root else sessionInfo.boxes[uid]
        }
        val refResolver: (String, String) -> FileRef = { raw, defaultProperty ->
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("afield://") -> {
                    val parts = trimmed.removePrefix("afield://").split("/", limit = 3)
                    when (parts.size) {
                        3 -> FileRef(parts[0], parts[1], parts[2])
                        2 -> FileRef(parts[0], parts[1], defaultProperty)
                        else -> FileRef(docName, trimmed, defaultProperty)
                    }
                }
                sessionInfo.boxes.containsKey(trimmed) -> FileRef(docName, trimmed, defaultProperty)
                else -> {
                    val byName = sessionInfo.boxes.values.firstOrNull { it.name == trimmed }
                    if (byName != null) FileRef(docName, byName.id, defaultProperty)
                    else FileRef(docName, trimmed, defaultProperty)
                }
            }
        }
        val codeTools = Ai4CodeToolProviders(
            docName = docName,
            refResolver = refResolver,
            boxByIdOrNull = boxByIdOrNull,
            stagedOrCurrentSource = { target, property, explicit ->
                explicit ?: (target.getPropertyRaw(property) as? String)?.takeIf { it.isNotBlank() }
            }
        )

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
            // Resolve target box tokens against the live document (uid or name).
            val targets = req.targetBoxes.mapNotNull { raw ->
                val token = raw.trim()
                if (token.isEmpty()) null
                else if (sessionInfo.boxes.containsKey(token)) token
                else sessionInfo.boxes.values.firstOrNull { it.name == token }?.id ?: token
            }
            val result = OperationTranscript.runMacro(
                documentPath = docPath,
                name = req.name,
                targetBoxes = targets,
                direction = req.direction,
                strict = req.strict
            )
            MacroRunResult(
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

        return ToolExecutionContext(
            listMacros = listMacros,
            readMacro = readMacro,
            writeMacro = writeMacro,
            runMacro = runMacro,
            semanticRetrievalSearch = semanticRetrievalSearch,
            semanticRetrievalStatus = semanticRetrievalStatus,
            semanticRetrievalRebuild = semanticRetrievalRebuild,
            agentMemory = agentMemory,
            compileKotlin = codeTools.compileKotlin,
            evaluateKotlinExpression = codeTools.evaluateKotlinExpression
        )
    }
}
