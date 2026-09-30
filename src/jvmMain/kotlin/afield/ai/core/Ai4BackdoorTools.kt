package afield.ai.core

import afield.Box
import afield.SessionInformation
import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import afield.OperationTranscript
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Shared AI4 registry for external agents; targets must be explicit in this context. */
internal object Ai4BackdoorTools {
    private val memoryByDocument = ConcurrentHashMap<String, AgentMemoryState>()

    /** The same registry used by in-Field chat; availability follows the wired providers. */
    val registry: ToolRegistry = ToolRegistry().also(::registerBuiltInAgentTools)

    /**
     * Build a [ToolExecutionContext] wiring the shared document and runtime providers for [sessionInfo].
     *
     * [focusBox] (a uid or a unique name) plays the part of the box whose chat is open, so a tool that
     * scopes itself to the focused box answers a backdoor call exactly as it answers the chat panel.
     * The focused property is the box's `default_property`, else `code`.
     */
    fun buildContext(sessionInfo: SessionInformation, focusBox: String? = null): ToolExecutionContext {
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
            resolveBackdoorFileRef(sessionInfo, docName, raw, defaultProperty)
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
            // Resolve target box tokens against the live document (uid or name). A duplicated name is
            // refused rather than resolved — see the refResolver above; replaying a macro over the
            // wrong box is the one failure here that edits a document.
            val targets = req.targetBoxes.mapNotNull { raw ->
                val token = raw.trim()
                if (token.isEmpty()) null
                else if (sessionInfo.boxes.containsKey(token)) token
                else uniqueBoxOrRefuse(token, sessionInfo.boxes.values.filter { it.name == token })?.id
                    ?: token
            }
            macroRunToolResult(docPath, req, targets)
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

        val runtimeTools = Ai4RuntimeToolProviders(sessionInfo, refResolver, boxByIdOrNull)
        return ToolExecutionContext(
            listDefinitions = {
                ListDefinitionsResult(sessionInfo.boxes.values.sortedBy { it.id }.flatMap { box ->
                    val ref = fileRefFor(docName, box.id, "code")
                    box.ki.context.vars.values.map { DefinitionInfo(it.name, "variable", ref, "Kotlin") } +
                        box.ki.context.functions.values.map { DefinitionInfo(it.toString(true), "function", ref, "Kotlin") }
                })
            },
            listFileContents = {
                ListContentsResult(contents = sessionInfo.boxes.values.sortedBy { it.id }.flatMap { box ->
                    Box.ioSeparateProperties.keys.sorted().mapNotNull { property ->
                        (box.getPropertyRaw(property) as? String)?.let { contents ->
                            ListContentEntry(fileRefFor(docName, box.id, property), property,
                                if (property == "code") "Kotlin" else "text", contents,
                                box.id, box.name, "document")
                        }
                    }
                })
            },
            reloadShaders = runtimeTools.reloadShaders,
            captureRenderWindow = runtimeTools.captureRenderWindow,
            listKotlinContextVariables = runtimeTools.listKotlinContextVariables,
            codeUseQuery = runtimeTools.codeUseQuery,
            workspaceSymbolSearch = runtimeTools.workspaceSymbolSearch,
            listMacros = listMacros,
            readMacro = readMacro,
            writeMacro = writeMacro,
            runMacro = runMacro,
            semanticRetrievalSearch = semanticRetrievalSearch,
            semanticRetrievalStatus = semanticRetrievalStatus,
            semanticRetrievalRebuild = semanticRetrievalRebuild,
            agentMemory = agentMemory,
            documentGuides = DocumentGuideTools.forSession(sessionInfo),
            compileKotlin = codeTools.compileKotlin,
            evaluateKotlinExpression = codeTools.evaluateKotlinExpression,
            fieldGraph = afield.ai.agent.FieldGraphTools(sessionInfo),
            focus = focusBox?.let { token ->
                val box = boxByIdOrNull(token)
                    ?: uniqueBoxOrRefuse(token, sessionInfo.boxes.values.filter { it.name == token })
                    ?: throw IllegalArgumentException("focusBox '$token' names no box in ${sessionInfo.filename}")
                ToolFocus(docPath, box, (box.getPropertyRaw("default_property") as? String)?.takeIf { it.isNotBlank() } ?: "code")
            },
            // Document-authored tools (box aiTool DSL), visible to external MCP agents too.
            dynamicTools = DocumentToolRegistry.toolsFor(docPath)
        )
    }
}

/**
 * How the backdoor/task tool context reads a box reference: a uid, a unique name, or an afield:// ref.
 * Top level so it can be tested without a Kotlin compiler.
 */
internal fun resolveBackdoorFileRef(sessionInfo: SessionInformation, docName: String, raw: String, defaultProperty: String): FileRef {
    // A box of *this* document named by uid or unique name, or null. Ambiguity throws (see below).
    val boxHere: (String) -> Box? = { token ->
        sessionInfo.boxes[token]
            ?: uniqueBoxOrRefuse(token, sessionInfo.boxes.values.filter { it.name == token && token.isNotBlank() })
    }
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("afield://") -> {
            val parts = trimmed.removePrefix("afield://").split("/", limit = 3)
            // This context reaches exactly one document, so a ref whose box segment names one
            // of its boxes means that box, whatever the document segment says. The live model
            // guessed the document segment in every eval run of 2026-09-29/30 — the box uid
            // (`afield://<uid>/code`), the display name (`afield://agent-task/<uid>/code`),
            // `scratch` — and spent a round or two per task finding the real one (the scratch
            // directory's uuid) through ListContents. A box uid is unique across documents, so
            // this cannot pick another document's box.
            when (parts.size) {
                3 -> boxHere(parts[1])?.let { FileRef(docName, it.id, parts[2]) }
                    ?: FileRef(parts[0], parts[1], parts[2])
                2 -> boxHere(parts[0])?.let { FileRef(docName, it.id, parts[1]) }
                    ?: FileRef(parts[0], parts[1], defaultProperty)
                else -> boxHere(parts[0])?.let { FileRef(docName, it.id, defaultProperty) }
                    ?: FileRef(docName, trimmed, defaultProperty)
            }
        }
        sessionInfo.boxes.containsKey(trimmed) -> FileRef(docName, trimmed, defaultProperty)
        else -> {
            // A duplicated name is refused, not resolved: uniqueBoxOrRefuse throws
            // AmbiguousBoxNameException, which agent-ai-tool-call turns into ok=false with the
            // count and "reference one by its uid instead" — the same refusal
            // tools/field_mcp.py's _resolve_box has always given.
            //
            // A name that matches *nothing* still falls through to the literal-uid branch
            // below, deliberately and unchanged. That branch is not a guess: it is how an
            // honest uid reaches its box when the uid is not in `sessionInfo.boxes` (the
            // document root), and when the token is simply a typo the provider's own
            // "Unknown box '<token>'" is a better message than anything this resolver could
            // give. Only ambiguity errors here, because only ambiguity has no answer.
            val byName = uniqueBoxOrRefuse(
                trimmed,
                sessionInfo.boxes.values.filter { it.name == trimmed }
            )
            if (byName != null) FileRef(docName, byName.id, defaultProperty)
            else FileRef(docName, trimmed, defaultProperty)
        }
    }
}
